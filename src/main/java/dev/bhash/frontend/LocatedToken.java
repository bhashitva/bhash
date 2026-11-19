// SPDX-License-Identifier: Apache-2.0
package dev.bhash.frontend;

import org.antlr.v4.runtime.*;
import org.antlr.v4.runtime.misc.Pair;

/** Keeps the original file and coordinates through package lowering and generic specialization. */
public final class LocatedToken extends CommonToken {
    public LocatedToken(Pair<TokenSource, CharStream> source, int type, int channel, int start, int stop) {
        super(source, type, channel, start, stop);
    }
    private String originalText;
    private boolean strictNulls;
    private int unionKind; // 1: generated family; 2: generated case; 3: checked match code; 4: a checked non-null proof obligation
    private String unionCaseName;
    public int unionKind() { return unionKind; }
    public String unionCaseName() { return unionCaseName; }
    public void unionOrigin(int kind, String name) { unionKind=kind; unionCaseName=name; }
    public boolean strictNulls() { return strictNulls; }
    public void strictNulls(boolean value) { strictNulls = value; }
    public String originalText() { return originalText == null ? getText() : originalText; }
    public void locate(Token origin) {
        originalText = origin instanceof LocatedToken token ? token.originalText() : origin.getText();
        strictNulls = origin instanceof LocatedToken token && token.strictNulls();
        unionKind = origin instanceof LocatedToken token ? token.unionKind : 0;
        unionCaseName = origin instanceof LocatedToken token ? token.unionCaseName : null;
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
