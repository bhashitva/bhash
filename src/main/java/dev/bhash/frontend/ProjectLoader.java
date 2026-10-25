// SPDX-License-Identifier: Apache-2.0
package dev.bhash.frontend;

import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Deterministic source discovery; manifests are UTF-8 Java properties, not executable build scripts. */
public final class ProjectLoader {
    public static final String MANIFEST = "bhash.project";
    private ProjectLoader() {}

    public record Project(Path input, Path root, String name, List<Path> sourceRoots,
            List<Path> sourcePaths, List<SourceFile> sources) {
        public void protectOutput(Path output, boolean directory) throws IOException {
            Path destination = physical(output);
            var protectedPaths = new ArrayList<>(sourcePaths);
            protectedPaths.add(input);
            for (var source : protectedPaths) {
                Path path = physical(source);
                if (path.equals(destination) || directory && path.startsWith(destination)
                        || Files.exists(output) && Files.isSameFile(source,output))
                    throw new IOException("Output must not overwrite a project manifest, source file or its containing directory: " + output);
            }
            if (directory) for (var sourceRoot : sourceRoots) if (destination.equals(physical(sourceRoot)))
                throw new IOException("Output must not replace a source root: " + output);
        }
    }
    // Resolve existing ancestors as well, so a symlink in an output parent cannot bypass protection.
    private static Path physical(Path path) throws IOException {
        path = path.toAbsolutePath().normalize();
        if (Files.exists(path)) return path.toRealPath();
        var parent = path.getParent();
        return parent == null ? path : physical(parent).resolve(path.getFileName());
    }
    public static Path manifest(Path input) {
        return Files.isDirectory(input) ? input.resolve(MANIFEST) : input;
    }
    public static Project load(Path requested) throws IOException { return load(requested, Map.of()); }

    /** Editor buffers replace disk text; new unsaved files inside source roots participate without disk writes. */
    public static Project load(Path requested, Map<Path,String> overlays) throws IOException {
        Path input = manifest(requested).toAbsolutePath().normalize();
        if (!Files.isRegularFile(input) && !overlays.containsKey(input)) throw new IOException("Source file or project manifest does not exist: " + input);
        Path root = input.getParent();
        if (!input.getFileName().toString().equals(MANIFEST)) {
            var source = new SourceFile(input.toString(),read(input,overlays));
            return new Project(input,root,stem(input),List.of(),List.of(input),List.of(source));
        }
        var properties = new Properties() {
            @Override public synchronized Object put(Object key, Object value) {
                if (containsKey(key)) throw new IllegalArgumentException("Duplicate project property: " + key);
                return super.put(key,value);
            }
        };
        try { properties.load(new StringReader(read(input,overlays))); }
        catch (IllegalArgumentException invalid) { throw new IOException(input + ": " + invalid.getMessage(),invalid); }
        for (String key : properties.stringPropertyNames()) if (!Set.of("name","sources").contains(key))
            throw new IOException(input + ": Unknown project property: " + key);
        String name = properties.getProperty("name",root.getFileName().toString()).strip();
        if (name.isEmpty() || name.equals(".") || name.equals("..") || name.contains("/") || name.contains("\\") || name.contains(":"))
            throw new IOException(input + ": Project name must be a filename, without path separators.");
        var roots = new ArrayList<Path>();
        var files = new TreeSet<Path>(Comparator.comparing(p -> root.relativize(p).toString().replace('\\','/')));
        var identities = new HashSet<Path>();
        for (String entry : properties.getProperty("sources","src").split(",",-1)) {
            if (entry.isBlank()) throw new IOException(input + ": Source roots must not be empty.");
            Path sourceRoot = root.resolve(entry.strip()).normalize();
            if (!sourceRoot.startsWith(root) || !Files.isDirectory(sourceRoot)
                    || !sourceRoot.toRealPath().startsWith(root.toRealPath()))
                throw new IOException(input + ": Source root must be an existing directory inside the project: " + entry);
            roots.add(sourceRoot);
            try (var paths = Files.walk(sourceRoot)) {
                for (Path path : paths.sorted().toList()) {
                    if (Files.isSymbolicLink(path)) throw new IOException("Symbolic links are not supported in source roots: " + path);
                    if (!Files.isRegularFile(path) || !path.getFileName().toString().endsWith(".bhash")) continue;
                    if (!identities.add(path.toRealPath())) throw new IOException("Overlapping source roots include a file more than once: " + path);
                    files.add(path);
                }
            }
        }
        for (Path path : overlays.keySet()) {
            path = path.toAbsolutePath().normalize();
            if (!path.getFileName().toString().endsWith(".bhash") || files.contains(path)) continue;
            for (Path sourceRoot : roots) if (path.startsWith(sourceRoot)) {
                if (!physical(path).startsWith(root.toRealPath())) throw new IOException("Editor source escapes project: " + path);
                files.add(path);
                break;
            }
        }
        if (files.isEmpty()) throw new IOException(input + ": Project contains no .bhash source files.");
        var sources = new ArrayList<SourceFile>();
        for (Path path : files) sources.add(new SourceFile(path.toString(),read(path,overlays)));
        return new Project(input,root,name,List.copyOf(roots),List.copyOf(files),List.copyOf(sources));
    }
    private static String read(Path path, Map<Path,String> overlays) throws IOException {
        String text = overlays.get(path);
        return text != null ? text : Files.readString(path,StandardCharsets.UTF_8);
    }
    private static String stem(Path path) {
        String name = path.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0,dot) : name;
    }
}
