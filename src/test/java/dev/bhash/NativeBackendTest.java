// SPDX-License-Identifier: Apache-2.0
package dev.bhash;

import dev.bhash.backend.*;
import dev.bhash.backend.CoreProgram.*;
import dev.bhash.backend.llvm.*;
import dev.bhash.backend.jvm.JvmCompiler;
import dev.bhash.frontend.SourceFile;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.*;

class NativeBackendTest {
    @TempDir Path temporary;
    private static CoreProgram core(String s)throws Exception{return ManagedEmitter.core(List.of(new SourceFile("native.bhash",s)));}
    @Test void corePreservesCheckedOverloadsShadowingAndDecodedLiterals()throws Exception{
        var program=core("""
            कार्य पहचान(अ: पूर्णांक) -> पूर्णांक { लौटाओ अ; }
            कार्य पहचान(अ: पाठ) -> पाठ { लौटाओ अ; }
            कार्य मुख्य() -> शून्य {
                मान अ: पूर्णांक = -९२२३३७२०३६८५४७७५८०८;
                { स्थिर अ: पाठ = पहचान("a\\u0000😀"); लिखो(अ); }
                लिखो(पहचान(अ));
            }
            """);
        assertEquals(3,program.functions().size());
        var main=program.functions().stream().filter(f->f.id()==program.entry()).findFirst().orElseThrow();
        var first=(Assign)main.body().statements().get(0);
        assertEquals(Long.MIN_VALUE,((Constant)first.value()).value());
        var nested=(Block)main.body().statements().get(1);
        var local=(Assign)nested.statements().get(0);
        assertNotEquals(first.binding().id(),local.binding().id());
        var call=(Call)local.value();assertEquals(Type.TEXT,call.type());
        assertEquals("a\0😀",((Constant)call.arguments().get(0)).value());
    }
    @Test void coreUsesExistingTypeAndImmutableBindingChecks(){
        assertThrows(CompilationException.class,()->core("कार्य मुख्य() -> शून्य { स्थिर अ: पूर्णांक = १; अ = २; }"));
        assertThrows(CompilationException.class,()->core("कार्य मुख्य() -> शून्य { स्थिर अ: पूर्णांक = सत्य; }"));
        assertThrows(CompilationException.class,()->core("कार्य मुख्य() -> शून्य { लिखो(अ); }"));
    }
    @Test void unsupportedUnusedDeclarationsAndLibraryCallsHaveSourceLocations(){
        for(String source:List.of(
            "अनुबंध अ<क> {} कार्य मुख्य() -> शून्य {}",
            "कार्य अप्रयुक्त() -> दशमलव { लौटाओ १.२; } कार्य मुख्य() -> शून्य {}",
            "कार्य मुख्य() -> शून्य { लिखो(पढ़ो()); }",
            "आयात मानक.पाठ्य; कार्य मुख्य() -> शून्य { पाठ्य.बाँटो(\"अ\", \"ब\"); }",
            "कार्य मुख्य() -> शून्य { लिखो(१ << २); }",
            "कार्य मुख्य() -> शून्य { लिखो(परिवर्तित<लघुपूर्णांक>(१)); }")){
            var error=assertThrows(CompilationException.class,()->core(source));
            assertTrue(error.getMessage().contains("native.bhash:1:"),error.getMessage());
            assertTrue(error.getMessage().contains("BH9001"),error.getMessage());
        }
    }
    @Test void crossFileVisibilityAndCallsUseExistingResolution()throws Exception{
        var p=ManagedEmitter.core(List.of(
            new SourceFile("library.bhash","संकुल गणना; सार्वजनिक कार्य जोड़(अ: पूर्णांक) -> पूर्णांक { लौटाओ अ + १; }"),
            new SourceFile("app.bhash","आयात गणना.जोड़; कार्य मुख्य() -> शून्य { लिखो(जोड़(२)); }")));
        assertEquals(2,p.functions().size());
        assertTrue(p.functions().stream().anyMatch(f->f.source().file().equals("library.bhash")));
    }
    @Test void coreBuildsPreciseObjectLayoutsAndUsesBarrierStores()throws Exception{
        var p=core("""
            वर्ग अ {
                सार्वजनिक मान न: पूर्णांक;
                सार्वजनिक मान हाँ: तार्किक;
                सार्वजनिक मान पाठ्य: पाठ;
                सार्वजनिक मान अगला: अ;
                निर्माता(न: पूर्णांक){ यह.न = न; }
                सार्वजनिक कार्य स्वयं() -> अ { लौटाओ यह; }
            }
            कार्य मुख्य() -> शून्य { स्थिर क: अ = नया अ(३); क.अगला = क.स्वयं(); लिखो(क.न); }
            """);
        assertEquals(1,p.classes().size());
        assertEquals(List.of(Type.INT,Type.BOOL,Type.TEXT,Type.OBJECT),p.classes().get(0).fields().stream().map(ObjectField::type).toList());
        assertEquals(List.of(8,16,24,32),p.classes().get(0).fields().stream().map(ObjectField::offset).toList());
        String ir=LlvmEmitter.emit(p);
        assertTrue(ir.contains("[2 x i64] [i64 24, i64 32]"),ir);
        assertTrue(ir.contains("call void @bh_reference_store"));
        assertTrue(ir.contains("call ptr @bh_alloc(ptr @class0, i64 40)"));
        assertTrue(ir.contains("call ptr @bh_instance"));
    }
    @Test void nativeObjectProfileKeepsExistingAccessTypeAndConstructionRules(){
        for(String s:List.of(
            "वर्ग अ { निजी मान न: पूर्णांक; } कार्य मुख्य() -> शून्य { स्थिर क: अ = नया अ(); लिखो(क.न); }",
            "वर्ग अ { सार्वजनिक स्थिर न: पूर्णांक = १; } कार्य मुख्य() -> शून्य { स्थिर क: अ = नया अ(); क.न = २; }",
            "वर्ग अ {} वर्ग ब {} कार्य मुख्य() -> शून्य { स्थिर क: अ = नया ब(); }",
            "वर्ग अ { मान न: पूर्णांक; निर्माता(){ यह.क(); } कार्य क() -> शून्य {} } कार्य मुख्य() -> शून्य {}"))
            assertThrows(CompilationException.class,()->core(s));
        for(String s:List.of(
            "वर्ग अ { साझा मान न: पूर्णांक; } कार्य मुख्य() -> शून्य {}",
            "वर्ग अ विस्तार अपवाद {} कार्य मुख्य() -> शून्य {}",
            "अनुबंध अ<क> {} कार्य मुख्य() -> शून्य {}",
            "अभिलेख अ(न: पूर्णांक); कार्य मुख्य() -> शून्य {}",
            "वर्ग अ { मान न: दशमलव; } कार्य मुख्य() -> शून्य {}")){
            var error=assertThrows(CompilationException.class,()->core(s));
            assertTrue(error.getMessage().contains("BH9001"),error.getMessage());
        }
    }
    @Test void heapObjectGraphsAndCyclesSurviveAndAreReclaimed()throws Exception{
        requireTools();
        var result=nativeRun(Files.readString(Path.of("examples/native/heap-objects.bhash")),GC_STRESS);
        assertEquals("कड़ी22 सत्य सत्य\nकड़ी2 १ सत्य\nसत्य\n",result.out());
        var counts=stats(result);
        assertTrue(counts.get("allocations")>10000,counts.toString());
        assertTrue(counts.get("collections")>=counts.get("allocations"),counts.toString());
        assertEquals(0,counts.get("live"));
        assertTrue(counts.get("peak")<10000,counts.toString());
    }
    @Test void objectConstructorsOverloadsDefaultsAndNestedAllocationsMatchJvm()throws Exception{
        requireTools();
        var result=nativeRun("""
            वर्ग खाली {}
            वर्ग अ {
                सार्वजनिक मान न: पूर्णांक;
                सार्वजनिक मान चिह्न: तार्किक;
                सार्वजनिक मान शब्द: पाठ;
                सार्वजनिक मान अगला: अ;
                निर्माता() { लौटाओ; }
                निर्माता(न: पूर्णांक, शब्द: पाठ) { यह.न = न; यह.शब्द = शब्द; }
                सार्वजनिक कार्य स्वयं() -> अ { लौटाओ यह; }
                सार्वजनिक कार्य फल(न: पूर्णांक) -> पूर्णांक { लौटाओ न + यह.न; }
                सार्वजनिक कार्य फल(शब्द: पाठ) -> पाठ { लौटाओ यह.शब्द + शब्द; }
            }
            कार्य पहचान(क: अ, ख: अ) -> अ { लौटाओ क; }
            कार्य मुख्य() -> शून्य {
                स्थिर ख: खाली = नया खाली();
                लिखो(ख == ख, ख != नया खाली());
                स्थिर क: अ = नया अ();
                लिखो(क.न, क.चिह्न, लंबाई(क.शब्द), क.अगला == रिक्त);
                क.अगला = पहचान(नया अ(७, "अ" + "ब"), नया अ(८, "क" + "ख"));
                लिखो(क.अगला.स्वयं().फल(३), क.अगला.फल("ग" + "घ"));
                लिखो(क.अगला == क.अगला.स्वयं(), क != क.अगला);
            }
            """,GC_STRESS);
        assertEquals("सत्य सत्य\n० असत्य ० सत्य\n१० अबगघ\nसत्य सत्य\n",result.out());
        assertEquals(0,stats(result).get("live"));
    }
    @Test void objectFieldReceiversAndArgumentsAreEvaluatedOnceInOrder()throws Exception{
        requireTools();
        var result=nativeRun("""
            वर्ग अ {
                सार्वजनिक मान न: पूर्णांक;
                सार्वजनिक मान बच्चा: अ;
                निर्माता(न: पूर्णांक){ यह.न = न; }
                सार्वजनिक कार्य लो(ख: अ) -> पूर्णांक { लौटाओ यह.न + ख.न; }
            }
            कार्य पहचान(क: अ) -> अ { लिखो(क.न); लौटाओ क; }
            कार्य मुख्य() -> शून्य {
                स्थिर क: अ = नया अ(१);
                पहचान(क).बच्चा = पहचान(नया अ(२));
                लिखो(पहचान(क).लो(पहचान(नया अ(३))));
                लिखो(क.बच्चा.न);
            }
            """,GC_STRESS);
        assertEquals("१\n२\n१\n३\n४\n२\n",result.out());
    }
    @Test void objectGraphTracingHandlesSharingChainsAndRecursiveReceivers()throws Exception{
        requireTools();
        var result=nativeRun("""
            वर्ग क {
                सार्वजनिक मान अगला: ख;
                सार्वजनिक मान नाम: पाठ;
                निर्माता(नाम: पाठ){ यह.नाम = नाम; }
                सार्वजनिक कार्य गिनो(न: पूर्णांक) -> पूर्णांक {
                    यदि (न == ०){ लौटाओ लंबाई(यह.नाम); }
                    स्थिर अस्थायी: क = नया क("x" + अंकपाठ(न));
                    लौटाओ यह.गिनो(न - १) + लंबाई(अस्थायी.नाम);
                }
            }
            वर्ग ख { सार्वजनिक मान बायाँ: क; सार्वजनिक मान दायाँ: क; }
            कार्य मुख्य() -> शून्य {
                स्थिर मूल: क = नया क("मूल" + "😀");
                मान प: क = मूल;
                मान न: पूर्णांक = ०;
                जबतक (न < १२८){
                    स्थिर खंड: ख = नया ख();
                    खंड.बायाँ = नया क("अ" + "ब");
                    खंड.दायाँ = खंड.बायाँ;
                    प.अगला = खंड;
                    प = खंड.बायाँ;
                    न = न + १;
                }
                लिखो(मूल.अगला.बायाँ == मूल.अगला.दायाँ, प.नाम, मूल.गिनो(३२));
                मूल.अगला = रिक्त;
                न = ०;
                जबतक (न < ५०){
                    स्थिर अस्थायी: क = नया क("अ" + अंकपाठ(न));
                    न = न + १;
                    यदि (न == ५०){ विराम; }
                }
                लिखो(मूल.नाम);
            }
            """,GC_STRESS);
        assertTrue(result.out().startsWith("सत्य अब "),result.out());
        assertTrue(result.out().endsWith("मूल😀\n"),result.out());
        assertEquals(0,stats(result).get("live"));
    }
    @Test void nullObjectFieldAndMethodFailuresAreLocatedAndMatchJvm()throws Exception{
        requireTools();
        String prefix="वर्ग अ { सार्वजनिक मान न: पूर्णांक; सार्वजनिक कार्य लो(न: पूर्णांक) -> पूर्णांक { लौटाओ न; } } कार्य अंक() -> पूर्णांक { लिखो(९); लौटाओ १; } कार्य मुख्य() -> शून्य { मान क: अ = रिक्त; ";
        for(String use:List.of("लिखो(क.न);","क.न = अंक();","लिखो(क.लो(अंक()));")){
            var result=nativeRun(prefix+use+"}",GC_STRESS);
            assertEquals(1,result.exit());
            assertTrue(result.err().contains("native.bhash:1:"),result.err());
            assertTrue(result.err().contains("रिक्त वस्तु के सदस्य"),result.err());
        }
    }

