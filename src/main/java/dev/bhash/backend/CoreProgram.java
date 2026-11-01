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
public record CoreProgram(List<Function> functions, int entry, List<ObjectType> classes, List<RuntimeType> runtimeTypes, List<CollectionType> collections, ObjectType exceptionType, StructuredTypes structuredTypes, List<StaticField> staticFields, List<ClassInitialization> initializations) {
    public enum Type { INT, BYTE, INT32, REAL, BOOL, TEXT, OBJECT, ANY, VOID;
        public boolean reference() { return this==TEXT || this==OBJECT || this==ANY; }
    }
    public record StaticField(int id, int owner, Type type) {}
    public record ClassInitialization(int owner, int function) {}
    public record Initialize(int owner) implements Statement {}
    public record StaticRead(StaticField field, Source source) implements Expression { public Type type(){return field.type();} }
    public record StaticWrite(StaticField field, Expression value) implements Statement {}
    public record ObjectField(int owner, int offset, Type type, String name) {
        public ObjectField(int owner,int offset,Type type){this(owner,offset,type,"");}
    }
    public record RuntimeType(int id, String name, boolean contract) {}
    public record ObjectType(int id, String name, List<ObjectField> fields, List<Integer> virtualMethods,
                             List<Integer> interfaceMethods, List<Integer> assignableTypes) {}
    public enum CollectionKind { ARRAY, LIST, MAP, BUFFER }
    public record CollectionType(int id, CollectionKind kind, int keyKind, int valueKind,
                                 int element, int keyList, int valueList) {}
    public enum CollectionOperation { SIZE, EMPTY, GET, GET_OR, SET, CONTAINS, ADD, INSERT,
        REMOVE, CLEAR, KEYS, VALUES, VERSION, ITER_CHECK, ITER_KEY, ITER_VALUE }
    public record CollectionCall(Type type, CollectionOperation operation, Expression receiver,
                                 List<Expression> arguments, int snapshot, Source source, boolean primitiveArray) implements Expression {
        public CollectionCall(Type type,CollectionOperation operation,Expression receiver,List<Expression> arguments,int snapshot,Source source){
            this(type,operation,receiver,arguments,snapshot,source,false);
        }
    }
    public record NewCollection(int collection, boolean literal, List<Expression> arguments, Source source) implements Expression {
        public Type type(){return Type.OBJECT;}
    }
    public record IndexRead(Type type, Expression receiver, Expression index, Type keyType, Source source, boolean primitiveArray) implements Expression {
        public IndexRead(Type type,Expression receiver,Expression index,Type keyType,Source source){this(type,receiver,index,keyType,source,false);}
    }
    public record IndexWrite(IndexRead destination, Expression value) implements Statement {}
    public record Foreach(Expression collection, Binding owner, Binding index, Binding version,
                          List<Assign> items, Block body, Source source) implements Statement {}
    public record Catch(int typeId, Binding binding, Block body) {}
    public record Try(Block body, List<Catch> handlers, Block cleanup, Binding exception, Source source) implements Statement {}
    public record Throw(Expression value, Source source) implements Statement {}
    public record Fail(Expression message, Expression location) implements Statement {}
    public enum TypeAction { TEST, SAFE, CAST }
    public record TypeOperation(Type type, TypeAction action, Expression value, Type targetType, int targetId, Source source) implements Expression {}
    public record StructuredTypes(int texts,int values,int object) {}
    public enum BufferOperation { ENCODE, DECODE, READ_FILE, WRITE_FILE, SLICE, ARRAY, FILL, READ_INT, WRITE_INT, READ_REAL, WRITE_REAL }
    public record BufferCall(Type type, BufferOperation operation, List<Expression> arguments, int descriptor, Source source) implements Expression {}
    public record InputCall(Type type, Source source) implements Expression {}
    public enum TimeOperation { NOW, READ, WRITE, YEAR, MONTH, DAY }
    public record TimeCall(Type type, TimeOperation operation, List<Expression> arguments, Source source) implements Expression {}
    public enum FileOperation { SUPPORTED, EXISTS, READ_TEXT, WRITE_TEXT }
    public record FileCall(Type type, FileOperation operation, List<Expression> arguments, Source source) implements Expression {}
    public enum StructuredOperation { SPLIT, JOIN, JSON_READ, JSON_WRITE }
    public record StructuredCall(Type type, StructuredOperation operation, List<Expression> arguments, Source source) implements Expression {}
    public enum TextOperation {
        LENGTH("length"), SLICE("slice"), FIND("stdFind"), CONTAINS("stdContains"),
        STARTS("stdStarts"), ENDS("stdEnds"), REPLACE("stdReplace"), TRIM("stdTrim"),
        UPPER("upper"), LOWER("stdLower"), REPEAT("stdRepeat"), DEVANAGARI("devanagari"),
        INTEGER(null), PARSE_INTEGER(null), REAL(null), PARSE_REAL(null), FIXED("stdFixed"), FAIL(null);
        final String catalog;
        TextOperation(String catalog) { this.catalog=catalog; }
    }
    public enum NumericOperation {
        ABS_INT(Type.INT,Type.INT), ABS_REAL(Type.REAL,Type.REAL),
        MIN_INT(Type.INT,Type.INT,Type.INT), MIN_REAL(Type.REAL,Type.REAL,Type.REAL),
        MAX_INT(Type.INT,Type.INT,Type.INT), MAX_REAL(Type.REAL,Type.REAL,Type.REAL),
        SQRT(Type.REAL,Type.REAL), POW(Type.REAL,Type.REAL,Type.REAL),
        FLOOR(Type.REAL,Type.REAL), CEIL(Type.REAL,Type.REAL), ROUND(Type.REAL,Type.REAL),
        SIN(Type.REAL,Type.REAL), COS(Type.REAL,Type.REAL), LOG(Type.REAL,Type.REAL),
        EXP(Type.REAL,Type.REAL), PI(Type.REAL), SEED(Type.VOID,Type.INT),
        RANDOM_INT(Type.INT,Type.INT,Type.INT), RANDOM_UNIT(Type.REAL);
        private final Type result;
        private final List<Type> parameters;
        NumericOperation(Type result,Type... parameters){this.result=result;this.parameters=List.of(parameters);}
        public Type result(){return result;}
        public List<Type> parameters(){return parameters;}
    }
    public record NumericCall(Type type, NumericOperation operation, List<Expression> arguments, Source source) implements Expression {}
    private static final Map<String,TextOperation> GLOBAL_TEXT=Map.of(
        "लंबाई",TextOperation.LENGTH,"अंश",TextOperation.SLICE,"अंकपाठ",TextOperation.INTEGER,
        "पूर्णांकपाठ",TextOperation.PARSE_INTEGER,"बड़ाअक्षर",TextOperation.UPPER,"त्रुटि",TextOperation.FAIL);
    public record Source(String file, int line, int column) {
        @Override public String toString() { return file+":"+line+":"+column; }
    }
    public record Binding(int id, Type type, String name, Source source) {
        public Binding(int id, Type type){this(id,type,"",null);}
    }
    public record Function(int id, Type result, List<Binding> parameters, Block body, Source source, int initialization, String name) {
        public Function(int id, Type result, List<Binding> parameters, Block body, Source source, int initialization){this(id,result,parameters,body,source,initialization,"");}
        public Function(int id, Type result, List<Binding> parameters, Block body, Source source){this(id,result,parameters,body,source,-1);}
    }
    public sealed interface Expression permits StaticRead, Constant, Read, Unary, Binary, Call, TextCall, NumericCall, StructuredCall, FileCall, BufferCall, TimeCall, InputCall, Allocate, FieldRead, TypeOperation, Box, CollectionCall, NewCollection, IndexRead {
        Type type(); Source source();
    }
    public record Box(Expression value, Source source) implements Expression { public Type type(){return Type.ANY;} }
    public record Constant(Type type, Object value, Source source) implements Expression {}
    public record Read(Binding binding, Source source) implements Expression {
        public Type type() { return binding.type(); }
    }
    public record Unary(Type type, String operator, Expression value, Source source) implements Expression {}
    public record Binary(Type type, String operator, Expression left, Expression right, Source source) implements Expression {}
    public record Call(Type type, int function, List<Expression> arguments, Source source, boolean instance, int virtualSlot, int interfaceSlot, int constructionClass) implements Expression {
        public Call(Type type,int function,List<Expression> arguments,Source source,boolean instance,int virtualSlot,int interfaceSlot){this(type,function,arguments,source,instance,virtualSlot,interfaceSlot,-1);}
        public Call(Type type,int function,List<Expression> arguments,Source source){this(type,function,arguments,source,false,-1,-1);}
        public Call(Type type,int function,List<Expression> arguments,Source source,boolean instance){this(type,function,arguments,source,instance,-1,-1);}
    }
    public record TextCall(Type type, TextOperation operation, List<Expression> arguments, Source source) implements Expression {}
    public record Allocate(int objectType, Source source) implements Expression { public Type type(){return Type.OBJECT;} }
    public record FieldRead(Expression receiver, ObjectField field, Source source) implements Expression { public Type type(){return field.type();} }
    public sealed interface Statement permits Fail, Initialize, StaticWrite, Block, Assign, FieldWrite, Branch, Loop, Return, Jump, Print, Evaluate, IndexWrite, Foreach, Try, Throw, Selection {}
    public record FieldWrite(FieldRead destination, Expression value) implements Statement {}
    public record Block(List<Statement> statements, List<Binding> locals) implements Statement {}
    public record Assign(Binding binding, Expression value) implements Statement {}
    public record Branch(Expression condition, Block yes, Statement no) implements Statement {}
    public record Loop(Expression condition, Block body) implements Statement {}
    public record SelectionCase(List<Expression> labels, Block body) {}
    public record Selection(Expression value, Binding selector, List<SelectionCase> cases, Block fallback) implements Statement {}
    public record Return(Expression value,Source source) implements Statement { public Return(Expression value){this(value,value==null?null:value.source());} }
    public record Jump(boolean breaking,Source source) implements Statement { public Jump(boolean breaking){this(breaking,null);} }
    public record Print(List<Expression> arguments,Source source) implements Statement { public Print(List<Expression> arguments){this(arguments,arguments.isEmpty()?null:arguments.get(0).source());} }
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
            if (!(c instanceof FunctionDeclContext) && !(c instanceof ClassDeclContext) && !(c instanceof InterfaceDeclContext) && !(c instanceof EnumDeclContext)) throw unsupported(c);
            validate(c);
        }
    }
    private static void validate(ParserRuleContext c) throws CompilationException {
        if (c instanceof TypeParametersContext) throw unsupported(c);
        if(c instanceof TypeArgumentsContext && !(c.getParent() instanceof ValueTypeContext t &&
                t.IDENTIFIER()!=null && CollectionTypes.builtin(t.IDENTIFIER().getText())))throw unsupported(c);
        if (c instanceof ValueTypeContext t &&
                !(t.INT_TYPE()!=null || t.REAL_TYPE()!=null || t.BOOL_TYPE()!=null || t.TEXT_TYPE()!=null || t.OBJECT_TYPE()!=null || t.BYTE_TYPE()!=null || t.INT32_TYPE()!=null || t.BUFFER_TYPE()!=null || t.IDENTIFIER()!=null)) throw unsupported(c);
        if (c instanceof PrimaryContext p && !(p.INTEGER_LITERAL()!=null || p.REAL_LITERAL()!=null || p.STRING_LITERAL()!=null ||
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
        final Map<Integer,String> functionNames=new HashMap<>();
        final Map<ManagedEmitter.Binding,Binding> bindings=new IdentityHashMap<>();
        final Map<ManagedEmitter.Function,Integer> ids=new IdentityHashMap<>();
        final Map<FunctionDeclContext,ManagedEmitter.Function> declarations=new IdentityHashMap<>();
        final Map<ManagedEmitter.ClassInfo,Integer> classIds=new IdentityHashMap<>();
        final Map<ManagedEmitter.Field,ObjectField> fields=new IdentityHashMap<>();
        final Map<ManagedEmitter.Field,StaticField> statics=new LinkedHashMap<>();
        final Map<ManagedEmitter.ClassInfo,Integer> classInitializers=new IdentityHashMap<>();
        final Map<ManagedEmitter.ClassInfo,Integer> enumLookups=new IdentityHashMap<>();
        final Map<ManagedEmitter.Constructor,Integer> constructors=new IdentityHashMap<>();
        final List<ManagedEmitter.ClassInfo> classes, types;
        final List<ManagedEmitter.Function> interfaceMethods=new ArrayList<>();
        final Map<ManagedEmitter.Function,Integer> interfaceSlots=new IdentityHashMap<>();
        Binding self;
        final ManagedEmitter.ClassInfo exceptionClass;
        final Deque<Binding> caught=new ArrayDeque<>();
        final ObjectField messageField=new ObjectField(-5,8,Type.TEXT,"संदेश"), locationField=new ObjectField(-5,16,Type.TEXT,"स्थान");
        final Source runtimeSource=new Source("runtime",1,1);
        final boolean exceptionConstructors;
        Type resultType;
        final Map<ManagedEmitter.Type,Integer> collectionIds=new IdentityHashMap<>();
        final List<CollectionType> collections=new ArrayList<>();
        StructuredTypes structuredTypes;
        void ensureStructuredTypes(ParserRuleContext at)throws CompilationException{
            if(structuredTypes!=null)return;
            int texts=collectionId(listType(ManagedEmitter.Type.TEXT),at);
            int values=collectionId(listType(ManagedEmitter.Type.OBJECT),at);
            var map=new ManagedEmitter.Type("native-json-map","मानचित्र",ManagedEmitter.CollectionKind.MAP,List.of(ManagedEmitter.Type.TEXT,ManagedEmitter.Type.OBJECT));
            structuredTypes=new StructuredTypes(texts,values,collectionId(map,at));
        }
        int valueKind(ManagedEmitter.Type t,ParserRuleContext at) throws CompilationException {
            Type value=type(t,at);
            return switch(value){case INT32->8;case BYTE->7;case REAL->6;case INT->1;case BOOL->2;case TEXT->3;case OBJECT->4;case ANY->5;default->throw unsupported(at);};
        }
        int runtimeId(ManagedEmitter.Type t,ParserRuleContext at) throws CompilationException {
            if(t.collection!=null)return collectionId(t,at);
            Type value=type(t,at);
            return switch(value){case INT32->-8;case BYTE->-7;case REAL->-6;case INT->-1;case BOOL->-2;case TEXT->-3;case ANY->-4;case OBJECT->classIds.get(t.object);default->throw unsupported(at);};
        }
        ManagedEmitter.Type listType(ManagedEmitter.Type element){
            return collectionIds.keySet().stream().filter(t->t.collection==ManagedEmitter.CollectionKind.LIST && t.arguments.get(0)==element).findFirst()
                .orElseGet(()->new ManagedEmitter.Type("native-list:"+element.java,"सूची",ManagedEmitter.CollectionKind.LIST,List.of(element)));
        }
        int collectionId(ManagedEmitter.Type t,ParserRuleContext at) throws CompilationException {
            // Managed types are interned except synthetic snapshot lists; canonicalize by structural signature.
            for(var entry:collectionIds.entrySet())if(entry.getKey().collection==t.collection && entry.getKey().arguments.equals(t.arguments))return entry.getValue();
            int id=classIds.size()+collections.size();collectionIds.put(t,id);int slot=collections.size();collections.add(null);
            boolean map=t.collection==ManagedEmitter.CollectionKind.MAP;
            var value=t.arguments.get(map?1:0);var key=map?t.arguments.get(0):ManagedEmitter.Type.INT;
            int keyKind=valueKind(key,at),valueKind=valueKind(value,at);
            int element=value.collection!=null?collectionId(value,at):-1;
            int keyList=map?collectionId(listType(key),at):-1,valueList=map?collectionId(listType(value),at):-1;
            collections.set(slot,new CollectionType(id,CollectionKind.valueOf(t.collection.name()),keyKind,valueKind,element,keyList,valueList));
            return id;
        }
        final Map<ManagedEmitter.Constructor,Integer> initializers=new IdentityHashMap<>();
        final Map<ManagedEmitter.ClassInfo,List<ObjectField>> layouts=new IdentityHashMap<>();
        final Map<ManagedEmitter.ClassInfo,List<ManagedEmitter.Function>> tables=new IdentityHashMap<>();
        final Map<ManagedEmitter.Function,Integer> slots=new IdentityHashMap<>();
        int nextBinding;
        Builder(ManagedEmitter.Analysis analysis,List<ManagedEmitter.Function> checked,List<ManagedEmitter.ClassInfo> allClasses) throws CompilationException {
            a=analysis;exceptionClass=a.exceptionClass;
            classes=allClasses.stream().filter(c->(c.declaration!=null || c.enumeration!=null) && !library(c.at())).toList();
            types=allClasses.stream().filter(c->c.at()!=null && !library(c.at()) && (c.declaration!=null || c.contract!=null || c.enumeration!=null)).toList();
            for(var c:types)classIds.put(c,classIds.size());
            classIds.put(exceptionClass,-5);
            fields.put(exceptionClass.fields.values().iterator().next(),messageField);
            layouts.put(exceptionClass,List.of(messageField,locationField));tables.put(exceptionClass,List.of());
            exceptionConstructors=classes.stream().anyMatch(c->c.parent==exceptionClass) || a.constructors.values().stream().anyMatch(exceptionClass.constructors::contains);
            for(var c:types)if(c.isInterface())for(var methods:c.methods.values())for(var f:methods){
                interfaceSlots.put(f,interfaceMethods.size());interfaceMethods.add(f);
            }
            for(var c:classes){
                if(c.parent!=null && !classIds.containsKey(c.parent))throw unsupported(c.declaration);
                var layout=new ArrayList<ObjectField>();
                var table=new ArrayList<ManagedEmitter.Function>();
                if(c.parent!=null){layout.addAll(layouts.get(c.parent));table.addAll(tables.get(c.parent));}
                for(var fieldEntry:c.fields.entrySet()){
                    var f=fieldEntry.getValue();
                    if(f.shared()){statics.put(f,new StaticField(statics.size(),classIds.get(c),type(f.type(),f.declaration())));continue;}
                    var field=new ObjectField(classIds.get(c),8+layout.size()*8,type(f.type(),f.declaration()),fieldEntry.getKey());
                    fields.put(f,field);layout.add(field);
                }
                layouts.put(c,List.copyOf(layout));
                for(var methods:c.methods.values())for(var f:methods){
                    if(f.shared || f.access==ManagedEmitter.Access.PRIVATE)continue;
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
                functionNames.put(ids.get(f),(f.owner==null?"":originalName(f.owner.declaration.IDENTIFIER().getSymbol())+".")+originalName(f.declaration.IDENTIFIER().getSymbol()));
            }
            var constructed=new ArrayList<>(classes);if(exceptionConstructors)constructed.add(exceptionClass);
            for(var c:constructed)for(var ctor:c.constructors)constructors.put(ctor,ids.size()+constructors.size());
            for(var c:constructed)for(var ctor:c.constructors)initializers.put(ctor,ids.size()+constructors.size()+initializers.size());
            for(var c:classes)if(!c.record())classInitializers.put(c,ids.size()+constructors.size()+initializers.size()+classInitializers.size());
            for(var c:classes)if(c.enumeration!=null)enumLookups.put(c,ids.size()+constructors.size()+initializers.size()+classInitializers.size()+enumLookups.size());
        }
        Type type(ManagedEmitter.Type t,ParserRuleContext at) throws CompilationException {
            if(t.collection!=null){collectionId(t,at);return Type.OBJECT;}
            if(t==ManagedEmitter.Type.INT)return Type.INT;
            if(t==ManagedEmitter.Type.BYTE)return Type.BYTE;
            if(t==ManagedEmitter.Type.INT32)return Type.INT32;
            if(t==ManagedEmitter.Type.REAL)return Type.REAL;
            if(t==ManagedEmitter.Type.BOOL)return Type.BOOL;
            if(t==ManagedEmitter.Type.TEXT)return Type.TEXT;
            if(t==ManagedEmitter.Type.OBJECT)return Type.ANY;
            if(t==ManagedEmitter.Type.VOID)return Type.VOID;
            if(t==ManagedEmitter.Type.NULL || classIds.containsKey(t.object))return Type.OBJECT;
            throw unsupported(at);
        }
        Binding binding(ManagedEmitter.Binding b,ParserRuleContext at) throws CompilationException {
            Binding result=bindings.get(b);
            if(result==null) { result=new Binding(nextBinding++,type(b.type(),at),at instanceof VariableDeclContext d?originalName(d.IDENTIFIER().getSymbol()):at instanceof ParameterContext d?originalName(d.IDENTIFIER().getSymbol()):"",source(at)); bindings.put(b,result); }
            return result;
        }
        static String originalName(org.antlr.v4.runtime.Token token){return token instanceof LocatedToken t?t.originalText():token.getText();}
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
                String displayName=originalName(c.declaration!=null?c.declaration.IDENTIFIER().getSymbol():c.enumeration.IDENTIFIER().getSymbol());
                objectTypes.add(new ObjectType(classIds.get(c),displayName,this.layouts.get(c),tables.get(c).stream().map(ids::get).toList(),List.copyOf(itable),java.util.stream.Stream.concat(membership.stream().map(classIds::get),c.record()?java.util.stream.Stream.of(-4,-9):java.util.stream.Stream.of(-4)).toList()));
                for(var methods:c.methods.values())for(var f:methods){
                    self=f.shared?null:new Binding(nextBinding++,Type.OBJECT);
                    var parameters=new ArrayList<Binding>();if(self!=null)parameters.add(self);
                    if(f.declaration.parameters()!=null)for(var p:f.declaration.parameters().parameter())parameters.add(binding(a.bindings.get(p),p));
                    resultType=type(f.result,f.declaration);
                    result.add(new Function(ids.get(f),resultType,List.copyOf(parameters),block(f.declaration.block()),source(f.declaration),f.shared?classIds.get(c):-1));
                }
                for(var ctor:c.constructors){
                    var at=ctor.declaration()==null?c.declaration:ctor.declaration();
                    var parameters=new ArrayList<Binding>();
                    if(ctor.declaration()!=null && ctor.declaration().parameters()!=null)for(var p:ctor.declaration().parameters().parameter())parameters.add(binding(a.bindings.get(p),p));
                    if(c.record())for(var parameter:ctor.parameters())parameters.add(new Binding(nextBinding++,type(parameter,at)));
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
                    int recordField=0;
                    for(var f:c.fields.values()){
                        if(f.shared())continue;
                        if(c.record()){
                            statements.add(new FieldWrite(new FieldRead(new Read(self,source(at)),fields.get(f),source(at)),coerce(new Read(parameters.get(recordField++),source(at)),type(f.type(),at))));
                            continue;
                        }
                        Type t=type(f.type(),f.declaration());
                        Expression value=f.declaration().expression()!=null?expression(f.declaration().expression()):new Constant(t,t==Type.TEXT?"":t==Type.BOOL?false:(t==Type.INT || t==Type.BYTE || t==Type.INT32)?(Object)0L:t==Type.REAL?(Object)0.0:null,source(f.declaration()));
                        statements.add(new FieldWrite(new FieldRead(new Read(self,source(at)),fields.get(f),source(f.declaration())),coerce(value,t)));
                    }
                    resultType=Type.VOID;
                    if(ctor.declaration()!=null)statements.add(block(ctor.declaration().block()));
                    functionNames.put(initializers.get(ctor),originalName(c.declaration.IDENTIFIER().getSymbol())+".निर्माता");
                    result.add(new Function(initializers.get(ctor),Type.VOID,List.copyOf(initializerParameters),new Block(List.copyOf(statements),List.of()),source(at)));
                }
            }
            for(var c:classes){
                if(c.record())continue;
                self=null;resultType=Type.VOID;var statements=new ArrayList<Statement>();
                if(c.enumeration!=null){
                    var receiver=new Binding(nextBinding++,Type.OBJECT);var at=source(c.at());
                    var name=fields.get(c.fields.get(Identifiers.key("नाम")));var code=fields.get(c.fields.get(Identifiers.key("अंक")));
                    for(var member:c.enumValues.entrySet()){
                        statements.add(new Assign(receiver,new Allocate(classIds.get(c),at)));
                        statements.add(new FieldWrite(new FieldRead(new Read(receiver,at),name,at),new Constant(Type.TEXT,member.getKey(),at)));
                        statements.add(new FieldWrite(new FieldRead(new Read(receiver,at),code,at),new Constant(Type.INT,member.getValue(),at)));
                        statements.add(new StaticWrite(statics.get(c.fields.get(member.getKey())),new Read(receiver,at)));
                    }
                    result.add(new Function(classInitializers.get(c),Type.VOID,List.of(),new Block(List.copyOf(statements),List.of(receiver)),at));
                    var input=new Binding(nextBinding++,Type.INT);var location=new Binding(nextBinding++,Type.TEXT);var lookup=new ArrayList<Statement>();
                    for(var member:c.enumValues.entrySet())lookup.add(new Branch(new Binary(Type.BOOL,"==",new Read(input,at),new Constant(Type.INT,member.getValue(),at),at),
                        new Block(List.of(new Return(new StaticRead(statics.get(c.fields.get(member.getKey())),at))),List.of()),null));
                    // Carry the call-site location into the existing runtime error helper.
                    lookup.add(new Fail(new Constant(Type.TEXT,"अमान्य गणना अंक।",at),new Read(location,at)));
                    lookup.add(new Return(new Constant(Type.OBJECT,null,at)));
                    result.add(new Function(enumLookups.get(c),Type.OBJECT,List.of(input,location),new Block(List.copyOf(lookup),List.of()),at,classIds.get(c)));
                    continue;
                }
                if(c.parent!=null && c.parent!=exceptionClass)statements.add(new Initialize(classIds.get(c.parent)));
                for(var f:c.fields.values())if(f.shared()){
                    Type t=type(f.type(),f.declaration());
                    Expression value=f.declaration().expression()!=null?expression(f.declaration().expression()):new Constant(t,t==Type.TEXT?"":t==Type.BOOL?false:(t==Type.INT || t==Type.BYTE || t==Type.INT32)?(Object)0L:t==Type.REAL?(Object)0.0:null,source(f.declaration()));
                    statements.add(new StaticWrite(statics.get(f),coerce(value,t)));
                }
                result.add(new Function(classInitializers.get(c),Type.VOID,List.of(),new Block(List.copyOf(statements),List.of()),source(c.declaration)));
            }
            if(exceptionConstructors)for(var ctor:exceptionClass.constructors){
                var parameters=new ArrayList<Binding>();
                if(!ctor.parameters().isEmpty())parameters.add(new Binding(nextBinding++,Type.TEXT));
                var receiver=new Binding(nextBinding++,Type.OBJECT);
                var args=new ArrayList<Expression>();args.add(new Read(receiver,runtimeSource));for(var p:parameters)args.add(new Read(p,runtimeSource));
                var factory=List.<Statement>of(new Assign(receiver,new Allocate(-5,runtimeSource)),
                    new Evaluate(new Call(Type.VOID,initializers.get(ctor),List.copyOf(args),runtimeSource)),new Return(new Read(receiver,runtimeSource)));
                result.add(new Function(constructors.get(ctor),Type.OBJECT,List.copyOf(parameters),new Block(factory,List.of(receiver)),runtimeSource));
                var initParameters=new ArrayList<Binding>();initParameters.add(receiver);initParameters.addAll(parameters);
                Expression message=parameters.isEmpty()?new Constant(Type.TEXT,"",runtimeSource):new Read(parameters.get(0),runtimeSource);
                var init=List.<Statement>of(new FieldWrite(new FieldRead(new Read(receiver,runtimeSource),messageField,runtimeSource),message),
                    new FieldWrite(new FieldRead(new Read(receiver,runtimeSource),locationField,runtimeSource),new Constant(Type.TEXT,"",runtimeSource)));
                result.add(new Function(initializers.get(ctor),Type.VOID,List.copyOf(initParameters),new Block(init,List.of()),runtimeSource));
            }
            for(var c:classes)if(!c.record())functionNames.put(classInitializers.get(c),originalName(c.declaration!=null?c.declaration.IDENTIFIER().getSymbol():c.enumeration.IDENTIFIER().getSymbol())+".<वर्ग आरंभ>");
            self=null;
            result.replaceAll(f->new Function(f.id(),f.result(),f.parameters(),f.body(),f.source(),f.initialization(),functionNames.getOrDefault(f.id(),"")));
            return new CoreProgram(List.copyOf(result),entry,List.copyOf(objectTypes),types.stream()
                .map(c->new RuntimeType(classIds.get(c),c.spelling,c.isInterface())).toList(),List.copyOf(collections),new ObjectType(-5,"अपवाद",List.of(messageField,locationField),List.of(),
                    Collections.nCopies(interfaceMethods.size(),-1),List.of(-5,-4)),structuredTypes,List.copyOf(statics.values()),classes.stream().filter(c->!c.record()).map(c->new ClassInitialization(classIds.get(c),classInitializers.get(c))).toList());
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
        Selection selection(SwitchStatementContext c) throws CompilationException {
            Expression value=expression(c.expression());var selector=new Binding(nextBinding++,value.type());
            var cases=new ArrayList<SelectionCase>();Block fallback=null;
            for(var section:c.switchSection()){
                var statements=new ArrayList<Statement>();var locals=new ArrayList<Binding>();
                for(var item:section.statement()){
                    statements.add(statement(item));
                    if(item.variableDecl()!=null)locals.add(binding(a.bindings.get(item.variableDecl()),item.variableDecl()));
                }
                var body=new Block(List.copyOf(statements),List.copyOf(locals));
                if(section.DEFAULT()!=null){fallback=body;continue;}
                var labels=new ArrayList<Expression>();
                for(var label:section.caseValue()){
                    if(label.qualifiedName()!=null){labels.add(new StaticRead(statics.get(a.fields.get(label)),source(label)));continue;}
                    Object constant=a.constants.get(label);
                    labels.add(value.type()==Type.BOOL?new Constant(Type.BOOL,label.TRUE()!=null,source(label))
                        :new Constant(value.type(),constant,source(label)));
                }
                cases.add(new SelectionCase(List.copyOf(labels),body));
            }
            return new Selection(value,selector,List.copyOf(cases),fallback);
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
                if(destination instanceof StaticRead r)return new StaticWrite(r.field(),coerce(expression(d.expression()),r.type()));
                if(destination instanceof Read r)return new Assign(r.binding(),coerce(expression(d.expression()),r.type()));
                if(destination instanceof IndexRead r)return new IndexWrite(r,coerce(expression(d.expression()),r.type()));
                if(destination instanceof FieldRead f)return new FieldWrite(f,coerce(expression(d.expression()),f.type()));
                throw unsupported(d);
            }
            if(s.THROW()!=null)return new Throw(s.expression()==null?new Read(caught.peek(),source(s)):expression(s.expression()),source(s));
            if(s.tryStatement()!=null){
                var t=s.tryStatement();var body=block(t.block(0));var handlers=new ArrayList<Catch>();
                for(var clause:t.catchClause()){
                    var parameter=clause.parameter();var bound=a.bindings.get(parameter);var binding=binding(bound,parameter);
                    caught.push(binding);var handler=block(clause.block());caught.pop();
                    handlers.add(new Catch(classIds.get(bound.type().object),binding,handler));
                }
                var cleanup=t.FINALLY()==null?null:block(t.block(1));
                return new Try(body,List.copyOf(handlers),cleanup,new Binding(nextBinding++,Type.OBJECT),source(t));
            }
            if(s.ifStatement()!=null)return branch(s.ifStatement());
            if(s.foreachStatement()!=null){
                var loop=s.foreachStatement();var managed=a.expressions.get(loop.expression()).type();
                Expression collection=expression(loop.expression());var owner=new Binding(nextBinding++,Type.OBJECT);
                var index=new Binding(nextBinding++,Type.INT);var version=new Binding(nextBinding++,Type.INT);
                var items=new ArrayList<Assign>();
                for(int i=0;i<loop.parameter().size();i++){
                    var parameter=loop.parameter(i);var local=binding(a.bindings.get(parameter),parameter);
                    var actual=managed.arguments.get(i);
                    var item=new CollectionCall(type(actual,parameter),i==0?CollectionOperation.ITER_KEY:CollectionOperation.ITER_VALUE,
                        new Read(owner,source(loop)),List.of(new Read(index,source(loop))),-1,source(loop));
                    items.add(new Assign(local,coerce(item,local.type())));
                }
                return new Foreach(collection,owner,index,version,List.copyOf(items),block(loop.block()),source(loop));
            }
            if(s.switchStatement()!=null)return selection(s.switchStatement());
            if(s.whileStatement()!=null) {
                var w=s.whileStatement(); return new Loop(expression(w.expression()),block(w.block()));
            }
            if(s.RETURN()!=null)return new Return(s.expression()==null?null:coerce(expression(s.expression()),resultType),source(s));
            if(s.BREAK()!=null || s.CONTINUE()!=null)return new Jump(s.BREAK()!=null,source(s));
            if(s.PRINT()!=null)return new Print(arguments(s.arguments()),source(s));
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
        Expression libraryCall(ManagedEmitter.Function f,ParserRuleContext at,ArgumentsContext args) throws CompilationException {
            if(f.java.equals("BhashRuntime.readText") || f.java.equals("BhashRuntime.readInteger"))return new InputCall(type(f.result,at),source(at));
            String intrinsic=f.intrinsic;
            if(intrinsic==null && at instanceof PrimaryContext p && p.IDENTIFIER()!=null){
                String name=Identifiers.key(p.IDENTIFIER().getText());
                if(name.equals(Identifiers.key("यूटीएफ८बफ़र")))intrinsic="stdEncode";
                if(name.equals(Identifiers.key("यूटीएफ८पाठ")))intrinsic="decodeUtf8";
            }
            BufferOperation buffer=intrinsic==null?null:switch(intrinsic){case "stdEncode"->BufferOperation.ENCODE;case "decodeUtf8"->BufferOperation.DECODE;
                case "stdFileReadBytes"->BufferOperation.READ_FILE;case "stdFileWriteBytes"->BufferOperation.WRITE_FILE;default->null;};
            if(buffer!=null)return new BufferCall(type(f.result,at),buffer,arguments(args),f.result.collection!=null?collectionId(f.result,at):-1,source(at));
            TextOperation operation=null;
            if(f.intrinsic!=null) {
                TimeOperation time=switch(f.intrinsic){case "stdNow"->TimeOperation.NOW;case "stdTimeRead"->TimeOperation.READ;
                    case "stdTimeWrite"->TimeOperation.WRITE;case "stdYear"->TimeOperation.YEAR;case "stdMonth"->TimeOperation.MONTH;case "stdDay"->TimeOperation.DAY;default->null;};
                if(time!=null)return new TimeCall(type(f.result,at),time,arguments(args),source(at));
                FileOperation file=switch(f.intrinsic){case "stdFilesSupported"->FileOperation.SUPPORTED;case "stdFileExists"->FileOperation.EXISTS;
                    case "stdFileReadText"->FileOperation.READ_TEXT;case "stdFileWriteText"->FileOperation.WRITE_TEXT;default->null;};
                if(file!=null)return new FileCall(type(f.result,at),file,arguments(args),source(at));
                StructuredOperation data=switch(f.intrinsic){case "stdSplit"->StructuredOperation.SPLIT;case "stdJoin"->StructuredOperation.JOIN;
                    case "stdJsonRead"->StructuredOperation.JSON_READ;case "stdJsonWrite"->StructuredOperation.JSON_WRITE;default->null;};
                if(data!=null){ensureStructuredTypes(at);return new StructuredCall(type(f.result,at),data,checkedArguments(args,f.parameters),source(at));}
                boolean integer=f.result==ManagedEmitter.Type.INT;
                NumericOperation numeric=switch(f.intrinsic){
                    case "stdAbs"->integer?NumericOperation.ABS_INT:NumericOperation.ABS_REAL;
                    case "stdMin"->integer?NumericOperation.MIN_INT:NumericOperation.MIN_REAL;
                    case "stdMax"->integer?NumericOperation.MAX_INT:NumericOperation.MAX_REAL;
                    case "stdSqrt"->NumericOperation.SQRT;case "stdPow"->NumericOperation.POW;
                    case "stdFloor"->NumericOperation.FLOOR;case "stdCeil"->NumericOperation.CEIL;
                    case "stdRound"->NumericOperation.ROUND;case "stdSin"->NumericOperation.SIN;
                    case "stdCos"->NumericOperation.COS;case "stdLog"->NumericOperation.LOG;
                    case "stdExp"->NumericOperation.EXP;case "stdPi"->NumericOperation.PI;
                    case "stdSeed"->NumericOperation.SEED;case "stdRandom"->NumericOperation.RANDOM_INT;
                    case "stdRandomUnit"->NumericOperation.RANDOM_UNIT;default->null;
                };
                if(numeric!=null)return new NumericCall(type(f.result,at),numeric,arguments(args),source(at));
                for(var op:TextOperation.values())if(f.intrinsic.equals(op.catalog))operation=op;
            } else if(f.declaration==null && at instanceof PrimaryContext p)
                operation=GLOBAL_TEXT.entrySet().stream().filter(e->Identifiers.key(e.getKey()).equals(Identifiers.key(p.IDENTIFIER().getText())))
                    .map(Map.Entry::getValue).findFirst().orElse(null);
            if(operation==null)throw unsupported(at);
            return new TextCall(type(f.result,at),operation,arguments(args),source(at));
        }
        CollectionOperation collectionOperation(String spelling,int arity){
            String name=Identifiers.key(spelling);
            for(var item:Map.ofEntries(Map.entry("आकार",CollectionOperation.SIZE),Map.entry("लंबाई",CollectionOperation.SIZE),
                Map.entry("खाली",CollectionOperation.EMPTY),Map.entry("लो",arity==2?CollectionOperation.GET_OR:CollectionOperation.GET),
                Map.entry("रखो",CollectionOperation.SET),Map.entry("है",CollectionOperation.CONTAINS),Map.entry("जोड़ो",CollectionOperation.ADD),
                Map.entry("डालो",CollectionOperation.INSERT),Map.entry("हटाओ",CollectionOperation.REMOVE),Map.entry("साफ़",CollectionOperation.CLEAR),
                Map.entry("कुंजियाँ",CollectionOperation.KEYS),Map.entry("मूल्य",CollectionOperation.VALUES)).entrySet())
                if(Identifiers.key(item.getKey()).equals(name))return item.getValue();
            throw new IllegalStateException("Unchecked collection operation");
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
            if(constant!=null)return new Constant(constant instanceof Long?Type.INT:constant instanceof Double?Type.REAL:Type.TEXT,constant,source(c));
            if(c instanceof PrimaryContext p) {
                if(p.CONVERT()!=null || p.TYPE_TEST()!=null || p.SAFE_CAST()!=null){
                    var destination=a.conversions.get(p);Type target=type(destination,p);
                    Expression value=expression(p.expression());
                    if(target==Type.ANY && p.TYPE_TEST()==null)return coerce(value,Type.ANY);
                    if(p.CONVERT()!=null){
                        var original=a.expressions.get(p.expression()).type();
                        if(original==destination)return value;
                        boolean enumSource=original.object!=null && original.object.enumeration!=null;
                        boolean enumTarget=destination.object!=null && destination.object.enumeration!=null;
                        if(enumSource && (enumTarget || target==Type.INT || target==Type.BYTE || target==Type.INT32 || target==Type.REAL || target==Type.TEXT)){
                            var property=original.object.fields.get(Identifiers.key(target==Type.TEXT?"नाम":"अंक"));
                            value=new FieldRead(value,fields.get(property),source(p));
                            if(target==Type.TEXT)return value;
                        }
                        if(enumTarget && original!=ManagedEmitter.Type.OBJECT){
                            if(value.type()==Type.TEXT)value=new TextCall(Type.INT,TextOperation.PARSE_INTEGER,List.of(value),source(p));
                            else if(value.type()!=Type.INT)value=new TypeOperation(Type.INT,TypeAction.CAST,value,Type.INT,-1,source(p));
                            return new Call(Type.OBJECT,enumLookups.get(destination.object),List.of(value,new Constant(Type.TEXT,source(p).toString(),source(p))),source(p));
                        }
                    }
                    if(p.CONVERT()!=null && target==Type.TEXT && (value.type()==Type.INT || value.type()==Type.BYTE || value.type()==Type.INT32))
                        return new TextCall(Type.TEXT,TextOperation.INTEGER,List.of(value),source(p));
                    if(p.CONVERT()!=null && target==Type.TEXT && value.type()==Type.REAL)
                        return new TextCall(Type.TEXT,TextOperation.REAL,List.of(value),source(p));
                    if(p.CONVERT()!=null && target==Type.REAL && value.type()==Type.TEXT)
                        return new TextCall(Type.REAL,TextOperation.PARSE_REAL,List.of(value),source(p));
                    if(p.CONVERT()!=null && target==Type.INT && value.type()==Type.TEXT)
                        return new TextCall(Type.INT,TextOperation.PARSE_INTEGER,List.of(value),source(p));
                    if(p.CONVERT()!=null && (target==Type.BYTE || target==Type.INT32) && value.type()==Type.TEXT)
                        value=new TextCall(Type.INT,TextOperation.PARSE_INTEGER,List.of(value),source(p));
                    var action=p.TYPE_TEST()!=null?TypeAction.TEST:p.SAFE_CAST()!=null?TypeAction.SAFE:TypeAction.CAST;
                    return new TypeOperation(action==TypeAction.TEST?Type.BOOL:target,action,value,target,runtimeId(destination,p),source(p));
                }
                if(p.NULL()!=null)return new Constant(Type.OBJECT,null,source(c));
                if(p.THIS()!=null || p.BASE()!=null)return new Read(self,source(c));
                if(p.NEW()!=null){
                    var actual=a.expressions.get(p).type();
                    if(actual.collection!=null){
                        int id=collectionId(actual,p);var values=new ArrayList<Expression>();
                        var initializer=p.collectionInitializer();
                        if(initializer!=null){
                            if(actual.collection==ManagedEmitter.CollectionKind.MAP){
                                for(var entry:initializer.mapEntry())for(int i=0;i<2;i++)values.add(coerce(expression(entry.expression(i)),type(actual.arguments.get(i),entry)));
                            }else for(var value:arguments(initializer.arguments()))values.add(coerce(value,type(actual.arguments.get(0),p)));
                        }else if(!p.arrayDimension().isEmpty())for(var dimension:p.arrayDimension())values.add(expression(dimension.expression()));
                        else values.addAll(arguments(p.arguments()));
                        return new NewCollection(id,initializer!=null,List.copyOf(values),source(p));
                    }
                    var ctor=a.constructors.get(p);
                    if(ctor==null || !constructors.containsKey(ctor))throw unsupported(p);
                    return new Call(Type.OBJECT,constructors.get(ctor),checkedArguments(p.arguments(),ctor.parameters()),source(c),false,-1,-1,actual.object.record()?-1:classIds.get(actual.object));
                }
                if(a.fields.containsKey(p)){var field=a.fields.get(p);return field.shared()?new StaticRead(statics.get(field),source(c)):new FieldRead(new Read(self,source(c)),fields.get(field),source(c));}
                if(p.TRUE()!=null || p.FALSE()!=null)return new Constant(Type.BOOL,p.TRUE()!=null,source(c));
                if(p.IDENTIFIER()!=null) {
                    if(p.LPAREN()==null)return new Read(binding(a.reads.get(p),p),source(c));
                    var f=a.calls.get(p);
                    if(f==null && Identifiers.key(p.IDENTIFIER().getText()).equals(Identifiers.key("लंबाई")))
                        return new CollectionCall(Type.INT,CollectionOperation.SIZE,expression(p.arguments().expression(0)),List.of(),-1,source(p));
                    if(f==null)throw unsupported(p);
                    if(!ids.containsKey(f))return libraryCall(f,p,p.arguments());
                    if(f.shared && f.owner!=null)return new Call(type(f.result,p),ids.get(f),checkedArguments(p.arguments(),f.parameters),source(p));
                    if(f.owner!=null){var args=new ArrayList<Expression>();args.add(new Read(self,source(p)));args.addAll(arguments(p.arguments()));return methodCall(f,args,p);}
                    return new Call(type(f.result,p),ids.get(f),checkedArguments(p.arguments(),f.parameters),source(c));
                }
                if(p.expression()!=null)return expression(p.expression());
                throw unsupported(p);
            }
            if(c instanceof UnaryContext u && u.postfix()==null) {
                return new Unary(type(a.expressions.get(c).type(),c),u.getChild(0).getText(),expression(u.unary()),source(c));
            }
            if(c instanceof PostfixContext p && !p.postfixPart().isEmpty()) {
                Expression value=null;
                ManagedEmitter.Type previous=a.expressions.get(p.primary()).type();
                for(var part:p.postfixPart()){
                    if(previous.collection!=null){
                        if(value==null)value=expression(p.primary());
                        var result=a.expressions.get(part).type();
                        boolean map=previous.collection==ManagedEmitter.CollectionKind.MAP;
                        var element=previous.arguments.get(map?1:0);var key=map?previous.arguments.get(0):ManagedEmitter.Type.INT;
                        if(part.LBRACK()!=null)value=new IndexRead(type(result,part),value,coerce(expression(part.expression()),type(key,part)),type(key,part),source(part),previous.collection==ManagedEmitter.CollectionKind.ARRAY && !type(element,part).reference());
                        else {
                            String name=part.IDENTIFIER().getText();var args=new ArrayList<>(arguments(part.arguments()));
                            BufferOperation bufferOp=previous.collection!=ManagedEmitter.CollectionKind.BUFFER?null:switch(Identifiers.key(name)){
                                case "पूर्णांकपढ़ो"->BufferOperation.READ_INT;case "पूर्णांकलिखो"->BufferOperation.WRITE_INT;
                                case "दशमलवपढ़ो"->BufferOperation.READ_REAL;case "दशमलवलिखो"->BufferOperation.WRITE_REAL;
                                case "काटो"->BufferOperation.SLICE;case "सरणी"->BufferOperation.ARRAY;case "भरो"->BufferOperation.FILL;default->null;};
                            if(bufferOp!=null){
                                args.add(0,value);value=new BufferCall(type(result,part),bufferOp,List.copyOf(args),result.collection!=null?collectionId(result,part):-1,source(part));
                                previous=result;continue;
                            }
                            CollectionOperation operation=collectionOperation(name,args.size());
                            for(int i=0;i<args.size();i++){
                                ManagedEmitter.Type expected=switch(operation){
                                    case GET,GET_OR,REMOVE,SET->i==0?key:element;
                                    case CONTAINS->map?key:element;case ADD->element;case INSERT->i==0?ManagedEmitter.Type.INT:element;
                                    default->throw unsupported(part);
                                };
                                args.set(i,coerce(args.get(i),type(expected,part)));
                            }
                            int snapshot=result.collection!=null?collectionId(result,part):-1;
                            value=new CollectionCall(type(result,part),operation,value,List.copyOf(args),snapshot,source(part),previous.collection==ManagedEmitter.CollectionKind.ARRAY && !type(element,part).reference());
                        }
                        previous=result;continue;
                    }
                    var f=a.calls.get(part);
                    if(f!=null && f.intrinsic!=null){value=libraryCall(f,part,part.arguments());previous=a.expressions.get(part).type();continue;}
                    var selected=a.fields.get(part);
                    if(f!=null && f.shared){value=new Call(type(f.result,part),ids.get(f),checkedArguments(part.arguments(),f.parameters),source(part));previous=a.expressions.get(part).type();continue;}
                    if(selected!=null && selected.shared()){value=new StaticRead(statics.get(selected),source(part));previous=a.expressions.get(part).type();continue;}
                    if(value==null)value=expression(p.primary());
                    if(f!=null){
                        if(!ids.containsKey(f) && !interfaceSlots.containsKey(f))throw unsupported(part);
                        var args=new ArrayList<Expression>();args.add(value);args.addAll(arguments(part.arguments()));
                        value=methodCall(f,args,part);
                    }else{
                        var field=fields.get(a.fields.get(part));if(field==null)throw unsupported(part);
                        value=new FieldRead(value,field,source(part));
                    }
                    previous=a.expressions.get(part).type();
                }
                return value;
            }
            if(c.getChildCount()==1)return expression((ParserRuleContext)c.getChild(0));
            if(c instanceof PostfixContext p)return expression(p.primary());
            Expression result=expression((ParserRuleContext)c.getChild(0));
            for(int i=1;i<c.getChildCount();i+=2) {
                String op=c.getChild(i).getText();
                Expression right=expression((ParserRuleContext)c.getChild(i+1));
                Type t=Set.of("==","!=","<","<=",">",">=","&&","||").contains(op)?Type.BOOL:(result.type()==Type.BYTE || result.type()==Type.INT32)?Type.INT:result.type();
                if((op.equals("==") || op.equals("!=")) && (result.type()==Type.ANY || right.type()==Type.ANY)){
                    result=coerce(result,Type.ANY);right=coerce(right,Type.ANY);
                }
                result=new Binary(t,op,result,right,source(c));
            }
            return result;
        }
    }
}
