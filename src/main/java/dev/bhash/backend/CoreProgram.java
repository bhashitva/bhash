// SPDX-License-Identifier: Apache-2.0
package dev.bhash.backend;

import dev.bhash.frontend.Identifiers;
import dev.bhash.frontend.LocatedToken;
import dev.bhash.parser.BhashParser.*;
import dev.bhash.stdlib.StandardLibrary;
import java.util.*;
import org.antlr.v4.runtime.ParserRuleContext;
import org.antlr.v4.runtime.tree.ParseTree;

/** Target-independent checked core. No host-language expressions or identifiers. */
public record CoreProgram(List<Function> functions, int entry, List<ObjectType> classes, List<RuntimeType> runtimeTypes) {
    public enum Type { INT, BOOL, TEXT, OBJECT, ANY, VOID;
        public boolean reference() { return this==TEXT || this==OBJECT || this==ANY; }
    }
    public record ObjectField(int owner, int offset, Type type) {}
    public record RuntimeType(int id, String name, boolean contract) {}
    public record ObjectType(int id, String name, List<ObjectField> fields, List<Integer> virtualMethods,
                             List<Integer> interfaceMethods, List<Integer> assignableTypes) {}
    public enum TypeAction { TEST, SAFE, CAST }
    public record TypeOperation(Type type, TypeAction action, Expression value, Type targetType, int targetId, Source source) implements Expression {}
    public enum TextOperation {
        LENGTH("length"), SLICE("slice"), FIND("stdFind"), CONTAINS("stdContains"),
        STARTS("stdStarts"), ENDS("stdEnds"), REPLACE("stdReplace"), TRIM("stdTrim"),
        UPPER("upper"), LOWER("stdLower"), REPEAT("stdRepeat"), DEVANAGARI("devanagari"),
        INTEGER(null), PARSE_INTEGER(null), FAIL(null);
        final String catalog;
        TextOperation(String catalog) { this.catalog=catalog; }
    }
    private static final Map<String,TextOperation> GLOBAL_TEXT=Map.of(
        "लंबाई",TextOperation.LENGTH,"अंश",TextOperation.SLICE,"अंकपाठ",TextOperation.INTEGER,
        "पूर्णांकपाठ",TextOperation.PARSE_INTEGER,"बड़ाअक्षर",TextOperation.UPPER,"त्रुटि",TextOperation.FAIL);
    public record Source(String file, int line, int column) {
        @Override public String toString() { return file+":"+line+":"+column; }
    }
    public record Binding(int id, Type type) {}
    public record Function(int id, Type result, List<Binding> parameters, Block body, Source source) {}
    public sealed interface Expression permits Constant, Read, Unary, Binary, Call, TextCall, Allocate, FieldRead, TypeOperation, Box {
        Type type(); Source source();
    }
    public record Box(Expression value, Source source) implements Expression { public Type type(){return Type.ANY;} }
    public record Constant(Type type, Object value, Source source) implements Expression {}
    public record Read(Binding binding, Source source) implements Expression {
        public Type type() { return binding.type(); }
    }
    public record Unary(Type type, String operator, Expression value, Source source) implements Expression {}
    public record Binary(Type type, String operator, Expression left, Expression right, Source source) implements Expression {}
    public record Call(Type type, int function, List<Expression> arguments, Source source, boolean instance, int virtualSlot, int interfaceSlot) implements Expression {
        public Call(Type type,int function,List<Expression> arguments,Source source){this(type,function,arguments,source,false,-1,-1);}
        public Call(Type type,int function,List<Expression> arguments,Source source,boolean instance){this(type,function,arguments,source,instance,-1,-1);}
    }
    public record TextCall(Type type, TextOperation operation, List<Expression> arguments, Source source) implements Expression {}
    public record Allocate(int objectType, Source source) implements Expression { public Type type(){return Type.OBJECT;} }
    public record FieldRead(Expression receiver, ObjectField field, Source source) implements Expression { public Type type(){return field.type();} }
    public sealed interface Statement permits Block, Assign, FieldWrite, Branch, Loop, Return, Jump, Print, Evaluate {}
    public record FieldWrite(FieldRead destination, Expression value) implements Statement {}
    public record Block(List<Statement> statements, List<Binding> locals) implements Statement {}
    public record Assign(Binding binding, Expression value) implements Statement {}
    public record Branch(Expression condition, Block yes, Statement no) implements Statement {}
    public record Loop(Expression condition, Block body) implements Statement {}
    public record Return(Expression value) implements Statement {}
    public record Jump(boolean breaking) implements Statement {}
    public record Print(List<Expression> arguments) implements Statement {}
    public record Evaluate(Expression value) implements Statement {}

