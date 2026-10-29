// SPDX-License-Identifier: Apache-2.0
package dev.bhash.cli;

import dev.bhash.backend.Target;
import dev.bhash.backend.llvm.NativeCompiler;
import dev.bhash.backend.CompilationException;
import dev.bhash.backend.jvm.JvmCompiler;
import dev.bhash.backend.clr.ClrCompiler;
import dev.bhash.backend.wasm.WasmCompiler;
import dev.bhash.frontend.Frontend;
import dev.bhash.frontend.ProjectLoader;
import dev.bhash.parser.BhashParser;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;

public final class Main {
    private Main() {}
    public static void main(String[] args) {
        if (args.length == 1 && args[0].equals("language-server")) {
            dev.bhash.lsp.BhashLanguageServer.main(args);
            return;
        }
        System.exit(run(args, System.out, System.err));
    }

    public static int run(String[] args, PrintStream out, PrintStream err) {
        if (args.length > 0 && args[0].equals("native")) return NativeCommands.run(args,out,err);
        if (args.length == 1 && args[0].equals("targets")) {
            for (var target : Target.values()) {
                out.println(target.name().toLowerCase(java.util.Locale.ROOT) + ": "
                        + target.description() + (target == Target.NATIVE ? " [experimental]" : (target == Target.JVM || target == Target.CLR || target == Target.WASM) ? " [implemented]" : " [planned]"));
            }
            return 0;
        }
        if (args.length >= 1 && args[0].equals("deps")) {
            if (args.length < 3 || args.length > 4 || !java.util.Set.of("lock","restore","verify").contains(args[1])
                    || args.length == 4 && !args[3].equals("--offline")) return usage(err);
            try {
                var project = dev.bhash.frontend.SourcePackages.manage(Path.of(args[2]),args[1],args.length == 4);
                out.println("Dependencies " + args[1] + ": " + project.name());
                return 0;
            } catch (IOException | IllegalArgumentException exception) {
                err.println(exception.getMessage()); return 2;
            }
        }
        if (args.length >= 2 && args[0].equals("compile")) return compile(args, out, err);
        if (args.length >= 2 && args[0].equals("run")) return execute(args, err);
        if (args.length != 2 || !(args[0].equals("check") || args[0].equals("parse"))) return usage(err);
        try {
            var project = ProjectLoader.load(Path.of(args[1]));
            if (args[0].equals("check")) {
                boolean valid = true;
                for (var file : project.sources()) {
                    var parsed = file.parse();
                    parsed.diagnostics().forEach(err::println);
                    valid &= parsed.valid();
                }
                if (valid) out.println("Syntax OK: " + args[1] + " (" + project.sources().size() + " file(s))");
                return valid ? 0 : 1;
            }
            if (project.sources().size()!=1 || Files.isDirectory(Path.of(args[1]))
                    || Path.of(args[1]).getFileName().toString().equals(ProjectLoader.MANIFEST)) {
                err.println("parse accepts one .bhash source file; use check for project syntax.");
                return 2;
            }
            var source = project.sources().get(0).text();
            // A UTF-8 BOM is allowed only at the beginning of a file.
            if (source.startsWith("\uFEFF")) source = source.substring(1);
            var result = Frontend.parse(args[1], source);
            if (!result.valid()) {
                result.diagnostics().forEach(err::println);
                return 1;
            }
            out.println(result.tree().toStringTree(java.util.Arrays.asList(BhashParser.ruleNames)));
            return 0;
        } catch (IOException | java.nio.file.InvalidPathException exception) {
            err.println(args[1] + ": " + exception.getMessage());
            return 2;
        }
    }

