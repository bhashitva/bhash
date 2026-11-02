// SPDX-License-Identifier: Apache-2.0
package dev.bhash;

import dev.bhash.backend.*;
import dev.bhash.backend.jvm.JvmCompiler;
import dev.bhash.backend.clr.ClrCompiler;
import dev.bhash.frontend.SourceFile;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

abstract class NetworkCompilerTest {
    @TempDir Path directory;
    final Target target;
    NetworkCompilerTest(Target target){this.target=target;}
    private void execute(String source)throws Exception {execute(source,false);}
    private void execute(String source,boolean trusted)throws Exception {
        Path artifact=directory.resolve(target==Target.JVM?"network.jar":"network-clr");
        var sources=List.of(new SourceFile("network-test.bhash",source));
        if(target==Target.JVM)new JvmCompiler().compile(sources,artifact);else new ClrCompiler().compile(sources,artifact);
        var command=new ArrayList<String>();
        if(target==Target.JVM){
            command.add(Path.of(System.getProperty("java.home"),"bin","java").toString());
            if(trusted){command.add("-Djavax.net.ssl.trustStore="+NetworkFixture.certificate("trust.p12"));command.add("-Djavax.net.ssl.trustStorePassword=bhash-http-test");}
            command.add("-jar");command.add(artifact.toString());
        }else {command.add(ClrCompiler.executable());command.add(artifact.resolve("BhashProgram.dll").toString());}
        Path log=directory.resolve("network.log");var builder=new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log.toFile());
        builder.environment().put("http_proxy","http://127.0.0.1:1");builder.environment().put("HTTP_PROXY","http://127.0.0.1:1");
        if(trusted)builder.environment().put("SSL_CERT_FILE",NetworkFixture.certificate("ca.pem").toString());
        var child=builder.start();
        try {assertTrue(child.waitFor(60,TimeUnit.SECONDS),"Network test exceeded its process budget");String output=Files.readString(log);assertEquals(0,child.exitValue(),output);assertEquals("",output);}
        finally {child.destroyForcibly();}
    }
    @Test void portableUrlsHeadersAndEncoding()throws Exception {execute(NetworkCoreTest.PURE);}
    @Test void httpRequestsResponsesRedirectsAndOpaqueHandleCleanup()throws Exception {
        try(var fixture=new NetworkFixture()){execute(fixture.functional());assertEquals(0,fixture.replayedBodies.get());}
    }
    @Test void httpTimeoutsLimitsAndFailuresLeaveClientUsable()throws Exception {
        try(var fixture=new NetworkFixture()){execute(fixture.failures());assertEquals(0,fixture.replayedBodies.get());}
    }
    @Test void untrustedHttpsCertificatesAreRejected()throws Exception {try(var fixture=new NetworkFixture()){execute(fixture.untrustedTls());}}
    @Test void trustedHttpsStillChecksNamesExpiryAndDowngrade()throws Exception {
        // Windows/macOS use their OS trust stores. The native Windows CI container
        // installs only the public fixture CA; never mutate a developer's store.
        org.junit.jupiter.api.Assumptions.assumeTrue(target==Target.JVM||System.getProperty("os.name").startsWith("Linux")||"true".equals(System.getenv("BHASH_HTTP_WINDOWS_TRUSTED")));
        try(var fixture=new NetworkFixture()){execute(fixture.trustedTls(),true);}
    }
}