    @Test void corePreservesInheritedPrefixesAndStableVirtualSlots()throws Exception{
        var p=core("""
            वर्ग अंतिम विस्तार बीच {
                सार्वजनिक मान साथी: मूल;
                सार्वजनिक अधिलेखन कार्य फल() -> पूर्णांक { लौटाओ आधार.फल() + ३; }
            }
            वर्ग मूल {
                संरक्षित मान अंक: पूर्णांक = १;
                सार्वजनिक मान नाम: पाठ;
                सार्वजनिक कार्य फल() -> पूर्णांक { लौटाओ अंक; }
                सार्वजनिक कार्य दो() -> पूर्णांक { लौटाओ फल() * २; }
            }
            वर्ग बीच विस्तार मूल {
                सार्वजनिक मान अतिरिक्त: पाठ;
                निजी कार्य सहायक() -> पूर्णांक { लौटाओ २; }
                सार्वजनिक अधिलेखन कार्य फल() -> पूर्णांक { लौटाओ सहायक(); }
                सार्वजनिक कार्य नया_फल() -> पाठ { लौटाओ अतिरिक्त; }
            }
            कार्य मुख्य() -> शून्य {
                स्थिर क: मूल = नया अंतिम(); लिखो(क.फल(), क.दो());
            }
            """);
        assertEquals(3,p.classes().size());
        var base=p.classes().get(0);var middle=p.classes().get(1);var leaf=p.classes().get(2);
        assertEquals(base.fields(),middle.fields().subList(0,2));
        assertEquals(middle.fields(),leaf.fields().subList(0,3));
        assertEquals(List.of(8,16,24,32),leaf.fields().stream().map(ObjectField::offset).toList());
        assertEquals(List.of(2,3,3),p.classes().stream().map(c->c.virtualMethods().size()).toList());
        assertEquals(base.virtualMethods().get(1),leaf.virtualMethods().get(1));
        assertNotEquals(base.virtualMethods().get(0),middle.virtualMethods().get(0));
        assertNotEquals(middle.virtualMethods().get(0),leaf.virtualMethods().get(0));
        String ir=LlvmEmitter.emit(p);
        assertTrue(ir.contains("@vtable2 = private constant [3 x ptr]"));
        assertTrue(ir.contains("[3 x i64] [i64 16, i64 24, i64 32]"));
        assertTrue(ir.matches("(?s).*call i64 %x[0-9]+\\(ptr .*"),ir);
    }
    private static List<SourceFile> inheritanceSources(){
        return List.of(
            new SourceFile("base.bhash","संकुल आधारपुस्तक; सार्वजनिक वर्ग मूल { सार्वजनिक मान नाम: पाठ; निर्माता(नाम: पाठ){ यह.नाम = नाम; } सार्वजनिक कार्य फल() -> पाठ { लौटाओ नाम; } }"),
            new SourceFile("derived.bhash","संकुल उपपुस्तक; आयात आधारपुस्तक.मूल; सार्वजनिक वर्ग उप विस्तार मूल { निर्माता() : आधार(\"मूल\") {} सार्वजनिक अधिलेखन कार्य फल() -> पाठ { लौटाओ आधार.फल() + \":उप\"; } }"),
            new SourceFile("app.bhash","आयात आधारपुस्तक.मूल; आयात उपपुस्तक.उप; कार्य मुख्य() -> शून्य { स्थिर क: मूल = नया उप(); लिखो(क.फल()); }"));
    }
    @Test void inheritedClassesAcrossPackagesUseCheckedResolution()throws Exception{
        var p=ManagedEmitter.core(inheritanceSources());
        assertEquals(2,p.classes().size());assertEquals(p.classes().get(0).fields(),p.classes().get(1).fields());
        assertTrue(p.functions().stream().anyMatch(f->f.source().file().equals("derived.bhash")));
    }
    @Test void nativeInheritedClassesAcrossPackagesMatchJvm()throws Exception{
        requireTools();
        var result=nativeRun(inheritanceSources(),GC_STRESS);
        assertEquals("मूल:उप\n",result.out());assertEquals(0,stats(result).get("live"));
    }
    @Test void nativeInheritanceRetainsOverrideAccessAndConstructorDiagnostics(){
        for(String source:List.of(
            "वर्ग अ विस्तार ब {} वर्ग ब विस्तार अ {} कार्य मुख्य() -> शून्य {}",
            "वर्ग अ { सार्वजनिक कार्य फल() -> पूर्णांक { लौटाओ १; } } वर्ग ब विस्तार अ { सार्वजनिक कार्य फल() -> पूर्णांक { लौटाओ २; } } कार्य मुख्य() -> शून्य {}",
            "वर्ग अ {} वर्ग ब विस्तार अ { सार्वजनिक अधिलेखन कार्य फल() -> पूर्णांक { लौटाओ १; } } कार्य मुख्य() -> शून्य {}",
            "वर्ग अ { सार्वजनिक कार्य फल() -> पूर्णांक { लौटाओ १; } } वर्ग ब विस्तार अ { सार्वजनिक अधिलेखन कार्य फल() -> तार्किक { लौटाओ सत्य; } } कार्य मुख्य() -> शून्य {}",
            "वर्ग अ { सार्वजनिक कार्य फल() -> पूर्णांक { लौटाओ १; } } वर्ग ब विस्तार अ { निजी अधिलेखन कार्य फल() -> पूर्णांक { लौटाओ २; } } कार्य मुख्य() -> शून्य {}",
            "वर्ग अ { निजी मान न: पूर्णांक; } वर्ग ब विस्तार अ { सार्वजनिक कार्य फल() -> पूर्णांक { लौटाओ यह.न; } } कार्य मुख्य() -> शून्य {}",
            "वर्ग अ { निर्माता(न: पूर्णांक){} } वर्ग ब विस्तार अ {} कार्य मुख्य() -> शून्य {}",
            "वर्ग अ { निजी निर्माता(){} } वर्ग ब विस्तार अ {} कार्य मुख्य() -> शून्य {}",
            "वर्ग अ { सार्वजनिक कार्य फल() -> पूर्णांक { लौटाओ १; } } वर्ग ब विस्तार अ { निर्माता(){ आधार.फल(); } } कार्य मुख्य() -> शून्य {}",
            "वर्ग अ { निर्माता(न: पूर्णांक){} सार्वजनिक मान न: पूर्णांक; } वर्ग ब विस्तार अ { निर्माता() : आधार(यह.न){} } कार्य मुख्य() -> शून्य {}")){
            var error=assertThrows(CompilationException.class,()->core(source));
            assertTrue(error.getMessage().contains("native.bhash:"),error.getMessage());
        }
    }
    @Test void nativeExistingInheritanceExampleMatchesJvm()throws Exception{
        requireTools();
        var result=nativeRun(Files.readString(Path.of("examples/objects.bhash")),GC_STRESS);
        assertEquals("मनोज १५० १५ १६५\n",result.out());
        assertEquals(0,stats(result).get("live"));
    }
    @Test void nativeVirtualDispatchHandlesOverridesOverloadsAndBaseCalls()throws Exception{
        requireTools();
        var result=nativeRun("""
            वर्ग मूल {
                सार्वजनिक मान अंक: पूर्णांक;
                निजी कार्य सहायक() -> पूर्णांक { लौटाओ ५; }
                सार्वजनिक कार्य निजीफल() -> पूर्णांक { लौटाओ सहायक(); }
                सार्वजनिक कार्य फल() -> पूर्णांक { लौटाओ १; }
                सार्वजनिक कार्य फल(शब्द: पाठ) -> पाठ { लौटाओ "B" + शब्द; }
                सार्वजनिक कार्य दो() -> पूर्णांक { लौटाओ फल() * २; }
                सार्वजनिक कार्य स्वयं() -> मूल { लौटाओ रिक्त; }
                सार्वजनिक कार्य सही() -> तार्किक { लौटाओ असत्य; }
                सार्वजनिक कार्य जमा(न: पूर्णांक) -> शून्य { अंक = न; }
            }
            वर्ग बीच विस्तार मूल {
                सार्वजनिक अधिलेखन कार्य फल() -> पूर्णांक { लौटाओ आधार.फल() + २; }
            }
            वर्ग अंतिम विस्तार बीच {
                सार्वजनिक अधिलेखन कार्य फल() -> पूर्णांक { लौटाओ आधार.फल() + ४; }
                सार्वजनिक अधिलेखन कार्य फल(शब्द: पाठ) -> पाठ { लौटाओ "G" + आधार.फल(शब्द); }
                सार्वजनिक कार्य फल(न: पूर्णांक) -> पूर्णांक { लौटाओ १०० + न; }
                सार्वजनिक अधिलेखन कार्य स्वयं() -> मूल { लौटाओ यह; }
                सार्वजनिक अधिलेखन कार्य सही() -> तार्किक { लौटाओ सत्य; }
                सार्वजनिक अधिलेखन कार्य जमा(न: पूर्णांक) -> शून्य { आधार.जमा(न + १०); }
            }
            वर्ग अलग { सार्वजनिक कार्य फल() -> पाठ { लौटाओ "अलग"; } }
            कार्य मुख्य() -> शून्य {
                स्थिर क: मूल = नया अंतिम();
                क.जमा(५);
                लिखो(क.फल(), क.दो(), क.फल("!" + "?"), क.स्वयं() == क, क.सही(), क.अंक, क.निजीफल());
                स्थिर ख: अंतिम = नया अंतिम();
                लिखो(ख.फल(३), नया अलग().फल());
            }
            """,GC_STRESS);
        assertEquals("७ १४ GB!? सत्य सत्य १५ ५\n१०३ अलग\n",result.out());
    }
    @Test void nativeBaseConstructorChainsInitializeOneObjectInManagedOrder()throws Exception{
        requireTools();
        var result=nativeRun("""
            वर्ग मूल {
                सार्वजनिक मान अंक: पूर्णांक;
                सार्वजनिक मान नाम: पाठ;
                निर्माता(){ अंक = १; लौटाओ; }
                निर्माता(नाम: पाठ){ यह.नाम = नाम; अंक = १०; लिखो("B", नाम); }
            }
            वर्ग बीच विस्तार मूल {
                सार्वजनिक मान अतिरिक्त: पाठ = "अ" + "ब";
                निर्माता() { अंक = अंक + २; }
                निर्माता(नाम: पाठ) : आधार(चरण(नाम)) { लिखो("M", अतिरिक्त); }
            }
            वर्ग अंतिम विस्तार बीच {
                निर्माता(){ अंक = अंक + ४; }
                निर्माता(नाम: पाठ) : आधार(नाम + "G") { लिखो("G", नाम, यह.नाम); }
            }
            कार्य चरण(नाम: पाठ) -> पाठ { लिखो("A", नाम); लौटाओ नाम + "!"; }
            कार्य मुख्य() -> शून्य {
                स्थिर क: अंतिम = नया अंतिम();
                लिखो(क.अंक, क.नाम == "", क.अतिरिक्त);
                स्थिर ख: मूल = नया अंतिम("x" + "y");
                लिखो(ख.नाम, ख.अंक);
            }
            """,GC_STRESS);
        assertEquals("७ सत्य अब\nA xyG\nB xyG!\nM अब\nG xy xyG!\nxyG! १०\n",result.out());
        var single=nativeRun("""
            वर्ग क { सार्वजनिक मान न: पूर्णांक; निर्माता(){ न = १; लौटाओ; } }
            वर्ग ख विस्तार क { निर्माता(){ न = न + २; } }
            वर्ग ग विस्तार ख { निर्माता(){ न = न + ४; } }
            कार्य मुख्य() -> शून्य { स्थिर क: क = नया ग(); लिखो(क.न); }
            """,GC_STRESS);
        assertEquals("७\n",single.out());assertEquals(1,stats(single).get("allocations"));
    }
    @Test void nativeVirtualReceiversRemainRootedAcrossAllocatingArguments()throws Exception{
        requireTools();
        var result=nativeRun("""
            वर्ग मूल {
                सार्वजनिक मान न: पूर्णांक;
                निर्माता(न: पूर्णांक){ यह.न = न; }
                सार्वजनिक कार्य जोड़(ख: मूल) -> पूर्णांक { लौटाओ न + ख.न; }
            }
            वर्ग उप विस्तार मूल {
                निर्माता(न: पूर्णांक) : आधार(न) {}
                सार्वजनिक अधिलेखन कार्य जोड़(ख: मूल) -> पूर्णांक { लौटाओ आधार.जोड़(ख) + १०; }
            }
            कार्य पहचान(क: मूल) -> मूल { लिखो(क.न); लौटाओ क; }
            कार्य मुख्य() -> शून्य { लिखो(पहचान(नया उप(१)).जोड़(पहचान(नया उप(२)))); }
            """,GC_STRESS);
        assertEquals("१\n२\n१३\n",result.out());
        var failure=nativeRun("""
            वर्ग मूल { सार्वजनिक कार्य जोड़(ख: मूल) -> पूर्णांक { लौटाओ १; } }
            वर्ग उप विस्तार मूल { सार्वजनिक अधिलेखन कार्य जोड़(ख: मूल) -> पूर्णांक { लौटाओ २; } }
            कार्य बनाओ() -> मूल { लिखो(९); लौटाओ नया उप(); }
            कार्य मुख्य() -> शून्य { स्थिर क: मूल = रिक्त; लिखो(क.जोड़(बनाओ())); }
            """,GC_STRESS);
        assertEquals(1,failure.exit());assertEquals("",failure.out());
        assertTrue(failure.err().contains("native.bhash:"));assertTrue(failure.err().contains("रिक्त वस्तु के सदस्य"));
    }
    @Test void nativeInheritedReferenceFieldsAndVirtualCallsSurviveCollection()throws Exception{
        requireTools();
        var result=nativeRun(Files.readString(Path.of("examples/native/inheritance.bhash")),GC_STRESS);
        assertEquals("मूल😀:आधार:विशेष!\nसाथी:आधार:ख! सत्य\nमूल😀:आधार:विशेष! साथी:आधार:ख!\n",result.out());
        var counts=stats(result);assertTrue(counts.get("allocations")>10000,counts.toString());
        assertTrue(counts.get("collections")>=counts.get("allocations"),counts.toString());
        assertEquals(0,counts.get("live"));assertTrue(counts.get("peak")<20000,counts.toString());
    }

