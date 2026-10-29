// SPDX-License-Identifier: Apache-2.0
package dev.bhash.backend;

/** Managed targets are implemented; native Linux/Windows is experimental; assembly targets remain planned. */
public enum Target {
    JVM("JVM bytecode (.class / .jar)"),
    CLR(".NET 10 managed assembly (.dll + runtime metadata)"),
    WASM("Browser WebAssembly (.NET 10 runtime bundle)"),
    NATIVE("Experimental Linux/Windows x86-64 executable (LLVM 18 + Bhash native runtime)"),
    X86_64("x86-64 assembly; initial ABI proposal: Linux System V"),
    AARCH64("ARM64 assembly; initial ABI proposal: Linux AAPCS64");

    private final String description;
    Target(String description) { this.description = description; }
    public String description() { return description; }
}
