# Native interfaces and runtime type checks/casts

The experimental Linux x86-64 / LLVM 18 target supports Bhash interface contracts,
interface inheritance, interface calls, and runtime tests/casts for native types.
This extends [native inheritance](native-inheritance.md). Compiled applications
continue to require neither JVM nor .NET; the compiler still uses Java and LLVM.

## Supported contracts

- `अनुबंध`, signature-only methods, and multiple interface inheritance with `विस्तार`.
- Classes implementing one or more interfaces with `पालन`, including inherited
  implementations and later class overrides.
- Interface-typed locals, parameters, fields, return values, nulls and equality.
- Implicit class-to-interface and derived-interface-to-base-interface assignment.
- Interface dispatch preserving the selected overload and all supported result
  representations: integer, boolean, text, object/interface reference and void.
- Shared signatures, diamond inheritance and explicit signature redeclarations.
  Unrelated interfaces may have identical method names/parameters but different
  results; they retain distinct dispatch entries.

Membership is nominal. A class exposing matching public methods without declaring
or inheriting the contract does not become an implementation. The shared checker
requires public implementations with exact parameter and result types, detects
incompatible inherited contracts and interface cycles, rejects interface bodies
and construction, and preserves existing access/override rules. Default interface
implementations and explicit interface implementation syntax are not introduced.

## Runtime operations

| Operation | Behavior |
| --- | --- |
| `प्रकारहै<T>(मूल्य)` | False for null; otherwise test actual class/interface membership or exact supported scalar type |
| `सुरक्षितरूप<T>(मूल्य)` | Return the same reference on success, otherwise null; target must be a class/interface or `सर्ववस्तु` |
| `परिवर्तित<T>(मूल्य)` | Checked class/interface cast; preserve null and identity, or report a located Hindi failure and exit 1 |

Each operand executes exactly once. Tests and reference casts introduce no managed
allocation or collection safepoint. Reference results remain rooted, including a
cast immediately followed by an allocating call or another argument evaluation.
A failed cast does not print partially evaluated output arguments. A null interface
receiver fails before its method arguments execute, matching managed lowering.

Checks support the existing native `पूर्णांक`, `तार्किक`, `पाठ` and class/interface
types. Scalar checks use exact type identity, not numeric coercion. Null matches
no type. A scalar safely cast to a class/interface returns null; a checked reference
cast from a scalar fails. Identically shaped classes and unrelated interfaces do
not pass nominal membership checks.

The existing scalar conversion syntax is also available for native scalar identity,
integer-to-text, boolean-to-text, and text-to-integer conversions. Integer text uses
ASCII digits; boolean text is `सत्य` / `असत्य`; integer parsing uses the same strict
signed-64-bit rules as `पूर्णांकपाठ`. Existing compile-time conversion restrictions
remain in force. An invalid checked cast such as integer-to-boolean reports the
same runtime failure category as managed targets.

[Universal `सर्ववस्तु` boxing/unboxing](native-boxing.md) extends these operations
to boxed integers, booleans, text and object references. Other numeric widths/decimals,
collections, records/enums, generics, static members and exceptions remain outside
the native profile.

## Hindi reference example

```bhash
अनुबंध नामी { कार्य नाम() -> पाठ; }
वर्ग मूल {}
वर्ग बिल्ली विस्तार मूल पालन नामी {
    सार्वजनिक कार्य नाम() -> पाठ { लौटाओ "बिल्ली"; }
}
वर्ग अन्य {}
कार्य मुख्य() -> शून्य {
    स्थिर क: मूल = नया बिल्ली();
    // प्रकार जाँच (Type test): वास्तविक वस्तु का वर्ग और अनुबंध जाँचे जाते हैं।
    लिखो(प्रकारहै<बिल्ली>(क), प्रकारहै<नामी>(क));
    // जाँचा हुआ रूपांतरण (Checked cast): सफलता पर वही वस्तु मिलती है।
    स्थिर न: नामी = परिवर्तित<नामी>(क);
    लिखो(न.नाम());
    // सुरक्षित रूपांतरण (Safe cast): असंगत प्रकार के लिए रिक्त मिलता है।
    लिखो(सुरक्षितरूप<अन्य>(न) == रिक्त);
}
```

Output:

```text
सत्य सत्य
बिल्ली
सत्य
```

The [interfaces.bhash](../examples/native/interfaces.bhash) example adds interface
fields, an interface hierarchy, class overrides, cyclic graphs and repeated casts:

```console
java -jar target/bhash-compiler-0.1.0-SNAPSHOT.jar compile examples/native/interfaces.bhash --target native -o build/interfaces
BHASH_GC_STRESS=1 BHASH_GC_STATS=1 ./build/interfaces
```

## Private metadata and collector contract

Private object ABI v4 (including [boxing metadata](native-boxing.md)) keeps the eight-byte nonmanaged prefix and all existing field
offsets. That prefix now points to an immutable metadata record containing pointers
to class-virtual and interface-dispatch arrays, plus nominal membership IDs. Class
virtual slots remain inherited prefixes. Each declared interface signature has a
whole-program dispatch index; implementing classes supply their checked most-derived
public method, and other entries are null. Interface-parent redeclarations can
share an implementation without merging nominal interface identities or incompatible
result signatures.

Interface calls load their selected entry after evaluating/checking the receiver
and evaluating/reloading arguments. Tests scan the actual object's deduplicated
class/base/interface membership list without allocation. The current interface
arrays are intentionally simple and potentially sparse: metadata grows with the
product of concrete classes and declared interface signatures. Per-contract compact
tables can follow if real programs justify them. IDs and layouts are internal to a
whole-program build, not a separate-compilation or foreign ABI.

Interface values are ordinary allocation-base pointers, not wrappers or adjusted
interior/fat pointers. GC roots and descriptors therefore treat class/interface
fields identically. Metadata and code pointers are immutable nonmanaged globals
and are excluded from tracing. Reference stores still use `bh_reference_store`.
The precise, stop-the-world, nonmoving collector and future relocation/barrier
contracts are unchanged. Tests/casts never retain interior pointers across an
allocation; successful aliases are published before temporary roots are released.

## Validation

`NativeBackendTest` compares native/JVM output and exit behavior under forced GC
and ASan/UBSan. Coverage includes diamonds/redeclarations, multiple/unrelated
contracts, inherited overrides, every supported result representation, cross-package
contracts, nominal versus structural matching, successful/failed/null casts, scalar
checks/conversions, once-only evaluation, temporary receivers across allocations,
interface fields and cycles, and thousands of casts with exactly one source object
allocation. Compiler-only tests cover membership/dispatch metadata and rejected
contracts. Existing native text/object/inheritance tests remain in the same corpus.

CI also builds the ordinary stress executable, checks dependencies, and runs it in
a clean Ubuntu container without Java/.NET/build tools or networking. Host LLVM 23
probes at debug/optimized settings are development aids; Linux LLVM 18 acceptance
and full managed/browser regressions remain required before merge.
