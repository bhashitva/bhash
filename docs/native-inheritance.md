# Native inheritance and virtual method dispatch

The experimental Linux x86-64 / LLVM 18 backend supports Bhash's existing single
class inheritance model. This extends [native objects](native-objects.md) without
requiring a JVM or .NET runtime in the compiled application. The compiler still
uses Java and LLVM. No new syntax or managed-target behavior is introduced.

## Supported behavior

- `वर्ग उप विस्तार मूल`, including deeper chains and forward/package declarations.
- Base-reference assignments, arguments and returns that preserve object identity.
- Inherited public/protected fields, methods and overloads, with existing access rules.
- `अधिलेखन` with the existing exact parameter/return signatures and access requirements.
- Virtual dispatch through the actual receiver's class, including calls made from
  an inherited method, recursion, and primitive/text/object/void method results.
- Explicit `आधार.सदस्य` field access and direct base-method calls.
- Default, overloaded and explicit `: आधार(...)` constructor chaining.

Overload selection uses checked static types first; virtual dispatch then chooses
the override of that selected signature. A derived-only overload does not change
which overload a base-typed call selects. A base call selects the implementation
resolved from the immediate base type; if that implementation internally calls
another virtual method on `यह`, that nested call still dispatches dynamically.
Private methods are direct calls. Interfaces, static members, records, enums,
generics, casts/type tests, collections and exceptions remain outside this native
profile. Inheritance from the built-in exception runtime is therefore unsupported.

Existing semantic diagnostics reject cycles, hidden/duplicate fields, a missing
`अधिलेखन`, an override without an inherited non-private method, changed signatures,
narrowed access, inaccessible members/constructors, and missing base arguments.
Protected receiver restrictions are unchanged. Immutable fields retain their
existing declaration-initializer rule. Constructors cannot expose `यह` early or
call instance methods on `यह`/`आधार` before construction completes.

## Hindi reference example

```bhash
वर्ग मूल {
    संरक्षित मान नाम: पाठ;
    निर्माता(नाम: पाठ) { यह.नाम = नाम; }
    सार्वजनिक कार्य परिचय() -> पाठ { लौटाओ "मूल: " + नाम; }
    सार्वजनिक कार्य विवरण() -> पाठ { लौटाओ परिचय(); }
}
वर्ग उप विस्तार मूल {
    निर्माता(नाम: पाठ) : आधार(नाम) {}
    // अधिलेखन (Override): वही हस्ताक्षर, नया व्यवहार।
    सार्वजनिक अधिलेखन कार्य परिचय() -> पाठ {
        // आधार आह्वान (Base call): मूल वर्ग का कार्य सीधे चलता है।
        लौटाओ आधार.परिचय() + " (उप)";
    }
}
कार्य मुख्य() -> शून्य {
    // बहुरूपता (Polymorphism): संदर्भ का प्रकार मूल है, वस्तु का प्रकार उप है।
    स्थिर क: मूल = नया उप("भाष");
    // आभासी प्रेषण (Virtual dispatch): विवरण के अंदर भी उप.परिचय चलता है।
    लिखो(क.विवरण());
}
```

Output: `मूल: भाष (उप)`.

The existing [bank-account example](../examples/objects.bhash) now also builds for
`--target native`. The stress example [inheritance.bhash](../examples/native/inheritance.bhash)
uses a three-level hierarchy, base-typed references, derived-only reference fields,
virtual text results and cyclic graphs:

```console
java -jar target/bhash-compiler-0.1.0-SNAPSHOT.jar compile examples/native/inheritance.bhash --target native -o build/inheritance
BHASH_GC_STRESS=1 BHASH_GC_STATS=1 ./build/inheritance
```

## Initialization and evaluation

A `नया` expression evaluates constructor arguments once, left to right, and roots
reference arguments. Its factory allocates exactly one object for the most-derived
class and installs that class's immutable method table before entering initializers.
Each initializer evaluates its base arguments, invokes the selected base initializer
on the same object, initializes its own fields in declaration order, then runs its
body. A missing explicit base initializer selects the existing no-argument base
constructor. An early return from a base constructor ends that initializer only;
derived initialization continues. No base subobject is allocated or copied.

Field defaults remain zero, false, empty text and null object references. Allocation
starts with zeroed slots so the most-derived descriptor can safely scan the whole
object even during base initialization. Null reference slots are ignored by GC.
User code cannot observe uninitialized derived fields through an early constructor
escape because the existing checker prohibits that escape and self-method calls.

Receivers are evaluated and checked for null before method arguments, matching
current managed lowering. Arguments finish evaluating before method-table lookup.
All managed arguments/receivers are reloaded from updateable roots after any nested
allocation. Table and function-pointer loads introduce no collection safepoint.
Null virtual access emits the same Hindi failure category with a Bhash location
and exits 1. Output arguments still all finish evaluating before any are printed.

## Private object ABI and memory management

The compiler's private object ABI is now v2. Payload offset zero holds a nonmanaged
pointer to a constant method table; all fields occupy eight-byte slots starting at
offset eight. A derived object preserves every base field's offset and appends its
own fields. Upcasts therefore preserve the same payload-base pointer and identity;
no pointer adjustment or extra base allocation is needed.

A class inherits its parent's method slots. Each checked override replaces the
implementation at its inherited slot, and new non-private signatures append slots.
A call records its statically selected signature's slot and invokes the function
loaded from the actual object's table. The implementation uses the same checked
native calling convention as direct functions. This is whole-program metadata,
not a stable separate-compilation or foreign ABI.

Descriptors include all inherited and derived text/object reference offsets, and
exclude primitive fields and the method-table pointer. Every managed field store
still passes through `bh_reference_store`. The precise, stop-the-world, nonmoving
mark-and-sweep collector, root frames, allocation safepoints and future barrier/
relocation contracts are unchanged. Inherited methods keep their receiver rooted
while allocations or recursive virtual calls execute. Unreachable cycles spanning
base and derived fields can be reclaimed.

## Validation

`NativeBackendTest` compares native output/exit codes with JVM output under forced
collection and ASan/UBSan. Cases cover three-level overrides, inherited virtual
self-calls, explicit base calls, private methods, overloads, all supported result
representations, constructor order and early return, one allocation per object,
forward/cross-package class declarations, temporary receivers across allocating
arguments, null access, and derived-only/inherited reference fields under repeated
cyclic allocation with bounded peak storage. Compiler-only tests check layout
prefixes, descriptor offsets, inherited slot stability and existing diagnostics.

CI builds the existing bank example and the stress example as ordinary native
executables, inspects dependencies, and runs them in a clean Ubuntu container
without Java/.NET/build tools or networking. Existing text/object/runtime tests and
full JVM/CLR/browser regressions remain required. Host LLVM 23 debug/optimized
probes aid development; they do not establish a supported Windows native target.
