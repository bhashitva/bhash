// SPDX-License-Identifier: Apache-2.0
package dev.bhash.backend;

import dev.bhash.frontend.Identifiers;
import dev.bhash.parser.BhashBaseVisitor;
import dev.bhash.parser.BhashParser.*;
import java.math.BigInteger;
import java.util.*;
import org.antlr.v4.runtime.ParserRuleContext;

/** Shared semantic checking and source lowering for JVM and CLR. User names are mangled. */
public final class ManagedEmitter extends BhashBaseVisitor<ManagedEmitter.Expr> {
    static final class Type {
        static final Type INT = new Type("long", "पूर्णांक"), REAL = new Type("double", "दशमलव"),
                BOOL = new Type("boolean", "तार्किक"), TEXT = new Type("String", "पाठ"),
                VOID = new Type("void", "शून्य"), NULL = new Type("", "रिक्त");
        final String java, bhash;
        final ClassInfo object;
        Type(String java, String bhash) { this(java, bhash, null); }
        Type(String java, String bhash, ClassInfo object) { this.java = java; this.bhash = bhash; this.object = object; }
        static Type[] values() { return new Type[]{INT, REAL, BOOL, TEXT, VOID}; }
        boolean reference() { return object != null || this == NULL; }
    }
    record Location(boolean mutable) {}
    record Expr(Type type, String java, boolean call, Location location, boolean base, boolean typeReceiver) {
        Expr(Type type, String java, boolean call, Location location, boolean base) { this(type,java,call,location,base,false); }
        Expr(Type type, String java) { this(type, java, false, null, false); }
        Expr(Type type, String java, boolean call) { this(type, java, call, null, false); }
        Expr(Type type, String java, Location location) { this(type, java, false, location, false); }
    }
    enum Access { PRIVATE, PROTECTED, PUBLIC }
    static final class ClassInfo {
        final String spelling, java;
        final Type type;
        final ClassDeclContext declaration;
        final InterfaceDeclContext contract;
        final List<ClassInfo> contracts = new ArrayList<>();
        ParserRuleContext at() { return declaration == null ? contract : declaration; }
        boolean isInterface() { return contract != null; }
        final Map<String, Field> fields = new LinkedHashMap<>();
        final Map<String, List<Function>> methods = new LinkedHashMap<>();
        ClassInfo parent;
        final List<Constructor> constructors = new ArrayList<>();
        int state;
        ClassInfo(String spelling, String java, ClassDeclContext declaration) {
            this(spelling, java, declaration, null);
        }
        ClassInfo(String spelling, String java, ClassDeclContext declaration, InterfaceDeclContext contract) {
            this.spelling = spelling; this.java = java; this.declaration = declaration; this.contract = contract;
            this.type = new Type(java, spelling, this);
        }
    }
    record Field(String java, Type type, boolean mutable, Access access, ClassInfo owner, FieldDeclContext declaration, boolean shared) {}
    record Constructor(List<Type> parameters, Access access, ConstructorDeclContext declaration) {}
    static final class Function {
        final String java;
        final Type result;
        final List<Type> parameters;
        final ClassInfo owner;
        final Access access;
        final boolean overriding;
        final boolean shared;
        final FunctionDeclContext declaration;
        Function(String java, Type result, List<Type> parameters, ClassInfo owner, Access access,
                 boolean overriding, FunctionDeclContext declaration, boolean shared) {
            this.java = java; this.result = result; this.parameters = parameters; this.owner = owner;
            this.access = access; this.overriding = overriding; this.declaration = declaration; this.shared = shared;
        }
    }
    record Binding(String java, Type type, boolean mutable) {}
    enum Flow { NORMAL, RETURN, BREAK, CONTINUE }
    private static final BigInteger MIN = BigInteger.valueOf(Long.MIN_VALUE);
    private static final BigInteger MAX = BigInteger.valueOf(Long.MAX_VALUE);
    private final String sourceName;
    private final Target target;
    private final Map<String, List<Function>> functions = new LinkedHashMap<>();
    private final Map<String,String> slots = new LinkedHashMap<>();
    private final Map<String, ClassInfo> classes = new LinkedHashMap<>();
    private final List<ClassInfo> classOrder = new ArrayList<>();
    private final Deque<Map<String, Binding>> scopes = new ArrayDeque<>();
    private final StringBuilder output = new StringBuilder();
    private Type returnType;
    private ClassInfo currentClass;
    private boolean fieldInitializer, baseArguments, constructorBody, currentStatic;
    private int variableId, loopDepth, methodId, fieldId, switchId;
    private static final class BreakTarget {
        final String label;
        boolean used;
        BreakTarget(String label) { this.label=label; }
    }
    private final Deque<BreakTarget> breakTargets = new ArrayDeque<>();

    // Visitor APIs cannot throw checked exceptions; unwrap this at the public boundary.
    private static final class Invalid extends RuntimeException {
        Invalid(String message) { super(message); }
    }

    private ManagedEmitter(String sourceName, Target target) { this.sourceName = sourceName; this.target = target; }

    public static String emit(String sourceName, ProgramContext program, Target target) throws CompilationException {
        if (target != Target.JVM && target != Target.CLR) throw new IllegalArgumentException("Not a managed target");
        try { return new ManagedEmitter(sourceName, target).program(GenericExpander.expand(sourceName, program)); }
        catch (Invalid error) { throw new CompilationException(error.getMessage()); }
    }

    private Invalid error(ParserRuleContext at, String message) {
        return new Invalid(sourceName + ":" + at.getStart().getLine() + ":"
                + (at.getStart().getCharPositionInLine() + 1) + ": " + message);
    }

    private Type type(ParserRuleContext at) {
        String spelling = at.getText();
        for (Type primitive : Type.values()) if (primitive.bhash.equals(spelling)) return primitive;
        ClassInfo found = classes.get(Identifiers.key(spelling));
        if (found == null) throw error(at, "Unknown type: " + spelling);
        return found.type;
    }

    private String typeName(Type type) {
        if (target == Target.CLR && type == Type.BOOL) return "bool";
        if (target == Target.CLR && type == Type.TEXT) return "string";
        return type.java;
    }

