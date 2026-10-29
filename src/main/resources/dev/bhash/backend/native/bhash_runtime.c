/* SPDX-License-Identifier: Apache-2.0 */
#ifdef _MSC_VER
#define _CRT_SECURE_NO_WARNINGS
#endif
#include "bhash_runtime.h"
#include <limits.h>
#include <float.h>
#include <math.h>
#include <time.h>
_Static_assert(sizeof(double) == 8 && DBL_MANT_DIG == 53 && DBL_MAX_EXP == 1024, "binary64 required");
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <errno.h>
#include <sys/stat.h>
#include <setjmp.h>
#ifdef _MSC_VER
#include <io.h>
#include <fcntl.h>
#else
#include <unistd.h>
#endif
/* Precise, single-threaded stop-the-world collection. The intrusive mark worklist
   makes collection allocation-free and avoids recursive graph traversal. */
#ifdef _MSC_VER
typedef union { long double real; void *reference; int64_t integer; } bh_alignment;
#else
typedef max_align_t bh_alignment;
#endif
typedef struct bh_object {
    struct bh_object *next, *work_next, *hash_next;
    const bh_type *type;
    void *diagnostic_trace; /* Precise header reference; retained only with its exception. */
    size_t bytes;
    unsigned marked;
    uint64_t identity;
    bh_alignment alignment;
    unsigned char payload[];
} bh_object;
static bh_object *objects, *work;
static uint64_t live_bytes, live_objects, threshold = 1048576;
/* Membership index only: it is never a root or a source of reachability. */
static bh_object *initial_index[1024];
static bh_object **object_index = initial_index;
static size_t index_count = 1024;
static size_t object_bucket(const void *payload, size_t count) {
    uint64_t hash = (uint64_t)(uintptr_t)payload >> 3;
    hash ^= hash >> 30; hash *= UINT64_C(0xbf58476d1ce4e5b9); hash ^= hash >> 27;
    return (size_t)hash & (count - 1);
}
static void grow_object_index(void) {
    if (live_objects <= index_count || index_count > SIZE_MAX / 2 / sizeof(bh_object *)) return;
    size_t count = index_count * 2;
    bh_object **expanded = calloc(count, sizeof(bh_object *));
    if (!expanded) return; /* Existing chains remain correct under memory pressure. */
    for (bh_object *o = objects; o; o = o->next) {
        size_t bucket = object_bucket(o->payload, count);
        o->hash_next = expanded[bucket]; expanded[bucket] = o;
    }
    if (object_index != initial_index) free(object_index);
    object_index = expanded; index_count = count;
}
static bh_root_frame *roots;
static int console_skip_lf;
static void *pending_exception;
static void ***static_roots;
static uint64_t static_root_count;
static const bh_type *exception_descriptor;
static const void *exception_info;
typedef struct bh_cleanup {
    struct bh_cleanup *previous;
    void *memory;
    void (*dispose)(void *);
} bh_cleanup;
static bh_cleanup *cleanups;
typedef struct bh_handler {
    jmp_buf jump;
    struct bh_handler *previous;
    bh_root_frame *saved_roots;
    bh_cleanup *saved_cleanups;
    bh_trace_frame *saved_trace;
} bh_handler;
static bh_handler *handlers;
static bh_trace_frame *trace_frames;
static _Noreturn void runtime_error(const void *, const char *);
static _Noreturn void raise_failure_text(const void *, const void *);
static _Noreturn void raise_failure(const void *, const char *);
static uint64_t random_state;
static int stress, collecting, report_stats;
static uint64_t allocations, collections, peak_bytes, next_identity = 1;
static void emergency_write(const char *s) {
    size_t remaining = strlen(s);
    while (remaining) {
#ifdef _MSC_VER
        int count = _write(2, s, (unsigned int)remaining);
#else
        ssize_t count = write(2, s, remaining);
#endif
        if (count < 0 && errno == EINTR) continue;
        if (count <= 0) return;
        s += count; remaining -= (size_t)count;
    }
}
static void fatal(const char *message) {
    emergency_write("भाष: "); emergency_write(message); emergency_write("\n");
    _Exit(1); /* No managed allocation, stdio initialization or exit callbacks. */
}
static bh_object *find_object(const void *payload) {
    for (bh_object *o = object_index[object_bucket(payload, index_count)]; o; o = o->hash_next)
        if (o->payload == payload) return o;
    return NULL; /* Also covers immortal literal text and NULL roots. */
}
static void mark(void *payload) {
    bh_object *o = find_object(payload);
    if (!o || o->marked) return;
    o->marked = 1; o->work_next = work; work = o;
}
void bh_roots_push(bh_root_frame *f, void **slots, uint64_t count) {
    f->previous = roots; f->slots = slots; f->count = count; roots = f;
}
void bh_roots_pop(bh_root_frame *f) {
    if (roots != f) fatal("आंतरिक मूल-संदर्भ क्रम अमान्य है।");
    roots = f->previous;
}
void bh_collect(void) {
    if (collecting) fatal("आंतरिक संग्रह पुनः प्रवेश अमान्य है।");
    collecting = 1;
    ++collections;
    mark(pending_exception);
    for(uint64_t i=0;i<static_root_count;++i)mark(*static_roots[i]);
    for (bh_root_frame *f = roots; f; f = f->previous)
        for (uint64_t i = 0; i < f->count; ++i) mark(f->slots[i]);
    while (work) {
        bh_object *o = work; work = o->work_next;
        mark(o->diagnostic_trace);
        for (size_t i = 0; i < o->type->reference_count; ++i) {
            void *value;
            memcpy(&value, o->payload + o->type->reference_offsets[i], sizeof value);
            mark(value);
        }
        const bh_type *t = o->type;
        if (t->repeated_stride) for (size_t at = t->repeated_offset; at < o->bytes; at += t->repeated_stride)
            for (size_t lane = 0; lane < t->repeated_stride / 8; ++lane)
                if (t->repeated_reference_mask & (UINT64_C(1) << lane)) {
                    void *value; memcpy(&value, o->payload + at + lane * 8, sizeof value); mark(value);
                }
    }
    bh_object **link = &objects;
    while (*link) {
        bh_object *o = *link;
        if (o->marked) { o->marked = 0; link = &o->next; }
        else {
            bh_object **bucket = &object_index[object_bucket(o->payload, index_count)];
            while (*bucket != o) bucket = &(*bucket)->hash_next;
            *bucket = o->hash_next;
            *link = o->next; live_bytes -= o->bytes + offsetof(bh_object, payload); --live_objects; free(o);
        }
    }
    threshold = live_bytes > (UINT64_MAX - 1048576) / 2
        ? UINT64_MAX : live_bytes * 2 + 1048576;
    collecting = 0;
}
void *bh_alloc(const bh_type *type, uint64_t requested) {
    if (collecting || !type || requested < type->minimum_size)
        fatal("आंतरिक आवंटन विवरण अमान्य है।");
    if (requested > SIZE_MAX - offsetof(bh_object, payload) ||
        requested > UINT64_MAX - offsetof(bh_object, payload) ||
        requested + offsetof(bh_object, payload) > UINT64_MAX - live_bytes)
        fatal("स्मृति आवंटन सीमा से बाहर है।");
    size_t bytes = (size_t)requested;
    for (size_t i = 0; i < type->reference_count; ++i)
        if (bytes < sizeof(void *) || type->reference_offsets[i] > bytes - sizeof(void *))
            fatal("आंतरिक संदर्भ क्षेत्र अमान्य है।");
    if (type->repeated_stride) {
        if (type->repeated_stride % 8 || type->repeated_stride > 512 || type->repeated_offset % 8 ||
            type->repeated_offset > bytes || (bytes - type->repeated_offset) % type->repeated_stride ||
            (type->repeated_stride < 512 && (type->repeated_reference_mask >> (type->repeated_stride / 8))))
            fatal("आंतरिक आवंटन विवरण अमान्य है।");
    } else if (type->repeated_offset || type->repeated_reference_mask) fatal("आंतरिक आवंटन विवरण अमान्य है।");
    if (stress || live_bytes + requested + offsetof(bh_object, payload) > threshold) bh_collect();
    bh_object *o = calloc(1, offsetof(bh_object, payload) + bytes);
    if (!o) {
        bh_collect(); o = calloc(1, offsetof(bh_object, payload) + bytes);
        if (!o) fatal("स्मृति उपलब्ध नहीं है।");
    }
    if (!next_identity) fatal("वस्तु पहचान सीमा से बाहर है।");
    o->identity = next_identity++;
    o->type = type; o->bytes = bytes; o->next = objects; objects = o;
    live_bytes += requested + offsetof(bh_object, payload); ++live_objects;
    size_t bucket = object_bucket(o->payload, index_count);
    o->hash_next = object_index[bucket]; object_index[bucket] = o;
    grow_object_index();
    ++allocations;
    if (live_bytes > peak_bytes) peak_bytes = live_bytes;
    return o->payload;
}
void bh_reference_store(void *owner, uint64_t offset, void *value) {
    bh_object *o = find_object(owner);
    if (!o || o->bytes < sizeof(void *) || offset > o->bytes - sizeof(void *))
        fatal("आंतरिक संदर्भ लेखन अमान्य है।");
    int declared = 0;
    for (size_t i = 0; i < o->type->reference_count; ++i)
        if (o->type->reference_offsets[i] == offset) declared = 1;
    if (o->type->repeated_stride && offset >= o->type->repeated_offset && offset % 8 == 0) {
        size_t lane = ((size_t)offset - o->type->repeated_offset) % o->type->repeated_stride / 8;
        if (o->type->repeated_reference_mask & (UINT64_C(1) << lane)) declared = 1;
    }
    if (!declared) fatal("आंतरिक संदर्भ क्षेत्र अमान्य है।");
    memcpy(o->payload + offset, &value, sizeof value);
}
static int environment_flag(const char *name) {
#ifdef _MSC_VER
    char *value = NULL; size_t length = 0;
    if (_dupenv_s(&value, &length, name)) return 0;
    int enabled = value && strcmp(value, "1") == 0;
    free(value); return enabled;
#else
    const char *value = getenv(name);
    return value && strcmp(value, "1") == 0;
#endif
}
void bh_runtime_init(void) {
    console_skip_lf=0;
#ifdef _MSC_VER
    (void)_setmode(_fileno(stdin),_O_BINARY);
#endif
    struct timespec now = {0,0}; (void)timespec_get(&now, TIME_UTC);
    random_state = (uint64_t)now.tv_sec ^ ((uint64_t)now.tv_nsec << 32) ^ (uint64_t)clock();
    stress = environment_flag("BHASH_GC_STRESS");
    report_stats = environment_flag("BHASH_GC_STATS");
}
void bh_shutdown(void) {
    if (roots || handlers || cleanups || pending_exception || trace_frames) fatal("आंतरिक मूल-संदर्भ शेष है।");
    for(uint64_t i=0;i<static_root_count;++i)*static_roots[i]=NULL;
    static_roots=NULL;static_root_count=0;
    bh_collect();
    if (object_index != initial_index) free(object_index);
    memset(initial_index, 0, sizeof initial_index);
    object_index = initial_index; index_count = 1024;
    if (report_stats) fprintf(stderr, "bhash-gc: allocations=%llu collections=%llu live=%llu peak_bytes=%llu\n",
        (unsigned long long)allocations, (unsigned long long)collections,
        (unsigned long long)live_objects, (unsigned long long)peak_bytes);
}
void bh_gc_stress(int enabled) { stress = enabled != 0; }
uint64_t bh_heap_objects(void) { return live_objects; }
uint64_t bh_heap_bytes(void) { return live_bytes; }
static uint64_t text_length(const void *text) {
    uint64_t size; memcpy(&size, text, sizeof size); return size;
}
void bh_print_text(const void *text) {
    uint64_t size = text_length(text);
    if (size > SIZE_MAX) fatal("पाठ सीमा से बाहर है।");
    if (size && fwrite((const unsigned char *)text + 8, 1, (size_t)size, stdout) != size)
        fatal("आउटपुट लिखा नहीं जा सका।");
}
int bh_text_equal(const void *left, const void *right) {
    uint64_t a = text_length(left), b = text_length(right);
    return a == b && (!a || memcmp((const unsigned char *)left + 8,
                                 (const unsigned char *)right + 8, (size_t)a) == 0);
}
void bh_print_int(int64_t value) {
    uint64_t n = value < 0 ? UINT64_C(0) - (uint64_t)value : (uint64_t)value;
    unsigned char digits[20]; size_t count = 0;
    do { digits[count++] = (unsigned char)(n % 10); n /= 10; } while (n);
    if (value < 0) fputc('-', stdout);
    while (count) {
        unsigned char utf8[3] = {0xE0, 0xA5, (unsigned char)(0xA6 + digits[--count])};
        fwrite(utf8, 1, 3, stdout);
    }
}
void bh_print_bool(int value) { fputs(value ? "सत्य" : "असत्य", stdout); }
void bh_print_space(void) { fputc(' ', stdout); }
void bh_print_end(void) {
    fputc('\n', stdout);
    if (fflush(stdout) || ferror(stdout)) fatal("आउटपुट लिखा नहीं जा सका।");
}
static _Noreturn void runtime_error(const void *location, const char *message) {
    if (exception_descriptor) raise_failure(location, message);
    fputs("भाष: ", stderr);
    uint64_t size = text_length(location);
    fwrite((const unsigned char *)location + 8, 1, (size_t)size, stderr);
    fputs(": ", stderr); fputs(message, stderr); fputc('\n', stderr);
    fflush(stderr); exit(1);
}
void *bh_instance(void *value, const void *location) {
    if (!value) runtime_error(location, "रिक्त वस्तु के सदस्य का उपयोग नहीं किया जा सकता।");
    return value;
}
int64_t bh_divide(int64_t a, int64_t b, const void *location) {
    if (!b) runtime_error(location, "शून्य से भाग देना संभव नहीं है।");
    return a == INT64_MIN && b == -1 ? INT64_MIN : a / b;
}
int64_t bh_remainder(int64_t a, int64_t b, const void *location) {
    if (!b) runtime_error(location, "शून्य से शेषफल निकालना संभव नहीं है।");
    return a == INT64_MIN && b == -1 ? 0 : a % b;
}

