// SPDX-License-Identifier: Apache-2.0
package dev.bhash.backend.jvm;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import dev.bhash.backend.ManagedEmitter;
import dev.bhash.frontend.Identifiers;
import dev.bhash.frontend.SourceFile;
import dev.bhash.parser.BhashLexer;
import dev.bhash.parser.BhashParser;
import org.antlr.v4.runtime.*;
import org.antlr.v4.runtime.misc.Interval;

import java.io.*;
import java.lang.reflect.InvocationTargetException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Process-local notebook session. Only portable scalar values cross cell class loaders. */
public final class NotebookWorker {
    private static final String PREFIX = "भाषकक्ष";
    private static final String STORAGE = PREFIX + "संग्रह";
    private static final List<String> TYPES = List.of("पूर्णांक", "दशमलव", "तार्किक", "पाठ", "बाइट", "लघुपूर्णांक");
    private static final int MAX_FRAME = 1024 * 1024;
    private final Gson json = new Gson();
    private final DataInputStream input = new DataInputStream(System.in);
    private final DataOutputStream output = new DataOutputStream(System.out);
    private final Map<String, Slot> slots = new LinkedHashMap<>();
    private final Map<String, Definition> functions = new LinkedHashMap<>();
    private final Map<String, String> types = new LinkedHashMap<>();
    private final Set<String> imports = new LinkedHashSet<>();
    private int cellNumber;

    private record Slot(String name, String type, boolean mutable, Object value) {}
    private record Definition(String name, String result, boolean async, String source) {}
    private record Initialization(String key, String function, String expression) {}

    public static void main(String[] args) throws IOException {
        ProcessHandle.current().parent().ifPresent(parent ->
                parent.onExit().thenRun(() -> Runtime.getRuntime().halt(0)));
        new NotebookWorker().serve();
    }

    private JsonObject receive() throws IOException {
        int size = input.readInt();
        if (size < 1 || size > MAX_FRAME) throw new IOException("Invalid notebook frame length");
        byte[] data = input.readNBytes(size);
        if (data.length != size) throw new EOFException("Truncated notebook frame");
        return json.fromJson(new String(data, StandardCharsets.UTF_8), JsonObject.class);
    }

    private synchronized void send(Map<String, ?> message) throws IOException {
        byte[] data = json.toJson(message).getBytes(StandardCharsets.UTF_8);
        if (data.length > MAX_FRAME) throw new IOException("Notebook frame too large");
        output.writeInt(data.length);
        output.write(data);
        output.flush();
    }

    private void serve() throws IOException {
        send(Map.of("kind", "hello", "version", 1));
        while (true) {
            JsonObject request;
            try { request = receive(); }
            catch (EOFException end) { return; }
            try {
                String kind = request.get("kind").getAsString();
                String source = request.get("code").getAsString();
                if (kind.equals("complete")) {
                    try { parse(source); send(Map.of("kind", "complete", "status", "complete")); }
                    catch (CellSyntax error) {
                        send(Map.of("kind", "complete", "status", error.incomplete ? "incomplete" : "invalid"));
                    }
                } else if (kind.equals("names")) {
                    var matches = new TreeSet<String>();
                    slots.values().forEach(slot -> matches.add(slot.name));
                    functions.values().forEach(function -> matches.add(function.name));
                    matches.addAll(types.keySet());
                    send(Map.of("kind", "names", "matches", matches.stream()
                            .filter(name -> Identifiers.key(name).startsWith(Identifiers.key(source))).toList()));
                } else if (kind.equals("inspect")) {
                    var slot = slots.get(Identifiers.key(source));
                    if (slot == null) send(Map.of("kind", "inspect", "found", false));
                    else send(Map.of("kind", "inspect", "found", true, "text",
                            bounded(slot.name + " : " + slot.type + " = " + render(slot.value))));
                } else if (kind.equals("execute")) {
                    evaluate(source, request.get("allowStdin").getAsBoolean());
                } else throw new IllegalArgumentException("Unknown notebook request");
            } catch (Exception error) {
                Throwable cause = error instanceof InvocationTargetException invocation
                        ? invocation.getTargetException() : error;
                String message = Objects.toString(cause.getMessage(), cause.getClass().getSimpleName());
                send(Map.of("kind", "error", "message", bounded(message)));
            }
        }
    }

    private static String bounded(String value) {
        return value.length() <= 32768 ? value : value.substring(0, 32768) + "…";
    }

