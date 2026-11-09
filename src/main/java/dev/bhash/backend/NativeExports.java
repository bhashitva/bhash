// SPDX-License-Identifier: Apache-2.0
package dev.bhash.backend;

import dev.bhash.frontend.Identifiers;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Pattern;

/** Typed declarations for the explicitly published native library symbols. */
public final class NativeExports {
    private NativeExports() {}

    public record Request(String symbol, String function, List<String> parameters, String result, String location) {}
    private static final String SEGMENT = "[\\p{L}_][\\p{L}\\p{M}\\p{N}_\\u200C\\u200D\\x{11B0A}]*";
    private static final String NAME = SEGMENT + "(?:\\." + SEGMENT + ")*";
    private static final Pattern LINE = Pattern.compile(
        "(bhash_[a-z][a-z0-9_]*)\\s*=\\s*(" + NAME + ")\\s*\\(([^()]*)\\)\\s*->\\s*(" + NAME + ")");
    private static final Set<String> RESERVED = Set.of("bhash_embed_abi_version", "bhash_runtime_start",
        "bhash_runtime_stop", "bhash_bytes_free", "bhash_handle_release");

    public static List<Request> read(Path path) throws IOException, CompilationException {
        var result = new ArrayList<Request>();
        var symbols = new HashSet<String>();
        int number = 0;
        for (String line : Files.readAllLines(path)) {
            number++;
            var text = line.strip();
            if (text.isEmpty() || text.startsWith("#")) continue;
            String location = path + ":" + number;
            var fields = LINE.matcher(text);
            if (!fields.matches())
                throw new CompilationException(location + ": Expected bhash_symbol = फलन(प्रकार) -> प्रकार");
            String symbol = fields.group(1);
            if (RESERVED.contains(symbol) || symbol.startsWith("bhash_internal_") || !symbols.add(symbol))
                throw new CompilationException(location + ": Reserved or duplicate native export: " + symbol);
            String parameters = fields.group(3).strip();
            List<String> types = parameters.isEmpty() ? List.of()
                : Arrays.stream(parameters.split(",", -1)).map(String::strip).toList();
            if (types.size() > 16 || types.stream().anyMatch(type -> !type.matches(NAME)))
                throw new CompilationException(location + ": Expected up to 16 named native parameter types");
            result.add(new Request(symbol, Identifiers.key(fields.group(2)), types.stream().map(Identifiers::key).toList(),
                Identifiers.key(fields.group(4)), location));
        }
        if (result.isEmpty()) throw new CompilationException(path + ": No native exports declared.");
        return List.copyOf(result);
    }
}