    private static Source source(ParserRuleContext c) {
        return new Source(LocatedToken.source(c.start,"project"), c.start.getLine(), c.start.getCharPositionInLine()+1);
    }
    private static CompilationException unsupported(ParserRuleContext c) {
        return new CompilationException(source(c)+": [BH9001] यह सुविधा प्रारंभिक मूल-मशीन लक्ष्य में उपलब्ध नहीं है।");
    }
    private static boolean library(ParserRuleContext c) {
        return StandardLibrary.SOURCE_NAME.equals(LocatedToken.source(c.start,""));
    }
    static void validateProfile(ProgramContext tree) throws CompilationException {
        for (int i=0;i<tree.getChildCount();i++) {
            var child=tree.getChild(i);
            if (!(child instanceof ParserRuleContext c) || library(c)) continue;
            if (c instanceof PackageDeclContext || c instanceof ImportDeclContext) continue;
            if (!(c instanceof FunctionDeclContext) && !(c instanceof ClassDeclContext) && !(c instanceof InterfaceDeclContext)) throw unsupported(c);
            validate(c);
        }
    }
    private static void validate(ParserRuleContext c) throws CompilationException {
        if(c instanceof ClassDeclContext d && d.RECORD()!=null)throw unsupported(c);
        if(c instanceof ClassMemberContext m && m.STATIC()!=null)throw unsupported(c);
        if (c instanceof TypeParametersContext || c instanceof TypeArgumentsContext) throw unsupported(c);
        if (c instanceof ValueTypeContext t && (!t.arraySuffix().isEmpty() ||
                !(t.INT_TYPE()!=null || t.BOOL_TYPE()!=null || t.TEXT_TYPE()!=null || t.OBJECT_TYPE()!=null || t.IDENTIFIER()!=null))) throw unsupported(c);
        if (c instanceof StatementContext s && (s.tryStatement()!=null || s.THROW()!=null ||
                s.switchStatement()!=null || s.foreachStatement()!=null)) throw unsupported(c);
        if(c instanceof PostfixPartContext p && p.LBRACK()!=null)throw unsupported(c);
        if (c instanceof UnaryContext u && u.BIT_NOT()!=null) throw unsupported(c);
        if ((c instanceof ShiftContext || c instanceof BitwiseOrContext || c instanceof BitwiseXorContext ||
                c instanceof BitwiseAndContext) && c.getChildCount()>1) throw unsupported(c);
        if (c instanceof PrimaryContext p && !(p.INTEGER_LITERAL()!=null || p.STRING_LITERAL()!=null ||
                p.TRUE()!=null || p.FALSE()!=null || p.IDENTIFIER()!=null || p.NEW()!=null || p.THIS()!=null || p.BASE()!=null || p.NULL()!=null || p.CONVERT()!=null || p.TYPE_TEST()!=null || p.SAFE_CAST()!=null ||
                p.expression()!=null && p.LPAREN()!=null && p.CONVERT()==null && p.TYPE_TEST()==null && p.SAFE_CAST()==null))
            throw unsupported(c);
        for (int i=0;i<c.getChildCount();i++) if (c.getChild(i) instanceof ParserRuleContext p) validate(p);
    }
    static CoreProgram build(ProgramContext tree, ManagedEmitter.Analysis analysis,
                             List<ManagedEmitter.Function> checked, List<ManagedEmitter.ClassInfo> classes) throws CompilationException {
        return new Builder(analysis,checked,classes).program(tree);
    }
    private static final class Builder {
        final ManagedEmitter.Analysis a;
        final Map<ManagedEmitter.Binding,Binding> bindings=new IdentityHashMap<>();
        final Map<ManagedEmitter.Function,Integer> ids=new IdentityHashMap<>();
        final Map<FunctionDeclContext,ManagedEmitter.Function> declarations=new IdentityHashMap<>();
        final Map<ManagedEmitter.ClassInfo,Integer> classIds=new IdentityHashMap<>();
        final Map<ManagedEmitter.Field,ObjectField> fields=new IdentityHashMap<>();
        final Map<ManagedEmitter.Constructor,Integer> constructors=new IdentityHashMap<>();
        final List<ManagedEmitter.ClassInfo> classes, types;
        final List<ManagedEmitter.Function> interfaceMethods=new ArrayList<>();
        final Map<ManagedEmitter.Function,Integer> interfaceSlots=new IdentityHashMap<>();
        Binding self;
        Type resultType;
        final Map<ManagedEmitter.Constructor,Integer> initializers=new IdentityHashMap<>();
        final Map<ManagedEmitter.ClassInfo,List<ObjectField>> layouts=new IdentityHashMap<>();
        final Map<ManagedEmitter.ClassInfo,List<ManagedEmitter.Function>> tables=new IdentityHashMap<>();
        final Map<ManagedEmitter.Function,Integer> slots=new IdentityHashMap<>();
        int nextBinding;
        Builder(ManagedEmitter.Analysis analysis,List<ManagedEmitter.Function> checked,List<ManagedEmitter.ClassInfo> allClasses) throws CompilationException {
            a=analysis;
            classes=allClasses.stream().filter(c->c.declaration!=null && !library(c.declaration)).toList();
            types=allClasses.stream().filter(c->c.at()!=null && !library(c.at()) && (c.declaration!=null || c.contract!=null)).toList();
            for(var c:types)classIds.put(c,classIds.size());
            for(var c:types)if(c.isInterface())for(var methods:c.methods.values())for(var f:methods){
                interfaceSlots.put(f,interfaceMethods.size());interfaceMethods.add(f);
            }
            for(var c:classes){
                if(c.parent!=null && !classIds.containsKey(c.parent))throw unsupported(c.declaration);
                var layout=new ArrayList<ObjectField>();
                var table=new ArrayList<ManagedEmitter.Function>();
                if(c.parent!=null){layout.addAll(layouts.get(c.parent));table.addAll(tables.get(c.parent));}
                for(var f:c.fields.values()){
                    var field=new ObjectField(classIds.get(c),8+layout.size()*8,type(f.type(),f.declaration()));
                    fields.put(f,field);layout.add(field);
                }
                layouts.put(c,List.copyOf(layout));
                for(var methods:c.methods.values())for(var f:methods){
                    if(f.access==ManagedEmitter.Access.PRIVATE)continue;
                    int index=-1;
                    for(int i=0;i<table.size();i++)if(table.get(i).java.equals(f.java)){index=i;break;}
                    if(index<0){index=table.size();table.add(f);}else table.set(index,f);
                    slots.put(f,index);
                }
                tables.put(c,List.copyOf(table));
            }
            var allFunctions=new ArrayList<>(checked);
            for(var c:classes)for(var methods:c.methods.values())allFunctions.addAll(methods);
            checked=allFunctions;
            for(var f:checked) if(f.declaration!=null && !library(f.declaration)) {
                ids.put(f,ids.size()); declarations.put(f.declaration,f);
            }
            for(var c:classes)for(var ctor:c.constructors)constructors.put(ctor,ids.size()+constructors.size());
            for(var c:classes)for(var ctor:c.constructors)initializers.put(ctor,ids.size()+constructors.size()+initializers.size());
        }
        Type type(ManagedEmitter.Type t,ParserRuleContext at) throws CompilationException {
            if(t==ManagedEmitter.Type.INT)return Type.INT;
            if(t==ManagedEmitter.Type.BOOL)return Type.BOOL;
            if(t==ManagedEmitter.Type.TEXT)return Type.TEXT;
            if(t==ManagedEmitter.Type.OBJECT)return Type.ANY;
            if(t==ManagedEmitter.Type.VOID)return Type.VOID;
            if(t==ManagedEmitter.Type.NULL || classIds.containsKey(t.object))return Type.OBJECT;
            throw unsupported(at);
        }
        Binding binding(ManagedEmitter.Binding b,ParserRuleContext at) throws CompilationException {
            Binding result=bindings.get(b);
            if(result==null) { result=new Binding(nextBinding++,type(b.type(),at)); bindings.put(b,result); }
            return result;
        }
        CoreProgram program(ProgramContext tree) throws CompilationException {
            var result=new ArrayList<Function>(); int entry=-1;
            for(var d:tree.functionDecl()) {
                if(library(d))continue;
                var f=declarations.get(d);
                var parameters=new ArrayList<Binding>();
                if(d.parameters()!=null)for(var p:d.parameters().parameter())parameters.add(binding(a.bindings.get(p),p));
                int id=ids.get(f);resultType=type(f.result,d);
                if(Identifiers.key(d.IDENTIFIER().getText()).equals(Identifiers.key("मुख्य")))entry=id;
                result.add(new Function(id,type(f.result,d),List.copyOf(parameters),block(d.block()),source(d)));
            }
            if(entry<0)throw new CompilationException("Native core has no checked entry point.");
            var objectTypes=new ArrayList<ObjectType>();
            for(var c:classes){
                var membership=new LinkedHashSet<ManagedEmitter.ClassInfo>();collectTypes(c,membership);
                var itable=new ArrayList<Integer>();
                for(var required:interfaceMethods){
                    var implementation=membership.contains(required.owner)?tables.get(c).stream().filter(f->f.java.equals(required.java) && f.result==required.result && f.access==ManagedEmitter.Access.PUBLIC).findFirst().orElse(null):null;
                    itable.add(implementation==null?-1:ids.get(implementation));
                }
                objectTypes.add(new ObjectType(classIds.get(c),c.spelling,this.layouts.get(c),tables.get(c).stream().map(ids::get).toList(),List.copyOf(itable),java.util.stream.Stream.concat(membership.stream().map(classIds::get),java.util.stream.Stream.of(-4)).toList()));
                for(var methods:c.methods.values())for(var f:methods){
                    self=new Binding(nextBinding++,Type.OBJECT);
                    var parameters=new ArrayList<Binding>();parameters.add(self);
                    if(f.declaration.parameters()!=null)for(var p:f.declaration.parameters().parameter())parameters.add(binding(a.bindings.get(p),p));
                    resultType=type(f.result,f.declaration);
                    result.add(new Function(ids.get(f),resultType,List.copyOf(parameters),block(f.declaration.block()),source(f.declaration)));
                }
                for(var ctor:c.constructors){
                    var at=ctor.declaration()==null?c.declaration:ctor.declaration();
                    var parameters=new ArrayList<Binding>();
                    if(ctor.declaration()!=null && ctor.declaration().parameters()!=null)for(var p:ctor.declaration().parameters().parameter())parameters.add(binding(a.bindings.get(p),p));
                    // A factory allocates exactly once. Initializers never allocate another receiver.
                    self=new Binding(nextBinding++,Type.OBJECT);
                    var arguments=new ArrayList<Expression>();arguments.add(new Read(self,source(at)));
                    for(var p:parameters)arguments.add(new Read(p,source(at)));
                    var factory=List.<Statement>of(new Assign(self,new Allocate(classIds.get(c),source(at))),
                        new Evaluate(new Call(Type.VOID,initializers.get(ctor),List.copyOf(arguments),source(at))),
                        new Return(new Read(self,source(at))));
                    result.add(new Function(constructors.get(ctor),Type.OBJECT,List.copyOf(parameters),new Block(factory,List.of(self)),source(at)));
                    self=new Binding(nextBinding++,Type.OBJECT);
                    var initializerParameters=new ArrayList<Binding>();initializerParameters.add(self);initializerParameters.addAll(parameters);
                    var statements=new ArrayList<Statement>();
                    if(c.parent!=null){
                        var parent=a.baseConstructors.get(ctor);
                        if(parent==null || !initializers.containsKey(parent))throw unsupported(at);
                        var baseArguments=new ArrayList<Expression>();baseArguments.add(new Read(self,source(at)));
                        baseArguments.addAll(checkedArguments(ctor.declaration()==null?null:ctor.declaration().arguments(),parent.parameters()));
                        statements.add(new Evaluate(new Call(Type.VOID,initializers.get(parent),List.copyOf(baseArguments),source(at))));
                    }
                    for(var f:c.fields.values()){
                        Type t=type(f.type(),f.declaration());
                        Expression value=f.declaration().expression()!=null?expression(f.declaration().expression()):new Constant(t,t==Type.TEXT?"":t==Type.BOOL?false:t==Type.INT?0L:null,source(f.declaration()));
                        statements.add(new FieldWrite(new FieldRead(new Read(self,source(at)),fields.get(f),source(f.declaration())),coerce(value,t)));
                    }
                    resultType=Type.VOID;
                    if(ctor.declaration()!=null)statements.add(block(ctor.declaration().block()));
                    result.add(new Function(initializers.get(ctor),Type.VOID,List.copyOf(initializerParameters),new Block(List.copyOf(statements),List.of()),source(at)));
                }
            }
            self=null;
            return new CoreProgram(List.copyOf(result),entry,List.copyOf(objectTypes),types.stream()
                .map(c->new RuntimeType(classIds.get(c),c.spelling,c.isInterface())).toList());
        }
        void collectTypes(ManagedEmitter.ClassInfo c, Set<ManagedEmitter.ClassInfo> result) throws CompilationException {
            if(c==null || !result.add(c))return;
            if(!classIds.containsKey(c))throw unsupported(c.at());
            collectTypes(c.parent,result);
            for(var contract:c.contracts)collectTypes(contract,result);
        }
        Block block(BlockContext c) throws CompilationException {
            var result=new ArrayList<Statement>();
            var locals=new ArrayList<Binding>();
            for(var s:c.statement()) {
                result.add(statement(s));
                if(s.variableDecl()!=null) locals.add(binding(a.bindings.get(s.variableDecl()),s.variableDecl()));
            }
            return new Block(List.copyOf(result),List.copyOf(locals));
        }
        Statement branch(IfStatementContext c) throws CompilationException {
            Statement no=c.ifStatement()!=null?branch(c.ifStatement()):c.block().size()>1?block(c.block(1)):null;
            return new Branch(expression(c.expression()),block(c.block(0)),no);
        }
        Statement statement(StatementContext s) throws CompilationException {
            if(s.variableDecl()!=null) {
                var d=s.variableDecl();
                var local=binding(a.bindings.get(d),d);
                return new Assign(local,coerce(expression(d.expression()),local.type()));
            }
            if(s.assignment()!=null) {
                var d=s.assignment();
                Expression destination=expression(d.postfix());
                if(destination instanceof Read r)return new Assign(r.binding(),coerce(expression(d.expression()),r.type()));
                if(destination instanceof FieldRead f)return new FieldWrite(f,coerce(expression(d.expression()),f.type()));
                throw unsupported(d);
            }
            if(s.ifStatement()!=null)return branch(s.ifStatement());
            if(s.whileStatement()!=null) {
                var w=s.whileStatement(); return new Loop(expression(w.expression()),block(w.block()));
            }
            if(s.RETURN()!=null)return new Return(s.expression()==null?null:coerce(expression(s.expression()),resultType));
            if(s.BREAK()!=null || s.CONTINUE()!=null)return new Jump(s.BREAK()!=null);
            if(s.PRINT()!=null)return new Print(arguments(s.arguments()));
            if(s.block()!=null)return block(s.block());
            return new Evaluate(expression(s.expression()));
        }
        List<Expression> arguments(ArgumentsContext c) throws CompilationException {
            var result=new ArrayList<Expression>();
            if(c!=null)for(var e:c.expression())result.add(expression(e));
            return List.copyOf(result);
        }
        Expression coerce(Expression value,Type expected){
            return expected==Type.ANY && value.type()!=Type.ANY?new Box(value,value.source()):value;
        }
        List<Expression> checkedArguments(ArgumentsContext c,List<ManagedEmitter.Type> parameters) throws CompilationException {
            var values=new ArrayList<>(arguments(c));
            for(int i=0;i<values.size();i++)values.set(i,coerce(values.get(i),type(parameters.get(i),c)));
            return List.copyOf(values);
        }
        TextCall textCall(ManagedEmitter.Function f,ParserRuleContext at,ArgumentsContext args) throws CompilationException {
            TextOperation operation=null;
            if(f.intrinsic!=null) {
                for(var op:TextOperation.values())if(f.intrinsic.equals(op.catalog))operation=op;
            } else if(f.declaration==null && at instanceof PrimaryContext p)
                operation=GLOBAL_TEXT.entrySet().stream().filter(e->Identifiers.key(e.getKey()).equals(Identifiers.key(p.IDENTIFIER().getText())))
                    .map(Map.Entry::getValue).findFirst().orElse(null);
            if(operation==null)throw unsupported(at);
            return new TextCall(type(f.result,at),operation,arguments(args),source(at));
        }
        Call methodCall(ManagedEmitter.Function f,List<Expression> args,ParserRuleContext at) throws CompilationException {
            args=new ArrayList<>(args);
            for(int i=0;i<f.parameters.size();i++)args.set(i+1,coerce(args.get(i+1),type(f.parameters.get(i),at)));
            int slot=a.baseCalls.contains(at)?-1:slots.getOrDefault(f,-1);
            boolean contract=f.owner.isInterface();
            return new Call(type(f.result,at),contract?-1:ids.get(f),List.copyOf(args),source(at),true,slot,contract?interfaceSlots.get(f):-1);
        }
        Expression expression(ParserRuleContext c) throws CompilationException {
            Object constant=a.constants.get(c);
            if(constant!=null)return new Constant(constant instanceof Long?Type.INT:Type.TEXT,constant,source(c));
            if(c instanceof PrimaryContext p) {
                if(p.CONVERT()!=null || p.TYPE_TEST()!=null || p.SAFE_CAST()!=null){
                    var destination=a.conversions.get(p);Type target=type(destination,p);
                    Expression value=expression(p.expression());
                    if(target==Type.ANY && p.TYPE_TEST()==null)return coerce(value,Type.ANY);
                    if(p.CONVERT()!=null && target==Type.TEXT && value.type()==Type.INT)
                        return new TextCall(Type.TEXT,TextOperation.INTEGER,List.of(value),source(p));
                    if(p.CONVERT()!=null && target==Type.INT && value.type()==Type.TEXT)
                        return new TextCall(Type.INT,TextOperation.PARSE_INTEGER,List.of(value),source(p));
                    var action=p.TYPE_TEST()!=null?TypeAction.TEST:p.SAFE_CAST()!=null?TypeAction.SAFE:TypeAction.CAST;
                    return new TypeOperation(action==TypeAction.TEST?Type.BOOL:target,action,value,target,target==Type.OBJECT?classIds.get(destination.object):target==Type.ANY?-4:target==Type.INT?-1:target==Type.BOOL?-2:-3,source(p));
                }
                if(p.NULL()!=null)return new Constant(Type.OBJECT,null,source(c));
                if(p.THIS()!=null || p.BASE()!=null)return new Read(self,source(c));
                if(p.NEW()!=null){
                    var ctor=a.constructors.get(p);
                    if(ctor==null || !constructors.containsKey(ctor))throw unsupported(p);
                    return new Call(Type.OBJECT,constructors.get(ctor),checkedArguments(p.arguments(),ctor.parameters()),source(c));
                }
                if(a.fields.containsKey(p))return new FieldRead(new Read(self,source(c)),fields.get(a.fields.get(p)),source(c));
                if(p.TRUE()!=null || p.FALSE()!=null)return new Constant(Type.BOOL,p.TRUE()!=null,source(c));
                if(p.IDENTIFIER()!=null) {
                    if(p.LPAREN()==null)return new Read(binding(a.reads.get(p),p),source(c));
                    var f=a.calls.get(p);
                    if(f==null)throw unsupported(p);
                    if(!ids.containsKey(f))return textCall(f,p,p.arguments());
                    if(f.owner!=null){var args=new ArrayList<Expression>();args.add(new Read(self,source(p)));args.addAll(arguments(p.arguments()));return methodCall(f,args,p);}
                    return new Call(type(f.result,p),ids.get(f),checkedArguments(p.arguments(),f.parameters),source(c));
                }
                if(p.expression()!=null)return expression(p.expression());
                throw unsupported(p);
            }
            if(c instanceof UnaryContext u && u.postfix()==null) {
                if(u.BIT_NOT()!=null)throw unsupported(u);
                return new Unary(type(a.expressions.get(c).type(),c),u.getChild(0).getText(),expression(u.unary()),source(c));
            }
            if(c instanceof PostfixContext p && !p.postfixPart().isEmpty()) {
                Expression value=null;
                for(var part:p.postfixPart()){
                    var f=a.calls.get(part);
                    if(f!=null && f.intrinsic!=null){value=textCall(f,part,part.arguments());continue;}
                    if(value==null)value=expression(p.primary());
                    if(f!=null){
                        if(!ids.containsKey(f) && !interfaceSlots.containsKey(f))throw unsupported(part);
                        var args=new ArrayList<Expression>();args.add(value);args.addAll(arguments(part.arguments()));
                        value=methodCall(f,args,part);
                    }else{
                        var field=fields.get(a.fields.get(part));if(field==null)throw unsupported(part);
                        value=new FieldRead(value,field,source(part));
                    }
                }
                return value;
            }
            if(c.getChildCount()==1)return expression((ParserRuleContext)c.getChild(0));
            if(c instanceof PostfixContext p)return expression(p.primary());
            Expression result=expression((ParserRuleContext)c.getChild(0));
            for(int i=1;i<c.getChildCount();i+=2) {
                String op=c.getChild(i).getText();
                Expression right=expression((ParserRuleContext)c.getChild(i+1));
                Type t=Set.of("==","!=","<","<=",">",">=","&&","||").contains(op)?Type.BOOL:result.type();
                if((op.equals("==") || op.equals("!=")) && (result.type()==Type.ANY || right.type()==Type.ANY)){
                    result=coerce(result,Type.ANY);right=coerce(right,Type.ANY);
                }
                result=new Binary(t,op,result,right,source(c));
            }
            return result;
        }
    }
}
