/* SPDX-License-Identifier: Apache-2.0 */
#define _POSIX_C_SOURCE 200809L
#include <jni.h>
#include <limits.h>
#include <stdlib.h>
#include "bhash_app.h"

#define JNI_METHOD(name) Java_org_bhashitva_androidstarter_GreetingBridge_##name

static void fail(JNIEnv *env, const char *message) {
    if ((*env)->ExceptionCheck(env)) return;
    jclass type = (*env)->FindClass(env, "java/lang/IllegalStateException");
    if (type) (*env)->ThrowNew(env, type, message);
}

JNIEXPORT void JNICALL JNI_METHOD(start)(JNIEnv *env, jobject bridge, jboolean stress) {
    (void)bridge;
    if (bhash_embed_abi_version() != BHASH_EMBED_ABI_VERSION ||
        (stress && setenv("BHASH_GC_STRESS", "1", 1) != 0) ||
        bhash_runtime_start(NULL) != BHASH_OK) {
        fail(env, "Could not start the Bhash runtime");
    }
}

JNIEXPORT jbyteArray JNICALL JNI_METHOD(greeting)(JNIEnv *env, jobject bridge, jbyteArray name) {
    (void)bridge;
    jsize length = (*env)->GetArrayLength(env, name);
    jbyte *data = length == 0 ? NULL : (*env)->GetByteArrayElements(env, name, NULL);
    if (length && !data) return NULL;
    bhash_utf8_view input = {(const uint8_t *)data, (size_t)length};
    bhash_bytes output = {0};
    bhash_status status = bhash_greeting(input, &output, NULL);
    if (data) (*env)->ReleaseByteArrayElements(env, name, data, JNI_ABORT);

    jbyteArray result = NULL;
    if (status == BHASH_OK && output.len <= INT_MAX) {
        result = (*env)->NewByteArray(env, (jsize)output.len);
        if (result && output.len) {
            (*env)->SetByteArrayRegion(env, result, 0, (jsize)output.len, (const jbyte *)output.data);
        }
    } else {
        fail(env, "Bhash greeting failed");
    }
    bhash_bytes_free(&output);
    return result;
}