    private String program(ProgramContext program) {
        collectClasses(program);
        functions.put(Identifiers.key("पढ़ो"), new ArrayList<>(List.of(new Function("BhashRuntime.readInteger", Type.INT, List.of(), null, Access.PUBLIC, false, null, true))));
        functions.put(Identifiers.key("पाठपढ़ो"), new ArrayList<>(List.of(new Function("BhashRuntime.readText", Type.TEXT, List.of(), null, Access.PUBLIC, false, null, true))));
        functions.put(Identifiers.key("लंबाई"), new ArrayList<>(List.of(new Function("BhashRuntime.length", Type.INT, List.of(Type.TEXT), null, Access.PUBLIC, false, null, true))));
        functions.put(Identifiers.key("अंश"), new ArrayList<>(List.of(new Function("BhashRuntime.slice", Type.TEXT, List.of(Type.TEXT,Type.INT,Type.INT), null, Access.PUBLIC, false, null, true))));
        functions.put(Identifiers.key("अंकपाठ"), new ArrayList<>(List.of(new Function("BhashRuntime.integerText", Type.TEXT, List.of(Type.INT), null, Access.PUBLIC, false, null, true))));
        functions.put(Identifiers.key("पूर्णांकपाठ"), new ArrayList<>(List.of(new Function("BhashRuntime.parseInteger", Type.INT, List.of(Type.TEXT), null, Access.PUBLIC, false, null, true))));
        functions.put(Identifiers.key("बड़ाअक्षर"), new ArrayList<>(List.of(new Function("BhashRuntime.upper", Type.TEXT, List.of(Type.TEXT), null, Access.PUBLIC, false, null, true))));
        functions.put(Identifiers.key("त्रुटि"), new ArrayList<>(List.of(new Function("BhashRuntime.fail", Type.VOID, List.of(Type.TEXT), null, Access.PUBLIC, false, null, true))));
        int globalId=0;
        for (var declaration : program.functionDecl()) {
            String name = Identifiers.key(declaration.IDENTIFIER().getText());
            if (functions.containsKey(name) && functions.get(name).get(0).declaration == null) throw error(declaration,"Duplicate or reserved function: पढ़ो");
            if (declaration.block()==null) throw error(declaration,"A function requires a body.");
            var function = new Function("f" + globalId++, type(declaration.returnType()), parameters(declaration.parameters()),
                    null, Access.PUBLIC, false, declaration, true);
            addFunction(functions, name, function, declaration);
        }
        var entries = functions.get(Identifiers.key("मुख्य"));
        if (entries == null || entries.size()!=1 || entries.get(0).result != Type.VOID || !entries.get(0).parameters.isEmpty())
            throw error(program, "Executable requires exactly one कार्य मुख्य() -> शून्य.");
        var main=entries.get(0);
        if (target == Target.JVM) {
            output.append("public final class BhashProgram {\n");
            output.append("public static void main(String[] args) { try { ").append(main.java)
                    .append("(); } catch (RuntimeException | ExceptionInInitializerError e) { Throwable f=bhashFailure(e);")
                    .append(" if (!(f instanceof BhashRuntime.Failure || f instanceof ArithmeticException)) throw e;")
                    .append(" BhashRuntime.report(f.getMessage()); System.exit(1); } }\n");
            output.append("private static Throwable bhashFailure(Throwable e) { while(e instanceof ExceptionInInitializerError && e.getCause()!=null) e=e.getCause(); return e; }\n");
        } else {
            output.append("using System;\ninternal static class BhashProgram {\n");
            output.append("public static int Main(string[] args) { try { ").append(main.java)
                    .append("(); return 0; } catch (Exception e) when (bhashFailure(e) is BhashRuntime.Failure || bhashFailure(e) is ArithmeticException) {")
                    .append(" BhashRuntime.report(bhashFailure(e).Message); return 1; } }\n");
            output.append("private static Exception bhashFailure(Exception e) { while(e is TypeInitializationException && e.InnerException!=null) e=e.InnerException; return e; }\n");
        }
        for (var overloads : functions.values()) for (var function : overloads) if (function.declaration!=null) emitFunction(function);
        for (ClassInfo definition : classOrder) emitClass(definition);
        return output.append("}\n").toString();
    }

    private void emitFunction(Function function) {
        var declaration = function.declaration;
        currentClass = function.owner;
        currentStatic = function.shared;
        returnType = function.result;
        scopes.push(new HashMap<>());
        if (function.owner == null) output.append("static ");
        else {
            if (target == Target.JVM && function.overriding) output.append("@Override ");
            output.append(accessName(function.access)).append(' ');
            if (function.shared) output.append("static ");
            if (target == Target.CLR && !function.shared && function.access != Access.PRIVATE) output.append(function.overriding ? "override " : "virtual ");
        }
        output.append(typeName(function.result)).append(' ').append(function.java).append('(');
        emitParameters(declaration.parameters(), function.parameters);
        output.append(") {\n");
        var flow = statements(declaration.block());
        if (returnType != Type.VOID && flow.contains(Flow.NORMAL))
            throw error(declaration, "Function must return " + returnType.bhash + " on every path.");
        output.append("}\n");
        scopes.pop();
        currentClass = null;
        currentStatic = false;
    }

    private List<Type> parameters(ParametersContext parameters) {
        return parameters == null ? List.of() : parameters.parameter().stream().map(p -> type(p.valueType())).toList();
    }

    private Access access(VisibilityContext visibility) {
        if (visibility == null || visibility.PUBLIC() != null) return Access.PUBLIC;
        return visibility.PRIVATE() != null ? Access.PRIVATE : Access.PROTECTED;
    }

    private String accessName(Access access) { return access.name().toLowerCase(Locale.ROOT); }

