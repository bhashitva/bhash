// SPDX-License-Identifier: Apache-2.0
package dev.bhash;

import com.google.gson.*;
import dev.bhash.ffi.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.*;

class ForeignCGeneratorTest {
    @TempDir Path directory;
    @Test void compiledGatewayRunsAnUnrelatedPackageWithoutCompressionNameRecognition() throws Exception {
        String compiler = System.getProperty("bhash.ffi.cc", ""), archive = System.getProperty("bhash.ffi.archive", "");
        assumeTrue(!compiler.isEmpty() && !archive.isEmpty(), "Set bhash.ffi.cc and bhash.ffi.archive to run the compiled gateway fixture.");
        var json = JsonParser.parseString(ForeignManifestTest.CONTRACT).getAsJsonObject();
        var functions = json.getAsJsonArray("functions");
        functions.add(JsonParser.parseString("""
            {"owner":"परीक्षण.जोड़","name":"प्रतिध्वनि","symbol":"fixture_echo","execution":"owner-sync",
             "parameters":[{"name":"मूल","type":"पाठ","wire":"utf8","ownership":"borrow-call","marshal":"copy"}],
             "return":{"type":"पाठ","wire":"status+owned-utf8","release":"bhffi_v1_bytes_release"}}
            """));
        functions.add(JsonParser.parseString("""
            {"owner":"परीक्षण.जोड़","name":"स्तर","symbol":"fixture_level","execution":"owner-sync",
             "parameters":[{"name":"मूल","type":"पूर्णांक","wire":"i32","range":[0,9]}],
             "return":{"type":"पूर्णांक","wire":"status+i32"}}
            """));
        json.getAsJsonArray("resources").add(JsonParser.parseString("""
            {"name":"परीक्षण.गणक","kind":"counter","release":"fixture_counter_release"}
            """));
        functions.add(JsonParser.parseString("""
            {"owner":"परीक्षण.गणक","name":"बनाओ","symbol":"fixture_counter_create","execution":"owner-sync",
             "parameters":[{"name":"मूल","type":"पूर्णांक","wire":"i64"}],
             "return":{"type":"परीक्षण.गणक","wire":"status+owned-handle","resource":"परीक्षण.गणक","release":"fixture_counter_release"}}
            """));
        functions.add(JsonParser.parseString("""
            {"owner":"परीक्षण.गणक","name":"मूल्य","symbol":"fixture_counter_get","execution":"owner-sync",
             "parameters":[{"name":"गणक","type":"परीक्षण.गणक","wire":"handle","ownership":"borrow-call"}],
             "return":{"type":"पूर्णांक","wire":"status+i64"}}
            """));
        functions.add(JsonParser.parseString("""
            {"owner":"परीक्षण.जोड़","name":"गलतप्रतिध्वनि","symbol":"fixture_bad_echo","execution":"owner-sync",
             "parameters":[{"name":"मूल","type":"पाठ","wire":"utf8","ownership":"borrow-call","marshal":"copy"},{"name":"विधि","type":"पूर्णांक","wire":"i64"}],
             "return":{"type":"पाठ","wire":"status+owned-utf8","release":"bhffi_v1_bytes_release"}}
            """));
        var module = ForeignManifest.parse(json.toString(), false);
        Path gateway = directory.resolve("gateway.c"), fixture = directory.resolve("fixture.c");
        Files.writeString(gateway, ForeignCGenerator.generate(module));
        Files.writeString(fixture, """
            #include "bhffi.h"
            #include <assert.h>
            #include <limits.h>
            #include <string.h>
            #include <stdio.h>
            #include <stdlib.h>
            extern int32_t PREFIX_invoke(bhffi_context *,uint32_t,const bhffi_wire *,uint32_t,bhffi_wire *);
            extern void PREFIX_release(bhffi_context *,uint32_t,bhffi_wire *);
            int32_t fixture_add(bhffi_context *ctx,int64_t a,int64_t b,int64_t *out){
                (void)ctx;
                if((b>0 && a>INT64_MAX-b)||(b<0 && a<INT64_MIN-b))return BHFFI_ARGUMENT;
                *out=a+b;return 0;
            }
            int32_t fixture_echo(bhffi_context *ctx,bhffi_view input,bhffi_owned *out){
                int32_t status=bhffi_v1_bytes_reserve(ctx,input.length,out);
                if(!status && input.length)memcpy(out->data,input.data,(size_t)input.length);
                return status;
            }
            int32_t fixture_level(bhffi_context *ctx,int32_t level,int32_t *out){
                (void)ctx;assert(level>=0 && level<=9);*out=level;return 0;
            }
            int32_t fixture_bad_echo(bhffi_context *ctx,bhffi_view input,int64_t mode,bhffi_owned *out){
                (void)ctx;out->data=(uint8_t *)input.data;out->length=input.length;return mode?BHFFI_ARGUMENT:BHFFI_OK;
            }
            static int disposed=0;
            static void dispose_counter(void *pointer){++disposed;free(pointer);}
            int32_t fixture_counter_create(bhffi_context *ctx,int64_t value,bhffi_handle *out){
                int32_t status=bhffi_v1_resource_reserve(ctx,COUNTER_KIND,dispose_counter,out);
                if(status)return status;
                int64_t *pointer=malloc(sizeof(*pointer));
                if(!pointer){bhffi_v1_resource_queue(out->cleanup);bhffi_v1_drain(ctx);*out=(bhffi_handle){0};return BHFFI_RESOURCE;}
                *pointer=value;
                return bhffi_v1_resource_commit(ctx,out->token,pointer);
            }
            int32_t fixture_counter_get(bhffi_context *ctx,uint64_t token,int64_t *out){
                void *pointer=NULL;
                int32_t status=bhffi_v1_resource_enter(ctx,token,COUNTER_KIND,&pointer);
                if(status)return status;
                *out=*(int64_t *)pointer;
                return bhffi_v1_resource_leave(ctx,token);
            }
            void fixture_counter_release(bhffi_context *ctx,bhffi_handle *handle){
                if(!handle->token)return;
                assert(bhffi_v1_resource_close(ctx,handle->token)==0);
                bhffi_v1_resource_queue(handle->cleanup);*handle=(bhffi_handle){0};
                assert(bhffi_v1_drain(ctx)==0);
            }
            int main(void){
                bhffi_context *ctx=bhffi_v1_context_create();assert(ctx);
                bhffi_wire args[2]={{2,NULL,0},{3,NULL,0}},out={0};
                assert(PREFIX_invoke(ctx,0,args,2,&out)==0 && out.scalar==5);
                int64_t minus=-9;memcpy(&args[0].scalar,&minus,8);
                assert(PREFIX_invoke(ctx,0,args,2,&out)==0);
                int64_t value;memcpy(&value,&out.scalar,8);assert(value==-6);
                args[0].scalar=INT64_MAX;args[1].scalar=1;
                assert(PREFIX_invoke(ctx,0,args,2,&out)==BHFFI_ARGUMENT && !out.scalar);
                assert(PREFIX_invoke(ctx,0,args,1,&out)==BHFFI_CONTRACT);
                assert(PREFIX_invoke(ctx,99,NULL,0,&out)==BHFFI_CONTRACT);
                const uint8_t text[]={0xe0,0xa4,0xad,0xe0,0xa4,0xbe,0xe0,0xa4,0xb0,0xe0,0xa4,0xa4,0,0xf0,0x9f,0x98,0x80};
                args[0]=(bhffi_wire){0,text,sizeof(text)};
                assert(PREFIX_invoke(ctx,1,args,1,&out)==0);
                assert(out.length==sizeof(text) && !memcmp(out.data,text,sizeof(text)) && out.data!=text);
                PREFIX_release(ctx,1,&out);assert(!out.data && !out.length);
                args[1]=(bhffi_wire){0};
                assert(PREFIX_invoke(ctx,5,args,2,&out)==BHFFI_CONTRACT && !out.data);
                args[1].scalar=1;
                assert(PREFIX_invoke(ctx,5,args,2,&out)==BHFFI_CONTRACT && !out.data);
                assert(bhffi_v1_context_bytes(ctx)==0);
                const uint8_t bad[]={0xed,0xa0,0x80};args[0]=(bhffi_wire){0,bad,sizeof(bad)};
                assert(PREFIX_invoke(ctx,1,args,1,&out)==BHFFI_ARGUMENT && !out.data);
                args[0]=(bhffi_wire){9,NULL,0};assert(PREFIX_invoke(ctx,2,args,1,&out)==0 && out.scalar==9);
                args[0].scalar=10;assert(PREFIX_invoke(ctx,2,args,1,&out)==BHFFI_ARGUMENT);
                args[0].scalar=UINT64_MAX;assert(PREFIX_invoke(ctx,2,args,1,&out)==BHFFI_ARGUMENT);
                args[0].scalar=123;assert(PREFIX_invoke(ctx,3,args,1,&out)==0);
                bhffi_wire handle=out,alias=out;
                args[0]=handle;assert(PREFIX_invoke(ctx,4,args,1,&out)==0 && out.scalar==123);
                assert(bhffi_v1_resource_close(ctx,handle.scalar)==0 && disposed==1);
                args[0]=alias;assert(PREFIX_invoke(ctx,4,args,1,&out)==BHFFI_CONTRACT);
                PREFIX_release(ctx,3,&handle);assert(disposed==1);
                assert(bhffi_v1_context_bytes(ctx)==0);assert(bhffi_v1_context_shutdown(ctx)==0);
                puts("Generated typed gateway: arithmetic, copied UTF-8, invalid frames and checked narrowing passed");
                return 0;
            }
            """.replace("PREFIX", ForeignCGenerator.prefix(module)).replace("COUNTER_KIND", ForeignCGenerator.resourceKind(module,"परीक्षण.गणक")));
        Path executable = directory.resolve(System.getProperty("os.name").startsWith("Windows") ? "fixture.exe" : "fixture");
        Path includes = Path.of("src/main/resources/dev/bhash/ffi").toAbsolutePath();
        run(List.of(compiler, "-std=c17", "-Wall", "-Wextra", "-Werror", "-I" + includes, gateway.toString(), fixture.toString(), archive, "-o", executable.toString()));
        run(List.of(executable.toString()));
    }
    private void run(List<String> command) throws Exception {
        Path log = directory.resolve("process.log");
        Process process = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        if (!process.waitFor(90, TimeUnit.SECONDS)) { process.destroyForcibly(); fail("Gateway fixture timed out: " + command); }
        assertEquals(0, process.exitValue(), Files.readString(log));
    }
}
