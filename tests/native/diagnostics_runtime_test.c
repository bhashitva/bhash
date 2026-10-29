/* SPDX-License-Identifier: Apache-2.0 */
#include "bhash_runtime.h"
#include <assert.h>
#include <stdio.h>
#include <string.h>
#define TEXT(name,value) static const struct {uint64_t n;char text[sizeof(value)];} name={sizeof(value)-1,value}
TEXT(first,"पहला");TEXT(last,"अंतिम");TEXT(later,"बाद");TEXT(location,"trace.bhash:7:3");TEXT(empty,"");TEXT(message,"दोष😀");
static const size_t refs[]={8,16};
static const bh_type exception_type={"exception",24,2,refs,0,0,0};
static const int64_t ids[]={-5,-4};static const bh_class_info info={NULL,NULL,2,ids};
static void *saved;static int dynamic;
static int64_t fail(void *unused){(void)unused;bh_trace_frame f;bh_trace_enter(&f,&last,&location);if(dynamic){
    void *slots[2]={NULL,NULL};bh_root_frame roots;bh_roots_push(&roots,slots,2);
    slots[0]=bh_alloc(&exception_type,24);const bh_class_info *metadata=&info;memcpy(slots[0],&metadata,8);
    bh_reference_store(slots[0],8,(void *)&message);bh_reference_store(slots[0],16,(void *)&location);
    slots[1]=bh_text_concat(&location,&empty,&location);bh_throw(slots[0],slots[1]);
}return bh_divide(1,0,&location);}
static int64_t original(void *unused){(void)unused;bh_trace_frame f;bh_trace_enter(&f,&first,&location);return fail(NULL);}
static int64_t again(void *unused){(void)unused;bh_trace_frame f;bh_trace_enter(&f,&later,&location);bh_throw(saved,&location);}
static int64_t deep(void *unused){int *depth=unused;bh_trace_frame f;bh_trace_enter(&f,&last,&location);if((*depth)-- >0)return deep(depth);return bh_divide(1,0,&location);}
int main(int argc,char **argv){
    bh_runtime_init();bh_gc_stress(1);bh_exception_configure(&exception_type,&info);
    if(argc>1 && !strcmp(argv[1],"deep")){int n=300;assert(bh_protect(deep,&n)==-1);saved=bh_exception_take();}
    else {dynamic=argc>1;assert(bh_protect(original,NULL)==-1);saved=bh_exception_take();}
    void **slots[]={&saved};bh_static_roots_configure(slots,1);bh_collect();
    assert(bh_run(again)==1);bh_shutdown();assert(bh_heap_objects()==0);
    puts("Native diagnostics checks passed.");return 0;
}