    @Test void coreIncludesNominalInterfaceClosureAndResolvedDispatch()throws Exception{
        var p=core("""
            अनुबंध अ { कार्य नाम() -> पाठ; }
            अनुबंध ब विस्तार अ { कार्य अंक() -> पूर्णांक; }
            वर्ग मूल पालन ब {
                सार्वजनिक कार्य नाम() -> पाठ { लौटाओ "मूल"; }
                सार्वजनिक कार्य अंक() -> पूर्णांक { लौटाओ १; }
            }
            वर्ग उप विस्तार मूल {
                सार्वजनिक अधिलेखन कार्य नाम() -> पाठ { लौटाओ "उप"; }
            }
            वर्ग अलग { सार्वजनिक कार्य नाम() -> पाठ { लौटाओ "अलग"; } }
            कार्य मुख्य() -> शून्य { स्थिर क: अ = नया उप(); लिखो(क.नाम(), प्रकारहै<ब>(क)); }
            """);
        var base=p.classes().get(0);var child=p.classes().get(1);var other=p.classes().get(2);
        var contracts=p.runtimeTypes().stream().filter(RuntimeType::contract).map(RuntimeType::id).toList();
        assertEquals(2,contracts.size());
        assertTrue(child.assignableTypes().containsAll(contracts));
        assertTrue(child.assignableTypes().containsAll(List.of(base.id(),child.id())));
        assertFalse(child.assignableTypes().contains(other.id()));
        assertTrue(child.interfaceMethods().stream().allMatch(id->id>=0));
        assertNotEquals(base.interfaceMethods().get(0),child.interfaceMethods().get(0));
        assertTrue(other.interfaceMethods().stream().allMatch(id->id<0));
        assertTrue(LlvmEmitter.emit(p).contains("call i1 @bhash_is_type"));
    }
    @Test void nativeInterfacesPreserveExistingContractDiagnostics(){
        for(String source:List.of(
            "अनुबंध अ { कार्य न() -> पूर्णांक; } वर्ग क पालन अ {}",
            "अनुबंध अ { कार्य न() -> पूर्णांक; } वर्ग क पालन अ { निजी कार्य न() -> पूर्णांक { लौटाओ १; } }",
            "अनुबंध अ { कार्य न() -> पूर्णांक; } वर्ग क पालन अ { सार्वजनिक कार्य न() -> पाठ { लौटाओ \"अ\"; } }",
            "अनुबंध अ विस्तार ब {} अनुबंध ब विस्तार अ {}",
            "अनुबंध अ { कार्य न() -> पूर्णांक { लौटाओ १; } }",
            "अनुबंध अ {} कार्य चूक() -> शून्य { स्थिर क: अ = नया अ(); }",
            "अनुबंध अ { कार्य न() -> पूर्णांक; } अनुबंध ब { कार्य न() -> पाठ; } अनुबंध ग विस्तार अ, ब {}"))
            assertThrows(CompilationException.class,()->core(source+" कार्य मुख्य() -> शून्य {}"));
        assertThrows(CompilationException.class,()->core("कार्य मुख्य() -> शून्य { लिखो(सुरक्षितरूप<पूर्णांक>(१)); }"));
        var unsupported=assertThrows(CompilationException.class,()->core("कार्य मुख्य() -> शून्य { स्थिर क: दशमलव = १.५; }"));
        assertTrue(unsupported.getMessage().contains("BH9001"));
    }
    @Test void nativeInterfaceDiamondsRedeclarationsAndMultipleContractsMatchJvm()throws Exception{
        requireTools();
        var result=nativeRun("""
            अनुबंध मूलनाम { कार्य नाम() -> पाठ; }
            अनुबंध बायाँ विस्तार मूलनाम { कार्य नाम() -> पाठ; }
            अनुबंध दायाँ विस्तार मूलनाम { कार्य अंक() -> पूर्णांक; }
            अनुबंध हीरा विस्तार बायाँ, दायाँ { कार्य स्वयं() -> मूलनाम; }
            अनुबंध अंकनाम { कार्य नाम() -> पूर्णांक; }
            वर्ग गिनक पालन अंकनाम { सार्वजनिक कार्य नाम() -> पूर्णांक { लौटाओ ९; } }
            अनुबंध अतिरिक्त { कार्य सही() -> तार्किक; कार्य रखो(न: पूर्णांक) -> शून्य; }
            वर्ग आधारवर्ग पालन हीरा, अतिरिक्त {
                सार्वजनिक मान न: पूर्णांक;
                सार्वजनिक कार्य नाम() -> पाठ { लौटाओ "आधार"; }
                सार्वजनिक कार्य अंक() -> पूर्णांक { लौटाओ न; }
                सार्वजनिक कार्य स्वयं() -> मूलनाम { लौटाओ यह; }
                सार्वजनिक कार्य सही() -> तार्किक { लौटाओ सत्य; }
                सार्वजनिक कार्य रखो(न: पूर्णांक) -> शून्य { यह.न = न; }
            }
            वर्ग उप विस्तार आधारवर्ग {
                सार्वजनिक अधिलेखन कार्य नाम() -> पाठ { लौटाओ "उप"; }
            }
            कार्य मुख्य() -> शून्य {
                स्थिर ग: अंकनाम = नया गिनक();
                स्थिर क: हीरा = नया उप();
                स्थिर अ: मूलनाम = क; स्थिर ब: बायाँ = क; स्थिर द: दायाँ = क;
                स्थिर ज: अतिरिक्त = परिवर्तित<अतिरिक्त>(अ); ज.रखो(७);
                लिखो(अ.नाम(), ब.नाम(), द.नाम(), क.नाम(), द.अंक(), क.स्वयं() == अ, ज.सही(), ग.नाम());
            }
            """,GC_STRESS);
        assertEquals("उप उप उप उप ७ सत्य सत्य ९\n",result.out());
    }
    @Test void nativeReferenceTypeTestsCastsAndNullsMatchJvm()throws Exception{
        requireTools();
        var result=nativeRun("""
            अनुबंध नामी { कार्य नाम() -> पाठ; }
            वर्ग मूल {}
            वर्ग बिल्ली विस्तार मूल पालन नामी { सार्वजनिक कार्य नाम() -> पाठ { लौटाओ "बिल्ली"; } }
            वर्ग अन्य { सार्वजनिक कार्य नाम() -> पाठ { लौटाओ "अन्य"; } }
            कार्य मुख्य() -> शून्य {
                स्थिर क: मूल = नया बिल्ली();
                स्थिर न: नामी = परिवर्तित<नामी>(क);
                लिखो(प्रकारहै<बिल्ली>(क), प्रकारहै<मूल>(न), प्रकारहै<नामी>(क), प्रकारहै<अन्य>(क));
                लिखो(न.नाम(), सुरक्षितरूप<अन्य>(न) == रिक्त, परिवर्तित<बिल्ली>(न) == क);
                लिखो(प्रकारहै<नामी>(नया अन्य()), सुरक्षितरूप<नामी>(नया अन्य()) == रिक्त);
                स्थिर खाली: नामी = रिक्त;
                लिखो(प्रकारहै<नामी>(खाली), परिवर्तित<बिल्ली>(खाली) == रिक्त, सुरक्षितरूप<बिल्ली>(खाली) == रिक्त);
                लिखो(सुरक्षितरूप<नामी>(१) == रिक्त, सुरक्षितरूप<मूल>("पाठ") == रिक्त);
            }
            """,GC_STRESS);
        assertEquals("सत्य सत्य सत्य असत्य\nबिल्ली सत्य सत्य\nअसत्य सत्य\nअसत्य सत्य सत्य\nसत्य सत्य\n",result.out());
    }
    @Test void nativeScalarTestsAndExistingScalarConversionsMatchJvm()throws Exception{
        requireTools();
        var result=nativeRun("""
            वर्ग क {}
            कार्य मुख्य() -> शून्य {
                लिखो(प्रकारहै<पूर्णांक>(१), प्रकारहै<तार्किक>(सत्य), प्रकारहै<पाठ>("अ" + "ब"));
                लिखो(प्रकारहै<पाठ>(१), प्रकारहै<पूर्णांक>(सत्य), प्रकारहै<पूर्णांक>(नया क()), प्रकारहै<क>("पाठ"), प्रकारहै<पूर्णांक>(रिक्त));
                लिखो(परिवर्तित<पूर्णांक>(५), परिवर्तित<तार्किक>(असत्य), परिवर्तित<पाठ>("पाठ"));
                लिखो(परिवर्तित<पाठ>(-४२), परिवर्तित<पाठ>(सत्य), परिवर्तित<पूर्णांक>("-४२"));
            }
            """,GC_STRESS);
        assertEquals("सत्य सत्य सत्य\nअसत्य असत्य असत्य असत्य असत्य\n५ असत्य पाठ\n-42 सत्य -४२\n",result.out());
    }
    @Test void nativeInterfaceReceiversCastsAndOperandsEvaluateOnceAndStayRooted()throws Exception{
        requireTools();
        var result=nativeRun("""
            अनुबंध गणक { कार्य जोड़(ख: गणक) -> पाठ; कार्य नाम() -> पाठ; }
            वर्ग क पालन गणक {
                सार्वजनिक मान शब्द: पाठ;
                निर्माता(शब्द: पाठ){ यह.शब्द = शब्द; }
                सार्वजनिक कार्य नाम() -> पाठ { लौटाओ शब्द; }
                सार्वजनिक कार्य जोड़(ख: गणक) -> पाठ { लौटाओ शब्द + ख.नाम(); }
            }
            कार्य लो(अंक: पूर्णांक) -> गणक { लिखो(अंक); लौटाओ नया क("क" + अंकपाठ(अंक)); }
            कार्य मुख्य() -> शून्य {
                लिखो(प्रकारहै<क>(लो(१)));
                लिखो(परिवर्तित<क>(लो(२)).नाम());
                लिखो(सुरक्षितरूप<क>(लो(३)).नाम());
                लिखो(लो(४).जोड़(लो(५)));
            }
            """,GC_STRESS);
        assertEquals("१\nसत्य\n२\nक2\n३\nक3\n४\n५\nक4क5\n",result.out());
        assertEquals(0,stats(result).get("live"));
    }
    @Test void nativeFailedCheckedCastsAreLocatedAndDoNotPrintPartialArguments()throws Exception{
        requireTools();
        for(String source:List.of(
            "अनुबंध अ { कार्य नाम() -> पाठ; } वर्ग क { सार्वजनिक कार्य नाम() -> पाठ { लौटाओ \"क\"; } } कार्य मुख्य() -> शून्य { लिखो(\"अधूरा\", परिवर्तित<अ>(नया क()).नाम()); }",
            "वर्ग क {} वर्ग ख {} कार्य मुख्य() -> शून्य { स्थिर क: क = परिवर्तित<क>(नया ख()); }",
            "वर्ग क {} कार्य मुख्य() -> शून्य { स्थिर क: क = परिवर्तित<क>(१); }",
            "कार्य मुख्य() -> शून्य { लिखो(\"अधूरा\", परिवर्तित<तार्किक>(१)); }")){
            var result=nativeRun(source,GC_STRESS);assertEquals(1,result.exit());assertEquals("",result.out());
            assertTrue(result.err().contains("native.bhash:"),result.err());
            assertTrue(result.err().contains("रनटाइम प्रकार परिवर्तन असफल हुआ।"),result.err());
        }
    }
    @Test void nativeNullInterfaceReceiverFailsBeforeEvaluatingArguments()throws Exception{
        requireTools();
        var result=nativeRun("""
            अनुबंध अ { कार्य जोड़(ब: अ) -> पूर्णांक; }
            वर्ग क पालन अ { सार्वजनिक कार्य जोड़(ब: अ) -> पूर्णांक { लौटाओ १; } }
            कार्य लो() -> अ { लिखो(९); लौटाओ नया क(); }
            कार्य मुख्य() -> शून्य { स्थिर क: अ = रिक्त; लिखो(क.जोड़(लो())); }
            """,GC_STRESS);
        assertEquals(1,result.exit());assertEquals("",result.out());assertTrue(result.err().contains("रिक्त वस्तु के सदस्य"));
    }
    @Test void nativeInterfaceGraphsAndRepeatedCastsSurviveCollection()throws Exception{
        requireTools();
        var result=nativeRun(Files.readString(Path.of("examples/native/interfaces.bhash")),GC_STRESS);
        assertEquals("[मूल😀:विशेष!] साथी:ख! सत्य\nसत्य सत्य सत्य सत्य\n[मूल😀:विशेष!] साथी:ख!\n",result.out());
        var counts=stats(result);assertTrue(counts.get("allocations")>10000,counts.toString());
        assertTrue(counts.get("collections")>=counts.get("allocations"),counts.toString());
        assertEquals(0,counts.get("live"));assertTrue(counts.get("peak")<20000,counts.toString());
    }

