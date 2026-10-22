package dev.bhash.backend;

import dev.bhash.frontend.Identifiers;
import dev.bhash.parser.BhashBaseVisitor;
import dev.bhash.parser.BhashParser.*;
import java.math.BigInteger;
import java.util.*;
import org.antlr.v4.runtime.ParserRuleContext;

/** Shared semantic checking and source lowering for JVM and CLR. User names are mangled. */
public final class ManagedEmitter extends BhashBaseVisitor<ManagedEmitter.Expr> {
    static final class Type {
        static final Type INT = new Type("long", "पूर्णांक"), REAL = new Type("double", "दशमलव"),
                BOOL = new Type("boolean", "तार्किक"), TEXT = new Type("String", "पाठ"),
                VOID = new Type("void", "शून्य"), NULL = new Type("", "रिक्त");
        final String java, bhash;
        final ClassInfo object;
        Type(String java, String bhash) { this(java, bhash, null); }
        Type(String java, String bhash, ClassInfo object) { this.java = java; this.bhash = bhash; this.object = object; }
        static Type[] values() { return new Type[]{INT, REAL, BOOL, TEXT, VOID}; }
        boolean reference() { return object != null || this == NULL; }
    }
    record Location(boolean mutable) {}
    record Expr(Type type, String java, boolean call, Location location, boolean base) {
        Expr(Type type, String java) { this(type, java, false, null, false); }
        Expr(Type type, String java, boolean call) { this(type, java, call, null, false); }
        Expr(Type type, String java, Location location) { this(type, java, false, location, false); }
    }
    enum Access { PRIVATE, PROTECTED, PUBLIC }
    static final class ClassInfo {
        final String spelling, java;
        final Type type;
        final ClassDeclContext declaration;
        final Map<String, Field> fields = new LinkedHashMap<>();
        final Map<String, Function> methods = new LinkedHashMap<>();
        ClassInfo parent;
        Constructor constructor;
        int state;
        ClassInfo(String spelling, String java, ClassDeclContext declaration) {
            this.spelling = spelling; this.java = java; this.declaration = declaration;
            this.type = new Type(java, spelling, this);
        }
    }
    record Field(String java, Type type, boolean mutable, Access access, ClassInfo owner, FieldDeclContext declaration) {}
    record Constructor(List<Type> parameters, Access access, ConstructorDeclContext declaration) {}
    static final class Function {
        final String java;
        final Type result;
        final List<Type> parameters;
        final ClassInfo owner;
        final Access access;
        final boolean overriding;
        final FunctionDeclContext declaration;
        Function(String java, Type result, List<Type> parameters, ClassInfo owner, Access access,
                 boolean overriding, FunctionDeclContext declaration) {
            this.java = java; this.result = result; this.parameters = parameters; this.owner = owner;
            this.access = access; this.overriding = overriding; this.declaration = declaration;
        }
    }
    record Binding(String java, Type type, boolean mutable) {}
    enum Flow { NORMAL, RETURN, BREAK, CONTINUE }
    private static final BigInteger MIN = BigInteger.valueOf(Long.MIN_VALUE);
    private static final BigInteger MAX = BigInteger.valueOf(Long.MAX_VALUE);
    private final String sourceName;
    private final Target target;
    private final Map<String, Function> functions = new LinkedHashMap<>();
    private final Map<String, ClassInfo> classes = new LinkedHashMap<>();
    private final List<ClassInfo> classOrder = new ArrayList<>();
    private final Deque<Map<String, Binding>> scopes = new ArrayDeque<>();
    private final StringBuilder output = new StringBuilder();
    private Type returnType;
    private ClassInfo currentClass;
    private boolean fieldInitializer, baseArguments, constructorBody;
    private int variableId, loopDepth, methodId, fieldId;

    // Visitor APIs cannot throw checked exceptions; unwrap this at the public boundary.
    private static final class Invalid extends RuntimeException {
        Invalid(String message) { super(message); }
    }

    private ManagedEmitter(String sourceName, Target target) { this.sourceName = sourceName; this.target = target; }

