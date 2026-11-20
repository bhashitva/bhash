/* SPDX-License-Identifier: Apache-2.0 */
#ifndef BHFFI_H
#define BHFFI_H
#include <stddef.h>
#include <stdint.h>
#if defined(_WIN32) && defined(BHFFI_SHARED)
#define BHFFI_API __declspec(dllexport)
#elif defined(__GNUC__) && defined(BHFFI_SHARED)
#define BHFFI_API __attribute__((visibility("default")))
#else
#define BHFFI_API
#endif
#ifdef __cplusplus
extern "C" {
#endif
#define BHFFI_ABI 1
#define BHFFI_INPUT_LIMIT UINT64_C(8388608)
#define BHFFI_OUTPUT_LIMIT UINT64_C(16777216)
#define BHFFI_CALL_LIMIT UINT64_C(33554432)
#define BHFFI_CONTEXT_LIMIT UINT64_C(67108864)
typedef struct bhffi_context bhffi_context;
typedef struct bhffi_resource bhffi_resource;
typedef struct { const uint8_t *data; uint64_t length; } bhffi_view;
typedef struct { uint8_t *data; uint64_t length; } bhffi_owned;
/* Internal by-reference invocation frame. Scalars contain their exact 64-bit
 * representation; data never points into a managed heap when passed to a shim. */
typedef struct { uint64_t scalar; const uint8_t *data; uint64_t length; } bhffi_wire;
enum bhffi_status {
    BHFFI_OK = 0, BHFFI_ARGUMENT = 1, BHFFI_INPUT = 2, BHFFI_OUTPUT = 3,
    BHFFI_CORRUPT = 4, BHFFI_DICTIONARY = 5, BHFFI_RESOURCE = 6,
    BHFFI_CONTRACT = 7
};
/* Contexts are confined to their creating execution thread. Shutdown rejects new
 * calls. Outstanding owned buffers retain the context until their release. */
BHFFI_API uint32_t bhffi_v1_abi(void);
BHFFI_API uint64_t bhffi_v1_layout(void);
BHFFI_API bhffi_context *bhffi_v1_context_create(void);
BHFFI_API int32_t bhffi_v1_context_shutdown(bhffi_context *context);
BHFFI_API uint64_t bhffi_v1_context_bytes(bhffi_context *context);
BHFFI_API int32_t bhffi_v1_check(bhffi_context *context);
BHFFI_API int32_t bhffi_v1_gateway_begin(bhffi_context *context);
BHFFI_API int32_t bhffi_v1_gateway_borrow(bhffi_context *context, bhffi_view input);
BHFFI_API int32_t bhffi_v1_gateway_end(bhffi_context *context);
BHFFI_API int32_t bhffi_v1_call_begin(bhffi_context *context, uint64_t input_bytes);
BHFFI_API int32_t bhffi_v1_call_end(bhffi_context *context);
BHFFI_API void *bhffi_v1_allocate(bhffi_context *context, uint64_t bytes);
BHFFI_API void bhffi_v1_free(bhffi_context *context, void *pointer);
BHFFI_API int32_t bhffi_v1_utf8(bhffi_view input);
BHFFI_API int32_t bhffi_v1_bytes_reserve(bhffi_context *context, uint64_t length, bhffi_owned *output);
BHFFI_API void bhffi_v1_bytes_release(bhffi_context *context, bhffi_owned *output);
BHFFI_API int32_t bhffi_v1_bytes_owned(bhffi_context *context, bhffi_view input);
/* Binding-author APIs. Reserve before creating the vendor resource. The opaque
 * cleanup record is held by runtime cleanup state, never by application code.
 * queue is allocation-free and may run on a GC/finalizer thread. drain invokes
 * native-only destructors on the owner, outside collection. */
typedef void (*bhffi_dispose)(void *payload);
typedef struct { uint64_t token; bhffi_resource *cleanup; } bhffi_handle;
BHFFI_API int32_t bhffi_v1_resource_reserve(bhffi_context *context, uint64_t kind,
    bhffi_dispose dispose, bhffi_handle *output);
BHFFI_API int32_t bhffi_v1_resource_commit(bhffi_context *context, uint64_t token, void *payload);
BHFFI_API int32_t bhffi_v1_resource_enter(bhffi_context *context, uint64_t token, uint64_t kind, void **payload);
BHFFI_API int32_t bhffi_v1_resource_validate(bhffi_context *context, uint64_t token, uint64_t kind);
/* Adopt exactly once. On rejection an already-adopted/foreign handle is cleared
 * so the producer's failure cleanup cannot close somebody else's resource. */
BHFFI_API int32_t bhffi_v1_resource_transfer(bhffi_context *context, bhffi_handle *handle, uint64_t kind);
BHFFI_API int32_t bhffi_v1_resource_leave(bhffi_context *context, uint64_t token);
BHFFI_API int32_t bhffi_v1_resource_close(bhffi_context *context, uint64_t token);
BHFFI_API void bhffi_v1_resource_queue(bhffi_resource *resource);
BHFFI_API int32_t bhffi_v1_drain(bhffi_context *context);
BHFFI_API int32_t bhffi_compression_v1_compress(bhffi_context *context,
    bhffi_view input, int32_t format, int32_t level, bhffi_owned *output);
BHFFI_API int32_t bhffi_compression_v1_decompress(bhffi_context *context,
    bhffi_view input, int32_t format, uint64_t limit, bhffi_owned *output);
BHFFI_API void bhffi_compression_v1_bytes_release(bhffi_context *context, bhffi_owned *output);
#ifdef BHFFI_TESTING
/* Fail the nth subsequent allocation; -1 disables injection. */
void bhffi_test_fail_after(bhffi_context *context, int64_t allocations);
#endif
#ifdef __cplusplus
}
#endif
#endif
