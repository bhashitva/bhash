/* SPDX-License-Identifier: Apache-2.0 */
#include "bhash_runtime.h"
#include <math.h>
#include <stdio.h>
#include <string.h>
int main(void){
    bh_runtime_init();bh_gc_stress(1);
    unsigned long long bits;int places;
    while(scanf("%llx %d",&bits,&places)==2){
        uint64_t raw=(uint64_t)bits;double value;memcpy(&value,&raw,8);
        if(!isfinite(value)||places<0||places>15)return 2;
        void *text=bh_text_fixed(value,places,NULL);uint64_t n;memcpy(&n,text,8);
        fwrite((char *)text+8,1,(size_t)n,stdout);fputc('\n',stdout);bh_collect();
        if(bh_heap_objects()!=0)return 3;
    }
    bh_shutdown();return 0;
}
