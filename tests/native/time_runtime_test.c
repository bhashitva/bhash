/* SPDX-License-Identifier: Apache-2.0 */
#include "bhash_runtime.h"
#include <assert.h>
#include <stdio.h>
#include <string.h>
#include <time.h>
#define TEXT(name,value) static const struct {uint64_t n;char data[sizeof(value)];} name={sizeof(value)-1,value}
TEXT(location,"time-test:1:1");TEXT(before_epoch,"1969-12-31T23:59:59.999Z");
TEXT(nul_text,"2000-01-01T00:00:00.000Z\0");
static const int64_t exception_ids[]={-5,-4};
static const size_t exception_refs[]={8,16};
static const bh_type exception_type={"exception",24,2,exception_refs,0,0,0};
static const bh_class_info exception_info={NULL,NULL,2,exception_ids};
static int64_t read_fail(void *text){return bh_time_read(text,&location);}
static int64_t invalid_value;
static unsigned invalid_operation;
static int64_t range_fail(void *unused){
    (void)unused;
    if(invalid_operation==0)bh_time_write(invalid_value,&location);
    else if(invalid_operation==1)bh_time_year(invalid_value,&location);
    else if(invalid_operation==2)bh_time_month(invalid_value,&location);
    else bh_time_day(invalid_value,&location);
    return 0;
}
static void failure(bh_protected_callback callback,void *input,const char *message){
    assert(bh_protect(callback,input)==-1);void *exception=bh_exception_take();bh_root_frame frame;bh_roots_push(&frame,&exception,1);bh_collect();
    void *value;memcpy(&value,(unsigned char *)exception+8,8);uint64_t n;memcpy(&n,value,8);
    assert(n==strlen(message)&&!memcmp((unsigned char *)value+8,message,(size_t)n));bh_roots_pop(&frame);bh_collect();assert(bh_heap_objects()==0);
}
int main(void){
    bh_runtime_init();bh_gc_stress(1);bh_exception_configure(&exception_type,&exception_info);
    void *slots[2]={NULL,NULL};bh_root_frame frame;bh_roots_push(&frame,slots,2);
    slots[0]=bh_time_write(-1,&location);assert(bh_text_equal(slots[0],&before_epoch));
    assert(bh_time_read(slots[0],&location)==-1);slots[0]=NULL;
    int64_t stamp=INT64_C(-62135596800000),days=0;
    /* Independent day-by-day calendar oracle covers the entire advertised range.
       First/last days of every month additionally exercise allocating text paths. */
    for(int year=1;year<=9999;++year){
        int leap=year%4==0&&(year%100!=0||year%400==0);
        const int lengths[]={31,28+leap,31,30,31,30,31,31,30,31,30,31};
        for(int month=1;month<=12;++month)for(int day=1;day<=lengths[month-1];++day){
            assert(bh_time_year(stamp,&location)==year&&bh_time_month(stamp,&location)==month&&bh_time_day(stamp,&location)==day);
            if(day==1||day==lengths[month-1]){
                int64_t value=stamp+INT64_C(45296789);
                slots[0]=bh_time_write(value,&location);assert(bh_time_read(slots[0],&location)==value);
                struct {uint64_t n;char data[25];} expected={24,{0}};
                assert(snprintf(expected.data,sizeof expected.data,"%04d-%02d-%02dT12:34:56.789Z",year,month,day)==24);
                assert(bh_text_equal(slots[0],&expected));
            }
            stamp+=INT64_C(86400000);++days;
        }
    }
    assert(days==3652059&&stamp==INT64_C(253402300800000));slots[0]=NULL;bh_collect();assert(bh_heap_objects()==0);
    const char *invalid[]={"","0000-01-01T00:00:00.000Z","1900-02-29T00:00:00.000Z","2000-13-01T00:00:00.000Z","2000-00-01T00:00:00.000Z",
        "2000-01-00T00:00:00.000Z","2001-02-29T00:00:00.000Z","2000-04-31T00:00:00.000Z","2000-01-01T24:00:00.000Z",
        "2000-01-01T00:60:00.000Z","2000-01-01T00:00:60.000Z","2000-01-01T00:00:00.0000Z","2000-01-01t00:00:00.000Z",
        "2000-01-01T00:00:00.000z","२०००-01-01T00:00:00.000Z","2000-01-01T00:00:00.000Z\n","2000-01-01T00:00:00.000+00:00"};
    for(size_t i=0;i<sizeof invalid/sizeof *invalid;++i){
        struct {uint64_t n;char data[64];} text={0,{0}};text.n=strlen(invalid[i]);memcpy(text.data,invalid[i],(size_t)text.n);
        for(int repeat=0;repeat<20;++repeat)failure(read_fail,&text,"अमान्य UTC समय पाठ।");
    }
    failure(read_fail,(void *)&nul_text,"अमान्य UTC समय पाठ।");
    failure(read_fail,NULL,"रिक्त वस्तु के सदस्य का उपयोग नहीं किया जा सकता।");
    const int64_t bounds[]={INT64_C(-62135596800001),INT64_C(253402300800000),INT64_MIN,INT64_MAX};
    for(invalid_operation=0;invalid_operation<4;++invalid_operation)for(unsigned i=0;i<4;++i){invalid_value=bounds[i];failure(range_fail,NULL,"समय सीमा से बाहर है।");}
    int64_t before=(int64_t)time(NULL),now=bh_time_now(&location),after=(int64_t)time(NULL);
    assert(now/1000>=before-1&&now/1000<=after+1);
    bh_roots_pop(&frame);bh_shutdown();assert(bh_heap_objects()==0);
    puts("Native UTC time checks passed.");return 0;
}
