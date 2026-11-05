# Identifier repertoire changes for review

Status — historical formula comparison. The [approved decisions](unicode-identifiers-decisions.md) retain both removed accents as explicit exceptions.

Date — 5 November 2026.

This report applies the original design formula to Unicode 18.0.0 and compares it with the preserved pre-change lexer fragments in `identifier-profile-baseline.g4`. The machine-readable predicates and generation procedure are included below. Every affected code point appears in a table. Code points provide the identity when fonts cannot render a character.

## Counts

| Position | Current | Proposed unconditional | Added | Removed |
| --- | --- | --- | --- | --- |
| First character | 79 | 102 | 23 | 0 |
| Subsequent character | 135 | 203 | 70 | 2 |

The proposed profile newly accepts 70 code points in identifiers and promotes 1 already accepted character to starting position. It removes 2 currently accepted characters. U+200C and U+200D are two additional conditional continuations, excluded from the unconditional counts. Starting characters also belong to the continuation set, so position counts must not be added together.

## Cases requiring attention

- U+0953 DEVANAGARI GRAVE ACCENT and U+0954 DEVANAGARI ACUTE ACCENT would become invalid in identifiers. Both are currently allowed as continuation marks. Their Unicode names contain DEVANAGARI, but their Script_Extensions value lacks Deva. This would reject an existing spelling even after NFC normalization.
- U+02BC MODIFIER LETTER APOSTROPHE would become valid at the start and within identifiers because Deva occurs in its Script_Extensions. Its glyph can resemble punctuation.
- U+20F0 COMBINING ASTERISK ABOVE would become valid within identifiers through the same shared-script rule.
- The Vedic and extended Devanagari additions follow the approved repertoire formula. They broaden identifiers beyond characters commonly used for modern Hindi.
- U+11B0A is supplementary and was introduced in Unicode 18.0. Its acceptance requires correct surrogate-pair handling in editors and normalization checks against supported JDKs.

My recommendation is to review explicit continuation exceptions for U+0953/U+0954 to preserve existing accepted spellings. Decide separately whether U+02BC and U+20F0 fit the intended language repertoire. The counts above describe the approved formula before any such exceptions. Retaining both accents would make the unconditional continuation count 205. Excluding the apostrophe subtracts one from both start and continuation counts. Excluding the combining asterisk subtracts one from the continuation count.

## Every newly accepted character

`Start + continue` means the code point is newly accepted in both positions. `Continue` means it remains invalid as the first character. For Mn and Mc marks the glyph is displayed on a dotted circle, which is illustrative and is not part of the identifier. Lo means other letter, Lm modifier letter, Mn nonspacing mark, Mc spacing combining mark, and Cf format character.

