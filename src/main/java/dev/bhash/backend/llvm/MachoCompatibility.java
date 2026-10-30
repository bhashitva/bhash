// SPDX-License-Identifier: Apache-2.0
package dev.bhash.backend.llvm;

import com.google.gson.*;
import dev.bhash.backend.CompilationException;
import java.io.*;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Bounded static Mach-O inspection. Never loads or executes the inspected file. */
final class MachoCompatibility {
    private final byte[] bytes;
    private final ByteBuffer data;
    private MachoCompatibility(byte[] bytes){this.bytes=bytes;data=ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);}
    private static CompilationException invalid(String message){return new CompilationException("[BH9106] "+message);}
    private int number(int at)throws CompilationException{long n=Integer.toUnsignedLong(data.getInt(at));if(n>Integer.MAX_VALUE)throw invalid("Mach-O offset exceeds inspection limits");return (int)n;}
    private String name(int at,int length){return new String(bytes,at,length,StandardCharsets.US_ASCII).split("\0",2)[0];}
    private String string(int at,int end)throws CompilationException{
        if(at<0 || end>bytes.length || at>=end)throw invalid("Invalid Mach-O string bounds");
        int stop=at;while(stop<end && bytes[stop]!=0)stop++;
        if(stop==end)throw invalid("Unterminated Mach-O string");
        return new String(bytes,at,stop-at,StandardCharsets.UTF_8);
    }
    private static String version(int n){return (n>>>16)+"."+((n>>>8)&255)+"."+(n&255);}
    static NativeCompatibility.Report inspect(Path file,boolean strict)throws IOException,CompilationException{
        if(Files.size(file)>256L*1024*1024)throw invalid("Mach-O executable exceeds inspection limit");
        try{return new MachoCompatibility(Files.readAllBytes(file)).inspect(strict);}
        catch(IndexOutOfBoundsException | IllegalStateException | NullPointerException | JsonParseException | UnsupportedOperationException e){throw invalid("Malformed Mach-O executable or Bhash metadata");}
    }
    private NativeCompatibility.Report inspect(boolean strict)throws IOException,CompilationException{
        if(data.getInt(0)!=0xfeedfacf || data.getInt(4)!=0x0100000c || data.getInt(8)!=0 || data.getInt(12)!=2)throw invalid("Expected a thin macOS ARM64 executable (not arm64e, Intel, universal, or a library)");
        int count=number(16),size=number(20);if(count<1 || count>4096 || size>bytes.length-32)throw invalid("Invalid Mach-O load commands");
        int at=32,end=32+size;String metadata=null,minimum=null,loader=null;var libraries=new TreeSet<String>();
        for(int i=0;i<count;i++){
            if(at>end-8)throw invalid("Truncated Mach-O load commands");
            int command=data.getInt(at),length=number(at+4);if(length<8 || length%8!=0 || length>end-at)throw invalid("Invalid Mach-O load command size");
            if(command==0x19){
                if(length<72)throw invalid("Truncated Mach-O segment");int sections=number(at+64);if(sections>(length-72)/80)throw invalid("Invalid Mach-O sections");
                for(int j=0;j<sections;j++){
                    int s=at+72+j*80;
                    if(name(s,16).equals("__bhash_build") && name(s+16,16).equals("__DATA")){
                        long n=data.getLong(s+40);int offset=number(s+48);
                        if(metadata!=null || n<2 || n>8*1024*1024 || offset+(long)n>bytes.length)throw invalid("Duplicate/oversized Mach-O Bhash metadata");
                        metadata=string(offset,offset+(int)n);
                    }
                }
            }else if(command==0x32){
                if(length<24 || data.getInt(at+8)!=1 || minimum!=null)throw invalid("Expected one macOS build-version command");minimum=version(data.getInt(at+12));
            }else if(command==0xe || command==0xc || command==0x80000018 || command==0x8000001f || command==0x80000023){
                int header=command==0xe?12:24;if(length<header)throw invalid("Truncated Mach-O dependency");int offset=number(at+8);if(offset<header || offset>=length)throw invalid("Invalid Mach-O dependency name");
                String value=string(at+offset,at+length);
                if(command==0xe){if(loader!=null)throw invalid("Duplicate Mach-O loader");loader=value;}else libraries.add(value);
            }else if(command==0x8000001c)throw invalid("Embedded Mach-O library search paths are not supported for distribution");
            at+=length;
        }
        if(at!=end || metadata==null || minimum==null || !"/usr/lib/dyld".equals(loader) || !libraries.equals(new TreeSet<>(Set.of("/usr/lib/libSystem.B.dylib"))))throw invalid("Expected macOS build metadata and only the system libSystem dependency");
        if(NativeCompatibility.compareVersions(minimum,NativePlatform.MINIMUM_MACOS)!=0)throw invalid("Unexpected macOS deployment minimum: "+minimum);
        JsonObject info=JsonParser.parseString(metadata).getAsJsonObject();
        if(!Integer.toString(NativeBuildInfo.FORMAT).equals(info.get("format").toString()) || !Integer.toString(NativeBuildInfo.ABI).equals(info.get("runtime_abi").toString()) || !NativePlatform.MACOS_TARGET.equals(info.get("target").getAsString()))throw invalid("Incompatible Bhash macOS metadata/ABI");
        if(!info.get("sanitized").isJsonPrimitive() || !info.get("sanitized").getAsJsonPrimitive().isBoolean() || info.get("sanitized").getAsBoolean())throw invalid("Invalid/sanitized macOS release metadata");
        for(String key:List.of("runtime_sha256","runtime_header_sha256"))if(!info.get(key).getAsString().matches("[0-9a-f]{64}"))throw invalid("Invalid runtime fingerprint");
        var current=NativeBuildInfo.identity();boolean match=info.get("runtime_sha256").getAsString().equals(current.get("runtime_sha256")) && info.get("runtime_header_sha256").getAsString().equals(current.get("runtime_header_sha256"));
        if(strict && !match)throw invalid("Runtime fingerprints differ from this compiler's bundled runtime");
        return new NativeCompatibility.Report(NativePlatform.MACOS_TARGET,loader,null,List.copyOf(libraries),info,match,minimum);
    }
}
