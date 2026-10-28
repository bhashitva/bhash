# Native exception handling

The experimental Linux x86-64 / LLVM 18 target supports `प्रयास` (try), ordered
`पकड़ो` (catch) handlers, `अंततः` (finally), `फेंको` (throw/rethrow), the built-in
`अपवाद`, and custom exception subclasses. Applications still require neither JVM
nor .NET. Java and LLVM are compiler/build dependencies only.

The shared [exception language rules](exceptions.md) apply within the existing
[native profile](native-compilation-design.md).

## Supported behavior

- `नया अपवाद()` creates an exception with an empty message;
  `नया अपवाद(text)` sets its immutable public `संदेश: पाठ` property.
- User classes extend `अपवाद`, call `आधार(message)`, and add supported fields,
  methods, subclasses or interfaces. Exceptions retain ordinary reference identity
  and can be stored in collections or `सर्ववस्तु` and tested/cast nominally.
- Throw operands execute once. Throwing a typed null raises a catchable null-reference
  failure. Untyped null, unrelated objects and scalar throw operands remain errors.
- Catch clauses run in source order; the first assignable type wins. A catch binding
  is immutable and scoped to that handler. Duplicate/unreachable handlers and invalid
  catch types retain the shared checker's diagnostics.
- `फेंको;` rethrows the enclosing handler's object, preserving identity/subtype.
  An exception raised by a handler bypasses sibling handlers and propagates outward.
- Finally runs on normal completion, handled/unhandled exceptions, returns, breaks
  and continues. Return expressions evaluate before cleanup; their results remain
  rooted even when cleanup allocates or changes the original local variable.
- A cleanup exception replaces the pending exception or return. Replaced values
  are released when their remaining references disappear; no suppressed/cause chain
  is added automatically.
- Return from finally, or break/continue leaving finally, remains forbidden. Control
  flow targeting loops inside finally is allowed.
- Functions, constructors, virtual/interface calls, nested handlers, foreach loops,
  and package boundaries all use the same rules.

Existing supported runtime failures are catchable as `अपवाद`: integer division or
remainder by zero, text parsing/range/limit errors, failed checked casts, null
receivers, array/list bounds, invalid collection sizes, missing/null map keys,
structural iteration changes, and `त्रुटि(message)`. Their Hindi messages are
available through `गलती.संदेश`. A failed print argument does not print partial
output from its surrounding `लिखो` call.

Unhandled exceptions run enclosing finally blocks, report a Hindi diagnostic with
an originating Bhash source location, and exit 1. The runtime then reclaims managed
allocations; `BHASH_GC_STATS=1` can report `live=0` on both normal and unhandled exits.
A rethrow keeps the original stored location. Portable stack traces, causes,
suppressed exceptions and foreign exception interoperation are not introduced.

Fatal infrastructure failures (out-of-memory, stack exhaustion, internal runtime
invariant failures, output I/O failures, or process termination) remain outside the
catch/finally guarantee. Unsupported native features remain unsupported: console
input, static members, selection, user-defined generics, other numeric widths,
decimals, records/enums and additional standard-library operations.

## Hindi reference examples

### पकड़ना और सफाई (Catch and finally)

`प्रयास` में विफल हो सकने वाला काम लिखें। `पकड़ो` में अपवाद का संदेश पढ़ा जा
सकता है और प्रोग्राम आगे बढ़ सकता है। `अंततः` सफलता और विफलता, दोनों स्थितियों
में चलता है।

```bhash
कार्य मुख्य() -> शून्य {
    प्रयास { लिखो("अधूरा", १ / ०); }
    पकड़ो (गलती: अपवाद) { लिखो(गलती.संदेश); }
    अंततः { लिखो("सफाई"); }
    लिखो("आगे");
}
```

Output:

```text
शून्य से भाग देना संभव नहीं है।
सफाई
आगे
```

### अपना अपवाद और पुनः फेंकना (Custom exception and rethrow)

अपना अपवाद बनाने के लिए `अपवाद` का विस्तार करें। `आधार` को संदेश देने पर वही
संदेश विरासत में मिलता है। विशेष प्रकार को उसके आधार प्रकार से पहले पकड़ें।
`फेंको;` नई वस्तु नहीं बनाता; वही पकड़ी गई वस्तु बाहर भेजता है।

```bhash
वर्ग विशेष विस्तार अपवाद {
    निर्माता(शब्द: पाठ) : आधार(शब्द) {}
}
कार्य मुख्य() -> शून्य {
    स्थिर मूल: विशेष = नया विशेष("मूल");
    प्रयास {
        प्रयास { फेंको मूल; }
        पकड़ो (गलती: विशेष) { लिखो(गलती == मूल); फेंको; }
        अंततः { लिखो("भीतर"); }
    } पकड़ो (गलती: अपवाद) { लिखो(गलती.संदेश, गलती == मूल); }
}
```