    private static List<SourceFile> interfaceSources(){
        return List.of(
            new SourceFile("contracts.bhash","संकुल अनुबंधपुस्तक; सार्वजनिक अनुबंध नामी { कार्य नाम() -> पाठ; } सार्वजनिक अनुबंध सज्जा विस्तार नामी { कार्य स्वयं() -> नामी; }"),
            new SourceFile("base.bhash","संकुल मूलपुस्तक; आयात अनुबंधपुस्तक.नामी; आयात अनुबंधपुस्तक.सज्जा; सार्वजनिक वर्ग मूल पालन सज्जा { सार्वजनिक कार्य नाम() -> पाठ { लौटाओ \"मूल\"; } सार्वजनिक कार्य स्वयं() -> नामी { लौटाओ यह; } }"),
            new SourceFile("derived.bhash","संकुल उपपुस्तक; आयात मूलपुस्तक.मूल; सार्वजनिक वर्ग उप विस्तार मूल { सार्वजनिक अधिलेखन कार्य नाम() -> पाठ { लौटाओ \"उप\"; } }"),
            new SourceFile("app.bhash","आयात अनुबंधपुस्तक.नामी; आयात अनुबंधपुस्तक.सज्जा; आयात मूलपुस्तक.मूल; आयात उपपुस्तक.उप; कार्य मुख्य() -> शून्य { स्थिर क: सज्जा = नया उप(); लिखो(क.नाम(), प्रकारहै<मूल>(क), परिवर्तित<नामी>(क.स्वयं()) == क); }"));
    }
    @Test void nativeInterfacesAcrossPackagesMatchJvm()throws Exception{
        requireTools();var result=nativeRun(interfaceSources(),GC_STRESS);
        assertEquals("उप सत्य सत्य\n",result.out());assertEquals(0,stats(result).get("live"));
    }
    @Test void nativeRepeatedTypeChecksAndCastsDoNotAllocate()throws Exception{
        requireTools();
        var result=nativeRun("""
            अनुबंध अ { कार्य अंक() -> पूर्णांक; }
            वर्ग क पालन अ { सार्वजनिक कार्य अंक() -> पूर्णांक { लौटाओ १; } }
            कार्य मुख्य() -> शून्य {
                स्थिर क: अ = नया क();
                मान न: पूर्णांक = ०; मान योग: पूर्णांक = ०;
                जबतक (न < ५०००){
                    यदि (प्रकारहै<क>(क)){ योग = योग + परिवर्तित<क>(क).अंक(); }
                    स्थिर ज: अ = सुरक्षितरूप<अ>(क);
                    यदि (ज != क){ त्रुटि("पहचान बदली"); }
                    न = न + १;
                }
                लिखो(योग);
            }
            """,GC_STRESS);
        assertEquals("५०००\n",result.out());assertEquals(1,stats(result).get("allocations"));
        assertEquals(0,stats(result).get("live"));
    }

