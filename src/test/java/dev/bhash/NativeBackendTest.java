// SPDX-License-Identifier: Apache-2.0
package dev.bhash;

import dev.bhash.backend.*;
import dev.bhash.backend.CoreProgram.*;
import dev.bhash.backend.llvm.*;
import dev.bhash.frontend.SourceFile;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.*;

class NativeBackendTest {
    @TempDir Path temporary;
    private static CoreProgram core(String s)throws Exception{return ManagedEmitter.core(List.of(new SourceFile("native.bhash",s)));}
    @Test void corePreservesCheckedOverloadsShadowingAndDecodedLiterals()throws Exception{
        var program=core("""
            कार्य पहचान(अ: पूर्णांक) -> पूर्णांक { लौटाओ अ; }
            कार्य पहचान(अ: पाठ) -> पाठ { लौटाओ अ; }
            कार्य मुख्य() -> शून्य {
                मान अ: पूर्णांक = -९२२३३७२०३६८५४७७५८०८;
                { स्थिर अ: पाठ = पहचान("a\\u0000😀"); लिखो(अ); }
                लिखो(पहचान(अ));
            }
            """);
        assertEquals(3,program.functions().size());
        var main=program.functions().stream().filter(f->f.id()==program.entry()).findFirst().orElseThrow();
        var first=(Assign)main.body().statements().get(0);
        assertEquals(Long.MIN_VALUE,((Constant)first.value()).value());
        var nested=(Block)main.body().statements().get(1);
        var local=(Assign)nested.statements().get(0);
        assertNotEquals(first.binding().id(),local.binding().id());
        var call=(Call)local.value();assertEquals(Type.TEXT,call.type());
        assertEquals("a\0😀",((Constant)call.arguments().get(0)).value());
    }
    @Test void coreUsesExistingTypeAndImmutableBindingChecks(){
        assertThrows(CompilationException.class,()->core("कार्य मुख्य() -> शून्य { स्थिर अ: पूर्णांक = १; अ = २; }"));
        assertThrows(CompilationException.class,()->core("कार्य मुख्य() -> शून्य { स्थिर अ: पूर्णांक = सत्य; }"));
        assertThrows(CompilationException.class,()->core("कार्य मुख्य() -> शून्य { लिखो(अ); }"));
    }
    @Test void unsupportedUnusedDeclarationsAndLibraryCallsHaveSourceLocations(){
        for(String source:List.of(
            "वर्ग अ {} कार्य मुख्य() -> शून्य {}",
            "कार्य अप्रयुक्त() -> दशमलव { लौटाओ १.२; } कार्य मुख्य() -> शून्य {}",
            "कार्य मुख्य() -> शून्य { लिखो(लंबाई(\"अ\")); }",
            "कार्य मुख्य() -> शून्य { लिखो(\"अ\" + \"ब\"); }",
            "कार्य मुख्य() -> शून्य { लिखो(१ << २); }",
            "कार्य मुख्य() -> शून्य { लिखो(परिवर्तित<पूर्णांक>(१)); }")){
            var error=assertThrows(CompilationException.class,()->core(source));
            assertTrue(error.getMessage().contains("native.bhash:1:"),error.getMessage());
            assertTrue(error.getMessage().contains("BH9001"),error.getMessage());
        }
    }
    @Test void crossFileVisibilityAndCallsUseExistingResolution()throws Exception{
        var p=ManagedEmitter.core(List.of(
            new SourceFile("library.bhash","संकुल गणना; सार्वजनिक कार्य जोड़(अ: पूर्णांक) -> पूर्णांक { लौटाओ अ + १; }"),
            new SourceFile("app.bhash","आयात गणना.जोड़; कार्य मुख्य() -> शून्य { लिखो(जोड़(२)); }")));
        assertEquals(2,p.functions().size());
        assertTrue(p.functions().stream().anyMatch(f->f.source().file().equals("library.bhash")));
    }
    private void requireTools()throws Exception{
        boolean required=Boolean.parseBoolean(System.getenv("BHASH_REQUIRE_NATIVE"));
        boolean supported=System.getProperty("os.name").equals("Linux") && Set.of("amd64","x86_64").contains(System.getProperty("os.arch"));
        if(required)assertTrue(supported,"Native CI requires Linux x86-64");else assumeTrue(supported);
        for(String tool:List.of(System.getenv().getOrDefault("BHASH_CLANG","clang-18"),System.getenv().getOrDefault("BHASH_LLVM_OPT","opt-18"))){
            boolean available;
            try{Process p=new ProcessBuilder(tool,"--version").redirectErrorStream(true).start();p.getInputStream().readAllBytes();available=p.waitFor(10,TimeUnit.SECONDS)&&p.exitValue()==0;}
            catch(java.io.IOException e){available=false;}
            if(required)assertTrue(available,"Missing "+tool);else assumeTrue(available,"Missing "+tool);
        }
    }
    private record Result(int exit,String out,String err){}
    private Result nativeRun(String source,String...arguments)throws Exception{
        Path exe=temporary.resolve("application");
        new NativeCompiler().compile(List.of(new SourceFile("native.bhash",source)),exe);
        var command=new ArrayList<String>();command.add(exe.toString());command.addAll(List.of(arguments));
        Path out=temporary.resolve("out"),err=temporary.resolve("err");
        var p=new ProcessBuilder(command).redirectOutput(out.toFile()).redirectError(err.toFile()).start();
        assertTrue(p.waitFor(15,TimeUnit.SECONDS));
        return new Result(p.exitValue(),Files.readString(out),Files.readString(err));
    }
    @Test void llvmExecutesFunctionsControlFlowUnicodeAndBoundaries()throws Exception{
        requireTools();
        Path artifact=Path.of("target/native-tests/factorial.ll");
        Files.createDirectories(artifact.getParent());
        Files.writeString(artifact,LlvmEmitter.emit(core(Files.readString(Path.of("examples/factorial.bhash")))),StandardCharsets.UTF_8);
        var verifier=new ProcessBuilder(System.getenv().getOrDefault("BHASH_LLVM_OPT","opt-18"),
            "-passes=verify","-disable-output",artifact.toString()).inheritIO().start();
        assertTrue(verifier.waitFor(15,TimeUnit.SECONDS));assertEquals(0,verifier.exitValue());
        var result=nativeRun("""
            कार्य गुणनफल(न: पूर्णांक) -> पूर्णांक {
                यदि (न <= १) { लौटाओ १; } अन्यथा { लौटाओ न * गुणनफल(न - १); }
            }
            कार्य मुख्य() -> शून्य {
                लिखो("नमस्ते, दुनिया!", "a\\u0000😀" == "a\\u0000😀");
                लिखो(गुणनफल(५), ९२२३३७२०३६८५४७७५८०७ + १, -९२२३३७२०३६८५४७७५८०८ / -१);
                लिखो(-७ / ३, -७ % ३);
                मान न: पूर्णांक = ०;
                जबतक (न < ५) {
                    न = न + १;
                    यदि (न == २) { जारी; }
                    यदि (न == ४) { विराम; }
                    लिखो(न);
                }
                लिखो(असत्य && (१ / ० == ०), सत्य || (१ / ० == ०));
            }
            """);
        assertEquals(0,result.exit());assertEquals("",result.err());
        assertEquals("नमस्ते, दुनिया! सत्य\n१२० -९२२३३७२०३६८५४७७५८०८ -९२२३३७२०३६८५४७७५८०८\n-२ -१\n१\n३\nअसत्य सत्य\n",result.out());
    }
    @Test void failureHasSourceLocationAndDoesNotPrintPartialArguments()throws Exception{
        requireTools();
        var result=nativeRun("कार्य मुख्य() -> शून्य { लिखो(\"अधूरा\", १ / ०); }");
        assertEquals(1,result.exit());assertEquals("",result.out());
        assertTrue(result.err().contains("native.bhash:1:"));
    }
    @Test void nativeExecutableIncludesAccessibleLicenseNotices()throws Exception{
        requireTools();
        var result=nativeRun("कार्य मुख्य() -> शून्य { लिखो(\"नहीं\"); }","--bhash-license");
        assertEquals(0,result.exit());assertTrue(result.out().contains("Apache License"));
        assertFalse(result.out().contains("नहीं"));
    }
    @Test void invalidSourcePreservesPreviousExecutable()throws Exception{
        Path output=temporary.resolve("previous");Files.writeString(output,"previous executable");
        assertThrows(CompilationException.class,()->new NativeCompiler().compile(
            List.of(new SourceFile("bad.bhash","कार्य मुख्य() -> शून्य { लिखो(अ); }")),output));
        assertEquals("previous executable",Files.readString(output));
    }
}
