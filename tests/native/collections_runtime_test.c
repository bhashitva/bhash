/* SPDX-License-Identifier: Apache-2.0 */
#include "bhash_runtime.h"
#include <assert.h>
#include <stdio.h>
#include <string.h>
#define TEXT(name, value) const struct { uint64_t length; char text[sizeof(value)]; } name = {sizeof(value)-1, value}
#define INFO(name, id, kind, key, value) \
    static const int64_t name##_ids[] = {id, -4}; \
    static const bh_collection_info name = {{NULL, NULL, 2, name##_ids}, kind, key, value, NULL, NULL, NULL}
INFO(array_int, 10, BH_ARRAY, BH_INT, BH_INT);
INFO(array_ref, 11, BH_ARRAY, BH_INT, BH_REFERENCE);
INFO(array_text, 12, BH_ARRAY, BH_INT, BH_TEXT);
INFO(list_text, 13, BH_LIST, BH_INT, BH_TEXT);
INFO(map_int, 14, BH_MAP, BH_INT, BH_INT);
INFO(map_text, 15, BH_MAP, BH_TEXT, BH_REFERENCE);
INFO(array_bool, 16, BH_ARRAY, BH_INT, BH_BOOL);
INFO(array_real, 17, BH_ARRAY, BH_INT, BH_REAL);
INFO(array_byte, 18, BH_ARRAY, BH_INT, BH_BYTE);
INFO(array_int32, 19, BH_ARRAY, BH_INT, BH_INT32);
static uint64_t bits(const void *p) { return (uint64_t)(uintptr_t)p; }
static uint64_t call(void *p, uint32_t op, uint64_t a, uint64_t b, const void *location) {
    return bh_collection_call(p, op, a, b, NULL, location);
}
int main(int argc, char **argv) {
    TEXT(location,"collection-test:1:1"); TEXT(key,"a\0😀"); TEXT(other_key,"a\0😀");
    void *slots[8] = {NULL}; bh_root_frame frame; bh_roots_push(&frame, slots, 8); bh_gc_stress(1);
    if (argc > 1) {
        if (!strcmp(argv[1],"negative-size")) bh_collection_new(&array_int,-1,&location);
        if (!strcmp(argv[1],"huge-size")) bh_collection_new(&array_int,INT64_MAX,&location);
        if (!strcmp(argv[1],"invalid-repeat")) {
            const bh_type bad = {"bad-repeat",0,0,NULL,0,3,1}; bh_alloc(&bad,6);
        }
        if (!strcmp(argv[1],"array-bounds")) { slots[0]=bh_collection_new(&array_int,0,&location); call(slots[0],BH_GET,0,0,&location); }
        slots[0]=bh_collection_new(&map_text,0,&location);
        if (!strcmp(argv[1],"null-key")) call(slots[0],BH_SET,0,0,&location);
        if (!strcmp(argv[1],"missing-key")) call(slots[0],BH_GET,bits(&key),0,&location);
        if (!strcmp(argv[1],"mutation")) {
            uint64_t version=call(slots[0],BH_VERSION,0,0,&location);
            call(slots[0],BH_SET,bits(&key),0,&location); call(slots[0],BH_ITER_CHECK,version,0,&location);
        }
        return 9;
    }
    /* Full scalar carriers survive the short path bit-for-bit. In particular,
       floating-point NaNs/signed zero and pointer-shaped integer bits are data.
       GET_OR and other operations must retain their generic behavior. */
    const bh_collection_info *scalars[] = {&array_int, &array_bool, &array_real, &array_byte, &array_int32};
    const uint64_t values[] = {UINT64_MAX, 1, UINT64_C(0x8000000000000000),
                              UINT64_C(0x7ff8000000000042), 255, UINT64_C(0xffffffff80000000)};
    for (size_t k=0; k<sizeof(scalars)/sizeof(*scalars); ++k) {
        slots[0]=bh_collection_new(scalars[k],2,&location);
        uint64_t version=call(slots[0],BH_VERSION,0,0,&location);
        for (size_t v=0; v<sizeof(values)/sizeof(*values); ++v) {
            assert(call(slots[0],BH_SET,1,values[v],&location)==0);
            bh_collect();
            assert(call(slots[0],BH_GET,1,0,&location)==values[v]);
            assert(call(slots[0],BH_GET_OR,1,0,&location)==values[v]);
            assert(call(slots[0],BH_VERSION,0,0,&location)==version);
            assert(call(slots[0],BH_GET,0,0,&location)==0);
        }
        slots[0]=NULL; bh_collect(); assert(bh_heap_objects()==0);
    }
    slots[0]=bh_collection_new(&array_text,100,&location);
    assert(call(slots[0],BH_SIZE,0,0,&location)==100);
    assert(bh_text_length((void *)(uintptr_t)call(slots[0],BH_GET,0,0,&location),&location)==0);
    slots[1]=bh_text_concat(&key,&key,&location);
    call(slots[0],BH_SET,99,bits(slots[1]),&location); slots[1]=NULL; bh_collect();
    assert(bh_text_length((void *)(uintptr_t)call(slots[0],BH_GET,99,0,&location),&location)==6);
    slots[0]=NULL; bh_collect(); assert(bh_heap_objects()==0);
    /* A repeated scalar lane containing pointer bits is never traced. */
    slots[0]=bh_collection_new(&array_int,1,&location);
    slots[1]=bh_collection_new(&array_ref,1,&location);
    call(slots[0],BH_SET,0,bits(slots[1]),&location); slots[1]=NULL; bh_collect();
    assert(bh_heap_objects()==2); slots[0]=NULL; bh_collect(); assert(bh_heap_objects()==0);
    /* Cyclic reference arrays are reachable only through explicitly traced lanes. */
    slots[0]=bh_collection_new(&array_ref,1,&location);
    call(slots[0],BH_SET,0,bits(slots[0]),&location); bh_collect(); assert(bh_heap_objects()==2);
    slots[0]=NULL; bh_collect(); assert(bh_heap_objects()==0);
    slots[0]=bh_collection_new(&map_int,0,&location);
    for(uint64_t i=0;i<512;i++)call(slots[0],BH_SET,i,i+1000,&location);
    for(uint64_t i=0;i<512;i++)assert(call(slots[0],BH_GET,i,0,&location)==i+1000);
    for(uint64_t i=0;i<512;i+=2)assert(call(slots[0],BH_REMOVE,i,0,&location)==1);
    for(uint64_t i=1;i<512;i+=2)assert(call(slots[0],BH_GET,i,0,&location)==i+1000);
    assert(call(slots[0],BH_SIZE,0,0,&location)==256);
    slots[0]=NULL; bh_collect(); assert(bh_heap_objects()==0);
    slots[0]=bh_collection_new(&map_text,0,&location);
    for(int64_t i=0;i<128;i++) {
        slots[1]=bh_text_integer(i,&location); slots[2]=bh_collection_new(&array_ref,1,&location);
        call(slots[2],BH_SET,0,bits(slots[0]),&location);
        call(slots[0],BH_SET,bits(slots[1]),bits(slots[2]),&location);
    }
    slots[1]=NULL; slots[2]=NULL; bh_collect();
    assert(call(slots[0],BH_SIZE,0,0,&location)==128);
    slots[3]=(void *)(uintptr_t)bh_collection_call(slots[0],BH_KEYS,0,0,&list_text,&location);
    call(slots[0],BH_CLEAR,0,0,&location); bh_collect();
    assert(call(slots[3],BH_SIZE,0,0,&location)==128);
    assert(bh_heap_objects()==131); /* map + list + list storage + 128 shallow key refs */
    slots[0]=slots[3]=NULL; bh_collect(); assert(bh_heap_objects()==0);
    slots[0]=bh_collection_new(&map_text,0,&location);
    call(slots[0],BH_SET,bits(&key),0,&location);
    assert(call(slots[0],BH_CONTAINS,bits(&other_key),0,&location)==1);
    assert(call(slots[0],BH_GET_OR,bits(&other_key),123,&location)==0);
    slots[0]=NULL; bh_collect();
    bh_roots_pop(&frame); bh_shutdown(); assert(bh_heap_objects()==0);
    puts("Native collection checks passed."); return 0;
}
