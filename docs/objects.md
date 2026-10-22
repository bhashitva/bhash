# Objects and inheritance — Bhash v0.2

Bhash now has a statically typed, class-based object model on both JVM and CLR.
Objects have identity, fields and instance methods. A class may extend one parent
class. This is not a JavaScript prototype or dynamic-property model.

## Vocabulary

| Bhash | Meaning |
| --- | --- |
| `वर्ग` | Class |
| `विस्तार` | Extend one parent class |
| `निर्माता` | Constructor |
| `नया` | Construct a new object |
| `यह` | Current instance (`this`) |
| `आधार` | Parent implementation / base-constructor initializer |
| `सार्वजनिक` | Public member |
| `निजी` | Private member |
| `संरक्षित` | Protected member |
| `अधिलेखन` | Override an inherited instance method |
| `रिक्त` | Null object reference |

Existing keywords are reused: `कार्य` declares a member function; `मान` declares
a mutable field; `स्थिर` declares an immutable field or binding. `स्थिर` does
**not** mean a static class member. `शून्य` continues to mean no return value.
The new words are reserved; longer names such as `वर्गीकरण` remain identifiers.

## A complete example

```text
वर्ग गणक {
    कार्य गणना(संख्या: पूर्णांक) -> पूर्णांक { लौटाओ संख्या; }
}

वर्ग गुणनफलगणक विस्तार गणक {
    अधिलेखन कार्य गणना(संख्या: पूर्णांक) -> पूर्णांक {
        यदि (संख्या <= १) { लौटाओ १; }
        लौटाओ संख्या * यह.गणना(संख्या - १);
    }
}

कार्य मुख्य() -> शून्य {
    स्थिर वस्तु: गणक = नया गुणनफलगणक();
    लिखो(वस्तु.गणना(५));
}
```

This prints `१२०`: the base-typed variable dispatches to the derived method.
`examples/factorial-objects.bhash` adds console input and the existing 0–20 guard.
`examples/objects.bhash` demonstrates private/protected fields, constructors,
mutation, base calls and inheritance with a small account model.

## Fields and constructors

```text
वर्ग व्यक्ति {
    निजी मान नाम: पाठ;
    स्थिर सीमा: पूर्णांक = १०;

    निर्माता(नाम: पाठ) {
        यह.नाम = नाम;
    }

    कार्य परिचय() -> पाठ { लौटाओ यह.नाम; }
}
```

- Members and constructors are public by default; access modifiers are optional.
- Each class has at most one constructor. Without one, a public zero-argument
  constructor is synthesized. Constructors are not inherited or overloaded.
- A derived constructor can write `निर्माता(...) : आधार(...) { ... }`. If omitted,
  the base initializer is `आधार()`; its argument count and access must be valid.
- The base constructor finishes first, then the current class's fields initialize
  in declaration order, then its constructor body runs. This order is enforced on
  both backends rather than relying on their different host-language defaults.
- Mutable fields without an initializer default to `०`, `०.०`, `असत्य`, empty text,
  or `रिक्त`, according to type. Immutable fields require an initializer and cannot
  be reassigned, including in constructors.
- Field initializers support constant expressions, not names, calls, `यह`, `आधार`
  or `नया`. Put object construction and parameter-dependent initialization in the
  constructor using mutable fields. Blank immutable fields assigned in constructors
  are not yet supported.
- Constructor parameters are immutable locals, just like function parameters.
  They may shadow field names; `यह.नाम = नाम;` disambiguates them.
- During construction, `यह` may be used only as a direct field receiver. Calling
  instance methods on `यह`/`आधार` or passing/returning the incomplete object is
  rejected. This avoids virtual dispatch and object escape during initialization.
  Base-initializer arguments cannot access the current instance.

## Methods, inheritance and visibility

- Methods use `कार्य नाम(...) -> प्रकार { ... }`; `शून्य` remains valid for
  methods returning no value. Inside a method, fields and other methods can be
  named implicitly or through `यह`.
- Public/protected methods dispatch virtually. An inherited method must be
  overridden explicitly with `अधिलेखन`, preserving parameter and return types
  exactly and without reducing visibility. Overloading is not supported.
- `आधार.कार्यनाम(...)` directly invokes the parent implementation; `यह.कार्यनाम(...)`
  uses normal virtual dispatch. Both can access inherited members when permitted.
- Private members are accessible only within their declaring class. Protected
  members are accessible there and in subclasses; subclass access through another
  instance requires a receiver of that subclass type (or a further subtype).
- Private constructors can be used only inside their declaring class; protected
  constructors can additionally be invoked by a derived base initializer.
- Member names cannot hide inherited fields or methods. Private methods cannot be
  overridden. Cycles, unknown parents, duplicate classes and invalid signatures
  produce Bhash source-located diagnostics.
- Member names and class names follow the existing NFC identifier normalization.

## References, access and lifetime

Class names can be used as variable, field, parameter and return types. A derived
instance can be assigned to a base-class binding or passed/returned where its base
type is expected. Downcasts and implicit base-to-derived conversions are rejected.
`स्थिर` prevents rebinding a reference; it does not freeze the referenced object.

`.` accesses fields or calls methods and can be chained:
`पात्र.लो().स्वयं().संख्या`. Assignment targets must be mutable variables or fields.
Receivers evaluate once and are null-checked before call arguments or assignment
right-hand sides execute. A null dereference reports a concise runtime error and
exits with status 1 on both targets.

`रिक्त` is assignable only to class-reference types. Equality/inequality compare
object identity; comparisons with null and between related class types are allowed.
Unrelated class types cannot be compared. Text retains its existing value equality.
`लिखो` accepts primitive values, so print an object's field or method result rather
than the object reference itself. Memory is managed by the host JVM/.NET GC.

## Run the examples

```bash
mvn clean verify
java -jar target/bhash-compiler-0.1.0-SNAPSHOT.jar run examples/objects.bhash
java -jar target/bhash-compiler-0.1.0-SNAPSHOT.jar run examples/objects.bhash --target clr
java -jar target/bhash-compiler-0.1.0-SNAPSHOT.jar run examples/factorial-objects.bhash --target clr
```

Interfaces, abstract/final classes, static members, generics, overloaded methods
or constructors, casts, properties, arrays and JavaScript-style dynamic fields
remain separate future extensions.
