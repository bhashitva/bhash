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
}
