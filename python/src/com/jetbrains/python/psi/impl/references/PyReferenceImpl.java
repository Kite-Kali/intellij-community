package com.jetbrains.python.psi.impl.references;

import com.google.common.collect.Lists;
import com.intellij.codeInsight.lookup.LookupElement;
import com.intellij.codeInsight.lookup.LookupElementBuilder;
import com.intellij.lang.ASTNode;
import com.intellij.lang.annotation.HighlightSeverity;
import com.intellij.openapi.util.Comparing;
import com.intellij.openapi.util.Pair;
import com.intellij.openapi.util.TextRange;
import com.intellij.psi.*;
import com.intellij.psi.impl.source.resolve.ResolveCache;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.util.IncorrectOperationException;
import com.intellij.util.PlatformIcons;
import com.intellij.util.ProcessingContext;
import com.intellij.util.containers.SortedList;
import com.jetbrains.cython.CythonLanguageDialect;
import com.jetbrains.cython.CythonResolveUtil;
import com.jetbrains.cython.psi.*;
import com.jetbrains.django.util.PythonDataflowUtil;
import com.jetbrains.python.PyNames;
import com.jetbrains.python.codeInsight.controlflow.ControlFlowCache;
import com.jetbrains.python.codeInsight.controlflow.ReadWriteInstruction;
import com.jetbrains.python.codeInsight.controlflow.ScopeOwner;
import com.jetbrains.python.codeInsight.dataflow.scope.Scope;
import com.jetbrains.python.codeInsight.dataflow.scope.ScopeUtil;
import com.jetbrains.python.psi.*;
import com.jetbrains.python.psi.impl.*;
import com.jetbrains.python.psi.resolve.*;
import com.jetbrains.python.psi.types.PyModuleType;
import com.jetbrains.python.psi.types.PyType;
import com.jetbrains.python.psi.types.TypeEvalContext;
import com.jetbrains.python.refactoring.PyDefUseUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * @author yole
 */
public class PyReferenceImpl implements PsiReferenceEx, PsiPolyVariantReference {
  protected final PyQualifiedExpression myElement;
  protected final PyResolveContext myContext;

  private static class ScopeDeclaration {
    @Nullable private NameDefiner myNameDefiner;
    @Nullable private PsiElement myElement;

    public ScopeDeclaration(@Nullable NameDefiner nameDefiner, @Nullable PsiElement element) {
      myNameDefiner = nameDefiner;
      myElement = element;
    }

    @Nullable
    public PsiElement getElement() {
      return myElement;
    }

    @Nullable
    public NameDefiner getNameDefiner() {
      return myNameDefiner;
    }
  }

  public PyReferenceImpl(PyQualifiedExpression element, @NotNull PyResolveContext context) {
    myElement = element;
    myContext = context;
  }

  public TextRange getRangeInElement() {
    final ASTNode nameElement = myElement.getNameElement();
    final TextRange range = nameElement != null ? nameElement.getTextRange() : myElement.getNode().getTextRange();
    return range.shiftRight(-myElement.getNode().getStartOffset());
  }

  public PsiElement getElement() {
    return myElement;
  }

  /**
   * Resolves reference to the most obvious point.
   * Imported module names: to module file (or directory for a qualifier).
   * Other identifiers: to most recent definition before this reference.
   * This implementation is cached.
   *
   * @see #resolveInner().
   */
  @Nullable
  public PsiElement resolve() {
    final ResolveResult[] results = multiResolve(false);
    return results.length >= 1 && !(results[0] instanceof ImplicitResolveResult) ? results[0].getElement() : null;
  }

  // it is *not* final so that it can be changed in debug time. if set to false, caching is off
  private static boolean USE_CACHE = true;

  /**
   * Resolves reference to possible referred elements.
   * First element is always what resolve() would return.
   * Imported module names: to module file, or {directory, '__init__.py}' for a qualifier.
   * todo Local identifiers: a list of definitions in the most recent compound statement
   * (e.g. <code>if X: a = 1; else: a = 2</code> has two definitions of <code>a</code>.).
   * todo Identifiers not found locally: similar definitions in imported files and builtins.
   *
   * @see com.intellij.psi.PsiPolyVariantReference#multiResolve(boolean)
   */
  @NotNull
  public ResolveResult[] multiResolve(final boolean incompleteCode) {
    if (USE_CACHE) {
      final ResolveCache cache = ResolveCache.getInstance(getElement().getProject());
      return cache.resolveWithCaching(this, CachingResolver.INSTANCE, false, incompleteCode);
    }
    else {
      return multiResolveInner();
    }
  }

