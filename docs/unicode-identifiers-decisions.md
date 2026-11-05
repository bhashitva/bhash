# Unicode identifier decisions

Approved on 5 November 2026 following review of the design and exact repertoire delta.

The implementation profile is `bhash-devanagari-1`, based on Unicode 18.0.0. The [design](unicode-identifiers-design.md) describes the architecture and the [repertoire review](unicode-identifiers-repertoire-review.md) preserves the original formula comparison. The [Hindi user guide](../tutorial/unicode-identifiers/README.md) explains the resulting language behavior.

## Accepted repertoire

Retain U+0953 DEVANAGARI GRAVE ACCENT and U+0954 DEVANAGARI ACUTE ACCENT as explicit continuation exceptions. This preserves all previously accepted characters. Accept the additions produced by the reviewed XID/Script_Extensions formula, including U+02BC MODIFIER LETTER APOSTROPHE and U+20F0 COMBINING ASTERISK ABOVE. The formula is a lexical profile and allows Vedic and extended Devanagari characters as documented in the exact list.

The final repertoire contains 102 starting characters and 205 unconditional continuation characters. Every starting character is also a continuation character. Two additional controls, U+200C and U+200D, are accepted only in the reviewed conjunct context. These counts describe identifier characters and do not change numeric literal rules.

The generated [profile manifest](identifier-profile.json) records the ranges, exceptions, Unicode data input hashes, and a normalization-pair digest. The [baseline lexer fragments](identifier-profile-baseline.g4) support reproducing the historical comparison after implementation.

## Equality and text preservation

Validate original spelling before computing an identifier key. Remove permitted ZWJ/ZWNJ controls from identifier comparison spelling and then apply NFC. The same key is used for keyword classification, symbols, overload names, imports, completion, notebook state, and Watch expressions. Existing scope and overload rules apply to equal keys.

Preserve original source text and code-point offsets. Token type can change when a shaped spelling denotes a keyword. Token text continues to represent what the user wrote. Runtime strings and data keep their original spelling and controls.

## Files and tools

Project source discovery preserves physical paths and rejects equivalent relative source paths with a diagnostic showing both files. Imports resolve through the declarations indexed across project sources. Equivalent package and symbol names use comparison keys. Git URL spelling, revisions, file bytes, and lockfile content hashes retain their established integrity rules. Snapshot extraction rejects Unicode-colliding paths before filesystem writes can obscure a conflict.

Generated frontend, browser, Jupyter, and debugger data use the pinned profile. Syntax highlighting is an editor aid. Compilation through the shared frontend determines validity. LSP fixes for invalid joiners are conservative when equivalent declarations are present.

Editor word-boundary helpers also recognize ASCII letters so existing English completion aliases replace the typed word correctly. Identifier validation continues to use the strict start and continuation tables.

## Validation and release

The implementation PR must run frontend/profile tests, managed and native behavior fixtures, forced-GC tests, browser execution, and full Jupyter client tests on Linux, Windows, and macOS. Native macOS remains scheduled weekly with manual validation available. The profile generation and normalization checks run independently of the host JDK's Unicode identifier-property version.

Publishing compiler, extension, kernel, or playground artifacts follows validation and the corresponding release workflow. This document records the language decisions for this implementation milestone.
