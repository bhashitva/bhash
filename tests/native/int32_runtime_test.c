/* SPDX-License-Identifier: Apache-2.0 */
#include "bhash_runtime.h"
#include <assert.h>
#include <stdio.h>
#include <string.h>
#define TEXT(name,value) static const struct {uint64_t n;char data[sizeof(value)];} name={sizeof(value)-1,value}
TEXT(location,"int32-test:1:1");TEXT(expected,"[-2147483648,2147483647,7]");
TEXT(bad_suffix,"999999999999999999999999999999999x");
static const size_t exception_refs[]={8,16},text_refs[]={8};
static const bh_type exception_type={"exception",24,2,exception_refs,0,0,0},scalar_box={"scalar box",16,0,NULL,0,0,0},text_box={"text box",16,1,text_refs,0,0,0};
static const int64_t exception_ids[]={-5,-4},small_ids[]={-8,-4},integer_ids[]={-1,-4},bool_ids[]={-2,-4},text_ids[]={-3,-4},real_ids[]={-6,-4};
static const bh_class_info exception_info={NULL,NULL,2,exception_ids},small_info={NULL,NULL,2,small_ids},integer_info={NULL,NULL,2,integer_ids},bool_info={NULL,NULL,2,bool_ids},text_info={NULL,NULL,2,text_ids},real_info={NULL,NULL,2,real_ids};
static const int64_t list_ids[]={0,-4},values_ids[]={1,-4},object_ids[]={2,-4},small_list_ids[]={3,-4},map_ids[]={4,-4},any_map_ids[]={5,-4},array_ids[]={6,-4};
static const bh_collection_info texts={{NULL,NULL,2,list_ids},BH_LIST,BH_INT,BH_TEXT,NULL,NULL,NULL};
static const bh_collection_info values={{NULL,NULL,2,values_ids},BH_LIST,BH_INT,BH_ANY,NULL,NULL,NULL};
static const bh_collection_info object={{NULL,NULL,2,object_ids},BH_MAP,BH_TEXT,BH_ANY,NULL,&texts,&values};
static const bh_collection_info small_list={{NULL,NULL,2,small_list_ids},BH_LIST,BH_INT,BH_INT32,NULL,NULL,NULL};
static const bh_collection_info map={{NULL,NULL,2,map_ids},BH_MAP,BH_INT32,BH_ANY,NULL,&small_list,&values};
static const bh_collection_info any_map={{NULL,NULL,2,any_map_ids},BH_MAP,BH_ANY,BH_ANY,NULL,&values,&values};
static const bh_collection_info array={{NULL,NULL,2,array_ids},BH_ARRAY,BH_INT,BH_INT32,NULL,NULL,NULL};
static const bh_data_info data={&texts,&values,&object,{&scalar_box,&scalar_box,&text_box,&scalar_box},{&integer_info,&bool_info,&text_info,&real_info}};
static uint64_t bits(const void *p){return (uint64_t)(uintptr_t)p;}
static void *box(int64_t value,int small){
    if(small)value=bh_to_int32(value,&location);
    void *result=bh_alloc(&scalar_box,16);const bh_class_info *info=small?&small_info:&integer_info;
    memcpy(result,&info,8);memcpy((unsigned char *)result+8,&value,8);return result;
}
static int64_t narrow(void *value){return bh_to_int32(*(int64_t *)value,&location);}
static int64_t suffix(void *unused){(void)unused;return bh_text_parse_integer(&bad_suffix,&location);}
static void failure(bh_protected_callback callback,void *input,const char *message){
    assert(bh_protect(callback,input)==-1);void *exception=bh_exception_take();bh_root_frame frame;bh_roots_push(&frame,&exception,1);bh_collect();
    void *text;memcpy(&text,(unsigned char *)exception+8,8);uint64_t n;memcpy(&n,text,8);assert(n==strlen(message)&&!memcmp((unsigned char *)text+8,message,(size_t)n));bh_roots_pop(&frame);
}
int main(void){
    bh_runtime_init();bh_gc_stress(1);bh_exception_configure(&exception_type,&exception_info);bh_data_configure(&data);
    for(int64_t n=-100000;n<=100000;++n)assert(bh_to_int32(n,&location)==n);
    assert(bh_to_int32(INT32_MIN,&location)==INT32_MIN);assert(bh_to_int32(INT32_MAX,&location)==INT32_MAX);
    for(unsigned repeat=0;repeat<100;++repeat){
        int64_t invalid[]={INT64_MIN,INT64_MAX,(int64_t)INT32_MIN-1,(int64_t)INT32_MAX+1};
        for(unsigned i=0;i<4;++i)failure(narrow,&invalid[i],"लघुपूर्णांक परिवर्तन सीमा से बाहर है।");
        failure(suffix,NULL,"अमान्य पूर्णांक पाठ।");bh_collect();assert(bh_heap_objects()==0);
    }
    void *slots[8]={NULL,NULL,NULL,NULL,NULL,NULL,NULL,NULL};bh_root_frame frame;bh_roots_push(&frame,slots,8);
    slots[0]=bh_collection_new(&small_list,0,&location);
    const int64_t entries[]={INT32_MIN,INT32_MAX,7};
    for(unsigned i=0;i<3;++i)bh_collection_call(slots[0],BH_ADD,(uint64_t)entries[i],0,NULL,&location);
    slots[1]=bh_data_json_write(slots[0],&location);assert(bh_text_equal(slots[1],&expected));slots[1]=NULL;
    slots[1]=bh_collection_new(&array,2,&location);assert(bh_collection_call(slots[1],BH_GET,0,0,NULL,&location)==0);
    bh_collection_call(slots[1],BH_SET,0,(uint64_t)(int64_t)INT32_MIN,NULL,&location);assert((int64_t)bh_collection_call(slots[1],BH_GET,0,0,NULL,&location)==INT32_MIN);
    slots[2]=bh_collection_new(&any_map,0,&location);slots[3]=box(2,1);slots[4]=box(2,0);
    bh_collection_call(slots[2],BH_SET,bits(slots[3]),bits(slots[3]),NULL,&location);bh_collection_call(slots[2],BH_SET,bits(slots[4]),bits(slots[4]),NULL,&location);
    assert(bh_collection_call(slots[2],BH_SIZE,0,0,NULL,&location)==2);
    slots[5]=box(2,1);assert(bh_collection_call(slots[2],BH_CONTAINS,bits(slots[5]),0,NULL,&location)==1);
    slots[6]=bh_collection_new(&map,0,&location);
    for(int64_t i=0;i<1000;++i){slots[7]=box(i,1);bh_collection_call(slots[6],BH_SET,(uint64_t)bh_to_int32(i-500,&location),bits(slots[7]),NULL,&location);}
    slots[7]=(void *)(uintptr_t)bh_collection_call(slots[6],BH_KEYS,0,0,&small_list,&location);
    assert((int64_t)bh_collection_call(slots[7],BH_GET,0,0,NULL,&location)==-500);
    assert((int64_t)bh_collection_call(slots[7],BH_GET,999,0,NULL,&location)==499);
    slots[7]=(void *)(uintptr_t)bh_collection_call(slots[6],BH_VALUES,0,0,&values,&location);
    bh_collection_call(slots[6],BH_CLEAR,0,0,NULL,&location);bh_collect();assert(bh_collection_call(slots[7],BH_SIZE,0,0,NULL,&location)==1000);
    void *last=(void *)(uintptr_t)bh_collection_call(slots[7],BH_GET,999,0,NULL,&location);int64_t payload;memcpy(&payload,(unsigned char *)last+8,8);assert(payload==999);
    for(unsigned i=0;i<8;++i)slots[i]=NULL;bh_collect();assert(bh_heap_objects()==0);
    bh_roots_pop(&frame);bh_shutdown();puts("Native int32 checks passed.");return 0;
}