  // sorts and modifies results of resolveInner

  private ResolveResult[] multiResolveInner() {
    final String referencedName = myElement.getReferencedName();
    if (referencedName == null) return ResolveResult.EMPTY_ARRAY;

    List<RatedResolveResult> targets = resolveInner();
    if (targets.size() == 0) return ResolveResult.EMPTY_ARRAY;

    // change class results to constructor results if there are any
    if (myElement.getParent() instanceof PyCallExpression) { // we're a call
      ListIterator<RatedResolveResult> it = targets.listIterator();
      while (it.hasNext()) {
        final RatedResolveResult rrr = it.next();
        final PsiElement elt = rrr.getElement();
        if (elt instanceof PyClass) {
          PyClass cls = (PyClass)elt;
          PyFunction init = cls.findMethodByName(PyNames.INIT, false);
          if (init != null) {
            // replace
            it.set(rrr.replace(init));
          }
          else { // init not found; maybe it's ancestor's
            for (PyClass ancestor : cls.iterateAncestorClasses()) {
              init = ancestor.findMethodByName(PyNames.INIT, false);
              if (init != null) {
                // add to resuls as low priority
                it.add(new RatedResolveResult(RatedResolveResult.RATE_LOW, init));
                break;
              }
            }
          }
        }
      }
    }

    // put everything in a sorting container
    List<RatedResolveResult> ret = new SortedList<RatedResolveResult>(new Comparator<RatedResolveResult>() {
      public int compare(final RatedResolveResult one, final RatedResolveResult another) {
        return another.getRate() - one.getRate();
      }
    });
    ret.addAll(targets);

    return ret.toArray(new ResolveResult[ret.size()]);
  }

  @Nullable
  private static ScopeDeclaration getScopeDeclaration(@NotNull String name, @NotNull Scope scope) {
    PsiElement element = scope.getNamedElement(name);
    if (element != null) {
      return new ScopeDeclaration(null, element);
    }
    element = scope.getImplicitElement(name);
    if (element != null) {
      return new ScopeDeclaration(null, element);
    }
    for (NameDefiner definer : scope.getNameDefiners()) {
      element = definer.getElementNamed(name);
      if (element != null) {
        return new ScopeDeclaration(definer, element);
      }
      else if (definer instanceof PyImportElement) {
        final PyImportElement importElement = (PyImportElement)definer;
        final PyQualifiedName importedQName = importElement.getImportedQName();

        // TODO: These checks are duplicates of checks inside the ResolveProcessor. We need to refactor them

        // http://stackoverflow.com/questions/6048786/from-module-import-in-init-py-makes-module-name-visible
        if (importedQName != null && importedQName.getComponentCount() > 1 && name.equals(importedQName.getLastComponent()) &&
            PyNames.INIT_DOT_PY.equals(importElement.getContainingFile().getName())) {
          final PsiElement packageElement = ResolveImportUtil.resolveImportElement(importElement, importedQName.removeLastComponent());
          if (PyUtil.turnDirIntoInit(packageElement) == importElement.getContainingFile()) {
            element = PyUtil.turnDirIntoInit(ResolveImportUtil.resolveImportElement(importElement));
            return new ScopeDeclaration(definer, element);
          }
        }
        final PyFromImportStatement fromImportStatement = PsiTreeUtil.getParentOfType(importElement, PyFromImportStatement.class);
        if (fromImportStatement != null && PyNames.INIT_DOT_PY.equals(importElement.getContainingFile().getName())) {
          final PyQualifiedName importSourceQName = fromImportStatement.getImportSourceQName();
          if (importSourceQName != null && importSourceQName.endsWith(name)) {
            final PsiElement source = PyUtil.turnInitIntoDir(fromImportStatement.resolveImportSource());
            if (source != null && source.getParent() == importElement.getContainingFile().getContainingDirectory()) {
              return new ScopeDeclaration(definer, source);
            }
          }
        }

        // name is resolved to unresolved import (PY-956)
        String definedName = importElement.getAsName();
        if (definedName == null) {
          if (importedQName != null && importedQName.getComponentCount() == 1) {
            definedName = importedQName.getComponents().get(0);
          }
        }
        if (name.equals(definedName)) {
          return new ScopeDeclaration(definer, null);
        }
      }
    }
    return null;
  }

