/* SPDX-License-Identifier: Apache-2.0 */
#include "bhffi.h"
#include <limits.h>
#include <string.h>
typedef struct { bhffi_context *context; int64_t value; } counter;
static int64_t live;
static bhffi_handle last_created;
int32_t fixture_add(bhffi_context *ctx,int64_t a,int64_t b,int64_t *output){
    (void)ctx;*output=0;
    if((b>0 && a>INT64_MAX-b)||(b<0 && a<INT64_MIN-b))return BHFFI_ARGUMENT;
    *output=a+b;return BHFFI_OK;
}
int32_t fixture_echo(bhffi_context *ctx,bhffi_view input,bhffi_owned *output){
    int32_t status=bhffi_v1_bytes_reserve(ctx,input.length,output);
    if(!status && input.length)memcpy(output->data,input.data,(size_t)input.length);
    return status;
}
int32_t fixture_level(bhffi_context *ctx,int32_t input,int32_t *output){(void)ctx;*output=input;return BHFFI_OK;}
int32_t fixture_real(bhffi_context *ctx,double input,double *output){(void)ctx;*output=input*2;return BHFFI_OK;}
int32_t fixture_byte(bhffi_context *ctx,uint8_t input,uint8_t *output){(void)ctx;*output=input;return BHFFI_OK;}
int32_t fixture_bool(bhffi_context *ctx,uint8_t input,uint8_t *output){(void)ctx;*output=input?0:1;return BHFFI_OK;}
static void dispose_counter(void *payload){counter *value=payload;--live;bhffi_v1_free(value->context,value);}
int32_t fixture_counter_create(bhffi_context *ctx,int64_t initial,bhffi_handle *output){
    int32_t status=bhffi_v1_resource_reserve(ctx,COUNTER_KIND,dispose_counter,output);
    if(status)return status;
    counter *value=bhffi_v1_allocate(ctx,sizeof(*value));
    if(!value){bhffi_v1_resource_queue(output->cleanup);bhffi_v1_drain(ctx);*output=(bhffi_handle){0};return BHFFI_RESOURCE;}
    value->context=ctx;value->value=initial;
    status=bhffi_v1_resource_commit(ctx,output->token,value);
    if(status){bhffi_v1_free(ctx,value);bhffi_v1_resource_queue(output->cleanup);bhffi_v1_drain(ctx);*output=(bhffi_handle){0};return status;}
    ++live;last_created=*output;return BHFFI_OK;
}
/* Deliberately violate owned-return semantics, on success AND error paths.
   The gateway must reject this without closing the existing Bhash wrapper. */
int32_t fixture_counter_duplicate(bhffi_context *ctx,int64_t mode,bhffi_handle *output){
    (void)ctx;*output=last_created;return mode?BHFFI_ARGUMENT:BHFFI_OK;
}
int32_t fixture_counter_get(bhffi_context *ctx,uint64_t token,int64_t *output){
    *output=0;void *payload=NULL;int32_t status=bhffi_v1_resource_enter(ctx,token,COUNTER_KIND,&payload);
    if(status)return status;
    *output=((counter *)payload)->value;return bhffi_v1_resource_leave(ctx,token);
}
void fixture_counter_release(bhffi_context *ctx,bhffi_handle *handle){
    if(!handle->token)return;
    if(bhffi_v1_resource_close(ctx,handle->token)!=BHFFI_OK)return;
    bhffi_v1_resource_queue(handle->cleanup);*handle=(bhffi_handle){0};bhffi_v1_drain(ctx);
}
int32_t fixture_counter_live(bhffi_context *ctx,int64_t *output){(void)ctx;*output=live;return BHFFI_OK;}
