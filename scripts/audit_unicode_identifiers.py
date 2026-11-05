#!/usr/bin/env python3
"""Generate the proposed identifier repertoire review from local Unicode 18 data."""
import argparse
import hashlib
import re
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
FILES = (
    "ReadMe.txt", "UnicodeData.txt", "DerivedCoreProperties.txt", "Scripts.txt",
    "ScriptExtensions.txt", "PropList.txt", "DerivedNormalizationProps.txt", "DerivedAge.txt",
)
BASE = "https://www.unicode.org/Public/18.0.0/ucd/"


def records(path):
    for line in path.read_text(encoding="utf-8-sig").splitlines():
        line = line.split("#", 1)[0].strip()
        if not line:
            continue
        code, *fields = (item.strip() for item in line.split(";"))
        ends = code.split("..")
        yield range(int(ends[0], 16), int(ends[-1], 16) + 1), fields


def properties(path, wanted=None):
    result = {}
    for points, fields in records(path):
        if wanted is None or fields[0] in wanted:
            result.setdefault(fields[0], set()).update(points)
    return result


def grammar_set(text, fragment):
    match = re.search(r"fragment " + fragment + r"\s*:\s*\[([^]]+)\]", text)
    if match is None:
        raise ValueError("Cannot locate grammar fragment " + fragment)
    if match[1] == "0-9":
        return set(range(0x30, 0x3A))
    ranges = re.findall(r"\\u([0-9A-Fa-f]{4})(?:-\\u([0-9A-Fa-f]{4}))?", match[1])
    if not ranges:
        raise ValueError("Cannot read grammar fragment " + fragment)
    return {point for first, last in ranges
            for point in range(int(first, 16), int(last or first, 16) + 1)}


