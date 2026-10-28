/* SPDX-License-Identifier: Apache-2.0 */
#include "bhash_runtime.h"
#include <assert.h>
#include <stdio.h>
#include <string.h>
#define TEXT(name, value) static const struct { uint64_t length; char text[sizeof(value)]; } name = {sizeof(value)-1, value}
TEXT(location,"exception-test:1:1"); TEXT(message,"जीवित😀");
static const size_t refs[] = {8,16};
static const bh_type exception_type = {"exception",24,2,refs,0,0,0};
static const int64_t ids[] = {-5,-4};
static const bh_class_info info = {NULL,NULL,2,ids};
static const int64_t map_ids[] = {0,-4};
static const bh_collection_info map_info = {{NULL,NULL,2,map_ids},BH_MAP,BH_ANY,BH_TEXT,NULL,NULL,NULL};
static int64_t divide_fail(void *unused) {
    (void)unused;
    void *slots[1] = {NULL}; bh_root_frame frame; bh_roots_push(&frame,slots,1);
    slots[0] = bh_text_concat(&message,&message,&location);
    return bh_divide(1,0,&location); /* Deliberately abandons the helper root frame. */
}
static int64_t literal_fail(void *unused) {
    (void)unused;
    const uint64_t values[] = {0,(uint64_t)(uintptr_t)&message};
    bh_collection_literal(&map_info,1,values,&location); return 99;
}
static int64_t nested(void *unused) {
    assert(bh_protect(divide_fail,unused)==-1);
    void *slots[1] = {bh_exception_take()}; bh_root_frame frame; bh_roots_push(&frame,slots,1);
    bh_collect(); assert(bh_heap_objects()==2);
    bh_roots_pop(&frame); return 7;
}
static int64_t recursive_throw(void *environment) {
    void **outer = environment;
    void *slots[1] = {NULL}; bh_root_frame frame; bh_roots_push(&frame,slots,1);
    slots[0] = bh_text_concat(&message,&message,&location);
    int64_t *depth = outer[1];
    if ((*depth)-- > 0) return recursive_throw(environment);
    bh_throw(outer[0],&location);
}
int main(int argc,char **argv) {
    bh_gc_stress(1);bh_exception_configure(&exception_type,&info);
    if(argc>1 && !strcmp(argv[1],"uncaught")) {
        int code=bh_run(divide_fail);bh_shutdown();assert(bh_heap_objects()==0);return code;
    }
    void *slots[2] = {NULL,NULL}; bh_root_frame frame; bh_roots_push(&frame,slots,2);
    for(int i=0;i<1000;i++) {
        slots[0]=NULL;
        assert(bh_protect(i%2 ? divide_fail : literal_fail,NULL)==-1);
        bh_collect();assert(bh_heap_objects()==2); /* Pending exception is itself rooted. */
        slots[0]=bh_exception_take();bh_collect();assert(bh_heap_objects()==2);
    }
    slots[0]=NULL;bh_collect();assert(bh_heap_objects()==0);
    assert(bh_protect(nested,NULL)==7);bh_collect();assert(bh_heap_objects()==0);
    slots[0]=bh_alloc(&exception_type,24);const void *metadata=&info;memcpy(slots[0],&metadata,8);
    bh_reference_store(slots[0],8,(void *)&message);
    int64_t depth=100;void *environment[2] = {slots[0],&depth};
    assert(bh_protect(recursive_throw,environment)==-1);assert(bh_exception_take()==slots[0]);
    bh_collect();assert(bh_heap_objects()==1); /* All 101 abandoned text roots are gone. */
    void *source;memcpy(&source,(unsigned char *)slots[0]+16,8);assert(source==&location);
    slots[0]=NULL;bh_roots_pop(&frame);bh_shutdown();assert(bh_heap_objects()==0);
    bh_exception_configure(&exception_type,&info);
    assert(bh_protect(literal_fail,NULL)==-1);bh_exception_take();bh_shutdown();assert(bh_heap_objects()==0);
    puts("Native exception checks passed.");return 0;
}
