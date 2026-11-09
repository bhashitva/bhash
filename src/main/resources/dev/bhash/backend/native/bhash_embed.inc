/* SPDX-License-Identifier: Apache-2.0 */
#include "bhash_embed_internal.h"
#include <stdatomic.h>

typedef struct bh_embed_handle {
    struct bh_embed_handle *next;
    bhash_handle id;
    void *object;
} bh_embed_handle;

/* Only the owning thread reads or writes the handle list and busy flag. */
enum { BH_EMBED_NEW, BH_EMBED_STARTING, BH_EMBED_ACTIVE, BH_EMBED_STOPPED };
static atomic_int embed_state;
static _Thread_local int embed_owner;
static int embed_busy;
static bh_embed_handle *embed_handles;
static bhash_handle embed_next_handle = 1;

extern void bhash_internal_configure(void);

static void bh_embed_mark_handles(void) {
    for (bh_embed_handle *entry = embed_handles; entry; entry = entry->next)
        mark(entry->object);
}

static void embed_clear_error(bhash_error *error) {
    if (error) *error = (bhash_error){0};
}

static bhash_status embed_copy_text(const void *text, bhash_bytes *out) {
    if (!text) return BHASH_INVALID_RESULT;
    size_t length = (size_t)text_length(text);
    if (length > BHASH_EMBED_MAX_UTF8) return BHASH_INVALID_RESULT;
    if (!length) return BHASH_OK;
    uint8_t *bytes = malloc(length);
    if (!bytes) return BHASH_NO_MEMORY;
    memcpy(bytes, text_data(text), length);
    *out = (bhash_bytes){bytes, length};
    return BHASH_OK;
}

uint32_t bhash_embed_abi_version(void) { return BHASH_EMBED_ABI_VERSION; }

void bhash_bytes_free(bhash_bytes *value) {
    if (!value) return;
    free(value->data);
    *value = (bhash_bytes){0};
}

bhash_status bhash_runtime_start(bhash_error *error) {
    embed_clear_error(error);
    int expected = BH_EMBED_NEW;
    if (!atomic_compare_exchange_strong(&embed_state, &expected, BH_EMBED_STARTING))
        return BHASH_RUNTIME_STATE;
    embed_owner = 1;
    bhash_internal_configure();
    atomic_store(&embed_state, BH_EMBED_ACTIVE);
    return BHASH_OK;
}

static bhash_status embed_enter(void) {
    if (atomic_load(&embed_state) != BH_EMBED_ACTIVE) return BHASH_RUNTIME_STATE;
    if (!embed_owner) return BHASH_WRONG_THREAD;
    if (embed_busy) return BHASH_RUNTIME_STATE;
    return BHASH_OK;
}

bhash_status bhash_runtime_stop(bhash_error *error) {
    embed_clear_error(error);
    bhash_status status = embed_enter();
    if (status) return status;
    /* Close application resources before releasing the corresponding handles. */
    if (embed_handles) return BHASH_RUNTIME_STATE;
    bh_shutdown();
    atomic_store(&embed_state, BH_EMBED_STOPPED);
    embed_owner = 0;
    return BHASH_OK;
}

bhash_status bhash_handle_release(bhash_handle handle, bhash_error *error) {
    embed_clear_error(error);
    bhash_status status = embed_enter();
    if (status) return status;
    bh_embed_handle **link = &embed_handles;
    while (*link && (*link)->id != handle) link = &(*link)->next;
    if (!*link) return BHASH_INVALID_HANDLE;
    bh_embed_handle *entry = *link;
    *link = entry->next;
    free(entry);
    return BHASH_OK;
}

static int embed_is_instance(const void *object, int32_t kind) {
    if (!object) return 0;
    const bh_class_info *info;
    memcpy(&info, object, sizeof info);
    if (!info) return 0;
    for (uint64_t i = 0; i < info->type_count; i++)
        if (info->type_ids[i] == kind - 16) return 1;
    return 0;
}

static void *embed_resolve(bhash_handle handle, int32_t kind) {
    for (bh_embed_handle *entry = embed_handles; entry; entry = entry->next)
        if (entry->id == handle) return embed_is_instance(entry->object, kind) ? entry->object : NULL;
    return NULL;
}