  /**
   * Does actual resolution of resolve().
   *
   * @return resolution result.
   * @see #resolve()
   */
  @NotNull
  protected List<RatedResolveResult> resolveInner() {
    ResolveResultList ret = new ResolveResultList();

    final String referencedName = myElement.getReferencedName();
    if (referencedName == null) return ret;

    final PsiElement parent = myElement.getParent();
    final boolean isGlobalOrNonlocal = parent instanceof PyGlobalStatement || parent instanceof PyNonlocalStatement;
    if (myElement instanceof PyTargetExpression && !isGlobalOrNonlocal) {
      addResolvedElement(ret, null, myElement);
      return ret;
    }

    // Use real context here to enable correct completion and resolve in case of PyExpressionCodeFragment!!!
    final PsiElement realContext = PyPsiUtils.getRealContext(myElement);
    final PsiElement roof = findResolveRoof(referencedName, realContext);
    final ScopeOwner originalOwner = ScopeUtil.getResolveScopeOwner(realContext);

    ScopeOwner owner = originalOwner;
    if (isGlobalOrNonlocal) {
      final ScopeOwner outerScopeOwner = ScopeUtil.getScopeOwner(owner);
      if (outerScopeOwner != null) {
        owner = outerScopeOwner;
      }
    }
    while (owner != null) {
      if (!(owner instanceof PyClass) || owner == originalOwner) {
        final Scope scope = ControlFlowCache.getScope(owner);
        final ScopeDeclaration scopeDeclaration = getScopeDeclaration(referencedName, scope);
        if (scopeDeclaration != null) {
          NameDefiner definer = scopeDeclaration.getNameDefiner();
          PsiElement element = scopeDeclaration.getElement();
          if (owner == originalOwner && definer == null) {
            final List<ReadWriteInstruction> defs = PyDefUseUtil.getLatestDefs(owner, referencedName, myElement, false);
            if (!defs.isEmpty()) {
              for (ReadWriteInstruction def : defs) {
                element = def.getElement();
                // TODO: This check may slow down resolving, but it is the current solution to the compehension scopes problem
                final PyComprehensionElement elementComprh = PsiTreeUtil.getParentOfType(element, PyComprehensionElement.class);
                if (elementComprh != null && elementComprh != PsiTreeUtil.getParentOfType(myElement, PyComprehensionElement.class)) {
                  continue;
                }
                if (element instanceof NameDefiner && !(element instanceof PsiNamedElement)) {
                  definer = (NameDefiner)element;
                  element = definer.getElementNamed(referencedName);
                }
                addResolvedElement(ret, definer, element);
              }
              return ret;
            }
            else if (!isCythonLevel(myElement)) {
              break;
            }
          }
          addResolvedElement(ret, definer, element);
          return ret;
        }
      }
      if (owner == roof) {
        break;
      }
      owner = ScopeUtil.getScopeOwner(owner);
    }

    PyBuiltinCache builtinsCache = PyBuiltinCache.getInstance(realContext);
    // ...as a part of current module
    PyType otype = builtinsCache.getObjectType(); // "object" as a closest kin to "module"
    if (otype != null) {
      ret.addAll(otype.resolveMember(myElement.getName(), null, AccessDirection.READ, myContext));
    }
    PsiElement uexpr = null;
    // ...as a builtin symbol
    PyFile bfile = builtinsCache.getBuiltinsFile();
    if (bfile != null) {
      uexpr = bfile.getElementNamed(referencedName);
      if (uexpr == null && "__builtins__".equals(referencedName)) {
        uexpr = bfile; // resolve __builtins__ reference
      }
    }
    if (uexpr == null && CythonLanguageDialect.isInsideCythonFile(realContext)) {
      final CythonFile implicit = CythonResolveUtil.findImplicitDefinitionFile(realContext);
      if (implicit != null) {
        uexpr = implicit.getElementNamed(referencedName);
      }
      if (originalOwner instanceof CythonFile) {
        final PsiElement resolved = ((CythonFile)originalOwner).getElementNamed(referencedName);
        if ((resolved instanceof CythonFunction && ((CythonFunction)resolved).isCythonLevel()) ||
            resolved instanceof CythonFile) {
          ret.poke(resolved, getRate(resolved));
        }
      }
    }
    if (uexpr != null) {
      ret.add(new ImportedResolveResult(uexpr, getRate(uexpr), Collections.<PsiElement>emptyList()));
    }

    return ret;
  }

  private static boolean isCythonLevel(@Nullable PsiElement element) {
    return PsiTreeUtil.getParentOfType(element, CythonNamedElement.class) != null;
  }

