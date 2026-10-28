/* SPDX-License-Identifier: Apache-2.0 */
#ifdef _MSC_VER
#define _CRT_SECURE_NO_WARNINGS
#endif
#include "bhash_runtime.h"
#include <assert.h>
#include <stdio.h>
#include <string.h>
#ifndef _MSC_VER
#include <dirent.h>
#endif
#define TEXT(name,value) static const struct {uint64_t n;char data[sizeof(value)];} name={sizeof(value)-1,value}
TEXT(location,"files-test:1:1");TEXT(path,"target/native-runtime-tests/files.tmp");
TEXT(missing,"target/native-runtime-tests/no-parent/file");TEXT(directory,"target/native-runtime-tests");
TEXT(content,"\xef\xbb\xbfभारत😀\r\n\0अंत\n");TEXT(empty,"");TEXT(bad_path,"x\0y");
static const int64_t exception_ids[]={-5,-4};
static const size_t exception_refs[]={8,16};
static const bh_type exception_type={"exception",24,2,exception_refs,0,0,0};
static const bh_class_info exception_info={NULL,NULL,2,exception_ids};
static int64_t read_file(void *p){bh_file_read_text(p,&location);return 0;}
static int64_t exists_file(void *p){bh_file_exists(p,&location);return 0;}
static int64_t write_file(void *p){bh_file_write_text(p,&content,&location);return 0;}
static void failure(bh_protected_callback callback,void *input,const char *message) {
    assert(bh_protect(callback,input)==-1);
    void *exception=bh_exception_take();bh_root_frame frame;bh_roots_push(&frame,&exception,1);bh_collect();
    void *text;memcpy(&text,(unsigned char *)exception+8,8);uint64_t n;memcpy(&n,text,8);
    assert(n==strlen(message)&&!memcmp((unsigned char *)text+8,message,(size_t)n));
    bh_roots_pop(&frame);bh_collect();assert(bh_heap_objects()==0);
}
#ifndef _MSC_VER
static int descriptors(void) {
    DIR *dir=opendir("/proc/self/fd");assert(dir);int n=0;
    while(readdir(dir))++n;
    assert(!closedir(dir));return n;
}
#endif
int main(void){
    bh_runtime_init();bh_gc_stress(1);bh_exception_configure(&exception_type,&exception_info);
    assert(bh_file_supported(&location));assert(!bh_file_exists(&directory,&location));assert(!bh_file_exists(&missing,&location));
    bh_file_write_text(&path,&content,&location);assert(bh_file_exists(&path,&location));
    void *value=bh_file_read_text(&path,&location);assert(bh_text_equal(value,&content));
    bh_file_write_text(&path,&empty,&location);value=bh_file_read_text(&path,&location);assert(bh_text_equal(value,&empty));
    bh_collect();assert(bh_heap_objects()==0);
    failure(exists_file,(void *)&empty,"अमान्य फ़ाइल पथ।");failure(read_file,(void *)&bad_path,"अमान्य फ़ाइल पथ।");
    failure(read_file,(void *)&missing,"फ़ाइल पढ़ी नहीं जा सकी।");failure(write_file,(void *)&directory,"फ़ाइल लिखी नहीं जा सकी।");
    failure(read_file,(void *)&directory,"फ़ाइल पढ़ी नहीं जा सकी।");
    const unsigned char invalid[][4]={{0x80,0,0,0},{0xc0,0xaf,0,0},{0xe0,0x80,0xaf,0},{0xed,0xa0,0x80,0},{0xf4,0x90,0x80,0x80},{0xf5,0x80,0x80,0x80},{0xe2,0x82,0,0},{0xf0,0x80,0x80,0x80},{0xe2,0x28,0xa1,0}};
    const size_t lengths[]={1,2,3,3,4,4,2,4,3};
    for(size_t i=0;i<sizeof lengths/sizeof *lengths;++i){
        FILE *f=fopen(path.data,"wb");assert(f);assert(fwrite(invalid[i],1,lengths[i],f)==lengths[i]);assert(!fclose(f));
        failure(read_file,(void *)&path,"अमान्य UTF-8 बाइटबफ़र।");
    }
    FILE *f=fopen(path.data,"wb");assert(f);
    unsigned char chunk[8192]={0};for(int i=0;i<2048;++i)assert(fwrite(chunk,1,sizeof chunk,f)==sizeof chunk);assert(!fclose(f));
    value=bh_file_read_text(&path,&location);assert(bh_text_length(value,&location)==16777216);bh_collect();
    f=fopen(path.data,"ab");assert(f);assert(fputc('x',f)!=EOF);assert(!fclose(f));
#ifndef _MSC_VER
    int before=descriptors();
#endif
    for(int i=0;i<64;++i)failure(read_file,(void *)&path,"फ़ाइल १६ MiB सीमा से बड़ी है।");
#ifndef _MSC_VER
    assert(descriptors()==before);
    TEXT(full,"/dev/full");failure(write_file,(void *)&full,"फ़ाइल लिखी नहीं जा सकी।");
#endif
    assert(!remove(path.data));bh_shutdown();assert(bh_heap_objects()==0);
    puts("Native file checks passed.");return 0;
}
