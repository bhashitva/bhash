package dev.bhash.cli;

import dev.bhash.backend.Target;
import dev.bhash.backend.jvm.CompilationException;
import dev.bhash.backend.jvm.JvmCompiler;
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
                        + target.description() + (target == Target.JVM ? " [implemented]" : " [planned]"));
            }
            return 0;
        }
        if (args.length >= 2 && args[0].equals("compile")) return compile(args, out, err);
        if (args.length == 2 && args[0].equals("run")) return execute(args[1], err);
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
        err.println("Usage: java -jar bhash-compiler.jar check|parse|run <file.bhash>");
        err.println("       java -jar bhash-compiler.jar compile <file.bhash> [-o <program.jar>] [--target jvm]");
        err.println("       java -jar bhash-compiler.jar targets");
        err.println("check validates syntax only; compile/run also validate names, types and control flow.");
        return 2;
    }

    private static int compile(String[] args, PrintStream out, PrintStream err) {
        try {
            Path source = Path.of(args[1]);
            if (source.getFileName() == null) throw new IOException("Expected a Bhash source file.");
            String filename = source.getFileName().toString();
            int dot = filename.lastIndexOf('.');
            Path output = source.resolveSibling((dot > 0 ? filename.substring(0, dot) : filename) + ".jar");
            boolean outputSet = false, targetSet = false;
            for (int i = 2; i < args.length; i++) {
                if (args[i].equals("-o") && !outputSet && i + 1 < args.length) {
                    output = Path.of(args[++i]);
                    outputSet = true;
                } else if (args[i].equals("--target") && !targetSet && i + 1 < args.length) {
                    targetSet = true;
                    if (!args[++i].equals("jvm")) { err.println("Only the jvm target is implemented."); return 2; }
                } else return usage(err);
            }
            if (source.toAbsolutePath().normalize().equals(output.toAbsolutePath().normalize())
                    || (Files.exists(output) && Files.isSameFile(source, output))) {
                err.println("Output must not overwrite the Bhash source file.");
                return 2;
            }
            new JvmCompiler().compile(source.toString(), Files.readString(source, StandardCharsets.UTF_8), output);
            out.println("Compiled JVM program: " + output);
            out.println("Run: java -jar \"" + output + "\"");
            return 0;
        } catch (CompilationException exception) {
            err.println(exception.getMessage());
            return 1;
        } catch (IOException | java.nio.file.InvalidPathException exception) {
            err.println(exception.getMessage());
            return 2;
        }
    }

    private static int execute(String filename, PrintStream err) {
        Path temporary = null;
        try {
            Path source = Path.of(filename);
            temporary = Files.createTempFile("bhash-run-", ".jar");
            new JvmCompiler().compile(filename, Files.readString(source, StandardCharsets.UTF_8), temporary);
            String executable = System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java";
            Path java = Path.of(System.getProperty("java.home"), "bin", executable);
            return new ProcessBuilder(java.toString(), "-jar", temporary.toString()).inheritIO().start().waitFor();
        } catch (CompilationException exception) {
            err.println(exception.getMessage());
            return 1;
        } catch (IOException | java.nio.file.InvalidPathException exception) {
            err.println(exception.getMessage());
            return 2;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return 130;
        } finally {
            if (temporary != null) {
                try { Files.deleteIfExists(temporary); }
                catch (IOException exception) { err.println("Could not remove temporary JAR: " + exception.getMessage()); }
            }
        }
    }
}
