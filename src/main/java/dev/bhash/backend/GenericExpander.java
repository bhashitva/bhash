// SPDX-License-Identifier: Apache-2.0
package dev.bhash.backend;

import dev.bhash.frontend.Frontend;
import dev.bhash.frontend.Identifiers;
import dev.bhash.frontend.LocatedToken;
import dev.bhash.parser.BhashParser.*;
import java.util.*;
import org.antlr.v4.runtime.*;
import org.antlr.v4.runtime.tree.*;

/** Specializes generic classes, interfaces and global functions before ordinary type checking. */
final class GenericExpander {
    private record Template(ParserRuleContext declaration, TypeParametersContext parameters, Token name) {}
    private record Instance(List<Template> templates, List<String> arguments, String name) {}
    private final Map<String,List<Template>> types = new LinkedHashMap<>(), functions = new LinkedHashMap<>();
    private final Map<String,Instance> instances = new LinkedHashMap<>();
    private final Set<String> names = new HashSet<>(), concreteTypes = new HashSet<>();
    private final List<Instance> pending = new ArrayList<>();
    private final String source;
    private final StringBuilder text = new StringBuilder();
    private final NavigableMap<Integer,Token> origins = new TreeMap<>();
    private int next, renderedCodePoints;
    private GenericExpander(String source) { this.source = source; }

