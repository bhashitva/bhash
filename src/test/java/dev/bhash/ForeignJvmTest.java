// SPDX-License-Identifier: Apache-2.0
package dev.bhash;

import com.google.gson.*;
import dev.bhash.backend.jvm.JvmCompiler;
import dev.bhash.backend.clr.ClrCompiler;
import dev.bhash.backend.llvm.NativeBuildInfo;
import dev.bhash.frontend.SourceFile;
import dev.bhash.ffi.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.*;

class ForeignJvmTest {
    @TempDir Path directory;
    @Test void packagedBhashJarRunsCompressionThroughGeneratedJniWithForcedGc() throws Exception {
        String compiler=System.getProperty("bhash.ffi.cc",""),archive=System.getProperty("bhash.ffi.archive","");
        assumeTrue(!compiler.isEmpty() && !archive.isEmpty(),"Build the pinned FFI provider and set bhash.ffi.cc/archive.");
        JsonObject json=JsonParser.parseString(resource("compression.ffi.json")).getAsJsonObject();
        var module=ForeignManifest.parse(json.toString(),true).at(directory);
        Path gateway=directory.resolve("gateway.c"),jni=directory.resolve("adapter.c");
        Files.writeString(gateway,ForeignCGenerator.generate(module));Files.writeString(jni,ForeignJniGenerator.generateC(module));
        String host=ForeignArtifacts.host(),suffix=host.startsWith("windows")?".dll":host.startsWith("macos")?".dylib":".so";
        Path library=directory.resolve("provider"+suffix),jdk=Path.of(System.getProperty("java.home"));
        Path core=Path.of("src/main/resources/dev/bhash/ffi").toAbsolutePath();
        var command=new ArrayList<>(List.of(compiler,"-std=c17","-O2","-Wall","-Wextra","-Werror","-DBHFFI_SHARED","-DBHFFI_GC_STRESS","-shared",
            "-I"+core,"-I"+jdk.resolve("include"),"-I"+jdk.resolve("include").resolve(host.startsWith("windows")?"win32":host.startsWith("macos")?"darwin":"linux"),
            core.resolve("bhffi.c").toString(),gateway.toString(),jni.toString(),archive,"-o",library.toString()));
        if(!host.startsWith("windows"))command.addAll(List.of("-fPIC","-pthread"));
        run(command,Map.of(),"build-jni");
        Files.writeString(directory.resolve("LICENSE"),resource("zlib-LICENSE.txt"));
        var artifact=new JsonObject();artifact.addProperty("target",host);artifact.addProperty("kind","shared");artifact.addProperty("path",library.getFileName().toString());
        artifact.addProperty("sha256",NativeBuildInfo.fileHash(library));artifact.addProperty("sourceSha256","02dd159783677a749c04adbdd02b55b8a2bcc7e054f51b76b549199a916ad584");artifact.addProperty("license","LICENSE");
        json.getAsJsonArray("artifacts").add(artifact);module=ForeignManifest.parse(json.toString(),true).at(directory);
        var sources=List.of(new SourceFile("main.bhash",Files.readString(Path.of("src/test/ffi/compression.bhash")),true));
        try(var catalog=ForeignCatalog.withArtifacts(module)){
        Path jar=directory.resolve("app.jar");new JvmCompiler().compile(sources,jar);
        // Runtime uses only the JDK and the packaged, hash-checked private adapter.
        String java=jdk.resolve("bin").resolve(host.startsWith("windows")?"java.exe":"java").toString();
        String output=run(List.of(java,"-Xcheck:jni","-XX:+UseSerialGC","-Dbhash.ffi.gcStress=true","-Dbhash.ffi.cache="+directory.resolve("cache"),"-jar",jar.toString()),Map.of(),"run");
        assertEquals("भारत😀 नमस्ते\nनिर्गतसीमा\nदूषितडेटा\n",output.replace("\r\n","\n"));
        Path clr=directory.resolve("clr");new ClrCompiler().compile(sources,clr);
        assertEquals("भारत😀 नमस्ते\nनिर्गतसीमा\nदूषितडेटा\n",run(List.of(ClrCompiler.executable(),clr.resolve("BhashProgram.dll").toString()),Map.of("BHASH_GC_STRESS","1"),"clr").replace("\r\n","\n"));
        var legacy=List.of(new SourceFile("legacy.bhash",Files.readString(Path.of("src/test/ffi/legacy-null.bhash"))));
        Path legacyJar=directory.resolve("legacy.jar");new JvmCompiler().compile(legacy,legacyJar);
        assertEquals("अवैधतर्क\n",run(List.of(java,"-Dbhash.ffi.cache="+directory.resolve("cache"),"-jar",legacyJar.toString()),Map.of(),"legacy-jvm").replace("\r\n","\n"));
        Path legacyClr=directory.resolve("legacy-clr");new ClrCompiler().compile(legacy,legacyClr);
        assertEquals("अवैधतर्क\n",run(List.of(ClrCompiler.executable(),legacyClr.resolve("BhashProgram.dll").toString()),Map.of("BHASH_GC_STRESS","1"),"legacy-clr").replace("\r\n","\n"));
        }
    }
    private String run(List<String> command,Map<String,String> environment,String name)throws Exception{
        Path out=directory.resolve(name+".out"),err=directory.resolve(name+".err");
        var builder=new ProcessBuilder(command).redirectOutput(out.toFile()).redirectError(err.toFile());builder.environment().putAll(environment);
        Process process=builder.start();if(!process.waitFor(120,TimeUnit.SECONDS)){process.destroyForcibly();fail("FFI process timed out: "+command);}
        assertEquals(0,process.exitValue(),Files.readString(err));return Files.readString(out);
    }
    private static String resource(String name)throws Exception{
        try(var input=ForeignJvmTest.class.getResourceAsStream("/dev/bhash/ffi/"+name)){assertNotNull(input);return new String(input.readAllBytes(),StandardCharsets.UTF_8);}
    }
}