Output: `सत्य`, then `भीतर`, then `मूल सत्य` on separate lines.

### वापसी के समय सफाई (Cleanup on return)

वापसी का मूल्य पहले निकाला जाता है। उसके बाद सफाई चलती है। सफाई में स्थानीय
चर बदलने से पहले से चुना गया वापसी का मूल्य नहीं बदलता। स्मृति प्रबंधन अपने-आप
होता है; पाठ या अपवाद को मुक्त करने के लिए अलग आदेश नहीं देना पड़ता।

```bhash
कार्य फल() -> पाठ {
    मान शब्द: पाठ = "अ" + "ब";
    प्रयास { लौटाओ शब्द; }
    अंततः { शब्द = "क" + "ख"; लिखो(शब्द); }
}
कार्य मुख्य() -> शून्य { लिखो(फल()); }
```

Output: `कख`, followed by `अब` on the next line.

The executable [exceptions.bhash](../examples/native/exceptions.bhash) example adds
recursive unwinding, custom exception fields, collection failures, allocating
cleanup, and repeated recovery under forced collection:

```console
java -jar target/bhash-compiler-0.1.0-SNAPSHOT.jar compile examples/native/exceptions.bhash --target native -o build/exceptions
BHASH_GC_STRESS=1 BHASH_GC_STATS=1 ./build/exceptions
```

```text
१२४७५० ५००
बचा😀
```

## Compiler/runtime contract

Private native ABI v7 reserves nominal type ID -5 for the built-in exception. Its
payload contains the immutable class metadata pointer, message at offset 8, and a
private originating-location reference at offset 16. Custom exception fields follow
that prefix. Both text fields are precisely traced. Ordinary class/collection/box
representations retain their existing layout. The location is runtime metadata,
not a public mutable Bhash property.

Try bodies, handlers and cleanup are outlined into synchronous LLVM callbacks.
Each receives an environment of addresses into its enclosing Bhash function's live
frame: captured bindings, writable root slots, literal buffers and a pending-return
slot. Bindings are shared by address, so writes remain visible after recovery.
Buffers/environments are allocated once at function entry, not repeatedly in loops.
These internal callbacks do not introduce user-visible lambdas or escaping closures.

`bh_protect` establishes `setjmp` inside C and invokes one callback. `bh_throw` first
publishes the exception in a precise global root, restores the saved root-chain
head, releases registered unmanaged helper scratch storage, and then uses `longjmp`
back to that C boundary. The C function returns once to its LLVM caller; generated
functions never execute `setjmp` and never resume a discarded callback frame.
Saved C boundary fields are initialized before setjmp and not modified afterward.
This avoids relying on LLVM returns-twice behavior or indeterminate C locals.

Normal/return/loop completions use private integer codes. The compiler executes
finally before propagating those codes to the enclosing function or loop. Exceptions
from a catch/finally callback are captured separately, so sibling catch clauses
cannot intercept them. A pending exception is stored in an enclosing precise root
while cleanup executes; a pending reference return has its own root. Cleanup that
throws releases a cancelled return root without disturbing returns awaiting an
outer cleanup. Original diagnostics keep their stored location through rethrow.

Restoring the root-chain head discards abandoned generated and C-helper frames.
Because callbacks share the enclosing function's root array, the compiler also
clears each finished/aborted region's temporary and local roots explicitly. It
preserves outer captured bindings. This distinction is required to reclaim large
objects before entering a catch, not merely at final function exit. Failed collection
literal construction additionally releases its temporary unmanaged input-root array
through the runtime cleanup chain before transfer.

The collector remains precise, stop-the-world, nonmoving mark-and-sweep. Exception
objects/messages use the normal heap and barriers; the pending global exception,
region roots and return slots participate in precise tracing. No JVM, .NET,
libunwind dependency, native host exception wrapper, or programmer-managed allocation
is required. Protected calls introduce callback/setjmp overhead; future lowerings
may optimize this private mechanism while preserving language and GC contracts.

## Validation

Native tests compare JVM/native output and exit codes for subtype matching, bare
rethrow, sibling-handler bypass, constructor failures, fields/interfaces/collections,
all supported result representations, nested try/finally/foreach control flow,
runtime failures, package boundaries and unhandled exits. Forced-GC retention tests
verify that cancelled reference returns, abandoned constructor frames and block
locals release large text before another allocation. A recursive 500-recovery
example exercises over 16,000 allocations without retaining unreachable objects.
Independent C tests check pending roots, 100-level unwinding, helper scratch cleanup,
nested boundaries and runtime restart at both optimization levels and under Linux
sanitizers. CI also executes the example in a clean deployment container.

[Native दशमलव support](native-decimals.md) adds binary64 arithmetic, conversions, formatting, boxing and collection values.