_Static_assert(BHASH_NATIVE_ABI_VERSION==15,"Compiler/runtime ABI mismatch");
void bh_runtime_abi_v15(void) {}
int bh_print_build_info_if_requested(int argc,char **argv,const char *info){
    if(argc!=2 || strcmp(argv[1],"--bhash-build-info"))return 0;
    fputs(info,stdout);fputc('\n',stdout);return 1;
}
int bh_print_licenses_if_requested(int argc, char **argv, const void *notices) {
    if (argc == 2 && strcmp(argv[1], "--bhash-license") == 0) {
        bh_print_text(notices);
        bh_print_end();
        return 1;
    }
    return 0;
}

/* Heap-backed immutable text. Every allocating helper protects/reloads its own
   arguments as well as relying on compiler roots. No interior pointer survives
   an allocation; offsets and lengths do. Literal text uses the same payload ABI. */
static const bh_type text_type = {"text", sizeof(uint64_t), 0, NULL, 0, 0, 0};
static unsigned char *text_data(const void *s) { return (unsigned char *)s + 8; }
static void *new_text(uint64_t size, const void *location) {
    if (size > SIZE_MAX - 8 || size > UINT64_MAX - 8)
        runtime_error(location, "पाठ सीमा से बाहर है।");
    void *result = bh_alloc(&text_type, size + 8);
    memcpy(result, &size, 8);
    return result;
}
static uint64_t checked_add(uint64_t a, uint64_t b, const void *location) {
    if (b > UINT64_MAX - a) runtime_error(location, "पाठ सीमा से बाहर है।");
    return a + b;
}
/* Inputs are valid UTF-8 from checked literals or the text operations below. */
static uint32_t scalar(const unsigned char *bytes, uint64_t *offset) {
    uint32_t c = bytes[(*offset)++];
    int extra = c < 0x80 ? 0 : c < 0xE0 ? 1 : c < 0xF0 ? 2 : 3;
    c &= extra == 0 ? 0x7F : extra == 1 ? 0x1F : extra == 2 ? 0x0F : 0x07;
    while (extra--) c = (c << 6) | (bytes[(*offset)++] & 0x3F);
    return c;
}
int64_t bh_text_length(const void *s, const void *location) {
    uint64_t n = text_length(s), i = 0, count = 0;
    while (i < n) { scalar(text_data(s), &i); ++count; }
    if (count > INT64_MAX) runtime_error(location, "पाठ सीमा से बाहर है।");
    return (int64_t)count;
}
static uint64_t scalar_byte(const void *s, int64_t at, const void *location) {
    if (at < 0) runtime_error(location, "पाठ अंश सीमा से बाहर है।");
    uint64_t n = text_length(s), i = 0, count = 0;
    while (i < n && count < (uint64_t)at) { scalar(text_data(s), &i); ++count; }
    if (count != (uint64_t)at) runtime_error(location, "पाठ अंश सीमा से बाहर है।");
    return i;
}
void *bh_text_concat(const void *a, const void *b, const void *location) {
    void *slots[2] = {(void *)a, (void *)b}; bh_root_frame frame;
    bh_roots_push(&frame, slots, 2);
    uint64_t na = text_length(a), nb = text_length(b);
    void *result = new_text(checked_add(na, nb, location), location);
    memcpy(text_data(result), text_data(slots[0]), (size_t)na);
    memcpy(text_data(result) + na, text_data(slots[1]), (size_t)nb);
    bh_roots_pop(&frame); return result;
}
void *bh_text_slice(const void *s, int64_t start, int64_t end, const void *location) {
    if (end < start) runtime_error(location, "पाठ अंश सीमा से बाहर है।");
    uint64_t a = scalar_byte(s, start, location), b = scalar_byte(s, end, location);
    void *slot = (void *)s; bh_root_frame frame; bh_roots_push(&frame, &slot, 1);
    void *result = new_text(b - a, location);
    memcpy(text_data(result), text_data(slot) + a, (size_t)(b - a));
    bh_roots_pop(&frame); return result;
}
static int matches(const void *s, uint64_t at, const void *needle) {
    uint64_t n = text_length(s), m = text_length(needle);
    return at <= n && m <= n - at && (!m || memcmp(text_data(s) + at, text_data(needle), (size_t)m) == 0);
}
int64_t bh_text_find(const void *s, const void *needle, const void *location) {
    uint64_t n = text_length(s), i = 0; int64_t count = 0; (void)location;
    for (;;) {
        if (matches(s, i, needle)) return count;
        if (i == n) return -1;
        scalar(text_data(s), &i); ++count;
    }
}
int bh_text_contains(const void *s, const void *needle, const void *location) {
    return bh_text_find(s, needle, location) >= 0;
}
int bh_text_starts(const void *s, const void *needle, const void *location) {
    (void)location; return matches(s, 0, needle);
}
int bh_text_ends(const void *s, const void *needle, const void *location) {
    (void)location; uint64_t n = text_length(s), m = text_length(needle);
    return m <= n && matches(s, n - m, needle);
}
void *bh_text_replace(const void *s, const void *old, const void *replacement, const void *location) {
    uint64_t n = text_length(s), m = text_length(old), r = text_length(replacement), size = 0;
    if (!m) runtime_error(location, "विभाजक या बदलने वाला पाठ खाली नहीं हो सकता।");
    for (uint64_t i = 0; i < n;) {
        int found = matches(s, i, old);
        size = checked_add(size, found ? r : 1, location); i += found ? m : 1;
    }
    void *slots[3] = {(void *)s, (void *)old, (void *)replacement}; bh_root_frame frame;
    bh_roots_push(&frame, slots, 3);
    void *result = new_text(size, location); uint64_t out = 0;
    for (uint64_t i = 0; i < n;) {
        if (matches(slots[0], i, slots[1])) {
            memcpy(text_data(result) + out, text_data(slots[2]), (size_t)r); i += m; out += r;
        } else text_data(result)[out++] = text_data(slots[0])[i++];
    }
    bh_roots_pop(&frame); return result;
}
static int white(uint32_t c) {
    return (c >= 9 && c <= 13) || c == 32 || c == 0x85 || c == 0xA0 || c == 0x1680 ||
        (c >= 0x2000 && c <= 0x200A) || c == 0x2028 || c == 0x2029 || c == 0x202F || c == 0x205F || c == 0x3000;
}
void *bh_text_trim(const void *s, const void *location) {
    uint64_t n = text_length(s), i = 0, first = n, last = n;
    while (i < n) {
        uint64_t begin = i; uint32_t c = scalar(text_data(s), &i);
        if (!white(c)) { if (first == n) first = begin; last = i; }
    }
    void *slot = (void *)s; bh_root_frame frame; bh_roots_push(&frame, &slot, 1);
    void *result = new_text(last - first, location);
    memcpy(text_data(result), text_data(slot) + first, (size_t)(last - first));
    bh_roots_pop(&frame); return result;
}
static void *text_case(const void *s, int mode, const void *location) {
    uint64_t n = text_length(s), size = n;
    if (mode == 3) for (uint64_t i = 0; i < n; ++i)
        if (text_data(s)[i] >= '0' && text_data(s)[i] <= '9') size = checked_add(size, 2, location);
    void *slot = (void *)s; bh_root_frame frame; bh_roots_push(&frame, &slot, 1);
    void *result = new_text(size, location); uint64_t out = 0;
    for (uint64_t i = 0; i < n; ++i) {
        unsigned char c = text_data(slot)[i];
        if (mode == 3 && c >= '0' && c <= '9') {
            text_data(result)[out++] = 0xE0; text_data(result)[out++] = 0xA5;
            text_data(result)[out++] = (unsigned char)(0xA6 + c - '0');
        } else {
            if (mode == 1 && c >= 'a' && c <= 'z') c = (unsigned char)(c - 32);
            if (mode == 2 && c >= 'A' && c <= 'Z') c = (unsigned char)(c + 32);
            text_data(result)[out++] = c;
        }
    }
    bh_roots_pop(&frame); return result;
}
void *bh_text_upper(const void *s, const void *location) { return text_case(s, 1, location); }
void *bh_text_lower(const void *s, const void *location) { return text_case(s, 2, location); }
void *bh_text_devanagari(const void *s, const void *location) { return text_case(s, 3, location); }
void *bh_text_repeat(const void *s, int64_t count, const void *location) {
    uint64_t n = text_length(s), units = 0, i = 0;
    while (i < n) units += scalar(text_data(s), &i) > 0xFFFF ? 2 : 1;
    if (count < 0 || (units && (uint64_t)count > UINT64_C(1048576) / units))
        runtime_error(location, "दोहराए गए पाठ की सीमा पार हो गई।");
    uint64_t size = units ? n * (uint64_t)count : 0;
    void *slot = (void *)s; bh_root_frame frame; bh_roots_push(&frame, &slot, 1);
    void *result = new_text(size, location);
    if (n) for (uint64_t at = 0; at < size; at += n) memcpy(text_data(result) + at, text_data(slot), (size_t)n);
    bh_roots_pop(&frame); return result;
}
void *bh_text_integer(int64_t value, const void *location) {
    unsigned char buffer[21]; size_t at = sizeof buffer;
    uint64_t n = value < 0 ? UINT64_C(0) - (uint64_t)value : (uint64_t)value;
    do { buffer[--at] = (unsigned char)('0' + n % 10); n /= 10; } while (n);
    if (value < 0) buffer[--at] = '-';
    void *result = new_text(sizeof buffer - at, location);
    memcpy(text_data(result), buffer + at, sizeof buffer - at); return result;
}
int64_t bh_text_parse_integer(const void *s, const void *location) {
    uint64_t n = text_length(s), i = 0, value = 0; int negative = 0, script = 0, overflow = 0;
    if (n && (text_data(s)[0] == '+' || text_data(s)[0] == '-')) negative = text_data(s)[i++] == '-';
    if (i == n) runtime_error(location, "अमान्य पूर्णांक पाठ।");
    uint64_t limit = negative ? UINT64_C(9223372036854775808) : INT64_MAX;
    while (i < n) {
        uint32_t cp = scalar(text_data(s), &i); int current, digit;
        if (cp >= '0' && cp <= '9') { current = 1; digit = (int)(cp - '0'); }
        else if (cp >= 0x966 && cp <= 0x96F) { current = 2; digit = (int)(cp - 0x966); }
        else { runtime_error(location, "अमान्य पूर्णांक पाठ।"); return 0; }
        if (script && current != script) runtime_error(location, "अमान्य पूर्णांक पाठ।");
        script = current;
        if (value > (limit - (uint64_t)digit) / 10) overflow = 1;
        if (!overflow) value = value * 10 + (uint64_t)digit;
    }
    if (overflow) runtime_error(location, "पूर्णांक 64-बिट सीमा से बाहर है।");
    if (negative && value == UINT64_C(9223372036854775808)) return INT64_MIN;
    return negative ? -(int64_t)value : (int64_t)value;
}

/* libm results may differ in their last bits from Java StrictMath/.NET.
   Domain, range, finite-input and signed-zero rules remain explicit here. */
