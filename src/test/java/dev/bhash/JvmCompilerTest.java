package dev.bhash;

import dev.bhash.backend.jvm.CompilationException;
import dev.bhash.backend.jvm.JvmCompiler;
import dev.bhash.cli.Main;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.jar.JarFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class JvmCompilerTest {
    @TempDir Path directory;
    private int next;
    private record Execution(int exit, String output) {}

    private Path compile(String source) throws Exception {
        Path jar = directory.resolve("program-" + next++ + ".jar");
        new JvmCompiler().compile("test.bhash", source, jar);
        return jar;
    }

    private Path body(String statements) throws Exception {
        return compile("कार्य मुख्य() -> शून्य { " + statements + " }");
    }

    private Execution run(Path jar, String input) throws Exception {
        String java = Path.of(System.getProperty("java.home"), "bin",
                System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java").toString();
        var process = new ProcessBuilder(java, "-Xverify:all", "-jar", jar.toString()).redirectErrorStream(true).start();
        try {
            try (var stdin = process.getOutputStream()) { stdin.write(input.getBytes(StandardCharsets.UTF_8)); }
            assertTrue(process.waitFor(10, TimeUnit.SECONDS), "Generated program timed out");
            return new Execution(process.exitValue(), new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).replace("\r\n", "\n"));
        } finally { process.destroyForcibly(); }
    }

    @Test void interactiveFactorialCompilesAndRunsAsStandaloneJar() throws Exception {
        Path jar = compile(Files.readString(Path.of("examples/factorial-console.bhash")));
        var cases = new LinkedHashMap<String, String>();
        cases.put("0\n", "१");
        cases.put("1\n", "१");
        cases.put("5\n", "१२०");
        cases.put("५\n", "१२०");
        cases.put("  +६  \n", "७२०");
        cases.put("10\n", "३६२८८००");
        cases.put("20\n", "२४३२९०२००८१७६६४००००");
        for (var entry : cases.entrySet()) {
            var result = run(jar, entry.getKey());
            assertEquals(0, result.exit, entry.getKey());
            assertEquals("० से २० तक एक पूर्णांक लिखें:\nगुणनफल: " + entry.getValue() + "\n", result.output);
        }
        for (String input : List.of("-1\n", "21\n")) {
            var result = run(jar, input);
            assertEquals(0, result.exit);
            assertTrue(result.output.contains("कृपया ० से २० तक की संख्या दें।"));
            assertFalse(result.output.contains("गुणनफल:"));
        }
        try (var archive = new JarFile(jar.toFile())) {
            assertEquals("BhashProgram", archive.getManifest().getMainAttributes().getValue("Main-Class"));
            assertNotNull(archive.getEntry("BhashRuntime.class"));
            assertTrue(archive.stream().noneMatch(e -> e.getName().startsWith("org/antlr") || e.getName().endsWith(".java")));
        }
    }

    @Test void consoleInputRejectsInvalidMixedScriptOverflowAndEof() throws Exception {
        Path jar = body("लिखो(पढ़ो());");
        for (String input : List.of("not-a-number\n", "\n", "1२\n", "1.5\n", "1 2\n", "9223372036854775808\n", "")) {
            var result = run(jar, input);
            assertEquals(1, result.exit, input);
            assertTrue(result.output.startsWith("भाष:"), result.output);
            assertFalse(result.output.contains("Exception"));
        }
        assertEquals("-९२२३३७२०३६८५४७७५८०८\n", run(jar, "-9223372036854775808\n").output);
    }

    @Test void multipleReadsAndBothSampleProgramsExecute() throws Exception {
        var result = run(body("स्थिर पहला: पूर्णांक = पढ़ो(); लिखो(पहला + पढ़ो());"), "२\n3\n");
        assertEquals(0, result.exit);
        assertEquals("५\n", result.output);
        assertEquals("नमस्ते, विश्व!\n", run(compile(Files.readString(Path.of("examples/hello.bhash"))), "").output);
        assertEquals("१ १\n२ २\n३ ६\n४ २४\n५ १२०\n",
                run(compile(Files.readString(Path.of("examples/factorial.bhash"))), "").output);
    }

    @Test void loopsScopesForwardCallsAndReturnBranchesWork() throws Exception {
        String source = """
                कार्य मुख्य() -> शून्य {
                    मान गिनती: पूर्णांक = ०;
                    मान योग: पूर्णांक = ०;
                    जबतक (गिनती < १०) {
                        गिनती = गिनती + १;
                        यदि (गिनती == २) { जारी; }
                        यदि (गिनती == ५) { विराम; }
                        योग = योग + गिनती;
                    }
                    { स्थिर योग: पूर्णांक = ९; लिखो(योग); }
                    लिखो(योग, चुनो(०), चुनो(१), चुनो(२));
                    नमस्ते();
                    (चुनो(१));
                }
                कार्य चुनो(संख्या: पूर्णांक) -> पूर्णांक {
                    यदि (संख्या == ०) { लौटाओ १०; }
                    अन्यथा यदि (संख्या == १) { लौटाओ २०; }
                    अन्यथा { लौटाओ ३०; }
                }
                कार्य नमस्ते() -> शून्य { लिखो("ठीक"); लौटाओ; }
                """;
        var result = run(compile(source), "");
        assertEquals(0, result.exit);
        assertEquals("९\n८ १० २० ३०\nठीक\n", result.output);
    }

    @Test void shortCircuitSkipsCallsAndArgumentsEvaluateLeftToRight() throws Exception {
        String source = """
                कार्य जांच() -> तार्किक { लिखो("नहीं"); लौटाओ सत्य; }
                कार्य पहचान(संख्या: पूर्णांक) -> पूर्णांक { लिखो(संख्या); लौटाओ संख्या; }
                कार्य मुख्य() -> शून्य {
                    लिखो(असत्य && जांच(), सत्य || जांच());
                    लिखो(पहचान(१), पहचान(२));
                }
                """;
        assertEquals("असत्य सत्य\n१\n२\n१ २\n", run(compile(source), "").output);
    }

    @Test void integerBoundariesWrappingDivisionAndPrecedenceMatchSpecification() throws Exception {
        Path jar = body("""
                स्थिर न्यूनतम: पूर्णांक = -९२२३३७२०३६८५४७७५८०८;
                लिखो(न्यूनतम, ९२२३३७२०३६८५४७७५८०७ + १);
                लिखो(न्यूनतम / -१, न्यूनतम % -१);
                लिखो(-७ / ३, -७ % ३, १ + २ * ३, 00012);
                """);
        assertEquals("-९२२३३७२०३६८५४७७५८०८ -९२२३३७२०३६८५४७७५८०८\n"
                + "-९२२३३७२०३६८५४७७५८०८ ०\n-२ -१ ७ १२\n", run(jar, "").output);
    }

    @Test void divisionAndRemainderByZeroFailCleanlyAtRuntime() throws Exception {
        for (String operator : List.of("/", "%")) {
            var result = run(body("लिखो(१ " + operator + " ०);"), "");
            assertEquals(1, result.exit);
            assertTrue(result.output.startsWith("भाष:"));
            assertFalse(result.output.contains("Exception"));
        }
    }

    @Test void textEqualityEscapesSupplementaryUnicodeAndRealArithmeticWork() throws Exception {
        Path jar = body("""
                स्थिर पाठक: पाठ = "नमस्ते";
                लिखो(पाठक == "नमस्ते", पाठक != "अन्य", "क़" == "क़");
                लिखो("उद्धरण: \\\" और \\\\ और \\u0967", "😀");
                लिखो(१.५ + २.५, १.० / ०.०, ०.० / ०.०);
                लिखो();
                """);
        assertEquals("सत्य सत्य असत्य\nउद्धरण: \" और \\ और १ 😀\n४.० Infinity NaN\n\n", run(jar, "").output);
    }

    @Test void normalizedIdentifiersResolveAndCanonicalDuplicatesFail() throws Exception {
        assertEquals("५\n", run(body("मान क़दम: पूर्णांक = ५; लिखो(क़दम);"), "").output);
        reject("कार्य मुख्य() -> शून्य { मान क़दम: पूर्णांक = १; मान क़दम: पूर्णांक = २; }", "Duplicate binding");
        reject("कार्य क़दम() -> शून्य {} कार्य क़दम() -> शून्य {} कार्य मुख्य() -> शून्य {}", "Duplicate");
    }

    private void reject(String source, String message) {
        var error = assertThrows(CompilationException.class, () -> compile(source), source);
        assertTrue(error.getMessage().contains(message), error.getMessage());
        assertTrue(error.getMessage().startsWith("test.bhash:"), error.getMessage());
    }

    @Test void rejectsUndefinedNamesTypeErrorsImmutableAssignmentsAndWrongCalls() {
        var invalid = new LinkedHashMap<String, String>();
        invalid.put("लिखो(अनजान);", "Undefined variable");
        invalid.put("अज्ञात();", "Undefined function");
        invalid.put("पढ़ो(१);", "argument count");
        invalid.put("मान संख्या: पूर्णांक = सत्य;", "Expected");
        invalid.put("मान संख्या: पूर्णांक = १; संख्या = असत्य;", "Expected");
        invalid.put("स्थिर संख्या: पूर्णांक = १; संख्या = २;", "immutable");
        invalid.put("यदि (१) {}", "Expected");
        invalid.put("लिखो(१ + २.०);", "Expected");
        invalid.put("लिखो(सत्य + असत्य);", "Numeric");
        invalid.put("लिखो(१.० % २.०);", "Expected");
        invalid.put("लिखो(१ < २ < ३);", "Expected");
        invalid.put("लिखो(मुख्य());", "void");
        invalid.put("मान संख्या: पूर्णांक = संख्या;", "Undefined");
        invalid.put("मान पढ़ो: पूर्णांक = १; पढ़ो();", "not callable");
        invalid.put("१ + २;", "Only a function call");
        for (var entry : invalid.entrySet()) reject("कार्य मुख्य() -> शून्य { " + entry.getKey() + " }", entry.getValue());
    }

    @Test void rejectsBadSignaturesScopeAndControlFlow() {
        reject("", "मुख्य");
        reject("कार्य मुख्य(संख्या: पूर्णांक) -> शून्य {}", "मुख्य");
        reject("कार्य मुख्य() -> पूर्णांक { लौटाओ १; }", "मुख्य");
        reject("कार्य पढ़ो() -> पूर्णांक { लौटाओ १; } कार्य मुख्य() -> शून्य {}", "reserved");
        reject("कार्य मुख्य() -> शून्य { विराम; }", "Loop control");
        reject("कार्य मुख्य() -> शून्य { जारी; }", "Loop control");
        reject("कार्य मुख्य() -> शून्य { लौटाओ १; }", "Void function");
        reject("कार्य मुख्य() -> शून्य { लौटाओ; लिखो(१); }", "Unreachable");
        reject("कार्य जांच() -> पूर्णांक { यदि (सत्य) { लौटाओ १; } } कार्य मुख्य() -> शून्य {}", "every path");
        reject("कार्य जांच() -> पूर्णांक { लौटाओ; } कार्य मुख्य() -> शून्य {}", "Return value");
        reject("कार्य जांच(संख्या: पूर्णांक) -> पूर्णांक { संख्या = २; लौटाओ संख्या; } कार्य मुख्य() -> शून्य {}", "immutable");
        reject("कार्य जांच(संख्या: पूर्णांक) -> शून्य { मान संख्या: पूर्णांक = १; } कार्य मुख्य() -> शून्य {}", "Duplicate binding");
        reject("कार्य जांच(संख्या: पूर्णांक, संख्या: पूर्णांक) -> शून्य {} कार्य मुख्य() -> शून्य {}", "Duplicate binding");
        reject("कार्य मुख्य() -> शून्य { { मान संख्या: पूर्णांक = १; } लिखो(संख्या); }", "Undefined");
        reject("कार्य जांच(संख्या: पूर्णांक) -> शून्य {} कार्य मुख्य() -> शून्य { जांच(सत्य); }", "Expected");
    }

    @Test void rejectsOutOfRangeLiteralsAndMalformedUnicodeBeforeWritingOutput() throws Exception {
        reject("कार्य मुख्य() -> शून्य { लिखो(9223372036854775808); }", "64-bit");
        reject("कार्य मुख्य() -> शून्य { लिखो(-9223372036854775809); }", "64-bit");
        reject("कार्य मुख्य() -> शून्य { लिखो(" + "9".repeat(400) + ".0); }", "binary64");
        reject("कार्य मुख्य() -> शून्य { लिखो(\"\\uD800\"); }", "surrogate");
        Path output = directory.resolve("existing.jar");
        Files.writeString(output, "existing artifact");
        assertThrows(CompilationException.class, () -> new JvmCompiler().compile("bad.bhash", "कार्य", output));
        assertEquals("existing artifact", Files.readString(output));
    }

    @Test void cliCompileProtectsSourceAndReportsErrorsAndSupportedTargets() throws Exception {
        Path source = directory.resolve("input.bhash");
        Files.writeString(source, "कार्य मुख्य() -> शून्य { लिखो(१२०); }");
        var stdout = new ByteArrayOutputStream();
        var stderr = new ByteArrayOutputStream();
        var out = new PrintStream(stdout, true, StandardCharsets.UTF_8);
        var err = new PrintStream(stderr, true, StandardCharsets.UTF_8);
        assertEquals(0, Main.run(new String[]{"compile", source.toString()}, out, err));
        assertEquals("१२०\n", run(directory.resolve("input.jar"), "").output);
        assertEquals(2, Main.run(new String[]{"compile", source.toString(), "-o", source.toString()}, out, err));
        assertTrue(Files.readString(source).contains("कार्य"));
        Path custom = directory.resolve("output folder/custom program.jar");
        assertEquals(0, Main.run(new String[]{"compile", source.toString(), "--target", "jvm", "-o", custom.toString()}, out, err));
        assertEquals("१२०\n", run(custom, "").output);
        assertEquals(2, Main.run(new String[]{"compile", source.getRoot().toString()}, out, err));
        assertEquals(2, Main.run(new String[]{"compile", source.toString(), "--target", "wasm"}, out, err));
        assertEquals(2, Main.run(new String[]{"compile", source.toString(), "-o"}, out, err));
        assertEquals(2, Main.run(new String[]{"compile", source.toString(), "--unknown"}, out, err));
        assertEquals(0, Main.run(new String[]{"targets"}, out, err));
        assertTrue(stdout.toString(StandardCharsets.UTF_8).contains("[implemented]"));
        Files.writeString(source, "कार्य मुख्य() -> शून्य { लिखो(अनजान); }");
        assertEquals(1, Main.run(new String[]{"compile", source.toString()}, out, err));
        assertTrue(stderr.toString(StandardCharsets.UTF_8).contains("Undefined variable"));
    }
}
