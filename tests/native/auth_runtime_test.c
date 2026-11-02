/* SPDX-License-Identifier: Apache-2.0 */
#include "bhash_runtime.h"
#include <assert.h>
#include <stdio.h>
#include <string.h>
#define TEXT(name,value) const struct { uint64_t length; unsigned char bytes[sizeof(value)]; } name={sizeof(value)-1,value}
static uint64_t bits(const void *p){return (uint64_t)(uintptr_t)p;}
int main(int argc,char **argv){
    TEXT(location,"auth-runtime:1:1");TEXT(password,"भारत😀");TEXT(wrong,"wrong");TEXT(empty,"");
    TEXT(vector,"$bhash$pbkdf2-sha256$600000$000102030405060708090a0b0c0d0e0f$1830bda13082d32f435d15f0fc1e53767279f1296b041a59180ff6cb4f812cc1");
    TEXT(nul,"nul\0password");TEXT(nul_vector,"$bhash$pbkdf2-sha256$600000$000102030405060708090a0b0c0d0e0f$68d32e83bf14efc86e32fede808037bd2373abe1529e663f9d2dcfd64e7c39ce");
    bh_gc_stress(1);void *slots[4]={NULL,NULL,NULL,NULL};bh_root_frame roots;bh_roots_push(&roots,slots,4);
    if(argc>1){
        if(!strcmp(argv[1],"empty-password"))bh_foundation_call(14,bits(&empty),0,NULL,&location);
        if(!strcmp(argv[1],"oversize-password")){slots[0]=bh_text_repeat(&password,1024,&location);bh_foundation_call(14,bits(slots[0]),0,NULL,&location);}
        return 9;
    }
    assert(bh_foundation_call(15,bits(&password),bits(&vector),NULL,&location)==1);
    assert(bh_foundation_call(15,bits(&nul),bits(&nul_vector),NULL,&location)==1);
    assert(bh_foundation_call(15,bits(&password),bits(&empty),NULL,&location)==0);
    slots[0]=(void *)(uintptr_t)bh_foundation_call(14,bits(&password),0,NULL,&location);
    assert(bh_foundation_call(15,bits(&password),bits(slots[0]),NULL,&location)==1);
    assert(bh_foundation_call(15,bits(&wrong),bits(slots[0]),NULL,&location)==0);
    uint64_t before=bh_foundation_call(18,0,0,NULL,&location);
    for(int i=0;i<64;i++){
        slots[1]=(void *)(uintptr_t)bh_foundation_call(16,0,0,NULL,&location);
        slots[2]=(void *)(uintptr_t)bh_foundation_call(16,0,0,NULL,&location);
        assert(bh_text_length(slots[1],&location)==64);
        assert(bh_foundation_call(17,bits(slots[1]),bits(slots[2]),NULL,&location)==0);
        assert(bh_foundation_call(17,bits(slots[0]),bits(slots[0]),NULL,&location)==1);
        slots[3]=bh_text_concat(&password,&wrong,&location);assert(bh_text_length(slots[3],&location)>0);
    }
    assert(bh_foundation_call(18,0,0,NULL,&location)>=before);
    bh_roots_pop(&roots);bh_collect();assert(bh_heap_objects()==0);bh_shutdown();puts("Native authentication checks passed.");return 0;
}
