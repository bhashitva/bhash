// SPDX-License-Identifier: Apache-2.0
package dev.bhash.backend;

import dev.bhash.frontend.Frontend;
import dev.bhash.parser.BhashParser.*;
import java.util.*;
import org.antlr.v4.runtime.*;
import org.antlr.v4.runtime.tree.*;

/** Normalize effective task signatures before package/generic/closure resolution.
 * Bodies retain their async marker until checked suspension lowering. */
final class AsyncSignatures {
    private final StringBuilder text=new StringBuilder();
    private final NavigableMap<Integer,Token> origins=new TreeMap<>();
    private int offset;
    static ProgramContext normalize(ProgramContext tree)throws CompilationException {
        if(!required(tree))return tree;
        var p=new AsyncSignatures();p.render(tree);
        var result=Frontend.parse("async-signatures",p.text.toString());
        if(!result.valid())throw new CompilationException(result.diagnostics().toString());
        ProjectResolver.remap(result.tree(),p.origins);return result.tree();
    }
    private static boolean required(ParseTree n){if(n instanceof FunctionDeclContext f && f.ASYNC()!=null || n instanceof LambdaExpressionContext l && l.ASYNC()!=null || n instanceof TypeArgumentsContext t && !t.VOID().isEmpty())return true;for(int i=0;i<n.getChildCount();i++)if(required(n.getChild(i)))return true;return false;}
    private void add(String value,Token at){origins.put(offset,at);text.append(value).append(' ');offset+=value.codePointCount(0,value.length())+1;}
    private void render(ParseTree n)throws CompilationException {
        if(n instanceof ReturnTypeContext r && (r.getParent() instanceof FunctionDeclContext f && f.ASYNC()!=null
                || r.getParent() instanceof LambdaExpressionContext l && l.ASYNC()!=null)) {
            add("मानक.कार्यफल<",r.start);
            if(r.VOID()!=null)add("मानक.कार्यशून्य",r.start);else render(r.valueType());
            add(">",r.stop);return;
        }
        if(n instanceof TypeArgumentsContext t && !t.VOID().isEmpty()) {
            String owner=t.getParent() instanceof ValueTypeContext v && v.IDENTIFIER()!=null?v.IDENTIFIER().getText():"";
            if(!owner.equals("कार्यफल") && !(owner.equals("मानक") && t.getParent().getText().startsWith("मानक.कार्यफल<")))
                throw new CompilationException("शून्य केवल कार्यफल<शून्य> में प्रकार तर्क हो सकता है।");
            if(t.getChildCount()!=3)throw new CompilationException("कार्यफल के लिए एक प्रकार तर्क चाहिए।");
            add("<मानक.कार्यशून्य>",t.start);return;
        }
        if(n instanceof ShiftOperatorContext s){add(s.getText(),s.start);return;}
        if(n instanceof TerminalNode t){if(t.getSymbol().getType()!=Token.EOF)add(t.getText(),t.getSymbol());return;}
        for(int i=0;i<n.getChildCount();i++)render(n.getChild(i));
    }
}
