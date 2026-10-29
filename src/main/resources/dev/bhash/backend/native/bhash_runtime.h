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
/* Protection is wholly inside C: generated callbacks never resume after longjmp. */
typedef int64_t (*bh_protected_callback)(void *);
void bh_exception_configure(const bh_type *, const void *);
int64_t bh_protect(bh_protected_callback, void *);
void *bh_exception_take(void);
_Noreturn void bh_throw(void *, const void *);
int bh_run(bh_protected_callback);
void bh_shutdown(void);
void bh_gc_stress(int enabled);
uint64_t bh_heap_objects(void);
uint64_t bh_heap_bytes(void);
void *bh_instance(void *value, const void *location);
void bh_reference_store(void *owner, uint64_t offset, void *value);
/* Collection metadata begins with the same immutable nominal metadata as classes. */
enum { BH_ARRAY=1, BH_LIST=2, BH_MAP=3, BH_BUFFER=4 };
enum { BH_INT=1, BH_BOOL=2, BH_TEXT=3, BH_REFERENCE=4, BH_ANY=5, BH_REAL=6, BH_BYTE=7 };
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
/* Immutable compiler metadata. Boxes: int, bool, text, real, in that order. */
typedef struct bh_data_info {
    const bh_collection_info *texts, *values, *object;
    const bh_type *boxes[4];
    const bh_class_info *box_info[4];
} bh_data_info;
void bh_data_configure(const bh_data_info *);
void *bh_data_split(const void *, const void *, const void *);
void *bh_data_join(void *, const void *, const void *);
void *bh_data_json_read(const void *, const void *);
void *bh_data_json_write(void *, const void *);
void *bh_collection_new(const bh_collection_info *, int64_t, const void *);
void *bh_array_dimensions(const bh_collection_info *, uint64_t, const int64_t *, const void *);
void *bh_collection_literal(const bh_collection_info *, uint64_t, const uint64_t *, const void *);
uint64_t bh_collection_call(void *, uint32_t, uint64_t, uint64_t, const bh_collection_info *, const void *);
/* Text payload: uint64 byte length, then UTF-8 bytes (including embedded NUL). */
int bh_print_licenses_if_requested(int argc, char **argv, const void *notices);
void bh_print_text(const void *);
void bh_print_int(int64_t);
/* Math and seeded randomness: process-local state; not a cryptographic generator. */
int64_t bh_numeric_abs_int(int64_t, const void *);
double bh_numeric_abs_real(double, const void *);
int64_t bh_numeric_min_int(int64_t, int64_t, const void *);
int64_t bh_numeric_max_int(int64_t, int64_t, const void *);
double bh_numeric_min_real(double, double, const void *);
double bh_numeric_max_real(double, double, const void *);
double bh_numeric_sqrt(double, const void *);
double bh_numeric_pow(double, double, const void *);
double bh_numeric_floor(double, const void *);
double bh_numeric_ceil(double, const void *);
double bh_numeric_round(double, const void *);
double bh_numeric_sin(double, const void *);
double bh_numeric_cos(double, const void *);
double bh_numeric_log(double, const void *);
double bh_numeric_exp(double, const void *);
double bh_numeric_pi(const void *);
void bh_numeric_seed(int64_t, const void *);
int64_t bh_numeric_random_int(int64_t, int64_t, const void *);
double bh_numeric_random_unit(const void *);
void bh_print_real(double);
int64_t bh_real_to_int(double, const void *);
void *bh_text_real(double, const void *);
double bh_text_parse_real(const void *, const void *);
void *bh_text_fixed(double, int64_t, const void *);
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
/* Packed buffers; operands/results carry scalar bits or allocation-base pointers. */
enum { BH_ENCODE, BH_DECODE, BH_READ_FILE, BH_WRITE_FILE, BH_SLICE, BH_TO_ARRAY, BH_FILL,
       BH_READ_INT, BH_WRITE_INT, BH_READ_REAL, BH_WRITE_REAL };
int64_t bh_to_byte(int64_t, const void *);
uint64_t bh_buffer_call(uint32_t, uint64_t, uint64_t, uint64_t, uint64_t, const bh_collection_info *, const void *);
/* Files: strict UTF-8, bounded whole-file text operations. */
int bh_file_supported(const void *);
int bh_file_exists(const void *, const void *);
void *bh_file_read_text(const void *, const void *);
void bh_file_write_text(const void *, const void *, const void *);
/* UTC timestamps: Unix epoch milliseconds, calendar years 0001..9999. */
int64_t bh_time_now(const void *);
int64_t bh_time_read(const void *, const void *);
void *bh_time_write(int64_t, const void *);
int64_t bh_time_year(int64_t, const void *);
int64_t bh_time_month(int64_t, const void *);
int64_t bh_time_day(int64_t, const void *);
#endif