  private static void addResolvedElement(@NotNull ResolveResultList ret, @Nullable NameDefiner definer, @Nullable PsiElement element) {
    if (definer != null) {
      if (definer instanceof PyImportElement || definer instanceof PyStarImportElement || definer instanceof PyImportedModule) {
        ret.add(new ImportedResolveResult(element, getRate(element), Collections.<PsiElement>singletonList(definer)));
      }
      else {
        ret.poke(element, getRate(element));
      }
      // TODO this kind of resolve contract is quite stupid
      if (element != null) {
        ret.poke(definer, RatedResolveResult.RATE_LOW);
      }
    }
    else {
      ret.poke(element, getRate(element));
    }
  }

  private PsiElement findResolveRoof(String referencedName, PsiElement realContext) {
    if (PyUtil.isClassPrivateName(referencedName)) {
      // a class-private name; limited by either class or this file
      PsiElement one = myElement;
      do {
        one = PyUtil.getConcealingParent(one);
      }
      while (one instanceof PyFunction);
      if (one instanceof PyClass) {
        PyArgumentList superClassExpressionList = ((PyClass)one).getSuperClassExpressionList();
        if (superClassExpressionList == null || !PsiTreeUtil.isAncestor(superClassExpressionList, myElement, false)) {
          return one;
        }
      }
    }

    if (myElement instanceof PyTargetExpression) {
      final ScopeOwner scopeOwner = PsiTreeUtil.getParentOfType(myElement, ScopeOwner.class);
      final Scope scope;
      if (scopeOwner != null) {
        scope = ControlFlowCache.getScope(scopeOwner);
        final String name = myElement.getName();
        if (scope.isNonlocal(name)) {
          final ScopeOwner nonlocalOwner = ScopeUtil.getDeclarationScopeOwner(myElement, referencedName);
          if (nonlocalOwner != null && !(nonlocalOwner instanceof PyFile)) {
            return nonlocalOwner;
          }
        }
        if (!scope.isGlobal(name)) {
          return scopeOwner;
        }
      }
    }
    return realContext.getContainingFile();
  }

  private boolean isSuperClassExpression(PyClass cls) {
    if (myElement.getContainingFile() != cls.getContainingFile()) {  // quick check to avoid unnecessary tree loading
      return false;
    }
    for (PyExpression base_expr : cls.getSuperClassExpressions()) {
      if (base_expr == this) {
        return true;
      }
    }
    return false;
  }

  // NOTE: very crude

  private static int getRate(PsiElement elt) {
    int rate;
    if (CythonLanguageDialect.isInsideCythonFile(elt) && elt instanceof CythonIncludeStatement) {
      rate = RatedResolveResult.RATE_LOW;
    }
    else if (elt instanceof PyImportElement || elt instanceof PyStarImportElement) {
      rate = RatedResolveResult.RATE_LOW;
    }
    else if (elt instanceof PyFile) {
      rate = RatedResolveResult.RATE_HIGH;
    }
    else {
      rate = RatedResolveResult.RATE_NORMAL;
    }
    return rate;
  }

  @NotNull
  public String getCanonicalText() {
    return getRangeInElement().substring(getElement().getText());
  }

  public PsiElement handleElementRename(String newElementName) throws IncorrectOperationException {
    ASTNode nameElement = myElement.getNameElement();
    if (newElementName.endsWith(PyNames.DOT_PY)) {
      newElementName = newElementName.substring(0, newElementName.length() - PyNames.DOT_PY.length());
    }
    if (nameElement != null && PyNames.isIdentifier(newElementName)) {
      final ASTNode newNameElement = PyElementGenerator.getInstance(myElement.getProject()).createNameIdentifier(newElementName);
      myElement.getNode().replaceChild(nameElement, newNameElement);
    }
    return myElement;
  }

  public PsiElement bindToElement(@NotNull PsiElement element) throws IncorrectOperationException {
    return null;
  }

  private static PsiElement transitiveResolve(PsiElement element) {
    PsiElement prev = null;
    while (element != prev) {
      prev = element;
      PsiReference ref = element.getReference();
      if (ref != null) {
        PsiElement e = ref.resolve();
        if (e != null) {
          element = e;
        }
      }
    }
    return element;
  }

  private static boolean isGlobal(PsiElement anchor, String name) {
    final ScopeOwner owner = ScopeUtil.getDeclarationScopeOwner(anchor, name);
    if (owner != null) {
      return ControlFlowCache.getScope(owner).isGlobal(name);
    }
    return false;
  }

