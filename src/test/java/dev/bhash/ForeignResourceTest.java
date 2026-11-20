// SPDX-License-Identifier: Apache-2.0
package dev.bhash;

import com.google.gson.*;
import dev.bhash.backend.jvm.JvmCompiler;
import dev.bhash.backend.clr.ClrCompiler;
import dev.bhash.backend.llvm.*;
import dev.bhash.frontend.SourceFile;
import dev.bhash.ffi.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.*;

class ForeignResourceTest {
    @TempDir Path directory;
    @Test void unrelatedCounterPackageSupportsScopesAliasesAndGcOnNativeAndJvm()throws Exception {
        String cc=System.getProperty("bhash.ffi.cc","");assumeTrue(!cc.isEmpty(),"Set bhash.ffi.cc to test generated resource bindings.");
        NativeToolchain.require(directory);
        Path fixtures=Path.of("src/test/ffi/counter"),core=Path.of("src/main/resources/dev/bhash/ffi").toAbsolutePath();
        var json=JsonParser.parseString(Files.readString(fixtures.resolve("bhash.ffi.json"))).getAsJsonObject();
        var module=ForeignManifest.parse(json.toString(),false).at(directory);
        Path gateway=directory.resolve("gateway.c"),jni=directory.resolve("jni.c"),object=directory.resolve("fixture.o");
        Files.writeString(gateway,ForeignCGenerator.generate(module));Files.writeString(jni,ForeignJniGenerator.generateC(module));
        String kind="-DCOUNTER_KIND="+ForeignCGenerator.resourceKind(module,"परीक्षण.गणक");
        String host=ForeignArtifacts.host(),suffix=host.startsWith("windows")?".dll":host.startsWith("macos")?".dylib":".so";
        var compile=new ArrayList<>(List.of(cc,"-std=c17","-O2","-Wall","-Wextra","-Werror",kind,"-I"+core,"-c",fixtures.resolve("fixture.c").toAbsolutePath().toString(),"-o",object.toString()));
        if(!host.startsWith("windows"))compile.add("-fPIC");run(compile,Map.of(),"fixture");
        Path archive=directory.resolve("provider.a");
        String ar=System.getProperty("bhash.ffi.ar",Path.of(cc).resolveSibling(host.startsWith("windows")?"llvm-ar.exe":"ar").toString());
        run(List.of(ar,"rcs",archive.toString(),object.toString()),Map.of("ZERO_AR_DATE","1"),"archive");
        Path library=directory.resolve("provider"+suffix),jdk=Path.of(System.getProperty("java.home"));
        var shared=new ArrayList<>(List.of(cc,"-std=c17","-O2","-Wall","-Wextra","-Werror","-DBHFFI_SHARED","-DBHFFI_GC_STRESS","-shared","-I"+core,
            "-I"+jdk.resolve("include"),"-I"+jdk.resolve("include").resolve(host.startsWith("windows")?"win32":host.startsWith("macos")?"darwin":"linux"),
            core.resolve("bhffi.c").toString(),gateway.toString(),jni.toString(),object.toString(),"-o",library.toString()));
        if(!host.startsWith("windows"))shared.addAll(List.of("-fPIC","-pthread"));run(shared,Map.of(),"shared");
        Files.writeString(directory.resolve("LICENSE"),"Apache-2.0 conformance fixture.\n");
        for(var entry:Map.of("static",archive,"shared",library).entrySet()){
            var artifact=new JsonObject();artifact.addProperty("target",host);artifact.addProperty("kind",entry.getKey());artifact.addProperty("path",entry.getValue().getFileName().toString());
            artifact.addProperty("sha256",NativeBuildInfo.fileHash(entry.getValue()));artifact.addProperty("sourceSha256",NativeBuildInfo.fileHash(fixtures.resolve("fixture.c")));artifact.addProperty("license","LICENSE");
            json.getAsJsonArray("artifacts").add(artifact);
        }
        module=ForeignManifest.parse(json.toString(),false).at(directory);
        var sources=List.of(new SourceFile(directory.resolve("bhash.ffi.json").toString(),"",true,module),new SourceFile("counter.bhash",Files.readString(fixtures.resolve("program.bhash")),true));
        String expected="५ भारत😀\n४२\n०\nबन्द\n०\n०\n";
        Path executable=directory.resolve("counter"+NativePlatform.executableSuffix());new NativeCompiler().compile(sources,executable);
        assertEquals(expected,run(List.of(executable.toString()),Map.of("BHASH_GC_STRESS","1","BHASH_GC_STATS","1"),"native").replace("\r\n","\n"));
        assertTrue(Files.readString(directory.resolve("native.err")).contains("live=0"));
        Path jar=directory.resolve("counter.jar");new JvmCompiler().compile(sources,jar);
        String java=jdk.resolve("bin").resolve(host.startsWith("windows")?"java.exe":"java").toString();
        assertEquals(expected,run(List.of(java,"-Xcheck:jni","-XX:+UseSerialGC","-Dbhash.ffi.gcStress=true","-Dbhash.ffi.cache="+directory.resolve("cache"),"-jar",jar.toString()),Map.of(),"jvm").replace("\r\n","\n"));
        Path clr=directory.resolve("clr");new ClrCompiler().compile(sources,clr);
        assertEquals(expected,run(List.of(ClrCompiler.executable(),clr.resolve("BhashProgram.dll").toString()),Map.of("BHASH_GC_STRESS","1"),"clr").replace("\r\n","\n"));
    }
    private String run(List<String> command,Map<String,String> env,String name)throws Exception{
        Path out=directory.resolve(name+".out"),err=directory.resolve(name+".err");var builder=new ProcessBuilder(command).redirectOutput(out.toFile()).redirectError(err.toFile());builder.environment().putAll(env);
        Process process=builder.start();if(!process.waitFor(120,TimeUnit.SECONDS)){process.destroyForcibly();fail("FFI resource command timed out: "+command);}
        assertEquals(0,process.exitValue(),Files.readString(err));return Files.readString(out);
    }
}
