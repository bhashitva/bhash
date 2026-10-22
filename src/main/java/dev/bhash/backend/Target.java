package dev.bhash.backend;

/** JVM, CLR and browser WebAssembly output are implemented; other targets remain planned. */
public enum Target {
    JVM("JVM bytecode (.class / .jar)"),
    CLR(".NET 10 managed assembly (.dll + runtime metadata)"),
    WASM("Browser WebAssembly (.NET 10 runtime bundle)"),
    X86_64("x86-64 assembly; initial ABI proposal: Linux System V"),
    AARCH64("ARM64 assembly; initial ABI proposal: Linux AAPCS64");

    private final String description;
    Target(String description) { this.description = description; }
    public String description() { return description; }
}