   public boolean isReferenceTo(PsiElement element) {
    if (element instanceof PsiFileSystemItem) {
      // may be import via alias, so don't check if names match, do simple resolve check instead
      PsiElement resolveResult = resolve();
      if (resolveResult instanceof PyImportedModule) {
        resolveResult = resolveResult.getNavigationElement();
      }
      if (element instanceof PsiDirectory && resolveResult instanceof PyFile &&
          PyNames.INIT_DOT_PY.equals(((PyFile)resolveResult).getName()) && ((PyFile)resolveResult).getContainingDirectory() == element) {
        return true;
      }
      return resolveResult == element;
    }
    if (element instanceof PsiNamedElement) {
      final String elementName = ((PsiNamedElement)element).getName();
      if ((Comparing.equal(myElement.getReferencedName(), elementName) || PyNames.INIT.equals(elementName)) && !haveQualifiers(element)) {
        // Global elements may in fact be resolved to their outer declarations
        if (isGlobal(element, elementName)) {
          element = transitiveResolve(element);
        }
        final ScopeOwner ourScopeOwner = ScopeUtil.getScopeOwner(getElement());
        final ScopeOwner theirScopeOwner = ScopeUtil.getScopeOwner(element);
        // TODO: Cython-dependent code without CythonLanguageDialect.isInsideCythonFile() check
        if (element instanceof PyParameter || element instanceof PyTargetExpression || element instanceof CythonVariable) {
          // Check if the reference is in the same or inner scope of the element scope, not shadowed by an intermediate declaration
          if (resolvesToSameLocal(element, elementName, ourScopeOwner, theirScopeOwner)) {
            return true;
          }
        }

        final PsiElement resolveResult = (isGlobal(getElement(), elementName)) ? transitiveResolve(getElement()) : resolve();
        if (resolveResult == element) {
          return true;
        }

        if (!haveQualifiers(element) && ourScopeOwner != null && theirScopeOwner != null) {
          if (resolvesToSameGlobal(element, elementName, ourScopeOwner, theirScopeOwner, resolveResult)) return true;
        }

        if (resolvesToWrapper(element, resolveResult)) {
          return true;
        }

        return false; // TODO: handle multi-resolve
      }
    }
    return false;
  }

  private boolean resolvesToSameLocal(PsiElement element, String elementName, ScopeOwner ourScopeOwner, ScopeOwner theirScopeOwner) {
    PsiElement ourContainer = PsiTreeUtil.getParentOfType(getElement(), ScopeOwner.class, PyComprehensionElement.class);
    PsiElement theirContainer = PsiTreeUtil.getParentOfType(element, ScopeOwner.class, PyComprehensionElement.class);
    if (ourContainer != null) {
      if (ourContainer == theirContainer) {
        return true;
      }
      if (PsiTreeUtil.isAncestor(theirContainer, ourContainer, true)) {
        if (ourScopeOwner != theirScopeOwner) {
          boolean shadowsName = false;
          ScopeOwner owner = ourScopeOwner;
          while(owner != theirScopeOwner && owner != null) {
            if (ControlFlowCache.getScope(owner).containsDeclaration(elementName)) {
              shadowsName = true;
              break;
            }
            owner = ScopeUtil.getScopeOwner(owner);
          }
          if (!shadowsName) {
            return true;
          }
        }
      }
    }
    return false;
  }

  private boolean resolvesToSameGlobal(PsiElement element, String elementName, ScopeOwner ourScopeOwner, ScopeOwner theirScopeOwner,
                                       PsiElement resolveResult) {
    // Handle situations when there is no top-level declaration for globals and transitive resolve doesn't help
    final boolean ourIsGlobal = ControlFlowCache.getScope(ourScopeOwner).isGlobal(elementName);
    final boolean theirIsGlobal = ControlFlowCache.getScope(theirScopeOwner).isGlobal(elementName);
    final PsiFile ourFile = getElement().getContainingFile();
    final PsiFile theirFile = element.getContainingFile();

    if (ourIsGlobal && theirIsGlobal && ourFile == theirFile) {
      return true;
    }
    if (theirIsGlobal && ScopeUtil.getScopeOwner(resolveResult) == ourFile) {
      return true;
    }
    return false;
  }