| Code point | Glyph | Unicode name | Category | Introduced | New positions | Explanation |
| --- | --- | --- | --- | --- | --- | --- |
| U+02BC | ʼ | MODIFIER LETTER APOSTROPHE | Lm | 1.1 | Start + continue | XID_Start modifier letter with Deva in its explicit Script_Extensions. This is U+02BC, distinct from ASCII apostrophe U+0027. |
| U+0950 | ॐ | DEVANAGARI OM | Lo | 1.1 | Start + continue | Devanagari Script letter meeting the XID predicate, outside the corresponding current grammar ranges. |
| U+1CD0 | ◌᳐ | VEDIC TONE KARSHANA | Mn | 5.2 | Continue | Shared Vedic combining mark meeting the XID predicate, with Deva explicitly present in Script_Extensions. |
| U+1CD1 | ◌᳑ | VEDIC TONE SHARA | Mn | 5.2 | Continue | Shared Vedic combining mark meeting the XID predicate, with Deva explicitly present in Script_Extensions. |
| U+1CD2 | ◌᳒ | VEDIC TONE PRENKHA | Mn | 5.2 | Continue | Shared Vedic combining mark meeting the XID predicate, with Deva explicitly present in Script_Extensions. |
| U+1CD4 | ◌᳔ | VEDIC SIGN YAJURVEDIC MIDLINE SVARITA | Mn | 5.2 | Continue | Shared Vedic combining mark meeting the XID predicate, with Deva explicitly present in Script_Extensions. |
| U+1CD5 | ◌᳕ | VEDIC TONE YAJURVEDIC AGGRAVATED INDEPENDENT SVARITA | Mn | 5.2 | Continue | Shared Vedic combining mark meeting the XID predicate, with Deva explicitly present in Script_Extensions. |
| U+1CD6 | ◌᳖ | VEDIC TONE YAJURVEDIC INDEPENDENT SVARITA | Mn | 5.2 | Continue | Shared Vedic combining mark meeting the XID predicate, with Deva explicitly present in Script_Extensions. |
| U+1CD7 | ◌᳗ | VEDIC TONE YAJURVEDIC KATHAKA INDEPENDENT SVARITA | Mn | 5.2 | Continue | Shared Vedic combining mark meeting the XID predicate, with Deva explicitly present in Script_Extensions. |
| U+1CD8 | ◌᳘ | VEDIC TONE CANDRA BELOW | Mn | 5.2 | Continue | Shared Vedic combining mark meeting the XID predicate, with Deva explicitly present in Script_Extensions. |
| U+1CD9 | ◌᳙ | VEDIC TONE YAJURVEDIC KATHAKA INDEPENDENT SVARITA SCHROEDER | Mn | 5.2 | Continue | Shared Vedic combining mark meeting the XID predicate, with Deva explicitly present in Script_Extensions. |
| U+1CDA | ◌᳚ | VEDIC TONE DOUBLE SVARITA | Mn | 5.2 | Continue | Shared Vedic combining mark meeting the XID predicate, with Deva explicitly present in Script_Extensions. |
| U+1CDB | ◌᳛ | VEDIC TONE TRIPLE SVARITA | Mn | 5.2 | Continue | Shared Vedic combining mark meeting the XID predicate, with Deva explicitly present in Script_Extensions. |
| U+1CDC | ◌᳜ | VEDIC TONE KATHAKA ANUDATTA | Mn | 5.2 | Continue | Shared Vedic combining mark meeting the XID predicate, with Deva explicitly present in Script_Extensions. |
| U+1CDD | ◌᳝ | VEDIC TONE DOT BELOW | Mn | 5.2 | Continue | Shared Vedic combining mark meeting the XID predicate, with Deva explicitly present in Script_Extensions. |
| U+1CDE | ◌᳞ | VEDIC TONE TWO DOTS BELOW | Mn | 5.2 | Continue | Shared Vedic combining mark meeting the XID predicate, with Deva explicitly present in Script_Extensions. |
| U+1CDF | ◌᳟ | VEDIC TONE THREE DOTS BELOW | Mn | 5.2 | Continue | Shared Vedic combining mark meeting the XID predicate, with Deva explicitly present in Script_Extensions. |
| U+1CE0 | ◌᳠ | VEDIC TONE RIGVEDIC KASHMIRI INDEPENDENT SVARITA | Mn | 5.2 | Continue | Shared Vedic combining mark meeting the XID predicate, with Deva explicitly present in Script_Extensions. |
| U+1CE1 | ◌᳡ | VEDIC TONE ATHARVAVEDIC INDEPENDENT SVARITA | Mc | 5.2 | Continue | Shared Vedic combining mark meeting the XID predicate, with Deva explicitly present in Script_Extensions. |
| U+1CE2 | ◌᳢ | VEDIC SIGN VISARGA SVARITA | Mn | 5.2 | Continue | Shared Vedic combining mark meeting the XID predicate, with Deva explicitly present in Script_Extensions. |
| U+1CE3 | ◌᳣ | VEDIC SIGN VISARGA UDATTA | Mn | 5.2 | Continue | Shared Vedic combining mark meeting the XID predicate, with Deva explicitly present in Script_Extensions. |
| U+1CE4 | ◌᳤ | VEDIC SIGN REVERSED VISARGA UDATTA | Mn | 5.2 | Continue | Shared Vedic combining mark meeting the XID predicate, with Deva explicitly present in Script_Extensions. |
| U+1CE5 | ◌᳥ | VEDIC SIGN VISARGA ANUDATTA | Mn | 5.2 | Continue | Shared Vedic combining mark meeting the XID predicate, with Deva explicitly present in Script_Extensions. |
| U+1CE6 | ◌᳦ | VEDIC SIGN REVERSED VISARGA ANUDATTA | Mn | 5.2 | Continue | Shared Vedic combining mark meeting the XID predicate, with Deva explicitly present in Script_Extensions. |
| U+1CE7 | ◌᳧ | VEDIC SIGN VISARGA UDATTA WITH TAIL | Mn | 5.2 | Continue | Shared Vedic combining mark meeting the XID predicate, with Deva explicitly present in Script_Extensions. |
| U+1CE8 | ◌᳨ | VEDIC SIGN VISARGA ANUDATTA WITH TAIL | Mn | 5.2 | Continue | Shared Vedic combining mark meeting the XID predicate, with Deva explicitly present in Script_Extensions. |
| U+1CE9 | ᳩ | VEDIC SIGN ANUSVARA ANTARGOMUKHA | Lo | 5.2 | Start + continue | Shared Vedic letter meeting the XID predicate, with Deva explicitly present in Script_Extensions. |
| U+1CEA | ᳪ | VEDIC SIGN ANUSVARA BAHIRGOMUKHA | Lo | 5.2 | Start + continue | Shared Vedic letter meeting the XID predicate, with Deva explicitly present in Script_Extensions. |
| U+1CEB | ᳫ | VEDIC SIGN ANUSVARA VAMAGOMUKHA | Lo | 5.2 | Start + continue | Shared Vedic letter meeting the XID predicate, with Deva explicitly present in Script_Extensions. |
| U+1CEC | ᳬ | VEDIC SIGN ANUSVARA VAMAGOMUKHA WITH TAIL | Lo | 5.2 | Start + continue | Shared Vedic letter meeting the XID predicate, with Deva explicitly present in Script_Extensions. |
| U+1CED | ◌᳭ | VEDIC SIGN TIRYAK | Mn | 5.2 | Continue | Shared Vedic combining mark meeting the XID predicate, with Deva explicitly present in Script_Extensions. |
| U+1CEE | ᳮ | VEDIC SIGN HEXIFORM LONG ANUSVARA | Lo | 5.2 | Start + continue | Shared Vedic letter meeting the XID predicate, with Deva explicitly present in Script_Extensions. |
| U+1CEF | ᳯ | VEDIC SIGN LONG ANUSVARA | Lo | 5.2 | Start + continue | Shared Vedic letter meeting the XID predicate, with Deva explicitly present in Script_Extensions. |
| U+1CF0 | ᳰ | VEDIC SIGN RTHANG LONG ANUSVARA | Lo | 5.2 | Start + continue | Shared Vedic letter meeting the XID predicate, with Deva explicitly present in Script_Extensions. |
| U+1CF1 | ᳱ | VEDIC SIGN ANUSVARA UBHAYATO MUKHA | Lo | 5.2 | Start + continue | Shared Vedic letter meeting the XID predicate, with Deva explicitly present in Script_Extensions. |
| U+1CF2 | ᳲ | VEDIC SIGN ARDHAVISARGA | Lo | 5.2 | Start + continue | Shared Vedic letter meeting the XID predicate, with Deva explicitly present in Script_Extensions. |
| U+1CF3 | ᳳ | VEDIC SIGN ROTATED ARDHAVISARGA | Lo | 6.1 | Start + continue | Shared Vedic letter meeting the XID predicate, with Deva explicitly present in Script_Extensions. |
| U+1CF4 | ◌᳴ | VEDIC TONE CANDRA ABOVE | Mn | 6.1 | Continue | Shared Vedic combining mark meeting the XID predicate, with Deva explicitly present in Script_Extensions. |
| U+1CF8 | ◌᳸ | VEDIC TONE RING ABOVE | Mn | 7.0 | Continue | Shared Vedic combining mark meeting the XID predicate, with Deva explicitly present in Script_Extensions. |
| U+1CF9 | ◌᳹ | VEDIC TONE DOUBLE RING ABOVE | Mn | 7.0 | Continue | Shared Vedic combining mark meeting the XID predicate, with Deva explicitly present in Script_Extensions. |
| U+20F0 | ◌⃰ | COMBINING ASTERISK ABOVE | Mn | 5.1 | Continue | XID_Continue combining mark with Deva in its explicit Script_Extensions. ASCII asterisk U+002A remains an operator. |
| U+A8E0 | ◌꣠ | COMBINING DEVANAGARI DIGIT ZERO | Mn | 5.2 | Continue | Devanagari Script combining mark meeting the XID predicate, outside the corresponding current grammar ranges. |
| U+A8E1 | ◌꣡ | COMBINING DEVANAGARI DIGIT ONE | Mn | 5.2 | Continue | Devanagari Script combining mark meeting the XID predicate, outside the corresponding current grammar ranges. |
| U+A8E2 | ◌꣢ | COMBINING DEVANAGARI DIGIT TWO | Mn | 5.2 | Continue | Devanagari Script combining mark meeting the XID predicate, outside the corresponding current grammar ranges. |
| U+A8E3 | ◌꣣ | COMBINING DEVANAGARI DIGIT THREE | Mn | 5.2 | Continue | Devanagari Script combining mark meeting the XID predicate, outside the corresponding current grammar ranges. |
| U+A8E4 | ◌꣤ | COMBINING DEVANAGARI DIGIT FOUR | Mn | 5.2 | Continue | Devanagari Script combining mark meeting the XID predicate, outside the corresponding current grammar ranges. |
| U+A8E5 | ◌꣥ | COMBINING DEVANAGARI DIGIT FIVE | Mn | 5.2 | Continue | Devanagari Script combining mark meeting the XID predicate, outside the corresponding current grammar ranges. |
| U+A8E6 | ◌꣦ | COMBINING DEVANAGARI DIGIT SIX | Mn | 5.2 | Continue | Devanagari Script combining mark meeting the XID predicate, outside the corresponding current grammar ranges. |
| U+A8E7 | ◌꣧ | COMBINING DEVANAGARI DIGIT SEVEN | Mn | 5.2 | Continue | Devanagari Script combining mark meeting the XID predicate, outside the corresponding current grammar ranges. |
| U+A8E8 | ◌꣨ | COMBINING DEVANAGARI DIGIT EIGHT | Mn | 5.2 | Continue | Devanagari Script combining mark meeting the XID predicate, outside the corresponding current grammar ranges. |
| U+A8E9 | ◌꣩ | COMBINING DEVANAGARI DIGIT NINE | Mn | 5.2 | Continue | Devanagari Script combining mark meeting the XID predicate, outside the corresponding current grammar ranges. |
| U+A8EA | ◌꣪ | COMBINING DEVANAGARI LETTER A | Mn | 5.2 | Continue | Devanagari Script combining mark meeting the XID predicate, outside the corresponding current grammar ranges. |
| U+A8EB | ◌꣫ | COMBINING DEVANAGARI LETTER U | Mn | 5.2 | Continue | Devanagari Script combining mark meeting the XID predicate, outside the corresponding current grammar ranges. |
| U+A8EC | ◌꣬ | COMBINING DEVANAGARI LETTER KA | Mn | 5.2 | Continue | Devanagari Script combining mark meeting the XID predicate, outside the corresponding current grammar ranges. |
| U+A8ED | ◌꣭ | COMBINING DEVANAGARI LETTER NA | Mn | 5.2 | Continue | Devanagari Script combining mark meeting the XID predicate, outside the corresponding current grammar ranges. |
| U+A8EE | ◌꣮ | COMBINING DEVANAGARI LETTER PA | Mn | 5.2 | Continue | Devanagari Script combining mark meeting the XID predicate, outside the corresponding current grammar ranges. |
| U+A8EF | ◌꣯ | COMBINING DEVANAGARI LETTER RA | Mn | 5.2 | Continue | Devanagari Script combining mark meeting the XID predicate, outside the corresponding current grammar ranges. |
| U+A8F0 | ◌꣰ | COMBINING DEVANAGARI LETTER VI | Mn | 5.2 | Continue | Devanagari Script combining mark meeting the XID predicate, outside the corresponding current grammar ranges. |
| U+A8F1 | ◌꣱ | COMBINING DEVANAGARI SIGN AVAGRAHA | Mn | 5.2 | Continue | Devanagari Script combining mark meeting the XID predicate, outside the corresponding current grammar ranges. |
| U+A8F2 | ꣲ | DEVANAGARI SIGN SPACING CANDRABINDU | Lo | 5.2 | Start + continue | Devanagari Script letter meeting the XID predicate, outside the corresponding current grammar ranges. |
| U+A8F3 | ꣳ | DEVANAGARI SIGN CANDRABINDU VIRAMA | Lo | 5.2 | Start + continue | Devanagari Script letter meeting the XID predicate, outside the corresponding current grammar ranges. |
| U+A8F4 | ꣴ | DEVANAGARI SIGN DOUBLE CANDRABINDU VIRAMA | Lo | 5.2 | Start + continue | Devanagari Script letter meeting the XID predicate, outside the corresponding current grammar ranges. |
| U+A8F5 | ꣵ | DEVANAGARI SIGN CANDRABINDU TWO | Lo | 5.2 | Start + continue | Devanagari Script letter meeting the XID predicate, outside the corresponding current grammar ranges. |
| U+A8F6 | ꣶ | DEVANAGARI SIGN CANDRABINDU THREE | Lo | 5.2 | Start + continue | Devanagari Script letter meeting the XID predicate, outside the corresponding current grammar ranges. |
| U+A8F7 | ꣷ | DEVANAGARI SIGN CANDRABINDU AVAGRAHA | Lo | 5.2 | Start + continue | Devanagari Script letter meeting the XID predicate, outside the corresponding current grammar ranges. |
| U+A8FB | ꣻ | DEVANAGARI HEADSTROKE | Lo | 5.2 | Start + continue | Devanagari Script letter meeting the XID predicate, outside the corresponding current grammar ranges. |
| U+A8FD | ꣽ | DEVANAGARI JAIN OM | Lo | 8.0 | Start + continue | Devanagari Script letter meeting the XID predicate, outside the corresponding current grammar ranges. |
| U+A8FE | ꣾ | DEVANAGARI LETTER AY | Lo | 11.0 | Start + continue | Devanagari Script letter meeting the XID predicate, outside the corresponding current grammar ranges. |
| U+A8FF | ◌ꣿ | DEVANAGARI VOWEL SIGN AY | Mn | 11.0 | Continue | Devanagari Script combining mark meeting the XID predicate, outside the corresponding current grammar ranges. |
| U+11B0A | 𑬊 | DEVANAGARI LETTER ALTERNATE DDDA | Lo | 18.0 | Start + continue | Devanagari Script letter meeting the XID predicate, outside the corresponding current grammar ranges. |

