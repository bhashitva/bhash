// SPDX-License-Identifier: Apache-2.0
package dev.bhash;

import dev.bhash.backend.*;
import dev.bhash.backend.llvm.LlvmEmitter;
import dev.bhash.frontend.SourceFile;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class NetworkCoreTest {
    static final String PURE="""
        आयात मानक.एचटीटीपी; आयात मानक.एचटीटीपीअनुरोध; आयात मानक.जालनियम; आयात मानक.परीक्षण;
        कार्य मुख्य() -> शून्य {
            परीक्षण.बराबर("a%20%2B%26%2F%E0%A4%AD%E0%A4%BE%E0%A4%B0%E0%A4%A4%F0%9F%98%80",एचटीटीपी.कूटित("a +&/भारत😀"));
            परीक्षण.बराबर("http://example.com/a/c?q=x",जालनियम.पता("HTTP://EXAMPLE.COM:080/a/b/../c?q=x#part")[०]);
            परीक्षण.बराबर("http://example.com/d?x",जालनियम.समाधान(जालनियम.पता("http://example.com/a/b"),"../d?x")[०]);
            परीक्षण.बराबर("https://example.com/a?q",जालनियम.समाधान(जालनियम.पता("https://example.com/a"),"?q")[०]);
            परीक्षण.बराबर("http://other.test/",जालनियम.समाधान(जालनियम.पता("http://example.com/"),"//other.test")[०]);
            परीक्षण.बराबर("http://example.com/a/x?q=http://other.test/",जालनियम.समाधान(जालनियम.पता("http://example.com/a/b"),"x?q=http://other.test/")[०]);
            परीक्षण.बराबर("http://[::1]:123/a",जालनियम.पता("http://[::1]:123/a")[०]);
            प्रत्येक (प: पाठ में नया सूची<पाठ>{"file:///tmp/a","http://a@b/","http://a:0/","http://a:65536/","http://a/%xy","http://a/क","http://a/\\u0000"}) {
                मान पकड़ा: तार्किक = असत्य; प्रयास { जालनियम.पता(प); } पकड़ो (ग: अपवाद) { पकड़ा = सत्य; }
                परीक्षण.सत्यहै(पकड़ा,"अमान्य URL अस्वीकार हो।");
            }
            स्थिर अ: एचटीटीपीअनुरोध = नया एचटीटीपीअनुरोध("GET","http://example.com/");
            अ.शीर्षजोड़ो("X-Test","one"); अ.शीर्षजोड़ो("x-test","two"); अ.शीर्षरखो("X-TEST","three");
            परीक्षण.बराबर(२,अ.शीर्ष.आकार); परीक्षण.बराबर("three",अ.शीर्ष[१]);
            अ.पाठरखो("नमस्ते😀"); परीक्षण.बराबर("नमस्ते😀",मानक.पाठ्य.यूटीएफ८पाठ(अ.सामग्री));
            मान पकड़ा: तार्किक = असत्य;
            प्रयास { अ.शीर्षजोड़ो("Host","other"); } पकड़ो (ग: अपवाद) { पकड़ा = सत्य; }
            परीक्षण.सत्यहै(पकड़ा,"Host परिवहन निर्धारित करे।");
        }
        """;
    @Test void networkingLowersThroughPreciseRootedCore()throws Exception {
        var core=ManagedEmitter.core(List.of(new SourceFile("network.bhash",PURE)));
        assertTrue(core.networking());
        String ir=LlvmEmitter.emit(core);
        assertTrue(ir.contains("call i64 @bh_network_call"));
        assertTrue(ir.contains("@bh_protect"));
        assertTrue(ir.contains("@bh_roots_push"));
        ManagedEmitter.check(List.of(new SourceFile("network.bhash",PURE)));
    }
    @Test void everyTransportFixtureLowersWithoutOpeningSockets()throws Exception {
        for(String source:List.of(NetworkFixture.functional("http://127.0.0.1:1234"),NetworkFixture.failures("http://127.0.0.1:1234"),
                NetworkFixture.untrustedTls("https://127.0.0.1:1234/"),NetworkFixture.trustedTls("https://127.0.0.1:1234","https://127.0.0.1:1235","https://127.0.0.1:1236"))) {
            var core=ManagedEmitter.core(List.of(new SourceFile("network-fixture.bhash",source)));
            assertTrue(core.networking());assertTrue(LlvmEmitter.emit(core).contains("call i64 @bh_network_call"));
        }
    }
    @Test void minimalProgramsDoNotEnableNetworking()throws Exception {
        var core=ManagedEmitter.core(List.of(new SourceFile("minimal.bhash","कार्य मुख्य() -> शून्य {}")));
        assertFalse(core.networking());assertEquals(1,core.functions().size());assertEquals(0,core.classes().size());
    }
}
