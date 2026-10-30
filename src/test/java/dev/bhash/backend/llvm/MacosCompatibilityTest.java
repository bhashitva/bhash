// SPDX-License-Identifier: Apache-2.0
package dev.bhash.backend.llvm;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import dev.bhash.backend.CompilationException;
import java.nio.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import static org.junit.jupiter.api.Assertions.*;

class MacosCompatibilityTest {
    @TempDir Path directory;
    private byte[] executable()throws Exception{
        var info=new java.util.LinkedHashMap<String,Object>(NativeBuildInfo.identity());
        info.put("format",NativeBuildInfo.FORMAT);info.put("runtime_abi",NativeBuildInfo.ABI);
        info.put("target",NativePlatform.MACOS_TARGET);info.put("sanitized",false);
        byte[] metadata=(NativeBuildInfo.JSON.toJson(info)+"\0").getBytes(StandardCharsets.UTF_8);
        byte[] bytes=new byte[512+metadata.length];var b=ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(0,0xfeedfacf).putInt(4,0x0100000c).putInt(8,0).putInt(12,2).putInt(16,4).putInt(20,264);
        b.putInt(32,0x19).putInt(36,152).putInt(96,1);
        System.arraycopy("__bhash_build".getBytes(StandardCharsets.US_ASCII),0,bytes,104,13);
        System.arraycopy("__DATA".getBytes(StandardCharsets.US_ASCII),0,bytes,120,6);
        b.putLong(144,metadata.length).putInt(152,512);
        b.putInt(184,0x32).putInt(188,24).putInt(192,1).putInt(196,14<<16);
        b.putInt(208,0xe).putInt(212,32).putInt(216,12);
        System.arraycopy("/usr/lib/dyld".getBytes(StandardCharsets.US_ASCII),0,bytes,220,13);
        b.putInt(240,0xc).putInt(244,56).putInt(248,24);
        byte[] library="/usr/lib/libSystem.B.dylib".getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(library,0,bytes,264,library.length);System.arraycopy(metadata,0,bytes,512,metadata.length);
        return bytes;
    }
    private NativeCompatibility.Report inspect(byte[] bytes)throws Exception{
        Path file=directory.resolve("input");Files.write(file,bytes);return NativeCompatibility.inspect(file,true);
    }
    @Test void inspectsArm64OnAnyHostWithoutExecutingTools()throws Exception{
        var report=inspect(executable());assertEquals(NativePlatform.MACOS_TARGET,report.target());assertEquals("14.0.0",report.minimumMacos());assertTrue(report.runtimeMatchesCompiler());assertNull(report.minimumGlibc());
    }
    @Test void rejectsWrongArchitectureDeploymentAndDependencies()throws Exception{
        for(int[] mutation:new int[][]{{4,0x01000007},{8,2},{196,15<<16},{240,0x8000001c},{36,8}}){
            byte[] bytes=executable();ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).putInt(mutation[0],mutation[1]);
            assertThrows(CompilationException.class,()->inspect(bytes));
        }
        byte[] bytes=executable();bytes[273]='x';assertThrows(CompilationException.class,()->inspect(bytes));
    }
    @Test void rejectsTruncationAndOversizedCommands()throws Exception{
        byte[] full=executable();for(int size:new int[]{0,4,24,32,96,295,513})assertThrows(CompilationException.class,()->inspect(java.util.Arrays.copyOf(full,size)));
        ByteBuffer.wrap(full).order(ByteOrder.LITTLE_ENDIAN).putInt(20,Integer.MAX_VALUE);assertThrows(CompilationException.class,()->inspect(full));
    }
    @Test void hostArchitectureContract(){
        assertTrue(NativePlatform.supported("Mac OS X","aarch64"));assertTrue(NativePlatform.supported("Darwin","arm64"));assertFalse(NativePlatform.supported("Mac OS X","x86_64"));assertFalse(NativePlatform.supported("Linux","aarch64"));
        assertTrue(NativePackage.macosHostCheck().contains("sw_vers"));
    }
}