    private static final class CellSyntax extends IllegalArgumentException {
        final boolean incomplete;
        CellSyntax(String message, boolean incomplete) {
            super(message);
            this.incomplete = incomplete;
        }
    }

    private static BhashParser.NotebookCellContext parse(String source) {
        var lexer = new BhashLexer(CharStreams.fromString(source));
        var tokens = new CommonTokenStream(lexer);
        var parser = new BhashParser(tokens);
        var errors = new ArrayList<CellSyntax>();
        var listener = new BaseErrorListener() {
            @Override public void syntaxError(Recognizer<?, ?> recognizer, Object symbol, int line,
                    int column, String message, RecognitionException exception) {
                errors.add(new CellSyntax("cell:" + line + ":" + (column + 1) + ": " + message,
                        symbol instanceof Token token && token.getType() == Token.EOF));
            }
        };
        lexer.removeErrorListeners();
        parser.removeErrorListeners();
        lexer.addErrorListener(listener);
        parser.addErrorListener(listener);
        var cell = parser.notebookCell();
        if (!errors.isEmpty()) throw errors.get(0);
        for (var token : tokens.getTokens()) {
            if (token.getType() == BhashLexer.IDENTIFIER && Identifiers.key(token.getText()).startsWith(PREFIX))
                throw new IllegalArgumentException("Reserved notebook identifier: " + token.getText());
        }
        return cell;
    }

    private static String source(ParserRuleContext context) {
        return context.start.getInputStream().getText(
                Interval.of(context.start.getStartIndex(), context.stop.getStopIndex()));
    }

