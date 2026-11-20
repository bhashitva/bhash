// SPDX-License-Identifier: Apache-2.0
package dev.bhash.ffi;

import com.google.gson.*;
import com.google.gson.stream.*;
import dev.bhash.frontend.*;
import java.io.*;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.util.*;

/** Declarative contracts only: reading a binding never loads or executes native code. */
public record ForeignManifest(String packageName, String contractVersion, String namespace,
        List<Function> functions, List<Resource> resources, List<Artifact> artifacts, String fingerprint, String inventoryFingerprint, Path packageRoot) {
    public ForeignManifest at(Path root) { return new ForeignManifest(packageName,contractVersion,namespace,functions,resources,artifacts,fingerprint,inventoryFingerprint,root.toAbsolutePath().normalize()); }
    public record Parameter(String name, String type, String wire, String ownership, Long minimum, Long maximum) {}
    public record Error(int status, String code, String message) {}
    public record Result(String type, String wire, String release, String resource, List<Error> errors) {}
    public record Function(String owner, String name, String symbol, List<Parameter> parameters, Result result) {
        public String identity() { return owner + "." + name; }
    }
    public record Resource(String name, String kind, String release) {}
    public record Artifact(String target, String path, String sha256, String sourceSha256, String license,String kind,String url) {
        public Artifact(String target,String path,String sha256,String sourceSha256,String license){this(target,path,sha256,sourceSha256,license,"static");}
        public Artifact(String target,String path,String sha256,String sourceSha256,String license,String kind){this(target,path,sha256,sourceSha256,license,kind,"");}
    }
    private static final Set<String> TARGETS = Set.of("linux-x64", "windows-x64", "macos-arm64");
    private static final Set<String> WIRES = Set.of("i64", "i32", "u8", "length", "bool", "f64", "utf8", "bytes", "handle");
    private static final Set<String> RESULTS = Set.of("status", "status+i64", "status+i32", "status+u8", "status+bool",
            "status+f64", "status+owned-utf8", "status+owned-bytes", "status+owned-handle");

    public static ForeignManifest read(Path path, boolean bundled) throws IOException {
        if (Files.isSymbolicLink(path) || Files.size(path) > 262144) throw failure("Binding manifest must be a regular file of at most 256 KiB.");
        return parse(Files.readString(path, StandardCharsets.UTF_8), bundled).at(path.toAbsolutePath().getParent());
    }

    public static ForeignManifest parse(String text, boolean bundled) throws IOException {
        if (text.length() > 262144 || text.getBytes(StandardCharsets.UTF_8).length > 262144) throw failure("Binding manifest exceeds 256 KiB.");
        JsonObject root;
        try (var reader = new JsonReader(new StringReader(text))) {
            reader.setStrictness(Strictness.STRICT);
            root = object(read(reader, 0));
            if (reader.peek() != JsonToken.END_DOCUMENT) throw failure("Trailing JSON data.");
        } catch (IllegalArgumentException | IllegalStateException malformed) {
            throw failure("Invalid binding JSON: " + malformed.getMessage());
        } catch (IOException malformed) {
            if (malformed.getMessage() != null && malformed.getMessage().startsWith("[BH8101]")) throw malformed;
            throw failure("Invalid binding JSON: " + malformed.getMessage());
        }
        fields(root, "schemaVersion", "abiVersion", "package", "contractVersion", "namespace", "functions", "resources", "artifacts");
        if (number(root.get("schemaVersion")) != 1 || number(root.get("abiVersion")) != 1) throw failure("Unsupported binding schema or ABI version.");
        String packageName = string(root, "package"), version = string(root, "contractVersion");
        if (!packageName.matches("[a-z][a-z0-9-]{0,63}") || !version.matches("[1-9][0-9]{0,8}")) throw failure("Invalid binding package or contract version.");
        String namespace = qualified(string(root, "namespace"));
        if (!bundled && (namespace.equals("मानक") || namespace.startsWith("मानक."))) throw failure("External bindings cannot own the reserved मानक namespace.");
        var resources = new ArrayList<Resource>();
        var resourceNames = new HashSet<String>();
        var kinds = new HashSet<String>();
        var symbols = new HashSet<String>();
        for (JsonElement element : array(root, "resources")) {
            JsonObject item = object(element); fields(item, "name", "kind", "release");
            String name = qualified(string(item, "name")), kind = string(item, "kind"), release = symbol(string(item, "release"));
            if (!name.startsWith(namespace + ".") || name.substring(namespace.length() + 1).contains(".") || !resourceNames.add(name) || !kinds.add(kind)
                    || !kind.matches("[a-z][a-z0-9-]{0,63}") || !symbols.add(release)) throw failure("Duplicate or invalid resource declaration: " + name);
            resources.add(new Resource(name, kind, release));
        }
        var functions = new ArrayList<Function>();
        var identities = new HashSet<String>();
        for (JsonElement element : array(root, "functions")) {
            JsonObject item = object(element);
            fields(item, "owner", "name", "symbol", "execution", "parameters", "return");
            String owner = qualified(string(item, "owner")), name = identifier(string(item, "name"));
            if(resourceNames.contains(owner) && name.equals("बंदकरो"))throw failure("Resource close is supplied by the runtime: "+owner+".बंदकरो");
            String entry = symbol(string(item, "symbol"));
            if (entry.startsWith("bhffi_v1_")) throw failure("External functions cannot replace the binding runtime ABI: " + entry);
            if (!owner.startsWith(namespace + ".") || owner.substring(namespace.length() + 1).contains(".") || !identities.add(owner + "." + name) || !symbols.add(entry)) throw failure("Duplicate or invalid external declaration: " + owner + "." + name);
            if (!string(item, "execution").equals("owner-sync")) throw failure("Only owner-sync execution is supported by FFI v1.");
            var parameters = new ArrayList<Parameter>();
            var names = new HashSet<String>();
            for (JsonElement argument : array(item, "parameters")) {
                JsonObject parameter = object(argument);
                fields(parameter, "name", "type", "wire", "ownership", "marshal", "range");
                String parameterName = identifier(string(parameter, "name"));
                if (!names.add(parameterName)) throw failure("Equivalent parameter names: " + parameterName);
                String type = type(string(parameter, "type")), wire = string(parameter, "wire");
                parameter.addProperty("type",type);
                if (!WIRES.contains(wire)) throw failure("Unsupported wire type: " + wire);
                String ownership = optional(parameter, "ownership");
                boolean copied = wire.equals("utf8") || wire.equals("bytes");
                if (copied) {
                    if (!ownership.equals("borrow-call") || !optional(parameter, "marshal").equals("copy")) throw failure("Text/bytes require borrow-call ownership and copy marshaling.");
                } else if (wire.equals("handle")) {
                    if (!ownership.equals("borrow-call") || parameter.has("marshal") || !resourceNames.contains(type)) throw failure("Handle parameter requires a declared resource and borrow-call ownership.");
                } else if (!ownership.isEmpty() || parameter.has("marshal")) throw failure("Scalar parameters cannot declare ownership/marshaling.");
                validateWire(type, wire);
                Long minimum = null, maximum = null;
                if (parameter.has("range")) {
                    JsonArray range = array(parameter, "range");
                    if (range.size() != 2 || !Set.of("i64", "i32", "u8", "length").contains(wire)) throw failure("Range requires two integer endpoints and an integer wire.");
                    minimum = number(range.get(0)); maximum = number(range.get(1));
                    if (minimum > maximum || wire.equals("i32") && (minimum < Integer.MIN_VALUE || maximum > Integer.MAX_VALUE)
                            || wire.equals("u8") && (minimum < 0 || maximum > 255)
                            || wire.equals("length") && (minimum < 0 || maximum > 16777216)) throw failure("Invalid wire range.");
                }
                parameters.add(new Parameter(parameterName, type, wire, ownership, minimum, maximum));
            }
            if (parameters.size() > 32) throw failure("FFI v1 permits at most 32 parameters.");
            JsonObject result = object(item.get("return")); fields(result, "type", "wire", "release", "resource", "errors");
            String resultType = type(string(result, "type")), wire = string(result, "wire");
            result.addProperty("type",resultType);
            String release = optional(result, "release"), resource = optional(result, "resource");
            if (!RESULTS.contains(wire)) throw failure("Unsupported result wire: " + wire);
            String payloadType = resultType;
            if (resultType.startsWith("मानक.परिणाम<")) {
                int nesting = 0, comma = -1;
                for (int i = resultType.indexOf('<') + 1; i < resultType.length(); i++) {
                    char c = resultType.charAt(i);
                    if (c == '<') nesting++; else if (c == '>') nesting--; else if (c == ',' && nesting == 0) { comma = i; break; }
                }
                if (comma < 0) throw failure("Result requires payload and error type arguments.");
                payloadType = resultType.substring(resultType.indexOf('<') + 1, comma);
            }
            if (!wire.equals("status")) {
                String payloadWire = wire.substring("status+".length()).replace("owned-", "");
                validateWire(payloadType, payloadWire);
                if (payloadWire.equals("handle") && !resourceNames.contains(payloadType)) throw failure("Handle result must name a declared resource.");
            } else if (!payloadType.equals("शून्य") && !payloadType.equals("मानक.इकाई")) throw failure("A status-only result requires शून्य or मानक.इकाई.");
            if (wire.startsWith("status+owned-")) {
                symbol(release);
                if (wire.equals("status+owned-handle")) {
                    resource = qualified(resource);
                    String selectedResource = resource;
                    if (!payloadType.equals(resource) || resources.stream().noneMatch(r -> r.name().equals(selectedResource) && r.release().equals(release))) throw failure("Owned handle must identify its declared resource and matching release.");
                } else if (!resource.isEmpty()) throw failure("Only owned handles declare a resource kind.");
            } else if (!release.isEmpty() || !resource.isEmpty()) throw failure("Only owned results declare release/resource.");
            var errors = new ArrayList<Error>();
            var statuses = new HashSet<Integer>();
            if (result.has("errors")) for (var mapping : array(result, "errors")) {
                var error = object(mapping); fields(error,"status","code","message");
                long status = number(error.get("status"));
                if (status < 1 || status == 7 || status > Integer.MAX_VALUE || !statuses.add((int)status)) throw failure("Error statuses must be unique positive int32 values; 7 is reserved for bridge contract failures.");
                String code=identifier(string(error,"code"));error.addProperty("code",code);
                errors.add(new Error((int)status,code,string(error,"message")));
            }
            if (resultType.startsWith("मानक.परिणाम<") != !errors.isEmpty()) throw failure("Typed Result requires explicit error mappings; other returns cannot declare them.");
            functions.add(new Function(owner, name, entry, List.copyOf(parameters), new Result(resultType, wire, release, resource,List.copyOf(errors))));
        }
        if (functions.isEmpty()) throw failure("A binding must declare at least one function.");
        var releaseTypes = new HashMap<String,String>();
        for (var function : functions) if (!function.result().release().isEmpty()) {
            String release = function.result().release();
            String signature = function.result().wire().equals("status+owned-handle") ? "handle" : "bytes";
            String previous = releaseTypes.putIfAbsent(release, signature);
            if (functions.stream().anyMatch(f -> f.symbol().equals(release)) || previous != null && !previous.equals(signature))
                throw failure("Release entry point has incompatible wire signatures: " + release);
        }
        var artifacts = new ArrayList<Artifact>();
        var targets = new HashSet<String>();
        for (JsonElement element : array(root, "artifacts")) {
            JsonObject item = object(element); fields(item, "target", "path", "sha256", "sourceSha256", "license", "kind", "url");
            String target = string(item, "target");
            String kind=item.has("kind")?string(item,"kind"):"static";
            if (!Set.of("static","shared").contains(kind) || !TARGETS.contains(target) || !targets.add(target+":"+kind)) throw failure("Unsupported or duplicate artifact target/kind: " + target+"/"+kind);
            String url=optional(item,"url");if(!url.isEmpty())downloadUri(url);
            artifacts.add(new Artifact(target, relative(string(item, "path")), hash(string(item, "sha256")),
                    hash(string(item, "sourceSha256")), relative(string(item, "license")),kind,url));
        }
        // Canonical object ordering and normalized public names make semantically equivalent
        // spelling/key order identical. Array order remains part of the declared contract.
        String inventory = new Gson().toJson(canonical(root));
        // Artifact hashes must not participate in generated C symbol identity:
        // hashing a binary containing its own hash-derived symbol is circular.
        JsonObject contract = root.deepCopy(); contract.remove("artifacts");
        String canonical = new Gson().toJson(canonical(contract));
        return new ForeignManifest(packageName, version, namespace, List.copyOf(functions), List.copyOf(resources), List.copyOf(artifacts), sha(canonical), sha(inventory),null);
    }
    private static void validateWire(String type, String wire) throws IOException {
        String expected = switch (wire) { case "utf8" -> "पाठ"; case "bytes" -> Identifiers.key("बाइटबफ़र");
            case "i64", "length" -> "पूर्णांक"; case "u8" -> "बाइट"; case "bool" -> "तार्किक"; case "f64" -> "दशमलव"; default -> null; };
        if (expected != null && !type.equals(expected)) throw failure("Incompatible Bhash/wire conversion: " + type + " / " + wire);
        if (wire.equals("i32") && !type.equals("पूर्णांक") && !type.equals("लघुपूर्णांक") && !type.contains(".")) throw failure("i32 requires an integer or qualified enum type.");
    }
    private static String type(String spelling) throws IOException {
        if(spelling.length()>2048 || spelling.indexOf('(')>=0 || spelling.indexOf(')')>=0 || spelling.indexOf('[')>=0 || spelling.indexOf(']')>=0)throw failure("Unsupported or oversized external type: "+spelling);
        int nesting=0;for(int i=0;i<spelling.length();i++)if(spelling.charAt(i)=='<' && ++nesting>8)throw failure("External type nesting exceeds 8.");else if(spelling.charAt(i)=='>')nesting--;
        var issues=new ArrayList<String>();
        var listener=new org.antlr.v4.runtime.BaseErrorListener(){
            @Override public void syntaxError(org.antlr.v4.runtime.Recognizer<?,?> source,Object token,int line,int column,String message,org.antlr.v4.runtime.RecognitionException error){issues.add(message);}
        };
        var lexer=new dev.bhash.parser.BhashLexer(org.antlr.v4.runtime.CharStreams.fromString(spelling,"binding-type"));lexer.removeErrorListeners();lexer.addErrorListener(listener);
        var parser=new dev.bhash.parser.BhashParser(new org.antlr.v4.runtime.CommonTokenStream(lexer));parser.removeErrorListeners();parser.addErrorListener(listener);
        var value=parser.returnType();
        if(!issues.isEmpty() || parser.getCurrentToken().getType()!=org.antlr.v4.runtime.Token.EOF)throw failure("Invalid external type: "+spelling);
        String normalized=Identifiers.key(value.getText());int[] index={0};parseType(normalized,index,0);
        if(index[0]!=normalized.length())throw failure("Unsupported external type: "+spelling);
        return normalized;
    }
    private static void parseType(String text, int[] cursor, int depth) throws IOException {
        if (depth > 8) throw failure("External type nesting exceeds 8.");
        int start = cursor[0];
        while (cursor[0] < text.length() && ".<>,?".indexOf(text.charAt(cursor[0])) < 0) cursor[0]++;
        String first = text.substring(start, cursor[0]);
        if (!Set.of("पूर्णांक", "लघुपूर्णांक", "बाइट", "तार्किक", "दशमलव", "पाठ", "शून्य", "सर्ववस्तु", Identifiers.key("बाइटबफ़र")).contains(first)) identifier(first);
        while (cursor[0] < text.length() && text.charAt(cursor[0]) == '.') {
            cursor[0]++; start = cursor[0];
            while (cursor[0] < text.length() && ".<>,?".indexOf(text.charAt(cursor[0])) < 0) cursor[0]++;
            identifier(text.substring(start, cursor[0]));
        }
        if (cursor[0] < text.length() && text.charAt(cursor[0]) == '<') {
            cursor[0]++;
            parseType(text, cursor, depth + 1);
            while (cursor[0] < text.length() && text.charAt(cursor[0]) == ',') { cursor[0]++; parseType(text, cursor, depth + 1); }
            if (cursor[0] >= text.length() || text.charAt(cursor[0]++) != '>') throw failure("Unclosed external generic type.");
        }
        // Nullability is expressed by Bhash type syntax; copied legacy-null values
        // are still validated at the bridge before accessing their storage.
        if (cursor[0] < text.length() && text.charAt(cursor[0]) == '?') cursor[0]++;
    }
    private static String qualified(String value) throws IOException {
        StringJoiner result = new StringJoiner(".");
        for (String part : value.split("\\.", -1)) result.add(identifier(part));
        return result.toString();
    }
    private static String identifier(String value) throws IOException {
        // Use the actual lexer, including contextual joiner and keyword validation.
        var parsed = Frontend.parse("binding-name", "फलन " + value + "() -> शून्य {}");
        if (!parsed.valid() || parsed.tree().functionDecl().size() != 1
                || !parsed.tree().functionDecl(0).IDENTIFIER().getText().equals(value)) throw failure("Invalid external identifier: " + value);
        return Identifiers.key(value);
    }
    private static String symbol(String value) throws IOException {
        if (!value.matches("[A-Za-z_][A-Za-z0-9_]{0,127}")) throw failure("Invalid C entry point: " + value);
        return value;
    }
    private static String relative(String value) throws IOException {
        if (value.isBlank() || value.startsWith("/") || value.contains("\\") || value.contains(":") || Arrays.asList(value.split("/", -1)).stream().anyMatch(p -> p.isEmpty() || p.equals(".") || p.equals(".."))) throw failure("Artifact paths must stay within their package: " + value);
        return value;
    }
    private static String hash(String value) throws IOException {
        if (!value.matches("[a-f0-9]{64}")) throw failure("Expected an exact lowercase SHA-256."); return value;
    }
    public static java.net.URI downloadUri(String value)throws IOException{
        try{var uri=java.net.URI.create(value);if(!"https".equals(uri.getScheme()) || uri.getHost()==null || uri.getRawUserInfo()!=null || uri.getRawFragment()!=null)throw failure("Artifact downloads require HTTPS, without embedded credentials or fragments.");return uri;}
        catch(IllegalArgumentException invalid){throw failure("Invalid artifact download URL.");}
    }
    private static String string(JsonObject object, String name) throws IOException {
        JsonElement value = object.get(name);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString() || value.getAsString().isEmpty()) throw failure("Expected nonempty string: " + name);
        return value.getAsString();
    }
    private static String optional(JsonObject object, String name) throws IOException { return object.has(name) ? string(object, name) : ""; }
    private static long number(JsonElement value) throws IOException {
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) throw failure("Expected integer.");
        try { return value.getAsBigDecimal().longValueExact(); } catch (ArithmeticException invalid) { throw failure("Integer outside signed 64-bit range."); }
    }
    private static JsonObject object(JsonElement value) throws IOException {
        if (value == null || !value.isJsonObject()) throw failure("Expected JSON object."); return value.getAsJsonObject();
    }
    private static JsonArray array(JsonObject object, String name) throws IOException {
        if (!object.has(name) || !object.get(name).isJsonArray()) throw failure("Expected array: " + name); return object.getAsJsonArray(name);
    }
    private static void fields(JsonObject object, String... allowed) throws IOException {
        Set<String> names = Set.of(allowed);
        for (String name : object.keySet()) if (!names.contains(name)) throw failure("Unknown binding property: " + name);
    }
    private static JsonElement read(JsonReader reader, int depth) throws IOException {
        if (depth > 24) throw failure("Binding JSON nesting exceeds 24.");
        switch (reader.peek()) {
            case BEGIN_OBJECT: {
                var value = new JsonObject(); reader.beginObject();
                while (reader.hasNext()) { String name = reader.nextName(); if (value.has(name)) throw failure("Duplicate binding property: " + name); value.add(name, read(reader, depth + 1)); }
                reader.endObject(); return value;
            }
            case BEGIN_ARRAY: {
                var value = new JsonArray(); reader.beginArray(); while (reader.hasNext()) value.add(read(reader, depth + 1)); reader.endArray(); return value;
            }
            case STRING: return new JsonPrimitive(reader.nextString());
            case NUMBER: return new JsonPrimitive(new BigDecimal(reader.nextString()));
            default: throw failure("Binding JSON permits objects, arrays, strings and numbers only.");
        }
    }
    private static JsonElement canonical(JsonElement value) {
        if (value.isJsonObject()) {
            var result = new JsonObject();
            new TreeSet<>(value.getAsJsonObject().keySet()).forEach(k -> {
                JsonElement field = value.getAsJsonObject().get(k);
                if (Set.of("name", "owner", "namespace", "type", "resource").contains(k) && field.isJsonPrimitive() && field.getAsJsonPrimitive().isString()) {
                    String normalized = Identifiers.key(field.getAsString());
                    result.addProperty(k, k.equals("type") ? normalized.replace(" ", "") : normalized);
                } else result.add(k, canonical(field));
            }); return result;
        }
        if (value.isJsonArray()) { var result = new JsonArray(); value.getAsJsonArray().forEach(v -> result.add(canonical(v))); return result; }
        if(value.isJsonPrimitive() && value.getAsJsonPrimitive().isNumber())return new JsonPrimitive(value.getAsBigDecimal().toBigIntegerExact());
        return value;
    }
    private static String sha(String text) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }
    private static IOException failure(String detail) { return new IOException("[BH8101] बाहरी पुस्तकालय का प्रकारयुक्त अनुबंध जाँचें। — " + detail); }
}