## Existing character newly allowed at the start

| Code point | Glyph | Unicode name | Category | Explanation |
| --- | --- | --- | --- | --- |
| U+093D | ऽ | DEVANAGARI SIGN AVAGRAHA | Lo | Already valid after the first character. XID_Start and Devanagari Script now allow it at the start too. |

## Every removed character

| Code point | Glyph | Unicode name | Category | Current position | Explanation |
| --- | --- | --- | --- | --- | --- |
| U+0953 | ◌॓ | DEVANAGARI GRAVE ACCENT | Mn | Continue | XID_Continue is true, but Script_Extensions defaults to Inherited and does not include Deva. NFC provides no equivalent replacement. |
| U+0954 | ◌॔ | DEVANAGARI ACUTE ACCENT | Mn | Continue | XID_Continue is true, but Script_Extensions defaults to Inherited and does not include Deva. NFC provides no equivalent replacement. |

No current starting characters are removed.

## Conditional additions

| Code point | Unicode name | Category | Explanation |
| --- | --- | --- | --- |
| U+200C | ZERO WIDTH NON-JOINER | Cf | Contextual exception only, after virama and before an accepted letter. Removed from identifier comparison keys after validation. |
| U+200D | ZERO WIDTH JOINER | Cf | Contextual exception only, after virama and before an accepted letter. Removed from identifier comparison keys after validation. |

