package org.robolectric.errorprone.bugpatterns;

import static com.google.errorprone.BugPattern.Category.ANDROID;
import static com.google.errorprone.BugPattern.SeverityLevel.SUGGESTION;
import static com.google.errorprone.matchers.Description.NO_MATCH;
import static com.google.errorprone.matchers.Matchers.argumentCount;
import static com.google.errorprone.matchers.Matchers.staticMethod;
import static com.google.errorprone.util.ASTHelpers.getSymbol;
import static com.google.errorprone.util.ASTHelpers.hasAnnotation;
import static org.robolectric.errorprone.bugpatterns.Helpers.isCastableTo;
import static org.robolectric.errorprone.bugpatterns.Helpers.isInShadowClass;

import com.google.auto.service.AutoService;
import com.google.errorprone.BugPattern;
import com.google.errorprone.BugPattern.ProvidesFix;
import com.google.errorprone.BugPattern.StandardTags;
import com.google.errorprone.VisitorState;
import com.google.errorprone.bugpatterns.BugChecker;
import com.google.errorprone.bugpatterns.BugChecker.ClassTreeMatcher;
import com.google.errorprone.fixes.Fix;
import com.google.errorprone.fixes.SuggestedFix;
import com.google.errorprone.fixes.SuggestedFix.Builder;
import com.google.errorprone.matchers.Description;
import com.google.errorprone.matchers.Matcher;
import com.google.errorprone.matchers.Matchers;
import com.google.errorprone.util.ASTHelpers;
import com.sun.source.tree.AssignmentTree;
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.ExpressionStatementTree;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.IdentifierTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.Tree.Kind;
import com.sun.source.tree.VariableTree;
import com.sun.source.util.TreePath;
import com.sun.source.util.TreePathScanner;
import com.sun.source.util.TreeScanner;
import com.sun.tools.javac.code.Attribute;
import com.sun.tools.javac.code.Attribute.Compound;
import com.sun.tools.javac.code.Symbol;
import com.sun.tools.javac.code.Symbol.ClassSymbol;
import com.sun.tools.javac.code.Symbol.MethodSymbol;
import com.sun.tools.javac.code.Symbol.TypeSymbol;
import com.sun.tools.javac.code.Type;
import com.sun.tools.javac.code.Type.ClassType;
import com.sun.tools.javac.code.Type.UnknownType;
import com.sun.tools.javac.code.Types;
import com.sun.tools.javac.tree.JCTree;
import com.sun.tools.javac.tree.JCTree.JCAssign;
import com.sun.tools.javac.tree.JCTree.JCExpression;
import com.sun.tools.javac.tree.JCTree.JCFieldAccess;
import com.sun.tools.javac.tree.JCTree.JCIdent;
import com.sun.tools.javac.tree.JCTree.JCMethodDecl;
import com.sun.tools.javac.tree.JCTree.JCMethodInvocation;
import com.sun.tools.javac.tree.JCTree.JCNewClass;
import com.sun.tools.javac.tree.JCTree.JCTypeCast;
import com.sun.tools.javac.tree.JCTree.JCVariableDecl;
import com.sun.tools.javac.tree.TreeMaker;
import com.sun.tools.javac.util.List;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import javax.lang.model.element.AnnotationValueVisitor;
import javax.lang.model.element.ElementKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.SimpleAnnotationValueVisitor6;
import org.robolectric.annotation.HiddenApi;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.errorprone.bugpatterns.Helpers.AnnotatedMethodMatcher;

/** @author christianw@google.com (Christian Williams) */
@AutoService(BugChecker.class)
@BugPattern(
    name = "ShadowUsageCheck",
    summary = "Robolectric shadows shouldn't be stored to variables or fields.",
    category = ANDROID,
    severity = SUGGESTION,
    documentSuppression = false,
    tags = StandardTags.REFACTORING,
    providesFix = ProvidesFix.REQUIRES_HUMAN_ATTENTION)
public final class ShadowUsageCheck extends BugChecker implements ClassTreeMatcher {

