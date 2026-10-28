/* SPDX-License-Identifier: Apache-2.0 */
#include "bhash_runtime.h"
#include <assert.h>
#include <float.h>
#include <math.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#define TEXT(name, value) static const struct { uint64_t n; char data[sizeof(value)]; } name = {sizeof(value)-1, value}
TEXT(location,"decimal-test:1:1"); TEXT(dev,"-१२३.१२५"); TEXT(zero,"-0.000");
static void check_text(void *text, const char *expected) {
    uint64_t n; memcpy(&n,text,8); assert(n==strlen(expected));
    assert(memcmp((char *)text+8,expected,(size_t)n)==0);
}
static uint64_t real_bits(double value) { uint64_t bits; memcpy(&bits,&value,8); return bits; }
static const size_t refs[] = {8,16};
static const bh_type exception_type = {"exception",24,2,refs,0,0,0};
static const int64_t exception_ids[] = {-5,-4}, list_ids[] = {0,-4}, map_ids[] = {1,-4};
static const bh_class_info exception_info = {NULL,NULL,2,exception_ids};
static const bh_collection_info list_info = {{NULL,NULL,2,list_ids},BH_LIST,BH_INT,BH_REAL,NULL,NULL,NULL};
static const bh_collection_info map_info = {{NULL,NULL,2,map_ids},BH_MAP,BH_REAL,BH_REAL,NULL,NULL,NULL};
static const char *invalid[] = {"", "+", " 1", "1 ", ".1", "1.", "1e3", "0x1", "NaN", "Infinity", "1.२", "१.2", "1.2.3", "1\0002"};
static int64_t bad_parse(void *environment) {
    size_t index=*(size_t *)environment;
    struct { uint64_t n; char data[32]; } input = {0,{0}};
    input.n=index==13?3:strlen(invalid[index]); memcpy(input.data,invalid[index],(size_t)input.n);
    (void)bh_text_parse_real(&input,&location); return 0;
}
static int64_t bad_int(void *environment) { (void)bh_real_to_int(*(double *)environment,&location); return 0; }
static int64_t bad_fixed(void *unused) { (void)unused; bh_text_fixed(INFINITY,2,&location); return 0; }
int main(void) {
    bh_runtime_init(); bh_gc_stress(1); bh_exception_configure(&exception_type,&exception_info);
    assert(bh_text_parse_real(&dev,&location)==-123.125);
    assert(real_bits(bh_text_parse_real(&zero,&location))==UINT64_C(0x8000000000000000));
    check_text(bh_text_real(-0.0,&location),"-0.0"); check_text(bh_text_real(INFINITY,&location),"Infinity");
    check_text(bh_text_real(-INFINITY,&location),"-Infinity"); check_text(bh_text_real(NAN,&location),"NaN");
    check_text(bh_text_real(0.001,&location),"0.001"); check_text(bh_text_real(0.0001,&location),"1.0E-4");
    check_text(bh_text_real(10000000.0,&location),"1.0E7");
    check_text(bh_text_fixed(2.5,0,&location),"2"); check_text(bh_text_fixed(3.5,0,&location),"4");
    check_text(bh_text_fixed(2.675,2,&location),"2.67"); check_text(bh_text_fixed(-0.0,3,&location),"-0.000");
    void *largest=bh_text_fixed(DBL_MAX,15,&location); uint64_t length; memcpy(&length,largest,8); assert(length==325);
    assert(bh_real_to_int(-0x1p63,&location)==INT64_MIN); assert(bh_real_to_int(-1.99,&location)==-1);
    assert(bh_real_to_int(0x1.fffffffffffffp62,&location)==INT64_C(9223372036854774784));
    const double boundaries[] = {DBL_MAX,DBL_MIN,0x1p-1074,0x1.fffffffffffffp-1023,0.1,1.0,-0x1p-1074};
    for(size_t i=0;i<sizeof boundaries/sizeof *boundaries;++i) {
        void *text=bh_text_real(boundaries[i],&location); uint64_t n; memcpy(&n,text,8);
        char buffer[512]; memcpy(buffer,(char *)text+8,(size_t)n); buffer[n]=0;
        assert(real_bits(strtod(buffer,NULL))==real_bits(boundaries[i]));
    }
    uint64_t state=UINT64_C(0xfeed123456789abc);
    for(int i=0;i<20000;++i) {
        state^=state<<13;state^=state>>7;state^=state<<17; double value;memcpy(&value,&state,8);
        if(!isfinite(value))continue;
        void *text=bh_text_real(value,&location); uint64_t n;memcpy(&n,text,8);
        char buffer[512]; assert(n<sizeof buffer);memcpy(buffer,(char *)text+8,(size_t)n);buffer[n]=0;
        assert(real_bits(strtod(buffer,NULL))==real_bits(value));
    }
    for(size_t i=0;i<sizeof invalid/sizeof *invalid;++i) {
        assert(bh_protect(bad_parse,&i)==-1); assert(bh_exception_take()!=NULL);
        bh_collect();assert(bh_heap_objects()==0);
    }
    double bad_values[]={INFINITY,-INFINITY,NAN,0x1p63,-0x1.0000000000001p63};
    for(size_t i=0;i<sizeof bad_values/sizeof *bad_values;++i) {
        assert(bh_protect(bad_int,&bad_values[i])==-1);assert(bh_exception_take()!=NULL);
    }
    assert(bh_protect(bad_fixed,NULL)==-1);assert(bh_exception_take()!=NULL);
    bh_collect();assert(bh_heap_objects()==0);
    void *slots[3]={NULL,NULL,NULL};bh_root_frame frame;bh_roots_push(&frame,slots,3);
    slots[0]=bh_collection_new(&list_info,0,&location); slots[1]=bh_text_real(1.25,&location);
    uint64_t pointer=(uint64_t)(uintptr_t)slots[1];
    bh_collection_call(slots[0],BH_ADD,pointer,0,NULL,&location);
    slots[1]=NULL;bh_collect();assert(bh_heap_objects()==2); /* scalar bits never become GC roots */
    slots[2]=bh_collection_new(&map_info,0,&location);
    bh_collection_call(slots[2],BH_SET,real_bits(-0.0),real_bits(1.25),NULL,&location);
    bh_collection_call(slots[2],BH_SET,real_bits(0.0),real_bits(2.5),NULL,&location);
    bh_collection_call(slots[2],BH_SET,UINT64_C(0x7ff8000000000001),real_bits(3.75),NULL,&location);
    assert(bh_collection_call(slots[2],BH_SIZE,0,0,NULL,&location)==2);
    assert(bh_collection_call(slots[2],BH_GET,real_bits(-0.0),0,NULL,&location)==real_bits(2.5));
    assert(bh_collection_call(slots[2],BH_GET,UINT64_C(0xfff8000000000002),0,NULL,&location)==real_bits(3.75));
    bh_roots_pop(&frame);bh_shutdown();assert(bh_heap_objects()==0);
    puts("Native decimal checks passed.");return 0;
}
