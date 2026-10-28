/* SPDX-License-Identifier: Apache-2.0 */
#include "bhash_runtime.h"
#include <limits.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <errno.h>
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
    struct bh_object *next, *work_next;
    const bh_type *type;
    size_t bytes;
    unsigned marked;
    bh_alignment alignment;
    unsigned char payload[];
} bh_object;
static bh_object *objects, *work;
static bh_root_frame *roots;
static uint64_t live_bytes, live_objects, threshold = 1048576;
static int stress, collecting, report_stats;
static uint64_t allocations, collections, peak_bytes;
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
    for (bh_object *o = objects; o; o = o->next)
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
    for (bh_root_frame *f = roots; f; f = f->previous)
        for (uint64_t i = 0; i < f->count; ++i) mark(f->slots[i]);
    while (work) {
        bh_object *o = work; work = o->work_next;
        for (size_t i = 0; i < o->type->reference_count; ++i) {
            void *value;
            memcpy(&value, o->payload + o->type->reference_offsets[i], sizeof value);
            mark(value);
        }
    }
    bh_object **link = &objects;
    while (*link) {
        bh_object *o = *link;
        if (o->marked) { o->marked = 0; link = &o->next; }
        else {
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
    if (stress || live_bytes + requested + offsetof(bh_object, payload) > threshold) bh_collect();
    bh_object *o = calloc(1, offsetof(bh_object, payload) + bytes);
    if (!o) {
        bh_collect(); o = calloc(1, offsetof(bh_object, payload) + bytes);
        if (!o) fatal("स्मृति उपलब्ध नहीं है।");
    }
    o->type = type; o->bytes = bytes; o->next = objects; objects = o;
    live_bytes += requested + offsetof(bh_object, payload); ++live_objects;
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
    if (roots) fatal("आंतरिक मूल-संदर्भ शेष है।");
    bh_collect();
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
static void runtime_error(const void *location, const char *message) {
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
static const bh_type text_type = {"text", sizeof(uint64_t), 0, NULL};
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
void bh_text_fail(const void *message, const void *location) {
    fputs("भाष: ", stderr); fwrite(text_data(location), 1, (size_t)text_length(location), stderr);
    fputs(": ", stderr); fwrite(text_data(message), 1, (size_t)text_length(message), stderr);
    fputc('\n', stderr); fflush(stderr); exit(1);
}
