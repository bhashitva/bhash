package dev.bhash.cli;

import dev.bhash.backend.Target;
import dev.bhash.backend.CompilationException;
import dev.bhash.backend.jvm.JvmCompiler;
import dev.bhash.backend.clr.ClrCompiler;
import dev.bhash.frontend.Frontend;
import dev.bhash.parser.BhashParser;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

public final class Main {
    private Main() {}
    public static void main(String[] args) { System.exit(run(args, System.out, System.err)); }

    public static int run(String[] args, PrintStream out, PrintStream err) {
        if (args.length == 1 && args[0].equals("targets")) {
            for (var target : Target.values()) {
                out.println(target.name().toLowerCase(java.util.Locale.ROOT) + ": "
                        + target.description() + ((target == Target.JVM || target == Target.CLR) ? " [implemented]" : " [planned]"));
            }
            return 0;
        }
        if (args.length >= 2 && args[0].equals("compile")) return compile(args, out, err);
        if (args.length >= 2 && args[0].equals("run")) return execute(args, err);
        if (args.length != 2 || !(args[0].equals("check") || args[0].equals("parse"))) return usage(err);
        try {
            var source = Files.readString(Path.of(args[1]), StandardCharsets.UTF_8);
            // A UTF-8 BOM is allowed only at the beginning of a file.
            if (source.startsWith("\uFEFF")) source = source.substring(1);
            var result = Frontend.parse(args[1], source);
            if (!result.valid()) {
                result.diagnostics().forEach(err::println);
                return 1;
            }
            if (args[0].equals("parse")) {
                out.println(result.tree().toStringTree(java.util.Arrays.asList(BhashParser.ruleNames)));
            } else {
                out.println("Syntax OK: " + args[1]);
            }
            return 0;
        } catch (IOException | java.nio.file.InvalidPathException exception) {
            err.println(args[1] + ": " + exception.getMessage());
            return 2;
        }
    }

    private static int usage(PrintStream err) {
        err.println("Usage: java -jar bhash-compiler.jar check|parse <file.bhash>");
        err.println("       java -jar bhash-compiler.jar compile <file.bhash> [-o <output>] [--target jvm|clr]");
        err.println("       java -jar bhash-compiler.jar run <file.bhash> [--target jvm|clr]");
        err.println("       java -jar bhash-compiler.jar targets");
        err.println("JVM output is a JAR; CLR output is a directory containing BhashProgram.dll and runtime metadata.");
        err.println("check validates syntax only; compile/run also validate names, types and control flow.");
        return 2;
    }

    private record Options(Path source, Path output, Target target) {}

    private static Options options(String[] args, boolean allowOutput) throws IOException {
        Path source = Path.of(args[1]);
        if (source.getFileName() == null) throw new IOException("Expected a Bhash source file.");
        Path output = null;
        Target target = Target.JVM;
        boolean targetSet = false;
        for (int i = 2; i < args.length; i++) {
            if (args[i].equals("-o") && allowOutput && output == null && i + 1 < args.length) {
                output = Path.of(args[++i]);
            } else if (args[i].equals("--target") && !targetSet && i + 1 < args.length) {
                targetSet = true;
                target = switch (args[++i]) {
                    case "jvm" -> Target.JVM;
                    case "clr" -> Target.CLR;
                    default -> throw new IllegalArgumentException("Only jvm and clr targets are implemented.");
                };
            } else throw new IllegalArgumentException("Invalid or incomplete option: " + args[i]);
        }
        if (output == null) {
            String filename = source.getFileName().toString();
            int dot = filename.lastIndexOf('.');
            String stem = dot > 0 ? filename.substring(0, dot) : filename;
            output = source.resolveSibling(stem + (target == Target.JVM ? ".jar" : "-clr"));
        }
        return new Options(source, output, target);
    }

    private static void build(Options options) throws IOException, CompilationException {
        String source = Files.readString(options.source, StandardCharsets.UTF_8);
        if (options.target == Target.JVM) new JvmCompiler().compile(options.source.toString(), source, options.output);
        else new ClrCompiler().compile(options.source.toString(), source, options.output);
    }

    private static int compile(String[] args, PrintStream out, PrintStream err) {
        try {
            var options = options(args, true);
            Path input = options.source.toAbsolutePath().normalize();
            Path output = options.output.toAbsolutePath().normalize();
            if (input.equals(output) || (options.target == Target.CLR && input.startsWith(output))
                    || (Files.exists(output) && Files.isSameFile(input, output))) {
                err.println("Output must not overwrite the Bhash source file or its containing directory.");
                return 2;
            }
            build(options);
            if (options.target == Target.JVM) {
                out.println("Compiled JVM program: " + options.output);
                out.println("Run: java -jar \"" + options.output + "\"");
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
            temporary = Files.createTempDirectory("bhash-run-");
            Path output = temporary.resolve(requested.target == Target.JVM ? "program.jar" : "clr");
            var options = new Options(requested.source, output, requested.target);
            build(options);
            ProcessBuilder builder;
            if (options.target == Target.JVM) {
                String executable = System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java";
                Path java = Path.of(System.getProperty("java.home"), "bin", executable);
                builder = new ProcessBuilder(java.toString(), "-jar", output.toString());
            } else builder = new ProcessBuilder(ClrCompiler.executable(), output.resolve("BhashProgram.dll").toString());
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
