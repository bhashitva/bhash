# Selection statements (v0.4)

`चयन` selects a section by value, `विकल्प` introduces a case, and `शेष` supplies an
optional default. This statement works on JVM, CLR and browser WebAssembly.

**हिन्दी:** `चयन` मान से खंड चुनता है, `विकल्प` उसका लेबल है और वैकल्पिक `शेष` किसी मेल के बिना चलता है। यह JVM, CLR और ब्राउज़र WebAssembly पर काम करता है।

```text
चयन (संख्या) {
    विकल्प ०:
        लिखो("शून्य");
        विराम;
    विकल्प १:
    विकल्प २:
        लिखो("एक या दो");
    शेष:
        लिखो("अन्य");
}
```

For `संख्या = १`, this prints only `एक या दो`. Sections do not fall through, so
`विराम` at the end of a section is optional. Consecutive `विकल्प` labels share one
body. A section may contain ordinary statements or an explicit braced block.

**हिन्दी:** `संख्या = १` पर केवल `एक या दो` छपता है। खंड से अगले खंड में निष्पादन अपने आप नहीं जाता, इसलिए अंत में `विराम` वैकल्पिक है। लगातार `विकल्प` लेबल एक शरीर साझा करते हैं। खंड में सामान्य कथन या स्पष्ट कोष्ठक वाला ब्लॉक हो सकता है।

## Values and labels

- The selector is evaluated exactly once, even for an empty selection.

  **हिन्दी:** खाली चयन में भी चुना जाने वाला व्यंजक ठीक एक बार चलता है।
- Supported selector types are `पूर्णांक`, `पाठ` and `तार्किक`. Integers use their full
  signed 64-bit range. Reals, objects/interfaces, `रिक्त` and void results are rejected.

  **हिन्दी:** समर्थित प्रकार `पूर्णांक`, `पाठ` और `तार्किक` हैं। पूर्णांक पूरी signed 64-bit सीमा उपयोग करता है। दशमलव, वस्तु, अनुबंध, `रिक्त` और `शून्य` परिणाम अस्वीकार हैं।
- A label must be a literal of the selector's exact type: a signed/unsigned integer
  literal, a string literal, `सत्य`, or `असत्य`. Integer `+` and `-` signs are allowed.
  Variables, constant bindings, arithmetic expressions, ranges and patterns are not labels.

  **हिन्दी:** लेबल चयनक के ठीक उसी प्रकार का literal हो। चिह्न सहित पूर्णांक, पाठ literal, `सत्य` या `असत्य` लिखें। बंधन, गणितीय व्यंजक, परास और pattern मान्य नहीं हैं।
- Integer labels are compared by numeric value. `१`, `1` and `+१` are duplicates;
  `-०` and `०` are duplicates. Range checks include the minimum signed integer literal.

  **हिन्दी:** पूर्णांक लेबल संख्यात्मक मान से मिलते हैं। `१`, `1` और `+१` एक ही मान हैं। `-०` और `०` भी एक हैं। सीमा में न्यूनतम signed मान शामिल है।
- Text labels compare exact decoded Unicode values, using the same ordinal/value equality
  as ordinary text comparisons. Escaped and directly written forms of the same string
  are duplicates. Strings are not NFC-normalized. Unpaired surrogate escapes are rejected.

  **हिन्दी:** पाठ लेबल decode किए गए Unicode मान की क्रमिक समानता से मिलते हैं। सीधे और escape से लिखे समान पाठ दोहराव हैं। पाठ पर NFC लागू नहीं होता। अकेला surrogate escape अस्वीकार है।
- Duplicate labels and multiple `शेष` sections are compile-time errors.

  **हिन्दी:** दोहरा लेबल और एक से अधिक `शेष` खंड संकलन त्रुटि हैं।