static double numeric_finite(double value, const void *location) {
    if (!isfinite(value)) runtime_error(location, "एक सीमित दशमलव मान आवश्यक है।");
    return value;
}
int64_t bh_numeric_abs_int(int64_t value, const void *location) {
    if (value == INT64_MIN) runtime_error(location, "निरपेक्ष पूर्णांक सीमा से बाहर है।");
    return value < 0 ? -value : value;
}
double bh_numeric_abs_real(double value, const void *location) { return fabs(numeric_finite(value, location)); }
int64_t bh_numeric_min_int(int64_t a, int64_t b, const void *location) { (void)location; return a < b ? a : b; }
int64_t bh_numeric_max_int(int64_t a, int64_t b, const void *location) { (void)location; return a > b ? a : b; }
double bh_numeric_min_real(double a, double b, const void *location) {
    numeric_finite(a, location); numeric_finite(b, location);
    if (a == 0 && b == 0) return signbit(a) || signbit(b) ? -0.0 : 0.0;
    return a < b ? a : b;
}
double bh_numeric_max_real(double a, double b, const void *location) {
    numeric_finite(a, location); numeric_finite(b, location);
    if (a == 0 && b == 0) return signbit(a) && signbit(b) ? -0.0 : 0.0;
    return a > b ? a : b;
}
double bh_numeric_sqrt(double x, const void *location) { return numeric_finite(sqrt(numeric_finite(x, location)), location); }
double bh_numeric_pow(double a, double b, const void *location) {
    numeric_finite(a, location); numeric_finite(b, location); return numeric_finite(pow(a, b), location);
}
double bh_numeric_floor(double x, const void *location) { return floor(numeric_finite(x, location)); }
double bh_numeric_ceil(double x, const void *location) { return ceil(numeric_finite(x, location)); }
double bh_numeric_round(double x, const void *location) { return rint(numeric_finite(x, location)); }
double bh_numeric_sin(double x, const void *location) { return sin(numeric_finite(x, location)); }
double bh_numeric_cos(double x, const void *location) { return cos(numeric_finite(x, location)); }
double bh_numeric_log(double x, const void *location) { return numeric_finite(log(numeric_finite(x, location)), location); }
double bh_numeric_exp(double x, const void *location) { return numeric_finite(exp(numeric_finite(x, location)), location); }
double bh_numeric_pi(const void *location) { (void)location; return 0x1.921fb54442d18p1; }
void bh_numeric_seed(int64_t seed, const void *location) { (void)location; random_state = (uint64_t)seed; }
static uint64_t next_random(void) {
    uint64_t z = (random_state += UINT64_C(0x9e3779b97f4a7c15));
    z = (z ^ (z >> 30)) * UINT64_C(0xbf58476d1ce4e5b9);
    z = (z ^ (z >> 27)) * UINT64_C(0x94d049bb133111eb);
    return z ^ (z >> 31);
}
int64_t bh_numeric_random_int(int64_t lower, int64_t upper, const void *location) {
    uint64_t bound = (uint64_t)upper - (uint64_t)lower;
    if (lower >= upper || bound > INT64_MAX) runtime_error(location, "यादृच्छिक पूर्णांक की सीमा अमान्य है।");
    uint64_t u, r;
    do { u = next_random() >> 1; r = u % bound; } while (u + (bound - 1) - r > INT64_MAX);
    return lower + (int64_t)r;
}
double bh_numeric_random_unit(const void *location) { (void)location; return (double)(next_random() >> 11) * 0x1p-53; }

/* Default process C locale and round-to-nearest are retained: no locale mutation
   or fast-math flags are used by this runtime or generated programs. */
int64_t bh_real_to_int(double value, const void *location) {
    if (!isfinite(value) || value < -0x1p63 || value >= 0x1p63)
        runtime_error(location, "संख्यात्मक परिवर्तन सीमा से बाहर है।");
    return (int64_t)value;
}
static void *ascii_text(const char *value, const void *location) {
    size_t n = strlen(value); void *result = new_text(n, location);
    memcpy(text_data(result), value, n); return result;
}
void *bh_text_fixed(double value, int64_t places, const void *location) {
    if (!isfinite(value)) runtime_error(location, "एक सीमित दशमलव मान आवश्यक है।");
    if (places < 0 || places > 15) runtime_error(location, "दशमलव स्थान ० से १५ तक होने चाहिए।");
    /* Largest finite binary64: 309 integer digits + sign, dot and 15 places. */
    char buffer[512]; int n = snprintf(buffer, sizeof buffer, "%.*f", (int)places, value);
    if (n < 0 || (size_t)n >= sizeof buffer) fatal("आंतरिक दशमलव प्रारूप सीमा।");
    return ascii_text(buffer, location);
}
void *bh_text_real(double value, const void *location) {
    if (isnan(value)) return ascii_text("NaN", location);
    if (isinf(value)) return ascii_text(signbit(value) ? "-Infinity" : "Infinity", location);
    if (value == 0) return ascii_text(signbit(value) ? "-0.0" : "0.0", location);
    char raw[64]; double magnitude = value < 0 ? -value : value;
    /* Find the shortest correctly rounded significant-digit representation that
       parses to the original bits. Two significant digits in exponent notation
       retain the Bhash/Java presentation of very small values. */
    for (int digits = 1; digits <= 17; ++digits) {
        int n = snprintf(raw, sizeof raw, "%.*g", digits, magnitude);
        if (n < 0 || (size_t)n >= sizeof raw) fatal("आंतरिक दशमलव प्रारूप सीमा।");
        if (strtod(raw, NULL) == magnitude) {
            if (digits == 1 && strchr(raw, 'e')) continue;
            break;
        }
    }
    char *exponent_at = strchr(raw, 'e'); int exponent = exponent_at ? atoi(exponent_at + 1) : 0;
    if (exponent_at) *exponent_at = 0;
    char digits[32]; int count = 0, position = 0, dot = 0;
    for (const char *p = raw; *p; ++p) {
        if (*p == '.') dot = 1;
        else { digits[count++] = *p; if (!dot) ++position; }
    }
    position += exponent;
    while (count > 1 && digits[0] == '0') { memmove(digits, digits + 1, (size_t)--count); --position; }
    while (count > 1 && digits[count - 1] == '0') --count;
    int power = position - 1; char result[512]; size_t at = 0;
    if (signbit(value)) result[at++] = '-';
    if (power >= 7 || power < -3) {
        result[at++] = digits[0]; result[at++] = '.';
        if (count == 1) result[at++] = '0';
        else { memcpy(result + at, digits + 1, (size_t)count - 1); at += (size_t)count - 1; }
        int n = snprintf(result + at, sizeof result - at, "E%d", power);
        if (n < 0 || (size_t)n >= sizeof result - at) fatal("आंतरिक दशमलव प्रारूप सीमा।");
        at += (size_t)n;
    } else if (position <= 0) {
        result[at++] = '0'; result[at++] = '.';
        for (int i = 0; i < -position; ++i) result[at++] = '0';
        memcpy(result + at, digits, (size_t)count); at += (size_t)count;
    } else {
        for (int i = 0; i < position || i < count; ++i) {
            if (i == position) result[at++] = '.';
            result[at++] = i < count ? digits[i] : '0';
        }
        if (position >= count) { result[at++] = '.'; result[at++] = '0'; }
    }
    result[at] = 0; return ascii_text(result, location);
}
void bh_print_real(double value) {
    /* The text print path has no allocation/safepoint after this result. */
    static const struct { uint64_t n; char data[8]; } location = {7, "runtime"};
    const void *formatted = bh_text_real(value, &location);
    for (uint64_t i = 0; i < text_length(formatted); ++i) {
        unsigned char c = text_data(formatted)[i];
        if (c >= '0' && c <= '9') {
            unsigned char utf8[3] = {0xE0, 0xA5, (unsigned char)(0xA6 + c - '0')};
            fwrite(utf8, 1, 3, stdout);
        } else fputc(c, stdout);
    }
}
double bh_text_parse_real(const void *input, const void *location) {
    uint64_t n = text_length(input);
    void *slots[2] = {(void *)input, NULL}; bh_root_frame frame; bh_roots_push(&frame, slots, 2);
    slots[1] = new_text(checked_add(n, 1, location), location);
    char *ascii = (char *)text_data(slots[1]); uint64_t i = 0, out = 0;
    int script = 0, point = 0, before = 0, after = 0;
    if (n && (text_data(slots[0])[0] == '+' || text_data(slots[0])[0] == '-')) ascii[out++] = (char)text_data(slots[0])[i++];
    while (i < n) {
        uint32_t cp = scalar(text_data(slots[0]), &i); int current, digit;
        if (cp == '.' && !point && before) { point = 1; ascii[out++] = '.'; continue; }
        if (cp >= '0' && cp <= '9') { current = 1; digit = (int)(cp - '0'); }
        else if (cp >= 0x966 && cp <= 0x96F) { current = 2; digit = (int)(cp - 0x966); }
        else { runtime_error(location, "अमान्य दशमलव पाठ।"); return 0; }
        if (script && current != script) runtime_error(location, "अमान्य दशमलव पाठ।");
        script = current; if (point) ++after; else ++before; ascii[out++] = (char)('0' + digit);
    }
    if (!before || (point && !after)) runtime_error(location, "अमान्य दशमलव पाठ।");
    ascii[out] = 0; double result = strtod(ascii, NULL);
    if (!isfinite(result)) runtime_error(location, "दशमलव परिवर्तन सीमा से बाहर है।");
    bh_roots_pop(&frame); return result;
}

void bh_text_fail(const void *message, const void *location) {
    if (exception_descriptor) raise_failure_text(message, location);
    fputs("भाष: ", stderr); fwrite(text_data(location), 1, (size_t)text_length(location), stderr);
    fputs(": ", stderr); fwrite(text_data(message), 1, (size_t)text_length(message), stderr);
    fputc('\n', stderr); fflush(stderr); exit(1);
}
void bh_exception_configure(const bh_type *descriptor, const void *info) {
    if (handlers || pending_exception) fatal("आंतरिक अपवाद विन्यास अमान्य है।");
    exception_descriptor = descriptor; exception_info = info;
}
int64_t bh_protect(bh_protected_callback callback, void *environment) {
    bh_handler handler;
    handler.previous = handlers; handler.saved_roots = roots; handler.saved_cleanups = cleanups; handler.saved_trace = trace_frames; handlers = &handler;
    /* Neither saved field changes between setjmp and longjmp. The generated
       function's environment lives in its caller, outside this C boundary. */
    if (setjmp(handler.jump) == 0) {
        int64_t completion = callback(environment);
        if (roots != handler.saved_roots || cleanups != handler.saved_cleanups || trace_frames != handler.saved_trace) fatal("आंतरिक अपवाद मूल-संदर्भ असंतुलित हैं।");
        handlers = handler.previous; return completion;
    }
    handlers = handler.previous;
    if (roots != handler.saved_roots || cleanups != handler.saved_cleanups || trace_frames != handler.saved_trace) fatal("आंतरिक अपवाद मूल-संदर्भ असंतुलित हैं।");
    return -1;
}
void bh_static_roots_configure(void ***slots, uint64_t count) {
    if(roots || handlers || static_roots || static_root_count || (count && !slots))fatal("आंतरिक स्थिर मूल-संदर्भ विन्यास अमान्य है।");
    for(uint64_t i=0;i<count;++i)if(!slots[i])fatal("आंतरिक स्थिर मूल-संदर्भ स्लॉट अमान्य है।");
    static_roots=slots;static_root_count=count;
}
void bh_static_reference_store(void **slot, void *value) {
    /* A dedicated nonallocating barrier permits a future generational collector. */
    *slot=value;
}
void bh_class_initialize(int32_t *state, void **failure, bh_protected_callback initializer) {
    /* Single mutator: 0 unseen, 1 initializing (recursive use), 2 ready, 3 failed. */
    if(*state==2 || *state==1)return;
    if(*state==3)bh_throw(*failure,NULL);
    if(*state!=0)fatal("आंतरिक वर्ग आरंभीकरण स्थिति अमान्य है।");
    *state=1;
    if(bh_protect(initializer,NULL)==-1){
        bh_static_reference_store(failure,bh_exception_take());
        *state=3;bh_throw(*failure,NULL);
    }
    *state=2;
}
void bh_trace_enter(bh_trace_frame *frame,const void *name,const void *location){
    frame->previous=trace_frames;frame->name=name;frame->location=location;trace_frames=frame;
}
void bh_trace_at(const void *location){if(trace_frames)trace_frames->location=location;}
void bh_trace_leave(void){if(!trace_frames)fatal("आंतरिक निदान स्टैक अमान्य है।");trace_frames=trace_frames->previous;}
/* Snapshot entries are precise text references (normally immortal compiler text).
   The exception header owns the snapshot across GC, cached failures and rethrow. */