  /** Matches when the shadowOf method is used to obtain a shadow from an instrumented instance. */
  private static final Matcher<MethodInvocationTree> shadowStaticMatcher =
      Matchers.allOf(staticMethod(), new AnnotatedMethodMatcher(Implementation.class));

  /** Matches when the shadowOf method is used to obtain a shadow from an instrumented instance. */
  private static final Matcher<MethodInvocationTree> shadowOfMatcher =
      Matchers.allOf(
          staticMethod()
              .onClass(isCastableTo("org.robolectric.internal.ShadowProvider"))
              .named("shadowOf"),
          argumentCount(1));

  @Override
  public Description matchClass(ClassTree tree, VisitorState state) {
    if (isInShadowClass(state.getPath(), state)) {
      return NO_MATCH;
    }

    final RefactorContext refactorContext = new RefactorContext();

    new TreeScanner<Void, VisitorState>() {
      private boolean inShadowClass;

      @Override
      public Void visitClass(ClassTree classTree, VisitorState visitorState) {
        boolean priorInShadowClass = inShadowClass;
        inShadowClass = hasAnnotation(classTree, Implements.class, visitorState);
        try {
          return super.visitClass(classTree, visitorState);
        } finally {
          inShadowClass = priorInShadowClass;
        }
      }

      @Override
      public Void visitVariable(VariableTree node, VisitorState state) {
        if (getSymbol(node).getKind() == ElementKind.LOCAL_VARIABLE) {
          refactorContext.knownLocalVars.add(node.getName().toString());
        } else {
          refactorContext.knownFields.add(node.getName().toString());
        }
        return super.visitVariable(node, state);
      }

      @Override
      public Void visitMethod(MethodTree node, VisitorState state) {
        refactorContext.knownLocalVars.clear();
        return super.visitMethod(node, state);
      }

      @Override
      public Void visitMethodInvocation(MethodInvocationTree tree, VisitorState state) {
        VisitorState nowState = state.withPath(TreePath.getPath(state.getPath(), tree));

        if (!inShadowClass && shadowStaticMatcher.matches(tree, nowState)) {
          // Replace ShadowXxx.method() with Xxx.method() where possible...
          JCFieldAccess methodSelect = (JCFieldAccess) tree.getMethodSelect();
          ClassSymbol owner = (ClassSymbol) methodSelect.sym.owner;

          ClassType shadowedClass = determineShadowedClassName(owner, nowState);
          TypeSymbol shadowedType = shadowedClass.asElement();
          refactorContext
              .fixBuilder
              .replace(methodSelect.selected, shadowedType.getSimpleName().toString())
              .addImport(shadowedType.getQualifiedName().toString());
          // .removeImport(((JCIdent) methodSelect.selected).sym.toString());
        }

        if (!inShadowClass && shadowOfMatcher.matches(tree, nowState)) {
          matchedShadowOf(tree, nowState, refactorContext);
        }

        return super.visitMethodInvocation(tree, nowState);
      }
    }.scan(tree, state);

    for (Runnable runnable : refactorContext.possibleFixes.values()) {
      runnable.run();
    }

    Fix fix = refactorContext.fixBuilder.build();
    return fix.isEmpty() ? NO_MATCH : describeMatch(tree, fix);
  }