    private void addFunction(Map<String,List<Function>> map, String name, Function function, ParserRuleContext at) {
        var overloads=map.computeIfAbsent(Identifiers.key(name),k->new ArrayList<>());
        if (overloads.stream().anyMatch(f->f.parameters.equals(function.parameters)))
            throw error(at,"Duplicate function signature (return types do not distinguish overloads): " + name);
        overloads.add(function);
    }
    private String slot(String name,List<Type> signature) {
        String key=Identifiers.key(name)+signature.stream().map(t->t.java).toList();
        return slots.computeIfAbsent(key,k->"m"+methodId++);
    }
    private void collectClasses(ProgramContext program) {
        for (var declaration : program.classDecl()) {
            String name = declaration.IDENTIFIER().getText();
            var definition = new ClassInfo(name, "C" + classes.size(), declaration);
            if (classes.putIfAbsent(Identifiers.key(name), definition) != null) throw error(declaration, "Duplicate class: " + name);
        }
        for (var declaration : program.interfaceDecl()) {
            String name=declaration.IDENTIFIER().getText();
            var definition=new ClassInfo(name,"C"+classes.size(),null,declaration);
            if (classes.putIfAbsent(Identifiers.key(name),definition)!=null) throw error(declaration,"Duplicate type: " + name);
        }
        for (ClassInfo definition : classes.values()) {
            if (!definition.isInterface() && definition.declaration.EXTENDS() != null) {
                String name = definition.declaration.valueType().getText();
                definition.parent = classes.get(Identifiers.key(name));
                if (definition.parent == null) throw error(definition.at(), "Unknown base class: " + name);
                if (definition.parent.isInterface()) throw error(definition.at(),"Use पालन to implement an interface.");
            }
            TypeListContext list=definition.isInterface()?definition.contract.typeList():definition.declaration.typeList();
            if (list!=null) for (var entry:list.valueType()) {
                Type contract=type(entry);
                if (contract.object==null || !contract.object.isInterface()) throw error(entry,"Expected an interface type.");
                if (definition.contracts.contains(contract.object)) throw error(entry,"Duplicate interface.");
                definition.contracts.add(contract.object);
            }
        }
        for (ClassInfo definition : classes.values()) orderClass(definition);
        for (ClassInfo definition : classOrder) {
            if (definition.isInterface()) {
                for (var declaration:definition.contract.functionDecl()) {
                    if (declaration.block()!=null) throw error(declaration,"Interface methods have signatures only; end with ';'.");
                    String name=Identifiers.key(declaration.IDENTIFIER().getText());
                    var signature=parameters(declaration.parameters());
                    var result=type(declaration.returnType());
                    for (var inherited:methods(definition,name))
                        if (inherited.parameters.equals(signature) && inherited.result!=result) throw error(declaration,"Interface method return types conflict.");
                    addFunction(definition.methods,name,new Function(slot(name,signature),result,signature,definition,Access.PUBLIC,false,declaration,false),declaration);
                }
                // Also check incompatible signatures arriving from two parent interfaces.
                for (String name:methodNames(definition)) methods(definition,name);
                continue;
            }
            for (var member : definition.declaration.classMember()) {
                Access access = access(member.visibility());
                boolean shared=member.STATIC()!=null;
                if (member.fieldDecl() != null) {
                    var declaration = member.fieldDecl();
                    String name = Identifiers.key(declaration.IDENTIFIER().getText());
                    if (definition.fields.containsKey(name) || definition.methods.containsKey(name)
                            || field(definition.parent, name) != null || !methods(definition.parent, name).isEmpty())
                        throw error(member, "Duplicate or hidden member: " + declaration.IDENTIFIER().getText());
                    if (declaration.CONST() != null && declaration.expression() == null)
                        throw error(declaration, "An immutable field requires an initializer.");
                    definition.fields.put(name, new Field("p" + fieldId++, type(declaration.valueType()),
                            declaration.LET() != null, access, definition, declaration,shared));
                } else if (member.constructorDecl() != null) {
                    if (shared) throw error(member,"Static constructors are not supported.");
                    var declaration = member.constructorDecl();
                    var signature=parameters(declaration.parameters());
                    if (definition.constructors.stream().anyMatch(c->c.parameters.equals(signature))) throw error(member,"Duplicate constructor signature.");
                    definition.constructors.add(new Constructor(signature, access, declaration));
                } else {
                    var declaration = member.functionDecl();
                    if (declaration.block()==null) throw error(declaration,"A class method requires a body.");
                    String name = Identifiers.key(declaration.IDENTIFIER().getText());
                    if (definition.fields.containsKey(name) || field(definition.parent, name) != null)
                        throw error(member, "Duplicate or hidden member: " + declaration.IDENTIFIER().getText());
                    var signature = parameters(declaration.parameters());
                    Function parent = methods(definition.parent,name).stream().filter(f->f.parameters.equals(signature)).findFirst().orElse(null);
                    boolean overriding = member.OVERRIDE() != null;
                    if (overriding && (shared || parent!=null && parent.shared)) throw error(member,"Static methods cannot override or be overridden.");
                    if (overriding && parent == null) {
                        if (!methods(definition.parent,name).isEmpty()) throw error(member,"Override must preserve parameter and return types exactly.");
                        throw error(member,"Override requires an inherited non-private method.");
                    }
                    if (overriding && parent.access == Access.PRIVATE) throw error(member,"Override requires an inherited non-private method.");
                    if (parent != null && !overriding) throw error(member, "Inherited method requires अधिलेखन; static hiding is not supported.");
                    Type result = type(declaration.returnType());
                    if (parent != null && parent.result != result) throw error(member, "Override must preserve parameter and return types exactly.");
                    if (parent != null && access.ordinal() < parent.access.ordinal()) throw error(member, "Override cannot narrow access.");
                    addFunction(definition.methods,name,new Function(slot(name,signature),result,signature,definition,access,overriding,declaration,shared),member);
                }
            }
            if (definition.constructors.isEmpty()) definition.constructors.add(new Constructor(List.of(), Access.PUBLIC, null));
        }
        for (ClassInfo definition:classOrder) if (!definition.isInterface()) validateContracts(definition);
    }
    private Set<String> methodNames(ClassInfo definition) {
        var names=new LinkedHashSet<String>();
        if (definition==null) return names;
        names.addAll(definition.methods.keySet());
        names.addAll(methodNames(definition.parent));
        for (var contract:definition.contracts) names.addAll(methodNames(contract));
        return names;
    }
    private void validateContracts(ClassInfo definition) {
        for (var contract:definition.contracts) for (String name:methodNames(contract)) for (Function required:methods(contract,name)) {
            Function actual=methods(definition,name).stream().filter(f->!f.owner.isInterface() && f.parameters.equals(required.parameters)).findFirst().orElse(null);
            if (actual==null || actual.shared || actual.access!=Access.PUBLIC || actual.result!=required.result)
                throw error(definition.at(),"Class must implement public interface method with exact types: " + name);
        }
    }
    private void orderClass(ClassInfo definition) {
        if (definition.state == 1) throw error(definition.at(), "Inheritance cycle detected.");
        if (definition.state == 2) return;
        definition.state = 1;
        if (definition.parent != null) orderClass(definition.parent);
        for (ClassInfo contract:definition.contracts) orderClass(contract);
        definition.state = 2;
        classOrder.add(definition);
    }
    private Field field(ClassInfo definition, String name) {
        for (; definition != null; definition = definition.parent) {
            var field = definition.fields.get(Identifiers.key(name));
            if (field != null) return field;
        }
        return null;
    }
    private List<Function> methods(ClassInfo definition, String name) {
        if (definition==null) return List.of();
        var found=new ArrayList<Function>(definition.methods.getOrDefault(Identifiers.key(name),List.of()));
        var inherited=new ArrayList<Function>(methods(definition.parent,name));
        // Classes obtain interface contracts through validation, never callable placeholder methods.
        if (definition.isInterface()) for (ClassInfo contract:definition.contracts) inherited.addAll(methods(contract,name));
        for (Function f:inherited) {
            Function existing=found.stream().filter(m->m.parameters.equals(f.parameters)).findFirst().orElse(null);
            if (existing==null) found.add(f);
            else if (definition.isInterface() && existing.result!=f.result) throw error(definition.at(),"Interface method return types conflict.");
        }
        return found;
    }
    private boolean subclass(ClassInfo child, ClassInfo parent) {
        if (child==null || parent==null) return false;
        if (child==parent || subclass(child.parent,parent)) return true;
        for (var contract:child.contracts) if (subclass(contract,parent)) return true;
        return false;
    }
    private Function select(ParserRuleContext at,List<Function> candidates,List<Expr> args,String name) {
        if (candidates.isEmpty()) throw error(at,"Undefined method or function: " + name);
        var applicable=candidates.stream().filter(f->applicable(f.parameters,args)).toList();
        if (applicable.isEmpty()) {
            if (candidates.stream().noneMatch(f->f.parameters.size()==args.size())) throw error(at,"Wrong argument count for " + name + ".");
            throw error(at,"Expected matching argument types for " + name + ".");
        }
        var best=applicable.stream().filter(f->applicable.stream().allMatch(g->moreSpecific(f.parameters,g.parameters))).toList();
        if (best.size()!=1) throw error(at,"Ambiguous overload: " + name);
        return best.get(0);
    }
    private boolean applicable(List<Type> parameters,List<Expr> args) {
        if (parameters.size()!=args.size()) return false;
        for (int i=0;i<args.size();i++) if (!compatible(parameters.get(i),args.get(i).type)) return false;
        return true;
    }
    private boolean moreSpecific(List<Type> left,List<Type> right) {
        for (int i=0;i<left.size();i++) if (!compatible(right.get(i),left.get(i))) return false;
        return true;
    }
    private Constructor selectConstructor(ParserRuleContext at,ClassInfo owner,List<Expr> args) {
        var candidates=owner.constructors.stream().filter(c->applicable(c.parameters,args)).toList();
        if (candidates.isEmpty()) {
            if (owner.constructors.stream().noneMatch(c->c.parameters.size()==args.size())) throw error(at,"Wrong argument count for constructor.");
            throw error(at,"Expected matching constructor argument types.");
        }
        var best=candidates.stream().filter(c->candidates.stream().allMatch(d->moreSpecific(c.parameters,d.parameters))).toList();
        if (best.size()!=1) throw error(at,"Ambiguous constructor overload.");
        return best.get(0);
    }
    private String constructorArguments(List<Expr> args,List<Type> signature) {
        var parts=new ArrayList<String>();
        for(int i=0;i<args.size();i++) parts.add("(("+typeName(signature.get(i))+")("+args.get(i).java+"))");
        return String.join(",",parts);
    }

