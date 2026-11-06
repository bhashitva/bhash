# Interfaces, generics, overloads and static members (v0.3)

These features use the same semantic rules on JVM, .NET CLR and browser WebAssembly.
The Linux native target also supports these explicit generics through the same specialization
pipeline; see [native generics](native-generics.md) for GC layouts and deployment validation.
The complete runnable example is [contracts-generics.bhash](../examples/contracts-generics.bhash).

**हिन्दी:** JVM, .NET CLR और WebAssembly पर अर्थ के एक जैसे नियम हैं। नेटिव में भी explicit generics उसी विशेषीकरण से चलते हैं। GC और तैनाती के लिए [नेटिव सामान्य प्रकार](native-generics.md) और चलाने के लिए [पूरा उदाहरण](../examples/contracts-generics.bhash) देखें।

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
    फलन लो() -> त;
}
वर्ग पात्र<त> पालन प्रदाता<त> {
    निजी मान मूल्य: त;
    साझा मान गिनती: पूर्णांक = ०;
    निर्माता(मूल्य: त) {
        यह.मूल्य = मूल्य;
        पात्र<त>.गिनती = पात्र<त>.गिनती + १;
    }
    फलन लो() -> त { लौटाओ मूल्य; }
}
फलन पहचान<त>(मूल्य: त) -> त { लौटाओ मूल्य; }
फलन मुख्य() -> शून्य {
    स्थिर वस्तु: प्रदाता<पूर्णांक> = नया पात्र<पूर्णांक>(४२);
    लिखो(पहचान<पूर्णांक>(वस्तु.लो()), पात्र<पूर्णांक>.गिनती);
}
```

This prints `४२ १`.

**हिन्दी:** इससे `४२ १` छपता है।

## Interface contracts

Use `अनुबंध नाम { फलन विधि(...) -> प्रकार; }`. Methods are public instance signatures;
they have no body. Use `अनुबंध विस्तृत विस्तार पहला, दूसरा { ... }` for multiple interface
inheritance, and `वर्ग बच्चा विस्तार मूल पालन पहला, दूसरा { ... }` for implementation.
A class can also omit the base-class clause: `वर्ग बच्चा पालन पहला { ... }`.

**हिन्दी:** `अनुबंध नाम { फलन विधि(...) -> प्रकार; }` लिखें। इसमें सार्वजनिक instance विधि का हस्ताक्षर है, शरीर नहीं। कई अनुबंधों की विरासत के लिए `विस्तार पहला, दूसरा` और वर्ग में कार्यान्वयन के लिए `पालन पहला, दूसरा` लिखें। वर्ग अपना मूल वर्ग छोड़े तो भी `पालन` कर सकता है।

Every required method must have an exact parameter/return-type match in a public instance
method on the class or an inherited base-class method. Interface implementation does not
use `अधिलेखन` unless the method also overrides a base-class method. Calls through an
interface dispatch to the actual object's implementation, including derived overrides.
Class-to-implemented-interface and derived-interface-to-base-interface conversions are implicit.
Interface references accept `रिक्त`, compare by identity and use the existing null checks.
Interfaces cannot be constructed. Cycles, conflicting inherited signatures and incomplete
implementations are errors. Fields, default methods and static interface members are not included.

**हिन्दी:** हर आवश्यक विधि का मापदंड और परिणाम प्रकार सार्वजनिक instance विधि से ठीक मेल खाए। वह मूल वर्ग से भी मिल सकती है। `अधिलेखन` तभी दें जब मूल वर्ग की विधि बदलते हों। अनुबंध से कॉल वास्तविक वस्तु के कार्यान्वयन तक जाती है। वर्ग से लागू अनुबंध और व्युत्पन्न अनुबंध से मूल अनुबंध का रूपांतरण अंतर्निहित है। अनुबंध संदर्भ `रिक्त` लेते हैं और पहचान से मिलते हैं। अनुबंध की वस्तु नहीं बना सकते। चक्र, विरोधी हस्ताक्षर और अधूरा कार्यान्वयन त्रुटि हैं। क्षेत्र, default विधि और साझा अनुबंध सदस्य उपलब्ध नहीं हैं।

## Generic types and functions

Classes, interfaces and global functions can declare one or more type parameters:
`वर्ग जोड़ी<क, ख> { ... }` or `फलन पहचान<त>(मूल्य: त) -> त { ... }`.
Supply explicit type arguments at every use: `जोड़ी<पूर्णांक, पाठ>` and `पहचान<पाठ>("नमस्ते")`.
There is no type-argument inference or raw generic type. Parameters can stand for any existing
value type, including integers, reals, booleans, text, classes, interfaces and nested generic types.
`शून्य` and `रिक्त` are not type arguments.

**हिन्दी:** वर्ग, अनुबंध और वैश्विक फलन एक या अधिक प्रकार मापदंड घोषित कर सकते हैं। हर उपयोग में `जोड़ी<पूर्णांक, पाठ>` या `पहचान<पाठ>("नमस्ते")` जैसे स्पष्ट प्रकार तर्क दें। अनुमान और बिना तर्क का सामान्य प्रकार उपलब्ध नहीं हैं। मापदंड आदिम, वर्ग, अनुबंध या भीतर के सामान्य प्रकार हो सकते हैं। `शून्य` और `रिक्त` प्रकार तर्क नहीं हैं।

Generic types are invariant: `पात्र<बच्चा>` cannot be assigned to `पात्र<मूल>`, even when
`बच्चा` extends `मूल`. Generic classes can extend generic base classes and implement generic
interfaces. Different closed generic types have separate static fields: `पात्र<पूर्णांक>.गिनती`
and `पात्र<पाठ>.गिनती` are independent.

**हिन्दी:** सामान्य प्रकार invariant हैं। `बच्चा` के `मूल` का उपवर्ग होने पर भी `पात्र<बच्चा>` को `पात्र<मूल>` में नहीं रख सकते। सामान्य वर्ग सामान्य मूल वर्ग बढ़ा और सामान्य अनुबंध लागू कर सकते हैं। अलग पूर्ण प्रकारों जैसे `पात्र<पूर्णांक>` और `पात्र<पाठ>` के साझा क्षेत्र अलग हैं।

The compiler specializes each used type/function into concrete definitions before normal
semantic checking. Each instantiated body must type-check for its actual arguments; for example,
a template that uses numeric operators is rejected when instantiated with text. Unused template
bodies are parsed but not type-checked. Recursive references to the same closed type reuse its
specialization; more than 256 distinct specializations produces a diagnostic rather than unbounded
expansion. Generic method declarations inside classes/interfaces, bounds/constraints, variance,
wildcards, default type arguments and generic reflection are deferred. Generic classes can contain
ordinary methods and overloaded/static members that use their class type parameters.

**हिन्दी:** संकलक अर्थ जाँच से पहले हर उपयोग किए प्रकार और फलन की ठोस प्रति बनाता है। प्रत्येक प्रति वास्तविक प्रकारों के साथ सही होनी चाहिए। संख्यात्मक संक्रिया वाला template पाठ के साथ असफल हो सकता है। अप्रयुक्त शरीर parse होता है लेकिन प्रकार जाँचे नहीं जाते। पुनरावृत्त समान प्रकार अपनी प्रति साझा करता है। २५६ से अधिक अलग विशेषीकरण पर निदान मिलता है। विधि के अपने सामान्य मापदंड, constraints, variance, wildcards, default प्रकार तर्क और reflection आगे के विषय हैं। सामान्य वर्ग की साधारण और साझा विधियाँ वर्ग के प्रकार मापदंड ले सकती हैं।

## Overloads

Global functions, instance/static methods and constructors can share a name when their parameter
type lists differ. A return type, parameter name, visibility or static modifier alone cannot
distinguish an overload. Generic global functions can also overload on their value parameters.

**हिन्दी:** वैश्विक फलन, instance या साझा विधि और निर्माता एक नाम साझा कर सकते हैं जब मापदंड प्रकारों की सूचियाँ अलग हों। केवल परिणाम प्रकार, मापदंड नाम, पहुँच या साझा चिह्न से ओवरलोड अलग नहीं होते। सामान्य वैश्विक फलनों के मान मापदंड भी अलग हो सकते हैं।

Resolution first filters by instance/static receiver kind and uses the arguments' compile-time types. An overload is applicable when every argument
can be assigned to its corresponding parameter. From applicable overloads, choose the single one
whose parameters are each assignable to those of every other candidate. A missing or ambiguous
match is an error. There are no numeric promotions, optional parameters, varargs or user conversions.
For `रिक्त`, the more-derived reference parameter wins; unrelated reference overloads are ambiguous.
Access is checked on the selected overload; an inaccessible exact match does not fall back to a
less-specific public match.

**हिन्दी:** पहले receiver का प्रकार और तर्कों के संकलनकालीन प्रकार देखें। जहाँ हर तर्क अपने मापदंड में रखा जा सकता है वही ओवरलोड लागू है। उसमें एक सबसे विशिष्ट उम्मीदवार चाहिए। न मिलने या अस्पष्टता पर त्रुटि है। संख्यात्मक promotion, वैकल्पिक मापदंड, varargs और उपयोगकर्ता रूपांतरण नहीं हैं। `रिक्त` के लिए अधिक व्युत्पन्न संदर्भ प्रकार चुना जाता है। असंबंधित प्रकार अस्पष्ट हैं। चुने हुए ओवरलोड की पहुँच जाँची जाती है।

Overload selection happens before virtual dispatch. An override must use `अधिलेखन`, exactly
preserve its inherited overload's types, and not narrow access. A subclass may add different
parameter signatures without `अधिलेखन`, while preserving inherited overloads. Constructors are
not inherited. An implicit public no-argument constructor exists only if no constructor is declared.
Base constructor initializers use the same overload resolution. Return-type-only overloads and
static member hiding remain errors.

**हिन्दी:** पहले ओवरलोड चुना जाता है, फिर गतिशील dispatch होता है। `अधिलेखन` में पुराने मापदंड और परिणाम ठीक रखें तथा पहुँच न घटाएँ। उपवर्ग दूसरे हस्ताक्षर जोड़ सकता है। निर्माता विरासत में नहीं आते। बिना मापदंड का अंतर्निहित सार्वजनिक निर्माता तभी है जब कोई निर्माता न लिखा हो। मूल निर्माता में भी वही ओवरलोड नियम हैं। केवल वापसी प्रकार बदलना या साझा सदस्य छिपाना त्रुटि है।

## Static members

Put `साझा` after an optional visibility modifier:

**हिन्दी:** वैकल्पिक पहुँच चिह्न के बाद `साझा` रखें:

```text
वर्ग गणित {
    साझा स्थिर सीमा: पूर्णांक = १००;
    निजी साझा मान गिनती: पूर्णांक = ०;
    साझा फलन दुगुना(संख्या: पूर्णांक) -> पूर्णांक { लौटाओ संख्या * २; }
    साझा फलन दुगुना(संख्या: दशमलव) -> दशमलव { लौटाओ संख्या * २.०; }
}
```

Call `गणित.दुगुना(२)` or `गणित.दुगुना(१.५)`. Static members belong to their declaring class;
inherited access refers to that same storage. Explicit static access must use a type receiver;
instance receivers are rejected. Inside a class, static members can be named without a qualifier.
Static bodies cannot access `यह`, `आधार` or an implicit instance member. An explicit object
parameter can still be used to access its instance members.

**हिन्दी:** `गणित.दुगुना(२)` या `गणित.दुगुना(१.५)` बुलाएँ। साझा सदस्य अपने घोषक वर्ग के हैं। विरासत वाला उपयोग भी उसी भंडार को देखता है। स्पष्ट साझा access में प्रकार receiver दें। वर्ग के भीतर नाम अकेला लिख सकते हैं। साझा विधि में `यह`, `आधार` और अंतर्निहित instance सदस्य नहीं हैं। स्पष्ट वस्तु मापदंड से उसके instance सदस्य ले सकते हैं।

Static methods do not dispatch virtually and cannot be overridden or hidden with the same
signature. Visibility checks still apply. Static fields use the existing defaults and constant-only
initializer rule; immutable fields still require initializers. Arithmetic failures in static initializers produce the usual Bhash diagnostic and exit status 1.
User-defined static constructors are deferred.

**हिन्दी:** साझा विधियाँ गतिशील dispatch नहीं करतीं और समान हस्ताक्षर से बदली या छिपाई नहीं जा सकतीं। पहुँच जाँची जाती है। साझा क्षेत्रों पर सामान्य default और स्थिर आरंभक नियम हैं। अचल क्षेत्र को आरंभक चाहिए। आरंभक में गणना विफल होने पर सामान्य निदान और exit code 1 मिलता है। उपयोगकर्ता साझा निर्माता आगे का विषय है।

## Running the example

```sh
mvn verify
java -jar target/bhash-compiler-0.1.0-SNAPSHOT.jar run examples/contracts-generics.bhash
java -jar target/bhash-compiler-0.1.0-SNAPSHOT.jar run examples/contracts-generics.bhash --target clr
java -jar target/bhash-compiler-0.1.0-SNAPSHOT.jar compile examples/contracts-generics.bhash --target wasm-browser -o target/contracts-web
```

For the last command, serve the output directory over HTTP as described in [browser target](wasm-browser.md).

**हिन्दी:** अंतिम आदेश का आउटपुट [ब्राउज़र निर्देश](wasm-browser.md) के अनुसार HTTP से परोसें।
