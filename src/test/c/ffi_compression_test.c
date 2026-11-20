/* SPDX-License-Identifier: Apache-2.0 */
#include "bhffi.h"
#include <assert.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include "zlib.h"
static void roundtrip(int format, int level, const uint8_t *data, size_t length) {
    bhffi_context *context = bhffi_v1_context_create();
    assert(context && bhffi_v1_abi() == 1);
    bhffi_owned packed = {0}, unpacked = {0};
    bhffi_view source = {data, length};
    assert(bhffi_compression_v1_compress(context, source, format, level, &packed) == BHFFI_OK);
    assert(packed.data && packed.length);
    if (format) {
        assert(packed.length >= 18 && packed.data[0] == 31 && packed.data[1] == 139);
        assert(packed.data[3] == 0 && packed.data[4] == 0 && packed.data[5] == 0
            && packed.data[6] == 0 && packed.data[7] == 0 && packed.data[9] == 255);
    }
    bhffi_view compressed = {packed.data, packed.length};
    assert(bhffi_compression_v1_decompress(context, compressed, format, length ? length : 1, &unpacked) == BHFFI_OK);
    assert(unpacked.length == length && (!length || memcmp(data, unpacked.data, length) == 0));
    bhffi_compression_v1_bytes_release(context, &unpacked);
    bhffi_compression_v1_bytes_release(context, &unpacked); /* empty release is idempotent */
    if (length > 1) {
        assert(bhffi_compression_v1_decompress(context, compressed, format, length - 1, &unpacked) == BHFFI_OUTPUT);
        assert(!unpacked.data && !unpacked.length);
    }
    compressed.length--;
    assert(bhffi_compression_v1_decompress(context, compressed, format, BHFFI_OUTPUT_LIMIT, &unpacked) == BHFFI_CORRUPT);
    assert(!unpacked.data && !unpacked.length);
    compressed.length++;
    uint8_t *trailing = malloc((size_t)packed.length * 2);
    assert(trailing);
    memcpy(trailing, packed.data, (size_t)packed.length);
    memcpy(trailing + packed.length, packed.data, (size_t)packed.length);
    bhffi_view twice = {trailing, packed.length * 2};
    assert(bhffi_compression_v1_decompress(context, twice, format, BHFFI_OUTPUT_LIMIT, &unpacked) == BHFFI_CORRUPT);
    free(trailing);
    packed.data[packed.length - 1] ^= 1;
    assert(bhffi_compression_v1_decompress(context, compressed, format, BHFFI_OUTPUT_LIMIT, &unpacked) == BHFFI_CORRUPT);
    bhffi_compression_v1_bytes_release(context, &packed);
    assert(bhffi_v1_context_bytes(context) == 0);
    assert(bhffi_v1_context_shutdown(context) == BHFFI_OK);
}
static void failures(void) {
    bhffi_context *context = bhffi_v1_context_create();
    bhffi_view empty = {NULL, 0};
    bhffi_owned output = {0};
    assert(bhffi_compression_v1_compress(context, empty, 2, 6, &output) == BHFFI_ARGUMENT);
    assert(bhffi_compression_v1_compress(context, empty, 0, -1, &output) == BHFFI_ARGUMENT);
    assert(bhffi_compression_v1_decompress(context, empty, 0, 0, &output) == BHFFI_ARGUMENT);
    assert(bhffi_compression_v1_decompress(context, empty, 0, BHFFI_OUTPUT_LIMIT + 1, &output) == BHFFI_ARGUMENT);
    assert(bhffi_compression_v1_decompress(context, empty, 0, 1, &output) == BHFFI_CORRUPT);
    bhffi_view invalid = {(const uint8_t *)"x", BHFFI_INPUT_LIMIT + 1};
    assert(bhffi_compression_v1_compress(context, invalid, 0, 6, &output) == BHFFI_INPUT);
    /* Visit every allocation in zlib setup and in the detached output buffer. */
    int succeeded = 0;
    for (int i = 0; i < 20; ++i) {
        bhffi_test_fail_after(context, i);
        int status = bhffi_compression_v1_compress(context, empty, 1, 6, &output);
        assert(status == BHFFI_RESOURCE || status == BHFFI_OK);
        if (status == BHFFI_OK) { bhffi_compression_v1_bytes_release(context, &output); succeeded = 1; }
        else assert(!output.data && !output.length);
        assert(bhffi_v1_context_bytes(context) == 0);
    }
    assert(succeeded);
    bhffi_test_fail_after(context, -1);
    assert(bhffi_compression_v1_compress(context, empty, 0, 6, &output) == BHFFI_OK);
    assert(bhffi_v1_context_shutdown(context) == BHFFI_OK);
    /* The buffer's lease keeps its allocator/context alive after shutdown. */
    bhffi_owned rejected = {0};
    assert(bhffi_compression_v1_compress(context, empty, 0, 6, &rejected) == BHFFI_CONTRACT);
    bhffi_compression_v1_bytes_release(context, &output);
}
static voidpf test_allocate(voidpf opaque, uInt count, uInt size) {
    (void)opaque; return calloc(count, size);
}
static void test_release(voidpf opaque, voidpf pointer) { (void)opaque; free(pointer); }
static void dictionary(void) {
    uint8_t packed[128];
    z_stream stream = {0};
    stream.zalloc = test_allocate; stream.zfree = test_release;
    assert(deflateInit(&stream, 6) == Z_OK);
    assert(deflateSetDictionary(&stream, (const Bytef *)"dictionary", 10) == Z_OK);
    stream.next_in = (Bytef *)"dictionary"; stream.avail_in = 10;
    stream.next_out = packed; stream.avail_out = sizeof(packed);
    assert(deflate(&stream, Z_FINISH) == Z_STREAM_END);
    bhffi_context *context = bhffi_v1_context_create();
    bhffi_owned output = {0};
    bhffi_view input = {packed, stream.total_out};
    assert(bhffi_compression_v1_decompress(context, input, 0, 100, &output) == BHFFI_DICTIONARY);
    assert(!output.data && bhffi_v1_context_bytes(context) == 0);
    deflateEnd(&stream);
    assert(bhffi_v1_context_shutdown(context) == BHFFI_OK);
}
int main(void) {
    const uint8_t hindi[] = "भारत नमस्ते 😀";
    for (int format = 0; format <= 1; ++format) for (int level = 0; level <= 9; ++level) {
        roundtrip(format, level, hindi, sizeof(hindi) - 1);
        roundtrip(format, level, NULL, 0);
    }
    uint8_t *bomb = calloc(1, (size_t)BHFFI_INPUT_LIMIT);
    assert(bomb);
    roundtrip(0, 9, bomb, (size_t)BHFFI_INPUT_LIMIT);
    free(bomb);
    failures(); dictionary();
    puts("FFI compression: roundtrips, limits, malformed streams, allocation failures and context leases passed");
    return 0;
}
