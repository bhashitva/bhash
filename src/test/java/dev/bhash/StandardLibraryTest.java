// SPDX-License-Identifier: Apache-2.0
package dev.bhash;

import dev.bhash.backend.*;
import dev.bhash.backend.jvm.JvmCompiler;
import dev.bhash.backend.clr.ClrCompiler;
import dev.bhash.frontend.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

abstract class StandardLibraryTest {
    final boolean clr;
    @TempDir Path directory;
    int next;
    StandardLibraryTest(boolean clr){this.clr=clr;}
    String execute(String source) throws Exception {
        Path output=directory.resolve("app"+next+++(clr?"-clr":".jar"));
        var sources=List.of(new SourceFile("standard-test.bhash",source));
        if(clr)new ClrCompiler().compile(sources,output);else new JvmCompiler().compile(sources,output);
        String java=Path.of(System.getProperty("java.home"),"bin",System.getProperty("os.name").startsWith("Windows")?"java.exe":"java").toString();
        Path log=directory.resolve("result"+next+".txt");
        var process=(clr?new ProcessBuilder(ClrCompiler.executable(),output.resolve("BhashProgram.dll").toString()):new ProcessBuilder(java,"-jar",output.toString())).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try{process.getOutputStream().close();assertTrue(process.waitFor(30,TimeUnit.SECONDS));String result=Files.readString(log).replace("\r\n","\n");assertEquals(0,process.exitValue(),result);return result;}
        finally{process.destroyForcibly();}
    }
    @Test void portableExampleAndTypedJsonCollectionsRunWithoutCompilerRuntimeDependencies() throws Exception {
        String result=execute(Files.readString(Path.of("examples/standard-library/portable.bhash")));
        assertEquals("३ नमस्ते | भारत | \n२ भाष सरल है\n2.62\nसत्य ४२\n१\nभारत ३ {\"नाम\":\"भारत\",\"अंक\":[1,2,3]}\n1970-01-01T00:00:00.000Z ०\n",result);
    }
    @Test void textFormattingUnicodeAndLiteralSeparatorsHaveOnePortableContract() throws Exception {
        assertEquals("२ 😀\n३ सत्य सत्य सत्य\n|अ||\nक + ख\nabcZ\nहिन्दी\n0.12 0.38 -0.00 2.62\n१२.५०\nसत्य\n",execute("""
            आयात मानक.पाठ्य उपनाम प;
            कार्य मुख्य() -> शून्य {
                लिखो(प.खोजो("a😀भारत", "भारत"), प.अंश("a😀भारत",१,२));
                लिखो(प.लंबाई("क😀ख"),प.है("भारत","रत"),प.आरंभ("भारत","भा"),प.अंत("भारत","रत"));
                लिखो(प.जोड़ो(प.बाँटो(".अ..","."),"|"));
                लिखो(प.बदलो("क.*ख",".*"," + "));
                लिखो(प.छोटाअक्षर("ABC") + प.बड़ाअक्षर("z"));
                लिखो(प.छाँटो("\\u00a0हिन्दी\\u3000"));
                लिखो(प.संख्यापाठ(०.१२५,२),प.संख्यापाठ(०.३७५,२),प.संख्यापाठ(-०.०,२),प.संख्यापाठ(२.६२५,२));
                लिखो(प.अंकदेवनागरी(प.संख्यापाठ(१२.५,२)));
                लिखो(प.यूटीएफ८पाठ(प.यूटीएफ८("हिन्दी😀")) == "हिन्दी😀");
            }
            """));
    }
    @Test void mathRandomAndUtcDatesArePortable() throws Exception {
        assertEquals("सत्य सत्य सत्य सत्य\nसत्य सत्य सत्य\n1970-01-01T00:00:00.000Z\n2000-02-29T12:34:56.789Z २००० २ २९\nसत्य\n",execute("""
            आयात मानक.गणित उपनाम ग;
            आयात मानक.यादृच्छिक उपनाम य;
            आयात मानक.समय;
            कार्य मुख्य() -> शून्य {
                लिखो(ग.घात(२.०,१०.०)==१०२४.०,ग.वर्गमूल(८१.०)==९.०,ग.न्यूनतम(७,२)==२,ग.अधिकतम(-१,२)==२);
                लिखो(ग.निकटतम(२.५)==२.०,ग.निकटतम(३.५)==४.०,ग.ज्या(०.०)==०.० && ग.कोज्या(०.०)==१.०);
                लिखो(समय.पाठबनाओ(०));
                स्थिर त: पूर्णांक = समय.पढ़ो("2000-02-29T12:34:56.789Z");
                लिखो(समय.पाठबनाओ(त),समय.वर्ष(त),समय.माह(त),समय.दिन(त));
                य.बीज(४२); स्थिर क: पूर्णांक = य.पूर्णांकचुनो(-१०,१०); स्थिर ख: दशमलव = य.अंश();
                य.बीज(४२); लिखो(क==य.पूर्णांकचुनो(-१०,१०) && ख==य.अंश() && ख>=०.० && ख<१.० && समय.अभी()>०);
            }
            """));
    }
    @Test void jsonRejectsMalformedDocumentsPreservesTypesAndDetectsCycles() throws Exception {
        String[] invalid={"", "01", "[1,]", "{\"a\":1,\"a\":2}", "[", "true false", "1e9999", "9223372036854775808", "\"\\ud800\"", "{\"a\" 1}", "+1", "[१२]", "NaN"};
        var body=new StringBuilder("आयात मानक.जेसन; कार्य मुख्य() -> शून्य {");
        for(String s:invalid)body.append("प्रयास { जेसन.पढ़ो(").append(literal(s)).append("); लिखो(\"गलत\"); } पकड़ो (ग: अपवाद) { लिखो(\"अमान्य\"); }");
        body.append("""
            स्थिर मूल: सर्ववस्तु = जेसन.पढ़ो("[1,1.0,true,null,\\\"😀\\\"]");
            स्थिर सूचीका: सूची<सर्ववस्तु> = परिवर्तित<सूची<सर्ववस्तु>>(मूल);
            लिखो(प्रकारहै<पूर्णांक>(सूचीका[०]),प्रकारहै<दशमलव>(सूचीका[१]),प्रकारहै<तार्किक>(सूचीका[२]),सूचीका[३]==रिक्त,परिवर्तित<पाठ>(सूचीका[४])=="😀");
            स्थिर चक्र: सूची<सर्ववस्तु> = नया सूची<सर्ववस्तु>(); चक्र.जोड़ो(चक्र);
            प्रयास { जेसन.पाठबनाओ(चक्र); लिखो("गलत"); } पकड़ो (ग: अपवाद) { लिखो("चक्रीय"); }
            स्थिर दोहरित: सूची<सर्ववस्तु> = नया सूची<सर्ववस्तु>{१};
            स्थिर दो: सूची<सर्ववस्तु> = नया सूची<सर्ववस्तु>{दोहरित,दोहरित};
            लिखो(जेसन.पाठबनाओ(दो));
            }
            """);
        assertEquals("अमान्य\n".repeat(invalid.length)+"सत्य सत्य सत्य सत्य सत्य\nचक्रीय\n[[1],[1]]\n",execute(body.toString()));
    }
    @Test void invalidArgumentsAndUnsupportedJsonValuesAreCatchable() throws Exception {
        String[] expressions={"मानक.पाठ्य.बाँटो(\"अ\",\"\")","मानक.पाठ्य.दोहराओ(\"अ\",-१)","मानक.पाठ्य.दोहराओ(\"अ\",१०४८५७७)","मानक.पाठ्य.संख्यापाठ(१.०,१६)","मानक.गणित.निरपेक्ष(-९२२३३७२०३६८५४७७५८०८)","मानक.गणित.वर्गमूल(-१.०)","मानक.गणित.लघुगणक(०.०)","मानक.यादृच्छिक.पूर्णांकचुनो(१,१)","मानक.यादृच्छिक.पूर्णांकचुनो(-९२२३३७२०३६८५४७७५८०८,९२२३३७२०३६८५४७७५८०७)","मानक.समय.पढ़ो(\"1900-02-29T00:00:00.000Z\")","मानक.समय.पढ़ो(\"2026-01-01T00:00:00Z\")","मानक.समय.पाठबनाओ(२५३४०२३००८०००००)","मानक.जेसन.पाठबनाओ(नया मानचित्र<पूर्णांक,पूर्णांक>{१:२})"};
        var body=new StringBuilder("कार्य मुख्य() -> शून्य {");
        for(String e:expressions)body.append("प्रयास { ").append(e).append("; लिखो(\"गलत\"); } पकड़ो (ग: अपवाद) { लिखो(\"पकड़ा\"); }");
        body.append('}');assertEquals("पकड़ा\n".repeat(expressions.length),execute(body.toString()));
    }
    @Test void recursionAndSizeLimitsFailWithoutDamagingExistingFiles() throws Exception {
        Path oversized=directory.resolve("oversized.bin"),kept=directory.resolve("kept.bin");
        Files.write(kept,new byte[]{1,2,3});
        try(var channel=java.nio.channels.FileChannel.open(oversized,StandardOpenOption.CREATE,StandardOpenOption.WRITE)) {
            channel.position(16777216);channel.write(java.nio.ByteBuffer.wrap(new byte[]{0}));
        }
        String code="""
            कार्य मुख्य() -> शून्य {
                प्रयास { मानक.जेसन.पढ़ो(मानक.पाठ्य.दोहराओ("[",६५) + "0" + मानक.पाठ्य.दोहराओ("]",६५)); } पकड़ो (ग: अपवाद) { लिखो("गहराई"); }
                प्रयास { मानक.जेसन.पाठबनाओ(मानक.पाठ्य.दोहराओ("अ",१०४८५७६)); } पकड़ो (ग: अपवाद) { लिखो("सीमा"); }
                प्रयास { मानक.फ़ाइल.बाइटपढ़ो(OVERSIZED); } पकड़ो (ग: अपवाद) { लिखो("फ़ाइल सीमा"); }
                प्रयास { मानक.फ़ाइल.बाइटलिखो(KEPT,नया बाइटबफ़र(१६७७७२१७)); } पकड़ो (ग: अपवाद) { लिखो("सुरक्षित"); }
            }
            """.replace("OVERSIZED",literal(oversized.toString())).replace("KEPT",literal(kept.toString()));
        assertEquals("गहराई\nसीमा\nफ़ाइल सीमा\nसुरक्षित\n",execute(code));
        assertArrayEquals(new byte[]{1,2,3},Files.readAllBytes(kept));
    }
    @Test void incorrectSignaturesAndCompilerOwnedDeclarationsAreRejectedBeforeTheToolchain() {
        for(String body:List.of("मानक.गणित.वर्गमूल(१);","मानक.पाठ्य.जोड़ो(नया सूची<पूर्णांक>(),\",\");","नया मानक.जेसन();","मानक.फ़ाइल.पाठलिखो(\"x\",१);"))
            assertThrows(CompilationException.class,()->ManagedEmitter.emit(List.of(new SourceFile("bad.bhash","कार्य मुख्य() -> शून्य {"+body+"}")),clr?Target.CLR:Target.JVM));
        var reserved=assertThrows(CompilationException.class,()->ManagedEmitter.check(List.of(new SourceFile("reserved.bhash","संकुल मानक; सार्वजनिक वर्ग गणित {}"))));
        assertTrue(reserved.getMessage().startsWith("reserved.bhash:1:"),reserved.getMessage());
        assertThrows(CompilationException.class,()->ManagedEmitter.check(List.of(new SourceFile(dev.bhash.stdlib.StandardLibrary.SOURCE_NAME,"कार्य मुख्य() -> शून्य {}"))));
    }
    @Test void fileIoUsesStrictUtf8BinaryBuffersAndCallerPaths() throws Exception {
        String path=literal(directory.resolve("हिन्दी file.txt").toString()),binary=literal(directory.resolve("bytes.bin").toString());
        assertEquals("सत्य\nनमस्ते😀\nसत्य २ २५५\nअनुपस्थित\nअमान्य\n",execute("""
            आयात मानक.फ़ाइल;
            कार्य मुख्य() -> शून्य {
                लिखो(फ़ाइल.समर्थित());
                फ़ाइल.पाठलिखो(PATH,"नमस्ते😀"); लिखो(फ़ाइल.पाठपढ़ो(PATH));
                स्थिर ब: बाइटबफ़र = नया बाइटबफ़र{परिवर्तित<बाइट>(०),परिवर्तित<बाइट>(२५५)};
                फ़ाइल.बाइटलिखो(BINARY,ब); स्थिर क: बाइटबफ़र = फ़ाइल.बाइटपढ़ो(BINARY);
                लिखो(फ़ाइल.है(PATH),क.आकार,क[१]);
                प्रयास { फ़ाइल.पाठपढ़ो(PATH + ".missing"); } पकड़ो (ग: अपवाद) { लिखो("अनुपस्थित"); }
                प्रयास { फ़ाइल.पाठपढ़ो(BINARY); } पकड़ो (ग: अपवाद) { लिखो("अमान्य"); }
            }
            """.replace("PATH",path).replace("BINARY",binary)));
        assertArrayEquals("नमस्ते😀".getBytes(StandardCharsets.UTF_8),Files.readAllBytes(directory.resolve("हिन्दी file.txt")));
    }
    static String literal(String s){return "\""+s.replace("\\","\\\\").replace("\"","\\\"").replace("\n","\\n").replace("\r","\\r").replace("\t","\\t")+"\"";}
}
