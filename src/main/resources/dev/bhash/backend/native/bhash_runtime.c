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
static int stress, collecting;
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
void bh_shutdown(void) {
    if (roots) fatal("आंतरिक मूल-संदर्भ शेष है।");
    bh_collect();
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
static void arithmetic_error(const void *location, const char *message) {
    fputs("भाष: ", stderr);
    uint64_t size = text_length(location);
    fwrite((const unsigned char *)location + 8, 1, (size_t)size, stderr);
    fputs(": ", stderr); fputs(message, stderr); fputc('\n', stderr);
    fflush(stderr); exit(1);
}
int64_t bh_divide(int64_t a, int64_t b, const void *location) {
    if (!b) arithmetic_error(location, "शून्य से भाग देना संभव नहीं है।");
    return a == INT64_MIN && b == -1 ? INT64_MIN : a / b;
}
int64_t bh_remainder(int64_t a, int64_t b, const void *location) {
    if (!b) arithmetic_error(location, "शून्य से शेषफल निकालना संभव नहीं है।");
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
