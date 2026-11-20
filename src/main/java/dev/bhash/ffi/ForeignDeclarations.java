// SPDX-License-Identifier: Apache-2.0
package dev.bhash.ffi;

import dev.bhash.frontend.*;
import dev.bhash.parser.BhashLexer;
import dev.bhash.parser.BhashParser;
import dev.bhash.parser.BhashParser.*;
import java.util.*;
import org.antlr.v4.runtime.*;
import org.antlr.v4.runtime.tree.*;

/** Builds typed external declarations directly. No invented application body is parsed. */
public final class ForeignDeclarations {
    public static final String EDITOR_PREFIX="__bhash_foreign_";
    public static SourceFile editorSource(java.nio.file.Path directory,ForeignManifest module,boolean strict)throws java.io.IOException {
        String text="संकुल "+module.namespace()+";\n"+documentation(module);
        var file=directory.resolve(EDITOR_PREFIX+module.fingerprint()+".bhash");
        if(java.nio.file.Files.isSymbolicLink(file))throw new java.io.IOException("Symbolic link in binding declaration cache");
        if(java.nio.file.Files.exists(file)){
            if(!java.nio.file.Files.readString(file).equals(text))throw new java.io.IOException("Changed generated binding declarations");
        }else java.nio.file.Files.writeString(file,text,java.nio.charset.StandardCharsets.UTF_8,java.nio.file.StandardOpenOption.CREATE_NEW);
        file.toFile().setReadOnly();return new SourceFile(file.toString(),text,strict);
    }
    private final String source;
    private final boolean strict;
    private int index;
    private ForeignDeclarations(String source, boolean strict) { this.source = source; this.strict = strict; }
    public static ProgramContext tree(ForeignManifest module, String source, boolean strict) {
        return new ForeignDeclarations(source, strict).build(module);
    }
    /** Read-only navigation text only; the compiler uses the typed tree above. */
    public static String documentation(ForeignManifest module){
        var text=new StringBuilder("\n// प्रकारयुक्त बाहरी पुस्तकालय: "+module.packageName()+"\n");
        var owners=new LinkedHashMap<String,List<ForeignManifest.Function>>();module.functions().forEach(f->owners.computeIfAbsent(f.owner(),ignored->new ArrayList<>()).add(f));
        module.resources().forEach(r->owners.computeIfAbsent(r.name(),ignored->new ArrayList<>()));
        for(var entry:owners.entrySet()){
            boolean resource=module.resources().stream().anyMatch(r->r.name().equals(entry.getKey()));
            text.append("सार्वजनिक वर्ग ").append(entry.getKey().substring(module.namespace().length()+1)).append(resource?" पालन मानक.बंदनीय":"").append(" {\n");
            for(var f:entry.getValue())text.append("सार्वजनिक साझा फलन ").append(f.name()).append('(')
                .append(String.join(", ",f.parameters().stream().map(p->p.name()+": "+p.type()).toList())).append(") -> ").append(f.result().type()).append(";\n");
            if(resource)text.append("सार्वजनिक फलन बंदकरो() -> शून्य;\n");text.append("}\n");
        }
        return text.toString();
    }
    private ProgramContext build(ForeignManifest module) {
        var program = new ProgramContext(null, -1);
        var pkg = new PackageDeclContext(program, -1);
        add(pkg, BhashParser.PACKAGE, "संकुल");
        var qualified = new QualifiedNameContext(pkg, -1);
        String[] parts = module.namespace().split("\\.");
        for (int i = 0; i < parts.length; i++) {
            if (i != 0) add(qualified, BhashParser.DOT, ".");
            add(qualified, BhashParser.IDENTIFIER, parts[i]);
        }
        finish(qualified); pkg.addChild(qualified); add(pkg, BhashParser.SEMI, ";"); finish(pkg); program.addChild(pkg);
        var owners = new LinkedHashMap<String, List<ForeignManifest.Function>>();
        module.functions().forEach(f -> owners.computeIfAbsent(f.owner(), ignored -> new ArrayList<>()).add(f));
        module.resources().forEach(r -> owners.computeIfAbsent(r.name(), ignored -> new ArrayList<>()));
        for (var entry : owners.entrySet()) {
            add(program, BhashParser.PUBLIC, "सार्वजनिक");
            var owner = new ClassDeclContext(program, -1);
            add(owner, BhashParser.CLASS, "वर्ग").foreignOwner(module);
            add(owner, BhashParser.IDENTIFIER, entry.getKey().substring(module.namespace().length() + 1)).foreignOwner(module);
            boolean resource=module.resources().stream().anyMatch(r->r.name().equals(entry.getKey()));
            if(resource){
                add(owner,BhashParser.IMPLEMENTS,"पालन");var interfaces=new TypeListContext(owner,-1);
                interfaces.addChild(valueType("मानक.बंदनीय",interfaces));finish(interfaces);owner.addChild(interfaces);
            }
            add(owner, BhashParser.LBRACE, "{");
            for (var function : entry.getValue()) {
                var member = new ClassMemberContext(owner, -1);
                var visibility = new VisibilityContext(member, -1);
                add(visibility, BhashParser.PUBLIC, "सार्वजनिक"); finish(visibility); member.addChild(visibility);
                add(member, BhashParser.STATIC, "साझा");
                var declaration = new FunctionDeclContext(member, -1);
                LocatedToken start = add(declaration, BhashParser.FUNCTION, "फलन");
                start.foreign(new ForeignDeclaration(module, function));
                add(declaration, BhashParser.IDENTIFIER, function.name()).foreign(start.foreign());
                add(declaration, BhashParser.LPAREN, "(");
                if (!function.parameters().isEmpty()) {
                    var parameters = new ParametersContext(declaration, -1);
                    for (int i = 0; i < function.parameters().size(); i++) {
                        if (i != 0) add(parameters, BhashParser.COMMA, ",");
                        var argument = function.parameters().get(i);
                        var parameter = new ParameterContext(parameters, -1);
                        add(parameter, BhashParser.IDENTIFIER, argument.name()); add(parameter, BhashParser.COLON, ":");
                        parameter.addChild(valueType(argument.type(), parameter)); finish(parameter); parameters.addChild(parameter);
                    }
                    finish(parameters); declaration.addChild(parameters);
                }
                add(declaration, BhashParser.RPAREN, ")"); add(declaration, BhashParser.ARROW, "->");
                var result = new ReturnTypeContext(declaration, -1);
                if (function.result().type().equals("शून्य")) add(result, BhashParser.VOID, "शून्य");
                else result.addChild(valueType(function.result().type(), result));
                finish(result); declaration.addChild(result); add(declaration, BhashParser.SEMI, ";"); finish(declaration);
                member.addChild(declaration); finish(member); owner.addChild(member);
            }
            if(resource){
                var member=new ClassMemberContext(owner,-1);var visibility=new VisibilityContext(member,-1);
                add(visibility,BhashParser.PUBLIC,"सार्वजनिक");finish(visibility);member.addChild(visibility);
                var method=new FunctionDeclContext(member,-1);
                var contract=new ForeignManifest.Function(entry.getKey(),"बंदकरो","bhffi_v1_resource_close",List.of(),new ForeignManifest.Result("शून्य","status","","",List.of()));
                var metadata=new ForeignDeclaration(module,contract,true);
                add(method,BhashParser.FUNCTION,"फलन").foreign(metadata);add(method,BhashParser.IDENTIFIER,"बंदकरो").foreign(metadata);
                add(method,BhashParser.LPAREN,"(");add(method,BhashParser.RPAREN,")");add(method,BhashParser.ARROW,"->");
                var result=new ReturnTypeContext(method,-1);add(result,BhashParser.VOID,"शून्य");finish(result);method.addChild(result);
                add(method,BhashParser.SEMI,";");finish(method);member.addChild(method);finish(member);owner.addChild(member);
            }
            add(owner, BhashParser.RBRACE, "}"); finish(owner); program.addChild(owner);
        }
        add(program, Token.EOF, ""); finish(program); return program;
    }
    private ValueTypeContext valueType(String text, ParserRuleContext parent) {
        var parser = new BhashParser(new CommonTokenStream(new BhashLexer(CharStreams.fromString(text, source))));
        var value = parser.valueType(); value.parent = parent;
        locate(value); return value;
    }
    private void locate(ParseTree tree) {
        if (tree instanceof TerminalNode node) {
            Token previous = node.getSymbol();
            LocatedToken replacement = token(previous.getType(), previous.getText());
            ((TerminalNodeImpl)node).symbol = replacement;
        } else {
            for (int i = 0; i < tree.getChildCount(); i++) locate(tree.getChild(i));
            finish((ParserRuleContext)tree);
        }
    }
    private LocatedToken token(int kind, String text) {
        var lexer = new BhashLexer(CharStreams.fromString(text, source));
        var token = new LocatedToken(new org.antlr.v4.runtime.misc.Pair<>(lexer, lexer.getInputStream()), kind, Token.DEFAULT_CHANNEL, 0, text.codePointCount(0, text.length()) - 1);
        token.setText(text); token.setTokenIndex(index++); token.setLine(1); token.setCharPositionInLine(0); token.strictNulls(strict);
        return token;
    }
    private LocatedToken add(ParserRuleContext parent, int kind, String text) {
        LocatedToken token = token(kind, text); parent.addChild(new TerminalNodeImpl(token)); return token;
    }
    private static void finish(ParserRuleContext context) {
        if (context.getChildCount() == 0) return;
        ParseTree first = context.getChild(0), last = context.getChild(context.getChildCount() - 1);
        context.start = first instanceof TerminalNode t ? t.getSymbol() : ((ParserRuleContext)first).start;
        context.stop = last instanceof TerminalNode t ? t.getSymbol() : ((ParserRuleContext)last).stop;
    }
}
