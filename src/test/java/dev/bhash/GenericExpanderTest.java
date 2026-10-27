// SPDX-License-Identifier: Apache-2.0
package dev.bhash;

import dev.bhash.backend.*;
import dev.bhash.frontend.Frontend;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class GenericExpanderTest {
    private String emit(String source) throws Exception {
        var parsed=Frontend.parse("generic.bhash",source);
        assertTrue(parsed.valid(),parsed.diagnostics().toString());
        return ManagedEmitter.emit("generic.bhash",parsed.tree(),Target.JVM);
    }
    @Test void recursiveClosedTypesReuseTheirSpecialization() throws Exception {
        String code=emit("""
                वर्ग कड़ी<त> { मान अगली: कड़ी<त>; मान मूल्य: त; }
                कार्य मुख्य() -> शून्य {
                    स्थिर पहली: कड़ी<पूर्णांक> = नया कड़ी<पूर्णांक>();
                    पहली.अगली = नया कड़ी<पूर्णांक>();
                    पहली.अगली.मूल्य = ७;
                    लिखो(पहली.अगली.मूल्य);
                }
                """);
        // Compiler-owned facade classes are not generic specializations.
        int facades=dev.bhash.stdlib.StandardLibrary.source().parse().tree().classDecl().size();
        assertEquals(1,code.split("static class C",-1).length-1-facades);
    }
    @Test void expandingRecursiveTemplatesFailWithADiagnostic() {
        var error=assertThrows(CompilationException.class,()->emit("""
                वर्ग पात्र<त> { मान अगला: पात्र<पात्र<त>>; }
                कार्य मुख्य() -> शून्य { नया पात्र<पूर्णांक>(); }
                """));
        assertTrue(error.getMessage().contains("specialization limit"),error.getMessage());
    }
    @Test void specializedBodyErrorsPreserveOriginalSourceLines() {
        var error=assertThrows(CompilationException.class,()->emit("""
                वर्ग पात्र<त> {
                    कार्य गलत() -> पूर्णांक {
                        लौटाओ "गलत";
                    }
                }
                कार्य मुख्य() -> शून्य { नया पात्र<पूर्णांक>(); }
                """));
        assertTrue(error.getMessage().startsWith("generic.bhash:3:"),error.getMessage());
    }
}
