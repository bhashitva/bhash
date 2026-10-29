/* SPDX-License-Identifier: Apache-2.0 */
#include "bhash_runtime.h"
#include <assert.h>
#include <stdio.h>
#include <string.h>
#define TEXT(name,value) static const struct {uint64_t n;char data[sizeof(value)];} name={sizeof(value)-1,value}
TEXT(location,"static-test:1:1");TEXT(message,"स्थिर😀");
static const size_t refs[]={8,16};
static const bh_type exception_type={"exception",24,2,refs,0,0,0};
static const int64_t ids[]={-5,-4};
static const bh_class_info info={NULL,NULL,2,ids};
static void *text_slot,*failure_slot,*partial_slot;
static void **global_slots[]={&text_slot,&failure_slot,&partial_slot};
static int32_t ready_state,failed_state;
static int success_calls,failure_calls;
static int64_t initialize_ready(void *unused){
    (void)unused;++success_calls;
    bh_class_initialize(&ready_state,&failure_slot,initialize_ready); /* recursive use */
    bh_static_reference_store(&text_slot,bh_text_concat(&message,&message,&location));
    return 0;
}
static int64_t initialize_failed(void *unused){
    (void)unused;++failure_calls;
    bh_static_reference_store(&partial_slot,bh_text_concat(&message,&message,&location));
    void *temporary=bh_text_concat(&message,&message,&location);bh_root_frame abandoned;bh_roots_push(&abandoned,&temporary,1);
    return bh_divide(1,0,&location);
}
static int64_t use_failed(void *unused){(void)unused;bh_class_initialize(&failed_state,&failure_slot,initialize_failed);return 0;}
int main(void){
    bh_runtime_init();bh_gc_stress(1);bh_exception_configure(&exception_type,&info);
    bh_static_roots_configure(global_slots,3);
    bh_class_initialize(&ready_state,&failure_slot,initialize_ready);
    assert(ready_state==2 && success_calls==1);
    for(unsigned i=0;i<1000;++i){
        bh_class_initialize(&ready_state,&failure_slot,initialize_ready);
        bh_static_reference_store(&text_slot,bh_text_concat(&message,&message,&location));
        bh_collect();assert(bh_heap_objects()==1);
    }
    /* Integers that happen to contain payload addresses are never global roots. */
    uint64_t scalar=(uint64_t)(uintptr_t)bh_text_concat(&message,&message,&location);
    bh_collect();assert(scalar && bh_heap_objects()==1);
    void *local=text_slot;bh_root_frame frame;bh_roots_push(&frame,&local,1);
    assert(bh_protect(use_failed,NULL)==-1);assert(failed_state==3 && failure_calls==1);
    assert(bh_exception_take()==failure_slot);bh_collect();assert(bh_text_length(local,&location)==12);
    for(unsigned i=0;i<1000;++i){
        assert(bh_protect(use_failed,NULL)==-1);assert(bh_exception_take()==failure_slot);
        bh_collect();assert(failure_calls==1 && bh_text_length(text_slot,&location)==12);
    }
    bh_roots_pop(&frame);
    bh_static_reference_store(&text_slot,NULL);bh_static_reference_store(&partial_slot,NULL);bh_static_reference_store(&failure_slot,NULL);
    bh_collect();assert(bh_heap_objects()==0);
    bh_static_reference_store(&text_slot,bh_text_concat(&message,&message,&location));
    bh_shutdown();assert(!text_slot && !failure_slot && !partial_slot && bh_heap_objects()==0);
    puts("Native static checks passed.");return 0;
}