    @Test void coreUniversalContextsBoxScalarsAndPreserveReferenceViews()throws Exception{
        var p=core("""
            वर्ग क { सार्वजनिक मान व: सर्ववस्तु = १; }
            कार्य पहचान(व: सर्ववस्तु) -> सर्ववस्तु { लौटाओ व; }
            कार्य मुख्य() -> शून्य {
                मान व: सर्ववस्तु = १; व = सत्य;
                स्थिर क: क = नया क(); क.व = "अ";
                पहचान(२); स्थिर ख: सर्ववस्तु = क;
            }
            """);
        var main=p.functions().stream().filter(f->f.id()==p.entry()).findFirst().orElseThrow();
        assertInstanceOf(Box.class,((Assign)main.body().statements().get(0)).value());
        assertEquals(Type.ANY,((Assign)main.body().statements().get(0)).binding().type());
        String ir=LlvmEmitter.emit(p);
        assertTrue(ir.contains("@box_gc3"));assertTrue(ir.contains("@bhash_any_equal"));
        assertTrue(ir.contains("call void @bh_reference_store"));
        assertTrue(p.classes().get(0).assignableTypes().contains(-4));
    }
    @Test void nativeBoxedScalarsExactTypesNullsAndEqualityMatchJvm()throws Exception{
        requireTools();
        var result=nativeRun("""
            वर्ग क {}
            कार्य मुख्य() -> शून्य {
                स्थिर न: सर्ववस्तु = -९२२३३७२०३६८५४७७५८०८;
                स्थिर हाँ: सर्ववस्तु = सत्य; स्थिर नहीं: सर्ववस्तु = असत्य;
                स्थिर शब्द: सर्ववस्तु = "a\\u0000" + "😀क";
                स्थिर खाली: सर्ववस्तु = रिक्त;
                लिखो(परिवर्तित<पूर्णांक>(न), परिवर्तित<तार्किक>(हाँ), परिवर्तित<तार्किक>(नहीं), परिवर्तित<पाठ>(शब्द));
                लिखो(प्रकारहै<पूर्णांक>(न), प्रकारहै<तार्किक>(न), प्रकारहै<पाठ>(शब्द), प्रकारहै<सर्ववस्तु>(न), प्रकारहै<सर्ववस्तु>(खाली));
                लिखो(न == -९२२३३७२०३६८५४७७५८०८, -९२२३३७२०३६८५४७७५८०८ == न, हाँ == सत्य, असत्य == नहीं);
                लिखो(शब्द == "a\\u0000😀क", "a\\u0000😀क" == शब्द, हाँ != न, खाली == रिक्त, रिक्त == खाली);
                लिखो(न == परिवर्तित<सर्ववस्तु>(-९२२३३७२०३६८५४७७५८०८), हाँ == सुरक्षितरूप<सर्ववस्तु>(सत्य), न != परिवर्तित<सर्ववस्तु>(१));
                लिखो(सुरक्षितरूप<क>(न) == रिक्त, सुरक्षितरूप<क>(शब्द) == रिक्त, सुरक्षितरूप<सर्ववस्तु>(खाली) == रिक्त);
                लिखो(परिवर्तित<क>(खाली) == रिक्त, परिवर्तित<सर्ववस्तु>(खाली) == रिक्त);
            }
            """,GC_STRESS);
        assertEquals(0,result.exit());assertEquals(0,stats(result).get("live"));
    }
    @Test void nativeBoxingAcrossConstructorsFieldsVirtualAndInterfaceCallsMatchesJvm()throws Exception{
        requireTools();
        var result=nativeRun("""
            अनुबंध पेटी { कार्य दो(व: सर्ववस्तु) -> सर्ववस्तु; }
            वर्ग आधारवर्ग पालन पेटी {
                सार्वजनिक मान व: सर्ववस्तु;
                सार्वजनिक मान आरंभ: सर्ववस्तु = "आर" + "ंभ";
                निर्माता(व: सर्ववस्तु) { यह.व = व; }
                सार्वजनिक कार्य दो(व: सर्ववस्तु) -> सर्ववस्तु { लौटाओ व; }
                सार्वजनिक कार्य सीधा() -> सर्ववस्तु { लौटाओ सत्य; }
            }
            वर्ग उप विस्तार आधारवर्ग {
                निर्माता() : आधार(७) {}
                सार्वजनिक अधिलेखन कार्य दो(व: सर्ववस्तु) -> सर्ववस्तु { यह.व = व; लौटाओ व; }
            }
            कार्य पहचान(व: सर्ववस्तु) -> सर्ववस्तु { लौटाओ व; }
            कार्य पाठदो() -> सर्ववस्तु { लौटाओ "मू" + "ल😀"; }
            कार्य मुख्य() -> शून्य {
                स्थिर क: उप = नया उप(); स्थिर अ: आधारवर्ग = क; स्थिर प: पेटी = क;
                लिखो(परिवर्तित<पूर्णांक>(क.व), परिवर्तित<पाठ>(क.आरंभ), परिवर्तित<तार्किक>(अ.सीधा()));
                लिखो(परिवर्तित<पाठ>(प.दो("अ" + "ब")), परिवर्तित<पूर्णांक>(अ.दो(८)), परिवर्तित<पूर्णांक>(क.व));
                स्थिर व: सर्ववस्तु = प;
                लिखो(व == क, क == व, व == प, प्रकारहै<उप>(व), प्रकारहै<आधारवर्ग>(व), प्रकारहै<पेटी>(व), प्रकारहै<सर्ववस्तु>(क));
                लिखो(परिवर्तित<पाठ>(परिवर्तित<पेटी>(व).दो(पाठदो())), परिवर्तित<तार्किक>(पहचान(असत्य)));
                स्थिर दूसरा: सर्ववस्तु = नया उप(); लिखो(व != दूसरा, सुरक्षितरूप<पेटी>(व) == प);
            }
            """,GC_STRESS);
        assertEquals("७ आरंभ सत्य\nअब ८ ८\nसत्य सत्य सत्य सत्य सत्य सत्य सत्य\nमूल😀 असत्य\nसत्य सत्य\n",result.out());
        assertEquals(0,stats(result).get("live"));
    }
    @Test void nativeBoxingEvaluationOrderAndUnboxedTextStayRooted()throws Exception{
        requireTools();
        var result=nativeRun("""
            वर्ग गिनक {
                सार्वजनिक मान न: पूर्णांक;
                सार्वजनिक कार्य अगला() -> पूर्णांक { न = न + १; लिखो(न); लौटाओ न; }
                सार्वजनिक कार्य शब्द() -> पाठ { न = न + १; लिखो(न); लौटाओ "क" + अंकपाठ(न); }
            }
            कार्य जोड़(अ: सर्ववस्तु, ब: सर्ववस्तु) -> पाठ { लौटाओ परिवर्तित<पाठ>(अ) + परिवर्तित<पाठ>(ब); }
            कार्य मुख्य() -> शून्य {
                स्थिर ग: गिनक = नया गिनक();
                स्थिर व: सर्ववस्तु = १;
                लिखो(व == ग.अगला(), ग.अगला() == व);
                लिखो(जोड़(ग.शब्द(), ग.शब्द()));
                लिखो(प्रकारहै<पूर्णांक>(परिवर्तित<सर्ववस्तु>(ग.अगला())));
                मान बचा: पाठ = "";
                { स्थिर डिब्बा: सर्ववस्तु = ग.शब्द(); बचा = परिवर्तित<पाठ>(डिब्बा); }
                मान न: पूर्णांक = ०;
                जबतक (न < १०००) { स्थिर क्षण: सर्ववस्तु = "x" + अंकपाठ(न); न = न + १; }
                लिखो(बचा, ग.न);
            }
            """,GC_STRESS);
        assertEquals("१\n२\nसत्य असत्य\n३\n४\nक3क4\n५\nसत्य\n६\nक6 ६\n",result.out());
        assertEquals(0,stats(result).get("live"));
    }
    @Test void nativeBoxedReferencesAndCyclicUniversalFieldsSurviveCollection()throws Exception{
        requireTools();
        var result=nativeRun(Files.readString(Path.of("examples/native/boxing.bhash")),GC_STRESS);
        assertEquals("४२ सत्य मूल😀 सत्य\nमूल😀 सत्य २९९९\n",result.out());
        var s=stats(result);assertTrue(s.get("allocations")>12000,result.err());
        assertTrue(s.get("collections")>=s.get("allocations"),result.err());
        assertTrue(s.get("peak")<10000,result.err());assertEquals(0,s.get("live"));
    }
    @Test void nativeBoxedCheckedCastFailuresAreExactAndLocated()throws Exception{
        requireTools();
        for(String expression:List.of(
            "परिवर्तित<पूर्णांक>(परिवर्तित<सर्ववस्तु>(\"42\"))",
            "परिवर्तित<पाठ>(परिवर्तित<सर्ववस्तु>(४२))",
            "परिवर्तित<तार्किक>(परिवर्तित<सर्ववस्तु>(१))",
            "परिवर्तित<पूर्णांक>(परिवर्तित<सर्ववस्तु>(सत्य))",
            "परिवर्तित<पूर्णांक>(परिवर्तित<सर्ववस्तु>(रिक्त))",
            "परिवर्तित<तार्किक>(परिवर्तित<सर्ववस्तु>(रिक्त))",
            "परिवर्तित<पाठ>(परिवर्तित<सर्ववस्तु>(रिक्त))",
            "परिवर्तित<क>(परिवर्तित<सर्ववस्तु>(१))",
            "परिवर्तित<अ>(परिवर्तित<सर्ववस्तु>(\"अ\"))",
            "परिवर्तित<पाठ>(परिवर्तित<सर्ववस्तु>(नया क()))")){
            var result=nativeRun("अनुबंध अ {} वर्ग क {} कार्य मुख्य() -> शून्य { लिखो(\"अधूरा\", प्रकारहै<सर्ववस्तु>("+expression+")); }",GC_STRESS);
            assertEquals(1,result.exit());assertEquals("",result.out());
            assertTrue(result.err().contains("native.bhash:1:"));
            assertTrue(result.err().contains("रनटाइम प्रकार परिवर्तन असफल हुआ।"));
        }
    }