typedef struct bh_trace_snapshot {uint64_t count,truncated;const void *entries[];} bh_trace_snapshot;
static const bh_type trace_type={"exception trace",16,0,NULL,16,16,3};
static void capture_trace(const void *location){
    bh_object *object=find_object(pending_exception);
    if(!object || object->diagnostic_trace || !trace_frames)return;
    uint64_t count=0;bh_trace_frame *at=trace_frames;
    while(at && count<256){++count;at=at->previous;}
    void *slots[]={(void *)location};bh_root_frame frame;bh_roots_push(&frame,slots,1);
    bh_trace_snapshot *snapshot=bh_alloc(&trace_type,16+count*16);
    snapshot->count=count;snapshot->truncated=at!=NULL;at=trace_frames;
    for(uint64_t i=0;i<count;++i,at=at->previous){snapshot->entries[i*2]=at->name;snapshot->entries[i*2+1]=(i==0 && slots[0])?slots[0]:at->location;}
    /* Header-reference store is nonallocating; a future moving/generational collector
       must update/track this header slot together with payload descriptor references. */
    find_object(pending_exception)->diagnostic_trace=snapshot;
    bh_roots_pop(&frame);
}
static void print_trace(const void *exception){
    bh_object *object=find_object(exception);if(!object || !object->diagnostic_trace)return;
    const bh_trace_snapshot *snapshot=object->diagnostic_trace;
    for(uint64_t i=0;i<snapshot->count;++i){
        const void *name=snapshot->entries[i*2],*location=snapshot->entries[i*2+1];
        fputs("  में ",stderr);fwrite(text_data(name),1,(size_t)text_length(name),stderr);
        if(location){fputs(" (",stderr);fwrite(text_data(location),1,(size_t)text_length(location),stderr);fputc(')',stderr);}fputc('\n',stderr);
    }
    if(snapshot->truncated)fputs("  … निदान स्टैक २५६ फ्रेम पर सीमित है।\n",stderr);
}
void *bh_exception_take(void) { void *value = pending_exception; pending_exception = NULL; return value; }
_Noreturn void bh_throw(void *value, const void *location) {
    if (!value) runtime_error(location, "रिक्त वस्तु के सदस्य का उपयोग नहीं किया जा सकता।");
    void *original; memcpy(&original, (unsigned char *)value + 16, 8);
    if (location && (!original || !text_length(original))) bh_reference_store(value, 16, (void *)location);
    pending_exception = value;
    capture_trace(location);
    if (!handlers) fatal("आंतरिक अपवाद सीमा अनुपस्थित है।");
    /* Discard every generated/runtime helper frame abandoned by this transfer.
       The pending object is a precise global root before any frame is removed. */
    roots = handlers->saved_roots;
    trace_frames = handlers->saved_trace;
    while (cleanups != handlers->saved_cleanups) {
        bh_cleanup *entry = cleanups; cleanups = entry->previous; entry->dispose(entry->memory);
    }
    longjmp(handlers->jump, 1);
}
static _Noreturn void raise_failure_text(const void *message, const void *location) {
    void *slots[3] = {(void *)message, (void *)location, NULL}; bh_root_frame frame; bh_roots_push(&frame, slots, 3);
    slots[2] = bh_alloc(exception_descriptor, 24);
    memcpy(slots[2], &exception_info, 8);
    bh_reference_store(slots[2], 8, slots[0]); bh_reference_store(slots[2], 16, slots[1]);
    bh_throw(slots[2], location);
}
static _Noreturn void raise_failure(const void *location, const char *message) {
    uint64_t length = (uint64_t)strlen(message);
    void *slots[2] = {(void *)location, NULL}; bh_root_frame frame; bh_roots_push(&frame, slots, 2);
    slots[1] = bh_alloc(&text_type, length + 8);
    memcpy(slots[1], &length, 8); memcpy((unsigned char *)slots[1] + 8, message, (size_t)length);
    raise_failure_text(slots[1], slots[0]);
}
int bh_run(bh_protected_callback entry) {
    int64_t completion = bh_protect(entry, NULL);
    if (completion != -1) return 0;
    void *message, *location;
    memcpy(&message, (unsigned char *)pending_exception + 8, 8);
    memcpy(&location, (unsigned char *)pending_exception + 16, 8);
    fputs("भाष: ", stderr);
    if (location && text_length(location)) {
        fwrite(text_data(location), 1, (size_t)text_length(location), stderr); fputs(": ", stderr);
    }
    fwrite(text_data(message), 1, (size_t)text_length(message), stderr); fputc('\n', stderr); print_trace(pending_exception); fflush(stderr);
    pending_exception = NULL; return 1;
}

/* Arrays first, then growable lists and insertion-ordered hash maps. All backing
   storage is managed and traced precisely; allocation is the only safepoint. */
typedef struct bh_collection {
    const bh_collection_info *info;
    uint64_t size, version, capacity;
    uint64_t *data, *buckets;
} bh_collection;
_Static_assert(sizeof(void *) == 8 && offsetof(bh_collection, data) == 32 && sizeof(bh_collection_info) == 80,
               "Native collection ABI requires 64-bit pointers");
