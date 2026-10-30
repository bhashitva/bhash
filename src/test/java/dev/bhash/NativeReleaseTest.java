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
    private static String metadata()throws Exception{return "  [     0]  "+NativeBuildInfo.describe(false,false,"18.1.8").replace(NativeBuildInfo.TARGET,NativePlatform.LINUX_TARGET);}
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
        assertTrue(ir.contains("call void @bh_runtime_abi_v15()"));assertTrue(ir.contains("section \""+(NativePlatform.macos()?"__DATA,__bhash_build":NativePlatform.windows()?".bhash":".bhash.build")+"\""));assertTrue(ir.contains("@llvm.used"));
        assertEquals(identity,NativeBuildInfo.identity());
    }
    @Test void inspectionEnforcesAbiDependenciesMetadataAndFingerprints()throws Exception{
        var report=NativeCompatibility.parse(elf(),metadata(),true);assertEquals("2.34",report.minimumGlibc());assertTrue(report.runtimeMatchesCompiler());
        for(String changed:List.of(elf().replace("libm.so.6","libjvm.so"),elf().replace("ld-linux-x86-64.so.2","ld-musl-x86_64.so.1"),elf()+" GLIBC_PRIVATE",elf()+" GLIBC_ABI_DT_RELR",elf()+" (RUNPATH) Library runpath: [/tmp]",elf().replace("GLIBC_","OTHER_")))assertThrows(CompilationException.class,()->NativeCompatibility.parse(changed,metadata(),false));
        for(String changed:List.of(metadata().replace("\"runtime_abi\":15","\"runtime_abi\":14"),metadata().replace("\"runtime_abi\":15","\"runtime_abi\":15.5"),metadata().replace("\"sanitized\":false","\"sanitized\":true"),"", " [ 0] {bad}"))assertThrows(CompilationException.class,()->NativeCompatibility.parse(elf(),changed,false));
        var json=NativeBuildInfo.JSON.fromJson(NativeBuildInfo.describe(false,false,"18.1.8"),com.google.gson.JsonObject.class);json.addProperty("runtime_sha256","0".repeat(64));json.addProperty("target",NativePlatform.LINUX_TARGET);String altered=" [ 0] "+json;
        assertFalse(NativeCompatibility.parse(elf(),altered,false).runtimeMatchesCompiler());assertThrows(CompilationException.class,()->NativeCompatibility.parse(elf(),altered,true));
        Path bad=temporary.resolve("bad");Files.writeString(bad,"not ELF");assertThrows(CompilationException.class,()->NativeCompatibility.inspect(bad,false));
    }
    private byte[] windowsPe(String dependency)throws Exception{
        byte[] bytes=new byte[8192];var b=java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        bytes[0]='M';bytes[1]='Z';b.putInt(0x3c,0x80);b.putInt(0x80,0x4550);b.putShort(0x84,(short)0x8664);b.putShort(0x86,(short)2);b.putShort(0x94,(short)240);b.putShort(0x96,(short)2);
        int opt=0x98;b.putShort(opt,(short)0x20b);b.putShort(opt+68,(short)3);b.putInt(opt+108,16);b.putInt(opt+120,4096);b.putInt(opt+124,60);
        int section=opt+240;System.arraycopy(".rdata".getBytes(StandardCharsets.US_ASCII),0,bytes,section,6);b.putInt(section+12,4096);b.putInt(section+16,1024);b.putInt(section+20,512);
        section+=40;System.arraycopy(".bhash".getBytes(StandardCharsets.US_ASCII),0,bytes,section,6);b.putInt(section+12,8192);b.putInt(section+16,4096);b.putInt(section+20,1536);
        b.putInt(512+12,4096+64);b.putInt(512+20+12,4096+128);
        byte[] dll=(dependency+"\0").getBytes(StandardCharsets.US_ASCII);System.arraycopy(dll,0,bytes,576,dll.length);
        byte[] ucrt="ucrtbase.dll\0".getBytes(StandardCharsets.US_ASCII);System.arraycopy(ucrt,0,bytes,640,ucrt.length);
        byte[] metadata=NativeBuildInfo.describe(false,false,"18.1.8").replace(NativeBuildInfo.TARGET,NativePlatform.WINDOWS_TARGET).getBytes(StandardCharsets.UTF_8);System.arraycopy(metadata,0,bytes,1536,metadata.length);return bytes;
    }
    @Test void peInspectionIsStaticAndRejectsForeignDependenciesAndMalformedInputs()throws Exception{
        Path file=temporary.resolve("fixture.exe");Files.write(file,windowsPe("KERNEL32.dll"));
        var report=NativeCompatibility.inspect(file,true);assertEquals(NativePlatform.WINDOWS_TARGET,report.target());assertNull(report.minimumGlibc());assertTrue(report.runtimeMatchesCompiler());
        for(String dll:List.of("libjvm.dll","coreclr.dll","libLLVM-18.dll","libwinpthread-1.dll")){Files.write(file,windowsPe(dll));assertThrows(CompilationException.class,()->NativeCompatibility.inspect(file,false));}
        byte[] bytes=windowsPe("KERNEL32.dll");bytes[0x84]=0;Files.write(file,bytes);assertThrows(CompilationException.class,()->NativeCompatibility.inspect(file,false));
        Files.write(file,Arrays.copyOf(windowsPe("KERNEL32.dll"),100));assertThrows(CompilationException.class,()->NativeCompatibility.inspect(file,false));
    }
    @Test void windowsZipIsDeterministicAndRejectsTraversal()throws Exception{
        var files=new TreeMap<String,byte[]>();files.put("bin/hello.exe",new byte[]{1,2,3});
        var a=new ByteArrayOutputStream();var b=new ByteArrayOutputStream();NativePackage.writeZip(a,"hello-1-windows-x86_64",files,0);NativePackage.writeZip(b,"hello-1-windows-x86_64",files,0);assertArrayEquals(a.toByteArray(),b.toByteArray());
        files.put("../escape",new byte[0]);assertThrows(IOException.class,()->NativePackage.writeZip(new ByteArrayOutputStream(),"root",files,0));
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
        assertThrows(IOException.class,()->NativePackage.build(List.of(),temporary,temporary.getRoot(),"hello","1",false,"2.39"));
    }
    @Test void cliRejectsInappropriateOptionsBeforeStartingTools(){
        var error=new ByteArrayOutputStream();var stream=new PrintStream(error);
        assertEquals(2,Main.run(new String[]{"native","doctor","--bad"},stream,stream));
        if(!NativePlatform.supported()){assertEquals(1,Main.run(new String[]{"native","doctor"},stream,stream));assertTrue(error.toString().contains("BH9101"));}
        assertEquals(2,Main.run(new String[]{"native","package","hello.bhash"},stream,stream));
        assertEquals(2,Main.run(new String[]{"compile","hello.bhash","--source-root","."},stream,stream));
    }
}