    private static List<SourceFile> boxingSources(){
        return List.of(
            new SourceFile("library.bhash","संकुल सर्वपुस्तक; सार्वजनिक अनुबंध पेटी { कार्य दो(व: सर्ववस्तु) -> सर्ववस्तु; } सार्वजनिक वर्ग क पालन पेटी { सार्वजनिक कार्य दो(व: सर्ववस्तु) -> सर्ववस्तु { लौटाओ व; } } सार्वजनिक कार्य पहचान(व: सर्ववस्तु) -> सर्ववस्तु { लौटाओ व; }"),
            new SourceFile("app.bhash","आयात सर्वपुस्तक.पेटी; आयात सर्वपुस्तक.क; आयात सर्वपुस्तक.पहचान; कार्य मुख्य() -> शून्य { स्थिर प: पेटी = नया क(); स्थिर व: सर्ववस्तु = प; लिखो(परिवर्तित<पूर्णांक>(पहचान(७)), परिवर्तित<पाठ>(परिवर्तित<पेटी>(व).दो(\"अ\" + \"ब\")), व == प); }"));
    }
    @Test void nativeBoxingAcrossPackagesMatchesJvm()throws Exception{
        requireTools();var result=nativeRun(boxingSources(),GC_STRESS);
        assertEquals("७ अब सत्य\n",result.out());assertEquals(0,stats(result).get("live"));
    }
    @Test void nativeUnboxingAndReferenceViewsDoNotAllocate()throws Exception{
        requireTools();
        var result=nativeRun("""
            वर्ग क {}
            कार्य पहचान(व: सर्ववस्तु) -> सर्ववस्तु { लौटाओ व; }
            कार्य मुख्य() -> शून्य {
                स्थिर क: क = नया क(); स्थिर व: सर्ववस्तु = क;
                स्थिर अंक: सर्ववस्तु = ७; स्थिर हाँ: सर्ववस्तु = सत्य; स्थिर शब्द: सर्ववस्तु = "अ";
                मान न: पूर्णांक = ०; मान योग: पूर्णांक = ०;
                जबतक (न < ५०००) {
                    यदि (परिवर्तित<तार्किक>(हाँ) && प्रकारहै<पूर्णांक>(अंक)) { योग = योग + परिवर्तित<पूर्णांक>(अंक); }
                    यदि (पहचान(व) != क || परिवर्तित<क>(व) != क || सुरक्षितरूप<क>(व) != क || परिवर्तित<सर्ववस्तु>(व) != व) { त्रुटि("पहचान"); }
                    यदि (परिवर्तित<पाठ>(शब्द) != "अ") { त्रुटि("पाठ"); }
                    न = न + १;
                }
                लिखो(योग);
            }
            """,GC_STRESS);
        assertEquals("३५०००\n",result.out());assertEquals(4,stats(result).get("allocations"));
        assertEquals(0,stats(result).get("live"));
    }

