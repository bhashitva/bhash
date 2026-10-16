package dev.bhash.cli;

import dev.bhash.backend.Target;
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
                        + target.description() + " [planned]");
            }
            return 0;
        }
        if (args.length != 2 || !(args[0].equals("check") || args[0].equals("parse"))) {
            err.println("Usage: java -jar bhash.jar {check|parse} <file.bhash> | targets");
            err.println("check validates syntax only; parse prints the ANTLR parse tree.");
            return 2;
        }
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
}