    public static String emit(String sourceName, ProgramContext program, Target target) throws CompilationException {
        if (target != Target.JVM && target != Target.CLR) throw new IllegalArgumentException("Not a managed target");
        try { return new ManagedEmitter(sourceName, target).program(program); }
        catch (Invalid error) { throw new CompilationException(error.getMessage()); }
    }

    private Invalid error(ParserRuleContext at, String message) {
        return new Invalid(sourceName + ":" + at.getStart().getLine() + ":"
                + (at.getStart().getCharPositionInLine() + 1) + ": " + message);
    }

    private Type type(ParserRuleContext at) {
        String spelling = at.getText();
        for (Type primitive : Type.values()) if (primitive.bhash.equals(spelling)) return primitive;
        ClassInfo found = classes.get(Identifiers.key(spelling));
        if (found == null) throw error(at, "Unknown type: " + spelling);
        return found.type;
    }

    private String typeName(Type type) {
        if (target == Target.CLR && type == Type.BOOL) return "bool";
        if (target == Target.CLR && type == Type.TEXT) return "string";
        return type.java;
    }

    private String program(ProgramContext program) {
        collectClasses(program);
        functions.put(Identifiers.key("पढ़ो"), new Function("BhashRuntime.readInteger", Type.INT, List.of(), null, Access.PUBLIC, false, null));
        for (var declaration : program.functionDecl()) {
            String name = Identifiers.key(declaration.IDENTIFIER().getText());
            var function = new Function("f" + functions.size(), type(declaration.returnType()), parameters(declaration.parameters()),
                    null, Access.PUBLIC, false, declaration);
            if (functions.putIfAbsent(name, function) != null)
                throw error(declaration, "Duplicate or reserved function: " + declaration.IDENTIFIER().getText());
        }
        var main = functions.get(Identifiers.key("मुख्य"));
        if (main == null || main.result != Type.VOID || !main.parameters.isEmpty())
            throw error(program, "Executable requires exactly one कार्य मुख्य() -> शून्य.");
        if (target == Target.JVM) {
            output.append("public final class BhashProgram {\n");
            output.append("public static void main(String[] args) { try { ").append(main.java)
                    .append("(); } catch (BhashRuntime.Failure | ArithmeticException e) {")
                    .append(" BhashRuntime.report(e.getMessage()); System.exit(1); } }\n");
        } else {
            output.append("using System;\ninternal static class BhashProgram {\n");
            output.append("public static int Main(string[] args) { try { ").append(main.java)
                    .append("(); return 0; } catch (Exception e) when (e is BhashRuntime.Failure || e is ArithmeticException) {")
                    .append(" BhashRuntime.report(e.Message); return 1; } }\n");
        }
        for (var declaration : program.functionDecl()) emitFunction(functions.get(Identifiers.key(declaration.IDENTIFIER().getText())));
        for (ClassInfo definition : classOrder) emitClass(definition);
        return output.append("}\n").toString();
    }

    private void emitFunction(Function function) {
        var declaration = function.declaration;
        currentClass = function.owner;
        returnType = function.result;
        scopes.push(new HashMap<>());
        if (function.owner == null) output.append("static ");
        else {
            if (target == Target.JVM && function.overriding) output.append("@Override ");
            output.append(accessName(function.access)).append(' ');
            if (target == Target.CLR && function.access != Access.PRIVATE) output.append(function.overriding ? "override " : "virtual ");
        }
        output.append(typeName(function.result)).append(' ').append(function.java).append('(');
        emitParameters(declaration.parameters(), function.parameters);
        output.append(") {\n");
        var flow = statements(declaration.block());
        if (returnType != Type.VOID && flow.contains(Flow.NORMAL))
            throw error(declaration, "Function must return " + returnType.bhash + " on every path.");
        output.append("}\n");
        scopes.pop();
        currentClass = null;
    }

    private List<Type> parameters(ParametersContext parameters) {
        return parameters == null ? List.of() : parameters.parameter().stream().map(p -> type(p.valueType())).toList();
    }

    private Access access(VisibilityContext visibility) {
        if (visibility == null || visibility.PUBLIC() != null) return Access.PUBLIC;
        return visibility.PRIVATE() != null ? Access.PRIVATE : Access.PROTECTED;
    }

