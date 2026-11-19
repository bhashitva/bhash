// SPDX-License-Identifier: Apache-2.0
package dev.bhash;

import dev.bhash.backend.*;
import dev.bhash.backend.jvm.JvmCompiler;
import dev.bhash.frontend.SourceFile;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class NullSafetyTest {
    static final String PROGRAM="""
        वर्ग आरंभ { सार्वजनिक मान वैकल्पिक:पाठ?; सार्वजनिक साझा मान साझापाठ:पाठ?; }
        वर्ग क { फलन लो()->पूर्णांक { लौटाओ २; } फलन जोड़ो(अ:पूर्णांक)->पूर्णांक{लौटाओ अ+१;} फलन बोलो(अ:पूर्णांक)->शून्य{लिखो("बोलो",अ);} }
        असमकालिक फलन असमउत्तर()->पूर्णांक {प्रतीक्षा मानक.कार्य.विलंब(१); लिखो("वैकल्पिक"); लौटाओ ८;}
        असमकालिक फलन क्रमदूसरा()->पूर्णांक {लिखो("दूसरा"); लौटाओ ६;}
        असमकालिक फलन मुख्य()->शून्य {
            मान ग:पूर्णांक=०;
            स्थिर वैकल्पिक:फलन()->पूर्णांक=फलन()->पूर्णांक {ग=ग+१; लौटाओ ७;};
            स्थिर अ:पूर्णांक?=३; स्थिर ब:पूर्णांक?=रिक्त;
            लिखो(अ??वैकल्पिक(),ब??वैकल्पिक(),ग);
            स्थिर व:क?=नया क(); स्थिर र:क?=रिक्त;
            लिखो(व?.लो()??९,र?.लो()??९);
            स्थिर स:सूची<पूर्णांक?> =नया सूची<पूर्णांक?>{अ,ब,०};
            लिखो(स[०]??९,स[१]??९,स[२]??९);
            स्थिर त:तार्किक?=असत्य; स्थिर द:दशमलव?=०.०;
            लिखो(त??सत्य,द??२.५);
            स्थिर मूल:सर्ववस्तु?=४;
            लिखो(सुरक्षितरूप<पूर्णांक>(मूल)??९,सुरक्षितरूप<दशमलव>(मूल)??२.५);
            लिखो(अ??प्रतीक्षा असमउत्तर(),ब??प्रतीक्षा असमउत्तर());
            लिखो(परिवर्तित<पूर्णांक?>(२.९)??९,परिवर्तित<पाठ?>(रिक्त)??"अनुपस्थित");
            स्थिर छोटा:बाइट?=परिवर्तित<बाइट>(२५५);
            स्थिर लघु:लघुपूर्णांक?=परिवर्तित<लघुपूर्णांक>(०);
            लिखो(परिवर्तित<पूर्णांक>(छोटा??परिवर्तित<बाइट>(०)),परिवर्तित<पूर्णांक>(लघु??परिवर्तित<लघुपूर्णांक>(१)));
            स्थिर शब्द:मानचित्र<पाठ,पाठ?> = नया मानचित्र<पाठ,पाठ?>{"अ":रिक्त,"ब":"भारत"+"😀"};
            लिखो(शब्द["अ"]??"नहीं",शब्द["ब"]??"नहीं");
            स्थिर पाठसूची:पाठ?[]=नया पाठ?[२]; लिखो(पाठसूची[०]??"नहीं",पाठसूची[१]==रिक्त);
            लिखो(मानक.जेसन.पाठबनाओ(पाठसूची));
            स्थिर समूह:मानक.समुच्चय<पूर्णांक?> = नया मानक.समुच्चय<पूर्णांक?>();
            प्रयास{समूह.जोड़ो(रिक्त);}पकड़ो(ग:अपवाद){लिखो("सदस्य रोका");} समूह.जोड़ो(०); लिखो(समूह.है(०));
            प्रयास{स्थिर कुंजी=नया मानचित्र<सर्ववस्तु?,पूर्णांक>{रिक्त:१};}पकड़ो(ग:अपवाद){लिखो("कुंजी रोकी");}
            स्थिर क्रमकार्य=क्रमदूसरा();
            र?.बोलो(प्रतीक्षा क्रमदूसरा());
            लिखो(अ??प्रतीक्षा क्रमकार्य);लिखो("पहले");लिखो(प्रतीक्षा क्रमकार्य);
            व?.बोलो(प्रतीक्षा क्रमदूसरा());
            लिखो(व?.जोड़ो(प्रतीक्षा क्रमदूसरा())??०);
            स्थिर फलनमान:सर्ववस्तु?=फलन()->पूर्णांक{लौटाओ ११;};
            स्थिर वैकल्पिकफलन=सुरक्षितरूप<फलन()->पूर्णांक>(फलनमान); यदि(वैकल्पिकफलन!=रिक्त){लिखो(वैकल्पिकफलन());}
            स्थिर आरंभमान=नया आरंभ(); लिखो(आरंभमान.वैकल्पिक==रिक्त,आरंभ.साझापाठ==रिक्त);
            स्थिर भीतर:पूर्णांक[]=नया पूर्णांक[]{१};
            स्थिर बाहर:सूची<पूर्णांक[]?> = नया सूची<पूर्णांक[]?>{भीतर,रिक्त};
            स्थिर संग्रहमान:सर्ववस्तु?=बाहर.लो(०); लिखो(प्रकारहै<पूर्णांक[]>(संग्रहमान));
        }
        """;
    static final String OUTPUT="३ ७ १\n२ ९\n३ ९ ०\nअसत्य ०.०\n४ २.५\nवैकल्पिक\n३ ८\n२ अनुपस्थित\n२५५ ०\nनहीं भारत😀\nनहीं सत्य\n[null,null]\nसदस्य रोका\nसत्य\nकुंजी रोकी\n३\nपहले\nदूसरा\n६\nदूसरा\nबोलो ६\nदूसरा\n७\n११\nसत्य सत्य\nसत्य\n";
    @TempDir Path temporary;
    private List<SourceFile> sources(String source){return List.of(new SourceFile("nulls.bhash",source,true));}
    private void check(String source)throws Exception {
        ManagedEmitter.emit(sources(source),Target.JVM);
        ManagedEmitter.emit(sources(source),Target.CLR);
        ManagedEmitter.core(sources(source));
    }
    private void reject(String source,String message) {
        var error=assertThrows(CompilationException.class,()->check(source));
        assertTrue(error.getMessage().contains(message),error.getMessage());
    }
    @Test void strictAssignmentsAndGuards()throws Exception {
        check("फलन लो(व:पूर्णांक?)->पूर्णांक {यदि(व==रिक्त){लौटाओ ०;} लौटाओ व+१;} फलन मुख्य()->शून्य {स्थिर व:पूर्णांक?=३; लिखो(लो(व));}");
        reject("वर्ग क {} फलन मुख्य()->शून्य {स्थिर व:क=रिक्त;}","Null assignment");
        reject("फलन मुख्य()->शून्य {स्थिर व:पूर्णांक?=रिक्त; लिखो(व+१);}","Numeric operand");
        reject("वर्ग क {फलन लो()->पूर्णांक{लौटाओ १;}} फलन मुख्य()->शून्य {स्थिर व:क?=रिक्त; लिखो(व.लो());}","रिक्त हो सकता");
    }
    @Test void assignmentsEstablishAndInvalidateLocalProofs()throws Exception {
        check("वर्ग क {फलन लो()->पूर्णांक{लौटाओ १;}} फलन मुख्य()->शून्य {स्थिर व:पूर्णांक?=२; लिखो(व+१); मान र:क?=नया क(); लिखो(र.लो()); र=रिक्त; यदि(र==रिक्त){लौटाओ;} लिखो(र.लो());}");
        reject("वर्ग क {फलन लो()->पूर्णांक{लौटाओ १;}} फलन मुख्य()->शून्य {मान र:क?=नया क(); र=रिक्त; र.लो();}","रिक्त हो सकता");
        reject("वर्ग क {मान र:क=रिक्त;} फलन मुख्य()->शून्य {}","Null assignment");
        reject("वर्ग क {साझा मान र:क;} फलन मुख्य()->शून्य {}","Non-null field");
    }
    @Test void operatorsCompileAcrossTargets()throws Exception {
        check("वर्ग क {फलन लो()->पूर्णांक{लौटाओ २;}} फलन मुख्य()->शून्य {स्थिर व:क?=नया क(); लिखो(व?.लो()??३); स्थिर अ:पूर्णांक?=रिक्त; लिखो(अ??५);}");
    }
    @Test void nullableValuesSurviveAsyncLowering()throws Exception {
        check("असमकालिक फलन लो()->पूर्णांक {प्रतीक्षा मानक.कार्य.विलंब(१); लौटाओ ५;} असमकालिक फलन मुख्य()->शून्य {स्थिर व:पूर्णांक?=३; प्रतीक्षा मानक.कार्य.विलंब(१); यदि(व!=रिक्त){लिखो(व+१);} लिखो(व??प्रतीक्षा लो());}");
        check("असमकालिक फलन लो()->पूर्णांक {लौटाओ ५;} असमकालिक फलन मुख्य()->शून्य {मान काम:कार्यफल<पूर्णांक>?=लो(); लिखो(प्रतीक्षा काम);}");
        reject("वर्ग क {फलन बंदकरो()->कार्यफल<शून्य>? {लौटाओ रिक्त;}} असमकालिक फलन मुख्य()->शून्य {असमकालिक संसाधन(व:क=नया क()){लिखो(१);}}","असमकालिक संसाधन");
    }
    @Test void optionalFunctionsAndGenericInferenceRetainNullability()throws Exception {
        check("फलन पहचान<त>(व:त)->त {लौटाओ व;} फलन मुख्य()->शून्य {स्थिर क:पूर्णांक?=रिक्त; स्थिर अ=पहचान(क); लिखो(अ??१); स्थिर फ:(फलन()->पूर्णांक)?=फलन()->पूर्णांक{लौटाओ २;}; यदि(फ!=रिक्त){लिखो(फ());}} ");
        reject("फलन पहचान<त>(व:त)->त {लौटाओ व;} फलन मुख्य()->शून्य {स्थिर क:पूर्णांक?=रिक्त; स्थिर अ:पूर्णांक=पहचान(क);}","Nullable value");
        check("वर्ग क {सार्वजनिक स्थिर काम:फलन()->पूर्णांक; निर्माता(){काम=फलन()->पूर्णांक{लौटाओ २;};}} फलन मुख्य()->शून्य {स्थिर व:क?=रिक्त; लिखो(व?.काम()??०);}");
        reject("वर्ग क {सार्वजनिक मान काम:(फलन()->पूर्णांक)?;} फलन मुख्य()->शून्य {स्थिर व=नया क(); लिखो(व.काम());}","रिक्त हो सकता");
        reject("वर्ग क {} फलन मुख्य()->शून्य {स्थिर व:क?=रिक्त; व?.अनुपलब्ध();}","Undefined member");
    }
    @Test void stableImmutableFieldsNarrowAndExceptionPathsDoNotLeakProofs()throws Exception {
        check("वर्ग क {सार्वजनिक स्थिर व:पूर्णांक?; निर्माता(){व=१;}} फलन मुख्य()->शून्य {स्थिर क१=नया क(); यदि(क१.व!=रिक्त){लिखो(क१.व+१);}} ");
        reject("फलन मुख्य()->शून्य {स्थिर व:पूर्णांक?=रिक्त; प्रयास {यदि(व==रिक्त){फेंको नया अपवाद();}} पकड़ो(ग:अपवाद){लिखो(व+१);}}","Numeric operand");
    }
    @Test void flowInvalidationAndConstructionRules()throws Exception {
        check("वर्ग क {मान व:क?; निर्माता(){व=रिक्त;}} फलन मुख्य()->शून्य {स्थिर अ:क?[]=नया क?[२]; स्थिर ब:क[]=नया क[]{नया क()};}");
        reject("वर्ग क {मान व:क;} फलन मुख्य()->शून्य {}","every successful");
        reject("वर्ग क {} फलन मुख्य()->शून्य {स्थिर अ:क[]=नया क[२];}","Non-null reference arrays");
        reject("फलन मुख्य()->शून्य {मान व:पूर्णांक?=२; यदि(व!=रिक्त){व=रिक्त; लिखो(व+१);}}","Numeric operand");
        reject("फलन मुख्य()->शून्य {मान व:पूर्णांक?=२; यदि(व!=रिक्त){जबतक(सत्य){लिखो(व+१); व=रिक्त;}}}","Numeric operand");
        reject("फलन लो(व:पूर्णांक)->शून्य{} फलन लो(व:पूर्णांक?)->शून्य{} फलन मुख्य()->शून्य{}","nullability");
        reject("वर्ग क {निर्माता(व:पूर्णांक){} निर्माता(व:पूर्णांक?){} } फलन मुख्य()->शून्य{}","nullability");
    }
    @Test void jvmOptionalScalarsAndLazyOperators()throws Exception {
        Path jar=temporary.resolve("nulls.jar");new JvmCompiler().compile(sources(PROGRAM),jar);
        var process=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin","java").toString(),"-jar",jar.toString()).redirectErrorStream(true).start();
        String output=new String(process.getInputStream().readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);
        assertEquals(0,process.waitFor(),output);assertEquals(OUTPUT,output.replace("\r\n","\n"));
    }
    @Test void clrOptionalScalarsAndLazyOperators()throws Exception {
        Path directory=temporary.resolve("clr");new dev.bhash.backend.clr.ClrCompiler().compile(sources(PROGRAM),directory);
        Path log=temporary.resolve("clr.log");
        var process=new ProcessBuilder(dev.bhash.backend.clr.ClrCompiler.executable(),directory.resolve("BhashProgram.dll").toString()).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        assertTrue(process.waitFor(30,java.util.concurrent.TimeUnit.SECONDS));
        String output=Files.readString(log).replace("\r\n","\n");assertEquals(0,process.exitValue(),output);assertEquals(OUTPUT,output);
    }
    @Test void standardLibraryMissingValuesAreNullable()throws Exception {
        check("फलन मुख्य()->शून्य {स्थिर व=मानक.जेसन.पढ़ो(\"null\"); लिखो(मानक.जेसन.पाठबनाओ(व));}");
        reject("फलन मुख्य()->शून्य {स्थिर व:सर्ववस्तु=मानक.जेसन.पढ़ो(\"null\");}","Expected");
    }
    @Test void strictStandardLibraryInterfaceContractsRemainConsistent()throws Exception {
        check("फलन मुख्य()->शून्य {स्थिर मार्ग=नया मानक.वेबसॉकेटमार्ग();}");
    }
}
