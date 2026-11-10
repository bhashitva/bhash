// SPDX-License-Identifier: Apache-2.0
package dev.bhash.backend.llvm;

import dev.bhash.backend.CoreProgram;
import dev.bhash.backend.CoreProgram.Type;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

/** Emits both sides of the Android JNI bridge from the checked native exports. */
final class AndroidBindings {
    private static final Set<String> PACKAGE_KEYWORDS = Set.of("as", "break", "class", "continue", "do", "else",
        "false", "for", "fun", "if", "in", "interface", "is", "null", "object", "package", "return", "super",
        "this", "throw", "true", "try", "typealias", "typeof", "val", "var", "when", "while");

    private AndroidBindings() {}

    static void write(CoreProgram.Library library, String packageName, Path output) throws IOException {
        if (!packageName.matches("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+")
                || Arrays.stream(packageName.split("\\.")).anyMatch(PACKAGE_KEYWORDS::contains)) {
            throw new IllegalArgumentException("Supply a Kotlin package such as org.example.app.bhash for --jni-package.");
        }
        String prefix = "Java_" + packageName.replace("_", "_1").replace('.', '_') + "_BhashNative_";
        var c = new StringBuilder(template("bhash_jni.c").replace("@JNI_PREFIX@", prefix)
            .replace("@EXCEPTION_CLASS@", packageName.replace('.', '/') + "/BhashException"));
        String kotlin = template("BhashBindings.kt").replace("@PACKAGE@", packageName);
        var methods = new StringBuilder();
        var nativeMethods = new StringBuilder();
        for (var export : library.exports()) {
            var parameters = export.function().parameters().stream().map(parameter -> parameter.type()).toList();
            var result = export.function().result();
            writeKotlin(export.symbol(), parameters, result, methods, nativeMethods);
            writeJni(prefix, export.symbol(), parameters, result, c);
        }
        Files.writeString(output.resolve("BhashBindings.kt"), kotlin
            .replace("@METHODS@", methods.toString()).replace("@NATIVE_METHODS@", nativeMethods.toString()));
        Files.writeString(output.resolve("bhash_jni.c"), c);
    }

