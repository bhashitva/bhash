# Native heap-backed objects

The experimental Linux x86-64 / LLVM 18 backend now supports ordinary Bhash class
instances on the automatically collected heap. This extends [native text](native-text.md)
and preserves the [approved collector design](native-compilation-design.md).
Applications require neither JVM nor .NET; the compiler still uses Java and LLVM.

## Supported class subset

- Ordinary nongeneric classes, including empty classes and mutually referring classes.
- Integer, boolean, text and concrete class instance fields; mutable fields and
  immutable fields with existing constant initializers.
- Default and overloaded constructors, and overloaded instance methods.
- Single class inheritance and virtual dispatch, detailed in [native inheritance](native-inheritance.md).
- Interface references and runtime tests/casts in [native interfaces](native-interfaces.md).
- `यह`, implicit instance members, chained field/method access, object parameters,
  return values, recursive methods, `रिक्त`, and object identity `==` / `!=`.
- Existing access control, overload resolution, initialization, immutability and
  constructor restrictions are checked by the same checker as JVM/CLR.

Fields default to zero, false, empty text or a null object reference, respectively.
Field initializers run before the constructor body. A constructor may return early;
the completed object is still returned to its caller. A constructor cannot expose
`यह` early or call an instance method on it; immutable fields still require their
existing declaration initializer. This milestone does not broaden those language rules.

[Arrays, lists and maps](native-collections.md) are supported as reference fields.
Static fields/methods, records, enums, user-defined generics, decimals and exceptions remain outside the
native class subset. Unsupported loaded declarations, even unused ones, produce
source-located `BH9001` diagnostics. Printing an object directly remains invalid;
print its scalar fields or method results.

## Hindi reference example

```bhash
वर्ग कड़ी {
    सार्वजनिक मान नाम: पाठ;
    सार्वजनिक मान अगला: कड़ी;
    निर्माता(नाम: पाठ) { यह.नाम = नाम; }
    सार्वजनिक कार्य शीर्षक() -> पाठ { लौटाओ नाम + "!"; }
}
कार्य मुख्य() -> शून्य {
    // वस्तु बनाना (Object construction): भंडारण अपने आप आवंटित होता है।
    स्थिर मूल: कड़ी = नया कड़ी("मूल");
    {
        स्थिर बच्चा: कड़ी = नया कड़ी("बच्चा");
        // सदस्य संदर्भ (Reference field): मूल से बच्चा जीवित रहता है।
        मूल.अगला = बच्चा;
        बच्चा.अगला = मूल;
    }
    // पहचान (Identity): दोनों संदर्भ एक ही वस्तु को दर्शाते हैं।
    लिखो(मूल.अगला.अगला == मूल, मूल.अगला.शीर्षक());
    मूल.अगला = रिक्त;
    // संग्रह (Garbage collection): अप्राप्य वस्तुएँ और चक्र अपने आप हट सकते हैं।
}
```

The executable stress example is [heap-objects.bhash](../examples/native/heap-objects.bhash):

```console
java -jar target/bhash-compiler-0.1.0-SNAPSHOT.jar compile examples/native/heap-objects.bhash --target native -o build/heap-objects
BHASH_GC_STRESS=1 BHASH_GC_STATS=1 ./build/heap-objects
```

Its output is:

```text
कड़ी22 सत्य सत्य
कड़ी2 १ सत्य
सत्य
```

`अंकपाठ` produces ASCII digits inside text; printing an integer produces Devanagari
digits. There is no public manual-memory or collection API.

## Compiler/runtime contract

The checked core records class identities, resolved fields, selected constructors,
selected methods and instance receivers. The native core collapses checked object
references to a pointer representation only after source type checking; field
identities and offsets remain explicit. Public/protected instance methods dispatch
through stable slots in per-class method tables; private methods and explicit base
calls use direct calls. Base fields form a prefix of every derived layout. Interface calls use resolved signature slots in a separate
per-class table; nominal membership metadata drives runtime tests/casts.

The private native object ABI v5 (extended by [boxing](native-boxing.md)) reserves the first eight payload bytes for an
immutable type-metadata pointer. Every field then occupies an eight-byte slot. Integers use 64 bits,
booleans use the low byte, and text/object fields contain payload-base pointers.
Descriptors list exactly the text and object reference offsets. Primitive fields
are never conservatively interpreted as pointers, and the metadata pointer
is excluded from GC tracing. Empty objects have only the eight-byte prefix and
distinct allocation identities. Layout is private, not a foreign ABI.

Constructors lower to a factory and an initializer. The factory allocates one zeroed
object with its final type metadata and roots it. Initializers invoke the selected
base initializer on that same object, then initialize their own fields and execute
their checked body. The factory returns the rooted object. Constructor arguments
are evaluated and rooted before allocation. Instance receivers are evaluated and null
checked before method arguments or an assignment RHS, matching existing managed
lowering. Null access reports a Hindi message with the original Bhash source location
and exits 1. Reading a null field value itself is valid; dereferencing it is not.

All live object and text expressions, receivers, parameters and locals use updateable
root slots. Allocation-capable calls reload reference arguments after argument
evaluation. Field assignment keeps the owner rooted during RHS evaluation and reloads
it before computing a field address. Interior field pointers never survive an
allocation. Consumed temporary roots and exited block roots are cleared, including
break/continue paths. Returning references crosses no safepoint until the caller
publishes its own root.

Every heap text/object field write, including initializers, passes through
`bh_reference_store`. This remains the future generational barrier boundary; current
stores are checked ordinary writes. The precise, stop-the-world, nonmoving collector
follows descriptors with its nonrecursive worklist and reclaims unreachable cycles.
Identity currently compares reloaded pointers without allocating; it does not expose
addresses to Bhash programs or promise permanent addresses for a future moving GC.

No new collector policy, thread support, finalizer, weak reference or manual ownership
rule is introduced. Existing linear heap lookup remains a performance limitation;
this is a correctness milestone, not a production-scale graph benchmark.

## Validation

`NativeBackendTest` compares native output and exit codes with JVM programs under
forced collection and ASan/UBSan. It covers defaults, overloads, empty objects,
constructor early return, recursive methods, chained access, nested allocation,
receiver/argument evaluation order, null failures, shared children, mutually typed
chains, surviving cycles, and thousands of transient cycles with a bounded peak.
Compiler-only tests check descriptor offsets, barrier calls, and rejected features.
Existing C collector/text tests run at debug and optimized settings with sanitizers.
CI also builds the ordinary executable and runs it in a clean Ubuntu container
without Java/.NET/build tools or networking, checking output and zero live objects
at shutdown. Full managed/backend regressions guard the shared-analysis hooks.

A host LLVM 23 development probe executes these programs at both `-O0` and `-O2`
and compares them with JVM output. This does not establish a supported Windows
native target; Linux LLVM 18 acceptance remains required before merge.
