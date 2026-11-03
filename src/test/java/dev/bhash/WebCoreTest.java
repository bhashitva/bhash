// SPDX-License-Identifier: Apache-2.0
package dev.bhash;

import dev.bhash.backend.*;
import dev.bhash.backend.llvm.LlvmEmitter;
import dev.bhash.frontend.SourceFile;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class WebCoreTest {
    static final String PURE="""
        आयात मानक.वेब; आयात मानक.वेबअनुरोध; आयात मानक.वेबउत्तर;
        आयात मानक.वेबचालक; आयात मानक.वेबअनुप्रयोग; आयात मानक.वेबत्रुटि;
        आयात मानक.परीक्षण; आयात मानक.पाठ्य;
        वर्ग प्रतिध्वनि पालन वेबचालक {
            सार्वजनिक फलन संभालो(अ: वेबअनुरोध) -> वेबउत्तर { लौटाओ वेब.पाठउत्तर(२००,अ.पथमान["id"]); }
        }
        फलन मुख्य() -> शून्य {
            परीक्षण.बराबर("0",वेब.अंक(०)); परीक्षण.बराबर("9223372036854775807",वेब.अंक(९२२३३७२०३६८५४७७५८०७));
            परीक्षण.बराबर(१०४८५७६,वेब.संख्या("1048576",१०४८५७६));
            परीक्षण.बराबर("भारत😀 +",वेब.विकूटित("%E0%A4%AD%E0%A4%BE%E0%A4%B0%E0%A4%A4%F0%9F%98%80+%2B",सत्य));
            परीक्षण.बराबर("a+b",वेब.विकूटित("a+b",असत्य));
            प्रत्येक (नया सूची<पाठ>{"%","%xy","%ff","%00","%0a","%2f","%5c"} में प: पाठ) {
                मान पकड़ा: तार्किक = असत्य; प्रयास { वेब.विकूटित(प,असत्य); } पकड़ो (ग: वेबत्रुटि) { परीक्षण.बराबर(४००,ग.स्थिति); पकड़ा = सत्य; } परीक्षण.सत्यहै(पकड़ा,"अमान्य URL अस्वीकार हो।");
            }
            प्रत्येक (नया सूची<पाठ>{"","-1","१","1x","9223372036854775808"} में प: पाठ) {
                मान पकड़ा: तार्किक = असत्य; प्रयास { वेब.संख्या(प,१०४८५७६); } पकड़ो (ग: वेबत्रुटि) { पकड़ा = सत्य; } परीक्षण.सत्यहै(पकड़ा,"अमान्य अंक अस्वीकार हो।");
            }
            स्थिर अ: वेबअनुरोध = नया वेबअनुरोध("GET","/tasks/%E0%A4%AD?q=a+b&q=two&empty=",नया सूची<पाठ>(),नया बाइटबफ़र(०));
            परीक्षण.बराबर(२,अ.प्रश्नमान("q").आकार); परीक्षण.बराबर("a b",अ.प्रश्नमान("q")[०]); परीक्षण.बराबर("",अ.प्रश्नमान("empty")[०]);
            स्थिर र: वेबअनुप्रयोग = नया वेबअनुप्रयोग(); र.जोड़ो("GET","/tasks/:id",नया प्रतिध्वनि());
            परीक्षण.बराबर("भ",पाठ्य.यूटीएफ८पाठ(र.संभालो(अ).सामग्री));
            अ.विधि = "HEAD"; परीक्षण.बराबर(२००,र.संभालो(अ).स्थिति);
            अ.विधि = "DELETE"; स्थिर गलत: वेबउत्तर = र.संभालो(अ); परीक्षण.बराबर(४०५,गलत.स्थिति); परीक्षण.बराबर("GET, HEAD",वेब.मानलो(गलत.शीर्ष,"allow")[०]);
            स्थिर नहीं: वेबअनुरोध = नया वेबअनुरोध("GET","/other",नया सूची<पाठ>(),नया बाइटबफ़र(०)); परीक्षण.बराबर(४०४,र.संभालो(नहीं).स्थिति);
            स्थिर ज: वेबअनुरोध = नया वेबअनुरोध("POST","/",नया सूची<पाठ>{"content-type","application/json; charset=utf-8"},पाठ्य.यूटीएफ८("{\\"नाम\\":\\"भारत😀\\"}"));
            परीक्षण.सत्यहै(प्रकारहै<मानचित्र<पाठ,सर्ववस्तु>>(ज.जेसनलो()),"JSON वस्तु मिले।");
            स्थिर उ: वेबउत्तर = वेब.पाठउत्तर(२००,"नमस्ते😀"); उ.शीर्षरखो("X-Test","one"); उ.शीर्षरखो("x-test","two"); परीक्षण.बराबर(४,उ.शीर्ष.आकार);
            मान पकड़ा: तार्किक = असत्य; प्रयास { उ.शीर्षरखो("X-Test","bad\\r\\nInjected: yes"); } पकड़ो (ग: अपवाद) { पकड़ा = सत्य; } परीक्षण.सत्यहै(पकड़ा,"शीर्ष में CRLF न आने दें।");
        }
        """;
    static List<SourceFile> demo()throws Exception {Path p=Path.of("examples/web-tasks/src/main.bhash");return List.of(new SourceFile(p.toString(),Files.readString(p)));}
    @Test void portableFrameworkUsesNoTransportOrClientDependency()throws Exception {
        var core=ManagedEmitter.core(List.of(new SourceFile("web-pure.bhash",PURE)));
        assertFalse(core.server());assertFalse(core.networking());ManagedEmitter.check(List.of(new SourceFile("web-pure.bhash",PURE)));
        assertNotNull(LlvmEmitter.emit(core));
    }
    @Test void shippedWebApplicationLowersWithPreciseRootsAndWithoutClientCurl()throws Exception {
        var core=ManagedEmitter.core(demo());assertTrue(core.server());assertTrue(core.database());assertFalse(core.networking());
        String ir=LlvmEmitter.emit(core);assertTrue(ir.contains("call i64 @bh_server_call"));assertTrue(ir.contains("@bh_roots_push"));assertTrue(ir.contains("@bh_protect"));
    }
}