These controls are valid only in the design's original-code-point sequence `accepted letter [U+093C] U+094D JOINER accepted letter`. Leading, trailing, repeated, and other placements remain invalid. Both controls are ignored in comparison keys only after the context has been validated. A shaped variant can therefore collide with a previously declared unshaped identifier. Strings retain the actual controls.

## Exact Script_Extensions evidence for the disputed cases

| Code point | XID_Start | XID_Continue | Script_Extensions |
| --- | --- | --- | --- |
| U+02BC | True | True | Beng Cyrl Deva Latn Lisu Thai Toto |
| U+0953 | False | True | Inherited (Script default) |
| U+0954 | False | True | Inherited (Script default) |
| U+20F0 | False | True | Deva Gran Latn |

## Normalization review

All 203 unconditional continuation characters pass the single-character NFC repertoire check using the pinned canonical decomposition, combining-class, and composition-exclusion data. All 41,209 ordered pairs pass the continuation-membership check. The audit code handles the accepted repertoire and does not implement general-purpose Hangul normalization. The profile contains no Hangul characters or Hangul canonical decompositions.

The current eight precomposed nukta letters remain accepted as starts and continuations. Their NFC comparison spelling expands to the following existing, accepted sequences.

| Original | Unicode name | NFC comparison spelling |
| --- | --- | --- |
| U+0958 क़ | DEVANAGARI LETTER QA | U+0915 U+093C |
| U+0959 ख़ | DEVANAGARI LETTER KHHA | U+0916 U+093C |
| U+095A ग़ | DEVANAGARI LETTER GHHA | U+0917 U+093C |
| U+095B ज़ | DEVANAGARI LETTER ZA | U+091C U+093C |
| U+095C ड़ | DEVANAGARI LETTER DDDHA | U+0921 U+093C |
| U+095D ढ़ | DEVANAGARI LETTER RHA | U+0922 U+093C |
| U+095E फ़ | DEVANAGARI LETTER FA | U+092B U+093C |
| U+095F य़ | DEVANAGARI LETTER YYA | U+092F U+093C |

