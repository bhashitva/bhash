// SPDX-License-Identifier: Apache-2.0
package dev.bhash;

import dev.bhash.backend.CompilationException;
import dev.bhash.backend.jvm.JvmCompiler;
import dev.bhash.backend.clr.ClrCompiler;
import dev.bhash.backend.Target;
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

abstract class ManagedCompilerTest {
    @Test void multiFileProjectRunsWithImportsGenericsOverloadsAndSharedObjectIdentity() throws Exception {
        var sources = dev.bhash.frontend.ProjectLoader.load(Path.of("examples/multi-file")).sources();
        Path output = directory.resolve(clr() ? "project-clr" : "project.jar");
        if (clr()) new ClrCompiler().compile(sources,output);
        else new JvmCompiler().compile(sources,output);
        Path executable = clr() ? output.resolve("BhashProgram.dll") : output;
        for (var item : Map.of("५", "१२०", "0", "१", "20", "२४३२९०२००८१७६६४००००").entrySet()) {
            var result = run(executable,item.getKey());
            assertEquals(0,result.exit);
            assertEquals("० से २० तक एक पूर्णांक लिखें:\nगुणनफल: " + item.getValue() + "\nसत्य १\nसंख्या ठीक\n",result.output);
        }
    }
    @Test void closedGenericTypesShareIdentityAndStaticStorageAcrossFilesAndAliases() throws Exception {
        var sources = List.of(new dev.bhash.frontend.SourceFile("box.bhash","""
                संकुल पुस्तक;
                सार्वजनिक वर्ग पात्र<त> {
                    साझा मान गिनती: पूर्णांक = ०;
                    निर्माता() { गिनती = गिनती + १; }
                }
                """),new dev.bhash.frontend.SourceFile("factory.bhash","""
                संकुल पुस्तक;
                सार्वजनिक कार्य बनाओ() -> पात्र<पूर्णांक> { लौटाओ नया पात्र<पूर्णांक>(); }
                """),new dev.bhash.frontend.SourceFile("app.bhash","""
                आयात पुस्तक.पात्र उपनाम डिब्बा;
                आयात पुस्तक.बनाओ;
                कार्य मुख्य() -> शून्य {
                    स्थिर क: डिब्बा<पूर्णांक> = बनाओ();
                    स्थिर ख: पुस्तक.पात्र<पूर्णांक> = नया डिब्बा<पूर्णांक>();
                    स्थिर ग: डिब्बा<पूर्णांक> = क;
                    लिखो(डिब्बा<पूर्णांक>.गिनती, क == ग, क == ख);
                }
                """));
        Path output = directory.resolve(clr() ? "generic-project-clr" : "generic-project.jar");
        if (clr()) new ClrCompiler().compile(sources,output);
        else new JvmCompiler().compile(sources,output);
        var result = run(clr() ? output.resolve("BhashProgram.dll") : output,"");
        assertEquals(0,result.exit);
        assertEquals("२ सत्य असत्य\n",result.output);
    }
    @Test void crossFileTypeErrorsFailBeforeInvokingTheTargetToolchain() {
        var sources = List.of(new dev.bhash.frontend.SourceFile("types.bhash","वर्ग वस्तु {}"),
                new dev.bhash.frontend.SourceFile("main.bhash","कार्य मुख्य() -> शून्य { स्थिर क: वस्तु = १; }"));
        var error = assertThrows(CompilationException.class,()-> {
            if (clr()) new ClrCompiler().compile(sources,directory.resolve("bad-clr"));
            else new JvmCompiler().compile(sources,directory.resolve("bad.jar"));
        });
        assertTrue(error.getMessage().startsWith("main.bhash:1:"),error.getMessage());
        assertTrue(error.getMessage().contains("Expected"),error.getMessage());
    }
    @Test void unicodeScalarTextOperationsAndAsciiNumericSerialization() throws Exception {
        var result = run(body("""
                स्थिर पाठ्य: पाठ = पाठपढ़ो();
                लिखो(पाठ्य + "!");
                लिखो(लंबाई(पाठ्य), अंश(पाठ्य, १, २));
                लिखो(अंकपाठ(-४२), पूर्णांकपाठ("५२"), बड़ाअक्षर("e2"), बड़ाअक्षर("aßé"));
                """), "a😀क\n");
        assertEquals(0, result.exit);
        assertEquals("a😀क!\n३ 😀\n-42 ५२ E2 Aßé\n", result.output);
    }

    @Test void textOperationsRejectInvalidRangesNumbersEofAndReservedNames() throws Exception {
        for (String statement : List.of("लिखो(अंश(\"😀\", ०, २));", "लिखो(पूर्णांकपाठ(\"1२\"));",
                "लिखो(पूर्णांकपाठ(\"9223372036854775808\"));", "लिखो(पाठपढ़ो());", "त्रुटि(\"test\");")) {
            var result = run(body(statement), "");
            assertEquals(1, result.exit);
            assertTrue(result.output.startsWith("भाष:"), result.output);
            assertFalse(result.output.contains("Exception"), result.output);
        }
        assertThrows(CompilationException.class, () -> compile("कार्य लंबाई() -> पूर्णांक { लौटाओ १; } कार्य मुख्य() -> शून्य {}"));
        assertThrows(CompilationException.class, () -> body("लिखो(\"a\" + १);"));
    }
    protected final Target target;
    protected ManagedCompilerTest(Target target) { this.target = target; }
    protected boolean clr() { return target == Target.CLR; }
    @TempDir Path directory;
    private int next;
    private record Execution(int exit, String output) {}