static const size_t collection_refs[] = {32, 40};
static const bh_type collection_type = {"collection", sizeof(bh_collection), 2, collection_refs, 0, 0, 0};
static const bh_type scalar_storage = {"scalar storage", 0, 0, NULL, 0, 0, 0};
static const bh_type reference_storage = {"reference storage", 0, 0, NULL, 0, 8, 1};
static const bh_type entry_storage[] = {
    {"map entries", 0, 0, NULL, 0, 32, 0}, {"map entries", 0, 0, NULL, 0, 32, 1},
    {"map entries", 0, 0, NULL, 0, 32, 2}, {"map entries", 0, 0, NULL, 0, 32, 3}
};
static const uint64_t empty_collection_text = 0;
static uint64_t pointer_bits(const void *value) { return (uint64_t)(uintptr_t)value; }
static void *bits_pointer(uint64_t value) { return (void *)(uintptr_t)value; }
static int reference_kind(uint64_t kind) { return kind == BH_TEXT || kind == BH_REFERENCE || kind == BH_ANY; }
static uint64_t mix_hash(uint64_t value) {
    value ^= value >> 30; value *= UINT64_C(0xbf58476d1ce4e5b9);
    value ^= value >> 27; value *= UINT64_C(0x94d049bb133111eb);
    return value ^ (value >> 31);
}
static uint64_t boxed_kind(const void *value) {
    const bh_class_info *info; memcpy(&info, value, 8);
    int64_t id = info->type_count ? info->type_ids[0] : 0;
    return id == -8 ? BH_INT32 : id == -7 ? BH_BYTE : id == -6 ? BH_REAL : id >= -3 && id <= -1 ? (uint64_t)-id : BH_REFERENCE;
}
static uint64_t box_payload(const void *value) { uint64_t bits; memcpy(&bits, (const unsigned char *)value + 8, 8); return bits; }
static const bh_record_info *record_info(const void *value) {
    if(!value)return NULL;
    const bh_class_info *info;memcpy(&info,value,8);
    for(uint64_t i=0;i<info->type_count;++i)if(info->type_ids[i]==-9)return (const bh_record_info *)info;
    return NULL;
}
static void *record_value(uint64_t kind,uint64_t bits) {
    if(!bits || (kind!=BH_REFERENCE && kind!=BH_ANY))return NULL;
    if(kind==BH_ANY && boxed_kind(bits_pointer(bits))!=BH_REFERENCE)return NULL;
    void *value=bits_pointer(bits);return record_info(value)?value:NULL;
}
static void *record_scratch(void *memory,size_t *capacity,size_t needed,size_t unit) {
    if(needed<=*capacity)return memory;
    if(needed>SIZE_MAX/unit)fatal("अभिलेख तुलना कार्य-स्थान सीमा से बाहर है।");
    size_t next=*capacity?*capacity:16;
    while(next<needed){if(next>SIZE_MAX/2){next=needed;break;}next*=2;}
    if(next>SIZE_MAX/unit)next=needed;
    void *result=realloc(memory,next*unit);if(!result)fatal("अभिलेख तुलना के लिए स्मृति उपलब्ध नहीं है।");
    *capacity=next;return result;
}
static int collection_same(uint64_t,uint64_t,uint64_t);
typedef struct record_pair {uint64_t kind,a,b;} record_pair;
static int record_same(uint64_t a,uint64_t b) {
    record_pair *work=NULL;size_t count=1,capacity=0;int equal=1;
    work=record_scratch(work,&capacity,count,sizeof(*work));work[0]=(record_pair){BH_REFERENCE,a,b};
    for(size_t cursor=0;cursor<count;++cursor){
        record_pair item=work[cursor];if(item.a==item.b)continue;
        if(item.kind==BH_ANY){
            if(!item.a || !item.b){equal=0;break;}
            uint64_t ak=boxed_kind(bits_pointer(item.a)),bk=boxed_kind(bits_pointer(item.b));
            if(ak!=bk){equal=0;break;}item.kind=ak;
            if(ak!=BH_REFERENCE){item.a=box_payload(bits_pointer(item.a));item.b=box_payload(bits_pointer(item.b));}
        }
        if(item.kind!=BH_REFERENCE){if(!collection_same(item.kind,item.a,item.b)){equal=0;break;}continue;}
        const bh_record_info *ai=record_info(bits_pointer(item.a)),*bi=record_info(bits_pointer(item.b));
        if(!ai || ai!=bi){equal=0;break;}
        /* Memoize expanded pairs: shared immutable DAGs must not expand exponentially. */
        int seen=0;for(size_t j=0;j<cursor;++j)if(work[j].kind==BH_REFERENCE && work[j].a==item.a && work[j].b==item.b){seen=1;break;}
        work[cursor]=item;if(seen)continue;
        if(ai->field_count>SIZE_MAX-count)fatal("अभिलेख तुलना कार्य-स्थान सीमा से बाहर है।");
        work=record_scratch(work,&capacity,count+(size_t)ai->field_count,sizeof(*work));
        for(uint64_t j=0;j<ai->field_count;++j){
            uint64_t av,bv;memcpy(&av,(const unsigned char *)bits_pointer(item.a)+8+j*8,8);memcpy(&bv,(const unsigned char *)bits_pointer(item.b)+8+j*8,8);
            work[count++]=(record_pair){ai->field_kinds[j],av,bv};
        }
    }
    free(work);return equal;
}
static int collection_same(uint64_t kind, uint64_t a, uint64_t b) {
    if(kind==BH_BOOL){a&=1;b&=1;}
    if (a == b) return 1;
    if (kind == BH_ANY) {
        if (!a || !b) return 0;
        uint64_t ak = boxed_kind(bits_pointer(a)), bk = boxed_kind(bits_pointer(b));
        if (ak != bk) return 0;
        if(ak==BH_REFERENCE)return record_same(a,b);
        return collection_same(ak, box_payload(bits_pointer(a)), box_payload(bits_pointer(b)));
    }
    if (kind == BH_REAL) {
        double x, y; memcpy(&x, &a, 8); memcpy(&y, &b, 8);
        return x == y || (isnan(x) && isnan(y));
    }
    if (kind == BH_TEXT) return bh_text_equal(bits_pointer(a), bits_pointer(b));
    if(kind==BH_REFERENCE && a && b)return record_same(a,b);
    return 0;
}
static uint64_t record_hash(void *);
int bh_value_same(uint64_t kind,uint64_t a,uint64_t b){return collection_same(kind,a,b);}
static uint64_t collection_hash(uint64_t kind, uint64_t value) {
    if(kind==BH_BOOL)value&=1;
    if(!value && reference_kind(kind))return mix_hash(kind);
    void *record=record_value(kind,value);if(record)return record_hash(record);
    if (kind == BH_ANY) {
        uint64_t actual = boxed_kind(bits_pointer(value));
        if (actual != BH_REFERENCE) return collection_hash(actual, box_payload(bits_pointer(value)));
        kind = BH_REFERENCE;
    }
    if (kind == BH_REAL) {
        double x; memcpy(&x, &value, 8);
        if (x == 0) value = 0;
        else if (isnan(x)) value = UINT64_C(0x7ff8000000000000);
    }
    if (kind == BH_TEXT) {
        const unsigned char *text = bits_pointer(value);
        uint64_t hash = UINT64_C(14695981039346656037), size = text_length(text);
        for (uint64_t i=0; i<size; ++i) { hash ^= text[8+i]; hash *= UINT64_C(1099511628211); }
        return mix_hash(hash ^ BH_TEXT);
    }
    if (kind == BH_REFERENCE) {
        bh_object *o = find_object(bits_pointer(value));
        if (!o) fatal("आंतरिक वस्तु पहचान अमान्य है।");
        value = o->identity; /* Retained with the object if a future collector moves it. */
    }
    return mix_hash(value ^ (kind * UINT64_C(0x9e3779b97f4a7c15)));
}
typedef struct record_hash_frame {
    void *value;const bh_record_info *info;
    uint64_t hash,field;size_t parent;int ready;
} record_hash_frame;
static uint64_t record_hash(void *value) {
    record_hash_frame *work=NULL;size_t count=1,capacity=0,cursor=0;
    work=record_scratch(work,&capacity,count,sizeof(*work));
    work[0]=(record_hash_frame){value,record_info(value),mix_hash((uint64_t)(uintptr_t)record_info(value)),0,0,0};
    for(;;){
        record_hash_frame frame=work[cursor];
        if(frame.field==frame.info->field_count){
            work[cursor].ready=1;if(cursor==0)break;cursor=frame.parent;continue;
        }
        uint64_t bits,kind=frame.info->field_kinds[frame.field];memcpy(&bits,(unsigned char *)frame.value+8+frame.field*8,8);
        void *child=record_value(kind,bits);uint64_t hash;
        if(child){
            size_t index=0;while(index<count && work[index].value!=child)++index;
            if(index==count){
                work=record_scratch(work,&capacity,count+1,sizeof(*work));const bh_record_info *info=record_info(child);
                work[count++]=(record_hash_frame){child,info,mix_hash((uint64_t)(uintptr_t)info),0,cursor,0};cursor=index;continue;
            }
            if(!work[index].ready)fatal("आंतरिक अभिलेख चक्र अमान्य है।");hash=work[index].hash;
        }else hash=collection_hash(kind,bits);
        work[cursor].hash=31*frame.hash+hash;++work[cursor].field;
    }
    uint64_t result=work[0].hash;free(work);return result;
}
static uint64_t checked_length(int64_t length, const void *location) {
    if (length < 0 || length > INT32_MAX) runtime_error(location, "सरणी की लंबाई सीमा से बाहर है।");
    return (uint64_t)length;
}
static uint64_t checked_index(uint64_t index, uint64_t size, int end, const void *location) {
    if (index > INT64_MAX || (end ? index > size : index >= size))
        runtime_error(location, "संग्रह सूचकांक सीमा से बाहर है।");
    return index;
}
static void storage_store(uint64_t *storage, uint64_t index, uint64_t bits, int reference) {
    if (reference) bh_reference_store(storage, index * 8, bits_pointer(bits));
    else storage[index] = bits;
}
static void rehash(bh_collection *c) {
    memset(c->buckets, 0, (size_t)(c->capacity * 2 * 8));
    for (uint64_t i=0; i<c->size; ++i) {
        uint64_t bucket = c->data[i*4+2] & (c->capacity*2-1);
        c->data[i*4+3] = c->buckets[bucket]; c->buckets[bucket] = i+1;
    }
}
static void collection_reserve(bh_collection *owner, uint64_t needed, const void *location) {
    if (needed <= owner->capacity) return;
    checked_length((int64_t)needed, location);
    if(owner->info->kind==BH_BUFFER){
        void *slot=owner;bh_root_frame frame;bh_roots_push(&frame,&slot,1);
        void *bytes=bh_alloc(&scalar_storage,needed);owner=slot;
        if(owner->size)memcpy(bytes,owner->data,(size_t)owner->size);
        bh_reference_store(owner,offsetof(bh_collection,data),bytes);owner->capacity=needed;
        bh_roots_pop(&frame);return;
    }
    uint64_t capacity = owner->capacity ? owner->capacity : 4;
    while (capacity < needed) capacity *= 2;
    /* Capacity is an internal power of two; logical sizes retain the signed-32-bit limit. */
    void *slots[3] = {owner, NULL, NULL}; bh_root_frame frame; bh_roots_push(&frame, slots, 3);
    int map = owner->info->kind == BH_MAP;
    unsigned mask = (reference_kind(owner->info->key_kind)?1u:0u) | (reference_kind(owner->info->value_kind)?2u:0u);
    const bh_type *storage_type = map ? &entry_storage[mask]
        : reference_kind(owner->info->value_kind) ? &reference_storage : &scalar_storage;
    slots[1] = bh_alloc(storage_type, capacity * (map ? 32 : 8));
    if (map) slots[2] = bh_alloc(&scalar_storage, capacity * 2 * 8);
    owner = slots[0]; uint64_t *data = slots[1];
    for (uint64_t i=0; i<owner->size; ++i) {
        if (map) for (uint64_t lane=0; lane<4; ++lane)
            storage_store(data, i*4+lane, owner->data[i*4+lane], lane<2 && (mask & (1u<<lane)));
        else storage_store(data, i, owner->data[i], reference_kind(owner->info->value_kind));
    }
    bh_reference_store(owner, offsetof(bh_collection, data), data);
    bh_reference_store(owner, offsetof(bh_collection, buckets), slots[2]);
    owner->capacity = capacity;
    if (map) rehash(owner);
    bh_roots_pop(&frame);
}
void *bh_collection_new(const bh_collection_info *info, int64_t length, const void *location) {
    uint64_t size = checked_length(length, location);
    bh_collection *c = bh_alloc(&collection_type, sizeof(bh_collection)); c->info = info;
    void *slots[1] = {c}; bh_root_frame frame; bh_roots_push(&frame, slots, 1);
    collection_reserve(c, size, location); c = slots[0];
    if (info->value_kind == BH_TEXT) for (uint64_t i=0; i<size; ++i)
        storage_store(c->data, i, pointer_bits(&empty_collection_text), 1);
    c->size = size;
    bh_roots_pop(&frame); return c;
}
static void *array_dimensions(const bh_collection_info *info, uint64_t count, const int64_t *sizes, const void *location) {
    void *slots[2] = {bh_collection_new(info, sizes[0], location), NULL};
    bh_root_frame frame; bh_roots_push(&frame, slots, 2);
    if (count > 1) for (uint64_t i=0; i<((bh_collection *)slots[0])->size; ++i) {
        slots[1] = array_dimensions(info->element, count-1, sizes+1, location);
        bh_collection *c = slots[0]; storage_store(c->data, i, pointer_bits(slots[1]), 1);
        slots[1] = NULL;
    }
    void *result = slots[0]; bh_roots_pop(&frame); return result;
}
void *bh_array_dimensions(const bh_collection_info *info, uint64_t count, const int64_t *sizes, const void *location) {
    if (!count) fatal("आंतरिक सरणी आयाम अमान्य है।");
    for (uint64_t i=0; i<count; ++i) checked_length(sizes[i], location);
    return array_dimensions(info, count, sizes, location);
}
static uint64_t map_find(bh_collection *c, uint64_t key, uint64_t hash) {
    if (!c->capacity) return UINT64_MAX;
    uint64_t index = c->buckets[hash & (c->capacity*2-1)];
    while (index) {
        uint64_t i = index-1;
        if (c->data[i*4+2] == hash && collection_same(c->info->key_kind, c->data[i*4], key)) return i;
        index = c->data[i*4+3];
    }
    return UINT64_MAX;
}
uint64_t bh_collection_call(void *receiver, uint32_t operation, uint64_t a, uint64_t b,
                            const bh_collection_info *snapshot, const void *location) {
    bh_collection *c = bh_instance(receiver, location);
    int map = c->info->kind == BH_MAP;
    uint64_t value_kind = c->info->value_kind, key_kind = c->info->key_kind;
    uint64_t ak = 0, bk = 0;
    switch (operation) {
        case BH_GET: case BH_GET_OR: case BH_SET: case BH_REMOVE: ak = map ? key_kind : BH_INT; break;
        case BH_CONTAINS: ak = map ? key_kind : value_kind; break;
        case BH_ADD: ak = value_kind; break;
        case BH_INSERT: ak = BH_INT; break;
        default: break;
    }
    if (operation == BH_GET_OR || operation == BH_SET || operation == BH_INSERT) bk = value_kind;
    void *slots[4] = {c, reference_kind(ak)?bits_pointer(a):NULL, reference_kind(bk)?bits_pointer(b):NULL, NULL};
    bh_root_frame frame; bh_roots_push(&frame, slots, 4);
    uint64_t result = 0, index = 0, hash = 0;
    if (map && (operation == BH_GET || operation == BH_GET_OR || operation == BH_SET || operation == BH_CONTAINS || operation == BH_REMOVE)) {
        if (reference_kind(key_kind) && !a) runtime_error(location, "मानचित्र की कुंजी रिक्त नहीं हो सकती।");
        hash = collection_hash(key_kind, a); index = map_find(c, a, hash);
    }
    switch (operation) {
        case BH_SIZE: result = c->size; break;
        case BH_EMPTY: result = c->size == 0; break;
        case BH_VERSION: result = c->version; break;
        case BH_ITER_CHECK:
            if (a != c->version) runtime_error(location, "पुनरावृत्ति के दौरान संग्रह का आकार बदला गया।");
            result = b < c->size; break;
        case BH_ITER_KEY: case BH_ITER_VALUE:
            index = checked_index(a, c->size, 0, location);
            result = c->info->kind==BH_BUFFER ? ((unsigned char *)c->data)[index] : c->data[map ? index*4+(operation == BH_ITER_VALUE ? 1 : 0) : index]; break;
        case BH_GET: case BH_GET_OR:
            if (map) {
                if (index == UINT64_MAX && operation == BH_GET) runtime_error(location, "मानचित्र में कुंजी नहीं मिली।");
                result = index == UINT64_MAX ? b : c->data[index*4+1];
            } else {index=checked_index(a,c->size,0,location);result=c->info->kind==BH_BUFFER?((unsigned char *)c->data)[index]:c->data[index];}
            break;
        case BH_CONTAINS:
            if (map) result = index != UINT64_MAX;
            else for (uint64_t i=0; i<c->size; ++i) if (collection_same(value_kind, c->info->kind==BH_BUFFER?((unsigned char *)c->data)[i]:c->data[i], a)) { result = 1; break; }
            break;
        case BH_SET:
            if (map) {
                if (index == UINT64_MAX) {
                    collection_reserve(c, c->size+1, location); c = slots[0];
                    if (reference_kind(ak)) a = pointer_bits(slots[1]);
                    if (reference_kind(bk)) b = pointer_bits(slots[2]);
                    index = c->size++; c->version++;
                    storage_store(c->data, index*4, a, reference_kind(key_kind));
                    c->data[index*4+2] = hash;
                    uint64_t bucket = hash & (c->capacity*2-1);
                    c->data[index*4+3] = c->buckets[bucket]; c->buckets[bucket] = index+1;
                }
                storage_store(c->data, index*4+1, b, reference_kind(value_kind));
            } else if(c->info->kind==BH_BUFFER)((unsigned char *)c->data)[checked_index(a,c->size,0,location)]=(unsigned char)bh_to_byte((int64_t)b,location);
            else storage_store(c->data, checked_index(a, c->size, 0, location), b, reference_kind(value_kind));
            break;
        case BH_ADD: case BH_INSERT:
            index = operation == BH_ADD ? c->size : checked_index(a, c->size, 1, location);
            collection_reserve(c, c->size+1, location); c = slots[0];
            if (reference_kind(ak)) a = pointer_bits(slots[1]);
            if (reference_kind(bk)) b = pointer_bits(slots[2]);
            for (uint64_t i=c->size; i>index; --i) storage_store(c->data, i, c->data[i-1], reference_kind(value_kind));
            storage_store(c->data, index, operation == BH_ADD ? a : b, reference_kind(value_kind));
            c->size++; c->version++; break;
        case BH_REMOVE:
            if (!map) index = checked_index(a, c->size, 0, location);
            if (index != UINT64_MAX) {
                result = map ? 1 : c->data[index];
                uint64_t width = map ? 4 : 1;
                for (uint64_t i=index; i+1<c->size; ++i) for (uint64_t lane=0; lane<width; ++lane)
                    storage_store(c->data, i*width+lane, c->data[(i+1)*width+lane],
                        map ? (lane==0 && reference_kind(key_kind)) || (lane==1 && reference_kind(value_kind)) : reference_kind(value_kind));
                for (uint64_t lane=0; lane<width; ++lane) storage_store(c->data, (c->size-1)*width+lane, 0,
                    map ? (lane==0 && reference_kind(key_kind)) || (lane==1 && reference_kind(value_kind)) : reference_kind(value_kind));
                c->size--; c->version++; if (map) rehash(c);
            }
            break;
        case BH_CLEAR:
            if (c->size) {
                /* Drop entire managed buffers: no stale references retain removed entries. */
                bh_reference_store(c, offsetof(bh_collection, data), NULL);
                bh_reference_store(c, offsetof(bh_collection, buckets), NULL);
                c->size = c->capacity = 0; c->version++;
            }
            break;
        case BH_KEYS: case BH_VALUES:
            slots[3] = bh_collection_new(snapshot, 0, location);
            c = slots[0]; collection_reserve(slots[3], c->size, location); c = slots[0];
            for (uint64_t i=0; i<c->size; ++i) {
                bh_collection *copy = slots[3];
                storage_store(copy->data, i, c->data[i*4+(operation == BH_VALUES ? 1 : 0)], reference_kind(copy->info->value_kind));
                copy->size++;
            }
            result = pointer_bits(slots[3]); break;
        default: fatal("आंतरिक संग्रह क्रिया अमान्य है।");
    }
    bh_roots_pop(&frame); return result;
}
void *bh_collection_literal(const bh_collection_info *info, uint64_t count, const uint64_t *values, const void *location) {
    uint64_t width = info->kind == BH_MAP ? 2 : 1;
    if (count > INT32_MAX || count > (SIZE_MAX / sizeof(void *) - 1) / width) runtime_error(location, "सरणी की लंबाई सीमा से बाहर है।");
    uint64_t words = count*width;
    void **slots = calloc((size_t)words+1, sizeof(void *));
    if (!slots) fatal("स्मृति उपलब्ध नहीं है।");
    bh_cleanup cleanup = {cleanups, slots, free}; cleanups = &cleanup;
    for (uint64_t i=0; i<words; ++i) {
        uint64_t kind = width==2 && i%2==0 ? info->key_kind : info->value_kind;
        if (reference_kind(kind)) slots[i+1] = bits_pointer(values[i]);
    }
    bh_root_frame frame; bh_roots_push(&frame, slots, words+1);
    slots[0] = bh_collection_new(info, (info->kind == BH_ARRAY || info->kind == BH_BUFFER) ? (int64_t)count : 0, location);
    for (uint64_t i=0; i<count; ++i) {
        uint64_t ak = width==2 ? info->key_kind : info->value_kind;
        uint64_t a = reference_kind(ak) ? pointer_bits(slots[i*width+1]) : values[i*width];
        if (width==2) {
            uint64_t b = reference_kind(info->value_kind) ? pointer_bits(slots[i*2+2]) : values[i*2+1];
            bh_collection_call(slots[0], BH_SET, a, b, NULL, location);
        } else if(info->kind==BH_BUFFER)bh_collection_call(slots[0],BH_SET,i,a,NULL,location);
        else if (info->kind == BH_LIST) bh_collection_call(slots[0], BH_ADD, a, 0, NULL, location);
        else {
            bh_collection *c = slots[0]; storage_store(c->data, i, a, reference_kind(info->value_kind));
        }
    }
    void *result = slots[0]; bh_roots_pop(&frame); cleanups = cleanup.previous; free(slots); return result;
}

