# Native arrays, lists and maps

The experimental Linux x86-64 / LLVM 18 target now supports Bhash arrays, growable
lists, insertion-ordered hash maps, and `प्रत्येक` iteration. Applications run
without JVM or .NET. Java and LLVM remain build-time requirements.

This follows [native universal values](native-boxing.md). The existing
[collection language rules and API reference](collections.md) apply, within the
native element-type profile below.

## Supported types and operations

- Elements, keys and values: signed 64-bit `पूर्णांक`, `तार्किक`, `पाठ`, class and
  interface references, `सर्ववस्तु`, and nested supported arrays/lists/maps.
- Fixed arrays: sized allocation, typed initializers, `सरणी<त>` aliases, rectangular
  multidimensional allocation, jagged rows, empty arrays and existing defaults.
- Arrays and lists: indexing, `लो`, `रखो`, `है`, `खाली`, `आकार` and `लंबाई`.
- Lists additionally support `जोड़ो`, `डालो`, `हटाओ` and `साफ़`.
- Maps: typed entry initializers, bracket reads/writes, `लो` with an optional
  eagerly evaluated fallback, `रखो`, `है`, `हटाओ`, `साफ़`, `खाली`, size operations,
  and independent shallow `कुंजियाँ()` / `मूल्य()` snapshots.
- All collections expose read-only `.आकार` and `.लंबाई`, and global `लंबाई(...)`.
- One iteration binding visits array/list elements or map keys; two bindings visit
  map key/value pairs. Bindings are immutable, with ordinary element upcasts and
  implicit scalar boxing when their declared type is `सर्ववस्तु`.
- Collections work in fields, parameters, results and constructors, across files,
  packages, class inheritance, virtual methods and interfaces.

Collection types remain invariant, including nested types and snapshot results.
`पूर्णांक[]` and `सरणी<पूर्णांक>` are identical. Runtime tests and safe/checked
casts through `सर्ववस्तु` retain the complete collection type, not merely the outer
array/list/map kind. Ordinary collection equality and collection keys use identity;
contents are not compared recursively. Null remains a valid collection reference.

Negative/oversized lengths, invalid indices, null receivers, null map keys, missing
map keys, and invalid casts report located Hindi diagnostics and exit 1. Lengths
retain the existing range 0..2,147,483,647; available memory further limits actual
allocation. All dimension expressions execute once, left to right, and all sizes
are validated before rectangular allocation begins, even when an outer size is zero.

Receivers are checked for null before index/argument evaluation. Writes evaluate
receiver, index/key, and value before checking bounds or performing the update,
matching managed execution. Literal entries and fallback arguments are evaluated
eagerly in their existing order. A failed print argument does not print partial
output from the surrounding `लिखो` call.

Array iteration observes current elements. List/map iteration checks a captured
structural version before every advance, including the final end check. Appending,
inserting, removing, or clearing a nonempty collection invalidates iteration.
Replacing a list item or an existing map value is allowed. Breaking or returning
immediately after mutation does not advance again and therefore does not fail.

## Hindi reference examples

### सरणी (Array)

सरणी की लंबाई बनने के बाद नहीं बदलती। सूचकांक शून्य से शुरू होता है। नई पूर्णांक
सरणी में `०`, तार्किक सरणी में `असत्य`, पाठ सरणी में खाली पाठ और संदर्भ सरणी में
`रिक्त` मिलता है। प्रत्येक बहुआयामी पंक्ति अलग सरणी होती है।

```bhash
कार्य मुख्य() -> शून्य {
    स्थिर जाल: पूर्णांक[][] = नया पूर्णांक[२][३];
    जाल[०][१] = ४२;
    लिखो(जाल[०][१], जाल[१][१], जाल.लंबाई);
    स्थिर शब्द: पाठ[] = नया पाठ[]{"अ", "ब"};
    प्रत्येक (शब्दांश: पाठ में शब्द) { लिखो(शब्दांश); }
}
```

Output: `४२ ० २`, followed by `अ` and `ब` on separate lines.

### सूची (List)

सूची में नए मूल्य जोड़े जा सकते हैं और पुराने मूल्य हटाए जा सकते हैं। `डालो`
दिए गए सूचकांक से पहले मूल्य डालता है। `हटाओ` हटाया गया मूल्य लौटाता है। संग्रह
में रखे गए पाठ और वस्तु संदर्भ अपने-आप कचरा संग्रहण द्वारा सुरक्षित रहते हैं।

```bhash
कार्य मुख्य() -> शून्य {
    स्थिर अंक: सूची<पूर्णांक> = नया सूची<पूर्णांक>{१,३};
    अंक.डालो(१,२); अंक.जोड़ो(४);
    लिखो(अंक.हटाओ(०), अंक.आकार);
    प्रत्येक (न: पूर्णांक में अंक) { लिखो(न); }
}
```

Output: `१ ३`, followed by `२`, `३`, and `४` on separate lines.

### मानचित्र (Map)

मानचित्र हर कुंजी को एक मूल्य से जोड़ता है। नई कुंजियाँ डालने के क्रम में रहती
हैं। पुरानी कुंजी का मूल्य बदलने पर उसका स्थान नहीं बदलता। `लो` का दूसरा तर्क
वैकल्पिक मूल्य है; बिना उसके, अनजान कुंजी पढ़ने पर त्रुटि मिलती है।