    private boolean compatible(Type expected, Type actual) {
        return expected == actual || (expected.object != null && (actual == Type.NULL || subclass(actual.object, expected.object)));
    }

    private void checkAccess(ParserRuleContext at, ClassInfo owner, Access access, Expr receiver) {
        if (access == Access.PUBLIC || currentClass == owner) return;
        if (access == Access.PROTECTED && currentClass != null && subclass(currentClass, owner)
                && (receiver.typeReceiver || receiver.base || subclass(receiver.type.object, currentClass))) return;
        throw error(at, "Member is not accessible (" + accessName(access) + ").");
    }

    private void emitParameters(ParametersContext parameters, List<Type> types) {
        if (parameters == null) return;
        int index = 0;
        for (var parameter : parameters.parameter()) {
            if (index > 0) output.append(',');
            var binding = declare(parameter, parameter.IDENTIFIER().getText(), types.get(index++), false);
            output.append(target == Target.JVM ? "final " : "").append(typeName(binding.type)).append(' ').append(binding.java);
        }
    }

    private void emitClass(ClassInfo definition) {
        currentClass = definition;
        if (definition.isInterface()) {
            output.append(target==Target.JVM?"static interface ":"public interface ").append(definition.java);
            if (!definition.contracts.isEmpty()) output.append(target==Target.JVM?" extends ":" : ")
                    .append(String.join(",",definition.contracts.stream().map(c->c.java).toList()));
            output.append(" {\n");
            for (var overloads:definition.methods.values()) for (Function method:overloads) {
                output.append(typeName(method.result)).append(' ').append(method.java).append('(');
                for (int i=0;i<method.parameters.size();i++) {
                    if(i>0) output.append(',');
                    output.append(typeName(method.parameters.get(i))).append(" a").append(i);
                }
                output.append(");\n");
            }
            output.append("}\n"); currentClass=null; return;
        }
        output.append(target == Target.JVM ? "static class " : "public class ").append(definition.java);
        if (definition.parent != null) output.append(target == Target.JVM ? " extends " : " : ").append(definition.parent.java);
        if (!definition.contracts.isEmpty()) output.append(target==Target.JVM?" implements ":definition.parent==null?" : ":",")
                .append(String.join(",",definition.contracts.stream().map(c->c.java).toList()));
        output.append(" {\n");
        Map<Field, String> initializers = new LinkedHashMap<>();
        for (Field field : definition.fields.values()) {
            currentClass = definition; currentStatic=field.shared;
            Expr value;
            if (field.declaration.expression() == null) value = new Expr(field.type,
                    field.type == Type.TEXT ? "\"\"" : field.type == Type.BOOL ? "false" : field.type.object != null ? "null" : field.type == Type.REAL ? "0.0" : "0L");
            else {
                fieldInitializer = true;
                value = visit(field.declaration.expression());
                fieldInitializer = false;
                require(field.declaration, field.type, value.type);
            }
            output.append(accessName(field.access)).append(' ');
            if (field.shared) output.append("static ");
            if (!field.mutable) output.append(target == Target.JVM ? "final " : "readonly ");
            output.append(typeName(field.type)).append(' ').append(field.java);
            if (field.shared) output.append('=').append(value.java);
            output.append(";\n");
            if (!field.shared) initializers.put(field, value.java);
        }
        if (target==Target.CLR && definition.fields.values().stream().anyMatch(f->f.shared))
            output.append("static ").append(definition.java).append("() {}\n");
        currentStatic=false;
        for (Constructor constructor:definition.constructors) emitConstructor(definition,constructor,initializers);
        for (var overloads:definition.methods.values()) for (Function method:overloads) emitFunction(method);
        output.append("}\n");
        currentClass = null;
    }