    private void requireTools()throws Exception{
        boolean required=Boolean.parseBoolean(System.getenv("BHASH_REQUIRE_NATIVE"));
        boolean supported=System.getProperty("os.name").equals("Linux") && Set.of("amd64","x86_64").contains(System.getProperty("os.arch"));
        if(required)assertTrue(supported,"Native CI requires Linux x86-64");else assumeTrue(supported);
        for(String tool:List.of(System.getenv().getOrDefault("BHASH_CLANG","clang-18"),System.getenv().getOrDefault("BHASH_LLVM_OPT","opt-18"))){
            boolean available;
            try{Process p=new ProcessBuilder(tool,"--version").redirectErrorStream(true).start();p.getInputStream().readAllBytes();available=p.waitFor(10,TimeUnit.SECONDS)&&p.exitValue()==0;}
            catch(java.io.IOException e){available=false;}
            if(required)assertTrue(available,"Missing "+tool);else assumeTrue(available,"Missing "+tool);
        }
    }
    private record Result(int exit,String out,String err){}
    private Result nativeRun(String source,String...arguments)throws Exception{
        return nativeRun(source,Map.of(),arguments);
    }
    private Result nativeRun(String source,Map<String,String> environment,String...arguments)throws Exception{
        return nativeRun(List.of(new SourceFile("native.bhash",source)),environment,arguments);
    }
    private Result nativeRun(List<SourceFile> sources,Map<String,String> environment,String...arguments)throws Exception{
        Path exe=temporary.resolve("application");
        new NativeCompiler(true).compile(sources,exe);
        var command=new ArrayList<String>();command.add(exe.toString());command.addAll(List.of(arguments));
        Path out=temporary.resolve("out"),err=temporary.resolve("err");
        var builder=new ProcessBuilder(command).redirectOutput(out.toFile()).redirectError(err.toFile());
        builder.environment().putAll(environment);
        var p=builder.start();
        assertTrue(p.waitFor(15,TimeUnit.SECONDS));
        var result=new Result(p.exitValue(),Files.readString(out),Files.readString(err));
        if(arguments.length==0){
            Path jar=temporary.resolve("reference.jar");
            new JvmCompiler().compile(sources,jar);
            Path referenceOut=temporary.resolve("reference.out"),referenceErr=temporary.resolve("reference.err");
            var reference=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin","java").toString(),"-jar",jar.toString())
                .redirectOutput(referenceOut.toFile()).redirectError(referenceErr.toFile()).start();
            assertTrue(reference.waitFor(15,TimeUnit.SECONDS));
            assertEquals(reference.exitValue(),result.exit(),"Native/JVM exit mismatch");
            assertEquals(Files.readString(referenceOut),result.out(),"Native/JVM output mismatch");
            if(result.exit()!=0)assertTrue(Files.readString(referenceErr).startsWith("भाष: "));
        }
        return result;
    }
    @Test void llvmPreservesTextOverloadsNestedLogicAndArgumentEvaluationOrder()throws Exception{
        requireTools();
        var result=nativeRun("""
            कार्य पहचान(अ: पाठ) -> पाठ { लौटाओ अ; }
            कार्य पहचान(अ: पूर्णांक) -> पूर्णांक { लौटाओ अ; }
            कार्य अंक(अ: पूर्णांक) -> पूर्णांक { लिखो(अ); लौटाओ अ; }
            कार्य मुख्य() -> शून्य {
                लिखो(अंक(१), अंक(२));
                स्थिर अ: पाठ = पहचान("a\\u0000😀");
                { स्थिर अ: पूर्णांक = पहचान(३); लिखो(अ); }
                लिखो(अ, सत्य && (असत्य || सत्य), असत्य || (सत्य && सत्य));
            }
            """);
        assertEquals("१\n२\n१ २\n३\na\0😀 सत्य सत्य\n",result.out());
    }
    @Test void llvmExecutesFunctionsControlFlowUnicodeAndBoundaries()throws Exception{
        requireTools();
        Path artifact=Path.of("target/native-tests/factorial.ll");
        Files.createDirectories(artifact.getParent());
        Files.writeString(artifact,LlvmEmitter.emit(core(Files.readString(Path.of("examples/factorial.bhash")))),StandardCharsets.UTF_8);
        var verifier=new ProcessBuilder(System.getenv().getOrDefault("BHASH_LLVM_OPT","opt-18"),
            "-passes=verify","-disable-output",artifact.toString()).inheritIO().start();
        assertTrue(verifier.waitFor(15,TimeUnit.SECONDS));assertEquals(0,verifier.exitValue());
        var result=nativeRun("""
            कार्य गुणनफल(न: पूर्णांक) -> पूर्णांक {
                यदि (न <= १) { लौटाओ १; } अन्यथा { लौटाओ न * गुणनफल(न - १); }
            }
            कार्य मुख्य() -> शून्य {
                लिखो("नमस्ते, दुनिया!", "a\\u0000😀" == "a\\u0000😀");
                लिखो(गुणनफल(५), ९२२३३७२०३६८५४७७५८०७ + १, -९२२३३७२०३६८५४७७५८०८ / -१);
                लिखो(-७ / ३, -७ % ३);
                मान न: पूर्णांक = ०;
                जबतक (न < ५) {
                    न = न + १;
                    यदि (न == २) { जारी; }
                    यदि (न == ४) { विराम; }
                    लिखो(न);
                }
                लिखो(असत्य && (१ / ० == ०), सत्य || (१ / ० == ०));
            }
            """);
        assertEquals(0,result.exit());assertEquals("",result.err());
        assertEquals("नमस्ते, दुनिया! सत्य\n१२० -९२२३३७२०३६८५४७७५८०८ -९२२३३७२०३६८५४७७५८०८\n-२ -१\n१\n३\nअसत्य सत्य\n",result.out());
    }
    @Test void failureHasSourceLocationAndDoesNotPrintPartialArguments()throws Exception{
        requireTools();
        var result=nativeRun("कार्य मुख्य() -> शून्य { लिखो(\"अधूरा\", १ / ०); }");
        assertEquals(1,result.exit());assertEquals("",result.out());
        assertTrue(result.err().contains("native.bhash:1:"));
    }
    @Test void nativeExecutableIncludesAccessibleLicenseNotices()throws Exception{
        requireTools();
        var result=nativeRun("कार्य मुख्य() -> शून्य { लिखो(\"नहीं\"); }","--bhash-license");
        assertEquals(0,result.exit());assertTrue(result.out().contains("Apache License"));
        assertFalse(result.out().contains("नहीं"));
    }

