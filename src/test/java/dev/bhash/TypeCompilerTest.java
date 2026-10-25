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

abstract class TypeCompilerTest {
    final boolean clr;
    @TempDir Path directory;
    int next;
    TypeCompilerTest(boolean clr) { this.clr=clr; }
    Path compile(List<SourceFile> files) throws Exception {
        Path output=directory.resolve("app"+next+++(clr?"-clr":".jar"));
        if (clr) { new ClrCompiler().compile(files,output); return output.resolve("BhashProgram.dll"); }
        new JvmCompiler().compile(files,output); return output;
    }
    Path compile(String text) throws Exception { return compile(List.of(new SourceFile("types.bhash",text))); }
    record Result(int exit,String text) {}
    Result run(Path program,String input) throws Exception {
        String java=Path.of(System.getProperty("java.home"),"bin",System.getProperty("os.name").startsWith("Windows")?"java.exe":"java").toString();
        var command=clr?new ProcessBuilder(ClrCompiler.executable(),program.toString()):new ProcessBuilder(java,"-Xverify:all","-jar",program.toString());
        Path output=directory.resolve("out"+next+++".txt");
        var process=command.redirectErrorStream(true).redirectOutput(output.toFile()).start();
        try {
            try(var stdin=process.getOutputStream()) { stdin.write(input.getBytes(StandardCharsets.UTF_8)); }
            assertTrue(process.waitFor(15,TimeUnit.SECONDS),"Type program timed out");
            return new Result(process.exitValue(),Files.readString(output).replace("\r\n","\n"));
        } finally { process.destroyForcibly(); }
    }
    void expect(String source,String expected) throws Exception {
        var result=run(compile(source),""); assertEquals(0,result.exit,result.text); assertEquals(expected,result.text);
    }
    @Test void numericConversionsAreExplicitCheckedAndTruncateTowardZero() throws Exception {
        expect("""
            वर्ग स्थिरांक {
                सार्वजनिक स्थिर ब: बाइट = परिवर्तित<बाइट>(२५५);
                सार्वजनिक साझा स्थिर ल: लघुपूर्णांक = परिवर्तित<लघुपूर्णांक>(२१४७४८३६४७);
            }
            कार्य मुख्य() -> शून्य {
                स्थिर ब: बाइट = परिवर्तित<बाइट>(२५५);
                स्थिर ल: लघुपूर्णांक = परिवर्तित<लघुपूर्णांक>(-२१४७४८३६४८);
                लिखो(ब, ल, ब + १, -ब, ~ब, ब << १);
                लिखो(परिवर्तित<पूर्णांक>(-३.९), परिवर्तित<बाइट>(-०.५), परिवर्तित<लघुपूर्णांक>(३.९));
                लिखो(परिवर्तित<दशमलव>(७), परिवर्तित<दशमलव>("-१२.५"));
                लिखो(परिवर्तित<पूर्णांक>("-९२२३३७२०३६८५४७७५८०८"), परिवर्तित<लघुपूर्णांक>("2147483647"));
                लिखो(परिवर्तित<पाठ>(ब), परिवर्तित<पाठ>(ल), परिवर्तित<पाठ>(सत्य));
                स्थिर अ: बाइट[] = नया बाइट[१]; स्थिर आ: लघुपूर्णांक[] = नया लघुपूर्णांक[१];
                लिखो(अ[०], आ[०], प्रकारहै<बाइट>(अ[०]), प्रकारहै<लघुपूर्णांक>(आ[०]));
                चयन (ब) { विकल्प २५५: लिखो("बाइट"); शेष: लिखो("गलत"); }
                लिखो(नया स्थिरांक().ब, स्थिरांक.ल);
                लिखो(परिवर्तित<पाठ>(७.०), परिवर्तित<पाठ>(-०.०));
                लिखो(परिवर्तित<पूर्णांक>(परिवर्तित<दशमलव>(९००७१९९२५४७४०९९३)));
            }
            ""","२५५ -२१४७४८३६४८ २५६ -२५५ -२५६ ५१०\n-३ ० ३\n७.० -१२.५\n-९२२३३७२०३६८५४७७५८०८ २१४७४८३६४७\n255 -2147483648 सत्य\n० ० सत्य सत्य\nबाइट\n२५५ २१४७४८३६४७\n7.0 -0.0\n९००७१९९२५४७४०९९२\n");
    }
    @Test void universalValuesRetainExactBoxedPrimitiveTypesAndPortableEquality() throws Exception {
        expect("""
            कार्य मुख्य() -> शून्य {
                स्थिर ब: सर्ववस्तु = परिवर्तित<बाइट>(२);
                स्थिर ल: सर्ववस्तु = परिवर्तित<लघुपूर्णांक>(२);
                स्थिर क: सर्ववस्तु = २;
                स्थिर प: सर्ववस्तु = "पाठ";
                लिखो(प्रकारहै<बाइट>(ब), प्रकारहै<लघुपूर्णांक>(ल), प्रकारहै<पूर्णांक>(क), प्रकारहै<पाठ>(प));
                लिखो(प्रकारहै<पूर्णांक>(ब), प्रकारहै<दशमलव>(क), प्रकारहै<सर्ववस्तु>(ब), प्रकारहै<सर्ववस्तु>(रिक्त));
                लिखो(परिवर्तित<बाइट>(ब), परिवर्तित<लघुपूर्णांक>(ल), परिवर्तित<पूर्णांक>(क), परिवर्तित<पाठ>(प));
                लिखो(क == २, २ == क, ब == परिवर्तित<बाइट>(२), ब == ल);
                स्थिर म: मानचित्र<सर्ववस्तु,पाठ> = नया मानचित्र<सर्ववस्तु,पाठ>{ब:"ब",ल:"ल",क:"क"};
                लिखो(म.आकार(), म[परिवर्तित<बाइट>(२)], म[परिवर्तित<लघुपूर्णांक>(२)], म[२]);
                स्थिर श: सर्ववस्तु = रिक्त; लिखो(परिवर्तित<सर्ववस्तु>(श) == रिक्त);
            }
            ""","सत्य सत्य सत्य सत्य\nअसत्य असत्य सत्य असत्य\n२ २ २ पाठ\nसत्य सत्य सत्य असत्य\n३ ब ल क\nसत्य\n");
    }
    @Test void runtimeTestsAndCastsSupportInheritanceInterfacesAndOnceOnlyEvaluation() throws Exception {
        expect("""
            अनुबंध नामी { कार्य नाम() -> पाठ; }
            वर्ग जीव {}
            वर्ग बिल्ली विस्तार जीव पालन नामी { सार्वजनिक कार्य नाम() -> पाठ { लौटाओ "बिल्ली"; } }
            वर्ग अन्य {}
            वर्ग गिनक { साझा मान अंक: पूर्णांक = ०; साझा कार्य लो() -> सर्ववस्तु { गिनक.अंक = गिनक.अंक + १; लौटाओ नया बिल्ली(); } }
            कार्य मुख्य() -> शून्य {
                स्थिर मूल: जीव = नया बिल्ली();
                लिखो(प्रकारहै<बिल्ली>(मूल), प्रकारहै<नामी>(मूल), प्रकारहै<अन्य>(मूल));
                लिखो(परिवर्तित<नामी>(मूल).नाम(), सुरक्षितरूप<अन्य>(मूल) == रिक्त);
                लिखो(प्रकारहै<बिल्ली>(गिनक.लो()), परिवर्तित<नामी>(गिनक.लो()).नाम(), गिनक.अंक);
                स्थिर खाली: सर्ववस्तु = रिक्त;
                लिखो(परिवर्तित<बिल्ली>(खाली) == रिक्त, सुरक्षितरूप<बिल्ली>(खाली) == रिक्त, प्रकारहै<बिल्ली>(खाली));
            }
            ""","सत्य सत्य असत्य\nबिल्ली सत्य\nसत्य बिल्ली २\nसत्य सत्य असत्य\n");
    }
    @Test void collectionTestsAreReifiedInvariantAndCheckNestedArraysAndSnapshots() throws Exception {
        expect("""
            कार्य मुख्य() -> शून्य {
                स्थिर म: मानचित्र<पाठ,पूर्णांक> = नया मानचित्र<पाठ,पूर्णांक>{"क":१};
                स्थिर स्रोत: सर्ववस्तु = नया सूची<पूर्णांक>{२};
                लिखो(प्रकारहै<सूची<पूर्णांक>>(स्रोत), प्रकारहै<सूची<पाठ>>(स्रोत), सुरक्षितरूप<सूची<पाठ>>(स्रोत) == रिक्त);
                स्थिर सही: सूची<पूर्णांक> = परिवर्तित<सूची<पूर्णांक>>(स्रोत); लिखो(सही[०]);
                लिखो(प्रकारहै<सूची<पाठ>>(म.कुंजियाँ()), प्रकारहै<सूची<पूर्णांक>>(म.मूल्य()));
                स्थिर जाल: पूर्णांक[][] = नया पूर्णांक[२][२];
                लिखो(प्रकारहै<पूर्णांक[][]>(जाल), प्रकारहै<पूर्णांक[]>(जाल[०]), प्रकारहै<पाठ[]>(जाल[०]));
                स्थिर मिश्र: सूची<सर्ववस्तु> = नया सूची<सर्ववस्तु>{१,"दो",सही};
                लिखो(परिवर्तित<पूर्णांक>(मिश्र[०]), परिवर्तित<पाठ>(मिश्र[१]), प्रकारहै<सूची<पूर्णांक>>(मिश्र[२]));
                स्थिर बा: बाइट[] = नया बाइट[]{परिवर्तित<बाइट>(७)};
                लिखो(प्रकारहै<बाइट[]>(बा), प्रकारहै<लघुपूर्णांक[]>(बा), प्रकारहै<पूर्णांक[]>(बा));
            }
            ""","सत्य असत्य सत्य\n२\nसत्य सत्य\nसत्य सत्य असत्य\n१ दो सत्य\nसत्य असत्य असत्य\n");
    }
    @Test void enumsHaveNamedSingletonsExplicitCodesAndTypedSwitchSections() throws Exception {
        expect("""
            परिगणना रंग { लाल, हरा = ५, नीला, अंतिम = -९२२३३७२०३६८५४७७५८०८ }
            वर्ग रंगधारी { स्थिर र: रंग = रंग.लाल; }
            कार्य मुख्य() -> शून्य {
                लिखो(रंग.लाल.अंक, रंग.हरा.नाम, रंग.नीला.अंक, रंग.अंतिम.अंक);
                स्थिर र: रंग = परिवर्तित<रंग>(५);
                लिखो(र == रंग.हरा, परिवर्तित<पूर्णांक>(र), परिवर्तित<पाठ>(र), नया रंगधारी().र == रंग.लाल);
                चयन (र) { विकल्प रंग.लाल: लिखो("लाल"); विकल्प रंग.हरा: लिखो("हरा"); शेष: लिखो("अन्य"); }
                स्थिर म: मानचित्र<रंग,पाठ> = नया मानचित्र<रंग,पाठ>{रंग.लाल:"ल",रंग.हरा:"ह"};
                लिखो(म[परिवर्तित<रंग>(५)], प्रकारहै<रंग>(र));
                स्थिर अ: रंग[] = नया रंग[१]; लिखो(अ[०] == रिक्त);
            }
            ""","० हरा ६ -९२२३३७२०३६८५४७७५८०८\nसत्य ५ हरा सत्य\nहरा\nह सत्य\nसत्य\n");
    }
    @Test void immutableRecordsHaveStructuralEqualityHashingAndGenericTypeIdentity() throws Exception {
        expect("""
            अभिलेख बिंदु(अ: पूर्णांक, ब: पूर्णांक);
            अभिलेख पात्र<त>(मानक: त);
            अभिलेख खाली();
            कार्य मुख्य() -> शून्य {
                स्थिर क: बिंदु = नया बिंदु(२,३);
                स्थिर ख: बिंदु = नया बिंदु(२,३);
                लिखो(क.अ, क.ब, क == ख, क == नया बिंदु(२,४), नया खाली() == नया खाली());
                स्थिर म: मानचित्र<बिंदु,पाठ> = नया मानचित्र<बिंदु,पाठ>{क:"ठीक"}; लिखो(म[ख]);
                स्थिर प: सर्ववस्तु = नया पात्र<पूर्णांक>(७);
                लिखो(प्रकारहै<पात्र<पूर्णांक>>(प), प्रकारहै<पात्र<पाठ>>(प));
                लिखो(परिवर्तित<पात्र<पूर्णांक>>(प).मानक);
                स्थिर ब: बाइटबफ़र = नया बाइटबफ़र(१);
                लिखो(नया पात्र<बाइटबफ़र>(ब) == नया पात्र<बाइटबफ़र>(ब), नया पात्र<बाइटबफ़र>(ब) == नया पात्र<बाइटबफ़र>(नया बाइटबफ़र(१)));
                स्थिर न: बिंदु = रिक्त; लिखो(न == रिक्त);
            }
            ""","२ ३ सत्य असत्य सत्य\nठीक\nसत्य असत्य\n७\nसत्य असत्य\nसत्य\n");
    }
    @Test void byteBuffersHaveUnsignedIndexingBinaryEndiannessCopiesAndUtf8() throws Exception {
        expect("""
            कार्य मुख्य() -> शून्य {
                स्थिर ब: बाइटबफ़र = नया बाइटबफ़र(१६);
                ब.पूर्णांकलिखो(०,७२६२३८५९७९०३८२८५६,सत्य);
                लिखो(ब[०],ब[७],ब.पूर्णांकपढ़ो(०,सत्य),ब.पूर्णांकपढ़ो(०,असत्य));
                ब.दशमलवलिखो(८,-१.५,असत्य); लिखो(ब.दशमलवपढ़ो(८,असत्य));
                स्थिर क: बाइटबफ़र = ब.काटो(०,८); क.भरो(परिवर्तित<बाइट>(२५५));
                लिखो(क.पूर्णांकपढ़ो(०,सत्य), ब[०], क.आकार, लंबाई(क), प्रकारहै<बाइटबफ़र>(क));
                स्थिर अ: बाइट[] = क.सरणी(); अ[०] = परिवर्तित<बाइट>(०); लिखो(क[०],अ[०],प्रकारहै<बाइट[]>(अ));
                स्थिर पाठ्य: पाठ = "नमस्ते 😀"; स्थिर लेख: बाइटबफ़र = यूटीएफ८बफ़र(पाठ्य);
                लिखो(यूटीएफ८पाठ(लेख) == पाठ्य, लेख.लंबाई);
                स्थिर छोटा: बाइटबफ़र = नया बाइटबफ़र{परिवर्तित<बाइट>(१),परिवर्तित<बाइट>(२५५)};
                प्रत्येक (अंक: बाइट में छोटा) { लिखो(अंक); }
            }
            ""","१ ८ ७२६२३८५९७९०३८२८५६ ५७८४३७६९५७५२३०७२०१\n-१.५\n-१ १ ८ ८ सत्य\n२५५ ० सत्य\nसत्य २३\n१\n२५५\n");
    }
    @Test void conversionAndBufferFailuresAreCatchableWithoutHostExceptions() throws Exception {
        String[] cases={
            "लिखो(परिवर्तित<बाइट>(२५६));", "लिखो(परिवर्तित<बाइट>(-१));",
            "लिखो(परिवर्तित<लघुपूर्णांक>(२१४७४८३६४८));", "लिखो(परिवर्तित<लघुपूर्णांक>(-२१४७४८३६४९));",
            "लिखो(परिवर्तित<पूर्णांक>(१.० / ०.०));", "लिखो(परिवर्तित<पूर्णांक>(०.० / ०.०));",
            "लिखो(परिवर्तित<पूर्णांक>(९२२३३७२०३६८५४७७५८०८.०));",
            "लिखो(परिवर्तित<दशमलव>(\"1२\"));", "लिखो(परिवर्तित<दशमलव>(\"1e2\"));",
            "स्थिर क: सर्ववस्तु = १; लिखो(परिवर्तित<बाइट>(क));",
            "स्थिर क: सर्ववस्तु = रिक्त; लिखो(परिवर्तित<पूर्णांक>(क));",
            "स्थिर क: सर्ववस्तु = नया सूची<पाठ>(); स्थिर ख: सूची<पूर्णांक> = परिवर्तित<सूची<पूर्णांक>>(क);",
            "स्थिर क: रंग = परिवर्तित<रंग>(९);",
            "स्थिर क: बाइटबफ़र = नया बाइटबफ़र(-१);",
            "लिखो(नया बाइटबफ़र(१)[-१]);", "लिखो(नया बाइटबफ़र(१)[९२२३३७२०३६८५४७७५८०७]);",
            "लिखो(नया बाइटबफ़र(७).पूर्णांकपढ़ो(०,सत्य));", "नया बाइटबफ़र(८).पूर्णांकलिखो(१,१,सत्य);",
            "स्थिर क: बाइटबफ़र = नया बाइटबफ़र(८).काटो(२,१);",
            "लिखो(यूटीएफ८पाठ(नया बाइटबफ़र{परिवर्तित<बाइट>(२५५)}));",
            "स्थिर क: सर्ववस्तु = नया अन्य(); स्थिर ख: विशेष = परिवर्तित<विशेष>(क);",
            "स्थिर क: बाइटबफ़र = नया बाइटबफ़र(२१४७४८३६४८);"
        };
        var body=new StringBuilder("परिगणना रंग { लाल } वर्ग अन्य {} वर्ग विशेष {} कार्य मुख्य() -> शून्य { स्थिर संकेत: पूर्णांक = पढ़ो(); प्रयास { चयन (संकेत) {");
        for(int i=0;i<cases.length;i++) body.append("विकल्प ").append(i).append(": ").append(cases[i]);
        body.append("} } पकड़ो (गलती: अपवाद) { लिखो(\"पकड़ा\"); } }");
        Path app=compile(body.toString());
        for(int i=0;i<cases.length;i++) { var result=run(app,Integer.toString(i)); assertEquals(0,result.exit,result.text); assertEquals("पकड़ा\n",result.text,"case "+i); }
    }
    @Test void browserTypeCorpusAlsoRunsOnManagedTargets() throws Exception {
        Path app=compile(Files.readString(Path.of("src/test/resources/types/browser.bhash")));
        var good=run(app,"0"); assertEquals(0,good.exit,good.text);
        assertEquals(Files.readString(Path.of("src/test/resources/types/browser-expected.txt")).replace("\r\n","\n"),good.text);
        for(int i=1;i<=16;i++) { var bad=run(app,Integer.toString(i)); assertEquals(1,bad.exit,"case "+i); assertTrue(bad.text.startsWith("भाष:"),bad.text); }
    }
    @Test void multiFileTypesExampleResolvesEnumsRecordsAliasesAndGenericCasts() throws Exception {
        var project=dev.bhash.frontend.ProjectLoader.load(Path.of("examples/types"));
        var result=run(compile(project.sources()),""); assertEquals(0,result.exit,result.text);
        assertEquals(Files.readString(Path.of("examples/types/expected.txt")).replace("\r\n","\n"),result.text);
    }
    @Test void invalidTypesAndConversionsAreRejectedBeforeHostCompilation() throws Exception {
        for(String body:List.of("स्थिर क: बाइट = १;", "स्थिर क: लघुपूर्णांक = १;", "स्थिर क: बाइट = परिवर्तित<लघुपूर्णांक>(१);",
                "स्थिर क: पूर्णांक = परिवर्तित<पूर्णांक>(सत्य);", "लिखो(सुरक्षितरूप<पूर्णांक>(१));", "लिखो(सुरक्षितरूप<पाठ>(\"क\"));",
                "स्थिर क: बाइटबफ़र = नया बाइटबफ़र(१); क[०] = १;", "नया बाइटबफ़र(१).भरो(२५५);",
                "स्थिर क: रंग = नया रंग();", "रंग.लाल = रंग.हरा;", "रंग.लाल.अंक = १;",
                "स्थिर क: बिंदु = नया बिंदु(१); क.अ = २;", "स्थिर क: बिंदु = नया बिंदु();",
                "चयन (रंग.लाल) { विकल्प ०: }", "चयन (रंग.लाल) { विकल्प रंग.लाल: विकल्प रंग.लाल: }",
                "चयन (परिवर्तित<बाइट>(१)) { विकल्प २५६: }")) {
            var error=assertThrows(CompilationException.class,()->compile("परिगणना रंग { लाल, हरा } अभिलेख बिंदु(अ: पूर्णांक); कार्य मुख्य() -> शून्य { "+body+" }"),body);
            assertTrue(error.getMessage().startsWith("types.bhash:"),error.getMessage());
        }
        for(String declaration:List.of("परिगणना रंग { क = १, ख = १ }", "परिगणना रंग { क, क }", "परिगणना रंग { नाम }",
                "परिगणना रंग { क = ९२२३३७२०३६८५४७७५८०७, ख }", "अभिलेख क(अ: पूर्णांक, अ: पूर्णांक);",
                "अभिलेख क(); वर्ग ख विस्तार क {}", "परिगणना क { अ } वर्ग ख विस्तार क {}"))
            assertThrows(CompilationException.class,()->compile(declaration+" कार्य मुख्य() -> शून्य {}"),declaration);
    }
}