    private void emitConstructor(ClassInfo definition, Constructor constructor, Map<Field, String> initializers) {
        currentClass = definition;
        returnType = Type.VOID;
        var declaration = constructor.declaration;
        ParserRuleContext at = declaration == null ? definition.declaration : declaration;
        scopes.push(new HashMap<>());
        output.append(accessName(constructor.access)).append(' ').append(definition.java).append('(');
        emitParameters(declaration == null ? null : declaration.parameters(), constructor.parameters);
        output.append(')');
        baseArguments = true;
        List<Expr> arguments = declaration == null ? List.of() : arguments(declaration.arguments());
        baseArguments = false;
        if (definition.parent == null) {
            if (declaration != null && declaration.BASE() != null) throw error(at, "A base initializer requires a parent class.");
        } else {
            Constructor parent = selectConstructor(at,definition.parent,arguments);
            if (parent.access == Access.PRIVATE) throw error(at, "Base constructor is not accessible (private).");
            checkArguments(at, arguments, parent.parameters, "base constructor");
            if (target == Target.CLR) output.append(" : base(").append(constructorArguments(arguments,parent.parameters)).append(')');
        }
        output.append(" {\n");
        if (definition.parent != null && target == Target.JVM) output.append("super(").append(constructorArguments(arguments,selectConstructor(at,definition.parent,arguments).parameters)).append(");\n");
        for (var initializer : initializers.entrySet()) output.append("this.").append(initializer.getKey().java)
                .append('=').append(initializer.getValue()).append(";\n");
        constructorBody = true;
        if (declaration != null) statements(declaration.block());
        constructorBody = false;
        output.append("}\n");
        scopes.pop();
    }

    private void checkArguments(ParserRuleContext at, List<Expr> arguments, List<Type> parameters, String name) {
        if (arguments.size() != parameters.size()) throw error(at, "Wrong argument count for " + name + ".");
        for (int i = 0; i < arguments.size(); i++) require(at, parameters.get(i), arguments.get(i).type);
    }

    private Binding declare(ParserRuleContext at, String spelling, Type type, boolean mutable) {
        var binding = new Binding("v" + variableId++, type, mutable);
        if (scopes.peek().putIfAbsent(Identifiers.key(spelling), binding) != null)
            throw error(at, "Duplicate binding: " + spelling);
        return binding;
    }

    private Binding find(String name) {
        for (var scope : scopes) {
            var binding = scope.get(Identifiers.key(name));
            if (binding != null) return binding;
        }
        return null;
    }

    private EnumSet<Flow> statements(BlockContext block) { return statements(block.statement()); }

    private EnumSet<Flow> statements(List<StatementContext> statements) {
        var result = EnumSet.of(Flow.NORMAL);
        for (var statement : statements) {
            if (!result.remove(Flow.NORMAL)) throw error(statement, "Unreachable statement.");
            result.addAll(statement(statement));
        }
        return result;
    }

    private EnumSet<Flow> block(BlockContext block) {
        scopes.push(new HashMap<>());
        output.append("{\n");
        var result = statements(block);
        output.append("}\n");
        scopes.pop();
        return result;
    }

    private EnumSet<Flow> statement(StatementContext statement) {
        if (statement.variableDecl() != null) {
            var declaration = statement.variableDecl();
            var value = visit(declaration.expression());
            var declaredType = type(declaration.valueType());
            require(declaration, declaredType, value.type);
            var binding = declare(declaration, declaration.IDENTIFIER().getText(), declaredType, declaration.LET() != null);
            output.append(binding.mutable || target == Target.CLR ? "" : "final ").append(typeName(declaredType)).append(' ')
                    .append(binding.java).append('=').append(value.java).append(";\n");
        } else if (statement.assignment() != null) {
            var assignment = statement.assignment();
            var destination = visit(assignment.postfix());
            if (destination.location == null) throw error(assignment, "Assignment requires a variable or field.");
            if (!destination.location.mutable) throw error(assignment, "Cannot assign to an immutable binding or field.");
            var value = visit(assignment.expression());
            require(assignment, destination.type, value.type);
            output.append(destination.java).append('=').append(value.java).append(";\n");
        } else if (statement.ifStatement() != null) {
            return ifStatement(statement.ifStatement());
        } else if (statement.switchStatement() != null) {
            return switchStatement(statement.switchStatement());
        } else if (statement.whileStatement() != null) {
            var loop = statement.whileStatement();
            var condition = visit(loop.expression());
            require(loop, Type.BOOL, condition.type);
            output.append("while (BhashRuntime.truth(").append(condition.java).append(")) ");
            loopDepth++;
            breakTargets.push(new BreakTarget(null));
            var body = block(loop.block());
            breakTargets.pop();
            loopDepth--;
            var flow = EnumSet.of(Flow.NORMAL); // Conservative: loops may execute zero times.
            if (body.contains(Flow.RETURN)) flow.add(Flow.RETURN);
            return flow;
        } else if (statement.RETURN() != null) {
            var value = statement.expression() == null ? null : visit(statement.expression());
            if (returnType == Type.VOID && value != null) throw error(statement, "Void function cannot return a value.");
            if (returnType != Type.VOID && value == null) throw error(statement, "Return value required.");
            if (value != null) require(statement, returnType, value.type);
            output.append("return").append(value == null ? "" : " " + value.java).append(";\n");
            return EnumSet.of(Flow.RETURN);
        } else if (statement.BREAK() != null || statement.CONTINUE() != null) {
            boolean isBreak = statement.BREAK() != null;
            if (isBreak) {
                if (breakTargets.isEmpty()) throw error(statement,"Loop control विराम is only valid inside a loop or चयन.");
                var destination=breakTargets.peek();
                destination.used=true;
                if (destination.label==null) output.append("break;\n");
                else output.append(target==Target.JVM?"break ":"goto ").append(destination.label).append(";\n");
            } else {
                if (loopDepth==0) throw error(statement,"Loop control जारी is only valid inside a loop.");
                output.append("continue;\n");
            }
            return EnumSet.of(isBreak ? Flow.BREAK : Flow.CONTINUE);
        } else if (statement.PRINT() != null) {
            var args = arguments(statement.arguments());
            for (var arg : args) {
                valueRequired(statement, arg.type);
                if (arg.type.reference()) throw error(statement, "Print an object's primitive fields or method results, not the object reference.");
            }
            output.append("BhashRuntime.print(").append(join(args)).append(");\n");
        } else if (statement.block() != null) {
            return block(statement.block());
        } else {
            var value = visit(statement.expression());
            if (!value.call) throw error(statement, "Only a function call may be used as an expression statement.");
            output.append(value.type == Type.VOID ? value.java : "BhashRuntime.discard(" + value.java + ")").append(";\n");
        }
        return EnumSet.of(Flow.NORMAL);
    }

