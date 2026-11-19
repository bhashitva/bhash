// SPDX-License-Identifier: Apache-2.0
package dev.bhash.backend;

import dev.bhash.frontend.*;
import dev.bhash.parser.BhashParser.*;
import java.util.*;
import org.antlr.v4.runtime.*;
import org.antlr.v4.runtime.tree.*;

/** Source-located specialization and local inference. The ordinary checker remains authoritative. */
final class GenericExpander {
    private record Ty(String name,List<Ty> args,int rank,Set<Integer> nullable) {
        Ty(String name,List<Ty> args,int rank) { this(name,args,rank,Set.of()); }
        Ty(String name) { this(Identifiers.key(name),List.of(),0); }
        Ty { name=Identifiers.key(name);args=List.copyOf(args);nullable=Set.copyOf(nullable); }
        Ty element() { return rank>0?new Ty(name,args,rank-1,nullable.stream().filter(n->n<rank).collect(java.util.stream.Collectors.toSet())):args.isEmpty()?null:args.get(args.size()-1); }
        Ty array() { return new Ty(name,args,rank+1,nullable); }
        boolean optional() { return nullable.contains(rank); }
        Ty optionalType() { var marks=new HashSet<>(nullable);marks.add(rank);return new Ty(name,args,rank,marks); }
        Ty nonNull() { var marks=new HashSet<>(nullable);marks.remove(rank);return new Ty(name,args,rank,marks); }
        String decorate(String base) {
            var result=new StringBuilder(function()&&(rank>0||nullable.contains(0))?"("+base+")":base);
            if(nullable.contains(0))result.append('?');
            for(int i=1;i<=rank;i++){result.append("[]");if(nullable.contains(i))result.append('?');}
            return result.toString();
        }
        boolean function() { return name.equals("फलन"); }
        @Override public String toString() {
            String base=function()?"फलन("+String.join(",",args.subList(0,args.size()-1).stream().map(Object::toString).toList())+")->"+args.get(args.size()-1)
                    :name+(args.isEmpty()?"":"<"+String.join(",",args.stream().map(Object::toString).toList())+">");
            return decorate(base);
        }
    }
    private record Template(ParserRuleContext tree,TypeParametersContext parameters,Token name) {}
    private record Instance(Template template,Map<String,Ty> subst,String name,Ty type) {}
    private record Method(Ty owner,FunctionDeclContext tree,Map<String,Ty> subst) {}
    private record Use(Method method,List<Ty> args,String name) {}
    private record Call(FunctionDeclContext tree,Map<String,Ty> subst,List<Ty> parameters,Ty result,Method method) {}
    private static final Ty INT=new Ty("पूर्णांक"),REAL=new Ty("दशमलव"),TEXT=new Ty("पाठ"),BOOL=new Ty("तार्किक"),NIL=new Ty("रिक्त"),VOID=new Ty("शून्य"),ANY=new Ty("सर्ववस्तु");
    private final String source;
    private final Map<String,Template> types=new LinkedHashMap<>();
    private final Map<String,List<FunctionDeclContext>> functions=new LinkedHashMap<>();
    private final Map<String,EnumDeclContext> enums=new HashMap<>();
    private final Map<String,Instance> instances=new LinkedHashMap<>();
    private final Map<Ty,Template> concrete=new LinkedHashMap<>();
    private final List<Instance> pending=new ArrayList<>();
    private final Map<String,Use> uses=new LinkedHashMap<>();
    private final Map<String,String> methodNames=new LinkedHashMap<>();
    private final Map<String,String> functionNames=new LinkedHashMap<>();
    private final Set<String> names=new HashSet<>();
    private final Deque<Map<String,Ty>> scopes=new ArrayDeque<>();
    private final Map<String,Ty> implicit=new HashMap<>();
    private final Map<ParserRuleContext,String> callNames=new IdentityHashMap<>();
    private record Helper(String code,Token origin) {}
    private final Map<String,Helper> nullHelpers=new LinkedHashMap<>();
    private final Map<String,String> nullHelperNames=new HashMap<>();
    private final Set<String> nullableContracts=new HashSet<>();
    private final Set<String> nullableAsyncResults=new HashSet<>();
    private final StringBuilder text=new StringBuilder();
    private final NavigableMap<Integer,Token> origins=new TreeMap<>();
    private Ty owner;
    private boolean staticContext;
    private boolean strictProfile;
    private int next,offset;
    private GenericExpander(String source) { this.source=source; }
    static ProgramContext expand(String source,ProgramContext tree)throws CompilationException { return expand(source,tree,null); }
    static ProgramContext expand(String source,ProgramContext tree,String storage)throws CompilationException {
        var expander=new GenericExpander(source);
        if(storage!=null)for(var c:tree.classDecl())if(key(c.IDENTIFIER().getText()).equals(key(storage)))
            for(var m:c.classMember())if(m.fieldDecl()!=null)expander.implicit.put(key(m.fieldDecl().IDENTIFIER().getText()),expander.type(m.fieldDecl().valueType(),Map.of()));
        return expander.program(tree);
    }
    private static String key(String s){return Identifiers.key(s);}
    private CompilationException error(ParserRuleContext at,String message) {
        return new CompilationException(LocatedToken.source(at.start,source)+":"+at.start.getLine()+":"+(at.start.getCharPositionInLine()+1)+": "+message);
    }
    private String fresh(){String n;do{n="विशेष_"+next++;}while(!names.add(n));return n;}
    private void names(ParseTree tree){if(tree instanceof TerminalNode t)names.add(key(t.getText()));else for(int i=0;i<tree.getChildCount();i++)names(tree.getChild(i));}
    private TypeParametersContext parameters(ParserRuleContext c){return c instanceof ClassDeclContext t?t.typeParameters():c instanceof InterfaceDeclContext t?t.typeParameters():((FunctionDeclContext)c).typeParameters();}
    private void register(ParserRuleContext c,Token name)throws CompilationException {
        var params=parameters(c);validateParameters(params,Set.of());
        if(types.putIfAbsent(key(name.getText()),new Template(c,params,name))!=null)throw error(c,"Duplicate type declaration: "+name.getText());
        if(params==null)concrete.put(new Ty(name.getText()),types.get(key(name.getText())));
    }
    private void validateParameters(TypeParametersContext p,Set<String> outer)throws CompilationException {
        if(p==null)return;var found=new HashSet<>(outer);
        for(var id:p.IDENTIFIER())if(!found.add(key(id.getText())))throw error(p,"Duplicate type parameter or shadowed outer parameter: "+id.getText());
        var edges=new HashMap<String,Set<String>>();String parameter=null;
        for(var child:p.children) {
            if(child instanceof TerminalNode t&&t.getSymbol().getType()==dev.bhash.parser.BhashParser.IDENTIFIER)parameter=key(t.getText());
            if(child instanceof TypeConstraintContext c)for(var bound:c.valueType()) {
                if(bound.IDENTIFIER()==null||!bound.arraySuffix().isEmpty()||!bound.nullableSuffix().isEmpty()||CollectionTypes.builtin(bound.IDENTIFIER().getText()))throw error(bound,"Generic constraint requires a non-null class or interface type.");
                if(bound.typeArguments()==null&&parameterNames(p).contains(key(bound.IDENTIFIER().getText())))edges.computeIfAbsent(parameter,k->new HashSet<>()).add(key(bound.IDENTIFIER().getText()));
            }
        }
        for(var start:edges.keySet())if(cyclic(start,start,edges,new HashSet<>()))throw error(p,"Cyclic generic parameter constraints.");
    }
    private boolean cyclic(String start,String at,Map<String,Set<String>> edges,Set<String> seen) {
        if(!seen.add(at))return false;
        for(var next:edges.getOrDefault(at,Set.of()))if(next.equals(start)||cyclic(start,next,edges,seen))return true;return false;
    }
    private Set<String> parameterNames(TypeParametersContext p){return p==null?Set.of():new HashSet<>(p.IDENTIFIER().stream().map(x->key(x.getText())).toList());}
    private ProgramContext program(ProgramContext program)throws CompilationException {
        strictProfile=program.start instanceof LocatedToken token&&token.strictNulls();
        names(program);
        for(var e:program.enumDecl())enums.put(key(e.IDENTIFIER().getText()),e);
        for(var c:program.classDecl())register(c,c.IDENTIFIER().getSymbol());
        for(var c:program.interfaceDecl())register(c,c.IDENTIFIER().getSymbol());
        if(strictProfile)for(var template:types.values())for(var method:declaredMethods(template.tree))
            if(standard(method)&&method.block()!=null&&returnsNull(method.block())) {
                nullableContracts.add(contractKey(method));if(method.ASYNC()!=null)nullableAsyncResults.add(resultFamily(method));
            }
        for(var f:program.functionDecl()) {
            validateParameters(f.typeParameters(),Set.of());
            if(f.block()==null)throw error(f,"A function requires a body.");
            if(f.typeParameters()!=null&&Set.of("मुख्य","पढ़ो").contains(key(f.IDENTIFIER().getText())))throw error(f,"Reserved function cannot be generic: "+f.IDENTIFIER().getText());
            functions.computeIfAbsent(key(f.IDENTIFIER().getText()),k->new ArrayList<>()).add(f);
        }
        for(var group:functions.values())for(int i=0;i<group.size();i++)if(group.get(i).typeParameters()!=null)
            for(int j=0;j<i;j++)if(sameShape(new Method(null,group.get(i),Map.of()),new Method(null,group.get(j),Map.of())))throw error(group.get(i),"Duplicate function signature.");
        for(var t:types.values())for(var m:declaredMethods(t.tree))validateParameters(m.typeParameters(),parameterNames(t.parameters));
        for(var t:types.values()) {
            validateBoundNames(t.parameters,Set.of());
            for(var m:declaredMethods(t.tree))validateBoundNames(m.typeParameters(),parameterNames(t.parameters));
        }
        for(var group:functions.values())for(var f:group)validateBoundNames(f.typeParameters(),Set.of());
        for(var group:functions.values())nullableOverloads(group.stream().map(f->new Method(null,f,Map.of())).toList());
        for(var template:types.values())nullableOverloads(declaredMethods(template.tree).stream().map(f->new Method(null,f,Map.of())).toList());
        for(var template:types.values())if(template.tree instanceof ClassDeclContext c&&c.start instanceof LocatedToken token&&token.strictNulls()) {
            var constructors=c.classMember().stream().map(ClassMemberContext::constructorDecl).filter(Objects::nonNull).toList();
            for(int i=0;i<constructors.size();i++)for(int j=0;j<i;j++) {
                var current=signature(constructors.get(i).parameters(),Map.of());var previous=signature(constructors.get(j).parameters(),Map.of());
                if(!current.equals(previous)&&current.stream().map(this::eraseNullable).toList().equals(previous.stream().map(this::eraseNullable).toList()))
                    throw error(constructors.get(i),"Constructor overloads cannot differ only by nullability.");
            }
        }
        validateMembers();
        // A method demand can be discovered after its class was emitted. Re-render to a fixed point.
        for(int pass=0;pass<=256;pass++) {
            int count=instances.size()+uses.size()+concrete.size();text.setLength(0);origins.clear();offset=0;callNames.clear();owner=null;
            for(int i=0;i<program.getChildCount()-1;i++) {
                var child=program.getChild(i);
                if(child instanceof ClassDeclContext c&&c.typeParameters()!=null||child instanceof InterfaceDeclContext contract&&contract.typeParameters()!=null||child instanceof FunctionDeclContext f&&f.typeParameters()!=null)continue;
                render(child,Map.of(),null,null);
            }
            for(int i=0;i<pending.size();i++){var instance=pending.get(i);render(instance.template.tree,instance.subst,instance.template.name,instance.name);}
            for(var helper:List.copyOf(nullHelpers.values()))append(helper.code,helper.origin);
            propagateMethods();
            if(count==instances.size()+uses.size()+concrete.size()) {
                var parsed=Frontend.parse(source,text.toString());
                if(!parsed.valid())throw new CompilationException("Internal generic specialization error: "+parsed.diagnostics());
                ProjectResolver.remap(parsed.tree(),origins);parsed.tree().start=program.start;parsed.tree().stop=program.stop;return parsed.tree();
            }
        }
        throw new CompilationException("Generic specialization limit exceeded (256); check expanding recursive types.");
    }
    private void validateBoundNames(TypeParametersContext parameters,Set<String> outer)throws CompilationException {
        if(parameters==null)return;
        var variables=new HashSet<>(outer);variables.addAll(parameterNames(parameters));
        for(var constraint:parameters.typeConstraint())for(var bound:constraint.valueType()) {
            String name=key(bound.IDENTIFIER().getText());
            if(!variables.contains(name)&&!types.containsKey(name))throw error(bound,"Generic constraint requires a class or interface type: "+name);
        }
    }
    private Map<String,Ty> bind(TypeParametersContext p,List<Ty> args,Map<String,Ty> outer,ParserRuleContext at)throws CompilationException {
        int size=p==null?0:p.IDENTIFIER().size();
        if(size!=args.size())throw error(at,args.isEmpty()&&size>0?"Generic type requires explicit type arguments.":"Wrong type argument count.");
        var result=new HashMap<>(outer);for(int i=0;i<size;i++)result.put(key(p.IDENTIFIER(i).getText()),args.get(i));return result;
    }
    private Ty type(ValueTypeContext t,Map<String,Ty> subst)throws CompilationException {
        if(t==null)return null;Ty result;
        if(t.functionType()!=null) {
            var f=t.functionType();var args=new ArrayList<Ty>();for(var a:f.valueType())args.add(type(a,subst));args.add(result(f.returnType(),subst));result=new Ty("फलन",args,0);
        } else {
            String name=t.IDENTIFIER()==null?key(t.getChild(0).getText()):key(t.IDENTIFIER().getText());
            var args=arguments(t.typeArguments(),subst);
            result=subst.get(name);
            if(result!=null&&!args.isEmpty())throw error(t,"A type parameter cannot take type arguments: "+name);
            if(result==null)result=name.equals("सरणी")&&args.size()==1?args.get(0).array():new Ty(name,args,0);
        }
        for(var child:t.children) {
            if(child instanceof NullableSuffixContext)result=result.optionalType();
            else if(child instanceof ArraySuffixContext)result=result.array();
        }
        return result;
    }
    private boolean standard(ParserRuleContext at){return dev.bhash.stdlib.StandardLibrary.reserved(LocatedToken.source(at.start,""));}
    private String contractKey(FunctionDeclContext f){return key(f.IDENTIFIER().getText())+":"+(f.parameters()==null?List.of():f.parameters().parameter().stream().map(p->p.valueType().getText()).toList())+":"+f.returnType().getText();}
    private String resultFamily(FunctionDeclContext f){return key(f.IDENTIFIER().getText())+":"+f.returnType().getText();}
    private boolean returnsNull(ParseTree node) {
        if(node instanceof StatementContext s&&s.RETURN()!=null&&s.expression()!=null&&s.expression().getText().equals("रिक्त"))return true;
        if(node instanceof LambdaExpressionContext)return false;
        for(int i=0;i<node.getChildCount();i++)if(returnsNull(node.getChild(i)))return true;
        return false;
    }
    private Ty result(ReturnTypeContext r,Map<String,Ty> s)throws CompilationException {
        Ty result=r.VOID()!=null?VOID:type(r.valueType(),s);
        if(strictProfile&&standard(r)&&!result.equals(VOID)&&(result.equals(ANY)||r.getParent() instanceof FunctionDeclContext f&&(nullableContracts.contains(contractKey(f))||nullableAsyncResults.contains(resultFamily(f))))) {
            if(r.getParent() instanceof FunctionDeclContext f&&nullableAsyncResults.contains(resultFamily(f))&&result.args.size()==1)
                result=new Ty(result.name,List.of(result.args.get(0).optionalType()),result.rank,result.nullable);
            else result=result.optionalType();
        }
        return result;
    }
    private List<Ty> arguments(TypeArgumentsContext p,Map<String,Ty> s)throws CompilationException {var r=new ArrayList<Ty>();if(p!=null)for(var c:p.children)if(c instanceof ValueTypeContext t)r.add(type(t,s));else if(c instanceof TerminalNode n&&n.getText().equals("शून्य"))r.add(VOID);return r;}
    private List<Ty> signature(ParametersContext p,Map<String,Ty> s)throws CompilationException {var r=new ArrayList<Ty>();if(p!=null)for(var a:p.parameter())r.add(type(a.valueType(),s));return r;}
    private Template template(Ty t){return t==null||t.rank>0?null:types.get(t.name);}
    private Map<String,Ty> classSubst(Ty t,ParserRuleContext at)throws CompilationException {var c=template(t);return c==null?Map.of():bind(c.parameters,t.args,Map.of(),at);}
    private List<Ty> parents(Ty t,Set<Ty> seen)throws CompilationException {
        if(t==null||!seen.add(t)||seen.size()>256)return List.of();var c=template(t);if(c==null)return List.of();
        var subst=classSubst(t,c.tree);var result=new ArrayList<Ty>();
        if(c.tree instanceof ClassDeclContext d){if(d.valueType()!=null)result.add(type(d.valueType(),subst));if(d.typeList()!=null)for(var p:d.typeList().valueType())result.add(type(p,subst));}
        if(c.tree instanceof InterfaceDeclContext d&&d.typeList()!=null)for(var p:d.typeList().valueType())result.add(type(p,subst));
        var direct=List.copyOf(result);for(var p:direct)result.addAll(parents(p,seen));return result;
    }
    private boolean reference(Ty t){return t!=null&&(t.rank>0||t.function()||t.equals(ANY)||t.name.equals("बाइटबफ़र")||CollectionTypes.builtin(t.name)||template(t)!=null);}
    private boolean assignable(Ty expected,Ty actual)throws CompilationException {
        if(expected==null||actual==null)return false;
        if(expected.optional())return actual.equals(NIL)||assignable(expected.nonNull(),actual.nonNull());
        if(actual.optional())return false;
        return expected.equals(actual)||expected.equals(ANY)&&!actual.equals(VOID)||actual.equals(NIL)&&reference(expected)||parents(actual,new HashSet<>()).contains(expected);
    }
    private void constraints(TypeParametersContext params,Map<String,Ty> subst,ParserRuleContext at)throws CompilationException {
        if(params==null)return;String parameter=null;
        for(var child:params.children) {
            if(child instanceof TerminalNode n&&n.getSymbol().getType()==dev.bhash.parser.BhashParser.IDENTIFIER)parameter=key(n.getText());
            if(child instanceof TypeConstraintContext bound)for(var b:bound.valueType()) {
                Ty target=type(b,subst),actual=subst.get(parameter);
                if(!assignable(target,actual))throw error(at,"Generic constraint not satisfied: "+actual+" must implement or extend "+target+".");
            }
        }
    }
    private String typeName(Ty t,ParserRuleContext at)throws CompilationException {
        if(!t.nullable.isEmpty()) {
            var base=new Ty(t.name,t.args,0);
            return t.decorate(typeName(base,at));
        }
        if(t.function()){var args=new ArrayList<String>();for(int i=0;i<t.args.size()-1;i++)args.add(typeName(t.args.get(i),at));return (t.rank>0?"(":"")+"फलन("+String.join(",",args)+")->"+typeName(t.args.get(t.args.size()-1),at)+(t.rank>0?")":"")+"[]".repeat(t.rank);}
        if(t.rank>0)return typeName(new Ty(t.name,t.args,0),at)+"[]".repeat(t.rank);
        if(CollectionTypes.builtin(t.name)){var args=new ArrayList<String>();for(var a:t.args)args.add(typeName(a,at));return t.name+"<"+String.join(",",args)+">";}
        var c=template(t);if(c==null){if(!t.args.isEmpty())throw error(at,"Type arguments require a generic type: "+t.name);return t.name;}
        var subst=classSubst(t,at);constraints(c.parameters,subst,at);concrete.putIfAbsent(t,c);
        if(c.parameters==null)return t.name;
        return instantiate("type:"+t,c,subst,t,at);
    }
    private String instantiate(String key,Template template,Map<String,Ty> subst,Ty ty,ParserRuleContext at)throws CompilationException {
        var old=instances.get(key);if(old!=null)return old.name;
        if(instances.size()+uses.size()>=256)throw error(at,"Generic specialization limit exceeded (256); check expanding recursive types.");
        var instance=new Instance(template,Map.copyOf(subst),fresh(),ty);instances.put(key,instance);pending.add(instance);return instance.name;
    }
    private List<FunctionDeclContext> declaredMethods(ParserRuleContext c){return c instanceof ClassDeclContext d?d.classMember().stream().filter(x->x.functionDecl()!=null).map(ClassMemberContext::functionDecl).toList():((InterfaceDeclContext)c).functionDecl();}
    private List<Method> methods(Ty receiver,String name,Set<Ty> seen)throws CompilationException {
        if(receiver==null||!seen.add(receiver)||seen.size()>256)return List.of();var c=template(receiver);if(c==null)return List.of();
        var subst=classSubst(receiver,c.tree);var found=new ArrayList<Method>();
        for(var f:declaredMethods(c.tree))if(key(f.IDENTIFIER().getText()).equals(key(name)))found.add(new Method(receiver,f,subst));
        for(var parent:parents(receiver,new HashSet<>()))for(var m:methods(parent,name,seen)) {
            boolean hidden=false;
            for(var f:found)if(methodShape(f).equals(methodShape(m))&&parameterNames(f.tree.typeParameters()).size()==parameterNames(m.tree.typeParameters()).size()){hidden=true;break;}
            if(!hidden)found.add(m);
        }
        return found;
    }
    private List<Ty> methodShape(Method method)throws CompilationException {
        return signature(method.tree.parameters(),methodVariables(method));
    }
    private Ty eraseNullable(Ty t){return new Ty(t.name,t.args.stream().map(this::eraseNullable).toList(),t.rank);}
    private void nullableOverloads(List<Method> methods)throws CompilationException {
        for(int i=0;i<methods.size();i++) {
            var current=methods.get(i);if(!(current.tree.start instanceof LocatedToken token&&token.strictNulls()))continue;
            var shape=methodShape(current);
            for(int j=0;j<i;j++)if(key(current.tree.IDENTIFIER().getText()).equals(key(methods.get(j).tree.IDENTIFIER().getText()))) {
                var previous=methodShape(methods.get(j));
                if(!shape.equals(previous)&&shape.stream().map(this::eraseNullable).toList().equals(previous.stream().map(this::eraseNullable).toList()))
                    throw error(current.tree,"Overloads cannot differ only by nullability.");
            }
        }
    }
    private Map<String,Ty> methodVariables(Method method) {
        var s=new HashMap<>(method.subst);var p=method.tree.typeParameters();
        if(p!=null)for(int i=0;i<p.IDENTIFIER().size();i++)s.put(key(p.IDENTIFIER(i).getText()),new Ty("#method"+i));return s;
    }
    private List<String> boundsShape(Method method)throws CompilationException {
        var result=new ArrayList<String>();var p=method.tree.typeParameters();if(p==null)return result;
        var subst=methodVariables(method);String parameter="";
        for(var child:p.children) {
            if(child instanceof TerminalNode t&&t.getSymbol().getType()==dev.bhash.parser.BhashParser.IDENTIFIER)parameter=subst.get(key(t.getText())).toString();
            if(child instanceof TypeConstraintContext c)for(var bound:c.valueType())result.add(parameter+":"+type(bound,subst));
        }
        Collections.sort(result);return result;
    }
    private ClassMemberContext member(Method m){return m.tree.getParent() instanceof ClassMemberContext c?c:null;}
    private boolean publicInstance(Method m){var c=member(m);return c==null||c.STATIC()==null&&(c.visibility()==null||c.visibility().PUBLIC()!=null);}
    private boolean sameShape(Method a,Method b)throws CompilationException {
        return key(a.tree.IDENTIFIER().getText()).equals(key(b.tree.IDENTIFIER().getText()))
            &&parameterNames(a.tree.typeParameters()).size()==parameterNames(b.tree.typeParameters()).size()&&methodShape(a).equals(methodShape(b));
    }
    private void sameContract(Method actual,Method inherited)throws CompilationException {
        if(!result(actual.tree.returnType(),methodVariables(actual)).equals(result(inherited.tree.returnType(),methodVariables(inherited)))||!boundsShape(actual).equals(boundsShape(inherited)))
            throw error(actual.tree,"Generic override/interface contract must preserve return type and constraints.");
    }
    private List<Method> declared(Ty receiver)throws CompilationException {
        var t=template(receiver);if(t==null)return List.of();var result=new ArrayList<Method>();var subst=classSubst(receiver,t.tree);
        for(var f:declaredMethods(t.tree))result.add(new Method(receiver,f,subst));return result;
    }
    private void validateMembers()throws CompilationException {
        for(var t:types.values()) {
            var args=t.parameters==null?List.<Ty>of():t.parameters.IDENTIFIER().stream().map(x->new Ty(x.getText())).toList();
            var receiver=new Ty(t.name.getText(),args,0);var own=declared(receiver);var inherited=new ArrayList<Method>();
            for(var parent:parents(receiver,new HashSet<>()))inherited.addAll(declared(parent));
            for(int i=0;i<own.size();i++) {
                var a=own.get(i);if(a.tree.typeParameters()==null)continue;
                if(member(a)!=null&&a.tree.block()==null)throw error(a.tree,"A generic method requires a body.");
                if(member(a)==null&&a.tree.block()!=null)throw error(a.tree,"Interface methods cannot have a body.");
                for(int j=0;j<i;j++)if(sameShape(a,own.get(j)))throw error(a.tree,"Duplicate generic method signature.");
                boolean overrides=false;
                for(var b:inherited)if(member(a)!=null&&template(b.owner).tree instanceof ClassDeclContext&&sameShape(a,b)) {
                    overrides=true;sameContract(a,b);
                    if(member(a).STATIC()!=null||member(b).STATIC()!=null||member(b).visibility()!=null&&member(b).visibility().PRIVATE()!=null)throw error(a.tree,"Generic method cannot override a private or static method.");
                    if(member(a).OVERRIDE()==null)throw error(a.tree,"Inherited method requires अधिलेखन.");
                    if(publicInstance(b)&&!publicInstance(a))throw error(a.tree,"Generic override cannot reduce visibility.");
                    if(member(b).visibility()!=null&&member(b).visibility().PROTECTED()!=null&&member(a).visibility()!=null&&member(a).visibility().PRIVATE()!=null)throw error(a.tree,"Generic override cannot reduce visibility.");
                }
                if(member(a)!=null&&member(a).OVERRIDE()!=null&&!overrides)throw error(a.tree,"अधिलेखन requires a matching inherited method.");
            }
            if(t.tree instanceof InterfaceDeclContext) {
                var all=new ArrayList<>(own);all.addAll(inherited);
                for(int i=0;i<all.size();i++)if(all.get(i).tree.typeParameters()!=null)for(int j=0;j<i;j++)if(sameShape(all.get(i),all.get(j)))sameContract(all.get(i),all.get(j));
            }else {
                var implementations=new ArrayList<>(own);for(var m:inherited)if(template(m.owner).tree instanceof ClassDeclContext)implementations.add(m);
                for(var required:inherited)if(required.tree.typeParameters()!=null&&template(required.owner).tree instanceof InterfaceDeclContext) {
                    Method implementation=null;for(var candidate:implementations)if(sameShape(candidate,required)){implementation=candidate;break;}
                    if(implementation==null||!publicInstance(implementation))throw error(t.tree,"Missing public generic interface implementation: "+required.tree.IDENTIFIER().getText());
                    sameContract(implementation,required);
                }
            }
        }
    }
    private Ty field(Ty receiver,String name,Set<Ty> seen)throws CompilationException {
        if(receiver==null||!seen.add(receiver)||seen.size()>256)return null;
        if(receiver.name.equals("अपवाद")&&key(name).equals("संदेश"))return TEXT;
        if(enums.containsKey(receiver.name)) {
            if(key(name).equals("नाम"))return TEXT;if(key(name).equals("अंक"))return INT;
            return enums.get(receiver.name).enumMember().stream().anyMatch(m->key(m.IDENTIFIER().getText()).equals(key(name)))?receiver:null;
        }
        if(receiver.rank>0||CollectionTypes.builtin(receiver.name)||receiver.name.equals("बाइटबफ़र"))return Set.of("आकार","लंबाई").contains(key(name))?INT:null;
        var c=template(receiver);if(c==null)return null;var s=classSubst(receiver,c.tree);
        if(c.tree instanceof ClassDeclContext d) {
            for(var m:d.classMember())if(m.fieldDecl()!=null&&key(m.fieldDecl().IDENTIFIER().getText()).equals(key(name)))return type(m.fieldDecl().valueType(),s);
            if(d.RECORD()!=null&&d.parameters()!=null)for(var p:d.parameters().parameter())if(key(p.IDENTIFIER().getText()).equals(key(name)))return type(p.valueType(),s);
        }
        for(var parent:parents(receiver,new HashSet<>())){var t=field(parent,name,seen);if(t!=null)return t;}return null;
    }
    private Ty local(String name){for(var scope:scopes)if(scope.containsKey(key(name)))return scope.get(key(name));return null;}
    private boolean unify(Ty formal,Ty actual,Set<String> variables,Map<String,Ty> result)throws CompilationException {
        if(actual==null||actual.equals(NIL))return true;
        if(variables.contains(formal.name)&&formal.args.isEmpty()) {
            if(actual.rank<formal.rank)return false;var value=actual;
            for(int i=0;i<formal.rank;i++)value=value.element();
            if(formal.nullable.contains(0))value=value.nonNull();
            var old=result.putIfAbsent(formal.name,value);return old==null||old.equals(value);
        }
        if(!formal.name.equals(actual.name)||formal.rank!=actual.rank) {
            for(var parent:parents(actual,new HashSet<>())){var copy=new HashMap<>(result);if(unify(formal,parent,variables,copy)){result.clear();result.putAll(copy);return true;}}
            return !containsVariable(formal,variables);
        }
        if(formal.args.size()!=actual.args.size())return false;
        for(int i=0;i<formal.args.size();i++)if(!unify(formal.args.get(i),actual.args.get(i),variables,result))return false;return true;
    }
    private boolean containsVariable(Ty t,Set<String> variables){return variables.contains(t.name)||t.args.stream().anyMatch(a->containsVariable(a,variables));}
    private Call choose(ParserRuleContext at,List<Method> methods,List<FunctionDeclContext> globals,List<Ty> actual,List<Ty> explicit)throws CompilationException {
        var candidates=new ArrayList<Call>();
        int count=methods==null?globals.size():methods.size();
        for(int n=0;n<count;n++) {
            var method=methods==null?null:methods.get(n);var f=method==null?globals.get(n):method.tree;
            var outer=method==null?Map.<String,Ty>of():method.subst;var subst=new HashMap<>(outer);var vars=parameterNames(f.typeParameters());
            if(explicit!=null){if(vars.size()!=explicit.size())continue;subst.putAll(bind(f.typeParameters(),explicit,outer,at));}
            else {
                var formal=signature(f.parameters(),outer);if(formal.size()!=actual.size())continue;boolean match=true;
                for(int i=0;i<formal.size();i++)if(!unify(formal.get(i),actual.get(i),vars,subst)){match=false;break;}
                if(!match||!subst.keySet().containsAll(vars))continue;
            }
            var params=signature(f.parameters(),subst);if(params.size()!=actual.size())continue;boolean valid=true;
            for(int i=0;i<params.size();i++)if(!assignable(params.get(i),actual.get(i))){valid=false;break;}
            if(valid)candidates.add(new Call(f,subst,params,result(f.returnType(),subst),method));
        }
        var best=new ArrayList<Call>();
        for(var c:candidates){boolean wins=true;for(var other:candidates)for(int i=0;i<c.parameters.size();i++)if(!assignable(other.parameters.get(i),c.parameters.get(i)))wins=false;if(wins)best.add(c);}
        var tied=List.copyOf(best);best.removeIf(c->c.tree.typeParameters()!=null&&tied.stream().anyMatch(other->other.tree.typeParameters()==null&&other.parameters.equals(c.parameters)));
        if(best.size()!=1)return null;
        var chosen=best.get(0);constraints(chosen.tree.typeParameters(),chosen.subst,at);return chosen;
    }
    private String demand(Method method,List<Ty> args,ParserRuleContext at)throws CompilationException {
        String id=method.owner+":"+method.tree.start.getTokenIndex()+":"+args;
        var old=uses.get(id);if(old!=null)return old.name;
        if(instances.size()+uses.size()>=256)throw error(at,"Generic specialization limit exceeded (256); check expanding recursive types.");
        var s=bind(method.tree.typeParameters(),args,method.subst,at);constraints(method.tree.typeParameters(),s,at);
        String name=methodNames.computeIfAbsent(key(method.tree.IDENTIFIER().getText())+":"+args,k->fresh());
        uses.put(id,new Use(method,List.copyOf(args),name));return name;
    }
    private void propagateMethods()throws CompilationException {
        for(var use:List.copyOf(uses.values()))for(var receiver:List.copyOf(concrete.keySet())) {
            if(!receiver.equals(use.method.owner)&&!parents(receiver,new HashSet<>()).contains(use.method.owner))continue;
            for(var m:methods(receiver,use.method.tree.IDENTIFIER().getText(),new HashSet<>()))
                if(m.tree.typeParameters()!=null&&m.tree.typeParameters().IDENTIFIER().size()==use.args.size())demand(m,use.args,m.tree);
        }
    }
    private Ty call(ParserRuleContext at,Ty receiver,String name,ArgumentsContext arguments,TypeArgumentsContext args,Map<String,Ty> subst)throws CompilationException {
        return call(at,receiver,name,arguments,args,subst,null);
    }
    private Ty call(ParserRuleContext at,Ty receiver,String name,ArgumentsContext arguments,TypeArgumentsContext args,Map<String,Ty> subst,Boolean shared)throws CompilationException {
        var actual=new ArrayList<Ty>();if(arguments!=null)for(var a:arguments.expression())actual.add(infer(a,subst));
        if(actual.contains(null))return null;
        var ms=receiver==null?null:methods(receiver,name,new HashSet<>());var fs=functions.getOrDefault(key(name),List.of());
        if(ms!=null&&shared!=null)ms=ms.stream().filter(m->(member(m)!=null&&member(m).STATIC()!=null)==shared).toList();
        Call chosen=choose(at,ms,fs,actual,args==null?null:arguments(args,subst));
        if(chosen==null) {
            if(receiver!=null){var builtin=builtinMethod(receiver,name);if(builtin!=null)return builtin;}
            boolean generic=(ms==null?fs.stream().anyMatch(f->f.typeParameters()!=null):ms.stream().anyMatch(m->m.tree.typeParameters()!=null));
            if(generic&&args!=null)throw error(at,"Expected matching argument types and type argument count for generic call: "+name);
            if(generic||args!=null)throw error(at,"Cannot infer a unique generic call; supply explicit type arguments or disambiguate the arguments: "+name);
            return receiver==null?builtinFunction(name):null;
        }
        if(chosen.tree.typeParameters()!=null) {
            var concreteArgs=chosen.tree.typeParameters().IDENTIFIER().stream().map(x->chosen.subst.get(key(x.getText()))).toList();
            String emitted=chosen.method==null?function(name,concreteArgs,at):demand(chosen.method,concreteArgs,at);
            callNames.put(at,emitted);
        }
        return chosen.result;
    }
    private String function(String name,List<Ty> args,ParserRuleContext at)throws CompilationException {
        String group=key(name)+":"+args;String emitted=functionNames.computeIfAbsent(group,k->fresh());
        for(var f:functions.getOrDefault(key(name),List.of()))if(f.typeParameters()!=null&&f.typeParameters().IDENTIFIER().size()==args.size()) {
            String id="function:"+group+":"+f.start.getTokenIndex();
            if(instances.containsKey(id))continue;
            var subst=bind(f.typeParameters(),args,Map.of(),at);
            try{constraints(f.typeParameters(),subst,at);}catch(CompilationException invalid){continue;}
            if(instances.size()+uses.size()>=256)throw error(at,"Generic specialization limit exceeded (256); check expanding recursive types.");
            var i=new Instance(new Template(f,f.typeParameters(),f.IDENTIFIER().getSymbol()),subst,emitted,null);instances.put(id,i);pending.add(i);
        }
        return emitted;
    }
    private Ty builtinFunction(String name){return switch(key(name)){case "पढ़ो","लंबाई","पूर्णांकपाठ"->INT;case "पाठपढ़ो","अंश","अंकपाठ","बड़ाअक्षर","यूटीएफ८पाठ"->TEXT;case "यूटीएफ८बफ़र"->new Ty("बाइटबफ़र");case "त्रुटि"->VOID;default->null;};}
    private Ty builtinMethod(Ty r,String n) {
        if(r.rank==0&&!CollectionTypes.builtin(r.name)&&!r.name.equals("बाइटबफ़र"))return null;
        return switch(key(n)) {
            case "आकार","लंबाई","पूर्णांकपढ़ो"->INT;case "है","खाली"->BOOL;
            case "दशमलवपढ़ो"->REAL;
            case "लो"->r.name.equals("बाइटबफ़र")?new Ty("बाइट"):r.element();
            case "हटाओ"->r.name.equals("मानचित्र")?BOOL:r.element();
            case "जोड़ो","रखो","डालो","साफ़","भरो","पूर्णांकलिखो","दशमलवलिखो"->VOID;
            case "काटो"->new Ty("बाइटबफ़र");
            case "सरणी"->new Ty("बाइट",List.of(),1);
            case "कुंजियाँ"->r.args.isEmpty()?null:new Ty("सूची",List.of(r.args.get(0)),0);
            case "मूल्य"->r.args.isEmpty()?null:new Ty("सूची",List.of(r.args.get(r.args.size()-1)),0);
            default->null;
        };
    }
    private Ty infer(ParseTree node,Map<String,Ty> s)throws CompilationException {
        if(node==null)return null;
        if(node instanceof ExpressionContext e&&e.COALESCE()!=null) {
            var left=infer(e.logicalOr(),s);var right=infer(e.expression(),s);
            if(left==null||!left.optional())throw error(e,"?? requires a nullable left operand.");
            return right!=null&&(right.optional()||right.equals(NIL))?left:left.nonNull();
        }
        if(node instanceof PostfixContext p) {
            return inferPostfix(p,p.postfixPart().size(),s);
        }
        if(node instanceof PrimaryContext p) {
            return inferPrimary(p,s);
        }
        return inferOther(node,s);
    }
    private Ty inferPostfix(PostfixContext p,int count,Map<String,Ty> s)throws CompilationException {
            Ty value=infer(p.primary(),s);
            var primary=p.primary();String spelling=primary.IDENTIFIER()==null?null:primary.IDENTIFIER().getText();
            boolean shared=spelling!=null&&primary.LPAREN()==null&&local(spelling)==null&&field(owner,spelling,new HashSet<>())==null&&!implicit.containsKey(key(spelling))&&(types.containsKey(key(spelling))||enums.containsKey(key(spelling)));
            for(var part:p.postfixPart().subList(0,count)) {
                boolean optional=part.SAFE_DOT()!=null;
                if(optional&&value!=null&&!value.optional())throw error(part,"?. requires a nullable receiver.");
                if(optional&&value!=null)value=value.nonNull();
                if(part.LBRACK()!=null){infer(part.expression(),s);value=value==null?null:value.name.equals("बाइटबफ़र")?new Ty("बाइट"):value.element();}
                else if(part.IDENTIFIER()!=null) {
                    if(part.LPAREN()!=null)value=memberResult(value,part,s,shared);
                    else value=field(value,part.IDENTIFIER().getText(),new HashSet<>());
                }else {if(part.arguments()!=null)for(var a:part.arguments().expression())infer(a,s);value=value!=null&&value.function()?value.element():null;}
                shared=false;
                if(optional&&value!=null&&!value.equals(VOID))value=value.optionalType();
            }
            return value;
    }
    private Ty inferPrimary(PrimaryContext p,Map<String,Ty> s)throws CompilationException {
            if(p.NEW()!=null){var t=type(p.valueType(),s);typeName(t,p);if(p.arguments()!=null)for(var a:p.arguments().expression())infer(a,s);for(var ignored:p.arrayDimension())t=t.array();return t;}
            if(p.INTEGER_LITERAL()!=null)return INT;if(p.REAL_LITERAL()!=null)return REAL;if(p.STRING_LITERAL()!=null)return TEXT;if(p.TRUE()!=null||p.FALSE()!=null||p.TYPE_TEST()!=null)return BOOL;if(p.NULL()!=null)return NIL;
            if(p.CONVERT()!=null||p.SAFE_CAST()!=null){var t=type(p.valueType(),s);return p.SAFE_CAST()!=null&&p.start instanceof LocatedToken token&&token.strictNulls()?t.optionalType():t;}
            if(p.THIS()!=null)return owner;
            if(p.BASE()!=null){var parents=parents(owner,new HashSet<>());return parents.isEmpty()?null:parents.get(0);}
            if(p.lambdaExpression()!=null)return infer(p.lambdaExpression(),s);
            if(p.IDENTIFIER()!=null) {
                String n=p.IDENTIFIER().getText();Ty variable=local(n);if(variable==null)variable=field(owner,n,new HashSet<>());if(variable==null)variable=implicit.get(key(n));
                if(variable!=null&&p.typeArguments()!=null)throw error(p,"Type arguments require a generic function or method; function values are monomorphic.");
                if(variable!=null)return p.LPAREN()!=null&&variable.function()?variable.element():variable;
                if(p.LPAREN()!=null) {
                    var ms=methods(owner,n,new HashSet<>());return call(p,ms.isEmpty()?null:owner,n,p.arguments(),p.typeArguments(),s,staticContext?Boolean.TRUE:null);
                }
                if(types.containsKey(key(n))||enums.containsKey(key(n)))return new Ty(n,arguments(p.typeArguments(),s),0);
                var fs=functions.getOrDefault(key(n),List.of());
                if(fs.size()>1&&p.typeArguments()!=null)throw error(p,"Ambiguous generic function value; use a typed lambda to select an overload.");
                if(fs.size()==1) {
                    if(fs.get(0).typeParameters()!=null&&p.typeArguments()==null)throw error(p,"Generic function value requires explicit type arguments or a typed lambda.");
                    var f=fs.get(0);var subst=f.typeParameters()==null?Map.<String,Ty>of():bind(f.typeParameters(),arguments(p.typeArguments(),s),Map.of(),p);
                    if(f.typeParameters()!=null){constraints(f.typeParameters(),subst,p);var args=arguments(p.typeArguments(),s);callNames.put(p,function(n,args,p));}
                    var args=new ArrayList<>(signature(f.parameters(),subst));args.add(result(f.returnType(),subst));return new Ty("फलन",args,0);
                }
                return null;
            }
            if(p.expression()!=null)return infer(p.expression(),s);return null;
    }
    private Ty memberResult(Ty receiver,PostfixPartContext part,Map<String,Ty> subst,boolean shared)throws CompilationException {
        var callback=field(receiver,part.IDENTIFIER().getText(),new HashSet<>());
        if(callback!=null&&callback.function()&&methods(receiver,part.IDENTIFIER().getText(),new HashSet<>()).isEmpty()) {
            if(part.typeArguments()!=null)throw error(part,"Function values are monomorphic; remove type arguments.");
            if(part.arguments()!=null)for(var argument:part.arguments().expression())infer(argument,subst);
            return callback.element();
        }
        return call(part,receiver,part.IDENTIFIER().getText(),part.arguments(),part.typeArguments(),subst,shared);
    }
    private Ty inferOther(ParseTree node,Map<String,Ty> s)throws CompilationException {
        if(node instanceof LambdaExpressionContext l){var args=new ArrayList<>(signature(l.parameters(),s));args.add(result(l.returnType(),s));return new Ty("फलन",args,0);}
        if(node instanceof UnaryContext u&&u.AWAIT()!=null){var task=infer(u.unary(),s);return task==null?null:task.element();}
        if(node instanceof UnaryContext u&&u.NOT()!=null){infer(u.unary(),s);return BOOL;}
        Ty result=null;boolean binary=false;
        for(int i=0;i<node.getChildCount();i++) {
            var child=node.getChild(i);if(child instanceof TerminalNode){binary=true;continue;}
            var t=infer(child,s);if(result==null)result=t;else if(t!=null&&!result.equals(t)){if(result.equals(TEXT)||t.equals(TEXT))result=TEXT;else if(result.equals(REAL)||t.equals(REAL))result=REAL;else result=INT;}
        }
        if(binary&&(node instanceof EqualityContext||node instanceof ComparisonContext||node instanceof LogicalAndContext||node instanceof LogicalOrContext))return BOOL;
        if(binary&&result!=null&&(result.name.equals("बाइट")||result.name.equals("लघुपूर्णांक")))return INT;
        return result;
    }
    private void append(String value,Token origin){origins.put(offset,origin);text.append(value).append(' ');offset+=value.codePointCount(0,value.length())+1;}
    private boolean awaiting(ParseTree node) {
        if(node instanceof LambdaExpressionContext)return false;
        if(node instanceof UnaryContext u&&u.AWAIT()!=null)return true;
        for(int i=0;i<node.getChildCount();i++)if(awaiting(node.getChild(i)))return true;return false;
    }
    private void renderPostfix(PostfixContext p,int count,Map<String,Ty> subst,Token renamed,String replacement)throws CompilationException {
        if(count==0){render(p.primary(),subst,renamed,replacement);return;}
        var part=p.postfixPart(count-1);
        if(part.SAFE_DOT()==null){renderPostfix(p,count-1,subst,renamed,replacement);render(part,subst,renamed,replacement);return;}
        Ty receiver=inferPostfix(p,count-1,subst),result=inferPostfix(p,count,subst);
        if(receiver==null||!receiver.optional())throw error(part,"?. requires a nullable receiver.");
        if(result==null)throw error(part,"Undefined member: "+part.IDENTIFIER().getText());
        Ty payload=part.LPAREN()!=null?memberResult(receiver.nonNull(),part,subst,false):field(receiver.nonNull(),part.IDENTIFIER().getText(),new HashSet<>());
        String rt=typeName(result,part),pt=typeName(payload,part),base=typeName(receiver.nonNull(),part),lt=typeName(receiver,part);
        String name=nullHelperNames.computeIfAbsent("member:"+receiver+":"+payload,k->fresh());
        String invocation=result.equals(VOID)?"यदि (व != रिक्त) { उपयोग(व); }":"यदि (व == रिक्त) { लौटाओ रिक्त; } लौटाओ उपयोग(व);";
        nullHelpers.put(name,new Helper("फलन "+name+"(व: "+lt+", उपयोग: फलन("+base+")->"+pt+")->"+rt+" { "+invocation+" }",part.start));
        append(name+"(",part.start);renderPostfix(p,count-1,subst,renamed,replacement);
        String parameter=fresh();append(", फलन("+parameter+":"+base+")->"+pt+" { "+(payload.equals(VOID)?"":"लौटाओ ")+parameter,part.start);
        append(".",part.start);
        if(callNames.containsKey(part)) {
            append(callNames.get(part),part.IDENTIFIER().getSymbol());
            for(var child:part.children)if(child!=part.SAFE_DOT()&&child!=part.IDENTIFIER()&&child!=part.typeArguments())render(child,subst,renamed,replacement);
        }else for(var child:part.children)if(child!=part.SAFE_DOT())render(child,subst,renamed,replacement);
        append("; })",part.stop);
    }
    private void render(ParseTree node,Map<String,Ty> subst,Token renamed,String replacement)throws CompilationException {
        if(node instanceof PrimaryContext p&&p.CONVERT()!=null&&type(p.valueType(),subst).optional()) {
            Ty target=type(p.valueType(),subst),source=infer(p.expression(),subst);
            if(source==null||source.equals(NIL))source=ANY;
            source=source.optionalType();String from=typeName(source,p),to=typeName(target,p),base=typeName(target.nonNull(),p);
            String name=nullHelperNames.computeIfAbsent("nullable-convert:"+source+":"+target,k->fresh());
            nullHelpers.put(name,new Helper("फलन "+name+"(व:"+from+")->"+to+" {यदि(व==रिक्त){लौटाओ रिक्त;} लौटाओ परिवर्तित<"+base+">(व);}",p.start));
            append(name+"(",p.start);render(p.expression(),subst,renamed,replacement);append(")",p.stop);return;
        }
        if(node instanceof ReturnTypeContext r){append(typeName(result(r,subst),r),r.start);return;}
        if(node instanceof ParameterContext p&&strictProfile&&standard(p)&&type(p.valueType(),subst).equals(ANY)) {
            append(p.IDENTIFIER().getText()+": सर्ववस्तु?",p.start);return;
        }
        if(node instanceof PostfixContext p&&p.postfixPart().stream().anyMatch(part->part.SAFE_DOT()!=null)) {
            infer(p,subst);
            if(p.postfixPart().stream().anyMatch(this::awaiting)) {
                for(var child:p.children)render(child,subst,renamed,replacement);
            }else renderPostfix(p,p.postfixPart().size(),subst,renamed,replacement);
            return;
        }
        if(node instanceof ExpressionContext e&&e.COALESCE()!=null) {
            Ty left=infer(e.logicalOr(),subst),result=infer(e,subst);
            boolean async=awaiting(e.expression());
            if(async) {
                // Keep conditional suspension in the caller's checked state machine.
                render(e.logicalOr(),subst,renamed,replacement);append("??",e.COALESCE().getSymbol());render(e.expression(),subst,renamed,replacement);return;
            }
            String signature=left+":"+result;
            String name=nullHelperNames.computeIfAbsent("coalesce:"+signature,k->fresh());
            String lt=typeName(left,e),rt=typeName(result,e);
            nullHelpers.put(name,new Helper("फलन "+name+"(व: "+lt+", वैकल्पिक: फलन()->"+rt+")->"+rt+" { यदि (व != रिक्त) { लौटाओ व; } लौटाओ वैकल्पिक(); }",e.start));
            append(name+"(",e.start);render(e.logicalOr(),subst,renamed,replacement);
            append(", फलन()->"+rt+" { लौटाओ",e.start);render(e.expression(),subst,renamed,replacement);append("; })",e.stop);return;
        }
        if(node instanceof PrimaryContext p&&p.SAFE_CAST()!=null&&p.start instanceof LocatedToken token&&token.strictNulls()) {
            Ty destination=type(p.valueType(),subst);
            if(destination.optional())throw error(p,"सुरक्षितरूप का लक्ष्य पहले से nullable नहीं होना चाहिए।");
            String name=nullHelperNames.computeIfAbsent("cast:"+destination,k->fresh()),t=typeName(destination,p),optional=typeName(destination.optionalType(),p);
            nullHelpers.put(name,new Helper("फलन "+name+"(व: सर्ववस्तु?)->"+optional+" {यदि (प्रकारहै<"+t+">(व)) { लौटाओ परिवर्तित<"+t+">(व); } लौटाओ रिक्त;}",p.start));
            append(name+"(",p.start);render(p.expression(),subst,renamed,replacement);append(")",p.stop);return;
        }
        if(node instanceof TypeParametersContext)return;
        if(node instanceof ClassDeclContext||node instanceof InterfaceDeclContext) {
            Ty saved=owner;var c=node instanceof ClassDeclContext d?d.IDENTIFIER():((InterfaceDeclContext)node).IDENTIFIER();
            var params=parameters((ParserRuleContext)node);var args=new ArrayList<Ty>();if(params!=null)for(var p:params.IDENTIFIER())args.add(subst.get(key(p.getText())));owner=new Ty(c.getText(),args,0);concrete.putIfAbsent(owner,types.get(key(c.getText())));
            for(int i=0;i<node.getChildCount();i++) {
                var child=node.getChild(i);var f=child instanceof ClassMemberContext m?m.functionDecl():child instanceof FunctionDeclContext d?d:null;
                if(f!=null&&f.typeParameters()!=null) {
                    for(var use:List.copyOf(uses.values()))if(use.method.owner.equals(owner)&&use.method.tree==f) {
                        var ms=bind(f.typeParameters(),use.args,subst,f);
                        if(child instanceof ClassMemberContext m)for(int j=0;j<m.getChildCount();j++){var part=m.getChild(j);render(part,ms,f.IDENTIFIER().getSymbol(),use.name);}
                        else render(f,ms,f.IDENTIFIER().getSymbol(),use.name);
                    }
                }else render(child,subst,renamed,replacement);
            }
            owner=saved;return;
        }
        if(node instanceof FunctionDeclContext||node instanceof ConstructorDeclContext||node instanceof LambdaExpressionContext) {
            boolean savedStatic=staticContext;
            if(node instanceof FunctionDeclContext f)staticContext=f.getParent() instanceof ClassMemberContext m&&m.STATIC()!=null;
            if(node instanceof ConstructorDeclContext)staticContext=false;
            var p=node instanceof FunctionDeclContext f?f.parameters():node instanceof ConstructorDeclContext c?c.parameters():((LambdaExpressionContext)node).parameters();
            scopes.push(new HashMap<>());if(p!=null)for(var parameter:p.parameter())scopes.peek().put(key(parameter.IDENTIFIER().getText()),type(parameter.valueType(),subst));
            for(int i=0;i<node.getChildCount();i++)render(node.getChild(i),subst,renamed,replacement);scopes.pop();staticContext=savedStatic;return;
        }
        if(node instanceof VariableDeclContext v) {
            var actual=infer(v.expression(),subst);var declared=v.valueType()==null?actual:type(v.valueType(),subst);
            if(declared==null||declared.equals(NIL)||declared.equals(VOID))throw error(v,"Cannot infer this binding's type; add an explicit type annotation.");
            append(v.CONST()!=null?"स्थिर":"मान",v.start);append(v.IDENTIFIER().getText(),v.IDENTIFIER().getSymbol());append(":"+typeName(declared,v)+" =",v.start);render(v.expression(),subst,renamed,replacement);append(";",v.stop);
            if(!scopes.isEmpty())scopes.peek().put(key(v.IDENTIFIER().getText()),declared);return;
        }
        if(node instanceof BlockContext||node instanceof SwitchSectionContext){scopes.push(new HashMap<>());for(int i=0;i<node.getChildCount();i++)render(node.getChild(i),subst,renamed,replacement);scopes.pop();return;}
        if(node instanceof ForeachStatementContext||node instanceof CatchClauseContext||node instanceof ResourceStatementContext) {
            var params=node instanceof ForeachStatementContext f?f.parameter():List.of(node instanceof CatchClauseContext c?c.parameter():((ResourceStatementContext)node).parameter());
            for(int i=0;i<node.getChildCount();i++) {
                var child=node.getChild(i);
                if(child instanceof BlockContext) {
                    scopes.push(new HashMap<>());for(var p:params)scopes.peek().put(key(p.IDENTIFIER().getText()),type(p.valueType(),subst));
                    render(child,subst,renamed,replacement);scopes.pop();
                }else render(child,subst,renamed,replacement);
            }
            return;
        }
        if(node instanceof ValueTypeContext t){append(typeName(type(t,subst),t),t.start);return;}
        if(node instanceof ShiftOperatorContext o){append(o.getText(),o.start);return;}
        if(node instanceof PostfixContext p)infer(p,subst);
        if(node instanceof PrimaryContext p&&p.IDENTIFIER()!=null) {
            if(callNames.containsKey(p)){append(callNames.get(p),p.start);for(var child:p.children)if(child!=p.IDENTIFIER()&&child!=p.typeArguments())render(child,subst,renamed,replacement);return;}
            if(p.typeArguments()!=null&&p.LPAREN()==null&&types.containsKey(key(p.IDENTIFIER().getText()))) {
                append(typeName(new Ty(p.IDENTIFIER().getText(),arguments(p.typeArguments(),subst),0),p),p.start);return;
            }
        }
        if(node instanceof PostfixPartContext p&&callNames.containsKey(p)) {
            append(p.SAFE_DOT()!=null?"?.":".",p.start);append(callNames.get(p),p.IDENTIFIER().getSymbol());for(var child:p.children)if(child!=p.DOT()&&child!=p.SAFE_DOT()&&child!=p.IDENTIFIER()&&child!=p.typeArguments())render(child,subst,renamed,replacement);return;
        }
        if(node instanceof TerminalNode n){append(n.getSymbol()==renamed?replacement:n.getText(),n.getSymbol());return;}
        for(int i=0;i<node.getChildCount();i++)render(node.getChild(i),subst,renamed,replacement);
    }
}
