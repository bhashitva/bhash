/* SPDX-License-Identifier: Apache-2.0 */
#include "bhffi.h"
#include <string.h>
#include "zlib.h"
static voidpf zallocate(voidpf opaque, uInt count, uInt size) {
    bhffi_context *context = opaque;
    uint64_t bytes = (uint64_t)count * size;
    void *pointer = bhffi_v1_allocate(context, bytes);
    if (pointer) memset(pointer, 0, (size_t)bytes);
    return pointer;
}
static void zrelease(voidpf opaque, voidpf pointer) { bhffi_v1_free(opaque, pointer); }
void bhffi_compression_v1_bytes_release(bhffi_context *context, bhffi_owned *output) {
    bhffi_v1_bytes_release(context, output);
}
static int32_t begin(bhffi_context *context, bhffi_view input, int32_t format, bhffi_owned *output) {
    if (!output) return BHFFI_ARGUMENT;
    output->data = NULL;
    output->length = 0;
    if ((!input.data && input.length) || (format != 0 && format != 1)) return BHFFI_ARGUMENT;
    if (input.length > BHFFI_INPUT_LIMIT) return BHFFI_INPUT;
    return bhffi_v1_call_begin(context, input.length);
}
static int32_t map_error(int status) {
    if (status == Z_NEED_DICT) return BHFFI_DICTIONARY;
    if (status == Z_MEM_ERROR) return BHFFI_RESOURCE;
    if (status == Z_DATA_ERROR || status == Z_BUF_ERROR) return BHFFI_CORRUPT;
    return BHFFI_CONTRACT;
}
int32_t bhffi_compression_v1_compress(bhffi_context *context, bhffi_view input,
        int32_t format, int32_t level, bhffi_owned *output) {
    int32_t status = begin(context, input, format, output);
    if (status) return status;
    if (level < 0 || level > 9) { bhffi_v1_call_end(context); return BHFFI_ARGUMENT; }
    z_stream stream;
    memset(&stream, 0, sizeof(stream));
    stream.zalloc = zallocate; stream.zfree = zrelease; stream.opaque = context;
    int zstatus = deflateInit2(&stream, level, Z_DEFLATED, format ? 31 : 15, 8, Z_DEFAULT_STRATEGY);
    if (zstatus != Z_OK) { bhffi_v1_call_end(context); return map_error(zstatus); }
    gz_header header;
    memset(&header, 0, sizeof(header));
    header.os = 255;
    if (format && deflateSetHeader(&stream, &header) != Z_OK) {
        deflateEnd(&stream); bhffi_v1_call_end(context); return BHFFI_CONTRACT;
    }
    uint64_t capacity = deflateBound(&stream, (uLong)input.length);
    if (capacity > BHFFI_OUTPUT_LIMIT) status = BHFFI_OUTPUT;
    else {
        output->data = bhffi_v1_allocate(context, capacity);
        if (!output->data) status = BHFFI_RESOURCE;
        else {
            stream.next_in = (Bytef *)input.data; stream.avail_in = (uInt)input.length;
            stream.next_out = output->data; stream.avail_out = (uInt)capacity;
            zstatus = deflate(&stream, Z_FINISH);
            status = zstatus == Z_STREAM_END ? BHFFI_OK : map_error(zstatus);
            output->length = stream.total_out;
        }
    }
    deflateEnd(&stream);
    if (status) bhffi_compression_v1_bytes_release(context, output);
    bhffi_v1_call_end(context);
    return status;
}
int32_t bhffi_compression_v1_decompress(bhffi_context *context, bhffi_view input,
        int32_t format, uint64_t limit, bhffi_owned *output) {
    int32_t status = begin(context, input, format, output);
    if (status) return status;
    if (!limit || limit > BHFFI_OUTPUT_LIMIT) { bhffi_v1_call_end(context); return BHFFI_ARGUMENT; }
    z_stream stream;
    memset(&stream, 0, sizeof(stream));
    stream.zalloc = zallocate; stream.zfree = zrelease; stream.opaque = context;
    int zstatus = inflateInit2(&stream, format ? 31 : 15);
    if (zstatus != Z_OK) { bhffi_v1_call_end(context); return map_error(zstatus); }
    /* One extra byte distinguishes an exact-limit stream from an oversized one,
     * including streams whose checksum is consumed after the last output byte. */
    output->data = bhffi_v1_allocate(context, limit + 1);
    if (!output->data) status = BHFFI_RESOURCE;
    else {
        stream.next_in = (Bytef *)input.data; stream.avail_in = (uInt)input.length;
        stream.next_out = output->data; stream.avail_out = (uInt)(limit + 1);
        zstatus = inflate(&stream, Z_FINISH);
        if (stream.total_out > limit) status = BHFFI_OUTPUT;
        else if (zstatus == Z_STREAM_END) status = stream.avail_in ? BHFFI_CORRUPT : BHFFI_OK;
        else status = map_error(zstatus);
        output->length = stream.total_out;
    }
    inflateEnd(&stream);
    if (status) bhffi_compression_v1_bytes_release(context, output);
    bhffi_v1_call_end(context);
    return status;
}
