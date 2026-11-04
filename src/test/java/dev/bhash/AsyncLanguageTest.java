// SPDX-License-Identifier: Apache-2.0
package dev.bhash;
import dev.bhash.backend.*;
import dev.bhash.frontend.SourceFile;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class AsyncLanguageTest {
    static final String BASIC="""
        असमकालिक फलन बढ़ाओ(अ:पूर्णांक)->पूर्णांक {
            प्रतीक्षा मानक.कार्य.छोड़ो(); लौटाओ अ+१;
        }
        असमकालिक फलन मुख्य()->शून्य {
            स्थिर क:कार्यफल<पूर्णांक> = बढ़ाओ(४१);
            लिखो("पहले"); लिखो(प्रतीक्षा क);
        }
        """;
    @Test void checkedAsyncSourceLowersForAllBackends()throws Exception {
        for(var fixture:AsyncFixtures.cases()) {
        var sources=List.of(new SourceFile(fixture.name()+".bhash",fixture.source()));
        assertFalse(ManagedEmitter.emit(sources,Target.JVM).contains("async_pending"));
        assertFalse(ManagedEmitter.emit(sources,Target.CLR).contains("async_pending"));
        assertFalse(ManagedEmitter.core(sources).functions().isEmpty());
        }
    }
    @Test void invalidAsyncContextsAndIgnoredTasksAreRejected() {
        for(String source:List.of(
            "फलन मुख्य()->शून्य { प्रतीक्षा १; }",
            "असमकालिक फलन मुख्य()->शून्य { प्रतीक्षा १; }",
            "असमकालिक फलन मुख्य()->शून्य { मानक.कार्य.छोड़ो(); }",
            "वर्ग क { मान अ:पूर्णांक=प्रतीक्षा १; } फलन मुख्य()->शून्य {}",
            "वर्ग क { निर्माता(){प्रतीक्षा १;} } फलन मुख्य()->शून्य {}",
            "असमकालिक फलन मुख्य()->शून्य { प्रयास{}अंततः{लौटाओ;} }",
            "फलन मुख्य()->शून्य { स्थिर क:सूची<शून्य> = नया सूची<शून्य>(); }"))
            assertThrows(CompilationException.class,()->ManagedEmitter.check(List.of(new SourceFile("invalid-async.bhash",source))),source);
    }
    @Test void taskUnitImportAliasAndTutorialCompile()throws Exception {
        ManagedEmitter.check(List.of(new SourceFile("alias.bhash","आयात मानक.कार्यफल उपनाम परिणाम; असमकालिक फलन मुख्य()->शून्य {स्थिर क:परिणाम<शून्य> = मानक.कार्य.छोड़ो(); प्रतीक्षा क;}")));
        var path=java.nio.file.Path.of("tutorial/async/tasks.bhash");
        ManagedEmitter.core(List.of(new SourceFile(path.toString(),java.nio.file.Files.readString(path))));
    }
}
