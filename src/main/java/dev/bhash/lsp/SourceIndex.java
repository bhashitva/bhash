// SPDX-License-Identifier: Apache-2.0
package dev.bhash.lsp;

import dev.bhash.frontend.*;
import dev.bhash.parser.BhashParser.*;
import java.util.*;
import org.antlr.v4.runtime.*;
import org.antlr.v4.runtime.tree.*;
import org.eclipse.lsp4j.*;

/** Source-level declaration index. Navigation is deliberately limited to lexical bindings and named top-level declarations. */
final class SourceIndex {
    record Symbol(String name, String qualified, String uri, SymbolKind kind, String detail,
            Range range, Range selection, boolean exported, boolean global, int scopeStart, int scopeEnd, int visibleFrom) {
        Location location() { return new Location(uri,selection); }
    }
    final String uri, text, pkg;
    final TextCoordinates coordinates;
    final List<Symbol> symbols = new ArrayList<>();
    final Map<Symbol,ParserRuleContext> declarations = new LinkedHashMap<>();
    final ProgramContext tree;
    final List<DocumentSymbol> outline = new ArrayList<>();
    final Map<String,String> imports = new LinkedHashMap<>();
    final List<Token> tokens = new ArrayList<>();
    final Map<Integer,String> typeReferences = new HashMap<>(), importReferences = new HashMap<>();