    private String accessName(Access access) { return access.name().toLowerCase(Locale.ROOT); }

    private void collectClasses(ProgramContext program) {
        for (var declaration : program.classDecl()) {
            String name = declaration.IDENTIFIER(0).getText();
            var definition = new ClassInfo(name, "C" + classes.size(), declaration);
            if (classes.putIfAbsent(Identifiers.key(name), definition) != null) throw error(declaration, "Duplicate class: " + name);
        }
        for (ClassInfo definition : classes.values()) {
            if (definition.declaration.EXTENDS() != null) {
                String name = definition.declaration.IDENTIFIER(1).getText();
                definition.parent = classes.get(Identifiers.key(name));
                if (definition.parent == null) throw error(definition.declaration, "Unknown base class: " + name);
            }
        }
        for (ClassInfo definition : classes.values()) orderClass(definition);
        for (ClassInfo definition : classOrder) {
            for (var member : definition.declaration.classMember()) {
                Access access = access(member.visibility());
                if (member.fieldDecl() != null) {
                    var declaration = member.fieldDecl();
                    String name = Identifiers.key(declaration.IDENTIFIER().getText());
                    if (definition.fields.containsKey(name) || definition.methods.containsKey(name)
                            || field(definition.parent, name) != null || method(definition.parent, name) != null)
                        throw error(member, "Duplicate or hidden member: " + declaration.IDENTIFIER().getText());
                    if (declaration.CONST() != null && declaration.expression() == null)
                        throw error(declaration, "An immutable field requires an initializer.");
                    definition.fields.put(name, new Field("p" + fieldId++, type(declaration.valueType()),
                            declaration.LET() != null, access, definition, declaration));
                } else if (member.constructorDecl() != null) {
                    if (definition.constructor != null) throw error(member, "Only one constructor is supported per class.");
                    var declaration = member.constructorDecl();
                    definition.constructor = new Constructor(parameters(declaration.parameters()), access, declaration);
                } else {
                    var declaration = member.functionDecl();
                    String name = Identifiers.key(declaration.IDENTIFIER().getText());
                    if (definition.fields.containsKey(name) || definition.methods.containsKey(name) || field(definition.parent, name) != null)
                        throw error(member, "Duplicate or hidden member: " + declaration.IDENTIFIER().getText());
                    Function parent = method(definition.parent, name);
                    boolean overriding = member.OVERRIDE() != null;
                    if (overriding && (parent == null || parent.access == Access.PRIVATE))
                        throw error(member, "Override requires an inherited non-private method.");
                    if (parent != null && !overriding) throw error(member, "Inherited method requires अधिलेखन.");
                    var signature = parameters(declaration.parameters());
                    Type result = type(declaration.returnType());
                    if (parent != null && (!parent.parameters.equals(signature) || parent.result != result))
                        throw error(member, "Override must preserve parameter and return types exactly.");
                    if (parent != null && access.ordinal() < parent.access.ordinal()) throw error(member, "Override cannot narrow access.");
                    definition.methods.put(name, new Function(parent == null ? "m" + methodId++ : parent.java,
                            result, signature, definition, access, overriding, declaration));
                }
            }
            if (definition.constructor == null) definition.constructor = new Constructor(List.of(), Access.PUBLIC, null);
        }
    }

    private void orderClass(ClassInfo definition) {
        if (definition.state == 1) throw error(definition.declaration, "Inheritance cycle detected.");
        if (definition.state == 2) return;
        definition.state = 1;
        if (definition.parent != null) orderClass(definition.parent);
        definition.state = 2;
        classOrder.add(definition);
    }

    private Field field(ClassInfo definition, String name) {
        for (; definition != null; definition = definition.parent) {
            var field = definition.fields.get(Identifiers.key(name));
            if (field != null) return field;
        }
        return null;
    }

    private Function method(ClassInfo definition, String name) {
        for (; definition != null; definition = definition.parent) {
            var method = definition.methods.get(Identifiers.key(name));
            if (method != null) return method;
        }
        return null;
    }

    private boolean subclass(ClassInfo child, ClassInfo parent) {
        for (; child != null; child = child.parent) if (child == parent) return true;
        return false;
    }

