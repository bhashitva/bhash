// SPDX-License-Identifier: Apache-2.0
package dev.bhash.ffi;

import java.util.*;

/** Produces a fixed, checked wire gateway around the reviewed typed C entry points.
 * Application declarations and producer gateways use the same parsed contract. */
public final class ForeignCGenerator {
    private ForeignCGenerator() {}
    public static String prefix(ForeignManifest module) { return "bhffi_" + module.fingerprint().substring(0, 24); }
    public static String resourceKind(ForeignManifest module, String resource) {
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256").digest((module.fingerprint() + ":" + resource).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            String hex = HexFormat.of().formatHex(Arrays.copyOf(digest, 8));
            return "UINT64_C(0x" + (hex.equals("0000000000000000") ? "0000000000000001" : hex) + ")";
        } catch (java.security.NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }
    public static String generate(ForeignManifest module) {
        String prefix = prefix(module);
        var code = new StringBuilder("/* Generated from the checked Bhash FFI contract; do not edit. */\n#include \"bhffi.h\"\n#include <string.h>\n#include <limits.h>\n");
        if(module.functions().stream().anyMatch(f->Set.of("status+owned-bytes","status+owned-utf8").contains(f.result().wire()) && f.parameters().stream().anyMatch(p->Set.of("bytes","utf8").contains(p.wire()))))
            code.append("static int ").append(prefix).append("_aliases(bhffi_owned *value,bhffi_view input){if(!value->data || !input.data)return 0;uintptr_t a=(uintptr_t)value->data,b=(uintptr_t)input.data;uint64_t length=value->length>BHFFI_OUTPUT_LIMIT?BHFFI_OUTPUT_LIMIT:value->length;if(a==b || (a>b?a-b<input.length:b-a<length)){*value=(bhffi_owned){0};return 1;}return 0;}\n");
        code.append("BHFFI_API const char *").append(prefix).append("_core(void){return \"").append(ForeignRuntimeInfo.coreHash()).append("\";}\n");
        for (var f : module.functions()) {
            code.append("extern int32_t ").append(f.symbol()).append("(bhffi_context *");
            for (var p : f.parameters()) code.append(',').append(cType(p.wire()));
            if (!f.result().wire().equals("status")) code.append(',').append(resultType(f.result())).append(" *");
            code.append(");\n");
        }
        var releases = new LinkedHashMap<String, String>();
        for (var f : module.functions()) if (!f.result().release().isEmpty()) {
            String type = resultType(f.result());
            String old = releases.putIfAbsent(f.result().release(), type);
            if (old != null && !old.equals(type)) throw new IllegalArgumentException("Release entry point has incompatible wire signatures: " + f.result().release());
        }
        releases.forEach((name, type) -> code.append("extern void ").append(name).append("(bhffi_context *,").append(type).append(" *);\n"));
        for (int i = 0; i < module.functions().size(); i++) function(code, module, prefix, i, module.functions().get(i));
        code.append("BHFFI_API int32_t ").append(prefix).append("_invoke(bhffi_context *ctx,uint32_t function,const bhffi_wire *args,uint32_t count,bhffi_wire *out){\n")
            .append("if(!out)return BHFFI_ARGUMENT; memset(out,0,sizeof(*out)); if(count && !args)return BHFFI_ARGUMENT; int32_t status=bhffi_v1_gateway_begin(ctx); if(status)return status; switch(function){\n");
        for (int i = 0; i < module.functions().size(); i++) code.append("case ").append(i).append(":status=").append(prefix).append('_').append(i).append("(ctx,args,count,out);break;\n");
        code.append("default:status=BHFFI_CONTRACT;break;}int32_t ended=bhffi_v1_gateway_end(ctx);return ended?ended:status;}\n");
        code.append("BHFFI_API void ").append(prefix).append("_release(bhffi_context *ctx,uint32_t function,bhffi_wire *out){if(!out)return;switch(function){\n");
        for (int i = 0; i < module.functions().size(); i++) {
            var r = module.functions().get(i).result();
            if (r.release().isEmpty()) continue;
            code.append("case ").append(i).append(":{ ").append(resultType(r)).append(" value=");
            if (r.wire().equals("status+owned-handle")) code.append("{out->scalar,(bhffi_resource *)out->data}");
            else code.append("{(uint8_t *)out->data,out->length}");
            code.append(';').append(r.release()).append("(ctx,&value);break;}\n");
        }
        code.append("default:break;}memset(out,0,sizeof(*out));}\n");
        return code.toString();
    }
    private static void function(StringBuilder code, ForeignManifest module, String prefix, int index, ForeignManifest.Function function) {
        code.append("static int32_t ").append(prefix).append('_').append(index)
            .append("(bhffi_context *ctx,const bhffi_wire *args,uint32_t count,bhffi_wire *out){\n")
            .append("(void)args;(void)out;if(count!=").append(function.parameters().size()).append(")return BHFFI_CONTRACT;int32_t status=BHFFI_OK;\n");
        boolean copied = function.parameters().stream().anyMatch(p -> Set.of("bytes", "utf8").contains(p.wire()));
        if (copied) code.append("uint64_t input_bytes=0;\n");
        for (int i = 0; i < function.parameters().size(); i++) {
            String wire = function.parameters().get(i).wire();
            code.append(cType(wire)).append(" a").append(i).append("={0};\n");
            if (Set.of("bytes", "utf8").contains(wire)) code.append("bhffi_owned copy").append(i).append("={0};\n");
        }
        String resultWire = function.result().wire();
        if (!resultWire.equals("status")) code.append(resultType(function.result())).append(" result={0};\n");
        for (int i = 0; i < function.parameters().size(); i++) {
            var p = function.parameters().get(i); String arg = "args[" + i + "]";
            switch (p.wire()) {
                case "bytes", "utf8" -> {
                    code.append("if(").append(arg).append(".length>BHFFI_INPUT_LIMIT-input_bytes){status=BHFFI_INPUT;goto done;}input_bytes+=").append(arg).append(".length;\n")
                        .append("if(!").append(arg).append(".data && ").append(arg).append(".length){status=BHFFI_ARGUMENT;goto done;}\n");
                    if (p.wire().equals("utf8")) code.append("if(bhffi_v1_utf8((bhffi_view){").append(arg).append(".data,").append(arg).append(".length})){status=BHFFI_ARGUMENT;goto done;}\n");
                    code.append("if(bhffi_v1_bytes_owned(ctx,(bhffi_view){").append(arg).append(".data,").append(arg).append(".length})){status=bhffi_v1_gateway_borrow(ctx,(bhffi_view){").append(arg).append(".data,").append(arg).append(".length});if(status)goto done;a").append(i).append("=(bhffi_view){").append(arg).append(".data,").append(arg).append(".length};}else{\n")
                        .append("status=bhffi_v1_bytes_reserve(ctx,").append(arg).append(".length,&copy").append(i).append(");if(status)goto done;\n")
                        .append("if(").append(arg).append(".length)memcpy(copy").append(i).append(".data,").append(arg).append(".data,(size_t)").append(arg).append(".length);\n")
                        .append("a").append(i).append("=(bhffi_view){copy").append(i).append(".data,copy").append(i).append(".length};}\n");
                }
                case "i64", "i32", "u8", "length", "bool" -> {
                    code.append("int64_t signed").append(i).append(";memcpy(&signed").append(i).append(",&").append(arg).append(".scalar,8);\n");
                    Long minimum = p.minimum(), maximum = p.maximum();
                    if (minimum == null) {
                        minimum = switch (p.wire()) { case "i32" -> (long)Integer.MIN_VALUE; case "u8", "bool", "length" -> 0L; default -> Long.MIN_VALUE; };
                        maximum = switch (p.wire()) { case "i32" -> (long)Integer.MAX_VALUE; case "u8" -> 255L; case "bool" -> 1L; case "length" -> 16777216L; default -> Long.MAX_VALUE; };
                    }
                    if (minimum != Long.MIN_VALUE) code.append("if(signed").append(i).append('<').append(integer(minimum)).append("){status=BHFFI_ARGUMENT;goto done;}\n");
                    if (maximum != Long.MAX_VALUE) code.append("if(signed").append(i).append('>').append(integer(maximum)).append("){status=BHFFI_ARGUMENT;goto done;}\n");
                    code.append('a').append(i).append("=(").append(cType(p.wire())).append(")signed").append(i).append(";\n");
                }
                case "f64" -> code.append("memcpy(&a").append(i).append(",&").append(arg).append(".scalar,8);\n");
                case "handle" -> code.append('a').append(i).append('=').append(arg).append(".scalar;status=bhffi_v1_resource_validate(ctx,a")
                    .append(i).append(',').append(resourceKind(module,p.type())).append(");if(status)goto done;\n");
                default -> throw new IllegalArgumentException("Unsupported checked wire");
            }
        }
        code.append("status=").append(function.symbol()).append("(ctx");
        for (int i = 0; i < function.parameters().size(); i++) code.append(",a").append(i);
        if (!resultWire.equals("status")) code.append(",&result");
        code.append(");\n");
        outputAliases(code,prefix,function);
        code.append("if(status)goto done;\n");
        switch (resultWire) {
            case "status" -> { }
            case "status+owned-bytes", "status+owned-utf8" -> {
                code.append("if((!result.data && result.length)||result.length>BHFFI_OUTPUT_LIMIT){status=BHFFI_CONTRACT;goto done;}\n");
                if (resultWire.equals("status+owned-utf8")) code.append("if(bhffi_v1_utf8((bhffi_view){result.data,result.length})){status=BHFFI_CONTRACT;goto done;}\n");
                code.append("out->data=result.data;out->length=result.length;result=(bhffi_owned){0};\n");
            }
            case "status+owned-handle" -> code.append("status=bhffi_v1_resource_transfer(ctx,&result,").append(resourceKind(module,function.result().resource()))
                .append(");if(status)goto done;out->scalar=result.token;out->data=(const uint8_t *)result.cleanup;result=(bhffi_handle){0};\n");
            case "status+f64" -> code.append("memcpy(&out->scalar,&result,8);\n");
            case "status+bool" -> code.append("if(result>1){status=BHFFI_CONTRACT;goto done;}out->scalar=result;\n");
            default -> code.append("out->scalar=(uint64_t)result;\n");
        }
        code.append("done:\n");
        outputAliases(code,prefix,function);
        if(resultWire.equals("status+owned-handle"))code.append("(void)bhffi_v1_resource_transfer(ctx,&result,").append(resourceKind(module,function.result().resource())).append(");\n");
        if (!function.result().release().isEmpty()) code.append(function.result().release()).append("(ctx,&result);\n");
        for (int i = function.parameters().size() - 1; i >= 0; i--) if (Set.of("bytes", "utf8").contains(function.parameters().get(i).wire())) code.append("bhffi_v1_bytes_release(ctx,&copy").append(i).append(");\n");
        code.append("return status;}\n");
    }
    private static void outputAliases(StringBuilder code,String prefix,ForeignManifest.Function function){
        if(!Set.of("status+owned-bytes","status+owned-utf8").contains(function.result().wire()))return;
        for(int i=0;i<function.parameters().size();i++)if(Set.of("bytes","utf8").contains(function.parameters().get(i).wire()))
            code.append("if(").append(prefix).append("_aliases(&result,a").append(i).append("))status=BHFFI_CONTRACT;\n");
    }
    private static String integer(long value) { return value == Long.MIN_VALUE ? "INT64_MIN" : value < 0 ? "(-INT64_C(" + -value + "))" : "INT64_C(" + value + ")"; }
    private static String resultType(ForeignManifest.Result result) {
        return switch (result.wire()) { case "status+owned-utf8", "status+owned-bytes" -> "bhffi_owned";
            case "status+owned-handle" -> "bhffi_handle"; default -> cType(result.wire().substring(7)); };
    }
    private static String cType(String wire) {
        return switch (wire) { case "i64" -> "int64_t"; case "i32" -> "int32_t"; case "u8", "bool" -> "uint8_t";
            case "f64" -> "double"; case "bytes", "utf8" -> "bhffi_view"; case "handle", "length" -> "uint64_t";
            default -> throw new IllegalArgumentException("Unknown wire type: " + wire); };
    }
}
