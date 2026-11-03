// SPDX-License-Identifier: Apache-2.0
package dev.bhash;
import dev.bhash.backend.*;
import dev.bhash.backend.llvm.LlvmEmitter;
import dev.bhash.frontend.SourceFile;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class AuthenticationCoreTest {
    @Test void sharedSecurityPolicyLowersWithoutNetworkClientOrDatabase()throws Exception {
        for(String name:List.of("crypto","sessions")){
            var core=ManagedEmitter.core(AuthenticationFixtures.source(name));
            assertTrue(core.authentication());assertFalse(core.networking());assertFalse(core.database());assertFalse(core.server());
            String ir=LlvmEmitter.emit(core);assertTrue(ir.contains("call i64 @bh_foundation_call"));assertTrue(ir.contains("@bh_roots_push"));
        }
    }
    @Test void ordinaryApplicationsDoNotRequireCryptoProviders()throws Exception {
        assertFalse(ManagedEmitter.core(List.of(new SourceFile("hello.bhash","फलन मुख्य() -> शून्य { लिखो(१); }"))).authentication());
    }
    @Test void exampleAndItsPureTestsCompileForAllBackends()throws Exception {
        var sources=AuthenticationHttpFixture.sources();
        for(Target target:List.of(Target.JVM,Target.CLR))assertNotNull(ManagedEmitter.emit(sources,target));
        var core=ManagedEmitter.core(sources);assertTrue(core.authentication());assertTrue(core.server());assertTrue(core.database());assertFalse(core.networking());
        assertNotNull(LlvmEmitter.emit(core));
    }
}