    private EnumSet<Flow> switchStatement(SwitchStatementContext statement) {
        Expr selector=visit(statement.expression());
        if (selector.type!=Type.INT && selector.type!=Type.TEXT && selector.type!=Type.BOOL)
            throw error(statement,"चयन requires पूर्णांक, पाठ or तार्किक.");
        var labels=new IdentityHashMap<SwitchSectionContext,List<Expr>>();
        var seen=new HashSet<String>();
        SwitchSectionContext fallback=null;
        for (var section:statement.switchSection()) {
            if (section.DEFAULT()!=null) {
                if (fallback!=null) throw error(section,"Duplicate शेष section.");
                fallback=section;
            } else {
                var values=new ArrayList<Expr>();
                for (var label:section.caseValue()) {
                    Expr value;
                    String key;
                    if (label.INTEGER_LITERAL()!=null) {
                        value=integer(label,digits(label.getText()));
                        key=value.java;
                    } else if (label.STRING_LITERAL()!=null) {
                        String decoded=decodeString(label,label.getText());
                        value=textLiteral(label,decoded);
                        key=decoded;
                    } else {
                        key=label.TRUE()!=null?"true":"false";
                        value=new Expr(Type.BOOL,key);
                    }
                    require(label,selector.type,value.type);
                    if (!seen.add(key)) throw error(label,"Duplicate विकल्प value.");
                    values.add(value);
                }
                labels.put(section,values);
            }
        }
        String id="s"+switchId++;
        var destination=new BreakTarget("end_"+id);
        if (target==Target.JVM) output.append(destination.label).append(": ");
        output.append("{\n").append(target==Target.JVM?"final ":"").append(typeName(selector.type)).append(' ')
                .append(id).append('=').append(selector.java).append(";\n");
        breakTargets.push(destination);
        var flow=EnumSet.noneOf(Flow.class);
        boolean first=true;
        for (var section:statement.switchSection()) {
            if (section.DEFAULT()!=null) continue;
            if (!first) output.append("else ");
            first=false;
            output.append("if (");
            var conditions=new ArrayList<String>();
            for (Expr label:labels.get(section)) {
                conditions.add(selector.type==Type.TEXT
                        ? (target==Target.JVM?"java.util.Objects.equals(":"string.Equals(")+id+","+label.java
                            +(target==Target.JVM?")":",StringComparison.Ordinal)")
                        : "("+id+" == "+label.java+")");
            }
            output.append(String.join(" || ",conditions)).append(") ");
            flow.addAll(switchBody(section));
        }
        if (fallback!=null) {
            if (!first) output.append("else ");
            flow.addAll(switchBody(fallback));
        } else flow.add(Flow.NORMAL);
        breakTargets.pop();
        output.append("}\n");
        if (target==Target.CLR && destination.used) output.append(destination.label).append(": ;\n");
        if (flow.remove(Flow.BREAK)) flow.add(Flow.NORMAL);
        return flow;
    }

    private EnumSet<Flow> switchBody(SwitchSectionContext section) {
        scopes.push(new HashMap<>());
        output.append("{\n");
        var flow=statements(section.statement());
        output.append("}\n");
        scopes.pop();
        return flow;
    }

    private EnumSet<Flow> ifStatement(IfStatementContext statement) {
        var condition = visit(statement.expression());
        require(statement, Type.BOOL, condition.type);
        output.append("if (").append(condition.java).append(") ");
        var flow = block(statement.block(0));
        if (statement.ELSE() == null) flow.add(Flow.NORMAL);
        else {
            output.append("else ");
            flow.addAll(statement.ifStatement() != null ? ifStatement(statement.ifStatement()) : block(statement.block(1)));
        }
        return flow;
    }

    private List<Expr> arguments(ArgumentsContext args) {
        return args == null ? List.of() : args.expression().stream().map(this::visit).toList();
    }
    private String join(List<Expr> args) { return String.join(",", args.stream().map(Expr::java).toList()); }
    private void require(ParserRuleContext at, Type expected, Type actual) {
        if (!compatible(expected, actual)) throw error(at, "Expected " + expected.bhash + ", found " + actual.bhash + ".");
    }
    private void valueRequired(ParserRuleContext at, Type type) {
        if (type == Type.VOID) throw error(at, "A void function does not produce a value.");
    }
    private void numeric(ParserRuleContext at, Type type) {
        if (type != Type.INT && type != Type.REAL) throw error(at, "Numeric operand required.");
    }