    private static int usage(PrintStream err) {
        err.println("Usage: java -jar bhash-compiler.jar check <file.bhash|project-directory|bhash.project>");
        err.println("       java -jar bhash-compiler.jar parse <file.bhash>");
        err.println("       java -jar bhash-compiler.jar compile <file.bhash|project-directory|bhash.project> [-o <output>] [--target jvm|clr|wasm-browser|native] [--debug]");
        err.println("       java -jar bhash-compiler.jar run <file.bhash|project-directory|bhash.project> [--target jvm|clr|native] [--debug]");
        err.println("       java -jar bhash-compiler.jar deps lock|restore|verify <project> [--offline]");
        err.println("       java -jar bhash-compiler.jar native toolchain status|verify|install [--archive FILE|--url HTTPS_URL --sha256 HASH] [--json]");
        err.println("       java -jar bhash-compiler.jar native doctor [--json]");
        err.println("       java -jar bhash-compiler.jar native inspect <executable> [--json] [--require-runtime-match]");
        err.println("       java -jar bhash-compiler.jar native package <source|project> -o <archive.tar.gz|archive.zip> --name NAME --version VERSION [--debug] [--source-root ROOT] [--max-glibc VERSION]");
        err.println("       Native compile/run also accept --source-root ROOT for reproducible source paths.");
        err.println("       java -jar bhash-compiler.jar targets");
        err.println("       java -jar bhash-compiler.jar language-server  (LSP over stdio)");
        err.println("JVM output is a JAR; CLR output is a directory containing BhashProgram.dll and runtime metadata.");
        err.println("Native output is an experimental Linux/Windows x86-64 executable; LLVM 18 is required for builds.");
        err.println("check validates syntax only; compile/run also validate names, types and control flow.");
        return 2;
    }

    private record Options(Path source, Path output, Target target, boolean debug, Path sourceRoot) {}

    private static Options options(String[] args, boolean allowOutput) throws IOException {
        Path source = Path.of(args[1]);
        if (source.getFileName() == null) throw new IOException("Expected a Bhash source file.");
        Path output = null, sourceRoot = null;
        Target target = Target.JVM;
        boolean targetSet = false, debug = false;
        for (int i = 2; i < args.length; i++) {
            if (args[i].equals("-o") && allowOutput && output == null && i + 1 < args.length) {
                output = Path.of(args[++i]);
            } else if (args[i].equals("--target") && !targetSet && i + 1 < args.length) {
                targetSet = true;
                target = switch (args[++i]) {
                    case "jvm" -> Target.JVM;
                    case "clr" -> Target.CLR;
                    case "native" -> Target.NATIVE;
                    case "wasm-browser", "wasm" -> Target.WASM;
                    default -> throw new IllegalArgumentException("Implemented targets: jvm, clr, wasm-browser (alias wasm), native (experimental Linux/Windows x86-64).");
                };
            } else if(args[i].equals("--source-root") && sourceRoot==null && i+1<args.length){sourceRoot=Path.of(args[++i]);
            } else if(args[i].equals("--debug") && !debug){debug=true;
            } else throw new IllegalArgumentException("Invalid or incomplete option: " + args[i]);
        }
        if(sourceRoot!=null && target!=Target.NATIVE)throw new IllegalArgumentException("--source-root requires --target native.");
        if(debug && target!=Target.NATIVE)throw new IllegalArgumentException("--debug currently requires --target native.");
        if (output == null && allowOutput) {
            var project = ProjectLoader.load(source);
            String suffix = target == Target.JVM ? ".jar" : target == Target.CLR ? "-clr" : target == Target.NATIVE ? "-native"+dev.bhash.backend.llvm.NativePlatform.executableSuffix() : "-web";
            output = ProjectLoader.manifest(source).getFileName().toString().equals(ProjectLoader.MANIFEST)
                    ? project.root().resolve("build").resolve(project.name() + suffix)
                    : source.resolveSibling(project.name() + suffix);
        }
        return new Options(source, output, target, debug, sourceRoot);
    }

