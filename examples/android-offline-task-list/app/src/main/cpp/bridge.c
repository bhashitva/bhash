/* SPDX-License-Identifier: Apache-2.0 */
#define _POSIX_C_SOURCE 200809L
#include <jni.h>
#include <limits.h>
#include <stdlib.h>
#include "bhash_app.h"

#define JNI_METHOD(name) Java_org_bhashitva_tasks_BhashBridge_##name

static void fail(JNIEnv *env, bhash_status status) {
    if (status == BHASH_OK || (*env)->ExceptionCheck(env)) return;
    const jclass type = (*env)->FindClass(env, "java/lang/IllegalStateException");
    if (type) (*env)->ThrowNew(env, type, "Bhash task store operation failed");
}

static bhash_utf8_view view(JNIEnv *env, jbyteArray bytes, jbyte **pin) {
    const jsize len = (*env)->GetArrayLength(env, bytes);
    *pin = len == 0 ? NULL : (*env)->GetByteArrayElements(env, bytes, NULL);
    return (bhash_utf8_view){(const uint8_t *)*pin, (size_t)len};
}

JNIEXPORT void JNICALL JNI_METHOD(start)(JNIEnv *env, jobject bridge, jboolean stress) {
    (void)bridge;
    if (bhash_embed_abi_version() != BHASH_EMBED_ABI_VERSION) {
        fail(env, BHASH_RUNTIME_STATE);
        return;
    }
    if (stress && setenv("BHASH_GC_STRESS", "1", 1) != 0) {
        fail(env, BHASH_RUNTIME_STATE);
        return;
    }
    fail(env, bhash_runtime_start(NULL));
}

JNIEXPORT jlong JNICALL JNI_METHOD(open)(JNIEnv *env, jobject bridge, jbyteArray path) {
    (void)bridge;
    jbyte *pin;
    const bhash_utf8_view input = view(env, path, &pin);
    if (input.len && !pin) return 0;
    bhash_handle store = 0;
    const bhash_status status = bhash_tasks_open(input, &store, NULL);
    if (pin) (*env)->ReleaseByteArrayElements(env, path, pin, JNI_ABORT);
    fail(env, status);
    return (jlong)store;
}

JNIEXPORT jlong JNICALL JNI_METHOD(add)(JNIEnv *env, jobject bridge, jlong store, jbyteArray title) {
    (void)bridge;
    jbyte *pin;
    const bhash_utf8_view input = view(env, title, &pin);
    if (input.len && !pin) return 0;
    int64_t id = 0;
    const bhash_status status = bhash_tasks_add((bhash_handle)store, input, &id, NULL);
    if (pin) (*env)->ReleaseByteArrayElements(env, title, pin, JNI_ABORT);
    fail(env, status);
    return (jlong)id;
}

JNIEXPORT jboolean JNICALL JNI_METHOD(setDone)(JNIEnv *env, jobject bridge, jlong store, jlong id, jboolean done) {
    (void)bridge;
    uint8_t found = 0;
    fail(env, bhash_tasks_set_done((bhash_handle)store, (int64_t)id, done, &found, NULL));
    return found ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jbyteArray JNICALL JNI_METHOD(list)(JNIEnv *env, jobject bridge, jlong store, jint filter) {
    (void)bridge;
    bhash_bytes snapshot = {0};
    const bhash_status status = bhash_tasks_list((bhash_handle)store, filter, &snapshot, NULL);
    jbyteArray result = NULL;
    if (status == BHASH_OK && snapshot.len <= INT_MAX) {
        result = (*env)->NewByteArray(env, (jsize)snapshot.len);
        if (result && snapshot.len)
            (*env)->SetByteArrayRegion(env, result, 0, (jsize)snapshot.len, (const jbyte *)snapshot.data);
    } else {
        fail(env, status == BHASH_OK ? BHASH_INVALID_RESULT : status);
    }
    bhash_bytes_free(&snapshot);
    return result;
}

JNIEXPORT void JNICALL JNI_METHOD(close)(JNIEnv *env, jobject bridge, jlong store) {
    (void)bridge;
    const bhash_status closed = bhash_tasks_close((bhash_handle)store, NULL);
    const bhash_status released = bhash_handle_release((bhash_handle)store, NULL);
    fail(env, closed != BHASH_OK ? closed : released);
}
