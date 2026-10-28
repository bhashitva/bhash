/* SPDX-License-Identifier: Apache-2.0 */
#include "bhash_runtime.h"
#include <assert.h>
#include <stdio.h>
#include <string.h>
#define TEXT(name,value) static const struct {uint64_t n;char data[sizeof(value)];} name={sizeof(value)-1,value}
TEXT(location,"bytes-test:1:1");TEXT(text,"\xef\xbb\xbfभारत😀\0\r\n");
TEXT(path,"target/native-runtime-tests/bytes.tmp");TEXT(empty,"");TEXT(invalid_path,"x\0y");
static const int64_t buffer_ids[]={0,-4},array_ids[]={1,-4},exception_ids[]={-5,-4};
static const bh_collection_info buffer_info={{NULL,NULL,2,buffer_ids},BH_BUFFER,BH_INT,BH_BYTE,NULL,NULL,NULL};
static const bh_collection_info array_info={{NULL,NULL,2,array_ids},BH_ARRAY,BH_INT,BH_BYTE,NULL,NULL,NULL};
static const size_t exception_refs[]={8,16};
static const bh_type exception_type={"exception",24,2,exception_refs,0,0,0},dummy_type={"dummy",8,0,NULL,0,0,0};
static const bh_class_info exception_info={NULL,NULL,2,exception_ids};
#define PTR(p) ((uint64_t)(uintptr_t)(p))
#define REF(v) ((void *)(uintptr_t)(v))
#define CALL(op,a,b,c,d,info) bh_buffer_call(op,a,b,c,d,info,&location)
static uint64_t get(void *buffer,uint64_t i){return bh_collection_call(buffer,BH_GET,i,0,NULL,&location);}
static void set(void *buffer,uint64_t i,uint64_t value){bh_collection_call(buffer,BH_SET,i,value,NULL,&location);}
static int64_t decode_fail(void *buffer){CALL(BH_DECODE,PTR(buffer),0,0,0,NULL);return 0;}
static int64_t span_fail(void *buffer){CALL(BH_READ_INT,PTR(buffer),UINT64_MAX,0,0,NULL);return 0;}
static int64_t byte_fail(void *unused){(void)unused;bh_to_byte(256,&location);return 0;}
static int64_t write_fail(void *buffer){CALL(BH_WRITE_FILE,PTR(&invalid_path),PTR(buffer),0,0,NULL);return 0;}
static void failure(bh_protected_callback callback,void *input,const char *message){
    assert(bh_protect(callback,input)==-1);void *exception=bh_exception_take();bh_root_frame frame;bh_roots_push(&frame,&exception,1);bh_collect();
    void *value;memcpy(&value,(unsigned char *)exception+8,8);uint64_t n;memcpy(&n,value,8);
    assert(n==strlen(message)&&!memcmp((unsigned char *)value+8,message,(size_t)n));bh_roots_pop(&frame);
}
int main(void){
    bh_runtime_init();bh_gc_stress(1);bh_exception_configure(&exception_type,&exception_info);
    void *slots[4]={NULL,NULL,NULL,NULL};bh_root_frame frame;bh_roots_push(&frame,slots,4);
    for(int i=0;i<256;++i)assert(bh_to_byte(i,&location)==i);
    slots[0]=bh_collection_new(&buffer_info,256,&location);
    for(uint64_t i=0;i<256;++i)set(slots[0],i,i);
    CALL(BH_WRITE_FILE,PTR(&path),PTR(slots[0]),0,0,NULL);slots[1]=REF(CALL(BH_READ_FILE,PTR(&path),0,0,0,&buffer_info));
    for(uint64_t i=0;i<256;++i)assert(get(slots[1],i)==i);
    slots[2]=REF(CALL(BH_SLICE,PTR(slots[1]),254,256,0,&buffer_info));set(slots[2],0,7);assert(get(slots[1],254)==254);
    slots[3]=REF(CALL(BH_TO_ARRAY,PTR(slots[2]),0,0,0,&array_info));set(slots[3],1,8);assert(get(slots[2],1)==255);
    CALL(BH_FILL,PTR(slots[2]),9,0,0,NULL);assert(get(slots[2],0)==9&&get(slots[2],1)==9);
    slots[0]=bh_collection_new(&buffer_info,17,&location);
    for(unsigned big=0;big<2;++big){
        const uint64_t values[]={0,UINT64_MAX,UINT64_C(0x8000000000000000),UINT64_C(0x7ff8000000000012),UINT64_C(0xfff0000000000000)};
        for(unsigned i=0;i<sizeof values/sizeof *values;++i){
            CALL(BH_WRITE_INT,PTR(slots[0]),1,values[i],big,NULL);assert(CALL(BH_READ_INT,PTR(slots[0]),1,big,0,NULL)==values[i]);
            CALL(BH_WRITE_REAL,PTR(slots[0]),9,values[i],big,NULL);assert(CALL(BH_READ_REAL,PTR(slots[0]),9,big,0,NULL)==values[i]);
        }
    }
    for(int i=1;i<4;++i)slots[i]=NULL;
    void *dummy=bh_alloc(&dummy_type,8);CALL(BH_WRITE_INT,PTR(slots[0]),0,PTR(dummy),0,NULL);dummy=NULL;
    bh_collect();assert(bh_heap_objects()==2); /* Pointer-looking raw bytes do not retain objects. */
    for(int i=0;i<4;++i)slots[i]=NULL;
    for(int i=0;i<500;++i){
        slots[0]=REF(CALL(BH_ENCODE,PTR(&text),0,0,0,&buffer_info));
        slots[1]=REF(CALL(BH_SLICE,PTR(slots[0]),0,text.n,0,&buffer_info));
        slots[2]=REF(CALL(BH_TO_ARRAY,PTR(slots[1]),0,0,0,&array_info));
        slots[3]=REF(CALL(BH_DECODE,PTR(slots[1]),0,0,0,NULL));assert(bh_text_equal(slots[3],&text));
        set(slots[1],0,255);failure(decode_fail,slots[1],"अमान्य UTF-8 बाइटबफ़र।");
        failure(span_fail,slots[1],"बाइटबफ़र सीमा से बाहर है।");
        for(int j=0;j<4;++j)slots[j]=NULL;bh_collect();assert(bh_heap_objects()==0);
    }
    failure(byte_fail,NULL,"बाइट परिवर्तन सीमा से बाहर है।");
    slots[0]=REF(CALL(BH_ENCODE,PTR(&empty),0,0,0,&buffer_info));
    failure(write_fail,slots[0],"अमान्य फ़ाइल पथ।");
    CALL(BH_WRITE_FILE,PTR(&path),PTR(slots[0]),0,0,NULL);slots[1]=REF(CALL(BH_READ_FILE,PTR(&path),0,0,0,&buffer_info));assert(bh_collection_call(slots[1],BH_SIZE,0,0,NULL,&location)==0);
    assert(!remove(path.data));bh_roots_pop(&frame);bh_shutdown();assert(bh_heap_objects()==0);
    puts("Native byte/buffer checks passed.");return 0;
}
