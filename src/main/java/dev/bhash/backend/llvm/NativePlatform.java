// SPDX-License-Identifier: Apache-2.0
package dev.bhash.backend.llvm;
import java.util.*;
/** Native host contract. Cross compilation is never selected implicitly. */
public final class NativePlatform {
    public static final String LINUX_TARGET="x86_64-unknown-linux-gnu", WINDOWS_TARGET="x86_64-w64-windows-gnu", MACOS_TARGET="arm64-apple-macosx14.0.0", MINIMUM_MACOS="14.0";
    private NativePlatform(){}
    public static boolean windows(){return System.getProperty("os.name").startsWith("Windows");}
    public static boolean macos(){return System.getProperty("os.name").equals("Mac OS X") || System.getProperty("os.name").equals("Darwin");}
    static boolean supported(String os,String arch){return (os.startsWith("Windows") || os.equals("Linux")) && Set.of("amd64","x86_64").contains(arch) || Set.of("Mac OS X","Darwin").contains(os) && Set.of("aarch64","arm64").contains(arch);}
    public static boolean supported(){return supported(System.getProperty("os.name"),System.getProperty("os.arch"));}
    public static String target(){return macos()?MACOS_TARGET:windows()?WINDOWS_TARGET:LINUX_TARGET;}
    public static String sdkId(){return macos()?"bhash-macos-arm64-llvm18-v1":windows()?"bhash-windows-x86_64-llvm18-v1":"bhash-linux-x86_64-llvm18-v1";}
    public static String executableSuffix(){return windows()?".exe":"";}
    public static List<String> requiredFiles(){return macos()?List.of("bin/clang-18","bin/opt-18","bin/llvm-readobj","bin/dsymutil","lib/clang/18/include/stddef.h"):windows()?List.of("bin/clang.exe","bin/clang-18.exe","bin/ld.lld.exe","bin/llvm-readobj.exe","include/stdio.h","x86_64-w64-mingw32/lib/libucrt.a","lib/clang/18/include/stddef.h"):
        List.of("bin/clang-18","bin/opt-18","bin/ld.lld","bin/llvm-readelf","libexec/clang-18","libexec/opt-18","libexec/ld.lld","libexec/llvm-readelf","sysroot/usr/include/stdio.h","resource/include/stddef.h");}
}
