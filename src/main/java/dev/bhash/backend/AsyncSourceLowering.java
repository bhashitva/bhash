// SPDX-License-Identifier: Apache-2.0
package dev.bhash.backend;

import dev.bhash.frontend.*;
import dev.bhash.parser.BhashParser.*;
import java.util.*;
import org.antlr.v4.runtime.*;
import org.antlr.v4.runtime.tree.*;

/** Checked AST suspension conversion shared by all backends. Bindings are
 * resolved before spilling and generated ordinary classes are checked again.
 * No host stack frame or exception context survives a suspension. */
final class AsyncSourceLowering {
    static final String START="भाषकार्यआरम्भ", STEP="भाषकार्यचरण", CANCEL="भाषकार्यनिरस्त";
    private final ManagedEmitter.Analysis analysis;
    private final Map<String,String> names=new HashMap<>();
    private final Set<String> used=new HashSet<>();
    private final Set<String> scalars=new HashSet<>(Set.of("पूर्णांक","लघुपूर्णांक","दशमलव","तार्किक","बाइट"));
    private final Map<FunctionDeclContext,Frame> frames=new IdentityHashMap<>();
    private final Map<ClassDeclContext,List<Frame>> members=new IdentityHashMap<>();
    private final List<Frame> ordered=new ArrayList<>();
    private final StringBuilder output=new StringBuilder();
    private final NavigableMap<Integer,Token> origins=new TreeMap<>();
    private int next,offset;
    private String root;
    private AsyncSourceLowering(ManagedEmitter.Analysis analysis,Map<String,String> names){this.analysis=analysis;names.forEach((k,v)->this.names.put(v,k));}
    static boolean needed(ParseTree n){if(n instanceof FunctionDeclContext f && f.ASYNC()!=null || n instanceof UnaryContext u && u.AWAIT()!=null)return true;for(int i=0;i<n.getChildCount();i++)if(needed(n.getChild(i)))return true;return false;}
    static ProgramContext lower(ProgramContext tree,ManagedEmitter.Analysis analysis,Map<String,String> names)throws CompilationException {
        var p=new AsyncSourceLowering(analysis,names);p.collect(tree);
        for(var enumeration:tree.enumDecl())p.scalars.add(enumeration.IDENTIFIER().getText());
        for(var f:tree.functionDecl())if(f.ASYNC()!=null && f.block()!=null)p.add(f,null,false);
        for(var c:tree.classDecl())for(var m:c.classMember())if(m.functionDecl()!=null && m.functionDecl().ASYNC()!=null)p.add(m.functionDecl(),c,m.STATIC()!=null);
        for(var f:p.ordered)f.build();
        p.render(tree);
        for(var f:p.ordered)p.append(f.frameClass(),f.tree.start);
        if(p.root!=null)p.entry(tree.start);
        var parsed=Frontend.parse("async-lowering",p.output.toString());
        if(!parsed.valid())throw new CompilationException(parsed.diagnostics().toString());
        ProjectResolver.remap(parsed.tree(),p.origins);return parsed.tree();
    }
    private void collect(ParseTree n){if(n instanceof TerminalNode t)used.add(t.getText());for(int i=0;i<n.getChildCount();i++)collect(n.getChild(i));}
    private String fresh(){String n;do{n="असम_"+next++;}while(!used.add(n));return n;}
    private String standard(String name){var n=names.get("मानक."+name);if(n==null)throw new IllegalStateException("Missing async runtime declaration: "+name);return n;}
    private void add(FunctionDeclContext f,ClassDeclContext c,boolean shared){var frame=new Frame(f,c,shared);frames.put(f,frame);ordered.add(frame);if(c!=null)members.computeIfAbsent(c,k->new ArrayList<>()).add(frame);}
    private void append(String value,Token origin){origins.put(offset,origin);output.append(value).append(' ');offset+=value.codePointCount(0,value.length())+1;}
    private void render(ParseTree n)throws CompilationException {
        if(n instanceof FunctionDeclContext f && frames.containsKey(f)) {var frame=frames.get(f);append(frame.factory(),f.IDENTIFIER().getSymbol());if(frame.owner==null)frame.emitHelper();return;}
        if(n instanceof ClassDeclContext c && c.RECORD()==null) {for(int i=0;i<c.getChildCount()-1;i++)render(c.getChild(i));for(var f:members.getOrDefault(c,List.of()))f.emitHelper();render(c.getChild(c.getChildCount()-1));return;}
        if(n instanceof TerminalNode t){if(t.getSymbol().getType()!=Token.EOF && t.getSymbol().getType()!=dev.bhash.parser.BhashParser.ASYNC)append(t.getText(),t.getSymbol());return;}
        if(n instanceof ShiftOperatorContext s){append(s.getText(),s.start);return;}
        for(int i=0;i<n.getChildCount();i++)render(n.getChild(i));
    }
    private void entry(Token at) {
        String holder=fresh(),scheduler=standard("कार्यतंत्र"),base=standard("कार्यआधार");
        append("वर्ग "+holder+" { सार्वजनिक साझा मान मूल:"+base+"; }",at);
        append("फलन "+START+"()->शून्य { "+holder+".मूल="+root+"(); }",at);
        append("फलन "+STEP+"()->तार्किक { मान ग:पूर्णांक=०; जबतक(ग<६४ && "+scheduler+".चरण()){ग=ग+१;} यदि("+holder+".मूल.समाप्तहै()){ "+holder+".मूल.आंतरिकपरिणामजाँचो(); "+holder+".मूल=रिक्त; लौटाओ असत्य;} लौटाओ सत्य; }",at);
        append("फलन "+CANCEL+"()->शून्य { यदि("+holder+".मूल!=रिक्त){"+holder+".मूल.आंतरिकचिह्न.निरस्तकरो();} }",at);
        append("फलन मुख्य()->शून्य { "+START+"(); जबतक("+STEP+"()){ "+scheduler+".विश्राम(); } }",at);
    }
    private record Flow(int failed,int returned,int broken,int continued,String caught){}
    private record State(String code,int failure,Token origin){}