    @Override public Expr visitExpression(ExpressionContext ctx) { return visit(ctx.logicalOr()); }
    @Override public Expr visitLogicalOr(LogicalOrContext ctx) { return binary(ctx); }
    @Override public Expr visitLogicalAnd(LogicalAndContext ctx) { return binary(ctx); }
    @Override public Expr visitEquality(EqualityContext ctx) { return binary(ctx); }
    @Override public Expr visitComparison(ComparisonContext ctx) { return binary(ctx); }
    @Override public Expr visitAdditive(AdditiveContext ctx) { return binary(ctx); }
    @Override public Expr visitMultiplicative(MultiplicativeContext ctx) { return binary(ctx); }

    private Expr binary(ParserRuleContext ctx) {
        var result = visit(ctx.getChild(0));
        for (int i = 1; i < ctx.getChildCount(); i += 2) {
            String operator = ctx.getChild(i).getText();
            var right = visit(ctx.getChild(i + 1));
            valueRequired(ctx, result.type);
            if ((operator.equals("==") || operator.equals("!=")) && result.type.reference() && right.type.reference()) {
                if (result.type != Type.NULL && right.type != Type.NULL
                        && !compatible(result.type, right.type) && !compatible(right.type, result.type))
                    throw error(ctx, "Cannot compare unrelated object types.");
                result = new Expr(Type.BOOL, (operator.equals("!=") ? "!" : "") + "BhashRuntime.same(" + result.java + "," + right.java + ")");
                continue;
            }
            require(ctx, result.type, right.type);
            Type type = result.type;
            String java;
            switch (operator) {
                case "&&", "||" -> { require(ctx, Type.BOOL, type); type = Type.BOOL; }
                case "==", "!=" -> { type = Type.BOOL; }
                case "<", "<=", ">", ">=" -> { numeric(ctx, type); type = Type.BOOL; }
                case "%" -> require(ctx, Type.INT, type);
                case "+" -> { if (type != Type.TEXT) numeric(ctx, type); }
                default -> numeric(ctx, type);
            }
            if ((operator.equals("==") || operator.equals("!=")) && result.type == Type.TEXT) {
                String equality = target == Target.JVM ? "java.util.Objects.equals(" : "string.Equals(";
                java = (operator.equals("!=") ? "!" : "") + equality + result.java + "," + right.java
                        + (target == Target.JVM ? ")" : ",StringComparison.Ordinal)");
            } else if ((operator.equals("/") || operator.equals("%")) && result.type == Type.INT) {
                // A helper gives constant-zero division the same runtime behavior as dynamic division.
                java = "BhashRuntime." + (operator.equals("/") ? "divide" : "remainder") + "(" + result.java + "," + right.java + ")";
            } else java = "(" + result.java + " " + operator + " " + right.java + ")";
            if (target == Target.CLR && type == Type.INT && Set.of("+", "-", "*").contains(operator))
                java = "unchecked(" + java + ")";
            result = new Expr(type, java);
        }
        return result;
    }

    @Override public Expr visitUnary(UnaryContext ctx) {
        if (ctx.postfix() != null) return visit(ctx.postfix());
        String op = ctx.getChild(0).getText();
        // Accept the minimum signed literal, including parenthesized magnitude.
        String magnitude = ctx.unary().getText().replace("(", "").replace(")", "");
        if (op.equals("-") && magnitude.matches("[0-9०-९]+")) return integer(ctx, "-" + digits(magnitude));
        var value = visit(ctx.unary());
        if (op.equals("!")) require(ctx, Type.BOOL, value.type);
        else numeric(ctx, value.type);
        String code = "(" + op + "(" + value.java + "))";
        if (target == Target.CLR && value.type == Type.INT) code = "unchecked(" + code + ")";
        return new Expr(value.type, code);
    }

    private Expr self(ParserRuleContext at, boolean base) {
        if (currentClass == null || currentStatic || fieldInitializer || baseArguments) throw error(at, "Instance access is unavailable in this context.");
        if (base && currentClass.parent == null) throw error(at, "आधार requires a parent class.");
        return new Expr(base ? currentClass.parent.type : currentClass.type,
                base ? (target == Target.JVM ? "super" : "base") : "this", false, null, base);
    }

    private String receiver(Expr object) {
        return object.typeReceiver || object.base ? object.java : "BhashRuntime.instance(" + object.java + ")";
    }

    private String memberReceiver(Expr object, ClassInfo owner, Access access) {
        String code = receiver(object);
        // Private members are not inherited by the host's subtype; resolve them in their declaring class.
        return access == Access.PRIVATE && object.type.object != owner ? "((" + owner.java + ")" + code + ")" : code;
    }

    private Expr readField(ParserRuleContext at, Expr object, String name) {
        Field field = field(object.type.object, name);
        if (field == null) throw error(at, "Undefined field: " + name);
        if (field.shared!=object.typeReceiver) throw error(at,field.shared?"Static member requires a type receiver.":"Instance member requires an instance receiver.");
        checkAccess(at, field.owner, field.access, object);
        String receiver=field.shared?field.owner.java:memberReceiver(object, field.owner, field.access);
        return new Expr(field.type, receiver + "." + field.java, new Location(field.mutable));
    }
    private Expr callMethod(ParserRuleContext at, Expr object, String name, List<Expr> arguments) {
        var candidates=methods(object.type.object,name);
        if (candidates.isEmpty()) throw error(at,"Undefined method: " + name);
        var matching=candidates.stream().filter(f->f.shared==object.typeReceiver).toList();
        if (matching.isEmpty()) throw error(at,object.typeReceiver?"Instance member requires an instance receiver.":"Static member requires a type receiver.");
        Function method=select(at,matching,arguments,name);
        if (!method.shared && constructorBody && (object.base || object.java.equals("this")))
            throw error(at, "Constructors cannot call instance methods on यह or आधार before construction completes.");
        checkAccess(at, method.owner, method.access, object);
        String receiver=method.shared?method.owner.java:memberReceiver(object, method.owner, method.access);
        return new Expr(method.result, receiver + "." + method.java + "(" + join(arguments) + ")", true);
    }
    private Expr classReceiver(ClassInfo definition) { return new Expr(definition.type,definition.java,false,null,false,true); }
    @Override public Expr visitPostfix(PostfixContext ctx) {
        Expr value = visit(ctx.primary());
        if (ctx.postfixPart().isEmpty() && value.typeReceiver) throw error(ctx,"A type name is only valid as a static member receiver.");
        if (ctx.postfixPart().isEmpty() && value.base) throw error(ctx, "आधार is only valid as a member receiver.");
        if (ctx.postfixPart().isEmpty() && constructorBody && value.java.equals("this"))
            throw error(ctx, "Constructors may use यह only as a direct field receiver; the new object cannot escape early.");
        for (var part : ctx.postfixPart()) {
            if (value.type.object == null) throw error(part, "Member access requires a class instance.");
            value = part.LPAREN() == null ? readField(part, value, part.IDENTIFIER().getText())
                    : callMethod(part, value, part.IDENTIFIER().getText(), arguments(part.arguments()));
        }
        return value;
    }

