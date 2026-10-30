// SPDX-License-Identifier: Apache-2.0
package dev.bhash.backend.llvm;
import java.util.*;
/** Native host contract. Cross compilation is never selected implicitly. */
public final class NativePlatform {
    public static final String LINUX_TARGET="x86_64-unknown-linux-gnu", WINDOWS_TARGET="x86_64-w64-windows-gnu";
    private NativePlatform(){}
    public static boolean windows(){return System.getProperty("os.name").startsWith("Windows");}
    public static boolean supported(){return (windows() || System.getProperty("os.name").equals("Linux")) && Set.of("amd64","x86_64").contains(System.getProperty("os.arch"));}
    public static String target(){return windows()?WINDOWS_TARGET:LINUX_TARGET;}
    public static String sdkId(){return windows()?"bhash-windows-x86_64-llvm18-v1":"bhash-linux-x86_64-llvm18-v1";}
    public static String executableSuffix(){return windows()?".exe":"";}
    public static List<String> requiredFiles(){return windows()?List.of("bin/clang.exe","bin/clang-18.exe","bin/ld.lld.exe","bin/llvm-readobj.exe","include/stdio.h","x86_64-w64-mingw32/lib/libucrt.a","lib/clang/18/include/stddef.h"):
        List.of("bin/clang-18","bin/opt-18","bin/ld.lld","bin/llvm-readelf","libexec/clang-18","libexec/opt-18","libexec/ld.lld","libexec/llvm-readelf","sysroot/usr/include/stdio.h","resource/include/stddef.h");}
}
