// SPDX-License-Identifier: Apache-2.0
package dev.bhash.backend;

/** A source-located Bhash diagnostic, or a compiler/toolchain failure. */
public final class CompilationException extends Exception {
    public CompilationException(String message) { super(message); }
}