/* Structured text: all working buffers, partial collections and active paths use
   precise roots. No malloc scratch survives an exception or an allocation. */
static const bh_data_info *data_info;
_Static_assert(sizeof(bh_data_info) == 88, "Structured metadata ABI");
void bh_data_configure(const bh_data_info *info) {
    if (roots || handlers || pending_exception) fatal("आंतरिक डेटा विन्यास अमान्य है।");
    data_info = info;
}
static uint64_t data_units(const void *text) {
    uint64_t i=0,n=text_length(text),units=0;
    while(i<n) units += scalar(text_data(text),&i)>0xffff?2:1;
    return units;
}
typedef struct data_buffer {
    void *slots[2]; bh_root_frame frame;
    uint64_t size,capacity,units,limit;
    const void *location; const char *limit_error;
} data_buffer;
static void buffer_init(data_buffer *b,uint64_t limit,const char *error,const void *location) {
    memset(b,0,sizeof *b); b->limit=limit;b->limit_error=error;b->location=location;
    bh_roots_push(&b->frame,b->slots,2);
}
static void buffer_reserve(data_buffer *b,uint64_t n,uint64_t units) {
    if(units>b->limit-b->units) runtime_error(b->location,b->limit_error);
    uint64_t required=checked_add(b->size,n,b->location);
    if(required<=b->capacity)return;
    uint64_t capacity=b->capacity?b->capacity:64;
    while(capacity<required){if(capacity>UINT64_MAX/2){capacity=required;break;}capacity*=2;}
    b->slots[1]=new_text(capacity,b->location);
    if(b->size)memcpy(text_data(b->slots[1]),text_data(b->slots[0]),(size_t)b->size);
    b->slots[0]=b->slots[1];b->slots[1]=NULL;b->capacity=capacity;
}
/* Bytes here are stack/static, never an interior pointer into managed storage. */
static void buffer_bytes(data_buffer *b,const void *bytes,uint64_t n,uint64_t units) {
    buffer_reserve(b,n,units);
    if(n)memcpy(text_data(b->slots[0])+b->size,bytes,(size_t)n);
    b->size+=n;b->units+=units;
}
static void buffer_text(data_buffer *b,void **slot,uint64_t n,uint64_t units) {
    buffer_reserve(b,n,units);
    if(n)memcpy(text_data(b->slots[0])+b->size,text_data(*slot),(size_t)n);
    b->size+=n;b->units+=units;
}
static void buffer_scalar(data_buffer *b,uint32_t cp) {
    unsigned char bytes[4];uint64_t n;
    if(cp<0x80){bytes[0]=(unsigned char)cp;n=1;}
    else if(cp<0x800){bytes[0]=(unsigned char)(0xc0|(cp>>6));bytes[1]=(unsigned char)(0x80|(cp&63));n=2;}
    else if(cp<0x10000){bytes[0]=(unsigned char)(0xe0|(cp>>12));bytes[1]=(unsigned char)(0x80|((cp>>6)&63));bytes[2]=(unsigned char)(0x80|(cp&63));n=3;}
    else{bytes[0]=(unsigned char)(0xf0|(cp>>18));bytes[1]=(unsigned char)(0x80|((cp>>12)&63));bytes[2]=(unsigned char)(0x80|((cp>>6)&63));bytes[3]=(unsigned char)(0x80|(cp&63));n=4;}
    buffer_bytes(b,bytes,n,cp>0xffff?2:1);
}
static void *buffer_finish(data_buffer *b) {
    b->slots[1]=new_text(b->size,b->location);
    if(b->size)memcpy(text_data(b->slots[1]),text_data(b->slots[0]),(size_t)b->size);
    void *result=b->slots[1];bh_roots_pop(&b->frame);return result;
}
void *bh_data_split(const void *text,const void *separator,const void *location) {
    void *slots[4]={(void *)text,(void *)separator,NULL,NULL};bh_root_frame frame;bh_roots_push(&frame,slots,4);
    uint64_t n=text_length(text),m=text_length(separator),start=0,i=0;
    if(!m)runtime_error(location,"विभाजक या बदलने वाला पाठ खाली नहीं हो सकता।");
    slots[2]=bh_collection_new(data_info->texts,0,location);
    for(;;){
        if(i==n||matches(slots[0],i,slots[1])){
            slots[3]=new_text(i-start,location);
            if(i>start)memcpy(text_data(slots[3]),text_data(slots[0])+start,(size_t)(i-start));
            bh_collection_call(slots[2],BH_ADD,pointer_bits(slots[3]),0,NULL,location);slots[3]=NULL;
            if(i==n)break;
            i+=m;start=i;
        }else ++i;
    }
    void *result=slots[2];bh_roots_pop(&frame);return result;
}
void *bh_data_join(void *values,const void *separator,const void *location) {
    void *slots[3]={bh_instance(values,location),(void *)separator,NULL};bh_root_frame frame;bh_roots_push(&frame,slots,3);
    uint64_t n=((bh_collection *)slots[0])->size;
    data_buffer buffer;buffer_init(&buffer,UINT64_MAX,"पाठ सीमा से बाहर है।",location);
    for(uint64_t i=0;i<n;++i){
        if(i)buffer_text(&buffer,&slots[1],text_length(slots[1]),data_units(slots[1]));
        slots[2]=bits_pointer(((bh_collection *)slots[0])->data[i]);
        buffer_text(&buffer,&slots[2],text_length(slots[2]),data_units(slots[2]));slots[2]=NULL;
    }
    void *result=buffer_finish(&buffer);bh_roots_pop(&frame);return result;
}
static void *json_box(uint64_t kind,uint64_t bits) {
    int index=kind==BH_INT?0:kind==BH_BOOL?1:kind==BH_TEXT?2:3;
    void *slots[2]={kind==BH_TEXT?bits_pointer(bits):NULL,NULL};bh_root_frame frame;bh_roots_push(&frame,slots,2);
    slots[1]=bh_alloc(data_info->boxes[index],16);
    memcpy(slots[1],&data_info->box_info[index],8);
    if(kind==BH_TEXT)bh_reference_store(slots[1],8,slots[0]);
    else memcpy((unsigned char *)slots[1]+8,&bits,8);
    void *result=slots[1];bh_roots_pop(&frame);return result;
}
typedef struct json_reader {void *input;const void *location;uint64_t at,units;} json_reader;
static _Noreturn void json_invalid(json_reader *r) {
    char message[160];snprintf(message,sizeof message,"अमान्य जेसन पाठ, स्थान %llu।",(unsigned long long)r->units);
    runtime_error(r->location,message);
}
static int json_take(json_reader *r,unsigned char c) {
    if(r->at<text_length(r->input)&&text_data(r->input)[r->at]==c){++r->at;++r->units;return 1;}return 0;
}
static void json_need(json_reader *r,unsigned char c){if(!json_take(r,c))json_invalid(r);}
static void json_space(json_reader *r) {
    while(r->at<text_length(r->input)){
        unsigned char c=text_data(r->input)[r->at];if(c!=' '&&c!='\t'&&c!='\r'&&c!='\n')break;
        ++r->at;++r->units;
    }
}
static int json_digit(json_reader *r){return r->at<text_length(r->input)&&text_data(r->input)[r->at]>='0'&&text_data(r->input)[r->at]<='9';}
/* Escape syntax consumes a single UTF-16 code unit before reporting a bad character. */
static uint32_t json_escape_char(json_reader *r) {
    if(r->at>=text_length(r->input))json_invalid(r);
    uint32_t c=scalar(text_data(r->input),&r->at);++r->units;return c;
}
static void *json_string(json_reader *r) {
    json_need(r,'"');data_buffer b;buffer_init(&b,UINT64_MAX,"पाठ सीमा से बाहर है।",r->location);
    uint32_t high=0;int invalid_unicode=0;
    while(r->at<text_length(r->input)){
        uint32_t c=scalar(text_data(r->input),&r->at);r->units+=c>0xffff?2:1;
        if(c=='"'){
            if(high||invalid_unicode)runtime_error(r->location,"अमान्य यूनिकोड पाठ।");
            return buffer_finish(&b);
        }
        if(c<32)json_invalid(r);
        if(c=='\\'){
            c=json_escape_char(r);
            switch(c){
                case '"':case '\\':case '/':break;
                case 'b':c=8;break;case 'f':c=12;break;case 'n':c=10;break;case 'r':c=13;break;case 't':c=9;break;
                case 'u':{
                    c=0;for(int i=0;i<4;++i){uint32_t digit=json_escape_char(r);int n=digit>='0'&&digit<='9'?(int)(digit-'0'):digit>='a'&&digit<='f'?(int)(digit-'a'+10):digit>='A'&&digit<='F'?(int)(digit-'A'+10):-1;if(n<0)json_invalid(r);c=c*16+(uint32_t)n;}
                    break;
                }
                default:json_invalid(r);
            }
        }
        if(c>=0xd800&&c<=0xdbff){if(high)invalid_unicode=1;high=c;continue;}
        if(c>=0xdc00&&c<=0xdfff){if(!high){invalid_unicode=1;continue;}c=0x10000+((high-0xd800)<<10)+(c-0xdc00);high=0;}
        else if(high){invalid_unicode=1;high=0;}
        buffer_scalar(&b,c);
    }
    json_invalid(r);
}
static void *json_number(json_reader *r) {
    uint64_t start=r->at;int negative=json_take(r,'-'),real=0;
    if(json_take(r,'0')){if(json_digit(r))json_invalid(r);}
    else{if(!json_digit(r))json_invalid(r);while(json_digit(r)){++r->at;++r->units;}}
    if(json_take(r,'.')){real=1;if(!json_digit(r))json_invalid(r);while(json_digit(r)){++r->at;++r->units;}}
    if(json_take(r,'e')||json_take(r,'E')){real=1;if(!json_take(r,'+'))json_take(r,'-');if(!json_digit(r))json_invalid(r);while(json_digit(r)){++r->at;++r->units;}}
    uint64_t n=r->at-start;
    if(!real){
        uint64_t value=0,limit=negative?UINT64_C(9223372036854775808):INT64_MAX;
        for(uint64_t i=start+(negative?1:0);i<r->at;++i){uint64_t digit=text_data(r->input)[i]-'0';if(value>(limit-digit)/10)json_invalid(r);value=value*10+digit;}
        int64_t integer=negative?(value==UINT64_C(9223372036854775808)?INT64_MIN:-(int64_t)value):(int64_t)value;
        uint64_t bits;memcpy(&bits,&integer,8);return json_box(BH_INT,bits);
    }
    void *slot=new_text(n+1,r->location);bh_root_frame frame;bh_roots_push(&frame,&slot,1);
    memcpy(text_data(slot),text_data(r->input)+start,(size_t)n);text_data(slot)[n]=0;
    double number=numeric_finite(strtod((char *)text_data(slot),NULL),r->location);uint64_t bits;memcpy(&bits,&number,8);
    slot=NULL;void *result=json_box(BH_REAL,bits);bh_roots_pop(&frame);return result;
}
static void *json_value(json_reader *r,unsigned depth) {
    json_space(r);if(depth>64||r->at>=text_length(r->input))json_invalid(r);
    void *slots[3]={NULL,NULL,NULL};bh_root_frame frame;bh_roots_push(&frame,slots,3);
    unsigned char c=text_data(r->input)[r->at];
    if(c=='"'){slots[0]=json_string(r);slots[0]=json_box(BH_TEXT,pointer_bits(slots[0]));}
    else if(json_take(r,'{')){
        slots[0]=bh_collection_new(data_info->object,0,r->location);json_space(r);
        if(!json_take(r,'}'))for(;;){
            json_space(r);if(r->at>=text_length(r->input)||text_data(r->input)[r->at]!='"')json_invalid(r);
            slots[1]=json_string(r);json_space(r);json_need(r,':');
            if(bh_collection_call(slots[0],BH_CONTAINS,pointer_bits(slots[1]),0,NULL,r->location))json_invalid(r);
            slots[2]=json_value(r,depth+1);
            bh_collection_call(slots[0],BH_SET,pointer_bits(slots[1]),pointer_bits(slots[2]),NULL,r->location);slots[1]=NULL;slots[2]=NULL;
            json_space(r);if(json_take(r,'}'))break;json_need(r,',');
        }
    }else if(json_take(r,'[')){
        slots[0]=bh_collection_new(data_info->values,0,r->location);json_space(r);
        if(!json_take(r,']'))for(;;){
            slots[1]=json_value(r,depth+1);bh_collection_call(slots[0],BH_ADD,pointer_bits(slots[1]),0,NULL,r->location);slots[1]=NULL;
            json_space(r);if(json_take(r,']'))break;json_need(r,',');
        }
    }else{
        const char *literal=NULL;uint64_t n=0;
        if(c=='t'){literal="true";n=4;}else if(c=='f'){literal="false";n=5;}else if(c=='n'){literal="null";n=4;}
        if(literal&&n<=text_length(r->input)-r->at&&!memcmp(text_data(r->input)+r->at,literal,(size_t)n)){
            r->at+=n;r->units+=n;slots[0]=c=='n'?NULL:json_box(BH_BOOL,c=='t'?1:0);
        }else slots[0]=json_number(r);
    }
    void *result=slots[0];bh_roots_pop(&frame);return result;
}
void *bh_data_json_read(const void *text,const void *location) {
    json_reader reader={(void *)text,location,0,0};bh_root_frame frame;bh_roots_push(&frame,&reader.input,1);
    if(data_units(reader.input)>1048576)runtime_error(location,"जेसन पाठ सीमा से बाहर है।");
    void *result=json_value(&reader,0); /* No allocation between this result and return. */
    json_space(&reader);if(reader.at!=text_length(reader.input))json_invalid(&reader);
    bh_roots_pop(&frame);return result;
}
typedef struct json_writer {data_buffer buffer;void *active[65];bh_root_frame frame;const void *location;} json_writer;
static void json_write_string(json_writer *w,void *text) {
    void *slot=text;bh_root_frame frame;bh_roots_push(&frame,&slot,1);
    buffer_bytes(&w->buffer,"\"",1,1);uint64_t at=0,n=text_length(slot);
    while(at<n){
        uint32_t c=scalar(text_data(slot),&at);
        if(c=='"'||c=='\\'){char escaped[2]={'\\',(char)c};buffer_bytes(&w->buffer,escaped,2,2);}
        else if(c<32){char escaped[7];snprintf(escaped,sizeof escaped,"\\u%04x",(unsigned)c);buffer_bytes(&w->buffer,escaped,6,6);}
        else buffer_scalar(&w->buffer,c);
    }
    buffer_bytes(&w->buffer,"\"",1,1);bh_roots_pop(&frame);
}
static void json_write_value(json_writer *w,uint64_t kind,uint64_t bits,unsigned depth) {
    void *slots[2]={reference_kind(kind)?bits_pointer(bits):NULL,NULL};bh_root_frame frame;bh_roots_push(&frame,slots,2);
    if(depth>64)runtime_error(w->location,"जेसन की गहराई सीमा पार हो गई।");
    if(kind==BH_ANY){
        if(!slots[0]){buffer_bytes(&w->buffer,"null",4,4);bh_roots_pop(&frame);return;}
        kind=boxed_kind(slots[0]);if(kind!=BH_REFERENCE){bits=box_payload(slots[0]);if(kind==BH_TEXT)slots[1]=bits_pointer(bits);}
    }else if(kind==BH_TEXT)slots[1]=slots[0];
    if(kind==BH_TEXT)json_write_string(w,slots[1]);
    else if(kind==BH_BOOL)buffer_bytes(&w->buffer,bits?"true":"false",bits?4:5,bits?4:5);
    else if(kind==BH_INT||kind==BH_BYTE||kind==BH_INT32||kind==BH_REAL){
        if(kind==BH_INT||kind==BH_BYTE||kind==BH_INT32){int64_t value;memcpy(&value,&bits,8);slots[1]=bh_text_integer(value,w->location);}
        else{double value;memcpy(&value,&bits,8);slots[1]=bh_text_real(numeric_finite(value,w->location),w->location);}
        uint64_t n=text_length(slots[1]);buffer_text(&w->buffer,&slots[1],n,n);
    }else{
        if(!slots[0])buffer_bytes(&w->buffer,"null",4,4);
        else{
            bh_object *allocation=find_object(slots[0]);
            if(!allocation||allocation->type!=&collection_type)runtime_error(w->location,"यह प्रकार जेसन में समर्थित नहीं है।");
            for(unsigned i=0;i<65;++i)if(w->active[i]==slots[0])runtime_error(w->location,"चक्रीय संग्रह को जेसन में नहीं लिख सकते।");
            w->active[depth]=slots[0];bh_collection *c=slots[0];int map=c->info->kind==BH_MAP;uint64_t n=c->size;
            buffer_bytes(&w->buffer,map?"{":"[",1,1);
            for(uint64_t i=0;i<n;++i){
                if(i)buffer_bytes(&w->buffer,",",1,1);
                c=slots[0];
                if(map){
                    uint64_t key=c->data[i*4];
                    if(c->info->key_kind==BH_TEXT)slots[1]=bits_pointer(key);
                    else if(c->info->key_kind==BH_ANY&&boxed_kind(bits_pointer(key))==BH_TEXT)slots[1]=bits_pointer(box_payload(bits_pointer(key)));
                    else runtime_error(w->location,"जेसन मानचित्र की कुंजी पाठ होनी चाहिए।");
                    json_write_string(w,slots[1]);slots[1]=NULL;buffer_bytes(&w->buffer,":",1,1);c=slots[0];
                }
                uint64_t value=c->info->kind==BH_BUFFER?((unsigned char *)c->data)[i]:c->data[map?i*4+1:i],value_kind=c->info->value_kind;
                json_write_value(w,value_kind,value,depth+1);
            }
            buffer_bytes(&w->buffer,map?"}":"]",1,1);w->active[depth]=NULL;
        }
    }
    bh_roots_pop(&frame);
}
void *bh_data_json_write(void *value,const void *location) {
    void *slot=value;bh_root_frame frame;bh_roots_push(&frame,&slot,1);
    json_writer writer;memset(&writer,0,sizeof writer);writer.location=location;
    buffer_init(&writer.buffer,1048576,"जेसन पाठ सीमा से बाहर है।",location);
    bh_roots_push(&writer.frame,writer.active,65);json_write_value(&writer,BH_ANY,pointer_bits(slot),0);bh_roots_pop(&writer.frame);
    void *result=buffer_finish(&writer.buffer);bh_roots_pop(&frame);return result;
}

