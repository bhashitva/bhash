/* SPDX-License-Identifier: Apache-2.0 */
/* Included after the runtime's allocation, text and collection helpers. */
typedef int32_t (*bh_ffi_invoke)(bhffi_context *,uint32_t,const bhffi_wire *,uint32_t,bhffi_wire *);
typedef void (*bh_ffi_release)(bhffi_context *,uint32_t,bhffi_wire *);
static bhffi_context *foreign_context;
static void bh_ffi_gc_drain(void) {
    if(foreign_context && bhffi_v1_drain(foreign_context)!=BHFFI_OK)fatal("बाहरी संसाधन की सफ़ाई विफल हुई।");
}
static void bh_ffi_shutdown(void) {
    if(foreign_context){
        bh_ffi_gc_drain();
        if(bhffi_v1_context_bytes(foreign_context)!=0)fatal("बाहरी native memory शेष है।");
        if(bhffi_v1_context_shutdown(foreign_context)!=BHFFI_OK)fatal("बाहरी संसाधन शेष है।");
        foreign_context=NULL;
    }
}
typedef struct { bh_ffi_release release; uint32_t function; bhffi_wire value; } bh_ffi_pending;
static void bh_ffi_dispose(void *memory) {
    bh_ffi_pending *pending=memory;
    pending->release(foreign_context,pending->function,&pending->value);
}
void bh_ffi_require_status(int32_t status,const void *location) {
    if(status)runtime_error(location,"[BH8105] बाहरी पुस्तकालय ने असफल या अमान्य परिणाम दिया।");
}
void bh_ffi_close(void *value,const void *location) {
    bh_instance(value,location);bh_object *object=find_object(value);
    if(!object)runtime_error(location,"[BH8105] बाहरी संसाधन अमान्य है।");
    if(!object->foreign_resource.token)return;
    bh_ffi_require_status(bhffi_v1_resource_close(foreign_context,object->foreign_resource.token),location);
    bhffi_resource *record=object->foreign_resource.cleanup;
    memset(&object->foreign_resource,0,sizeof(object->foreign_resource));
    bhffi_v1_resource_queue(record);bh_ffi_gc_drain();
}
int32_t bh_ffi_call(bh_ffi_invoke invoke,bh_ffi_release release,uint32_t function,uint32_t count,
        const uint8_t *kinds,const uint64_t *arguments,uint32_t result_kind,const void *descriptor,
        const void *type_info,const void *location,uint64_t *output) {
    *output=0;
    if(count>32 || !invoke || !release)return BHFFI_CONTRACT;
    if(!foreign_context){foreign_context=bhffi_v1_context_create();if(!foreign_context)return BHFFI_RESOURCE;}
    bhffi_wire inputs[32];memset(inputs,0,sizeof(inputs));
    for(uint32_t i=0;i<count;++i){
        if(kinds[i]==0)inputs[i].scalar=arguments[i];
        else {
            void *reference=bits_pointer(arguments[i]);
            if(!reference)return kinds[i]==3?BHFFI_CONTRACT:BHFFI_ARGUMENT;
            if(kinds[i]==1){inputs[i].data=text_data(reference);inputs[i].length=text_length(reference);}
            else if(kinds[i]==2){bh_collection *buffer=reference;inputs[i].data=(const uint8_t *)buffer->data;inputs[i].length=buffer->size;}
            else if(kinds[i]==3){
                bh_object *object=find_object(reference);
                if(!object || !object->foreign_resource.token)return BHFFI_CONTRACT;
                inputs[i].scalar=object->foreign_resource.token;
            }else return BHFFI_CONTRACT;
        }
    }
    bh_ffi_pending pending={release,function,{0}};
    bh_cleanup cleanup={cleanups,&pending,bh_ffi_dispose};cleanups=&cleanup;
    int32_t status=invoke(foreign_context,function,inputs,count,&pending.value);
    void *slots[1]={NULL};bh_root_frame frame;bh_roots_push(&frame,slots,1);
    if(!status){
        if(result_kind==0)*output=pending.value.scalar;
        else if(result_kind==1){
            slots[0]=new_text(pending.value.length,location);
            if(pending.value.length)memcpy(text_data(slots[0]),pending.value.data,(size_t)pending.value.length);
            *output=pointer_bits(slots[0]);
        }else if(result_kind==2){
            slots[0]=bh_collection_new(descriptor,(int64_t)pending.value.length,location);
            if(pending.value.length)memcpy(((bh_collection *)slots[0])->data,pending.value.data,(size_t)pending.value.length);
            *output=pointer_bits(slots[0]);
        }else if(result_kind==3){
            const bh_type *type=descriptor;
            slots[0]=bh_alloc(type,type->minimum_size);
            memcpy(slots[0],&type_info,sizeof(type_info));
            bh_object *object=find_object(slots[0]);
            object->foreign_resource=(bhffi_handle){pending.value.scalar,(bhffi_resource *)pending.value.data};
            memset(&pending.value,0,sizeof(pending.value));
            *output=pointer_bits(slots[0]);
        }else status=BHFFI_CONTRACT;
    }
    bh_ffi_dispose(&pending);cleanups=cleanup.previous;
    bh_roots_pop(&frame);
    return status;
}