  protected boolean resolvesToWrapper(PsiElement element, PsiElement resolveResult) {
    if (element instanceof PyFunction && ((PyFunction) element).getContainingClass() != null && resolveResult instanceof PyTargetExpression) {
      final PyExpression assignedValue = ((PyTargetExpression)resolveResult).findAssignedValue();
      if (assignedValue instanceof PyCallExpression) {
        final PyCallExpression call = (PyCallExpression)assignedValue;
        final Pair<String,PyFunction> functionPair = PyCallExpressionHelper.interpretAsModifierWrappingCall(call, myElement);
        if (functionPair != null && functionPair.second == element) {
          return true;
        }
      }
    }
    return false;
  }

  private boolean haveQualifiers(PsiElement element) {
    if (myElement.getQualifier() != null) {
      return true;
    }
    if (element instanceof PyQualifiedExpression && ((PyQualifiedExpression)element).getQualifier() != null) {
      return true;
    }
    return false;
  }

  @NotNull
  public Object[] getVariants() {
    final List<LookupElement> ret = Lists.newArrayList();

    // Use real context here to enable correct completion and resolve in case of PyExpressionCodeFragment!!!
    final PsiElement realContext = PyPsiUtils.getRealContext(myElement);

    // include our own names
    final int underscores = PyUtil.getInitialUnderscores(myElement.getName());
    final CompletionVariantsProcessor processor = new CompletionVariantsProcessor(myElement);
    PyResolveUtil.treeCrawlUp(processor, realContext); // names from here
    PyResolveUtil.scanOuterContext(processor, realContext); // possible names from around us at call time

    // in a call, include function's arg names
    PythonDataflowUtil.collectFunctionArgNames(myElement, ret);

    // include builtin names
    processor.setNotice("__builtin__");
    PyResolveUtil.treeCrawlUp(processor, true, PyBuiltinCache.getInstance(getElement()).getBuiltinsFile()); // names from __builtin__

    if (underscores >= 2) {
      // if we're a normal module, add module's attrs
      PsiFile f = realContext.getContainingFile();
      if (f instanceof PyFile) {
        for (String name : PyModuleType.getPossibleInstanceMembers()) {
          ret.add(LookupElementBuilder.create(name).setIcon(PlatformIcons.FIELD_ICON));
        }
      }
    }

    ret.addAll(processor.getResultList());
    return ret.toArray();
  }

  public boolean isSoft() {
    return false;
  }

  public HighlightSeverity getUnresolvedHighlightSeverity(TypeEvalContext context) {
    if (isBuiltInConstant()) return null;
    final PyExpression qualifier = myElement.getQualifier();
    if (qualifier == null) {
      return HighlightSeverity.ERROR;
    }
    if (context.getType(qualifier) != null) {
      return HighlightSeverity.WARNING;
    }
    return null;
  }

  private boolean isBuiltInConstant() {
    // TODO: generalize
    String name = myElement.getReferencedName();
    return PyNames.NONE.equals(name) || "True".equals(name) || "False".equals(name);
  }

  @Nullable
  public String getUnresolvedDescription() {
    return null;
  }


  // our very own caching resolver

  private static class CachingResolver implements ResolveCache.PolyVariantResolver<PyReferenceImpl> {
    public static CachingResolver INSTANCE = new CachingResolver();
    private ThreadLocal<AtomicInteger> myNesting = new ThreadLocal<AtomicInteger>() {
      @Override
      protected AtomicInteger initialValue() {
        return new AtomicInteger();
      }
    };

    private static final int MAX_NESTING_LEVEL = 30;

    @Nullable
    public ResolveResult[] resolve(final PyReferenceImpl ref, final boolean incompleteCode) {
      if (myNesting.get().getAndIncrement() >= MAX_NESTING_LEVEL) {
        System.out.println("Stack overflow pending");
      }
      try {
        return ref.multiResolveInner();
      }
      finally {
        myNesting.get().getAndDecrement();
      }
    }
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (o == null || getClass() != o.getClass()) return false;

    PyReferenceImpl that = (PyReferenceImpl)o;

    if (!myElement.equals(that.myElement)) return false;
    if (!myContext.equals(that.myContext)) return false;

    return true;
  }

  @Override
  public int hashCode() {
    return myElement.hashCode();
  }

  protected static Object[] getTypeCompletionVariants(PyExpression pyExpression, PyType type) {
    ProcessingContext context = new ProcessingContext();
    context.put(PyType.CTX_NAMES, new HashSet<String>());
    return type.getCompletionVariants(pyExpression.getName(), pyExpression, context);
  }
}