  private static void matchedShadowOf(
      MethodInvocationTree shadowOfCall, VisitorState state, RefactorContext refactorContext) {
    ExpressionTree shadowOfArg = shadowOfCall.getArguments().get(0);
    Type shadowOfArgType = getExpressionType(shadowOfArg);

    Tree parent = state.getPath().getParentPath().getLeaf();
    CompilationUnitTree compilationUnit = state.getPath().getCompilationUnit();

    // pointless (ShadowX) shadowOf(x)? drop it.
    if (parent.getKind() == Kind.TYPE_CAST) {
      parent = removeCastIfUnnecessary((JCTypeCast) parent, state);
    }

    switch (parent.getKind()) {
      case VARIABLE: // ShadowType shadowType = shadowOf(type);
        {
          // shadow is being assigned to a variable; don't do that!
          JCVariableDecl variableDecl = (JCVariableDecl) parent;
          String oldVarName = variableDecl.getName().toString();

          // since it's being declared here, no danger of a collision on this var name...
          refactorContext.knownLocalVars.remove(oldVarName);

          String newVarName = pickNewName(shadowOfArg, oldVarName, refactorContext);
          refactorContext.varRemapping.put(getSymbol(variableDecl), newVarName);

          // ... but be careful not to collide with it later.
          refactorContext.knownLocalVars.add(newVarName);

          // replace shadow variable declaration with shadowed type and name
          if (!newVarName.equals(shadowOfArg.toString())) {
            Type shadowedType = getUpperBound(shadowOfArgType, state);
            String newAssignment =
                shadowedType.tsym.name + " " + newVarName + " = " + shadowOfArg + ";";

            // avoid overlapping replacements:
            if (shadowOfArg instanceof JCMethodInvocation) {
              JCExpression jcExpression = ((JCMethodInvocation) shadowOfArg).meth;
              if (jcExpression instanceof JCFieldAccess) {
                refactorContext.possibleFixes.remove(((JCFieldAccess) jcExpression).selected);
              }
            }

            refactorContext
                .fixBuilder
                .replace(parent, newAssignment)
                .addImport(shadowedType.toString());
          } else {
            refactorContext.fixBuilder.delete(parent);
          }

          // replace shadow variable reference with `nonShadowInstance` or
          // `shadowOf(nonShadowInstance)` as appropriate.
          new TreePathScanner<Void, Void>() {
            @Override
            public Void visitIdentifier(IdentifierTree identifierTreeX, Void aVoid) {
              JCIdent identifierTree = (JCIdent) identifierTreeX;

              Symbol symbol = getSymbol(identifierTree);
              if (variableDecl.sym.equals(symbol) && !isLeftSideOfAssignment(identifierTree)) {
                TreePath idPath = TreePath.getPath(compilationUnit, identifierTree);
                Tree parent = idPath.getParentPath().getLeaf();
                boolean callDirectlyOnFramework = shouldCallDirectlyOnFramework(idPath);

                JCTree replaceNode;
                if (parent instanceof JCFieldAccess && !callDirectlyOnFramework) {
                  JCFieldAccess fieldAccess = (JCFieldAccess) parent;
                  replaceNode = fieldAccess.selected = createSyntheticShadowAccess(
                      identifierTree, fieldAccess, shadowOfCall, newVarName, state);
                } else {
                  identifierTree.name = state.getName(newVarName);
                  identifierTree.sym.name = state.getName(newVarName);
                  replaceNode = identifierTree;
                }

                refactorContext.possibleFixes.put(
                    replaceNode,
                    () -> {
                      refactorContext.fixBuilder.replace(
                          identifierTree,
                          callDirectlyOnFramework
                              ? newVarName
                              : shadowOfCall.getMethodSelect() + "(" + newVarName + ")");
                    });
              }
              return super.visitIdentifier(identifierTree, aVoid);
            }

            private boolean isLeftSideOfAssignment(IdentifierTree identifierTree) {
              Tree parent = getCurrentPath().getParentPath().getLeaf();
              if (parent instanceof AssignmentTree) {
                return identifierTree.equals(((AssignmentTree) parent).getVariable());
              }
              return false;
            }
          }.scan(compilationUnit, null);
        }
        break;

      case ASSIGNMENT: // this.shadowType = shadowOf(type);
        {
          // shadow is being assigned to a field or variable; don't do that!
          JCAssign assignment = (JCAssign) parent;
          Symbol fieldSymbol = getSymbol(assignment.lhs);

          String oldFieldName = assignment.lhs.toString();
          String remappedName = refactorContext.varRemapping.get(fieldSymbol);

          // since it's being declared here, no danger of a collision on this var name...
          refactorContext.knownFields.remove(oldFieldName);

          String newFieldName = remappedName == null
              ? pickNewName(shadowOfArg, oldFieldName, refactorContext)
              : remappedName;
          refactorContext.varRemapping.put(fieldSymbol, newFieldName);

          // ... but be careful not to collide with it later.
          refactorContext.knownLocalVars.add(newFieldName);

          // local variable declaration should have been handled above in the VARIABLE case;
          // just strip shadowOf() and assign it to the de-shadowed variable.
          if (fieldSymbol.getKind() == ElementKind.LOCAL_VARIABLE) {
            if (newFieldName.equals(shadowOfArg.toString())) {
              // assigning to self, don't bother
              TreePath assignmentPath = TreePath.getPath(compilationUnit, assignment);
              Tree assignmentParent = assignmentPath.getParentPath().getLeaf();
              if (assignmentParent instanceof ExpressionStatementTree) {
                refactorContext.fixBuilder.delete(assignmentParent);
              }
            } else {
              refactorContext.fixBuilder.replace(
                  assignment, newFieldName + " = " + shadowOfArg.toString());
            }
            break;
          }

          Symbol shadowOfArgSym = getSymbol(shadowOfArg);
          ElementKind shadowOfArgDomicile = shadowOfArgSym == null
              ? ElementKind.OTHER // it's probably an expression, not a var...
              : shadowOfArgSym.getKind();
          boolean namesAreSame = newFieldName.equals(shadowOfArg.toString());

          boolean useExistingField =
              shadowOfArgDomicile == ElementKind.FIELD
                  && namesAreSame
                  && !isMethodParam(ASTHelpers.getSymbol(shadowOfArg), state.getPath());

          if (useExistingField) {
            fixVar(fieldSymbol, state, refactorContext.fixBuilder).delete();

            ExpressionStatementTree enclosingNode =
                ASTHelpers.findEnclosingNode(
                    TreePath.getPath(compilationUnit, parent), ExpressionStatementTree.class);
            if (enclosingNode != null) {
              refactorContext.fixBuilder.delete(enclosingNode);
            }
          } else {
            Type shadowedType = getUpperBound(shadowOfArgType, state);
            fixVar(fieldSymbol, state, refactorContext.fixBuilder)
                .setName(newFieldName)
                .setType(shadowedType.tsym)
                .setRenameUses(false)
                .modify();

            String thisStr = "";
            if (((JCAssign) parent).lhs.toString().startsWith("this.")
                || (shadowOfArgDomicile == ElementKind.LOCAL_VARIABLE && namesAreSame)) {
              thisStr = "this.";
            }

            refactorContext.fixBuilder
                .replace(parent, thisStr + newFieldName + " = " + shadowOfArg)
                .addImport(shadowOfArgType.tsym.toString());
          }

          TreePath containingBlock = findParentOfKind(state, Kind.BLOCK);
          if (containingBlock != null) {
            // replace shadow field reference with `nonShadowInstance` or
            // `shadowOf(nonShadowInstance)` as appropriate.
            new TreePathScanner<Void, Void>() {
              @Override
              public Void visitMemberSelect(MemberSelectTree memberSelectTree, Void aVoid) {
                maybeReplaceFieldRef(memberSelectTree.getExpression());

                return super.visitMemberSelect(memberSelectTree, aVoid);
              }

              @Override
              public Void visitIdentifier(IdentifierTree identifierTree, Void aVoid) {
                maybeReplaceFieldRef(identifierTree);

                return super.visitIdentifier(identifierTree, aVoid);
              }

              private void maybeReplaceFieldRef(ExpressionTree subject) {
                Symbol symbol = getSymbol(subject);
                if (symbol != null && symbol.getKind() == ElementKind.FIELD) {
                  TreePath subjectPath = TreePath.getPath(compilationUnit, subject);

                  if (symbol.equals(fieldSymbol) && isPartOfMethodInvocation(subjectPath)) {
                    String fieldRef =
                        subject.toString().startsWith("this.")
                            ? "this." + newFieldName
                            : newFieldName;

                    JCTree replaceNode = (JCTree) subject;
                    Tree container = subjectPath.getParentPath().getLeaf();
                    if (container instanceof JCFieldAccess) {
                      replaceNode = createSyntheticShadowAccess(replaceNode,
                          (JCFieldAccess) container, shadowOfCall, newFieldName, state);
                    }

                    String replaceWith =
                        shouldCallDirectlyOnFramework(subjectPath)
                            ? fieldRef
                            : shadowOfCall.getMethodSelect() + "(" + fieldRef + ")";
                    refactorContext.possibleFixes.put(replaceNode, () ->
                        refactorContext.fixBuilder.replace(subject, replaceWith));
                  }
                }
              }
            }.scan(compilationUnit, null);
          }
        }
        break;

      case MEMBER_SELECT: // shadowOf(type).method();
        {
          if (shouldCallDirectlyOnFramework(state.getPath())) {
            if (!isInSyntheticShadowAccess(state)) {
              refactorContext.fixBuilder.replace(shadowOfCall, shadowOfArg.toString());
            }
          }
        }
        break;

      case TYPE_CAST:
        System.out.println("WARN: not sure what to do with " + parent.getKind() + ": " + parent);
        break;

      default:
        throw new RuntimeException("not sure what to do with " + parent.getKind() + ": " + parent);
    }
  }