- `शेष` can appear anywhere, but runs only when no case matches. Without it, an unmatched
  value does nothing. An empty selection is valid. `शेष` does not group with case labels;
  an empty case before `शेष` does nothing when matched.

  **हिन्दी:** `शेष` कहीं भी लिखा जा सकता है, पर किसी लेबल के न मिलने पर चलता है। इसके बिना कोई मेल न मिले तो कुछ नहीं होता। खाली चयन मान्य है। `शेष` अन्य लेबल से नहीं जुड़ता, इसलिए उसके पहले खाली लेबल मिलने पर कुछ नहीं चलता।

## Scope and control flow

Each section has its own scope. Two sections may declare a local with the same name;
those bindings are unavailable outside their section. Existing shadowing rules apply.

**हिन्दी:** हर खंड का अपना दायरा है। दो खंडों में समान स्थानीय नाम हो सकता है और वह अपने खंड के बाहर उपलब्ध नहीं है। सामान्य shadowing नियम लागू हैं।

`विराम` exits the nearest enclosing `चयन` or `जबतक`. A loop nested in a selection
therefore receives its own `विराम`; a selection nested in a loop receives its own.
`जारी` always continues the nearest enclosing loop, including when used inside a selection.
A selection without an enclosing loop cannot use `जारी`. Neither keyword is valid as a
standalone statement outside an eligible construct.

**हिन्दी:** `विराम` सबसे भीतर के `चयन` या `जबतक` से बाहर आता है। `जारी` हमेशा निकटतम चक्र की अगली बारी पर जाता है, चयन के अंदर से भी। बाहर चक्र न होने पर `जारी` अमान्य है। दोनों को उपयुक्त खंड के बाहर अकेला नहीं लिख सकते।

`लौटाओ` exits the enclosing function as usual. Return-path analysis recognizes a selection
as returning on every path only when it has a `शेष` section and every section returns.
A section containing a reachable `विराम` can complete normally. Even a boolean selection
with both boolean labels requires `शेष` for this conservative return-path analysis.
Statements following an unconditional return, break or continue in a section are unreachable
and rejected. Code after a selection whose every section returns is also unreachable.

**हिन्दी:** `लौटाओ` आसपास के फलन से बाहर आता है। हर पथ पर वापसी की जाँच में चयन तभी पूरा माना जाता है जब `शेष` हो और सभी खंड लौटें। पहुँच योग्य `विराम` खंड को सामान्य रूप से समाप्त कर सकता है। दोनों तार्किक लेबल होने पर भी इस जाँच में `शेष` चाहिए। बिना शर्त वापसी, विराम या जारी के बाद का कथन अस्वीकार है। सभी खंड लौटें तो चयन के बाद का कथन भी पहुँच योग्य नहीं है।

## Example

[The console example](../examples/switch-console.bhash) reads an integer, uses grouped
integer labels to return a description, then selects again using the returned text.

**हिन्दी:** [कंसोल उदाहरण](../examples/switch-console.bhash) पूर्णांक पढ़ता है, साथ रखे लेबलों से वर्णन लौटाता है और फिर उस पाठ पर दूसरा चयन करता है।

```sh
mvn package
java -jar target/bhash-compiler-0.1.0-SNAPSHOT.jar run examples/switch-console.bhash
java -jar target/bhash-compiler-0.1.0-SNAPSHOT.jar run examples/switch-console.bhash --target clr
java -jar target/bhash-compiler-0.1.0-SNAPSHOT.jar compile examples/switch-console.bhash --target wasm-browser -o target/switch-web
```

Serve the browser output over HTTP as described in [browser target](wasm-browser.md).
The new words `चयन`, `विकल्प` and `शेष` are reserved in v0.4; rename existing identifiers
with those exact spellings. Longer identifiers such as `शेषराशि` remain valid.

**हिन्दी:** [ब्राउज़र लक्ष्य](wasm-browser.md) के अनुसार आउटपुट HTTP से परोसें। v0.4 से `चयन`, `विकल्प` और `शेष` आरक्षित हैं। इन्हीं वर्तनियों वाले पुराने नाम बदलें। `शेषराशि` जैसे लंबे नाम मान्य हैं।