/* Whole-file text I/O. Open handles join the same nonallocating unwind cleanup
   stack as compiler scratch storage. Managed buffers/paths have writable roots;
   no interior pointer survives a GC safepoint. The supported platform is Linux. */
#define BH_FILE_LIMIT UINT64_C(16777216)
static int valid_utf8(const unsigned char *s,uint64_t n) {
    uint64_t i=0;
    while(i<n){
        unsigned c=s[i++];uint32_t cp;unsigned extra;
        if(c<0x80)continue;
        if(c>=0xc2&&c<=0xdf){cp=c&31;extra=1;}
        else if(c>=0xe0&&c<=0xef){cp=c&15;extra=2;}
        else if(c>=0xf0&&c<=0xf4){cp=c&7;extra=3;}
        else return 0;
        if(n-i<extra)return 0;
        unsigned width=extra;
        while(extra--){c=s[i++];if((c&0xc0)!=0x80)return 0;cp=(cp<<6)|(c&63);}
        if((width==2&&cp<0x800)||(width==3&&cp<0x10000)||
           (cp>=0xd800&&cp<=0xdfff)||cp>0x10ffff)return 0;
    }
    return 1;
}
static void *file_path(const void *path,const void *location) {
    bh_instance((void *)path,location);
    uint64_t n=text_length(path);
    if(!valid_utf8(text_data(path),n))runtime_error(location,"अमान्य यूनिकोड पाठ।");
    if(!n||memchr(text_data(path),0,(size_t)n))runtime_error(location,"अमान्य फ़ाइल पथ।");
    void *input=(void *)path;bh_root_frame frame;bh_roots_push(&frame,&input,1);
    void *result=new_text(checked_add(n,1,location),location);
    memcpy(text_data(result),text_data(input),(size_t)n);text_data(result)[n]=0;
    bh_roots_pop(&frame);return result;
}
static void file_dispose(void *file) {(void)fclose((FILE *)file);}
static int file_close(FILE *file,bh_cleanup *cleanup) {
    int status=fclose(file);cleanups=cleanup->previous;return status;
}
int bh_file_supported(const void *location) {(void)location;return 1;}
int bh_file_exists(const void *path,const void *location) {
    void *name=file_path(path,location); /* No allocation during stat. */
#ifdef _MSC_VER
    struct _stat info;
    return !_stat((const char *)text_data(name),&info)&&(info.st_mode&_S_IFMT)==_S_IFREG;
#else
    struct stat info;
    return !stat((const char *)text_data(name),&info)&&S_ISREG(info.st_mode);
#endif
}
static void *file_read(const void *path,const bh_collection_info *info,const void *location) {
    void *slots[2]={NULL,NULL};bh_root_frame frame;bh_roots_push(&frame,slots,2);
    slots[0]=file_path(path,location);
    FILE *file=fopen((const char *)text_data(slots[0]),"rb");
    if(!file)runtime_error(location,"फ़ाइल पढ़ी नहीं जा सकी।");
    bh_cleanup cleanup={cleanups,file,file_dispose};cleanups=&cleanup;
    data_buffer buffer;buffer_init(&buffer,BH_FILE_LIMIT,"फ़ाइल १६ MiB सीमा से बड़ी है।",location);
    unsigned char chunk[8192];size_t count;
    while((count=fread(chunk,1,sizeof chunk,file))!=0)buffer_bytes(&buffer,chunk,count,count);
    int failed=ferror(file);int closed=file_close(file,&cleanup);
    if(failed||closed)runtime_error(location,"फ़ाइल पढ़ी नहीं जा सकी।");
    if(info){
        slots[1]=bh_collection_new(info,(int64_t)buffer.size,location);
        if(buffer.size)memcpy(((bh_collection *)slots[1])->data,text_data(buffer.slots[0]),(size_t)buffer.size);
        bh_roots_pop(&buffer.frame);
    }else{
        slots[1]=buffer_finish(&buffer);
        if(!valid_utf8(text_data(slots[1]),text_length(slots[1])))runtime_error(location,"अमान्य UTF-8 बाइटबफ़र।");
    }
    void *result=slots[1];bh_roots_pop(&frame);return result;
}
static void file_write(const void *path,const void *value,int binary,const void *location) {
    bh_instance((void *)value,location);
    uint64_t n=binary?((const bh_collection *)value)->size:text_length(value);
    if(!binary&&!valid_utf8(text_data(value),n))runtime_error(location,"अमान्य यूनिकोड पाठ।");
    if(n>BH_FILE_LIMIT)runtime_error(location,"फ़ाइल १६ MiB सीमा से बड़ी है।");
    void *slots[2]={(void *)value,NULL};bh_root_frame frame;bh_roots_push(&frame,slots,2);
    slots[1]=file_path(path,location);
    FILE *file=fopen((const char *)text_data(slots[1]),"wb");
    if(!file)runtime_error(location,"फ़ाइल लिखी नहीं जा सकी।");
    /* All validation/allocation precedes truncation. No safepoint until close. */
    const void *bytes=binary?(const void *)((bh_collection *)slots[0])->data:(const void *)text_data(slots[0]);
    size_t count=n?fwrite(bytes,1,(size_t)n,file):0;
    int closed=fclose(file);
    if(count!=n||closed)runtime_error(location,"फ़ाइल लिखी नहीं जा सकी।");
    bh_roots_pop(&frame);
}