  private static boolean isInSyntheticShadowAccess(VisitorState state) {
    Tree myParent = state.getPath().getParentPath().getLeaf();
    if (myParent instanceof JCFieldAccess) {
      JCFieldAccess myParentFieldAccess = (JCFieldAccess) myParent;
      return (myParentFieldAccess.selected.type instanceof UnknownType);
    }
    return false;
  }

  private static JCMethodInvocation createSyntheticShadowAccess(JCTree replaceNode,
      JCFieldAccess fieldAccess, MethodInvocationTree shadowOfCall, String newFieldName,
      VisitorState state) {
    TreeMaker treeMaker = TreeMaker.instance(state.context);

    JCMethodInvocation apply =
        treeMaker.Apply(
            null,
            (JCExpression) shadowOfCall.getMethodSelect(),
            List.of(treeMaker.Ident(state.getName(newFieldName))));
    apply.type = new UnknownType();
    fieldAccess.selected = apply;
    apply.pos = replaceNode.pos;
    return apply;
  }

  private static boolean isMethodParam(Symbol fieldSymbol, TreePath path) {
    JCMethodDecl enclosingMethodDecl = ASTHelpers.findEnclosingNode(path, JCMethodDecl.class);
    if (enclosingMethodDecl != null) {
      for (JCVariableDecl param : enclosingMethodDecl.getParameters()) {
        if (getSymbol(param).equals(fieldSymbol)) {
          return true;
        }
      }
    }
    return false;
  }

