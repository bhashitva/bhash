// SPDX-License-Identifier: Apache-2.0
package dev.bhash.backend.llvm;
import com.google.gson.*;
import dev.bhash.backend.CompilationException;
import java.io.*;
import java.nio.*;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;

/** Static ELF inspection only: never runs the input executable or invokes ldd. */
public final class NativeCompatibility {
    public record Report(String target,String interpreter,String minimumGlibc,List<String> libraries,JsonObject buildInfo,boolean runtimeMatchesCompiler){}
    private static final Set<String> SYSTEM_LIBRARIES=Set.of("libc.so.6","libm.so.6","libgcc_s.so.1","libpthread.so.0","libdl.so.2","librt.so.1");
    private NativeCompatibility(){}
    private static CompilationException invalid(String message){return new CompilationException("[BH9106] "+message);}
    public static int compareVersions(String a,String b){
        if(!a.matches("[0-9]+(?:\\.[0-9]+){1,2}") || !b.matches("[0-9]+(?:\\.[0-9]+){1,2}"))throw new IllegalArgumentException("Expected a numeric glibc version such as 2.39");
        String[] x=a.split("\\."),y=b.split("\\.");for(int i=0;i<Math.max(x.length,y.length);i++){int cmp=new java.math.BigInteger(i<x.length?x[i]:"0").compareTo(new java.math.BigInteger(i<y.length?y[i]:"0"));if(cmp!=0)return cmp;}return 0;
    }
    public static Report parse(String elf,String metadata,boolean requireRuntimeMatch)throws IOException,CompilationException{
        if(Pattern.compile("\\((?:RPATH|RUNPATH)\\)").matcher(elf).find())throw invalid("Embedded library search paths are not supported for native distribution");
        var glibcNames=Pattern.compile("GLIBC_[A-Za-z0-9_.]+").matcher(elf);while(glibcNames.find())if(!glibcNames.group().matches("GLIBC_[0-9]+(?:\\.[0-9]+){1,2}"))throw invalid("Unsupported glibc version requirement: "+glibcNames.group());
        var libraries=new TreeSet<String>();var needed=Pattern.compile("\\(NEEDED\\).*?Shared library: \\[([^]\\r\\n]+)]").matcher(elf);while(needed.find())libraries.add(needed.group(1));
        if(!libraries.contains("libc.so.6") || !SYSTEM_LIBRARIES.containsAll(libraries))throw invalid("Unsupported native dependencies: "+libraries+". Ship ordinary glibc builds, not sanitizer/JVM/.NET binaries.");
        var interpreter=Pattern.compile("Requesting program interpreter: ([^]\\r\\n]+)]").matcher(elf);if(!interpreter.find() || !interpreter.group(1).equals("/lib64/ld-linux-x86-64.so.2"))throw invalid("Expected the Linux x86-64 glibc loader /lib64/ld-linux-x86-64.so.2");
        if(elf.contains("GLIBC_PRIVATE"))throw invalid("Private glibc symbols are not a distribution contract");
        String minimum=null;var versions=Pattern.compile("GLIBC_([0-9]+(?:\\.[0-9]+){1,2})").matcher(elf);while(versions.find()){String v=versions.group(1);if(minimum==null || compareVersions(v,minimum)>0)minimum=v;}if(minimum==null)throw invalid("Cannot determine required glibc symbol versions");
        var section=Pattern.compile("(?m)^\\s*\\[\\s*[0-9a-fA-F]+]\\s*(\\{.*})\\s*$").matcher(metadata);if(!section.find())throw invalid("Missing .bhash.build metadata; rebuild with the current native compiler");
        try{
            JsonObject info=JsonParser.parseString(section.group(1)).getAsJsonObject();
            for(String key:List.of("format","runtime_abi"))if(!info.get(key).isJsonPrimitive() || !info.get(key).getAsJsonPrimitive().isNumber() || !info.get(key).toString().matches("[0-9]+"))throw invalid("Invalid metadata integer: "+key);
            if(!info.get("sanitized").isJsonPrimitive() || !info.get("sanitized").getAsJsonPrimitive().isBoolean())throw invalid("Invalid sanitizer metadata");
            if(!info.get("format").toString().equals(Integer.toString(NativeBuildInfo.FORMAT)) || !info.get("runtime_abi").toString().equals(Integer.toString(NativeBuildInfo.ABI)) || !info.get("target").getAsString().equals(NativeBuildInfo.TARGET))throw invalid("Incompatible Bhash native metadata/ABI; rebuild with this compiler");
            if(info.get("sanitized").getAsBoolean())throw invalid("Sanitized binaries are for validation, not release distribution");
            for(String key:List.of("runtime_sha256","runtime_header_sha256"))if(!info.get(key).getAsString().matches("[0-9a-f]{64}"))throw invalid("Invalid runtime fingerprint");
            var current=NativeBuildInfo.identity();boolean match=info.get("runtime_sha256").getAsString().equals(current.get("runtime_sha256")) && info.get("runtime_header_sha256").getAsString().equals(current.get("runtime_header_sha256"));
            if(requireRuntimeMatch && !match)throw invalid("Runtime fingerprints differ from this compiler's bundled runtime");
            return new Report(NativeBuildInfo.TARGET,interpreter.group(1),minimum,List.copyOf(libraries),info,match);
        }catch(JsonParseException | IllegalStateException | UnsupportedOperationException | NullPointerException | NumberFormatException e){throw invalid("Malformed Bhash build metadata: "+e.getClass().getSimpleName());}
    }
    public static Report inspect(Path binary,boolean strict)throws IOException,CompilationException{
        Path file=binary.toAbsolutePath().normalize();byte[] bytes;try(var in=Files.newInputStream(file)){bytes=in.readNBytes(24);}
        if(bytes.length<24 || bytes[0]!=127 || bytes[1]!='E' || bytes[2]!='L' || bytes[3]!='F' || bytes[4]!=2 || bytes[5]!=1 || ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).getShort(18)!=62)throw invalid("Expected a little-endian Linux x86-64 ELF executable");
        short type=ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).getShort(16);if(type!=2 && type!=3)throw invalid("ELF input is not an executable or PIE");
        String tool=NativeToolchain.readelf();Path work=file.getParent();
        return parse(NativeToolchain.run(work,List.of(tool,"-W","-h","-l","-d","-V","--",file.toString())),NativeToolchain.run(work,List.of(tool,"-W","-p",".bhash.build","--",file.toString())),strict);
    }
}