```bhash
कार्य मुख्य() -> शून्य {
    स्थिर अंक: मानचित्र<पाठ,पूर्णांक> = नया मानचित्र<पाठ,पूर्णांक>{"एक":१,"दो":२};
    अंक["दो"] = २०; अंक["तीन"] = ३;
    लिखो(अंक.लो("अनजान",०));
    प्रत्येक (कुंजी: पाठ, मानक: पूर्णांक में अंक) { लिखो(कुंजी,मानक); }
    स्थिर चित्र: सूची<पाठ> = अंक.कुंजियाँ(); अंक.साफ़();
    लिखो(चित्र.आकार, अंक.खाली());
}
```

Output: `०`, then `एक १`, `दो २०`, `तीन ३`, and `३ सत्य` on separate lines.

### सामान्य मूल्य (Universal value)

`सर्ववस्तु` संग्रह में अलग-अलग समर्थित प्रकार रखे जा सकते हैं। पूर्णांक और
तार्किक प्रकार अलग रहते हैं। पाठ की समानता उसके अक्षरों से और वस्तु या संग्रह
की समानता उसकी पहचान से तय होती है।

```bhash
कार्य मुख्य() -> शून्य {
    स्थिर मिश्र: सूची<सर्ववस्तु> = नया सूची<सर्ववस्तु>{१,सत्य,"अ"+"ब"};
    लिखो(परिवर्तित<पूर्णांक>(मिश्र[०]), परिवर्तित<पाठ>(मिश्र[२]));
    स्थिर व: सर्ववस्तु = मिश्र;
    लिखो(प्रकारहै<सूची<सर्ववस्तु>>(व), प्रकारहै<सूची<पाठ>>(व));
}
```

Output: `१ अब`, then `सत्य असत्य` on the next line.

Run [collections.bhash](../examples/native/collections.bhash) for growing maps and
lists, snapshots, reference arrays, cyclic graphs and repeated temporary storage:

```console
java -jar target/bhash-compiler-0.1.0-SNAPSHOT.jar compile examples/native/collections.bhash --target native -o build/collections
BHASH_GC_STRESS=1 BHASH_GC_STATS=1 ./build/collections
```

## Runtime and collector contract

Private native ABI v5 extends GC descriptors with a repeated region: byte offset,
stride, and a bitmap of reference lanes within each stride. The allocator validates
these regions against payload size. The collector visits only declared pointer
lanes, and the reference-store barrier accepts those same writable slots. Integer
and boolean storage is never scanned as pointers. Unused capacity and removed slots
are zeroed, and clearing a nonempty list/map releases its backing buffers.

Each collection is a managed wrapper containing immutable type metadata, logical
size, structural version, capacity, and managed storage/bucket references. Arrays
and lists use eight-byte element slots. Maps use ordered entries with key, value,
cached hash and bucket-chain index; scalar bucket storage is also managed. Both
wrapper and storage addresses are payload-base pointers. No application code
allocates or releases native memory explicitly.

Runtime helpers root and reload receivers, reference arguments, partially built
arrays, new buffers, and snapshot results across allocation. Literal input roots
use temporary unmanaged scratch storage that is released before returning. LLVM
argument buffers are allocated once at function entry, so loops do not accumulate
stack allocations. The collector remains precise, stop-the-world and nonmoving;
repeated writable slots also support future reference relocation.

Maps use content hashing for text and exact scalar hashing for boxed primitives.
Objects and collections receive a stable private identity number in the allocation
header, retained across any future relocation. Updating a key object's fields does
not change its hash. Lookup and insertion use hash buckets; replacing a key keeps
its original entry position. Removal compacts ordered entries and rebuilds bucket
chains, so removal is linear in map size. Hash lookup is expected constant time,
plus text hashing where applicable.

The collector's allocation-membership index replaces linear pointer lookup. It is
never a root and does not confer reachability: unreachable cycles are still swept.
Index growth happens outside collection; collection itself allocates no memory.
If optional index growth cannot obtain memory, existing buckets remain correct.
GC byte statistics count managed payloads and headers, excluding metadata and the
lookup index. Index storage is released/reset at shutdown. A future relocating collector must
rebuild this address index after moving objects while preserving their identity
numbers; map hashes therefore remain stable.

## Validation and remaining scope

Tests compare native and JVM output and exit status for every built-in collection
operation, defaults, nesting, invariant casts, snapshots, identity keys, universal
values, iteration, evaluation order, errors and package boundaries. Independent C
tests exercise repeated tracing, scalar pointer-bit precision, cycles, table growth,
snapshot lifetimes, rejection of invalid descriptors, and collector restart. Linux
CI runs LLVM verification and sanitizers and executes the example under GC stress
inside a clean container without Java, .NET or development tools.

Other numeric widths, decimals, records/enums, user-defined generics, static members,
exceptions and foreign interoperability remain outside the native profile. Built-in
collection type arguments are supported; this does not add user-defined generic
classes/functions. Standard-library intrinsics outside the existing native text
subset remain unsupported, including collection-oriented JSON/text/file APIs.
