/* SPDX-License-Identifier: Apache-2.0 */
#ifndef BHASH_EMBED_INTERNAL_H
#define BHASH_EMBED_INTERNAL_H
#include "bhash_embed.h"

/* Positive kinds are nominal Bhash class IDs. */
enum { BH_EMBED_VOID = 0, BH_EMBED_INT = -1, BH_EMBED_BOOL = -2, BH_EMBED_TEXT = -3 };
typedef union {
    int64_t integer;
    uint8_t boolean;
    bhash_utf8_view text;
    bhash_handle handle;
} bh_embed_arg;

typedef void (*bh_embed_thunk)(uint64_t *, void **);
bhash_status bh_embed_call(bh_embed_thunk, size_t, const int32_t *, const bh_embed_arg *,
                          int32_t, void *, bhash_error *);
#endif
