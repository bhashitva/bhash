// SPDX-License-Identifier: Apache-2.0
package dev.bhash.frontend;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;

/** Deterministic source discovery; manifests are UTF-8 Java properties, not executable build scripts. */
public final class ProjectLoader {
    public static final String MANIFEST = "bhash.project";
    private ProjectLoader() {}

    public record Project(Path input, Path root, String name, List<Path> sourceRoots,
            List<Path> sourcePaths, List<SourceFile> sources, String version, boolean library, List<Path> manifests) {
        public void protectOutput(Path output, boolean directory) throws IOException {
            Path destination = physical(output);
            if (destination.startsWith(physical(root.resolve(SourcePackages.CACHE))))
                throw new IOException("Output must not overwrite the dependency cache: " + output);
            var protectedPaths = new ArrayList<>(sourcePaths);
            protectedPaths.addAll(manifests);
            protectedPaths.add(root.resolve(SourcePackages.LOCK));
            for (var source : protectedPaths) {
                Path path = physical(source);
                if (path.equals(destination) || directory && path.startsWith(destination)
                        || Files.exists(source) && Files.exists(output) && Files.isSameFile(source,output))
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
        return SourcePackages.load(manifest(requested).toAbsolutePath().normalize(), overlays);
    }

    static Project loadOwn(Path input, Map<Path,String> overlays, ProjectManifest metadata) throws IOException {
        if (!Files.isRegularFile(input) && !overlays.containsKey(input)) throw new IOException("Source file or project manifest does not exist: " + input);
        Path root = input.getParent();
        if (!input.getFileName().toString().equals(MANIFEST)) {
            var source = new SourceFile(input.toString(),read(input,overlays));
            return new Project(input,root,stem(input),List.of(),List.of(input),List.of(source),null,false,List.of(input));
        }
        if (metadata == null) metadata = ProjectManifest.read(input,overlays);
        String name = metadata.name();
        var roots = new ArrayList<Path>();
        var files = new TreeSet<Path>(Comparator.comparing(p -> root.relativize(p).toString().replace('\\','/')));
        var identities = new HashSet<Path>();
        for (String entry : metadata.roots()) {
            if (entry.isBlank()) throw new IOException(input + ": Source roots must not be empty.");
            Path sourceRoot = root.resolve(entry.strip()).normalize();
            if (!sourceRoot.startsWith(root) || !Files.isDirectory(sourceRoot)
                    || !sourceRoot.toRealPath().startsWith(root.toRealPath()))
                throw new IOException(input + ": Source root must be an existing directory inside the project: " + entry);
            roots.add(sourceRoot);
            Files.walkFileTree(sourceRoot,new SimpleFileVisitor<>() {
                @Override public FileVisitResult preVisitDirectory(Path path,BasicFileAttributes attributes) {
                    return path.startsWith(root.resolve(SourcePackages.CACHE)) ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
                }
                @Override public FileVisitResult visitFile(Path path,BasicFileAttributes attributes) throws IOException {
                    if (Files.isSymbolicLink(path)) throw new IOException("Symbolic links are not supported in source roots: " + path);
                    if (!attributes.isRegularFile() || !path.getFileName().toString().endsWith(".bhash")) return FileVisitResult.CONTINUE;
                    if (!identities.add(path.toRealPath())) throw new IOException("Overlapping source roots include a file more than once: " + path);
                    files.add(path);
                    return FileVisitResult.CONTINUE;
                }
            });
        }
        for (Path path : overlays.keySet()) {
            path = path.toAbsolutePath().normalize();
            if (!path.getFileName().toString().endsWith(".bhash") || files.contains(path) || path.startsWith(root.resolve(SourcePackages.CACHE))) continue;
            for (Path sourceRoot : roots) if (path.startsWith(sourceRoot)) {
                if (!physical(path).startsWith(root.toRealPath())) throw new IOException("Editor source escapes project: " + path);
                files.add(path);
                break;
            }
        }
        if (files.isEmpty()) throw new IOException(input + ": Project contains no .bhash source files.");
        var sources = new ArrayList<SourceFile>();
        for (Path path : files) sources.add(new SourceFile(path.toString(),read(path,overlays)));
        return new Project(input,root,name,List.copyOf(roots),List.copyOf(files),List.copyOf(sources),metadata.version(),metadata.library(),List.of(input,root.resolve(SourcePackages.LOCK)));
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