  private static Type getUpperBound(Type type, VisitorState state) {
    return ASTHelpers.getUpperBound(type.tsym.type, Types.instance(state.context));
  }

  private static TreePath findParentOfKind(VisitorState state, Kind kind) {
    TreePath path = state.getPath();
    while (path != null && path.getLeaf().getKind() != kind) {
      path = path.getParentPath();
    }
    return path;
  }

  private static Type getExpressionType(ExpressionTree shadowOfArg) {
    Type shadowOfArgType;
    if (shadowOfArg instanceof JCNewClass) {
      shadowOfArgType = ((JCNewClass) shadowOfArg).type;
    } else if (shadowOfArg instanceof JCTree) {
      shadowOfArgType = ((JCTree) shadowOfArg).type;
    } else {
      throw new RuntimeException("huh? " + shadowOfArg.getClass() + " for " + shadowOfArg);
    }
    return shadowOfArgType;
  }

  private static String pickNewName(ExpressionTree shadowOfArg, String oldVarName,
      RefactorContext refactorContext) {
    String newVarName = oldVarName;

    if (shadowOfArg.getKind() == Kind.IDENTIFIER) {
      // no need to worry about a name collision in this case...
      return shadowOfArg.toString();
    } else if (newVarName.equals("shadow")) {
      newVarName = varNameFromType(getExpressionType(shadowOfArg));
    } else if (newVarName.startsWith("shadow")) {
      newVarName = newVarName.substring(6, 7).toLowerCase() + newVarName.substring(7);
    } else if (newVarName.endsWith("Shadow")) {
      newVarName = newVarName.substring(0, newVarName.length() - "Shadow".length());
    }

    // if the new name is already in use, find a unique name...
    String origNewVarName = newVarName;
    for (int i = 2;
        refactorContext.knownFields.contains(newVarName)
            || refactorContext.knownLocalVars.contains(newVarName);
        i++) {
      newVarName = origNewVarName + i;
    }

    return newVarName;
  }

