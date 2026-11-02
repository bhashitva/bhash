// SPDX-License-Identifier: Apache-2.0
package dev.bhash;
import dev.bhash.backend.SqliteAssets;
import java.nio.file.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
class SqliteAssetsTest {
    @TempDir Path directory;
    @Test void sourceAndAllRuntimeLibrariesHavePinnedIdentity()throws Exception {
        SqliteAssets.copySource(directory);assertTrue(Files.size(directory.resolve("sqlite3.c"))>9000000);
        SqliteAssets.copyLibraries(directory);
        for(String target:SqliteAssets.TARGETS){var m=SqliteAssets.manifest(target);assertEquals("3.54.1",m.get("sqlite_version").getAsString());assertEquals(m.get("sha256").getAsString(),SqliteAssets.digest("SHA-256",Files.readAllBytes(directory.resolve(m.get("file").getAsString()))));}
        String template="__SQLITE_HASH_LINUX__ __SQLITE_HASH_WINDOWS__ __SQLITE_HASH_MACOS__";assertFalse(SqliteAssets.clrSource(template).contains("__SQLITE"));
    }
}
