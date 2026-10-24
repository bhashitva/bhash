// SPDX-License-Identifier: Apache-2.0
package dev.bhash.backend;

import dev.bhash.frontend.*;
import dev.bhash.parser.BhashParser;
import dev.bhash.parser.BhashParser.*;
import java.util.*;
import org.antlr.v4.runtime.*;
import org.antlr.v4.runtime.tree.*;

/** Resolves each file's lexical imports into a shared, whole-project symbol graph. */
public final class ProjectResolver {
    private enum Kind { TYPE, FUNCTION }
    private static final Set<String> BUILTINS = Set.of("पढ़ो", "पाठपढ़ो", "लंबाई", "अंश", "अंकपाठ", "पूर्णांकपाठ", "बड़ाअक्षर", "त्रुटि");
    private static final class Unit {
        final ProgramContext tree;
        final String pkg;
        final Map<String,String> imports = new HashMap<>();
        final Map<Integer,String> replacements = new HashMap<>();
        final Set<Integer> removed = new HashSet<>();
        Unit(ProgramContext tree) {
            this.tree = tree;
            pkg = tree.packageDecl() == null ? "" : key(tree.packageDecl().qualifiedName().getText());
        }
    }
    private record Members(Set<String> fields, Set<String> methods) {}
    private record Symbol(String full, String lowered, Kind kind, boolean exported, Unit unit, ParserRuleContext declaration) {}
    private final List<Unit> units = new ArrayList<>();
    private final Map<String,Symbol> types = new LinkedHashMap<>(), functions = new LinkedHashMap<>();
    private final Set<String> spellings = new HashSet<>();
    private int next;

