// SPDX-License-Identifier: Apache-2.0
using System.IO.Compression;
using System.Runtime.InteropServices;
using System.Text;

// Standalone CLR/C ABI conformance probe, not the Bhash P/Invoke generator.
[StructLayout(LayoutKind.Sequential)]
struct Wire { public IntPtr Data; public ulong Length; }
[UnmanagedFunctionPointer(CallingConvention.Cdecl)] delegate IntPtr Create();
[UnmanagedFunctionPointer(CallingConvention.Cdecl)] delegate int Shutdown(IntPtr context);
[UnmanagedFunctionPointer(CallingConvention.Cdecl)] delegate ulong Bytes(IntPtr context);
[UnmanagedFunctionPointer(CallingConvention.Cdecl)] delegate int Reserve(IntPtr context, ulong length, out Wire buffer);
[UnmanagedFunctionPointer(CallingConvention.Cdecl)] delegate int Compress(IntPtr context, Wire input, int format, int level, out Wire output);
[UnmanagedFunctionPointer(CallingConvention.Cdecl)] delegate int Decompress(IntPtr context, Wire input, int format, ulong limit, out Wire output);
[UnmanagedFunctionPointer(CallingConvention.Cdecl)] delegate void Release(IntPtr context, ref Wire output);
static class Program {
    static void Check(bool condition, string message) { if (!condition) throw new Exception(message); }
    static void Collect() { GC.Collect(); GC.WaitForPendingFinalizers(); GC.Collect(); }
    static void Main(string[] args) {
        IntPtr library = NativeLibrary.Load(Path.GetFullPath(args[0]));
        T Symbol<T>(string name) where T : Delegate => Marshal.GetDelegateForFunctionPointer<T>(NativeLibrary.GetExport(library, name));
        var create = Symbol<Create>("bhffi_v1_context_create");
        var shutdown = Symbol<Shutdown>("bhffi_v1_context_shutdown");
        var bytes = Symbol<Bytes>("bhffi_v1_context_bytes");
        var reserve = Symbol<Reserve>("bhffi_v1_bytes_reserve");
        var compress = Symbol<Compress>("bhffi_compression_v1_compress");
        var decompress = Symbol<Decompress>("bhffi_compression_v1_decompress");
        var release = Symbol<Release>("bhffi_compression_v1_bytes_release");
        Check(Marshal.SizeOf<Wire>() == 16 && Marshal.OffsetOf<Wire>(nameof(Wire.Length)).ToInt32() == 8, "wire layout");
        IntPtr context = create();
        Check(context != IntPtr.Zero, "context allocation");
        byte[] Call(byte[] source, int format, int option, bool decode) {
            Wire input = default, output = default;
            try {
                Check(reserve(context, (ulong)source.Length, out input) == 0, "input allocation");
                Marshal.Copy(source, 0, input.Data, source.Length);
                Collect();
                int status = decode ? decompress(context, input, format, (ulong)option, out output)
                                    : compress(context, input, format, option, out output);
                Check(status == 0, "bridge status " + status);
                Collect();
                byte[] result = new byte[checked((int)output.Length)];
                Marshal.Copy(output.Data, result, 0, result.Length);
                Collect();
                return result;
            } finally {
                release(context, ref output); release(context, ref input);
                Check(bytes(context) == 0, "foreign buffer leak");
            }
        }
        try {
            foreach (byte[] source in new[] { Array.Empty<byte>(), Encoding.UTF8.GetBytes("नमस्ते भारत 😀\0अंत"), new byte[65536] }) {
                foreach (int format in new[] { 0, 1 }) {
                    byte[] packed = Call(source, format, 6, false);
                    Check(source.SequenceEqual(Call(packed, format, Math.Max(1, source.Length), true)), "roundtrip");
                    using var input = new MemoryStream(packed);
                    using Stream decoder = format == 0 ? new ZLibStream(input, CompressionMode.Decompress) : new GZipStream(input, CompressionMode.Decompress);
                    using var decoded = new MemoryStream();
                    decoder.CopyTo(decoded);
                    Check(source.SequenceEqual(decoded.ToArray()), "independent decoder");
                }
            }
        } finally { Check(shutdown(context) == 0, "shutdown"); }
        // Keep the module loaded through process exit, matching the v1 lifetime contract.
        Console.WriteLine("CLR probe: explicit C ABI, copied buffers, independent decoding and forced GC passed");
    }
}