def code(point):
    return f"U+{point:04X}"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--data-dir", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    data = args.data_dir
    for filename in FILES:
        text = (data / filename).read_text(encoding="utf-8-sig")
        if filename != "UnicodeData.txt" and "18.0.0" not in text[:2500]:
            raise ValueError("Unicode version header missing in " + filename)
        if filename not in ("ReadMe.txt", "UnicodeData.txt") and "# EOF" not in text[-300:]:
            raise ValueError("Unicode file is incomplete: " + filename)

    chars = {}
    for line in (data / "UnicodeData.txt").read_text(encoding="utf-8-sig").splitlines():
        fields = line.split(";")
        chars[int(fields[0], 16)] = fields
    core = properties(data / "DerivedCoreProperties.txt",
                      {"XID_Start", "XID_Continue", "Default_Ignorable_Code_Point"})
    scripts = properties(data / "Scripts.txt", {"Devanagari"})
    extensions = {}
    for points, fields in records(data / "ScriptExtensions.txt"):
        for point in points:
            extensions[point] = set(fields[0].split())
    deva = scripts["Devanagari"] - extensions.keys()
    deva.update(point for point, values in extensions.items() if "Deva" in values)
    exclusions = core["Default_Ignorable_Code_Point"] | properties(data / "PropList.txt", {"Bidi_Control"})["Bidi_Control"]
    digits = set(range(0x30, 0x3A)) | set(range(0x966, 0x970))
    new_start = (core["XID_Start"] & deva) - exclusions
    new_continue = {point for point in (core["XID_Continue"] & deva) - exclusions
                    if chars[point][2][0] in "LM"} | digits | {0x5F}
    assert new_start <= new_continue

    grammar = (ROOT / "docs/identifier-profile-baseline.g4").read_text(encoding="utf-8")
    old_start = grammar_set(grammar, "DEV_LETTER")
    old_continue = (old_start | grammar_set(grammar, "DEV_MARK")
                    | grammar_set(grammar, "ASCII_DIGIT") | grammar_set(grammar, "DEV_DIGIT") | {0x5F})
    additions = new_continue - old_continue
    removals = old_continue - new_continue
    promotions = (new_start - old_start) & old_continue
    joining = {0x200C, 0x200D}
    changes = additions | removals | promotions | joining
    ages = {}
    for points, fields in records(data / "DerivedAge.txt"):
        for point in changes.intersection(points):
            ages[point] = fields[0]

    def scx(point):
        return " ".join(sorted(extensions[point])) if point in extensions else (
            "Deva (Script default)" if point in scripts["Devanagari"] else "Inherited (Script default)")

    def reason(point):
        if point in joining:
            return "Contextual exception only, after virama and before an accepted letter. Removed from identifier comparison keys after validation."
        if point in removals:
            return "XID_Continue is true, but Script_Extensions defaults to Inherited and does not include Deva. NFC provides no equivalent replacement."
        if point in promotions:
            return "Already valid after the first character. XID_Start and Devanagari Script now allow it at the start too."
        if point == 0x2BC:
            return "XID_Start modifier letter with Deva in its explicit Script_Extensions. This is U+02BC, distinct from ASCII apostrophe U+0027."
        if point == 0x20F0:
            return "XID_Continue combining mark with Deva in its explicit Script_Extensions. ASCII asterisk U+002A remains an operator."
        kind = "letter" if point in new_start else "combining mark"
        if point in scripts["Devanagari"]:
            return f"Devanagari Script {kind} meeting the XID predicate, outside the corresponding current grammar ranges."
        return f"Shared Vedic {kind} meeting the XID predicate, with Deva explicitly present in Script_Extensions."

    out = [
        "# Identifier repertoire changes for review", "",
        "Status — historical formula comparison. The [approved decisions](unicode-identifiers-decisions.md) retain both removed accents as explicit exceptions.", "",
        "Date — 5 November 2026.", "",
        "This report applies the original design formula to Unicode 18.0.0 and compares it with the preserved pre-change lexer fragments in `identifier-profile-baseline.g4`. The machine-readable predicates and generation procedure are included below. Every affected code point appears in a table. Code points provide the identity when fonts cannot render a character.", "",
        "## Counts", "",
        "| Position | Current | Proposed unconditional | Added | Removed |",
        "| --- | --- | --- | --- | --- |",
        f"| First character | {len(old_start)} | {len(new_start)} | {len(new_start - old_start)} | {len(old_start - new_start)} |",
        f"| Subsequent character | {len(old_continue)} | {len(new_continue)} | {len(additions)} | {len(removals)} |", "",
        f"The proposed profile newly accepts {len(additions)} code points in identifiers and promotes {len(promotions)} already accepted character to starting position. It removes {len(removals)} currently accepted characters. U+200C and U+200D are two additional conditional continuations, excluded from the unconditional counts. Starting characters also belong to the continuation set, so position counts must not be added together.", "",
        "## Cases requiring attention", "",
        "- U+0953 DEVANAGARI GRAVE ACCENT and U+0954 DEVANAGARI ACUTE ACCENT would become invalid in identifiers. Both are currently allowed as continuation marks. Their Unicode names contain DEVANAGARI, but their Script_Extensions value lacks Deva. This would reject an existing spelling even after NFC normalization.",
        "- U+02BC MODIFIER LETTER APOSTROPHE would become valid at the start and within identifiers because Deva occurs in its Script_Extensions. Its glyph can resemble punctuation.",
        "- U+20F0 COMBINING ASTERISK ABOVE would become valid within identifiers through the same shared-script rule.",
        "- The Vedic and extended Devanagari additions follow the approved repertoire formula. They broaden identifiers beyond characters commonly used for modern Hindi.",
        "- U+11B0A is supplementary and was introduced in Unicode " + ages.get(0x11B0A, "unknown") + ". Its acceptance requires correct surrogate-pair handling in editors and normalization checks against supported JDKs.", "",
        "My recommendation is to review explicit continuation exceptions for U+0953/U+0954 to preserve existing accepted spellings. Decide separately whether U+02BC and U+20F0 fit the intended language repertoire. The counts above describe the approved formula before any such exceptions. Retaining both accents would make the unconditional continuation count 205. Excluding the apostrophe subtracts one from both start and continuation counts. Excluding the combining asterisk subtracts one from the continuation count.", "",
        "## Every newly accepted character", "",
        "`Start + continue` means the code point is newly accepted in both positions. `Continue` means it remains invalid as the first character. For Mn and Mc marks the glyph is displayed on a dotted circle, which is illustrative and is not part of the identifier. Lo means other letter, Lm modifier letter, Mn nonspacing mark, Mc spacing combining mark, and Cf format character.", "",
        "| Code point | Glyph | Unicode name | Category | Introduced | New positions | Explanation |",
        "| --- | --- | --- | --- | --- | --- | --- |",
    ]
    for point in sorted(additions):
        fields = chars[point]
        glyph = ("◌" if fields[2][0] == "M" else "") + chr(point)
        positions = "Start + continue" if point in new_start else "Continue"
        out.append(f"| {code(point)} | {glyph} | {fields[1]} | {fields[2]} | {ages[point]} | {positions} | {reason(point)} |")
    out.extend(["", "## Existing character newly allowed at the start", "",
                "| Code point | Glyph | Unicode name | Category | Explanation |",
                "| --- | --- | --- | --- | --- |"])
    for point in sorted(promotions):
        out.append(f"| {code(point)} | {chr(point)} | {chars[point][1]} | {chars[point][2]} | {reason(point)} |")
    out.extend(["", "## Every removed character", "",
                "| Code point | Glyph | Unicode name | Category | Current position | Explanation |",
                "| --- | --- | --- | --- | --- | --- |"])
    for point in sorted(removals):
        out.append(f"| {code(point)} | ◌{chr(point)} | {chars[point][1]} | {chars[point][2]} | Continue | {reason(point)} |")
    out.extend(["", "No current starting characters are removed.", "", "## Conditional additions", "",
                "| Code point | Unicode name | Category | Explanation |", "| --- | --- | --- | --- |"])
    for point in sorted(joining):
        out.append(f"| {code(point)} | {chars[point][1]} | {chars[point][2]} | {reason(point)} |")
    out.extend(["", "These controls are valid only in the design's original-code-point sequence `accepted letter [U+093C] U+094D JOINER accepted letter`. Leading, trailing, repeated, and other placements remain invalid. Both controls are ignored in comparison keys only after the context has been validated. A shaped variant can therefore collide with a previously declared unshaped identifier. Strings retain the actual controls.", "",
                "## Exact Script_Extensions evidence for the disputed cases", "",
                "| Code point | XID_Start | XID_Continue | Script_Extensions |",
                "| --- | --- | --- | --- |"])
    for point in (0x2BC, 0x953, 0x954, 0x20F0):
        out.append(f"| {code(point)} | {point in core['XID_Start']} | {point in core['XID_Continue']} | {scx(point)} |")

    # Canonical decomposition plus composition uses the pinned UCD, independently of Python's Unicode version.
    full_exclusions = properties(data / "DerivedNormalizationProps.txt", {"Full_Composition_Exclusion"})["Full_Composition_Exclusion"]
    decomposition = {point: tuple(int(value, 16) for value in fields[5].split())
                     for point, fields in chars.items() if fields[5] and not fields[5].startswith("<")}
    compositions = {parts: point for point, parts in decomposition.items()
                    if len(parts) == 2 and point not in full_exclusions}

    def expand(point):
        if point in decomposition:
            return [child for part in decomposition[point] for child in expand(part)]
        return [point]

    def ccc(point):
        return int(chars[point][3])

    def nfc(points):
        ordered = []
        for point in points:
            for part in expand(point):
                ordered.append(part)
                index = len(ordered) - 1
                while index and ccc(part) and ccc(ordered[index - 1]) > ccc(part):
                    ordered[index], ordered[index - 1] = ordered[index - 1], ordered[index]
                    index -= 1
        result = []
        starter = None
        last_class = 0
        for point in ordered:
            current_class = ccc(point)
            combined = compositions.get((result[starter], point)) if starter is not None else None
            if combined is not None and (last_class == 0 or last_class < current_class):
                result[starter] = combined
            else:
                result.append(point)
                if current_class == 0:
                    starter = len(result) - 1
                last_class = current_class
        return result

    assert nfc([0x958]) == [0x915, 0x93C]
    closure_failures = []
    for point in sorted(new_continue):
        normalized = nfc([point])
        if not set(normalized) <= new_continue or (point in new_start and normalized[0] not in new_start):
            closure_failures.append(code(point))
    assert not closure_failures, closure_failures
    pair_failures = []
    for left in sorted(new_continue):
        for right in sorted(new_continue):
            if not set(nfc([left, right])) <= new_continue:
                pair_failures.append((code(left), code(right)))
    assert not pair_failures, pair_failures
    out.extend(["", "## Normalization review", "",
                f"All {len(new_continue)} unconditional continuation characters pass the single-character NFC repertoire check using the pinned canonical decomposition, combining-class, and composition-exclusion data. All {len(new_continue) ** 2:,} ordered pairs pass the continuation-membership check. The audit code handles the accepted repertoire and does not implement general-purpose Hangul normalization. The profile contains no Hangul characters or Hangul canonical decompositions.", "",
                "The current eight precomposed nukta letters remain accepted as starts and continuations. Their NFC comparison spelling expands to the following existing, accepted sequences.", "",
                "| Original | Unicode name | NFC comparison spelling |", "| --- | --- | --- |"])
    for point in range(0x958, 0x960):
        out.append(f"| {code(point)} {chr(point)} | {chars[point][1]} | {' '.join(code(p) for p in nfc([point]))} |")
    out.extend(["", "U+0953 and U+0954 each normalize to themselves. Removing them has no canonical spelling workaround. The singletons and pair checks establish these repertoire properties, with full normalization conformance and runtime-JDK checks still required by the implementation milestone.", "",
                "## Reproducing the comparison", "",
                "The generator reads the preserved baseline grammar directly. Its start predicate is `XID_Start ∩ Deva_Script_Extensions − exclusions`. Its continuation predicate selects categories L and M from `XID_Continue ∩ Deva_Script_Extensions − exclusions`, then adds ASCII digits, Devanagari digits, and underscore. Exclusions are `Default_Ignorable_Code_Point ∪ Bidi_Control`. Explicit Script_Extensions records replace the Script default. Characters without such a record inherit their Script property.", "",
                "Use the eight files listed below from the versioned Unicode directory and run from the repository root.", "",
                "```sh", "python scripts/audit_unicode_identifiers.py --data-dir target/unicode-18-audit --output docs/unicode-identifiers-repertoire-review.md", "```", "",
                "The inputs used for this report were saved from the browser-rendered plain-text files as UTF-8 text. The SHA-256 values identify those exact local input copies, including their line endings. These hashes are audit provenance and are not vendor-published checksums. A download with different encoding or line endings may have a different file hash while yielding the same character sets.", "",
                "| Input | SHA-256 of local input |", "| --- | --- |"])
    for filename in FILES:
        digest = hashlib.sha256((data / filename).read_bytes()).hexdigest()
        out.append(f"| [{filename}]({BASE}{filename}) | `{digest}` |")
    out.extend(["", "The baseline grammar SHA-256 is `" + hashlib.sha256(grammar.encode("utf-8")).hexdigest() + "` for the decoded grammar saved as UTF-8 with LF line endings. The comparison reads identifier fragments only. See the [approved design](unicode-identifiers-design.md) and [audit generator](../scripts/audit_unicode_identifiers.py).", "",
                "## Approved follow-up", "",
                "The approved profile retains U+0953/U+0954 as explicit continuation exceptions and accepts the listed additions, including U+02BC and U+20F0. Its final counts are 102 starts and 205 unconditional continuations, plus two contextual joiners. See the decision document for the final rules. The original-formula removals above remain visible as audit history.", ""])
    args.output.write_text("\n".join(out), encoding="utf-8")
    print(f"Wrote {args.output}: starts {len(old_start)} -> {len(new_start)}, continuations {len(old_continue)} -> {len(new_continue)}, plus two contextual joiners")


if __name__ == "__main__":
    main()
