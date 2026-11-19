// SPDX-License-Identifier: Apache-2.0
package dev.bhash;
import dev.bhash.backend.*;
import dev.bhash.frontend.*;
import dev.bhash.backend.jvm.JvmCompiler;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
class TaggedUnionTest {
    @TempDir Path temporary;
    private static String processOutput(Process process)throws Exception {
        var output=java.util.concurrent.CompletableFuture.supplyAsync(()->{
            try{return new String(process.getInputStream().readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);}
            catch(java.io.IOException e){throw new java.io.UncheckedIOException(e);}
        });
        boolean exited=process.waitFor(60,java.util.concurrent.TimeUnit.SECONDS);
        if(!exited){process.descendants().forEach(ProcessHandle::destroyForcibly);process.destroyForcibly();process.waitFor(10,java.util.concurrent.TimeUnit.SECONDS);}
        String text=output.get(10,java.util.concurrent.TimeUnit.SECONDS);assertTrue(exited,"Fixture exceeded 60 seconds: "+text);return text;
    }
    static final String PROGRAM="""
        विकल्पसमूह खोज<त> { मिला(मूल्य:त); अनुपस्थित(); }
        अभिलेख छात्र(नाम:पाठ, अंक:पूर्णांक);
        फलन बताओ(व:खोज<छात्र>)->पाठ {
            लौटाओ मिलान(व){विकल्प खोज.मिला(छात्र(स्थिर नाम, स्थिर अंक)) यदि(अंक>०)=>नाम,
                विकल्प खोज.मिला(_)=>"शून्य", विकल्प खोज.अनुपस्थित()=>"नहीं"};
        }
        फलन मुख्य()->शून्य {
            स्थिर अ=खोज<छात्र>.मिला(नया छात्र("आशा",५));
            लिखो(बताओ(अ),बताओ(खोज<छात्र>.अनुपस्थित()));
            लिखो(अ==खोज<छात्र>.मिला(नया छात्र("आशा",५)));
            स्थिर उत्तर=मानक.परिणाम<पूर्णांक,पाठ>.सफल(७);
            मिलान(उत्तर){विकल्प मानक.परिणाम.सफल(स्थिर संख्या):{लिखो(संख्या);}
                विकल्प मानक.परिणाम.असफल(स्थिर दोष):{लिखो(दोष);}}
        }
        """;
    static List<SourceFile> sources(String text){return List.of(new SourceFile("unions.bhash",text,true));}
    static String check(String text)throws Exception{return ManagedEmitter.emit(sources(text),Target.JVM);}
    @Test void closedFamiliesAndNestedPatterns()throws Exception {check(PROGRAM);}
    @Test void strictSubtypePatternsDoNotCompleteOpenClassDomains()throws Exception {
        var prefix="वर्ग आधारवर्ग {} वर्ग उपवर्ग विस्तार आधारवर्ग {} फलन मुख्य()->शून्य {स्थिर व:आधारवर्ग=नया आधारवर्ग(); स्थिर र=मिलान(व){विकल्प स्थिर क:उपवर्ग=>१";
        var e=assertThrows(CompilationException.class,()->check(prefix+"};}"));assertTrue(e.getMessage().contains("BH3312"),e.getMessage());
        check(prefix+",शेष=>२};}");
    }
    @Test void recursiveWildcardColumnsDoNotConsumeTheCoverageBudget()throws Exception {
        String prefix="विकल्पसमूह पेड़ {शाखा(अगली:पेड़,ध्वज:तार्किक); अंत();} फलन मुख्य()->शून्य {स्थिर व=पेड़.अंत();स्थिर र=मिलान(व){विकल्प पेड़.शाखा(_,सत्य)=>१,";
        check(prefix+"विकल्प पेड़.शाखा(_,असत्य)=>२, विकल्प पेड़.अंत()=>३};}");
        var e=assertThrows(CompilationException.class,()->check(prefix+"विकल्प पेड़.अंत()=>३};}"));assertTrue(e.getMessage().contains("BH3312"),e.getMessage());
    }
    @Test void assignmentAndApplicableOverloadSupplyMatchTypes()throws Exception {
        check("फलन लो(अ:सर्ववस्तु,ब:तार्किक)->शून्य{} फलन लो(अ:पूर्णांक,ब:पाठ)->शून्य{} फलन मुख्य()->शून्य{मान र:सर्ववस्तु=०;र=मिलान(सत्य){विकल्प सत्य=>१,विकल्प असत्य=>\"अ\"}; लो(मिलान(सत्य){विकल्प सत्य=>१,विकल्प असत्य=>\"अ\"},सत्य);}");
    }
    @Test void integralPatternBoundsFollowTheSelector()throws Exception {
        check("फलन मुख्य()->शून्य {स्थिर ब:बाइट=परिवर्तित<बाइट>(०); स्थिर र=मिलान(ब){विकल्प ०=>१,विकल्प २५५=>२,शेष=>३};}");
        for(String type:List.of("बाइट","लघुपूर्णांक")){String number=type.equals("बाइट")?"-१":"२१४७४८३६४८";var e=assertThrows(CompilationException.class,()->check("फलन मुख्य()->शून्य {स्थिर ब:"+type+"=परिवर्तित<"+type+">(०); स्थिर र=मिलान(ब){विकल्प "+number+"=>१,शेष=>२};}"));assertTrue(e.getMessage().contains("BH3310"),e.getMessage());}
    }
    @Test void caseNamesMayCoincideWithFamilyAndTypeParameterNames()throws Exception {
        check("विकल्पसमूह व<त>{व();त(मूल्य:त);} फलन मुख्य()->शून्य {व<पूर्णांक>.व();व<पूर्णांक>.त(१);}");
    }
    @Test void caseLayoutDoesNotConsumeThePublicSpecializationLimit()throws Exception {
        var source=new StringBuilder("वर्ग पात्र<त> {} विकल्पसमूह व<त>{अ(मूल्य:त);} ");
        for(int i=0;i<256;i++)source.append("वर्ग प्रकार").append(i).append(" {} ");
        source.append("फलन मुख्य()->शून्य {");for(int i=0;i<255;i++)source.append("नया पात्र<प्रकार").append(i).append(">();");
        source.append("व<पूर्णांक>.अ(१);");check(source+"}");
        var e=assertThrows(CompilationException.class,()->check(source+"नया पात्र<प्रकार255>();}"));assertTrue(e.getMessage().contains("specialization limit"),e.getMessage());
    }
    @Test void genericPatternOwnerStopsBeforeTheCaseDespitePackageCollisions()throws Exception {
        var app=new SourceFile("app.bhash","संकुल उपयोग; सार्वजनिक वर्ग प {} सार्वजनिक विकल्पसमूह व<त>{अ(मूल्य:त);} फलन मुख्य()->शून्य {स्थिर क=व<प>.अ(नया प());स्थिर र=मिलान(क){विकल्प व<प>.अ(_)=>१};}",true);
        var unrelated=new SourceFile("unrelated.bhash","संकुल व; वर्ग अ {}",true);
        ManagedEmitter.emit(List.of(app,unrelated),Target.JVM);
    }
    @Test void jvmExecution()throws Exception {
        Path jar=temporary.resolve("unions.jar");new JvmCompiler().compile(sources(PROGRAM),jar);
        var p=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin","java").toString(),"-jar",jar.toString()).redirectErrorStream(true).start();
        String output=processOutput(p);assertEquals(0,p.waitFor(),output);assertEquals("आशा नहीं\nसत्य\n७\n",output.replace("\r\n","\n"));
    }
    @Test void missingCaseIsRejected(){var e=assertThrows(CompilationException.class,()->check("विकल्पसमूह व { अ(); ब(); } फलन मुख्य()->शून्य {स्थिर क=व.अ(); स्थिर र=मिलान(क){विकल्प व.अ()=>१};}"));assertTrue(e.getMessage().contains("BH3312"),e.getMessage());assertTrue(e.getMessage().contains(".ब()"),e.getMessage());}
    @Test void guardedArmsDoNotCover(){var e=assertThrows(CompilationException.class,()->check("फलन मुख्य()->शून्य {स्थिर व=मिलान(सत्य){विकल्प _ यदि(सत्य)=>१};}"));assertTrue(e.getMessage().contains("BH3312"),e.getMessage());}
    @Test void duplicateArmRejected(){var e=assertThrows(CompilationException.class,()->check("फलन मुख्य()->शून्य {स्थिर व=मिलान(सत्य){विकल्प सत्य=>१,विकल्प सत्य=>२,विकल्प असत्य=>३};}"));assertTrue(e.getMessage().contains("BH3311"),e.getMessage());}
    static final String ASYNC="""
        विकल्पसमूह उत्तर { अंक(मूल्य:पूर्णांक); नहीं(); }
        असमकालिक फलन जाँच(व:पूर्णांक)->तार्किक {प्रतीक्षा मानक.कार्य.विलंब(१); लौटाओ व>०;}
        असमकालिक फलन दोगुना(व:पूर्णांक)->पूर्णांक {प्रतीक्षा मानक.कार्य.विलंब(१); लौटाओ व*२;}
        असमकालिक फलन मुख्य()->शून्य {
            स्थिर अ=उत्तर.अंक(७);
            स्थिर ब=मिलान(अ){विकल्प उत्तर.अंक(स्थिर व) यदि(प्रतीक्षा जाँच(व))=>प्रतीक्षा दोगुना(व),
                विकल्प उत्तर.अंक(_)=>०, विकल्प उत्तर.नहीं()=>१};
            लिखो(ब);
        }
        """;
    static final String EDGES="""
        विकल्पसमूह पेड़ {शाखा(अगली:पेड़,ध्वज:तार्किक);अंत();}
        वर्ग आधारवर्ग {} वर्ग उपवर्ग विस्तार आधारवर्ग {}
        फलन लो(अ:सर्ववस्तु,ब:तार्किक)->पाठ{लौटाओ "ठीक";}
        फलन लो(अ:पूर्णांक,ब:पाठ)->पाठ{लौटाओ "गलत";}
        फलन मुख्य()->शून्य {
            मान र:सर्ववस्तु=०;र=मिलान(सत्य){विकल्प सत्य=>१,विकल्प असत्य=>"अ"};लिखो(परिवर्तित<पूर्णांक>(र));
            लिखो(लो(मिलान(सत्य){विकल्प सत्य=>१,विकल्प असत्य=>"अ"},सत्य));
            स्थिर ब:आधारवर्ग=नया आधारवर्ग();लिखो(मिलान(ब){विकल्प स्थिर स:उपवर्ग=>१,शेष=>२});
            स्थिर प=पेड़.शाखा(पेड़.अंत(),असत्य);
            लिखो(मिलान(प){विकल्प पेड़.शाखा(_,सत्य)=>१,विकल्प पेड़.शाखा(_,असत्य)=>२,विकल्प पेड़.अंत()=>३});
            स्थिर छोटा:बाइट=परिवर्तित<बाइट>(२५५);लिखो(मिलान(छोटा){विकल्प ०=>०,विकल्प २५५=>२५५,शेष=>१});
        }
        """;
    static final String EDGES_OUTPUT="१\nठीक\n२\n२\n२५५\n";
    @Test void reviewedEdgeCasesExecuteOnJvm()throws Exception {assertEquals(EDGES_OUTPUT,runJvm(EDGES));}
    String runJvm(String source)throws Exception {
        Path jar=temporary.resolve("run.jar");new JvmCompiler().compile(sources(source),jar);
        var p=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin","java").toString(),"-jar",jar.toString()).redirectErrorStream(true).start();
        String output=processOutput(p);assertEquals(0,p.waitFor(),output);return output.replace("\r\n","\n");
    }
    @Test void awaitingGuardAndArmStayInTheCallerTask()throws Exception {assertEquals("१४\n",runJvm(ASYNC));}
    @Test void nullableAndFiniteNestedCoverage()throws Exception {
        check("विकल्पसमूह व { अ(स:तार्किक); ब(); } फलन मुख्य()->शून्य {स्थिर क:व?=रिक्त; स्थिर र=मिलान(क){विकल्प रिक्त=>०,विकल्प व.अ(सत्य)=>१,विकल्प व.अ(असत्य)=>२,विकल्प व.ब()=>३};}");
    }
    @Test void legacyReferencesNeedANullArm() {var source="विकल्पसमूह व { अ(); } फलन मुख्य()->शून्य {स्थिर क=व.अ(); स्थिर र=मिलान(क){विकल्प व.अ()=>१};}";var e=assertThrows(CompilationException.class,()->ManagedEmitter.emit(List.of(new SourceFile("legacy.bhash",source)),Target.JVM));assertTrue(e.getMessage().contains("BH3312"),e.getMessage());}
    @Test void explicitUniversalResultAllowsHeterogeneousArms()throws Exception {check("फलन मुख्य()->शून्य {स्थिर र:सर्ववस्तु=मिलान(सत्य){विकल्प सत्य=>१,विकल्प असत्य=>\"अ\"};}");}
    @Test void unitResult()throws Exception {check("फलन मुख्य()->शून्य {स्थिर र=मानक.परिणाम<मानक.इकाई,पाठ>.सफल(नया मानक.इकाई()); मिलान(र){विकल्प मानक.परिणाम.सफल(मानक.इकाई()):{} विकल्प मानक.परिणाम.असफल(स्थिर दोष):{लिखो(दोष);}}}");}
    @Test void clrExecution()throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(ClrCompilerTest.dotnetAvailable());
        for(var source:List.of(PROGRAM,ASYNC,DEEP,CLEANUP,CANCELLATION,FLOW,EDGES)) {
            Path directory=temporary.resolve("clr"+Math.abs(source.hashCode()));new dev.bhash.backend.clr.ClrCompiler().compile(sources(source),directory);
            var p=new ProcessBuilder(dev.bhash.backend.clr.ClrCompiler.executable(),directory.resolve("BhashProgram.dll").toString()).redirectErrorStream(true).start();
            String output=processOutput(p);assertEquals(0,p.waitFor(),output);
            assertEquals(source.equals(EDGES)?EDGES_OUTPUT:source.equals(PROGRAM)?"आशा नहीं\nसत्य\n७\n":source.equals(ASYNC)?"१४\n":source.equals(DEEP)?"सत्य साझा\nसत्य ९\n":source.equals(CLEANUP)?"भारत😀\nसाफ\nजीवित\nसाफ\nखाली\n२\nतीन\n३\n":source.equals(FLOW)?"७ ०\n":"बंद\nनिरस्त\nअपेक्षित\n",output.replace("\r\n","\n"));
        }
    }
    static final String DEEP="""
        विकल्पसमूह पेड़ { पत्ता(मूल्य:पूर्णांक); शाखा(बायाँ:पेड़,दायाँ:पेड़); }
        विकल्पसमूह कड़ी { अंत(); जोड़(पाठमान:पाठ,अगली:कड़ी); }
        फलन मुख्य()->शून्य {
            मान अ=पेड़.पत्ता(७); मान ब=पेड़.पत्ता(७); मान ग=०;
            जबतक(ग<४०){अ=पेड़.शाखा(अ,अ);ब=पेड़.शाखा(ब,ब);ग=ग+१;}
            स्थिर म=नया मानचित्र<पेड़,पाठ>{अ:"साझा"}; लिखो(अ==ब,म[ब]);
            मान क=कड़ी.अंत();मान ख=कड़ी.अंत();ग=०;
            जबतक(ग<३००){क=कड़ी.जोड़(मानक.पाठ्य.दोहराओ("क😀",२),क);ख=कड़ी.जोड़("क😀क😀",ख);ग=ग+१;}
            स्थिर न=नया मानचित्र<कड़ी,पूर्णांक>{क:९};लिखो(क==ख,न[ख]);
        }
        """;
    static final String CLEANUP="""
        विकल्पसमूह उत्तर { मिला(मूल्य:पाठ); नहीं(); }
        वर्ग साधन {सार्वजनिक फलन बंदकरो()->शून्य {लिखो("साफ");}}
        फलन लो(व:उत्तर)->पाठ {
            संसाधन(स:साधन=नया साधन()){
                मिलान(व){विकल्प उत्तर.मिला(स्थिर पाठमान):{लौटाओ पाठमान;} विकल्प उत्तर.नहीं():{फेंको नया अपवाद("खाली");}}
            }
        }
        फलन मुख्य()->शून्य {
            मान बाद:फलन()->पाठ=फलन()->पाठ {लौटाओ "पहले";};
            मिलान(उत्तर.मिला("भारत😀")){विकल्प उत्तर.मिला(स्थिर पाठमान):{बाद=फलन()->पाठ {लौटाओ पाठमान;};} विकल्प उत्तर.नहीं():{}}
            मानक.पाठ्य.दोहराओ("नया",२०);लिखो(बाद());लिखो(लो(उत्तर.मिला("जीवित")));
            प्रयास{लो(उत्तर.नहीं());}पकड़ो(द:अपवाद){लिखो(द.संदेश);}
            मान ग=०;जबतक(ग<३){ग=ग+१;मिलान(ग){विकल्प १:{जारी;} विकल्प २:{विराम;} शेष:{लिखो("तीन");}}लिखो(ग);}
        }
        """;
    @Test void sharedGraphsAndDeepValuesUseBoundedHostStack()throws Exception {assertEquals("सत्य साझा\nसत्य ९\n",runJvm(DEEP));}
    @Test void escapingCapturesReturnsCleanupAndLoopControl()throws Exception {assertEquals("भारत😀\nसाफ\nजीवित\nसाफ\nखाली\n२\nतीन\n३\n",runJvm(CLEANUP));}
    @Test void tutorialsCompileForBothManagedTargets()throws Exception {
        for(var name:List.of("results","async-result","unit")){var source=Files.readString(Path.of("tutorial/tagged-unions/"+name+".bhash"));assertEquals(name.equals("unit")?"पूरा\n":Files.readString(Path.of("tutorial/tagged-unions/"+name+".expected.txt")).replace("\r\n","\n"),runJvm(source));ManagedEmitter.emit(sources(source),Target.CLR);
            if(ClrCompilerTest.dotnetAvailable()){var directory=temporary.resolve("tutorial-"+name);new dev.bhash.backend.clr.ClrCompiler().compile(sources(source),directory);var process=new ProcessBuilder(dev.bhash.backend.clr.ClrCompiler.executable(),directory.resolve("BhashProgram.dll").toString()).redirectErrorStream(true).start();String output=processOutput(process);assertEquals(0,process.waitFor(),output);assertEquals(Files.readString(Path.of("tutorial/tagged-unions/"+name+".expected.txt")).replace("\r\n","\n"),output.replace("\r\n","\n"));}
        }
    }
    @Test void typedUniversalPatternsCoverTheirLiterals(){var e=assertThrows(CompilationException.class,()->check("फलन मुख्य()->शून्य {स्थिर व:सर्ववस्तु=१; स्थिर र=मिलान(व){विकल्प स्थिर अ:पूर्णांक=>१, विकल्प २=>२,शेष=>३};}".replace("शेष","शेष")));assertTrue(e.getMessage().contains("BH3311"),e.getMessage());}
    @Test void unreachableEscapedTextAndSignedZeroAreRejected(){for(var source:List.of("फलन मुख्य()->शून्य {स्थिर र=मिलान(\"अ\"){विकल्प \"\\u0905\"=>१, विकल्प \"अ\"=>२,शेष=>३};}","फलन मुख्य()->शून्य {स्थिर र=मिलान(०){विकल्प -०=>१,विकल्प +0=>२,शेष=>३};}")){var e=assertThrows(CompilationException.class,()->check(source));assertTrue(e.getMessage().contains("BH3311"),e.getMessage());}}
    @Test void publicPayloadAndVoidResultsAreRejected(){for(var source:List.of("वर्ग निजीप्रकार {} सार्वजनिक विकल्पसमूह व {अ(मूल्य:निजीप्रकार);} फलन मुख्य()->शून्य {}","फलन मुख्य()->शून्य {मानक.परिणाम<शून्य,पाठ>.असफल(\"दोष\");}")){assertThrows(CompilationException.class,()->check(source));}}

    static final String FLOW="""
        विकल्पसमूह व{अ(मूल्य:पूर्णांक);}
        फलन झूठ()->तार्किक {मानक.पाठ्य.दोहराओ("जीसी😀",२०);लौटाओ असत्य;}
        फलन लो(क:व?)->पूर्णांक {यदि(क==रिक्त){लौटाओ ०;} लौटाओ मिलान(क){विकल्प व.अ(_) यदि(झूठ())=>९९,विकल्प व.अ(स्थिर मूल्य)=>मूल्य};}
        असमकालिक फलन मुख्य()->शून्य {प्रतीक्षा मानक.कार्य.विलंब(१);लिखो(लो(व.अ(७)),लो(रिक्त));}
        """;
    @Test void nonNullProofsSurviveMixedAsyncAndSynchronousLowering()throws Exception {assertEquals("७ ०\n",runJvm(FLOW));}
    static final String CANCELLATION="""
        आयात मानक.निरस्तअपवाद;
        विकल्पसमूह व { मिला(मूल्य:पाठ); नहीं(); }
        वर्ग साधन {सार्वजनिक असमकालिक फलन बंदकरो()->शून्य {प्रतीक्षा मानक.कार्य.विलंब(१); लिखो("बंद");}}
        असमकालिक फलन रोक(चिह्न:निरस्तीकरणचिह्न)->तार्किक {प्रतीक्षा मानक.कार्य.विलंब(१०००००,चिह्न); लौटाओ सत्य;}
        असमकालिक फलन काम(चिह्न:निरस्तीकरणचिह्न)->पाठ {
            असमकालिक संसाधन(स:साधन=नया साधन()) {
                स्थिर उत्तर=व.मिला(मानक.पाठ्य.दोहराओ("भारत😀",२));
                लौटाओ मिलान(उत्तर){विकल्प व.मिला(स्थिर पाठमान) यदि(प्रतीक्षा रोक(चिह्न))=>पाठमान,विकल्प व.मिला(_)=>"नहीं",विकल्प व.नहीं()=>"खाली"};
            }
        }
        असमकालिक फलन मुख्य()->शून्य {
            स्थिर स्रोत=नया निरस्तीकरणस्रोत();स्थिर क=काम(स्रोत.चिह्नलो());स्रोत.निरस्तकरो();
            प्रयास{लिखो(प्रतीक्षा क);}पकड़ो(द:निरस्तअपवाद){लिखो("निरस्त");} स्रोत.बंदकरो();
            स्थिर परिणाम=मानक.परिणाम<पूर्णांक,पाठ>.असफल("अपेक्षित");
            स्थिर सफलकार्य=कार्यफल<मानक.परिणाम<पूर्णांक,पाठ>>.पूरा(परिणाम);
            स्थिर मिलामान=प्रतीक्षा सफलकार्य;
            मिलान(मिलामान){विकल्प मानक.परिणाम.सफल(स्थिर अंक):{लिखो(अंक);} विकल्प मानक.परिणाम.असफल(स्थिर दोष):{लिखो(दोष);}}
        }
        """;
    @Test void cancellationUnwindsAwaitedGuardsAndDomainFailureRemainsData()throws Exception {assertEquals("बंद\nनिरस्त\nअपेक्षित\n",runJvm(CANCELLATION));}

    @Test void contextualWordsRemainOrdinaryIdentifiers()throws Exception {assertEquals("३\n",runJvm("फलन मिलान(अ:पूर्णांक)->पूर्णांक {लौटाओ अ;} फलन विकल्पसमूह()->पूर्णांक {लौटाओ २;} फलन मुख्य()->शून्य {लिखो(मिलान(१)+विकल्पसमूह());}"));}
    @Test void closedOwnersArityAndImmutableBindingRulesAreChecked(){for(var source:List.of(
        "विकल्पसमूह व<त>{अ(क:त);} फलन मुख्य()->शून्य {स्थिर क=व<पूर्णांक>.अ(१); स्थिर र=मिलान(क){विकल्प व<पाठ>.अ(_)=>०};}",
        "विकल्पसमूह व{अ(क:पूर्णांक);} फलन मुख्य()->शून्य {स्थिर र=मिलान(व.अ(१)){विकल्प व.अ()=>०};}",
        "विकल्पसमूह व{अ(क:पूर्णांक,ख:पूर्णांक);} फलन मुख्य()->शून्य {स्थिर र=मिलान(व.अ(१,२)){विकल्प व.अ(स्थिर क,स्थिर क)=>०};}",
        "विकल्पसमूह व{अ(क:पूर्णांक);} फलन मुख्य()->शून्य {मिलान(व.अ(१)){विकल्प व.अ(स्थिर क):{क=२;}}}",
        "विकल्पसमूह व{अ();अ();} फलन मुख्य()->शून्य {}",
        "विकल्पसमूह व{अ();} वर्ग ख विस्तार व{} फलन मुख्य()->शून्य {}",
        "विकल्पसमूह व{अ();} फलन मुख्य()->शून्य {नया व();}"
    ))assertThrows(CompilationException.class,()->check(source),source);}
    @Test void booleanProductsNeedEveryCombination(){var prefix="विकल्पसमूह व{युगल(अ:तार्किक,ब:तार्किक);} फलन मुख्य()->शून्य {स्थिर र=मिलान(व.युगल(सत्य,असत्य)){";var arms="विकल्प व.युगल(सत्य,सत्य)=>१, विकल्प व.युगल(सत्य,असत्य)=>२, विकल्प व.युगल(असत्य,सत्य)=>३";var e=assertThrows(CompilationException.class,()->check(prefix+arms+"};}"));assertTrue(e.getMessage().contains("BH3312"),e.getMessage());assertDoesNotThrow(()->check(prefix+arms+",विकल्प व.युगल(असत्य,असत्य)=>४};}"));}

    @Test void parametersSupplyExpectedMatchTypes()throws Exception {
        check("फलन लो(व:सर्ववस्तु)->शून्य {} फलन मुख्य()->शून्य {लो(मिलान(सत्य){विकल्प सत्य=>१,विकल्प असत्य=>\"अ\"});}");
        check("वर्ग क {सार्वजनिक साझा फलन लो(व:सर्ववस्तु)->शून्य{}} फलन मुख्य()->शून्य {क.लो(मिलान(सत्य){विकल्प सत्य=>१,विकल्प असत्य=>\"अ\"});}");
        check("अभिलेख क(व:सर्ववस्तु); फलन मुख्य()->शून्य {नया क(मिलान(सत्य){विकल्प सत्य=>१,विकल्प असत्य=>\"अ\"});}");
    }

    @Test void existingNonNullProofsDefineTheMatchDomain()throws Exception {
        check("विकल्पसमूह व{अ();} फलन लो(क:व?)->पूर्णांक {यदि(क==रिक्त){लौटाओ ०;} लौटाओ मिलान(क){विकल्प व.अ()=>१};} फलन मुख्य()->शून्य {}");
        check("विकल्पसमूह व{अ();} फलन लो(क:व?)->पूर्णांक {यदि(क!=रिक्त){लौटाओ मिलान(क){विकल्प व.अ()=>१};} लौटाओ ०;} फलन मुख्य()->शून्य {}");
        var e=assertThrows(CompilationException.class,()->check("विकल्पसमूह व{अ();} फलन लो(क:व?)->पूर्णांक {लौटाओ मिलान(क){विकल्प व.अ()=>१};} फलन मुख्य()->शून्य {}"));assertTrue(e.getMessage().contains("BH3312"),e.getMessage());
    }

    @Test void enclosingOperatorsDoNotSupplyAnIncorrectMatchTargetType()throws Exception {
        check("फलन मुख्य()->शून्य {स्थिर स:तार्किक=मिलान(सत्य){विकल्प सत्य=>१,विकल्प असत्य=>२}>०; लिखो(स);}");
    }

    @Test void baseTypeBindingCoversEveryNonNullDerivedSelector()throws Exception {
        check("वर्ग आधारवर्ग {} वर्ग उपवर्ग विस्तार आधारवर्ग {} फलन लो(व:उपवर्ग)->पूर्णांक {लौटाओ मिलान(व){विकल्प स्थिर मूल:आधारवर्ग=>१};} फलन मुख्य()->शून्य {}");
    }

    @Test void nestedMatchesKeepTheirOwnBudgetAndInheritAnExplicitResultType()throws Exception {
        check("फलन मुख्य()->शून्य {स्थिर व:सर्ववस्तु=मिलान(सत्य){विकल्प सत्य=>मिलान(असत्य){विकल्प सत्य=>१,विकल्प असत्य=>\"अ\"},विकल्प असत्य=>२};}");
    }

}
