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
    // A null condition marks a selection: break is valid, continue searches an outer loop.
    private record ControlTarget(String condition,String exit,int scopeDepth,int id,String owner) {}
    private final Deque<ControlTarget> targets=new ArrayDeque<>();
    private final Deque<List<Binding>> scopes=new ArrayDeque<>();
    private final Map<Expression,Integer> roots=new IdentityHashMap<>();
    private final Map<Binding,Integer> bindingRoots=new LinkedHashMap<>();
    private final Map<Binding,Boolean> bindings=new LinkedHashMap<>();
    private int rootCount, returnRoot, nextTarget, nextCallback;
    private Type functionResult;
    private String owner;
    private boolean outlined, traced;
    private boolean debugEnabled;
    private NativeDebugInfo debug;
    private String buildInfo;
    private int debugScope=-1;
    private Source debugSource;
    private Function currentFunction;
    private final Map<NewCollection,Integer> buffers=new IdentityHashMap<>();
    private final Map<Integer,Integer> objectSizes=new HashMap<>();
    private final Map<Expression,Value> values=new IdentityHashMap<>();
    private record Value(Type type,String ir) { String argument(){return llvm(type)+" "+ir;} }
    private static String llvm(Type t) { return switch(t){case REAL->"double";case INT,BYTE,INT32->"i64";case BOOL->"i1";case TEXT,OBJECT,ANY->"ptr";case VOID->"void";}; }
    private static int valueKind(Type t){return switch(t){case INT->1;case BOOL->2;case TEXT->3;case OBJECT->4;case ANY->5;case REAL->6;case BYTE->7;case INT32->8;default->throw new IllegalArgumentException("Void field");};}
    private String temporary(){return "%x"+next++;}
    private String freshLabel(){return "l"+next++;}
    private void line(String s){code.append("  ").append(s);if(debug!=null && debugScope>=0 && debugSource!=null)code.append(", !dbg !").append(debug.location(debugScope,debugSource));code.append('\n');}
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
    public static String emit(CoreProgram program,String notices){return emit(program,notices,false);}
    public static String emit(CoreProgram program,String notices,boolean debugging){
        try{return emit(program,notices,debugging,NativeBuildInfo.describe(debugging,false,"development"));}
        catch(java.io.IOException | dev.bhash.backend.CompilationException error){throw new IllegalStateException(error);}
    }
    public static String emit(CoreProgram program,String notices,boolean debugging,String buildInfo){var emitter=new LlvmEmitter();emitter.debugEnabled=debugging;emitter.buildInfo=buildInfo;return emitter.program(program,notices);}
    private String program(CoreProgram program,String notices){
        if(debugEnabled){
            debug=new NativeDebugInfo(program.functions().get(0).source());
            byte[] schema=NativeDebugInfo.schema(program).getBytes(StandardCharsets.UTF_8);
            globals.append("@bhash_debug_schema = constant { i64, [").append(schema.length).append(" x i8] } { i64 ").append(schema.length).append(", [").append(schema.length).append(" x i8] c\"");
            for(byte b:schema)globals.append(String.format(Locale.ROOT,"\\%02X",b&255));
            globals.append("\" }, align 8\n");
        }
        boxMetadata();
        collectionMetadata(program.collections());
        String dataInit="";
        if(program.structuredTypes()!=null){
            var t=program.structuredTypes();
            globals.append("@data_info = private constant %DataInfo { ptr ").append(collectionInfo(t.texts()))
                .append(", ptr ").append(collectionInfo(t.values())).append(", ptr ").append(collectionInfo(t.object()))
                .append(", [4 x ptr] [ptr @box_gc1, ptr @box_gc2, ptr @box_gc3, ptr @box_gc6], [4 x ptr] [ptr @box_info1, ptr @box_info2, ptr @box_info3, ptr @box_info6] }, align 8\n");
            dataInit="  call void @bh_data_configure(ptr @data_info)\n";
        }
        var objectTypes=new ArrayList<>(program.classes());objectTypes.add(program.exceptionType());
        for(var c:objectTypes){
            objectSizes.put(c.id(),8+c.fields().size()*8);
            globals.append("@vtable").append(c.id()).append(" = private constant [").append(c.virtualMethods().size()).append(" x ptr] [")
                .append(String.join(", ",c.virtualMethods().stream().map(id->"ptr @bfn"+id).toList())).append("], align 8\n");
            globals.append("@itable").append(c.id()).append(" = private constant [").append(c.interfaceMethods().size()).append(" x ptr] [")
                .append(String.join(", ",c.interfaceMethods().stream().map(id->id<0?"ptr null":"ptr @bfn"+id).toList())).append("], align 8\n");
            globals.append("@types").append(c.id()).append(" = private constant [").append(c.assignableTypes().size()).append(" x i64] [")
                .append(String.join(", ",c.assignableTypes().stream().map(id->"i64 "+id).toList())).append("]\n");
            boolean record=c.assignableTypes().contains(-9);
            if(record)globals.append("@record_kinds").append(c.id()).append(" = private constant [").append(c.fields().size()).append(" x i64] [")
                .append(String.join(", ",c.fields().stream().map(f->"i64 "+valueKind(f.type())).toList())).append("]\n");
            globals.append("@typeinfo").append(c.id()).append(" = private constant ").append(record?"%RecordInfo { %ClassInfo {":"%ClassInfo {")
                .append(" ptr @vtable").append(c.id()).append(", ptr @itable").append(c.id()).append(", i64 ").append(c.assignableTypes().size()).append(", ptr @types").append(c.id()).append(" }");
            if(record)globals.append(", i64 ").append(c.fields().size()).append(", ptr @record_kinds").append(c.id()).append(" }");
            globals.append(", align 8\n");
            var refs=c.fields().stream().filter(f->f.type().reference()).toList();
            String offsets="@refs"+c.id();
            globals.append(offsets).append(" = private constant [").append(refs.size()).append(" x i64] [")
                .append(String.join(", ",refs.stream().map(f->"i64 "+f.offset()).toList())).append("]\n");
            byte[] nameBytes=(c.name()+"\0").getBytes(StandardCharsets.UTF_8);
            String name="@classname"+c.id();
            globals.append(name).append(" = private constant [").append(nameBytes.length).append(" x i8] c\"");
            for(byte b:nameBytes)globals.append(String.format(Locale.ROOT,"\\%02X",b&255));
            globals.append("\"\n");
            globals.append("@class").append(c.id()).append(" = private constant { ptr, i64, i64, ptr, i64, i64, i64 } { ptr ").append(name)
                .append(", i64 ").append(8+c.fields().size()*8).append(", i64 ").append(refs.size()).append(", ptr ").append(offsets).append(", i64 0, i64 0, i64 0 }, align 8\n");
        }
        var staticRoots=new ArrayList<String>();
        for(var field:program.staticFields()){
            globals.append("@static").append(field.id()).append(" = internal global ").append(llvm(field.type())).append(" zeroinitializer, align 8\n");
            if(field.type().reference())staticRoots.add("ptr @static"+field.id());
        }
        for(var init:program.initializations()){
            globals.append("@init_state").append(init.owner()).append(" = internal global i32 0, align 4\n")
                .append("@init_failure").append(init.owner()).append(" = internal global ptr null, align 8\n");
            staticRoots.add("ptr @init_failure"+init.owner());
            functions.append("define internal i64 @class_init").append(init.owner()).append("(ptr %unused) {\nentry:\n  call void @bfn")
                .append(init.function()).append("()\n  ret i64 0\n}\n");
        }
        globals.append("@static_roots = private constant [").append(staticRoots.size()).append(" x ptr] [")
            .append(String.join(", ",staticRoots)).append("], align 8\n");
        for(var f:program.functions())function(f);
        String license=text(notices);
        byte[] info=buildInfo.getBytes(StandardCharsets.UTF_8);globals.append("@bhash_build_info = private constant [").append(info.length+1).append(" x i8] c\"");
        for(byte b:info)globals.append(String.format(Locale.ROOT,"\\%02X",b&255));
        globals.append("\\00\", section \""+(NativePlatform.macos()?"__DATA,__bhash_build":NativePlatform.windows()?".bhash":".bhash.build")+"\", align 1\n@llvm.used = appending global [1 x ptr] [ptr @bhash_build_info], section \"llvm.metadata\"\n");
        return "; Bhash native: direct LLVM IR, internal ABI v15\n"+
            "target triple = \""+NativeBuildInfo.TARGET+"\"\n%TraceFrame = type { ptr, ptr, ptr }\n%RootFrame = type { ptr, ptr, i64 }\n%ClassInfo = type { ptr, ptr, i64, ptr }\n%RecordInfo = type { %ClassInfo, i64, ptr }\n%CollectionInfo = type { %ClassInfo, i64, i64, i64, ptr, ptr, ptr }\n%DataInfo = type { ptr, ptr, ptr, [4 x ptr], [4 x ptr] }\n"+
            globals+"\ndeclare void @"+NativeBuildInfo.GUARD+"()\ndeclare i32 @bh_print_build_info_if_requested(i32, ptr, ptr)\ndeclare void @bh_trace_enter(ptr, ptr, ptr)\ndeclare void @bh_trace_at(ptr)\ndeclare void @bh_trace_leave()\ndeclare void @bh_static_roots_configure(ptr, i64)\ndeclare void @bh_static_reference_store(ptr, ptr)\ndeclare void @bh_class_initialize(ptr, ptr, ptr)\ndeclare void @bh_roots_push(ptr, ptr, i64)\ndeclare void @bh_roots_pop(ptr)\n"+
            "declare ptr @bh_alloc(ptr, i64)\ndeclare ptr @bh_instance(ptr, ptr)\ndeclare void @bh_reference_store(ptr, i64, ptr)\n"+
            "declare i32 @bh_print_licenses_if_requested(i32, ptr, ptr)\ndeclare void @bh_runtime_init()\ndeclare void @bh_shutdown()\ndeclare void @bh_print_text(ptr)\ndeclare void @bh_print_int(i64)\n"+
            "declare void @bh_print_bool(i32)\ndeclare void @bh_print_space()\ndeclare void @bh_print_end()\n"+
            "declare i32 @bh_text_equal(ptr, ptr)\ndeclare i64 @bh_divide(i64, i64, ptr)\n"+
            "declare i64 @bh_remainder(i64, i64, ptr)\n"+
            "declare ptr @bh_collection_new(ptr, i64, ptr)\ndeclare ptr @bh_array_dimensions(ptr, i64, ptr, ptr)\n"+
            "declare ptr @bh_collection_literal(ptr, i64, ptr, ptr)\ndeclare i64 @bh_collection_call(ptr, i32, i64, i64, ptr, ptr)\n"+
            "declare void @bh_exception_configure(ptr, ptr)\ndeclare i64 @bh_protect(ptr, ptr)\ndeclare ptr @bh_exception_take()\ndeclare void @bh_throw(ptr, ptr) noreturn\ndeclare i32 @bh_run(ptr)\n"+
            textDeclarations()+numericDeclarations()+"declare ptr @bh_console_text(ptr)\ndeclare i64 @bh_console_integer(ptr)\n"+"declare i64 @bh_time_now(ptr)\ndeclare i64 @bh_time_read(ptr, ptr)\ndeclare ptr @bh_time_write(i64, ptr)\ndeclare i64 @bh_time_year(i64, ptr)\ndeclare i64 @bh_time_month(i64, ptr)\ndeclare i64 @bh_time_day(i64, ptr)\n"+"declare i64 @bh_to_byte(i64, ptr)\ndeclare i64 @bh_to_int32(i64, ptr)\ndeclare i64 @bh_buffer_call(i32, i64, i64, i64, i64, ptr, ptr)\n"+"declare i32 @bh_file_supported(ptr)\ndeclare i32 @bh_file_exists(ptr, ptr)\ndeclare ptr @bh_file_read_text(ptr, ptr)\ndeclare void @bh_file_write_text(ptr, ptr, ptr)\n"+"declare void @bh_data_configure(ptr)\ndeclare ptr @bh_data_split(ptr, ptr, ptr)\ndeclare ptr @bh_data_join(ptr, ptr, ptr)\ndeclare ptr @bh_data_json_read(ptr, ptr)\ndeclare ptr @bh_data_json_write(ptr, ptr)\n"+typeTestHelper()+equalityHelper()+functions+
            "define internal i64 @bhash_entry(ptr %unused) {\nentry:\n  call void @bfn"+program.entry()+"()\n  ret i64 0\n}\n"+
            "define i32 @main(i32 %argc, ptr %argv) {\nentry:\n  call void @"+NativeBuildInfo.GUARD+"()\n  call void @bh_runtime_init()\n  call void @bh_exception_configure(ptr @class-5, ptr @typeinfo-5)\n"+dataInit+"  call void @bh_static_roots_configure(ptr @static_roots, i64 "+staticRoots.size()+")\n  %shown = call i32 @bh_print_licenses_if_requested(i32 %argc, ptr %argv, ptr "+license+")\n  %info = call i32 @bh_print_build_info_if_requested(i32 %argc, ptr %argv, ptr @bhash_build_info)\n  %handled = or i32 %shown, %info\n  %skip = icmp ne i32 %handled, 0\n  br i1 %skip, label %done, label %application\napplication:\n  %status = call i32 @bh_run(ptr @bhash_entry)\n  call void @bh_shutdown()\n  br label %done\ndone:\n  %result = phi i32 [ 0, %entry ], [ %status, %application ]\n  ret i32 %result\n}\n"+(debug==null?"":debug.render());
    }
    private static String numericDeclarations(){
        var declarations=new StringBuilder();
        for(var op:NumericOperation.values()){
            var parameters=new ArrayList<String>();for(var p:op.parameters())parameters.add(llvm(p));parameters.add("ptr");
            declarations.append("declare ").append(llvm(op.result())).append(" @bh_numeric_")
                .append(op.name().toLowerCase(Locale.ROOT)).append("(").append(String.join(", ",parameters)).append(")\n");
        }
        return declarations.toString();
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
            "declare ptr @bh_text_real(double, ptr)\ndeclare double @bh_text_parse_real(ptr, ptr)\ndeclare ptr @bh_text_fixed(double, i64, ptr)\n"+
            "declare i64 @bh_real_to_int(double, ptr)\ndeclare void @bh_print_real(double)\n"+
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
        for(int kind:new int[]{1,2,3,6,7,8}){
            globals.append("@box_types").append(kind).append(" = private constant [2 x i64] [i64 -").append(kind).append(", i64 -4]\n");
            globals.append("@box_info").append(kind).append(" = private constant %ClassInfo { ptr null, ptr null, i64 2, ptr @box_types").append(kind).append(" }, align 8\n");
            globals.append("@box_refs").append(kind).append(" = private constant [").append(kind==3?1:0).append(" x i64] [").append(kind==3?"i64 8":"").append("]\n");
            // The collector uses the name only for diagnostics, so share a static UTF-8 name.
            globals.append("@box_gc").append(kind).append(" = private constant { ptr, i64, i64, ptr, i64, i64, i64 } { ptr @box_name, i64 16, i64 ")
                .append(kind==3?1:0).append(", ptr @box_refs").append(kind).append(", i64 0, i64 0, i64 0 }, align 8\n");
        }
        globals.append("@box_name = private constant [4 x i8] c\"box\\00\"\n");
    }
    private static String collectionInfo(int id){return id<0?"null":"@collection_info"+id;}
    private void collectionMetadata(List<CollectionType> types){
        for(var t:types){
            collectionTypes.put(t.id(),t);
            globals.append("@collection_ids").append(t.id()).append(" = private constant [2 x i64] [i64 ").append(t.id()).append(", i64 -4]\n");
            globals.append(collectionInfo(t.id())).append(" = private constant %CollectionInfo { %ClassInfo { ptr null, ptr null, i64 2, ptr @collection_ids")
                .append(t.id()).append(" }, i64 ").append(t.kind().ordinal()+1).append(", i64 ").append(t.keyKind()).append(", i64 ").append(t.valueKind())
                .append(", ptr ").append(collectionInfo(t.element())).append(", ptr ").append(collectionInfo(t.keyList())).append(", ptr ")
                .append(collectionInfo(t.valueList())).append(" }, align 8\n");
        }
    }
    private Value bits(Value value){
        if(value.type().reference())return instruction(Type.INT,"ptrtoint ptr "+value.ir()+" to i64");
        if(value.type()==Type.REAL)return instruction(Type.INT,"bitcast double "+value.ir()+" to i64");
        if(value.type()==Type.BOOL)return instruction(Type.INT,"zext i1 "+value.ir()+" to i64");
        return value;
    }
    private Value collectionResult(Type type,Value bits){
        if(type.reference())return instruction(type,"inttoptr i64 "+bits.ir()+" to ptr");
        if(type==Type.REAL)return instruction(type,"bitcast i64 "+bits.ir()+" to double");
        if(type==Type.BOOL)return instruction(type,"icmp ne i64 "+bits.ir()+", 0");
        if(type==Type.VOID)return new Value(type,"");
        return new Value(type,bits.ir());
    }
    private Value collectionCall(Type result,CollectionOperation operation,Value receiver,List<Value> arguments,int snapshot,Source source){
        var args=new ArrayList<Value>();for(var arg:arguments)args.add(bits(arg));
        String a=args.isEmpty()?"0":args.get(0).ir(),b=args.size()<2?"0":args.get(1).ir();
        Value bits=instruction(Type.INT,"call i64 @bh_collection_call(ptr "+receiver.ir()+", i32 "+operation.ordinal()+", i64 "+a+", i64 "+b+
            ", ptr "+collectionInfo(snapshot)+", ptr "+text(source.toString())+")");
        return collectionResult(result,bits);
    }
    private Value collectionCall(CollectionCall c){
        instance(expression(c.receiver()),c.source());for(var arg:c.arguments())expression(arg);
        return collectionCall(c.type(),c.operation(),reload(c.receiver()),c.arguments().stream().map(this::reload).toList(),c.snapshot(),c.source());
    }
    private Value newCollection(NewCollection c){
        for(var arg:c.arguments())expression(arg);
        String buffer="%buffer"+buffers.get(c);int count=c.arguments().size();
        for(int i=0;i<count;i++){
            Value value=bits(reload(c.arguments().get(i)));String slot=temporary();
            line(slot+" = getelementptr ["+count+" x i64], ptr "+buffer+", i64 0, i64 "+i);
            line("store i64 "+value.ir()+", ptr "+slot);
        }
        String location=text(c.source().toString());
        CollectionType type=collectionTypes.get(c.collection());
        if(c.literal())return instruction(Type.OBJECT,"call ptr @bh_collection_literal(ptr "+collectionInfo(c.collection())+", i64 "+(type.kind()==CollectionKind.MAP?count/2:count)+", ptr "+buffer+", ptr "+location+")");
        if(type.kind()==CollectionKind.BUFFER)return instruction(Type.OBJECT,"call ptr @bh_collection_new(ptr "+collectionInfo(c.collection())+", i64 "+reload(c.arguments().get(0)).ir()+", ptr "+location+")");
        if(type.kind()==CollectionKind.ARRAY)return instruction(Type.OBJECT,"call ptr @bh_array_dimensions(ptr "+collectionInfo(c.collection())+", i64 "+count+", ptr "+buffer+", ptr "+location+")");
        return instruction(Type.OBJECT,"call ptr @bh_collection_new(ptr "+collectionInfo(c.collection())+", i64 0, ptr "+location+")");
    }
    private final Map<Integer,CollectionType> collectionTypes=new HashMap<>();
    private static String equalityHelper(){
        return "declare i32 @bh_value_same(i64, i64, i64)\n"+
            "define internal i1 @bhash_any_equal(ptr %a, ptr %b) {\nentry:\n  %ab = ptrtoint ptr %a to i64\n  %bb = ptrtoint ptr %b to i64\n  %same = call i32 @bh_value_same(i64 5, i64 %ab, i64 %bb)\n  %result = icmp ne i32 %same, 0\n  ret i1 %result\n}\n";
    }
    private Value box(Box b){
        Value input=expression(b.value());
        if(input.type()==Type.OBJECT || input.type()==Type.ANY)return new Value(Type.ANY,input.ir());
        int kind=switch(input.type()){case INT32->8;case BYTE->7;case REAL->6;case INT->1;case BOOL->2;case TEXT->3;default->throw new IllegalStateException("Unchecked box type");};
        Value result=instruction(Type.ANY,"call ptr @bh_alloc(ptr @box_gc"+kind+", i64 16)");
        line("store ptr @box_info"+kind+", ptr "+result.ir());
        // Publish before the reference-store barrier; reload rooted text after allocation.
        line("store ptr "+result.ir()+", ptr "+rootAddress(roots.get(b)));
        input=reload(b.value());
        if(kind==3)line("call void @bh_reference_store(ptr "+result.ir()+", i64 8, ptr "+input.ir()+")");
        else {
            if(kind==2)input=instruction(Type.INT,"zext i1 "+input.ir()+" to i64");
            String payload=temporary();line(payload+" = getelementptr i8, ptr "+result.ir()+", i64 8");
            line("store "+(kind==6?"double":"i64")+" "+input.ir()+", ptr "+payload);
        }
        return reload(b);
    }
    private Value typeOperation(TypeOperation t){
        Value value=expression(t.value());
        if(t.action()==TypeAction.CAST && (value.type()==Type.INT || value.type()==Type.BYTE || value.type()==Type.INT32) && t.targetType()==Type.REAL)
            return instruction(Type.REAL,"sitofp i64 "+value.ir()+" to double");
        if(t.action()==TypeAction.CAST && value.type()==Type.REAL && t.targetType()==Type.INT)
            return instruction(Type.INT,"call i64 @bh_real_to_int(double "+value.ir()+", ptr "+text(t.source().toString())+")");
        if(t.action()==TypeAction.CAST && t.targetType()==Type.INT && (value.type()==Type.BYTE || value.type()==Type.INT32))return new Value(Type.INT,value.ir());
        if(t.action()==TypeAction.CAST && (t.targetType()==Type.BYTE || t.targetType()==Type.INT32) && (value.type()==Type.INT || value.type()==Type.BYTE || value.type()==Type.INT32 || value.type()==Type.REAL)){
            if(value.type()==Type.REAL)value=instruction(Type.INT,"call i64 @bh_real_to_int(double "+value.ir()+", ptr "+text(t.source().toString())+")");
            return instruction(t.targetType(),"call i64 @"+(t.targetType()==Type.BYTE?"bh_to_byte":"bh_to_int32")+"(i64 "+value.ir()+", ptr "+text(t.source().toString())+")");
        }
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
        return value.type()==t.targetType()?value:new Value(t.targetType(),t.targetType()==Type.BOOL?"false":t.targetType()==Type.TEXT?text(""):t.targetType()==Type.REAL?"0.0":"0");
    }
    private void scan(Expression e){
        if(e==null)return;
        if(e.type().reference() && !roots.containsKey(e))roots.put(e,rootCount++);
        if(e instanceof Read r)register(r.binding());
        else if(e instanceof Unary u)scan(u.value());
        else if(e instanceof Binary b){scan(b.left());scan(b.right());}
        else if(e instanceof Call c)c.arguments().forEach(this::scan);
        else if(e instanceof TextCall c)c.arguments().forEach(this::scan);
        else if(e instanceof NumericCall c)c.arguments().forEach(this::scan);
        else if(e instanceof StructuredCall c)c.arguments().forEach(this::scan);
        else if(e instanceof FileCall c)c.arguments().forEach(this::scan);
        else if(e instanceof TimeCall c)c.arguments().forEach(this::scan);
        else if(e instanceof BufferCall c)c.arguments().forEach(this::scan);
        else if(e instanceof FieldRead f)scan(f.receiver());
        else if(e instanceof TypeOperation t)scan(t.value());
        else if(e instanceof Box b)scan(b.value());
        else if(e instanceof IndexRead r){scan(r.receiver());scan(r.index());}
        else if(e instanceof CollectionCall c){scan(c.receiver());c.arguments().forEach(this::scan);}
        else if(e instanceof NewCollection c){buffers.putIfAbsent(c,buffers.size());c.arguments().forEach(this::scan);}
    }
    private void register(Binding b){
        if(bindings.putIfAbsent(b,true)==null && b.type().reference())bindingRoots.put(b,rootCount++);
    }
    private void scan(Statement s){
        if(s==null)return;
        if(s instanceof Block b)b.statements().forEach(this::scan);
        else if(s instanceof Assign a){register(a.binding());scan(a.value());}
        else if(s instanceof StaticWrite f)scan(f.value());
        else if(s instanceof FieldWrite f){scan(f.destination().receiver());scan(f.value());}
        else if(s instanceof Branch b){scan(b.condition());scan(b.yes());scan(b.no());}
        else if(s instanceof Loop l){scan(l.condition());scan(l.body());}
        else if(s instanceof Selection c){register(c.selector());scan(c.value());for(var item:c.cases()){item.labels().forEach(this::scan);scan(item.body());}scan(c.fallback());}
        else if(s instanceof Return r)scan(r.value());
        else if(s instanceof Throw t)scan(t.value());
        else if(s instanceof Fail f){scan(f.message());scan(f.location());}
        else if(s instanceof Try t){register(t.exception());scan(t.body());for(var handler:t.handlers()){register(handler.binding());scan(handler.body());}scan(t.cleanup());}
        else if(s instanceof Print p)p.arguments().forEach(this::scan);
        else if(s instanceof Evaluate e)scan(e.value());
        else if(s instanceof IndexWrite w){scan(w.destination().receiver());scan(w.destination().index());scan(w.value());}
        else if(s instanceof Foreach f){scan(f.collection());register(f.owner());register(f.index());register(f.version());f.items().forEach(this::scan);scan(f.body());}
    }
    private String rootAddress(int slot){
        String address=temporary();line(address+" = getelementptr ["+rootCount+" x ptr], ptr %roots, i64 0, i64 "+slot);return address;
    }
    private String address(Binding b){return "%b"+b.id();}
    /** Environments contain addresses, so callbacks observe writes to captured locals. */
    private void environment(boolean load){
        var addresses=new ArrayList<String>();addresses.add("%roots");
        if(functionResult!=Type.VOID)addresses.add("%return");
        for(var binding:bindings.keySet())addresses.add(address(binding));
        for(int i=0;i<buffers.size();i++)addresses.add("%buffer"+i);
        if(!load)line("%environment = alloca ["+addresses.size()+" x ptr], align 8");
        for(int i=0;i<addresses.size();i++){
            String slot=temporary();line(slot+" = getelementptr ["+addresses.size()+" x ptr], ptr %environment, i64 0, i64 "+i);
            if(load)line(addresses.get(i)+" = load ptr, ptr "+slot);
            else line("store ptr "+addresses.get(i)+", ptr "+slot);
        }
    }
    private String outline(Block body){
        String callback="@btry"+nextCallback++;
        int savedScope=debugScope;Source savedSource=debugSource;
        var savedCode=code;int savedNext=next;String savedLabel=label,savedOwner=owner;boolean savedTerminated=terminated,savedOutlined=outlined;
        var savedValues=new IdentityHashMap<>(values);var savedTargets=new ArrayDeque<>(targets);var savedScopes=new ArrayDeque<>(scopes);
        code=new StringBuilder();next=0;owner=callback;outlined=true;values.clear();
        if(debug!=null)debugScope=debug.function(currentFunction,callback.substring(1),true);
        code.append("define internal i64 ").append(callback).append("(ptr %environment)").append(debug==null?"":" noinline optnone !dbg !"+debugScope).append(" {\n");block("entry");environment(true);declareVariables(List.of());
        statement(body);if(!terminated)line("ret i64 0");code.append("}\n\n");functions.append(code);
        debugScope=savedScope;debugSource=savedSource;
        code=savedCode;next=savedNext;label=savedLabel;owner=savedOwner;terminated=savedTerminated;outlined=savedOutlined;
        values.clear();values.putAll(savedValues);targets.clear();targets.addAll(savedTargets);scopes.clear();scopes.addAll(savedScopes);
        return callback;
    }
    private void expressionRoots(Expression e,Set<Integer> slots){
        if(e==null)return;if(e.type().reference())slots.add(roots.get(e));
        if(e instanceof Unary u)expressionRoots(u.value(),slots);
        else if(e instanceof Binary b){expressionRoots(b.left(),slots);expressionRoots(b.right(),slots);}
        else if(e instanceof Call c)c.arguments().forEach(a->expressionRoots(a,slots));
        else if(e instanceof TextCall c)c.arguments().forEach(a->expressionRoots(a,slots));
        else if(e instanceof NumericCall c)c.arguments().forEach(a->expressionRoots(a,slots));
        else if(e instanceof StructuredCall c)c.arguments().forEach(a->expressionRoots(a,slots));
        else if(e instanceof FileCall c)c.arguments().forEach(a->expressionRoots(a,slots));
        else if(e instanceof TimeCall c)c.arguments().forEach(a->expressionRoots(a,slots));
        else if(e instanceof BufferCall c)c.arguments().forEach(a->expressionRoots(a,slots));
        else if(e instanceof FieldRead f)expressionRoots(f.receiver(),slots);
        else if(e instanceof TypeOperation t)expressionRoots(t.value(),slots);
        else if(e instanceof Box b)expressionRoots(b.value(),slots);
        else if(e instanceof IndexRead r){expressionRoots(r.receiver(),slots);expressionRoots(r.index(),slots);}
        else if(e instanceof CollectionCall c){expressionRoots(c.receiver(),slots);c.arguments().forEach(a->expressionRoots(a,slots));}
        else if(e instanceof NewCollection c)c.arguments().forEach(a->expressionRoots(a,slots));
    }
    private void bindingRoot(Binding binding,Set<Integer> slots){if(binding.type().reference())slots.add(bindingRoots.get(binding));}
    private void statementRoots(Statement s,Set<Integer> slots){
        if(s==null)return;
        if(s instanceof Block b){b.locals().forEach(v->bindingRoot(v,slots));b.statements().forEach(v->statementRoots(v,slots));}
        else if(s instanceof Assign a)expressionRoots(a.value(),slots);
        else if(s instanceof StaticWrite f)expressionRoots(f.value(),slots);
        else if(s instanceof FieldWrite f){expressionRoots(f.destination().receiver(),slots);expressionRoots(f.value(),slots);}
        else if(s instanceof IndexWrite w){expressionRoots(w.destination().receiver(),slots);expressionRoots(w.destination().index(),slots);expressionRoots(w.value(),slots);}
        else if(s instanceof Branch b){expressionRoots(b.condition(),slots);statementRoots(b.yes(),slots);statementRoots(b.no(),slots);}
        else if(s instanceof Loop l){expressionRoots(l.condition(),slots);statementRoots(l.body(),slots);}
        else if(s instanceof Selection c){bindingRoot(c.selector(),slots);expressionRoots(c.value(),slots);for(var item:c.cases()){item.labels().forEach(x->expressionRoots(x,slots));statementRoots(item.body(),slots);}statementRoots(c.fallback(),slots);}
        else if(s instanceof Return r)expressionRoots(r.value(),slots);
        else if(s instanceof Throw t)expressionRoots(t.value(),slots);
        else if(s instanceof Fail f){expressionRoots(f.message(),slots);expressionRoots(f.location(),slots);}
        else if(s instanceof Print p)p.arguments().forEach(a->expressionRoots(a,slots));
        else if(s instanceof Evaluate e)expressionRoots(e.value(),slots);
        else if(s instanceof Foreach f){expressionRoots(f.collection(),slots);bindingRoot(f.owner(),slots);for(var item:f.items()){bindingRoot(item.binding(),slots);statementRoots(item,slots);}statementRoots(f.body(),slots);}
        else if(s instanceof Try t){bindingRoot(t.exception(),slots);statementRoots(t.body(),slots);for(var h:t.handlers()){bindingRoot(h.binding(),slots);statementRoots(h.body(),slots);}statementRoots(t.cleanup(),slots);}
    }
    private Value protectedBlock(Block body){
        String callback=outline(body);Value result=instruction(Type.INT,"call i64 @bh_protect(ptr "+callback+", ptr %environment)");
        var slots=new TreeSet<Integer>();statementRoots(body,slots);
        for(int slot:slots)line("store ptr null, ptr "+rootAddress(slot));
        return result;
    }
    private void takeException(Binding binding){
        Value exception=instruction(Type.OBJECT,"call ptr @bh_exception_take()");line("store ptr "+exception.ir()+", ptr "+address(binding));
    }
    private Value completionPhi(List<String> incoming){return instruction(Type.INT,"phi i64 "+String.join(", ",incoming));}
    private void tryStatement(Try t){
        line("store ptr null, ptr "+address(t.exception()));
        Value completion=protectedBlock(t.body());String normal=freshLabel(),dispatch=freshLabel(),merge=freshLabel();
        Value failed=instruction(Type.BOOL,"icmp eq i64 "+completion.ir()+", -1");
        line("br i1 "+failed.ir()+", label %"+dispatch+", label %"+normal);
        var incoming=new ArrayList<String>();block(normal);incoming.add("[ "+completion.ir()+", %"+label+" ]");jump(merge);
        block(dispatch);takeException(t.exception());
        for(var handler:t.handlers()){
            Value exception=load(t.exception());Value matches=instruction(Type.BOOL,"call i1 @bhash_is_type(ptr "+exception.ir()+", i64 "+handler.typeId()+")");
            String caught=freshLabel(),next=freshLabel();line("br i1 "+matches.ir()+", label %"+caught+", label %"+next);block(caught);
            line("store ptr "+exception.ir()+", ptr "+address(handler.binding()));
            Value caughtCompletion=protectedBlock(handler.body());clearBindings(List.of(handler.binding()));
            Value caughtFailure=instruction(Type.BOOL,"icmp eq i64 "+caughtCompletion.ir()+", -1");String thrown=freshLabel(),handled=freshLabel();
            line("br i1 "+caughtFailure.ir()+", label %"+thrown+", label %"+handled);
            block(thrown);takeException(t.exception());incoming.add("[ -1, %"+label+" ]");jump(merge);
            block(handled);line("store ptr null, ptr "+address(t.exception()));incoming.add("[ "+caughtCompletion.ir()+", %"+label+" ]");jump(merge);
            block(next);
        }
        incoming.add("[ -1, %"+label+" ]");jump(merge);block(merge);completion=completionPhi(incoming);
        if(t.cleanup()!=null){
            Value cleanup=protectedBlock(t.cleanup());Value cleanupFailed=instruction(Type.BOOL,"icmp eq i64 "+cleanup.ir()+", -1");
            String thrown=freshLabel(),kept=freshLabel(),after=freshLabel();line("br i1 "+cleanupFailed.ir()+", label %"+thrown+", label %"+kept);
            block(thrown);takeException(t.exception());
            if(functionResult.reference()){
                Value returning=instruction(Type.BOOL,"icmp eq i64 "+completion.ir()+", 1");Value old=instruction(functionResult,"load ptr, ptr %return");
                Value keep=instruction(functionResult,"select i1 "+returning.ir()+", ptr null, ptr "+old.ir());line("store ptr "+keep.ir()+", ptr %return");
            }
            String thrownEnd=label;jump(after);block(kept);String keptEnd=label;jump(after);block(after);
            completion=completionPhi(List.of("[ -1, %"+thrownEnd+" ]","[ "+completion.ir()+", %"+keptEnd+" ]"));
        }
        String done=freshLabel(),returning=freshLabel(),throwing=freshLabel(),escape=freshLabel();
        var exits=new LinkedHashMap<ControlTarget,List<String>>();
        var cases=new ArrayList<String>();cases.add("i64 0, label %"+done);cases.add("i64 1, label %"+returning);cases.add("i64 -1, label %"+throwing);
        for(var loop:targets)if(loop.owner().equals(owner)){
            var labels=loop.condition()==null?List.of(freshLabel()):List.of(freshLabel(),freshLabel());exits.put(loop,labels);
            cases.add("i64 "+(2+loop.id()*2)+", label %"+labels.get(0));if(loop.condition()!=null)cases.add("i64 "+(3+loop.id()*2)+", label %"+labels.get(1));
        }
        line("switch i64 "+completion.ir()+", label %"+escape+" [ "+String.join(" ",cases)+" ]");terminated=true;
        block(returning);
        if(outlined)line("ret i64 1");
        else {
            Value value=functionResult==Type.VOID?null:instruction(functionResult,"load "+llvm(functionResult)+", ptr %return");
            leaveTrace();line("call void @bh_roots_pop(ptr %frame)");line(value==null?"ret void":"ret "+value.argument());
        }
        terminated=true;block(throwing);Value exception=load(t.exception());line("call void @bh_throw(ptr "+exception.ir()+", ptr null)");line("unreachable");terminated=true;
        for(var entry:exits.entrySet())for(int i=0;i<entry.getValue().size();i++){
            block(entry.getValue().get(i));clearExitScopes(entry.getKey().scopeDepth());jump(i==0?entry.getKey().exit():entry.getKey().condition());
        }
        block(escape);line(outlined?"ret i64 "+completion.ir():"unreachable");terminated=true;
        block(done);line("store ptr null, ptr "+address(t.exception()));
    }
    private void declareVariables(List<Binding> parameters){
        if(debug==null)return;
        for(var b:bindings.keySet()){String declaration=debug.declare(b,address(b),debugScope,parameters.indexOf(b)+1);if(!declaration.isEmpty()){Source previous=debugSource;debugSource=b.source();line(declaration);debugSource=previous;}}
    }
    private void traceAt(Source source){if(source!=null)line("call void @bh_trace_at(ptr "+text(source.toString())+")");}
    private void leaveTrace(){if(traced)line("call void @bh_trace_leave()");}
    private void function(Function f){
        currentFunction=f;traced=!f.name().isEmpty();debugSource=f.source();if(debug!=null)debugScope=debug.function(f,"bfn"+f.id(),!traced);
        code=new StringBuilder(); values.clear(); roots.clear(); bindingRoots.clear(); bindings.clear();rootCount=0;next=0;buffers.clear();targets.clear();scopes.clear();
        functionResult=f.result();owner="@bfn"+f.id();outlined=false;nextTarget=0;returnRoot=-1;
        f.parameters().forEach(this::register);scan(f.body());
        if(f.result().reference())returnRoot=rootCount++;
        code.append("define internal ").append(llvm(f.result())).append(" @bfn").append(f.id()).append('(');
        var arguments=new ArrayList<String>();
        for(int i=0;i<f.parameters().size();i++)arguments.add(llvm(f.parameters().get(i).type())+" %arg"+i);
        code.append(String.join(", ",arguments)).append(")").append(debug==null?"":" noinline optnone !dbg !"+debugScope).append(" {\n");block("entry");debugSource=null;
        if(traced){line("%trace = alloca %TraceFrame, align 8");line("call void @bh_trace_enter(ptr %trace, ptr "+text(f.name())+", ptr "+text(f.source().toString())+")");}
        line("%frame = alloca %RootFrame, align 8");
        line("%roots = alloca ["+rootCount+" x ptr], align 8");
        line("store ["+rootCount+" x ptr] zeroinitializer, ptr %roots");
        for(var entry:buffers.entrySet().stream().sorted(Map.Entry.comparingByValue()).toList())line("%buffer"+entry.getValue()+" = alloca ["+entry.getKey().arguments().size()+" x i64], align 8");
        for(var b:bindings.keySet()) {
            if(b.type().reference())line(address(b)+" = getelementptr ["+rootCount+" x ptr], ptr %roots, i64 0, i64 "+bindingRoots.get(b));
            else line(address(b)+" = alloca "+llvm(b.type()));
        }
        if(f.result()!=Type.VOID){
            if(f.result().reference())line("%return = getelementptr ["+rootCount+" x ptr], ptr %roots, i64 0, i64 "+returnRoot);
            else line("%return = alloca "+llvm(f.result()));
        }
        environment(false);
        for(int i=0;i<f.parameters().size();i++) {
            var b=f.parameters().get(i);line("store "+llvm(b.type())+" %arg"+i+", ptr "+address(b));
        }
        line("call void @bh_roots_push(ptr %frame, ptr %roots, i64 "+rootCount+")");
        // The first debugger stop must follow parameter stores/root registration.
        // Declaring earlier makes GDB end the prologue before arguments are valid.
        declareVariables(f.parameters());
        debugSource=f.source();
        if(f.initialization()>=0)initialize(f.initialization());
        statement(f.body());
        if(!terminated){leaveTrace();line("call void @bh_roots_pop(ptr %frame)");line(f.result()==Type.VOID?"ret void":"unreachable");}
        code.append("}\n\n");functions.append(code);
    }
    private void initialize(int id){if(id>=0)line("call void @bh_class_initialize(ptr @init_state"+id+", ptr @init_failure"+id+", ptr @class_init"+id+")");}
    private Value expression(Expression e){
        Source previous=debugSource;debugSource=e.source();
        if(e instanceof Call || e instanceof StaticRead)traceAt(e.source());
        try{return expressionImpl(e);}finally{debugSource=previous;}
    }
    private Value expressionImpl(Expression e){
        Value result;
        if(e instanceof Constant c)result=new Value(c.type(),c.type()==Type.TEXT?text((String)c.value()):c.type()==Type.BOOL?((Boolean)c.value()?"true":"false"):(c.type()==Type.OBJECT || c.type()==Type.ANY)?"null":c.type()==Type.REAL?String.format(Locale.ROOT,"0x%016X",Double.doubleToRawLongBits((Double)c.value())):c.value().toString());
        else if(e instanceof StaticRead r){
            initialize(r.field().owner());result=instruction(r.type(),"load "+llvm(r.type())+", ptr @static"+r.field().id());
        }
        else if(e instanceof Allocate a){
            result=instruction(Type.OBJECT,"call ptr @bh_alloc(ptr @class"+a.objectType()+", i64 "+objectSizes.get(a.objectType())+")");
            // The immutable type-metadata pointer is not a managed reference or safepoint.
            line("store ptr @typeinfo"+a.objectType()+", ptr "+result.ir());
        }
        else if(e instanceof NewCollection c)result=newCollection(c);
        else if(e instanceof CollectionCall c)result=collectionCall(c);
        else if(e instanceof IndexRead r){
            instance(expression(r.receiver()),r.source());expression(r.index());
            result=collectionCall(r.type(),CollectionOperation.GET,reload(r.receiver()),List.of(reload(r.index())),-1,r.source());
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
                case "+"->new Value(u.type(),v.ir());
                case "-"->v.type()==Type.REAL?instruction(Type.REAL,"fneg double "+v.ir()):instruction(Type.INT,"sub i64 0, "+v.ir());
                case "!"->instruction(Type.BOOL,"xor i1 "+v.ir()+", true");
                case "~"->instruction(Type.INT,"xor i64 "+v.ir()+", -1");
                default->throw new IllegalStateException("Unchecked unary operator");};
        } else if(e instanceof Call c){
            initialize(c.constructionClass());
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
            traceAt(c.source());
            String call="call "+llvm(c.type())+" "+target+"("+String.join(", ",args)+")";
            if(c.type()==Type.VOID){line(call);result=new Value(Type.VOID,"");}
            else result=instruction(c.type(),call);
        } else if(e instanceof BufferCall c) {
            for(var a:c.arguments())expression(a);
            var args=new ArrayList<String>();for(var a:c.arguments())args.add("i64 "+bits(reload(a)).ir());
            while(args.size()<4)args.add("i64 0");
            Value raw=instruction(Type.INT,"call i64 @bh_buffer_call(i32 "+c.operation().ordinal()+", "+String.join(", ",args)+", ptr "+collectionInfo(c.descriptor())+", ptr "+text(c.source().toString())+")");
            result=collectionResult(c.type(),raw);
        } else if(e instanceof InputCall c) {
            result=instruction(c.type(),"call "+llvm(c.type())+" @bh_console_"+(c.type()==Type.TEXT?"text":"integer")+"(ptr "+text(c.source().toString())+")");
        } else if(e instanceof TimeCall c) {
            for(var a:c.arguments())expression(a);
            var args=new ArrayList<String>();for(var a:c.arguments())args.add(reload(a).argument());args.add("ptr "+text(c.source().toString()));
            result=instruction(c.type(),"call "+llvm(c.type())+" @bh_time_"+c.operation().name().toLowerCase(Locale.ROOT)+"("+String.join(", ",args)+")");
        } else if(e instanceof FileCall c) {
            for(var a:c.arguments())expression(a);
            var args=new ArrayList<String>();for(var a:c.arguments())args.add(reload(a).argument());args.add("ptr "+text(c.source().toString()));
            String resultType=c.type()==Type.BOOL?"i32":llvm(c.type());
            String call="call "+resultType+" @bh_file_"+c.operation().name().toLowerCase(Locale.ROOT)+"("+String.join(", ",args)+")";
            if(c.type()==Type.VOID){line(call);result=new Value(Type.VOID,"");}
            else if(c.type()==Type.BOOL){String value=temporary();line(value+" = "+call);result=instruction(Type.BOOL,"icmp ne i32 "+value+", 0");}
            else result=instruction(c.type(),call);
        } else if(e instanceof StructuredCall c) {
            for(var a:c.arguments())expression(a);
            var args=new ArrayList<String>();for(var a:c.arguments())args.add(reload(a).argument());args.add("ptr "+text(c.source().toString()));
            result=instruction(c.type(),"call ptr @bh_data_"+c.operation().name().toLowerCase(Locale.ROOT)+"("+String.join(", ",args)+")");
        } else if(e instanceof NumericCall c) {
            for(var a:c.arguments())expression(a);
            var args=new ArrayList<String>();for(var a:c.arguments())args.add(reload(a).argument());
            args.add("ptr "+text(c.source().toString()));
            traceAt(c.source());
            String call="call "+llvm(c.type())+" @bh_numeric_"+c.operation().name().toLowerCase(Locale.ROOT)+"("+String.join(", ",args)+")";
            if(c.type()==Type.VOID){line(call);result=new Value(Type.VOID,"");}else result=instruction(c.type(),call);
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
        else if(e instanceof NumericCall c)c.arguments().forEach(this::release);
        else if(e instanceof StructuredCall c)c.arguments().forEach(this::release);
        else if(e instanceof FileCall c)c.arguments().forEach(this::release);
        else if(e instanceof TimeCall c)c.arguments().forEach(this::release);
        else if(e instanceof BufferCall c)c.arguments().forEach(this::release);
        else if(e instanceof Binary b){release(b.left());release(b.right());}
        else if(e instanceof Unary u)release(u.value());
        else if(e instanceof FieldRead f)release(f.receiver());
        else if(e instanceof TypeOperation t)release(t.value());
        else if(e instanceof Box b)release(b.value());
        else if(e instanceof IndexRead r){release(r.receiver());release(r.index());}
        else if(e instanceof CollectionCall c){release(c.receiver());c.arguments().forEach(this::release);}
        else if(e instanceof NewCollection c)c.arguments().forEach(this::release);
        values.put(e,result);
        return result;
    }
    private void release(Expression e){
        if(e!=null && e.type().reference())line("store ptr null, ptr "+rootAddress(roots.get(e)));
    }
    private void clearBindings(List<Binding> locals){
        for(var b:locals)if(b.type().reference())line("store ptr null, ptr "+address(b));
    }
    private void clearExitScopes(int depth){
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
        if(Set.of("&","|","^").contains(op))return instruction(Type.INT,
            (op.equals("&")?"and":op.equals("|")?"or":"xor")+" i64 "+left.ir()+", "+right.ir());
        if(Set.of("<<",">>",">>>").contains(op)){
            Value count=instruction(Type.INT,"and i64 "+right.ir()+", 63");
            return instruction(Type.INT,(op.equals("<<")?"shl":op.equals(">>")?"ashr":"lshr")+" i64 "+left.ir()+", "+count.ir());
        }
        if(left.type()==Type.REAL){
            String operation=switch(op){case "+"->"fadd";case "-"->"fsub";case "*"->"fmul";case "/"->"fdiv";
                case "=="->"fcmp oeq";case "!="->"fcmp une";case "<"->"fcmp olt";case "<="->"fcmp ole";case ">"->"fcmp ogt";case ">="->"fcmp oge";
                default->throw new IllegalStateException("Unchecked real operator");};
            return instruction(b.type(),operation+" double "+left.ir()+", "+right.ir());
        }
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
        if(left.type()==Type.OBJECT){
            left=bits(reload(b.left()));right=bits(reload(b.right()));
            Value same=instruction(Type.INT,"call i32 @bh_value_same(i64 4, i64 "+left.ir()+", i64 "+right.ir()+")");
            return instruction(Type.BOOL,"icmp "+(op.equals("==")?"ne":"eq")+" i32 "+same.ir()+", 0");
        }
        String predicate=switch(op){case "=="->"eq";case "!="->"ne";case "<"->"slt";case "<="->"sle";case ">"->"sgt";case ">="->"sge";default->throw new IllegalStateException("Unchecked binary operator");};
        return instruction(Type.BOOL,"icmp "+predicate+" "+left.argument()+", "+right.ir());
    }
    private Value load(Binding b){return instruction(b.type(),"load "+llvm(b.type())+", ptr "+address(b));}
    private void foreach(Foreach f){
        Value source=expression(f.collection());instance(source,f.source());
        line("store ptr "+source.ir()+", ptr "+address(f.owner()));release(f.collection());
        Value version=collectionCall(Type.INT,CollectionOperation.VERSION,load(f.owner()),List.of(),-1,f.source());
        line("store i64 "+version.ir()+", ptr "+address(f.version()));line("store i64 0, ptr "+address(f.index()));
        String condition=freshLabel(),body=freshLabel(),advance=freshLabel(),exit=freshLabel();jump(condition);block(condition);
        Value more=collectionCall(Type.BOOL,CollectionOperation.ITER_CHECK,load(f.owner()),List.of(load(f.version()),load(f.index())),-1,f.source());
        line("br i1 "+more.ir()+", label %"+body+", label %"+exit);block(body);
        var locals=f.items().stream().map(Assign::binding).toList();int depth=scopes.size();scopes.push(locals);
        targets.push(new ControlTarget(advance,exit,depth,nextTarget++,owner));f.items().forEach(this::statement);statement(f.body());targets.pop();
        if(!terminated){clearBindings(locals);jump(advance);}scopes.pop();
        block(advance);Value index=load(f.index());Value next=instruction(Type.INT,"add i64 "+index.ir()+", 1");
        line("store i64 "+next.ir()+", ptr "+address(f.index()));jump(condition);block(exit);
        clearBindings(locals);line("store ptr null, ptr "+address(f.owner()));
    }
    private void selection(Selection c){
        Value value=expression(c.value());line("store "+value.argument()+", ptr "+address(c.selector()));release(c.value());
        int depth=scopes.size();scopes.push(List.of(c.selector()));
        String exit=freshLabel();targets.push(new ControlTarget(null,exit,depth,nextTarget++,owner));
        for(var item:c.cases()){
            String body=freshLabel(),nextCase=freshLabel();Value matches=null;
            for(var constant:item.labels()){
                Value labelValue=expression(constant),selector=load(c.selector()),same;
                if(selector.type()==Type.TEXT){
                    Value comparison=instruction(Type.INT,"call i32 @bh_text_equal(ptr "+selector.ir()+", ptr "+labelValue.ir()+")");
                    same=instruction(Type.BOOL,"icmp ne i32 "+comparison.ir()+", 0");
                }else same=instruction(Type.BOOL,"icmp eq "+selector.argument()+", "+labelValue.ir());
                release(constant);matches=matches==null?same:instruction(Type.BOOL,"or i1 "+matches.ir()+", "+same.ir());
            }
            line("br i1 "+matches.ir()+", label %"+body+", label %"+nextCase);
            block(body);statement(item.body());if(!terminated)jump(exit);block(nextCase);
        }
        statement(c.fallback());if(!terminated)jump(exit);
        targets.pop();scopes.pop();block(exit);clearBindings(List.of(c.selector()));
    }
    private Source statementSource(Statement s){
        if(s instanceof Assign a)return a.value().source();if(s instanceof Return r)return r.source();if(s instanceof Print p)return p.source();if(s instanceof Jump j)return j.source();if(s instanceof Evaluate e)return e.value().source();
        if(s instanceof Throw t)return t.source();if(s instanceof Try t)return t.source();if(s instanceof Branch b)return b.condition().source();if(s instanceof Loop l)return l.condition().source();if(s instanceof Selection c)return c.value().source();if(s instanceof Foreach f)return f.source();
        if(s instanceof FieldWrite f)return f.destination().source();if(s instanceof IndexWrite w)return w.destination().source();if(s instanceof StaticWrite f)return f.value().source();return debugSource;
    }
    private void statement(Statement s){
        Source previous=debugSource;Source source=statementSource(s);if(source!=null)debugSource=source;
        try{statementImpl(s);}finally{debugSource=previous;}
    }
    private void statementImpl(Statement s){
        if(s==null || terminated)return;
        if(s instanceof Block b){
            scopes.push(b.locals());for(var x:b.statements())statement(x);
            if(!terminated)clearBindings(b.locals());scopes.pop();
        }
        else if(s instanceof Assign a){Value v=expression(a.value());line("store "+v.argument()+", ptr "+address(a.binding()));release(a.value());}
        else if(s instanceof Foreach f)foreach(f);
        else if(s instanceof Selection c)selection(c);
        else if(s instanceof Try t)tryStatement(t);
        else if(s instanceof Fail f){
            expression(f.message());expression(f.location());line("call void @bh_text_fail(ptr "+reload(f.message()).ir()+", ptr "+reload(f.location()).ir()+")");line("unreachable");terminated=true;
        }
        else if(s instanceof Throw t){
            Value value=expression(t.value());line("call void @bh_throw(ptr "+value.ir()+", ptr "+text(t.source().toString())+")");
            line("unreachable");terminated=true;
        }
        else if(s instanceof IndexWrite w){
            var target=w.destination();instance(expression(target.receiver()),target.source());expression(target.index());expression(w.value());
            collectionCall(Type.VOID,CollectionOperation.SET,reload(target.receiver()),List.of(reload(target.index()),reload(w.value())),-1,target.source());
            release(target.receiver());release(target.index());release(w.value());
        }
        else if(s instanceof Initialize i)initialize(i.owner());
        else if(s instanceof StaticWrite f){
            expression(f.value());traceAt(f.value().source());initialize(f.field().owner());Value value=reload(f.value());
            if(f.field().type().reference())line("call void @bh_static_reference_store(ptr @static"+f.field().id()+", ptr "+value.ir()+")");
            else line("store "+value.argument()+", ptr @static"+f.field().id());
            release(f.value());
        }
        else if(s instanceof FieldWrite f){
            var target=f.destination();
            instance(expression(target.receiver()),target.source());
            expression(f.value());
            Value owner=reload(target.receiver()),value=reload(f.value());
            if(target.type().reference())line("call void @bh_reference_store(ptr "+owner.ir()+", i64 "+target.field().offset()+", ptr "+value.ir()+")");
            else {
                String pointer=temporary();line(pointer+" = getelementptr i8, ptr "+owner.ir()+", i64 "+target.field().offset());
                // Fields use full 8-byte carriers; canonicalize i1 before C value inspection.
                if(value.type()==Type.BOOL)value=bits(value);
                line("store "+value.argument()+", ptr "+pointer);
            }
            release(target.receiver());release(f.value());
        }
        else if(s instanceof Return r){
            Value v=r.value()==null?null:expression(r.value());
            if(outlined){if(v!=null)line("store "+v.argument()+", ptr %return");line("ret i64 1");terminated=true;}
            else {leaveTrace();line("call void @bh_roots_pop(ptr %frame)");line(v==null?"ret void":"ret "+v.argument());terminated=true;}
        } else if(s instanceof Evaluate e){expression(e.value());release(e.value());}
        else if(s instanceof Print p){
            var values=new ArrayList<Value>();for(var a:p.arguments())values.add(expression(a));
            for(int i=0;i<values.size();i++){
                if(i>0)line("call void @bh_print_space()");Value v=values.get(i);
                if(v.type()==Type.TEXT)v=reload(p.arguments().get(i));
                if(v.type()==Type.BOOL){Value n=instruction(Type.INT,"zext i1 "+v.ir()+" to i32");line("call void @bh_print_bool(i32 "+n.ir()+")");}
                else line("call void @"+(v.type()==Type.TEXT?"bh_print_text":v.type()==Type.REAL?"bh_print_real":"bh_print_int")+"("+v.argument()+")");
                release(p.arguments().get(i));
            }
            line("call void @bh_print_end()");
        } else if(s instanceof Jump j){
            var loop=j.breaking()?targets.peek():targets.stream().filter(x->x.condition()!=null).findFirst().orElseThrow();
            if(!loop.owner().equals(owner)){line("ret i64 "+(2+loop.id()*2+(j.breaking()?0:1)));terminated=true;}
            else {clearExitScopes(loop.scopeDepth());jump(j.breaking()?loop.exit():loop.condition());}
        }
        else if(s instanceof Loop l){
            String condition=freshLabel(),body=freshLabel(),exit=freshLabel();jump(condition);block(condition);
            Value v=expression(l.condition());line("br i1 "+v.ir()+", label %"+body+", label %"+exit);
            block(body);targets.push(new ControlTarget(condition,exit,scopes.size(),nextTarget++,owner));statement(l.body());targets.pop();
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
