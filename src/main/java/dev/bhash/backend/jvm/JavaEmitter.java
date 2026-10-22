package dev.bhash.backend.jvm;

import dev.bhash.frontend.Identifiers;
import dev.bhash.parser.BhashBaseVisitor;
import dev.bhash.parser.BhashParser.*;
import java.math.BigInteger;
import java.util.*;
import org.antlr.v4.runtime.ParserRuleContext;

/** Type-checks the v0.1 core and lowers it to Java. User names never become Java names. */
final class JavaEmitter extends BhashBaseVisitor<JavaEmitter.Expr> {
    enum Type {
        INT("long", "पूर्णांक"), REAL("double", "दशमलव"),
        BOOL("boolean", "तार्किक"), TEXT("String", "पाठ"), VOID("void", "शून्य");
        final String java, bhash;
        Type(String java, String bhash) { this.java = java; this.bhash = bhash; }
    }
    record Expr(Type type, String java, boolean call) {
        Expr(Type type, String java) { this(type, java, false); }
    }
    record Function(String java, Type result, List<Type> parameters) {}
    record Binding(String java, Type type, boolean mutable) {}
    enum Flow { NORMAL, RETURN, BREAK, CONTINUE }
    private static final BigInteger MIN = BigInteger.valueOf(Long.MIN_VALUE);
    private static final BigInteger MAX = BigInteger.valueOf(Long.MAX_VALUE);
    private final String sourceName;
    private final Map<String, Function> functions = new LinkedHashMap<>();
    private final Deque<Map<String, Binding>> scopes = new ArrayDeque<>();
    private final StringBuilder output = new StringBuilder();
    private Type returnType;
    private int variableId, loopDepth;

    // Visitor APIs cannot throw checked exceptions; unwrap this at the public boundary.
    private static final class Invalid extends RuntimeException {
        Invalid(String message) { super(message); }
    }

    private JavaEmitter(String sourceName) { this.sourceName = sourceName; }

    static String emit(String sourceName, ProgramContext program) throws CompilationException {
        try { return new JavaEmitter(sourceName).program(program); }
        catch (Invalid error) { throw new CompilationException(error.getMessage()); }
    }

    private Invalid error(ParserRuleContext at, String message) {
        return new Invalid(sourceName + ":" + at.getStart().getLine() + ":"
                + (at.getStart().getCharPositionInLine() + 1) + ": " + message);
    }

    private Type type(String spelling) {
        return Arrays.stream(Type.values()).filter(t -> t.bhash.equals(spelling)).findFirst().orElseThrow();
    }

    private String program(ProgramContext program) {
        functions.put(Identifiers.key("पढ़ो"), new Function("BhashRuntime.readInteger", Type.INT, List.of()));
        for (var declaration : program.functionDecl()) {
            String name = Identifiers.key(declaration.IDENTIFIER().getText());
            var parameters = new ArrayList<Type>();
            if (declaration.parameters() != null) {
                for (var p : declaration.parameters().parameter()) parameters.add(type(p.valueType().getText()));
            }
            var function = new Function("f" + functions.size(), type(declaration.returnType().getText()), List.copyOf(parameters));
            if (functions.putIfAbsent(name, function) != null)
                throw error(declaration, "Duplicate or reserved function: " + declaration.IDENTIFIER().getText());
        }
        var main = functions.get(Identifiers.key("मुख्य"));
        if (main == null || main.result != Type.VOID || !main.parameters.isEmpty())
            throw error(program, "Executable requires exactly one कार्य मुख्य() -> शून्य.");
        output.append("public final class BhashProgram {\n");
        output.append("public static void main(String[] args) { try { ").append(main.java)
                .append("(); } catch (BhashRuntime.Failure | ArithmeticException e) {")
                .append(" BhashRuntime.report(e.getMessage()); System.exit(1); } }\n");
        for (var declaration : program.functionDecl()) emitFunction(declaration);
        return output.append("}\n").toString();
    }

