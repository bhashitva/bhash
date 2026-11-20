/* SPDX-License-Identifier: Apache-2.0 */
/* Conformance probe for copied JNI marshaling; not the language binding generator. */
#include "bhffi.h"
#include <jni.h>
#include <stdint.h>
static bhffi_context *context(jlong value) { return (bhffi_context *)(uintptr_t)(uint64_t)value; }
JNIEXPORT jlong JNICALL Java_CompressionBridgeProbe_create(JNIEnv *env, jclass type) {
    (void)env; (void)type;
    return (jlong)(uintptr_t)bhffi_v1_context_create();
}
JNIEXPORT jint JNICALL Java_CompressionBridgeProbe_shutdown(JNIEnv *env, jclass type, jlong value) {
    (void)env; (void)type; return bhffi_v1_context_shutdown(context(value));
}
JNIEXPORT jlong JNICALL Java_CompressionBridgeProbe_bytes(JNIEnv *env, jclass type, jlong value) {
    (void)env; (void)type; return (jlong)bhffi_v1_context_bytes(context(value));
}
static int collect(JNIEnv *env) {
    jclass system = (*env)->FindClass(env, "java/lang/System");
    if (!system) return 0;
    jmethodID gc = (*env)->GetStaticMethodID(env, system, "gc", "()V");
    if (!gc) { (*env)->DeleteLocalRef(env, system); return 0; }
    (*env)->CallStaticVoidMethod(env, system, gc);
    (*env)->DeleteLocalRef(env, system);
    return !(*env)->ExceptionCheck(env);
}
JNIEXPORT jint JNICALL Java_CompressionBridgeProbe_operation(JNIEnv *env, jclass type,
        jlong value, jbyteArray source, jint format, jint option, jboolean decode, jobjectArray holder) {
    (void)type;
    if (!source || !holder || (*env)->GetArrayLength(env, holder) != 1) return BHFFI_ARGUMENT;
    bhffi_context *runtime = context(value);
    bhffi_owned input = {0}, output = {0};
    jsize length = (*env)->GetArrayLength(env, source);
    int status = bhffi_v1_bytes_reserve(runtime, (uint64_t)length, &input);
    if (status) return status;
    (*env)->GetByteArrayRegion(env, source, 0, length, (jbyte *)input.data);
    if ((*env)->ExceptionCheck(env) || !collect(env)) goto done;
    bhffi_view view = {input.data, input.length};
    status = decode ? bhffi_compression_v1_decompress(runtime, view, format, (uint64_t)option, &output)
                    : bhffi_compression_v1_compress(runtime, view, format, option, &output);
    if (status) goto done;
    if (!collect(env)) goto done;
    jbyteArray result = (*env)->NewByteArray(env, (jsize)output.length);
    if (!result) goto done;
    (*env)->SetByteArrayRegion(env, result, 0, (jsize)output.length, (const jbyte *)output.data);
    if (!(*env)->ExceptionCheck(env) && collect(env)) (*env)->SetObjectArrayElement(env, holder, 0, result);
    (*env)->DeleteLocalRef(env, result);
done:
    bhffi_compression_v1_bytes_release(runtime, &output);
    bhffi_compression_v1_bytes_release(runtime, &input);
    return status;
}
