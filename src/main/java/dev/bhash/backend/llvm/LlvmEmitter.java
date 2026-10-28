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
    private record LoopTarget(String condition,String exit,int scopeDepth) {}
    private final Deque<LoopTarget> loops=new ArrayDeque<>();
    private final Deque<List<Binding>> scopes=new ArrayDeque<>();
    private final Map<Expression,Integer> roots=new IdentityHashMap<>();
    private final Map<Binding,Integer> bindingRoots=new LinkedHashMap<>();
    private final Map<Binding,Boolean> bindings=new LinkedHashMap<>();
    private int rootCount;
    private final Map<Integer,Integer> objectSizes=new HashMap<>();
    private final Map<Expression,Value> values=new IdentityHashMap<>();
    private record Value(Type type,String ir) { String argument(){return llvm(type)+" "+ir;} }
    private static String llvm(Type t) { return switch(t){case INT->"i64";case BOOL->"i1";case TEXT,OBJECT,ANY->"ptr";case VOID->"void";}; }
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
        boxMetadata();
        for(var c:program.classes()){
            objectSizes.put(c.id(),8+c.fields().size()*8);
            globals.append("@vtable").append(c.id()).append(" = private constant [").append(c.virtualMethods().size()).append(" x ptr] [")
                .append(String.join(", ",c.virtualMethods().stream().map(id->"ptr @bfn"+id).toList())).append("], align 8\n");
            globals.append("@itable").append(c.id()).append(" = private constant [").append(c.interfaceMethods().size()).append(" x ptr] [")
                .append(String.join(", ",c.interfaceMethods().stream().map(id->id<0?"ptr null":"ptr @bfn"+id).toList())).append("], align 8\n");
            globals.append("@types").append(c.id()).append(" = private constant [").append(c.assignableTypes().size()).append(" x i64] [")
                .append(String.join(", ",c.assignableTypes().stream().map(id->"i64 "+id).toList())).append("]\n");
            globals.append("@typeinfo").append(c.id()).append(" = private constant %ClassInfo { ptr @vtable").append(c.id())
                .append(", ptr @itable").append(c.id()).append(", i64 ").append(c.assignableTypes().size()).append(", ptr @types").append(c.id()).append(" }, align 8\n");
            var refs=c.fields().stream().filter(f->f.type().reference()).toList();
            String offsets="@refs"+c.id();
            globals.append(offsets).append(" = private constant [").append(refs.size()).append(" x i64] [")
                .append(String.join(", ",refs.stream().map(f->"i64 "+f.offset()).toList())).append("]\n");
            byte[] nameBytes=(c.name()+"\0").getBytes(StandardCharsets.UTF_8);
            String name="@classname"+c.id();
            globals.append(name).append(" = private constant [").append(nameBytes.length).append(" x i8] c\"");
            for(byte b:nameBytes)globals.append(String.format(Locale.ROOT,"\\%02X",b&255));
            globals.append("\"\n");
            globals.append("@class").append(c.id()).append(" = private constant { ptr, i64, i64, ptr } { ptr ").append(name)
                .append(", i64 ").append(8+c.fields().size()*8).append(", i64 ").append(refs.size()).append(", ptr ").append(offsets).append(" }, align 8\n");
        }
        for(var f:program.functions())function(f);
        String license=text(notices);
        return "; Bhash native: direct LLVM IR, internal ABI v4\n"+
            "target triple = \"x86_64-unknown-linux-gnu\"\n%RootFrame = type { ptr, ptr, i64 }\n%ClassInfo = type { ptr, ptr, i64, ptr }\n"+
            globals+"\ndeclare void @bh_roots_push(ptr, ptr, i64)\ndeclare void @bh_roots_pop(ptr)\n"+
            "declare ptr @bh_alloc(ptr, i64)\ndeclare ptr @bh_instance(ptr, ptr)\ndeclare void @bh_reference_store(ptr, i64, ptr)\n"+
            "declare i32 @bh_print_licenses_if_requested(i32, ptr, ptr)\ndeclare void @bh_runtime_init()\ndeclare void @bh_shutdown()\ndeclare void @bh_print_text(ptr)\ndeclare void @bh_print_int(i64)\n"+
            "declare void @bh_print_bool(i32)\ndeclare void @bh_print_space()\ndeclare void @bh_print_end()\n"+
            "declare i32 @bh_text_equal(ptr, ptr)\ndeclare i64 @bh_divide(i64, i64, ptr)\n"+
            "declare i64 @bh_remainder(i64, i64, ptr)\n"+
            textDeclarations()+"\n"+typeTestHelper()+equalityHelper()+functions+
            "define i32 @main(i32 %argc, ptr %argv) {\nentry:\n  call void @bh_runtime_init()\n  %shown = call i32 @bh_print_licenses_if_requested(i32 %argc, ptr %argv, ptr "+license+")\n  %skip = icmp ne i32 %shown, 0\n  br i1 %skip, label %done, label %application\napplication:\n  call void @bfn"+program.entry()+"()\n  call void @bh_shutdown()\n  br label %done\ndone:\n  ret i32 0\n}\n";
    }
    private static String textDeclarations(){
        return "declare ptr @bh_text_concat(ptr, ptr, ptr)\n"+
            "declare i64 @bh_text_length(ptr, ptr)\ndeclare ptr @bh_text_slice(ptr, i64, i64, ptr)\n"+
            "declare i64 @bh_text_find(ptr, ptr, ptr)\ndeclare i32 @bh_text_contains(ptr, ptr, ptr)\n"+
            "declare i32 @bh_text_starts(ptr, ptr, ptr)\ndeclare i32 @bh_text_ends(ptr, ptr, ptr)\n"+
            "declare ptr @bh_text_replace(ptr, ptr, ptr, ptr)\ndeclare ptr @bh_text_trim(ptr, ptr)\n"+
            "declare ptr @bh_text_upper(ptr, ptr)\ndeclare ptr @bh_text_lower(ptr, ptr)\n"+
            "declare ptr @bh_text_repeat(ptr, i64, ptr)\ndeclare ptr @bh_text_devanagari(ptr, ptr)\n"+
            "declare ptr @bh_text_integer(i64, ptr)\ndeclare i64 @bh_text_parse_integer(ptr, ptr)\n"+
            "declare void @bh_text_fail(ptr, ptr)\n";
    }
    private static String typeTestHelper(){
        return """
            define internal i1 @bhash_is_type(ptr %object, i64 %wanted) {
            entry:
              %empty = icmp eq ptr %object, null
              br i1 %empty, label %no, label %lookup
            lookup:
              %info = load ptr, ptr %object
              %count_address = getelementptr %ClassInfo, ptr %info, i32 0, i32 2
              %count = load i64, ptr %count_address
              %types_address = getelementptr %ClassInfo, ptr %info, i32 0, i32 3
              %types = load ptr, ptr %types_address
              br label %loop
            loop:
              %index = phi i64 [ 0, %lookup ], [ %next, %advance ]
              %more = icmp ult i64 %index, %count
              br i1 %more, label %check, label %no
            check:
              %address = getelementptr i64, ptr %types, i64 %index
              %actual = load i64, ptr %address
              %same = icmp eq i64 %actual, %wanted
              br i1 %same, label %yes, label %advance
            advance:
              %next = add i64 %index, 1
              br label %loop
            yes:
              ret i1 true
            no:
              ret i1 false
            }
            """;
    }
    /** Negative membership IDs are reserved for built-in boxed types and सर्ववस्तु. */
    private void boxMetadata(){
        for(int kind=1;kind<=3;kind++){
            globals.append("@box_types").append(kind).append(" = private constant [2 x i64] [i64 -").append(kind).append(", i64 -4]\n");
            globals.append("@box_info").append(kind).append(" = private constant %ClassInfo { ptr null, ptr null, i64 2, ptr @box_types").append(kind).append(" }, align 8\n");
            globals.append("@box_refs").append(kind).append(" = private constant [").append(kind==3?1:0).append(" x i64] [").append(kind==3?"i64 8":"").append("]\n");
            // The collector uses the name only for diagnostics, so share a static UTF-8 name.
            globals.append("@box_gc").append(kind).append(" = private constant { ptr, i64, i64, ptr } { ptr @box_name, i64 16, i64 ")
                .append(kind==3?1:0).append(", ptr @box_refs").append(kind).append(" }, align 8\n");
        }
        globals.append("@box_name = private constant [4 x i8] c\"box\\00\"\n");
    }
    private static String equalityHelper(){
        return """
            define internal i1 @bhash_any_equal(ptr %a, ptr %b) {
            entry:
              %identical = icmp eq ptr %a, %b
              br i1 %identical, label %yes, label %nulls
            nulls:
              %anull = icmp eq ptr %a, null
              %bnull = icmp eq ptr %b, null
              %empty = or i1 %anull, %bnull
              br i1 %empty, label %no, label %metadata
            metadata:
              %ai = load ptr, ptr %a
              %bi = load ptr, ptr %b
              %samekind = icmp eq ptr %ai, %bi
              br i1 %samekind, label %kind, label %no
            kind:
              %integer = icmp eq ptr %ai, @box_info1
              %boolean = icmp eq ptr %ai, @box_info2
              %scalar = or i1 %integer, %boolean
              br i1 %scalar, label %numbers, label %textkind
            numbers:
              %ap = getelementptr i8, ptr %a, i64 8
              %bp = getelementptr i8, ptr %b, i64 8
              %av = load i64, ptr %ap
              %bv = load i64, ptr %bp
              %equal = icmp eq i64 %av, %bv
              ret i1 %equal
            textkind:
              %text = icmp eq ptr %ai, @box_info3
              br i1 %text, label %texts, label %no
            texts:
              %atp = getelementptr i8, ptr %a, i64 8
              %btp = getelementptr i8, ptr %b, i64 8
              %at = load ptr, ptr %atp
              %bt = load ptr, ptr %btp
              %same = call i32 @bh_text_equal(ptr %at, ptr %bt)
              %result = icmp ne i32 %same, 0
              ret i1 %result
            yes:
              ret i1 true
            no:
              ret i1 false
            }
            """;
    }
    private Value box(Box b){
        Value input=expression(b.value());
        if(input.type()==Type.OBJECT || input.type()==Type.ANY)return new Value(Type.ANY,input.ir());
        int kind=switch(input.type()){case INT->1;case BOOL->2;case TEXT->3;default->throw new IllegalStateException("Unchecked box type");};
        Value result=instruction(Type.ANY,"call ptr @bh_alloc(ptr @box_gc"+kind+", i64 16)");
        line("store ptr @box_info"+kind+", ptr "+result.ir());
        // Publish before the reference-store barrier; reload rooted text after allocation.
        line("store ptr "+result.ir()+", ptr "+rootAddress(roots.get(b)));
        input=reload(b.value());
        if(kind==3)line("call void @bh_reference_store(ptr "+result.ir()+", i64 8, ptr "+input.ir()+")");
        else {
            if(kind==2)input=instruction(Type.INT,"zext i1 "+input.ir()+" to i64");
            String payload=temporary();line(payload+" = getelementptr i8, ptr "+result.ir()+", i64 8");
            line("store i64 "+input.ir()+", ptr "+payload);
        }
        return reload(b);
    }
    private Value typeOperation(TypeOperation t){
        Value value=expression(t.value());
        boolean dynamic=value.type()==Type.OBJECT || value.type()==Type.ANY;
        Value matches=dynamic?instruction(Type.BOOL,"call i1 @bhash_is_type(ptr "+value.ir()+", i64 "+t.targetId()+")")
            :new Value(Type.BOOL,value.type()==t.targetType() || t.targetType()==Type.ANY?"true":"false");
        if(t.action()==TypeAction.TEST)return matches;
        String pointer=dynamic?value.ir():"null";
        if(t.action()==TypeAction.SAFE)
            return instruction(t.type(),"select i1 "+matches.ir()+", ptr "+pointer+", ptr null");
        if(t.targetType()==Type.TEXT && value.type()==Type.BOOL)
            return instruction(Type.TEXT,"select i1 "+value.ir()+", ptr "+text("सत्य")+", ptr "+text("असत्य"));
        Value allowed=matches;
        if(dynamic && (t.targetType()==Type.OBJECT || t.targetType()==Type.ANY)){
            Value empty=instruction(Type.BOOL,"icmp eq ptr "+value.ir()+", null");
            allowed=instruction(Type.BOOL,"or i1 "+matches.ir()+", "+empty.ir());
        }
        String pass=freshLabel(),fail=freshLabel();
        line("br i1 "+allowed.ir()+", label %"+pass+", label %"+fail);
        block(fail);
        line("call void @bh_text_fail(ptr "+text("रनटाइम प्रकार परिवर्तन असफल हुआ।")+", ptr "+text(t.source().toString())+")");
        line("unreachable");terminated=true;
        block(pass);
        if(t.targetType()==Type.OBJECT || t.targetType()==Type.ANY)return new Value(t.targetType(),pointer);
        if(value.type()==Type.ANY){
            String payload=temporary();line(payload+" = getelementptr i8, ptr "+pointer+", i64 8");
            if(t.targetType()==Type.BOOL){
                Value integer=instruction(Type.INT,"load i64, ptr "+payload);
                return instruction(Type.BOOL,"icmp ne i64 "+integer.ir()+", 0");
            }
            return instruction(t.targetType(),"load "+llvm(t.targetType())+", ptr "+payload);
        }
        return value.type()==t.targetType()?value:new Value(t.targetType(),t.targetType()==Type.BOOL?"false":t.targetType()==Type.TEXT?text(""):"0");
    }
    private void scan(Expression e){
        if(e==null)return;
        if(e.type().reference() && !roots.containsKey(e))roots.put(e,rootCount++);
        if(e instanceof Read r)register(r.binding());
        else if(e instanceof Unary u)scan(u.value());
        else if(e instanceof Binary b){scan(b.left());scan(b.right());}
        else if(e instanceof Call c)c.arguments().forEach(this::scan);
        else if(e instanceof TextCall c)c.arguments().forEach(this::scan);
        else if(e instanceof FieldRead f)scan(f.receiver());
        else if(e instanceof TypeOperation t)scan(t.value());
        else if(e instanceof Box b)scan(b.value());
    }
    private void register(Binding b){
        if(bindings.putIfAbsent(b,true)==null && b.type().reference())bindingRoots.put(b,rootCount++);
    }
    private void scan(Statement s){
        if(s==null)return;
        if(s instanceof Block b)b.statements().forEach(this::scan);
        else if(s instanceof Assign a){register(a.binding());scan(a.value());}
        else if(s instanceof FieldWrite f){scan(f.destination().receiver());scan(f.value());}
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
        code=new StringBuilder(); values.clear(); roots.clear(); bindingRoots.clear(); bindings.clear();rootCount=0;next=0;loops.clear();scopes.clear();
        f.parameters().forEach(this::register);scan(f.body());
        code.append("define internal ").append(llvm(f.result())).append(" @bfn").append(f.id()).append('(');
        var arguments=new ArrayList<String>();
        for(int i=0;i<f.parameters().size();i++)arguments.add(llvm(f.parameters().get(i).type())+" %arg"+i);
        code.append(String.join(", ",arguments)).append(") {\n");block("entry");
        line("%frame = alloca %RootFrame, align 8");
        line("%roots = alloca ["+rootCount+" x ptr], align 8");
        line("store ["+rootCount+" x ptr] zeroinitializer, ptr %roots");
        for(var b:bindings.keySet()) {
            if(b.type().reference())line(address(b)+" = getelementptr ["+rootCount+" x ptr], ptr %roots, i64 0, i64 "+bindingRoots.get(b));
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
        if(e instanceof Constant c)result=new Value(c.type(),c.type()==Type.TEXT?text((String)c.value()):c.type()==Type.BOOL?((Boolean)c.value()?"true":"false"):(c.type()==Type.OBJECT || c.type()==Type.ANY)?"null":c.value().toString());
        else if(e instanceof Allocate a){
            result=instruction(Type.OBJECT,"call ptr @bh_alloc(ptr @class"+a.objectType()+", i64 "+objectSizes.get(a.objectType())+")");
            // The immutable type-metadata pointer is not a managed reference or safepoint.
            line("store ptr @typeinfo"+a.objectType()+", ptr "+result.ir());
        }
        else if(e instanceof FieldRead f){
            Value receiver=expression(f.receiver());
            receiver=instance(receiver,f.source());
            String pointer=temporary();line(pointer+" = getelementptr i8, ptr "+receiver.ir()+", i64 "+f.field().offset());
            result=instruction(f.type(),"load "+llvm(f.type())+", ptr "+pointer);
        }
        else if(e instanceof Read r)result=instruction(r.type(),"load "+llvm(r.type())+", ptr "+address(r.binding()));
        else if(e instanceof Unary u){
            Value v=expression(u.value());
            result=switch(u.operator()){
                case "+"->v;
                case "-"->instruction(Type.INT,"sub i64 0, "+v.ir());
                case "!"->instruction(Type.BOOL,"xor i1 "+v.ir()+", true");
                default->throw new IllegalStateException("Unchecked unary operator");};
        } else if(e instanceof Call c){
            for(int i=0;i<c.arguments().size();i++){
                Value value=expression(c.arguments().get(i));
                if(i==0 && c.instance())instance(value,c.source());
            }
            var args=new ArrayList<String>();
            for(var a:c.arguments())args.add(reload(a).argument());
            String target="@bfn"+c.function();
            if(c.virtualSlot()>=0 || c.interfaceSlot()>=0){
                Value receiver=reload(c.arguments().get(0));
                String info=temporary(),array=temporary(),table=temporary(),slot=temporary(),method=temporary();
                line(info+" = load ptr, ptr "+receiver.ir());
                line(array+" = getelementptr %ClassInfo, ptr "+info+", i32 0, i32 "+(c.interfaceSlot()>=0?1:0));
                line(table+" = load ptr, ptr "+array);
                line(slot+" = getelementptr ptr, ptr "+table+", i64 "+(c.interfaceSlot()>=0?c.interfaceSlot():c.virtualSlot()));
                line(method+" = load ptr, ptr "+slot);
                target=method;
            }
            String call="call "+llvm(c.type())+" "+target+"("+String.join(", ",args)+")";
            if(c.type()==Type.VOID){line(call);result=new Value(Type.VOID,"");}
            else result=instruction(c.type(),call);
        } else if(e instanceof TextCall c) {
            for(var a:c.arguments())expression(a);
            var args=new ArrayList<String>();
            for(var a:c.arguments())args.add(reload(a).argument());
            args.add("ptr "+text(c.source().toString()));
            String resultType=c.type()==Type.BOOL?"i32":llvm(c.type());
            String call="call "+resultType+" @bh_text_"+c.operation().name().toLowerCase(Locale.ROOT)+"("+String.join(", ",args)+")";
            if(c.type()==Type.VOID){line(call);result=new Value(Type.VOID,"");}
            else if(c.type()==Type.BOOL){String value=temporary();line(value+" = "+call);result=instruction(Type.BOOL,"icmp ne i32 "+value+", 0");}
            else result=instruction(c.type(),call);
        } else if(e instanceof Box b)result=box(b);
        else if(e instanceof TypeOperation t)result=typeOperation(t);
        else if(e instanceof Binary b)result=binary(b);
        else throw new IllegalStateException("Unknown core expression");
        if(e.type().reference())line("store ptr "+result.ir()+", ptr "+rootAddress(roots.get(e)));
        // Publish the result before releasing inputs: they may alias its storage.
        if(e instanceof Call c)c.arguments().forEach(this::release);
        else if(e instanceof TextCall c)c.arguments().forEach(this::release);
        else if(e instanceof Binary b){release(b.left());release(b.right());}
        else if(e instanceof Unary u)release(u.value());
        else if(e instanceof FieldRead f)release(f.receiver());
        else if(e instanceof TypeOperation t)release(t.value());
        else if(e instanceof Box b)release(b.value());
        values.put(e,result);
        return result;
    }
    private void release(Expression e){
        if(e!=null && e.type().reference())line("store ptr null, ptr "+rootAddress(roots.get(e)));
    }
    private void clearBindings(List<Binding> locals){
        for(var b:locals)if(b.type().reference())line("store ptr null, ptr "+address(b));
    }
    private void clearLoopScopes(int depth){
        int count=scopes.size()-depth;
        for(var scope:scopes){if(count--<=0)break;clearBindings(scope);}
    }
    private Value reload(Expression e){
        return e.type().reference()?instruction(e.type(),"load ptr, ptr "+rootAddress(roots.get(e))):values.get(e);
    }
    private Value instance(Value receiver,Source source){
        return instruction(Type.OBJECT,"call ptr @bh_instance(ptr "+receiver.ir()+", ptr "+text(source.toString())+")");
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
        if(op.equals("+") && left.type()==Type.TEXT){
            left=reload(b.left());right=reload(b.right());
            return instruction(Type.TEXT,"call ptr @bh_text_concat(ptr "+left.ir()+", ptr "+right.ir()+", ptr "+text(b.source().toString())+")");
        }
        if(op.equals("+") || op.equals("-") || op.equals("*"))return instruction(Type.INT,
            switch(op){case "+"->"add";case "-"->"sub";default->"mul";}+" i64 "+left.ir()+", "+right.ir());
        if(left.type()==Type.ANY){
            left=reload(b.left());right=reload(b.right());
            Value same=instruction(Type.BOOL,"call i1 @bhash_any_equal(ptr "+left.ir()+", ptr "+right.ir()+")");
            return op.equals("==")?same:instruction(Type.BOOL,"xor i1 "+same.ir()+", true");
        }
        if(left.type()==Type.TEXT){
            left=reload(b.left());right=reload(b.right());
            Value same=instruction(Type.INT,"call i32 @bh_text_equal(ptr "+left.ir()+", ptr "+right.ir()+")");
            return instruction(Type.BOOL,"icmp "+(op.equals("==")?"ne":"eq")+" i32 "+same.ir()+", 0");
        }
        if(left.type()==Type.OBJECT){left=reload(b.left());right=reload(b.right());}
        String predicate=switch(op){case "=="->"eq";case "!="->"ne";case "<"->"slt";case "<="->"sle";case ">"->"sgt";case ">="->"sge";default->throw new IllegalStateException("Unchecked binary operator");};
        return instruction(Type.BOOL,"icmp "+predicate+" "+left.argument()+", "+right.ir());
    }
    private void statement(Statement s){
        if(s==null || terminated)return;
        if(s instanceof Block b){
            scopes.push(b.locals());for(var x:b.statements())statement(x);
            if(!terminated)clearBindings(b.locals());scopes.pop();
        }
        else if(s instanceof Assign a){Value v=expression(a.value());line("store "+v.argument()+", ptr "+address(a.binding()));release(a.value());}
        else if(s instanceof FieldWrite f){
            var target=f.destination();
            instance(expression(target.receiver()),target.source());
            expression(f.value());
            Value owner=reload(target.receiver()),value=reload(f.value());
            if(target.type().reference())line("call void @bh_reference_store(ptr "+owner.ir()+", i64 "+target.field().offset()+", ptr "+value.ir()+")");
            else {
                String pointer=temporary();line(pointer+" = getelementptr i8, ptr "+owner.ir()+", i64 "+target.field().offset());
                line("store "+value.argument()+", ptr "+pointer);
            }
            release(target.receiver());release(f.value());
        }
        else if(s instanceof Return r){
            Value v=r.value()==null?null:expression(r.value());
            line("call void @bh_roots_pop(ptr %frame)");line(v==null?"ret void":"ret "+v.argument());terminated=true;
        } else if(s instanceof Evaluate e){expression(e.value());release(e.value());}
        else if(s instanceof Print p){
            var values=new ArrayList<Value>();for(var a:p.arguments())values.add(expression(a));
            for(int i=0;i<values.size();i++){
                if(i>0)line("call void @bh_print_space()");Value v=values.get(i);
                if(v.type()==Type.TEXT)v=reload(p.arguments().get(i));
                if(v.type()==Type.BOOL){Value n=instruction(Type.INT,"zext i1 "+v.ir()+" to i32");line("call void @bh_print_bool(i32 "+n.ir()+")");}
                else line("call void @"+(v.type()==Type.TEXT?"bh_print_text":"bh_print_int")+"("+v.argument()+")");
                release(p.arguments().get(i));
            }
            line("call void @bh_print_end()");
        } else if(s instanceof Jump j){
            var loop=loops.peek();clearLoopScopes(loop.scopeDepth());jump(j.breaking()?loop.exit():loop.condition());
        }
        else if(s instanceof Loop l){
            String condition=freshLabel(),body=freshLabel(),exit=freshLabel();jump(condition);block(condition);
            Value v=expression(l.condition());line("br i1 "+v.ir()+", label %"+body+", label %"+exit);
            block(body);loops.push(new LoopTarget(condition,exit,scopes.size()));statement(l.body());loops.pop();
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