    public static Resolution resolve(List<SourceFile> sources) throws CompilationException {
        var trees = new ArrayList<ProgramContext>();
        var errors = new ArrayList<String>();
        for (var source : sources) {
            var parsed = source.parse();
            if (parsed.valid()) trees.add(parsed.tree());
            else parsed.diagnostics().forEach(d -> errors.add(d.toString()));
        }
        if (!errors.isEmpty()) throw new CompilationException(String.join("\n", errors));
        return resolveTrees(trees);
    }
    public record Resolution(ProgramContext tree, Map<String,String> names) {
        String describe(String message) {
            var pattern = java.util.regex.Pattern.compile("संकुलित_[0-9]+");
            return pattern.matcher(message).replaceAll(m -> java.util.regex.Matcher.quoteReplacement(names.getOrDefault(m.group(), m.group())));
        }
    }
    static Resolution resolveTrees(List<ProgramContext> trees) throws CompilationException {
        var resolver = new ProjectResolver();
        var tree = resolver.resolveProgram(trees);
        var names = new HashMap<String,String>();
        for (var table : List.of(resolver.types,resolver.functions))
            for (var symbol : table.values()) names.put(symbol.lowered,symbol.full);
        return new Resolution(tree,Map.copyOf(names));
    }
    private static String key(String text) { return Identifiers.key(text); }
    private static String full(Unit unit, String name) { return unit.pkg.isEmpty() ? key(name) : unit.pkg + "." + key(name); }
    private static CompilationException error(Token token, String message) {
        return new CompilationException(LocatedToken.source(token, "project") + ":" + token.getLine() + ":"
                + (token.getCharPositionInLine()+1) + ": " + message);
    }
    private ProgramContext resolveProgram(List<ProgramContext> trees) throws CompilationException {
        if (trees.isEmpty()) throw new CompilationException("Project contains no .bhash source files.");
        for (var tree : trees) { units.add(new Unit(tree)); collectSpellings(tree); }
        for (var unit : units) {
            boolean exported = false;
            for (int i=0; i<unit.tree.getChildCount(); i++) {
                ParseTree child = unit.tree.getChild(i);
                if (child instanceof TerminalNode t && t.getSymbol().getType() == BhashParser.PUBLIC) { exported = true; continue; }
                if (child instanceof ClassDeclContext c) register(unit, c, c.IDENTIFIER().getSymbol(), Kind.TYPE, exported);
                else if (child instanceof InterfaceDeclContext c) register(unit, c, c.IDENTIFIER().getSymbol(), Kind.TYPE, exported);
                else if (child instanceof FunctionDeclContext f) register(unit, f, f.IDENTIFIER().getSymbol(), Kind.FUNCTION, exported);
                exported = false;
            }
        }
        for (var unit : units) resolveImports(unit);
        var combined = new ProgramContext(null, -1);
        combined.start = trees.get(0).start;
        for (var unit : units) {
            walk(unit.tree, unit, new ArrayDeque<>(), Set.of(), new Members(Set.of(),Set.of()));
            var lowered = lower(unit);
            for (int i=0;i<lowered.getChildCount();i++) {
                ParseTree child = lowered.getChild(i);
                if (child instanceof ParserRuleContext declaration) combined.addChild(declaration);
            }
        }
        var last = trees.get(trees.size()-1).EOF();
        combined.addChild(new TerminalNodeImpl(last.getSymbol()));
        combined.stop = last.getSymbol();
        return combined;
    }
    private void collectSpellings(ParseTree node) {
        if (node instanceof TerminalNode t) spellings.add(key(t.getText()));
        else for (int i=0;i<node.getChildCount();i++) collectSpellings(node.getChild(i));
    }
    private void register(Unit unit, ParserRuleContext declaration, Token name, Kind kind, boolean exported) throws CompilationException {
        String shortName = key(name.getText()), full = full(unit, shortName);
        if (kind == Kind.FUNCTION && BUILTINS.contains(shortName)) {
            if (((FunctionDeclContext)declaration).typeParameters()!=null) throw error(name,"Reserved function cannot be generic: " + shortName);
            throw error(name, "Duplicate or reserved function: " + shortName);
        }
        var table = kind == Kind.TYPE ? types : functions;
        var old = table.get(full);
        if (old != null && kind == Kind.TYPE) {
            boolean plainClasses = old.declaration instanceof ClassDeclContext a && a.typeParameters()==null
                    && declaration instanceof ClassDeclContext b && b.typeParameters()==null;
            throw error(name, (plainClasses ? "Duplicate class: " : "Duplicate type declaration: ") + full);
        }
        if (old != null && old.exported != exported) throw error(name, "Overloads must have identical package visibility: " + full);
        String lowered;
        if (old != null) lowered = old.lowered;
        else if (shortName.equals(key("मुख्य")) && kind == Kind.FUNCTION) lowered = "मुख्य";
        else {
            do { lowered = "संकुलित_" + next++; } while (!spellings.add(lowered));
        }
        if (old == null) table.put(full, new Symbol(full, lowered, kind, exported, unit, declaration));
        unit.replacements.put(name.getTokenIndex(), lowered);
    }
    private void resolveImports(Unit unit) throws CompilationException {
        for (var declaration : unit.tree.importDecl()) {
            String name = key(declaration.qualifiedName().getText());
            Symbol type = types.get(name), function = functions.get(name);
            if (type == null && function == null) throw error(declaration.start, "Unknown import: " + name);
            if (type != null) accessible(type, unit, declaration.start);
            if (function != null) accessible(function, unit, declaration.start);
            String alias = declaration.IDENTIFIER() == null
                    ? name.substring(name.lastIndexOf('.')+1) : key(declaration.IDENTIFIER().getText());
            if (BUILTINS.contains(alias) || alias.equals(key("मुख्य"))) throw error(declaration.start, "Reserved import name: " + alias);
            if (unit.imports.putIfAbsent(alias, name) != null) throw error(declaration.start, "Duplicate or ambiguous import: " + alias);
            for (var table : List.of(types, functions)) {
                var local = table.get(full(unit, alias));
                if (local != null && !local.full.equals(name)) throw error(declaration.start, "Import conflicts with package declaration: " + alias);
            }
        }
    }
    private static void accessible(Symbol symbol, Unit unit, Token at) throws CompilationException {
        if (!symbol.unit.pkg.equals(unit.pkg) && !symbol.exported)
            throw error(at, "Declaration is not public outside its package: " + symbol.full);
    }
    private Symbol lookup(Unit unit, String name, Kind kind, Token at) throws CompilationException {
        var table = kind == Kind.TYPE ? types : functions;
        name = key(name);
        Symbol symbol;
        if (name.contains(".")) symbol = table.get(name);
        else {
            String imported = unit.imports.get(name);
            symbol = table.get(imported == null ? full(unit,name) : imported);
        }
        if (symbol != null) accessible(symbol,unit,at);
        return symbol;
    }
    private Set<String> parameters(TypeParametersContext parameters, Set<String> outer) {
        var result = new HashSet<>(outer);
        if (parameters != null) for (var id : parameters.IDENTIFIER()) result.add(key(id.getText()));
        return result;
    }
    private String typeSpelling(ValueTypeContext type) {
        var name = new StringBuilder(type.IDENTIFIER().getText());
        for (var part : type.typeQualifier()) name.append('.').append(part.IDENTIFIER().getText());
        return name.toString();
    }
    private Members members(Unit unit, ParserRuleContext declaration, Set<ParserRuleContext> seen) throws CompilationException {
        var fields = new HashSet<String>();
        var methods = new HashSet<String>();
        var result = new Members(fields,methods);
        if (!seen.add(declaration)) return result;
        List<ValueTypeContext> parents = new ArrayList<>();
        if (declaration instanceof ClassDeclContext c) {
            for (var m : c.classMember()) {
                if (m.fieldDecl()!=null) fields.add(key(m.fieldDecl().IDENTIFIER().getText()));
                if (m.functionDecl()!=null) methods.add(key(m.functionDecl().IDENTIFIER().getText()));
            }
            if (c.valueType()!=null) parents.add(c.valueType());
        } else if (declaration instanceof InterfaceDeclContext c) {
            for (var f : c.functionDecl()) methods.add(key(f.IDENTIFIER().getText()));
            if (c.typeList()!=null) parents.addAll(c.typeList().valueType());
        }
        for (var parent : parents) if (parent.IDENTIFIER()!=null) {
            Symbol base = lookup(unit,typeSpelling(parent),Kind.TYPE,parent.start);
            if (base!=null) {
                var inherited = members(base.unit,base.declaration,seen);
                fields.addAll(inherited.fields); methods.addAll(inherited.methods);
            }
        }
        return result;
    }
    private boolean bound(Deque<Set<String>> scopes, Members members, String name, boolean call) {
        return members.fields.contains(name) || call && members.methods.contains(name)
                || scopes.stream().anyMatch(s -> s.contains(name));
    }
    private void walk(ParseTree node, Unit unit, Deque<Set<String>> scopes, Set<String> typeParameters, Members members) throws CompilationException {
        if (node instanceof PackageDeclContext || node instanceof ImportDeclContext || node instanceof TypeParametersContext) return;
        if (node instanceof ClassDeclContext c) {
            typeParameters = parameters(c.typeParameters(),typeParameters);
            members = members(unit,c,new HashSet<>());
        } else if (node instanceof InterfaceDeclContext c) {
            typeParameters = parameters(c.typeParameters(),typeParameters);
            members = members(unit,c,new HashSet<>());
        }
        boolean callable = node instanceof FunctionDeclContext || node instanceof ConstructorDeclContext;
        if (callable) {
            ParametersContext params = node instanceof FunctionDeclContext f ? f.parameters() : ((ConstructorDeclContext)node).parameters();
            if (node instanceof FunctionDeclContext f) typeParameters = parameters(f.typeParameters(),typeParameters);
            var locals = new HashSet<String>();
            if (params!=null) for (var param : params.parameter()) locals.add(key(param.IDENTIFIER().getText()));
            scopes.push(locals);
        }
        boolean block = node instanceof BlockContext || node instanceof SwitchSectionContext;
        if (block) scopes.push(new HashSet<>());
        if (node instanceof ValueTypeContext type && type.IDENTIFIER()!=null) {
            String name = typeSpelling(type);
            if (!(type.typeQualifier().isEmpty() && typeParameters.contains(key(name)))) {
                Symbol symbol = lookup(unit,name,Kind.TYPE,type.start);
                if (symbol == null) {
                    boolean base = type.getParent() instanceof ClassDeclContext c && c.valueType()==type;
                    boolean construction = type.getParent() instanceof PrimaryContext p && p.NEW()!=null;
                    throw error(type.start,(base ? "Unknown base class: " : construction ? "Unknown class: " : "Unknown type: ") + name);
                }
                replace(unit,type.IDENTIFIER().getSymbol(),type.typeQualifier().isEmpty() ? type.IDENTIFIER().getSymbol()
                        : type.typeQualifier(type.typeQualifier().size()-1).stop,symbol.lowered);
            }
        }
        if (node instanceof PostfixContext postfix) resolvePostfix(postfix,unit,scopes,members,typeParameters);
        if (!(node instanceof TerminalNode)) for (int i=0;i<node.getChildCount();i++) walk(node.getChild(i),unit,scopes,typeParameters,members);
        if (node instanceof VariableDeclContext variable && !scopes.isEmpty()) scopes.peek().add(key(variable.IDENTIFIER().getText()));
        if (block) scopes.pop();
        if (callable) scopes.pop();
    }
    private void resolvePostfix(PostfixContext postfix, Unit unit, Deque<Set<String>> scopes, Members members,
            Set<String> typeParameters) throws CompilationException {
        var p = postfix.primary();
        if (p.IDENTIFIER()==null) return;
        Token first = p.IDENTIFIER().getSymbol();
        String name = key(first.getText());
        if (bound(scopes,members,name,p.LPAREN()!=null)) return;
        // A visible short type name is a receiver, even if a root package has the same spelling.
        if (p.LPAREN()==null && !typeParameters.contains(name)) {
            Symbol receiver = lookup(unit,name,Kind.TYPE,first);
            if (receiver!=null) {
                unit.replacements.put(first.getTokenIndex(),receiver.lowered);
                return;
            }
        }
        // A qualified type receiver or function call consumes only its symbolic prefix, leaving member access intact.
        if (p.LPAREN()==null && p.typeArguments()==null && !postfix.postfixPart().isEmpty()) {
            String path = name;
            Symbol match = null;
            PostfixPartContext end = null;
            for (var part : postfix.postfixPart()) {
                path += "." + key(part.IDENTIFIER().getText());
                Symbol candidate = (part.LPAREN()==null ? types : functions).get(path);
                if (candidate!=null) { match=candidate; end=part; }
                if (part.LPAREN()!=null || part.typeArguments()!=null) break;
            }
            if (match!=null) {
                accessible(match,unit,first);
                replace(unit,first,end.IDENTIFIER().getSymbol(),match.lowered);
                return;
            }
        }
        Symbol symbol = lookup(unit,name,p.LPAREN()==null ? Kind.TYPE : Kind.FUNCTION,first);
        if (symbol!=null && (p.LPAREN()!=null || !typeParameters.contains(name))) unit.replacements.put(first.getTokenIndex(),symbol.lowered);
        else if (name.equals(key("मुख्य")) && p.LPAREN()!=null) throw error(first,"Undefined function in this package: मुख्य");
    }
    private void replace(Unit unit, Token first, Token last, String text) {
        unit.replacements.put(first.getTokenIndex(),text);
        for (int i=first.getTokenIndex()+1;i<=last.getTokenIndex();i++) unit.removed.add(i);
    }
    private ProgramContext lower(Unit unit) throws CompilationException {
        var text = new StringBuilder();
        var origins = new TreeMap<Integer,Token>();
        render(unit.tree,unit,text,origins);
        var parsed = Frontend.parse(LocatedToken.source(unit.tree.start,"project"),text.toString());
        if (!parsed.valid()) throw new CompilationException("Internal package resolution error: " + parsed.diagnostics());
        remap(parsed.tree(),origins);
        return parsed.tree();
    }
    private void render(ParseTree node, Unit unit, StringBuilder text, NavigableMap<Integer,Token> origins) throws CompilationException {
        if (node instanceof PackageDeclContext || node instanceof ImportDeclContext) return;
        if (node instanceof ShiftOperatorContext operator) {
            origins.put(text.length(),operator.start);
            text.append(operator.getText()).append(' ');
            return;
        }
        if (node instanceof TerminalNode t) {
            Token token = t.getSymbol();
            if (token.getType()==Token.EOF || unit.removed.contains(token.getTokenIndex())) return;
            if (token.getType()==BhashParser.PUBLIC && t.getParent() instanceof ProgramContext) return;
            origins.put(text.length(),token);
            text.append(unit.replacements.getOrDefault(token.getTokenIndex(),token.getText())).append(' ');
        } else {
            if (node instanceof PostfixPartContext part && part.typeArguments()!=null
                    && !unit.removed.contains(part.IDENTIFIER().getSymbol().getTokenIndex()))
                throw error(part.start,"Generic member methods are not supported; import the generic type or function.");
            for (int i=0;i<node.getChildCount();i++) render(node.getChild(i),unit,text,origins);
        }
    }
    static void remap(ParseTree node, NavigableMap<Integer,Token> origins) {
        if (node instanceof TerminalNode t && t.getSymbol() instanceof LocatedToken token) {
            var origin = origins.floorEntry(token.getStartIndex());
            if (origin!=null) token.locate(origin.getValue());
        } else for (int i=0;i<node.getChildCount();i++) remap(node.getChild(i),origins);
    }
}
