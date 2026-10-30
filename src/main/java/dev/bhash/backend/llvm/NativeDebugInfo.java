// SPDX-License-Identifier: Apache-2.0
package dev.bhash.backend.llvm;

import dev.bhash.backend.CoreProgram.*;
import dev.bhash.backend.CoreProgram;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;

/** DWARF metadata for original Bhash sources; LLVM linkage names remain stable ASCII. */
final class NativeDebugInfo {
    private final List<String> nodes=new ArrayList<>();
    private final Map<String,Integer> files=new LinkedHashMap<>(),locations=new HashMap<>();
    private final Map<Type,Integer> types=new EnumMap<>(Type.class);
    private final int unit,expression;
    NativeDebugInfo(Source source){
        int retained=add("!{}");
        int file=file(source);unit=add("distinct !DICompileUnit(language: DW_LANG_C, file: !"+file+", producer: "+quote("Bhash native LLVM")+", isOptimized: false, runtimeVersion: 0, emissionKind: FullDebug, retainedTypes: !"+retained+")");
        expression=add("!DIExpression()");
        for(var reference:List.of(Type.TEXT,Type.OBJECT,Type.ANY))type(reference);
        nodes.set(retained,"!{"+String.join(", ",List.of(Type.TEXT,Type.OBJECT,Type.ANY).stream().map(t->"!"+type(t)).toList())+"}");
    }
    static String quote(String value){
        var out=new StringBuilder("\"");for(byte b:value.getBytes(StandardCharsets.UTF_8)){int c=b&255;if(c>=32 && c<127 && c!=34 && c!=92)out.append((char)c);else out.append(String.format(Locale.ROOT,"\\%02X",c));}return out.append('"').toString();
    }
    int add(String value){int id=nodes.size();nodes.add(value);return id;}
    int file(Source source){return files.computeIfAbsent(source.file(),name->{
        Path path=Path.of(name).toAbsolutePath().normalize();return add("!DIFile(filename: "+quote(path.getFileName().toString())+", directory: "+quote(path.getParent().toString())+")");
    });}
    int function(Function f,String linkage,boolean artificial){
        String name=f.name().isEmpty()?"<निर्मित सहायक>":f.name()+(artificial?".<सहायक>":"");
        boolean callback=linkage.startsWith("btry");
        var arguments=new ArrayList<String>();Type result=callback?Type.INT:f.result();arguments.add(result==Type.VOID?"null":"!"+type(result));
        if(callback)arguments.add("!"+type(Type.OBJECT));else for(var parameter:f.parameters())arguments.add("!"+type(parameter.type()));
        int signature=add("!DISubroutineType(types: !"+add("!{"+String.join(", ",arguments)+"}")+")");
        return add("distinct !DISubprogram(name: "+quote(name)+", scope: !"+file(f.source())+", file: !"+file(f.source())+", line: "+f.source().line()+", type: !"+signature+", scopeLine: "+f.source().line()+", flags: DIFlagPrototyped"+(artificial?" | DIFlagArtificial":"")+", spFlags: DISPFlagLocalToUnit | DISPFlagDefinition, unit: !"+unit+")");
    }
    int location(int scope,Source source){return locations.computeIfAbsent(scope+":"+source,k->{
        // Lexical file scopes also handle generated constructor/base initialization from other files.
        int local=add("!DILexicalBlockFile(scope: !"+scope+", file: !"+file(source)+", discriminator: 0)");
        int line=Math.max(0,source.line());
        return add("!DILocation(line: "+line+", column: "+(line==0?0:Math.min(65535,Math.max(1,source.column())))+", scope: !"+local+")");
    });}
    int type(Type type){return types.computeIfAbsent(type,t->switch(t){
        case INT->add("!DIBasicType(name: "+quote("पूर्णांक")+", size: 64, encoding: DW_ATE_signed)");
        case INT32->add("!DIBasicType(name: "+quote("लघुपूर्णांक")+", size: 32, encoding: DW_ATE_signed)");
        case BYTE->add("!DIBasicType(name: "+quote("बाइट")+", size: 8, encoding: DW_ATE_unsigned)");
        case BOOL->add("!DIBasicType(name: "+quote("तार्किक")+", size: 8, encoding: DW_ATE_boolean)");
        case REAL->add("!DIBasicType(name: "+quote("दशमलव")+", size: 64, encoding: DW_ATE_float)");
        default->add("!DIDerivedType(tag: DW_TAG_typedef, name: "+quote(t==Type.TEXT?"पाठ":t==Type.ANY?"सर्ववस्तु":"वस्तु")+", baseType: !"+add("!DIDerivedType(tag: DW_TAG_pointer_type, baseType: null, size: 64)")+")");
    });}
    String declare(Binding binding,String address,int scope,int argument){
        if(binding.name().isEmpty() || binding.source()==null)return "";
        int var=add("!DILocalVariable(name: "+quote(binding.name())+(argument>0?", arg: "+argument:"")+", scope: !"+scope+", file: !"+file(binding.source())+", line: "+binding.source().line()+", type: !"+type(binding.type())+")");
        return "call void @llvm.dbg.declare(metadata ptr "+address+", metadata !"+var+", metadata !"+expression+")";
    }
    /** Debug-only, immutable schema. No runtime calls, allocation or GC ABI changes. */
    static String schema(CoreProgram program){
        var root=new com.google.gson.JsonObject();root.addProperty("version",1);root.addProperty("abi",NativeBuildInfo.ABI);
        var classes=new com.google.gson.JsonObject();
        var types=new ArrayList<>(program.classes());types.add(program.exceptionType());
        for(var object:types){
            var entry=new com.google.gson.JsonObject();entry.addProperty("name",object.name());var fields=new com.google.gson.JsonArray();
            for(var field:object.fields()){
                var f=new com.google.gson.JsonObject();f.addProperty("name",field.name().isEmpty()?"@"+field.offset():field.name());
                f.addProperty("offset",field.offset());f.addProperty("type",field.type().name());fields.add(f);
            }
            entry.add("fields",fields);classes.add(Integer.toString(object.id()),entry);
        }
        root.add("classes",classes);var collections=new com.google.gson.JsonObject();
        for(var type:program.collections()){
            var entry=new com.google.gson.JsonObject();entry.addProperty("kind",type.kind().name());
            entry.addProperty("key",type.keyKind());entry.addProperty("value",type.valueKind());collections.add(Integer.toString(type.id()),entry);
        }
        root.add("collections",collections);return root.toString();
    }
    String render(){
        int dwarf=add("!{i32 2, !\"Dwarf Version\", i32 4}"),debug=add("!{i32 2, !\"Debug Info Version\", i32 3}");
        var out=new StringBuilder("\ndeclare void @llvm.dbg.declare(metadata, metadata, metadata)\n!llvm.dbg.cu = !{").append('!').append(unit).append("}\n!llvm.module.flags = !{!").append(dwarf).append(", !").append(debug).append("}\n");
        for(int i=0;i<nodes.size();i++)out.append('!').append(i).append(" = ").append(nodes.get(i)).append('\n');return out.toString();
    }
}
