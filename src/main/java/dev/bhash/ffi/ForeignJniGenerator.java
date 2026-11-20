// SPDX-License-Identifier: Apache-2.0
package dev.bhash.ffi;

/** JNI adapter around the same generated gateway used by native and CLR.
 * No vendor entry point or compression operation is recognized here. */
public final class ForeignJniGenerator {
    private ForeignJniGenerator(){}
    public static String className(ForeignManifest module){return "BhashFFI_"+module.fingerprint().substring(0,24);}
    public static String adapterJava(ForeignManifest module,String sha,String resource,String suffix){
        if(!sha.matches("[a-f0-9]{64}") || !resource.matches("/dev/bhash/ffi/assets/[a-z0-9/_.-]+") || !java.util.Set.of(".dll",".so",".dylib").contains(suffix))throw new IllegalArgumentException("Invalid packaged binding identity");
        String name=className(module);
        return "final class "+name+" implements dev.bhash.ffi.NativeModules.Binding {\n"
            +"static String identity(){return \""+module.fingerprint()+":"+sha+"\";}\n"
            +"static dev.bhash.ffi.NativeModules.Binding binding(){return dev.bhash.ffi.NativeModules.load("+name+".class,\""+name+"\",\""+resource+"\",\""+sha+"\",\""+suffix+"\");}\n"
            +name+"(String path){try{System.load(path);}catch(LinkageError|SecurityException error){throw new dev.bhash.ffi.NativeModules.Failure(\"बाहरी पुस्तकालय लोड नहीं हुआ: \"+error.getMessage());}}\n"
            +"public native int abi();public native String coreHash();public native long layout();public native long create();public native long bytes(long context);public native int shutdown(long context);public native int drain(long context);\n"
            +"public native int close(long context,long token);public native void queue(long record);\n"
            +"public native Object[] invoke(long context,int function,long[] scalars,byte[][] buffers);\n"
            +"public native void release(long context,int function,long scalar,long record);}\n";
    }
    public static String generateC(ForeignManifest module){
        String prefix=ForeignCGenerator.prefix(module),jni="Java_"+className(module).replace("_","_1")+"_";
        String kinds=String.join(",",module.functions().stream().map(f->switch(f.result().wire()){case "status+owned-utf8","status+owned-bytes"->"1";case "status+owned-handle"->"2";default->"0";}).toList());
        return """
            /* Generated from the checked Bhash FFI contract; do not edit. */
            #include "bhffi.h"
            #include <jni.h>
            #include <string.h>
            extern int32_t PREFIX_invoke(bhffi_context *,uint32_t,const bhffi_wire *,uint32_t,bhffi_wire *);
            extern void PREFIX_release(bhffi_context *,uint32_t,bhffi_wire *);
            extern const char *PREFIX_core(void);
            static bhffi_context *context(jlong value){return (bhffi_context *)(uintptr_t)(uint64_t)value;}
            static int stress(JNIEnv *env){
            #ifdef BHFFI_GC_STRESS
                jclass system=(*env)->FindClass(env,"java/lang/System");if(!system)return 0;
                jmethodID gc=(*env)->GetStaticMethodID(env,system,"gc","()V");if(!gc){(*env)->DeleteLocalRef(env,system);return 0;}
                (*env)->CallStaticVoidMethod(env,system,gc);(*env)->DeleteLocalRef(env,system);
                return !(*env)->ExceptionCheck(env);
            #else
                (void)env;return 1;
            #endif
            }
            JNIEXPORT jint JNICALL JNIabi(JNIEnv *env,jobject self){(void)env;(void)self;return (jint)bhffi_v1_abi();}
            JNIEXPORT jlong JNICALL JNIlayout(JNIEnv *env,jobject self){(void)env;(void)self;return (jlong)bhffi_v1_layout();}
            JNIEXPORT jstring JNICALL JNIcoreHash(JNIEnv *env,jobject self){(void)self;return (*env)->NewStringUTF(env,PREFIX_core());}
            JNIEXPORT jlong JNICALL JNIcreate(JNIEnv *env,jobject self){(void)env;(void)self;return (jlong)(uintptr_t)bhffi_v1_context_create();}
            JNIEXPORT jlong JNICALL JNIbytes(JNIEnv *env,jobject self,jlong ctx){(void)env;(void)self;return (jlong)bhffi_v1_context_bytes(context(ctx));}
            JNIEXPORT jint JNICALL JNIshutdown(JNIEnv *env,jobject self,jlong ctx){(void)env;(void)self;return bhffi_v1_context_shutdown(context(ctx));}
            JNIEXPORT jint JNICALL JNIdrain(JNIEnv *env,jobject self,jlong ctx){(void)env;(void)self;return bhffi_v1_drain(context(ctx));}
            JNIEXPORT jint JNICALL JNIclose(JNIEnv *env,jobject self,jlong ctx,jlong token){(void)env;(void)self;return bhffi_v1_resource_close(context(ctx),(uint64_t)token);}
            JNIEXPORT void JNICALL JNIqueue(JNIEnv *env,jobject self,jlong record){(void)env;(void)self;bhffi_v1_resource_queue((bhffi_resource *)(uintptr_t)(uint64_t)record);}
            JNIEXPORT void JNICALL JNIrelease(JNIEnv *env,jobject self,jlong ctx,jint function,jlong scalar,jlong record){
                (void)env;(void)self;bhffi_wire output={(uint64_t)scalar,(const uint8_t *)(uintptr_t)(uint64_t)record,0};PREFIX_release(context(ctx),(uint32_t)function,&output);
            }
            JNIEXPORT jobjectArray JNICALL JNIinvoke(JNIEnv *env,jobject self,jlong ctx,jint function,jlongArray scalars,jobjectArray buffers){
                (void)self;
                const uint8_t result_kinds[]={KINDS};
                bhffi_context *runtime=context(ctx);bhffi_wire args[32],output={0};bhffi_owned copies[32];
                memset(args,0,sizeof(args));memset(copies,0,sizeof(copies));
                jobjectArray result=NULL;int32_t status=BHFFI_CONTRACT;uint32_t count=0;uint64_t input_bytes=0;
                if(function<0 || (uint32_t)function>=sizeof(result_kinds) || !scalars || !buffers)goto construct;
                jsize length=(*env)->GetArrayLength(env,scalars);
                if(length<0 || length>32 || (*env)->GetArrayLength(env,buffers)!=length)goto construct;
                count=(uint32_t)length;
                jlong values[32];(*env)->GetLongArrayRegion(env,scalars,0,length,values);
                if((*env)->ExceptionCheck(env))goto done;
                for(uint32_t i=0;i<count;++i){
                    memcpy(&args[i].scalar,&values[i],8);
                    jbyteArray data=(jbyteArray)(*env)->GetObjectArrayElement(env,buffers,(jsize)i);
                    if((*env)->ExceptionCheck(env))goto done;
                    if(data){
                        jsize size=(*env)->GetArrayLength(env,data);
                        if((uint64_t)size>BHFFI_INPUT_LIMIT-input_bytes){(*env)->DeleteLocalRef(env,data);status=BHFFI_INPUT;goto construct;}
                        input_bytes+=(uint64_t)size;
                        status=bhffi_v1_bytes_reserve(runtime,(uint64_t)size,&copies[i]);
                        if(status){(*env)->DeleteLocalRef(env,data);goto construct;}
                        (*env)->GetByteArrayRegion(env,data,0,size,(jbyte *)copies[i].data);(*env)->DeleteLocalRef(env,data);
                        if((*env)->ExceptionCheck(env))goto done;
                        args[i].data=copies[i].data;args[i].length=copies[i].length;
                    }
                }
                if(!stress(env))goto done;
                status=PREFIX_invoke(runtime,(uint32_t)function,args,count,&output);
                if(!stress(env))goto done;
            construct:;
                jclass object_type=(*env)->FindClass(env,"java/lang/Object");if(!object_type)goto done;
                result=(*env)->NewObjectArray(env,4,object_type,NULL);(*env)->DeleteLocalRef(env,object_type);if(!result || !stress(env))goto done;
                jclass long_type=(*env)->FindClass(env,"java/lang/Long");if(!long_type)goto done;
                jmethodID box=(*env)->GetStaticMethodID(env,long_type,"valueOf","(J)Ljava/lang/Long;");
                if(!box){(*env)->DeleteLocalRef(env,long_type);goto done;}
                jlong fields[3]={(jlong)status,(jlong)output.scalar,0};
                if(!status && result_kinds[function]==2)fields[2]=(jlong)(uintptr_t)output.data;
                for(unsigned i=0;i<3;++i){
                    jobject number=(*env)->CallStaticObjectMethod(env,long_type,box,fields[i]);
                    if((*env)->ExceptionCheck(env)){(*env)->DeleteLocalRef(env,long_type);goto done;}
                    (*env)->SetObjectArrayElement(env,result,i==2?3:(jsize)i,number);(*env)->DeleteLocalRef(env,number);
                    if((*env)->ExceptionCheck(env)){(*env)->DeleteLocalRef(env,long_type);goto done;}
                }
                (*env)->DeleteLocalRef(env,long_type);
                if(!status && result_kinds[function]==1){
                    jbyteArray data=(*env)->NewByteArray(env,(jsize)output.length);if(!data)goto done;
                    if(!stress(env)){(*env)->DeleteLocalRef(env,data);goto done;}
                    if(output.length)(*env)->SetByteArrayRegion(env,data,0,(jsize)output.length,(const jbyte *)output.data);
                    if(!(*env)->ExceptionCheck(env))(*env)->SetObjectArrayElement(env,result,2,data);
                    (*env)->DeleteLocalRef(env,data);
                    if((*env)->ExceptionCheck(env))goto done;
                }
                if(!status && result_kinds[function]==2)memset(&output,0,sizeof(output)); /* managed cleanup state takes ownership */
            done:
                PREFIX_release(runtime,(uint32_t)function,&output);
                for(uint32_t i=0;i<count;++i)bhffi_v1_bytes_release(runtime,&copies[i]);
                return result;
            }
            """.replace("PREFIX",prefix).replaceAll("\\bJNI(?=[a-z])",jni).replace("KINDS",kinds);
    }
}
