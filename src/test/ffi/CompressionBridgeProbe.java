// SPDX-License-Identifier: Apache-2.0
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.*;

/** Standalone Java-17 JNI/GC conformance probe; independent of generated Bhash code. */
public final class CompressionBridgeProbe {
    private static native long create();
    private static native int shutdown(long context);
    private static native long bytes(long context);
    private static native int operation(long context, byte[] input, int format, int option, boolean decode, byte[][] output);
    private static byte[] call(long context, byte[] input, int format, int option, boolean decode) {
        byte[][] output = new byte[1][];
        int status = operation(context, input, format, option, decode, output);
        if (status != 0 || output[0] == null) throw new AssertionError("bridge status " + status);
        if (bytes(context) != 0) throw new AssertionError("foreign buffer leak");
        return output[0];
    }
    public static void main(String[] args) throws Exception {
        System.load(new File(args[0]).getAbsolutePath());
        long context = create();
        if (context == 0) throw new AssertionError("no context");
        try {
            for (byte[] source : List.of(new byte[0], "नमस्ते भारत 😀\u0000अंत".getBytes(StandardCharsets.UTF_8), new byte[65536])) {
                for (int format = 0; format <= 1; format++) {
                    byte[] packed = call(context, source, format, 6, false);
                    if (!Arrays.equals(source, call(context, packed, format, Math.max(1, source.length), true))) throw new AssertionError("roundtrip");
                    try (InputStream decode = format == 1 ? new GZIPInputStream(new ByteArrayInputStream(packed)) : new InflaterInputStream(new ByteArrayInputStream(packed))) {
                        if (!Arrays.equals(source, decode.readAllBytes())) throw new AssertionError("independent decoder");
                    }
                }
            }
            byte[][] rejected = new byte[1][];
            if (operation(context, new byte[]{1,2,3}, 0, 1024, true, rejected) != 4 || rejected[0] != null) throw new AssertionError("corrupt stream");
            if (bytes(context) != 0) throw new AssertionError("failure cleanup");
        } finally {
            if (shutdown(context) != 0) throw new AssertionError("shutdown");
        }
        System.out.println("JNI probe: Java 17 ABI, copied buffers, independent decoding and forced GC passed");
    }
}
