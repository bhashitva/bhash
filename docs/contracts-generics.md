# Interfaces, generics, overloads and static members (v0.3)

These features use the same semantic rules on JVM, .NET CLR and browser WebAssembly.
The complete runnable example is [contracts-generics.bhash](../examples/contracts-generics.bhash).

## Vocabulary

| Bhash | Meaning |
| --- | --- |
| `अनुबंध` | Nominal interface with method signatures |
| `पालन` | A class implements one or more interfaces |
| `विस्तार` | A class extends one class, or an interface extends multiple interfaces |
| `साझा` | Static field or method, accessed through its class name |
| `स्थिर` | Immutable binding or field; combine with `साझा` for a static immutable field |

```text
अनुबंध प्रदाता<त> {
    कार्य लो() -> त;
}
वर्ग पात्र<त> पालन प्रदाता<त> {
    निजी मान मूल्य: त;
    साझा मान गिनती: पूर्णांक = ०;
    निर्माता(मूल्य: त) {
        यह.मूल्य = मूल्य;
        पात्र<त>.गिनती = पात्र<त>.गिनती + १;
    }
    कार्य लो() -> त { लौटाओ मूल्य; }
}
कार्य पहचान<त>(मूल्य: त) -> त { लौटाओ मूल्य; }
कार्य मुख्य() -> शून्य {
    स्थिर वस्तु: प्रदाता<पूर्णांक> = नया पात्र<पूर्णांक>(४२);
    लिखो(पहचान<पूर्णांक>(वस्तु.लो()), पात्र<पूर्णांक>.गिनती);
}
```

This prints `४२ १`.

## Interface contracts

Use `अनुबंध नाम { कार्य विधि(...) -> प्रकार; }`. Methods are public instance signatures;
they have no body. Use `अनुबंध विस्तृत विस्तार पहला, दूसरा { ... }` for multiple interface
inheritance, and `वर्ग बच्चा विस्तार मूल पालन पहला, दूसरा { ... }` for implementation.
A class can also omit the base-class clause: `वर्ग बच्चा पालन पहला { ... }`.

Every required method must have an exact parameter/return-type match in a public instance
method on the class or an inherited base-class method. Interface implementation does not
use `अधिलेखन` unless the method also overrides a base-class method. Calls through an
interface dispatch to the actual object's implementation, including derived overrides.
Class-to-implemented-interface and derived-interface-to-base-interface conversions are implicit.
Interface references accept `रिक्त`, compare by identity and use the existing null checks.
Interfaces cannot be constructed. Cycles, conflicting inherited signatures and incomplete
implementations are errors. Fields, default methods and static interface members are not included.

## Generic types and functions

Classes, interfaces and global functions can declare one or more type parameters:
`वर्ग जोड़ी<क, ख> { ... }` or `कार्य पहचान<त>(मूल्य: त) -> त { ... }`.
Supply explicit type arguments at every use: `जोड़ी<पूर्णांक, पाठ>` and `पहचान<पाठ>("नमस्ते")`.
There is no type-argument inference or raw generic type. Parameters can stand for any existing
value type, including integers, reals, booleans, text, classes, interfaces and nested generic types.
`शून्य` and `रिक्त` are not type arguments.

Generic types are invariant: `पात्र<बच्चा>` cannot be assigned to `पात्र<मूल>`, even when
`बच्चा` extends `मूल`. Generic classes can extend generic base classes and implement generic
interfaces. Different closed generic types have separate static fields: `पात्र<पूर्णांक>.गिनती`
and `पात्र<पाठ>.गिनती` are independent.

The compiler specializes each used type/function into concrete definitions before normal
semantic checking. Each instantiated body must type-check for its actual arguments; for example,
a template that uses numeric operators is rejected when instantiated with text. Unused template
bodies are parsed but not type-checked. Recursive references to the same closed type reuse its
specialization; more than 256 distinct specializations produces a diagnostic rather than unbounded
expansion. Generic method declarations inside classes/interfaces, bounds/constraints, variance,
wildcards, default type arguments and generic reflection are deferred. Generic classes can contain
ordinary methods and overloaded/static members that use their class type parameters.

## Overloads

Global functions, instance/static methods and constructors can share a name when their parameter
type lists differ. A return type, parameter name, visibility or static modifier alone cannot
distinguish an overload. Generic global functions can also overload on their value parameters.

Resolution first filters by instance/static receiver kind and uses the arguments' compile-time types. An overload is applicable when every argument
can be assigned to its corresponding parameter. From applicable overloads, choose the single one
whose parameters are each assignable to those of every other candidate. A missing or ambiguous
match is an error. There are no numeric promotions, optional parameters, varargs or user conversions.
For `रिक्त`, the more-derived reference parameter wins; unrelated reference overloads are ambiguous.
Access is checked on the selected overload; an inaccessible exact match does not fall back to a
less-specific public match.

Overload selection happens before virtual dispatch. An override must use `अधिलेखन`, exactly
preserve its inherited overload's types, and not narrow access. A subclass may add different
parameter signatures without `अधिलेखन`, while preserving inherited overloads. Constructors are
not inherited. An implicit public no-argument constructor exists only if no constructor is declared.
Base constructor initializers use the same overload resolution. Return-type-only overloads and
static member hiding remain errors.

## Static members

Put `साझा` after an optional visibility modifier:

```text
वर्ग गणित {
    साझा स्थिर सीमा: पूर्णांक = १००;
    निजी साझा मान गिनती: पूर्णांक = ०;
    साझा कार्य दुगुना(संख्या: पूर्णांक) -> पूर्णांक { लौटाओ संख्या * २; }
    साझा कार्य दुगुना(संख्या: दशमलव) -> दशमलव { लौटाओ संख्या * २.०; }
}
```

Call `गणित.दुगुना(२)` or `गणित.दुगुना(१.५)`. Static members belong to their declaring class;
inherited access refers to that same storage. Explicit static access must use a type receiver;
instance receivers are rejected. Inside a class, static members can be named without a qualifier.
Static bodies cannot access `यह`, `आधार` or an implicit instance member. An explicit object
parameter can still be used to access its instance members.

Static methods do not dispatch virtually and cannot be overridden or hidden with the same
signature. Visibility checks still apply. Static fields use the existing defaults and constant-only
initializer rule; immutable fields still require initializers. Static constructors are deferred.

## Running the example

```sh
mvn verify
java -jar target/bhash-compiler-0.1.0-SNAPSHOT.jar run examples/contracts-generics.bhash
java -jar target/bhash-compiler-0.1.0-SNAPSHOT.jar run examples/contracts-generics.bhash --target clr
java -jar target/bhash-compiler-0.1.0-SNAPSHOT.jar compile examples/contracts-generics.bhash --target wasm-browser -o target/contracts-web
```

For the last command, serve the output directory over HTTP as described in [browser target](wasm-browser.md).
