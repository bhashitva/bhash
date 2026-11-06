# Objects and inheritance — Bhash v0.2

Bhash now has a statically typed, class-based object model on JVM, CLR and browser WebAssembly.
Objects have identity, fields and instance methods. A class may extend one parent
class. This is not a JavaScript prototype or dynamic-property model.

**हिन्दी:** भाष में स्थिर प्रकारों वाला वर्ग-आधारित वस्तु मॉडल है। वस्तु की पहचान, क्षेत्र और instance विधियाँ हैं। वर्ग एक मूल वर्ग बढ़ा सकता है। JVM, CLR और WebAssembly पर यही मॉडल है।

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

Existing keywords are reused: `फलन` declares a member function; `मान` declares
a mutable field; `स्थिर` declares an immutable field or binding. `स्थिर` does
**not** mean a static class member. `शून्य` continues to mean no return value.
The new words are reserved; longer names such as `वर्गीकरण` remain identifiers.

**हिन्दी:** `फलन` सदस्य फलन, `मान` बदलने वाला क्षेत्र और `स्थिर` अचल क्षेत्र या बंधन बताता है। वर्ग का साझा सदस्य `साझा` से लिखें। `शून्य` बिना वापसी मान का प्रकार है। आरक्षित शब्द के लंबे रूप जैसे `वर्गीकरण` नाम रह सकते हैं।

## A complete example

```text
वर्ग गणक {
    फलन गणना(संख्या: पूर्णांक) -> पूर्णांक { लौटाओ संख्या; }
}

वर्ग गुणनफलगणक विस्तार गणक {
    अधिलेखन फलन गणना(संख्या: पूर्णांक) -> पूर्णांक {
        यदि (संख्या <= १) { लौटाओ १; }
        लौटाओ संख्या * यह.गणना(संख्या - १);
    }
}

फलन मुख्य() -> शून्य {
    स्थिर वस्तु: गणक = नया गुणनफलगणक();
    लिखो(वस्तु.गणना(५));
}
```

This prints `१२०`: the base-typed variable dispatches to the derived method.
`examples/factorial-objects.bhash` adds console input and the existing 0–20 guard.
`examples/objects.bhash` demonstrates private/protected fields, constructors,
mutation, base calls and inheritance with a small account model.

**हिन्दी:** `१२०` छपता है क्योंकि मूल प्रकार वाला संदर्भ व्युत्पन्न विधि चलाता है। `examples/factorial-objects.bhash` कंसोल और ०–२० सीमा जोड़ता है। `examples/objects.bhash` छोटे खाते से निजी/संरक्षित क्षेत्र, निर्माता, बदलाव, मूल कॉल और विरासत दिखाता है।

## Fields and constructors

```text
वर्ग व्यक्ति {
    निजी मान नाम: पाठ;
    स्थिर सीमा: पूर्णांक = १०;

    निर्माता(नाम: पाठ) {
        यह.नाम = नाम;
    }

    फलन परिचय() -> पाठ { लौटाओ यह.नाम; }
}
```

- Members and constructors are public by default; access modifiers are optional.

  **हिन्दी:** सदस्य और निर्माता सामान्यतः सार्वजनिक हैं। पहुँच चिह्न देना वैकल्पिक है।
- Classes may overload constructors by parameter types. Without any declaration, a public zero-argument
  constructor is synthesized. Constructors are not inherited.

  **हिन्दी:** निर्माता मापदंड प्रकारों से अलग हो सकते हैं। कोई निर्माता न लिखने पर बिना तर्क का सार्वजनिक निर्माता बनता है। निर्माता विरासत में नहीं मिलते।
- A derived constructor can write `निर्माता(...) : आधार(...) { ... }`. If omitted,
  the base initializer is `आधार()`; its argument count and access must be valid.

  **हिन्दी:** व्युत्पन्न निर्माता `निर्माता(...) : आधार(...) { ... }` लिख सकता है। छोड़ने पर `आधार()` चलता है। तर्क संख्या और पहुँच मान्य हों।
- The base constructor finishes first, then the current class's fields initialize
  in declaration order, then its constructor body runs. This order is enforced on
  both backends rather than relying on their different host-language defaults.

  **हिन्दी:** पहले मूल निर्माता पूरा होता है, फिर वर्तमान वर्ग के क्षेत्र घोषणा क्रम में आरंभ होते हैं, फिर उसका निर्माता शरीर चलता है। दोनों backend यही क्रम लागू करते हैं।