  private static String varNameFromType(Type type) {
    String simpleName = type.tsym.name.toString();
    return simpleName.substring(0, 1).toLowerCase() + simpleName.substring(1);
  }

  private static ClassType determineShadowedClassName(ClassSymbol owner, VisitorState state) {
    for (Compound compound : owner.getAnnotationMirrors()) {
      if (Implements.class.getName().equals(compound.getAnnotationType().toString())) {
        for (Entry<MethodSymbol, Attribute> entry : compound.getElementValues().entrySet()) {
          String key = entry.getKey().name.toString();
          Attribute value = entry.getValue();

          if (key.equals("value")) {
            TypeMirror typeMirror = valueVisitor.visit(value);
            if (!typeMirror.equals(state.getTypeFromString("void"))) {
              return (ClassType) typeMirror;
            }
          }

          if (key.equals("className")) {
            String name = classNameVisitor.visit(value);
            if (!name.isEmpty()) {
              return (ClassType) state.getTypeFromString(name);
            }
          }
        }
      }
    }

    throw new RuntimeException("couldn't determine shadowed class for " + owner);
  }

  public static AnnotationValueVisitor<TypeMirror, Void> valueVisitor =
      new SimpleAnnotationValueVisitor6<TypeMirror, Void>() {
        @Override
        public TypeMirror visitType(TypeMirror t, Void arg) {
          return t;
        }
      };

  public static AnnotationValueVisitor<String, Void> classNameVisitor =
      new SimpleAnnotationValueVisitor6<String, Void>() {
        @Override
        public String visitString(String s, Void arg) {
          return s;
        }
      };

  private static boolean isPartOfMethodInvocation(TreePath idPath) {
    Kind parentKind = idPath.getParentPath().getLeaf().getKind();
    if (parentKind == Kind.METHOD_INVOCATION) {
      // must be an argument
      return true;
    }
    if (parentKind == Kind.MEMBER_SELECT) {
      Tree maybeMethodInvocation = idPath.getParentPath().getParentPath().getLeaf();
      // likely the target of the method invocation
      return maybeMethodInvocation.getKind() == Kind.METHOD_INVOCATION;
    }
    return false;
  }

  private static boolean shouldCallDirectlyOnFramework(TreePath idPath) {
    if (idPath.getParentPath().getLeaf().getKind() == Kind.MEMBER_SELECT) {
      Tree maybeMethodInvocation = idPath.getParentPath().getParentPath().getLeaf();
      if (maybeMethodInvocation.getKind() == Kind.METHOD_INVOCATION) {
        JCMethodInvocation methodInvocation = (JCMethodInvocation) maybeMethodInvocation;
        MethodSymbol methodSym = (MethodSymbol) ((JCFieldAccess) methodInvocation.meth).sym;
        Implementation implAnnotation = methodSym.getAnnotation(Implementation.class);
        if (implAnnotation != null) {
          int minSdk = implAnnotation.minSdk();
          int maxSdk = implAnnotation.maxSdk();

          // if minSdk or maxSdk is set (or the method is marked @HiddenApi), this method might
          // not be available at every SDK level (or at all).
          return (minSdk == Implementation.DEFAULT_SDK || minSdk <= 16)
              && maxSdk == Implementation.DEFAULT_SDK
              && methodSym.getAnnotation(HiddenApi.class) == null;
        }
      }
    }
    return false;
  }

