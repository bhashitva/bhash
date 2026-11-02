// SPDX-License-Identifier: Apache-2.0
package dev.bhash;

import dev.bhash.backend.clr.ClrCompiler;
import org.junit.jupiter.api.condition.EnabledIf;
import dev.bhash.frontend.SourceFile;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.jar.JarFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIf("dotnetAvailable")
class ClrSqliteTest {
    static boolean dotnetAvailable(){return ClrCompilerTest.dotnetAvailable();}
    @TempDir Path directory;
    private void execute(String source,boolean database)throws Exception {execute(source,database,"");}
    private void execute(String source,boolean database,String expected)throws Exception {
        Path output=directory.resolve("program"),log=directory.resolve("program.log");
        new ClrCompiler().compile(List.of(new SourceFile("sqlite-test.bhash",source)),output);
        assertEquals(database,Files.exists(output.resolve("bhash_sqlite.dll")));
        var command=List.of(ClrCompiler.executable(),output.resolve("BhashProgram.dll").toString());
        var process=new ProcessBuilder(command).directory(directory.toFile()).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try{assertTrue(process.waitFor(90,TimeUnit.SECONDS));String text=Files.readString(log);assertEquals(0,process.exitValue(),text);assertEquals(expected,text.replace("\r\n","\n"));}
        finally{process.destroyForcibly();}
    }
    @Test void sqliteTypesBindingsAndStatementsUseBundledRuntime()throws Exception {execute(SqliteFixtures.BASIC,true);}
    @Test void portableSqlPolicyRequiresNoDatabaseRuntime()throws Exception {execute(SqliteFixtures.PURE,false);}
    @Test void sqliteTransactionsPersistenceAndCleanup()throws Exception {execute(SqliteFixtures.TRANSACTIONS,true);}
    @Test void sqliteErrorsAndStatementReuse()throws Exception {execute(SqliteFixtures.ERRORS,true);}
    @Test void sqliteRepeatedHandlesAndDetachedBuffers()throws Exception {execute(SqliteFixtures.STRESS,true);}
    @Test void hindiTutorialExamplesRunAsDocumented()throws Exception {
        execute(Files.readString(Path.of("examples/sqlite/values.bhash")),true,"3.54.1\n६ ६ अंक\n४२ १.२५ सत्य भारत😀\nबाइटबफ़र ० सत्य\nरिक्त\n०\n");
        execute(Files.readString(Path.of("examples/sqlite/transactions.bhash")),true,"रद्द करें\n१ मनोज\n");
        execute(Files.readString(Path.of("examples/sqlite/offline.test.bhash")),true);
    }
    @Test void oldOutputMarkerNeverClaimsUserSqliteLibraries()throws Exception {
        Path output=directory.resolve("preserve");var compiler=new ClrCompiler();
        compiler.compile("pure.bhash","कार्य मुख्य() -> शून्य {}",output);
        Files.writeString(output.resolve("bhash_sqlite.dll"),"user-owned");
        assertThrows(java.io.IOException.class,()->compiler.compile("sqlite.bhash",SqliteFixtures.BASIC,output));
        assertEquals("user-owned",Files.readString(output.resolve("bhash_sqlite.dll")));
    }
    @Test void alteredRuntimeFailsBeforeLoadingAndDatabaseOutputCanBecomePure()throws Exception {
        Path output=directory.resolve("verified");var compiler=new ClrCompiler();
        compiler.compile("sqlite.bhash",SqliteFixtures.BASIC,output);
        String os=System.getProperty("os.name").toLowerCase(java.util.Locale.ROOT);
        Path library=output.resolve(os.contains("win")?"bhash_sqlite.dll":os.contains("mac")?"libbhash_sqlite.dylib":"libbhash_sqlite.so");
        Files.write(library,new byte[]{0});Path log=directory.resolve("tamper.log");
        var child=new ProcessBuilder(ClrCompiler.executable(),output.resolve("BhashProgram.dll").toString()).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try{assertTrue(child.waitFor(30,TimeUnit.SECONDS));assertEquals(1,child.exitValue());assertTrue(Files.readString(log).contains("SQLite रनटाइम का सत्यापन असफल।"));}finally{child.destroyForcibly();}
        compiler.compile("pure.bhash","कार्य मुख्य() -> शून्य {}",output);assertFalse(Files.exists(library));
    }
}