    private boolean compatible(Type expected, Type actual) {
        return expected == actual || (expected.object != null && (actual == Type.NULL || subclass(actual.object, expected.object)));
    }

    private void checkAccess(ParserRuleContext at, ClassInfo owner, Access access, Expr receiver) {
        if (access == Access.PUBLIC || currentClass == owner) return;
        if (access == Access.PROTECTED && currentClass != null && subclass(currentClass, owner)
                && (receiver.base || subclass(receiver.type.object, currentClass))) return;
        throw error(at, "Member is not accessible (" + accessName(access) + ").");
    }

    private void emitParameters(ParametersContext parameters, List<Type> types) {
        if (parameters == null) return;
        int index = 0;
        for (var parameter : parameters.parameter()) {
            if (index > 0) output.append(',');
            var binding = declare(parameter, parameter.IDENTIFIER().getText(), types.get(index++), false);
            output.append(target == Target.JVM ? "final " : "").append(typeName(binding.type)).append(' ').append(binding.java);
        }
    }

    private void emitClass(ClassInfo definition) {
        currentClass = definition;
        output.append(target == Target.JVM ? "static class " : "public class ").append(definition.java);
        if (definition.parent != null) output.append(target == Target.JVM ? " extends " : " : ").append(definition.parent.java);
        output.append(" {\n");
        Map<Field, String> initializers = new LinkedHashMap<>();
        for (Field field : definition.fields.values()) {
            currentClass = definition;
            Expr value;
            if (field.declaration.expression() == null) value = new Expr(field.type,
                    field.type == Type.TEXT ? "\"\"" : field.type == Type.BOOL ? "false" : field.type.object != null ? "null" : field.type == Type.REAL ? "0.0" : "0L");
            else {
                fieldInitializer = true;
                value = visit(field.declaration.expression());
                fieldInitializer = false;
                require(field.declaration, field.type, value.type);
            }
            output.append(accessName(field.access)).append(' ');
            if (!field.mutable) output.append(target == Target.JVM ? "final " : "readonly ");
            output.append(typeName(field.type)).append(' ').append(field.java).append(";\n");
            initializers.put(field, value.java);
        }
        emitConstructor(definition, initializers);
        for (Function method : definition.methods.values()) emitFunction(method);
        output.append("}\n");
        currentClass = null;
    }

    private void emitConstructor(ClassInfo definition, Map<Field, String> initializers) {
        currentClass = definition;
        returnType = Type.VOID;
        var constructor = definition.constructor;
        var declaration = constructor.declaration;
        ParserRuleContext at = declaration == null ? definition.declaration : declaration;
        scopes.push(new HashMap<>());
        output.append(accessName(constructor.access)).append(' ').append(definition.java).append('(');
        emitParameters(declaration == null ? null : declaration.parameters(), constructor.parameters);
        output.append(')');
        baseArguments = true;
        List<Expr> arguments = declaration == null ? List.of() : arguments(declaration.arguments());
        baseArguments = false;
        if (definition.parent == null) {
            if (declaration != null && declaration.BASE() != null) throw error(at, "A base initializer requires a parent class.");
        } else {
            Constructor parent = definition.parent.constructor;
            if (parent.access == Access.PRIVATE) throw error(at, "Base constructor is not accessible (private).");
            checkArguments(at, arguments, parent.parameters, "base constructor");
            if (target == Target.CLR) output.append(" : base(").append(join(arguments)).append(')');
        }
        output.append(" {\n");
        if (definition.parent != null && target == Target.JVM) output.append("super(").append(join(arguments)).append(");\n");
        for (var initializer : initializers.entrySet()) output.append("this.").append(initializer.getKey().java)
                .append('=').append(initializer.getValue()).append(";\n");
        constructorBody = true;
        if (declaration != null) statements(declaration.block());
        constructorBody = false;
        output.append("}\n");
        scopes.pop();
    }

    private void checkArguments(ParserRuleContext at, List<Expr> arguments, List<Type> parameters, String name) {
        if (arguments.size() != parameters.size()) throw error(at, "Wrong argument count for " + name + ".");
        for (int i = 0; i < arguments.size(); i++) require(at, parameters.get(i), arguments.get(i).type);
    }