- Mutable fields without an initializer default to `०`, `०.०`, `असत्य`, empty text,
  or `रिक्त`, according to type. Immutable fields require an initializer and cannot
  be reassigned, including in constructors.

  **हिन्दी:** बिना आरंभक के बदलने वाले क्षेत्र को प्रकार अनुसार `०`, `०.०`, `असत्य`, खाली पाठ या `रिक्त` मिलता है। अचल क्षेत्र को आरंभक चाहिए और निर्माता भी उसे दोबारा नहीं लिख सकता।
- Field initializers support constant expressions, not names, calls, `यह`, `आधार`
  or `नया`. Put object construction and parameter-dependent initialization in the
  constructor using mutable fields. Blank immutable fields assigned in constructors
  are not yet supported.

  **हिन्दी:** क्षेत्र आरंभक में स्थिर व्यंजक लिखें। नाम, कॉल, `यह`, `आधार` और `नया` नहीं हैं। मापदंड पर निर्भर काम निर्माता में बदलने वाले क्षेत्र से करें। खाली अचल क्षेत्र को निर्माता में भरना अभी उपलब्ध नहीं है।
- Constructor parameters are immutable locals, just like function parameters.
  They may shadow field names; `यह.नाम = नाम;` disambiguates them.

  **हिन्दी:** निर्माता के मापदंड अचल स्थानीय नाम हैं। वे क्षेत्र का नाम ढक सकते हैं। `यह.नाम = नाम;` से अंतर दिखाएँ।
- During construction, `यह` may be used only as a direct field receiver. Calling
  instance methods on `यह`/`आधार` or passing/returning the incomplete object is
  rejected. This avoids virtual dispatch and object escape during initialization.
  Base-initializer arguments cannot access the current instance.

  **हिन्दी:** निर्माण के दौरान `यह` केवल सीधे क्षेत्र तक पहुँच के लिए है। अधूरी वस्तु भेजना या लौटाना और `यह`/`आधार` पर instance विधि बुलाना अस्वीकार है। मूल आरंभक के तर्क वर्तमान वस्तु नहीं पढ़ सकते।

## Methods, inheritance and visibility

- Methods use `फलन नाम(...) -> प्रकार { ... }`; `शून्य` remains valid for
  methods returning no value. Inside a method, fields and other methods can be
  named implicitly or through `यह`.

  **हिन्दी:** विधि `फलन नाम(...) -> प्रकार { ... }` लिखें। बिना परिणाम के `शून्य` दें। अंदर क्षेत्र और विधि सीधे या `यह` से लिख सकते हैं।
- Public/protected methods dispatch virtually. An inherited method must be
  overridden explicitly with `अधिलेखन`, preserving parameter and return types
  exactly and without reducing visibility. Additional parameter signatures may overload a method.

  **हिन्दी:** सार्वजनिक/संरक्षित विधियाँ वास्तविक वस्तु के अनुसार चुनी जाती हैं। उसी हस्ताक्षर के लिए `अधिलेखन` दें और मापदंड, परिणाम तथा पहुँच बनाए रखें। अन्य हस्ताक्षर ओवरलोड हो सकते हैं।
- `आधार.कार्यनाम(...)` directly invokes the parent implementation; `यह.कार्यनाम(...)`
  uses normal virtual dispatch. Both can access inherited members when permitted.

  **हिन्दी:** `आधार.कार्यनाम(...)` मूल कार्यान्वयन सीधे बुलाता है। `यह.कार्यनाम(...)` सामान्य virtual dispatch करता है। अनुमति होने पर दोनों विरासत सदस्य देख सकते हैं।
- Private members are accessible only within their declaring class. Protected
  members are accessible there and in subclasses; subclass access through another
  instance requires a receiver of that subclass type (or a further subtype).

  **हिन्दी:** निजी सदस्य केवल घोषक वर्ग में दिखते हैं। संरक्षित सदस्य उपवर्ग में भी दिखते हैं। दूसरी वस्तु पर उपवर्ग से पहुँच के लिए receiver उसी उपवर्ग या उसके उपप्रकार का हो।
