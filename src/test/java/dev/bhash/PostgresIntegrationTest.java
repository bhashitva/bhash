// SPDX-License-Identifier: Apache-2.0
package dev.bhash;
import dev.bhash.backend.*;
import dev.bhash.backend.jvm.JvmCompiler;
import dev.bhash.backend.clr.ClrCompiler;
import dev.bhash.backend.llvm.NativeCompiler;
import dev.bhash.backend.llvm.LlvmEmitter;
import dev.bhash.frontend.SourceFile;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
class PostgresIntegrationTest {
    @TempDir Path temp;
    static String source()throws Exception {return Files.readString(Path.of("src/test/resources/postgresql/integration.bhash"));}
    @Test void fixtureCompilesAndPackagesVerifiedJdbc()throws Exception {
        Path jar=temp.resolve("postgres.jar");new JvmCompiler().compile("postgres.bhash",source(),jar);
        try(var zip=new java.util.zip.ZipFile(jar.toFile())) {
            assertNotNull(zip.getEntry("org/postgresql/Driver.class"));
            assertNotNull(zip.getEntry("META-INF/bhash/postgresql-LICENSE"));
            assertTrue(zip.stream().anyMatch(e->e.getName().startsWith("META-INF/licenses/")));
        }
        var core=ManagedEmitter.core(List.of(new SourceFile("postgres.bhash",source())));
        assertTrue(core.postgresql());assertTrue(LlvmEmitter.emit(core).contains("@bh_database_call"));
    }
    @Test void jvm()throws Exception {integration("jvm");}
    @Test void clr()throws Exception {integration("clr");}
    @Test void nativeForcedGc()throws Exception {integration("native");}
    void integration(String target)throws Exception {
        Assumptions.assumeTrue("true".equals(System.getenv("BHASH_REQUIRE_POSTGRES")),"PostgreSQL integration requires the dedicated CI database");
        Path output=temp.resolve(target.equals("jvm")?"app.jar":target.equals("clr")?"clr":System.getProperty("os.name").startsWith("Windows")?"app.exe":"app");
        List<String> command;
        if(target.equals("jvm")){new JvmCompiler().compile("postgres.bhash",source(),output);command=List.of(Path.of(System.getProperty("java.home"),"bin","java").toString(),"-jar",output.toString());}
        else if(target.equals("clr")){new ClrCompiler().compile("postgres.bhash",source(),output);command=List.of(ClrCompiler.executable(),output.resolve("BhashProgram.dll").toString());}
        else {new NativeCompiler().compile(List.of(new SourceFile("postgres.bhash",source())),output);command=List.of(output.toString());}
        Path stdout=temp.resolve("stdout"),stderr=temp.resolve("stderr");
        var builder=new ProcessBuilder(command).directory(temp.toFile()).redirectOutput(stdout.toFile()).redirectError(stderr.toFile());
        builder.environment().remove("JAVA_TOOL_OPTIONS");
        if(target.equals("native")){builder.environment().put("BHASH_GC_STRESS","1");builder.environment().put("BHASH_GC_STATS","1");}
        var child=builder.start();
        try {assertTrue(child.waitFor(180,TimeUnit.SECONDS),"PostgreSQL test timed out");String errors=Files.readString(stderr);assertEquals(0,child.exitValue(),errors);assertEquals("PostgreSQL ठीक\n",Files.readString(stdout).replace("\r\n","\n"));if(target.equals("native"))assertTrue(errors.contains("live=0"),errors);}
        finally {child.destroyForcibly();}
        if("verify-full".equals(System.getenv("BHASH_PG_SSLMODE"))) {
            builder.environment().put("BHASH_PG_CA",temp.resolve("missing-ca.pem").toString());
            var rejected=builder.start();
            try {assertTrue(rejected.waitFor(45,TimeUnit.SECONDS));assertNotEquals(0,rejected.exitValue());String error=Files.readString(stderr);assertTrue(error.contains("PostgreSQL त्रुटि [XXXXX]"),error);String password=System.getenv("BHASH_PG_PASSWORD");if(password!=null&&!password.isEmpty())assertFalse(error.contains(password),error);}
            finally {rejected.destroyForcibly();}
        }
    }
}
