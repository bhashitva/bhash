package dev.bhash.backend;

/** Planned output targets; no code generators are implemented in the starter. */
public enum Target {
    JVM("JVM bytecode (.class / .jar)"),
    CLR(".NET Common Intermediate Language (CIL)"),
    WASM("WebAssembly; initial runtime proposal: WASI"),
    X86_64("x86-64 assembly; initial ABI proposal: Linux System V"),
    AARCH64("ARM64 assembly; initial ABI proposal: Linux AAPCS64");

    private final String description;
    Target(String description) { this.description = description; }
    public String description() { return description; }
}