void *bh_file_read_text(const void *path,const void *location){return file_read(path,NULL,location);}
void bh_file_write_text(const void *path,const void *value,const void *location){file_write(path,value,0,location);}
int64_t bh_to_byte(int64_t value,const void *location){
    if(value<0||value>255)runtime_error(location,"बाइट परिवर्तन सीमा से बाहर है।");
    return value;
}
int64_t bh_to_int32(int64_t value,const void *location){
    if(value<INT32_MIN||value>INT32_MAX)runtime_error(location,"लघुपूर्णांक परिवर्तन सीमा से बाहर है।");
    return value;
}
static uint64_t buffer_span(uint64_t offset,uint64_t size,const void *location){
    if(size<8||offset>size-8)runtime_error(location,"बाइटबफ़र सीमा से बाहर है।");return offset;
}
uint64_t bh_buffer_call(uint32_t operation,uint64_t a,uint64_t b,uint64_t c,uint64_t d,
                        const bh_collection_info *info,const void *location){
    void *slots[3]={bits_pointer(a),NULL,NULL};bh_root_frame frame;bh_roots_push(&frame,slots,3);
    if(operation==BH_WRITE_FILE)slots[1]=bits_pointer(b);
    if(operation!=BH_WRITE_FILE)bh_instance(slots[0],location);
    uint64_t result=0;
    if(operation==BH_READ_FILE)slots[2]=file_read(slots[0],info,location);
    else if(operation==BH_WRITE_FILE)file_write(slots[0],slots[1],1,location);
    else if(operation==BH_ENCODE){
        uint64_t n=text_length(slots[0]);
        if(!valid_utf8(text_data(slots[0]),n))runtime_error(location,"अमान्य यूनिकोड पाठ।");
        if(n>INT32_MAX)runtime_error(location,"सरणी की लंबाई सीमा से बाहर है।");
        slots[2]=bh_collection_new(info,(int64_t)n,location);
        if(n)memcpy(((bh_collection *)slots[2])->data,text_data(slots[0]),(size_t)n);
    }else{
        bh_collection *buffer=slots[0];uint64_t n=buffer->size;
        if(operation==BH_DECODE){
            if(!valid_utf8((unsigned char *)buffer->data,n))runtime_error(location,"अमान्य UTF-8 बाइटबफ़र।");
            slots[2]=new_text(n,location);buffer=slots[0];
            if(n)memcpy(text_data(slots[2]),buffer->data,(size_t)n);
        }else if(operation==BH_SLICE){
            if(b>n||c<b||c>n)runtime_error(location,"बाइटबफ़र सीमा से बाहर है।");
            slots[2]=bh_collection_new(info,(int64_t)(c-b),location);buffer=slots[0];
            if(c>b)memcpy(((bh_collection *)slots[2])->data,(unsigned char *)buffer->data+b,(size_t)(c-b));
        }else if(operation==BH_TO_ARRAY){
            slots[2]=bh_collection_new(info,(int64_t)n,location);buffer=slots[0];
            bh_collection *array=slots[2];for(uint64_t i=0;i<n;++i)array->data[i]=((unsigned char *)buffer->data)[i];
        }else if(operation==BH_FILL){
            bh_to_byte((int64_t)b,location);if(n)memset(buffer->data,(int)b,(size_t)n);
        }else{
            uint64_t at=buffer_span(b,n,location);unsigned char *bytes=(unsigned char *)buffer->data;
            int writing=operation==BH_WRITE_INT||operation==BH_WRITE_REAL;uint64_t big=writing?d:c;
            if(writing){for(unsigned i=0;i<8;++i)bytes[at+i]=(unsigned char)(c>>(8*(big?7-i:i)));}
            else if(operation==BH_READ_INT||operation==BH_READ_REAL){
                for(unsigned i=0;i<8;++i)result|=(uint64_t)bytes[at+i]<<(8*(big?7-i:i));
            }else fatal("आंतरिक बाइटबफ़र क्रिया अमान्य है।");
        }
    }
    if(slots[2])result=pointer_bits(slots[2]);bh_roots_pop(&frame);return result;
}

/* Proleptic Gregorian UTC arithmetic, independent of timezone, locale and time_t
   calendar conversion limits. Days are counted from 0001-01-01. */
#define BH_TIME_MIN INT64_C(-62135596800000)
#define BH_TIME_MAX INT64_C(253402300799999)
#define BH_DAY_MS INT64_C(86400000)
typedef struct bh_calendar {int year,month,day,hour,minute,second,millisecond;} bh_calendar;
static int time_leap(int year){return year%4==0&&(year%100!=0||year%400==0);}
static int time_month_days(int year,int month){
    static const int days[]={31,28,31,30,31,30,31,31,30,31,30,31};
    return days[month-1]+(month==2&&time_leap(year));
}
static bh_calendar time_calendar(int64_t value,const void *location){
    if(value<BH_TIME_MIN||value>BH_TIME_MAX)runtime_error(location,"समय सीमा से बाहर है।");
    int64_t elapsed=value-BH_TIME_MIN,days=elapsed/BH_DAY_MS,clock=elapsed%BH_DAY_MS;
    int year=1+(int)(days/146097)*400;days%=146097;
    int cycles=(int)(days/36524);if(cycles==4)cycles=3;
    year+=cycles*100;days-=cycles*36524;
    year+=(int)(days/1461)*4;days%=1461;
    cycles=(int)(days/365);if(cycles==4)cycles=3;
    year+=cycles;days-=cycles*365;
    int month=1;while(days>=time_month_days(year,month)){days-=time_month_days(year,month);++month;}
    bh_calendar result={year,month,(int)days+1,(int)(clock/3600000),(int)(clock/60000%60),(int)(clock/1000%60),(int)(clock%1000)};
    return result;
}
static int time_digits(const unsigned char *text,unsigned at,unsigned count,const void *location){
    int value=0;for(unsigned i=0;i<count;++i){unsigned c=text[at+i];if(c<'0'||c>'9')runtime_error(location,"अमान्य UTC समय पाठ।");value=value*10+(int)(c-'0');}return value;
}
int64_t bh_time_read(const void *text,const void *location){
    bh_instance((void *)text,location);
    if(text_length(text)!=24)runtime_error(location,"अमान्य UTC समय पाठ।");
    const unsigned char *s=text_data(text);
    if(s[4]!='-'||s[7]!='-'||s[10]!='T'||s[13]!=':'||s[16]!=':'||s[19]!='.'||s[23]!='Z')runtime_error(location,"अमान्य UTC समय पाठ।");
    int year=time_digits(s,0,4,location),month=time_digits(s,5,2,location),day=time_digits(s,8,2,location);
    int hour=time_digits(s,11,2,location),minute=time_digits(s,14,2,location),second=time_digits(s,17,2,location),ms=time_digits(s,20,3,location);
    if(year<1||month<1||month>12||day<1||day>time_month_days(year,month)||hour>23||minute>59||second>59)runtime_error(location,"अमान्य UTC समय पाठ।");
    int64_t y=year-1,days=y*365+y/4-y/100+y/400+day-1;
    for(int m=1;m<month;++m)days+=time_month_days(year,m);
    return BH_TIME_MIN+days*BH_DAY_MS+hour*INT64_C(3600000)+minute*INT64_C(60000)+second*INT64_C(1000)+ms;
}
void *bh_time_write(int64_t value,const void *location){
    bh_calendar t=time_calendar(value,location);char buffer[25];
    int n=snprintf(buffer,sizeof buffer,"%04d-%02d-%02dT%02d:%02d:%02d.%03dZ",t.year,t.month,t.day,t.hour,t.minute,t.second,t.millisecond);
    if(n!=24)fatal("आंतरिक समय प्रारूप अमान्य है।");
    return ascii_text(buffer,location);
}
int64_t bh_time_year(int64_t value,const void *location){return time_calendar(value,location).year;}
int64_t bh_time_month(int64_t value,const void *location){return time_calendar(value,location).month;}
int64_t bh_time_day(int64_t value,const void *location){return time_calendar(value,location).day;}
int64_t bh_time_now(const void *location){
    struct timespec now;
    if(timespec_get(&now,TIME_UTC)!=TIME_UTC||now.tv_nsec<0||now.tv_nsec>=1000000000L)runtime_error(location,"वर्तमान समय उपलब्ध नहीं है।");
    int64_t seconds=(int64_t)now.tv_sec,fraction=now.tv_nsec/1000000;
    if(seconds<INT64_MIN/1000||seconds>INT64_MAX/1000)runtime_error(location,"वर्तमान समय उपलब्ध नहीं है।");
    int64_t result=seconds*1000;
    if(result>INT64_MAX-fraction)runtime_error(location,"वर्तमान समय उपलब्ध नहीं है।");
    return result+fraction;
}

/* Buffer only raw bytes ahead of the next call; UTF-8 decoding is line-local.
   The growable scratch text is precisely rooted across every capacity allocation. */
static void *console_line(int integer,const void *location){
    const char *read_error=integer?"कंसोल से UTF-8 इनपुट पढ़ा नहीं जा सका।":"UTF-8 पाठ पढ़ा नहीं जा सका।";
    if(fflush(stdout))runtime_error(location,read_error);
    data_buffer b;buffer_init(&b,UINT64_MAX,"पाठ सीमा से बाहर है।",location);
    for(;;){
        int c=fgetc(stdin);
        if(console_skip_lf){console_skip_lf=0;if(c=='\n')c=fgetc(stdin);}
        if(c==EOF){
            if(ferror(stdin))runtime_error(location,read_error);
            if(!b.size)runtime_error(location,integer?"इनपुट समाप्त: एक पूर्णांक अपेक्षित था।":"इनपुट समाप्त: पाठ अपेक्षित था।");
            break;
        }
        if(c=='\n')break;
        if(c=='\r'){console_skip_lf=1;break;}
        unsigned char byte=(unsigned char)c;buffer_bytes(&b,&byte,1,1);
    }
    if(b.size&&!valid_utf8(text_data(b.slots[0]),b.size))runtime_error(location,read_error);
    return buffer_finish(&b);
}
void *bh_console_text(const void *location){return console_line(0,location);}
/* Java Character.isWhitespace: deliberately excludes NBSP, figure space and NNBSP. */
static int console_space(uint32_t c){
    return (c>=9&&c<=13)||(c>=0x1c&&c<=0x20)||c==0x1680||(c>=0x2000&&c<=0x2006)
        ||(c>=0x2008&&c<=0x200a)||c==0x2028||c==0x2029||c==0x205f||c==0x3000;
}
int64_t bh_console_integer(const void *location){
    void *slot=console_line(1,location);bh_root_frame frame;bh_roots_push(&frame,&slot,1);
    uint64_t n=text_length(slot),i=0,start=n,end=0;
    while(i<n){uint64_t at=i;uint32_t c=scalar(text_data(slot),&i);if(!console_space(c)){if(start==n)start=at;end=i;}}
    i=start;int negative=0,script=0,overflow=0;uint64_t value=0;
    if(i<end&&(text_data(slot)[i]=='+'||text_data(slot)[i]=='-'))negative=text_data(slot)[i++]=='-';
    const char *invalid="अमान्य इनपुट: एक पंक्ति में एक पूर्णांक लिखें (0-9 या ०-९)।";
    if(i>=end)runtime_error(location,invalid);
    uint64_t limit=negative?UINT64_C(9223372036854775808):INT64_MAX;
    while(i<end){
        uint32_t c=scalar(text_data(slot),&i);int current,digit;
        if(c>='0'&&c<='9'){current=1;digit=(int)(c-'0');}
        else if(c>=0x966&&c<=0x96f){current=2;digit=(int)(c-0x966);}
        else{runtime_error(location,invalid);return 0;}
        if(script&&current!=script)runtime_error(location,invalid);script=current;
        if(value>(limit-(uint64_t)digit)/10)overflow=1;
        if(!overflow)value=value*10+(uint64_t)digit;
    }
    /* Grammar errors take precedence over overflow, like the managed regex check. */
    if(overflow)runtime_error(location,"पूर्णांक 64-बिट सीमा से बाहर है।");
    int64_t result=negative?(value==UINT64_C(9223372036854775808)?INT64_MIN:-(int64_t)value):(int64_t)value;
    bh_roots_pop(&frame);return result;
}