    private Binding declare(ParserRuleContext at, String spelling, Type type, boolean mutable) {
        var binding = new Binding("v" + variableId++, type, mutable);
        if (scopes.peek().putIfAbsent(Identifiers.key(spelling), binding) != null)
            throw error(at, "Duplicate binding: " + spelling);
        return binding;
    }

    private Binding find(String name) {
        for (var scope : scopes) {
            var binding = scope.get(Identifiers.key(name));
            if (binding != null) return binding;
        }
        return null;
    }

    private EnumSet<Flow> statements(BlockContext block) {
        var result = EnumSet.of(Flow.NORMAL);
        for (var statement : block.statement()) {
            if (!result.remove(Flow.NORMAL)) throw error(statement, "Unreachable statement.");
            result.addAll(statement(statement));
        }
        return result;
    }

    private EnumSet<Flow> block(BlockContext block) {
        scopes.push(new HashMap<>());
        output.append("{\n");
        var result = statements(block);
        output.append("}\n");
        scopes.pop();
        return result;
    }

    private EnumSet<Flow> statement(StatementContext statement) {
        if (statement.variableDecl() != null) {
            var declaration = statement.variableDecl();
            var value = visit(declaration.expression());
            var declaredType = type(declaration.valueType());
            require(declaration, declaredType, value.type);
            var binding = declare(declaration, declaration.IDENTIFIER().getText(), declaredType, declaration.LET() != null);
            output.append(binding.mutable || target == Target.CLR ? "" : "final ").append(typeName(declaredType)).append(' ')
                    .append(binding.java).append('=').append(value.java).append(";\n");
        } else if (statement.assignment() != null) {
            var assignment = statement.assignment();
            var destination = visit(assignment.postfix());
            if (destination.location == null) throw error(assignment, "Assignment requires a variable or field.");
            if (!destination.location.mutable) throw error(assignment, "Cannot assign to an immutable binding or field.");
            var value = visit(assignment.expression());
            require(assignment, destination.type, value.type);
            output.append(destination.java).append('=').append(value.java).append(";\n");
        } else if (statement.ifStatement() != null) {
            return ifStatement(statement.ifStatement());
        } else if (statement.whileStatement() != null) {
            var loop = statement.whileStatement();
            var condition = visit(loop.expression());
            require(loop, Type.BOOL, condition.type);
            output.append("while (BhashRuntime.truth(").append(condition.java).append(")) ");
            loopDepth++;
            var body = block(loop.block());
            loopDepth--;
            var flow = EnumSet.of(Flow.NORMAL); // Conservative: loops may execute zero times.
            if (body.contains(Flow.RETURN)) flow.add(Flow.RETURN);
            return flow;
        } else if (statement.RETURN() != null) {
            var value = statement.expression() == null ? null : visit(statement.expression());
            if (returnType == Type.VOID && value != null) throw error(statement, "Void function cannot return a value.");
            if (returnType != Type.VOID && value == null) throw error(statement, "Return value required.");
            if (value != null) require(statement, returnType, value.type);
            output.append("return").append(value == null ? "" : " " + value.java).append(";\n");
            return EnumSet.of(Flow.RETURN);
        } else if (statement.BREAK() != null || statement.CONTINUE() != null) {
            if (loopDepth == 0) throw error(statement, "Loop control is only valid inside a loop.");
            boolean isBreak = statement.BREAK() != null;
            output.append(isBreak ? "break;\n" : "continue;\n");
            return EnumSet.of(isBreak ? Flow.BREAK : Flow.CONTINUE);
        } else if (statement.PRINT() != null) {
            var args = arguments(statement.arguments());
            for (var arg : args) {
                valueRequired(statement, arg.type);
                if (arg.type.reference()) throw error(statement, "Print an object's primitive fields or method results, not the object reference.");
            }
            output.append("BhashRuntime.print(").append(join(args)).append(");\n");
        } else if (statement.block() != null) {
            return block(statement.block());
        } else {
            var value = visit(statement.expression());
            if (!value.call) throw error(statement, "Only a function call may be used as an expression statement.");
            output.append(value.type == Type.VOID ? value.java : "BhashRuntime.discard(" + value.java + ")").append(";\n");
        }
        return EnumSet.of(Flow.NORMAL);
    }

