/* SPDX-License-Identifier: Apache-2.0 */
#include "bhash_runtime.h"
#include <assert.h>
#include <stdio.h>
#include <string.h>
#include <stdlib.h>
typedef struct Node { void *next; uint64_t marker; } Node;
static const size_t offsets[] = { offsetof(Node, next) };
static const bh_type node_type = {"test.node", sizeof(Node), 1, offsets, 0, 0, 0};
static const bh_type scalar_type = {"test.scalar", sizeof(uint64_t), 0, NULL, 0, 0, 0};
static void nested(void *outer) {
    void *slots[2] = {NULL, outer}; bh_root_frame frame;
    bh_roots_push(&frame, slots, 2);
    slots[0] = bh_alloc(&scalar_type, sizeof(uint64_t));
    bh_collect();
    assert(bh_heap_objects() == 2);
    bh_roots_pop(&frame);
}
int main(int argc, char **argv) {
    bh_gc_stress(1);
    void *slots[2] = {NULL, NULL}; bh_root_frame frame;
    bh_roots_push(&frame, slots, 2);
    if (argc > 1 && strcmp(argv[1], "oversize") == 0) {
        bh_alloc(&scalar_type, UINT64_MAX); return 9;
    }
    if (argc > 1 && strcmp(argv[1], "invalid-descriptor") == 0) {
        const size_t bad_offsets[] = {sizeof(Node)};
        const bh_type bad_type = {"bad", sizeof(Node), 1, bad_offsets, 0, 0, 0};
        bh_alloc(&bad_type, sizeof(Node)); return 9;
    }
    if (argc > 1 && strcmp(argv[1], "output") == 0) {
        bh_print_int(INT64_MIN); bh_print_space(); bh_print_int(INT64_MAX);
        bh_print_space(); bh_print_bool(1); bh_print_space(); bh_print_bool(0); bh_print_end();
        bh_roots_pop(&frame); bh_shutdown(); return 0;
    }
    slots[0] = bh_alloc(&node_type, sizeof(Node));
    ((Node *)slots[0])->marker = 42;
    nested(slots[0]);
    bh_collect();
    assert(bh_heap_objects() == 1 && ((Node *)slots[0])->marker == 42);
    slots[1] = bh_alloc(&node_type, sizeof(Node));
    bh_reference_store(slots[0], offsetof(Node, next), slots[1]);
    bh_reference_store(slots[1], offsetof(Node, next), slots[0]);
    void *first = slots[0];
    slots[0] = NULL;
    bh_collect();
    assert(bh_heap_objects() == 2 && ((Node *)slots[1])->next == first);
    slots[1] = NULL;
    bh_collect();
    assert(bh_heap_objects() == 0 && bh_heap_bytes() == 0); /* Unreachable cycle. */
    /* A number with the bit pattern of a heap pointer is not a root. */
    slots[0] = bh_alloc(&scalar_type, sizeof(uint64_t));
    void *dead = bh_alloc(&node_type, sizeof(Node));
    uintptr_t pointer_bits = (uintptr_t)dead;
    memcpy(slots[0], &pointer_bits, sizeof pointer_bits);
    bh_collect();
    assert(bh_heap_objects() == 1);
    slots[0] = NULL;
    /* A deep graph is scanned iteratively, not on the C call stack. */
    bh_gc_stress(0);
    for (int i = 0; i < 5000; ++i) {
        Node *n = bh_alloc(&node_type, sizeof(Node));
        bh_reference_store(n, offsetof(Node, next), slots[0]);
        slots[0] = n;
    }
    bh_collect(); assert(bh_heap_objects() == 5000);
    slots[0] = NULL; bh_collect(); assert(bh_heap_objects() == 0);
    bh_gc_stress(1);
    for (int i = 0; i < 10000; ++i) slots[0] = bh_alloc(&scalar_type, sizeof(uint64_t));
    assert(bh_heap_objects() <= 2);
    slots[0] = NULL;
    /* Static text is a precisely typed reference but not a managed allocation. */
    const struct { uint64_t length; char data[3]; } a = {3, {'a', 0, 'b'}}, b = {3, {'a', 0, 'b'}};
    slots[0] = (void *)&a; bh_collect(); assert(bh_heap_objects() == 0);
    assert(bh_text_equal(&a, &b));
    assert(bh_divide(INT64_MIN, -1, &a) == INT64_MIN);
    assert(bh_remainder(INT64_MIN, -1, &a) == 0);
    assert(bh_divide(-7, 3, &a) == -2 && bh_remainder(-7, 3, &a) == -1);
    bh_roots_pop(&frame); bh_shutdown();
    /* A grown membership index must also be safe across shutdown/reinitialization. */
    slots[0] = slots[1] = NULL; bh_roots_push(&frame, slots, 2);
    slots[0] = bh_alloc(&scalar_type, sizeof(uint64_t)); bh_collect(); assert(bh_heap_objects() == 1);
    slots[0] = NULL; bh_roots_pop(&frame); bh_shutdown(); assert(bh_heap_objects() == 0);
    puts("Native runtime checks passed.");
    return 0;
}
