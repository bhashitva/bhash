// SPDX-License-Identifier: Apache-2.0
package dev.bhash.backend.wasm;

import java.nio.file.*;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class WasmOutputPermissionsTest {
    @TempDir Path directory;

    @Test void publishedBundleCanBeServedByAnotherUser() throws Exception {
        assumeTrue(Files.getFileAttributeView(directory, PosixFileAttributeView.class) != null);
        Path stage = Files.createTempDirectory(directory, "stage-");
        Path framework = Files.createDirectory(stage.resolve("_framework"));
        Path html = Files.writeString(stage.resolve("index.html"), "hello");
        Path wasm = Files.write(framework.resolve("app.wasm"), new byte[]{0, 97, 115, 109});
        for (Path path : new Path[]{stage, framework})
            Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rwx------"));
        for (Path path : new Path[]{html, wasm})
            Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-------"));
        Path output = directory.resolve("app-web");
        WasmCompiler.install(stage, output);
        assertFalse(Files.exists(stage));
        for (Path path : new Path[]{output, output.resolve("_framework")})
            assertEquals("rwxr-xr-x", PosixFilePermissions.toString(Files.getPosixFilePermissions(path)));
        for (Path path : new Path[]{output.resolve("index.html"), output.resolve("_framework/app.wasm")})
            assertEquals("rw-r--r--", PosixFilePermissions.toString(Files.getPosixFilePermissions(path)));
        assertEquals("hello", Files.readString(output.resolve("index.html")));
    }
}