typedef struct {
    bh_embed_thunk thunk;
    size_t count;
    const int32_t *kinds;
    const bh_embed_arg *arguments;
    uint64_t scalars[17];
    void *references[17];
} bh_embed_invocation;

static int64_t embed_invoke(void *environment) {
    bh_embed_invocation *call = environment;
    for (size_t i = 0; i < call->count; i++) {
        const bh_embed_arg *arg = &call->arguments[i];
        switch (call->kinds[i]) {
            case BH_EMBED_INT:
                memcpy(&call->scalars[i], &arg->integer, sizeof arg->integer);
                break;
            case BH_EMBED_BOOL:
                if (arg->boolean > 1) return BHASH_INVALID_INPUT;
                call->scalars[i] = arg->boolean;
                break;
            case BH_EMBED_TEXT:
                if ((!arg->text.data && arg->text.len) || arg->text.len > BHASH_EMBED_MAX_UTF8 ||
                    !valid_utf8(arg->text.data, arg->text.len)) return BHASH_INVALID_INPUT;
                call->references[i] = new_text(arg->text.len, &empty_collection_text);
                if (arg->text.len) memcpy(text_data(call->references[i]), arg->text.data, arg->text.len);
                break;
            default:
                call->references[i] = embed_resolve(arg->handle, call->kinds[i]);
                if (!call->references[i]) return BHASH_INVALID_HANDLE;
                break;
        }
    }
    call->thunk(call->scalars, call->references);
    return BHASH_OK;
}

bhash_status bh_embed_call(bh_embed_thunk thunk, size_t count, const int32_t *kinds,
                           const bh_embed_arg *arguments, int32_t result, void *out, bhash_error *error) {
    embed_clear_error(error);
    if (result != BH_EMBED_VOID && !out) return BHASH_INVALID_INPUT;
    if (result == BH_EMBED_INT) *(int64_t *)out = 0;
    else if (result == BH_EMBED_BOOL) *(uint8_t *)out = 0;
    else if (result == BH_EMBED_TEXT) *(bhash_bytes *)out = (bhash_bytes){0};
    else if (result > 0) *(bhash_handle *)out = 0;
    bhash_status status = embed_enter();
    if (status) return status;

    /* Reserve handle bookkeeping before running a factory with side effects. */
    bh_embed_handle *reserved = result > 0 && embed_next_handle ? malloc(sizeof *reserved) : NULL;
    if (result > 0 && !reserved) return BHASH_NO_MEMORY;
    embed_busy = 1;
    bh_embed_invocation call = {.thunk = thunk, .count = count, .kinds = kinds, .arguments = arguments};
    bh_root_frame roots_frame;
    bh_roots_push(&roots_frame, call.references, count + 1);
    int64_t completion = bh_protect(embed_invoke, &call);
    if (completion == -1) {
        status = BHASH_EXCEPTION;
        call.references[count] = bh_exception_take();
        if (error && call.references[count]) {
            void *message;
            memcpy(&message, (unsigned char *)call.references[count] + 8, sizeof message);
            (void)embed_copy_text(message, &error->message);
        }
    } else if (completion != BHASH_OK) status = (bhash_status)completion;
    else {
        /* Exercise the interval between returning from LLVM and copying/retaining. */
        if (stress) bh_collect();
        if (result == BH_EMBED_INT) memcpy(out, &call.scalars[count], sizeof(int64_t));
        else if (result == BH_EMBED_BOOL) *(uint8_t *)out = (uint8_t)call.scalars[count];
        else if (result == BH_EMBED_TEXT) status = embed_copy_text(call.references[count], out);
        else if (result > 0) {
            if (!embed_is_instance(call.references[count], result)) status = BHASH_INVALID_RESULT;
            else {
                *reserved = (bh_embed_handle){embed_handles, embed_next_handle++, call.references[count]};
                embed_handles = reserved;
                *(bhash_handle *)out = reserved->id;
                reserved = NULL;
            }
        }
    }
    bh_roots_pop(&roots_frame);
    free(reserved);
    embed_busy = 0;
    return status;
}
