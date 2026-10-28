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
public record CoreProgram(List<Function> functions, int entry) {
    public enum Type { INT, BOOL, TEXT, VOID }
    public record Source(String file, int line, int column) {
        @Override public String toString() { return file+":"+line+":"+column; }
    }
    public record Binding(int id, Type type) {}
    public record Function(int id, Type result, List<Binding> parameters, Block body, Source source) {}
    public sealed interface Expression permits Constant, Read, Unary, Binary, Call {
        Type type(); Source source();
    }
    public record Constant(Type type, Object value, Source source) implements Expression {}
    public record Read(Binding binding, Source source) implements Expression {
        public Type type() { return binding.type(); }
    }
    public record Unary(Type type, String operator, Expression value, Source source) implements Expression {}
    public record Binary(Type type, String operator, Expression left, Expression right, Source source) implements Expression {}
    public record Call(Type type, int function, List<Expression> arguments, Source source) implements Expression {}
    public sealed interface Statement permits Block, Assign, Branch, Loop, Return, Jump, Print, Evaluate {}
    public record Block(List<Statement> statements) implements Statement {}
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
            if (!(c instanceof FunctionDeclContext)) throw unsupported(c);
            validate(c);
        }
    }
    private static void validate(ParserRuleContext c) throws CompilationException {
        if (c instanceof TypeParametersContext || c instanceof TypeArgumentsContext) throw unsupported(c);
        if (c instanceof ValueTypeContext t && (!t.arraySuffix().isEmpty() ||
                !(t.INT_TYPE()!=null || t.BOOL_TYPE()!=null || t.TEXT_TYPE()!=null))) throw unsupported(c);
        if (c instanceof StatementContext s && (s.tryStatement()!=null || s.THROW()!=null ||
                s.switchStatement()!=null || s.foreachStatement()!=null)) throw unsupported(c);
        if (c instanceof PostfixContext p && !p.postfixPart().isEmpty()) throw unsupported(c);
        if (c instanceof UnaryContext u && u.BIT_NOT()!=null) throw unsupported(c);
        if ((c instanceof ShiftContext || c instanceof BitwiseOrContext || c instanceof BitwiseXorContext ||
                c instanceof BitwiseAndContext) && c.getChildCount()>1) throw unsupported(c);
        if (c instanceof PrimaryContext p && !(p.INTEGER_LITERAL()!=null || p.STRING_LITERAL()!=null ||
                p.TRUE()!=null || p.FALSE()!=null || p.IDENTIFIER()!=null ||
                p.expression()!=null && p.LPAREN()!=null && p.CONVERT()==null && p.TYPE_TEST()==null && p.SAFE_CAST()==null))
            throw unsupported(c);
        for (int i=0;i<c.getChildCount();i++) if (c.getChild(i) instanceof ParserRuleContext p) validate(p);
    }
    static CoreProgram build(ProgramContext tree, ManagedEmitter.Analysis analysis,
                             List<ManagedEmitter.Function> checked) throws CompilationException {
        return new Builder(analysis,checked).program(tree);
    }
    private static final class Builder {
        final ManagedEmitter.Analysis a;
        final Map<ManagedEmitter.Binding,Binding> bindings=new IdentityHashMap<>();
        final Map<ManagedEmitter.Function,Integer> ids=new IdentityHashMap<>();
        final Map<FunctionDeclContext,ManagedEmitter.Function> declarations=new IdentityHashMap<>();
        Builder(ManagedEmitter.Analysis analysis,List<ManagedEmitter.Function> checked) {
            a=analysis;
            for(var f:checked) if(f.declaration!=null && !library(f.declaration)) {
                ids.put(f,ids.size()); declarations.put(f.declaration,f);
            }
        }
        Type type(ManagedEmitter.Type t,ParserRuleContext at) throws CompilationException {
            if(t==ManagedEmitter.Type.INT)return Type.INT;
            if(t==ManagedEmitter.Type.BOOL)return Type.BOOL;
            if(t==ManagedEmitter.Type.TEXT)return Type.TEXT;
            if(t==ManagedEmitter.Type.VOID)return Type.VOID;
            throw unsupported(at);
        }
        Binding binding(ManagedEmitter.Binding b,ParserRuleContext at) throws CompilationException {
            Binding result=bindings.get(b);
            if(result==null) { result=new Binding(bindings.size(),type(b.type(),at)); bindings.put(b,result); }
            return result;
        }
        CoreProgram program(ProgramContext tree) throws CompilationException {
            var result=new ArrayList<Function>(); int entry=-1;
            for(var d:tree.functionDecl()) {
                if(library(d))continue;
                var f=declarations.get(d);
                var parameters=new ArrayList<Binding>();
                if(d.parameters()!=null)for(var p:d.parameters().parameter())parameters.add(binding(a.bindings.get(p),p));
                int id=ids.get(f);
                if(Identifiers.key(d.IDENTIFIER().getText()).equals(Identifiers.key("मुख्य")))entry=id;
                result.add(new Function(id,type(f.result,d),List.copyOf(parameters),block(d.block()),source(d)));
            }
            if(entry<0)throw new CompilationException("Native core has no checked entry point.");
            return new CoreProgram(List.copyOf(result),entry);
        }
        Block block(BlockContext c) throws CompilationException {
            var result=new ArrayList<Statement>();
            for(var s:c.statement())result.add(statement(s));
            return new Block(List.copyOf(result));
        }
        Statement branch(IfStatementContext c) throws CompilationException {
            Statement no=c.ifStatement()!=null?branch(c.ifStatement()):c.block().size()>1?block(c.block(1)):null;
            return new Branch(expression(c.expression()),block(c.block(0)),no);
        }
        Statement statement(StatementContext s) throws CompilationException {
            if(s.variableDecl()!=null) {
                var d=s.variableDecl();
                return new Assign(binding(a.bindings.get(d),d),expression(d.expression()));
            }
            if(s.assignment()!=null) {
                var d=s.assignment();
                return new Assign(binding(a.reads.get(d.postfix().primary()),d),expression(d.expression()));
            }
            if(s.ifStatement()!=null)return branch(s.ifStatement());
            if(s.whileStatement()!=null) {
                var w=s.whileStatement(); return new Loop(expression(w.expression()),block(w.block()));
            }
            if(s.RETURN()!=null)return new Return(s.expression()==null?null:expression(s.expression()));
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
        Expression expression(ParserRuleContext c) throws CompilationException {
            Object constant=a.constants.get(c);
            if(constant!=null)return new Constant(constant instanceof Long?Type.INT:Type.TEXT,constant,source(c));
            if(c instanceof PrimaryContext p) {
                if(p.TRUE()!=null || p.FALSE()!=null)return new Constant(Type.BOOL,p.TRUE()!=null,source(c));
                if(p.IDENTIFIER()!=null) {
                    if(p.LPAREN()==null)return new Read(binding(a.reads.get(p),p),source(c));
                    var f=a.calls.get(p);
                    if(f==null || !ids.containsKey(f))throw unsupported(p);
                    return new Call(type(f.result,p),ids.get(f),arguments(p.arguments()),source(c));
                }
                if(p.expression()!=null)return expression(p.expression());
                throw unsupported(p);
            }
            if(c instanceof UnaryContext u && u.postfix()==null) {
                if(u.BIT_NOT()!=null)throw unsupported(u);
                return new Unary(type(a.expressions.get(c).type(),c),u.getChild(0).getText(),expression(u.unary()),source(c));
            }
            if(c.getChildCount()==1)return expression((ParserRuleContext)c.getChild(0));
            if(c instanceof PostfixContext p)return expression(p.primary());
            Expression result=expression((ParserRuleContext)c.getChild(0));
            for(int i=1;i<c.getChildCount();i+=2) {
                String op=c.getChild(i).getText();
                if(op.equals("+") && result.type()==Type.TEXT)throw unsupported(c);
                Expression right=expression((ParserRuleContext)c.getChild(i+1));
                Type t=Set.of("==","!=","<","<=",">",">=","&&","||").contains(op)?Type.BOOL:result.type();
                result=new Binary(t,op,result,right,source(c));
            }
            return result;
        }
    }
}