    SourceIndex(SourceFile source) {
        uri = java.nio.file.Path.of(source.name()).toUri().toASCIIString();
        text = source.text(); coordinates = new TextCoordinates(text);
        var parsed = source.parse();
        tree = parsed.tree();
        pkg = tree.packageDecl() == null ? "" : key(tree.packageDecl().qualifiedName().getText());
        for (var imp : tree.importDecl()) {
            String full = key(imp.qualifiedName().getText());
            for(var id:imp.qualifiedName().IDENTIFIER())importReferences.put(id.getSymbol().getStartIndex(),full);
            if(imp.IDENTIFIER()!=null)importReferences.put(imp.IDENTIFIER().getSymbol().getStartIndex(),full);
            imports.put(imp.IDENTIFIER() == null ? full.substring(full.lastIndexOf('.') + 1) : key(imp.IDENTIFIER().getText()),full);
        }
        // Syntax recovery can produce missing declaration identifiers; keep the rest of the editor functional.
        boolean exported = false;
        for (int i = 0; i < tree.getChildCount(); i++) {
            ParseTree child = tree.getChild(i);
            if (child instanceof TerminalNode node && node.getText().equals("सार्वजनिक")) { exported = true; continue; }
            if (child instanceof ParserRuleContext context) {
                collect(context,outline,exported,true,0,text.codePointCount(0,text.length()));
                exported = false;
            }
        }
        var lexer = new dev.bhash.parser.BhashLexer(CharStreams.fromString(text.startsWith("\uFEFF") ? text.substring(1) : text));
        lexer.removeErrorListeners();
        tokens.addAll(lexer.getAllTokens());
    }
    static String key(String name) { return Identifiers.key(name); }
    private int start(ParserRuleContext context) { return context.start == null ? 0 : Math.max(0,context.start.getStartIndex()); }
    private int end(ParserRuleContext context) {
        TerminalNode close=context instanceof BlockContext c?c.RBRACE():context instanceof ClassDeclContext c?c.RBRACE():context instanceof InterfaceDeclContext c?c.RBRACE():null;
        if((context instanceof BlockContext || context instanceof InterfaceDeclContext || context instanceof ClassDeclContext c && c.RECORD()==null)
                && (close==null || close.getSymbol().getStartIndex()<0))return text.codePointCount(0,text.length());
        if(context instanceof FunctionDeclContext c && c.block()!=null)return end(c.block());
        if(context instanceof ConstructorDeclContext c && c.block()!=null)return end(c.block());
        return context.stop == null ? start(context) : Math.max(start(context),context.stop.getStopIndex() + 1);
    }
    private Range range(int start, int end) {
        // AST token offsets exclude an initial BOM.
        int shift = text.startsWith("\uFEFF") ? 1 : 0;
        return coordinates.range(start + shift,end + shift);
    }
    private void collect(ParserRuleContext context, List<DocumentSymbol> destination, boolean exported, boolean global, int scopeStart, int scopeEnd) {
        TerminalNode id = null; SymbolKind kind = null; String detail = "";
        if (context instanceof ClassDeclContext c) { id=c.IDENTIFIER(); kind=c.RECORD()!=null?SymbolKind.Struct:SymbolKind.Class; detail=c.RECORD()!=null?"अभिलेख":"वर्ग"; }
        else if (context instanceof InterfaceDeclContext c) { id=c.IDENTIFIER();kind=SymbolKind.Interface;detail="अनुबंध"; }
        else if (context instanceof EnumDeclContext c) { id=c.IDENTIFIER();kind=SymbolKind.Enum;detail="परिगणना"; }
        else if (context instanceof EnumMemberContext c) { id=c.IDENTIFIER();kind=SymbolKind.EnumMember; }
        else if (context instanceof FunctionDeclContext c) { id=c.IDENTIFIER();kind=global?SymbolKind.Function:SymbolKind.Method;detail="कार्य " + c.IDENTIFIER() + (c.typeParameters()==null?"":c.typeParameters().getText()) + "(" + (c.parameters()==null?"":c.parameters().getText()) + ") -> " + (c.returnType()==null?"?":c.returnType().getText()); }
        else if (context instanceof FieldDeclContext c) { id=c.IDENTIFIER();kind=c.CONST()==null?SymbolKind.Field:SymbolKind.Constant;detail=c.valueType()==null?"":c.valueType().getText(); }
        else if (context instanceof ConstructorDeclContext c) { id=c.CONSTRUCTOR();kind=SymbolKind.Constructor;detail="निर्माता("+(c.parameters()==null?"":c.parameters().getText())+")"; }
        else if (context instanceof VariableDeclContext c) { id=c.IDENTIFIER();kind=c.CONST()==null?SymbolKind.Variable:SymbolKind.Constant;detail=c.valueType()==null?"":c.valueType().getText(); }
        else if (context instanceof ParameterContext c) { id=c.IDENTIFIER();kind=SymbolKind.Variable;detail=c.valueType()==null?"":c.valueType().getText(); }
        if(context instanceof ValueTypeContext value && value.IDENTIFIER()!=null) {
            String full=value.IDENTIFIER().getText();for(var part:value.typeQualifier())full+="."+part.IDENTIFIER().getText();
            typeReferences.put(value.IDENTIFIER().getSymbol().getStartIndex(),key(full));
            for(var part:value.typeQualifier())typeReferences.put(part.IDENTIFIER().getSymbol().getStartIndex(),key(full));
        }
        if(context instanceof TypeParametersContext parameters)for(var parameter:parameters.IDENTIFIER()) {
            if(parameter.getSymbol().getStartIndex()<0)continue;
            Range selected=range(parameter.getSymbol().getStartIndex(),parameter.getSymbol().getStopIndex()+1);
            symbols.add(new Symbol(parameter.getText(),key(parameter.getText()),uri,SymbolKind.TypeParameter,"type parameter",selected,selected,false,false,scopeStart,scopeEnd,scopeStart));
        }
        boolean declaration = id != null && id.getSymbol().getStartIndex() >= 0;
        List<DocumentSymbol> children = destination;
        if (declaration) {
            String name = id.getText();
            Range selection = range(id.getSymbol().getStartIndex(),id.getSymbol().getStopIndex()+1);
            Range full = range(start(context),Math.max(end(context),id.getSymbol().getStopIndex()+1));
            String qualified = pkg.isEmpty()?key(name):pkg+"."+key(name);
            int visible = context instanceof VariableDeclContext ? end(context) : scopeStart;
            var symbol = new Symbol(name,qualified,uri,kind,detail,full,selection,exported,global,scopeStart,scopeEnd,visible);
            symbols.add(symbol); declarations.put(symbol,context);
            if (!(context instanceof ParameterContext) && !(context instanceof VariableDeclContext)) {
                var document = new DocumentSymbol(name,kind,full,selection,detail);
                children = new ArrayList<>(); document.setChildren(children);destination.add(document);
            }
        }
        boolean scope = context instanceof BlockContext || context instanceof ClassDeclContext || context instanceof InterfaceDeclContext
                || context instanceof EnumDeclContext || context instanceof FunctionDeclContext || context instanceof ConstructorDeclContext
                || context instanceof CatchClauseContext || context instanceof ForeachStatementContext;
        int childStart = scope?start(context):scopeStart, childEnd=scope?end(context):scopeEnd;
        for (int i=0;i<context.getChildCount();i++) if (context.getChild(i) instanceof ParserRuleContext child)
            collect(child,children,false,false,childStart,childEnd);
    }
    Token token(Position position) {
        int offset = text.codePointCount(0,coordinates.offset(position)) - (text.startsWith("\uFEFF")?1:0);
        return tokens.stream().filter(t->t.getStartIndex()<=offset && t.getStopIndex()>=offset).findFirst().orElse(null);
    }
    List<Symbol> visibleLexical(Position position) {
        int offset=text.codePointCount(0,coordinates.offset(position)) - (text.startsWith("\uFEFF")?1:0);
        return symbols.stream().filter(s->!s.global && s.scopeStart<=offset && offset<=s.scopeEnd && s.visibleFrom<=offset).toList();
    }
    List<Symbol> resolve(Position position, Collection<SourceIndex> project) {
        Token token=token(position);
        if (token==null || token.getType()!=dev.bhash.parser.BhashLexer.IDENTIFIER) return List.of();
        String name=key(token.getText());
        String imported=importReferences.get(token.getStartIndex());
        if(imported!=null)return project.stream().flatMap(i->i.symbols.stream()).filter(s->s.global&&s.qualified.equals(imported)&&(s.exported||pkg.equals(packageOf(s.qualified)))).toList();
        boolean typeReference=typeReferences.containsKey(token.getStartIndex());
        if(typeReference)name=typeReferences.get(token.getStartIndex());
        final String lookupName=name;
        for (var symbol:symbols) if (symbol.selection.equals(range(token.getStartIndex(),token.getStopIndex()+1))) return List.of(symbol);
        // Member navigation uses the same conservative declaration/type model as completion.
        int at=tokens.indexOf(token);
        if (!typeReference && at>0 && tokens.get(at-1).getText().equals(".")) return new EditorModel(this,project).resolveMember(position);
        var lexical=visibleLexical(position).stream().filter(s->key(s.name).equals(lookupName) && (!typeReference||s.kind==SymbolKind.TypeParameter)).toList();
        if (!lexical.isEmpty()) {
            int narrowest=lexical.stream().mapToInt(s->s.scopeEnd-s.scopeStart).min().orElse(0);
            return lexical.stream().filter(s->s.scopeEnd-s.scopeStart==narrowest).toList();
        }
        String full=name.contains(".")?name:imports.getOrDefault(name,pkg.isEmpty()?name:pkg+"."+name);
        return project.stream().flatMap(i->i.symbols.stream()).filter(s->s.global && (!typeReference||s.kind!=SymbolKind.Function) && s.qualified.equals(full) && (s.exported || pkg.equals(packageOf(s.qualified)))).toList();
    }
    static String packageOf(String qualified) { int dot=qualified.lastIndexOf('.');return dot<0?"":qualified.substring(0,dot); }
    List<CompletionItem> completions(Position position, Collection<SourceIndex> project) {
        int offset=coordinates.offset(position), start=offset;
        boolean importing=text.substring(Math.max(0,text.lastIndexOf('\n',Math.max(0,offset-1))+1),offset).stripLeading().startsWith("आयात ");
        var model=new EditorModel(this,project);
        var members=importing?null:model.memberCompletions(position);
        if(members!=null)return members;
        var result=new ArrayList<CompletionItem>();
        while(start>0) { int cp=text.codePointBefore(start);if(!Character.isLetterOrDigit(cp)&&Character.getType(cp)!=Character.NON_SPACING_MARK&&Character.getType(cp)!=Character.COMBINING_SPACING_MARK&&cp!='_')break;start-=Character.charCount(cp); }
        var replace=new Range(coordinates.position(start),position);
        for(int type=1;type<=dev.bhash.parser.BhashLexer.VOCABULARY.getMaxTokenType();type++) {
            String literal=dev.bhash.parser.BhashLexer.VOCABULARY.getLiteralName(type);
            if(literal==null)continue;
            String word=java.util.regex.Pattern.compile("\\\\u([0-9A-Fa-f]{4})").matcher(literal.substring(1,literal.length()-1))
                    .replaceAll(m->Character.toString((char)Integer.parseInt(m.group(1),16)));
            if(word.isEmpty() || !Character.isLetter(word.codePointAt(0)))continue;
            String alias=dev.bhash.parser.BhashLexer.VOCABULARY.getSymbolicName(type).toLowerCase(Locale.ROOT);
            var item=new CompletionItem(word+" · "+alias);item.setKind(CompletionItemKind.Keyword);item.setFilterText(word+" "+alias);
            item.setTextEdit(org.eclipse.lsp4j.jsonrpc.messages.Either.forLeft(new TextEdit(replace,word)));result.add(item);
        }
        for(String builtin:List.of("पढ़ो","पाठपढ़ो","लंबाई","अंश","अंकपाठ","पूर्णांकपाठ","बड़ाअक्षर","त्रुटि","यूटीएफ८बफ़र","यूटीएफ८पाठ","सरणी","सूची","मानचित्र","अपवाद")) {
            var item=new CompletionItem(builtin);item.setKind(CompletionItemKind.Function);item.setDetail("Bhash built-in");result.add(item);
        }

        for(var index:project)for(var s:index.symbols)if(s.global) {
            String label=null;
            if(importing && (s.exported || index.pkg.equals(pkg)))label=s.qualified;
            else if(index.pkg.equals(pkg))label=s.name;
            else if(s.exported)for(var imp:imports.entrySet())if(imp.getValue().equals(s.qualified))label=imp.getKey();
            if(label!=null){var item=new CompletionItem(label);
                if(importing){int lineStart=text.lastIndexOf('\n',Math.max(0,offset-1))+1;int importStart=text.indexOf("आयात",lineStart)+"आयात".length();while(importStart<offset&&Character.isWhitespace(text.charAt(importStart)))importStart++;
                    item.setTextEdit(org.eclipse.lsp4j.jsonrpc.messages.Either.forLeft(new TextEdit(new Range(coordinates.position(importStart),position),label)));}item.setKind(s.kind==SymbolKind.Function?CompletionItemKind.Function:CompletionItemKind.Class);item.setDetail(s.detail+" — "+s.qualified);result.add(item);}
        }
        for(var s:visibleLexical(position)){var item=new CompletionItem(s.name);item.setKind(CompletionItemKind.Variable);item.setDetail(s.detail);result.add(item);}
        if(!importing)result.addAll(model.autoImports(position));
        return result;
    }
}
