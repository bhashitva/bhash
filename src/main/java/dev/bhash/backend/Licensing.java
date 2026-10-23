// SPDX-License-Identifier: Apache-2.0
package dev.bhash.backend;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.*;
import java.util.List;
import java.util.jar.*;

/** Distribute the license and attribution for Bhash code included in applications. */
public final class Licensing {
    private Licensing() {}
    public static final List<String> APPLICATION_FILES = List.of("LICENSE", "NOTICE", "LICENSING.md");
    private static final String PREFIX = "META-INF/bhash/";

    private static InputStream resource(String name) throws IOException {
        var input=Licensing.class.getResourceAsStream("/" + PREFIX + name);
        if (input==null) throw new IOException("Compiler installation is missing its licensing resource: " + name);
        return input;
    }

    public static void copyTo(Path directory) throws IOException {
        for (String name:APPLICATION_FILES) try (var input=resource(name)) {
            Files.copy(input,directory.resolve(name),StandardCopyOption.REPLACE_EXISTING);
        }
    }

    public static void addToJar(JarOutputStream jar) throws IOException {
        for (String name:APPLICATION_FILES) try (var input=resource(name)) {
            var entry=new JarEntry(PREFIX + name);
            entry.setTime(0);
            jar.putNextEntry(entry);
            input.transferTo(jar);
            jar.closeEntry();
        }
    }
}
