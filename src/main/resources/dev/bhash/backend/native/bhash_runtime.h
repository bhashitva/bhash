/* SPDX-License-Identifier: Apache-2.0 */
#ifndef BHASH_NATIVE_RUNTIME_H
#define BHASH_NATIVE_RUNTIME_H
#include <stddef.h>
#include <stdint.h>
/* Internal ABI. References are allocation-base payload pointers, never interior
   pointers. Type descriptors and root slots permit future reference updates. */
typedef struct bh_type {
    const char *name;
    size_t minimum_size;
    size_t reference_count;
    const size_t *reference_offsets;
} bh_type;
typedef struct bh_root_frame {
    struct bh_root_frame *previous;
    void **slots;
    uint64_t count;
} bh_root_frame;
void bh_roots_push(bh_root_frame *, void **slots, uint64_t count);
void bh_roots_pop(bh_root_frame *);
void *bh_alloc(const bh_type *, uint64_t bytes);
void bh_collect(void);
void bh_shutdown(void);
void bh_gc_stress(int enabled);
uint64_t bh_heap_objects(void);
uint64_t bh_heap_bytes(void);
void bh_reference_store(void *owner, uint64_t offset, void *value);
/* Text payload: uint64 byte length, then UTF-8 bytes (including embedded NUL). */
int bh_print_licenses_if_requested(int argc, char **argv, const void *notices);
void bh_print_text(const void *);
void bh_print_int(int64_t);
void bh_print_bool(int);
void bh_print_space(void);
void bh_print_end(void);
int bh_text_equal(const void *, const void *);
int64_t bh_divide(int64_t, int64_t, const void *location);
int64_t bh_remainder(int64_t, int64_t, const void *location);
#endif
