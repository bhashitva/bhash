// SPDX-License-Identifier: Apache-2.0
package dev.bhash;

import dev.bhash.backend.*;
import dev.bhash.backend.jvm.JvmCompiler;
import dev.bhash.frontend.SourceFile;
import java.nio.file.*;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class AsyncDatabaseTest {
    @TempDir Path directory;
    static final String BASIC="""
        आयात मानक.असमडेटा;आयात मानक.असमडेटासंबंध;आयात मानक.असमडेटाकथन;आयात मानक.असमडेटालेनदेन;
        आयात मानक.परीक्षण;आयात मानक.कार्य;आयात मानक.कार्यफल;आयात मानक.निरस्तीकरणस्रोत;आयात मानक.निरस्तअपवाद;
        असमकालिक फलन बादमेंरोको(स:निरस्तीकरणस्रोत)->शून्य {प्रतीक्षा कार्य.विलंब(२५);स.निरस्तकरो();}
        असमकालिक फलन मूलजाँच()->शून्य {
            असमकालिक संसाधन(ड:असमडेटासंबंध=प्रतीक्षा असमडेटा.एसक्यूलाइट(":memory:")){
                असमकालिक संसाधन(क:असमडेटाकथन=प्रतीक्षा ड.तैयार("CREATE TABLE entries(id BIGINT, title TEXT)")){
                    स्थिर ग:पूर्णांक=प्रतीक्षा क.चलाओ();
                }
                असमकालिक संसाधन(ल:असमडेटालेनदेन=प्रतीक्षा ड.लेनदेन()){
                    असमकालिक संसाधन(क:असमडेटाकथन=प्रतीक्षा ड.तैयार("INSERT INTO entries VALUES (?,?)")){
                        क.पूर्णांकरखो(१,४२);क.पाठरखो(२,"भारत😀");परीक्षण.बराबर(१,प्रतीक्षा क.चलाओ());
                    }
                    प्रतीक्षा ल.सहेजो();
                }
                असमकालिक संसाधन(क:असमडेटाकथन=प्रतीक्षा ड.तैयार("SELECT id,title FROM entries")){
                    परीक्षण.बराबर(सत्य,प्रतीक्षा क.अगला());परीक्षण.बराबर(४२,क.पूर्णांकलो(०));परीक्षण.बराबर("भारत😀",क.पाठलो(१));
                    परीक्षण.बराबर(असत्य,प्रतीक्षा क.अगला());प्रतीक्षा क.रीसेट();परीक्षण.बराबर(सत्य,प्रतीक्षा क.अगला());
                }
            }
        }
        असमकालिक फलन सीमाजाँच()->शून्य {
            असमकालिक संसाधन(ड:असमडेटासंबंध=प्रतीक्षा असमडेटा.एसक्यूलाइट(":memory:")){
                // A pre-cancelled begin must release admission and transaction ownership.
                संसाधन(स:निरस्तीकरणस्रोत=नया निरस्तीकरणस्रोत()){
                    स.निरस्तकरो();मान रोका:तार्किक=असत्य;
                    प्रयास{स्थिर ल:असमडेटालेनदेन=प्रतीक्षा ड.लेनदेन(स.चिह्नलो());}पकड़ो(द:निरस्तअपवाद){रोका=सत्य;}
                    परीक्षण.बराबर(सत्य,रोका);
                }
                असमकालिक संसाधन(ल:असमडेटालेनदेन=प्रतीक्षा ड.लेनदेन()){प्रतीक्षा ल.वापसलो();}
                असमकालिक संसाधन(क:असमडेटाकथन=प्रतीक्षा ड.तैयार("SELECT ?, ?, ?, ?, ?")){
                    स्थिर ब:बाइटबफ़र=नया बाइटबफ़र(१);ब[०]=परिवर्तित<बाइट>(२५५);
                    क.पूर्णांकरखो(१,४२);क.दशमलवरखो(२,१.२५);क.तार्किकरखो(३,सत्य);क.बाइटरखो(४,ब);क.रिक्तरखो(५);
                    स्थिर प:कार्यफल<तार्किक> = क.अगला();ब[०]=परिवर्तित<बाइट>(०);मान रोका:तार्किक=असत्य;
                    प्रयास{क.पूर्णांकरखो(१,९९);}पकड़ो(द:अपवाद){रोका=सत्य;}
                    परीक्षण.बराबर(सत्य,रोका);परीक्षण.बराबर(सत्य,प्रतीक्षा प);परीक्षण.बराबर(४२,क.पूर्णांकलो(०));
                    परीक्षण.सत्यहै(क.दशमलवलो(१)==१.२५,"दशमलव");परीक्षण.बराबर(सत्य,क.तार्किकलो(२));
                    स्थिर मिला:बाइटबफ़र=क.बाइटलो(३);परीक्षण.बराबर(२५५,परिवर्तित<पूर्णांक>(मिला[०]));परीक्षण.बराबर(सत्य,क.रिक्तहै(४));
                }
                असमकालिक संसाधन(क:असमडेटाकथन=प्रतीक्षा ड.तैयार("WITH RECURSIVE n(x) AS (VALUES(1) UNION ALL SELECT x+1 FROM n WHERE x<1025) SELECT x FROM n")){
                    मान रोका:तार्किक=असत्य;प्रयास{स्थिर प:तार्किक=प्रतीक्षा क.अगला();}पकड़ो(द:अपवाद){रोका=सत्य;}
                    परीक्षण.बराबर(सत्य,रोका);
                }
            }
        }
        असमकालिक फलन निरस्तजाँच()->शून्य {
            असमकालिक संसाधन(ड:असमडेटासंबंध=प्रतीक्षा असमडेटा.एसक्यूलाइट(":memory:")){
                असमकालिक संसाधन(क:असमडेटाकथन=प्रतीक्षा ड.तैयार("WITH RECURSIVE n(x) AS (VALUES(1) UNION ALL SELECT x+1 FROM n WHERE x<1000000000) SELECT sum(x) FROM n")){
                    संसाधन(स:निरस्तीकरणस्रोत=नया निरस्तीकरणस्रोत()){
                        स्थिर रोक:कार्यफल<शून्य> = बादमेंरोको(स);मान रोका:तार्किक=असत्य;
                        प्रयास{स्थिर प:तार्किक=प्रतीक्षा क.अगला(स.चिह्नलो());}पकड़ो(द:निरस्तअपवाद){रोका=सत्य;}
                        प्रतीक्षा रोक;परीक्षण.बराबर(सत्य,रोका);
                    }
                }
            }
        }
        असमकालिक फलन मुख्य()->शून्य {
            प्रतीक्षा मूलजाँच();प्रतीक्षा सीमाजाँच();प्रतीक्षा निरस्तजाँच();
            परीक्षण.बराबर(०,मानक.असमडाटातंत्र.जीवित());
        }
        """;
    @Test void asyncTutorialAndHttpPoliciesLower()throws Exception {
        for(String source:List.of(Files.readString(Path.of("tutorial/async-io/sqlite.bhash")),AsyncHttpFixtures.policies("http://127.0.0.1:8080"),AsyncHttpFixtures.requests("http://127.0.0.1:8080"))){
            var files=List.of(new SourceFile("async-adapter.bhash",source));ManagedEmitter.check(files);ManagedEmitter.core(files);
        }
    }
    @Test void asyncDatabaseApiLowers()throws Exception {
        var source=List.of(new SourceFile("async-db.bhash",BASIC));
        ManagedEmitter.check(source);assertFalse(ManagedEmitter.core(source).functions().isEmpty());
    }
    @Test void jvmAsyncSqliteTransactionsAndOwnedRows()throws Exception {
        Path artifact=directory.resolve("async-db.jar"),log=directory.resolve("run.log");
        new JvmCompiler().compile(List.of(new SourceFile("async-db.bhash",BASIC)),artifact);
        var process=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin","java").toString(),"-jar",artifact.toString())
            .redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try{assertTrue(process.waitFor(60,TimeUnit.SECONDS));assertEquals(0,process.exitValue(),Files.readString(log));assertEquals("",Files.readString(log));}
        finally{process.destroyForcibly();}
    }
    @Test @org.junit.jupiter.api.condition.EnabledIf("dev.bhash.ClrCompilerTest#dotnetAvailable")
    void clrAsyncSqliteTransactionsAndOwnedRows()throws Exception {
        Path artifact=directory.resolve("async-db-clr"),log=directory.resolve("clr-run.log");
        new dev.bhash.backend.clr.ClrCompiler().compile(List.of(new SourceFile("async-db.bhash",BASIC)),artifact);
        var process=new ProcessBuilder(dev.bhash.backend.clr.ClrCompiler.executable(),artifact.resolve("BhashProgram.dll").toString())
            .redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try{assertTrue(process.waitFor(60,TimeUnit.SECONDS));assertEquals(0,process.exitValue(),Files.readString(log));assertEquals("",Files.readString(log));}
        finally{process.destroyForcibly();}
    }
}