    private void evaluate(String code, boolean allowStdin) throws Exception {
        var cell = parse(code);
        boolean fullProgram = cell.packageDecl() != null || cell.notebookDeclaration().stream()
                .map(BhashParser.NotebookDeclarationContext::functionDecl)
                .filter(Objects::nonNull).anyMatch(f -> Identifiers.key(f.IDENTIFIER().getText()).equals("मुख्य"));
        if (fullProgram) {
            compileAndRun(code, new ManagedEmitter.NotebookCell(null, List.of()),
                    Map.of(), Map.of(), Map.of(), Set.of(), List.of(), allowStdin);
            return;
        }
        var nextImports = new LinkedHashSet<>(imports);
        var nextFunctions = new LinkedHashMap<>(functions);
        var nextTypes = new LinkedHashMap<>(types);
        var nextSlots = new LinkedHashMap<>(slots);
        var initializations = new ArrayList<Initialization>();
        var locals = new ArrayList<BhashParser.VariableDeclContext>();
        var declared = new HashSet<String>();
        for (var statement : cell.statement())
            if (statement.variableDecl() != null)
                throw new IllegalArgumentException("Place notebook bindings before executable statements.");
        for (var declaration : cell.importDecl()) nextImports.add(source(declaration));
        for (var item : cell.notebookDeclaration()) {
            var declaration = item.functionDecl();
            if (declaration == null) {
                String name = Identifiers.key(item.classDecl() != null ? item.classDecl().IDENTIFIER().getText()
                        : item.interfaceDecl() != null ? item.interfaceDecl().IDENTIFIER().getText()
                        : item.enumDecl().IDENTIFIER().getText());
                if (!declared.add(name)) throw new IllegalArgumentException("Duplicate declaration: " + name);
                String typeSource = source(item);
                String previous = nextTypes.putIfAbsent(name, typeSource);
                if (previous != null && !previous.equals(typeSource))
                    throw new IllegalArgumentException("Restart the kernel to change type " + name);
                continue;
            }
            String name = Identifiers.key(declaration.IDENTIFIER().getText());
            String parameters = declaration.parameters() == null ? "" : String.join(",",
                    declaration.parameters().parameter().stream().map(p -> Identifiers.key(p.valueType().getText())).toList());
            String generic = declaration.typeParameters() == null ? "" : declaration.typeParameters().getText();
            String key = name + generic + "(" + parameters + ")";
            if (!declared.add(key)) throw new IllegalArgumentException("Duplicate function: " + name);
            var definition = new Definition(name, declaration.returnType().getText(),
                    declaration.ASYNC() != null, source(item));
            var previous = functions.get(key);
            if (previous != null && (!previous.result.equals(definition.result) || previous.async != definition.async))
                throw new IllegalArgumentException("Restart the kernel to change the signature of " + name);
            nextFunctions.put(key, definition);
        }
        for (var declaration : cell.variableDecl()) {
            String name = declaration.IDENTIFIER().getText();
            String key = Identifiers.key(name);
            String type = declaration.valueType().getText();
            boolean mutable = declaration.LET() != null;
            if (!declared.add(key)) throw new IllegalArgumentException("Duplicate binding: " + name);
            if (!TYPES.contains(type) || !locals.isEmpty() || hasAsync(declaration)) {
                locals.add(declaration);
                continue;
            }
            Slot previous = slots.get(key);
            if (previous != null && (!previous.type.equals(type) || previous.mutable != mutable))
                throw new IllegalArgumentException("Restart the kernel to change the type or mutability of " + name);
            nextSlots.put(key, new Slot(previous == null ? name : previous.name, type, mutable,
                    previous == null ? null : previous.value));
            initializations.add(new Initialization(key, PREFIX + "आरम्भ" + initializations.size(), source(declaration.expression())));
        }
        if (nextSlots.size() + nextFunctions.size() + nextTypes.size() + nextImports.size() > 512
                || nextFunctions.values().stream().mapToInt(f -> f.source.length()).sum()
                + nextTypes.values().stream().mapToInt(String::length).sum() > 262144)
            throw new IllegalArgumentException("Notebook session limit reached. Restart the kernel.");
        var program = new StringBuilder();
        nextImports.forEach(line -> program.append(line).append('\n'));
        program.append("वर्ग ").append(STORAGE).append(" {\n");
        nextSlots.values().forEach(slot -> program.append("सार्वजनिक साझा ")
                .append(slot.mutable ? "मान " : "स्थिर ").append(slot.name).append(": ").append(slot.type).append(";\n"));
        program.append("}\n");
        nextTypes.values().forEach(type -> program.append(type).append('\n'));
        nextFunctions.values().forEach(function -> program.append(function.source).append('\n'));
        for (var init : initializations) program.append("फलन ").append(init.function).append("() -> ")
                .append(nextSlots.get(init.key).type).append(" { लौटाओ ").append(init.expression).append("; }\n");
        if (cell.expression() != null) for (String type : TYPES) {
            program.append("फलन ").append(PREFIX).append("फल(मूल्य:").append(type)
                    .append(") -> शून्य { लिखो(मूल्य); }\n");
        }
        boolean async = locals.stream().anyMatch(NotebookWorker::hasAsync)
                || cell.statement().stream().anyMatch(NotebookWorker::hasAsync)
                || cell.expression() != null && hasAsync(cell.expression());
        if (async) program.append("असमकालिक ");
        program.append("फलन मुख्य() -> शून्य {\n");
        locals.forEach(declaration -> program.append(source(declaration)).append('\n'));
        cell.statement().forEach(s -> program.append(source(s)).append('\n'));
        if (cell.expression() != null) program.append(PREFIX).append("फल(").append(source(cell.expression())).append(");\n");
        program.append("}\n");
        compileAndRun(program.toString(),
                new ManagedEmitter.NotebookCell(STORAGE, initializations.stream().map(Initialization::function).toList()),
                nextSlots, nextFunctions, nextTypes, nextImports, initializations, allowStdin);
    }

    private void compileAndRun(String program, ManagedEmitter.NotebookCell configuration,
            Map<String, Slot> nextSlots, Map<String, Definition> nextFunctions, Map<String, String> nextTypes,
            Set<String> nextImports, List<Initialization> initializations, boolean allowStdin) throws Exception {
        Path directory = Files.createTempDirectory("bhash-cell-");
        Path jar = directory.resolve("cell.jar");
        try {
            new JvmCompiler().compileNotebook(List.of(new SourceFile("cell-" + (++cellNumber) + ".bhash", program)),
                    configuration, jar);
            run(jar, nextSlots, nextFunctions, nextTypes, nextImports, initializations, allowStdin);
        } finally {
            try {
                Files.deleteIfExists(jar);
                Files.deleteIfExists(directory);
            } catch (IOException locked) {
                directory.toFile().deleteOnExit();
                jar.toFile().deleteOnExit();
            }
        }
    }

    private static boolean hasAsync(org.antlr.v4.runtime.tree.ParseTree tree) {
        if (tree instanceof org.antlr.v4.runtime.tree.TerminalNode node)
            return node.getSymbol().getType() == BhashLexer.AWAIT || node.getSymbol().getType() == BhashLexer.ASYNC;
        for (int i=0; i<tree.getChildCount(); i++) if (hasAsync(tree.getChild(i))) return true;
        return false;
    }

