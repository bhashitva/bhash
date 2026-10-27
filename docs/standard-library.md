# Bhash standard library — first edition

For Hindi explanations and runnable examples of every function and overload, see the
[standard-library tutorials](../tutorial/standard-library/README.md).

The `मानक` package is bundled with the compiler and available to single-file and
multi-file programs on JVM, .NET CLR and browser .NET WebAssembly. Compiled programs
include their runtime support; they do not download Bhash source packages, Maven
libraries or NuGet packages at execution time. Ordinary imports, aliases, cross-file
checking, VS Code completion, signatures and F12 work for the library.

```bhash
आयात मानक.पाठ्य;
आयात मानक.जेसन;
कार्य मुख्य() -> शून्य {
    स्थिर शब्द: सूची<पाठ> = पाठ्य.बाँटो("भाष,भारत,ज्ञान", ",");
    लिखो(पाठ्य.जोड़ो(शब्द, " · "));
    लिखो(जेसन.पाठबनाओ(शब्द));
}
```

Output:

```text
भाष · भारत · ज्ञान
["भाष","भारत","ज्ञान"]
```

Fully qualified calls such as `मानक.गणित.वर्गमूल(२.०)` also work. In playground
Statements mode, use fully qualified names; imports belong to Full program mode.
Library classes contain static methods and cannot be instantiated or redefined.
The catalog reserves the six listed fully qualified class names, not all ordinary
Devanagari names. Existing globals such as `लंबाई`, `अंश`, `पाठपढ़ो` and conversions
retain their existing behavior.

## Portable contracts

- **Text:** ordinal matching and literal separators, with Unicode scalar positions
  for search/slicing/length. Combining marks count separately; these are not visual
  grapheme clusters. Text is not implicitly normalized. Case conversion is ASCII
  only, matching the existing `बड़ाअक्षर` contract. Trimming uses the Unicode
  White_Space set explicitly, including NBSP; it differs from older console trimming.
- **Numbers:** math inputs/results must be finite. Domain errors and integer absolute
  value overflow are catchable. There are no implicit integer-to-real promotions:
  pass `२.०` or an explicit conversion to a real-valued API. Transcendental functions
  can differ in their last binary64 bits between host runtimes; compare with a tolerance.
- **Fixed formatting:** `संख्यापाठ(value, places)` produces ASCII digits, a `.` decimal
  point, and exactly 0–15 fractional places. It rounds the *stored binary64 value*
  to nearest, ties to even, using integer arithmetic on every target. For example,
  `०.१२५` at 2 places is `0.12`, and `०.३७५` is `0.38`. Negative zero retains its sign.
  This is formatting, not an exact decimal arithmetic type. Use `अंकदेवनागरी` for display.
- **Randomness:** SplitMix64 with explicit signed 64-bit seeds gives the same sequence
  on all targets. `पूर्णांकचुनो(lower, upper)` uses an inclusive lower and exclusive
  upper bound, unbiased rejection sampling, and requires a positive span no greater
  than signed 64-bit maximum. `अंश()` is in `[0,1)`. State is per running program;
  the default seed is unspecified. This generator is for simulation/games, not secrets.
- **Time:** epoch milliseconds and Gregorian UTC only. Formatting/parsing uses exactly
  `yyyy-MM-ddTHH:mm:ss.SSSZ`, ASCII digits, years 0001–9999, with no locale or local
  timezone dependence. Invalid dates, offsets, omitted milliseconds and leap seconds
  are rejected. Clock values come from the host clock, not a monotonic timer.
- **Failures:** invalid arguments, malformed data and ordinary I/O failures raise
  catchable `अपवाद` values. This edition does not introduce filesystem-specific
  exception subclasses. Fatal host allocation/process failures remain outside that contract.

## API reference

All methods below are static. Parameter types and results are checked before a
backend toolchain is invoked. The Bhash catalog is shared by the compiler and IDE.

### मानक.पाठ्य

| Method | Parameters | Result |
| --- | --- | --- |
| `लंबाई` | `मानक: पाठ` | `पूर्णांक` |
| `अंश` | `मानक: पाठ, आरंभ: पूर्णांक, अंत: पूर्णांक` | `पाठ` |
| `खोजो` | `मानक: पाठ, खोज: पाठ` | `पूर्णांक` |
| `है` | `मानक: पाठ, खोज: पाठ` | `तार्किक` |
| `आरंभ` | `मानक: पाठ, खोज: पाठ` | `तार्किक` |
| `अंत` | `मानक: पाठ, खोज: पाठ` | `तार्किक` |
| `बदलो` | `मानक: पाठ, पुराना: पाठ, नवीन: पाठ` | `पाठ` |
| `बाँटो` | `मानक: पाठ, विभाजक: पाठ` | `सूची<पाठ>` |
| `जोड़ो` | `मानक: सूची<पाठ>, विभाजक: पाठ` | `पाठ` |
| `छाँटो` | `मानक: पाठ` | `पाठ` |
| `बड़ाअक्षर` | `मानक: पाठ` | `पाठ` |
| `छोटाअक्षर` | `मानक: पाठ` | `पाठ` |
| `दोहराओ` | `मानक: पाठ, गिनती: पूर्णांक` | `पाठ` |
| `संख्यापाठ` | `मानक: दशमलव, स्थान: पूर्णांक` | `पाठ` |
| `अंकदेवनागरी` | `मानक: पाठ` | `पाठ` |
| `यूटीएफ८` | `मानक: पाठ` | `बाइटबफ़र` |
| `यूटीएफ८पाठ` | `मानक: बाइटबफ़र` | `पाठ` |

