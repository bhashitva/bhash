// SPDX-License-Identifier: Apache-2.0
package dev.bhash;

import dev.bhash.backend.*;
import dev.bhash.frontend.SourceFile;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ForeignCapabilityTest {
    @Test void importsAndAnalysisDoNotLoadOrRequireProviderAssets()throws Exception {
        var source=List.of(new SourceFile("imports.bhash","आयात मानक.संपीड़न; फलन मुख्य() -> शून्य { लिखो(१); }"));
        ManagedEmitter.check(source);
        String browser=ManagedEmitter.emitForBrowser(source);
        assertFalse(browser.contains("BhashForeign"));
        assertFalse(browser.contains("NativeModules"));
    }
    @Test void browserRejectsForeignCallBeforeBuildingOrPublishing() {
        var source=List.of(new SourceFile("call.bhash","आयात मानक.संपीड़न; आयात मानक.संपीड़नप्रारूप; फलन मुख्य() -> शून्य { स्थिर अ=संपीड़न.संपीड़ितकरो(नया बाइटबफ़र(०), संपीड़नप्रारूप.गज़िप, ६); }"));
        var error=assertThrows(CompilationException.class,()->ManagedEmitter.emitForBrowser(source));
        assertTrue(error.getMessage().contains("BH8103"),error.getMessage());
    }
}