  /**
   * Renames the given {@link Symbol} and its usages in the current compilation unit to {@code
   * newName}.
   */
  static VariableFixer fixVar(Symbol symbol, VisitorState state, Builder fixBuilder) {
    return new VariableFixer(symbol, state, fixBuilder);
  }

  private static class VariableFixer {

    private final Symbol symbol;
    private final VisitorState state;
    private final SuggestedFix.Builder fixBuilder;
    private boolean renameUses = true;
    private String newName;
    private TypeSymbol newType;

    public VariableFixer(Symbol symbol, VisitorState state, Builder fixBuilder) {
      this.symbol = symbol;
      this.state = state;
      this.fixBuilder = fixBuilder;
    }

    VariableFixer setName(String newName) {
      this.newName = newName;
      return this;
    }

    VariableFixer setType(TypeSymbol newType) {
      this.newType = newType;
      return this;
    }

    VariableFixer setRenameUses(boolean renameUses) {
      this.renameUses = renameUses;
      return this;
    }

    void modify() {
      new TreePathScanner<Void, Void>() {
        @Override
        public Void visitVariable(VariableTree variableTree, Void v) {
          if (getSymbol(variableTree).equals(symbol)) {
            String name = variableTree.getName().toString();
            // For a lambda parameter without explicit type, it will return null.
            String source = state.getSourceForNode(variableTree.getType());
            if (newType != null) {
              fixBuilder.replace(variableTree.getType(), newType.name.toString());
            }

            if (newName != null && !newName.equals(name)) {
              int typeLength = source == null ? 0 : source.length();
              int pos =
                  ((JCTree) variableTree).getStartPosition()
                      + state.getSourceForNode(variableTree).indexOf(name, typeLength);
              fixBuilder.replace(pos, pos + name.length(), newName);
            }
          }

          return super.visitVariable(variableTree, v);
        }
      }.scan(state.getPath().getCompilationUnit(), null);

      if (newName != null && renameUses) {
        ((JCTree) state.getPath().getCompilationUnit())
            .accept(
                new com.sun.tools.javac.tree.TreeScanner() {
                  @Override
                  public void visitIdent(JCTree.JCIdent tree) {
                    if (symbol.equals(getSymbol(tree))) {
                      fixBuilder.replace(tree, newName);
                    }
                  }
                });
      }
    }

    void delete() {
      new TreePathScanner<Void, Void>() {
        @Override
        public Void visitVariable(VariableTree variableTree, Void v) {
          if (getSymbol(variableTree).equals(symbol)) {
            fixBuilder.delete(variableTree);
          }

          return super.visitVariable(variableTree, v);
        }
      }.scan(state.getPath().getCompilationUnit(), null);
    }
  }

  private static Tree removeCastIfUnnecessary(JCTypeCast cast, VisitorState state) {
    if (cast.type.tsym.equals(cast.expr.type.tsym)) {
      Tree grandparent = findParent(cast, state);
      switch (grandparent.getKind()) {
        case VARIABLE:
          JCVariableDecl variableDecl = (JCVariableDecl) grandparent;
          variableDecl.init = cast.expr;
          break;
        case ASSIGNMENT:
          JCAssign assignment = (JCAssign) grandparent;
          assignment.rhs = cast.expr;
          break;
        default:
          // ok
      }

      // point to the expression that was previously being cast
      return grandparent;
    } else {
      return cast;
    }
  }

  private static Tree findParent(Tree node, VisitorState state) {
    return TreePath.getPath(state.getPath().getCompilationUnit(), node).getParentPath().getLeaf();
  }

  private static class RefactorContext {
    private final SuggestedFix.Builder fixBuilder = SuggestedFix.builder();
    private final Map<Tree, Runnable> possibleFixes = new HashMap<>();

    private final Set<String> knownFields = new HashSet<>();
    private final Set<String> knownLocalVars = new HashSet<>();
    private final Map<Symbol, String> varRemapping = new HashMap<>();
  }
}
