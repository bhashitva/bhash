// SPDX-License-Identifier: Apache-2.0
package dev.bhash.backend.llvm;

import dev.bhash.backend.CompilationException;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/** Apple's SDK/linker remain a local prerequisite; they are never redistributed. */
final class MacosToolchain {
    record Tools(Path sdk,String sdkVersion,String linker,String osVersion){}
    private MacosToolchain(){}
    static Tools require(Path work)throws IOException,CompilationException{
        String os=NativeToolchain.run(work,List.of("/usr/bin/sw_vers","-productVersion")).strip();
        if(NativeCompatibility.compareVersions(os,NativePlatform.MINIMUM_MACOS)<0)
            throw new CompilationException("[BH9111] Apple Silicon native builds require macOS "+NativePlatform.MINIMUM_MACOS+" or later; found "+os);
        try{
            Path sdk=Path.of(NativeToolchain.run(work,List.of("/usr/bin/xcrun","--sdk","macosx","--show-sdk-path")).strip());
            String version=NativeToolchain.run(work,List.of("/usr/bin/xcrun","--sdk","macosx","--show-sdk-version")).strip();
            String linker=NativeToolchain.run(work,List.of("/usr/bin/xcrun","--sdk","macosx","--find","ld")).strip();
            if(!sdk.isAbsolute() || !Files.isRegularFile(sdk.resolve("usr/include/stdio.h")) || !Files.isRegularFile(Path.of(linker)))
                throw new IOException("The selected Apple SDK or linker is incomplete");
            if(!version.matches("[0-9]+(?:\\.[0-9]+){1,2}"))throw new IOException("Invalid Apple SDK version");
            return new Tools(sdk,version,linker,os);
        }catch(IOException | CompilationException error){
            throw new CompilationException("[BH9112] Install Apple's Command Line Tools (xcode-select --install), complete Apple's installation/license prompts, and select a valid macOS SDK with xcode-select. Bhash does not redistribute the Apple SDK.\n"+error.getMessage());
        }
    }
    static List<String> compilerCommand(List<String> args,Tools tools){
        var command=new ArrayList<>(args);
        command.addAll(List.of("-isysroot",tools.sdk().toString(),"-mmacosx-version-min="+NativePlatform.MINIMUM_MACOS));
        if(!command.contains("-c"))command.add("-fuse-ld="+tools.linker());
        return List.copyOf(command);
    }
    static void requireDebugInfo(Path file,String diagnostics)throws IOException,CompilationException{
        if(Files.size(file)<=256L*1024*1024){
            byte[] bytes=Files.readAllBytes(file);
            var data=java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN);
            try{
                int count=data.getInt(16),end=Math.addExact(32,data.getInt(20)),at=32;
                if(data.getInt(0)==0xfeedfacf && count>0 && count<4096 && end>=32 && end<=bytes.length){
                    for(int i=0;i<count;i++){
                        int command=data.getInt(at),length=data.getInt(at+4);
                        if(length<8 || length>end-at)break;
                        if(command==0x19 && length>=72){
                            int sections=data.getInt(at+64);
                            if(sections<0 || sections>(length-72)/80)break;
                            for(int j=0;j<sections;j++){
                                int section=at+72+j*80;long size=data.getLong(section+40),offset=Integer.toUnsignedLong(data.getInt(section+48));
                                String name=new String(bytes,section,16,java.nio.charset.StandardCharsets.US_ASCII).split("\0",2)[0];
                                if(name.equals("__debug_info") && size>0 && size<=bytes.length && offset<=bytes.length-size)return;
                            }
                        }
                        at+=length;
                    }
                }
            }catch(IndexOutOfBoundsException | ArithmeticException malformed){ /* report the tool failure below */ }
        }
        throw new CompilationException("[BH9113] dsymutil produced no usable DWARF information. Native debug objects must remain accessible until symbol generation completes.\n"+diagnostics.strip());
    }
}
