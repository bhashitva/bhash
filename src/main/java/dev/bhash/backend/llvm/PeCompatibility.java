// SPDX-License-Identifier: Apache-2.0
package dev.bhash.backend.llvm;
import com.google.gson.*;
import dev.bhash.backend.CompilationException;
import java.io.*;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Bounded, nonexecuting PE32+ inspection, independent of the host's installed tools. */
final class PeCompatibility {
    private final byte[] bytes;
    private final ByteBuffer data;
    private final List<int[]> sections=new ArrayList<>();
    private PeCompatibility(byte[] bytes){this.bytes=bytes;data=ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);}
    private static CompilationException invalid(String message){return new CompilationException("[BH9106] "+message);}
    private int u16(int at){return Short.toUnsignedInt(data.getShort(at));}
    private int u32(int at)throws CompilationException{long n=Integer.toUnsignedLong(data.getInt(at));if(n>Integer.MAX_VALUE)throw invalid("PE offset exceeds inspection limits");return (int)n;}
    private int offset(int rva)throws CompilationException{
        for(int[] s:sections)if(rva>=s[0] && (long)rva<s[0]+(long)s[1]){long at=s[2]+(long)rva-s[0];if(at>=bytes.length)break;return (int)at;}
        throw invalid("PE RVA outside file-backed sections");
    }
    private String string(int at,int maximum)throws CompilationException{
        int end=at;while(end<bytes.length && end-at<maximum && bytes[end]!=0)++end;
        if(end==bytes.length || end-at==maximum)throw invalid("Unterminated PE string");
        return new String(bytes,at,end-at,StandardCharsets.UTF_8);
    }
    static NativeCompatibility.Report inspect(Path file,boolean strict)throws IOException,CompilationException{
        if(Files.size(file)>256L*1024*1024)throw invalid("PE executable exceeds inspection limit");
        try{return new PeCompatibility(Files.readAllBytes(file)).inspect(strict);}
        catch(IndexOutOfBoundsException | IllegalStateException | NullPointerException | JsonParseException | UnsupportedOperationException e){throw invalid("Malformed PE executable or Bhash metadata");}
    }
    private NativeCompatibility.Report inspect(boolean strict)throws IOException,CompilationException{
        int pe=u32(0x3c);if(data.getInt(pe)!=0x00004550 || u16(pe+4)!=0x8664 || (u16(pe+22)&0x2002)!=2)throw invalid("Expected Windows x64 PE executable, not a DLL");
        int count=u16(pe+6),optional=pe+24,size=u16(pe+20);
        if(count<1 || count>96 || size<240 || u16(optional)!=0x20b || u16(optional+68)!=3 || u32(optional+108)<14)throw invalid("Expected a PE32+ console executable");
        String metadata=null;
        for(int i=0;i<count;i++){
            int at=optional+size+i*40,raw=u32(at+20),length=u32(at+16);
            if((long)raw+length>bytes.length)throw invalid("PE section extends beyond input");
            sections.add(new int[]{u32(at+12),length,raw});
            if(new String(bytes,at,8,StandardCharsets.US_ASCII).replace("\0","").equals(".bhash")){
                if(metadata!=null || length>8*1024*1024)throw invalid("Duplicate/oversized Bhash metadata");
                metadata=string(raw,length);
            }
        }
        if(metadata==null)throw invalid("Missing .bhash metadata; rebuild with this compiler");
        if(u32(optional+112+13*8+4)!=0)throw invalid("Delayed imports are not supported for native distribution");
        int imports=u32(optional+112+8),importSize=u32(optional+112+12);
        if(imports==0 || importSize<20)throw invalid("Missing Windows system imports");
        var libraries=new TreeSet<String>();int table=offset(imports);boolean ended=false;
        for(int i=0;i<Math.min(importSize/20,4096);i++){
            int at=table+i*20,name=u32(at+12);
            if(name==0){ended=true;break;}
            String dll=string(offset(name),256).toLowerCase(Locale.ROOT);
            if(!dll.equals("kernel32.dll") && !dll.equals("ucrtbase.dll") && !dll.matches("api-ms-win-crt-[a-z0-9-]+\\.dll"))throw invalid("Unsupported Windows dependency: "+dll+". Distribution must not require LLVM, MinGW DLLs, JVM or .NET.");
            libraries.add(dll);
        }
        if(!ended || libraries.stream().noneMatch(x->x.equals("ucrtbase.dll") || x.startsWith("api-ms-win-crt-")))throw invalid("Expected bounded Windows UCRT imports");
        JsonObject info=JsonParser.parseString(metadata).getAsJsonObject();
        if(!Integer.toString(NativeBuildInfo.FORMAT).equals(info.get("format").toString()) || !Integer.toString(NativeBuildInfo.ABI).equals(info.get("runtime_abi").toString()) || !NativePlatform.WINDOWS_TARGET.equals(info.get("target").getAsString()))throw invalid("Incompatible Bhash Windows metadata/ABI");
        if(!info.get("sanitized").isJsonPrimitive() || !info.get("sanitized").getAsJsonPrimitive().isBoolean() || info.get("sanitized").getAsBoolean())throw invalid("Invalid/sanitized Windows release metadata");
        for(String key:List.of("runtime_sha256","runtime_header_sha256"))if(!info.get(key).getAsString().matches("[0-9a-f]{64}"))throw invalid("Invalid runtime fingerprint");
        var current=NativeBuildInfo.identity();boolean match=info.get("runtime_sha256").getAsString().equals(current.get("runtime_sha256")) && info.get("runtime_header_sha256").getAsString().equals(current.get("runtime_header_sha256"));
        if(strict && !match)throw invalid("Runtime fingerprints differ from this compiler's bundled runtime");
        return new NativeCompatibility.Report(NativePlatform.WINDOWS_TARGET,"Windows 10+ x64 / UCRT",null,List.copyOf(libraries),info,match);
    }
}