    static ProgramContext expand(String source, ProgramContext program) throws CompilationException {
        return new GenericExpander(source).program(program);
    }
    private CompilationException error(ParserRuleContext at, String message) {
        return new CompilationException(LocatedToken.source(at.start, source) + ":" + at.start.getLine() + ":" + (at.start.getCharPositionInLine()+1) + ": " + message);
    }
    private void collectNames(ParseTree tree) {
        if (tree instanceof TerminalNode terminal) names.add(Identifiers.key(terminal.getText()));
        else for (int i=0;i<tree.getChildCount();i++) collectNames(tree.getChild(i));
    }
    private ProgramContext program(ProgramContext program) throws CompilationException {
        collectNames(program);
        concreteTypes.addAll(List.of("पूर्णांक", "दशमलव", "तार्किक", "पाठ", "अपवाद", "बाइट", "लघुपूर्णांक", "सर्ववस्तु", "बाइटबफ़र"));
        for (var e : program.enumDecl()) concreteTypes.add(Identifiers.key(e.IDENTIFIER().getText()));
        for (var c : program.classDecl()) register(types,c,c.typeParameters(),c.IDENTIFIER().getSymbol());
        for (var c : program.interfaceDecl()) register(types,c,c.typeParameters(),c.IDENTIFIER().getSymbol());
        for (var f : program.functionDecl()) register(functions,f,f.typeParameters(),f.IDENTIFIER().getSymbol());
        for (var c : program.classDecl()) for (var m : c.classMember())
            if (m.functionDecl()!=null && m.functionDecl().typeParameters()!=null)
                throw error(m,"Generic member methods are not supported yet; use a generic class or global function.");
        for (var c : program.interfaceDecl()) for (var f : c.functionDecl())
            if (f.typeParameters()!=null) throw error(f,"Generic interface methods are not supported yet; use a generic interface.");
        for (var entry:types.entrySet()) if (concreteTypes.contains(entry.getKey()))
            throw error(entry.getValue().get(0).declaration,"Duplicate type declaration: " + entry.getKey());
        if (types.isEmpty() && functions.isEmpty()) return program;
        for (int i=0;i<program.getChildCount()-1;i++) {
            var node=program.getChild(i);
            if (node instanceof ClassDeclContext c && c.typeParameters()!=null
                    || node instanceof InterfaceDeclContext contract && contract.typeParameters()!=null
                    || node instanceof FunctionDeclContext f && f.typeParameters()!=null) continue;
            render(node,Map.of(),null,null);
        }
        for (int i=0;i<pending.size();i++) {
            Instance instance=pending.get(i);
            for (Template template : instance.templates) {
                var substitutions=new HashMap<String,String>();
                for (int j=0;j<instance.arguments.size();j++) substitutions.put(Identifiers.key(template.parameters.IDENTIFIER(j).getText()),instance.arguments.get(j));
                render(template.declaration,substitutions,template.name,instance.name);
            }
        }
        var parsed=Frontend.parse(source,text.toString());
        if (!parsed.valid()) throw new CompilationException("Internal generic specialization error: " + parsed.diagnostics());
        remap(parsed.tree());
        return parsed.tree();
    }
    private void register(Map<String,List<Template>> map, ParserRuleContext declaration, TypeParametersContext parameters, Token name) throws CompilationException {
        String key=Identifiers.key(name.getText());
        if (parameters==null) { if (map==types) concreteTypes.add(key); return; }
        if (map==functions && (key.equals(Identifiers.key("पढ़ो")) || key.equals(Identifiers.key("मुख्य"))))
            throw error(declaration,"Reserved function cannot be generic: " + key);
        var seen=new HashSet<String>();
        for (var id:parameters.IDENTIFIER()) if (!seen.add(Identifiers.key(id.getText()))) throw error(parameters,"Duplicate type parameter: " + id.getText());
        if (map==types && map.containsKey(key)) throw error(declaration,"Duplicate generic type: " + name.getText());
        map.computeIfAbsent(key,k->new ArrayList<>()).add(new Template(declaration,parameters,name));
    }
    private String instantiate(Map<String,List<Template>> map, String name, TypeArgumentsContext args, Map<String,String> subst) throws CompilationException {
        var templates=map.get(Identifiers.key(name));
        if (templates==null) throw error(args,"Type arguments require a generic " + (map==types?"type: ":"function: ") + name);
        var arguments=new ArrayList<String>();
        for (var type:args.valueType()) arguments.add(type(type,subst));
        templates=templates.stream().filter(t->t.parameters.IDENTIFIER().size()==arguments.size()).toList();
        if (templates.isEmpty()) throw error(args,"Wrong type argument count for " + name);
        String key=(map==types?"type:":"function:")+Identifiers.key(name)+arguments;
        Instance old=instances.get(key);
        if (old!=null) return old.name;
        if (pending.size()>=256) throw error(args,"Generic specialization limit exceeded (256); check expanding recursive types.");
        String generated;
        do { generated="विशेष_"+next++; } while (!names.add(generated));
        var instance=new Instance(templates,List.copyOf(arguments),generated);
        instances.put(key,instance); pending.add(instance); concreteTypes.add(generated);
        return generated;
    }
    private String type(ValueTypeContext type, Map<String,String> subst) throws CompilationException {
        String result;
        if (type.IDENTIFIER()==null) result=type.getChild(0).getText();
        else {
            String name=Identifiers.key(type.IDENTIFIER().getText());
            if (subst.containsKey(name)) {
                if (type.typeArguments()!=null) throw error(type,"A type parameter cannot take type arguments: "+name);
                result=subst.get(name);
            } else if (CollectionTypes.builtin(name)) {
                if (type.typeArguments()==null || type.typeArguments().valueType().size()!=CollectionTypes.ARITY.get(name))
                    throw error(type,"Wrong type argument count for " + name);
                var args=new ArrayList<String>();
                for (var argument:type.typeArguments().valueType()) args.add(type(argument,subst));
                result=name+"<"+String.join(",",args)+">";
            } else if (type.typeArguments()!=null) result=instantiate(types,name,type.typeArguments(),subst);
            else {
                if (types.containsKey(name)) throw error(type,"Generic type requires explicit type arguments: " + name);
                if (!concreteTypes.contains(name)) throw error(type,"Unknown type: " + name);
                result=name;
            }
        }
        return result+"[]".repeat(type.arraySuffix().size());
    }
    private void append(String value, Token origin) {
        origins.put(renderedCodePoints,origin);
        text.append(value).append(' ');renderedCodePoints+=value.codePointCount(0,value.length())+1;
    }
    private void render(ParseTree node, Map<String,String> subst, Token renamed, String replacement) throws CompilationException {
        if (node instanceof TypeParametersContext) return;
        if (node instanceof ShiftOperatorContext operator) { append(operator.getText(),operator.start); return; }
        if (node instanceof ValueTypeContext t) { append(type(t,subst),t.start); return; }
        if (node instanceof PrimaryContext p && p.typeArguments()!=null) {
            if (p.LPAREN()==null && CollectionTypes.builtin(p.IDENTIFIER().getText()))
                throw error(p,"Collection types have no static members; construct them with नया.");
            String name=instantiate(p.LPAREN()==null?types:functions,p.IDENTIFIER().getText(),p.typeArguments(),subst);
            append(name,p.start);
            for (int i=2;i<p.getChildCount();i++) render(p.getChild(i),subst,renamed,replacement);
            return;
        }
        if (node instanceof TerminalNode terminal) {
            Token token=terminal.getSymbol();
            append(token==renamed?replacement:token.getText(),token);
        } else for (int i=0;i<node.getChildCount();i++) render(node.getChild(i),subst,renamed,replacement);
    }
    private void remap(ParseTree node) {
        ProjectResolver.remap(node,origins);
    }
}
