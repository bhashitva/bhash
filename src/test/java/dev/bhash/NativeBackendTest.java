// SPDX-License-Identifier: Apache-2.0
package dev.bhash;

import dev.bhash.backend.*;
import dev.bhash.backend.CoreProgram.*;
import dev.bhash.backend.llvm.*;
import dev.bhash.backend.jvm.JvmCompiler;
import dev.bhash.backend.clr.ClrCompiler;
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
    @AfterEach void releaseWindowsHelperProcessHandles(){
        // JDK 17 closes Windows Process handles through its Cleaner. This suite
        // launches thousands of helpers in one VM; reclaim those native handles
        // between cases rather than waiting for Java heap pressure.
        if(NativePlatform.windows())System.gc();
    }
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
    @Test void decimalCoreUsesBinary64AndPreciseNonreferenceSlots()throws Exception{
        var program=core("""
            वर्ग माप { मान अंक: दशमलव; }
            कार्य पहचान(अ: दशमलव) -> दशमलव { लौटाओ -अ / २.०; }
            कार्य मुख्य() -> शून्य { स्थिर क: दशमलव = पहचान(१.५); लिखो(क); }
            """);
        var ir=LlvmEmitter.emit(program);
        assertTrue(ir.contains("fneg double"));assertTrue(ir.contains("fdiv double"));
        assertTrue(ir.contains("0x3FF8000000000000"));
        assertEquals(Type.REAL,program.classes().get(0).fields().get(0).type());
        assertFalse(Type.REAL.reference());
    }
    @Test void nativeDecimalsArithmeticComparisonsAndSpecialValues()throws Exception{
        requireTools();
        var result=nativeRun("""
            कार्य मुख्य() -> शून्य {
                स्थिर क: दशमलव = ५.७; स्थिर ख: दशमलव = ४.२;
                लिखो(क + ख, क - ख, क * ख, क / ख, -क, +ख);
                स्थिर न: दशमलव = ०.० / ०.०;
                स्थिर अ: दशमलव = १.० / ०.०;
                लिखो(न == न, न != न, न < क, न <= क, न > क, न >= क);
                लिखो(अ, -अ, न, -०.०, ०.० == -०.०);
                लिखो(क > ख, क >= क, ख < क, ख <= ख, क != ख, ख == ख);
            }
            """,GC_STRESS);
        assertEquals(0,result.exit());assertEquals(0L,stats(result).get("live"));
    }
    @Test void nativeDecimalConversionsFormattingAndFailures()throws Exception{
        requireTools();
        var result=nativeRun("""
            आयात मानक.पाठ्य;
            कार्य मुख्य() -> शून्य {
                लिखो(परिवर्तित<दशमलव>(-९२२३३७२०३६८५४७७५८०८), परिवर्तित<दशमलव>(९२२३३७२०३६८५४७७५८०७));
                लिखो(परिवर्तित<पूर्णांक>(-९.९), परिवर्तित<पूर्णांक>(९.९));
                लिखो(परिवर्तित<दशमलव>("-१२३.२५"), परिवर्तित<दशमलव>("+42.125"));
                लिखो(परिवर्तित<पाठ>(-०.०), परिवर्तित<पाठ>(१०००००००.०), परिवर्तित<पाठ>(०.०००१));
                लिखो(पाठ्य.संख्यापाठ(२.५, ०), पाठ्य.संख्यापाठ(३.५, ०), पाठ्य.संख्यापाठ(-०.०, ३));
                लिखो(पाठ्य.संख्यापाठ(२.६७५, २), पाठ्य.संख्यापाठ(०.१, १५));
                प्रयास { लिखो(परिवर्तित<पूर्णांक>(१.० / ०.०)); } पकड़ो (ग: अपवाद) { लिखो(ग.संदेश); }
                प्रयास { लिखो(परिवर्तित<पूर्णांक>(९२२३३७२०३६८५४७७५८०८.०)); } पकड़ो (ग: अपवाद) { लिखो(ग.संदेश); }
                प्रयास { लिखो(परिवर्तित<दशमलव>("1.२")); } पकड़ो (ग: अपवाद) { लिखो(ग.संदेश); }
                प्रयास { लिखो(परिवर्तित<दशमलव>("1e3")); } पकड़ो (ग: अपवाद) { लिखो(ग.संदेश); }
                प्रयास { लिखो(पाठ्य.संख्यापाठ(०.० / ०.०, २)); } पकड़ो (ग: अपवाद) { लिखो(ग.संदेश); }
                प्रयास { लिखो(पाठ्य.संख्यापाठ(१.०, १६)); } पकड़ो (ग: अपवाद) { लिखो(ग.संदेश); }
            }
            """,GC_STRESS);
        assertEquals(0,result.exit());assertEquals(0L,stats(result).get("live"));
    }
    @Test void nativeDecimalBoxingChecksAndCollectionKeys()throws Exception{
        requireTools();
        var result=nativeRun("""
            कार्य मुख्य() -> शून्य {
                स्थिर न: दशमलव = ०.० / ०.०;
                स्थिर क: सर्ववस्तु = १.२५;
                स्थिर श: सर्ववस्तु = -०.०;
                स्थिर ज: सर्ववस्तु = ०.०;
                स्थिर अ: सर्ववस्तु = न; स्थिर ब: सर्ववस्तु = -न;
                लिखो(प्रकारहै<दशमलव>(क), प्रकारहै<पूर्णांक>(क), प्रकारहै<सर्ववस्तु>(१.२५));
                लिखो(परिवर्तित<दशमलव>(क), श == ज, अ == ब);
                प्रयास { लिखो(परिवर्तित<पूर्णांक>(क)); } पकड़ो (ग: अपवाद) { लिखो(ग.संदेश); }
                स्थिर सूचीमान: सूची<दशमलव> = नया सूची<दशमलव>{-०.०, न, १.२५};
                लिखो(सूचीमान.है(०.०), सूचीमान.है(-न));
                स्थिर म: मानचित्र<दशमलव, पाठ> = नया मानचित्र<दशमलव, पाठ>{};
                म.रखो(-०.०, "शून्य"); म.रखो(०.०, "बदला"); म.रखो(न, "अनंक");
                लिखो(म.आकार(), म.लो(०.०), म.लो(-न));
                स्थिर व: मानचित्र<सर्ववस्तु, दशमलव> = नया मानचित्र<सर्ववस्तु, दशमलव>{};
                व.रखो(अ, १.२५); व.रखो(ब, २.५); व.रखो(श, ३.७५);
                लिखो(व.आकार(), व.लो(अ), व.लो(ज), व.है(१));
                प्रत्येक (च: दशमलव में म.कुंजियाँ()) { लिखो(म.लो(च)); }
                स्थिर स: दशमलव[] = नया दशमलव[२]; लिखो(स[०], स[१]);
            }
            """,GC_STRESS);
        assertEquals(0,result.exit());assertEquals(0L,stats(result).get("live"));
    }
    @Test void nativeDecimalVirtualMethodsAndFinallyResults()throws Exception{
        requireTools();
        var result=nativeRun("""
            आयात मानक.पाठ्य;
            अनुबंध मापक { कार्य मानलो(क: दशमलव) -> दशमलव; }
            वर्ग मूलमाप { सार्वजनिक मान अंक: दशमलव; सार्वजनिक कार्य लो() -> दशमलव { लौटाओ यह.अंक; } }
            वर्ग विशेष विस्तार मूलमाप पालन मापक {
                निर्माता(क: दशमलव) { यह.अंक = क; }
                सार्वजनिक अधिलेखन कार्य लो() -> दशमलव { लौटाओ यह.अंक * २.०; }
                सार्वजनिक कार्य मानलो(क: दशमलव) -> दशमलव { लौटाओ यह.लो() + क; }
            }
            कार्य वापस(क: दशमलव) -> दशमलव {
                मान द: दशमलव = क;
                प्रयास { लौटाओ द; } अंततः { स्थिर स: पाठ = पाठ्य.दोहराओ("क", १०००); द = ९.०; }
            }
            कार्य मुख्य() -> शून्य {
                स्थिर क: विशेष = नया विशेष(१.२५); स्थिर अ: मूलमाप = क; स्थिर म: मापक = क;
                लिखो(अ.लो(), म.मानलो(३.०), वापस(-०.०));
                स्थिर श: मूलमाप = नया मूलमाप(); लिखो(श.लो());
                मान न: दशमलव = ०.०;
                जबतक (न < ३.०) { प्रयास { न = न + ०.५; यदि (न == १.०) { जारी; } यदि (न > २.०) { विराम; } } अंततः { लिखो(न); } }
            }
            """,GC_STRESS);
        assertEquals(0,result.exit());assertEquals(0L,stats(result).get("live"));
    }
    @Test void nativeDecimalStressExercisesBoxingParsingAndCleanup()throws Exception{
        requireTools();
        var result=nativeRun(Files.readString(Path.of("examples/native/decimals.bhash")),GC_STRESS);
        assertEquals(0,result.exit());assertEquals(0L,stats(result).get("live"));
        assertTrue(stats(result).get("allocations")>4000,result.err());
        assertTrue(stats(result).get("peak")<200000,result.err());
    }
    @Test void numericCoreRecognizesBothOverloadsAndLibraryAliases()throws Exception{
        var p=core("""
            आयात मानक.गणित उपनाम ग;
            आयात मानक.यादृच्छिक उपनाम य;
            कार्य मुख्य() -> शून्य { य.बीज(१); लिखो(ग.निरपेक्ष(-२), ग.निरपेक्ष(-२.०), य.पूर्णांकचुनो(०, १०), य.अंश()); }
            """);
        var main=p.functions().stream().filter(f->f.id()==p.entry()).findFirst().orElseThrow();
        var seed=(NumericCall)((Evaluate)main.body().statements().get(0)).value();
        assertEquals(NumericOperation.SEED,seed.operation());
        var print=(Print)main.body().statements().get(1);
        assertEquals(NumericOperation.ABS_INT,((NumericCall)print.arguments().get(0)).operation());
        assertEquals(NumericOperation.ABS_REAL,((NumericCall)print.arguments().get(1)).operation());
        assertTrue(LlvmEmitter.emit(p).contains("call double @bh_numeric_random_unit(ptr"));
    }
    @Test void nativeMathCatalogAndSignedZerosMatchBothManagedTargets()throws Exception{
        requireNumericTargets();
        var result=numericExact("""
            आयात मानक.गणित उपनाम ग;
            कार्य मुख्य() -> शून्य {
                लिखो(ग.निरपेक्ष(-९२२३३७२०३६८५४७७५८०७), ग.न्यूनतम(-९२२३३७२०३६८५४७७५८०८, ९२२३३७२०३६८५४७७५८०७), ग.अधिकतम(-७, ९));
                लिखो(ग.निरपेक्ष(-२.५), ग.न्यूनतम(२.५, ३.५), ग.अधिकतम(२.५, ३.५));
                लिखो(ग.वर्गमूल(८१.०), ग.घात(२.०, १०.०), ग.फर्श(-२.२५), ग.छत(-२.२५));
                लिखो(ग.निकटतम(२.५), ग.निकटतम(३.५), ग.निकटतम(-२.५), ग.निकटतम(-३.५));
                लिखो(ग.ज्या(०.०), ग.कोज्या(०.०), ग.लघुगणक(१.०), ग.घातांक(०.०), ग.पाई() > ३.१४);
                लिखो(ग.न्यूनतम(०.०, -०.०), ग.न्यूनतम(-०.०, ०.०), ग.अधिकतम(०.०, -०.०), ग.अधिकतम(-०.०, ०.०));
                लिखो(ग.अधिकतम(-०.०, -०.०), ग.न्यूनतम(०.०, ०.०), ग.निरपेक्ष(-०.०));
                लिखो(ग.वर्गमूल(-०.०), ग.घात(-०.०, ३.०), ग.फर्श(-०.०), ग.छत(-०.२५), ग.निकटतम(-०.५), ग.ज्या(-०.०));
                लिखो(ग.घात(०.०, ०.०), ग.घात(-२.०, ३.०), ग.घातांक(-१०००.०));
                लिखो(मानक.गणित.वर्गमूल(२५.०), मानक.गणित.पाई() == ग.पाई());
            }
            """);assertEquals(0,result.exit());
    }
    @Test void nativeSeededRandomMixedSequencesMatchBothManagedTargets()throws Exception{
        requireNumericTargets();
        var result=numericExact("""
            आयात मानक.यादृच्छिक उपनाम य;
            कार्य जाँच(बीज: पूर्णांक) -> शून्य {
                य.बीज(बीज);
                लिखो(य.पूर्णांकचुनो(-१०, १०), य.पूर्णांकचुनो(०, ४६११६८६०१८४२७३८७९०५), य.पूर्णांकचुनो(-९२२३३७२०३६८५४७७५८०८, -१), य.पूर्णांकचुनो(१, ९२२३३७२०३६८५४७७५८०७), य.पूर्णांकचुनो(१७, १८));
                लिखो(परिवर्तित<पूर्णांक>(य.अंश() * ९००७१९९२५४७४०९९२.०));
                मान न: पूर्णांक = ०; मान योग: पूर्णांक = ०;
                जबतक (न < २०००) {
                    योग = योग * ३१ + य.पूर्णांकचुनो(-१०००, १००१);
                    स्थिर अ: दशमलव = य.अंश(); यदि (अ < ०.० || अ >= १.०) { त्रुटि("अंश सीमा"); }
                    योग = योग + परिवर्तित<पूर्णांक>(अ * ९००७१९९२५४७४०९९२.०); न = न + १;
                }
                लिखो(योग);
            }
            कार्य मुख्य() -> शून्य { जाँच(०); जाँच(-१); जाँच(-९२२३३७२०३६८५४७७५८०८); जाँच(९२२३३७२०३६८५४७७५८०७); जाँच(०); }
            """);assertEquals(0,result.exit());
    }
    @Test void nativeMathAndRandomErrorsMatchBothManagedTargets()throws Exception{
        requireNumericTargets();
        var source=new StringBuilder("आयात मानक.गणित उपनाम ग; आयात मानक.यादृच्छिक उपनाम य; कार्य मुख्य() -> शून्य { य.बीज(४२); ");
        var calls=new ArrayList<>(List.of("ग.निरपेक्ष(-९२२३३७२०३६८५४७७५८०८)","ग.वर्गमूल(-१.०)","ग.लघुगणक(०.०)","ग.लघुगणक(-१.०)","ग.घात(-१.०,०.५)","ग.घात(०.०,-१.०)","ग.घात(१०.०,१०००.०)","ग.घातांक(१०००.०)","य.पूर्णांकचुनो(१,१)","य.पूर्णांकचुनो(२,१)","य.पूर्णांकचुनो(-९२२३३७२०३६८५४७७५८०८,०)","य.पूर्णांकचुनो(-१,९२२३३७२०३६८५४७७५८०७)"));
        for(String name:List.of("निरपेक्ष","वर्गमूल","फर्श","छत","निकटतम","ज्या","कोज्या","लघुगणक","घातांक")){
            calls.add("ग."+name+"(०.० / ०.०)");calls.add("ग."+name+"(१.० / ०.०)");
        }
        for(String name:List.of("न्यूनतम","अधिकतम","घात")){
            calls.add("ग."+name+"(०.० / ०.०,१.०)");calls.add("ग."+name+"(१.०,-१.० / ०.०)");
        }
        for(String call:calls)source.append("प्रयास { ").append(call).append("; त्रुटि(\"अपवाद गायब\"); } पकड़ो (क: अपवाद) { लिखो(क.संदेश); } अंततः { स्थिर क: पाठ = \"जीवित\" + \"😀\"; } ");
        source.append("लिखो(य.पूर्णांकचुनो(०,१००)); य.बीज(४२); लिखो(य.पूर्णांकचुनो(०,१००)); }");
        var result=numericExact(source.toString());assertEquals(0,result.exit());
        assertFalse(result.out().contains("अपवाद गायब"));
    }
    @Test void nativeTranscendentalsMatchManagedTargetsWithinFourUlps()throws Exception{
        requireNumericTargets();
        var source="""
            आयात मानक.गणित उपनाम ग;
            कार्य मुख्य() -> शून्य {
                लिखो(परिवर्तित<पाठ>(ग.वर्गमूल(२.०)));
                लिखो(परिवर्तित<पाठ>(ग.घात(१.२५, ३.७५)));
                लिखो(परिवर्तित<पाठ>(ग.घात(-२.०, -३.०)));
                लिखो(परिवर्तित<पाठ>(ग.ज्या(१.०)));
                लिखो(परिवर्तित<पाठ>(ग.ज्या(-१०००.०)));
                लिखो(परिवर्तित<पाठ>(ग.ज्या(१००००००००००००००००००००.०)));
                लिखो(परिवर्तित<पाठ>(ग.कोज्या(१.०)));
                लिखो(परिवर्तित<पाठ>(ग.कोज्या(ग.पाई())));
                लिखो(परिवर्तित<पाठ>(ग.लघुगणक(२.०)));
                लिखो(परिवर्तित<पाठ>(ग.लघुगणक(०.००००००१)));
                लिखो(परिवर्तित<पाठ>(ग.घातांक(१.०)));
                लिखो(परिवर्तित<पाठ>(ग.घातांक(-७००.०)));
                लिखो(परिवर्तित<पाठ>(ग.घातांक(-१०००.०)));
                लिखो(परिवर्तित<पाठ>(ग.वर्गमूल(-०.०)));
                लिखो(परिवर्तित<पाठ>(ग.पाई()));
            }
            """;
        var sources=List.of(new SourceFile("native.bhash",source));var result=nativeOnlyRun(sources,GC_STRESS);
        assertEquals(0,result.exit(),result.err());String[] actual=result.out().strip().split("\n");
        for(boolean clr:new boolean[]{false,true}){
            var reference=numericManagedRun(sources,clr);assertEquals(0,reference.exit(),reference.err());
            String[] expected=reference.out().strip().split("\n");assertEquals(expected.length,actual.length);
            for(int i=0;i<actual.length;i++){
                double a=Double.parseDouble(actual[i]),e=Double.parseDouble(expected[i]);
                assertTrue(Double.isFinite(a)&&Double.isFinite(e));
                if(e==0)assertEquals(Double.doubleToRawLongBits(e),Double.doubleToRawLongBits(a));
                else assertEquals(e,a,4*Math.ulp(e),"Math mismatch index="+i+" clr="+clr);
            }
        }
        assertEquals(0L,stats(result).get("live"));
    }
    @Test void nativeNumericArgumentsEvaluateOnceInOrderAcrossCleanup()throws Exception{
        requireNumericTargets();
        var result=numericExact("""
            आयात मानक.गणित उपनाम ग;
            आयात मानक.यादृच्छिक उपनाम य;
            वर्ग क्रम {
                सार्वजनिक मान अंक: पूर्णांक;
                सार्वजनिक कार्य अगला() -> दशमलव {
                    यह.अंक = यह.अंक + १; लिखो(यह.अंक);
                    लौटाओ परिवर्तित<दशमलव>(यह.अंक);
                }
            }
            कार्य सीमा(क: पूर्णांक) -> पूर्णांक { लिखो(क); लौटाओ क; }
            कार्य लौटना(क: क्रम) -> दशमलव {
                प्रयास { लौटाओ ग.घात(क.अगला(), क.अगला()); }
                अंततः { स्थिर स: पाठ = "अ" + "😀"; क.अंक = ७; }
            }
            कार्य मुख्य() -> शून्य {
                स्थिर क: क्रम = नया क्रम();
                लिखो(ग.न्यूनतम(क.अगला(), क.अगला()), क.अंक);
                लिखो(लौटना(क), क.अंक);
                य.बीज(०); लिखो(य.पूर्णांकचुनो(सीमा(-१०), सीमा(१०)));
                प्रयास { लिखो("अधूरा", ग.वर्गमूल(-क.अगला())); }
                पकड़ो (अ: अपवाद) { लिखो(अ.संदेश); }
            }
            """);assertEquals(0,result.exit());assertFalse(result.out().contains("अधूरा"));
    }
    @Test void nativeMathRandomStandaloneExampleMatchesBothManagedTargets()throws Exception{
        requireNumericTargets();
        var result=numericExact(Files.readString(Path.of("examples/native/math-random.bhash")));
        assertEquals(0,result.exit());assertTrue(stats(result).get("allocations")>1000,result.err());
        assertTrue(stats(result).get("peak")<200000,result.err());
    }
    @Test void nativeUncaughtNumericErrorsRunFinallyAndKeepSourceLocations()throws Exception{
        requireNumericTargets();
        for(String operation:List.of("गणित.वर्गमूल(-१.०)","यादृच्छिक.पूर्णांकचुनो(२,१)")){
            String source="आयात मानक.गणित; आयात मानक.यादृच्छिक; कार्य भीतर() -> शून्य { स्थिर स: पाठ = \"जड़\" + \"😀\"; "+operation+"; } कार्य मुख्य() -> शून्य { प्रयास { भीतर(); } अंततः { लिखो(\"साफ\"); } }";
            var result=numericExact(source);assertEquals(1,result.exit());assertEquals("साफ\n",result.out());
            assertTrue(result.err().startsWith("भाष: native.bhash:1:"),result.err());
            assertTrue(result.err().contains(operation.startsWith("गणित")?"एक सीमित दशमलव मान आवश्यक है।":"यादृच्छिक पूर्णांक की सीमा अमान्य है।"),result.err());
        }
    }
    @Test void structuredCoreAddsCanonicalTypesAndBoxesJsonArguments()throws Exception{
        var program=core("""
            आयात मानक.पाठ्य उपनाम प; आयात मानक.जेसन उपनाम ज;
            कार्य मुख्य() -> शून्य { लिखो(ज.पाठबनाओ(१), प.जोड़ो(प.बाँटो("अ,ब",","),"|")); }
            """);
        assertNotNull(program.structuredTypes());
        var main=program.functions().stream().filter(f->f.id()==program.entry()).findFirst().orElseThrow();
        var write=(StructuredCall)((Print)main.body().statements().get(0)).arguments().get(0);
        assertInstanceOf(Box.class,write.arguments().get(0));assertEquals(Type.ANY,write.arguments().get(0).type());
        assertEquals(1,program.collections().stream().filter(c->c.kind()==CollectionKind.LIST&&c.valueKind()==3).count());
        assertTrue(LlvmEmitter.emit(program).contains("call void @bh_data_configure(ptr @data_info)"));
    }
    @Test void nativeSplitJoinMatchManagedLiteralUnicodeAndEmptyRules()throws Exception{
        requireNumericTargets();
        var result=numericExact("""
            आयात मानक.पाठ्य उपनाम प;
            कार्य मुख्य() -> शून्य {
                स्थिर अ: सूची<पाठ> = प.बाँटो(".अ..", ".");
                लिखो(अ.आकार(), प.जोड़ो(अ,"|")); अ.जोड़ो("क"); लिखो(प.जोड़ो(अ,""));
                लिखो(प.जोड़ो(प.बाँटो("aaa","aa"),"|"));
                लिखो(प.जोड़ो(प.बाँटो("😀क😀😀ख😀","😀"),"|"));
                लिखो(प.बाँटो("",",").आकार(), प.जोड़ो(नया सूची<पाठ>(),"-"));
                लिखो(प.जोड़ो(प.बाँटो("क\\u0000ख\\u0000","\\u0000"),"|"));
                लिखो(मानक.पाठ्य.जोड़ो(मानक.पाठ्य.बाँटो("x.*y.*", ".*"),":"));
                प्रयास { प.बाँटो("क",""); } पकड़ो (क: अपवाद) { लिखो(क.संदेश); }
                प्रयास { स्थिर अ: सूची<पाठ> = रिक्त; प.जोड़ो(अ,","); } पकड़ो (क: अपवाद) { लिखो(क.संदेश); }
            }
            """);assertEquals(0,result.exit());
    }
    @Test void nativeJsonTypedNestedValuesAndCollectionsMatchManaged()throws Exception{
        requireNumericTargets();
        var result=numericExact("""
            आयात मानक.जेसन उपनाम ज; आयात मानक.पाठ्य उपनाम प;
            कार्य मुख्य() -> शून्य {
                स्थिर मूल: सर्ववस्तु = ज.पढ़ो("{\\\"नाम\\\":\\\"क😀\\\\u0000\\\",\\\"अंक\\\":[-9223372036854775808,9223372036854775807,1.25,1.0,true,false,null],\\\"भीतर\\\":{\\\"a\\\":[]}}");
                स्थिर म: मानचित्र<पाठ,सर्ववस्तु> = परिवर्तित<मानचित्र<पाठ,सर्ववस्तु>>(मूल);
                स्थिर अ: सूची<सर्ववस्तु> = परिवर्तित<सूची<सर्ववस्तु>>(म["अंक"]);
                लिखो(प्रकारहै<मानचित्र<पाठ,सर्ववस्तु>>(मूल), प्रकारहै<मानचित्र<पाठ,पूर्णांक>>(मूल), प्रकारहै<सूची<सर्ववस्तु>>(म["अंक"]));
                लिखो(परिवर्तित<पूर्णांक>(अ[०]), परिवर्तित<पूर्णांक>(अ[१]), परिवर्तित<दशमलव>(अ[२]), प्रकारहै<दशमलव>(अ[३]), प्रकारहै<पूर्णांक>(अ[३]), अ[६] == रिक्त);
                लिखो(ज.पाठबनाओ(मूल)); म.रखो("नया",प.बाँटो("अ,,ब,",",")); अ.जोड़ो("अंत");
                लिखो(ज.पाठबनाओ(म), ज.पाठबनाओ(म.कुंजियाँ()));
                लिखो(ज.पाठबनाओ(नया पूर्णांक[]{-१,२}),ज.पाठबनाओ(नया तार्किक[]{सत्य,असत्य}),ज.पाठबनाओ(नया पाठ[२]));
                लिखो(ज.पाठबनाओ(रिक्त),ज.पाठबनाओ(सत्य),ज.पाठबनाओ(१),ज.पाठबनाओ(१.०));
                स्थिर व: मानचित्र<सर्ववस्तु,सर्ववस्तु> = नया मानचित्र<सर्ववस्तु,सर्ववस्तु>{"क":अ}; लिखो(ज.पाठबनाओ(व));
            }
            """);assertEquals(0,result.exit());
    }
    @Test void nativeJsonNumberKindsAndRoundTripsMatchManaged()throws Exception{
        requireNumericTargets();
        var result=numericExact("""
            आयात मानक.जेसन उपनाम ज;
            कार्य मुख्य() -> शून्य {
                स्थिर अ: सूची<सर्ववस्तु> = परिवर्तित<सूची<सर्ववस्तु>>(ज.पढ़ो("[-0,-0.0,1e3,1E-300,-1e-9999,5e-324,9007199254740993,9007199254740993.0]"));
                लिखो(प्रकारहै<पूर्णांक>(अ[०]),प्रकारहै<दशमलव>(अ[१]),परिवर्तित<दशमलव>(अ[१]),परिवर्तित<दशमलव>(अ[२]),परिवर्तित<दशमलव>(अ[४]));
                स्थिर ब: सूची<सर्ववस्तु> = परिवर्तित<सूची<सर्ववस्तु>>(ज.पढ़ो(ज.पाठबनाओ(अ)));
                प्रत्येक (न: सर्ववस्तु में ब) { लिखो(प्रकारहै<पूर्णांक>(न),प्रकारहै<दशमलव>(न)); }
                मान क: पूर्णांक = ०; जबतक (क < अ.आकार()) { लिखो(अ[क] == ब[क]); क = क + १; }
                लिखो(परिवर्तित<पूर्णांक>(अ[६]), परिवर्तित<दशमलव>(अ[७]) == ९००७१९९२५४७४०९९२.०);
            }
            """);assertEquals(0,result.exit());
    }
    private static String bhashString(String value){
        var result=new StringBuilder("\"");
        for(char c:value.toCharArray())switch(c){
            case '"'->result.append("\\\"");case '\\'->result.append("\\\\");
            case '\n'->result.append("\\n");case '\r'->result.append("\\r");case '\t'->result.append("\\t");
            default->{if(c<32)result.append(String.format(Locale.ROOT,"\\u%04x",(int)c));else result.append(c);}
        }
        return result.append('"').toString();
    }
    static String jsonFailuresSource(){
        var source=new StringBuilder("आयात मानक.जेसन उपनाम ज; कार्य मुख्य() -> शून्य {");
        for(String text:List.of("", " ", "+1", "01", "-", "1.", "1e", "1e+", "१", "NaN", "null x", "[1,]", "{\"a\":1,}", "{\"a\":1,\"a\":2}", "{\"a\":1,\"\\u0061\":2}", "{\"😀\":[1,2,]}", "\"\\😀\"", "\"\\u😀\"", "\"\\ud800\"", "\"\\udc00\"", "\"\\ud800x\"", "\"\\ud800\\q\"", "\"\\ud800", "\"a\n\"", "9223372036854775808", "-9223372036854775809", "1e9999", "[trueX]", "[", "\"x", "[1\u0000]"))
            source.append("प्रयास { ज.पढ़ो(").append(bhashString(text)).append("); त्रुटि(\"अपवाद गायब\"); } पकड़ो (क: अपवाद) { लिखो(क.संदेश); } ");
        source.append("लिखो(ज.पाठबनाओ(ज.पढ़ो(\"\\\"\\\\ud83d\\\\ude00\\\"\"))); }");
        return source.toString();
    }
    @Test void nativeJsonErrorsAndUtf16PositionsMatchManaged()throws Exception{
        requireNumericTargets();var result=numericExact(jsonFailuresSource());assertEquals(0,result.exit());assertFalse(result.out().contains("अपवाद गायब"));
    }
    @Test void nativeJsonCyclesSharedValuesAndRejectedTypesMatchManaged()throws Exception{
        requireNumericTargets();
        var result=numericExact("""
            आयात मानक.जेसन उपनाम ज;
            वर्ग अ {}
            कार्य मुख्य() -> शून्य {
                स्थिर क: सूची<सर्ववस्तु> = नया सूची<सर्ववस्तु>{१,"क"};
                स्थिर मूल: सूची<सर्ववस्तु> = नया सूची<सर्ववस्तु>{क,क}; लिखो(ज.पाठबनाओ(मूल));
                क.जोड़ो(क); प्रयास { ज.पाठबनाओ(मूल); } पकड़ो (ग: अपवाद) { लिखो(ग.संदेश); } क.हटाओ(२);
                स्थिर म: मानचित्र<पाठ,सर्ववस्तु> = नया मानचित्र<पाठ,सर्ववस्तु>(); म.रखो("चक्र",म);
                प्रयास { ज.पाठबनाओ(म); } पकड़ो (ग: अपवाद) { लिखो(ग.संदेश); }
                लिखो(ज.पाठबनाओ(नया मानचित्र<पूर्णांक,पाठ>()));
                प्रयास { ज.पाठबनाओ(नया मानचित्र<पूर्णांक,पाठ>{१:"क"}); } पकड़ो (ग: अपवाद) { लिखो(ग.संदेश); }
                प्रयास { ज.पाठबनाओ(नया अ()); } पकड़ो (ग: अपवाद) { लिखो(ग.संदेश); }
                प्रयास { ज.पाठबनाओ(०.० / ०.०); } पकड़ो (ग: अपवाद) { लिखो(ग.संदेश); }
                प्रयास { ज.पाठबनाओ(१.० / ०.०); } पकड़ो (ग: अपवाद) { लिखो(ग.संदेश); }
                लिखो(ज.पाठबनाओ(मूल));
            }
            """);assertEquals(0,result.exit());
    }
    static String jsonLimitsSource(){
        return """
            आयात मानक.जेसन उपनाम ज; आयात मानक.पाठ्य उपनाम प;
            कार्य मुख्य() -> शून्य {
                स्थिर ठीक: पाठ = प.दोहराओ("[",६५) + प.दोहराओ("]",६५);
                लिखो(ज.पाठबनाओ(ज.पढ़ो(ठीक)) == ठीक);
                प्रयास { ज.पढ़ो(प.दोहराओ("[",६६) + प.दोहराओ("]",६६)); } पकड़ो (क: अपवाद) { लिखो(क.संदेश); }
                स्थिर मूल: सूची<सर्ववस्तु> = नया सूची<सर्ववस्तु>(); मान चल: सूची<सर्ववस्तु> = मूल;
                मान न: पूर्णांक = ०; जबतक (न < ६५) { स्थिर अगला: सूची<सर्ववस्तु> = नया सूची<सर्ववस्तु>(); चल.जोड़ो(अगला); चल = अगला; न = न + १; }
                प्रयास { ज.पाठबनाओ(मूल); } पकड़ो (क: अपवाद) { लिखो(क.संदेश); }
                { स्थिर शब्द: पाठ = प.दोहराओ("a",१०४८५७४); स्थिर पाठ्य: पाठ = "\\\"" + शब्द + "\\\"";
                  लिखो(ज.पाठबनाओ(ज.पढ़ो(पाठ्य)) == पाठ्य, लंबाई(पाठ्य)); }
                { स्थिर शब्द: पाठ = प.दोहराओ("😀",५२४२८७); स्थिर पाठ्य: पाठ = "\\\"" + शब्द + "\\\"";
                  लिखो(ज.पाठबनाओ(ज.पढ़ो(पाठ्य)) == पाठ्य, लंबाई(पाठ्य)); }
                { स्थिर शब्द: पाठ = प.दोहराओ("😀",५२४२८८);
                  प्रयास { ज.पढ़ो("\\\"" + शब्द + "\\\""); } पकड़ो (क: अपवाद) { लिखो(क.संदेश); }
                  प्रयास { ज.पाठबनाओ(शब्द); } पकड़ो (क: अपवाद) { लिखो(क.संदेश); } }
            }
            """;
    }
    @Test void nativeJsonLimitsCountUtf16AndRespectDepthBoundaries()throws Exception{
        requireNumericTargets();var result=numericExact(jsonLimitsSource());assertEquals(0,result.exit());
    }
    @Test void nativeJsonNestedGraphsAndRepeatedPartialFailuresStayBounded()throws Exception{
        requireNumericTargets();var result=numericExact(Files.readString(Path.of("examples/native/json-text.bhash")));
        assertEquals(0,result.exit());assertTrue(stats(result).get("allocations")>15000,result.err());
        assertTrue(stats(result).get("peak")<200000,result.err());
    }
    static String jsonUnwindSource(){
        return """
            आयात मानक.जेसन उपनाम ज; आयात मानक.पाठ्य उपनाम प;
            कार्य भीतर() -> शून्य { ज.पढ़ो("{\\\"क\\\":[1,2,{\\\"स\\\":\\\"" + प.दोहराओ("क",३००००) + "\\\"}]"); }
            कार्य मुख्य() -> शून्य {
                प्रयास { भीतर(); }
                अंततः { स्थिर स: पाठ = प.दोहराओ("ब",३००००); लिखो(लंबाई(स)); }
            }
            """;
    }
    @Test void nativeJsonUncaughtFailureUnwindsParserAndFinallyRoots()throws Exception{
        requireNumericTargets();var result=numericExact(jsonUnwindSource());assertEquals(1,result.exit());
        assertTrue(result.err().startsWith("भाष: native.bhash:"),result.err());
        assertEquals("३००००\n",result.out());
    }
    @Test void coreUsesExistingTypeAndImmutableBindingChecks(){
        assertThrows(CompilationException.class,()->core("कार्य मुख्य() -> शून्य { स्थिर अ: पूर्णांक = १; अ = २; }"));
        assertThrows(CompilationException.class,()->core("कार्य मुख्य() -> शून्य { स्थिर अ: पूर्णांक = सत्य; }"));
        assertThrows(CompilationException.class,()->core("कार्य मुख्य() -> शून्य { लिखो(अ); }"));
    }
    @Test void genericMemberDeclarationsKeepSharedSourceDiagnostics(){
        for(String source:List.of(
            "अनुबंध अ { कार्य न<त>(व: त) -> त; } कार्य मुख्य() -> शून्य {}",
            "वर्ग अ { कार्य न<त>(व: त) -> त { लौटाओ व; } } कार्य मुख्य() -> शून्य {}")){
            var error=assertThrows(CompilationException.class,()->core(source));
            assertTrue(error.getMessage().contains("native.bhash:1:"),error.getMessage());
            assertTrue(error.getMessage().contains("Generic"),error.getMessage());
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
            "वर्ग अ<त> { मान न: त; } कार्य मुख्य() -> शून्य {}",
            "अनुबंध अ<क> {} कार्य मुख्य() -> शून्य {}",
            "अभिलेख अ<त>(न: त); कार्य मुख्य() -> शून्य {}")){
            assertDoesNotThrow(()->core(s));
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
        assertDoesNotThrow(()->core("वर्ग क<त> { मान न: त; } कार्य मुख्य() -> शून्य {}"));
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

    @Test void coreCollectionMetadataCanonicalizesAliasesAndSnapshots()throws Exception{
        var p=core("""
            कार्य मुख्य() -> शून्य {
                स्थिर अ: पूर्णांक[] = नया सरणी<पूर्णांक>(२);
                स्थिर ब: सरणी<पूर्णांक> = अ;
                स्थिर म: मानचित्र<पाठ,पूर्णांक[]> = नया मानचित्र<पाठ,पूर्णांक[]>{"क":अ};
                स्थिर कुंजी: सूची<पाठ> = म.कुंजियाँ();
                स्थिर व: सर्ववस्तु = कुंजी;
                लिखो(प्रकारहै<सूची<पाठ>>(व), ब.लंबाई);
            }
            """);
        assertEquals(1,p.collections().stream().filter(t->t.kind()==CollectionKind.ARRAY).count());
        assertEquals(1,p.collections().stream().filter(t->t.kind()==CollectionKind.LIST && t.valueKind()==3).count());
        assertTrue(LlvmEmitter.emit(p).contains("%CollectionInfo"));
        assertDoesNotThrow(()->core("कार्य मुख्य() -> शून्य { स्थिर अ: लघुपूर्णांक[] = नया लघुपूर्णांक[१]; }"));
        assertDoesNotThrow(()->core("वर्ग क<त> {} कार्य मुख्य() -> शून्य {}"));
    }
    @Test void nativeArraysDefaultsAliasesInitializersAndJaggedDimensionsMatchJvm()throws Exception{
        requireTools();var result=nativeRun("""
            वर्ग क {}
            कार्य आयाम(न: पूर्णांक) -> पूर्णांक { लिखो(न); लौटाओ न; }
            कार्य मुख्य() -> शून्य {
                स्थिर अ: पूर्णांक[] = नया सरणी<पूर्णांक>(३); अ[१] = ७; अ.रखो(२,८);
                स्थिर ब: तार्किक[] = नया तार्किक[२]; ब[१] = सत्य;
                स्थिर शब्द: पाठ[] = नया पाठ[२]; शब्द[१] = "क" + "😀";
                स्थिर व: क[] = नया क[२]; व[१] = नया क();
                लिखो(अ[०], अ.लो(१), लंबाई(अ), अ.आकार(), अ.है(८), ब[०], ब.है(सत्य), शब्द[०] == "", शब्द[१], व[०] == रिक्त);
                स्थिर खाली: पाठ[] = नया पाठ[]{}; लिखो(खाली.खाली(), खाली.आकार);
                स्थिर जाल: पूर्णांक[][] = नया पूर्णांक[आयाम(२)][आयाम(३)]; जाल[०][१] = ९;
                लिखो(जाल[०][१], जाल[१][१], जाल[०] != जाल[१]);
                स्थिर अधूरा: पूर्णांक[][] = नया पूर्णांक[२][]; लिखो(अधूरा[०] == रिक्त);
                अधूरा[०] = नया पूर्णांक[]{१,२}; अधूरा[१] = नया पूर्णांक[]{३}; लिखो(अधूरा[०][१], अधूरा[१].लंबाई);
                स्थिर मिश्र: सर्ववस्तु[] = नया सर्ववस्तु[]{१,सत्य,"अ"+"ब",व[१],अ};
                लिखो(परिवर्तित<पूर्णांक>(मिश्र[०]), परिवर्तित<तार्किक>(मिश्र[१]), परिवर्तित<पाठ>(मिश्र[२]), मिश्र[३] == व[१], मिश्र[४] == अ);
                स्थिर रिक्तसरणी: सर्ववस्तु[] = नया सर्ववस्तु[१]; लिखो(रिक्तसरणी[०] == रिक्त);
            }
            """,GC_STRESS);assertEquals(0,result.exit());assertEquals(0,stats(result).get("live"));
    }
    @Test void nativeListsSupportEveryOperationAndPreserveRemovedReferences()throws Exception{
        requireTools();var result=nativeRun("""
            कार्य पाठदो() -> पाठ {
                स्थिर क: सूची<पाठ> = नया सूची<पाठ>{"अ"+"ब","क"};
                क.जोड़ो("ख"); क.डालो(१,"ग"+"घ"); क.रखो(२,"च"); क[३] = "छ";
                लिखो(क.आकार, लंबाई(क), क.आकार(), क.है("गघ"), क.लो(२));
                स्थिर निकला: पाठ = क.हटाओ(१); क.साफ़(); क.साफ़();
                लिखो(क.खाली(), क.लंबाई()); क.डालो(०,"आ");
                लिखो(क[०]); लौटाओ निकला;
            }
            कार्य मुख्य() -> शून्य {
                लिखो(पाठदो());
                स्थिर मिश्र: सूची<सर्ववस्तु> = नया सूची<सर्ववस्तु>{१,सत्य,"अ"+"ब"};
                मिश्र.जोड़ो(रिक्त); मिश्र.डालो(१,असत्य); मिश्र[०] = ७;
                लिखो(मिश्र.है(७), मिश्र.है(असत्य), मिश्र.है("अब"), मिश्र.है(रिक्त), परिवर्तित<पूर्णांक>(मिश्र.हटाओ(०)));
            }
            """,GC_STRESS);
        assertEquals("४ ४ ४ सत्य च\nसत्य ०\nआ\nगघ\nसत्य सत्य सत्य सत्य ७\n",result.out());assertEquals(0,stats(result).get("live"));
    }
    @Test void nativeOrderedMapsSnapshotsHashKeysAndUniversalKindsMatchJvm()throws Exception{
        requireTools();var result=nativeRun("""
            अनुबंध अ {}
            वर्ग क पालन अ { सार्वजनिक मान न: पूर्णांक; }
            कार्य मुख्य() -> शून्य {
                स्थिर म: मानचित्र<पाठ,पूर्णांक> = नया मानचित्र<पाठ,पूर्णांक>{"क":१,"ख":२,"क":३};
                म["ग"] = ४; म.रखो("ख",५); लिखो(म.आकार, म.लंबाई(), लंबाई(म), म.लो("च",९), म.है("क"));
                स्थिर पहले: सूची<पाठ> = म.कुंजियाँ(); स्थिर मानक: सूची<पूर्णांक> = म.मूल्य();
                लिखो(म.हटाओ("ख"), म.हटाओ("च")); म["ख"] = ७;
                प्रत्येक (कुंजी: पाठ, अंक: पूर्णांक में म) { लिखो(कुंजी,अंक); }
                म.साफ़(); लिखो(म.खाली(), पहले[१], मानक[१]);
                स्थिर वस्तु: क = नया क(); स्थिर अनु: अ = वस्तु; स्थिर सरणी: पूर्णांक[] = नया पूर्णांक[]{१};
                स्थिर मिश्र: मानचित्र<सर्ववस्तु,सर्ववस्तु> = नया मानचित्र<सर्ववस्तु,सर्ववस्तु>{१:"अ",सत्य:"ब","क"+"ख":७,अनु:सरणी,सरणी:रिक्त};
                वस्तु.न = ९; सरणी[०] = २;
                लिखो(मिश्र.आकार(), परिवर्तित<पाठ>(मिश्र[१]), परिवर्तित<पाठ>(मिश्र[सत्य]), परिवर्तित<पूर्णांक>(मिश्र["कख"]));
                लिखो(मिश्र[वस्तु] == सरणी, मिश्र.है(सरणी), मिश्र[सरणी] == रिक्त, मिश्र.है(नया क()), मिश्र.है(नया पूर्णांक[]{२}));
                स्थिर व: सर्ववस्तु = मिश्र.कुंजियाँ();
                लिखो(प्रकारहै<सूची<सर्ववस्तु>>(व), प्रकारहै<सूची<पाठ>>(व), सुरक्षितरूप<सूची<पाठ>>(व) == रिक्त);
            }
            """,GC_STRESS);assertEquals(0,result.exit());assertEquals(0,stats(result).get("live"));
    }
    @Test void nativeCollectionIterationBindingsControlFlowAndUpdatesMatchJvm()throws Exception{
        requireTools();var result=nativeRun("""
            कार्य स्रोत() -> सूची<पूर्णांक> { लिखो("स्रोत"); लौटाओ नया सूची<पूर्णांक>{१,२,३,४}; }
            कार्य जल्दी() -> पाठ {
                स्थिर क: सूची<पाठ> = नया सूची<पाठ>{"अ"+"ब"};
                प्रत्येक (शब्द: पाठ में क) { क.जोड़ो("क"); लौटाओ शब्द; }
                लौटाओ "";
            }
            कार्य मुख्य() -> शून्य {
                मान योग: पूर्णांक = ०;
                प्रत्येक (अंक: सर्ववस्तु में स्रोत()) {
                    स्थिर न: पूर्णांक = परिवर्तित<पूर्णांक>(अंक);
                    यदि (न == २) { जारी; } यदि (न == ४) { विराम; }
                    प्रत्येक (भीतर: पूर्णांक में नया पूर्णांक[]{१,२}) { यदि (भीतर == २) { विराम; } योग = योग + न; }
                }
                लिखो(योग, जल्दी());
                स्थिर क: पूर्णांक[] = नया पूर्णांक[]{१,२}; प्रत्येक (न: पूर्णांक में क) { क[१] = ९; लिखो(न); }
                स्थिर ख: सूची<पूर्णांक> = नया सूची<पूर्णांक>{३,४}; प्रत्येक (न: पूर्णांक में ख) { ख[१] = ८; लिखो(न); }
                स्थिर म: मानचित्र<पाठ,पूर्णांक> = नया मानचित्र<पाठ,पूर्णांक>{"क":५,"ख":६};
                प्रत्येक (कुंजी: सर्ववस्तु, मानक: सर्ववस्तु में म) { म["ख"] = ७; लिखो(परिवर्तित<पाठ>(कुंजी),परिवर्तित<पूर्णांक>(मानक)); }
                प्रत्येक (न: पूर्णांक में नया पूर्णांक[०]) { त्रुटि("खाली"); }
            }
            """,GC_STRESS);
        assertEquals("स्रोत\n४ अब\n१\n९\n३\n८\nक ५\nख ७\n",result.out());assertEquals(0,stats(result).get("live"));
    }
    @Test void nativeCollectionArgumentsAndDimensionsEvaluateOnceLeftToRight()throws Exception{
        requireTools();var result=nativeRun("""
            कार्य अंक(न: पूर्णांक) -> पूर्णांक { लिखो(न); लौटाओ न; }
            कार्य स्रोत() -> पूर्णांक[] { लिखो("सरणी"); लौटाओ नया पूर्णांक[२]; }
            कार्य मुख्य() -> शून्य {
                स्रोत()[अंक(१)] = अंक(७);
                स्थिर जाल: पूर्णांक[][] = नया पूर्णांक[अंक(२)][अंक(३)];
                स्थिर म: मानचित्र<पूर्णांक,पूर्णांक> = नया मानचित्र<पूर्णांक,पूर्णांक>{अंक(४):अंक(५),अंक(६):अंक(७)};
                लिखो(म.लो(४,अंक(९)), जाल[०][०]);
                स्थिर व: सर्ववस्तु = नया सूची<पूर्णांक[]>{नया पूर्णांक[]{१}};
                लिखो(प्रकारहै<सूची<पूर्णांक[]>>(व), प्रकारहै<सूची<पाठ[]>>(व), प्रकारहै<सूची<सर्ववस्तु>>(व));
                लिखो(परिवर्तित<सूची<पूर्णांक[]>>(व)[०][०]);
            }
            """,GC_STRESS);
        assertEquals("सरणी\n१\n७\n२\n३\n४\n५\n६\n७\n९\n५ ०\nसत्य असत्य असत्य\n१\n",result.out());assertEquals(0,stats(result).get("live"));
    }
    @Test void nativeCollectionFailuresHaveLocationsAndMatchJvm()throws Exception{
        requireTools();
        for(String body:List.of(
            "स्थिर अ: पूर्णांक[] = नया पूर्णांक[-१];", "स्थिर अ: पूर्णांक[] = नया पूर्णांक[२१४७४८३६४८];",
            "स्थिर अ: पूर्णांक[][] = नया पूर्णांक[०][-१];", "स्थिर अ: पूर्णांक[] = नया पूर्णांक[०]; लिखो(\"अधूरा\",अ[०]);",
            "स्थिर अ: सूची<पूर्णांक> = नया सूची<पूर्णांक>{१}; अ.डालो(-१,२);",
            "स्थिर अ: सूची<पूर्णांक> = नया सूची<पूर्णांक>{१}; अ.रखो(१,२);",
            "स्थिर अ: सूची<पूर्णांक> = नया सूची<पूर्णांक>(); अ.हटाओ(०);",
            "स्थिर म: मानचित्र<पाठ,पूर्णांक> = नया मानचित्र<पाठ,पूर्णांक>(); लिखो(\"अधूरा\",म[\"क\"]);",
            "स्थिर म: मानचित्र<सर्ववस्तु,पूर्णांक> = नया मानचित्र<सर्ववस्तु,पूर्णांक>(); म[रिक्त] = १;",
            "स्थिर अ: सूची<पूर्णांक> = नया सूची<पूर्णांक>{१}; प्रत्येक (न: पूर्णांक में अ) { अ.जोड़ो(२); }",
            "स्थिर अ: सूची<पूर्णांक> = नया सूची<पूर्णांक>{१}; प्रत्येक (न: पूर्णांक में अ) { अ.हटाओ(०); जारी; }",
            "स्थिर म: मानचित्र<पाठ,पूर्णांक> = नया मानचित्र<पाठ,पूर्णांक>{\"क\":१}; प्रत्येक (क: पाठ में म) { म.साफ़(); }",
            "स्थिर व: सर्ववस्तु = नया पूर्णांक[१]; लिखो(परिवर्तित<पाठ[]>(व).लंबाई);",
            "स्थिर अ: पूर्णांक[] = रिक्त; अ[अंक()] = अंक();",
            "स्थिर अ: सूची<पूर्णांक> = रिक्त; अ.जोड़ो(अंक());",
            "स्थिर अ: पूर्णांक[] = नया पूर्णांक[०]; अ[अंक()] = अंक();")){
            var result=nativeRun("कार्य अंक() -> पूर्णांक { लिखो(७); लौटाओ ७; } कार्य मुख्य() -> शून्य { "+body+" }",GC_STRESS);
            assertEquals(1,result.exit());assertTrue(result.err().contains("native.bhash:"));
        }
    }
    @Test void nativeCollectionsStressResizingCyclesSnapshotsAndReclamation()throws Exception{
        requireTools();var result=nativeRun(Files.readString(Path.of("examples/native/collections.bhash")),GC_STRESS);
        assertEquals("३०० ३०० श299 २९९\nश0 श299 सत्य\nसत्य सत्य\n",result.out());
        var stats=stats(result);assertTrue(stats.get("allocations")>10000,result.err());
        assertEquals(0,stats.get("live"));assertTrue(stats.get("peak")<1000000,result.err());
    }

    private static List<SourceFile> collectionSources(){
        return List.of(
            new SourceFile("contract.bhash","संकुल संग्रहपुस्तक; सार्वजनिक अनुबंध नामी { कार्य नाम() -> पाठ; }"),
            new SourceFile("library.bhash","संकुल संग्रहपुस्तक; सार्वजनिक वर्ग कड़ी पालन नामी { सार्वजनिक मान शब्द: पाठ; निर्माता(शब्द: पाठ) { यह.शब्द = शब्द; } सार्वजनिक कार्य नाम() -> पाठ { लौटाओ शब्द; } } सार्वजनिक कार्य बनाओ(व: सर्ववस्तु) -> सूची<सर्ववस्तु> { लौटाओ नया सूची<सर्ववस्तु>{व}; }"),
            new SourceFile("app.bhash","आयात संग्रहपुस्तक.नामी; आयात संग्रहपुस्तक.कड़ी; आयात संग्रहपुस्तक.बनाओ; कार्य मुख्य() -> शून्य { स्थिर क: नामी = नया कड़ी(\"अ\"+\"ब\"); स्थिर म: मानचित्र<पाठ,सूची<सर्ववस्तु>> = नया मानचित्र<पाठ,सूची<सर्ववस्तु>>{\"क\":बनाओ(क),\"न\":बनाओ(७)}; स्थिर व: सर्ववस्तु = म; लिखो(परिवर्तित<नामी>(म[\"क\"][०]).नाम(), परिवर्तित<पूर्णांक>(म[\"न\"][०]), प्रकारहै<मानचित्र<पाठ,सूची<सर्ववस्तु>>>(व)); स्थिर अ: नामी[] = नया नामी[]{क}; प्रत्येक (ज: सर्ववस्तु में अ) { लिखो(परिवर्तित<नामी>(ज).नाम()); } }"));
    }
    @Test void nativeCollectionsAcrossPackagesAndInterfacesMatchJvm()throws Exception{
        requireTools();var result=nativeRun(collectionSources(),GC_STRESS);
        assertEquals("अब ७ सत्य\nअब\n",result.out());assertEquals(0,stats(result).get("live"));
    }
    @Test void nativeCollectionCheckingPreservesInvarianceAndImmutableBindings(){
        for(String body:List.of(
            "स्थिर अ: सूची<पूर्णांक> = नया सूची<पाठ>();",
            "स्थिर अ: सर्ववस्तु[] = नया पूर्णांक[१];",
            "स्थिर अ: पूर्णांक[] = नया पूर्णांक[]{१}; प्रत्येक (न: पूर्णांक में अ) { न = २; }",
            "स्थिर अ: पूर्णांक[] = नया पूर्णांक[]{१}; प्रत्येक (क: पूर्णांक, व: पूर्णांक में अ) {}",
            "स्थिर म: मानचित्र<पाठ,पूर्णांक> = नया मानचित्र<पाठ,पूर्णांक>(); म.जोड़ो(१);",
            "स्थिर अ: पूर्णांक[] = नया पूर्णांक[]{१}; अ.साफ़();"))
            assertThrows(CompilationException.class,()->core("कार्य मुख्य() -> शून्य { "+body+" }"));
    }

    @Test void nativeExceptionsPreserveSubtypeIdentityAndRethrow()throws Exception{
        requireTools();var result=nativeRun("""
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
                प्रयास {
                    प्रयास { फेंको नया विशेष("भीतर"); }
                    पकड़ो (ग: विशेष) { फेंको नया अपवाद("बाहर पहुँचा"); }
                    पकड़ो (ग: अपवाद) { त्रुटि("सहचर पकड़ो चला"); }
                    अंततः { लिखो("साफ"); }
                } पकड़ो (ग: अपवाद) { लिखो(ग.संदेश); }
            }
            """,GC_STRESS);
        assertEquals("सत्य मूल\nभीतर\nसत्य मूल\nबाहर\nसत्य\nसाफ\nबाहर पहुँचा\n",result.out());assertEquals(0,stats(result).get("live"));
    }
    @Test void nativeFinallyRunsForReturnsAndLoopControl()throws Exception{
        requireTools();var result=nativeRun("""
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
            """,GC_STRESS);
        assertEquals("वापसी\nपकड़\n७ ८\nचक्र १\nचक्र २\nसफाई\nदूसरा\nसामान्य\n",result.out());assertEquals(0,stats(result).get("live"));
    }
    @Test void nativeFinallyReplacesPendingCompletions()throws Exception{
        requireTools();var result=nativeRun("""
            कार्य फल() -> पूर्णांक {
                प्रयास { लौटाओ १; } अंततः { फेंको नया अपवाद("सफाई"); }
            }
            कार्य मुख्य() -> शून्य {
                प्रयास { लिखो(फल()); } पकड़ो (गलती: अपवाद) { लिखो(गलती.संदेश); }
                प्रयास {
                    प्रयास { फेंको नया अपवाद("पहला"); } अंततः { फेंको नया अपवाद("अंतिम"); }
                } पकड़ो (गलती: अपवाद) { लिखो(गलती.संदेश); }
            }
            """,GC_STRESS);
        assertEquals("सफाई\nअंतिम\n",result.out());assertEquals(0,stats(result).get("live"));
    }
    @Test void nativeRuntimeFailuresAreCatchable()throws Exception{
        requireTools();var result=nativeRun("""
            कार्य मुख्य() -> शून्य {
                प्रयास { लिखो(१ / ०); } पकड़ो (गलती: अपवाद) { लिखो(गलती.संदेश); }
                प्रयास { लिखो(नया पूर्णांक[०].लो(०)); } पकड़ो (गलती: अपवाद) { लिखो("सीमा"); }
                प्रयास { त्रुटि("स्वयं"); } पकड़ो (गलती: अपवाद) { लिखो(गलती.संदेश); }
                प्रयास { लिखो(पूर्णांकपाठ("abc")); } पकड़ो (गलती: अपवाद) { लिखो("पाठ"); }
                स्थिर खाली: अपवाद = रिक्त;
                प्रयास { फेंको खाली; } पकड़ो (गलती: अपवाद) { लिखो("रिक्त"); }
                लिखो("चालू");
            }
            """,GC_STRESS);
        assertEquals("शून्य से भाग देना संभव नहीं है।\nसीमा\nस्वयं\nपाठ\nरिक्त\nचालू\n",result.out());assertEquals(0,stats(result).get("live"));
    }
    @Test void coreSupportsExceptionLayoutsAndPreservesExceptionChecking()throws Exception{
        var p=core("""
            वर्ग विशेष विस्तार अपवाद { निर्माता(व: पाठ) : आधार(व) {} }
            कार्य मुख्य() -> शून्य {
                प्रयास { फेंको नया विशेष("क"); } पकड़ो (ग: अपवाद) { लिखो(ग.संदेश); } अंततः { लिखो("अंत"); }
            }
            """);
        assertEquals(-5,p.exceptionType().id());assertEquals(List.of(8,16),p.exceptionType().fields().stream().map(ObjectField::offset).toList());
        assertTrue(p.classes().get(0).assignableTypes().contains(-5));assertTrue(LlvmEmitter.emit(p).contains("@bh_protect"));
        for(String body:List.of("फेंको १;","फेंको रिक्त;","फेंको;",
            "प्रयास {} पकड़ो (क: पूर्णांक) {}", "प्रयास {} पकड़ो (क: अपवाद) {} पकड़ो (ख: अपवाद) {}",
            "प्रयास {} पकड़ो (क: अपवाद) { क = नया अपवाद(); }", "प्रयास {} पकड़ो (क: अपवाद) { क.संदेश = \"ब\"; }",
            "प्रयास {} अंततः { लौटाओ; }", "जबतक (सत्य) { प्रयास {} अंततः { जारी; } }"))
            assertThrows(CompilationException.class,()->core("कार्य मुख्य() -> शून्य { "+body+" }"));
    }
    @Test void nativeFinallyPreservesEvaluatedReturnValuesAndOuterBindings()throws Exception{
        requireTools();var result=nativeRun("""
            कार्य फल() -> पाठ {
                मान व: पाठ = "अ"+"ब";
                प्रयास { लौटाओ व; } अंततः {
                    व = "क"+"ख";
                    प्रयास { त्रुटि("भीतर"); } पकड़ो (ग: अपवाद) { लिखो(ग.संदेश); }
                    लिखो(व);
                }
            }
            कार्य मुख्य() -> शून्य {
                मान न: पूर्णांक = ०; मान शब्द: पाठ = "";
                प्रयास { न = ७; शब्द = "मू"+"ल"; त्रुटि("क"); } पकड़ो (ग: अपवाद) { न = न + १; }
                लिखो(न,शब्द,फल());
            }
            """,GC_STRESS);assertEquals("भीतर\nकख\n८ मूल अब\n",result.out());assertEquals(0,stats(result).get("live"));
    }
    @Test void nativeExceptionStressDiscardsAbandonedRootsAndKeepsPendingValues()throws Exception{
        requireTools();var result=nativeRun(Files.readString(Path.of("examples/native/exceptions.bhash")),GC_STRESS);
        assertEquals("१२४७५० ५००\nबचा😀\n",result.out());
        var s=stats(result);assertTrue(s.get("allocations")>5000,result.err());assertEquals(0,s.get("live"));assertTrue(s.get("peak")<100000,result.err());
    }
    @Test void nativeUncaughtExceptionRunsFinallyAndReclaimsRoots()throws Exception{
        requireTools();var result=nativeRun("""
            कार्य मुख्य() -> शून्य {
                प्रयास { प्रयास { फेंको नया अपवाद("अ"+"ब"); } अंततः { लिखो("भीतर"); } }
                अंततः { लिखो("बाहर"); }
            }
            """,GC_STRESS);
        assertEquals(1,result.exit());assertEquals("भीतर\nबाहर\n",result.out());assertTrue(result.err().contains("अब"));
        assertTrue(result.err().contains("native.bhash:"));assertEquals(0,stats(result).get("live"));
    }

    @Test void nativeNestedCleanupTransportsForeachAndLocalLoopExits()throws Exception{
        requireTools();var result=nativeRun("""
            कार्य मुख्य() -> शून्य {
                मान योग: पूर्णांक = ०;
                प्रत्येक (न: पूर्णांक में नया पूर्णांक[]{१,२,३,४}) {
                    प्रयास {
                        प्रयास { यदि (न == १) { जारी; } यदि (न == ३) { विराम; } योग = योग + न; }
                        अंततः { लिखो("भीतर",न); }
                    } अंततः {
                        मान क: पूर्णांक = ०;
                        जबतक (क < २) {
                            प्रयास { क = क + १; यदि (क == १) { जारी; } विराम; }
                            अंततः { लिखो("स्थानीय",क); }
                        }
                        लिखो("बाहर",न);
                    }
                }
                लिखो(योग);
            }
            """,GC_STRESS);
        assertEquals("भीतर १\nस्थानीय १\nस्थानीय २\nबाहर १\nभीतर २\nस्थानीय १\nस्थानीय २\nबाहर २\nभीतर ३\nस्थानीय १\nस्थानीय २\nबाहर ३\n२\n",result.out());
        assertEquals(0,stats(result).get("live"));
    }
    @Test void nativeCancelledReturnsAndAbandonedRegionsReleaseLargeText()throws Exception{
        requireTools();var result=nativeRun("""
            आयात मानक.पाठ्य;
            कार्य फल() -> पाठ {
                प्रयास {
                    प्रयास { लौटाओ पाठ्य.दोहराओ("क",३००००); }
                    अंततः { फेंको नया अपवाद("बदला"); }
                } पकड़ो (ग: अपवाद) {
                    स्थिर ताजा: पाठ = पाठ्य.दोहराओ("ख",३००००); लिखो(लंबाई(ताजा)); लौटाओ "बचा";
                }
            }
            कार्य मुख्य() -> शून्य {
                मान न: पूर्णांक = ०;
                जबतक (न < ५) {
                    प्रयास { स्थिर भारी: पाठ = पाठ्य.दोहराओ("क",३००००); त्रुटि("दोष"); }
                    पकड़ो (ग: अपवाद) { स्थिर अगला: पाठ = पाठ्य.दोहराओ("ख",३००००); यदि (लंबाई(अगला) != ३००००) { त्रुटि("पाठ"); } }
                    न = न + १;
                }
                लिखो(फल());
            }
            """,GC_STRESS);
        assertEquals("३००००\nबचा\n",result.out());assertEquals(0,stats(result).get("live"));
        assertTrue(stats(result).get("peak")<130000,result.err());
    }
    @Test void nativeCustomExceptionsSupportInterfacesCollectionsAndConstructorUnwinding()throws Exception{
        requireTools();var result=nativeRun("""
            आयात मानक.पाठ्य;
            अनुबंध नामी { कार्य नाम() -> पाठ; }
            वर्ग विशेष विस्तार अपवाद पालन नामी {
                सार्वजनिक मान अंश: सूची<सर्ववस्तु>;
                निर्माता(व: पाठ) : आधार(व) { अंश = नया सूची<सर्ववस्तु>{यह.संदेश,७}; }
                सार्वजनिक कार्य नाम() -> पाठ { लौटाओ संदेश; }
            }
            वर्ग अधूरा {
                सार्वजनिक मान भारी: पाठ;
                निर्माता() { भारी = पाठ्य.दोहराओ("क",३००००); फेंको नया विशेष("मू"+"ल"); }
            }
            कार्य लेआओ() -> सर्ववस्तु[] {
                प्रयास { लौटाओ नया सर्ववस्तु[]{नया विशेष("क"+"ख"),सत्य}; }
                अंततः { स्थिर क्षण: पाठ = "अ"+"ब"; }
            }
            कार्य मुख्य() -> शून्य {
                प्रयास { स्थिर क: अधूरा = नया अधूरा(); }
                पकड़ो (ग: विशेष) {
                    स्थिर न: नामी = ग; स्थिर व: सर्ववस्तु = ग;
                    स्थिर म: मानचित्र<अपवाद,पाठ> = नया मानचित्र<अपवाद,पाठ>{ग:"है"};
                    लिखो(न.नाम(), प्रकारहै<अपवाद>(व), परिवर्तित<विशेष>(व) == ग, म[ग], परिवर्तित<पूर्णांक>(ग.अंश[१]));
                    स्थिर ताजा: पाठ = पाठ्य.दोहराओ("ख",३००००); लिखो(लंबाई(ताजा));
                }
                स्थिर अ: सर्ववस्तु[] = लेआओ(); लिखो(परिवर्तित<विशेष>(अ[०]).संदेश, परिवर्तित<तार्किक>(अ[१]));
            }
            """,GC_STRESS);
        assertEquals("मूल सत्य सत्य है ७\n३००००\nकख सत्य\n",result.out());assertEquals(0,stats(result).get("live"));
        assertTrue(stats(result).get("peak")<130000,result.err());
    }
    private static List<SourceFile> exceptionSources(){
        return List.of(new SourceFile("types.bhash","संकुल दोषपुस्तक; सार्वजनिक वर्ग विशेष विस्तार अपवाद { निर्माता(व: पाठ) : आधार(व) {} } सार्वजनिक कार्य चलाओ() -> शून्य { फेंको नया विशेष(\"मू\"+\"ल\"); }"),
            new SourceFile("app.bhash","आयात दोषपुस्तक.विशेष; आयात दोषपुस्तक.चलाओ; कार्य मुख्य() -> शून्य { प्रयास { चलाओ(); } पकड़ो (ग: विशेष) { लिखो(ग.संदेश, प्रकारहै<अपवाद>(ग)); } अंततः { लिखो(\"अंत\"); } }"));
    }
    @Test void nativeExceptionsAcrossPackagesMatchJvm()throws Exception{
        requireTools();var result=nativeRun(exceptionSources(),GC_STRESS);
        assertEquals("मूल सत्य\nअंत\n",result.out());assertEquals(0,stats(result).get("live"));
    }

    @Test void nativeExceptionsCoverSupportedRuntimeFailureCategories()throws Exception{
        requireTools();var result=nativeRun("""
            आयात मानक.पाठ्य;
            कार्य मुख्य() -> शून्य {
                प्रयास { लिखो("अधूरा",१ % ०); } पकड़ो (ग: अपवाद) { लिखो(१,ग.संदेश); }
                प्रयास { लिखो(अंश("क",०,२)); } पकड़ो (ग: अपवाद) { लिखो(२,ग.संदेश); }
                प्रयास { लिखो(पाठ्य.बदलो("क","","ख")); } पकड़ो (ग: अपवाद) { लिखो(३,ग.संदेश); }
                प्रयास { लिखो(पाठ्य.दोहराओ("क",-१)); } पकड़ो (ग: अपवाद) { लिखो(४,ग.संदेश); }
                प्रयास { लिखो(पूर्णांकपाठ("9223372036854775808")); } पकड़ो (ग: अपवाद) { लिखो(५,ग.संदेश); }
                प्रयास { स्थिर म: मानचित्र<पाठ,पूर्णांक> = नया मानचित्र<पाठ,पूर्णांक>(); लिखो(म["क"]); }
                पकड़ो (ग: अपवाद) { लिखो(६,ग.संदेश); }
                प्रयास { स्थिर म: मानचित्र<अपवाद,पाठ> = नया मानचित्र<अपवाद,पाठ>(); स्थिर क: अपवाद = रिक्त; म[क] = "ख"; }
                पकड़ो (ग: अपवाद) { लिखो(७,ग.संदेश); }
                प्रयास { स्थिर अ: सूची<पूर्णांक> = नया सूची<पूर्णांक>{१}; प्रत्येक (न: पूर्णांक में अ) { अ.साफ़(); } }
                पकड़ो (ग: अपवाद) { लिखो(८,ग.संदेश); }
                प्रयास { स्थिर व: सर्ववस्तु = "१"; लिखो(परिवर्तित<पूर्णांक>(व)); }
                पकड़ो (ग: अपवाद) { लिखो(९,ग.संदेश); }
                प्रयास { स्थिर अ: सूची<पूर्णांक> = रिक्त; अ.जोड़ो(१); }
                पकड़ो (ग: अपवाद) { लिखो(१०,ग.संदेश); }
                प्रयास { स्थिर अ: पूर्णांक[][] = नया पूर्णांक[०][-१]; }
                पकड़ो (ग: अपवाद) { लिखो(११,ग.संदेश); }
            }
            """,GC_STRESS);
        assertEquals(0,result.exit());assertFalse(result.out().contains("अधूरा"));assertEquals(11,result.out().lines().count());assertEquals(0,stats(result).get("live"));
    }
    @Test void nativeProtectedReturnsPreserveBooleanUniversalAndObjectResults()throws Exception{
        requireTools();var result=nativeRun("""
            कार्य हाँ() -> तार्किक { प्रयास { लौटाओ सत्य; } अंततः { स्थिर क: पाठ = "अ"+"ब"; } }
            कार्य अंक() -> सर्ववस्तु { प्रयास { लौटाओ ७; } अंततः { स्थिर क: पाठ = "क"+"ख"; } }
            कार्य वस्तु() -> अपवाद { प्रयास { लौटाओ नया अपवाद("मू"+"ल"); } अंततः { स्थिर क: पाठ = "ग"+"घ"; } }
            कार्य मुख्य() -> शून्य { लिखो(हाँ(),परिवर्तित<पूर्णांक>(अंक()),वस्तु().संदेश); }
            """,GC_STRESS);assertEquals("सत्य ७ मूल\n",result.out());assertEquals(0,stats(result).get("live"));
    }

    @Test void fileCoreRecognizesAliasesAndTypedOperations()throws Exception{
        var p=core("""
            आयात मानक.फ़ाइल उपनाम फ;
            कार्य मुख्य() -> शून्य { लिखो(फ.समर्थित(),फ.है("x")); फ.पाठलिखो("x",फ.पाठपढ़ो("y")); }
            """);
        var ir=LlvmEmitter.emit(p);
        for(String operation:List.of("supported","exists","read_text","write_text"))assertTrue(ir.contains("call "+(operation.equals("write_text")?"void":operation.equals("read_text")?"ptr":"i32")+" @bh_file_"+operation));
        assertTrue(LlvmEmitter.emit(core("कार्य मुख्य() -> शून्य { मानक.फ़ाइल.बाइटपढ़ो(\"x\"); }")).contains("@bh_buffer_call"));
    }
    private String fileLiteral(Path path){return "\""+path.toString().replace("\\","/").replace("\"","\\\"")+"\"";}
    @Test void nativeFileTextRoundTripsAndErrorsMatchBothManagedTargets()throws Exception{
        requireNumericTargets();
        Path data=temporary.resolve("पाठ😀.txt"),bad=temporary.resolve("invalid.bin");
        Files.write(bad,new byte[]{(byte)0xe0,(byte)0x80,(byte)0xaf});
        String source="""
            आयात मानक.फ़ाइल उपनाम फ;
            कार्य मुख्य() -> शून्य {
                लिखो(फ.समर्थित(),फ.है(DIR),फ.है(MISSING));
                फ.पाठलिखो(DATA,"पुराना"); फ.पाठलिखो(DATA,"\\uFEFFभारत😀\\r\\nशून्य\\u0000अंत\\n");
                लिखो(फ.पाठपढ़ो(DATA)=="\\uFEFFभारत😀\\r\\nशून्य\\u0000अंत\\n",फ.है(DATA));
                फ.पाठलिखो(DATA,"");लिखो(फ.पाठपढ़ो(DATA)=="");
                प्रयास { फ.पाठपढ़ो(MISSING); } पकड़ो (ग: अपवाद) { लिखो(ग.संदेश); }
                प्रयास { फ.पाठपढ़ो(DIR); } पकड़ो (ग: अपवाद) { लिखो(ग.संदेश); }
                प्रयास { फ.पाठलिखो(DIR,"x"); } पकड़ो (ग: अपवाद) { लिखो(ग.संदेश); }
                प्रयास { फ.पाठलिखो(NO_PARENT,"x"); } पकड़ो (ग: अपवाद) { लिखो(ग.संदेश); }
                प्रयास { फ.पाठपढ़ो(BAD); } पकड़ो (ग: अपवाद) { लिखो(ग.संदेश); }
                प्रयास { फ.है(""); } पकड़ो (ग: अपवाद) { लिखो(ग.संदेश); }
                प्रयास { फ.पाठपढ़ो("x\\u0000y"); } पकड़ो (ग: अपवाद) { लिखो(ग.संदेश); }
                प्रयास { फ.पाठलिखो("","x"); } पकड़ो (ग: अपवाद) { लिखो(ग.संदेश); }
            }
            """.replace("NO_PARENT",fileLiteral(temporary.resolve("absent/child.txt"))).replace("MISSING",fileLiteral(temporary.resolve("missing")))
            .replace("DATA",fileLiteral(data)).replace("DIR",fileLiteral(temporary)).replace("BAD",fileLiteral(bad));
        assertEquals(0,numericExact(source).exit());
        assertEquals(0,Files.size(data));
    }
    @Test void nativeFileByteLimitsAndFailureCleanupMatchManaged()throws Exception{
        requireNumericTargets();Path large=temporary.resolve("large"),kept=temporary.resolve("kept");
        try(var f=new java.io.RandomAccessFile(large.toFile(),"rw")){f.setLength(16777217);}
        String source="""
            आयात मानक.फ़ाइल उपनाम फ;
            आयात मानक.पाठ्य उपनाम प;
            कार्य मुख्य() -> शून्य {
                मान सीमा: पाठ = प.दोहराओ("😀",५२४२८८);
                मान दोगुना: पूर्णांक = ०;
                जबतक (दोगुना < ३) { सीमा=सीमा+सीमा; दोगुना=दोगुना+१; }
                फ.पाठलिखो(KEPT,सीमा); लिखो(फ.पाठपढ़ो(KEPT)==सीमा);
                फ.पाठलिखो(KEPT,"बचा");
                प्रयास { फ.पाठलिखो(KEPT,सीमा+"x"); } पकड़ो (ग: अपवाद) { लिखो(ग.संदेश); }
                लिखो(फ.पाठपढ़ो(KEPT));
                मान गिनती: पूर्णांक = ०;
                जबतक (गिनती < ८) {
                    प्रयास { फ.पाठपढ़ो(LARGE); } पकड़ो (ग: अपवाद) { यदि (गिनती == ०) { लिखो(ग.संदेश); } }
                    अंततः { स्थिर अ: पाठ = प.दोहराओ("मूल",१०); }
                    गिनती=गिनती+१;
                }
                लिखो(गिनती);
            }
            """.replace("KEPT",fileLiteral(kept)).replace("LARGE",fileLiteral(large));
        assertEquals(0,numericExact(source).exit());assertEquals("बचा",Files.readString(kept));
    }
    @Test void nativeFileInvalidUtf8AndSymlinksMatchManaged()throws Exception{
        requireNumericTargets();
        byte[][] invalid={{(byte)0x80},{(byte)0xc0,(byte)0xaf},{(byte)0xe0,(byte)0x80,(byte)0xaf},
            {(byte)0xed,(byte)0xa0,(byte)0x80},{(byte)0xf4,(byte)0x90,(byte)0x80,(byte)0x80},
            {(byte)0xf5,(byte)0x80,(byte)0x80,(byte)0x80},{(byte)0xe2,(byte)0x82},{(byte)0xf0,(byte)0x80,(byte)0x80,(byte)0x80},
            {(byte)0xe2,(byte)0x28,(byte)0xa1}};
        var code=new StringBuilder("आयात मानक.फ़ाइल उपनाम फ; कार्य मुख्य() -> शून्य { ");
        for(int i=0;i<invalid.length;i++){
            Path file=temporary.resolve("bad-"+i);Files.write(file,invalid[i]);
            code.append("प्रयास { फ.पाठपढ़ो(").append(fileLiteral(file)).append("); } पकड़ो (ग: अपवाद) { लिखो(ग.संदेश); } ");
        }
        Path target=temporary.resolve("target.txt"),link=temporary.resolve("link.txt"),broken=temporary.resolve("broken");
        Files.writeString(target,"लिंक😀");Files.createSymbolicLink(link,target);Files.createSymbolicLink(broken,temporary.resolve("missing"));
        code.append("लिखो(फ.है(").append(fileLiteral(link)).append("),फ.है(").append(fileLiteral(broken)).append("),फ.पाठपढ़ो(").append(fileLiteral(link)).append(")); }");
        var result=numericExact(code.toString());assertEquals(0,result.exit());
        assertEquals("अमान्य UTF-8 बाइटबफ़र।\n".repeat(invalid.length)+"सत्य असत्य लिंक😀\n",result.out());
    }
    @Test void nativeFilePermissionsAndCloseFailureMatchManaged()throws Exception{
        requireNumericTargets();Path denied=temporary.resolve("denied");Files.writeString(denied,"kept");
        assumeTrue(FileSystems.getDefault().supportedFileAttributeViews().contains("posix"));
        var permissions=Files.getPosixFilePermissions(denied);
        try{
            Files.setPosixFilePermissions(denied,Set.of());assumeFalse(Files.isReadable(denied),"Privileged user bypasses permission test");
            String source="""
                आयात मानक.फ़ाइल उपनाम फ;
                कार्य मुख्य() -> शून्य {
                    प्रयास { फ.पाठपढ़ो(DENIED); } पकड़ो (ग: अपवाद) { लिखो(ग.संदेश); }
                    प्रयास { फ.पाठलिखो(DENIED,"x"); } पकड़ो (ग: अपवाद) { लिखो(ग.संदेश); }
                    प्रयास { फ.पाठलिखो("/dev/full","x"); } पकड़ो (ग: अपवाद) { लिखो(ग.संदेश); }
                }
                """.replace("DENIED",fileLiteral(denied));
            var result=numericExact(source);assertEquals(0,result.exit());
            assertEquals("फ़ाइल पढ़ी नहीं जा सकी।\nफ़ाइल लिखी नहीं जा सकी।\nफ़ाइल लिखी नहीं जा सकी।\n",result.out());
        }finally{Files.setPosixFilePermissions(denied,permissions);}
        assertEquals("kept",Files.readString(denied));
    }
    @Test void nativeFileStressExampleMatchesBothManagedTargets()throws Exception{
        requireNumericTargets();Files.createDirectories(Path.of("target/native-file-example"));
        assertEquals(0,numericExact(Files.readString(Path.of("examples/native/files.bhash"))).exit());
    }
    @Test void byteCoreRetainsDistinctKindsAndPackedBufferMetadata()throws Exception{
        var p=core("""
            वर्ग अंक { मान ब: बाइट; }
            कार्य मुख्य() -> शून्य {
                स्थिर ब: बाइटबफ़र = मानक.पाठ्य.यूटीएफ८("भारत😀");
                स्थिर स: सर्ववस्तु = परिवर्तित<बाइट>(२५५); लिखो(परिवर्तित<बाइट>(स));
                ब.भरो(परिवर्तित<बाइट>(१)); लिखो(ब.काटो(०,१).सरणी()[०]);
            }
            """);
        assertTrue(p.collections().stream().anyMatch(c->c.kind()==CollectionKind.BUFFER&&c.valueKind()==7));
        var ir=LlvmEmitter.emit(p);assertTrue(ir.contains("@box_info7"));assertTrue(ir.contains("@bh_buffer_call"));
    }
    @Test void nativeBytesConversionsBoxingAndCollectionKeysMatchManaged()throws Exception{
        requireNumericTargets();assertEquals(0,numericExact("""
            आयात मानक.जेसन उपनाम ज;
            वर्ग अंक { सार्वजनिक मान ब: बाइट; }
            कार्य पहचान(ब: बाइट) -> बाइट { लौटाओ ब; }
            कार्य मुख्य() -> शून्य {
                स्थिर ब: बाइट = पहचान(परिवर्तित<बाइट>(२५५)); स्थिर श: अंक = नया अंक();
                लिखो(ब,श.ब,ब+ब,-ब,+ब,ब>२५४,परिवर्तित<पूर्णांक>(ब),परिवर्तित<दशमलव>(ब),परिवर्तित<पाठ>(ब));
                लिखो(परिवर्तित<बाइट>("२५५"),परिवर्तित<बाइट>(२५५.९),परिवर्तित<बाइट>(-०.५));
                स्थिर स: सर्ववस्तु = ब; स्थिर क: सर्ववस्तु = परिवर्तित<बाइट>(२५५);
                स्थिर धन: सर्ववस्तु = +ब; लिखो(प्रकारहै<पूर्णांक>(धन),प्रकारहै<बाइट>(धन),प्रकारहै<पूर्णांक>(ब+ब));
                लिखो(प्रकारहै<बाइट>(स),प्रकारहै<पूर्णांक>(स),प्रकारहै<पूर्णांक>(ब),परिवर्तित<बाइट>(स),स==क,स==२५५);
                स्थिर म: मानचित्र<सर्ववस्तु,पाठ> = नया मानचित्र<सर्ववस्तु,पाठ>{};
                म.रखो(स,"byte");म.रखो(२५५,"int");लिखो(म.आकार(),म.लो(क),म.लो(२५५));
                स्थिर बा: बाइट[] = नया बाइट[]{ब,परिवर्तित<बाइट>(०)};
                स्थिर ल: सूची<बाइट> = नया सूची<बाइट>{ब};ल.जोड़ो(परिवर्तित<बाइट>(१));लिखो(बा[०],ल.लो(१),ल.है(ब));
                लिखो(ज.पाठबनाओ(स),ज.पाठबनाओ(ल));
                प्रयास { लिखो(परिवर्तित<बाइट>(-१)); } पकड़ो (ग: अपवाद) { लिखो(ग.संदेश); }
                प्रयास { लिखो(परिवर्तित<बाइट>(२५६)); } पकड़ो (ग: अपवाद) { लिखो(ग.संदेश); }
                प्रयास { लिखो(परिवर्तित<बाइट>(१.०/०.०)); } पकड़ो (ग: अपवाद) { लिखो(ग.संदेश); }
                प्रयास { लिखो(परिवर्तित<पूर्णांक>(स)); } पकड़ो (ग: अपवाद) { लिखो(ग.संदेश); }
            }
            """).exit());
    }
    @Test void nativeBufferOperationsUtf8AndBinaryLayoutsMatchManaged()throws Exception{
        requireNumericTargets();assertEquals(0,numericExact("""
            आयात मानक.पाठ्य उपनाम प;
            आयात मानक.जेसन उपनाम ज;
            कार्य मुख्य() -> शून्य {
                स्थिर ब: बाइटबफ़र = नया बाइटबफ़र(२५६);
                मान इ: पूर्णांक = ०; जबतक (इ<२५६) { ब[इ]=परिवर्तित<बाइट>(इ);इ=इ+१; }
                मान योग: पूर्णांक = ०; प्रत्येक (क: बाइट में ब) { योग=योग+क; }
                लिखो(ब.आकार,ब.लंबाई(),लंबाई(ब),ब.खाली(),योग,ब.है(परिवर्तित<बाइट>(२५५)));
                स्थिर क: बाइटबफ़र = ब.काटो(२५४,२५६);क.रखो(०,परिवर्तित<बाइट>(७));स्थिर स: बाइट[] = क.सरणी();स[१]=परिवर्तित<बाइट>(८);
                लिखो(ब[२५४],क.लो(०),क[१],स[१],ज.पाठबनाओ(क));
                क.भरो(परिवर्तित<बाइट>(९));प्रत्येक (व: बाइट में क) { लिखो(व); }
                स्थिर आवरण: सर्ववस्तु = क; लिखो(प्रकारहै<बाइटबफ़र>(आवरण),परिवर्तित<बाइटबफ़र>(आवरण)==क,प्रकारहै<बाइट[]>(आवरण));
                मान क्रम: पूर्णांक = ०;प्रत्येक (व: बाइट में क) { क[क्रम]=परिवर्तित<बाइट>(व+१);क्रम=क्रम+१; }लिखो(क[०],क[१]);
                स्थिर मूलपाठ: पाठ = "\\uFEFFभारत😀\\u0000\\r\\n";
                स्थिर उ: बाइटबफ़र = प.यूटीएफ८(मूलपाठ);लिखो(प.यूटीएफ८पाठ(उ)==मूलपाठ,यूटीएफ८पाठ(यूटीएफ८बफ़र(मूलपाठ))==मूलपाठ);
                स्थिर र: बाइटबफ़र = नया बाइटबफ़र(१७);
                र.पूर्णांकलिखो(१,-९२२३३७२०३६८५४७७५८०८,असत्य);लिखो(र.पूर्णांकपढ़ो(१,असत्य),र[१],र[८]);
                र.पूर्णांकलिखो(९,९२२३३७२०३६८५४७७५८०७,सत्य);लिखो(र.पूर्णांकपढ़ो(९,सत्य),र[९],र[१६]);
                र.दशमलवलिखो(१,-०.०,सत्य);लिखो(र.दशमलवपढ़ो(१,सत्य),र.पूर्णांकपढ़ो(१,सत्य));
                र.दशमलवलिखो(९,१२३.२५,असत्य);लिखो(र.दशमलवपढ़ो(९,असत्य));
                लिखो(प.यूटीएफ८पाठ(नया बाइटबफ़र(०)),नया बाइटबफ़र{}.खाली());
                प्रयास { लिखो(प.यूटीएफ८पाठ(ब)); } पकड़ो (ग: अपवाद) { लिखो(ग.संदेश); }
                प्रयास { लिखो(क[-१]); } पकड़ो (ग: अपवाद) { लिखो(ग.संदेश); }
                प्रयास { र.पूर्णांकलिखो(१०,१,असत्य); } पकड़ो (ग: अपवाद) { लिखो(ग.संदेश); }
                प्रयास { क.काटो(१,०); } पकड़ो (ग: अपवाद) { लिखो(ग.संदेश); }
                प्रयास { स्थिर अ: बाइटबफ़र = नया बाइटबफ़र(-१); } पकड़ो (ग: अपवाद) { लिखो(ग.संदेश); }
                प्रयास { स्थिर अ: बाइटबफ़र = नया बाइटबफ़र(२१४७४८३६४८); } पकड़ो (ग: अपवाद) { लिखो(ग.संदेश); }
            }
            """).exit());
    }
    @Test void nativeBinaryFilesByteLimitsAndFailuresMatchManaged()throws Exception{
        requireNumericTargets();Path file=temporary.resolve("बाइट😀.bin"),large=temporary.resolve("large");
        try(var f=new java.io.RandomAccessFile(large.toFile(),"rw")){f.setLength(16777217);}
        String code="""
            आयात मानक.फ़ाइल उपनाम फ;
            आयात मानक.पाठ्य उपनाम प;
            कार्य मुख्य() -> शून्य {
                स्थिर ब: बाइटबफ़र = नया बाइटबफ़र(२५६);मान इ: पूर्णांक = ०;
                जबतक (इ<२५६) { ब[इ]=परिवर्तित<बाइट>(इ);इ=इ+१; }
                फ.बाइटलिखो(FILE,ब);स्थिर क: बाइटबफ़र = फ.बाइटपढ़ो(FILE);
                मान योग: पूर्णांक = ०;प्रत्येक (व: बाइट में क) { योग=योग+व; }लिखो(क.आकार,योग,क[२५५]);
                फ.बाइटलिखो(FILE,नया बाइटबफ़र(०));लिखो(फ.बाइटपढ़ो(FILE).खाली());
                स्थिर सीमा: बाइटबफ़र = नया बाइटबफ़र(१६७७७२१६);सीमा[१६७७७२१५]=परिवर्तित<बाइट>(२५५);
                फ.बाइटलिखो(FILE,सीमा);लिखो(फ.बाइटपढ़ो(FILE)[१६७७७२१५]);
                फ.पाठलिखो(FILE,"बचा");
                प्रयास { फ.बाइटलिखो(FILE,नया बाइटबफ़र(१६७७७२१७)); } पकड़ो (ग: अपवाद) { लिखो(ग.संदेश); }
                लिखो(फ.पाठपढ़ो(FILE));
                प्रयास { फ.बाइटपढ़ो(LARGE); } पकड़ो (ग: अपवाद) { लिखो(ग.संदेश); }
                प्रयास { फ.बाइटलिखो("",ब); } पकड़ो (ग: अपवाद) { लिखो(ग.संदेश); }
                प्रयास { फ.बाइटपढ़ो(FILE+".missing"); } पकड़ो (ग: अपवाद) { लिखो(ग.संदेश); }
            }
            """.replace("FILE",fileLiteral(file)).replace("LARGE",fileLiteral(large));
        assertEquals(0,numericExact(code).exit());assertEquals("बचा",Files.readString(file));
    }
    @Test void nativeBufferStressAndUnwindingMatchManaged()throws Exception{
        requireNumericTargets();Files.createDirectories(Path.of("target/native-byte-example"));
        var result=numericExact(Files.readString(Path.of("examples/native/bytes.bhash")));
        assertEquals(0,result.exit());assertTrue(stats(result).get("allocations")>3000,result.err());
    }
    @Test void timeCoreRecognizesAllSixSignaturesAndAliases()throws Exception{
        var p=core("""
            आयात मानक.समय उपनाम स;
            कार्य मुख्य() -> शून्य { स्थिर त: पूर्णांक = स.अभी(); लिखो(स.पढ़ो(स.पाठबनाओ(त)),स.वर्ष(त),स.माह(त),स.दिन(त)); }
            """);
        var ir=LlvmEmitter.emit(p);
        for(String name:List.of("now","read","write","year","month","day"))assertTrue(ir.contains("@bh_time_"+name+"("));
    }
    @Test void nativeUtcCalendarGoldenDatesAndPreEpochMillisecondsMatchManaged()throws Exception{
        requireNumericTargets();var result=numericExact("""
            आयात मानक.समय उपनाम स;
            कार्य मुख्य() -> शून्य {
                स्थिर क्षणसूची: पूर्णांक[] = नया पूर्णांक[]{-६२१३५५९६८०००००,२५३४०२३००७९९९९९,-१,-१०००,-१००१,-८६४०००००,-८६४००००१,०,१,९५१८२७६९६७८९,२१४७४८३६४८०००};
                प्रत्येक (क: पूर्णांक में क्षणसूची) { स्थिर प: पाठ = स.पाठबनाओ(क);लिखो(प,स.पढ़ो(प)==क,स.वर्ष(क),स.माह(क),स.दिन(क)); }
                स्थिर दिनांक: पाठ[] = नया पाठ[]{"0001-01-01T00:00:00.000Z","0400-02-29T23:59:59.999Z","1600-02-29T00:00:00.000Z","1900-03-01T00:00:00.000Z","2000-02-29T12:34:56.789Z","2100-03-01T00:00:00.000Z","2400-02-29T00:00:00.000Z","9999-12-31T23:59:59.999Z"};
                प्रत्येक (प: पाठ में दिनांक) { लिखो(स.पढ़ो(प),स.पाठबनाओ(स.पढ़ो(प))==प); }
            }
            """);assertEquals(0,result.exit());
        assertTrue(result.out().contains("1969-12-31T23:59:59.999Z सत्य १९६९ १२ ३१"));
    }
    @Test void nativeUtcEveryYearMatchesManagedCalendar()throws Exception{
        requireNumericTargets();assertEquals(0,numericExact("""
            आयात मानक.समय उपनाम स;
            आयात मानक.पाठ्य उपनाम प;
            कार्य मुख्य() -> शून्य {
                मान वर्षमान: पूर्णांक = १;मान योग: पूर्णांक = ०;
                जबतक (वर्षमान <= ९९९९) {
                    स्थिर अंक: पाठ = परिवर्तित<पाठ>(वर्षमान);
                    स्थिर मूल: पाठ = प.दोहराओ("0",४-प.लंबाई(अंक))+अंक+"-03-01T12:34:56.789Z";
                    स्थिर त: पूर्णांक = स.पढ़ो(मूल);
                    यदि (स.पाठबनाओ(त)!=मूल || स.वर्ष(त)!=वर्षमान || स.माह(त)!=३ || स.दिन(त)!=१) { त्रुटि("calendar mismatch"); }
                    योग=योग+त;वर्षमान=वर्षमान+१;
                }
                लिखो(योग);
            }
            """).exit());
    }
    @Test void nativeUtcInvalidTextAndRangesMatchManaged()throws Exception{
        requireNumericTargets();var code=new StringBuilder("आयात मानक.समय उपनाम स; कार्य मुख्य() -> शून्य { ");
        for(String value:List.of("","0000-01-01T00:00:00.000Z","10000-01-01T00:00:00.000Z","1900-02-29T00:00:00.000Z",
            "2001-02-29T00:00:00.000Z","2024-04-31T00:00:00.000Z","2000-00-01T00:00:00.000Z","2000-13-01T00:00:00.000Z",
            "2000-01-00T00:00:00.000Z","2000-01-01T24:00:00.000Z","2000-01-01T23:60:00.000Z","2000-01-01T23:59:60.000Z",
            "2000-01-01T00:00:00Z","2000-01-01T00:00:00.00Z","2000-01-01T00:00:00.0000Z","2000-01-01t00:00:00.000Z",
            "2000-01-01T00:00:00.000z","2000-01-01T00:00:00.000+00:00","2000-01-01T00:00:00.000Z\n",
            "२०००-01-01T00:00:00.000Z","2000-01-01T00:00:00.00xZ","2000-01-01T00:00:00.000Z\0")){
            code.append("प्रयास { स.पढ़ो(").append(StandardLibraryTest.literal(value)).append("); } पकड़ो (ग: अपवाद) { लिखो(ग.संदेश); } ");
        }

        for(String operation:List.of("पाठबनाओ","वर्ष","माह","दिन"))for(String value:List.of("-६२१३५५९६८००००१","२५३४०२३००८०००००","-९२२३३७२०३६८५४७७५८०८","९२२३३७२०३६८५४७७५८०७"))
            code.append("प्रयास { स.").append(operation).append("(").append(value).append("); } पकड़ो (ग: अपवाद) { लिखो(ग.संदेश); } ");
        code.append("}");var result=numericExact(code.toString());assertEquals(0,result.exit());
        assertEquals(22,result.out().lines().filter(x->x.equals("अमान्य UTC समय पाठ।")).count());
        assertEquals(16,result.out().lines().filter(x->x.equals("समय सीमा से बाहर है।")).count());
    }
    @Test void nativeUtcClockUsesCurrentEpochMilliseconds()throws Exception{
        requireTools();long before=System.currentTimeMillis();
        var result=nativeOnlyRun(List.of(new SourceFile("clock.bhash","कार्य मुख्य() -> शून्य { लिखो(परिवर्तित<पाठ>(मानक.समय.अभी())); }")),GC_STRESS);
        long after=System.currentTimeMillis(),actual=Long.parseLong(result.out().strip());
        // Older JVMs sample Windows' coarse wall clock; native uses the precise API.
        // Allow two scheduler ticks when comparing these independent clock samples.
        long resolution=NativePlatform.windows()?32:0;
        assertEquals(0,result.exit());assertTrue(actual>=before-resolution&&actual<=after+resolution,result.out());assertEquals(0L,stats(result).get("live"));
    }
    @Test void nativeUtcStressAndPortableLibraryExampleMatchManaged()throws Exception{
        requireNumericTargets();var result=numericExact(Files.readString(Path.of("examples/native/time.bhash")));
        assertEquals(0,result.exit());assertTrue(stats(result).get("allocations")>3000,result.err());assertTrue(stats(result).get("peak")<100000,result.err());
        assertEquals(0,numericExact(Files.readString(Path.of("examples/standard-library/portable.bhash"))).exit());
    }
    @Test void coreRecognizesBothConsoleInputFunctions()throws Exception{
        String ir=LlvmEmitter.emit(core("कार्य मुख्य() -> शून्य { लिखो(पाठपढ़ो(),पढ़ो()); }"));
        assertTrue(ir.contains("call ptr @bh_console_text("));assertTrue(ir.contains("call i64 @bh_console_integer("));
    }
    private Result consoleRun(List<String> command,byte[] input,boolean nativeProgram)throws Exception{
        Path stdin=temporary.resolve("console-input"),out=temporary.resolve("console-out"),err=temporary.resolve("console-err");
        Files.write(stdin,input);var builder=new ProcessBuilder(command).redirectInput(stdin.toFile()).redirectOutput(out.toFile()).redirectError(err.toFile());
        if(nativeProgram)builder.environment().putAll(GC_STRESS);var process=builder.start();
        try{assertTrue(process.waitFor(30,TimeUnit.SECONDS),"Console program timed out");
            return new Result(process.exitValue(),Files.readString(out).replace("\r\n","\n"),Files.readString(err));
        }finally{if(process.isAlive())process.destroyForcibly();}
    }
    @Test void nativeConsoleMatchesJvmClrAndExactFixturesUnderForcedGc()throws Exception{
        requireNumericTargets();int index=0;
        for(var fixture:ConsoleFixtures.cases()){
            var sources=List.of(new SourceFile("console.bhash",fixture.source()));
            Path exe=executable("console"+index),jar=temporary.resolve("console"+index+".jar"),clr=temporary.resolve("console-clr"+index++);
            new NativeCompiler(!NativePlatform.windows()).compile(sources,exe);new JvmCompiler().compile(sources,jar);new ClrCompiler().compile(sources,clr);
            var nativeResult=consoleRun(List.of(exe.toString()),fixture.input(),true);
            assertEquals(0,nativeResult.exit(),fixture.name()+nativeResult.err());assertEquals(fixture.expected(),nativeResult.out(),fixture.name());
            assertEquals(0L,stats(nativeResult).get("live"));
            if(fixture.name().equals("stress")){assertTrue(stats(nativeResult).get("allocations")>5000);assertTrue(stats(nativeResult).get("peak")<2500000);}
            for(var command:List.of(List.of(Path.of(System.getProperty("java.home"),"bin","java").toString(),"-Dfile.encoding=UTF-8","-jar",jar.toString()),List.of(ClrCompiler.executable(),clr.resolve("BhashProgram.dll").toString()))){
                var managed=consoleRun(command,fixture.input(),false);assertEquals(nativeResult.exit(),managed.exit(),managed.err());assertEquals(nativeResult.out(),managed.out(),fixture.name());
            }
        }
    }
    @Test void nativeConsoleCrReturnsBeforeNextInputByteAndFlushesOutput()throws Exception{
        requireNumericTargets();var sources=List.of(new SourceFile("console.bhash","कार्य मुख्य() -> शून्य { लिखो(पाठपढ़ो()); लिखो(पाठपढ़ो()); }"));
        Path exe=executable("interactive"),jar=temporary.resolve("interactive.jar"),clr=temporary.resolve("interactive-clr");
        new NativeCompiler(!NativePlatform.windows()).compile(sources,exe);new JvmCompiler().compile(sources,jar);new ClrCompiler().compile(sources,clr);
        for(var command:List.of(List.of(exe.toString()),List.of(Path.of(System.getProperty("java.home"),"bin","java").toString(),"-Dfile.encoding=UTF-8","-jar",jar.toString()),List.of(ClrCompiler.executable(),clr.resolve("BhashProgram.dll").toString()))){
            var process=new ProcessBuilder(command).redirectError(temporary.resolve("interactive.err").toFile()).start();
            var executor=java.util.concurrent.Executors.newSingleThreadExecutor();
            try{
                var reader=new java.io.BufferedReader(new java.io.InputStreamReader(process.getInputStream(),StandardCharsets.UTF_8));
                var first=executor.submit(()->reader.readLine());process.getOutputStream().write(ConsoleFixtures.utf8("पहला\r"));process.getOutputStream().flush();
                assertEquals("पहला",first.get(5,TimeUnit.SECONDS),"CR must finish the line without waiting for LF");
                process.getOutputStream().write(ConsoleFixtures.utf8("\nदूसरा"));process.getOutputStream().close();
                assertEquals("दूसरा",executor.submit(()->reader.readLine()).get(5,TimeUnit.SECONDS));assertTrue(process.waitFor(10,TimeUnit.SECONDS));assertEquals(0,process.exitValue());
            }finally{process.destroyForcibly();executor.shutdownNow();}
        }
    }
    @Test void nativeConsoleFactorialAndExampleMatchManaged()throws Exception{
        requireNumericTargets();
        for(String file:List.of("examples/factorial-console.bhash","examples/native/console.bhash")){
            var sources=List.of(new SourceFile("console.bhash",Files.readString(Path.of(file))));Path exe=executable("example"),jar=temporary.resolve("example.jar");
            new NativeCompiler(!NativePlatform.windows()).compile(sources,exe);new JvmCompiler().compile(sources,jar);
            byte[] input=ConsoleFixtures.utf8(file.contains("factorial")?"५\n":"भारत😀\n+४२\n");
            var result=consoleRun(List.of(exe.toString()),input,true);var managed=consoleRun(List.of(Path.of(System.getProperty("java.home"),"bin","java").toString(),"-Dfile.encoding=UTF-8","-jar",jar.toString()),input,false);
            assertEquals(0,result.exit(),result.err());assertEquals(managed.out(),result.out());assertEquals(0L,stats(result).get("live"));
        }
    }
    @Test void coreLowersBitwiseShiftsAndSelectionWithoutHostExpressions()throws Exception{
        String ir=LlvmEmitter.emit(core("कार्य मुख्य() -> शून्य { चयन ((~१ & ३) << ६४) { विकल्प २: लिखो(-१ >>> -१,-१ >> १); शेष: लिखो(० ^ १ | २); } }"));
        for(String operation:List.of("xor i64","and i64","or i64","shl i64","ashr i64","lshr i64"))assertTrue(ir.contains(operation),operation);
        assertTrue(ir.contains(", 63"));
    }
    @Test void nativeBitwiseAndSelectionFixturesMatchJvmClrUnderForcedGc()throws Exception{
        requireNumericTargets();
        for(var fixture:NativeControlFixtures.cases()){
            var result=numericExact(fixture.source());assertEquals(0,result.exit(),fixture.name()+result.err());assertEquals(fixture.expected(),result.out(),fixture.name());
            if(fixture.name().equals("gc-and-completions")){assertTrue(stats(result).get("allocations")>4000,result.err());assertTrue(stats(result).get("peak")<100000,result.err());}
        }
    }
    @Test void nativeSelectionKeepsSharedTypeLabelScopeAndFlowDiagnostics(){
        for(String source:List.of(
            "कार्य मुख्य() -> शून्य { लिखो(सत्य & असत्य); }",
            "कार्य मुख्य() -> शून्य { लिखो(१.० << २); }",
            "कार्य मुख्य() -> शून्य { चयन (१) { विकल्प १: विकल्प +1: } }",
            "कार्य मुख्य() -> शून्य { चयन (०) { विकल्प -०: विकल्प 0: } }",
            "कार्य मुख्य() -> शून्य { चयन (\"अ\") { विकल्प \"अ\": विकल्प \"\\u0905\": } }",
            "कार्य मुख्य() -> शून्य { चयन (सत्य) { विकल्प सत्य: विकल्प सत्य: } }",
            "कार्य मुख्य() -> शून्य { चयन (१) { शेष: शेष: } }",
            "कार्य मुख्य() -> शून्य { चयन (१.०) {} }",
            "कार्य मुख्य() -> शून्य { चयन (१) { विकल्प \"एक\": } }",
            "कार्य मुख्य() -> शून्य { चयन (१) { शेष: जारी; } }",
            "कार्य मुख्य() -> शून्य { चयन (१) { विकल्प १: स्थिर अंदर: पूर्णांक = १; शेष: लिखो(अंदर); } }",
            "कार्य मुख्य() -> शून्य { चयन (१) { शेष: विराम; लिखो(१); } }",
            "कार्य गलत(क: पूर्णांक) -> पूर्णांक { चयन (क) { विकल्प १: लौटाओ १; } } कार्य मुख्य() -> शून्य {}")){
            var error=assertThrows(CompilationException.class,()->core(source));
            for(var target:List.of(Target.JVM,Target.CLR)){
                var reference=assertThrows(CompilationException.class,()->ManagedEmitter.emit(List.of(new SourceFile("native.bhash",source)),target));
                assertEquals(reference.getMessage(),error.getMessage());
            }
        }
    }
    @Test void nativeSwitchConsoleExamplePreservesGroupedCasesAndDefault()throws Exception{
        requireNumericTargets();var sources=List.of(new SourceFile("switch-console.bhash",Files.readString(Path.of("examples/switch-console.bhash"))));
        Path exe=executable("selection"),jar=temporary.resolve("selection.jar"),clr=temporary.resolve("selection-clr");
        new NativeCompiler(!NativePlatform.windows()).compile(sources,exe);new JvmCompiler().compile(sources,jar);new ClrCompiler().compile(sources,clr);
        for(String input:List.of("०\n","२\n","९\n")){
            var result=consoleRun(List.of(exe.toString()),ConsoleFixtures.utf8(input),true);assertEquals(0,result.exit(),result.err());assertEquals(0L,stats(result).get("live"));
            for(var command:List.of(List.of(Path.of(System.getProperty("java.home"),"bin","java").toString(),"-Dfile.encoding=UTF-8","-jar",jar.toString()),List.of(ClrCompiler.executable(),clr.resolve("BhashProgram.dll").toString()))){
                var reference=consoleRun(command,ConsoleFixtures.utf8(input),false);assertEquals(reference.exit(),result.exit());assertEquals(reference.out(),result.out());
            }
        }
    }
    @Test void corePreservesInt32TypeIdentityBoxMetadataAndScalarCollectionKinds()throws Exception{
        var program=core("वर्ग पात्र { सार्वजनिक मान क: लघुपूर्णांक; } कार्य मुख्य() -> शून्य { स्थिर अ: लघुपूर्णांक[] = नया लघुपूर्णांक[२]; स्थिर ब: सर्ववस्तु = परिवर्तित<लघुपूर्णांक>(१); लिखो(प्रकारहै<लघुपूर्णांक>(ब),परिवर्तित<लघुपूर्णांक>(ब)); }");
        String ir=LlvmEmitter.emit(program);assertTrue(ir.contains("@box_info8"));assertTrue(ir.contains("@bh_to_int32"));assertTrue(ir.contains("internal ABI v15"));
        assertTrue(program.collections().stream().anyMatch(c->c.valueKind()==8));
    }
    @Test void nativeInt32FixturesMatchJvmClrAndForcedGc()throws Exception{
        requireNumericTargets();
        for(var fixture:NativeInt32Fixtures.cases()){
            var result=numericExact(fixture.source());assertEquals(0,result.exit(),fixture.name()+result.err());assertEquals(fixture.expected(),result.out(),fixture.name());
            if(fixture.name().equals("gc-stress")){assertTrue(stats(result).get("allocations")>6000,result.err());assertTrue(stats(result).get("peak")<350000,result.err());}
        }
    }
    @Test void nativeInt32KeepsManagedNarrowingAndSelectionDiagnostics(){
        for(String source:List.of(
            "कार्य मुख्य() -> शून्य { स्थिर क: लघुपूर्णांक = १; }",
            "कार्य मुख्य() -> शून्य { स्थिर क: लघुपूर्णांक = परिवर्तित<लघुपूर्णांक>(१); क=क+१; }",
            "कार्य मुख्य() -> शून्य { परिवर्तित<लघुपूर्णांक>(सत्य); }",
            "कार्य मुख्य() -> शून्य { सुरक्षितरूप<लघुपूर्णांक>(रिक्त); }",
            "कार्य मुख्य() -> शून्य { चयन (परिवर्तित<लघुपूर्णांक>(१)) { विकल्प २१४७४८३६४८: } }",
            "कार्य मुख्य() -> शून्य { स्थिर अ: सूची<लघुपूर्णांक> = नया सूची<लघुपूर्णांक>{१}; }")){
            var error=assertThrows(CompilationException.class,()->core(source));
            for(var target:List.of(Target.JVM,Target.CLR)){
                var reference=assertThrows(CompilationException.class,()->ManagedEmitter.emit(List.of(new SourceFile("native.bhash",source)),target));assertEquals(reference.getMessage(),error.getMessage());
            }
        }
    }
    private void requireTools()throws Exception{
        boolean required=Boolean.parseBoolean(System.getenv("BHASH_REQUIRE_NATIVE"));
        if(required)assertTrue(NativePlatform.supported(),"Native CI requires Linux/Windows x86-64");else assumeTrue(NativePlatform.supported());
        try{NativeToolchain.require(temporary);}
        catch(java.io.IOException | CompilationException error){if(required)throw error;assumeTrue(false,error.getMessage());}
    }
    private Path executable(String name){return temporary.resolve(name+NativePlatform.executableSuffix());}
    private record Result(int exit,String out,String err){}
    private Result nativeRun(String source,String...arguments)throws Exception{
        return nativeRun(source,Map.of(),arguments);
    }
    private Result nativeRun(String source,Map<String,String> environment,String...arguments)throws Exception{
        return nativeRun(List.of(new SourceFile("native.bhash",source)),environment,arguments);
    }
    private Result nativeRun(List<SourceFile> sources,Map<String,String> environment,String...arguments)throws Exception{
        var result=nativeOnlyRun(sources,environment,arguments);
        if(arguments.length==0){
            Path jar=temporary.resolve("reference.jar");
            new JvmCompiler().compile(sources,jar);
            Path referenceOut=temporary.resolve("reference.out"),referenceErr=temporary.resolve("reference.err");
            var reference=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin","java").toString(),"-Dfile.encoding=UTF-8","-jar",jar.toString())
                .redirectOutput(referenceOut.toFile()).redirectError(referenceErr.toFile()).start();
            assertTrue(reference.waitFor(15,TimeUnit.SECONDS));
            assertEquals(reference.exitValue(),result.exit(),"Native/JVM exit mismatch");
            assertEquals(Files.readString(referenceOut).replace("\r\n","\n"),result.out(),"Native/JVM output mismatch");
            if(result.exit()!=0)assertTrue(Files.readString(referenceErr).startsWith("भाष: "));
        }
        return result;
    }
    private Result nativeOnlyRun(List<SourceFile> sources,Map<String,String> environment,String...arguments)throws Exception{
        Path exe=executable("application");
        new NativeCompiler(!NativePlatform.windows()).compile(sources,exe);
        var command=new ArrayList<String>();command.add(exe.toString());command.addAll(List.of(arguments));
        Path out=temporary.resolve("out"),err=temporary.resolve("err");
        var builder=new ProcessBuilder(command).redirectOutput(out.toFile()).redirectError(err.toFile());
        builder.environment().putAll(environment);
        var p=builder.start();
        assertTrue(p.waitFor(15,TimeUnit.SECONDS));
        var result=new Result(p.exitValue(),Files.readString(out),Files.readString(err));
        return result;
    }
    private Result numericManagedRun(List<SourceFile> sources,boolean clr)throws Exception{
        Path application=temporary.resolve(clr?"numeric-clr":"numeric.jar");
        if(clr)new ClrCompiler().compile(sources,application);else new JvmCompiler().compile(sources,application);
        List<String> command=clr?List.of(ClrCompiler.executable(),application.resolve("BhashProgram.dll").toString())
            :List.of(Path.of(System.getProperty("java.home"),"bin","java").toString(),"-Dfile.encoding=UTF-8","-jar",application.toString());
        Path out=temporary.resolve("numeric-"+clr+".out"),err=temporary.resolve("numeric-"+clr+".err");
        var process=new ProcessBuilder(command).redirectOutput(out.toFile()).redirectError(err.toFile()).start();
        try{process.getOutputStream().close();assertTrue(process.waitFor(30,TimeUnit.SECONDS));
            return new Result(process.exitValue(),Files.readString(out).replace("\r\n","\n"),Files.readString(err));
        }finally{if(process.isAlive())process.destroyForcibly();}
    }
    private void requireNumericTargets()throws Exception{
        requireTools();boolean clr=ClrCompilerTest.dotnetAvailable();
        if("true".equalsIgnoreCase(System.getenv("BHASH_REQUIRE_NATIVE")))assertTrue(clr,"Missing .NET 10 SDK for cross-target validation");
        else assumeTrue(clr,"Missing .NET 10 SDK for cross-target validation");
    }
    private Result numericExact(String source)throws Exception{
        var sources=List.of(new SourceFile("native.bhash",source));
        var result=nativeOnlyRun(sources,GC_STRESS);
        for(boolean clr:new boolean[]{false,true}){
            var managed=numericManagedRun(sources,clr);assertEquals(managed.exit(),result.exit(),"Exit mismatch clr="+clr);
            assertEquals(managed.out(),result.out(),"Output mismatch clr="+clr);
        }
        assertEquals(0L,stats(result).get("live"));return result;
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
        var tools=NativeToolchain.require(artifact.toAbsolutePath().getParent());
        var verify=NativePlatform.windows()?List.of(tools.clang(),"--target="+NativeBuildInfo.TARGET,"-Xclang","-llvm-verify-each","-c",artifact.toString(),"-o",artifact.resolveSibling("factorial.o").toString()):List.of(tools.opt(),"-passes=verify","-disable-output",artifact.toString());
        var verifier=new ProcessBuilder(verify).inheritIO().start();
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
    @Test void coreSeparatesStaticStorageFromObjectLayoutsAndVirtualSlots()throws Exception{
        var p=core("वर्ग क { साझा मान न: पूर्णांक; साझा मान प: पाठ; साझा कार्य लो() -> पूर्णांक { लौटाओ न; } कार्य अलग() -> पूर्णांक { लौटाओ न; } } कार्य मुख्य() -> शून्य { क.न=२; लिखो(क.लो()); }");
        assertEquals(2,p.staticFields().size());assertEquals(0,p.classes().get(0).fields().size());assertEquals(1,p.classes().get(0).virtualMethods().size());
        String ir=LlvmEmitter.emit(p);assertTrue(ir.contains("@static_roots"));assertTrue(ir.contains("@bh_class_initialize"));assertTrue(ir.contains("@bh_static_reference_store"));
    }
    @Test void nativeStaticFixturesMatchJvmClrAndForcedGc()throws Exception{
        requireNumericTargets();
        for(var fixture:NativeStaticFixtures.cases()){
            var result=numericExact(fixture.source());assertEquals(0,result.exit(),fixture.name()+result.err());assertEquals(fixture.expected(),result.out(),fixture.name());
            if(fixture.name().equals("gc-stress")){assertTrue(stats(result).get("allocations")>10000,result.err());assertTrue(stats(result).get("peak")<100000,result.err());}
        }
    }
    @Test void nativeStaticMembersKeepManagedAccessAndInitializerDiagnostics(){
        for(String source:List.of(
            "वर्ग क { साझा मान न: पूर्णांक; } कार्य मुख्य() -> शून्य { नया क().न=२; }",
            "वर्ग क { निजी साझा मान न: पूर्णांक; } कार्य मुख्य() -> शून्य { लिखो(क.न); }",
            "वर्ग क { साझा स्थिर न: पूर्णांक=१; } कार्य मुख्य() -> शून्य { क.न=२; }",
            "वर्ग क { मान न: पूर्णांक; साझा कार्य लो() -> पूर्णांक { लौटाओ न; } } कार्य मुख्य() -> शून्य {}",
            "वर्ग क { साझा मान न: पूर्णांक=लो(); साझा कार्य लो() -> पूर्णांक { लौटाओ १; } } कार्य मुख्य() -> शून्य {}",
            "वर्ग क { साझा निर्माता() {} } कार्य मुख्य() -> शून्य {}",
            "वर्ग क { साझा कार्य लो() -> शून्य {} } वर्ग ख विस्तार क { साझा कार्य लो() -> शून्य {} } कार्य मुख्य() -> शून्य {}")){
            var error=assertThrows(CompilationException.class,()->core(source));
            for(var target:List.of(Target.JVM,Target.CLR))assertEquals(assertThrows(CompilationException.class,()->ManagedEmitter.emit(List.of(new SourceFile("native.bhash",source)),target)).getMessage(),error.getMessage());
        }
    }

    @Test void nativeEnumFixturesMatchJvmClrAndForcedGc()throws Exception{
        requireNumericTargets();for(var fixture:NativeEnumFixtures.cases()){
            var result=numericExact(fixture.source());assertEquals(0,result.exit(),fixture.name()+result.err());assertEquals(fixture.expected(),result.out(),fixture.name());
            if(fixture.name().equals("gc-stress")){assertTrue(stats(result).get("allocations")>6000,result.err());assertTrue(stats(result).get("peak")<100000,result.err());}
        }
    }
    @Test void coreEnumsUseNominalObjectsPersistentSingletonRootsAndTypedLabels()throws Exception{
        var p=core("परिगणना रंग { लाल, हरा=५ } कार्य मुख्य() -> शून्य { चयन (रंग.हरा) { विकल्प रंग.लाल: लिखो(१); शेष: लिखो(२); } }");
        assertEquals(1,p.classes().size());assertEquals(2,p.classes().get(0).fields().size());assertEquals(2,p.staticFields().size());
        assertEquals(List.of(CoreProgram.Type.TEXT,CoreProgram.Type.INT),p.classes().get(0).fields().stream().map(CoreProgram.ObjectField::type).toList());
        assertTrue(LlvmEmitter.emit(p).contains("@bh_static_reference_store"));
    }
    @Test void nativeEnumDiagnosticsMatchManagedTargets(){
        for(String source:List.of(
            "परिगणना क { अ=१, ब=१ }", "परिगणना क { अ, अ }", "परिगणना क { नाम }", "परिगणना क { अंक }",
            "परिगणना क { अ=९२२३३७२०३६८५४७७५८०७, ब }", "परिगणना क { अ=९२२३३७२०३६८५४७७५८०८ }", "परिगणना क { अ } वर्ग ख विस्तार क {}",
            "परिगणना क { अ } कार्य गलत() -> शून्य { नया क(); }",
            "परिगणना क { अ } कार्य गलत() -> शून्य { क.अ=क.अ; }",
            "परिगणना क { अ } कार्य गलत() -> शून्य { स्थिर र: क=क.अ; र.अंक=१; }",
            "परिगणना क { अ } परिगणना ख { अ } कार्य गलत() -> शून्य { चयन (क.अ) { विकल्प ख.अ: } }",
            "परिगणना क { अ } कार्य गलत() -> शून्य { चयन (क.अ) { विकल्प ०: } }",
            "परिगणना क { अ } कार्य गलत() -> शून्य { चयन (क.अ) { विकल्प क.अ: विकल्प क.अ: } }",
            "परिगणना क { अ } कार्य गलत() -> शून्य { परिवर्तित<क>(सत्य); }")){
            String program=source+" कार्य मुख्य() -> शून्य {}";var error=assertThrows(CompilationException.class,()->core(program));
            for(var target:List.of(Target.JVM,Target.CLR))assertEquals(assertThrows(CompilationException.class,()->ManagedEmitter.emit(List.of(new SourceFile("native.bhash",program)),target)).getMessage(),error.getMessage());
        }
    }

    @Test void nativeEnumsResolvePackageAliasesAndQualifiedCaseLabels()throws Exception{
        requireNumericTargets();var sources=List.of(
            new SourceFile("colors.bhash","संकुल रंगसंग्रह; सार्वजनिक परिगणना रंग { लाल, हरा=५ }"),
            new SourceFile("app.bhash","आयात रंगसंग्रह.रंग उपनाम र; कार्य मुख्य() -> शून्य { स्थिर क: र=परिवर्तित<र>(५); चयन (क) { विकल्प र.हरा: लिखो(क.नाम); शेष: लिखो(क.अंक); } }")
        );var result=nativeOnlyRun(sources,GC_STRESS);assertEquals(0,result.exit(),result.err());assertEquals("हरा\n",result.out());assertEquals(0L,stats(result).get("live"));
        for(boolean clr:new boolean[]{false,true}){var managed=numericManagedRun(sources,clr);assertEquals(result.exit(),managed.exit(),managed.err());assertEquals(result.out(),managed.out());}
    }
    @Test void nativeEnumLookupFailurePreservesUseSiteAndRunsFinally()throws Exception{
        requireNumericTargets();var result=numericExact("परिगणना रंग { लाल }\nकार्य मुख्य() -> शून्य { प्रयास { परिवर्तित<रंग>(१); } अंततः { लिखो(मानक.पाठ्य.दोहराओ(\"साफ😀\",१)); } }");
        assertEquals(1,result.exit());assertEquals("साफ😀\n",result.out());assertTrue(result.err().contains("native.bhash:2:"),result.err());assertTrue(result.err().contains("अमान्य गणना अंक।"),result.err());
    }

    @Test void debugOptionRequiresNativeTarget()throws Exception{
        var out=new java.io.ByteArrayOutputStream();var err=new java.io.ByteArrayOutputStream();
        int status=dev.bhash.cli.Main.run(new String[]{"compile","examples/native/debugging.bhash","--debug"},new java.io.PrintStream(out),new java.io.PrintStream(err));
        assertEquals(2,status);assertTrue(err.toString(java.nio.charset.StandardCharsets.UTF_8).contains("--debug currently requires --target native"));
    }
    @Test void nativeCorePreservesOriginalNamesAndEmitsOptionalDebugMetadata()throws Exception{
        String source="""
            कार्य पहचान<त>(अ: त) -> त { स्थिर उत्तर: त=अ; लौटाओ उत्तर; }
            कार्य मुख्य() -> शून्य { लिखो(पहचान<पूर्णांक>(४२)); }
            """;
        var program=core(source);assertTrue(program.functions().stream().anyMatch(f->f.name().equals("पहचान")));
        var function=program.functions().stream().filter(f->f.name().equals("पहचान")).findFirst().orElseThrow();assertEquals("अ",function.parameters().get(0).name());
        String normal=LlvmEmitter.emit(program),debug=LlvmEmitter.emit(program,"",true);
        assertTrue(normal.contains("@bh_trace_enter"));assertFalse(normal.contains("!DICompileUnit"));
        for(String metadata:List.of("!DICompileUnit","!DISubprogram","!DILocation","!DILocalVariable","@llvm.dbg.declare","noinline optnone","Debug Info Version"))assertTrue(debug.contains(metadata),metadata);
    }
    @Test void nativeRuntimeTraceUsesBhashNamesAcrossVirtualGenericAndFinallyCalls()throws Exception{
        requireTools();String source=Files.readString(Path.of("examples/native/diagnostics.bhash"));
        var result=nativeOnlyRun(List.of(new SourceFile("diagnostics.bhash",source)),GC_STRESS);
        assertEquals(1,result.exit());assertEquals("साफ😀\n",result.out());assertEquals(0L,stats(result).get("live"));
        assertTrue(result.err().contains("diagnostics.bhash:3:"),result.err());
        int previous=-1;for(String name:List.of("भाग","गणक.लो","परत","मुख्य")){int next=result.err().indexOf("  में "+name+" (");assertTrue(next>previous,result.err());previous=next;}
        for(String hidden:List.of("bfn","btry","विशेष_","संकुलित_"))assertFalse(result.err().contains(hidden),result.err());
    }
    @Test void nativeRethrowPreservesFirstStackAcrossCollection()throws Exception{
        requireTools();String source="""
            वर्ग भंडार { सार्वजनिक साझा मान ग: अपवाद; }
            कार्य पहला() -> शून्य { फेंको नया अपवाद("दोष😀"); }
            कार्य पकड़() -> शून्य { प्रयास { पहला(); } पकड़ो (ग: अपवाद) { भंडार.ग=ग; } }
            कार्य बाद() -> शून्य { मानक.पाठ्य.दोहराओ("दबाव",१००); फेंको भंडार.ग; }
            कार्य मुख्य() -> शून्य { पकड़(); बाद(); }
            """;
        var result=nativeOnlyRun(List.of(new SourceFile("rethrow.bhash",source)),GC_STRESS);
        assertEquals(1,result.exit());assertTrue(result.err().contains("  में पहला (rethrow.bhash:2:"),result.err());assertTrue(result.err().contains("  में पकड़ (rethrow.bhash:3:"),result.err());
        assertFalse(result.err().contains("  में बाद ("),result.err());assertEquals(0L,stats(result).get("live"));
    }
    @Test void nativeStaticInitializationTraceKeepsTheWriteUseSite()throws Exception{
        requireTools();String source="""
            वर्ग असफल { सार्वजनिक साझा मान न: पूर्णांक=१/०; }
            कार्य मुख्य() -> शून्य {
                लिखो(०);
                असफल.न=२;
            }
            """;
        var result=nativeOnlyRun(List.of(new SourceFile("static-trace.bhash",source)),GC_STRESS);
        assertEquals(1,result.exit());assertEquals("०\n",result.out());assertTrue(result.err().contains("  में असफल.<वर्ग आरंभ> (static-trace.bhash:1:"),result.err());assertTrue(result.err().contains("  में मुख्य (static-trace.bhash:4:"),result.err());assertEquals(0L,stats(result).get("live"));
    }
    @Test void nativeFinallyReplacementGetsItsOwnTrace()throws Exception{
        requireTools();String source="""
            कार्य पुराना() -> शून्य { फेंको नया अपवाद("पुराना"); }
            कार्य नया_दोष() -> शून्य { फेंको नया अपवाद("नया"); }
            कार्य मुख्य() -> शून्य { प्रयास { पुराना(); } अंततः { नया_दोष(); } }
            """;
        var result=nativeOnlyRun(List.of(new SourceFile("finally.bhash",source)),GC_STRESS);
        assertEquals(1,result.exit());assertTrue(result.err().contains("  में नया_दोष (finally.bhash:2:"),result.err());assertFalse(result.err().contains("  में पुराना ("),result.err());assertEquals(0L,stats(result).get("live"));
    }

    @Test void nativeGenericFixturesMatchJvmClrAndForcedGc()throws Exception{
        requireNumericTargets();for(var fixture:NativeGenericFixtures.cases()){
            var result=numericExact(fixture.source());assertEquals(0,result.exit(),fixture.name()+result.err());assertEquals(fixture.expected(),result.out(),fixture.name());
            if(fixture.name().equals("gc-stress")){assertTrue(stats(result).get("allocations")>4000,result.err());assertTrue(stats(result).get("peak")<100000,result.err());}
        }
    }
    @Test void coreSpecializesGenericLayoutsAndReusesClosedDefinitions()throws Exception{
        var p=core("""
            वर्ग पात्र<त> { सार्वजनिक मान व: त; सार्वजनिक साझा मान याद: त; }
            कार्य पहचान<त>(व: त) -> त { लौटाओ व; }
            कार्य मुख्य() -> शून्य { नया पात्र<पूर्णांक>(); नया पात्र<पाठ>(); नया पात्र<पूर्णांक>(); लिखो(पहचान<पूर्णांक>(१),पहचान<पूर्णांक>(२)); }
            """);
        assertEquals(2,p.classes().size());assertEquals(List.of(Type.INT,Type.TEXT),p.classes().stream().map(c->c.fields().get(0).type()).toList());
        assertEquals(2,p.staticFields().size());assertEquals(1,p.functions().stream().filter(f->f.result()==Type.INT).count());
        String ir=LlvmEmitter.emit(p);assertTrue(ir.contains("@bh_static_reference_store"));
    }
    @Test void nativeGenericDiagnosticsMatchManaged()throws Exception{
        for(String source:List.of(
            "वर्ग क<त> {} कार्य मुख्य() -> शून्य { नया क(); }",
            "अभिलेख क<त>(व: त); कार्य मुख्य() -> शून्य { नया क<पूर्णांक,पाठ>(१); }",
            "वर्ग क<त> {} कार्य मुख्य() -> शून्य { स्थिर अ: क<सर्ववस्तु> = नया क<पाठ>(); }",
            "कार्य जोड़<त>(व: त) -> त { लौटाओ व+१; } कार्य मुख्य() -> शून्य { जोड़<तार्किक>(सत्य); }",
            "कार्य पहचान<त>(व: त) -> त { लौटाओ व; } कार्य मुख्य() -> शून्य { पहचान<पूर्णांक>(सत्य); }",
            "वर्ग पात्र<त> { मान अगला: पात्र<पात्र<त>>; } कार्य मुख्य() -> शून्य { नया पात्र<पूर्णांक>(); }")){
            var error=assertThrows(CompilationException.class,()->core(source));
            for(var target:List.of(Target.JVM,Target.CLR))assertEquals(assertThrows(CompilationException.class,()->ManagedEmitter.emit(List.of(new SourceFile("native.bhash",source)),target)).getMessage(),error.getMessage());
        }
    }

    @Test void nativeGenericsResolvePackageAliasesAndFailureLocations()throws Exception{
        requireNumericTargets();var sources=List.of(
            new SourceFile("library.bhash","""
                संकुल संग्रह;
                सार्वजनिक अभिलेख पात्र<त>(व: त);
                सार्वजनिक कार्य पहचान<त>(व: त) -> त { लौटाओ व; }
                सार्वजनिक कार्य असफल<त>(व: त) -> त { प्रयास { स्थिर न: पूर्णांक=०; लिखो(१/न); लौटाओ व; } अंततः { लिखो("साफ😀"); } }
                """),
            new SourceFile("app.bhash","""
                आयात संग्रह.पात्र उपनाम प; आयात संग्रह.पहचान उपनाम लो;
                कार्य मुख्य() -> शून्य {
                    स्थिर क: प<पाठ> = लो<प<पाठ>>(नया प<पाठ>("भारत😀"));
                    स्थिर म: मानचित्र<प<पाठ>,पूर्णांक> = नया मानचित्र<प<पाठ>,पूर्णांक>{क:७}; लिखो(म[नया संग्रह.पात्र<पाठ>("भारत😀")]);
                    संग्रह.असफल<प<पाठ>>(क);
                }
                """));
        var result=nativeOnlyRun(sources,GC_STRESS);assertEquals(1,result.exit(),result.err());assertEquals("७\nसाफ😀\n",result.out());assertTrue(result.err().contains("library.bhash:4:"),result.err());assertEquals(0L,stats(result).get("live"));
        for(boolean clr:new boolean[]{false,true}){var managed=numericManagedRun(sources,clr);assertEquals(result.exit(),managed.exit(),managed.err());assertEquals(result.out(),managed.out());assertTrue(managed.err().contains("शून्य से भाग देना संभव नहीं है।"),managed.err());}
    }

    @Test void nativeRecordFixturesMatchJvmClrAndForcedGc()throws Exception{
        requireNumericTargets();for(var fixture:NativeRecordFixtures.cases()){
            var result=numericExact(fixture.source());assertEquals(0,result.exit(),fixture.name()+result.err());assertEquals(fixture.expected(),result.out(),fixture.name());
            if(fixture.name().equals("gc-stress")){assertTrue(stats(result).get("allocations")>10000,result.err());assertTrue(stats(result).get("peak")<100000,result.err());}
        }
    }
    @Test void coreRecordsHavePreciseLayoutsNominalMarkersAndNoVirtualSlots()throws Exception{
        var p=core("अभिलेख पात्र(नाम: पाठ, न: पूर्णांक, व: सर्ववस्तु); कार्य मुख्य() -> शून्य { स्थिर क: पात्र=नया पात्र(\"क\",१,रिक्त); लिखो(क==नया पात्र(\"क\",१,रिक्त)); }");
        var c=p.classes().get(0);assertEquals(List.of(CoreProgram.Type.TEXT,CoreProgram.Type.INT,CoreProgram.Type.ANY),c.fields().stream().map(CoreProgram.ObjectField::type).toList());
        assertTrue(c.assignableTypes().contains(-9));assertTrue(c.virtualMethods().isEmpty());assertTrue(p.initializations().isEmpty());
        String ir=LlvmEmitter.emit(p);assertTrue(ir.contains("%RecordInfo"));assertTrue(ir.contains("@bh_value_same"));assertTrue(ir.contains("internal ABI v15"));
    }
    @Test void nativeRecordDiagnosticsMatchManaged(){
        for(String declaration:List.of(
            "अभिलेख क(अ: पूर्णांक, अ: पूर्णांक);", "अभिलेख क(); वर्ग ख विस्तार क {}",
            "अभिलेख क(अ: पूर्णांक); कार्य गलत() -> शून्य { नया क(); }",
            "अभिलेख क(अ: पूर्णांक); कार्य गलत() -> शून्य { नया क(\"अ\"); }",
            "अभिलेख क(अ: पूर्णांक); कार्य गलत() -> शून्य { स्थिर व: क=नया क(१); व.अ=२; }")){
            String source=declaration+" कार्य मुख्य() -> शून्य {}";var error=assertThrows(CompilationException.class,()->core(source));
            for(var target:List.of(Target.JVM,Target.CLR))assertEquals(assertThrows(CompilationException.class,()->ManagedEmitter.emit(List.of(new SourceFile("native.bhash",source)),target)).getMessage(),error.getMessage());
        }
        assertDoesNotThrow(()->core("अभिलेख पात्र<त>(व: त); कार्य मुख्य() -> शून्य {}"));
    }

    @Test void nativeRecordsResolveAliasesAndPreserveFailureLocations()throws Exception{
        requireNumericTargets();var sources=List.of(new SourceFile("point.bhash","संकुल आकृति; सार्वजनिक अभिलेख बिंदु(नाम: पाठ, न: पूर्णांक);"),
            new SourceFile("app.bhash","आयात आकृति.बिंदु उपनाम ब; कार्य मुख्य() -> शून्य { स्थिर क: ब=नया ब(\"क\",१); स्थिर म: मानचित्र<ब,पूर्णांक> = नया मानचित्र<ब,पूर्णांक>{क:७}; लिखो(म[नया ब(\"क\",१)]); }"));
        var result=nativeOnlyRun(sources,GC_STRESS);assertEquals(0,result.exit(),result.err());assertEquals("७\n",result.out());assertEquals(0L,stats(result).get("live"));
        for(boolean clr:new boolean[]{false,true}){var managed=numericManagedRun(sources,clr);assertEquals(result.exit(),managed.exit(),managed.err());assertEquals(result.out(),managed.out());}
        result=numericExact("अभिलेख बिंदु(न: पूर्णांक);\nकार्य मुख्य() -> शून्य { स्थिर क: बिंदु=रिक्त; प्रयास { लिखो(क.न); } अंततः { लिखो(मानक.पाठ्य.दोहराओ(\"साफ😀\",१)); } }");
        assertEquals(1,result.exit());assertEquals("साफ😀\n",result.out());assertTrue(result.err().contains("native.bhash:2:"),result.err());
    }
    @Test void nativeRecordsCompareAndHashDeepSharedGraphsWithoutHostRecursion()throws Exception{
        requireTools();var result=nativeOnlyRun(List.of(new SourceFile("native.bhash","""
            अभिलेख नोड(अ: नोड, ब: नोड, न: पूर्णांक);
            कार्य मुख्य() -> शून्य {
                मान क: नोड=रिक्त; मान ख: नोड=रिक्त; मान न: पूर्णांक=०;
                जबतक (न<१०००) { क=नया नोड(क,रिक्त,न); ख=नया नोड(ख,रिक्त,न); न=न+१; }
                स्थिर म: मानचित्र<नोड,पूर्णांक> = नया मानचित्र<नोड,पूर्णांक>{क:७}; लिखो(क==ख,म[ख]);
                क=रिक्त; ख=रिक्त; न=०;
                जबतक (न<८०) { क=नया नोड(क,क,न); ख=नया नोड(ख,ख,न); न=न+१; }
                म[क]=८; लिखो(क==ख,म[ख]);
            }
            """)),GC_STRESS);assertEquals(0,result.exit(),result.err());assertEquals("सत्य ७\nसत्य ८\n",result.out());assertEquals(0L,stats(result).get("live"));
    }

}
