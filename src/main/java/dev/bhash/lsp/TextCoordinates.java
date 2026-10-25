// SPDX-License-Identifier: Apache-2.0
package dev.bhash.lsp;

import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;

/** ANTLR counts Unicode code points; LSP and VS Code count UTF-16 code units. */
final class TextCoordinates {
    private final String text;
    TextCoordinates(String text) { this.text = text; }
    int offset(Position position) {
        int start = 0;
        for (int line = 0; line < position.getLine() && start < text.length(); line++) {
            int newline = text.indexOf('\n', start);
            start = newline < 0 ? text.length() : newline + 1;
        }
        int end = text.indexOf('\n', start);
        if (end < 0) end = text.length();
        if (end > start && text.charAt(end - 1) == '\r') end--;
        return Math.min(end, start + Math.max(0, position.getCharacter()));
    }
    Position position(int offset) {
        offset = Math.max(0, Math.min(text.length(), offset));
        int line = 0, start = 0;
        for (int i = 0; i < offset; i++) if (text.charAt(i) == '\n') { line++; start = i + 1; }
        return new Position(line, offset - start);
    }
    int fromCodePoint(int offset) { return text.offsetByCodePoints(0, Math.max(0, Math.min(text.codePointCount(0,text.length()),offset))); }
    Range range(int start, int end) { return new Range(position(fromCodePoint(start)),position(fromCodePoint(end))); }
    Range diagnostic(int line, int column) {
        int start = offset(new Position(Math.max(0,line - 1),0));
        int end = text.indexOf('\n',start);
        if (end < 0) end = text.length();
        int index = text.offsetByCodePoints(start,Math.min(Math.max(0,column - 1),text.codePointCount(start,end)));
        // The parser strips a leading BOM before counting the first line.
        if (line == 1 && text.startsWith("\uFEFF") && index < end) index++;
        int next = index < end ? index + Character.charCount(text.codePointAt(index)) : index;
        return new Range(position(index),position(next));
    }
}
