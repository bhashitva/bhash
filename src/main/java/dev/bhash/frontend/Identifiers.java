// SPDX-License-Identifier: Apache-2.0
package dev.bhash.frontend;

import java.text.Normalizer;

/** Symbol tables must use this key, preserving original spelling for diagnostics. */
public final class Identifiers {
    private Identifiers() {}
    public static String key(String spelling) {
        return Normalizer.normalize(spelling, Normalizer.Form.NFC);
    }
}