    private void emitFunction(FunctionDeclContext declaration) {
        var function = functions.get(Identifiers.key(declaration.IDENTIFIER().getText()));
        returnType = function.result;
        scopes.push(new HashMap<>());
        output.append("static ").append(function.result.java).append(' ').append(function.java).append('(');
        if (declaration.parameters() != null) {
            int index = 0;
            for (var parameter : declaration.parameters().parameter()) {
                if (index > 0) output.append(',');
                var binding = declare(parameter, parameter.IDENTIFIER().getText(), function.parameters.get(index++), false);
                output.append("final ").append(binding.type.java).append(' ').append(binding.java);
            }
        }
        output.append(") {\n");
        var flow = statements(declaration.block());
        if (returnType != Type.VOID && flow.contains(Flow.NORMAL))
            throw error(declaration, "Function must return " + returnType.bhash + " on every path.");
        output.append("}\n");
        scopes.pop();
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

    private Binding binding(ParserRuleContext at, String name) {
        var binding = find(name);
        if (binding == null) throw error(at, "Undefined variable: " + name);
        return binding;
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
            var declaredType = type(declaration.valueType().getText());
            require(declaration, declaredType, value.type);
            var binding = declare(declaration, declaration.IDENTIFIER().getText(), declaredType, declaration.LET() != null);
            output.append(binding.mutable ? "" : "final ").append(declaredType.java).append(' ')
                    .append(binding.java).append('=').append(value.java).append(";\n");
        } else if (statement.assignment() != null) {
            var assignment = statement.assignment();
            var binding = binding(assignment, assignment.IDENTIFIER().getText());
            if (!binding.mutable) throw error(assignment, "Cannot assign to an immutable binding.");
            var value = visit(assignment.expression());
            require(assignment, binding.type, value.type);
            output.append(binding.java).append('=').append(value.java).append(";\n");
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
            for (var arg : args) valueRequired(statement, arg.type);
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
        if (expected != actual) throw error(at, "Expected " + expected.bhash + ", found " + actual.bhash + ".");
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
                java = (operator.equals("!=") ? "!" : "") + "java.util.Objects.equals(" + result.java + "," + right.java + ")";
            } else if ((operator.equals("/") || operator.equals("%")) && result.type == Type.INT) {
                // A helper gives constant-zero division the same runtime behavior as dynamic division.
                java = "BhashRuntime." + (operator.equals("/") ? "divide" : "remainder") + "(" + result.java + "," + right.java + ")";
            } else java = "(" + result.java + " " + operator + " " + right.java + ")";
            result = new Expr(type, java);
        }
        return result;
    }

    @Override public Expr visitUnary(UnaryContext ctx) {
        if (ctx.primary() != null) return visit(ctx.primary());
        String op = ctx.getChild(0).getText();
        // Accept the minimum signed literal, including parenthesized magnitude.
        String magnitude = ctx.unary().getText().replace("(", "").replace(")", "");
        if (op.equals("-") && magnitude.matches("[0-9०-९]+")) return integer(ctx, "-" + digits(magnitude));
        var value = visit(ctx.unary());
        if (op.equals("!")) require(ctx, Type.BOOL, value.type);
        else numeric(ctx, value.type);
        return new Expr(value.type, "(" + op + value.java + ")");
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
        if (ctx.IDENTIFIER() != null) {
            String name = ctx.IDENTIFIER().getText();
            if (ctx.LPAREN() == null) {
                var binding = binding(ctx, name);
                return new Expr(binding.type, binding.java);
            }
            if (find(name) != null) throw error(ctx, "Binding is not callable: " + name);
            var function = functions.get(Identifiers.key(name));
            if (function == null) throw error(ctx, "Undefined function: " + name);
            var args = arguments(ctx.arguments());
            if (args.size() != function.parameters.size()) throw error(ctx, "Wrong argument count for " + name + ".");
            for (int i = 0; i < args.size(); i++) require(ctx, function.parameters.get(i), args.get(i).type);
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
        return new Expr(Type.TEXT, "new String(new int[]{" + codepoints + "},0," + count + ")");
    }
}