    private EnumSet<Flow> ifStatement(IfStatementContext statement) {
        var condition = visit(statement.expression());
        require(statement, Type.BOOL, condition.type);
        output.append("if (").append(condition.java).append(") ");
        var flow = block(statement.block(0));
        if (statement.ELSE() == null) flow.add(Flow.NORMAL);
        else {
            output.append("else ");
            flow.addAll(statement.ifStatement() != null ? ifStatement(statement.ifStatement()) : block(statement.block(1)));
        }
        return flow;
    }

    private List<Expr> arguments(ArgumentsContext args) {
        return args == null ? List.of() : args.expression().stream().map(this::visit).toList();
    }
    private String join(List<Expr> args) { return String.join(",", args.stream().map(Expr::java).toList()); }
    private void require(ParserRuleContext at, Type expected, Type actual) {
        if (!compatible(expected, actual)) throw error(at, "Expected " + expected.bhash + ", found " + actual.bhash + ".");
    }
    private void valueRequired(ParserRuleContext at, Type type) {
        if (type == Type.VOID) throw error(at, "A void function does not produce a value.");
    }
    private void numeric(ParserRuleContext at, Type type) {
        if (type != Type.INT && type != Type.REAL) throw error(at, "Numeric operand required.");
    }

    @Override public Expr visitExpression(ExpressionContext ctx) { return visit(ctx.logicalOr()); }
    @Override public Expr visitLogicalOr(LogicalOrContext ctx) { return binary(ctx); }
    @Override public Expr visitLogicalAnd(LogicalAndContext ctx) { return binary(ctx); }
    @Override public Expr visitEquality(EqualityContext ctx) { return binary(ctx); }
    @Override public Expr visitComparison(ComparisonContext ctx) { return binary(ctx); }
    @Override public Expr visitAdditive(AdditiveContext ctx) { return binary(ctx); }
    @Override public Expr visitMultiplicative(MultiplicativeContext ctx) { return binary(ctx); }

    private Expr binary(ParserRuleContext ctx) {
        var result = visit(ctx.getChild(0));
        for (int i = 1; i < ctx.getChildCount(); i += 2) {
            String operator = ctx.getChild(i).getText();
            var right = visit(ctx.getChild(i + 1));
            valueRequired(ctx, result.type);
            if ((operator.equals("==") || operator.equals("!=")) && result.type.reference() && right.type.reference()) {
                if (result.type != Type.NULL && right.type != Type.NULL
                        && !compatible(result.type, right.type) && !compatible(right.type, result.type))
                    throw error(ctx, "Cannot compare unrelated object types.");
                result = new Expr(Type.BOOL, (operator.equals("!=") ? "!" : "") + "BhashRuntime.same(" + result.java + "," + right.java + ")");
                continue;
            }
            require(ctx, result.type, right.type);
            Type type = result.type;
            String java;
            switch (operator) {
                case "&&", "||" -> { require(ctx, Type.BOOL, type); type = Type.BOOL; }
                case "==", "!=" -> { type = Type.BOOL; }
                case "<", "<=", ">", ">=" -> { numeric(ctx, type); type = Type.BOOL; }
                case "%" -> require(ctx, Type.INT, type);
                default -> numeric(ctx, type);
            }
            if ((operator.equals("==") || operator.equals("!=")) && result.type == Type.TEXT) {
                String equality = target == Target.JVM ? "java.util.Objects.equals(" : "string.Equals(";
                java = (operator.equals("!=") ? "!" : "") + equality + result.java + "," + right.java
                        + (target == Target.JVM ? ")" : ",StringComparison.Ordinal)");
            } else if ((operator.equals("/") || operator.equals("%")) && result.type == Type.INT) {
                // A helper gives constant-zero division the same runtime behavior as dynamic division.
                java = "BhashRuntime." + (operator.equals("/") ? "divide" : "remainder") + "(" + result.java + "," + right.java + ")";
            } else java = "(" + result.java + " " + operator + " " + right.java + ")";
            if (target == Target.CLR && type == Type.INT && Set.of("+", "-", "*").contains(operator))
                java = "unchecked(" + java + ")";
            result = new Expr(type, java);
        }
        return result;
    }

