/* SPDX-License-Identifier: Apache-2.0 */
#include "bhash_runtime.h"
#include <limits.h>
#include <float.h>
#include <math.h>
_Static_assert(sizeof(double) == 8 && DBL_MANT_DIG == 53 && DBL_MAX_EXP == 1024, "binary64 required");
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <errno.h>
#include <setjmp.h>
#ifdef _MSC_VER
#include <io.h>
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
static void *pending_exception;
static const bh_type *exception_descriptor;
static const void *exception_info;
typedef struct bh_cleanup {
    struct bh_cleanup *previous;
    void *memory;
} bh_cleanup;
static bh_cleanup *cleanups;
typedef struct bh_handler {
    jmp_buf jump;
    struct bh_handler *previous;
    bh_root_frame *saved_roots;
    bh_cleanup *saved_cleanups;
} bh_handler;
static bh_handler *handlers;
static _Noreturn void runtime_error(const void *, const char *);
static _Noreturn void raise_failure_text(const void *, const void *);
static _Noreturn void raise_failure(const void *, const char *);
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
    for (bh_root_frame *f = roots; f; f = f->previous)
        for (uint64_t i = 0; i < f->count; ++i) mark(f->slots[i]);
    while (work) {
        bh_object *o = work; work = o->work_next;
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
    stress = environment_flag("BHASH_GC_STRESS");
    report_stats = environment_flag("BHASH_GC_STATS");
}
void bh_shutdown(void) {
    if (roots || handlers || cleanups || pending_exception) fatal("आंतरिक मूल-संदर्भ शेष है।");
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
    uint64_t n = text_length(s), i = 0, value = 0; int negative = 0, script = 0;
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
        if (value > (limit - (uint64_t)digit) / 10) runtime_error(location, "पूर्णांक 64-बिट सीमा से बाहर है।");
        value = value * 10 + (uint64_t)digit;
    }
    if (negative && value == UINT64_C(9223372036854775808)) return INT64_MIN;
    return negative ? -(int64_t)value : (int64_t)value;
}

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
    handler.previous = handlers; handler.saved_roots = roots; handler.saved_cleanups = cleanups; handlers = &handler;
    /* Neither saved field changes between setjmp and longjmp. The generated
       function's environment lives in its caller, outside this C boundary. */
    if (setjmp(handler.jump) == 0) {
        int64_t completion = callback(environment);
        if (roots != handler.saved_roots || cleanups != handler.saved_cleanups) fatal("आंतरिक अपवाद मूल-संदर्भ असंतुलित हैं।");
        handlers = handler.previous; return completion;
    }
    handlers = handler.previous;
    if (roots != handler.saved_roots || cleanups != handler.saved_cleanups) fatal("आंतरिक अपवाद मूल-संदर्भ असंतुलित हैं।");
    return -1;
}
void *bh_exception_take(void) { void *value = pending_exception; pending_exception = NULL; return value; }
_Noreturn void bh_throw(void *value, const void *location) {
    if (!value) runtime_error(location, "रिक्त वस्तु के सदस्य का उपयोग नहीं किया जा सकता।");
    void *original; memcpy(&original, (unsigned char *)value + 16, 8);
    if (location && (!original || !text_length(original))) bh_reference_store(value, 16, (void *)location);
    pending_exception = value;
    if (!handlers) fatal("आंतरिक अपवाद सीमा अनुपस्थित है।");
    /* Discard every generated/runtime helper frame abandoned by this transfer.
       The pending object is a precise global root before any frame is removed. */
    roots = handlers->saved_roots;
    while (cleanups != handlers->saved_cleanups) {
        bh_cleanup *entry = cleanups; cleanups = entry->previous; free(entry->memory);
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
    fwrite(text_data(message), 1, (size_t)text_length(message), stderr); fputc('\n', stderr); fflush(stderr);
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
    return id == -6 ? BH_REAL : id >= -3 && id <= -1 ? (uint64_t)-id : BH_REFERENCE;
}
static uint64_t box_payload(const void *value) { uint64_t bits; memcpy(&bits, (const unsigned char *)value + 8, 8); return bits; }
static int collection_same(uint64_t kind, uint64_t a, uint64_t b) {
    if (a == b) return 1;
    if (kind == BH_ANY) {
        if (!a || !b) return 0;
        uint64_t ak = boxed_kind(bits_pointer(a)), bk = boxed_kind(bits_pointer(b));
        if (ak != bk || ak == BH_REFERENCE) return 0;
        return collection_same(ak, box_payload(bits_pointer(a)), box_payload(bits_pointer(b)));
    }
    if (kind == BH_REAL) {
        double x, y; memcpy(&x, &a, 8); memcpy(&y, &b, 8);
        return x == y || (isnan(x) && isnan(y));
    }
    if (kind == BH_TEXT) return bh_text_equal(bits_pointer(a), bits_pointer(b));
    return 0;
}
static uint64_t collection_hash(uint64_t kind, uint64_t value) {
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
            result = c->data[map ? index*4+(operation == BH_ITER_VALUE ? 1 : 0) : index]; break;
        case BH_GET: case BH_GET_OR:
            if (map) {
                if (index == UINT64_MAX && operation == BH_GET) runtime_error(location, "मानचित्र में कुंजी नहीं मिली।");
                result = index == UINT64_MAX ? b : c->data[index*4+1];
            } else result = c->data[checked_index(a, c->size, 0, location)];
            break;
        case BH_CONTAINS:
            if (map) result = index != UINT64_MAX;
            else for (uint64_t i=0; i<c->size; ++i) if (collection_same(value_kind, c->data[i], a)) { result = 1; break; }
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
            } else storage_store(c->data, checked_index(a, c->size, 0, location), b, reference_kind(value_kind));
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
    bh_cleanup cleanup = {cleanups, slots}; cleanups = &cleanup;
    for (uint64_t i=0; i<words; ++i) {
        uint64_t kind = width==2 && i%2==0 ? info->key_kind : info->value_kind;
        if (reference_kind(kind)) slots[i+1] = bits_pointer(values[i]);
    }
    bh_root_frame frame; bh_roots_push(&frame, slots, words+1);
    slots[0] = bh_collection_new(info, info->kind == BH_ARRAY ? (int64_t)count : 0, location);
    for (uint64_t i=0; i<count; ++i) {
        uint64_t ak = width==2 ? info->key_kind : info->value_kind;
        uint64_t a = reference_kind(ak) ? pointer_bits(slots[i*width+1]) : values[i*width];
        if (width==2) {
            uint64_t b = reference_kind(info->value_kind) ? pointer_bits(slots[i*2+2]) : values[i*2+1];
            bh_collection_call(slots[0], BH_SET, a, b, NULL, location);
        } else if (info->kind == BH_LIST) bh_collection_call(slots[0], BH_ADD, a, 0, NULL, location);
        else {
            bh_collection *c = slots[0]; storage_store(c->data, i, a, reference_kind(info->value_kind));
        }
    }
    void *result = slots[0]; bh_roots_pop(&frame); cleanups = cleanup.previous; free(slots); return result;
}
