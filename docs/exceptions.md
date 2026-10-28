# Exception handling (v0.8)

`प्रयास` = try, `पकड़ो` = catch, `अंततः` = finally, `फेंको` = throw.
The same source runs on JVM, .NET CLR and browser .NET WebAssembly. The
experimental Linux native target supports the [native exception subset](native-exceptions.md).

```text
वर्ग अमान्यअंक विस्तार अपवाद {
    सार्वजनिक निर्माता(संदेश: पाठ) : आधार(संदेश) {}
}
कार्य मुख्य() -> शून्य {
    प्रयास {
        स्थिर अंक: पूर्णांक = पढ़ो();
        यदि (अंक < ०) { फेंको नया अमान्यअंक("अंक ऋणात्मक है।"); }
        लिखो(अंक);
    } पकड़ो (गलती: अमान्यअंक) {
        लिखो("अमान्य अंक:", गलती.संदेश);
    } पकड़ो (गलती: अपवाद) {
        लिखो("त्रुटि:", गलती.संदेश);
    } अंततः {
        लिखो("कार्य समाप्त।");
    }
}
```

## Exception values

`अपवाद` is a predeclared, non-generic reference type, available in every package
without an import. Its constructors are `नया अपवाद()` (empty message) and
`नया अपवाद(message)` with a `पाठ` argument. Its public `संदेश: पाठ` property
is immutable. User classes may extend it, inherit its message and add ordinary
fields/methods or interfaces. Call `आधार(message)` from a constructor to set
an inherited message. Exception values retain normal reference identity and can
be stored in generic collections, passed to functions or returned. The name
`अपवाद` cannot be redeclared as a type or used as an import alias.

`फेंको expression;` evaluates its expression once and throws an `अपवाद` or
subclass instance. Primitives, unrelated classes and an untyped `रिक्त` literal
are compile errors. Throwing a typed null exception raises a catchable Bhash
null-reference failure.

Within a catch body, `फेंको;` rethrows that handler's exception object, preserving
its identity and subtype. It also works inside nested blocks. Outside a catch it
is a compile error. Bhash does not yet expose portable stack traces, causes,
suppressed exceptions or host exception interoperation; rethrow does not promise
to preserve a host stack trace.

## Handler and cleanup rules

A try must have at least one catch or a finally. Catch clauses precede the optional
single finally. Each `पकड़ो (name: ExceptionType)` has an immutable binding scoped
to its body. Handlers run in source order: the first assignable type wins. Put
specialized exception types before their base types; duplicate catches and catches
hidden by an earlier base catch are compile errors. Exceptions raised by a catch
body propagate outward, rather than to sibling catches.

The optional finally runs after normal completion, handled/unhandled exceptions,
returns, breaks and continues. Return expressions are evaluated before cleanup.
Cleanup exceptions replace a pending exception or return value. Such replacement
does not retain the earlier exception automatically.

To keep control flow portable to CLR, `लौटाओ` cannot appear in finally, and
`विराम`/`जारी` cannot leave finally. Loop control targeting a loop or selection
inside that finally is allowed. Throwing from finally is allowed. Cleanup must
itself finish to let a pending return or exception proceed.

A function with a non-void result must return or throw on every reachable path;
all catch bodies participate in that check. Statements directly after unconditional
throw are unreachable. Exceptions are unchecked: no declaration on function
signatures or mandatory caller catches is required.

## Runtime failures

`पकड़ो (गलती: अपवाद)` also catches existing Bhash runtime failures, including
integer division/remainder by zero, invalid/ended console input, failed integer
parsing, array/list bounds, missing map keys, null references, structural mutation
during iteration and `त्रुटि(message)`. Their existing Devanagari messages are
available through `गलती.संदेश`. Failure from a static field initializer is
unwrapped from the JVM/CLR initialization wrapper before matching handlers.
The host may leave the failed type unusable; catching does not reset that type.

Fatal host failures such as out-of-memory, stack overflow or worker termination
are outside this contract. In particular browser Stop terminates the worker and
does not guarantee finally execution. Uncaught Bhash exceptions retain the existing
`भाष: message` error report and nonzero application exit status (or browser error
result). A handled exception can let execution continue normally.

## Run the factorial example

The [factorial example](../examples/exceptions.bhash) accepts 0–20, catches its
custom input-range exception separately from input-reading errors, and always
prints a completion message.

```bash
git pull --ff-only
mvn clean package -DskipTests
java -jar target/bhash-compiler-0.1.0-SNAPSHOT.jar run examples/exceptions.bhash
java -jar target/bhash-compiler-0.1.0-SNAPSHOT.jar run examples/exceptions.bhash --target clr
java -jar target/bhash-compiler-0.1.0-SNAPSHOT.jar compile examples/exceptions.bhash --target wasm-browser -o target/exceptions-web
```

Serve the browser bundle over HTTP, as described in [browser setup](wasm-browser.md).
