// SPDX-License-Identifier: Apache-2.0
package dev.bhash.backend.llvm;

import dev.bhash.backend.CoreProgram;
import dev.bhash.backend.CoreProgram.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Direct LLVM IR generation. All Bhash values and branches are explicit. */
public final class LlvmEmitter {
    private final StringBuilder globals=new StringBuilder(), functions=new StringBuilder();
    private final Map<String,String> strings=new LinkedHashMap<>();
    private StringBuilder code;
    private int next;
    private String label;
    private boolean terminated;
    private final Deque<String[]> loops=new ArrayDeque<>();
    private final Map<Expression,Integer> roots=new IdentityHashMap<>();
    private final Map<Binding,Integer> bindingRoots=new LinkedHashMap<>();
    private final Map<Binding,Boolean> bindings=new LinkedHashMap<>();
    private int rootCount;
    private final Map<Expression,Value> values=new IdentityHashMap<>();
    private record Value(Type type,String ir) { String argument(){return llvm(type)+" "+ir;} }
    private static String llvm(Type t) { return switch(t){case INT->"i64";case BOOL->"i1";case TEXT->"ptr";case VOID->"void";}; }
    private String temporary(){return "%x"+next++;}
    private String freshLabel(){return "l"+next++;}
    private void line(String s){code.append("  ").append(s).append('\n');}
    private void block(String name){code.append(name).append(":\n");label=name;terminated=false;}
    private void jump(String name){line("br label %"+name);terminated=true;}
    private Value instruction(Type type,String text){String n=temporary();line(n+" = "+text);return new Value(type,n);}
    private String text(String value) {
        return strings.computeIfAbsent(value,s->{
            byte[] bytes=s.getBytes(StandardCharsets.UTF_8); String name="@text"+strings.size();
            globals.append(name).append(" = private constant { i64, [").append(bytes.length)
                .append(" x i8] } { i64 ").append(bytes.length).append(", [").append(bytes.length).append(" x i8] c\"");
            for(byte b:bytes)globals.append(String.format(Locale.ROOT,"\\%02X",b&255));
            globals.append("\" }, align 8\n");return name;
        });
    }
    public static String emit(CoreProgram program){return emit(program, "");}
    public static String emit(CoreProgram program,String notices){return new LlvmEmitter().program(program,notices);}
    private String program(CoreProgram program,String notices){
        for(var f:program.functions())function(f);
        String license=text(notices);
        return "; Bhash native: direct LLVM IR, internal ABI v1\n"+
            "target triple = \"x86_64-unknown-linux-gnu\"\n%RootFrame = type { ptr, ptr, i64 }\n"+
            globals+"\ndeclare void @bh_roots_push(ptr, ptr, i64)\ndeclare void @bh_roots_pop(ptr)\n"+
            "declare i32 @bh_print_licenses_if_requested(i32, ptr, ptr)\ndeclare void @bh_shutdown()\ndeclare void @bh_print_text(ptr)\ndeclare void @bh_print_int(i64)\n"+
            "declare void @bh_print_bool(i32)\ndeclare void @bh_print_space()\ndeclare void @bh_print_end()\n"+
            "declare i32 @bh_text_equal(ptr, ptr)\ndeclare i64 @bh_divide(i64, i64, ptr)\n"+
            "declare i64 @bh_remainder(i64, i64, ptr)\n\n"+functions+
            "define i32 @main(i32 %argc, ptr %argv) {\nentry:\n  %shown = call i32 @bh_print_licenses_if_requested(i32 %argc, ptr %argv, ptr "+license+")\n  %skip = icmp ne i32 %shown, 0\n  br i1 %skip, label %done, label %application\napplication:\n  call void @bfn"+program.entry()+"()\n  call void @bh_shutdown()\n  br label %done\ndone:\n  ret i32 0\n}\n";
    }
    private void scan(Expression e){
        if(e==null)return;
        if(e.type()==Type.TEXT && !roots.containsKey(e))roots.put(e,rootCount++);
        if(e instanceof Read r)register(r.binding());
        else if(e instanceof Unary u)scan(u.value());
        else if(e instanceof Binary b){scan(b.left());scan(b.right());}
        else if(e instanceof Call c)c.arguments().forEach(this::scan);
    }
    private void register(Binding b){
        if(bindings.putIfAbsent(b,true)==null && b.type()==Type.TEXT)bindingRoots.put(b,rootCount++);
    }
    private void scan(Statement s){
        if(s==null)return;
        if(s instanceof Block b)b.statements().forEach(this::scan);
        else if(s instanceof Assign a){register(a.binding());scan(a.value());}
        else if(s instanceof Branch b){scan(b.condition());scan(b.yes());scan(b.no());}
        else if(s instanceof Loop l){scan(l.condition());scan(l.body());}
        else if(s instanceof Return r)scan(r.value());
        else if(s instanceof Print p)p.arguments().forEach(this::scan);
        else if(s instanceof Evaluate e)scan(e.value());
    }
    private String rootAddress(int slot){
        String address=temporary();line(address+" = getelementptr ["+rootCount+" x ptr], ptr %roots, i64 0, i64 "+slot);return address;
    }
    private String address(Binding b){return "%b"+b.id();}
    private void function(Function f){
        code=new StringBuilder(); values.clear(); roots.clear(); bindingRoots.clear(); bindings.clear();rootCount=0;next=0;loops.clear();
        f.parameters().forEach(this::register);scan(f.body());
        code.append("define internal ").append(llvm(f.result())).append(" @bfn").append(f.id()).append('(');
        var arguments=new ArrayList<String>();
        for(int i=0;i<f.parameters().size();i++)arguments.add(llvm(f.parameters().get(i).type())+" %arg"+i);
        code.append(String.join(", ",arguments)).append(") {\n");block("entry");
        line("%frame = alloca %RootFrame, align 8");
        line("%roots = alloca ["+rootCount+" x ptr], align 8");
        line("store ["+rootCount+" x ptr] zeroinitializer, ptr %roots");
        for(var b:bindings.keySet()) {
            if(b.type()==Type.TEXT)line(address(b)+" = getelementptr ["+rootCount+" x ptr], ptr %roots, i64 0, i64 "+bindingRoots.get(b));
            else line(address(b)+" = alloca "+llvm(b.type()));
        }
        for(int i=0;i<f.parameters().size();i++) {
            var b=f.parameters().get(i);line("store "+llvm(b.type())+" %arg"+i+", ptr "+address(b));
        }
        line("call void @bh_roots_push(ptr %frame, ptr %roots, i64 "+rootCount+")");
        statement(f.body());
        if(!terminated){line("call void @bh_roots_pop(ptr %frame)");line("ret void");}
        code.append("}\n\n");functions.append(code);
    }
    private Value expression(Expression e){
        Value result;
        if(e instanceof Constant c)result=new Value(c.type(),c.type()==Type.TEXT?text((String)c.value()):c.type()==Type.BOOL?((Boolean)c.value()?"true":"false"):c.value().toString());
        else if(e instanceof Read r)result=instruction(r.type(),"load "+llvm(r.type())+", ptr "+address(r.binding()));
        else if(e instanceof Unary u){
            Value v=expression(u.value());
            result=switch(u.operator()){
                case "+"->v;
                case "-"->instruction(Type.INT,"sub i64 0, "+v.ir());
                case "!"->instruction(Type.BOOL,"xor i1 "+v.ir()+", true");
                default->throw new IllegalStateException("Unchecked unary operator");};
        } else if(e instanceof Call c){
            for(var a:c.arguments())expression(a);
            var args=new ArrayList<String>();
            for(var a:c.arguments())args.add(reload(a).argument());
            String call="call "+llvm(c.type())+" @bfn"+c.function()+"("+String.join(", ",args)+")";
            if(c.type()==Type.VOID){line(call);result=new Value(Type.VOID,"");}
            else result=instruction(c.type(),call);
        } else if(e instanceof Binary b)result=binary(b);
        else throw new IllegalStateException("Unknown core expression");
        if(e.type()==Type.TEXT)line("store ptr "+result.ir()+", ptr "+rootAddress(roots.get(e)));
        values.put(e,result);
        return result;
    }
    private Value reload(Expression e){
        return e.type()==Type.TEXT?instruction(Type.TEXT,"load ptr, ptr "+rootAddress(roots.get(e))):values.get(e);
    }
    private Value binary(Binary b){
        Value left=expression(b.left());String op=b.operator();
        if(op.equals("&&") || op.equals("||")){
            String from=label,rightLabel=freshLabel(),merge=freshLabel();boolean and=op.equals("&&");
            line("br i1 "+left.ir()+", label %"+(and?rightLabel:merge)+", label %"+(and?merge:rightLabel));
            block(rightLabel);Value right=expression(b.right());String rightEnd=label;jump(merge);block(merge);
            return instruction(Type.BOOL,"phi i1 [ "+(and?"false":"true")+", %"+from+" ], [ "+right.ir()+", %"+rightEnd+" ]");
        }
        Value right=expression(b.right());
        if(op.equals("/") || op.equals("%"))return instruction(Type.INT,"call i64 @"+(op.equals("/")?"bh_divide":"bh_remainder")+
            "(i64 "+left.ir()+", i64 "+right.ir()+", ptr "+text(b.source().toString())+")");
        if(op.equals("+") || op.equals("-") || op.equals("*"))return instruction(Type.INT,
            switch(op){case "+"->"add";case "-"->"sub";default->"mul";}+" i64 "+left.ir()+", "+right.ir());
        if(left.type()==Type.TEXT){
            left=reload(b.left());right=reload(b.right());
            Value same=instruction(Type.INT,"call i32 @bh_text_equal(ptr "+left.ir()+", ptr "+right.ir()+")");
            return instruction(Type.BOOL,"icmp "+(op.equals("==")?"ne":"eq")+" i32 "+same.ir()+", 0");
        }
        String predicate=switch(op){case "=="->"eq";case "!="->"ne";case "<"->"slt";case "<="->"sle";case ">"->"sgt";case ">="->"sge";default->throw new IllegalStateException("Unchecked binary operator");};
        return instruction(Type.BOOL,"icmp "+predicate+" "+left.argument()+", "+right.ir());
    }
    private void statement(Statement s){
        if(s==null || terminated)return;
        if(s instanceof Block b){for(var x:b.statements())statement(x);}
        else if(s instanceof Assign a){Value v=expression(a.value());line("store "+v.argument()+", ptr "+address(a.binding()));}
        else if(s instanceof Return r){
            Value v=r.value()==null?null:expression(r.value());
            line("call void @bh_roots_pop(ptr %frame)");line(v==null?"ret void":"ret "+v.argument());terminated=true;
        } else if(s instanceof Evaluate e)expression(e.value());
        else if(s instanceof Print p){
            var values=new ArrayList<Value>();for(var a:p.arguments())values.add(expression(a));
            for(int i=0;i<values.size();i++){
                if(i>0)line("call void @bh_print_space()");Value v=values.get(i);
                if(v.type()==Type.TEXT)v=reload(p.arguments().get(i));
                if(v.type()==Type.BOOL){Value n=instruction(Type.INT,"zext i1 "+v.ir()+" to i32");line("call void @bh_print_bool(i32 "+n.ir()+")");}
                else line("call void @"+(v.type()==Type.TEXT?"bh_print_text":"bh_print_int")+"("+v.argument()+")");
            }
            line("call void @bh_print_end()");
        } else if(s instanceof Jump j)jump(loops.peek()[j.breaking()?1:0]);
        else if(s instanceof Loop l){
            String condition=freshLabel(),body=freshLabel(),exit=freshLabel();jump(condition);block(condition);
            Value v=expression(l.condition());line("br i1 "+v.ir()+", label %"+body+", label %"+exit);
            block(body);loops.push(new String[]{condition,exit});statement(l.body());loops.pop();
            if(!terminated)jump(condition);block(exit);
        } else if(s instanceof Branch b){
            Value v=expression(b.condition());String yes=freshLabel(),no=freshLabel(),merge=freshLabel();
            line("br i1 "+v.ir()+", label %"+yes+", label %"+no);
            block(yes);statement(b.yes());boolean yesEnds=terminated;if(!yesEnds)jump(merge);
            block(no);statement(b.no());boolean noEnds=terminated;if(!noEnds)jump(merge);
            if(yesEnds && noEnds)terminated=true;else block(merge);
        }
    }
}
