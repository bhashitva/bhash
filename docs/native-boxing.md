# Native सर्ववस्तु boxing and unboxing

The experimental Linux x86-64 / LLVM 18 backend supports `सर्ववस्तु` containing
`पूर्णांक` (signed 64-bit integer), `तार्किक` (boolean), `पाठ` (text), ordinary
class/interface references, and `रिक्त`. Compiled applications require neither
JVM nor .NET. The compiler still requires Java and LLVM.

Runtime failures are now catchable using [native exceptions](native-exceptions.md);
exit-1 diagnostics below describe unhandled failures.

## Language behavior

Universal values preserve the same language behavior as managed targets:

| Operation | Behavior |
| --- | --- |
| Assignment, argument, field write or return to `सर्ववस्तु` | Implicitly box a scalar; preserve an object/interface reference or null |
| `परिवर्तित<सर्ववस्तु>(व)` | Same boxing/reference behavior; an existing universal value is unchanged |
| `सुरक्षितरूप<सर्ववस्तु>(व)` | Same behavior, including scalar boxing and null preservation |
| `प्रकारहै<T>(व)` | Test exact scalar kind or nominal class/interface membership; false for null |
| `प्रकारहै<सर्ववस्तु>(व)` | True for every supported nonnull value, false for null |
| `परिवर्तित<पूर्णांक/तार्किक/पाठ>(व)` from a universal value | Exact checked unboxing; mismatch or null reports a located Hindi runtime failure and exits 1 |
| Checked class/interface cast from a universal value | Preserve matching reference identity and null; reject scalar boxes and unrelated objects |
| Safe class/interface cast from a universal value | Preserve matching reference identity; otherwise return null |
| `==` / `!=` with a universal operand | Compare integers/booleans by exact kind and value, text by content, ordinary objects by identity, null by null equality |

Conversions to a universal destination also apply in constructors, base-constructor
arguments, field initializers, and virtual/interface calls. Operands execute once
and in the existing left-to-right order. Unboxing, type checks, and reference views
allocate nothing. Boxing integers, booleans and text creates managed storage.

Unboxing is distinct from scalar conversion. Raw `परिवर्तित<पूर्णांक>("42")`
parses an integer; `परिवर्तित<पूर्णांक>(परिवर्तित<सर्ववस्तु>("42"))` fails because
the box contains text. Likewise a boxed integer cannot be unboxed as boolean or
text. A boxed integer `१` and boxed boolean `सत्य` compare unequal. Formatting or
parsing after unboxing remains available through existing scalar conversions.

Printing a universal value directly remains a checker error. Unbox or cast it to
a printable scalar first. Safe casts still require a reference destination: a
class/interface or `सर्ववस्तु`; `सुरक्षितरूप<पूर्णांक>(व)` is invalid.

## Hindi reference examples

### बॉक्सिंग (Boxing)

`सर्ववस्तु` अलग-अलग प्रकार के मूल्यों को रख सकता है। पूर्णांक, तार्किक और पाठ
देने पर संकलक अपने-आप एक प्रबंधित बॉक्स बनाता है। प्रोग्रामर को स्मृति आवंटन
या मुक्त करने का काम नहीं करना पड़ता।

```bhash
कार्य पहचान(व: सर्ववस्तु) -> सर्ववस्तु { लौटाओ व; }
कार्य मुख्य() -> शून्य {
    स्थिर अंक: सर्ववस्तु = पहचान(४२);
    स्थिर हाँ: सर्ववस्तु = सत्य;
    स्थिर शब्द: सर्ववस्तु = "नमस्ते, " + "दुनिया!";
    लिखो(परिवर्तित<पूर्णांक>(अंक), परिवर्तित<तार्किक>(हाँ), परिवर्तित<पाठ>(शब्द));
}
```

Output: `४२ सत्य नमस्ते, दुनिया!`

### अनबॉक्सिंग और प्रकार जाँच (Unboxing and type test)

`प्रकारहै` से वास्तविक प्रकार जाँचें और `परिवर्तित` से उसी प्रकार का मूल्य
निकालें। बॉक्स में रखा प्रकार नहीं बदलता। गलत प्रकार निकालने पर रनटाइम त्रुटि
मिलती है; `रिक्त` से पूर्णांक, तार्किक या पाठ नहीं निकाला जा सकता।