- Private constructors can be used only inside their declaring class; protected
  constructors can additionally be invoked by a derived base initializer.

  **हिन्दी:** निजी निर्माता केवल उसके वर्ग में बुलाएँ। संरक्षित निर्माता को व्युत्पन्न मूल आरंभक भी बुला सकता है।
- Fields cannot hide inherited members. Methods may add overloads, but inherited
  signatures require explicit overrides. Private methods cannot be overridden. Cycles, unknown parents, duplicate classes and invalid signatures
  produce Bhash source-located diagnostics.

  **हिन्दी:** क्षेत्र विरासत सदस्य नहीं छिपा सकते। विधि नया ओवरलोड जोड़ सकती है। समान विरासत हस्ताक्षर में स्पष्ट अधिलेखन चाहिए। निजी विधि नहीं बदली जा सकती। चक्र, अज्ञात मूल, दोहरा वर्ग और गलत हस्ताक्षर पर स्रोत निदान मिलता है।
- Member names and class names follow the existing NFC identifier normalization.

  **हिन्दी:** वर्ग और सदस्य नाम पहचानकर्ता सामान्यीकरण के नियम मानते हैं।

## References, access and lifetime

Class names can be used as variable, field, parameter and return types. A derived
instance can be assigned to a base-class binding or passed/returned where its base
type is expected. Downcasts and implicit base-to-derived conversions are rejected.
`स्थिर` prevents rebinding a reference; it does not freeze the referenced object.

**हिन्दी:** वर्ग नाम चर, क्षेत्र, मापदंड और परिणाम के प्रकार हो सकते हैं। व्युत्पन्न वस्तु को मूल प्रकार में रख या भेज सकते हैं। उल्टा रूपांतरण अपने आप नहीं होता। `स्थिर` संदर्भ फिर बाँधने से रोकता है। वस्तु के भीतर के बदलने योग्य क्षेत्र फिर भी बदल सकते हैं।

`.` accesses fields or calls methods and can be chained:
`पात्र.लो().स्वयं().संख्या`. Assignment targets must be mutable variables or fields.
Receivers evaluate once and are null-checked before call arguments or assignment
right-hand sides execute. A null dereference reports a concise runtime error and
returns exit status 1 on all three targets.

**हिन्दी:** `.` क्षेत्र पढ़ता और विधि बुलाता है। `पात्र.लो().स्वयं().संख्या` जैसे क्रम मान्य हैं। केवल बदलने वाला चर या क्षेत्र लिखें। receiver एक बार चलता है और तर्क या दायाँ व्यंजक चलने से पहले null जाँचा जाता है। null पहुँच पर त्रुटि और exit status 1 मिलता है।

`रिक्त` is assignable only to class-reference types. Equality/inequality compare
object identity; comparisons with null and between related class types are allowed.
Unrelated class types cannot be compared. Text retains its existing value equality.
`लिखो` accepts primitive values, so print an object's field or method result rather
than the object reference itself. Memory is managed by the host JVM/.NET GC.

**हिन्दी:** वर्ग संदर्भ में `रिक्त` रख सकते हैं। संबंधित वर्गों की समानता पहचान से है, पाठ की समानता मान से। असंबंधित वर्गों की तुलना त्रुटि है। `लिखो` में वस्तु का क्षेत्र या आदिम परिणाम दें। JVM/.NET में स्मृति होस्ट GC सँभालता है।

## Run the examples

```bash
mvn clean verify
java -jar target/bhash-compiler-0.1.0-SNAPSHOT.jar run examples/objects.bhash
java -jar target/bhash-compiler-0.1.0-SNAPSHOT.jar run examples/objects.bhash --target clr
java -jar target/bhash-compiler-0.1.0-SNAPSHOT.jar run examples/factorial-objects.bhash --target clr
```

Interfaces, generics, overloads and static members are specified in [v0.3 extensions](contracts-generics.md).
Abstract/final classes, casts, properties, arrays and JavaScript-style dynamic fields remain future extensions.

**हिन्दी:** [अनुबंध, सामान्य प्रकार, ओवरलोड और साझा सदस्य](contracts-generics.md) अलग संदर्भ में हैं। आज उपलब्ध casts और सरणियों के लिए [प्रकार](types-conversions.md) और [संग्रह](collections.md) देखें।
