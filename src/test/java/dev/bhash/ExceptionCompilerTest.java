// SPDX-License-Identifier: Apache-2.0
package dev.bhash;

import dev.bhash.backend.*;
import dev.bhash.backend.jvm.JvmCompiler;
import dev.bhash.backend.clr.ClrCompiler;
import dev.bhash.frontend.SourceFile;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

abstract class ExceptionCompilerTest {
    final boolean clr;
    @TempDir Path directory;
    int next;
    ExceptionCompilerTest(boolean clr) { this.clr=clr; }
    Path compile(List<SourceFile> files) throws Exception {
        Path output=directory.resolve("app"+next+++(clr?"-clr":".jar"));
        if (clr) { new ClrCompiler().compile(files,output); return output.resolve("BhashProgram.dll"); }
        new JvmCompiler().compile(files,output); return output;
    }
    Path compile(String text) throws Exception { return compile(List.of(new SourceFile("collection.bhash",text))); }
    record Result(int exit,String text) {}
    Result run(Path program,String input) throws Exception {
        String java=Path.of(System.getProperty("java.home"),"bin",System.getProperty("os.name").startsWith("Windows")?"java.exe":"java").toString();
        var command=clr?new ProcessBuilder(ClrCompiler.executable(),program.toString()):new ProcessBuilder(java,"-Xverify:all","-jar",program.toString());
        Path output=directory.resolve("out"+next+++".txt");
        var process=command.redirectErrorStream(true).redirectOutput(output.toFile()).start();
        try {
            try(var stdin=process.getOutputStream()) { stdin.write(input.getBytes(StandardCharsets.UTF_8)); }
            assertTrue(process.waitFor(15,TimeUnit.SECONDS),"Collection program timed out");
            return new Result(process.exitValue(),Files.readString(output).replace("\r\n","\n"));
        } finally { process.destroyForcibly(); }
    }
    void expect(String source,String expected) throws Exception {
        var result=run(compile(source),""); assertEquals(0,result.exit,result.text); assertEquals(expected,result.text);
    }
    @Test void factorialExampleHandlesInputAndCustomErrorsAndAlwaysCleansUp() throws Exception {
        Path program=compile(Files.readString(Path.of("examples/exceptions.bhash")));
        assertEquals("१२०\nकार्य समाप्त।\n",run(program,"५\n").text);
        assertEquals("अमान्य अंक: ० से २० के बीच पूर्णांक लिखें।\nकार्य समाप्त।\n",run(program,"-१\n").text);
        var bad=run(program,"abc\n"); assertEquals(0,bad.exit); assertTrue(bad.text.startsWith("इनपुट त्रुटि:")); assertTrue(bad.text.endsWith("कार्य समाप्त।\n"));
    }
    @Test void catchesMatchDerivedTypesInOrderPreserveIdentityAndRethrow() throws Exception {
        expect("""
            वर्ग विशेष विस्तार अपवाद { सार्वजनिक निर्माता(क: पाठ) : आधार(क) {} }
            कार्य मुख्य() -> शून्य {
                स्थिर मूल: विशेष = नया विशेष("मूल");
                प्रयास {
                    प्रयास { फेंको मूल; }
                    पकड़ो (गलती: विशेष) { लिखो(गलती == मूल, गलती.संदेश); फेंको; }
                    अंततः { लिखो("भीतर"); }
                } पकड़ो (गलती: अपवाद) { लिखो(गलती == मूल, गलती.संदेश); }
                अंततः { लिखो("बाहर"); }
                प्रयास { फेंको नया अपवाद(); } पकड़ो (गलती: अपवाद) { लिखो(गलती.संदेश == ""); }
            }
            ""","सत्य मूल\nभीतर\nसत्य मूल\nबाहर\nसत्य\n");
    }
    @Test void finallyRunsOnNormalReturnBreakContinueAndExceptionsFromCatch() throws Exception {
        expect("""
            कार्य फल() -> पूर्णांक {
                प्रयास { लौटाओ ७; } अंततः { लिखो("वापसी"); }
            }
            कार्य दूसरा() -> पूर्णांक {
                प्रयास { फेंको नया अपवाद("क"); }
                पकड़ो (गलती: अपवाद) { लौटाओ ८; }
                अंततः { लिखो("पकड़"); }
            }
            कार्य मुख्य() -> शून्य {
                लिखो(फल(), दूसरा());
                मान क: पूर्णांक = ०;
                जबतक (क < ३) {
                    क = क + १;
                    प्रयास { यदि (क == १) { जारी; } विराम; }
                    अंततः { लिखो("चक्र",क); }
                }
                प्रयास {
                    प्रयास { फेंको नया अपवाद("पहला"); }
                    पकड़ो (गलती: अपवाद) { फेंको नया अपवाद("दूसरा"); }
                    अंततः { लिखो("सफाई"); }
                } पकड़ो (गलती: अपवाद) { लिखो(गलती.संदेश); }
                प्रयास { लिखो("सामान्य"); } अंततः { जबतक (सत्य) { विराम; } }
            }
            ""","वापसी\nपकड़\n७ ८\nचक्र १\nचक्र २\nसफाई\nदूसरा\nसामान्य\n");
    }
    @Test void finallyExceptionReplacesPendingExceptionOrReturn() throws Exception {
        expect("""
            कार्य फल() -> पूर्णांक {
                प्रयास { लौटाओ १; } अंततः { फेंको नया अपवाद("सफाई"); }
            }
            कार्य मुख्य() -> शून्य {
                प्रयास { लिखो(फल()); } पकड़ो (गलती: अपवाद) { लिखो(गलती.संदेश); }
                प्रयास {
                    प्रयास { फेंको नया अपवाद("पहला"); } अंततः { फेंको नया अपवाद("अंतिम"); }
                } पकड़ो (गलती: अपवाद) { लिखो(गलती.संदेश); }
            }
            ""","सफाई\nअंतिम\n");
    }
    @Test void runtimeFailuresAreCatchableAndExecutionCanContinue() throws Exception {
        expect("""
            कार्य मुख्य() -> शून्य {
                प्रयास { लिखो(१ / ०); } पकड़ो (गलती: अपवाद) { लिखो(गलती.संदेश); }
                प्रयास { लिखो(नया पूर्णांक[०].लो(०)); } पकड़ो (गलती: अपवाद) { लिखो("सीमा"); }
                प्रयास { त्रुटि("स्वयं"); } पकड़ो (गलती: अपवाद) { लिखो(गलती.संदेश); }
                प्रयास { लिखो(पूर्णांकपाठ("abc")); } पकड़ो (गलती: अपवाद) { लिखो("पाठ"); }
                स्थिर खाली: अपवाद = रिक्त;
                प्रयास { फेंको खाली; } पकड़ो (गलती: अपवाद) { लिखो("रिक्त"); }
                लिखो("चालू");
            }
            ""","शून्य से भाग देना संभव नहीं है।\nसीमा\nस्वयं\nपाठ\nरिक्त\nचालू\n");
    }
    @Test void browserCorpusAlsoRunsOnManagedTargets() throws Exception {
        Path app=compile(Files.readString(Path.of("src/test/resources/exceptions/browser.bhash")));
        var good=run(app,"0"); assertEquals(0,good.exit,good.text);
        assertEquals("सत्य मूल\nभीतर\nसत्य मूल\nबाहर\nभाग\nसीमा\nप्रारंभ\nवापसी\n७\nचक्र १\nचक्र २\n",good.text);
        var bad=run(app,"1"); assertEquals(1,bad.exit); assertEquals("सफाई\nभाष: असफल\n",bad.text);
        assertEquals("अंतिम\n",run(app,"2").text);
    }
    @Test void staticInitializationFailuresCanBeCaught() throws Exception {
        expect("""
            वर्ग गलत { साझा मान अंक: पूर्णांक = १ / ०; }
            कार्य मुख्य() -> शून्य {
                प्रयास { लिखो(गलत.अंक); } पकड़ो (गलती: अपवाद) { लिखो("पकड़ा"); }
            }
            ""","पकड़ा\n");
    }
    @Test void uncaughtExceptionReportsFailureAfterFinally() throws Exception {
        var result=run(compile("कार्य मुख्य() -> शून्य { प्रयास { फेंको नया अपवाद(\"असफल\"); } अंततः { लिखो(\"सफाई\"); } }"),"");
        assertEquals(1,result.exit); assertEquals("सफाई\nभाष: असफल\n",result.text);
    }
    @Test void catchesResolveAcrossFilesAndInsideGenericBodies() throws Exception {
        var files=List.of(new SourceFile("types.bhash","""
            संकुल दोष;
            वर्ग संदेश {}
            सार्वजनिक वर्ग विशेष विस्तार अपवाद {
                सार्वजनिक निर्माता() : आधार("विशेष") {}
                सार्वजनिक कार्य लो() -> पाठ { लौटाओ संदेश; }
            }
            सार्वजनिक कार्य जांच<त>(मानक: त) -> त {
                प्रयास { फेंको नया विशेष(); } पकड़ो (गलती: अपवाद) { लिखो(गलती.संदेश); लौटाओ मानक; }
            }
            """),new SourceFile("main.bhash","""
            संकुल प्रयोग; आयात दोष.विशेष; आयात दोष.जांच;
            कार्य मुख्य() -> शून्य {
                प्रयास { फेंको नया विशेष(); } पकड़ो (गलती: विशेष) { लिखो(गलती.लो()); }
                लिखो(जांच<पूर्णांक>(५));
            }
            """));
        var result=run(compile(files),""); assertEquals(0,result.exit,result.text); assertEquals("विशेष\nविशेष\n५\n",result.text);
    }
    @Test void invalidExceptionProgramsFailSemanticChecking() throws Exception {
        for (String body:List.of(
                "फेंको १;", "फेंको रिक्त;", "फेंको;",
                "प्रयास {} पकड़ो (क: पूर्णांक) {}",
                "प्रयास {} पकड़ो (क: अपवाद) {} पकड़ो (ख: अपवाद) {}",
                "प्रयास {} पकड़ो (क: अपवाद) { क = नया अपवाद(); }",
                "प्रयास {} पकड़ो (क: अपवाद) { क.संदेश = \"बदल\"; }",
                "प्रयास {} अंततः { लौटाओ; }",
                "जबतक (सत्य) { प्रयास {} अंततः { विराम; } }",
                "जबतक (सत्य) { प्रयास {} अंततः { जारी; } }",
                "फेंको नया अपवाद(); लिखो(१);",
                "प्रयास {} पकड़ो (क: अपवाद) {} लिखो(क.संदेश);")) {
            var failure=assertThrows(CompilationException.class,()->compile("कार्य मुख्य() -> शून्य { "+body+" }"),body);
            assertFalse(failure.getMessage().contains("generated"),failure.getMessage());
        }
        assertThrows(CompilationException.class,()->compile("वर्ग अपवाद {} कार्य मुख्य() -> शून्य {}"));
        assertThrows(CompilationException.class,()->compile("वर्ग क विस्तार अपवाद {} कार्य मुख्य() -> शून्य { प्रयास {} पकड़ो (क: अपवाद) {} पकड़ो (ख: क) {} }"));
        assertThrows(CompilationException.class,()->compile("कार्य फल() -> पूर्णांक { प्रयास { लौटाओ १; } पकड़ो (क: अपवाद) {} } कार्य मुख्य() -> शून्य {}"));
    }
}
