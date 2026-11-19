// SPDX-License-Identifier: Apache-2.0
package dev.bhash.backend;

import dev.bhash.parser.BhashParser.*;
import dev.bhash.frontend.LocatedToken;
import java.util.*;
import java.util.function.BiFunction;
import org.antlr.v4.runtime.ParserRuleContext;
import org.antlr.v4.runtime.tree.ParseTree;
import static dev.bhash.backend.ManagedEmitter.*;

/** Definite assignment for blank immutable instance fields, using resolved field identities. */
final class ConstructorInitialization {
    private record State(Set<Field> definite, Set<Field> possible, Flow completion) {
        State copy() { return new State(new HashSet<>(definite),new HashSet<>(possible),completion); }
        State end(Flow flow) { return new State(definite,possible,flow); }
    }
    private final Set<Field> required;
    private final Map<ParserRuleContext,Field> fields;
    private final BiFunction<ParserRuleContext,String,? extends RuntimeException> error;
    private ConstructorInitialization(ClassInfo owner,Map<ParserRuleContext,Field> fields,
            BiFunction<ParserRuleContext,String,? extends RuntimeException> error) {
        required=new LinkedHashSet<>();
        for(var f:owner.fields.values())if(!f.shared()&&f.declaration()!=null&&f.declaration().expression()==null) {
            boolean strict=f.declaration().start instanceof LocatedToken token&&token.strictNulls();
            if(!f.mutable()||strict&&!f.type().nullable()&&f.type().reference())required.add(f);
        }
        this.fields=fields;this.error=error;
    }
    static void check(ParserRuleContext at,BlockContext body,ClassInfo owner,Map<ParserRuleContext,Field> fields,
            BiFunction<ParserRuleContext,String,? extends RuntimeException> error) {
        var checker=new ConstructorInitialization(owner,fields,error);
        if(checker.required.isEmpty())return;
        var initial=new State(new HashSet<>(),new HashSet<>(),Flow.NORMAL);
        var results=body==null?List.of(initial):checker.block(body,List.of(initial),false);
        for(var result:results)if(result.completion==Flow.NORMAL||result.completion==Flow.RETURN)
            for(var field:checker.required)if(!result.definite.contains(field))
                throw error.apply(at,(field.mutable()?"Non-null field":"Immutable field")+" must be initialized on every successful constructor path: "+field.declaration().IDENTIFIER().getText());
    }
    private void reads(ParseTree tree,State state) {
        if(tree==null)return;
        var field=fields.get(tree);
        if(required.contains(field)&&!state.definite.contains(field))
            throw error.apply((ParserRuleContext)tree,"Immutable field read before initialization: "+field.declaration().IDENTIFIER().getText());
        for(int i=0;i<tree.getChildCount();i++)reads(tree.getChild(i),state);
    }
    private List<State> block(BlockContext block,List<State> input,boolean loop) {
        var states=input;
        for(var statement:block.statement()) {
            var next=new ArrayList<State>();
            for(var state:states)if(state.completion!=Flow.NORMAL)next.add(state);else next.addAll(statement(statement,state.copy(),loop));
            // Merge paths with the same abrupt completion. This keeps analysis linear in branch count.
            states=merge(next);
        }
        return states;
    }
    private List<State> merge(List<State> states) {
        var result=new EnumMap<Flow,State>(Flow.class);
        for(var state:states) {
            var old=result.get(state.completion);
            if(old==null)result.put(state.completion,state.copy());
            else {old.definite.retainAll(state.definite);old.possible.addAll(state.possible);}
        }
        return new ArrayList<>(result.values());
    }
    private void writes(ParseTree tree,Set<Field> result) {
        if(tree instanceof AssignmentContext a) {
            var p=a.postfix();var at=p.postfixPart().isEmpty()?p.primary():p.postfixPart(p.postfixPart().size()-1);
            var f=fields.get(at);if(required.contains(f))result.add(f);
        }
        for(int i=0;i<tree.getChildCount();i++)writes(tree.getChild(i),result);
    }
    private List<State> statement(StatementContext s,State state,boolean loop) {
        if(s.block()!=null)return block(s.block(),List.of(state),loop);
        if(s.assignment()!=null) {
            var a=s.assignment();var p=a.postfix();
            ParserRuleContext target=p.postfixPart().isEmpty()?p.primary():p.postfixPart(p.postfixPart().size()-1);
            var field=fields.get(target);
            if(required.contains(field)) {
                // Evaluate the receiver and RHS before performing the write.
                if(target!=p.primary()) { reads(p.primary(),state);for(var part:p.postfixPart())if(part!=target)reads(part,state); }
                reads(a.expression(),state);
                if(!field.mutable()&&(loop||state.possible.contains(field)))throw error.apply(a,"Immutable field may already be initialized: "+field.declaration().IDENTIFIER().getText());
                state.definite.add(field);state.possible.add(field);return List.of(state);
            }
        }
        if(s.ifStatement()!=null) {
            var b=s.ifStatement();reads(b.expression(),state);
            var paths=new ArrayList<>(block(b.block(0),List.of(state.copy()),loop));
            if(b.ifStatement()!=null)paths.addAll(branch(b.ifStatement(),state.copy(),loop));
            else paths.addAll(b.block().size()>1?block(b.block(1),List.of(state.copy()),loop):List.of(state.copy()));
            return merge(paths);
        }
        if(s.whileStatement()!=null||s.foreachStatement()!=null) {
            var b=s.whileStatement()!=null?s.whileStatement().block():s.foreachStatement().block();
            reads(s.whileStatement()!=null?s.whileStatement().expression():s.foreachStatement().expression(),state);
            var paths=new ArrayList<State>();paths.add(state.copy());
            for(var path:block(b,List.of(state.copy()),true))if(path.completion==Flow.RETURN||path.completion==Flow.THROW)paths.add(path);
            return merge(paths);
        }
        if(s.switchStatement()!=null) {
            var choice=s.switchStatement();reads(choice.expression(),state);var paths=new ArrayList<State>();boolean fallback=false;
            for(var section:choice.switchSection()) {
                fallback|=section.DEFAULT()!=null;var current=List.of(state.copy());
                for(var child:section.statement()) {var next=new ArrayList<State>();for(var p:current)if(p.completion==Flow.NORMAL)next.addAll(statement(child,p,loop));else next.add(p);current=merge(next);}
                for(var p:current)paths.add(p.completion==Flow.BREAK?p.end(Flow.NORMAL):p);
            }
            if(!fallback)paths.add(state.copy());return merge(paths);
        }
        if(s.tryStatement()!=null) {
            var t=s.tryStatement();var paths=new ArrayList<>(block(t.block(0),List.of(state.copy()),loop));
            var possible=new HashSet<>(state.possible);writes(t.block(0),possible);
            for(var handler:t.catchClause())paths.addAll(block(handler.block(),List.of(new State(new HashSet<>(state.definite),new HashSet<>(possible),Flow.NORMAL)),loop));
            if(t.FINALLY()!=null) {
                var results=new ArrayList<State>();
                for(var path:paths)for(var fin:block(t.block(1),List.of(path.end(Flow.NORMAL).copy()),loop))results.add(fin.completion==Flow.NORMAL?fin.end(path.completion):fin);
                return merge(results);
            }
            return merge(paths);
        }
        if(s.resourceStatement()!=null) {reads(s.resourceStatement().expression(),state);return block(s.resourceStatement().block(),List.of(state),loop);}
        reads(s,state);
        return List.of(s.RETURN()!=null?state.end(Flow.RETURN):s.THROW()!=null?state.end(Flow.THROW):s.BREAK()!=null?state.end(Flow.BREAK):s.CONTINUE()!=null?state.end(Flow.CONTINUE):state);
    }
    private List<State> branch(IfStatementContext b,State state,boolean loop) {
        reads(b.expression(),state);var paths=new ArrayList<>(block(b.block(0),List.of(state.copy()),loop));
        if(b.ifStatement()!=null)paths.addAll(branch(b.ifStatement(),state.copy(),loop));
        else paths.addAll(b.block().size()>1?block(b.block(1),List.of(state.copy()),loop):List.of(state.copy()));
        return merge(paths);
    }
}
