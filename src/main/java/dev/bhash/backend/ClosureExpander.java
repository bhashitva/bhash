// SPDX-License-Identifier: Apache-2.0
package dev.bhash.backend;

import dev.bhash.frontend.Frontend;
import dev.bhash.frontend.Identifiers;
import dev.bhash.frontend.LocatedToken;
import dev.bhash.parser.BhashParser.*;
import java.util.*;
import org.antlr.v4.runtime.*;
import org.antlr.v4.runtime.tree.*;

/** Closure conversion shared by every backend. Environments and shared cells are ordinary GC objects. */
final class ClosureExpander {
    private static final String INVOKE="आह्वान";
    private static final class Binding {
        final String name, type; final boolean mutable; final Lambda owner;
        boolean captured; String cell, storage;
        Binding(String name,String type,boolean mutable,Lambda owner) {
            this.name=name;this.type=type;this.mutable=mutable;this.owner=owner;
        }
    }
    private static final class Lambda {
        final LambdaExpressionContext tree; final Lambda parent; final String name, contract;
        String lexicalClass, bridge;
        final LinkedHashMap<Binding,String> captures=new LinkedHashMap<>();
        Lambda(LambdaExpressionContext tree,Lambda parent,String name,String contract) {
            this.tree=tree;this.parent=parent;this.name=name;this.contract=contract;
        }
    }
    private record Signature(String name,List<String> arguments,String result,Token origin) {}
    private final String source;
    private final Set<String> names=new HashSet<>();
    private final Map<String,Signature> signatures=new LinkedHashMap<>();
    private final Map<String,List<FunctionDeclContext>> functions=new HashMap<>();
    private final Map<String,ClassDeclContext> classes=new HashMap<>();
    private final Map<PrimaryContext,String> memberReads=new IdentityHashMap<>();
    private final Set<PrimaryContext> callableMembers=Collections.newSetFromMap(new IdentityHashMap<>());
    private final Map<PrimaryContext,String> staticCallbacks=new IdentityHashMap<>();
    private final List<Binding> bindings=new ArrayList<>();
    private final Map<ParseTree,Binding> declarations=new IdentityHashMap<>();
    private final Map<PrimaryContext,Binding> reads=new IdentityHashMap<>();
    private final Map<LambdaExpressionContext,Lambda> lambdas=new IdentityHashMap<>();
    private final List<Lambda> ordered=new ArrayList<>();
    private final Map<PrimaryContext,FunctionDeclContext> references=new IdentityHashMap<>();
    private final Map<FunctionDeclContext,String> wrappers=new LinkedHashMap<>();
    private final Deque<Map<String,Binding>> scopes=new ArrayDeque<>();
    private final StringBuilder text=new StringBuilder();
    private final NavigableMap<Integer,Token> origins=new TreeMap<>();
    private int next,offset;
    private Lambda current;
    private String lexicalClass;
    private boolean staticMember, inBridge;
    private Binding self;

