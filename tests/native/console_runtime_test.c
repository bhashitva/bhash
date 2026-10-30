/* SPDX-License-Identifier: Apache-2.0 */
#include "bhash_runtime.h"
#include <assert.h>
#include <stdio.h>
#include <string.h>
#ifdef _WIN32
#include <io.h>
#else
#include <unistd.h>
#endif
#define TEXT(name,value) static const struct {uint64_t n;char data[sizeof(value)];} name={sizeof(value)-1,value}
TEXT(location,"console-test:1:1");TEXT(retained,"नमस्ते😀\0ब");TEXT(short_line,"क😀");TEXT(last,"last");
static const int64_t exception_ids[]={-5,-4};
static const size_t exception_refs[]={8,16};
static const bh_type exception_type={"exception",24,2,exception_refs,0,0,0};
static const bh_class_info exception_info={NULL,NULL,2,exception_ids};
static int64_t read_integer(void *unused){(void)unused;return bh_console_integer(&location);}
static int64_t read_text(void *unused){(void)unused;bh_console_text(&location);return 0;}
static void failure(bh_protected_callback callback,const char *message){
    bh_collect();uint64_t before=bh_heap_objects();assert(bh_protect(callback,NULL)==-1);
    void *exception=bh_exception_take();bh_root_frame frame;bh_roots_push(&frame,&exception,1);bh_collect();
    void *value;memcpy(&value,(unsigned char *)exception+8,8);uint64_t n;memcpy(&n,value,8);
    assert(n==strlen(message)&&!memcmp((unsigned char *)value+8,message,(size_t)n));
    bh_roots_pop(&frame);bh_collect();assert(bh_heap_objects()==before);
}
int main(int argc,char **argv){
    bh_runtime_init();bh_gc_stress(1);bh_exception_configure(&exception_type,&exception_info);
    if(argc>1){
#ifdef _WIN32
        assert(_close(0)==0);
#else
        assert(close(0)==0);
#endif
        int integer=!strcmp(argv[1],"integer");
        failure(integer?read_integer:read_text,integer?"कंसोल से UTF-8 इनपुट पढ़ा नहीं जा सका।":"UTF-8 पाठ पढ़ा नहीं जा सका।");
        bh_shutdown();puts("Native console I/O failure checks passed.");return 0;
    }
    void *slots[2]={NULL,NULL};bh_root_frame frame;bh_roots_push(&frame,slots,2);
    slots[0]=bh_console_text(&location);assert(bh_text_length(slots[0],&location)==100000);slots[0]=NULL;bh_collect();assert(bh_heap_objects()==0);
    slots[0]=bh_console_text(&location);assert(bh_text_equal(slots[0],&retained));
    assert(bh_console_integer(&location)==INT64_MIN);assert(bh_console_integer(&location)==INT64_MAX);
    const char *invalid="अमान्य इनपुट: एक पंक्ति में एक पूर्णांक लिखें (0-9 या ०-९)।";
    failure(read_integer,invalid);failure(read_integer,invalid);
    failure(read_integer,"पूर्णांक 64-बिट सीमा से बाहर है।");
    failure(read_text,"UTF-8 पाठ पढ़ा नहीं जा सका।");assert(bh_console_integer(&location)==7);
    for(int i=0;i<1000;++i){
        slots[1]=bh_console_text(&location);assert(bh_text_equal(slots[1],&short_line));slots[1]=NULL;
        failure(read_integer,invalid);assert(bh_text_equal(slots[0],&retained));
    }
    slots[1]=bh_console_text(&location);assert(bh_text_equal(slots[1],&last));slots[1]=NULL;
    for(int i=0;i<2;++i){failure(read_text,"इनपुट समाप्त: पाठ अपेक्षित था।");failure(read_integer,"इनपुट समाप्त: एक पूर्णांक अपेक्षित था।");}
    slots[0]=NULL;bh_roots_pop(&frame);bh_shutdown();assert(bh_heap_objects()==0);
    puts("Native console checks passed.");return 0;
}
