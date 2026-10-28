/* SPDX-License-Identifier: Apache-2.0 */
#include "bhash_runtime.h"
#include <assert.h>
#include <stdio.h>
#include <string.h>
#define TEXT(name, value) const struct { uint64_t length; unsigned char bytes[sizeof(value)]; } name = {sizeof(value)-1, value}
int main(int argc, char **argv) {
    TEXT(location, "runtime-test:1:1"); TEXT(empty, ""); TEXT(unicode, "a😀क́");
    TEXT(slice, "😀क"); TEXT(abc, "ab\0abc"); TEXT(ab, "ab"); TEXT(x, "X"); TEXT(replaced, "X\0Xc");
    TEXT(upper, "ABÉक"); TEXT(lower, "abÉक"); TEXT(padded, " \t abÉक　\n"); TEXT(emoji, "😀");
    TEXT(decimal, "-9223372036854775808"); TEXT(devanagari, "-९२२३३७२०३६८५४७७५८०८");
    TEXT(mixed, "1२"); TEXT(overflow, "9223372036854775808");
    bh_gc_stress(1);
    void *slots[5] = {NULL, NULL, NULL, NULL, NULL}; bh_root_frame frame;
    bh_roots_push(&frame, slots, 5);
    if (argc > 1) {
        if (!strcmp(argv[1], "empty-replace")) bh_text_replace(&abc, &empty, &x, &location);
        if (!strcmp(argv[1], "slice-bounds")) bh_text_slice(&unicode, 0, 5, &location);
        if (!strcmp(argv[1], "negative-repeat")) bh_text_repeat(&x, -1, &location);
        if (!strcmp(argv[1], "repeat-limit")) bh_text_repeat(&emoji, 524289, &location);
        if (!strcmp(argv[1], "mixed-digits")) bh_text_parse_integer(&mixed, &location);
        if (!strcmp(argv[1], "integer-overflow")) bh_text_parse_integer(&overflow, &location);
        if (!strcmp(argv[1], "explicit-failure")) bh_text_fail(&abc, &location);
        return 9;
    }
    assert(bh_text_length(&unicode, &location) == 4);
    slots[0] = bh_text_slice(&unicode, 1, 3, &location); assert(bh_text_equal(slots[0], &slice));
    assert(bh_text_find(&unicode, &slice, &location) == 1);
    assert(bh_text_find(&unicode, &empty, &location) == 0);
    assert(bh_text_contains(&unicode, &slice, &location));
    assert(bh_text_starts(&abc, &ab, &location));
    assert(bh_text_ends(&unicode, &empty, &location));
    slots[1] = bh_text_replace(&abc, &ab, &x, &location); assert(bh_text_equal(slots[1], &replaced));
    slots[2] = bh_text_trim(&padded, &location); assert(bh_text_equal(slots[2], &lower));
    slots[3] = bh_text_upper(slots[2], &location); assert(bh_text_equal(slots[3], &upper));
    slots[4] = bh_text_lower(slots[3], &location); assert(bh_text_equal(slots[4], &lower));
    slots[0] = bh_text_integer(INT64_MIN, &location); assert(bh_text_equal(slots[0], &decimal));
    assert(bh_text_parse_integer(slots[0], &location) == INT64_MIN);
    slots[1] = bh_text_devanagari(slots[0], &location); assert(bh_text_equal(slots[1], &devanagari));
    assert(bh_text_parse_integer(slots[1], &location) == INT64_MIN);
    slots[0] = bh_text_concat(slots[2], &empty, &location); assert(bh_text_equal(slots[0], &lower));
    slots[0] = bh_text_repeat(&emoji, 524288, &location); assert(bh_text_length(slots[0], &location) == 524288);
    slots[0] = bh_text_repeat(&empty, INT64_MAX, &location); assert(bh_text_equal(slots[0], &empty));
    /* The helper itself roots an otherwise unregistered argument during allocation. */
    void *unregistered = bh_text_concat(&lower, &empty, &location);
    slots[0] = bh_text_upper(unregistered, &location); assert(bh_text_equal(slots[0], &upper));
    for (size_t i = 0; i < 5; ++i) slots[i] = NULL;
    bh_collect(); assert(bh_heap_objects() == 0);
    bh_roots_pop(&frame); bh_shutdown();
    puts("Native heap text checks passed."); return 0;
}