    @Override public Expr visitPrimary(PrimaryContext ctx) {
        if (ctx.INTEGER_LITERAL() != null) return integer(ctx, digits(ctx.getText()));
        if (ctx.REAL_LITERAL() != null) {
            double value = Double.parseDouble(digits(ctx.getText()));
            if (!Double.isFinite(value)) throw error(ctx, "Real literal is outside the finite binary64 range.");
            return new Expr(Type.REAL, Double.toString(value));
        }
        if (ctx.STRING_LITERAL() != null) return string(ctx);
        if (ctx.TRUE() != null || ctx.FALSE() != null) return new Expr(Type.BOOL, ctx.TRUE() != null ? "true" : "false");
        if (ctx.NULL() != null) return new Expr(Type.NULL, "null");
        if (ctx.THIS() != null) return self(ctx, false);
        if (ctx.BASE() != null) return self(ctx, true);
        if (ctx.NEW() != null) {
            if (fieldInitializer) throw error(ctx, "Field initializers must be constant expressions, without calls or new objects.");
            var definition = classes.get(Identifiers.key(ctx.valueType().getText()));
            if (definition == null) throw error(ctx, "Unknown class: " + ctx.valueType().getText());
            if (definition.isInterface()) throw error(ctx,"Cannot construct an interface.");
            var arguments = arguments(ctx.arguments());
            var constructor=selectConstructor(ctx,definition,arguments);
            if (constructor.access != Access.PUBLIC && currentClass != definition)
                throw error(ctx, "Constructor is not accessible (" + accessName(constructor.access) + ").");
            return new Expr(definition.type, "new " + definition.java + "(" + constructorArguments(arguments,constructor.parameters) + ")", true);
        }
        if (ctx.IDENTIFIER() != null) {
            String name = ctx.IDENTIFIER().getText();
            if (fieldInitializer) throw error(ctx, "Field initializers must be constant expressions, without names or calls.");
            if (ctx.LPAREN() == null) {
                var local = find(name);
                if (local != null) return new Expr(local.type, local.java, new Location(local.mutable));
                var member=field(currentClass,name);
                if (member!=null) return readField(ctx,member.shared?classReceiver(currentClass):self(ctx,false),name);
                var definition=classes.get(Identifiers.key(name));
                if (definition!=null) return classReceiver(definition);
                throw error(ctx, "Undefined variable: " + name);
            }
            if (find(name) != null) throw error(ctx, "Binding is not callable: " + name);
            if (!methods(currentClass,name).isEmpty()) {
                var args=arguments(ctx.arguments());
                var candidates=methods(currentClass,name);
                if (currentStatic) candidates=candidates.stream().filter(f->f.shared).toList();
                if (candidates.isEmpty()) throw error(ctx,"Instance access is unavailable in a static context.");
                var chosen=select(ctx,candidates,args,name);
                return callMethod(ctx,chosen.shared?classReceiver(currentClass):self(ctx,false),name,args);
            }
            if (field(currentClass, name) != null) throw error(ctx, "Binding is not callable: " + name);
            var function = functions.get(Identifiers.key(name));
            if (function == null) throw error(ctx, "Undefined function: " + name);
            var args = arguments(ctx.arguments());
            var chosen=select(ctx,function,args,name);
            return new Expr(chosen.result, chosen.java + "(" + join(args) + ")", true);
        }
        var value = visit(ctx.expression());
        return new Expr(value.type, value.type == Type.VOID ? value.java : "(" + value.java + ")", value.call);
    }

    private Expr integer(ParserRuleContext at, String decimal) {
        var value = new BigInteger(decimal);
        if (value.compareTo(MIN) < 0 || value.compareTo(MAX) > 0) throw error(at, "Integer literal is outside signed 64-bit range.");
        return new Expr(Type.INT, value + "L");
    }

    private static String digits(String input) {
        var result = new StringBuilder();
        for (char c : input.toCharArray()) result.append(c >= '०' && c <= '९' ? (char) ('0' + c - '०') : c);
        return result.toString();
    }

    private Expr string(PrimaryContext ctx) { return textLiteral(ctx,decodeString(ctx,ctx.getText())); }

    private String decodeString(ParserRuleContext ctx,String raw) {
        var decoded = new StringBuilder();
        for (int i = 1; i < raw.length() - 1; i++) {
            char c = raw.charAt(i);
            if (c != '\\') { decoded.append(c); continue; }
            c = raw.charAt(++i);
            switch (c) {
                case 'n' -> decoded.append('\n');
                case 'r' -> decoded.append('\r');
                case 't' -> decoded.append('\t');
                case '"', '\\' -> decoded.append(c);
                case 'u' -> { decoded.append((char) Integer.parseInt(raw.substring(i + 1, i + 5), 16)); i += 4; }
                default -> throw error(ctx, "Invalid string escape.");
            }
        }
        return decoded.toString();
    }

    private Expr textLiteral(ParserRuleContext ctx,String value) {
        var codepoints = new StringJoiner(",");
        int count = 0;
        for (int cp : value.codePoints().toArray()) {
            if (cp >= 0xD800 && cp <= 0xDFFF) throw error(ctx, "Unpaired surrogate in text literal.");
            codepoints.add(Integer.toString(cp));
            count++;
        }
        return new Expr(Type.TEXT, target == Target.JVM
                ? "new String(new int[]{" + codepoints + "},0," + count + ")"
                : "BhashRuntime.text(new int[]{" + codepoints + "})");
    }
}