```bhash
कार्य मुख्य() -> शून्य {
    स्थिर व: सर्ववस्तु = "४२";
    लिखो(प्रकारहै<पाठ>(व), प्रकारहै<पूर्णांक>(व));
    लिखो(परिवर्तित<पूर्णांक>(परिवर्तित<पाठ>(व)));
    // परिवर्तित<पूर्णांक>(व) असफल होगा: बॉक्स में पाठ है।
}
```

Output: `सत्य असत्य`, then `४२` on the next line.

### वस्तु की पहचान और सुरक्षित रूपांतरण (Object identity and safe cast)

वर्ग या अनुबंध का संदर्भ `सर्ववस्तु` में रखने पर नई वस्तु नहीं बनती। रूपांतरण
के बाद भी वही मूल वस्तु मिलती है। असंगत सुरक्षित रूपांतरण `रिक्त` देता है।

```bhash
वर्ग क {}
वर्ग ख {}
कार्य मुख्य() -> शून्य {
    स्थिर मूल: क = नया क();
    स्थिर व: सर्ववस्तु = मूल;
    लिखो(व == मूल, परिवर्तित<क>(व) == मूल, सुरक्षितरूप<ख>(व) == रिक्त);
    स्थिर खाली: सर्ववस्तु = रिक्त;
    लिखो(प्रकारहै<सर्ववस्तु>(खाली), परिवर्तित<क>(खाली) == रिक्त);
}
```

Output: `सत्य सत्य सत्य`, then `असत्य सत्य` on the next line.

The runnable [boxing.bhash](../examples/native/boxing.bhash) example additionally
exercises interface references, universal fields, cyclic objects, temporary boxes,
and collector stress:

```console
java -jar target/bhash-compiler-0.1.0-SNAPSHOT.jar compile examples/native/boxing.bhash --target native -o build/boxing
BHASH_GC_STRESS=1 BHASH_GC_STATS=1 ./build/boxing
```

```text
४२ सत्य मूल😀 सत्य
मूल😀 सत्य २९९९
```

## Private representation and collector contract

Private native ABI v9 (extended by [collections](native-collections.md)) retains the ABI v3 class layout: an eight-byte immutable
`ClassInfo` pointer followed by eight-byte field slots. Ordinary class and
interface references remain payload-base pointers with unchanged identity.
`सर्ववस्तु` is a managed pointer or null, never an interior pointer or tagged word.
It must not be confused with the raw `पाठ` pointer representation.

Scalar boxes have 16-byte payloads: the metadata pointer at offset 0, followed by
an integer, zero-extended boolean, or managed text pointer at offset 8. Integer and
boolean descriptors trace no fields; text-box descriptors trace offset 8.
Immutable metadata uses reserved membership IDs -1 (integer), -2 (boolean),
-3 (text), -4 (universal), and -6 (decimal); -5 remains reserved for exceptions. User class/interface IDs remain nonnegative; class
membership includes -4. Scalar boxes expose no virtual or interface methods.
Metadata pointers are nonmanaged and never traced.

All universal locals, temporaries, parameters and results use precise writable
root slots. Universal fields appear in their owner's reference descriptor and
use the runtime reference-store barrier, as does the text pointer in a text box.
Text inputs remain rooted across box allocation and are reloaded afterward.
The new box is published in a root before its text reference is stored. Unboxed
text results are rooted before releasing their box operand. This preserves the
existing compiler/runtime contract for future relocation and generations; the
collector remains precise, stop-the-world, nonmoving mark-and-sweep.

Universal equality is a nonallocating LLVM helper: null/identity first, then exact
scalar metadata and payload comparison. Distinct ordinary objects remain unequal,
even when their classes and fields match. No global object registry or runtime
host reflection is involved.

## Scope and validation

[Arrays, lists and maps](native-collections.md) now support these universal values.
Other numeric widths, records/enums, user-defined generics, static
members and foreign interoperability remain outside the native profile.
There are no user-managed allocation/free operations and no new public C ABI.

`NativeBackendTest` compares native execution with JVM output and exit status,
including exact unboxing failures, nulls, both equality operand orders, call and
return boundaries, inheritance/interface dispatch, packages, single evaluation,
unboxed-text lifetimes, cyclic universal fields and bounded collector retention.
Linux CI verifies LLVM IR, runs native tests with sanitizers, and executes the
example under GC stress in a clean container without Java, .NET or build tools.

[Native दशमलव support](native-decimals.md) adds binary64 arithmetic, conversions, formatting, boxing and collection values.
