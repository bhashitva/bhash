package dev.bhash.backend.llvm;

import com.google.gson.Gson;
import dev.bhash.backend.CompilationException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class WindowsToolLaunchTest {
    @TempDir Path temporary;
    private static String quote(String text){return new Gson().toJson(text);}
    @Test void bypassesMingwWrapperAndKeepsCompilationInProcess()throws Exception{
        Path wrapper=temporary.resolve("clang.exe"),real=temporary.resolve("clang-18.exe");
        Files.writeString(wrapper,"");Files.writeString(real,"");
        var args=List.of(wrapper.toString(),"-c","भारत source.c","-o","program.o");
        var command=NativeToolchain.windowsClangInvocation(args);
        assertEquals(real.toString(),command.get(0));
        assertTrue(command.contains("-fintegrated-cc1"));
        assertTrue(command.contains("--target="+NativeBuildInfo.TARGET));
        assertEquals("भारत source.c",command.get(2));
        assertEquals(wrapper.toString(),args.get(0));
    }
    @Test void preservesExplicitTargetAndSystemCompiler(){
        var command=NativeToolchain.windowsClangInvocation(List.of("clang.exe","--target=x86_64-w64-windows-gnu","--version"));
        assertEquals("clang.exe",command.get(0));assertEquals(3,command.size());
    }
    @Test void readsExactlyOneQuotedLinkerJobWithoutShellInterpretation()throws Exception{
        var args=List.of("C:/SDK भारत/bin/ld.lld.exe","-o","C:\\भारत\\app.exe","","C:/space ' & %/a.o","$(never execute)");
        String line=String.join(" ",args.stream().map(WindowsToolLaunchTest::quote).toList());
        assertEquals(args,NativeToolchain.windowsLinkCommand("clang version 18.1.8\n "+line+"\n"));
    }
    @Test void rejectsUnexpectedOrMultipleJobsAndMalformedArguments(){
        for(String plan:List.of("", "\"clang-18.exe\" \"-cc1\"", "\"ld.lld\" raw", "\"ld.lld\" \"unterminated", "\"ld.lld\"\n\"ld.lld\"")){
            assertThrows(CompilationException.class,()->NativeToolchain.windowsLinkCommand(plan),plan);
        }
    }
}
