/* SPDX-License-Identifier: Apache-2.0 */
#include "bhffi.h"
#include <assert.h>
#include <stdio.h>
#include <stdlib.h>
#if defined(_WIN32)
#include <windows.h>
#else
#include <pthread.h>
#endif
static int disposed;
static void dispose_counter(void *payload) { ++disposed; free(payload); }
static bhffi_handle counter(bhffi_context *context) {
    bhffi_handle handle = {0};
    assert(bhffi_v1_resource_reserve(context, 42, dispose_counter, &handle) == BHFFI_OK);
    int64_t *value = malloc(sizeof(*value));
    assert(value); *value = 0;
    assert(bhffi_v1_resource_commit(context, handle.token, value) == BHFFI_OK);
    return handle;
}
typedef struct { bhffi_context *context; bhffi_handle handle; } worker_args;
#if defined(_WIN32)
static DWORD WINAPI worker(void *argument) {
#else
static void *worker(void *argument) {
#endif
    worker_args *args = argument;
    void *payload = (void *)(uintptr_t)1;
    assert(bhffi_v1_resource_enter(args->context, args->handle.token, 42, &payload) == BHFFI_CONTRACT);
    assert(payload == NULL);
    assert(bhffi_v1_resource_close(args->context, args->handle.token) == BHFFI_CONTRACT);
    bhffi_v1_resource_queue(args->handle.cleanup);
#if defined(_WIN32)
    return 0;
#else
    return NULL;
#endif
}
int main(void) {
    bhffi_context *context = bhffi_v1_context_create(), *other = bhffi_v1_context_create();
    assert(context && other);
    /* The generated gateway enforces budgets/reentry for every vendor, including
       those that use resource APIs without entering a codec operation. */
    bhffi_owned input={0};assert(bhffi_v1_bytes_reserve(context,8,&input)==BHFFI_OK);
    assert(bhffi_v1_gateway_begin(context)==BHFFI_OK);
    assert(bhffi_v1_gateway_begin(context)==BHFFI_CONTRACT);
    assert(bhffi_v1_context_shutdown(context)==BHFFI_CONTRACT);
    assert(bhffi_v1_gateway_borrow(context,(bhffi_view){input.data,input.length})==BHFFI_OK);
    assert(!bhffi_v1_allocate(context,BHFFI_CALL_LIMIT));
    void *workspace=bhffi_v1_allocate(context,BHFFI_CALL_LIMIT/2);assert(workspace);
    assert(bhffi_v1_call_begin(context,8)==BHFFI_OK);
    assert(!bhffi_v1_allocate(context,BHFFI_CALL_LIMIT/2));
    assert(bhffi_v1_call_end(context)==BHFFI_OK);
    bhffi_v1_free(context,workspace);
    assert(bhffi_v1_gateway_end(context)==BHFFI_OK);
    assert(bhffi_v1_gateway_end(context)==BHFFI_CONTRACT);
    bhffi_v1_bytes_release(context,&input);
    assert(bhffi_v1_context_bytes(context)==0);
    bhffi_handle handle = counter(context), unrelated = counter(other);
    bhffi_handle transferred=handle;
    assert(bhffi_v1_resource_transfer(context,&transferred,42)==BHFFI_OK);
    bhffi_handle duplicate=handle;
    assert(bhffi_v1_resource_transfer(context,&duplicate,42)==BHFFI_CONTRACT);
    assert(!duplicate.token && !duplicate.cleanup);
    bhffi_handle wrong_context=unrelated;
    assert(bhffi_v1_resource_transfer(context,&wrong_context,42)==BHFFI_CONTRACT);
    assert(!wrong_context.token && !wrong_context.cleanup);
    void *payload = NULL;
    assert(bhffi_v1_resource_enter(context, handle.token, 43, &payload) == BHFFI_CONTRACT);
    assert(bhffi_v1_resource_enter(other, handle.token, 42, &payload) == BHFFI_CONTRACT);
    assert(bhffi_v1_resource_enter(context, handle.token, 42, &payload) == BHFFI_OK);
    ++*(int64_t *)payload;
    assert(bhffi_v1_resource_enter(context, handle.token, 42, &payload) == BHFFI_CONTRACT);
    assert(bhffi_v1_resource_close(context, handle.token) == BHFFI_CONTRACT);
    assert(bhffi_v1_context_shutdown(context) == BHFFI_CONTRACT);
    assert(bhffi_v1_resource_leave(context, handle.token) == BHFFI_OK);
    assert(bhffi_v1_resource_enter(context, handle.token, 42, &payload) == BHFFI_OK);
    assert(*(int64_t *)payload == 1);
    assert(bhffi_v1_resource_leave(context, handle.token) == BHFFI_OK);
    assert(bhffi_v1_resource_close(context, handle.token) == BHFFI_OK);
    assert(bhffi_v1_resource_close(context, handle.token) == BHFFI_OK);
    assert(disposed == 1);
    assert(bhffi_v1_resource_enter(context, handle.token, 42, &payload) == BHFFI_CONTRACT);
    bhffi_v1_resource_queue(handle.cleanup);
    assert(bhffi_v1_drain(context) == BHFFI_OK);
    assert(bhffi_v1_context_bytes(context) == 0);
    assert(bhffi_v1_resource_close(context, handle.token) == BHFFI_CONTRACT);
    /* A constructor that fails after reservation owns no vendor payload. */
    bhffi_handle failed = {0};
    assert(bhffi_v1_resource_reserve(context, 42, dispose_counter, &failed) == BHFFI_OK);
    bhffi_v1_resource_queue(failed.cleanup);
    assert(bhffi_v1_drain(context) == BHFFI_OK && disposed == 1);
    /* A finalizer thread only queues; vendor destruction waits for the owner. */
    worker_args args = {context, counter(context)};
#if defined(_WIN32)
    HANDLE thread = CreateThread(NULL, 0, worker, &args, 0, NULL);
    assert(thread && WaitForSingleObject(thread, INFINITE) == WAIT_OBJECT_0);
    CloseHandle(thread);
#else
    pthread_t thread;
    assert(pthread_create(&thread, NULL, worker, &args) == 0);
    assert(pthread_join(thread, NULL) == 0);
#endif
    assert(disposed == 1);
    assert(bhffi_v1_drain(context) == BHFFI_OK && disposed == 2);
    assert(bhffi_v1_context_bytes(context) == 0);
    /* Reserve the complete bounded cleanup inventory before creating payloads. */
    bhffi_handle handles[256];
    for (unsigned i = 0; i < 256; ++i) handles[i] = counter(context);
    bhffi_handle rejected = {0};
    assert(bhffi_v1_resource_reserve(context, 42, dispose_counter, &rejected) == BHFFI_RESOURCE);
    assert(!rejected.token && !rejected.cleanup);
    for (unsigned i = 0; i < 256; ++i) bhffi_v1_resource_queue(handles[i].cleanup);
    assert(bhffi_v1_drain(context) == BHFFI_OK && disposed == 258);
    assert(bhffi_v1_context_bytes(context) == 0);
    assert(bhffi_v1_context_shutdown(context) == BHFFI_OK);
    /* Shutdown closes vendor state while its outstanding cleanup lease survives. */
    assert(bhffi_v1_context_shutdown(other) == BHFFI_OK && disposed == 259);
    bhffi_v1_resource_queue(unrelated.cleanup);
    assert(bhffi_v1_drain(other) == BHFFI_OK);
    puts("FFI resources: aliases, wrong context/kind/thread, busy/closed handles, deferred cleanup and shutdown leases passed");
    return 0;
}