    @Override public Expr visitUnary(UnaryContext ctx) {
        if (ctx.postfix() != null) return visit(ctx.postfix());
        String op = ctx.getChild(0).getText();
        // Accept the minimum signed literal, including parenthesized magnitude.
        String magnitude = ctx.unary().getText().replace("(", "").replace(")", "");
        if (op.equals("-") && magnitude.matches("[0-9०-९]+")) return integer(ctx, "-" + digits(magnitude));
        var value = visit(ctx.unary());
        if (op.equals("!")) require(ctx, Type.BOOL, value.type);
        else numeric(ctx, value.type);
        String code = "(" + op + "(" + value.java + "))";
        if (target == Target.CLR && value.type == Type.INT) code = "unchecked(" + code + ")";
        return new Expr(value.type, code);
    }

    private Expr self(ParserRuleContext at, boolean base) {
        if (currentClass == null || fieldInitializer || baseArguments) throw error(at, "Instance access is unavailable in this context.");
        if (base && currentClass.parent == null) throw error(at, "आधार requires a parent class.");
        return new Expr(base ? currentClass.parent.type : currentClass.type,
                base ? (target == Target.JVM ? "super" : "base") : "this", false, null, base);
    }

    private String receiver(Expr object) {
        return object.base ? object.java : "BhashRuntime.instance(" + object.java + ")";
    }

    private String memberReceiver(Expr object, ClassInfo owner, Access access) {
        String code = receiver(object);
        // Private members are not inherited by the host's subtype; resolve them in their declaring class.
        return access == Access.PRIVATE && object.type.object != owner ? "((" + owner.java + ")" + code + ")" : code;
    }

    private Expr readField(ParserRuleContext at, Expr object, String name) {
        Field field = field(object.type.object, name);
        if (field == null) throw error(at, "Undefined field: " + name);
        checkAccess(at, field.owner, field.access, object);
        return new Expr(field.type, memberReceiver(object, field.owner, field.access) + "." + field.java, new Location(field.mutable));
    }

    private Expr callMethod(ParserRuleContext at, Expr object, String name, List<Expr> arguments) {
        Function method = method(object.type.object, name);
        if (method == null) throw error(at, "Undefined method: " + name);
        if (constructorBody && (object.base || object.java.equals("this")))
            throw error(at, "Constructors cannot call instance methods on यह or आधार before construction completes.");
        checkAccess(at, method.owner, method.access, object);
        checkArguments(at, arguments, method.parameters, name);
        return new Expr(method.result, memberReceiver(object, method.owner, method.access) + "." + method.java + "(" + join(arguments) + ")", true);
    }

    @Override public Expr visitPostfix(PostfixContext ctx) {
        Expr value = visit(ctx.primary());
        if (ctx.postfixPart().isEmpty() && value.base) throw error(ctx, "आधार is only valid as a member receiver.");
        if (ctx.postfixPart().isEmpty() && constructorBody && value.java.equals("this"))
            throw error(ctx, "Constructors may use यह only as a direct field receiver; the new object cannot escape early.");
        for (var part : ctx.postfixPart()) {
            if (value.type.object == null) throw error(part, "Member access requires a class instance.");
            value = part.LPAREN() == null ? readField(part, value, part.IDENTIFIER().getText())
                    : callMethod(part, value, part.IDENTIFIER().getText(), arguments(part.arguments()));
        }
        return value;
    }

