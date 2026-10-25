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

abstract class CollectionCompilerTest {
    final boolean clr;
    @TempDir Path directory;
    int next;
    CollectionCompilerTest(boolean clr) { this.clr=clr; }
    private Path compile(List<SourceFile> files) throws Exception {
        Path output=directory.resolve("app"+next+++(clr?"-clr":".jar"));
        if (clr) { new ClrCompiler().compile(files,output); return output.resolve("BhashProgram.dll"); }
        new JvmCompiler().compile(files,output); return output;
    }
    private Path compile(String text) throws Exception { return compile(List.of(new SourceFile("collection.bhash",text))); }
    private record Result(int exit,String text) {}
    private Result run(Path program,String input) throws Exception {
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
    private void expect(String source,String expected) throws Exception {
        var result=run(compile(source),""); assertEquals(0,result.exit,result.text); assertEquals(expected,result.text);
    }
    @Test void multiFileCollectionsExampleWorksAcrossGenericInterfacesAndImports() throws Exception {
        var project=dev.bhash.frontend.ProjectLoader.load(Path.of("examples/collections"));
        var result=run(compile(project.sources()),"");
        assertEquals(0,result.exit,result.text);
        assertEquals(Files.readString(Path.of("examples/collections/expected.txt")).replace("\r\n","\n"),result.text);
    }
    @Test void arraysHaveTypedDefaultsNestedShapesAliasesAndReferenceIdentity() throws Exception {
        expect("""
            वर्ग वस्तु { मान संख्या: पूर्णांक; }
            वर्ग धारक { मान भीतर: पूर्णांक[]; }
            कार्य मुख्य() -> शून्य {
                स्थिर क: पूर्णांक[] = नया पूर्णांक[२];
                स्थिर ख: सरणी<पूर्णांक> = क;
                ख[१] = ७;
                लिखो(क[०], क[१], क == ख, क.लंबाई, लंबाई(क));
                स्थिर पाठ्य: पाठ[] = नया पाठ[१];
                स्थिर सही: तार्किक[] = नया तार्किक[१];
                स्थिर दश: दशमलव[] = नया दशमलव[१];
                स्थिर वस्तुएँ: वस्तु[] = नया वस्तु[१];
                लिखो(पाठ्य[०] == "", सही[०], दश[०], वस्तुएँ[०] == रिक्त, नया धारक().भीतर == रिक्त);
                स्थिर जाल: पूर्णांक[][] = नया पूर्णांक[२][३];
                जाल[१][२] = ९;
                लिखो(जाल[०][२], जाल[१][२], जाल[०] == जाल[१]);
                स्थिर अधूरा: पूर्णांक[][] = नया पूर्णांक[२][];
                लिखो(अधूरा[०] == रिक्त);
                स्थिर ऊबड़: पूर्णांक[][] = नया पूर्णांक[][]{नया पूर्णांक[]{१}, नया पूर्णांक[]{२,३}};
                लिखो(ऊबड़[०][०], ऊबड़[१][१], नया पूर्णांक[०].खाली());
                स्थिर त्रि: पूर्णांक[][][] = नया पूर्णांक[२][२][२];
                त्रि[१][१][१] = ४२;
                लिखो(त्रि[०][१][१], त्रि[१][१][१]);
            }
            ""","० ७ सत्य २ २\nसत्य असत्य ०.० सत्य सत्य\n० ९ असत्य\nसत्य\n१ ३ सत्य\n० ४२\n");
    }
    @Test void listsSupportGrowthIndexingInsertionRemovalContainsClearAndAliasing() throws Exception {
        expect("""
            कार्य मुख्य() -> शून्य {
                स्थिर क: सूची<पूर्णांक> = नया सूची<पूर्णांक>{१,३};
                स्थिर ख: सूची<पूर्णांक> = क;
                क.डालो(१,२); ख.जोड़ो(४); ख[०] = ९;
                लिखो(क.आकार(), लंबाई(ख), क.है(२), क == ख, क.हटाओ(०));
                प्रत्येक (अ: पूर्णांक में क) { लिखो(अ); }
                क.रखो(०,७); लिखो(क.लो(०)); क.साफ़(); क.साफ़(); लिखो(क.खाली());
                स्थिर घ: सूची<पूर्णांक[]> = नया सूची<पूर्णांक[]>{नया पूर्णांक[]{५}};
                घ[०][०] = ६; लिखो(घ[०][०]);
            }
            ""","४ ४ सत्य सत्य ९\n२\n३\n४\n७\nसत्य\n६\n");
    }
    @Test void mapsPreserveInsertionOrderHandleDuplicatesAndProvideIndependentSnapshots() throws Exception {
        expect("""
            कार्य मुख्य() -> शून्य {
                स्थिर क: मानचित्र<पाठ,पूर्णांक> = नया मानचित्र<पाठ,पूर्णांक>{"क":१,"ख":२,"क":३};
                क["ग"] = ४; क.रखो("ख",५);
                लिखो(क.आकार(), क.है("क"), क.है("न"), क.लो("न",९), क.लो("क"));
                स्थिर कुंजियाँ: सूची<पाठ> = क.कुंजियाँ();
                स्थिर मूल्य: सूची<पूर्णांक> = क.मूल्य();
                लिखो(क.हटाओ("ख"), क.हटाओ("ख")); क["ख"] = ६;
                प्रत्येक (चाबी: पाठ, मानक: पूर्णांक में क) { लिखो(चाबी,मानक); }
                प्रत्येक (चाबी: पाठ में कुंजियाँ) { लिखो(चाबी); }
                प्रत्येक (मानक: पूर्णांक में मूल्य) { लिखो(मानक); }
                प्रत्येक (चाबी: पाठ में क.कुंजियाँ()) { क.हटाओ(चाबी); }
                लिखो(क.खाली());
            }
            ""","३ सत्य असत्य ९ ३\nसत्य असत्य\nक ३\nग ४\nख ६\nक\nख\nग\n३\n५\n४\nसत्य\n");
    }
    @Test void mapAndMembershipEqualityIsPortableForRealsTextNullValuesAndObjectKeys() throws Exception {
        expect("""
            वर्ग वस्तु {}
            कार्य मुख्य() -> शून्य {
                स्थिर दश: मानचित्र<दशमलव,पूर्णांक> = नया मानचित्र<दशमलव,पूर्णांक>();
                दश[-०.०] = १; दश[०.०] = २;
                दश[०.० / ०.०] = ३; दश[-(०.० / ०.०)] = ४;
                लिखो(दश.आकार(), दश[-०.०], दश[०.० / ०.०]);
                स्थिर सूचीका: सूची<दशमलव> = नया सूची<दशमलव>{-०.०, ०.० / ०.०};
                लिखो(सूचीका.है(०.०), सूचीका.है(०.० / ०.०));
                स्थिर क: वस्तु = नया वस्तु(); स्थिर ख: वस्तु = नया वस्तु();
                स्थिर वस्तुमान: मानचित्र<वस्तु,पाठ> = नया मानचित्र<वस्तु,पाठ>{क:"एक",ख:"दो"};
                लिखो(वस्तुमान.आकार(), वस्तुमान[क], वस्तुमान.है(नया वस्तु()));
                स्थिर रिक्तमान: मानचित्र<पाठ,वस्तु> = नया मानचित्र<पाठ,वस्तु>{"क":रिक्त};
                लिखो(रिक्तमान.है("क"), रिक्तमान["क"] == रिक्त, रिक्तमान.लो("क",क) == रिक्त);
                स्थिर पाठ्य: मानचित्र<पाठ,पूर्णांक> = नया मानचित्र<पाठ,पूर्णांक>{"क़":१,"क़":२};
                लिखो(पाठ्य.आकार(), पाठ्य["क़"], पाठ्य["क़"]);
                स्थिर झंडे: मानचित्र<तार्किक,पाठ> = नया मानचित्र<तार्किक,पाठ>{सत्य:"हाँ",असत्य:"नहीं"};
                लिखो(झंडे[सत्य], झंडे[असत्य]);
                स्थिर अ: पूर्णांक[] = नया पूर्णांक[]{१};
                स्थिर ब: मानचित्र<पूर्णांक[],पूर्णांक> = नया मानचित्र<पूर्णांक[],पूर्णांक>{अ:७};
                अ[०] = ९; लिखो(ब[अ], ब.है(नया पूर्णांक[]{९}));
            }
            ""","२ २ ४\nसत्य सत्य\n२ एक असत्य\nसत्य सत्य सत्य\n२ १ २\nहाँ नहीं\n७ असत्य\n");
    }
    @Test void foreachEvaluatesSourceOnceSupportsShadowingUpcastsAndNestedControlFlow() throws Exception {
        expect("""
            वर्ग मूल { कार्य लो() -> पूर्णांक { लौटाओ ७; } }
            वर्ग बच्चा विस्तार मूल {}
            कार्य स्रोत() -> सूची<पूर्णांक> { लिखो("स्रोत"); लौटाओ नया सूची<पूर्णांक>{१,२,३,४}; }
            कार्य मुख्य() -> शून्य {
                स्थिर अंक: सूची<पूर्णांक> = स्रोत();
                मान योग: पूर्णांक = ०;
                प्रत्येक (अंक: पूर्णांक में अंक) {
                    चयन (अंक) { विकल्प १: जारी; विकल्प ३: विराम; }
                    प्रत्येक (भीतर: पूर्णांक में नया पूर्णांक[]{१,२}) { यदि (भीतर == २) { विराम; } योग = योग + अंक; }
                    यदि (अंक == ४) { विराम; }
                }
                लिखो(योग, अंक.आकार());
                प्रत्येक (म: मूल में नया बच्चा[]{नया बच्चा()}) { लिखो(म.लो()); }
                प्रत्येक (क: पूर्णांक में नया पूर्णांक[०]) { लिखो("गलत"); }
                प्रत्येक (क: पूर्णांक में स्रोत()) { लिखो(क); विराम; }
            }
            ""","स्रोत\n९ ४\n७\nस्रोत\n१\n");
    }
    @Test void nonStructuralUpdatesRemainVisibleDuringIteration() throws Exception {
        expect("""
            कार्य मुख्य() -> शून्य {
                स्थिर क: पूर्णांक[] = नया पूर्णांक[]{१,२};
                प्रत्येक (अ: पूर्णांक में क) { क[१] = ९; लिखो(अ); }
                स्थिर ख: सूची<पूर्णांक> = नया सूची<पूर्णांक>{३,४};
                प्रत्येक (अ: पूर्णांक में ख) { ख[१] = ८; लिखो(अ); }
                स्थिर ग: मानचित्र<पाठ,पूर्णांक> = नया मानचित्र<पाठ,पूर्णांक>{"क":५,"ख":६};
                प्रत्येक (चाबी: पाठ, मानक: पूर्णांक में ग) { ग["ख"] = ७; लिखो(चाबी,मानक); }
            }
            ""","१\n९\n३\n८\nक ५\nख ७\n");
    }
    @Test void receiversIndicesValuesDimensionsAndEntriesEvaluateOnceLeftToRight() throws Exception {
        expect("""
            कार्य अंक(मानक: पूर्णांक) -> पूर्णांक { लिखो(मानक); लौटाओ मानक; }
            कार्य स्रोत() -> पूर्णांक[] { लिखो("सरणी"); लौटाओ नया पूर्णांक[२]; }
            कार्य मुख्य() -> शून्य {
                स्रोत()[अंक(१)] = अंक(७);
                स्थिर जाल: पूर्णांक[][] = नया पूर्णांक[अंक(२)][अंक(३)];
                स्थिर क: मानचित्र<पूर्णांक,पूर्णांक> = नया मानचित्र<पूर्णांक,पूर्णांक>{अंक(४):अंक(५),अंक(६):अंक(७)};
                लिखो(क.लो(४,अंक(९)), जाल[०][०]);
            }
            ""","सरणी\n१\n७\n२\n३\n४\n५\n६\n७\n९\n५ ०\n");
    }
    @Test void genericsInterfacesAndConstructorOverloadsRetainCollectionTypeIdentity() throws Exception {
        expect("""
            अनुबंध गणक { कार्य लो(मानक: सूची<पूर्णांक>) -> पूर्णांक; कार्य लो(मानक: सूची<पाठ>) -> पाठ; }
            वर्ग गणना पालन गणक {
                मान संख्या: पूर्णांक;
                निर्माता(आ: सूची<पूर्णांक>) { संख्या = आ[०]; }
                निर्माता(आ: सूची<पाठ>) { संख्या = लंबाई(आ[०]); }
                कार्य लो(मानक: सूची<पूर्णांक>) -> पूर्णांक { लौटाओ मानक[०]; }
                कार्य लो(मानक: सूची<पाठ>) -> पाठ { लौटाओ मानक[०]; }
            }
            वर्ग आवरण<त> { मान मूल्य: त; निर्माता(आ: त) { मूल्य = आ; } }
            कार्य सूचीबनाओ<त>(आ: त[]) -> सूची<त> {
                स्थिर फल: सूची<त> = नया सूची<त>();
                प्रत्येक (मानक: त में आ) { फल.जोड़ो(मानक); }
                लौटाओ फल;
            }
            कार्य लो<सूची>(आ: सूची[]) -> सूची { लौटाओ आ[०]; }
            कार्य मुख्य() -> शून्य {
                स्थिर क: सूची<पूर्णांक> = सूचीबनाओ<पूर्णांक>(नया पूर्णांक[]{७});
                स्थिर ख: सूची<पाठ> = नया सूची<पाठ>{"भाष"};
                स्थिर ग: गणक = नया गणना(क); लिखो(ग.लो(क), ग.लो(ख));
                लिखो(नया गणना(ख).संख्या);
                स्थिर डिब्बा: आवरण<मानचित्र<पाठ,सूची<पूर्णांक[]>>> = नया आवरण<मानचित्र<पाठ,सूची<पूर्णांक[]>>>(नया मानचित्र<पाठ,सूची<पूर्णांक[]>>());
                डिब्बा.मूल्य["क"] = नया सूची<पूर्णांक[]>{नया पूर्णांक[]{९}};
                लिखो(डिब्बा.मूल्य["क"][०][०], लो<पूर्णांक>(नया पूर्णांक[]{५}));
            }
            ""","७ भाष\n३\n९ ५\n");
    }
    @Test void runtimeErrorsUsePortableBhashDiagnostics() throws Exception {
        String[] cases={"नया पूर्णांक[-१];","नया पूर्णांक[२१४७४८३६४८];","लिखो(अ[-१]);","लिखो(अ[२]);",
            "लिखो(ब[२]);","ब.डालो(३,१);","ब.हटाओ(-१);","लिखो(म[\"नहीं\"]);",
            "स्थिर क: मानचित्र<वस्तु,पूर्णांक> = नया मानचित्र<वस्तु,पूर्णांक>(); क[रिक्त] = १;",
            "स्थिर क: पूर्णांक[] = रिक्त; लिखो(क[०]);","स्थिर क: सूची<पूर्णांक> = रिक्त; क.जोड़ो(१);",
            "स्थिर क: मानचित्र<पाठ,पूर्णांक> = रिक्त; प्रत्येक (चाबी: पाठ में क) {}",
            "प्रत्येक (मानक: पूर्णांक में ब) { ब.जोड़ो(३); }","प्रत्येक (मानक: पूर्णांक में ब) { ब.हटाओ(०); }",
            "प्रत्येक (मानक: पूर्णांक में ब) { ब.साफ़(); }","प्रत्येक (चाबी: पाठ, मानक: पूर्णांक में म) { म[\"नया\"] = २; }",
            "प्रत्येक (चाबी: पाठ में म) { म.हटाओ(चाबी); }","प्रत्येक (चाबी: पाठ में म) { म.साफ़(); }",
            "नया पूर्णांक[०][-१];","लिखो(अ[९२२३३७२०३६८५४७७५८०७]);",
            "स्थिर क: पूर्णांक[][] = नया पूर्णांक[१][]; लिखो(क[०][०]);"};
        var text=new StringBuilder("वर्ग वस्तु {} कार्य मुख्य() -> शून्य { स्थिर अ: पूर्णांक[] = नया पूर्णांक[२]; स्थिर ब: सूची<पूर्णांक> = नया सूची<पूर्णांक>{१,२}; स्थिर म: मानचित्र<पाठ,पूर्णांक> = नया मानचित्र<पाठ,पूर्णांक>{\"क\":१}; चयन (पढ़ो()) {");
        for (int i=0;i<cases.length;i++) text.append("विकल्प ").append(i).append(": ").append(cases[i]);
        text.append("} लिखो(\"अनपेक्षित\"); }");
        Path program=compile(text.toString());
        for (int i=0;i<cases.length;i++) {
            var result=run(program,Integer.toString(i));
            assertEquals(1,result.exit,i+": "+result.text);
            assertTrue(result.text.startsWith("भाष:"),i+": "+result.text);
            assertFalse(result.text.contains("Exception"),result.text);
            assertFalse(result.text.contains("अनपेक्षित"),result.text);
        }
    }
    @Test void compileTimeErrorsRejectUnsafeCollectionPrograms() {
        String[] cases={"स्थिर क: सूची = रिक्त;","स्थिर क: सूची<पूर्णांक,पाठ> = रिक्त;","स्थिर क: मानचित्र<पाठ> = रिक्त;",
            "स्थिर क: पूर्णांक[] = नया पूर्णांक[]{सत्य};","स्थिर क: पूर्णांक[] = नया पूर्णांक[]{१}; क[०] = सत्य;",
            "स्थिर क: पूर्णांक[] = नया पूर्णांक[१.०];","स्थिर क: पूर्णांक[] = नया पूर्णांक[१]; लिखो(क[सत्य]);",
            "स्थिर क: पूर्णांक[] = नया पूर्णांक[१]; क.लंबाई = २;",
            "स्थिर क: सूची<पूर्णांक> = नया सूची<पूर्णांक>(); क.जोड़ो(\"अ\");","स्थिर क: सूची<पूर्णांक> = नया सूची<पूर्णांक>(); क = नया सूची<पूर्णांक>();",
            "स्थिर क: मानचित्र<पाठ,पूर्णांक> = नया मानचित्र<पाठ,पूर्णांक>(); क[१] = २;",
            "स्थिर क: मानचित्र<पाठ,पूर्णांक> = नया मानचित्र<पाठ,पूर्णांक>(); क[\"अ\"] = सत्य;",
            "प्रत्येक (क: पाठ में नया पूर्णांक[१]) {}","प्रत्येक (क: पूर्णांक, ख: पूर्णांक में नया पूर्णांक[१]) {}",
            "प्रत्येक (क: पूर्णांक में १) {}","प्रत्येक (क: पूर्णांक में नया पूर्णांक[१]) { क = २; }",
            "स्थिर क: पूर्णांक[] = नया पूर्णांक[१]; लिखो(क);","स्थिर क: सूची<पूर्णांक> = नया सूची<पूर्णांक>{\"अ\"};",
            "स्थिर क: मानचित्र<पाठ,पूर्णांक> = नया मानचित्र<पाठ,पूर्णांक>{१,२};",
            "स्थिर क: पूर्णांक[] = नया पूर्णांक[]{१:२};"};
        for (String source:cases) assertThrows(CompilationException.class,()->compile("कार्य मुख्य() -> शून्य { "+source+" }"),source);
        for (String source:List.of("वर्ग सूची {} कार्य मुख्य() -> शून्य {}",
                "कार्य लो() -> पूर्णांक { प्रत्येक (क: पूर्णांक में नया पूर्णांक[१]) { लौटाओ क; } } कार्य मुख्य() -> शून्य {}",
                "वर्ग मूल {} वर्ग बच्चा विस्तार मूल {} कार्य मुख्य() -> शून्य { स्थिर क: मूल[] = नया बच्चा[१]; }",
                "कार्य मुख्य() -> शून्य { स्थिर क: सूची<पाठ> = नया सूची<पूर्णांक>(); }"))
            assertThrows(CompilationException.class,()->compile(source),source);
    }
    @Test void collectionErrorsInSpecializationsRetainTheDefiningFileAndLine() {
        var files=List.of(new SourceFile("library.bhash","""
                संकुल पुस्तक;
                सार्वजनिक कार्य गलत<त>(आ: त) -> त {
                    स्थिर क: सूची<त> = नया सूची<त>();
                    क.जोड़ो(सत्य);
                    लौटाओ आ;
                }
                """),new SourceFile("app.bhash","आयात पुस्तक.गलत; कार्य मुख्य() -> शून्य { गलत<पूर्णांक>(१); }"));
        var error=assertThrows(CompilationException.class,()->compile(files));
        assertTrue(error.getMessage().startsWith("library.bhash:4:"),error.getMessage());
    }
}
