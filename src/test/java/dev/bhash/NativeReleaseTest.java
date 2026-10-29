// SPDX-License-Identifier: Apache-2.0
package dev.bhash;
import dev.bhash.backend.*;
import dev.bhash.backend.llvm.*;
import dev.bhash.frontend.SourceFile;
import dev.bhash.cli.Main;
import java.io.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.GZIPInputStream;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class NativeReleaseTest {
    @TempDir Path temporary;
    private static String metadata()throws Exception{return "  [     0]  "+NativeBuildInfo.describe(false,false,"18.1.8");}
    private static String elf(){return "[Requesting program interpreter: /lib64/ld-linux-x86-64.so.2]\n (NEEDED) Shared library: [libc.so.6]\n (NEEDED) Shared library: [libm.so.6]\nGLIBC_2.9 GLIBC_2.34 GLIBC_2.2.5";}
    @Test void versionChecksAndNumericOrdering()throws Exception{
        assertEquals("18.1.8",NativeToolchain.version("Ubuntu clang version 18.1.8 (vendor)"));
        assertThrows(CompilationException.class,()->NativeToolchain.version("LLVM version 19.1.8"));
        assertThrows(CompilationException.class,()->NativeToolchain.version("18.1.8"));
        assertTrue(NativeCompatibility.compareVersions("2.10","2.9")>0);
        assertEquals(0,NativeCompatibility.compareVersions("2.34","2.34.0"));
        assertThrows(IllegalArgumentException.class,()->NativeCompatibility.compareVersions("2.39;bad","2.39"));
    }
    @Test void runtimeContractMatchesHeaderAndEmittedGuard()throws Exception{
        var identity=NativeBuildInfo.identity();assertEquals(15,identity.get("runtime_abi"));
        String source="कार्य मुख्य() -> शून्य { लिखो(१); }";
        String ir=LlvmEmitter.emit(ManagedEmitter.core(List.of(new SourceFile("hello.bhash",source))));
        assertTrue(ir.contains("call void @bh_runtime_abi_v15()"));assertTrue(ir.contains("section \".bhash.build\""));assertTrue(ir.contains("@llvm.used"));
        assertEquals(identity,NativeBuildInfo.identity());
    }
    @Test void inspectionEnforcesAbiDependenciesMetadataAndFingerprints()throws Exception{
        var report=NativeCompatibility.parse(elf(),metadata(),true);assertEquals("2.34",report.minimumGlibc());assertTrue(report.runtimeMatchesCompiler());
        for(String changed:List.of(elf().replace("libm.so.6","libjvm.so"),elf().replace("ld-linux-x86-64.so.2","ld-musl-x86_64.so.1"),elf()+" GLIBC_PRIVATE",elf().replace("GLIBC_","OTHER_")))assertThrows(CompilationException.class,()->NativeCompatibility.parse(changed,metadata(),false));
        for(String changed:List.of(metadata().replace("\"runtime_abi\":15","\"runtime_abi\":14"),metadata().replace("\"runtime_abi\":15","\"runtime_abi\":15.5"),metadata().replace("\"sanitized\":false","\"sanitized\":true"),"", " [ 0] {bad}"))assertThrows(CompilationException.class,()->NativeCompatibility.parse(elf(),changed,false));
        var json=NativeBuildInfo.JSON.fromJson(NativeBuildInfo.describe(false,false,"18.1.8"),com.google.gson.JsonObject.class);json.addProperty("runtime_sha256","0".repeat(64));String altered=" [ 0] "+json;
        assertFalse(NativeCompatibility.parse(elf(),altered,false).runtimeMatchesCompiler());assertThrows(CompilationException.class,()->NativeCompatibility.parse(elf(),altered,true));
        Path bad=temporary.resolve("bad");Files.writeString(bad,"not ELF");assertThrows(CompilationException.class,()->NativeCompatibility.inspect(bad,false));
    }
    @Test void mappingRemovesWorkspacePathsAndRejectsEscapingSources()throws Exception{
        String text="कार्य मुख्य() -> शून्य { लिखो(१); }";String previous=null;
        for(String name:List.of("first space","दूसरा-स्थान")){
            Path root=Files.createDirectories(temporary.resolve(name)),file=root.resolve("hello.bhash");Files.writeString(file,text);
            var mapped=NativeCompiler.normalizeSources(List.of(new SourceFile(file.toString(),text)),root);assertEquals("/bhash/source/hello.bhash",mapped.get(0).name());
            String ir=LlvmEmitter.emit(ManagedEmitter.core(mapped),"",true);if(previous!=null)assertEquals(previous,ir);previous=ir;assertFalse(ir.contains(name));
            assertThrows(IOException.class,()->NativeCompiler.normalizeSources(List.of(new SourceFile(file.toString(),text)),temporary.resolve(name.equals("first space")?"दूसरा-स्थान":"first space")));
        }
    }
    @Test void archiveIsDeterministicPortableAndRejectsTraversal()throws Exception{
        var files=new TreeMap<String,byte[]>();files.put("bin/hello",new byte[]{1,2,3});files.put("README.txt","भारत😀".getBytes(StandardCharsets.UTF_8));
        var first=new ByteArrayOutputStream();NativePackage.writeArchive(first,"hello-1-linux-x86_64",files,1234);
        var second=new ByteArrayOutputStream();NativePackage.writeArchive(second,"hello-1-linux-x86_64",files,1234);assertArrayEquals(first.toByteArray(),second.toByteArray());
        byte[] tar;try(var zip=new GZIPInputStream(new ByteArrayInputStream(first.toByteArray()))){tar=zip.readAllBytes();}
        assertEquals("0000644",new String(tar,100,7,StandardCharsets.US_ASCII));assertEquals("0000755",new String(tar,1124,7,StandardCharsets.US_ASCII));assertEquals("00000002322",new String(tar,136,11,StandardCharsets.US_ASCII));
        files.put("../escape",new byte[0]);assertThrows(IOException.class,()->NativePackage.writeArchive(new ByteArrayOutputStream(),"root",files,0));
        assertThrows(IllegalArgumentException.class,()->NativePackage.validateName("../bad","1"));
    }
    @Test void cliRejectsInappropriateOptionsBeforeStartingTools(){
        var error=new ByteArrayOutputStream();var stream=new PrintStream(error);
        assertEquals(2,Main.run(new String[]{"native","doctor","--bad"},stream,stream));
        assertEquals(2,Main.run(new String[]{"native","package","hello.bhash"},stream,stream));
        assertEquals(2,Main.run(new String[]{"compile","hello.bhash","--source-root","."},stream,stream));
    }
}