    private ClosureExpander(String source) { this.source=source; }
    static ProgramContext expand(String source,ProgramContext program)throws CompilationException {
        return new ClosureExpander(source).program(program);
    }
    private CompilationException error(ParserRuleContext at,String message) {
        return new CompilationException(LocatedToken.source(at.start,source)+":"+at.start.getLine()+":"+(at.start.getCharPositionInLine()+1)+": "+message);
    }
    private String fresh() { String s;do{s="बंदफलन_"+next++;}while(!names.add(s));return s; }
    private static String key(String s){return Identifiers.key(s);}
    private void names(ParseTree n) {
        if(n instanceof TerminalNode t)names.add(key(t.getText()));
        else for(int i=0;i<n.getChildCount();i++)names(n.getChild(i));
    }
    private boolean usesFunctions(ParseTree n) {
        if(n instanceof FunctionTypeContext || n instanceof LambdaExpressionContext)return true;
        if(n instanceof PrimaryContext p && p.IDENTIFIER()!=null && p.LPAREN()==null && functions.containsKey(key(p.IDENTIFIER().getText())))return true;
        if(n instanceof PostfixPartContext p && p.LPAREN()!=null && p.IDENTIFIER()==null)return true;
        for(int i=0;i<n.getChildCount();i++)if(usesFunctions(n.getChild(i)))return true;
        return false;
    }
    private String type(ValueTypeContext t) {
        String base;
        if(t.functionType()!=null) {
            var f=t.functionType();base=signature(f.valueType().stream().map(this::type).toList(),result(f.returnType()),f.start);
        } else {
            base=t.getChild(0).getText();
            for(var q:t.typeQualifier())base+=q.getText();
            if(t.typeArguments()!=null)base+="<"+String.join(",",t.typeArguments().valueType().stream().map(this::type).toList())+">";
        }
        return base+"[]".repeat(t.arraySuffix().size());
    }
    private String result(ReturnTypeContext r){return r.VOID()!=null?"शून्य":type(r.valueType());}
    private String signature(List<String> args,String result,Token origin) {
        String k=args+"->"+result;
        return signatures.computeIfAbsent(k,ignored->new Signature(fresh(),args,result,origin)).name;
    }
    private List<String> parameterTypes(ParametersContext p){return p==null?List.of():p.parameter().stream().map(x->type(x.valueType())).toList();}
    private Binding declare(ParseTree node,String name,String type,boolean mutable)throws CompilationException {
        var b=new Binding(name,type,mutable,current);
        if(scopes.peek().putIfAbsent(key(name),b)!=null)throw error((ParserRuleContext)node,"Duplicate binding: "+name);
        declarations.put(node,b);bindings.add(b);return b;
    }
    private Binding find(String name){for(var scope:scopes){var b=scope.get(key(name));if(b!=null)return b;}return null;}
    private void capture(Binding b) {
        for(Lambda l=current;l!=b.owner && l!=null;l=l.parent) {
            l.captures.computeIfAbsent(b,ignored->fresh());b.captured=true;
        }
    }
    private void parameters(ParametersContext p)throws CompilationException {
        if(p!=null)for(var a:p.parameter())declare(a,a.IDENTIFIER().getText(),type(a.valueType()),false);
    }
    private void analyze(ParseTree n)throws CompilationException {
        if(n instanceof ClassDeclContext c) {
            var saved=lexicalClass;lexicalClass=c.IDENTIFIER().getText();
            for(var m:c.classMember())analyze(m);lexicalClass=saved;return;
        }
        if(n instanceof ClassMemberContext m) {
            boolean saved=staticMember;staticMember=m.STATIC()!=null;
            for(int i=0;i<m.getChildCount();i++)analyze(m.getChild(i));staticMember=saved;return;
        }
        if(n instanceof LambdaExpressionContext l) {
            var lambda=new Lambda(l,current,fresh(),signature(parameterTypes(l.parameters()),result(l.returnType()),l.start));
            lambda.lexicalClass=lexicalClass;if(lexicalClass!=null)lambda.bridge=fresh();
            lambdas.put(l,lambda);ordered.add(lambda);var saved=current;current=lambda;
            scopes.push(new HashMap<>());parameters(l.parameters());analyze(l.block());scopes.pop();current=saved;return;
        }
        if(n instanceof FunctionDeclContext f) {
            var saved=self;self=lexicalClass!=null && !staticMember?new Binding("यह",lexicalClass,false,null):null;
            scopes.push(new HashMap<>());parameters(f.parameters());if(f.block()!=null)analyze(f.block());scopes.pop();self=saved;return;
        }
        if(n instanceof ConstructorDeclContext c) {
            var saved=self;self=new Binding("यह",lexicalClass,false,null);
            scopes.push(new HashMap<>());parameters(c.parameters());if(c.arguments()!=null)analyze(c.arguments());analyze(c.block());scopes.pop();self=saved;return;
        }
        if(n instanceof VariableDeclContext v) {
            analyze(v.expression());declare(v,v.IDENTIFIER().getText(),type(v.valueType()),v.LET()!=null);return;
        }
        if(n instanceof ForeachStatementContext f) {
            analyze(f.expression());scopes.push(new HashMap<>());
            for(var p:f.parameter())declare(p,p.IDENTIFIER().getText(),type(p.valueType()),false);
            analyze(f.block());scopes.pop();return;
        }
        if(n instanceof CatchClauseContext c) {
            scopes.push(new HashMap<>());var p=c.parameter();declare(p,p.IDENTIFIER().getText(),type(p.valueType()),false);
            analyze(c.block());scopes.pop();return;
        }
        if(n instanceof ResourceStatementContext r) {
            analyze(r.expression());scopes.push(new HashMap<>());var p=r.parameter();declare(p,p.IDENTIFIER().getText(),type(p.valueType()),false);
            analyze(r.block());scopes.pop();return;
        }
        if(n instanceof PrimaryContext p && p.IDENTIFIER()!=null) {
            Binding b=find(p.IDENTIFIER().getText());
            if(b!=null){reads.put(p,b);capture(b);}
            else if(callableField(lexicalClass,p.IDENTIFIER().getText()) && member(lexicalClass,p.IDENTIFIER().getText()).STATIC()!=null) {
                staticCallbacks.put(p,lexicalClass);
            } else if(self!=null && member(lexicalClass,p.IDENTIFIER().getText())!=null && member(lexicalClass,p.IDENTIFIER().getText()).STATIC()==null && (current!=null || callableField(lexicalClass,p.IDENTIFIER().getText()))) {
                reads.put(p,self);memberReads.put(p,p.IDENTIFIER().getText());capture(self);
                if(callableField(lexicalClass,p.IDENTIFIER().getText()))callableMembers.add(p);
            } else if(p.LPAREN()==null && functions.containsKey(key(p.IDENTIFIER().getText()))) {
                var candidates=functions.get(key(p.IDENTIFIER().getText()));
                if(candidates.size()!=1)throw error(p,"Overloaded function reference requires an explicitly typed lambda.");
                var f=candidates.get(0);references.put(p,f);wrappers.computeIfAbsent(f,ignored->fresh());
                signature(parameterTypes(f.parameters()),result(f.returnType()),f.start);
            }
        }
        if(n instanceof PrimaryContext p && p.THIS()!=null && (current!=null || self!=null)) {
            if(self==null)throw error(p,"यह requires an instance context.");
            reads.put(p,self);capture(self);
        }
        if(n instanceof PrimaryContext p && current!=null && p.BASE()!=null)
            throw error(p,"Use an enclosing method to perform आधार dispatch from a lambda.");
        boolean block=n instanceof BlockContext || n instanceof SwitchSectionContext;
        if(block)scopes.push(new HashMap<>());
        for(int i=0;i<n.getChildCount();i++)analyze(n.getChild(i));
        if(block)scopes.pop();
    }
    private void append(String s,Token token){origins.put(offset,token);text.append(s).append(' ');offset+=s.codePointCount(0,s.length())+1;}
    private String storage(Binding b) {
        if(current!=null && current.captures.containsKey(b))return (inBridge?"":"यह.")+current.captures.get(b);
        return b.cell!=null?b.storage:b.name;
    }
    private String read(Binding b){return storage(b)+(b.cell!=null?".मूल्य":"");}
    private boolean callable(String type){return signatures.values().stream().anyMatch(s->s.name.equals(type));}
    private boolean callableField(String owner,String name) {
        return callable(fieldType(owner,name));
    }
    private String fieldType(String owner,String name) {
        var m=member(owner,name);if(m!=null && m.fieldDecl()!=null)return type(m.fieldDecl().valueType());
        var c=classes.get(owner);
        if(c!=null && c.RECORD()!=null && c.parameters()!=null)for(var p:c.parameters().parameter())
            if(key(p.IDENTIFIER().getText()).equals(key(name)))return type(p.valueType());
        return "";
    }
    private String inferred(ParseTree n) {
        if(n instanceof PrimaryContext p) {
            var b=reads.get(p);
            if(b!=null) {
                String t=b.type;
                if(memberReads.containsKey(p)){var m=member(t,memberReads.get(p));t=m.fieldDecl()!=null?type(m.fieldDecl().valueType()):result(m.functionDecl().returnType());}
                if(p.LPAREN()!=null && (memberReads.get(p)==null || callableMembers.contains(p)))return invocationResult(t);
                return t;
            }
            if(p.lambdaExpression()!=null)return lambdas.get(p.lambdaExpression()).contract;
            if(p.valueType()!=null)return type(p.valueType());
            if(p.IDENTIFIER()!=null && p.LPAREN()==null && classes.containsKey(p.IDENTIFIER().getText()))return p.IDENTIFIER().getText();
            if(p.IDENTIFIER()!=null && p.LPAREN()!=null){var fs=functions.get(key(p.IDENTIFIER().getText()));if(fs!=null && fs.size()==1)return result(fs.get(0).returnType());}
            if(p.expression()!=null)return inferred(p.expression());
            return "";
        }
        if(n instanceof PostfixContext p){String t=inferred(p.primary());for(var part:p.postfixPart())t=following(t,part);return t;}
        return n.getChildCount()==1?inferred(n.getChild(0)):"";
    }
    private String invocationResult(String type){return signatures.values().stream().filter(s->s.name.equals(type)).map(s->s.result).findFirst().orElse("");}
    private String following(String owner,PostfixPartContext p) {
        if(p.LBRACK()!=null) {
            if(owner.endsWith("[]"))return owner.substring(0,owner.length()-2);
            int start=owner.indexOf('<'), depth=0,last=start;
            if(start>=0){for(int i=start+1;i<owner.length()-1;i++){char c=owner.charAt(i);if(c=='<')depth++;else if(c=='>')depth--;else if(c==',' && depth==0)last=i;}return owner.substring(last+1,owner.length()-1);}
            return "";
        }
        if(p.IDENTIFIER()==null)return invocationResult(owner);
        String field=fieldType(owner,p.IDENTIFIER().getText());
        if(!field.isEmpty())return p.LPAREN()!=null?invocationResult(field):field;
        var m=member(owner,p.IDENTIFIER().getText());if(m==null)return "";
        String t=m.fieldDecl()!=null?type(m.fieldDecl().valueType()):result(m.functionDecl().returnType());
        return m.fieldDecl()!=null && p.LPAREN()!=null?invocationResult(t):t;
    }
    private ClassMemberContext member(String owner,String name) {
        return member(owner,name,new HashSet<>());
    }
    private ClassMemberContext member(String owner,String name,Set<String> visited) {
        if(!visited.add(owner))return null;
        var c=classes.get(owner);if(c==null)return null;
        for(var m:c.classMember()) {
            String n=m.fieldDecl()!=null?m.fieldDecl().IDENTIFIER().getText():m.functionDecl()!=null?m.functionDecl().IDENTIFIER().getText():"";
            if(key(n).equals(key(name)))return m;
        }
        return c.valueType()!=null?member(type(c.valueType()),name,visited):null;
    }
    private void render(ParseTree n)throws CompilationException {
        if(n instanceof PostfixContext p) {
            String owner=inferred(p.primary());render(p.primary());
            for(var part:p.postfixPart()) {
                if(part.IDENTIFIER()==null && part.LPAREN()!=null && !owner.isEmpty() && !callable(owner))
                    throw error(part,"Expression is not a function value.");
                if(part.IDENTIFIER()!=null && part.LPAREN()!=null && callableField(owner,part.IDENTIFIER().getText())) {
                    append("."+part.IDENTIFIER().getText()+"."+INVOKE+"(",part.start);
                    if(part.arguments()!=null)render(part.arguments());append(")",part.stop);
                }else render(part);
                owner=following(owner,part);
            }
            return;
        }
        if(n instanceof ClassDeclContext c && c.RECORD()==null) {
            for(int i=0;i<n.getChildCount()-1;i++)render(n.getChild(i));
            for(var l:ordered)if(c.IDENTIFIER().getText().equals(l.lexicalClass)) {
                var params=new ArrayList<String>();
                for(var e:l.captures.entrySet())params.add(e.getValue()+":"+(e.getKey().cell!=null?e.getKey().cell:e.getKey().type));
                append("सार्वजनिक साझा फलन "+l.bridge+"("+String.join(",",params),l.tree.start);
                if(l.tree.parameters()!=null){if(!params.isEmpty())append(",",l.tree.start);render(l.tree.parameters());}
                append(")->"+result(l.tree.returnType()),l.tree.start);
                current=l;inBridge=true;render(l.tree.block());current=null;inBridge=false;
            }
            render(n.getChild(n.getChildCount()-1));return;
        }
        if(n instanceof ValueTypeContext t){append(type(t),t.start);return;}
        if(n instanceof ShiftOperatorContext s){append(s.getText(),s.start);return;}
        if(n instanceof LambdaExpressionContext l) {
            var lambda=lambdas.get(l);append("नया "+lambda.name+"("+String.join(",",lambda.captures.keySet().stream().map(this::storage).toList())+")",l.start);return;
        }
        if(n instanceof VariableDeclContext v) {
            var b=declarations.get(v);
            if(b.cell!=null){append("स्थिर "+b.storage+":"+b.cell+"= नया "+b.cell+"(",v.start);render(v.expression());append(");",v.stop);return;}
        }
        if(n instanceof AssignmentContext a) {
            var p=a.postfix().primary();var b=reads.get(p);
            if(b!=null && !b.mutable && !memberReads.containsKey(p) && a.postfix().postfixPart().isEmpty())throw error(a,"Cannot assign to an immutable binding or field.");
        }
        if(n instanceof PrimaryContext p) {
            if(staticCallbacks.containsKey(p)) {
                append(staticCallbacks.get(p)+"."+p.IDENTIFIER().getText(),p.start);
                if(p.LPAREN()!=null){append("."+INVOKE+"(",p.start);if(p.arguments()!=null)render(p.arguments());append(")",p.stop);}
                return;
            }
            var b=reads.get(p);
            if(b!=null){
                append(read(b),p.start);
                var member=memberReads.get(p);
                if(member!=null)append("."+member,p.start);
                if(p.LPAREN()!=null){
                    if(member==null && !callable(b.type))throw error(p,"Binding is not callable: "+b.name);
                    append((member==null || callableMembers.contains(p)?"."+INVOKE:"")+"(",p.start);if(p.arguments()!=null)render(p.arguments());append(")",p.stop);
                }
                return;
            }
            var f=references.get(p);
            if(f!=null){append("नया "+wrappers.get(f)+"()",p.start);return;}
        }
        if(n instanceof PostfixPartContext p && p.LPAREN()!=null && p.IDENTIFIER()==null) {
            append("."+INVOKE+"(",p.start);if(p.arguments()!=null)render(p.arguments());append(")",p.stop);return;
        }
        if(n instanceof TerminalNode t){if(t.getSymbol().getType()!=Token.EOF)append(t.getText(),t.getSymbol());return;}
        for(int i=0;i<n.getChildCount();i++)render(n.getChild(i));
    }
    private String arguments(int count){var a=new ArrayList<String>();for(int i=0;i<count;i++)a.add("अ_"+i);return String.join(",",a);}
    private String parameters(List<String> types){var a=new ArrayList<String>();for(int i=0;i<types.size();i++)a.add("अ_"+i+":"+types.get(i));return String.join(",",a);}
    private ProgramContext program(ProgramContext program)throws CompilationException {
        names(program);
        for(var c:program.classDecl())classes.put(c.IDENTIFIER().getText(),c);
        for(var f:program.functionDecl())functions.computeIfAbsent(key(f.IDENTIFIER().getText()),ignored->new ArrayList<>()).add(f);
        if(!usesFunctions(program))return program;
        scopes.push(new HashMap<>());analyze(program);scopes.pop();
        // Only mutable captured locals need shared cells. Other locals keep their original representation.
        for(var b:bindings)if(b.captured && b.mutable){b.cell=fresh();b.storage=fresh();}
        render(program);
        for(var b:bindings)if(b.cell!=null)append("वर्ग "+b.cell+" { सार्वजनिक मान मूल्य:"+b.type+"; निर्माता(अ:"+b.type+"){यह.मूल्य=अ;} }",program.start);
        for(var lambda:ordered) {
            var l=lambda.tree;append("वर्ग "+lambda.name+" पालन "+lambda.contract+" {",l.start);
            var params=new ArrayList<String>();var assigns=new StringBuilder();int i=0;
            for(var e:lambda.captures.entrySet()) {
                var b=e.getKey();String type=b.cell!=null?b.cell:b.type;String arg="अ_"+i++;
                append("निजी मान "+e.getValue()+":"+type+";",l.start);params.add(arg+":"+type);assigns.append("यह.").append(e.getValue()).append('=').append(arg).append(';');
            }
            append("निर्माता("+String.join(",",params)+"){"+assigns+"} सार्वजनिक फलन "+INVOKE+"(",l.start);
            if(l.parameters()!=null)render(l.parameters());append(")->"+result(l.returnType()),l.start);
            if(lambda.bridge==null){current=lambda;render(l.block());current=null;}
            else {
                var args=new ArrayList<String>();for(var field:lambda.captures.values())args.add("यह."+field);
                if(l.parameters()!=null)for(var p:l.parameters().parameter())args.add(p.IDENTIFIER().getText());
                append("{"+(result(l.returnType()).equals("शून्य")?"":"लौटाओ ")+lambda.lexicalClass+"."+lambda.bridge+"("+String.join(",",args)+");}",l.start);
            }
            append("}",l.stop);
        }
        for(var e:wrappers.entrySet()) {
            var f=e.getKey();var args=parameterTypes(f.parameters());var r=result(f.returnType());var contract=signature(args,r,f.start);
            append("वर्ग "+e.getValue()+" पालन "+contract+" { सार्वजनिक फलन "+INVOKE+"("+parameters(args)+")->"+r+" { "+(r.equals("शून्य")?"":"लौटाओ ")+f.IDENTIFIER().getText()+"("+arguments(args.size())+"); } }",f.start);
        }
        for(var s:signatures.values())append("अनुबंध "+s.name+" { फलन "+INVOKE+"("+parameters(s.arguments)+")->"+s.result+"; }",s.origin);
        if(signatures.isEmpty())return program;
        var parsed=Frontend.parse(source,text.toString());
        if(!parsed.valid())throw new CompilationException("Internal closure conversion error: "+parsed.diagnostics());
        ProjectResolver.remap(parsed.tree(),origins);return parsed.tree();
    }
}