    private static final Map<String,String> GC_STRESS=Map.of("BHASH_GC_STRESS","1","BHASH_GC_STATS","1");
    private static Map<String,Long> stats(Result result){
        var matcher=java.util.regex.Pattern.compile("bhash-gc: allocations=(\\d+) collections=(\\d+) live=(\\d+) peak_bytes=(\\d+)").matcher(result.err());
        assertTrue(matcher.find(),result.err());
        return Map.of("allocations",Long.parseLong(matcher.group(1)),"collections",Long.parseLong(matcher.group(2)),
            "live",Long.parseLong(matcher.group(3)),"peak",Long.parseLong(matcher.group(4)));
    }
    @Test void coreResolvesStandardTextCallsAndConcatenation()throws Exception{
        var p=core("आयात मानक.पाठ्य उपनाम प; कार्य मुख्य() -> शून्य { लिखो(प.अंश(\"अ\" + \"ब\", ०, १)); }");
        var main=p.functions().stream().filter(f->f.id()==p.entry()).findFirst().orElseThrow();
        var print=(Print)main.body().statements().get(0);
        var call=(TextCall)print.arguments().get(0);
        assertEquals(TextOperation.SLICE,call.operation());
        assertEquals(Type.TEXT,call.arguments().get(0).type());
        assertThrows(CompilationException.class,()->core("आयात मानक.पाठ्य; कार्य मुख्य() -> शून्य { पाठ्य.बदलो(\"अ\", १, \"ब\"); }"));
    }
    @Test void nativeHeapTextMatchesJvmUnicodeAndScalarLibraryOperations()throws Exception{
        requireTools();
        var result=nativeRun("""
            आयात मानक.पाठ्य उपनाम प;
            कार्य पहचान(शब्द: पाठ) -> पाठ { लौटाओ शब्द; }
            कार्य मुख्य() -> शून्य {
                स्थिर क: पाठ = पहचान("a" + "😀क́");
                लिखो(प.लंबाई(क), लंबाई(क), प.अंश(क, १, ३), अंश(क, ०, ०));
                लिखो(प.खोजो(क, "क"), प.है(क, "😀"), प.आरंभ(क, "a"), प.अंत(क, "́"));
                लिखो(प.खोजो(क, ""), प.खोजो(क, "x"));
                लिखो(प.बदलो("ab\\u0000abc", "ab", "X"));
                लिखो(प.छाँटो("\\u00a0\\t ABÉक\\u3000\\n"), प.छोटाअक्षर("ABÉक"), प.बड़ाअक्षर("abÉक"));
                लिखो(प.दोहराओ("😀क", २), प.दोहराओ("", ९२२३३७२०३६८५४७७५८०७));
                लिखो(प.अंकदेवनागरी("v12😀"), अंकपाठ(-९२२३३७२०३६८५४७७५८०८));
                लिखो(पूर्णांकपाठ("-९२२३३७२०३६८५४७७५८०८"), पूर्णांकपाठ("+42"));
                लिखो(बड़ाअक्षर("abÉक"), प.छाँटो("\\u00a0\\u3000"));
            }
            """,GC_STRESS);
        assertEquals(0,result.exit());assertEquals(0L,stats(result).get("live"));
        assertTrue(stats(result).get("allocations")>=12,result.err());
    }
    @Test void nestedAllocatingArgumentsAndRecursionKeepLiveTextRooted()throws Exception{
        requireTools();
        var result=nativeRun("""
            आयात मानक.पाठ्य;
            कार्य जोड़(क: पाठ, ख: पाठ) -> पाठ { लौटाओ क + ख; }
            कार्य लपेटो(शब्द: पाठ, गहराई: पूर्णांक) -> पाठ {
                यदि (गहराई == ०) { लौटाओ शब्द; }
                लौटाओ "[" + लपेटो(शब्द + अंकपाठ(गहराई), गहराई - १) + "]";
            }
            कार्य मुख्य() -> शून्य {
                स्थिर मूल: पाठ = लपेटो("मूल", ३);
                मान न: पूर्णांक = ०;
                जबतक (न < २०००) {
                    स्थिर क्षणिक: पाठ = जोड़(पाठ्य.बड़ाअक्षर("a" + अंकपाठ(न)), पाठ्य.अंश("😀क" + अंकपाठ(न), ०, २));
                    यदि (लंबाई(क्षणिक) < ४) { त्रुटि("पाठ नष्ट हुआ"); }
                    न = न + १;
                }
                लिखो(मूल, जोड़("अ" + "ब", "क" + "ख"));
            }
            """,GC_STRESS);
        assertEquals("[[[मूल321]]] अबकख\n",result.out());
        var s=stats(result);assertTrue(s.get("allocations")>10000,result.err());
        assertTrue(s.get("collections")>=s.get("allocations"),result.err());
        assertEquals(0L,s.get("live"));assertTrue(s.get("peak")<100000,result.err());
    }
    @Test void exitedScopesAndLoopJumpsReleaseLargeTextBeforeNextAllocation()throws Exception{
        requireTools();
        var result=nativeRun("""
            आयात मानक.पाठ्य;
            कार्य मुख्य() -> शून्य {
                मान न: पूर्णांक = ०;
                जबतक (न < ३) {
                    { स्थिर अस्थायी: पाठ = पाठ्य.दोहराओ("क", ३००००);
                      न = न + १;
                      यदि (न == १) { जारी; }
                      यदि (न == २) { विराम; }
                      लिखो(लंबाई(अस्थायी)); }
                }
                { स्थिर अस्थायी: पाठ = पाठ्य.दोहराओ("ख", ३००००); लिखो(लंबाई(अस्थायी)); }
                { स्थिर अस्थायी: पाठ = पाठ्य.दोहराओ("ग", ३००००); लिखो(लंबाई(अस्थायी)); }
            }
            """,GC_STRESS);
        assertEquals("३००००\n३००००\n",result.out());
        assertTrue(stats(result).get("peak")<100000,result.err());assertEquals(0L,stats(result).get("live"));
    }
    @Test void explicitHeapTextFailurePreservesMessageAndLocation()throws Exception{
        requireTools();
        var result=nativeRun("कार्य मुख्य() -> शून्य { त्रुटि(\"अ\" + \"ब\"); }",GC_STRESS);
        assertEquals(1,result.exit());assertEquals("",result.out());
        assertTrue(result.err().contains("native.bhash:1:"));assertTrue(result.err().contains("अब"));
    }
    @Test void nativeTextBoundsAndParsingFailuresMatchJvm()throws Exception{
        requireTools();
        for(String expression:List.of("अंश(\"क\", -१, १)","अंश(\"क\", ०, २)",
                "पाठ्य.बदलो(\"abc\", \"\", \"x\")","पाठ्य.दोहराओ(\"क\", -१)",
                "पाठ्य.दोहराओ(\"😀\", ५२४२८९)","पूर्णांकपाठ(\"1२\")", "पूर्णांकपाठ(\"9223372036854775808\")")){
            var result=nativeRun("आयात मानक.पाठ्य; कार्य मुख्य() -> शून्य { लिखो(\"अधूरा\", "+expression+"); }");
            assertEquals(1,result.exit());assertEquals("",result.out());assertTrue(result.err().contains("native.bhash:1:"));
        }
    }

    @Test void invalidSourcePreservesPreviousExecutable()throws Exception{
        Path output=temporary.resolve("previous");Files.writeString(output,"previous executable");
        assertThrows(CompilationException.class,()->new NativeCompiler().compile(
            List.of(new SourceFile("bad.bhash","कार्य मुख्य() -> शून्य { लिखो(अ); }")),output));
        assertEquals("previous executable",Files.readString(output));
    }
}
