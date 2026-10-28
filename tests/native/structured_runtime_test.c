/* SPDX-License-Identifier: Apache-2.0 */
#include "bhash_runtime.h"
#include <assert.h>
#include <stdio.h>
#include <string.h>
#define TEXT(name,value) static const struct {uint64_t n;char data[sizeof(value)];} name={sizeof(value)-1,value}
TEXT(location,"structured-test:1:1");TEXT(key_a,"a");TEXT(separator,",");TEXT(bar,"|");TEXT(empty,"");
TEXT(parts,"a,,😀,");TEXT(joined,"a||😀|");TEXT(nul_parts,"a\0b\0");TEXT(nul_separator,"\0");TEXT(nul_joined,"a|b|");
TEXT(document,"{\"a\":[1,true,null,{\"s\":\"😀\\u0000\"}],\"r\":1.25}");
TEXT(partial,"{\"a\":[1,{\"b\":[true,{\"s\":\"😀\\u0000\"}]}],");
static const int64_t text_ids[]={0,-4},value_ids[]={1,-4},object_ids[]={2,-4};
static const bh_collection_info texts={{NULL,NULL,2,text_ids},BH_LIST,BH_INT,BH_TEXT,NULL,NULL,NULL};
static const bh_collection_info values={{NULL,NULL,2,value_ids},BH_LIST,BH_INT,BH_ANY,NULL,NULL,NULL};
static const bh_collection_info object={{NULL,NULL,2,object_ids},BH_MAP,BH_TEXT,BH_ANY,NULL,&texts,&values};
static const int64_t integer_ids[]={-1,-4},bool_ids[]={-2,-4},string_ids[]={-3,-4},real_ids[]={-6,-4},exception_ids[]={-5,-4};
static const bh_class_info integer_info={NULL,NULL,2,integer_ids},bool_info={NULL,NULL,2,bool_ids},string_info={NULL,NULL,2,string_ids},real_info={NULL,NULL,2,real_ids};
static const size_t text_refs[]={8},exception_refs[]={8,16};
static const bh_type scalar_box={"scalar box",16,0,NULL,0,0,0},text_box={"text box",16,1,text_refs,0,0,0};
static const bh_type exception_type={"exception",24,2,exception_refs,0,0,0};
static const bh_class_info exception_info={NULL,NULL,2,exception_ids};
static const bh_data_info data={&texts,&values,&object,{&scalar_box,&scalar_box,&text_box,&scalar_box},{&integer_info,&bool_info,&string_info,&real_info}};
static void equal_text(const void *a,const void *b){assert(bh_text_equal(a,b));}
static int64_t parse_fail(void *input){bh_data_json_read(input,&location);return 0;}
static int64_t write_fail(void *input){bh_data_json_write(input,&location);return 0;}
static int64_t split_fail(void *input){bh_data_split(input,&empty,&location);return 0;}
static int64_t join_fail(void *unused){(void)unused;bh_data_join(NULL,&bar,&location);return 0;}
static void failure(bh_protected_callback callback,void *input,const char *message) {
    assert(bh_protect(callback,input)==-1);
    void *exception=bh_exception_take();bh_root_frame frame;bh_roots_push(&frame,&exception,1);bh_collect();
    void *text;memcpy(&text,(unsigned char *)exception+8,8);uint64_t n;memcpy(&n,text,8);
    if(message)assert(n==strlen(message)&&!memcmp((unsigned char *)text+8,message,(size_t)n));
    bh_roots_pop(&frame);
}
int main(void){
    bh_runtime_init();bh_gc_stress(1);bh_exception_configure(&exception_type,&exception_info);bh_data_configure(&data);
    void *slots[4]={NULL,NULL,NULL,NULL};bh_root_frame frame;bh_roots_push(&frame,slots,4);
    slots[0]=bh_data_split(&parts,&separator,&location);assert(bh_collection_call(slots[0],BH_SIZE,0,0,NULL,&location)==4);
    slots[1]=bh_data_join(slots[0],&bar,&location);equal_text(slots[1],&joined);
    slots[0]=bh_data_split(&nul_parts,&nul_separator,&location);slots[1]=bh_data_join(slots[0],&bar,&location);equal_text(slots[1],&nul_joined);
    slots[0]=bh_data_json_read(&document,&location);bh_collect();slots[1]=bh_data_json_write(slots[0],&location);equal_text(slots[1],&document);
    slots[2]=bh_data_json_read(slots[1],&location);slots[3]=bh_data_json_write(slots[2],&location);equal_text(slots[1],slots[3]);
    slots[2]=(void *)(uintptr_t)bh_collection_call(slots[0],BH_GET,(uint64_t)(uintptr_t)&key_a,0,NULL,&location);
    bh_collection_call(slots[2],BH_ADD,(uint64_t)(uintptr_t)slots[2],0,NULL,&location);
    failure(write_fail,slots[0],"चक्रीय संग्रह को जेसन में नहीं लिख सकते।");
    for(int i=0;i<4;++i)slots[i]=NULL;
    failure(split_fail,(void *)&parts,"विभाजक या बदलने वाला पाठ खाली नहीं हो सकता।");
    failure(join_fail,NULL,"रिक्त वस्तु के सदस्य का उपयोग नहीं किया जा सकता।");
    const char *invalid[]={"","01","[1,]","{\"a\":1,\"a\":2}","\"\\ud800\"","\"\\udc00\"","\"\\ud800x\"","\"\\ud800\\q\"","\"\\😀\"","9223372036854775808","1e9999"};
    for(size_t i=0;i<sizeof invalid/sizeof *invalid;++i){
        struct {uint64_t n;char data[128];} input={0,{0}};input.n=strlen(invalid[i]);memcpy(input.data,invalid[i],(size_t)input.n);
        failure(parse_fail,&input,NULL);
    }
    for(int i=0;i<1000;++i){
        slots[0]=bh_data_json_read(&document,&location);slots[1]=bh_data_json_write(slots[0],&location);equal_text(slots[1],&document);
        slots[0]=NULL;slots[1]=NULL;failure(parse_fail,(void *)&partial,NULL);bh_collect();assert(bh_heap_objects()==0);
    }
    /* The parser accepts 65 nested empty containers; a 66th value crosses depth 64. */
    struct {uint64_t n;char data[140];} depth={130,{0}};
    memset(depth.data,'[',65);memset(depth.data+65,']',65);slots[0]=bh_data_json_read(&depth,&location);
    slots[1]=bh_data_json_write(slots[0],&location);equal_text(slots[1],&depth);slots[0]=NULL;slots[1]=NULL;
    depth.n=132;memset(depth.data,'[',66);memset(depth.data+66,']',66);failure(parse_fail,&depth,"अमान्य जेसन पाठ, स्थान 65।");
    bh_roots_pop(&frame);bh_shutdown();assert(bh_heap_objects()==0);
    puts("Native structured text checks passed.");return 0;
}
