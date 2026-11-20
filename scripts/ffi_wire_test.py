#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Exercise the fixed ABI and compare streams with independent Python codecs."""
import argparse
import ctypes as C
import gzip
from pathlib import Path
import random
import zlib


class View(C.Structure):
    _fields_ = [("data", C.c_void_p), ("length", C.c_uint64)]


class Owned(C.Structure):
    _fields_ = [("data", C.c_void_p), ("length", C.c_uint64)]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("library", type=Path)
    args = parser.parse_args()
    library = C.CDLL(str(args.library.resolve()))
    assert C.sizeof(View) == C.sizeof(Owned) == 16
    assert View.length.offset == Owned.length.offset == 8
    library.bhffi_v1_abi.restype = C.c_uint32
    assert library.bhffi_v1_abi() == 1
    library.bhffi_v1_layout.restype = C.c_uint64
    assert library.bhffi_v1_layout() != 0
    create = library.bhffi_v1_context_create
    create.restype = C.c_void_p
    shutdown = library.bhffi_v1_context_shutdown
    shutdown.argtypes = [C.c_void_p]
    shutdown.restype = C.c_int32
    compress = library.bhffi_compression_v1_compress
    compress.argtypes = [C.c_void_p, View, C.c_int32, C.c_int32, C.POINTER(Owned)]
    compress.restype = C.c_int32
    decompress = library.bhffi_compression_v1_decompress
    decompress.argtypes = [C.c_void_p, View, C.c_int32, C.c_uint64, C.POINTER(Owned)]
    decompress.restype = C.c_int32
    release = library.bhffi_compression_v1_bytes_release
    release.argtypes = [C.c_void_p, C.POINTER(Owned)]
    release.restype = None
    context = create()
    assert context

    def invoke(call, data, format, option):
        buffer = C.create_string_buffer(data)
        output = Owned()
        status = call(context, View(C.cast(buffer, C.c_void_p), len(data)), format, option, C.byref(output))
        try:
            if status:
                assert output.data is None and output.length == 0
                return status, None
            return 0, C.string_at(output.data, output.length)
        finally:
            release(context, C.byref(output))

    try:
        samples = [b"", "नमस्ते भारत 😀\x00अंत".encode(), bytes(range(256)), random.Random(42).randbytes(65536)]
        for format in (0, 1):
            decode = zlib.decompress if format == 0 else gzip.decompress
            encode = zlib.compress if format == 0 else gzip.compress
            for source in samples:
                for level in (0, 1, 6, 9):
                    status, packed = invoke(compress, source, format, level)
                    assert status == 0 and decode(packed) == source
                    assert invoke(decompress, encode(source, compresslevel=level) if format else encode(source, level), format, max(1, len(source))) == (0, source)
                    assert invoke(compress, source, format, level) == (0, packed)
        # Check that the bridge reports a bounded failure for a real compression bomb.
        assert invoke(decompress, gzip.compress(b"x" * 1048576), 1, 1024)[0] == 3
        assert invoke(decompress, zlib.compress(b"x") + b"\x00", 0, 1024)[0] == 4
        assert invoke(decompress, gzip.compress(b"x") + gzip.compress(b"y"), 1, 1024)[0] == 4
    finally:
        assert shutdown(context) == 0
    print("FFI wire: layout, independent zlib/gzip interoperability, deterministic headers and bounded decoding passed")


if __name__ == "__main__":
    main()
