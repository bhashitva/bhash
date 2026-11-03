// SPDX-License-Identifier: Apache-2.0
package dev.bhash;

import dev.bhash.backend.*;
import dev.bhash.backend.jvm.JvmCompiler;
import dev.bhash.backend.clr.ClrCompiler;
import dev.bhash.backend.llvm.LlvmEmitter;
import dev.bhash.frontend.SourceFile;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class DatabaseProviderTest {
    @TempDir Path temp;
    static final String CHECKSUM="""
        आयात मानक.डेटाचेकसम;
        फलन मुख्य() -> शून्य {
            लिखो(डेटाचेकसम.शा२५६("")); लिखो(डेटाचेकसम.शा२५६("abc"));
            लिखो(डेटाचेकसम.शा२५६("भारत😀"));
        }
        """;
    static final String MIGRATIONS="""
        आयात मानक.डेटा;
        आयात मानक.डेटासंबंध;
        आयात मानक.डेटाकथन;
        आयात मानक.माइग्रेशन;
        आयात मानक.माइग्रेशनयोजना;
        फलन मुख्य() -> शून्य {
            स्थिर पहला: माइग्रेशन=नया माइग्रेशन(१,"आरंभ",नया सूची<पाठ>{"CREATE TABLE tasks(id BIGINT PRIMARY KEY, title TEXT NOT NULL)"});
            स्थिर दूसरा: माइग्रेशन=नया माइग्रेशन(२,"तारीख",नया सूची<पाठ>{"ALTER TABLE tasks ADD COLUMN due TEXT"});
            स्थिर व१: माइग्रेशनयोजना=नया माइग्रेशनयोजना(नया सूची<माइग्रेशन>{पहला});
            स्थिर व२: माइग्रेशनयोजना=नया माइग्रेशनयोजना(नया सूची<माइग्रेशन>{पहला,दूसरा});
            संसाधन (ड: डेटासंबंध=डेटा.एसक्यूलाइट("app.db")) {
                लिखो(व१.लागूकरो(ड));
                संसाधन (क: डेटाकथन=ड.तैयार("INSERT INTO tasks(id,title) VALUES (?,?)")) { क.पूर्णांकरखो(१,१); क.पाठरखो(२,"भारत😀"); क.चलाओ(); }
                लिखो(व२.लागूकरो(ड)); लिखो(व२.लागूकरो(ड));
                संसाधन (क: डेटाकथन=ड.तैयार("SELECT title,due FROM tasks")) { क.अगला(); लिखो(क.पाठलो(०),क.रिक्तहै(१)); }
                स्थिर बदला: माइग्रेशनयोजना=नया माइग्रेशनयोजना(नया सूची<माइग्रेशन>{नया माइग्रेशन(१,"बदला",नया सूची<पाठ>{"CREATE TABLE tasks(id BIGINT PRIMARY KEY, title TEXT NOT NULL)"}),दूसरा});
                मान बदला_रोका: तार्किक = असत्य;
                प्रयास { बदला.जाँचो(ड); } पकड़ो (ग: अपवाद) { बदला_रोका=सत्य; }
                यदि (!बदला_रोका) { त्रुटि("बदला इतिहास स्वीकार हुआ"); }
                स्थिर खराब: माइग्रेशनयोजना=नया माइग्रेशनयोजना(नया सूची<माइग्रेशन>{पहला,दूसरा,नया माइग्रेशन(३,"असफल",नया सूची<पाठ>{"INSERT INTO tasks VALUES (2,'rollback',NULL)","INSERT INTO tasks VALUES (1,'duplicate',NULL)"})});
                मान असफल: तार्किक = असत्य;
                प्रयास { खराब.लागूकरो(ड); } पकड़ो (ग: अपवाद) { असफल=सत्य; }
                यदि (!असफल) { त्रुटि("असफल माइग्रेशन स्वीकार हुआ"); }
                व२.जाँचो(ड);
                संसाधन (क: डेटाकथन=ड.तैयार("SELECT count(*) FROM tasks")) { क.अगला(); यदि (क.पूर्णांकलो(०)!=१) { त्रुटि("माइग्रेशन वापस नहीं हुआ"); } }
                मान रोका: तार्किक = असत्य;
                प्रयास { व१.जाँचो(ड); } पकड़ो (ग: अपवाद) { रोका=सत्य; }
                यदि (!रोका) { त्रुटि("पुराना संस्करण स्वीकार हुआ"); } लिखो("पुराना रोका");
            }
        }
        """;
    private String run(String source,boolean clr)throws Exception {
        Path output=temp.resolve(clr?"app":"app.jar"),log=temp.resolve("run.log");
        if(clr)new ClrCompiler().compile("provider.bhash",source,output);else new JvmCompiler().compile("provider.bhash",source,output);
        var command=clr?List.of(ClrCompiler.executable(),output.resolve("BhashProgram.dll").toString()):List.of(Path.of(System.getProperty("java.home"),"bin","java").toString(),"-jar",output.toString());
        var builder=new ProcessBuilder(command).directory(temp.toFile()).redirectErrorStream(true).redirectOutput(log.toFile());
        builder.environment().remove("JAVA_TOOL_OPTIONS");
        var p=builder.start();
        try{assertTrue(p.waitFor(90,TimeUnit.SECONDS));String text=Files.readString(log).replace("\r\n","\n");assertEquals(0,p.exitValue(),text);return text;}finally{p.destroyForcibly();}
    }
    @Test void sqlScannerPreservesQuotesAndNestedComments()throws Exception {
        assertEquals("SELECT /* outer /* inner ? */ ? */ $1, '?' -- ?\n, $2;\n",run("""
            आयात मानक.एसक्यूएलनियम;
            फलन मुख्य() -> शून्य {
                लिखो(एसक्यूएलनियम.पोस्टग्रेस("SELECT /* outer /* inner ? */ ? */ ?, '?' -- ?\\n, ?;"));
                प्रत्येक (नया सूची<पाठ>{"SELECT ?1", "SELECT 1; SELECT 2", "/* unfinished", "BEGIN", "SELECT E'escape'"} में स: पाठ) {
                    मान रोका: तार्किक = असत्य;
                    प्रयास { एसक्यूएलनियम.पोस्टग्रेस(स); } पकड़ो (ग: अपवाद) { रोका=सत्य; }
                    यदि (!रोका) { त्रुटि("अमान्य SQL स्वीकार हुआ"); }
                }
            }
            """,false));
    }
    @Test void sha256KnownVectorsAndUnicode()throws Exception {
        var sha=java.security.MessageDigest.getInstance("SHA-256");var expected=new StringBuilder();
        for(String text:List.of("","abc","भारत😀"))expected.append(java.util.HexFormat.of().formatHex(sha.digest(text.getBytes(java.nio.charset.StandardCharsets.UTF_8)))).append('\n');
        assertEquals(expected.toString(),run(CHECKSUM,false));
    }
    @Test void sqliteUpgradePreservesDataAndRejectsDowngrade()throws Exception {assertEquals("१\n१\n०\nभारत😀 सत्य\nपुराना रोका\n",run(MIGRATIONS,false));}
    @Test void hindiTutorialRunsOnJvmAndClr()throws Exception {
        String source=Files.readString(Path.of("tutorial/database-providers/migrations.bhash"));
        String initial=run(source,false);assertTrue(initial.contains("लागू बदलाव:  २"),initial);
        if(ClrCompilerTest.dotnetAvailable()){String again=run(source,true);assertTrue(again.contains("लागू बदलाव:  ०"),again);assertTrue(again.contains("भारत😀 का स्वागत"),again);}
    }
    @Test void migrationsLowerToNativeWithRootedCalls()throws Exception {
        var core=ManagedEmitter.core(List.of(new SourceFile("migrations.bhash",MIGRATIONS)));
        assertTrue(core.database());assertTrue(LlvmEmitter.emit(core).contains("@bh_database_call"));
    }
    @Test void clrChecksumAndMigrations()throws Exception {
        Assumptions.assumeTrue(ClrCompilerTest.dotnetAvailable());
        assertEquals("१\n१\n०\nभारत😀 सत्य\nपुराना रोका\n",run(MIGRATIONS,true));
    }
}
