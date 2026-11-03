# Function values and closure conversion

Function types use `फलन(T1, T2) -> R`; lambdas use `फलन(a: T1, b: T2) -> R { ... }`. Parameters and return types are explicit. Function signatures are structural and invariant. A function can be passed, returned, stored in collections/fields, invoked with normal parentheses, and converted through the existing object/interface facilities. Parenthesize the entire function type for an array of functions. Null and identity comparison follow ordinary reference semantics.

Unambiguous global functions, including explicit generic specializations and imported functions, can be used as values. Overloaded function groups require a typed lambda selecting an overload. Method callbacks use explicit lambdas; there is no implicit conversion to existing single-method interfaces. This milestone adds synchronous callbacks, not tasks, asynchronous I/O, or threads.

## Binding and lifetime contract

Lexical resolution precedes closure conversion. Only referenced outer bindings are captured. Immutable locals, parameters, catch bindings and foreach bindings are captured by value; object values retain reference identity. Captured mutable locals use one shared heap cell per dynamic declaration. Reads and writes in the declaring function and every closure use that cell. Each invocation and each execution of a local declaration gets independent storage. Each foreach iteration gets its own immutable binding. Nested closures carry the captures needed to construct their descendants. Recursive closures can use a mutable function variable initialized to null before assigning the lambda.

Capturing `यह` retains the enclosing object. Class lambdas use compiler-generated static body helpers in the original class to preserve lexical access control without making source members public. Generated closure methods forward captures and arguments to those helpers. Direct `आधार` expressions in lambdas are diagnosed; use an enclosing helper method for base dispatch. Capturing a resource does not postpone deterministic resource cleanup.

## Shared lowering and GC

After package resolution and generic specialization, `ClosureExpander` creates one synthetic interface per signature, one class per lambda/function-reference wrapper, and cells only for mutable captured locals. Generated identifiers are deterministic and avoid source identifiers. Source token origins survive reparsing so diagnostics retain original files and lines. Environments, cells and interfaces enter the existing managed semantic checker and typed core; JVM, CLR/WASM and LLVM need no independent closure calling convention.

Native allocations therefore use existing object descriptors, precise reference-field tracing, interface dispatch, stack roots, exception unwinding and collection barriers. There are no raw stack addresses in environments and no separate closure heap. Cyclic environments are collected by mark/sweep. Calls use normal backend evaluation order and exception handling. The compiler does not add finalizers or retain closed resources artificially.

## Validation

`ClosureFixtures` is shared by JVM/CLR execution tests and native forced-GC tests. It covers named functions, typed lambdas, higher-order calls, nested/escaping closures, independent counters, shared mutation, generic functions, lists/arrays, enclosing-object private access, cycles, and exception/finally execution. Native checks require zero live allocations after shutdown and repeated collections in the stress fixture. `ClosureExpanderTest` checks typed-core conversion, source locations, imported references, parameter shadowing and invalid function contracts. The Hindi tutorial is an executable cross-target fixture.
