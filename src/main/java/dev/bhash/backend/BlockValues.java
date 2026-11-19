// SPDX-License-Identifier: Apache-2.0
package dev.bhash.backend;
import dev.bhash.frontend.*;
import dev.bhash.parser.BhashParser.*;
import java.util.*;
import org.antlr.v4.runtime.*;
import org.antlr.v4.runtime.tree.*;
/** Pure synchronous block values become ordinary closure invocations after async lowering. */
final class BlockValues {
    private final StringBuilder text=new StringBuilder();
    private final NavigableMap<Integer,Token> origins=new TreeMap<>();
    static boolean needed(ParseTree n){if(n instanceof BlockValueContext)return true;for(int i=0;i<n.getChildCount();i++)if(needed(n.getChild(i)))return true;return false;}
    static ProgramContext lower(String source,ProgramContext tree)throws CompilationException {
        if(!needed(tree))return tree;var p=new BlockValues();p.render(tree);var parsed=Frontend.parse(source,p.text.toString());
        if(!parsed.valid())throw new CompilationException(parsed.diagnostics().toString());ProjectResolver.remap(parsed.tree(),p.origins);return parsed.tree();
    }
    private void append(String value,Token origin){if(origin instanceof LocatedToken t&&t.unionKind()==4){var checked=new LocatedToken(new org.antlr.v4.runtime.misc.Pair<>(origin.getTokenSource(),origin.getInputStream()),origin.getType(),origin.getChannel(),origin.getStartIndex(),origin.getStopIndex());checked.setText(origin.getText());checked.locate(origin);checked.unionOrigin(3,null);origin=checked;}
        origins.put(text.codePointCount(0,text.length()),origin);text.append(value).append(' ');}
    private void render(ParseTree node)throws CompilationException {
        if(node instanceof BlockValueContext b){if(!(b.start instanceof LocatedToken t)||t.unionKind()!=3)throw new CompilationException("Untrusted compiler block value.");
            append("(फलन()->",b.start);render(b.valueType());render(b.block());append(")()",b.stop);return;}
        if(node instanceof UnaryContext u&&u.AWAIT()!=null)throw new CompilationException("Suspension survived async lowering.");
        if(node instanceof ShiftOperatorContext s){append(s.getText(),s.start);return;}
        if(node instanceof TerminalNode t){if(t.getSymbol().getType()!=Token.EOF)append(t.getText(),t.getSymbol());return;}
        for(int i=0;i<node.getChildCount();i++)render(node.getChild(i));
    }
}
