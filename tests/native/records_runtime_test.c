/* SPDX-License-Identifier: Apache-2.0 */
#include "bhash_runtime.h"
#include <assert.h>
#include <stdio.h>
#include <string.h>
#include <math.h>
#define TEXT(name,value) static const struct {uint64_t n;char data[sizeof(value)];} name={sizeof(value)-1,value}
TEXT(location,"records-test:1:1");
static const int64_t node_ids[]={20,-4,-9},pair_ids[]={21,-4,-9},empty_ids[]={22,-4,-9},other_ids[]={23,-4,-9},real_ids[]={24,-4,-9},any_ids[]={25,-4,-9},bool_ids[]={26,-4,-9};
static const uint64_t node_kinds[]={BH_INT,BH_REFERENCE},pair_kinds[]={BH_REFERENCE,BH_REFERENCE},real_kinds[]={BH_REAL,BH_REFERENCE},any_kinds[]={BH_ANY,BH_REFERENCE},bool_kinds[]={BH_BOOL,BH_REFERENCE};
static const bh_record_info node={{NULL,NULL,3,node_ids},2,node_kinds},pair={{NULL,NULL,3,pair_ids},2,pair_kinds},empty={{NULL,NULL,3,empty_ids},0,NULL},other={{NULL,NULL,3,other_ids},0,NULL},real={{NULL,NULL,3,real_ids},2,real_kinds},any={{NULL,NULL,3,any_ids},2,any_kinds},boolean={{NULL,NULL,3,bool_ids},2,bool_kinds};
static const size_t next_ref[]={16},both_refs[]={8,16};
static const bh_type node_type={"record node",24,1,next_ref,0,0,0},pair_type={"record pair",24,2,both_refs,0,0,0},empty_type={"empty",8,0,NULL,0,0,0},box_type={"box",16,0,NULL,0,0,0};
static const int64_t map_ids[]={30,-4},any_map_ids[]={31,-4},integer_ids[]={-1,-4},small_ids[]={-8,-4};
static const bh_collection_info map_info={{NULL,NULL,2,map_ids},BH_MAP,BH_REFERENCE,BH_INT,NULL,NULL,NULL},any_map_info={{NULL,NULL,2,any_map_ids},BH_MAP,BH_ANY,BH_INT,NULL,NULL,NULL};
static const bh_class_info integer_info={NULL,NULL,2,integer_ids},small_info={NULL,NULL,2,small_ids};
static uint64_t bits(const void *p){return (uint64_t)(uintptr_t)p;}
static void *make(const bh_type *type,const bh_record_info *info,uint64_t a,void *b){
    void *slots[3]={NULL,b,NULL};bh_root_frame frame;
    if(info->field_count && (info->field_kinds[0]==BH_REFERENCE || info->field_kinds[0]==BH_ANY || info->field_kinds[0]==BH_TEXT))slots[0]=(void *)(uintptr_t)a;
    bh_roots_push(&frame,slots,3);slots[2]=bh_alloc(type,type->minimum_size);memcpy(slots[2],&info,8);
    if(info->field_count){
        if(slots[0])bh_reference_store(slots[2],8,slots[0]);else memcpy((unsigned char *)slots[2]+8,&a,8);
        bh_reference_store(slots[2],16,slots[1]);
    }
    void *result=slots[2];bh_roots_pop(&frame);return result;
}
static void *box(int small){void *value=bh_alloc(&box_type,16);const bh_class_info *info=small?&small_info:&integer_info;uint64_t one=1;memcpy(value,&info,8);memcpy((unsigned char *)value+8,&one,8);return value;}
static void map_equal(void **slots,int universal){
    slots[8]=bh_collection_new(universal?&any_map_info:&map_info,0,&location);
    bh_collection_call(slots[8],BH_SET,bits(slots[0]),7,NULL,&location);
    assert(bh_collection_call(slots[8],BH_GET,bits(slots[1]),0,NULL,&location)==7);
    bh_collection_call(slots[8],BH_SET,bits(slots[1]),9,NULL,&location);
    assert(bh_collection_call(slots[8],BH_SIZE,0,0,NULL,&location)==1);
    assert(bh_collection_call(slots[8],BH_GET,bits(slots[0]),0,NULL,&location)==9);slots[8]=NULL;
}
int main(void){
    bh_runtime_init();bh_gc_stress(1);void *slots[9]={NULL};bh_root_frame frame;bh_roots_push(&frame,slots,9);
    for(unsigned i=0;i<5000;++i){slots[0]=make(&node_type,&node,i,slots[0]);slots[1]=make(&node_type,&node,i,slots[1]);}
    assert(bh_value_same(BH_REFERENCE,bits(slots[0]),bits(slots[1])));map_equal(slots,0);map_equal(slots,1);
    slots[1]=make(&node_type,&node,9999,slots[1]);assert(!bh_value_same(BH_ANY,bits(slots[0]),bits(slots[1])));
    memset(slots,0,sizeof slots);bh_collect();assert(bh_heap_objects()==0);
    slots[0]=make(&empty_type,&empty,0,NULL);slots[1]=make(&empty_type,&empty,0,NULL);
    for(unsigned i=0;i<80;++i){slots[0]=make(&pair_type,&pair,bits(slots[0]),slots[0]);slots[1]=make(&pair_type,&pair,bits(slots[1]),slots[1]);}
    assert(bh_value_same(BH_ANY,bits(slots[0]),bits(slots[1])));map_equal(slots,0);
    memset(slots,0,sizeof slots);bh_collect();assert(bh_heap_objects()==0);
    slots[0]=make(&empty_type,&empty,0,NULL);slots[1]=make(&empty_type,&other,0,NULL);assert(!bh_value_same(BH_REFERENCE,bits(slots[0]),bits(slots[1])));
    slots[0]=make(&pair_type,&any,0,NULL);slots[1]=make(&pair_type,&any,0,NULL);map_equal(slots,0);
    slots[2]=box(0);slots[0]=make(&pair_type,&any,bits(slots[2]),NULL);slots[2]=box(1);slots[1]=make(&pair_type,&any,bits(slots[2]),NULL);assert(!bh_value_same(BH_ANY,bits(slots[0]),bits(slots[1])));
    uint64_t plus=0,minus=UINT64_C(0x8000000000000000),nan1=UINT64_C(0x7ff8000000000001),nan2=UINT64_C(0x7ff8000000000002);
    slots[0]=make(&node_type,&real,plus,NULL);slots[1]=make(&node_type,&real,minus,NULL);map_equal(slots,0);
    slots[0]=make(&node_type,&real,nan1,NULL);slots[1]=make(&node_type,&real,nan2,NULL);assert(bh_value_same(BH_REFERENCE,bits(slots[0]),bits(slots[1])));map_equal(slots,1);
    slots[0]=make(&node_type,&boolean,1,NULL);slots[1]=make(&node_type,&boolean,UINT64_C(0x8000000000000003),NULL);
    assert(bh_value_same(BH_REFERENCE,bits(slots[0]),bits(slots[1])));map_equal(slots,0);
    memset(slots,0,sizeof slots);bh_collect();assert(bh_heap_objects()==0);
    slots[0]=make(&node_type,&node,0,NULL);uint64_t scalar=bits(make(&node_type,&node,1,NULL));memcpy((unsigned char *)slots[0]+8,&scalar,8);
    bh_collect();assert(bh_heap_objects()==1);slots[0]=NULL;bh_roots_pop(&frame);bh_shutdown();assert(bh_heap_objects()==0);
    puts("Native record checks passed.");return 0;
}