    private static String template(String file) throws IOException {
        try (var input = AndroidBindings.class.getResourceAsStream("/dev/bhash/backend/android/" + file)) {
            if (input == null) throw new IOException("Missing Android bindings template: " + file);
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static void writeKotlin(String symbol, List<Type> parameters, Type result,
                                    StringBuilder methods, StringBuilder nativeMethods) {
        var declarations = new ArrayList<String>();
        var rawDeclarations = new ArrayList<String>();
        var arguments = new ArrayList<String>();
        for (int i = 0; i < parameters.size(); i++) {
            var type = parameters.get(i);
            String arg = "arg" + i;
            declarations.add(arg + ": " + kotlinType(type, false));
            rawDeclarations.add(arg + ": " + kotlinType(type, true));
            arguments.add(arg + switch (type) {
                case TEXT -> ".encodeToByteArray(throwOnInvalidSequence = true)";
                case OBJECT -> ".borrow()";
                default -> "";
            });
        }
        String call = "BhashNative." + symbol + "(" + String.join(", ", arguments) + ")";
        String value = switch (result) {
            case TEXT -> call + ".decodeToString(throwOnInvalidSequence = true)";
            case OBJECT -> "BhashHandle.own(" + call + ")";
            default -> call;
        };
        methods.append("    fun ").append(symbol).append("(").append(String.join(", ", declarations)).append("): ")
            .append(kotlinType(result, false)).append(" {\n        BhashRuntime.requireWorker()\n");
        methods.append(result == Type.VOID ? "        " : "        return ").append(value).append("\n    }\n\n");
        nativeMethods.append("    external fun ").append(symbol).append("(")
            .append(String.join(", ", rawDeclarations)).append("): ").append(kotlinType(result, true)).append("\n");
    }

    private static String kotlinType(Type type, boolean raw) {
        return switch (type) {
            case INT -> "Long";
            case BOOL -> "Boolean";
            case TEXT -> raw ? "ByteArray" : "String";
            case OBJECT -> raw ? "Long" : "BhashHandle";
            case VOID -> "Unit";
            default -> throw new IllegalArgumentException("Unsupported Android export type: " + type);
        };
    }

    private static String jniType(Type type) {
        return switch (type) {
            case INT, OBJECT -> "jlong";
            case BOOL -> "jboolean";
            case TEXT -> "jbyteArray";
            case VOID -> "void";
            default -> throw new IllegalArgumentException("Unsupported Android export type: " + type);
        };
    }

    private static void writeJni(String prefix, String symbol, List<Type> parameters, Type result,
                                 StringBuilder c) {
        c.append("\nJNIEXPORT ").append(jniType(result)).append(" JNICALL ").append(prefix)
            .append(symbol.replace("_", "_1")).append("(JNIEnv *env, jobject self");
        var arguments = new ArrayList<String>();
        var textParameters = new ArrayList<String>();
        for (int i = 0; i < parameters.size(); i++) {
            var type = parameters.get(i);
            String arg = "arg" + i;
            c.append(", ").append(jniType(type)).append(" ").append(arg);
            arguments.add(switch (type) {
                case TEXT -> arg + "_view";
                case INT -> "(int64_t)" + arg;
                case BOOL -> arg + " == JNI_TRUE";
                case OBJECT -> "(bhash_handle)" + arg;
                default -> throw new IllegalArgumentException("Unsupported Android parameter type: " + type);
            });
            if (type == Type.TEXT) textParameters.add(arg);
        }
        c.append(") {\n    (void)self;\n    bhash_error error = {0};\n    bhash_status status = BHASH_OK;\n");
        switch (result) {
            case INT -> c.append("    int64_t value = 0;\n");
            case BOOL -> c.append("    uint8_t value = 0;\n");
            case OBJECT -> c.append("    bhash_handle value = 0;\n");
            case TEXT -> c.append("    bhash_bytes value = {0};\n");
            case VOID -> {}
            default -> throw new IllegalArgumentException("Unsupported Android result type: " + result);
        }
        for (String arg : textParameters) {
            c.append("    jbyte *").append(arg).append("_pin = NULL;\n")
                .append("    bhash_utf8_view ").append(arg).append("_view = {0};\n");
        }
        for (String arg : textParameters) {
            c.append("    status = borrow_bytes(env, ").append(arg).append(", &").append(arg)
                .append("_pin, &").append(arg).append("_view);\n")
                .append("    if (status != BHASH_OK) goto cleanup;\n");
        }
        if (result != Type.VOID) arguments.add("&value");
        arguments.add("&error");
        c.append("    status = ").append(symbol).append("(").append(String.join(", ", arguments)).append(");\n");
        if (!textParameters.isEmpty()) c.append("cleanup:\n");
        for (String arg : textParameters) {
            c.append("    if (").append(arg).append("_pin) (*env)->ReleaseByteArrayElements(env, ").append(arg)
                .append(", ").append(arg).append("_pin, JNI_ABORT);\n");
        }
        c.append("    report_failure(env, status, &error);\n");
        switch (result) {
            case TEXT -> c.append("""
                    jbyteArray output = NULL;
                    if (status == BHASH_OK && !(*env)->ExceptionCheck(env)) output = copy_bytes(env, &value);
                    bhash_bytes_free(&value);
                    return output;
                """.indent(4));
            case INT, OBJECT -> c.append("    return (jlong)value;\n");
            case BOOL -> c.append("    return value ? JNI_TRUE : JNI_FALSE;\n");
            case VOID -> {}
            default -> throw new IllegalArgumentException("Unsupported Android result type: " + result);
        }
        c.append("}\n");
    }
}