    private final class Frame {
        final FunctionDeclContext tree;final ClassDeclContext owner;final boolean shared;
        final String name=fresh(),helper=fresh(),arg=fresh(),pc=fresh(),error=fresh(),receiver=fresh();
        final LinkedHashMap<String,String> fields=new LinkedHashMap<>();
        final Map<ManagedEmitter.Binding,String> bindings=new IdentityHashMap<>();
        final List<State> states=new ArrayList<>();
        List<Set<String>> clearAt=List.of();
        final String resultType;int entry,success,failure;
        Frame(FunctionDeclContext tree,ClassDeclContext owner,boolean shared){this.tree=tree;this.owner=owner;this.shared=shared;resultType=raw(tree.returnType());fields.put(pc,"पूर्णांक");fields.put(error,"अपवाद");if(owner!=null&&!shared)fields.put(receiver,owner.IDENTIFIER().getText());register(tree.block());if(tree.parameters()!=null)register(tree.parameters());}
        void register(ParseTree node){if(node instanceof ParserRuleContext p){var b=analysis.bindings.get(p);if(b!=null)binding(b);}for(int i=0;i<node.getChildCount();i++)register(node.getChild(i));}
        String binding(ManagedEmitter.Binding b){return bindings.computeIfAbsent(b,k->field(type(k.type())));}
        String field(String type){String n=fresh();fields.put(n,type);return n;}
        String ref(String name){return arg+"."+name;}
        String type(ManagedEmitter.Type t){return t==ManagedEmitter.Type.NULL?"सर्ववस्तु":t.bhash;}
        String temp(ParseTree expression){var e=analysis.expressions.get(expression);if(e==null)throw new IllegalStateException("No checked expression: "+expression.getText());return field(type(e.type()));}
        String raw(ParseTree n){return text(n,Map.of());}
        String text(ParseTree n,Map<ParseTree,String> replaced) {
            var replacement=replaced.get(n);if(replacement!=null)return replacement;
            if(n instanceof PrimaryContext p && analysis.reads.containsKey(p)) {
                String value=ref(binding(analysis.reads.get(p)));
                if(p.LPAREN()!=null)value+="("+(p.arguments()==null?"":text(p.arguments(),replaced))+")";
                return value;
            }
            if(n instanceof ShiftOperatorContext)return n.getText();
            if(n instanceof TerminalNode t)return t.getSymbol().getType()==Token.EOF?"":t.getText();
            var b=new StringBuilder();for(int i=0;i<n.getChildCount();i++)b.append(text(n.getChild(i),replaced)).append(' ');return b.toString();
        }
        int state(String code,int failed,Token origin){int id=states.size();states.add(new State(code,failed,origin));return id;}
        String go(int target){return ref(pc)+"="+target+"; जारी;";}
        int action(String code,int next,Flow flow,Token at){return state(code+go(next),flow.failed,at);}
        boolean awaits(ParseTree n){if(n instanceof UnaryContext u && u.AWAIT()!=null)return true;for(int i=0;i<n.getChildCount();i++)if(awaits(n.getChild(i)))return true;return false;}
        void build()throws CompilationException {
            success=state(arg+".आंतरिकसफल(); लौटाओ;",-1,tree.stop);
            failure=state(arg+".आंतरिकअसफल("+ref(error)+"); लौटाओ;",-1,tree.stop);
            entry=block(tree.block(),success,new Flow(failure,success,-1,-1,null));
            clearAt=deadReferences();
        }
        /** Conservative liveness over generated state references. Assignments
         * count as uses, so a false positive only prolongs retention. No field
         * is cleared if any reachable state can mention it before completion. */
        List<Set<String>> deadReferences() {
            var live=new ArrayList<Set<String>>();var edges=new ArrayList<Set<Integer>>();
            var jumps=java.util.regex.Pattern.compile(java.util.regex.Pattern.quote(ref(pc))+"\\s*=\\s*([0-9]+)\\s*;");
            for(var state:states){var uses=new HashSet<String>();for(var f:fields.keySet())if(state.code.contains(ref(f)))uses.add(f);if(owner!=null&&!shared)uses.add(receiver);live.add(uses);
                var destinations=new HashSet<Integer>();if(state.failure>=0)destinations.add(state.failure);var match=jumps.matcher(state.code);while(match.find()){int target=Integer.parseInt(match.group(1));if(target<states.size())destinations.add(target);}edges.add(destinations);}
            boolean changed;do{changed=false;for(int i=states.size()-1;i>=0;i--)for(int target:edges.get(i))changed|=live.get(i).addAll(live.get(target));}while(changed);
            var clear=new ArrayList<Set<String>>();for(int i=0;i<states.size();i++)clear.add(new HashSet<>());
            clear.get(entry).addAll(fields.keySet());
            for(int i=0;i<states.size();i++)for(int target:edges.get(i))clear.get(target).addAll(live.get(i));
            for(int i=0;i<states.size();i++){clear.get(i).removeAll(live.get(i));clear.get(i).removeIf(f->scalars.contains(fields.get(f)));}
            return clear;
        }
        int assign(String destination,String value,int next,Flow flow,Token at){return action((destination==null?value:destination+"="+value)+";",next,flow,at);}
        int expression(ParseTree node,String destination,int next,Flow flow)throws CompilationException {
            var at=((ParserRuleContext)node).start;
            if(!awaits(node))return assign(destination,raw(node),next,flow,at);
            if(node instanceof UnaryContext u && u.AWAIT()!=null) {
                var task=temp(u.unary());var payload=analysis.expressions.get(u).type();
                String get=ref(task)+".आंतरिकमूल्यलो()";
                int release=action(ref(task)+"=रिक्त;",next,flow,at);
                int resume=assign(payload==ManagedEmitter.Type.VOID?null:destination,get,release,flow,at);
                int suspend=state(ref(pc)+"="+resume+"; "+ref(task)+".आंतरिकप्रतीक्षा("+arg+"); लौटाओ;",flow.failed,at);
                return expression(u.unary(),ref(task),suspend,flow);
            }
            if(node instanceof PostfixContext p)return postfix(p,p.postfixPart().size(),destination,next,flow);
            if(node instanceof PrimaryContext p) {
                var operands=new ArrayList<ExpressionContext>();collectArguments(p,operands);
                var replacement=new IdentityHashMap<ParseTree,String>();var temps=new ArrayList<String>();
                for(var e:operands){String t=temp(e);temps.add(t);replacement.put(e,ref(t));}
                int start=assign(destination,text(p,replacement),next,flow,at);
                for(int i=operands.size()-1;i>=0;i--)start=expression(operands.get(i),ref(temps.get(i)),start,flow);
                return start;
            }
            var children=new ArrayList<ParseTree>();for(int i=0;i<node.getChildCount();i++)if(analysis.expressions.containsKey(node.getChild(i)))children.add(node.getChild(i));
            if(children.size()==1) {
                if(node instanceof UnaryContext u && u.postfix()==null) {var t=temp(children.get(0));int apply=assign(destination,u.getChild(0).getText()+ref(t),next,flow,at);return expression(children.get(0),ref(t),apply,flow);}
                return expression(children.get(0),destination,next,flow);
            }
            if(children.size()>1) {
                // Left folds preserve the language's associativity and capture every
                // earlier operand before a subsequent suspension.
                var accumulated=new ArrayList<String>();accumulated.add(temp(children.get(0)));
                for(int i=1;i<children.size();i++)accumulated.add(field(type(analysis.expressions.get(node).type())));
                int tail=assign(destination,ref(accumulated.get(accumulated.size()-1)),next,flow,at);
                for(int i=children.size()-1;i>=1;i--) {
                    String op=node.getChild(2*i-1).getText();String right=temp(children.get(i));
                    String left=ref(accumulated.get(i-1)),result=ref(accumulated.get(i));
                    int apply=assign(result,left+op+ref(right),tail,flow,at);
                    int eval=expression(children.get(i),ref(right),apply,flow);
                    if(op.equals("&&")||op.equals("||")) {
                        int shortcut=assign(result,op.equals("||")?"सत्य":"असत्य",tail,flow,at);
                        eval=state("यदि("+(op.equals("||")?"!":"")+left+"){"+go(eval)+"}"+go(shortcut),flow.failed,at);
                    }
                    tail=eval;
                }
                return expression(children.get(0),ref(accumulated.get(0)),tail,flow);
            }
            throw new CompilationException("Cannot lower suspended expression: "+node.getText());
        }
        void collectArguments(ParseTree n,List<ExpressionContext> result){for(int i=0;i<n.getChildCount();i++){var c=n.getChild(i);if(c instanceof ExpressionContext e)result.add(e);else collectArguments(c,result);}}
        int postfix(PostfixContext p,int count,String destination,int next,Flow flow)throws CompilationException {
            if(count==0)return expression(p.primary(),destination,next,flow);
            var part=p.postfixPart(count-1);ParseTree previous=count==1?p.primary():p.postfixPart(count-2);
            var checked=analysis.expressions.get(previous);boolean direct=checked.typeReceiver()||checked.base();
            String receiverValue=direct?raw(previous):ref(temp(previous));
            var operands=new ArrayList<ExpressionContext>();if(part.expression()!=null)operands.add(part.expression());if(part.arguments()!=null)operands.addAll(part.arguments().expression());
            var replacements=new IdentityHashMap<ParseTree,String>();var temps=new ArrayList<String>();
            for(var e:operands){var t=temp(e);temps.add(t);replacements.put(e,ref(t));}
            int start=assign(destination,receiverValue+text(part,replacements),next,flow,part.start);
            for(int i=operands.size()-1;i>=0;i--)start=expression(operands.get(i),ref(temps.get(i)),start,flow);
            return direct?start:postfix(p,count-1,receiverValue,start,flow);
        }
        int block(BlockContext b,int next,Flow flow)throws CompilationException {return statements(b.statement(),next,flow);}
        int statements(List<StatementContext> list,int next,Flow flow)throws CompilationException {for(int i=list.size()-1;i>=0;i--)next=statement(list.get(i),next,flow);return next;}
        int statement(StatementContext s,int next,Flow flow)throws CompilationException {
            if(s.variableDecl()!=null){var v=s.variableDecl();return expression(v.expression(),ref(binding(analysis.bindings.get(v))),next,flow);}
            if(s.RETURN()!=null)return s.expression()==null?flow.returned:expression(s.expression(),arg+".आंतरिकमूल्य",flow.returned,flow);
            if(s.THROW()!=null)return s.expression()==null?action(ref(error)+"="+flow.caught+";",flow.failed,flow,s.start):expression(s.expression(),ref(error),flow.failed,flow);
            if(s.BREAK()!=null)return flow.broken;
            if(s.CONTINUE()!=null)return flow.continued;
            if(s.block()!=null)return block(s.block(),next,flow);
            if(s.ifStatement()!=null)return conditional(s.ifStatement(),next,flow);
            if(s.whileStatement()!=null){var w=s.whileStatement();int head=state("",flow.failed,w.start);int body=block(w.block(),head,new Flow(flow.failed,flow.returned,next,head,flow.caught));var condition=temp(w.expression());int choose=state("यदि("+ref(condition)+"){"+go(body)+"}"+go(next),flow.failed,w.start);int evaluate=expression(w.expression(),ref(condition),choose,flow);states.set(head,new State(go(evaluate),flow.failed,w.start));return head;}
            if(s.tryStatement()!=null)return attempt(s.tryStatement(),next,flow);
            if(s.resourceStatement()!=null)return resource(s.resourceStatement(),next,flow);
            if(s.foreachStatement()!=null)return each(s.foreachStatement(),next,flow);
            if(s.switchStatement()!=null){var sw=s.switchStatement();String selector=temp(sw.expression());int dispatch=next;var arms=sw.switchSection();var inner=new Flow(flow.failed,flow.returned,next,flow.continued,flow.caught);for(var arm:arms)if(arm.DEFAULT()!=null)dispatch=statements(arm.statement(),next,inner);for(int i=arms.size()-1;i>=0;i--){var arm=arms.get(i);if(arm.DEFAULT()!=null)continue;int body=statements(arm.statement(),next,inner);for(var label:arm.caseValue())dispatch=state("यदि("+ref(selector)+"=="+raw(label)+"){"+go(body)+"}"+go(dispatch),flow.failed,label.start);}return expression(sw.expression(),ref(selector),dispatch,flow);}
            if(s.assignment()!=null){var a=s.assignment();var p=a.postfix();if(p.postfixPart().isEmpty())return expression(a.expression(),raw(p),next,flow);int count=p.postfixPart().size();var last=p.postfixPart(count-1);ParseTree previous=count==1?p.primary():p.postfixPart(count-2);var checked=analysis.expressions.get(previous);boolean direct=checked.base()||checked.typeReceiver();String recv=direct?raw(previous):ref(temp(previous));String index=last.expression()==null?null:temp(last.expression());String location=recv+(index==null?"."+last.IDENTIFIER().getText():"["+ref(index)+"]");int start=expression(a.expression(),location,next,flow);if(index!=null)start=expression(last.expression(),ref(index),start,flow);return direct?start:postfix(p,count-1,recv,start,flow);}
            if(s.PRINT()!=null){var args=s.arguments()==null?List.<ExpressionContext>of():s.arguments().expression();var temps=args.stream().map(this::temp).toList();int start=action("लिखो("+String.join(",",temps.stream().map(this::ref).toList())+");",next,flow,s.start);for(int i=args.size()-1;i>=0;i--)start=expression(args.get(i),ref(temps.get(i)),start,flow);return start;}
            return expression(s.expression(),null,next,flow);
        }
        int conditional(IfStatementContext s,int next,Flow flow)throws CompilationException {int yes=block(s.block(0),next,flow),no=s.ifStatement()!=null?conditional(s.ifStatement(),next,flow):s.block().size()>1?block(s.block(1),next,flow):next;var condition=temp(s.expression());int choose=state("यदि("+ref(condition)+"){"+go(yes)+"}"+go(no),flow.failed,s.start);return expression(s.expression(),ref(condition),choose,flow);}
        int cleanup(BlockContext b,int target,Flow flow)throws CompilationException{return target<0?target:b==null?target:block(b,target,flow);}
        int attempt(TryStatementContext t,int next,Flow outer)throws CompilationException {
            var cleanup=t.FINALLY()==null?null:t.block(t.block().size()-1);
            int fail=outer.failed;
            if(cleanup!=null){String saved=field("अपवाद");int restore=action(ref(error)+"="+ref(saved)+";"+ref(saved)+"=रिक्त;",outer.failed,outer,t.start);int body=block(cleanup,restore,outer);fail=action(ref(saved)+"="+ref(error)+";",body,outer,t.start);}
            var inner=new Flow(fail,cleanup(cleanup,outer.returned,outer),cleanup(cleanup,outer.broken,outer),cleanup(cleanup,outer.continued,outer),outer.caught);
            int normal=cleanup(cleanup,next,outer),dispatch=fail;
            for(int i=t.catchClause().size()-1;i>=0;i--){var c=t.catchClause(i);String caught=ref(binding(analysis.bindings.get(c.parameter())));var handler=new Flow(inner.failed,inner.returned,inner.broken,inner.continued,caught);int body=block(c.block(),normal,handler);int bind=action(caught+"=परिवर्तित<"+raw(c.parameter().valueType())+">("+ref(error)+");",body,inner,c.start);dispatch=state("यदि(प्रकारहै<"+raw(c.parameter().valueType())+">("+ref(error)+")){"+go(bind)+"}"+go(dispatch),fail,c.start);}
            return block(t.block(0),normal,new Flow(dispatch,inner.returned,inner.broken,inner.continued,outer.caught));
        }
        int resource(ResourceStatementContext r,int next,Flow flow)throws CompilationException {
            // Every exit drains the nested owner before invoking close. Close is
            // run with runtime cancellation shielding and may itself suspend.
            String value=ref(binding(analysis.bindings.get(r.parameter()))),scope=ref(field(standard("कार्यसीमा"))),previous=ref(field(standard("कार्यआधार")));
            int normal=closeResource(r,value,scope,previous,next,flow,false);
            int failed=closeResource(r,value,scope,previous,flow.failed,flow,true);
            int returned=closeResource(r,value,scope,previous,flow.returned,flow,false);
            int broken=flow.broken<0?-1:closeResource(r,value,scope,previous,flow.broken,flow,false);
            int continued=flow.continued<0?-1:closeResource(r,value,scope,previous,flow.continued,flow,false);
            int body=block(r.block(),normal,new Flow(failed,returned,broken,continued,flow.caught));
            int begin=action("यदि("+value+"==रिक्त){फेंको नया अपवाद(\"रिक्त वस्तु के सदस्य का उपयोग नहीं किया जा सकता।\");}"+previous+"="+arg+".आंतरिकसीमा;"+scope+"=नया "+standard("कार्यसीमा")+"();"+standard("कार्यतंत्र")+".सीमाआरम्भ("+scope+");"+arg+".आंतरिकसीमा="+scope+";",body,flow,r.start);
            return expression(r.expression(),value,begin,flow);
        }
        int closeResource(ResourceStatementContext r,String value,String scope,String previous,int target,Flow outer,boolean failed)throws CompilationException {
            String pending=ref(field("अपवाद"));String shield=ref(field("तार्किक"));
            int finish=action(arg+".आंतरिकआवरण="+shield+"; यदि("+pending+"!=रिक्त){"+ref(error)+"="+pending+";"+go(outer.failed)+"}",target,outer,r.start);
            int closeFailure=action(arg+".आंतरिकआवरण="+shield+";",outer.failed,outer,r.start);
            int close;
            if(r.ASYNC()!=null){String task=ref(field(standard("कार्यआधार")));int resume=action(task+".आंतरिकपरिणामजाँचो();",finish,new Flow(closeFailure,0,0,0,null),r.start);close=state(task+"="+value+".बंदकरो();"+ref(pc)+"="+resume+";"+task+".आंतरिकप्रतीक्षा("+arg+");लौटाओ;",closeFailure,r.start);}
            else close=action(value+".बंदकरो();",finish,new Flow(closeFailure,0,0,0,null),r.start);
            int prepare=action(arg+".आंतरिकसीमा="+previous+";"+shield+"="+arg+".आंतरिकआवरण;"+arg+".आंतरिकआवरण=सत्य;",close,outer,r.start);
            int drainFailed=action("यदि("+pending+"==रिक्त){"+pending+"="+ref(error)+";}",prepare,outer,r.start);
            int drained=action(scope+".आंतरिकपरिणामजाँचो();",prepare,new Flow(drainFailed,0,0,0,null),r.start);
            return state(pending+"="+(failed?ref(error):"रिक्त")+";"+(failed?scope+".आंतरिकचिह्न.निरस्तकरो();":"")+scope+".आंतरिकसफल();"+ref(pc)+"="+drained+";"+scope+".आंतरिकप्रतीक्षा("+arg+");लौटाओ;",outer.failed,r.start);
        }
        int each(ForeachStatementContext loop,int next,Flow flow)throws CompilationException {
            String collection=ref(temp(loop.expression())),index=ref(field("पूर्णांक")),version=ref(field("पूर्णांक"));
            int head=state("",flow.failed,loop.start),advance=action(index+"="+index+"+१;",head,flow,loop.start);
            int body=block(loop.block(),advance,new Flow(flow.failed,flow.returned,next,advance,flow.caught));
            StringBuilder bind=new StringBuilder();for(int i=0;i<loop.parameter().size();i++)bind.append(ref(binding(analysis.bindings.get(loop.parameter(i))))).append('=').append(collection).append(i==0?".आंतरिककुंजी(":".आंतरिकमान(").append(index).append(");");
            int items=action(bind.toString(),body,flow,loop.start);
            states.set(head,new State("यदि("+collection+".आंतरिकजाँच("+version+","+index+")){"+go(items)+"}"+go(next),flow.failed,loop.start));
            int init=action(index+"=०;"+version+"="+collection+".आंतरिकसंस्करण();",head,flow,loop.start);
            return expression(loop.expression(),collection,init,flow);
        }
        String factory() {
            String original=tree.IDENTIFIER().getText();if(original.equals("मुख्य")){original=fresh();root=original;}
            var b=new StringBuilder("फलन ").append(original).append('(').append(tree.parameters()==null?"":plain(tree.parameters())).append(")->").append(resultType).append("{ स्थिर ").append(arg).append(':').append(name).append("=नया ").append(name).append("();");
            if(owner!=null&&!shared)b.append(ref(receiver)).append("=यह;");
            if(tree.parameters()!=null)for(var p:tree.parameters().parameter())b.append(ref(binding(analysis.bindings.get(p)))).append('=').append(p.IDENTIFIER().getText()).append(';');
            b.append(ref(pc)).append('=').append(entry).append(';').append(standard("कार्यतंत्र")).append(".आरम्भ(").append(arg).append("); लौटाओ ").append(arg).append(";}");return b.toString();
        }
        void emitHelper() {
            append((owner==null?"":"सार्वजनिक "+(shared?"साझा ":""))+"फलन "+helper+"("+arg+":"+name+")->शून्य { जबतक(सत्य){",tree.IDENTIFIER().getSymbol());
            append(standard("कार्यतंत्र")+".मालिकरखो("+arg+".आंतरिकसीमा,"+arg+".आंतरिकआवरण);",tree.start);
            for(int i=0;i<states.size();i++){var s=states.get(i);var b=new StringBuilder("यदि(").append(ref(pc)).append("==").append(i).append("){");if(s.failure>=0)b.append("प्रयास{");
                for(var f:clearAt.get(i))b.append(ref(f)).append('=').append(fields.get(f).equals("पाठ")?"\"\"":"रिक्त").append(';');
                b.append(s.code);if(s.failure>=0)b.append("}पकड़ो(दोष:अपवाद){").append(ref(error)).append("=दोष;").append(go(s.failure)).append('}');b.append('}');append(b.toString(),s.origin);}
            append("फेंको नया अपवाद(\"अमान्य कार्यचरण।\"); }}",tree.stop);
        }
        String frameClass() {
            var b=new StringBuilder("वर्ग ").append(name).append(" विस्तार ").append(resultType).append(" {");fields.forEach((n,t)->b.append("सार्वजनिक मान ").append(n).append(':').append(t).append(';'));
            b.append("सार्वजनिक अधिलेखन फलन आगे()->शून्य { ");if(owner!=null)b.append(shared?owner.IDENTIFIER().getText():"यह."+receiver).append('.');b.append(helper).append("(यह); }");
            b.append("सार्वजनिक अधिलेखन फलन आंतरिकमुक्तकरो()->शून्य {");
            fields.forEach((n,t)->{if(!scalars.contains(t))b.append("यह.").append(n).append('=').append(t.equals("पाठ")?"\"\"":"रिक्त").append(';');});
            return b.append("} }").toString();
        }
    }
    private static String plain(ParseTree n){if(n instanceof TerminalNode)return n.getText();if(n instanceof ShiftOperatorContext)return n.getText();var b=new StringBuilder();for(int i=0;i<n.getChildCount();i++)b.append(plain(n.getChild(i))).append(' ');return b.toString();}
}
