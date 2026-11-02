// SPDX-License-Identifier: Apache-2.0
package dev.bhash;

import dev.bhash.backend.jvm.JvmCompiler;
import dev.bhash.frontend.SourceFile;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.jar.JarFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class JvmSqliteTest {
    @TempDir Path directory;
    private void execute(String source,boolean database)throws Exception {execute(source,database,"");}
    private void execute(String source,boolean database,String expected)throws Exception {
        Path jar=directory.resolve("program.jar"),log=directory.resolve("program.log");
        new JvmCompiler().compile(List.of(new SourceFile("sqlite-test.bhash",source)),jar);
        try(var archive=new JarFile(jar.toFile())){assertEquals(database,archive.getEntry("org/sqlite/JDBC.class")!=null);}
        var command=List.of(Path.of(System.getProperty("java.home"),"bin","java").toString(),"-jar",jar.toString());
        var process=new ProcessBuilder(command).directory(directory.toFile()).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try{assertTrue(process.waitFor(90,TimeUnit.SECONDS));String output=Files.readString(log);assertEquals(0,process.exitValue(),output);assertEquals(expected,output.replace("\r\n","\n"));}
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
}