### मानक.गणित

| Method | Parameters | Result |
| --- | --- | --- |
| `निरपेक्ष` | `मानक: पूर्णांक` | `पूर्णांक` |
| `निरपेक्ष` | `मानक: दशमलव` | `दशमलव` |
| `न्यूनतम` | `क: पूर्णांक, ख: पूर्णांक` | `पूर्णांक` |
| `न्यूनतम` | `क: दशमलव, ख: दशमलव` | `दशमलव` |
| `अधिकतम` | `क: पूर्णांक, ख: पूर्णांक` | `पूर्णांक` |
| `अधिकतम` | `क: दशमलव, ख: दशमलव` | `दशमलव` |
| `वर्गमूल` | `मानक: दशमलव` | `दशमलव` |
| `घात` | `आधारक: दशमलव, घातक: दशमलव` | `दशमलव` |
| `फर्श` | `मानक: दशमलव` | `दशमलव` |
| `छत` | `मानक: दशमलव` | `दशमलव` |
| `निकटतम` | `मानक: दशमलव` | `दशमलव` |
| `ज्या` | `मानक: दशमलव` | `दशमलव` |
| `कोज्या` | `मानक: दशमलव` | `दशमलव` |
| `लघुगणक` | `मानक: दशमलव` | `दशमलव` |
| `घातांक` | `मानक: दशमलव` | `दशमलव` |
| `पाई` | `—` | `दशमलव` |

### मानक.यादृच्छिक

| Method | Parameters | Result |
| --- | --- | --- |
| `बीज` | `मानक: पूर्णांक` | `शून्य` |
| `पूर्णांकचुनो` | `निम्न: पूर्णांक, ऊपरी: पूर्णांक` | `पूर्णांक` |
| `अंश` | `—` | `दशमलव` |

### मानक.जेसन

| Method | Parameters | Result |
| --- | --- | --- |
| `पढ़ो` | `मानक: पाठ` | `सर्ववस्तु` |
| `पाठबनाओ` | `मानक: सर्ववस्तु` | `पाठ` |

### मानक.समय

| Method | Parameters | Result |
| --- | --- | --- |
| `अभी` | `—` | `पूर्णांक` |
| `पढ़ो` | `मानक: पाठ` | `पूर्णांक` |
| `पाठबनाओ` | `मानक: पूर्णांक` | `पाठ` |
| `वर्ष` | `मानक: पूर्णांक` | `पूर्णांक` |
| `माह` | `मानक: पूर्णांक` | `पूर्णांक` |
| `दिन` | `मानक: पूर्णांक` | `पूर्णांक` |

### मानक.फ़ाइल

| Method | Parameters | Result |
| --- | --- | --- |
| `समर्थित` | `—` | `तार्किक` |
| `है` | `पथ: पाठ` | `तार्किक` |
| `पाठपढ़ो` | `पथ: पाठ` | `पाठ` |
| `पाठलिखो` | `पथ: पाठ, मानक: पाठ` | `शून्य` |
| `बाइटपढ़ो` | `पथ: पाठ` | `बाइटबफ़र` |
| `बाइटलिखो` | `पथ: पाठ, मानक: बाइटबफ़र` | `शून्य` |

### Text details

`खोजो` returns a scalar index, or `-१` when absent; an empty search matches at 0.
`आरंभ`, `अंत` and `है` are ordinal prefix/suffix/contains tests. `बाँटो` and `बदलो`
require a nonempty literal separator/search; no regex syntax is involved. Splitting
retains empty fields, including trailing ones. `जोड़ो` accepts `सूची<पाठ>`; use a
list rather than an array. `दोहराओ` requires a nonnegative count and caps its result
at 1,048,576 UTF-16 code units; repeating empty text returns empty text.
UTF-8 conversion is strict and writes no BOM. A BOM present in input data is preserved.

### JSON data model

`जेसन.पढ़ो` returns `सर्ववस्तु`. Values are represented as follows:

