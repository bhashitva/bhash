// SPDX-License-Identifier: Apache-2.0
package dev.bhash;

import dev.bhash.ffi.ForeignManifest;
import dev.bhash.frontend.*;
import dev.bhash.backend.ProjectResolver;
import dev.bhash.backend.ManagedEmitter;
import dev.bhash.backend.CompilationException;
import dev.bhash.parser.BhashParser.*;
import com.google.gson.*;
import org.antlr.v4.runtime.tree.ParseTree;
import java.util.*;
import java.io.IOException;
import java.nio.file.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ForeignManifestTest {
    @TempDir Path directory;
    static final String CONTRACT = """
        {"schemaVersion":1,"abiVersion":1,"package":"fixture","contractVersion":"1",
         "namespace":"परीक्षण","resources":[],"artifacts":[],"functions":[
          {"owner":"परीक्षण.जोड़","name":"जोड़ो","symbol":"fixture_add","execution":"owner-sync",
           "parameters":[{"name":"पहला","type":"पूर्णांक","wire":"i64"},
                         {"name":"दूसरा","type":"पूर्णांक","wire":"i64"}],
           "return":{"type":"पूर्णांक","wire":"status+i64"}}]}
        """;
    @Test void pureTypedDiscoveryAndDeterministicIdentity() throws Exception {
        var contract = ForeignManifest.parse(CONTRACT, false);
        assertEquals("परीक्षण.जोड़.जोड़ो", contract.functions().get(0).identity());
        assertEquals("पूर्णांक", contract.functions().get(0).parameters().get(0).type());
        assertEquals(contract.fingerprint(), ForeignManifest.parse(CONTRACT.replace("\"schemaVersion\":1,", "")
            .replace("\"abiVersion\":1", "\"abiVersion\":1,\"schemaVersion\":1"), false).fingerprint());
        assertNotEquals(contract.fingerprint(), ForeignManifest.parse(CONTRACT.replace("status+i64", "status+i32"), false).fingerprint());
    }
    @Test void rejectsDuplicatePropertiesAndUnknownExecutionSemantics() {
        reject(CONTRACT.replace("\"abiVersion\":1", "\"abiVersion\":1,\"abiVersion\":1"));
        reject(CONTRACT.replace("\"abiVersion\":1", "\"abiVersion\":1,\"unsafe\":1"));
        reject(CONTRACT.replace("owner-sync", "worker"));
        reject(CONTRACT.replace("\"schemaVersion\":1", "\"schemaVersion\":2"));
        reject(CONTRACT.replace("\"schemaVersion\":1", "\"schemaVersion\":\"1\""));
        reject(CONTRACT + " {}");
    }
    @Test void rejectsUnsafeConversionsNamesAndNamespaceClaims() {
        reject(CONTRACT.replace("\"wire\":\"i64\"", "\"wire\":\"pointer\""));
        reject(CONTRACT.replace("\"wire\":\"i64\"", "\"wire\":\"utf8\""));
        reject(CONTRACT.replace("\"type\":\"पूर्णांक\"", "\"type\":\"पाठ\""));
        reject(CONTRACT.replace("दूसरा", "पहला"));
        reject(CONTRACT.replace("fixture_add", "fixture-add"));
        reject(CONTRACT.replace("परीक्षण", "मानक"));
        reject(CONTRACT.replace("\"type\":\"पूर्णांक\"", "\"type\":\"पूर्ण ांक\""));
        reject(CONTRACT.replace("\"type\":\"पूर्णांक\"", "\"type\":\"परीक्षण.अ\\u200dब\""));
        reject(CONTRACT.replace("\"type\":\"पूर्णांक\"", "\"type\":\"पूर्णांक[]\""));
    }
    @Test void requiresExplicitCopiedBufferOwnershipAndMatchingRelease() throws Exception {
        String bytes = CONTRACT.replace("\"type\":\"पूर्णांक\",\"wire\":\"i64\"",
            "\"type\":\"बाइटबफ़र\",\"wire\":\"bytes\",\"ownership\":\"borrow-call\",\"marshal\":\"copy\"")
            .replace("\"type\":\"पूर्णांक\",\"wire\":\"status+i64\"",
            "\"type\":\"बाइटबफ़र\",\"wire\":\"status+owned-bytes\",\"release\":\"fixture_release\"");
        assertEquals("bytes", ForeignManifest.parse(bytes, false).functions().get(0).parameters().get(0).wire());
        reject(bytes.replace(",\"marshal\":\"copy\"", ""));
        reject(bytes.replace("borrow-call", "retain"));
        reject(bytes.replace(",\"release\":\"fixture_release\"", ""));
    }
    @Test void equivalentNamesCannotCreateDifferentParameters() {
        reject(CONTRACT.replace("पहला", "फ़ल").replace("दूसरा", "फ़ल"));
    }
    @Test void releaseSymbolsRejectReservedRuntimeEntriesAndIncompatibleResourceSignatures() throws Exception {
        var json = JsonParser.parseString(CONTRACT).getAsJsonObject();
        var resource = new JsonObject();
        resource.addProperty("name", "परीक्षण.हैंडल");
        resource.addProperty("kind", "counter");
        resource.addProperty("release", "fixture_release");
        json.getAsJsonArray("resources").add(resource);
        var result = json.getAsJsonArray("functions").get(0).getAsJsonObject().getAsJsonObject("return");
        result.addProperty("type", "बाइटबफ़र");
        result.addProperty("wire", "status+owned-bytes");
        result.addProperty("release", "fixture_release");
        reject(json.toString()); // No owned-handle-returning function is needed to expose the conflict.
        result.addProperty("release", "bhffi_v1_context_create");
        reject(json.toString());
        result.addProperty("release", "bhffi_v1_bytes_release");
        assertDoesNotThrow(() -> ForeignManifest.parse(json.toString(), false));
        resource.addProperty("release", "bhffi_v1_bytes_release");
        reject(json.toString());
        resource.addProperty("release", "bhffi_v1_context_shutdown");
        reject(json.toString());
    }
    @Test void discoversForeignCallsInsideCatchHandlersAndSelectionCases() throws Exception {
        var module = ForeignManifest.parse(CONTRACT, false);
        for (String body : List.of(
                "प्रयास { फेंको नया अपवाद(\"दोष\"); } पकड़ो (द: अपवाद) { लिखो(जोड़.जोड़ो(२,३)); }",
                "चयन (१) { विकल्प १: लिखो(जोड़.जोड़ो(२,३)); विराम; शेष: लिखो(०); }")) {
            var core = ManagedEmitter.core(List.of(new SourceFile("binding.json", "", false, module),
                new SourceFile("main.bhash", "आयात परीक्षण.जोड़; फलन मुख्य()->शून्य {" + body + "}")));
            assertTrue(core.foreignRuntime(), body);
            assertEquals(1, core.foreignCalls().size(), body);
        }
    }
    @Test void errorMappingMessagesAreEscapedInBothManagedTargets() throws Exception {
        var json = JsonParser.parseString(CONTRACT).getAsJsonObject();
        var result = json.getAsJsonArray("functions").get(0).getAsJsonObject().getAsJsonObject("return");
        result.addProperty("type", "मानक.परिणाम<पूर्णांक,परीक्षण.दोष>");
        String message = "पहली\nदूसरी\r\n\t\"पंक्ति\"\\\u0085\u2028\u2029";
        var mapping = new JsonObject();
        mapping.addProperty("status", 1);
        mapping.addProperty("code", "अमान्य");
        mapping.addProperty("message", message);
        var errors = new JsonArray(); errors.add(mapping); result.add("errors", errors);
        var module = ForeignManifest.parse(json.toString(), false);
        var sources = List.of(new SourceFile("binding.json", "", false, module),
            new SourceFile("types.bhash", "संकुल परीक्षण; सार्वजनिक परिगणना दोषकोड { अमान्य } सार्वजनिक अभिलेख दोष(कोड: दोषकोड, संदेश: पाठ);"),
            new SourceFile("main.bhash", "आयात परीक्षण.जोड़; फलन मुख्य()->शून्य { स्थिर फल = जोड़.जोड़ो(२,३); }"));
        for (var target : List.of(dev.bhash.backend.Target.JVM, dev.bhash.backend.Target.CLR)) {
            String generated = ManagedEmitter.emit(sources, target);
            assertTrue(generated.contains("पहली\\nदूसरी\\r\\n\\t\\\"पंक्ति\\\"\\\\\\u0085\\u2028\\u2029"), target.toString());
            assertFalse(generated.contains(message), target.toString());
        }
    }
    @Test void qualifiedClassCannotMasqueradeAsEnumWire()throws Exception {
        String json=CONTRACT.replace("पूर्णांक","परीक्षण.नकली").replace("status+i64","status+i32").replace("\"wire\":\"i64\"","\"wire\":\"i32\"");
        var module=ForeignManifest.parse(json,false);
        var sources=List.of(new SourceFile("binding.json","",false,module),
            new SourceFile("fake.bhash","संकुल परीक्षण; सार्वजनिक वर्ग नकली {}"),
            new SourceFile("main.bhash","फलन मुख्य()->शून्य {}"));
        var error=assertThrows(CompilationException.class,()->ManagedEmitter.check(sources));
        assertTrue(error.getMessage().contains("BH8101"),error.getMessage());
    }
    @Test void artifactHashesAreLockedWithoutCreatingASelfReferentialBinaryIdentity() throws Exception {
        String artifact = "{\"target\":\"windows-x64\",\"path\":\"provider.zip\",\"sha256\":\"" + "0".repeat(64)
            + "\",\"sourceSha256\":\"" + "1".repeat(64) + "\",\"license\":\"LICENSE\"}";
        var empty = ForeignManifest.parse(CONTRACT, false);
        var first = ForeignManifest.parse(CONTRACT.replace("\"artifacts\":[]", "\"artifacts\":[" + artifact + "]"), false);
        var second = ForeignManifest.parse(CONTRACT.replace("\"artifacts\":[]", "\"artifacts\":[" + artifact.replace("0".repeat(64), "2".repeat(64)) + "]"), false);
        assertEquals(empty.fingerprint(), first.fingerprint());
        assertEquals(first.fingerprint(), second.fingerprint());
        assertNotEquals(first.inventoryFingerprint(), second.inventoryFingerprint());
    }
    @Test void typedDeclarationsKeepTheirIdentityThroughPackageResolution() throws Exception {
        var manifest = ForeignManifest.parse(CONTRACT, false);
        var declarations = new SourceFile("fixture/bhash.ffi.json", "", false, manifest);
        var original = declarations.parse().tree().classDecl(0).classMember(0).functionDecl();
        assertNull(original.block());
        assertEquals("fixture@1:परीक्षण.जोड़.जोड़ो", ((LocatedToken)original.start).foreign().identity());
        var resolved = ProjectResolver.resolve(List.of(declarations,
            new SourceFile("main.bhash", "आयात परीक्षण.जोड़; फलन मुख्य() -> शून्य { लिखो(जोड़.जोड़ो(२,३)); }")));
        var found = new ArrayList<FunctionDeclContext>();
        collect(resolved.tree(), found);
        assertEquals(1, found.size());
        assertEquals(((LocatedToken)original.start).foreign(), ((LocatedToken)found.get(0).start).foreign());
        assertNull(found.get(0).block());
    }
    static void collect(ParseTree tree, List<FunctionDeclContext> found) {
        if (tree instanceof FunctionDeclContext function && function.start instanceof LocatedToken token && token.foreign() != null) found.add(function);
        for (int i = 0; i < tree.getChildCount(); i++) collect(tree.getChild(i), found);
    }
    @Test void projectRequiresExplicitVersionTwoLockAndPinsTheBindingContract() throws Exception {
        Files.createDirectories(directory.resolve("src"));
        Files.writeString(directory.resolve("src/main.bhash"), "फलन मुख्य() -> शून्य {}");
        Files.writeString(directory.resolve("bhash.project"), "name=fixture\nffi=bhash.ffi.json\n");
        Path binding = directory.resolve("bhash.ffi.json");
        Files.writeString(binding, CONTRACT);
        assertThrows(IOException.class, () -> ProjectLoader.load(directory));
        SourcePackages.manage(directory, "lock", true);
        String lock = Files.readString(directory.resolve("bhash.lock"));
        assertTrue(lock.contains("\"lockVersion\": 2"));
        assertTrue(lock.contains("bindingsSha256"));
        var project = ProjectLoader.load(directory);
        assertEquals(1, project.sources().stream().filter(s -> s.foreign() != null).count());
        assertThrows(IOException.class, () -> project.protectOutput(binding, false));
        SourcePackages.manage(directory, "verify", true);
        Files.writeString(binding, CONTRACT.replace("fixture_add", "fixture_changed"));
        assertThrows(IOException.class, () -> SourcePackages.manage(directory, "verify", true));
        assertEquals(lock, Files.readString(directory.resolve("bhash.lock")));
        SourcePackages.manage(directory, "lock", true);
        assertNotEquals(lock, Files.readString(directory.resolve("bhash.lock")));
    }
    @Test void semanticAnalysisChecksExternalSignaturesWithoutAnArtifactOrNativeExecution() throws Exception {
        var module = ForeignManifest.parse(CONTRACT, false);
        var declaration = new SourceFile("fixture/bhash.ffi.json", "", false, module);
        ManagedEmitter.check(List.of(declaration, new SourceFile("main.bhash",
            "आयात परीक्षण.जोड़; फलन मुख्य() -> शून्य { लिखो(जोड़.जोड़ो(२,३)); }")));
        assertThrows(CompilationException.class, () -> ManagedEmitter.check(List.of(declaration, new SourceFile("bad.bhash",
            "आयात परीक्षण.जोड़; फलन मुख्य() -> शून्य { लिखो(जोड़.जोड़ो(\"अ\",३)); }"))));
        var error = assertThrows(CompilationException.class, () -> ManagedEmitter.check(List.of(declaration, new SourceFile("bad.bhash",
            "आयात परीक्षण.जोड़; फलन मुख्य() -> शून्य { स्थिर क = नया जोड़(); }"))));
        assertTrue(error.getMessage().contains("BH8104"),error.getMessage());
        var core = ManagedEmitter.core(List.of(declaration, new SourceFile("main.bhash",
            "आयात परीक्षण.जोड़; फलन मुख्य() -> शून्य { लिखो(जोड़.जोड़ो(२,३)); }")));
        assertTrue(core.functions().stream().anyMatch(f -> f.body().toString().contains("ForeignCall")));
    }
    static void reject(String json) { assertThrows(IOException.class, () -> ForeignManifest.parse(json, false)); }
}
