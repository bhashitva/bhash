/* SPDX-License-Identifier: Apache-2.0 */
#include "bhffi.h"
#include <limits.h>
#include <stdlib.h>
#include <string.h>
#include <stdatomic.h>
#if defined(_WIN32)
#include <windows.h>
typedef DWORD bhffi_thread;
static bhffi_thread current_thread(void) { return GetCurrentThreadId(); }
static int same_thread(bhffi_thread a, bhffi_thread b) { return a == b; }
#else
#include <pthread.h>
typedef pthread_t bhffi_thread;
static bhffi_thread current_thread(void) { return pthread_self(); }
static int same_thread(bhffi_thread a, bhffi_thread b) { return pthread_equal(a, b); }
#endif
_Static_assert(sizeof(uint64_t) == 8 && sizeof(int32_t) == 4, "wire integers");
_Static_assert(sizeof(void *) == 8, "FFI v1 requires a 64-bit host");
_Static_assert(sizeof(bhffi_view) == 16 && offsetof(bhffi_view, length) == 8, "view ABI");
_Static_assert(sizeof(bhffi_owned) == 16 && offsetof(bhffi_owned, length) == 8, "owned ABI");
_Static_assert(sizeof(bhffi_wire) == 24 && offsetof(bhffi_wire, data) == 8 && offsetof(bhffi_wire, length) == 16, "frame ABI");
struct bhffi_context {
    bhffi_thread owner;
    uint64_t bytes, call_bytes, leases, epoch;
    bhffi_resource *resources;
    uint32_t resource_count;
    uint64_t (*next_token)(void);
    void (*destroy)(void *);
    union allocation *allocations;
    int active, gateway, stopped;
    /* Keep the private context layout independent of producer test switches. */
    int64_t fail_after;
};
struct bhffi_resource {
    bhffi_resource *next;
    uint64_t token, kind;
    void *payload;
    bhffi_dispose dispose;
    int committed, busy, closed, handed;
    atomic_int queued;
};
static atomic_uint_fast64_t next_resource = 1;
_Static_assert(ATOMIC_INT_LOCK_FREE == 2, "GC cleanup enqueue must be lock-free");
static uint64_t resource_token(void) {
    uint_fast64_t token=atomic_load_explicit(&next_resource,memory_order_relaxed);
    do{if(token==UINT64_MAX)return 0;}
    while(!atomic_compare_exchange_weak_explicit(&next_resource,&token,token+1,memory_order_relaxed,memory_order_relaxed));
    return token;
}
typedef union allocation {
    max_align_t alignment;
    struct { bhffi_context *context; uint64_t size, epoch; union allocation *next,*previous; void (*destroy)(void *); int bytes; } value;
} allocation;
uint32_t bhffi_v1_abi(void) { return BHFFI_ABI; }
uint64_t bhffi_v1_layout(void) {
    /* Source identity alone cannot distinguish /Zp or -fpack-struct, or two
       producers whose max_align_t differs. Check before sharing opaque state. */
#define BH_LAYOUT(t,f) ((uint64_t)offsetof(t,f))
    const uint64_t parts[]={sizeof(bhffi_context),_Alignof(bhffi_context),
        BH_LAYOUT(bhffi_context,owner),BH_LAYOUT(bhffi_context,bytes),BH_LAYOUT(bhffi_context,call_bytes),BH_LAYOUT(bhffi_context,leases),BH_LAYOUT(bhffi_context,epoch),
        BH_LAYOUT(bhffi_context,resources),BH_LAYOUT(bhffi_context,resource_count),BH_LAYOUT(bhffi_context,next_token),BH_LAYOUT(bhffi_context,destroy),
        BH_LAYOUT(bhffi_context,allocations),BH_LAYOUT(bhffi_context,active),BH_LAYOUT(bhffi_context,gateway),BH_LAYOUT(bhffi_context,stopped),BH_LAYOUT(bhffi_context,fail_after),
        sizeof(allocation),_Alignof(allocation),BH_LAYOUT(allocation,value.context),BH_LAYOUT(allocation,value.size),BH_LAYOUT(allocation,value.epoch),
        BH_LAYOUT(allocation,value.next),BH_LAYOUT(allocation,value.previous),BH_LAYOUT(allocation,value.destroy),BH_LAYOUT(allocation,value.bytes),
        sizeof(bhffi_resource),_Alignof(bhffi_resource),sizeof(atomic_int),_Alignof(atomic_int),
        BH_LAYOUT(bhffi_resource,next),BH_LAYOUT(bhffi_resource,token),BH_LAYOUT(bhffi_resource,kind),BH_LAYOUT(bhffi_resource,payload),
        BH_LAYOUT(bhffi_resource,dispose),BH_LAYOUT(bhffi_resource,committed),BH_LAYOUT(bhffi_resource,busy),BH_LAYOUT(bhffi_resource,closed),BH_LAYOUT(bhffi_resource,handed),BH_LAYOUT(bhffi_resource,queued)};
#undef BH_LAYOUT
    uint64_t hash=UINT64_C(14695981039346656037);
    for(size_t i=0;i<sizeof(parts)/sizeof(parts[0]);++i)for(unsigned shift=0;shift<64;shift+=8){hash^=(parts[i]>>shift)&255;hash*=UINT64_C(1099511628211);}
    return hash;
}
bhffi_context *bhffi_v1_context_create(void) {
    bhffi_context *context = calloc(1, sizeof(*context));
    if (!context) return NULL;
    context->owner = current_thread();
    context->leases = 1;
    context->next_token = resource_token;
    context->destroy = free;
    context->fail_after = -1;
    return context;
}
static int owner(bhffi_context *context) {
    return context && same_thread(context->owner, current_thread());
}
static void unlease(bhffi_context *context) {
    if (--context->leases == 0) context->destroy(context);
}
int32_t bhffi_v1_context_shutdown(bhffi_context *context) {
    if (!owner(context) || context->active || context->gateway || context->stopped) return BHFFI_CONTRACT;
    for (bhffi_resource *resource = context->resources; resource; resource = resource->next)
        if (resource->busy) return BHFFI_CONTRACT;
    bhffi_v1_drain(context);
    for (bhffi_resource *resource = context->resources; resource; resource = resource->next)
        bhffi_v1_resource_close(context, resource->token);
    context->stopped = 1;
    unlease(context);
    return BHFFI_OK;
}
uint64_t bhffi_v1_context_bytes(bhffi_context *context) {
    return owner(context) ? context->bytes : UINT64_MAX;
}
int32_t bhffi_v1_check(bhffi_context *context) {
    if (!owner(context) || context->active || context->gateway || context->stopped) return BHFFI_CONTRACT;
    return bhffi_v1_drain(context);
}
int32_t bhffi_v1_utf8(bhffi_view input) {
    if ((!input.data && input.length) || input.length > BHFFI_OUTPUT_LIMIT) return BHFFI_ARGUMENT;
    for (uint64_t i = 0; i < input.length;) {
        uint32_t code = input.data[i++], minimum;
        unsigned following;
        if (code < 0x80) continue;
        if (code >= 0xc2 && code <= 0xdf) { following = 1; minimum = 0x80; code &= 0x1f; }
        else if (code >= 0xe0 && code <= 0xef) { following = 2; minimum = 0x800; code &= 0x0f; }
        else if (code >= 0xf0 && code <= 0xf4) { following = 3; minimum = 0x10000; code &= 7; }
        else return BHFFI_ARGUMENT;
        if (following > input.length - i) return BHFFI_ARGUMENT;
        while (following--) {
            uint8_t next = input.data[i++];
            if ((next & 0xc0) != 0x80) return BHFFI_ARGUMENT;
            code = (code << 6) | (next & 0x3f);
        }
        if (code < minimum || code > 0x10ffff || (code >= 0xd800 && code <= 0xdfff)) return BHFFI_ARGUMENT;
    }
    return BHFFI_OK;
}
#ifdef BHFFI_TESTING
void bhffi_test_fail_after(bhffi_context *context, int64_t allocations) {
    if (owner(context)) context->fail_after = allocations;
}
#endif
static void *allocate(bhffi_context *context, uint64_t size) {
    if (size > SIZE_MAX - sizeof(allocation)) return NULL;
    uint64_t charged = size + sizeof(allocation);
    if (((context->active || context->gateway) && charged > BHFFI_CALL_LIMIT - context->call_bytes)
            || charged > BHFFI_CONTEXT_LIMIT - context->bytes) return NULL;
#ifdef BHFFI_TESTING
    if (context->fail_after == 0) return NULL;
    if (context->fail_after > 0) --context->fail_after;
#endif
    allocation *block = malloc(sizeof(*block) + (size_t)size);
    if (!block) return NULL;
    block->value.context = context;
    block->value.size = charged;
    block->value.epoch = (context->active || context->gateway) ? context->epoch : 0;
    block->value.destroy = free; block->value.bytes = 0;
    block->value.previous = NULL; block->value.next = context->allocations;
    if(context->allocations)context->allocations->value.previous=block;
    context->allocations=block;
    context->bytes += charged;
    if (context->active || context->gateway) context->call_bytes += charged;
    ++context->leases;
    return block + 1;
}
static void release(void *pointer) {
    if (!pointer) return;
    allocation *block = (allocation *)pointer - 1;
    bhffi_context *context = block->value.context;
    context->bytes -= block->value.size;
    if ((context->active || context->gateway) && block->value.epoch == context->epoch) context->call_bytes -= block->value.size;
    if(block->value.previous)block->value.previous->value.next=block->value.next;
    else context->allocations=block->value.next;
    if(block->value.next)block->value.next->value.previous=block->value.previous;
    block->value.destroy(block);
    unlease(context);
}
int32_t bhffi_v1_call_begin(bhffi_context *context, uint64_t input_bytes) {
    if (!owner(context) || context->active || context->stopped) return BHFFI_CONTRACT;
    int32_t status = context->gateway ? BHFFI_OK : bhffi_v1_check(context);
    if (status) return status;
    if (input_bytes > BHFFI_INPUT_LIMIT) return BHFFI_INPUT;
    context->active = 1;
    if(!context->gateway){++context->epoch; context->call_bytes = input_bytes + sizeof(allocation);}
    return BHFFI_OK;
}
int32_t bhffi_v1_call_end(bhffi_context *context) {
    if (!owner(context) || !context->active) return BHFFI_CONTRACT;
    context->active = 0; return BHFFI_OK;
}
void *bhffi_v1_allocate(bhffi_context *context, uint64_t bytes) {
    return owner(context) && !context->stopped ? allocate(context, bytes) : NULL;
}
void bhffi_v1_free(bhffi_context *context, void *pointer) {
    if (!owner(context) || !pointer) return;
    allocation *block = (allocation *)pointer - 1;
    if (block->value.context == context) release(pointer);
}
int32_t bhffi_v1_bytes_reserve(bhffi_context *context, uint64_t length, bhffi_owned *output) {
    if (!output) return BHFFI_ARGUMENT;
    output->data = NULL; output->length = 0;
    if (!owner(context) || context->active || context->stopped) return BHFFI_CONTRACT;
    if (length > BHFFI_INPUT_LIMIT) return BHFFI_INPUT;
    output->data = allocate(context, length);
    if (!output->data) return BHFFI_RESOURCE;
    ((allocation *)output->data-1)->value.bytes=1;
    output->length = length;
    return BHFFI_OK;
}
int32_t bhffi_v1_bytes_owned(bhffi_context *context,bhffi_view input) {
    if(!owner(context) || context->active || context->stopped)return 0;
    for(allocation *block=context->allocations;block;block=block->value.next)
        if((const void *)(block+1)==input.data)return block->value.bytes && input.length<=block->value.size-sizeof(allocation);
    return 0;
}
int32_t bhffi_v1_gateway_begin(bhffi_context *context) {
    int32_t status=bhffi_v1_check(context);if(status)return status;
    context->gateway=1;++context->epoch;context->call_bytes=0;return BHFFI_OK;
}
int32_t bhffi_v1_gateway_borrow(bhffi_context *context,bhffi_view input) {
    if(!owner(context) || !context->gateway || context->active || context->stopped)return BHFFI_CONTRACT;
    for(allocation *block=context->allocations;block;block=block->value.next)
        if((const void *)(block+1)==input.data && block->value.bytes && input.length<=block->value.size-sizeof(allocation)){
            if(block->value.epoch!=context->epoch){
                if(block->value.size>BHFFI_CALL_LIMIT-context->call_bytes)return BHFFI_RESOURCE;
                context->call_bytes+=block->value.size;block->value.epoch=context->epoch;
            }
            return BHFFI_OK;
        }
    return BHFFI_CONTRACT;
}
int32_t bhffi_v1_gateway_end(bhffi_context *context) {
    if(!owner(context) || !context->gateway)return BHFFI_CONTRACT;
    int32_t status=context->active?BHFFI_CONTRACT:BHFFI_OK;
    context->active=0;context->gateway=0;return status;
}
static bhffi_resource *find_resource(bhffi_context *context, uint64_t token) {
    for (bhffi_resource *resource = context->resources; resource; resource = resource->next)
        if (resource->token == token) return resource;
    return NULL;
}
int32_t bhffi_v1_resource_reserve(bhffi_context *context, uint64_t kind, bhffi_dispose dispose, bhffi_handle *output) {
    if (!output) return BHFFI_ARGUMENT;
    output->token = 0; output->cleanup = NULL;
    if (!owner(context) || context->active || context->stopped) return BHFFI_CONTRACT;
    if (!kind || !dispose) return BHFFI_ARGUMENT;
    if (context->resource_count >= 256) return BHFFI_RESOURCE;
    uint64_t token=context->next_token();
    if(!token)return BHFFI_RESOURCE;
    bhffi_resource *resource = allocate(context, sizeof(*resource));
    if (!resource) return BHFFI_RESOURCE;
    memset(resource, 0, sizeof(*resource));
    atomic_init(&resource->queued, 0);
    resource->token = token; resource->kind = kind; resource->dispose = dispose;
    resource->next = context->resources; context->resources = resource; ++context->resource_count;
    output->token = token; output->cleanup = resource;
    return BHFFI_OK;
}
int32_t bhffi_v1_resource_commit(bhffi_context *context, uint64_t token, void *payload) {
    if (!owner(context) || context->active || context->stopped) return BHFFI_CONTRACT;
    bhffi_resource *resource = find_resource(context, token);
    if (!resource || resource->closed || resource->committed || atomic_load_explicit(&resource->queued, memory_order_acquire)) return BHFFI_CONTRACT;
    resource->payload = payload; resource->committed = 1;
    return BHFFI_OK;
}
int32_t bhffi_v1_resource_enter(bhffi_context *context, uint64_t token, uint64_t kind, void **payload) {
    if (payload) *payload = NULL;
    if (!owner(context) || context->active || context->stopped || !payload) return BHFFI_CONTRACT;
    bhffi_resource *resource = find_resource(context, token);
    if (!resource || resource->kind != kind || resource->closed || resource->busy || !resource->committed
            || atomic_load_explicit(&resource->queued, memory_order_acquire)) return BHFFI_CONTRACT;
    resource->busy = 1; *payload = resource->payload;
    return BHFFI_OK;
}
int32_t bhffi_v1_resource_validate(bhffi_context *context, uint64_t token, uint64_t kind) {
    void *payload = NULL;
    int32_t status = bhffi_v1_resource_enter(context, token, kind, &payload);
    if (status != BHFFI_OK) return status;
    return bhffi_v1_resource_leave(context, token);
}
int32_t bhffi_v1_resource_transfer(bhffi_context *context,bhffi_handle *handle,uint64_t kind) {
    if(!handle)return BHFFI_CONTRACT;
    if(!owner(context) || context->active || context->stopped){*handle=(bhffi_handle){0};return BHFFI_CONTRACT;}
    bhffi_resource *resource=find_resource(context,handle->token);
    if(!resource || resource->handed){*handle=(bhffi_handle){0};return BHFFI_CONTRACT;}
    if(handle->cleanup!=resource || resource->kind!=kind || !resource->committed || resource->closed || resource->busy
            || atomic_load_explicit(&resource->queued,memory_order_acquire)){
        /* Only a newly reserved record in this context can be failure-cleaned. */
        handle->cleanup=resource;return BHFFI_CONTRACT;
    }
    resource->handed=1;return BHFFI_OK;
}
int32_t bhffi_v1_resource_leave(bhffi_context *context, uint64_t token) {
    if (!owner(context) || context->active) return BHFFI_CONTRACT;
    bhffi_resource *resource = find_resource(context, token);
    if (!resource || !resource->busy) return BHFFI_CONTRACT;
    resource->busy = 0; return BHFFI_OK;
}
int32_t bhffi_v1_resource_close(bhffi_context *context, uint64_t token) {
    if (!owner(context) || context->active) return BHFFI_CONTRACT;
    bhffi_resource *resource = find_resource(context, token);
    if (!resource || resource->busy) return BHFFI_CONTRACT;
    if (resource->closed) return BHFFI_OK;
    resource->closed = 1;
    if (resource->committed) {
        context->active = 1; /* Vendor cleanup cannot reenter a Bhash foreign call. */
        if(!context->gateway){context->call_bytes = 0; ++context->epoch;}
        resource->dispose(resource->payload);
        context->active = 0; resource->payload = NULL;
    }
    return BHFFI_OK;
}
void bhffi_v1_resource_queue(bhffi_resource *resource) {
    if (resource) atomic_store_explicit(&resource->queued, 1, memory_order_release);
}
int32_t bhffi_v1_drain(bhffi_context *context) {
    if (!owner(context) || context->active) return BHFFI_CONTRACT;
    ++context->leases; /* Keep context alive while releasing its last queued record. */
    bhffi_resource **link = &context->resources;
    int32_t status = BHFFI_OK;
    while (*link) {
        bhffi_resource *resource = *link;
        if (atomic_load_explicit(&resource->queued, memory_order_acquire) && !resource->busy) {
            status = bhffi_v1_resource_close(context, resource->token);
            if (status != BHFFI_OK) break;
            *link = resource->next; --context->resource_count; release(resource);
        } else link = &resource->next;
    }
    unlease(context);
    return status;
}
void bhffi_v1_bytes_release(bhffi_context *context, bhffi_owned *output) {
    if (!owner(context) || !output || !output->data) return;
    allocation *block = (allocation *)output->data - 1;
    if (block->value.context != context) return;
    uint8_t *pointer = output->data;
    output->data = NULL;
    output->length = 0;
    release(pointer);
}
