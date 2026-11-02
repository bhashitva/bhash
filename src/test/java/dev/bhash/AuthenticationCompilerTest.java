// SPDX-License-Identifier: Apache-2.0
package dev.bhash;
import dev.bhash.backend.*;
import java.nio.file.*;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

abstract class AuthenticationCompilerTest {
    @TempDir Path directory;
    final Target target;
    AuthenticationCompilerTest(Target target){this.target=target;}
    private void execute(String fixture)throws Exception {
        execute(fixture,AuthenticationFixtures.source(fixture),"");
    }
    private void execute(String fixture,java.util.List<dev.bhash.frontend.SourceFile> sources,String expected)throws Exception {
        Path artifact=WebServerFixture.compile(target,directory,fixture,sources,false);
        Path output=directory.resolve("auth.out"),error=directory.resolve("auth.err");
        var child=new ProcessBuilder(WebServerFixture.command(target,artifact)).redirectOutput(output.toFile()).redirectError(error.toFile()).start();
        try{assertTrue(child.waitFor(120,TimeUnit.SECONDS),"Authentication fixture exceeded budget");assertEquals(0,child.exitValue(),Files.readString(error));assertEquals(expected,Files.readString(output).replace("\r\n","\n"));assertEquals("",Files.readString(error));}
        finally{child.destroyForcibly();}
    }
    @Test void providerVectorsPasswordBoundsAndCryptographicTokens()throws Exception {execute("crypto");}
    @Test void rotationExpiryRevocationRolesCookieCsrfAndResourceCleanup()throws Exception {execute("sessions");}
    @Test void hindiTutorialOutputsAndExamplePolicy()throws Exception {
        for(Path source:AuthenticationFixtures.tutorials()){
            String expected=Files.readString(source.resolveSibling(source.getFileName().toString().replace(".bhash",".expected.txt"))).replace("\r\n","\n");
            execute(source.getFileName().toString(),java.util.List.of(new dev.bhash.frontend.SourceFile(source.toString(),Files.readString(source))),expected);
        }
        Path policy=Path.of("examples/auth-sessions/tests/policy.test.bhash");execute("demo-policy",java.util.List.of(new dev.bhash.frontend.SourceFile(policy.toString(),Files.readString(policy))),"");
    }
    @Test void realLoginSessionCookieAuthorizationCsrfAndLogout()throws Exception {AuthenticationHttpFixture.verify(target,directory,false,java.util.Map.of());}
}
