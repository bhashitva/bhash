# Exception handling (v0.8)

`प्रयास` = try, `पकड़ो` = catch, `अंततः` = finally, `फेंको` = throw.
The same source runs on JVM, .NET CLR and browser .NET WebAssembly. The
experimental Linux native target supports the [native exception subset](native-exceptions.md).

**हिन्दी:** `प्रयास` (try), `पकड़ो` (catch), `अंततः` (finally) और `फेंको` (throw) अपवाद सँभालते हैं। यही स्रोत JVM, .NET CLR और ब्राउज़र WebAssembly पर चलता है। [नेटिव अपवाद](native-exceptions.md) में नेटिव लक्ष्य के नियम देखें।

```text
वर्ग अमान्यअंक विस्तार अपवाद {
    सार्वजनिक निर्माता(संदेश: पाठ) : आधार(संदेश) {}
}
फलन मुख्य() -> शून्य {
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

**हिन्दी:** `अपवाद` पहले से घोषित, बिना प्रकार मापदंड वाला संदर्भ प्रकार है। हर संकुल में बिना आयात मिलता है। `नया अपवाद()` खाली संदेश बनाता है और `नया अपवाद(message)` पाठ लेता है। सार्वजनिक `संदेश: पाठ` अचल है। उपवर्ग इसमें क्षेत्र, विधि और अनुबंध जोड़ सकता है। विरासत में मिले संदेश के लिए निर्माता में `आधार(message)` बुलाएँ। पहचान अन्य संदर्भ वस्तुओं जैसी है। अपवाद संग्रह में रख सकते हैं, फलन को दे सकते हैं और लौटा सकते हैं। `अपवाद` नाम प्रकार या आयात उपनाम के लिए आरक्षित है।

`फेंको expression;` evaluates its expression once and throws an `अपवाद` or
subclass instance. Primitives, unrelated classes and an untyped `रिक्त` literal
are compile errors. Throwing a typed null exception raises a catchable Bhash
null-reference failure.

**हिन्दी:** `फेंको expression;` व्यंजक को एक बार चलाकर `अपवाद` या उसके उपवर्ग की वस्तु फेंकता है। आदिम मान, असंबंधित वर्ग और बिना प्रकार का `रिक्त` संकलन त्रुटि देते हैं। अपवाद प्रकार वाला null फेंकने पर पकड़ने योग्य null-reference विफलता मिलती है।

Within a catch body, `फेंको;` rethrows that handler's exception object, preserving
its identity and subtype. It also works inside nested blocks. Outside a catch it
is a compile error. Bhash does not yet expose portable stack traces, causes,
suppressed exceptions or host exception interoperation; rethrow does not promise
to preserve a host stack trace.

**हिन्दी:** `पकड़ो` के भीतर `फेंको;` उसी अपवाद को उसकी पहचान और उपप्रकार सहित फिर भेजता है। यह भीतर के ब्लॉक में भी चलता है। `पकड़ो` के बाहर संकलन त्रुटि है। होस्ट का stack trace, कारण और दबाए गए अपवाद लक्ष्य-पार अनुबंध का हिस्सा नहीं हैं।

## Handler and cleanup rules

A try must have at least one catch or a finally. Catch clauses precede the optional
single finally. Each `पकड़ो (name: ExceptionType)` has an immutable binding scoped
to its body. Handlers run in source order: the first assignable type wins. Put
specialized exception types before their base types; duplicate catches and catches
hidden by an earlier base catch are compile errors. Exceptions raised by a catch
body propagate outward, rather than to sibling catches.

**हिन्दी:** `प्रयास` के साथ कम से कम एक `पकड़ो` या `अंततः` चाहिए। सभी handlers वैकल्पिक `अंततः` से पहले आते हैं। हर `पकड़ो (नाम: प्रकार)` का अचल बंधन उसके शरीर तक रहता है। स्रोत क्रम में पहला संगत प्रकार चुना जाता है। विशेष प्रकार पहले रखें। पहले से ढके या दोहराए handler त्रुटि हैं। handler में नया अपवाद बाहर जाता है।

The optional finally runs after normal completion, handled/unhandled exceptions,
returns, breaks and continues. Return expressions are evaluated before cleanup.
Cleanup exceptions replace a pending exception or return value. Such replacement
does not retain the earlier exception automatically.

**हिन्दी:** सामान्य समापन, अपवाद, `लौटाओ`, `विराम` और `जारी` सभी रास्तों पर `अंततः` चलता है। लौटने वाला व्यंजक सफ़ाई से पहले चलता है। सफ़ाई में नया अपवाद लंबित अपवाद या वापसी को बदल देता है और पुराना अपवाद अपने आप नहीं जुड़ता।

To keep control flow portable to CLR, `लौटाओ` cannot appear in finally, and
`विराम`/`जारी` cannot leave finally. Loop control targeting a loop or selection
inside that finally is allowed. Throwing from finally is allowed. Cleanup must
itself finish to let a pending return or exception proceed.

**हिन्दी:** CLR संगतता के लिए `अंततः` में `लौटाओ` और उससे बाहर जाने वाले `विराम`/`जारी` मान्य नहीं हैं। उसके भीतर के चक्र या चयन तक जाने वाला नियंत्रण मान्य है। `अंततः` से अपवाद फेंक सकते हैं। लंबित वापसी या अपवाद आगे जाने से पहले सफ़ाई पूरी होनी चाहिए।

A function with a non-void result must return or throw on every reachable path;
all catch bodies participate in that check. Statements directly after unconditional
throw are unreachable. Exceptions are unchecked: no declaration on function
signatures or mandatory caller catches is required.

**हिन्दी:** मान लौटाने वाले फलन के हर पहुँच योग्य रास्ते पर वापसी या अपवाद चाहिए। `पकड़ो` की शाखाएँ भी इसमें गिनी जाती हैं। बिना शर्त `फेंको` के बाद के कथन पहुँच योग्य नहीं हैं। हस्ताक्षर में अपवाद घोषित करना या कॉल करने वाले से उसे पकड़वाना आवश्यक नहीं है।

## Runtime failures

`पकड़ो (गलती: अपवाद)` also catches existing Bhash runtime failures, including
integer division/remainder by zero, invalid/ended console input, failed integer
parsing, array/list bounds, missing map keys, null references, structural mutation
during iteration and `त्रुटि(message)`. Their existing Devanagari messages are
available through `गलती.संदेश`. Failure from a static field initializer is
unwrapped from the JVM/CLR initialization wrapper before matching handlers.
The host may leave the failed type unusable; catching does not reset that type.

**हिन्दी:** `पकड़ो (गलती: अपवाद)` शून्य से पूर्णांक भाग/शेष, गलत इनपुट या EOF, पूर्णांक parsing, संग्रह सीमा, अनुपस्थित कुंजी, null, iteration के बीच संरचनात्मक बदलाव और `त्रुटि(message)` भी पकड़ता है। देवनागरी संदेश `गलती.संदेश` में है। साझा क्षेत्र के आरंभक की त्रुटि JVM/CLR wrapper से निकालकर मिलाई जाती है। विफल प्रकार को पकड़ने से वह फिर आरंभ नहीं होता।

Fatal host failures such as out-of-memory, stack overflow or worker termination
are outside this contract. In particular browser Stop terminates the worker and
does not guarantee finally execution. Uncaught Bhash exceptions retain the existing
`भाष: message` error report and nonzero application exit status (or browser error
result). A handled exception can let execution continue normally.

**हिन्दी:** स्मृति खत्म होना, stack overflow और worker समाप्ति जैसी होस्ट विफलताएँ इस नियम से बाहर हैं। ब्राउज़र का **रोकें** worker समाप्त करता है और `अंततः` चलने की गारंटी नहीं देता। न पकड़ा गया अपवाद `भाष: message` और nonzero exit या ब्राउज़र त्रुटि देता है। पकड़ा गया अपवाद सामान्य निष्पादन जारी रहने दे सकता है।

## Run the factorial example

The [factorial example](../examples/exceptions.bhash) accepts 0–20, catches its
custom input-range exception separately from input-reading errors, and always
prints a completion message.

**हिन्दी:** [गुणनफल उदाहरण](../examples/exceptions.bhash) ०–२० स्वीकारता है। सीमा वाला अपना अपवाद इनपुट पढ़ने की त्रुटि से अलग पकड़ता है और हर बार समापन संदेश छापता है।

```bash
git pull --ff-only
mvn clean package -DskipTests
java -jar target/bhash-compiler-0.1.0-SNAPSHOT.jar run examples/exceptions.bhash
java -jar target/bhash-compiler-0.1.0-SNAPSHOT.jar run examples/exceptions.bhash --target clr
java -jar target/bhash-compiler-0.1.0-SNAPSHOT.jar compile examples/exceptions.bhash --target wasm-browser -o target/exceptions-web
```

Serve the browser bundle over HTTP, as described in [browser setup](wasm-browser.md).

**हिन्दी:** [ब्राउज़र निर्देश](wasm-browser.md) के अनुसार बंडल HTTP से परोसें।
