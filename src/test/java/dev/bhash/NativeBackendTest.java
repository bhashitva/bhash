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
            "वर्ग अ विस्तार ब {} वर्ग ब {} कार्य मुख्य() -> शून्य {}",
            "कार्य अप्रयुक्त() -> दशमलव { लौटाओ १.२; } कार्य मुख्य() -> शून्य {}",
            "कार्य मुख्य() -> शून्य { लिखो(पढ़ो()); }",
            "आयात मानक.पाठ्य; कार्य मुख्य() -> शून्य { पाठ्य.बाँटो(\"अ\", \"ब\"); }",
            "कार्य मुख्य() -> शून्य { लिखो(१ << २); }",
            "कार्य मुख्य() -> शून्य { लिखो(परिवर्तित<पूर्णांक>(१)); }")){
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
        assertEquals(List.of(0,8,16,24),p.classes().get(0).fields().stream().map(ObjectField::offset).toList());
        String ir=LlvmEmitter.emit(p);
        assertTrue(ir.contains("[2 x i64] [i64 16, i64 24]"),ir);
        assertTrue(ir.contains("call void @bh_reference_store"));
        assertTrue(ir.contains("call ptr @bh_alloc(ptr @class0, i64 32)"));
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
            "वर्ग अ विस्तार ब {} वर्ग ब {} कार्य मुख्य() -> शून्य {}",
            "अनुबंध अ {} कार्य मुख्य() -> शून्य {}",
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
        Path exe=temporary.resolve("application");
        new NativeCompiler(true).compile(List.of(new SourceFile("native.bhash",source)),exe);
        var command=new ArrayList<String>();command.add(exe.toString());command.addAll(List.of(arguments));
        Path out=temporary.resolve("out"),err=temporary.resolve("err");
        var builder=new ProcessBuilder(command).redirectOutput(out.toFile()).redirectError(err.toFile());
        builder.environment().putAll(environment);
        var p=builder.start();
        assertTrue(p.waitFor(15,TimeUnit.SECONDS));
        var result=new Result(p.exitValue(),Files.readString(out),Files.readString(err));
        if(arguments.length==0){
            Path jar=temporary.resolve("reference.jar");
            new JvmCompiler().compile(List.of(new SourceFile("native.bhash",source)),jar);
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
