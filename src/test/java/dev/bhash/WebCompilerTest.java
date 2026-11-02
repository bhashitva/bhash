// SPDX-License-Identifier: Apache-2.0
package dev.bhash;
import dev.bhash.backend.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
abstract class WebCompilerTest {
    @TempDir Path directory;
    final Target target;
    WebCompilerTest(Target target){this.target=target;}
    @Test void routesEncodingJsonAndValidationRunWithoutSockets()throws Exception {
        Path artifact=WebServerFixture.compile(target,directory,"pure",List.of(new dev.bhash.frontend.SourceFile("web-pure.bhash",WebCoreTest.PURE)),false);
        Path log=directory.resolve("pure.log");var child=new ProcessBuilder(WebServerFixture.command(target,artifact)).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try{assertTrue(child.waitFor(90,TimeUnit.SECONDS));assertEquals(0,child.exitValue(),Files.readString(log));assertEquals("",Files.readString(log));}finally{child.destroyForcibly();}
    }
    @Test void realHttpParserDispatchCleanupAndShutdown()throws Exception {WebServerFixture.protocol(target,directory,false,Map.of());}
    @Test void osStopSignalRunsResourceCleanup()throws Exception {WebServerFixture.signal(target,directory,false,Map.of());}
    @Test void closedServerDoesNotSwallowOsStopSignal()throws Exception {WebServerFixture.signalAfterClose(target,directory,false,Map.of());}
    @Test void shippedTaskBoardUsesTransactionsAndSurvivesRestart()throws Exception {WebServerFixture.demo(target,directory,false,Map.of());}
}
