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
    size_t repeated_offset, repeated_stride;
    uint64_t repeated_reference_mask;
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
void bh_runtime_init(void);
void bh_shutdown(void);
void bh_gc_stress(int enabled);
uint64_t bh_heap_objects(void);
uint64_t bh_heap_bytes(void);
void *bh_instance(void *value, const void *location);
void bh_reference_store(void *owner, uint64_t offset, void *value);
/* Collection metadata begins with the same immutable nominal metadata as classes. */
enum { BH_ARRAY=1, BH_LIST=2, BH_MAP=3 };
enum { BH_INT=1, BH_BOOL=2, BH_TEXT=3, BH_REFERENCE=4, BH_ANY=5 };
enum { BH_SIZE, BH_EMPTY, BH_GET, BH_GET_OR, BH_SET, BH_CONTAINS, BH_ADD,
       BH_INSERT, BH_REMOVE, BH_CLEAR, BH_KEYS, BH_VALUES, BH_VERSION,
       BH_ITER_CHECK, BH_ITER_KEY, BH_ITER_VALUE };
typedef struct bh_class_info {
    const void *virtual_methods, *interface_methods;
    uint64_t type_count;
    const int64_t *type_ids;
} bh_class_info;
typedef struct bh_collection_info {
    bh_class_info nominal;
    uint64_t kind, key_kind, value_kind;
    const struct bh_collection_info *element, *key_list, *value_list;
} bh_collection_info;
void *bh_collection_new(const bh_collection_info *, int64_t, const void *);
void *bh_array_dimensions(const bh_collection_info *, uint64_t, const int64_t *, const void *);
void *bh_collection_literal(const bh_collection_info *, uint64_t, const uint64_t *, const void *);
uint64_t bh_collection_call(void *, uint32_t, uint64_t, uint64_t, const bh_collection_info *, const void *);
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
void *bh_text_concat(const void *, const void *, const void *location);
int64_t bh_text_length(const void *, const void *location);
void *bh_text_slice(const void *, int64_t, int64_t, const void *location);
int64_t bh_text_find(const void *, const void *, const void *location);
int bh_text_contains(const void *, const void *, const void *location);
int bh_text_starts(const void *, const void *, const void *location);
int bh_text_ends(const void *, const void *, const void *location);
void *bh_text_replace(const void *, const void *, const void *, const void *location);
void *bh_text_trim(const void *, const void *location);
void *bh_text_upper(const void *, const void *location);
void *bh_text_lower(const void *, const void *location);
void *bh_text_repeat(const void *, int64_t, const void *location);
void *bh_text_devanagari(const void *, const void *location);
void *bh_text_integer(int64_t, const void *location);
int64_t bh_text_parse_integer(const void *, const void *location);
void bh_text_fail(const void *, const void *location);
#endif