| JSON | Bhash runtime type |
| --- | --- |
| object | `मानचित्र<पाठ,सर्ववस्तु>` |
| array | `सूची<सर्ववस्तु>` |
| string | `पाठ` |
| integer lexeme | `पूर्णांक`, with checked signed 64-bit range |
| fraction/exponent lexeme | finite binary64 `दशमलव` |
| boolean | `तार्किक` |
| null | `रिक्त` |

Nested collections carry the normal Bhash runtime type tags. Use explicit casts:

```bhash
स्थिर मूल: सर्ववस्तु = मानक.जेसन.पढ़ो("{\"नाम\":\"राधा\",\"अंक\":[1,2]}");
स्थिर छात्र: मानचित्र<पाठ,सर्ववस्तु> = परिवर्तित<मानचित्र<पाठ,सर्ववस्तु>>(मूल);
स्थिर अंक: सूची<सर्ववस्तु> = परिवर्तित<सूची<सर्ववस्तु>>(छात्र["अंक"]);
लिखो(परिवर्तित<पाठ>(छात्र["नाम"]), परिवर्तित<पूर्णांक>(अंक[०]));
```

Serialization accepts null, strings, booleans, Bhash numeric scalars, arrays,
lists, byte buffers and maps with text keys. Records, enums and ordinary class
instances require explicit conversion to these values. Map insertion order is
preserved. Shared child collections are allowed; cycles raise `अपवाद`.

The reader rejects trailing tokens, trailing commas, duplicate object keys,
non-ASCII JSON number digits, non-finite/out-of-range numbers and malformed Unicode
(including unpaired surrogate escapes). Input and serialized output are each
limited to 1,048,576 UTF-16 code units; recursive depth is limited to 64. The writer
retains a decimal/exponent marker for real values so `1.0` reads back as `दशमलव`.
Floating-point JSON spelling is not a canonical cross-target byte format; values
round-trip through each host's round-trip binary64 rendering. Full 64-bit integers
remain exact in Bhash; external JavaScript-number consumers may lose precision.

### File access and browser capability

`फ़ाइल.समर्थित()` is true on JVM/CLR and false in browser WASM. Every other file
operation raises `अपवाद` on the browser **before any filesystem access**. Browser
programs do not gain access to the compilation server's filesystem. A browser file
picker/download API is a separate future feature.

Desktop operations use the program's working directory and OS permissions, and
follow ordinary OS path/symlink semantics. `है` tests regular files, not directories.
It returns false for absent/inaccessible files according to the OS; invalid paths
raise `अपवाद`. Parent directories are not created automatically.

Reads and writes are bounded to **16 MiB**. Text uses strict UTF-8, with no automatic
BOM writing or newline translation. Writes create or replace the destination;
validation failures such as an oversized buffer leave the old file intact. An
underlying I/O failure can still leave a partial write: this is not a transactional
or atomic-replacement API. Streams are closed by the runtime on success or failure.

```bhash
यदि (मानक.फ़ाइल.समर्थित()) {
    प्रयास {
        मानक.फ़ाइल.पाठलिखो("नमूना.txt", "नमस्ते😀");
        लिखो(मानक.फ़ाइल.पाठपढ़ो("नमूना.txt"));
    } पकड़ो (गलती: अपवाद) {
        लिखो(गलती.संदेश);
    }
}
```

## Run and learn

```sh
java -jar target/bhash-compiler-0.1.0-SNAPSHOT.jar run examples/standard-library/portable.bhash
java -jar target/bhash-compiler-0.1.0-SNAPSHOT.jar run examples/standard-library/portable.bhash --target clr
java -jar target/bhash-compiler-0.1.0-SNAPSHOT.jar compile examples/standard-library/portable.bhash --target wasm-browser -o target/standard-web
python -m http.server 8000 --directory target/standard-web
```

The playground has a **मानक पुस्तकालय · Standard library** example. The desktop
read-only example `examples/standard-library/files.bhash` accepts a UTF-8 file path
from the console. Use the usual .NET 10 + wasm-tools prerequisites for browser builds.

| Bhash concept | Familiar Java/C# concept |
| --- | --- |
| `आयात मानक.पाठ्य;` | import/use a standard-library type |
| `पाठ्य.बाँटो` / `जोड़ो` | literal split / string join |
| `गणित.वर्गमूल` | `Math.sqrt` / `Math.Sqrt` |
| `यादृच्छिक.बीज` | seed a pseudorandom sequence |
| `जेसन.पढ़ो` / `पाठबनाओ` | JSON parse / serialize |
| `समय.अभी` | UTC Unix epoch milliseconds |
| `फ़ाइल.पाठपढ़ो` / `पाठलिखो` | strict UTF-8 file read / write |

This first edition does not add external dependency management, arbitrary host
library interop, exact decimal arithmetic, networking, streaming I/O, local time
zones, Unicode case folding or asynchronous APIs. Those remain independent roadmap
items. Public library declarations are compiler-owned; applications can use normal
packages alongside them, and compiled applications carry the library runtime.
