// SPDX-License-Identifier: Apache-2.0
package dev.bhash;

import dev.bhash.backend.*;
import dev.bhash.frontend.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MultiDiagnosticTest {
    @Test void reportsIndependentErrorsAcrossBodiesAndFilesWithoutEmittingOutput() {
        var sources=List.of(new SourceFile("a.bhash","""
            कार्य मुख्य() -> शून्य {
                मान क: पूर्णांक = सत्य;
                लिखो(क);
                लिखो(अज्ञात);
                स्थिर ख: पूर्णांक = १;
                ख = २;
            }
            """),new SourceFile("b.bhash","कार्य दूसरा() -> पूर्णांक { लौटाओ \"गलत\"; }"));
        for(var target:List.of(Target.JVM,Target.CLR)) {
            var error=assertThrows(CompilationException.class,()->ManagedEmitter.emit(sources,target));
            var message=error.getMessage();assertEquals(4,message.lines().count(),message);
            for(String code:List.of("BH2001","BH3001","BH4001"))assertTrue(message.contains(code),message);
            assertFalse(message.contains("Undefined variable: क"));assertFalse(message.contains("Function must return"));
            assertTrue(message.contains("a.bhash:2:"));assertTrue(message.contains("b.bhash:1:"));
        }
    }
    @Test void recoveryRestoresNestedScopeAndControlFlowAndDoesNotAcceptInvalidPrograms() {
        var source=new SourceFile("nested.bhash","""
            कार्य मुख्य() -> शून्य {
                जबतक (सत्य) { प्रयास { स्थिर क: पूर्णांक = १; क = २; } अंततः { लिखो(अज्ञात); } विराम; }
                जारी;
            }
            वर्ग गणक { कार्य पहला() -> पूर्णांक { लौटाओ सत्य; } कार्य दूसरा() -> शून्य { लिखो(अनजान); } }
            """);
        var error=assertThrows(CompilationException.class,()->ManagedEmitter.check(List.of(source)));
        assertEquals(5,error.getMessage().lines().count(),error.getMessage());
        assertTrue(error.getMessage().contains("Loop control जारी"));
    }
    @Test void diagnosticCodesAreStableIdempotentAndBounded() {
        assertEquals("BH2002",DiagnosticMessages.code("Unknown type: अ"));
        String rendered=DiagnosticMessages.render("C:\\src\\a.bhash:1:2: Expected पूर्णांक, found पाठ.");
        assertEquals(rendered,DiagnosticMessages.render(rendered));assertTrue(rendered.contains("[BH3001]"));
        String program="कार्य मुख्य() -> शून्य {"+"लिखो(अज्ञात);".repeat(100)+"}";
        var error=assertThrows(CompilationException.class,()->ManagedEmitter.check(List.of(new SourceFile("many.bhash",program))));
        assertEquals(50,error.getMessage().lines().count());
    }
}
