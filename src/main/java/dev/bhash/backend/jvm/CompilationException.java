package dev.bhash.backend.jvm;

/** A source-located Bhash diagnostic, or a compiler/toolchain failure. */
public final class CompilationException extends Exception {
    public CompilationException(String message) { super(message); }
}
