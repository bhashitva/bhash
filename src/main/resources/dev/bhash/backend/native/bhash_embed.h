/* SPDX-License-Identifier: Apache-2.0 */
#ifndef BHASH_EMBED_H
#define BHASH_EMBED_H
#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

#define BHASH_EMBED_ABI_VERSION 1
#define BHASH_EMBED_MAX_UTF8 (8u * 1024u * 1024u)
#define BHASH_API __attribute__((visibility("default")))

typedef int32_t bhash_status;
typedef uint64_t bhash_handle;
typedef struct { const uint8_t *data; size_t len; } bhash_utf8_view;
typedef struct { uint8_t *data; size_t len; } bhash_bytes;
typedef struct { bhash_bytes message; } bhash_error;

/* Views are borrowed for a call. Output/error buffers start empty and are
 * released by bhash_bytes_free, including before reuse. Handles and calls
 * belong to the thread that starts the runtime; detached bytes do not. */

enum {
    BHASH_OK = 0, BHASH_INVALID_INPUT = 1, BHASH_INVALID_HANDLE = 2,
    BHASH_RUNTIME_STATE = 3, BHASH_WRONG_THREAD = 4, BHASH_NO_MEMORY = 5,
    BHASH_EXCEPTION = 6, BHASH_INVALID_RESULT = 7
};

BHASH_API uint32_t bhash_embed_abi_version(void);
BHASH_API bhash_status bhash_runtime_start(bhash_error *error);
BHASH_API bhash_status bhash_runtime_stop(bhash_error *error);
BHASH_API bhash_status bhash_handle_release(bhash_handle handle, bhash_error *error);
BHASH_API void bhash_bytes_free(bhash_bytes *value);

#ifdef __cplusplus
}
#endif
#endif
