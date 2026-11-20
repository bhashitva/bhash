// SPDX-License-Identifier: Apache-2.0
package dev.bhash.frontend;

import java.util.Objects;

/** One independently parsed UTF-8 compilation unit. */
public record SourceFile(String name, String text, boolean strictNulls, dev.bhash.ffi.ForeignManifest foreign) {
    public SourceFile(String name,String text,boolean strictNulls) { this(name,text,strictNulls,null); }
    public SourceFile(String name,String text) { this(name,text,false); }
    public SourceFile { Objects.requireNonNull(name); Objects.requireNonNull(text);strictNulls|=strictDirective(text); }
    /** A single-file/notebook equivalent of the project profile; offsets stay unchanged. */
    public static boolean strictDirective(String source) {
        return source.replaceFirst("^\\uFEFF","").stripLeading().matches("(?s)^//[ \\t]*bhash:null-safety=strict(?:\\r?\\n.*|$)");
    }
    public Frontend.Result parse() {
        if (foreign != null) return new Frontend.Result(dev.bhash.ffi.ForeignDeclarations.tree(foreign,name,strictNulls),java.util.List.of());
        return Frontend.parse(name, text.startsWith("\uFEFF") ? text.substring(1) : text,strictNulls);
    }
}
