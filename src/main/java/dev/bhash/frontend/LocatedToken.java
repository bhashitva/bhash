// SPDX-License-Identifier: Apache-2.0
package dev.bhash.frontend;

import org.antlr.v4.runtime.*;
import org.antlr.v4.runtime.misc.Pair;

/** Keeps the original file and coordinates through package lowering and generic specialization. */
public final class LocatedToken extends CommonToken {
    public LocatedToken(Pair<TokenSource, CharStream> source, int type, int channel, int start, int stop) {
        super(source, type, channel, start, stop);
    }
    public void locate(Token origin) {
        // Cache lowered spelling before changing its backing input stream.
        setText(getText());
        source = new Pair<>(origin.getTokenSource(), origin.getInputStream());
        setLine(origin.getLine());
        setCharPositionInLine(origin.getCharPositionInLine());
    }
    public static String source(Token token, String fallback) {
        return token == null || token.getTokenSource() == null ? fallback : token.getTokenSource().getSourceName();
    }
}