U+0953 and U+0954 each normalize to themselves. Removing them has no canonical spelling workaround. The singletons and pair checks establish these repertoire properties, with full normalization conformance and runtime-JDK checks still required by the implementation milestone.

## Reproducing the comparison

The generator reads the preserved baseline grammar directly. Its start predicate is `XID_Start ∩ Deva_Script_Extensions − exclusions`. Its continuation predicate selects categories L and M from `XID_Continue ∩ Deva_Script_Extensions − exclusions`, then adds ASCII digits, Devanagari digits, and underscore. Exclusions are `Default_Ignorable_Code_Point ∪ Bidi_Control`. Explicit Script_Extensions records replace the Script default. Characters without such a record inherit their Script property.

Use the eight files listed below from the versioned Unicode directory and run from the repository root.

```sh
python scripts/audit_unicode_identifiers.py --data-dir target/unicode-18-audit --output docs/unicode-identifiers-repertoire-review.md
```

The inputs used for this report were saved from the browser-rendered plain-text files as UTF-8 text. The SHA-256 values identify those exact local input copies, including their line endings. These hashes are audit provenance and are not vendor-published checksums. A download with different encoding or line endings may have a different file hash while yielding the same character sets.

| Input | SHA-256 of local input |
| --- | --- |
| [ReadMe.txt](https://www.unicode.org/Public/18.0.0/ucd/ReadMe.txt) | `b0ea442f29dee90584aacd3665a6e1ce87ffc917a4d31f34e1916d2d6f7f9205` |
| [UnicodeData.txt](https://www.unicode.org/Public/18.0.0/ucd/UnicodeData.txt) | `0736451de439ae7baf1425136617da495e09ee5afbe6e394374db7009ea08950` |
| [DerivedCoreProperties.txt](https://www.unicode.org/Public/18.0.0/ucd/DerivedCoreProperties.txt) | `09c928886a178fcafd93c29e4bd59073a058e5a100b716d425cb563ab50f68c9` |
| [Scripts.txt](https://www.unicode.org/Public/18.0.0/ucd/Scripts.txt) | `0071fd81b6aeae25f6e8bce8efec3066a6476a91b49bdb2f52dc76e817862a6a` |
| [ScriptExtensions.txt](https://www.unicode.org/Public/18.0.0/ucd/ScriptExtensions.txt) | `5c9d34a922f687726f2a8bcf57d49f905987e51f1b21b58c95a00fbe255cec23` |
| [PropList.txt](https://www.unicode.org/Public/18.0.0/ucd/PropList.txt) | `f438f532e8737bb8a2702126cdf9c4af5e357c58c7acf9d9eb2fc7c1a1d955d6` |
| [DerivedNormalizationProps.txt](https://www.unicode.org/Public/18.0.0/ucd/DerivedNormalizationProps.txt) | `98ac7f67d985fe781e317f6182e885e94cabb0c314769e6dd73e48b226931ccd` |
| [DerivedAge.txt](https://www.unicode.org/Public/18.0.0/ucd/DerivedAge.txt) | `58b04334e3a31f9612694bbc3f6bff4a302b7d424565bca9c47e30aa74f8dbac` |

The baseline grammar SHA-256 is `b164011a7a96fdda2f38d839a2cc55b2f16abf268b9fa3b86452517466bfcae1` for the decoded grammar saved as UTF-8 with LF line endings. The comparison reads identifier fragments only. See the [approved design](unicode-identifiers-design.md) and [audit generator](../scripts/audit_unicode_identifiers.py).

## Approved follow-up

The approved profile retains U+0953/U+0954 as explicit continuation exceptions and accepts the listed additions, including U+02BC and U+20F0. Its final counts are 102 starts and 205 unconditional continuations, plus two contextual joiners. See the decision document for the final rules. The original-formula removals above remain visible as audit history.