    private Path compile(String source) throws Exception {
        Path output = directory.resolve("program-" + next++ + (clr() ? "-clr" : ".jar"));
        if (clr()) {
            new ClrCompiler().compile("test.bhash", source, output);
            return output.resolve("BhashProgram.dll");
        }
        new JvmCompiler().compile("test.bhash", source, output);
        return output;
    }

    private Path body(String statements) throws Exception {
        return compile("कार्य मुख्य() -> शून्य { " + statements + " }");
    }

    private Execution run(Path jar, String input) throws Exception {
        String java = Path.of(System.getProperty("java.home"), "bin",
                System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java").toString();
        var command = clr() ? new ProcessBuilder(ClrCompiler.executable(), jar.toString())
                : new ProcessBuilder(java, "-Xverify:all", "-jar", jar.toString());
        var process = command.redirectErrorStream(true).start();
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
        if (clr()) {
            assertTrue(Files.readString(jar.resolveSibling("BhashProgram.runtimeconfig.json")).contains("net10.0"));
            assertTrue(Files.exists(jar.resolveSibling("BhashProgram.deps.json")));
            assertFalse(Files.exists(jar.resolveSibling("Program.cs")));
        } else try (var archive = new JarFile(jar.toFile())) {
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
                लिखो(--१, ++१, -(-९२२३३७२०३६८५४७७५८०८));
                """);
        assertEquals("-९२२३३७२०३६८५४७७५८०८ -९२२३३७२०३६८५४७७५८०८\n"
                + "-९२२३३७२०३६८५४७७५८०८ ०\n-२ -१ ७ १२\n१ १ -९२२३३७२०३६८५४७७५८०८\n", run(jar, "").output);
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
        assertEquals(0, Main.run(new String[]{"compile", source.toString(), "--target", clr() ? "clr" : "jvm"}, out, err));
        assertEquals("१२०\n", run(directory.resolve(clr() ? "input-clr/BhashProgram.dll" : "input.jar"), "").output);
        assertEquals(2, Main.run(new String[]{"compile", source.toString(), "--target", clr() ? "clr" : "jvm", "-o", source.toString()}, out, err));
        assertTrue(Files.readString(source).contains("कार्य"));
        Path custom = directory.resolve(clr() ? "output folder/custom program-clr" : "output folder/custom program.jar");
        assertEquals(0, Main.run(new String[]{"compile", source.toString(), "--target", clr() ? "clr" : "jvm", "-o", custom.toString()}, out, err));
        assertEquals("१२०\n", run(clr() ? custom.resolve("BhashProgram.dll") : custom, "").output);
        assertEquals(2, Main.run(new String[]{"compile", source.getRoot().toString()}, out, err));
        assertEquals(2, Main.run(new String[]{"compile", source.toString(), "--target", "wasi"}, out, err));
        assertEquals(2, Main.run(new String[]{"compile", source.toString(), "-o"}, out, err));
        assertEquals(2, Main.run(new String[]{"compile", source.toString(), "--unknown"}, out, err));
        assertEquals(0, Main.run(new String[]{"targets"}, out, err));
        assertTrue(stdout.toString(StandardCharsets.UTF_8).contains("[implemented]"));
        Files.writeString(source, "कार्य मुख्य() -> शून्य { लिखो(अनजान); }");
        assertEquals(1, Main.run(new String[]{"compile", source.toString(), "--target", clr() ? "clr" : "jvm"}, out, err));
        assertTrue(stderr.toString(StandardCharsets.UTF_8).contains("Undefined variable"));
    }
    @Test void objectExamplesRunWithPolymorphismAndConsoleFactorial() throws Exception {
        var account = run(compile(Files.readString(Path.of("examples/objects.bhash"))), "");
        assertEquals(0, account.exit);
        assertEquals("मनोज १५० १५ १६५\n", account.output);
        Path factorial = compile(Files.readString(Path.of("examples/factorial-objects.bhash")));
        for (String input : List.of("5\n", "५\n")) {
            var result = run(factorial, input);
            assertEquals(0, result.exit);
            assertEquals("० से २० तक एक पूर्णांक लिखें:\nगुणनफल: १२०\n", result.output);
        }
        assertTrue(run(factorial, "0\n").output.endsWith("गुणनफल: १\n"));
        assertTrue(run(factorial, "20\n").output.endsWith("गुणनफल: २४३२९०२००८१७६६४००००\n"));
    }

    @Test void objectCompositionChainedAccessIdentityAndReturningThisWork() throws Exception {
        String source = """
                वर्ग वस्तु {
                    मान मूल्य: पूर्णांक = १;
                    कार्य स्वयं() -> वस्तु { लौटाओ यह; }
                    कार्य बढ़ाओ() -> शून्य { मूल्य = मूल्य + १; }
                }
                वर्ग डिब्बा {
                    मान अंदर: वस्तु;
                    निर्माता(अंदर: वस्तु) { यह.अंदर = अंदर; }
                    कार्य लो() -> वस्तु { लौटाओ अंदर; }
                }
                कार्य मुख्य() -> शून्य {
                    स्थिर पात्र: डिब्बा = नया डिब्बा(नया वस्तु());
                    पात्र.लो().बढ़ाओ();
                    पात्र.अंदर.मूल्य = पात्र.अंदर.मूल्य + ३;
                    लिखो(पात्र.लो().स्वयं().मूल्य, पात्र.लो() == पात्र.अंदर,
                        नया वस्तु() != पात्र.अंदर, पात्र.अंदर != रिक्त, रिक्त == रिक्त);
                }
                """;
        var result = run(compile(source), "");
        assertEquals(0, result.exit);
        assertEquals("५ सत्य सत्य सत्य सत्य\n", result.output);
    }

    @Test void multiLevelOverridesBaseCallsAndSubtypeArgumentsWork() throws Exception {
        String source = """
                वर्ग प्रथम {
                    संरक्षित निर्माता() {}
                    संरक्षित कार्य बीज() -> पूर्णांक { लौटाओ १; }
                    कार्य मानदो() -> पूर्णांक { लौटाओ यह.बीज(); }
                }
                वर्ग द्वितीय विस्तार प्रथम {
                    अधिलेखन कार्य मानदो() -> पूर्णांक { लौटाओ आधार.मानदो() + २; }
                    कार्य जांच(अन्य: द्वितीय) -> पूर्णांक { लौटाओ अन्य.बीज(); }
                }
                वर्ग तृतीय विस्तार द्वितीय {
                    अधिलेखन कार्य मानदो() -> पूर्णांक { लौटाओ आधार.मानदो() + ४; }
                }
                वर्ग गुप्त {
                    निजी मान रहस्य: पूर्णांक = ९;
                    निजी कार्य छिपा() -> पूर्णांक { लौटाओ यह.रहस्य; }
                    कार्य लो(अन्य: बालक) -> पूर्णांक { लौटाओ अन्य.रहस्य + अन्य.छिपा(); }
                }
                वर्ग बालक विस्तार गुप्त {}
                कार्य बनाओ() -> प्रथम { लौटाओ नया तृतीय(); }
                कार्य पढ़(वस्तु: प्रथम) -> पूर्णांक { लौटाओ वस्तु.मानदो(); }
                कार्य मुख्य() -> शून्य {
                    मान आधारवस्तु: प्रथम = नया द्वितीय();
                    आधारवस्तु = बनाओ();
                    लिखो(पढ़(आधारवस्तु), पढ़(नया द्वितीय()), आधारवस्तु == रिक्त, नया द्वितीय().जांच(नया तृतीय()), नया गुप्त().लो(नया बालक()));
                }
                """;
        var result = run(compile(source), "");
        assertEquals(0, result.exit);
        assertEquals("७ ३ असत्य १ १८\n", result.output);
    }

    @Test void fieldDefaultsAndBaseFirstInitializationArePortable() throws Exception {
        String source = """
                वर्ग मूल { निर्माता() { लिखो("मूल"); } }
                वर्ग पात्र विस्तार मूल {
                    मान संख्या: पूर्णांक;
                    मान दशांश: दशमलव;
                    मान सही: तार्किक;
                    मान नाम: पाठ;
                    मान अन्य: मूल;
                    स्थिर सीमा: पूर्णांक = ५ * २;
                    निर्माता() {
                        लिखो(यह.संख्या, यह.दशांश, यह.सही, यह.नाम, यह.अन्य == रिक्त, यह.सीमा);
                    }
                }
                कार्य मुख्य() -> शून्य { नया पात्र(); }
                """;
        assertEquals("मूल\n० ०.० असत्य  सत्य १०\n", run(compile(source), "").output);
    }

    @Test void nullFieldAndMethodDereferencesFailCleanly() throws Exception {
        String source = """
                वर्ग वस्तु { मान संख्या: पूर्णांक; कार्य मानदो(मानक: पूर्णांक) -> पूर्णांक { लौटाओ मानक; } }
                कार्य चिह्न() -> पूर्णांक { लिखो("दुष्प्रभाव"); लौटाओ १; }
                कार्य मुख्य() -> शून्य {
                    स्थिर वस्तु: वस्तु = रिक्त;
                    यदि (पढ़ो() == ०) { वस्तु.संख्या = चिह्न(); }
                    अन्यथा { लिखो(वस्तु.मानदो(चिह्न())); }
                }
                """;
        Path program = compile(source);
        for (String input : List.of("0\n", "1\n")) {
            var result = run(program, input);
            assertEquals(1, result.exit);
            assertTrue(result.output.startsWith("भाष: रिक्त"), result.output);
            assertFalse(result.output.contains("दुष्प्रभाव"));
            assertFalse(result.output.contains("Exception"));
        }
    }

    @Test void accessControlRejectsPrivateAndProtectedMembers() {
        String prefix = "वर्ग मूल { निजी मान गुप्त: पूर्णांक; संरक्षित मान धन: पूर्णांक; "
                + "निजी कार्य छिपा() -> पूर्णांक { लौटाओ १; } संरक्षित निर्माता() {} } ";
        reject(prefix + "कार्य मुख्य() -> शून्य { नया मूल(); }", "Constructor is not accessible");
        String members = "वर्ग वस्तु { निजी मान संख्या: पूर्णांक; निजी कार्य मानदो() -> पूर्णांक { लौटाओ १; } } ";
        reject(members + "कार्य मुख्य() -> शून्य { लिखो(नया वस्तु().संख्या); }", "not accessible");
        reject(members + "कार्य मुख्य() -> शून्य { नया वस्तु().मानदो(); }", "not accessible");
        reject(prefix + "वर्ग बच्चा विस्तार मूल { कार्य जांच(अन्य: मूल) -> पूर्णांक { लौटाओ अन्य.धन; } } कार्य मुख्य() -> शून्य {}", "not accessible");
        reject("वर्ग मूल { संरक्षित मान संख्या: पूर्णांक; } कार्य मुख्य() -> शून्य { लिखो(नया मूल().संख्या); }", "not accessible");
    }

    @Test void rejectsInvalidClassesOverridesAndObjectTypeAssignments() {
        String main = " कार्य मुख्य() -> शून्य {}";
        reject("वर्ग वस्तु {} वर्ग वस्तु {}" + main, "Duplicate class");
        reject("वर्ग बच्चा विस्तार अज्ञात {}" + main, "Unknown base class");
        reject("वर्ग प्रथम विस्तार द्वितीय {} वर्ग द्वितीय विस्तार प्रथम {}" + main, "cycle");
        reject("वर्ग वस्तु { मान संख्या: पूर्णांक; मान संख्या: पूर्णांक; }" + main, "Duplicate or hidden");
        reject("वर्ग मूल { मान संख्या: पूर्णांक; } वर्ग बच्चा विस्तार मूल { मान संख्या: पूर्णांक; }" + main, "hidden");
        reject("वर्ग वस्तु { अधिलेखन कार्य जांच() -> शून्य {} }" + main, "Override requires");
        reject("वर्ग मूल { कार्य जांच() -> शून्य {} } वर्ग बच्चा विस्तार मूल { कार्य जांच() -> शून्य {} }" + main, "अधिलेखन");
        reject("वर्ग मूल { कार्य जांच() -> पूर्णांक { लौटाओ १; } } वर्ग बच्चा विस्तार मूल { अधिलेखन कार्य जांच() -> पाठ { लौटाओ \"एक\"; } }" + main, "preserve");
        reject("वर्ग मूल { कार्य जांच(संख्या: पूर्णांक) -> शून्य {} } वर्ग बच्चा विस्तार मूल { अधिलेखन कार्य जांच(संख्या: पाठ) -> शून्य {} }" + main, "preserve");
        reject("वर्ग मूल { निजी कार्य जांच() -> शून्य {} } वर्ग बच्चा विस्तार मूल { अधिलेखन कार्य जांच() -> शून्य {} }" + main, "non-private");
        reject("वर्ग मूल { कार्य जांच() -> शून्य {} } वर्ग बच्चा विस्तार मूल { निजी अधिलेखन कार्य जांच() -> शून्य {} }" + main, "narrow access");
        reject("वर्ग वस्तु { निर्माता() {} निर्माता() {} }" + main, "Duplicate constructor");
        reject("वर्ग मूल { निर्माता(संख्या: पूर्णांक) {} } वर्ग बच्चा विस्तार मूल {}" + main, "argument count");
        reject("वर्ग मूल { निजी निर्माता() {} } वर्ग बच्चा विस्तार मूल {}" + main, "Base constructor");
        reject("कार्य मुख्य() -> शून्य { स्थिर वस्तु: अज्ञात = रिक्त; }", "Unknown type");
        reject("कार्य मुख्य() -> शून्य { नया अज्ञात(); }", "Unknown class");
        reject("वर्ग मूल {} वर्ग बच्चा विस्तार मूल {} कार्य मुख्य() -> शून्य { स्थिर वस्तु: बच्चा = नया मूल(); }", "Expected");
        reject("वर्ग प्रथम {} वर्ग द्वितीय {} कार्य मुख्य() -> शून्य { लिखो(नया प्रथम() == नया द्वितीय()); }", "unrelated");
        reject("वर्ग वस्तु {} कार्य मुख्य() -> शून्य { लिखो(नया वस्तु()); }", "primitive fields");
        reject("वर्ग वस्तु {} कार्य मुख्य() -> शून्य { नया वस्तु().अज्ञात(); }", "Undefined method");
        reject("कार्य मुख्य() -> शून्य { मान संख्या: पूर्णांक = १; संख्या.अज्ञात(); }", "class instance");
        reject("वर्ग वस्तु {} कार्य मुख्य() -> शून्य { नया वस्तु() = नया वस्तु(); }", "Assignment requires");
    }

    @Test void rejectsUnsafeConstructorsAndNonconstantFieldInitializers() {
        String main = " कार्य मुख्य() -> शून्य {}";
        reject("वर्ग वस्तु { स्थिर सीमा: पूर्णांक; }" + main, "requires an initializer");
        reject("वर्ग वस्तु { मान संख्या: पूर्णांक = पढ़ो(); }" + main, "constant expressions");
        reject("वर्ग वस्तु { मान अन्य: वस्तु = नया वस्तु(); }" + main, "constant expressions");
        reject("वर्ग वस्तु { मान संख्या: पूर्णांक = यह.संख्या; }" + main, "Instance access");
        reject("वर्ग वस्तु { स्थिर सीमा: पूर्णांक = १; निर्माता() { यह.सीमा = २; } }" + main, "immutable");
        reject("वर्ग वस्तु { निर्माता() { जांच(); } कार्य जांच() -> शून्य {} }" + main, "Constructors cannot call");
        reject("वर्ग वस्तु { निर्माता() { स्थिर स्वयं: वस्तु = यह; } }" + main, "cannot escape");
        reject("वर्ग वस्तु { निर्माता() : आधार() {} }" + main, "requires a parent");
        reject("वर्ग मूल { निर्माता(संख्या: पूर्णांक) {} } वर्ग बच्चा विस्तार मूल { मान संख्या: पूर्णांक; निर्माता() : आधार(यह.संख्या) {} }" + main, "Instance access");
        reject("कार्य मुख्य() -> शून्य { लिखो(यह); }", "Instance access");
        reject("वर्ग वस्तु { कार्य जांच() -> शून्य { आधार.जांच(); } }" + main, "parent class");
    }

    @Test void contractsGenericTypesFunctionsAndStaticMembersExecute() throws Exception {
        var result=run(compile(Files.readString(Path.of("examples/contracts-generics.bhash"))),"");
        assertEquals(0,result.exit);
        assertEquals("पूर्णांक: २१\nपाठ: नमस्ते\nपात्र ४२ ३.०\n१ १ १००\n",result.output);
    }
    @Test void overloadsChooseMostSpecificAndDispatchVirtually() throws Exception {
        String source="""
            अनुबंध नामित { कार्य मानदो() -> पूर्णांक; }
            वर्ग मूल पालन नामित {
                कार्य मानदो() -> पूर्णांक { लौटाओ १; }
                कार्य जांच(संख्या: पूर्णांक) -> पूर्णांक { लौटाओ संख्या + १०; }
                कार्य जांच(शब्द: पाठ) -> पूर्णांक { लौटाओ २०; }
            }
            वर्ग बच्चा विस्तार मूल {
                अधिलेखन कार्य मानदो() -> पूर्णांक { लौटाओ २; }
                अधिलेखन कार्य जांच(संख्या: पूर्णांक) -> पूर्णांक { लौटाओ संख्या + १००; }
                कार्य जांच(मानक: तार्किक) -> पूर्णांक { लौटाओ ३०; }
            }
            वर्ग पात्र {
                मान मूल्य: पूर्णांक;
                निर्माता() { यह.मूल्य = १; }
                निर्माता(मूल्य: पूर्णांक) { यह.मूल्य = मूल्य; }
                निर्माता(वस्तु: मूल) { यह.मूल्य = ३; }
                निर्माता(वस्तु: बच्चा) { यह.मूल्य = ४; }
            }
            वर्ग उपपात्र विस्तार पात्र { निर्माता(मूल्य: पूर्णांक) : आधार(मूल्य) {} }
            कार्य चुनो(वस्तु: मूल) -> पूर्णांक { लौटाओ १; }
            कार्य चुनो(वस्तु: बच्चा) -> पूर्णांक { लौटाओ २; }
            कार्य चुनो(वस्तु: नामित) -> पूर्णांक { लौटाओ ३; }
            कार्य मुख्य() -> शून्य {
                स्थिर मूलवस्तु: मूल = नया बच्चा();
                स्थिर अनुबंधवस्तु: नामित = मूलवस्तु;
                लिखो(चुनो(मूलवस्तु), चुनो(नया बच्चा()), चुनो(अनुबंधवस्तु), चुनो(रिक्त));
                लिखो(मूलवस्तु.जांच(५), मूलवस्तु.जांच("पाठ"), नया बच्चा().जांच(सत्य), अनुबंधवस्तु.मानदो());
                लिखो(नया पात्र().मूल्य, नया पात्र(८).मूल्य, नया पात्र(रिक्त).मूल्य, नया उपपात्र(९).मूल्य);
            }
            """;
        var result=run(compile(source),"");
        assertEquals(0,result.exit);
        assertEquals("१ २ ३ २\n१०५ २० ३० २\n१ ८ ४ ९\n",result.output);
    }
    @Test void nestedGenericsGenericInheritanceAndReferenceArgumentsWork() throws Exception {
        String source="""
            अनुबंध प्रदाता<त> { कार्य लो() -> त; }
            वर्ग पात्र<त> पालन प्रदाता<त> {
                मान अंदर: त;
                निर्माता(मूल्य: त) { यह.अंदर = मूल्य; }
                कार्य लो() -> त { लौटाओ अंदर; }
            }
            वर्ग उपपात्र<त> विस्तार पात्र<त> { निर्माता(मूल्य: त) : आधार(मूल्य) {} }
            वर्ग वस्तु { मान संख्या: पूर्णांक = ७; }
            कार्य पहचान<त>(त: त) -> त { लौटाओ त; }
            कार्य पहचान<त>(पहला: त, दूसरा: त) -> त { लौटाओ दूसरा; }
            कार्य मुख्य() -> शून्य {
                स्थिर बाहरी: प्रदाता<पात्र<वस्तु>> = नया उपपात्र<पात्र<वस्तु>>(नया पात्र<वस्तु>(नया वस्तु()));
                लिखो(बाहरी.लो().लो().संख्या, पहचान<पूर्णांक>(१, ९), पहचान<तार्किक>(सत्य));
            }
            """;
        assertEquals("७ ९ सत्य\n",run(compile(source),"").output);
    }
    @Test void staticStorageInheritanceAndVisibilityAreConsistent() throws Exception {
        String source="""
            वर्ग मूल {
                संरक्षित साझा मान संख्या: पूर्णांक = १;
                निजी साझा कार्य रहस्य() -> पूर्णांक { लौटाओ ४; }
                साझा कार्य लो() -> पूर्णांक { लौटाओ संख्या + रहस्य(); }
            }
            वर्ग बच्चा विस्तार मूल {
                साझा कार्य बढ़ाओ() -> शून्य { मूल.संख्या = मूल.संख्या + १; }
            }
            कार्य मुख्य() -> शून्य { बच्चा.बढ़ाओ(); लिखो(मूल.लो(), बच्चा.लो()); }
            """;
        assertEquals("६ ६\n",run(compile(source),"").output);
    }
    @Test void rejectsInvalidContractsOverloadsAndStaticAccess() {
        String main=" कार्य मुख्य() -> शून्य {}";
        reject("अनुबंध क { कार्य लो() -> पूर्णांक; } वर्ग ख पालन क {}"+main,"implement");
        reject("अनुबंध क { कार्य लो() -> पूर्णांक; } वर्ग ख पालन क { निजी कार्य लो() -> पूर्णांक { लौटाओ १; } }"+main,"implement");
        reject("अनुबंध क { कार्य लो() -> पूर्णांक; } वर्ग ख पालन क { साझा कार्य लो() -> पूर्णांक { लौटाओ १; } }"+main,"implement");
        reject("अनुबंध क { कार्य लो() -> पूर्णांक; } वर्ग ख पालन क { कार्य लो() -> पाठ { लौटाओ \"एक\"; } }"+main,"implement");
        reject("अनुबंध क {} कार्य मुख्य() -> शून्य { नया क(); }","construct an interface");
        reject("अनुबंध क विस्तार ख {} अनुबंध ख विस्तार क {}"+main,"cycle");
        reject("वर्ग क {} वर्ग ख पालन क {}"+main,"interface type");
        reject("अनुबंध क {} वर्ग ख विस्तार क {}"+main,"पालन");
        reject("अनुबंध क { कार्य लो() -> पूर्णांक; } अनुबंध ख { कार्य लो() -> पाठ; } अनुबंध ग विस्तार क, ख {}"+main,"conflict");
        reject("कार्य जांच(संख्या: पूर्णांक) -> पूर्णांक { लौटाओ १; } कार्य जांच(संख्या: पूर्णांक) -> पाठ { लौटाओ \"एक\"; }"+main,"Duplicate");
        reject("वर्ग क {} वर्ग ख {} कार्य जांच(वस्तु: क) -> शून्य {} कार्य जांच(वस्तु: ख) -> शून्य {} कार्य मुख्य() -> शून्य { जांच(रिक्त); }","Ambiguous overload");
        reject("वर्ग क { मान संख्या: पूर्णांक; साझा कार्य लो() -> पूर्णांक { लौटाओ संख्या; } }"+main,"Instance access");
        reject("वर्ग क { साझा कार्य लो() -> क { लौटाओ यह; } }"+main,"Instance access");
        reject("वर्ग क { कार्य लो() -> पूर्णांक { लौटाओ १; } } कार्य मुख्य() -> शून्य { क.लो(); }","instance receiver");
        reject("वर्ग क { साझा कार्य लो() -> पूर्णांक { लौटाओ १; } } कार्य मुख्य() -> शून्य { नया क().लो(); }","type receiver");
        reject("वर्ग क { साझा स्थिर संख्या: पूर्णांक = १; } कार्य मुख्य() -> शून्य { क.संख्या = २; }","immutable");
        reject("वर्ग क { साझा कार्य लो() -> शून्य {} } वर्ग ख विस्तार क { अधिलेखन कार्य लो() -> शून्य {} }"+main,"Static methods");
        reject("वर्ग क { साझा निर्माता() {} }"+main,"Static constructors");
    }
    @Test void rejectsUnsafeGenericInstantiations() {
        String box="वर्ग पात्र<त> { मान अंदर: त; निर्माता(मूल्य: त) { यह.अंदर = मूल्य; } } ";
        reject(box+"कार्य मुख्य() -> शून्य { नया पात्र<पूर्णांक>(\"गलत\"); }","Expected");
        reject(box+"कार्य मुख्य() -> शून्य { स्थिर गलत: पात्र<पाठ> = नया पात्र<पूर्णांक>(१); }","Expected");
        reject(box+"कार्य मुख्य() -> शून्य { नया पात्र<पूर्णांक, पाठ>(१); }","type argument count");
        reject(box+"कार्य मुख्य() -> शून्य { नया पात्र<अज्ञात>(रिक्त); }","Unknown type");
        reject(box+"कार्य मुख्य() -> शून्य { स्थिर गलत: पात्र = रिक्त; }","explicit type arguments");
        reject("वर्ग पात्र<त, त> {} कार्य मुख्य() -> शून्य {}","Duplicate type parameter");
        reject("वर्ग पात्र<त> {} वर्ग पात्र {} कार्य मुख्य() -> शून्य {}","Duplicate type");
        reject("कार्य पढ़ो<त>() -> त { लौटाओ रिक्त; } कार्य मुख्य() -> शून्य {}","Reserved function");
        reject("वर्ग पात्र<त> { कार्य लो() -> पूर्णांक { लौटाओ \"गलत\"; } } कार्य मुख्य() -> शून्य { नया पात्र<पूर्णांक>(); }","Expected");
    }

    @Test void staticInitializerFailuresReportBhashDiagnostics() throws Exception {
        var result=run(compile("""
            वर्ग गलत { साझा मान संख्या: पूर्णांक = १ / ०; }
            कार्य मुख्य() -> शून्य { लिखो(गलत.संख्या); }
            """),"");
        assertEquals(1,result.exit);
        assertTrue(result.output.startsWith("भाष: शून्य से भाग"),result.output);
        assertFalse(result.output.contains("Exception"),result.output);
    }

    @Test void switchConsoleExampleSelectsGroupedCasesAndDefaultWithoutFallthrough() throws Exception {
        Path program=compile(Files.readString(Path.of("examples/switch-console.bhash")));
        for (var item:Map.of("0", "शून्य\n० मिला\n", "१", "एक या दो\n१ या २ मिला\n",
                "2", "एक या दो\n१ या २ मिला\n", "9", "अन्य संख्या\nदूसरा मान मिला\n").entrySet()) {
            var result=run(program,item.getKey());
            assertEquals(0,result.exit);
            assertEquals("एक पूर्णांक लिखें:\n"+item.getValue(),result.output);
        }
    }
    @Test void switchHandlesLongLimitsTextValuesBooleanAndDefaultPosition() throws Exception {
        String source="""
            कार्य अंक(संख्या: पूर्णांक) -> पूर्णांक {
                चयन (संख्या) {
                    शेष: लौटाओ ०;
                    विकल्प -९२२३३७२०३६८५४७७५८०८: लौटाओ १;
                    विकल्प ९२२३३७२०३६८५४७७५८०७: लौटाओ २;
                    विकल्प २१४७४८३६४८: लौटाओ ३;
                }
            }
            कार्य मुख्य() -> शून्य {
                लिखो(अंक(-९२२३३७२०३६८५४७७५८०८), अंक(९२२३३७२०३६८५४७७५८०७), अंक(२१४७४८३६४८), अंक(५));
                स्थिर शब्द: पाठ = "नमस्ते";
                चयन (शब्द) {
                    विकल्प "नमस्ते": लिखो("पाठ");
                    शेष: लिखो("गलत");
                }
                चयन (१ == १) {
                    विकल्प असत्य: लिखो("गलत");
                    विकल्प सत्य: लिखो("सत्य");
                }
                चयन ("😀") { विकल्प "😀": लिखो("यूनिकोड"); }
            }
            """;
        assertEquals("१ २ ३ ०\nपाठ\nसत्य\nयूनिकोड\n",run(compile(source),"").output);
    }
    @Test void switchEvaluatesSelectorOnceAndKeepsSectionScopesSeparate() throws Exception {
        String source="""
            वर्ग गिनक {
                साझा मान गिनती: पूर्णांक = ०;
                साझा कार्य लो() -> पूर्णांक { गिनती = गिनती + १; लौटाओ २; }
            }
            कार्य मुख्य() -> शून्य {
                स्थिर संख्या: पूर्णांक = ९;
                चयन (गिनक.लो()) {
                    विकल्प १: स्थिर संख्या: पूर्णांक = १; लिखो(संख्या);
                    विकल्प २: स्थिर संख्या: पूर्णांक = २; लिखो(संख्या);
                    शेष: स्थिर संख्या: पूर्णांक = ३; लिखो(संख्या);
                }
                लिखो(संख्या, गिनक.गिनती);
                चयन (गिनक.लो()) {}
                लिखो(गिनक.गिनती);
                चयन (९) { विकल्प १: लिखो("गलत"); }
                चयन (०) { शेष: लिखो("केवल शेष"); }
            }
            """;
        assertEquals("२\n९ १\n२\nकेवल शेष\n",run(compile(source),"").output);
    }
    @Test void switchBreakContinueAndNestedLoopsTargetTheRightConstruct() throws Exception {
        String source="""
            कार्य मुख्य() -> शून्य {
                मान संख्या: पूर्णांक = ०;
                जबतक (संख्या < ४) {
                    संख्या = संख्या + १;
                    चयन (संख्या) {
                        विकल्प १: जारी;
                        विकल्प २: चयन (९) { विकल्प ९: विराम; } लिखो("भीतर"); विराम;
                        विकल्प ३: जबतक (सत्य) { लिखो("चक्र"); विराम; } लिखो("बाद");
                        शेष: विराम;
                    }
                    लिखो(संख्या);
                }
                चयन (०) { शेष: चयन (१) { विकल्प १: विराम; } लिखो("बाहरी"); }
                चयन (०) { विकल्प ०: विराम; }
                लिखो("अंत");
            }
            """;
        assertEquals("भीतर\n२\nचक्र\nबाद\n३\n४\nबाहरी\nअंत\n",run(compile(source),"").output);
    }
    @Test void switchWorksInsideGenericBodiesAndWithInterfaceCalls() throws Exception {
        String source="""
            अनुबंध गणक { कार्य लो() -> पूर्णांक; }
            वर्ग वस्तु पालन गणक { कार्य लो() -> पूर्णांक { लौटाओ २; } }
            कार्य चुनो<त>(मानक: त) -> पाठ {
                चयन (मानक) { विकल्प २: लौटाओ "दो"; शेष: लौटाओ "अन्य"; }
            }
            कार्य मुख्य() -> शून्य {
                स्थिर गणना: गणक = नया वस्तु();
                लिखो(चुनो<पूर्णांक>(गणना.लो()));
            }
            """;
        assertEquals("दो\n",run(compile(source),"").output);
    }
    @Test void switchRejectsDuplicateLabelsBadTypesScopeLeaksAndInvalidFlow() {
        reject("कार्य मुख्य() -> शून्य { चयन (१) { विकल्प १: विकल्प +1: } }","Duplicate विकल्प");
        reject("कार्य मुख्य() -> शून्य { चयन (०) { विकल्प -०: विकल्प 0: } }","Duplicate विकल्प");
        reject("कार्य मुख्य() -> शून्य { चयन (\"अ\") { विकल्प \"अ\": विकल्प \"\\u0905\": } }","Duplicate विकल्प");
        reject("कार्य मुख्य() -> शून्य { चयन (सत्य) { विकल्प सत्य: विकल्प सत्य: } }","Duplicate विकल्प");
        reject("कार्य मुख्य() -> शून्य { चयन (१) { शेष: शेष: } }","Duplicate शेष");
        reject("कार्य मुख्य() -> शून्य { चयन (१) { विकल्प \"एक\": } }","Expected");
        reject("कार्य मुख्य() -> शून्य { चयन (१.०) {} }","चयन requires");
        reject("वर्ग वस्तु {} कार्य मुख्य() -> शून्य { चयन (नया वस्तु()) {} }","चयन requires");
        reject("कार्य मुख्य() -> शून्य { चयन (रिक्त) {} }","चयन requires");
        reject("कार्य मुख्य() -> शून्य { चयन (१) { विकल्प 9223372036854775808: } }","64-bit");
        reject("कार्य मुख्य() -> शून्य { चयन (१) { शेष: जारी; } }","Loop control");
        reject("कार्य मुख्य() -> शून्य { चयन (१) { विकल्प १: स्थिर अंदर: पूर्णांक = १; शेष: लिखो(अंदर); } }","Undefined");
        reject("कार्य मुख्य() -> शून्य { चयन (१) { शेष: विराम; लिखो(१); } }","Unreachable");
        reject("कार्य गलत(क: पूर्णांक) -> पूर्णांक { चयन (क) { विकल्प १: लौटाओ १; } } कार्य मुख्य() -> शून्य {}","every path");
        reject("कार्य गलत(क: पूर्णांक) -> पूर्णांक { चयन (क) { विकल्प १: विराम; शेष: लौटाओ १; } } कार्य मुख्य() -> शून्य {}","every path");
        reject("कार्य मुख्य() -> शून्य { चयन (१) { विकल्प १: लौटाओ; शेष: लौटाओ; } लिखो(१); }","Unreachable");
    }

}