    private static void build(Options options) throws IOException, CompilationException {
        var project = ProjectLoader.load(options.source);
        if (project.library()) throw new IOException("[BH8001] Library packages are shared source, not executable programs. Build an application that depends on this library.");
        project.protectOutput(options.output,options.target == Target.CLR || options.target == Target.WASM);
        if (options.target == Target.JVM) new JvmCompiler().compile(project.sources(), options.output);
        else if (options.target == Target.WASM) new WasmCompiler().compile(project.sources(), options.output);
        else if (options.target == Target.NATIVE) new NativeCompiler(false,options.debug,options.sourceRoot).compile(project.sources(), options.output);
        else new ClrCompiler().compile(project.sources(), options.output);
    }

    private static int compile(String[] args, PrintStream out, PrintStream err) {
        try {
            var options = options(args, true);
            Path input = options.source.toAbsolutePath().normalize();
            Path output = options.output.toAbsolutePath().normalize();
            if (input.equals(output) || (options.target != Target.JVM && input.startsWith(output))
                    || (Files.exists(output) && Files.isSameFile(input, output))) {
                err.println("Output must not overwrite the Bhash source file or its containing directory.");
                return 2;
            }
            build(options);
            if (options.target == Target.JVM) {
                out.println("Compiled JVM program: " + options.output);
                out.println("Run: java -jar \"" + options.output + "\"");
            } else if (options.target == Target.WASM) {
                out.println("Compiled browser WebAssembly program: " + options.output);
                out.println("Serve this directory over HTTP, then open index.html (see docs/wasm-browser.md).");
            } else if (options.target == Target.NATIVE) {
                out.println("Compiled experimental native program: " + options.output);
                out.println("Run: \"" + options.output + "\"");
            } else {
                Path assembly = options.output.resolve("BhashProgram.dll");
                out.println("Compiled CLR program: " + assembly);
                out.println("Run: dotnet \"" + assembly + "\"");
            }
            return 0;
        } catch (CompilationException exception) {
            err.println(exception.getMessage());
            return 1;
        } catch (IllegalArgumentException exception) {
            err.println(exception.getMessage());
            return usage(err);
        } catch (IOException exception) {
            err.println(exception.getMessage());
            return 2;
        }
    }

    private static int execute(String[] args, PrintStream err) {
        Path temporary = null;
        Process process = null;
        try {
            var requested = options(args, false);
            if (requested.target == Target.WASM) {
                err.println("Browser programs use compile --target wasm-browser; serve the output over HTTP and open index.html.");
                return 2;
            }
            temporary = Files.createTempDirectory("bhash-run-");
            Path output = temporary.resolve(requested.target == Target.JVM ? "program.jar" : requested.target == Target.NATIVE ? "program"+dev.bhash.backend.llvm.NativePlatform.executableSuffix() : "clr");
            var options = new Options(requested.source, output, requested.target, requested.debug, requested.sourceRoot);
            build(options);
            ProcessBuilder builder;
            if (options.target == Target.JVM) {
                String executable = System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java";
                Path java = Path.of(System.getProperty("java.home"), "bin", executable);
                builder = new ProcessBuilder(java.toString(), "-jar", output.toString());
            } else if (options.target == Target.NATIVE) builder = new ProcessBuilder(output.toAbsolutePath().toString());
            else builder = new ProcessBuilder(ClrCompiler.executable(), output.resolve("BhashProgram.dll").toString());
            process = builder.inheritIO().start();
            return process.waitFor();
        } catch (CompilationException exception) {
            err.println(exception.getMessage());
            return 1;
        } catch (IllegalArgumentException exception) {
            err.println(exception.getMessage());
            return usage(err);
        } catch (IOException exception) {
            err.println(exception.getMessage());
            return 2;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return 130;
        } finally {
            if (process != null && process.isAlive()) process.destroyForcibly();
            if (temporary != null) {
                try { ClrCompiler.deleteTree(temporary); }
                catch (IOException exception) { err.println("Could not remove temporary output: " + exception.getMessage()); }
            }
        }
    }
}
