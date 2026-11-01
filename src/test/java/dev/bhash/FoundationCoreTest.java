// SPDX-License-Identifier: Apache-2.0
package dev.bhash;
import dev.bhash.backend.*;
import dev.bhash.backend.llvm.*;
import dev.bhash.frontend.SourceFile;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class FoundationCoreTest {
    @Test void portableFoundationsLowerToRootedNativeCore()throws Exception {
        for(String source:FoundationFixtures.portable()) {
            var core=ManagedEmitter.core(List.of(new SourceFile("foundation.bhash",source)));
            String ir=LlvmEmitter.emit(core);
            assertTrue(ir.contains("@bh_roots_push"));
        }
        String files=FoundationFixtures.files(java.nio.file.Path.of("target/foundation-files"));
        var core=ManagedEmitter.core(List.of(new SourceFile("foundation.bhash",files)));
        String ir=LlvmEmitter.emit(core);assertTrue(ir.contains("call i64 @bh_foundation_call"));
        assertTrue(ir.contains("@bh_protect"));
    }
    @Test void unusedFoundationsDoNotChangeSimpleProgramLayouts()throws Exception {
        var core=ManagedEmitter.core(List.of(new SourceFile("minimal.bhash","कार्य मुख्य() -> शून्य {}")));
        assertEquals(1,core.functions().size());assertEquals(0,core.classes().size());
    }
    @Test void resourceContractRejectsMissingOrWrongCloseMethod() {
        for(String body:List.of("", "सार्वजनिक कार्य बंदकरो() -> पूर्णांक { लौटाओ १; }"))
            assertThrows(CompilationException.class,()->ManagedEmitter.check(List.of(new SourceFile("bad.bhash",
                "वर्ग क {"+body+"} कार्य मुख्य() -> शून्य { संसाधन (ग: क = नया क()) {} }"))));
    }
}
