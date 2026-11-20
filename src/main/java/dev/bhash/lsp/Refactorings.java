// SPDX-License-Identifier: Apache-2.0
package dev.bhash.lsp;

import dev.bhash.backend.ManagedEmitter;
import dev.bhash.frontend.*;
import dev.bhash.parser.BhashLexer;
import dev.bhash.parser.BhashParser.*;
import java.nio.file.Path;
import java.util.*;
import org.antlr.v4.runtime.*;
import org.eclipse.lsp4j.*;

/** Project-scoped, binding-preserving edits. Never writes files or edits dependency caches. */
final class Refactorings {
    private final Collection<SourceIndex> project;
    Refactorings(Collection<SourceIndex> project){this.project=project;}
    private static String id(SourceIndex.Symbol symbol){return symbol.uri()+":"+symbol.selection().getStart();}
    private static String key(String s){return Identifiers.key(s);}
    private static ParserRuleContext owner(ParserRuleContext node) {
        if(node==null)return null;
        for(var p=node.getParent();p!=null;p=p.getParent())if(p instanceof ClassDeclContext||p instanceof InterfaceDeclContext||p instanceof EnumDeclContext)return p;
        return null;
    }
    private String ownerName(SourceIndex index,SourceIndex.Symbol symbol) {
        var o=owner(index.declarations.get(symbol));
        var type=index.declarations.entrySet().stream().filter(e->e.getValue()==o).map(Map.Entry::getKey).findFirst().orElse(null);
        return type==null?null:type.qualified();
    }
    private Set<String> ancestors(String name,Set<String> seen) {
        if(name==null||!seen.add(name)||seen.size()>256)return Set.of();var result=new HashSet<String>();
        for(var index:project)for(var entry:index.declarations.entrySet())if(entry.getKey().global()&&entry.getKey().qualified().equals(name)) {
            var node=entry.getValue();var parents=new ArrayList<ValueTypeContext>();
            if(node instanceof ClassDeclContext c){if(c.valueType()!=null)parents.add(c.valueType());if(c.typeList()!=null)parents.addAll(c.typeList().valueType());}
            if(node instanceof InterfaceDeclContext c&&c.typeList()!=null)parents.addAll(c.typeList().valueType());
            for(var parent:parents)if(parent.IDENTIFIER()!=null) {
                String p=parent.IDENTIFIER().getText()+String.join("",parent.typeQualifier().stream().map(x->x.getText()).toList());
                p=key(p);if(!p.contains("."))p=index.imports.getOrDefault(p,index.pkg.isEmpty()?p:index.pkg+"."+p);
                result.add(p);result.addAll(ancestors(p,seen));
            }
        }
        return result;
    }
    private Set<SourceIndex.Symbol> family(SourceIndex index,Position position) {
        var selected=index.resolve(position,project);if(selected.isEmpty())return Set.of();
        var result=new LinkedHashSet<>(selected);var first=selected.get(0);
        if(first.kind()==SymbolKind.Function)for(var i:project)for(var s:i.symbols)
            if(s.global()&&s.kind()==SymbolKind.Function&&s.qualified().equals(first.qualified()))result.add(s);
        if(first.kind()==SymbolKind.Method) {
            var owners=new HashSet<String>();
            for(var i:project)for(var s:selected)if(i.uri.equals(s.uri())){var o=ownerName(i,s);if(o!=null)owners.add(o);}
            boolean changed;
            do {
                changed=false;
                for(var i:project)for(var s:i.symbols)if(s.kind()==SymbolKind.Method&&key(s.name()).equals(key(first.name()))) {
                    String o=ownerName(i,s);if(o==null)continue;
                    for(var existing:List.copyOf(owners))if(o.equals(existing)||ancestors(o,new HashSet<>()).contains(existing)||ancestors(existing,new HashSet<>()).contains(o)) {
                        if(owners.add(o))changed=true;result.add(s);break;
                    }
                }
            }while(changed);
        }
        return result;
    }
    private boolean identifier(SourceIndex i,Token t){return t.getType()==BhashLexer.IDENTIFIER&&!i.qualifiers.contains(t.getStartIndex());}
    List<Location> references(SourceIndex index,Position at,boolean declarations) {
        var group=family(index,at);if(group.isEmpty())return List.of();var ids=new HashSet<String>();group.forEach(s->ids.add(id(s)));
        var names=new HashSet<String>();group.forEach(s->names.add(key(s.name())));
        var result=new LinkedHashMap<String,Location>();
        for(var i:project)for(var token:i.tokens)if(identifier(i,token)&&names.contains(key(token.getText()))) {
            var range=i.range(token.getStartIndex(),token.getStopIndex()+1);
            if(!declarations&&group.stream().anyMatch(s->s.uri().equals(i.uri)&&s.selection().equals(range)))continue;
            if(i.resolve(range.getStart(),project).stream().anyMatch(s->ids.contains(id(s)))) {
                var loc=new Location(i.uri,range);result.put(i.uri+range,loc);
            }
        }
        return List.copyOf(result.values());
    }
    Range prepare(SourceIndex index,Position at,Path root) {
        var token=index.token(at);if(token==null||!identifier(index,token))return null;
        var group=family(index,at);if(group.isEmpty()||group.stream().anyMatch(s->!editable(s.uri(),root)))return null;
        return index.range(token.getStartIndex(),token.getStopIndex()+1);
    }
    private boolean editable(String uri,Path root) {
        var path=BhashLanguageServer.path(uri);if(path==null||!path.startsWith(root))return false;
        for(var part:root.relativize(path))if(part.toString().equals(".bhash"))return false;
        return !path.getFileName().toString().equals(dev.bhash.stdlib.StandardLibrary.SOURCE_NAME)
            && !path.getFileName().toString().startsWith(dev.bhash.ffi.ForeignDeclarations.EDITOR_PREFIX);
    }
    Map<String,List<TextEdit>> rename(SourceIndex index,Position at,String name,Path root)throws Exception {
        var lexer=new BhashLexer(CharStreams.fromString(name));var errors=new int[1];lexer.removeErrorListeners();
        lexer.addErrorListener(new BaseErrorListener(){@Override public void syntaxError(Recognizer<?,?> r,Object token,int line,int column,String message,RecognitionException e){errors[0]++;}});
        var token=lexer.nextToken();
        if(errors[0]!=0||token.getType()!=BhashLexer.IDENTIFIER||lexer.nextToken().getType()!=Token.EOF||!token.getText().equals(name))
            throw new IllegalArgumentException("नया नाम एक मान्य भाष पहचानकर्ता होना चाहिए।");
        if(prepare(index,at,root)==null)throw new IllegalArgumentException("इस नाम का सुरक्षित परिवर्तन उपलब्ध नहीं है; निर्भरता या अस्पष्ट घोषणा की जाँच करें।");
        var edits=new LinkedHashMap<String,List<TextEdit>>();
        for(var location:references(index,at,true)) {
            if(!editable(location.getUri(),root))throw new IllegalArgumentException("नाम बदलने के लिए केवल वर्तमान परियोजना की फ़ाइलें संपादित की जा सकती हैं।");
            edits.computeIfAbsent(location.getUri(),k->new ArrayList<>()).add(new TextEdit(location.getRange(),name));
        }
        var rewritten=new LinkedHashMap<String,SourceIndex>();var sources=new ArrayList<SourceFile>();
        for(var before:project) {
            if(!edits.containsKey(before.uri)){rewritten.put(before.uri,before);continue;}
            var text=apply(before,edits.getOrDefault(before.uri,List.of()));
            var after=new SourceIndex(new SourceFile(BhashLanguageServer.path(before.uri).toString(),text,before.strictNulls));rewritten.put(before.uri,after);
            if(!BhashLanguageServer.path(before.uri).getFileName().toString().equals(dev.bhash.stdlib.StandardLibrary.SOURCE_NAME))sources.add(new SourceFile(BhashLanguageServer.path(before.uri).toString(),text,before.strictNulls));
        }
        // Verify every resolvable name, including unedited names: a successful compile alone cannot detect capture.
        for(var before:project)if(editable(before.uri,root))for(var original:before.tokens)if(identifier(before,original)) {
            var oldRange=before.range(original.getStartIndex(),original.getStopIndex()+1);
            var expected=new HashSet<String>();
            for(var symbol:before.resolve(oldRange.getStart(),project)) {
                var defining=project.stream().filter(i->i.uri.equals(symbol.uri())).findFirst().orElseThrow();
                var after=rewritten.get(symbol.uri());
                expected.add(symbol.uri()+":"+after.coordinates.position(shift(defining,defining.coordinates.offset(symbol.selection().getStart()),edits.getOrDefault(symbol.uri(),List.of()))));
            }
            var after=rewritten.get(before.uri);int offset=shift(before,before.coordinates.offset(oldRange.getStart()),edits.getOrDefault(before.uri,List.of()));
            var actual=new HashSet<String>();after.resolve(after.coordinates.position(offset),rewritten.values()).forEach(s->actual.add(id(s)));
            if(!expected.equals(actual))throw new IllegalArgumentException("नया नाम किसी अन्य घोषणा को छिपाता है या नाम का अर्थ बदलता है।");
        }
        sources.clear();for(var after:rewritten.values())if(!BhashLanguageServer.path(after.uri).getFileName().toString().equals(dev.bhash.stdlib.StandardLibrary.SOURCE_NAME))sources.add(new SourceFile(BhashLanguageServer.path(after.uri).toString(),after.text,after.strictNulls));
        ManagedEmitter.check(sources);
        return edits;
    }
    private int shift(SourceIndex index,int offset,List<TextEdit> edits) {
        int delta=0;for(var edit:edits)if(index.coordinates.offset(edit.getRange().getStart())<offset)
            delta+=edit.getNewText().length()-(index.coordinates.offset(edit.getRange().getEnd())-index.coordinates.offset(edit.getRange().getStart()));
        return offset+delta;
    }
    static String apply(SourceIndex index,List<TextEdit> edits) {
        var sorted=new ArrayList<>(edits);sorted.sort(Comparator.comparingInt((TextEdit e)->index.coordinates.offset(e.getRange().getStart())).reversed());
        var result=new StringBuilder(index.text);for(var edit:sorted)result.replace(index.coordinates.offset(edit.getRange().getStart()),index.coordinates.offset(edit.getRange().getEnd()),edit.getNewText());return result.toString();
    }
}
