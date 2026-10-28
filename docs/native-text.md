# Native heap-backed text

The native Linux x86-64 target supports immutable, garbage-collected text values.
Programmers use ordinary Bhash text expressions and standard-library functions;
there is no manual allocation, release, ownership annotation or GC API in Bhash.
This extends the [native LLVM milestone](native-compilation-design.md).

## Supported operations

| API | Native behavior |
| --- | --- |
| `पाठ + पाठ` | Allocate an immutable concatenation |
| `मानक.पाठ्य.लंबाई` | Unicode scalar count |
| `मानक.पाठ्य.अंश` | Copy scalar slice `[start,end)`; validate bounds |
| `मानक.पाठ्य.खोजो` | First scalar position, or -1; empty needle returns 0 |
| `मानक.पाठ्य.है`, `आरंभ`, `अंत` | Literal contains/prefix/suffix tests |
| `मानक.पाठ्य.बदलो` | Nonoverlapping literal replacement; old text must be nonempty |
| `मानक.पाठ्य.छाँटो` | Trim the same explicit Unicode White_Space set as managed targets |
| `मानक.पाठ्य.बड़ाअक्षर`, `छोटाअक्षर` | ASCII-only case conversion; preserve other scalars |
| `मानक.पाठ्य.दोहराओ` | Repeat immutable text, preserving the existing 1,048,576 UTF-16-unit output limit |
| `मानक.पाठ्य.अंकदेवनागरी` | Replace ASCII digits with Devanagari digits |
| `अंकपाठ`, `पूर्णांकपाठ` | ASCII integer serialization and strict ASCII/Devanagari signed-64-bit parsing |
| `त्रुटि` | Print the supplied text with the Bhash source location and terminate with status 1 |

Existing global `लंबाई`, `अंश`, and `बड़ाअक्षर` aliases are supported too. Imports,
import aliases and qualified standard-library calls use the existing compiler's
resolution and signature checking. Unsupported library functions still fail clearly;
this is not complete native standard-library support.

Input, decimal formatting, byte buffers, text split/join (which require collections),
arrays and collection APIs remain outside this milestone. Ordinary classes are
covered by the subsequent [native objects milestone](native-objects.md). No grammar or
public function signatures change.

The repeat limit intentionally matches existing JVM/CLR behavior, including two
UTF-16 units per supplementary scalar. Public positions and lengths remain scalar
based. Empty text repeated any nonnegative signed-64-bit count returns empty text.
Concatenation and replacement introduce no arbitrary new length limit; allocation
arithmetic is checked and exhausted memory is a controlled fatal runtime failure.

## Hindi reference example

```bhash
आयात मानक.पाठ्य;

कार्य मुख्य() -> शून्य {
    // पाठ जोड़ना (Concatenation): परिणाम अपने अलग प्रबंधित भंडारण में रहता है।
    स्थिर संदेश: पाठ = "नमस्ते, " + "दुनिया!";
    लिखो(संदेश);
    // पाठ का अंश (Substring): स्थान यूनिकोड scalar के अनुसार गिने जाते हैं।
    लिखो(पाठ्य.अंश("a😀क", १, ३));
    // पाठ बदलना (Replace): पुराना पाठ नियमित व्यंजक नहीं है।
    लिखो(पाठ्य.बदलो("अ-ब-अ", "अ", "भाष"));
}
```

Output:

```text
नमस्ते, दुनिया!
😀क
भाष-ब-भाष
```

The [heap-text example](../examples/native/heap-text.bhash) also exercises recursive
returns, nested allocating arguments, 2,000 iterations and scope/loop exits.

```console
java -jar target/bhash-compiler-0.1.0-SNAPSHOT.jar compile examples/native/heap-text.bhash --target native -o build/heap-text
./build/heap-text
```

## Storage and rooting

Literal text stays in immutable static storage. Dynamically produced text has the
same payload layout: a 64-bit UTF-8 byte length followed by those bytes, including
embedded NUL. Its GC descriptor has no reference fields. Operations that create
text produce independent payloads; slices do not retain a larger parent buffer.
Text equality remains value-based, without Unicode normalization.

The compiler roots every managed text parameter, binding and intermediate result.
After publishing a parent result in its root slot, it releases consumed temporary
slots. Assignment protects the new binding before releasing its temporary. Print
arguments all finish evaluating before output begins. Calls reload text arguments
from root slots after evaluating other allocating arguments.

Block-local roots are cleared on scope exit. `विराम` and `जारी` clear the scopes they
leave, preserving outer bindings. Every return removes the function's root frame.
Parameters may remain rooted until function return; exact last-use analysis for
bindings that stay in scope is not implemented.

Each allocating runtime helper also roots its own arguments. It computes integer
lengths/offsets before allocation, then reloads input references and reconstructs
byte pointers afterward. No interior pointer is retained across allocation. This
preserves the compiler/runtime boundary needed for a future moving collector; the
collector remains precise, single-threaded, stop-the-world and nonmoving today.

## Diagnostics and validation

Bounds errors, invalid integer text, invalid repeat counts and empty replacement
patterns produce Hindi diagnostics at the original Bhash operation. Catchable
exceptions remain deferred on native. Malformed UTF-8 input is not a new concern in
this profile: all text originates from checked literals and operations preserving
valid UTF-8; text input/foreign text decoding are not yet exposed.

Two optional runtime diagnostics support testing and troubleshooting:

- `BHASH_GC_STRESS=1` attempts collection before every managed allocation.
- `BHASH_GC_STATS=1` emits allocation/collection counts, remaining objects after
  shutdown and peak charged heap bytes to stderr on normal termination.

They do not alter Bhash source APIs or ordinary stdout. Peak charged bytes include
Bhash allocation headers and payloads, not operating-system RSS or sanitizer costs.
The collector still uses a linear allocation lookup; these correctness tests do not
establish performance for large object graphs or a low-latency GC guarantee.

Native tests compare real program output with JVM output under forced collection.
They check recursion, nested calls, embedded NUL, supplementary/combining characters,
integer boundaries, scope cleanup on loop jumps and bounded peak heap usage. C runtime
tests run at debug/optimized levels with sanitizers in Linux CI. Native integration
builds also enable ASan/UBSan; ordinary CLI builds do not depend on those sanitizers.
The clean Ubuntu deployment test runs the heap-text program without JVM/.NET/build
tools and with container networking disabled.

Local Windows development can additionally compile generated LLVM IR with the
installed host Clang and run this ABI-compatible subset as a test probe. That is
not supported Windows CLI output: the public native target remains Linux x86-64
with LLVM 18, and Linux CI remains the platform acceptance check.

## Recorded milestone validation

The [first Linux heap-text acceptance run](https://github.com/bhashitva/bhash/actions/runs/40628334816)
passed all 15 native tests with no skips, including ASan/UBSan builds and clean Ubuntu
container execution. The container example recorded 14,020 allocations, 14,021
collections, zero remaining managed objects and a 90,194-byte peak charged heap.

A separate host LLVM 23 test matched the JVM's output. With stress disabled, ordinary
allocation-pressure collection also ran automatically: 14,020 allocations, two
collections and zero objects remaining. The developer probe does not change the
supported Linux target or make the compiler independent of Java.