    private void run(Path jar, Map<String, Slot> nextSlots, Map<String, Definition> nextFunctions,
            Map<String, String> nextTypes, Set<String> nextImports, List<Initialization> initializations,
            boolean allowStdin) throws Exception {
        var oldOut = System.out;
        var oldErr = System.err;
        var oldIn = System.in;
        try (var loader = new URLClassLoader(new URL[]{jar.toUri().toURL()}, ClassLoader.getPlatformClassLoader())) {
            System.setOut(stream("stdout"));
            System.setErr(stream("stderr"));
            System.setIn(new InputStream() {
                private ByteArrayInputStream line = new ByteArrayInputStream(new byte[0]);
                @Override public int read() throws IOException {
                    int value = line.read();
                    if (value >= 0 || !allowStdin) return value;
                    send(Map.of("kind", "input", "prompt", "भाष> "));
                    var reply = receive();
                    if (!reply.get("kind").getAsString().equals("input")) throw new IOException("Expected notebook input");
                    if (reply.get("value").isJsonNull()) return -1;
                    line = new ByteArrayInputStream((reply.get("value").getAsString() + "\n").getBytes(StandardCharsets.UTF_8));
                    return line.read();
                }
                @Override public int read(byte[] bytes, int start, int count) throws IOException {
                    if (count == 0) return 0;
                    int first = read();
                    if (first < 0) return -1;
                    bytes[start] = (byte) first;
                    return 1 + Math.max(0, line.read(bytes, start + 1, count - 1));
                }
            });
            var entry = loader.loadClass("BhashProgram");
            var storage = (Class<?>) entry.getMethod("notebookStorage").invoke(null);
            var names = (String[]) entry.getMethod("notebookFields").invoke(null);
            @SuppressWarnings("unchecked")
            var initialized = (Set<String>) entry.getField("notebookInitialized").get(null);
            var fields = new LinkedHashMap<String, java.lang.reflect.Field>();
            int index = 0;
            for (var key : nextSlots.keySet()) {
                var field = storage.getDeclaredField(names[index++]);
                field.setAccessible(true);
                fields.put(key, field);
                if (slots.containsKey(key)) {
                    field.set(null, slots.get(key).value);
                    initialized.add(field.getName());
                }
            }
            boolean success = false;
            try {
                for (int i=0; i<initializations.size(); i++) {
                    var field = fields.get(initializations.get(i).key);
                    field.set(null, entry.getMethod("notebookInit" + i).invoke(null));
                    initialized.add(field.getName());
                }
                entry.getMethod("notebookRun").invoke(null);
                success = true;
            } finally {
                for (var item : nextSlots.entrySet()) {
                    if (!success && !slots.containsKey(item.getKey())) continue;
                    var slot = item.getValue();
                    slots.put(item.getKey(), new Slot(slot.name, slot.type, slot.mutable, fields.get(item.getKey()).get(null)));
                }
            }
            functions.putAll(nextFunctions);
            types.putAll(nextTypes);
            imports.addAll(nextImports);
            Object result = entry.getField("notebookResult").get(null);
            System.out.flush();
            System.err.flush();
            if (result != null) send(Map.of("kind", "result", "text", bounded(render(result))));
            send(Map.of("kind", "done"));
        } finally {
            System.out.flush();
            System.err.flush();
            System.setOut(oldOut);
            System.setErr(oldErr);
            System.setIn(oldIn);
        }
    }

    private PrintStream stream(String name) {
        return new PrintStream(new OutputStream() {
            private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            private int remaining = 256 * 1024;
            @Override public void write(int value) throws IOException {
                if (remaining-- <= 0) { remaining = 0; return; }
                buffer.write(value);
                if (value == '\n') flush();
            }
            @Override public void flush() throws IOException {
                if (buffer.size() == 0) return;
                send(Map.of("kind", "stream", "name", name, "text", buffer.toString(StandardCharsets.UTF_8)));
                buffer.reset();
            }
        }, false, StandardCharsets.UTF_8);
    }

    private static String render(Object value) {
        if (value == null) return "रिक्त";
        if (value instanceof Boolean flag) return flag ? "सत्य" : "असत्य";
        if (!(value instanceof Number)) return value.toString();
        var result = new StringBuilder();
        for (char c : value.toString().toCharArray())
            result.append(c >= '0' && c <= '9' ? (char) ('०' + c - '0') : c);
        return result.toString();
    }
}
