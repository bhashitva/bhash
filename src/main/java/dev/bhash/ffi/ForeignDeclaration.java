// SPDX-License-Identifier: Apache-2.0
package dev.bhash.ffi;

/** Stable declaration identity survives package resolution and type specialization. */
public record ForeignDeclaration(ForeignManifest module, ForeignManifest.Function function,boolean close) {
    public ForeignDeclaration(ForeignManifest module,ForeignManifest.Function function){this(module,function,false);}
    public String identity() { return module.packageName() + "@" + module.contractVersion() + ":" + function.identity(); }
}
