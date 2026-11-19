// SPDX-License-Identifier: Apache-2.0
package dev.bhash.backend;

import dev.bhash.frontend.*;
import dev.bhash.parser.BhashParser.*;
import java.util.*;
import org.antlr.v4.runtime.*;
import org.antlr.v4.runtime.tree.*;

/** Closed families lower to opaque case classes; token provenance cannot be forged by source. */
final class UnionExpander {
    private final StringBuilder text=new StringBuilder();
    private final NavigableMap<Integer,Token> origins=new TreeMap<>();
    private final Set<String> names=new HashSet<>();
    private int next,offset;
    static ProgramContext expand(String source,ProgramContext tree)throws CompilationException {
        if(tree.classDecl().stream().noneMatch(c->c.unionKeyword()!=null))return tree;
        var p=new UnionExpander();p.collect(tree);p.render(tree);
        var parsed=Frontend.parse(source,p.text.toString());
        if(!parsed.valid())throw new CompilationException(parsed.diagnostics().toString());
        ProjectResolver.remap(parsed.tree(),p.origins);return parsed.tree();
    }
    private void collect(ParseTree n){if(n instanceof TerminalNode t)names.add(Identifiers.key(t.getText()));for(int i=0;i<n.getChildCount();i++)collect(n.getChild(i));}
    private String fresh(){String n;do{n="विकल्पआंतरिक_"+next++;}while(!names.add(n));return n;}
    private void append(String s,Token at){origins.put(offset,at);text.append(s).append(' ');offset+=s.codePointCount(0,s.length())+1;}
    private static Token mark(Token at,int kind,String name){var t=new LocatedToken(new org.antlr.v4.runtime.misc.Pair<>(at.getTokenSource(),at.getInputStream()),at.getType(),at.getChannel(),at.getStartIndex(),at.getStopIndex());t.setText(at.getText());t.locate(at);t.unionOrigin(kind,name);return t;}
    private static String plain(ParseTree n){if(n==null)return "";if(n instanceof TerminalNode t)return t.getText()+" ";var s=new StringBuilder();for(int i=0;i<n.getChildCount();i++)s.append(plain(n.getChild(i)));return s.toString();}
    private void render(ParseTree n)throws CompilationException {
        if(n instanceof ClassDeclContext c&&c.unionKeyword()!=null){family(c);return;}
        if(n instanceof TerminalNode t){if(t.getSymbol().getType()!=Token.EOF)append(t.getText(),t.getSymbol());return;}
        if(n instanceof ShiftOperatorContext s){append(s.getText(),s.start);return;}
        for(int i=0;i<n.getChildCount();i++)render(n.getChild(i));
    }
    private void family(ClassDeclContext c)throws CompilationException {
        var seen=new HashSet<String>();var cases=new LinkedHashMap<UnionCaseContext,String>();
        for(var v:c.unionCase()) {
            if(!seen.add(Identifiers.key(v.IDENTIFIER().getText())))throw error(v,"Duplicate union case: "+v.IDENTIFIER().getText());
            var fields=new HashSet<String>();if(v.parameters()!=null)for(var f:v.parameters().parameter())if(!fields.add(Identifiers.key(f.IDENTIFIER().getText())))throw error(f,"Duplicate union payload field.");
            cases.put(v,fresh());
        }
        String name=c.IDENTIFIER().getText(),parameters=plain(c.typeParameters());
        String args=c.typeParameters()==null?"":"<"+String.join(",",c.typeParameters().IDENTIFIER().stream().map(ParseTree::getText).toList())+">";
        Token origin=mark(c.IDENTIFIER().getSymbol(),1,name);
        append("वर्ग "+name+parameters+" { संरक्षित निर्माता() {}",origin);
        for(var e:cases.entrySet()) {
            var v=e.getKey();var ps=v.parameters()==null?List.<ParameterContext>of():v.parameters().parameter();
            Token factory=mark(v.IDENTIFIER().getSymbol(),1,v.IDENTIFIER().getText());
            append("सार्वजनिक साझा फलन "+v.IDENTIFIER().getText()+"("+plain(v.parameters())+")->"+name+args+" { लौटाओ नया "+e.getValue()+args+"("+String.join(",",ps.stream().map(p->p.IDENTIFIER().getText()).toList())+"); }",factory);
        }
        append("}",origin);
        for(var e:cases.entrySet()) {
            var v=e.getKey();var ps=v.parameters()==null?List.<ParameterContext>of():v.parameters().parameter();
            Token at=mark(v.IDENTIFIER().getSymbol(),2,v.IDENTIFIER().getText());
            append("वर्ग "+e.getValue()+parameters+" विस्तार "+name+args+" {",at);
            for(var p:ps)append("सार्वजनिक स्थिर "+p.IDENTIFIER().getText()+":"+plain(p.valueType())+";",at);
            append("सार्वजनिक निर्माता("+plain(v.parameters())+"){",at);
            for(var p:ps)append("यह."+p.IDENTIFIER().getText()+"="+p.IDENTIFIER().getText()+";",at);
            append("} }",at);
        }
    }
    private static CompilationException error(ParserRuleContext at,String message){return new CompilationException(LocatedToken.source(at.start,"union")+":"+at.start.getLine()+":"+(at.start.getCharPositionInLine()+1)+": [BH3301] विकल्पसमूह की घोषणा जाँचें। — "+message);}
}
