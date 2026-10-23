// SPDX-License-Identifier: Apache-2.0
package dev.bhash.frontend;

import dev.bhash.parser.BhashLexer;
import dev.bhash.parser.BhashParser;
import java.util.ArrayList;
import java.util.List;
import org.antlr.v4.runtime.*;

/** UTF-8 decoding happens at the caller. Any lexer/parser error makes the result invalid. */
public final class Frontend {
    private Frontend() {}

    public record Diagnostic(String source, int line, int column, String message) {
        @Override public String toString() {
            return source + ":" + line + ":" + column + ": " + message;
        }
    }

    public record Result(BhashParser.ProgramContext tree, List<Diagnostic> diagnostics) {
        public boolean valid() { return diagnostics.isEmpty(); }
    }

    public static Result parse(String sourceName, String source) {
        var diagnostics = new ArrayList<Diagnostic>();
        var listener = new BaseErrorListener() {
            @Override public void syntaxError(Recognizer<?, ?> recognizer, Object symbol,
                    int line, int column, String message, RecognitionException exception) {
                diagnostics.add(new Diagnostic(sourceName, line, column + 1, message));
            }
        };
        var lexer = new BhashLexer(CharStreams.fromString(source, sourceName));
        lexer.removeErrorListeners();
        lexer.addErrorListener(listener);
        var parser = new BhashParser(new CommonTokenStream(lexer));
        parser.removeErrorListeners();
        parser.addErrorListener(listener);
        var tree = parser.program();
        return new Result(tree, List.copyOf(diagnostics));
    }
}