    @Override public Expr visitPrimary(PrimaryContext ctx) {
        if (ctx.INTEGER_LITERAL() != null) return integer(ctx, digits(ctx.getText()));
        if (ctx.REAL_LITERAL() != null) {
            double value = Double.parseDouble(digits(ctx.getText()));
            if (!Double.isFinite(value)) throw error(ctx, "Real literal is outside the finite binary64 range.");
            return new Expr(Type.REAL, Double.toString(value));
        }
        if (ctx.STRING_LITERAL() != null) return string(ctx);
        if (ctx.TRUE() != null || ctx.FALSE() != null) return new Expr(Type.BOOL, ctx.TRUE() != null ? "true" : "false");
        if (ctx.NULL() != null) return new Expr(Type.NULL, "null");
        if (ctx.THIS() != null) return self(ctx, false);
        if (ctx.BASE() != null) return self(ctx, true);
        if (ctx.NEW() != null) {
            if (fieldInitializer) throw error(ctx, "Field initializers must be constant expressions, without calls or new objects.");
            var definition = classes.get(Identifiers.key(ctx.IDENTIFIER().getText()));
            if (definition == null) throw error(ctx, "Unknown class: " + ctx.IDENTIFIER().getText());
            var constructor = definition.constructor;
            if (constructor.access != Access.PUBLIC && currentClass != definition)
                throw error(ctx, "Constructor is not accessible (" + accessName(constructor.access) + ").");
            var arguments = arguments(ctx.arguments());
            checkArguments(ctx, arguments, constructor.parameters, "constructor " + definition.spelling);
            return new Expr(definition.type, "new " + definition.java + "(" + join(arguments) + ")", true);
        }
        if (ctx.IDENTIFIER() != null) {
            String name = ctx.IDENTIFIER().getText();
            if (fieldInitializer) throw error(ctx, "Field initializers must be constant expressions, without names or calls.");
            if (ctx.LPAREN() == null) {
                var local = find(name);
                if (local != null) return new Expr(local.type, local.java, new Location(local.mutable));
                if (field(currentClass, name) != null) return readField(ctx, self(ctx, false), name);
                throw error(ctx, "Undefined variable: " + name);
            }
            if (find(name) != null) throw error(ctx, "Binding is not callable: " + name);
            if (method(currentClass, name) != null) return callMethod(ctx, self(ctx, false), name, arguments(ctx.arguments()));
            if (field(currentClass, name) != null) throw error(ctx, "Binding is not callable: " + name);
            var function = functions.get(Identifiers.key(name));
            if (function == null) throw error(ctx, "Undefined function: " + name);
            var args = arguments(ctx.arguments());
            checkArguments(ctx, args, function.parameters, name);
            return new Expr(function.result, function.java + "(" + join(args) + ")", true);
        }
        var value = visit(ctx.expression());
        return new Expr(value.type, value.type == Type.VOID ? value.java : "(" + value.java + ")", value.call);
    }

    private Expr integer(ParserRuleContext at, String decimal) {
        var value = new BigInteger(decimal);
        if (value.compareTo(MIN) < 0 || value.compareTo(MAX) > 0) throw error(at, "Integer literal is outside signed 64-bit range.");
        return new Expr(Type.INT, value + "L");
    }

    private static String digits(String input) {
        var result = new StringBuilder();
        for (char c : input.toCharArray()) result.append(c >= '०' && c <= '९' ? (char) ('0' + c - '०') : c);
        return result.toString();
    }

    private Expr string(PrimaryContext ctx) {
        String raw = ctx.getText();
        var decoded = new StringBuilder();
        for (int i = 1; i < raw.length() - 1; i++) {
            char c = raw.charAt(i);
            if (c != '\\') { decoded.append(c); continue; }
            c = raw.charAt(++i);
            switch (c) {
                case 'n' -> decoded.append('\n');
                case 'r' -> decoded.append('\r');
                case 't' -> decoded.append('\t');
                case '"', '\\' -> decoded.append(c);
                case 'u' -> { decoded.append((char) Integer.parseInt(raw.substring(i + 1, i + 5), 16)); i += 4; }
                default -> throw error(ctx, "Invalid string escape.");
            }
        }
        String value = decoded.toString();
        var codepoints = new StringJoiner(",");
        int count = 0;
        for (int cp : value.codePoints().toArray()) {
            if (cp >= 0xD800 && cp <= 0xDFFF) throw error(ctx, "Unpaired surrogate in text literal.");
            codepoints.add(Integer.toString(cp));
            count++;
        }
        return new Expr(Type.TEXT, target == Target.JVM
                ? "new String(new int[]{" + codepoints + "},0," + count + ")"
                : "BhashRuntime.text(new int[]{" + codepoints + "})");
    }
}
