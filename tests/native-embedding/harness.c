/* SPDX-License-Identifier: Apache-2.0 */
#include "bhash_app.h"
#include <assert.h>
#include <stdio.h>
#include <string.h>
#ifdef _WIN32
#include <windows.h>
#else
#include <pthread.h>
#endif

#define VIEW(s) ((bhash_utf8_view){(const uint8_t *)(s), sizeof(s) - 1})

static void check_foreign(void) {
    int64_t output = 1;
    assert(bhash_number(42, &output, NULL) == BHASH_WRONG_THREAD && output == 0);
    assert(bhash_runtime_stop(NULL) == BHASH_WRONG_THREAD);
}

#ifdef _WIN32
static DWORD WINAPI foreign(void *unused) {
    (void)unused;
    check_foreign();
    return 0;
}
#else
static void *foreign(void *unused) {
    (void)unused;
    check_foreign();
    return NULL;
}
#endif

int main(void) {
    assert(bhash_embed_abi_version() == BHASH_EMBED_ABI_VERSION);
    int64_t number = 1;
    assert(bhash_number(42, &number, NULL) == BHASH_RUNTIME_STATE && number == 0);
    assert(bhash_runtime_start(NULL) == BHASH_OK);
    assert(bhash_runtime_start(NULL) == BHASH_RUNTIME_STATE);
#ifdef _WIN32
    HANDLE thread = CreateThread(NULL, 0, foreign, NULL, 0, NULL);
    assert(thread && WaitForSingleObject(thread, INFINITE) == WAIT_OBJECT_0);
    assert(CloseHandle(thread));
#else
    pthread_t thread;
    assert(pthread_create(&thread, NULL, foreign, NULL) == 0);
    assert(pthread_join(thread, NULL) == 0);
#endif
    assert(bhash_number(INT64_MIN, &number, NULL) == BHASH_OK && number == INT64_MIN);
    uint8_t boolean = 1;
    assert(bhash_invert(2, &boolean, NULL) == BHASH_INVALID_INPUT && boolean == 0);
    assert(bhash_invert(0, &boolean, NULL) == BHASH_OK && boolean == 1);
    bhash_handle saved = 1, other = 0;
    assert(bhash_join(VIEW("\xc0\x80"), VIEW(""), &saved, NULL) == BHASH_INVALID_INPUT && saved == 0);
    bhash_utf8_view oversized = {(const uint8_t *)"a", BHASH_EMBED_MAX_UTF8 + 1u};
    assert(bhash_join(oversized, VIEW(""), &saved, NULL) == BHASH_INVALID_INPUT && saved == 0);
    assert(bhash_join(VIEW("नमस्ते\0"), VIEW("भारत😀"), &saved, NULL) == BHASH_OK);
    assert(bhash_make_other(&other, NULL) == BHASH_OK);
    assert(bhash_runtime_stop(NULL) == BHASH_RUNTIME_STATE);
    bhash_bytes result = {0};
    assert(bhash_read(other, 0, &result, NULL) == BHASH_INVALID_HANDLE && !result.data);
    bhash_error error = {0};
    for (int i = 0; i < 100; i++) {
        assert(bhash_read(saved, 1, &result, &error) == BHASH_EXCEPTION && !result.data);
        assert(error.message.len == sizeof("परीक्षण अपवाद 😀") - 1);
        assert(memcmp(error.message.data, "परीक्षण अपवाद 😀", error.message.len) == 0);
        bhash_bytes_free(&error.message);
        assert(bhash_read(saved, 0, &result, &error) == BHASH_OK && !error.message.data);
        assert(result.len == sizeof("नमस्ते\0भारत😀!") - 1);
        assert(memcmp(result.data, "नमस्ते\0भारत😀!", result.len) == 0);
        bhash_bytes_free(&result);
        bhash_bytes_free(&result);
    }
    assert(bhash_handle_release(saved, NULL) == BHASH_OK);
    assert(bhash_handle_release(saved, NULL) == BHASH_INVALID_HANDLE);
    bhash_handle replacement = 0;
    assert(bhash_join(VIEW(""), VIEW(""), &replacement, NULL) == BHASH_OK && replacement != saved);
    assert(bhash_read(saved, 0, &result, NULL) == BHASH_INVALID_HANDLE);
    assert(bhash_handle_release(replacement, NULL) == BHASH_OK);
    assert(bhash_handle_release(other, NULL) == BHASH_OK);
    assert(bhash_runtime_stop(NULL) == BHASH_OK);
    assert(bhash_runtime_start(NULL) == BHASH_RUNTIME_STATE);
    puts("embedding passed");
}
