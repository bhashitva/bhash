// SPDX-License-Identifier: Apache-2.0
package dev.bhash.lsp;

import dev.bhash.frontend.*;
import dev.bhash.parser.*;
import dev.bhash.parser.BhashParser.*;
import java.util.*;
import org.antlr.v4.runtime.*;
import org.antlr.v4.runtime.tree.*;
import org.eclipse.lsp4j.*;
import org.eclipse.lsp4j.jsonrpc.messages.Either;

/** Read-only declaration/type model for incomplete editor buffers. Never executes or lowers
 * user code. Unknown or ambiguous receivers deliberately produce no member suggestions. */
final class EditorModel {
    record Type(String name, List<Type> args) {
        Type(String name) { this(name,List.of()); }
        @Override public String toString() {
            if(name.equals("सरणी") && args.size()==1)return args.get(0)+"[]";
            return name+(args.isEmpty()?"":"<"+String.join(", ",args.stream().map(Object::toString).toList())+">");
        }
    }
    record Value(Type type, boolean shared, boolean base) { Value(Type type) {this(type,false,false);} }
    record Member(String name, Type result, List<String> names, List<Type> parameters,
                  boolean callable, boolean shared, String access, String owner, SourceIndex.Symbol symbol) {
        String label() {
            if(!callable)return name+": "+result;
            var parts=new ArrayList<String>();
            for(int i=0;i<parameters.size();i++)parts.add(names.get(i)+": "+parameters.get(i));
            return name+"("+String.join(", ",parts)+") -> "+result;
        }
    }
    record Definition(SourceIndex index, SourceIndex.Symbol symbol, ParserRuleContext node) {}
    private static final Set<String> BUILTIN_TYPES=Set.of("पूर्णांक","लघुपूर्णांक","बाइट","दशमलव","तार्किक","पाठ","सर्ववस्तु","शून्य","रिक्त","सरणी","सूची","मानचित्र","बाइटबफ़र","अपवाद");
    private final SourceIndex index;
    private final Collection<SourceIndex> project;
    private final Map<String,Definition> types=new LinkedHashMap<>();
    EditorModel(SourceIndex index, Collection<SourceIndex> project) {
        this.index=index;this.project=project;
        for(var source:project)for(var symbol:source.symbols)if(symbol.global()&&symbol.kind()!=SymbolKind.Function)
            types.putIfAbsent(symbol.qualified(),new Definition(source,symbol,source.declarations.get(symbol)));
    }
    private static BhashParser parser(String text) {
        var lexer=new BhashLexer(CharStreams.fromString(text));lexer.removeErrorListeners();
        var parser=new BhashParser(new CommonTokenStream(lexer));parser.removeErrorListeners();return parser;
    }
    private static String name(ParserRuleContext node) {
        if(node instanceof ClassDeclContext c)return c.IDENTIFIER().getText();
        if(node instanceof InterfaceDeclContext c)return c.IDENTIFIER().getText();
        if(node instanceof EnumDeclContext c)return c.IDENTIFIER().getText();
        return null;
    }
    private static ParserRuleContext owner(ParserRuleContext node) {
        for(var p=node.getParent();p!=null;p=p.getParent())
            if(p instanceof ClassDeclContext || p instanceof InterfaceDeclContext || p instanceof EnumDeclContext)return p;
        return null;
    }
    private static List<String> formals(ParserRuleContext node) {
        TypeParametersContext p=node instanceof ClassDeclContext c?c.typeParameters():node instanceof InterfaceDeclContext c?c.typeParameters():node instanceof FunctionDeclContext c?c.typeParameters():null;
        return p==null?List.of():p.IDENTIFIER().stream().map(t->SourceIndex.key(t.getText())).toList();
    }
    private String qualify(String raw, SourceIndex source) {
        raw=SourceIndex.key(raw);
        if(BUILTIN_TYPES.contains(raw))return raw;
        return raw.contains(".")?raw:source.imports.getOrDefault(raw,source.pkg.isEmpty()?raw:source.pkg+"."+raw);
    }
    private Type type(ValueTypeContext node, SourceIndex source, Map<String,Type> substitutions) {
        if(node==null)return null;
        String raw=node.IDENTIFIER()==null?node.getChild(0).getText():node.IDENTIFIER().getText()+String.join("",node.typeQualifier().stream().map(ParseTree::getText).toList());
        Type result=substitutions.get(SourceIndex.key(raw));
        if(result==null) {
            var args=node.typeArguments()==null?List.<Type>of():node.typeArguments().valueType().stream().map(t->type(t,source,substitutions)).toList();
            if(args.stream().anyMatch(Objects::isNull))return null;
            result=new Type(qualify(raw,source),args);
        }
        for(var ignored:node.arraySuffix())result=new Type("सरणी",List.of(result));
        return result;
    }
    private Type type(String text, SourceIndex source, Map<String,Type> substitutions) {
        if(text==null||text.isBlank())return null;
        if(text.equals("शून्य"))return new Type("शून्य");
        var p=parser(text);var node=p.valueType();
        return p.getNumberOfSyntaxErrors()==0&&p.getCurrentToken().getType()==Token.EOF?type(node,source,substitutions):null;
    }
    private Map<String,Type> substitutions(Type receiver, Definition definition) {
        var names=formals(definition.node);var result=new HashMap<String,Type>();
        for(int i=0;i<names.size();i++)result.put(names.get(i),i<receiver.args.size()?receiver.args.get(i):new Type(names.get(i)));
        return result;
    }
    private List<Type> bases(Type receiver) {
        var definition=types.get(receiver.name);if(definition==null)return List.of();
        var nodes=new ArrayList<ValueTypeContext>();
        if(definition.node instanceof ClassDeclContext c) {if(c.valueType()!=null)nodes.add(c.valueType());if(c.typeList()!=null)nodes.addAll(c.typeList().valueType());}
        if(definition.node instanceof InterfaceDeclContext c && c.typeList()!=null)nodes.addAll(c.typeList().valueType());
        var env=substitutions(receiver,definition);
        return nodes.stream().map(n->type(n,definition.index,env)).filter(Objects::nonNull).toList();
    }
    private boolean subtype(Type from, Type to, Set<Type> seen) {
        if(from==null||to==null)return false;
        if(from.equals(to))return true;
        if(from.name.equals("रिक्त"))return !Set.of("पूर्णांक","लघुपूर्णांक","बाइट","दशमलव","तार्किक","पाठ","शून्य").contains(to.name);
        if(!seen.add(from))return false;
        return bases(from).stream().anyMatch(base->subtype(base,to,seen));
    }
    private String currentOwner(Position position) {
        int at=index.coordinates.offset(position);
        return index.declarations.entrySet().stream().filter(e->e.getKey().global()&&e.getKey().kind()!=SymbolKind.Function
                && index.coordinates.offset(e.getKey().range().getStart())<=at && index.coordinates.offset(e.getKey().range().getEnd())>=at)
                .map(e->e.getKey().qualified()).findFirst().orElse(null);
    }
    private boolean derivedFrom(Type type,String declaration,Set<String> seen) {
        if(type.name.equals(declaration))return true;
        if(!seen.add(type.name)||seen.size()>64)return false;
        return bases(type).stream().anyMatch(base->derivedFrom(base,declaration,seen));
    }
    private boolean accessible(Member member, Value receiver, Position position) {
        if(member.access.equals("सार्वजनिक"))return true;
        String here=currentOwner(position);
        if(here==null)return false;
        if(member.owner.equals(here))return true;
        return member.access.equals("संरक्षित") && derivedFrom(new Type(here),member.owner,new HashSet<>())
                && (receiver.shared || receiver.base || derivedFrom(receiver.type,here,new HashSet<>()));
    }
    private List<Member> members(Value receiver, Position position) {
        if(receiver==null||receiver.type==null)return List.of();
        var all=new LinkedHashMap<String,Member>();
        members(receiver.type,all,new HashSet<>());
        return all.values().stream().filter(m->m.shared==receiver.shared&&accessible(m,receiver,position)).toList();
    }
    private void members(Type receiver, Map<String,Member> result, Set<Type> seen) {
        if(!seen.add(receiver)||seen.size()>64)return;
        var definition=types.get(receiver.name);
        if(definition==null) {for(var member:builtins(receiver))result.putIfAbsent(member.name+member.callable+member.parameters,member);return;}
        var env=substitutions(receiver,definition);
        for(var e:definition.index.declarations.entrySet()) {
            var symbol=e.getKey();var node=e.getValue();
            if(owner(node)!=definition.node)continue;
            var member=member(symbol,node,definition.index,receiver.name,env);
            if(member!=null)result.putIfAbsent(member.name+member.callable+member.parameters,member);
        }
        if(definition.node instanceof ClassDeclContext c && c.RECORD()!=null && c.parameters()!=null)
            for(var p:c.parameters().parameter()) {
                Type value=type(p.valueType(),definition.index,env);if(value==null)continue;
                var symbol=definition.index.declarations.entrySet().stream().filter(e->e.getValue()==p).map(Map.Entry::getKey).findFirst().orElse(null);
                var member=new Member(p.IDENTIFIER().getText(),value,List.of(),List.of(),false,false,"सार्वजनिक",receiver.name,symbol);
                result.putIfAbsent(member.name+false+List.of(),member);
            }
        if(definition.node instanceof EnumDeclContext) {
            for(String property:List.of("नाम","अंक")) {
                var member=new Member(property,new Type(property.equals("नाम")?"पाठ":"पूर्णांक"),List.of(),List.of(),false,false,"सार्वजनिक",receiver.name,null);
                result.putIfAbsent(property+false+List.of(),member);
            }
        }
        for(Type base:bases(receiver))members(base,result,seen);
    }
    private Member member(SourceIndex.Symbol symbol, ParserRuleContext node, SourceIndex source, String owner, Map<String,Type> env) {
        Type result=null;ParametersContext parameters=null;boolean callable=false,shared=false;String access="सार्वजनिक";
        if(node instanceof FunctionDeclContext c){if(c.returnType()==null)return null;result=type(c.returnType().getText(),source,env);parameters=c.parameters();callable=true;}
        else if(node instanceof FieldDeclContext c)result=type(c.valueType(),source,env);
        else if(node instanceof EnumMemberContext){result=new Type(owner);shared=true;}
        else return null;
        if(node.getParent() instanceof ClassMemberContext c){shared=c.STATIC()!=null;if(c.visibility()!=null)access=c.visibility().getText();}
        if(result==null)return null;
        List<String> names=parameters==null?List.of():parameters.parameter().stream().map(p->p.IDENTIFIER().getText()).toList();
        List<Type> types=parameters==null?List.of():parameters.parameter().stream().map(p->type(p.valueType(),source,env)).toList();
        if(types.stream().anyMatch(Objects::isNull))return null;
        return new Member(symbol.name(),result,names,types,callable,shared,access,owner,symbol);
    }
    private static Member builtin(String name,String result,List<String> names,Type... args) {
        return new Member(name,new Type(result),names,List.of(args),true,false,"सार्वजनिक","",null);
    }
    private List<Member> builtins(Type type) {
        var result=new ArrayList<Member>();
        var integer=new Type("पूर्णांक");var bool=new Type("तार्किक");
        if(type.name.equals("अपवाद"))return List.of(new Member("संदेश",new Type("पाठ"),List.of(),List.of(),false,false,"सार्वजनिक","",null));
        boolean map=type.name.equals("मानचित्र"),list=type.name.equals("सूची"),buffer=type.name.equals("बाइटबफ़र");
        if(!map&&!list&&!buffer&&!type.name.equals("सरणी"))return List.of();
        if(!buffer && type.args.size()!=(map?2:1))return List.of();
        Type key=map?type.args.get(0):integer,element=buffer?new Type("बाइट"):type.args.get(map?1:0);
        for(String n:List.of("आकार","लंबाई")) {
            result.add(new Member(n,integer,List.of(),List.of(),false,false,"सार्वजनिक","",null));
            result.add(builtin(n,"पूर्णांक",List.of()));
        }
        result.add(builtin("खाली","तार्किक",List.of()));
        result.add(new Member("लो",element,List.of(map?"कुंजी":"सूचकांक"),List.of(key),true,false,"सार्वजनिक","",null));
        if(map)result.add(new Member("लो",element,List.of("कुंजी","वैकल्पिक"),List.of(key,element),true,false,"सार्वजनिक","",null));
        result.add(builtin("रखो","शून्य",List.of(map?"कुंजी":"सूचकांक","मानक"),key,element));
        result.add(builtin("है","तार्किक",List.of("मानक"),map?key:element));
        if(list) {
            result.add(builtin("जोड़ो","शून्य",List.of("मानक"),element));
            result.add(builtin("डालो","शून्य",List.of("सूचकांक","मानक"),integer,element));
        }
        if(map||list){result.add(new Member("हटाओ",map?bool:element,List.of(map?"कुंजी":"सूचकांक"),List.of(key),true,false,"सार्वजनिक","",null));result.add(builtin("साफ़","शून्य",List.of()));}
        if(map)for(String n:List.of("कुंजियाँ","मूल्य"))result.add(new Member(n,new Type("सूची",List.of(n.equals("कुंजियाँ")?key:element)),List.of(),List.of(),true,false,"सार्वजनिक","",null));
        if(buffer){
            result.add(builtin("पूर्णांकपढ़ो","पूर्णांक",List.of("स्थान","बड़ाक्रम"),integer,bool));
            result.add(builtin("पूर्णांकलिखो","शून्य",List.of("स्थान","मानक","बड़ाक्रम"),integer,integer,bool));
            result.add(builtin("दशमलवपढ़ो","दशमलव",List.of("स्थान","बड़ाक्रम"),integer,bool));
            result.add(builtin("दशमलवलिखो","शून्य",List.of("स्थान","मानक","बड़ाक्रम"),integer,new Type("दशमलव"),bool));
            result.add(builtin("काटो","बाइटबफ़र",List.of("आरंभ","अंत"),integer,integer));
            result.add(builtin("भरो","शून्य",List.of("मानक"),element));
            result.add(new Member("सरणी",new Type("सरणी",List.of(element)),List.of(),List.of(),true,false,"सार्वजनिक","",null));
        }
        return result;
    }
    private List<Member> globals(String name, TypeArgumentsContext arguments, Position position) {
        String full=qualify(name,index);var result=new ArrayList<Member>();
        for(var source:project)for(var e:source.declarations.entrySet()) {
            var symbol=e.getKey();
            if(!symbol.global()||!symbol.qualified().equals(full)||!symbol.exported()&&!source.pkg.equals(index.pkg)||!(e.getValue() instanceof FunctionDeclContext f))continue;
            var env=new HashMap<String,Type>();var names=formals(f);
            var actual=arguments==null?List.<ValueTypeContext>of():arguments.valueType();
            if(names.size()!=actual.size())continue;
            for(int i=0;i<names.size();i++)env.put(names.get(i),type(actual.get(i),index,Map.of()));
            var member=member(symbol,f,source,"",env);if(member!=null)result.add(member);
        }
        if(!result.isEmpty())return result;
        Type integer=new Type("पूर्णांक"),text=new Type("पाठ");
        Member builtin=switch(name){
            case "पढ़ो" -> builtin(name,"पूर्णांक",List.of());
            case "पाठपढ़ो" -> builtin(name,"पाठ",List.of());
            case "लंबाई" -> builtin(name,"पूर्णांक",List.of("पाठ्य"),text);
            case "अंश" -> builtin(name,"पाठ",List.of("पाठ्य","आरंभ","अंत"),text,integer,integer);
            case "अंकपाठ" -> builtin(name,"पाठ",List.of("अंक"),integer);
            case "पूर्णांकपाठ" -> builtin(name,"पूर्णांक",List.of("पाठ्य"),text);
            case "बड़ाअक्षर" -> builtin(name,"पाठ",List.of("पाठ्य"),text);
            case "यूटीएफ८बफ़र" -> builtin(name,"बाइटबफ़र",List.of("पाठ्य"),text);
            case "यूटीएफ८पाठ" -> builtin(name,"पाठ",List.of("बफ़र"),new Type("बाइटबफ़र"));
            case "त्रुटि" -> builtin(name,"शून्य",List.of("संदेश"),text);
            case "लिखो" -> builtin(name,"शून्य",List.of("मानक"),new Type("सर्ववस्तु"));
            default -> null;
        };
        return builtin==null?List.of():List.of(builtin);
    }
    private Type selected(List<Member> candidates, ArgumentsContext args, Position position, int depth) {
        var actual=args==null?List.<ExpressionContext>of():args.expression();
        var values=actual.stream().map(a->infer(a,position,depth+1)).toList();
        if(values.stream().anyMatch(Objects::isNull))return null;
        var matching=candidates.stream().filter(m->m.callable&&m.parameters.size()==values.size()).filter(m->{
            for(int i=0;i<values.size();i++)if(!subtype(values.get(i).type,m.parameters.get(i),new HashSet<>()))return false;return true;
        }).toList();
        var best=matching.stream().filter(m->matching.stream().allMatch(other->{
            for(int i=0;i<m.parameters.size();i++)if(!subtype(m.parameters.get(i),other.parameters.get(i),new HashSet<>()))return false;return true;
        })).toList();
        return best.size()==1?best.get(0).result:null;
    }
    private Value variable(String name, Position position) {
        var found=index.visibleLexical(position).stream().filter(s->SourceIndex.key(s.name()).equals(SourceIndex.key(name))).toList();
        if(!found.isEmpty()) {
            int width=found.stream().mapToInt(s->s.scopeEnd()-s.scopeStart()).min().orElse(0);
            var s=found.stream().filter(v->v.scopeEnd()-v.scopeStart()==width).findFirst().orElseThrow();
            var t=type(s.detail(),index,Map.of());
            if(t!=null)return new Value(t);
        }
        String here=currentOwner(position);
        if(here!=null) {
            var receiver=new Value(new Type(here));
            for(var member:members(receiver,position))if(member.name.equals(name)&&!member.callable)return new Value(member.result);
        }
        return null;
    }
    private Value infer(ParseTree node, Position position, int depth) {
        if(node==null||depth>128)return null;
        if(node instanceof PostfixContext p) {
            Value value=infer(p.primary(),position,depth+1);
            // Qualified type names can occupy several postfix components.
            int skip=0;
            if(p.primary().IDENTIFIER()!=null && p.primary().LPAREN()==null && variable(p.primary().IDENTIFIER().getText(),position)==null) {
                String candidate=p.primary().getText();
                for(int i=0;i<p.postfixPart().size();i++) {
                    var part=p.postfixPart(i);if(part.DOT()==null||part.LPAREN()!=null)break;
                    candidate+="."+part.getText().substring(1);
                    Type resolved=type(candidate,index,Map.of());
                    if(resolved!=null && types.containsKey(resolved.name)){value=new Value(resolved,true,false);skip=i+1;}
                }
            }
            for(int i=skip;i<p.postfixPart().size();i++) {
                if(value==null)return null;
                var part=p.postfixPart(i);
                if(part.LBRACK()!=null) {
                    var t=value.type;
                    if(t.name.equals("बाइटबफ़र"))value=new Value(new Type("बाइट"));
                    else if(Set.of("सरणी","सूची","मानचित्र").contains(t.name)&&!t.args.isEmpty())value=new Value(t.args.get(t.name.equals("मानचित्र")?1:0));
                    else return null;
                } else {
                    String member=SourceIndex.key(part.IDENTIFIER().getText());
                    var candidates=members(value,position).stream().filter(m->SourceIndex.key(m.name).equals(member)).toList();
                    Type result=part.LPAREN()!=null?selected(candidates,part.arguments(),position,depth+1):candidates.stream().filter(m->!m.callable).map(Member::result).findFirst().orElse(null);
                    value=result==null?null:new Value(result);
                }
            }
            return value;
        }
        if(node instanceof PrimaryContext p) {
            if(p.NEW()!=null) {
                Type t=type(p.valueType(),index,Map.of());if(t==null)return null;
                for(var ignored:p.arrayDimension())t=new Type("सरणी",List.of(t));
                return new Value(t);
            }
            if(p.TYPE_TEST()!=null)return new Value(new Type("तार्किक"));
            if(p.CONVERT()!=null||p.SAFE_CAST()!=null){var t=type(p.valueType(),index,Map.of());return t==null?null:new Value(t);}
            if(p.THIS()!=null||p.BASE()!=null) {
                String here=currentOwner(position);if(here==null)return null;
                Type t=new Type(here);
                if(p.BASE()!=null){var parents=bases(t);if(parents.isEmpty())return null;t=parents.get(0);}
                return new Value(t,false,p.BASE()!=null);
            }
            if(p.INTEGER_LITERAL()!=null)return new Value(new Type("पूर्णांक"));
            if(p.REAL_LITERAL()!=null)return new Value(new Type("दशमलव"));
            if(p.STRING_LITERAL()!=null)return new Value(new Type("पाठ"));
            if(p.TRUE()!=null||p.FALSE()!=null)return new Value(new Type("तार्किक"));
            if(p.NULL()!=null)return new Value(new Type("रिक्त"));
            if(p.IDENTIFIER()!=null) {
                String name=p.IDENTIFIER().getText();
                if(p.LPAREN()!=null) {
                    var candidates=globals(name,p.typeArguments(),position);
                    if(candidates.isEmpty()) {
                        String here=currentOwner(position);
                        if(here!=null)candidates=members(new Value(new Type(here)),position).stream().filter(m->m.name.equals(name)).toList();
                    }
                    Type result=selected(candidates,p.arguments(),position,depth+1);return result==null?null:new Value(result);
                }
                var local=variable(name,position);if(local!=null)return local;
                Type t=type(p.getText(),index,Map.of());
                if(t!=null&&types.containsKey(t.name))return new Value(t,true,false);
                return null;
            }
            return infer(p.expression(),position,depth+1);
        }
        if(node instanceof ParserRuleContext c) {
            // All precedence wrappers with a single operand preserve its type. For a
            // compound expression infer only unambiguous numeric/text/boolean results.
            var children=new ArrayList<ParseTree>();
            for(int i=0;i<c.getChildCount();i++)if(c.getChild(i) instanceof ParserRuleContext)children.add(c.getChild(i));
            if(children.size()==1)return infer(children.get(0),position,depth+1);
            if(children.size()>1) {
                var values=children.stream().map(n->infer(n,position,depth+1)).toList();
                if(values.stream().anyMatch(Objects::isNull))return null;
                if(c instanceof ComparisonContext||c instanceof EqualityContext||c instanceof LogicalAndContext||c instanceof LogicalOrContext)return new Value(new Type("तार्किक"));
                Type t=values.get(0).type;
                return values.stream().allMatch(v->v.type.equals(t))?new Value(t):null;
            }
        }
        return null;
    }
    private List<Token> before(int offset) {
        int cp=index.text.codePointCount(0,offset)-(index.text.startsWith("\uFEFF")?1:0);
        return index.tokens.stream().filter(t->t.getStopIndex()<cp).toList();
    }
    private String slice(Token start,int end) {
        return index.text.substring(index.coordinates.fromCodePoint(start.getStartIndex()+(index.text.startsWith("\uFEFF")?1:0)),end);
    }
    /** Parse the longest complete expression suffix ending immediately before a dot. */
    private Value receiver(int end,Position position) {
        var tokens=before(end);
        for(int i=Math.max(0,tokens.size()-160);i<tokens.size();i++) {
            var token=tokens.get(i);
            var parser=parser(slice(token,end));var node=parser.expression();
            if(parser.getNumberOfSyntaxErrors()==0&&parser.getCurrentToken().getType()==Token.EOF) {
                var result=infer(node,position,0);if(result!=null)return result;
                // A syntactically complete receiver with an unknown type must not be
                // replaced by a shorter, coincidentally matching identifier suffix.
                return null;
            }
        }
        return null;
    }
    private static boolean identifierPart(int cp) {
        return Character.isLetterOrDigit(cp)||Character.getType(cp)==Character.NON_SPACING_MARK||Character.getType(cp)==Character.COMBINING_SPACING_MARK||cp=='_';
    }
    private int wordStart(int offset) {
        while(offset>0&&identifierPart(index.text.codePointBefore(offset)))offset-=Character.charCount(index.text.codePointBefore(offset));
        return offset;
    }
    private int dotBefore(int start) {
        int p=start;while(p>0&&Character.isWhitespace(index.text.charAt(p-1)))p--;
        return p>0&&index.text.charAt(p-1)=='.'?p-1:-1;
    }
    List<CompletionItem> memberCompletions(Position position) {
        int offset=index.coordinates.offset(position),start=wordStart(offset),dot=dotBefore(start);
        if(dot<0)return null; // null means ordinary lexical completion; empty means unknown receiver.
        var result=new ArrayList<CompletionItem>();
        for(var member:members(receiver(dot,position),position)) {
            var item=new CompletionItem(member.name);item.setDetail(member.label());
            item.setKind(member.callable?CompletionItemKind.Method:CompletionItemKind.Field);
            item.setTextEdit(Either.forLeft(new TextEdit(new Range(index.coordinates.position(start),position),member.name)));
            result.add(item);
        }
        return result;
    }
    List<CompletionItem> autoImports(Position position) {
        int offset=index.coordinates.offset(position),start=wordStart(offset);
        if(start==offset)return List.of();
        String prefix=SourceIndex.key(index.text.substring(start,offset));
        if(dotBefore(start)>=0)return List.of();
        var occupied=new HashSet<String>(index.imports.keySet());
        for(var source:project)if(source.pkg.equals(index.pkg))for(var s:source.symbols)if(s.global())occupied.add(SourceIndex.key(s.name()));
        for(var s:index.visibleLexical(position))occupied.add(SourceIndex.key(s.name()));
        var result=new ArrayList<CompletionItem>();var seen=new HashSet<String>();
        int insert=0;
        if(index.tree.packageDecl()!=null)insert=index.coordinates.fromCodePoint(index.tree.packageDecl().stop.getStopIndex()+1+(index.text.startsWith("\uFEFF")?1:0));
        for(var imp:index.tree.importDecl())if(imp.stop!=null)insert=Math.max(insert,index.coordinates.fromCodePoint(imp.stop.getStopIndex()+1+(index.text.startsWith("\uFEFF")?1:0)));
        if(insert==0 && index.text.startsWith("\uFEFF"))insert=1;
        String newline=index.text.contains("\r\n")?"\r\n":"\n";
        for(var source:project)for(var symbol:source.symbols) {
            String name=SourceIndex.key(symbol.name());
            if(!symbol.global()||!symbol.exported()||source.pkg.equals(index.pkg)||occupied.contains(name)||!name.startsWith(prefix)||!seen.add(symbol.qualified())||index.imports.containsValue(symbol.qualified()))continue;
            var item=new CompletionItem(symbol.name());item.setKind(symbol.kind()==SymbolKind.Function?CompletionItemKind.Function:CompletionItemKind.Class);
            item.setDetail("आयात "+symbol.qualified());item.setSortText("z_"+name+symbol.qualified());
            item.setTextEdit(Either.forLeft(new TextEdit(new Range(index.coordinates.position(start),position),symbol.name())));
            var at=index.coordinates.position(insert);
            // Insert before a declaration at offset zero; otherwise start a new line
            // after the package/import semicolon, preserving trailing comments.
            if(insert>0) {
                int end=index.text.indexOf('\n',insert);
                String tail=index.text.substring(insert,end<0?index.text.length():end).strip();
                if(tail.startsWith("//")){insert=end<0?index.text.length():end;at=index.coordinates.position(insert);}
            }
            String addition=(insert>0?newline:"")+"आयात "+symbol.qualified()+";"+(insert==0?newline:"");
            item.setAdditionalTextEdits(List.of(new TextEdit(new Range(at,at),addition)));result.add(item);
        }
        return result;
    }
    List<SourceIndex.Symbol> resolveMember(Position position) {
        int offset=index.coordinates.offset(position),start=wordStart(offset),dot=dotBefore(start);
        if(dot<0)return List.of();
        int end=offset;while(end<index.text.length()&&identifierPart(index.text.codePointAt(end)))end+=Character.charCount(index.text.codePointAt(end));
        String name=SourceIndex.key(index.text.substring(start,end));
        return members(receiver(dot,position),position).stream().filter(m->SourceIndex.key(m.name).equals(name)&&m.symbol!=null).map(Member::symbol).distinct().toList();
    }
    private Set<Integer> genericCommas(List<Token> tokens) {
        var result=new HashSet<Integer>();
        for(int start=1;start<tokens.size();start++) {
            if(!tokens.get(start).getText().equals("<"))continue;
            int previous=tokens.get(start-1).getType();
            if(previous!=BhashLexer.IDENTIFIER && previous!=BhashLexer.CONVERT && previous!=BhashLexer.TYPE_TEST && previous!=BhashLexer.SAFE_CAST)continue;
            int angle=0;var commas=new ArrayList<Integer>();
            for(int i=start;i<tokens.size();i++) {
                String t=tokens.get(i).getText();int kind=tokens.get(i).getType();
                if(t.equals("<"))angle++;
                else if(t.equals(">")) {
                    if(--angle==0) {
                        if(i+1<tokens.size()&&Set.of("(",".","{","[",">").contains(tokens.get(i+1).getText()))result.addAll(commas);
                        break;
                    }
                } else if(t.equals(","))commas.add(i);
                else if(kind!=BhashLexer.IDENTIFIER && !Set.of(".","[","]").contains(t)
                        && !BUILTIN_TYPES.contains(t))break;
            }
        }
        return result;
    }
    SignatureHelp signatureHelp(Position position) {
        var tokens=before(index.coordinates.offset(position));
        int depth=0,comma=0,open=-1;
        var typeCommas=genericCommas(tokens);
        for(int i=tokens.size()-1;i>=0;i--) {
            String t=tokens.get(i).getText();
            if(Set.of(")","]","}").contains(t))depth++;
            else if(Set.of("(","[","{").contains(t)) {if(depth>0)depth--;else {if(t.equals("(")){open=i;break;}return null;}}
            else if(depth==0&&t.equals(",")&&!typeCommas.contains(i))comma++;
        }
        if(open<=0)return null;
        int nameEnd=open-1;TypeArgumentsContext arguments=null;
        // Explicit generic arguments are part of the callable name, not parameters.
        if(tokens.get(nameEnd).getText().equals(">")) {
            int angle=1,end=nameEnd;
            while(--nameEnd>=0) {String t=tokens.get(nameEnd).getText();if(t.equals(">"))angle++;if(t.equals("<")&&--angle==0)break;}
            if(nameEnd<=0)return null;
            var p=parser(slice(tokens.get(nameEnd),index.coordinates.fromCodePoint(tokens.get(end).getStopIndex()+1+(index.text.startsWith("\uFEFF")?1:0))));arguments=p.typeArguments();nameEnd--;
        }
        Token name=tokens.get(nameEnd);String spelling=name.getText();
        int nameStart=index.coordinates.fromCodePoint(name.getStartIndex()+(index.text.startsWith("\uFEFF")?1:0));
        int dot=dotBefore(nameStart);List<Member> candidates;
        int pathStart=nameEnd;
        while(pathStart>=2 && tokens.get(pathStart-1).getText().equals(".") && tokens.get(pathStart-2).getType()==BhashLexer.IDENTIFIER)pathStart-=2;
        int callableEnd=index.coordinates.fromCodePoint(tokens.get(open-1).getStopIndex()+1+(index.text.startsWith("\uFEFF")?1:0));
        if(pathStart>0&&tokens.get(pathStart-1).getText().equals("नया"))
            candidates=constructors(type(slice(tokens.get(pathStart),callableEnd),index,Map.of()),position);
        else if(dot>=0) {
            var target=receiver(dot,position);
            candidates=members(target,position).stream().filter(m->m.callable&&SourceIndex.key(m.name).equals(SourceIndex.key(spelling))).toList();
            if(target==null && pathStart<nameEnd) {
                String qualified=String.join("",tokens.subList(pathStart,nameEnd+1).stream().map(Token::getText).toList());
                candidates=globals(qualified,arguments,position);
            }
        } else candidates=globals(spelling,arguments,position);
        if(candidates.isEmpty()) {
            String here=currentOwner(position);
            if(dot<0&&here!=null)candidates=members(new Value(new Type(here)),position).stream().filter(m->m.callable&&m.name.equals(spelling)).toList();
        }
        if(candidates.isEmpty())return null;
        var signatures=new ArrayList<SignatureInformation>();
        for(var member:candidates) {
            var signature=new SignatureInformation(member.label());
            var parameters=new ArrayList<ParameterInformation>();
            for(int i=0;i<member.parameters.size();i++)parameters.add(new ParameterInformation(member.names.get(i)+": "+member.parameters.get(i)));
            signature.setParameters(parameters);signatures.add(signature);
        }
        int active=0;for(int i=0;i<candidates.size();i++)if(candidates.get(i).parameters.size()>comma){active=i;break;}
        var help=new SignatureHelp(signatures,active,null);
        if(comma<candidates.get(active).parameters.size())help.setActiveParameter(comma);
        return help;
    }
    private List<Member> constructors(Type type,Position position) {
        if(type==null)return List.of();var definition=types.get(type.name);
        if(definition==null) {
            if(Set.of("सूची","मानचित्र").contains(type.name))return List.of(new Member(type.name,type,List.of(),List.of(),true,false,"सार्वजनिक","",null));
            if(Set.of("सरणी","बाइटबफ़र").contains(type.name))return List.of(new Member(type.name,type,List.of("आकार"),List.of(new Type("पूर्णांक")),true,false,"सार्वजनिक","",null));
            if(type.name.equals("अपवाद"))return List.of(new Member("अपवाद",type,List.of(),List.of(),true,false,"सार्वजनिक","",null),new Member("अपवाद",type,List.of("संदेश"),List.of(new Type("पाठ")),true,false,"सार्वजनिक","",null));
            return List.of();
        }
        var result=new ArrayList<Member>();var env=substitutions(type,definition);
        if(definition.node instanceof ClassDeclContext c && c.RECORD()!=null) {
            var ps=c.parameters()==null?List.<ParameterContext>of():c.parameters().parameter();
            result.add(new Member(name(c),type,ps.stream().map(p->p.IDENTIFIER().getText()).toList(),ps.stream().map(p->type(p.valueType(),definition.index,env)).toList(),true,false,"सार्वजनिक",type.name,definition.symbol));
        } else if(definition.node instanceof ClassDeclContext c) {
            boolean declared=false;
            for(var m:c.classMember())if(m.constructorDecl()!=null) {
                declared=true;var ps=m.constructorDecl().parameters()==null?List.<ParameterContext>of():m.constructorDecl().parameters().parameter();
                var member=new Member(name(c),type,ps.stream().map(p->p.IDENTIFIER().getText()).toList(),ps.stream().map(p->type(p.valueType(),definition.index,env)).toList(),true,false,m.visibility()==null?"सार्वजनिक":m.visibility().getText(),type.name,definition.symbol);
                if(accessible(member,new Value(type),position))result.add(member);
            }
            if(!declared)result.add(new Member(name(c),type,List.of(),List.of(),true,false,"सार्वजनिक",type.name,definition.symbol));
        }
        return result;
    }
}
