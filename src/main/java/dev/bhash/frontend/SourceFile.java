// SPDX-License-Identifier: Apache-2.0
package dev.bhash.frontend;

import java.util.Objects;

/** One independently parsed UTF-8 compilation unit. */
public record SourceFile(String name, String text) {
    public SourceFile { Objects.requireNonNull(name); Objects.requireNonNull(text); }
    public Frontend.Result parse() {
        return Frontend.parse(name, text.startsWith("\uFEFF") ? text.substring(1) : text);
    }
}
