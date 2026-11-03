// SPDX-License-Identifier: Apache-2.0
package dev.bhash;
import dev.bhash.backend.*;
import dev.bhash.frontend.*;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class ClosureExpanderTest {
    private void reject(String body,String message) {
        var e=assertThrows(CompilationException.class,()->ManagedEmitter.core(List.of(new SourceFile("invalid.bhash","फलन मुख्य()->शून्य {"+body+"}"))));
        assertTrue(e.getMessage().contains(message),e.getMessage());
    }
    @Test void functionContractsRejectInvalidUses() {
        reject("स्थिर क: फलन(पूर्णांक)->पूर्णांक=फलन(अ: पाठ)->पूर्णांक { लौटाओ १; };","Expected");
        reject("स्थिर क: फलन(पूर्णांक)->पूर्णांक=फलन(अ: पूर्णांक)->पूर्णांक { लौटाओ अ; }; क();","argument count");
        reject("स्थिर क: फलन()->पूर्णांक=फलन()->पूर्णांक { लौटाओ \"गलत\"; };","Expected");
        reject("स्थिर क: फलन()->पूर्णांक=फलन()->पूर्णांक { लिखो(१); };","every path");
        reject("स्थिर क: फलन(पूर्णांक)->शून्य=फलन(अ: पूर्णांक)->शून्य { अ=२; };","immutable");
    }
    @Test void importedFunctionReferencesAndShadowedLambdaParameters()throws Exception {
        var sources=List.of(new SourceFile("library.bhash","संकुल पुस्तक; सार्वजनिक फलन अंक(अ: पूर्णांक)->पूर्णांक { लौटाओ अ+१; }"),
            new SourceFile("main.bhash","""
                आयात पुस्तक.अंक;
                फलन मुख्य()->शून्य {
                    स्थिर क: फलन(पूर्णांक)->पूर्णांक=पुस्तक.अंक;
                    स्थिर ख: फलन(पूर्णांक)->पूर्णांक=फलन(अंक: पूर्णांक)->पूर्णांक { लौटाओ अंक+१; };
                    लिखो(क(३),ख(३));
                }
                """));
        assertNotNull(ManagedEmitter.core(sources));
    }
    @Test void allFixturesLowerToTypedNativeCore()throws Exception {
        for(var f:ClosureFixtures.cases()) {
            var core=ManagedEmitter.core(List.of(new SourceFile(f.name()+".bhash",f.source())));
            assertFalse(dev.bhash.backend.llvm.LlvmEmitter.emit(core,"",true).isBlank(),f.name());
        }
    }
    @Test void loweringIsDeterministic()throws Exception {
        var sources=List.of(new SourceFile("stable.bhash",ClosureFixtures.cases().stream().filter(f->f.name().equals("escaping-shared-mutation")).findFirst().orElseThrow().source()));
        assertEquals(dev.bhash.backend.llvm.LlvmEmitter.emit(ManagedEmitter.core(sources)),dev.bhash.backend.llvm.LlvmEmitter.emit(ManagedEmitter.core(sources)));
    }
    @Test void invalidCaptureAssignmentRetainsSourceLocation(){
        var e=assertThrows(CompilationException.class,()->ManagedEmitter.core(List.of(new SourceFile("capture.bhash","""
            फलन मुख्य()->शून्य {
                स्थिर अ: पूर्णांक=१;
                स्थिर क: फलन()->शून्य=फलन()->शून्य {
                    अ=२;
                };
            }
            """))));
        assertTrue(e.getMessage().contains("capture.bhash:4:"),e.getMessage());
        assertTrue(e.getMessage().contains("immutable"),e.getMessage());
    }
}
