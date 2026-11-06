# Bitwise operations (v0.6)

JVM, .NET CLR and browser .NET WebAssembly support the same seven operators on
`पूर्णांक`. Both binary operands, including a shift count, must be 64-bit integers;
`~` takes one integer. Results are integers. There are no implicit conversions or
bitwise Boolean overloads; use `&&`, `||` and `!` for Boolean logic.

**हिन्दी:** JVM, .NET CLR और WebAssembly में `पूर्णांक` पर वही सात संक्रियाएँ हैं। shift की गिनती समेत दोनों operands 64-bit पूर्णांक हैं और `~` एक पूर्णांक लेता है। परिणाम पूर्णांक है। तार्किक काम के लिए `&&`, `||` और `!` उपयोग करें। अंतर्निहित रूपांतरण या Boolean bitwise overload नहीं है।

| Operator | Meaning | Example | Result |
| --- | --- | --- | --- |
| `&` | Bitwise AND | `५ & ३` | `१` |
| `\|` | Bitwise OR | `५ \| २` | `७` |
| `^` | Bitwise XOR | `५ ^ ३` | `६` |
| `~` | Complement all 64 bits | `~०` | `-१` |
| `<<` | Left shift, discard high bits and fill low bits with zero | `१ << ३` | `८` |
| `>>` | Arithmetic right shift, fill high bits with the sign bit | `-८ >> २` | `-२` |
| `>>>` | Logical right shift, fill high bits with zero | `-१ >>> १` | `९२२३३७२०३६८५४७७५८०७` |

## Integer representation and shift counts

Integers remain signed 64-bit two's-complement values. A bitboard uses all 64 bits;
setting bit 63 produces a negative displayed value but preserves the entire bit
pattern. No new unsigned type or binary/hexadecimal literal syntax is introduced.

**हिन्दी:** पूर्णांक signed 64-bit two's complement रहता है। bitboard सभी ६४ बिट उपयोग करता है। बिट ६३ लगाने पर प्रदर्शित मान ऋणात्मक है लेकिन पूरा बिट क्रम बचता है। इससे नया unsigned प्रकार या binary/hex literal नहीं जुड़ता।

The effective shift distance is **`count & 63`**, in the range 0–63, for all three
shift operators. Counts are not range errors: 64 means zero, 65 means one, and
-1 means 63. Wide counts are masked before narrowing to a host count type.
A zero-distance shift preserves the original signed value, including `-१ >>> ६४`.

**हिन्दी:** तीनों shift में प्रभावी दूरी `count & 63` है। ६४ की दूरी शून्य, ६५ की एक और `-1` की ६३ है। बड़ी गिनती को होस्ट के प्रकार में छोटा करने से पहले mask किया जाता है। शून्य दूरी `-१ >>> ६४` जैसे मान को भी यथावत रखती है।

```text
फलन मुख्य() -> शून्य {
    लिखो(१ << ६३);        // -९२२३३७२०३६८५४७७५८०८
    लिखो(-१ >> १);       // -१
    लिखो(-१ >>> १);      // ९२२३३७२०३६८५४७७५८०७
    लिखो(१ << ६४);       // १
    लिखो(१ << -१);       // -९२२३३७२०३६८५४७७५८०८
}
```

Operands evaluate once, from left to right. `&`, `|` and `^` always evaluate both
operands; `&&` and `||` retain their short-circuit behavior.

**हिन्दी:** बाएँ से दाएँ हर operand एक बार चलता है। `&`, `|` और `^` दोनों चलाते हैं। `&&` और `||` आवश्यक होने पर ही दायाँ operand चलाते हैं।

## Precedence and parsing

Highest to lowest: grouping/calls/member access; unary `! ~ + -`; `* / %`;
`+ -`; `<< >> >>>`; `< <= > >=`; `== !=`; `&`; `^`; `|`; `&&`; `||`.
Binary operators associate left to right. Thus `१ + १ << ३` is `(१ + १) << ३`,
and `३२ >> १ >> २` is `(३२ >> १) >> २`.

**हिन्दी:** ऊपर दिए क्रम में पहले grouping, call और member access हैं, फिर एक operand वाली संक्रियाएँ, गुणा/भाग, जोड़/घटाव, shift, तुलना, समानता, bitwise AND/XOR/OR और तार्किक AND/OR। समान प्राथमिकता की द्विआधारी संक्रियाएँ बाएँ से दाएँ जुड़ती हैं। `१ + १ << ३` और `३२ >> १ >> २` में ऊपर के कोष्ठक देखें।

Parenthesize a mask before comparing it:

**हिन्दी:** तुलना से पहले mask की संक्रिया को कोष्ठक में रखें:

```text
यदि ((पट्ट & मुखौटा) != ०) { लिखो("बिट मौजूद है"); }
```

Without those parentheses, `पट्ट & मुखौटा != ०` compares `मुखौटा` to zero first,
then attempts integer AND with a Boolean, which is a type error.

**हिन्दी:** कोष्ठक के बिना `पट्ट & मुखौटा != ०` पहले `मुखौटा` और शून्य की तुलना करता है। फिर पूर्णांक और तार्किक मान पर AND लगाने से प्रकार त्रुटि आती है।

Characters within a shift operator must be adjacent: `८ >> १` is valid;
`८ > > १` and `८ >/*comment*/>१` are invalid. Nested generic closing angles
remain valid, including `पात्र<पात्र<पूर्णांक>>` and deeper `>>>` closers.
Expression type arguments must be followed by a call or static receiver, so
`क < ख >> १` compares `क` with `(ख >> १)` rather than treating `ख` as a type.
The parser recognizes contiguous shift operators in expression context while
retaining separate angle tokens for generic types. Package lowering and generic
specialization preserve the joined operator spelling and original source locations.

**हिन्दी:** shift चिह्नों के बीच खाली जगह या टिप्पणी नहीं हो सकती। `८ >> १` मान्य है और `८ > > १` अमान्य। सामान्य प्रकार के लगातार `>>` या `>>>` समापन मान्य रहते हैं। व्यंजक में प्रकार तर्क के बाद कॉल या साझा सदस्य का receiver चाहिए। `क < ख >> १` में `क` की तुलना `(ख >> १)` से है। parser और lowering चिह्नों के जुड़ाव और स्रोत स्थान सुरक्षित रखते हैं।

Compound assignments such as `&=` or `<<=` are not part of this addition. Write
`पट्ट = पट्ट & मुखौटा;`. Selection labels remain literals, not bitwise expressions.

**हिन्दी:** इस रूप में `&=` और `<<=` जैसे संयुक्त assignment नहीं हैं। `पट्ट = पट्ट & मुखौटा;` लिखें। चयन के लेबल literal हैं।

## Run the bitboard example

Update and rebuild the compiler before using the new syntax:

**हिन्दी:** नई वाक्यरचना उपयोग करने से पहले संकलक अपडेट करके बनाएँ:

```bash
git pull --ff-only
mvn clean package -DskipTests
java -jar target/bhash-compiler-0.1.0-SNAPSHOT.jar run examples/bitboards.bhash
java -jar target/bhash-compiler-0.1.0-SNAPSHOT.jar run examples/bitboards.bhash --target clr
java -jar target/bhash-compiler-0.1.0-SNAPSHOT.jar compile examples/bitboards.bhash --target wasm-browser -o target/bitboards-web
```

The [example](../examples/bitboards.bhash) sets bits 0 and 63, tests both squares,
counts occupied squares, toggles bit 0, and demonstrates both right shifts:

**हिन्दी:** [उदाहरण](../examples/bitboards.bhash) बिट ० और ६३ लगाता है, दोनों स्थान जाँचता है, कुल भरे स्थान गिनता है, बिट ० पलटता है और दोनों right shift दिखाता है:

```text
-९२२३३७२०३६८५४७७५८०७
सत्य सत्य
२
१ १
-१ -१ ९२२३३७२०३६८५४७७५८०७
१ २
```

Serve browser output over HTTP as described in [the browser guide](wasm-browser.md).
JVM compilation requires a full JDK 17+; CLR/browser toolchain requirements are
unchanged.

**हिन्दी:** [ब्राउज़र निर्देश](wasm-browser.md) के अनुसार HTTP से आउटपुट परोसें। JVM संकलन के लिए पूरा JDK 17+ चाहिए। CLR और ब्राउज़र के अपने उपकरण चाहिए।

As of v0.9, `बाइट` and `लघुपूर्णांक` operands promote to signed 64-bit
`पूर्णांक` before bitwise operations. The existing 64-bit shift/complement
semantics apply; explicitly narrow a result with `परिवर्तित<बाइट>` or
`परिवर्तित<लघुपूर्णांक>` when storing it in a smaller type. Narrowing is checked.

**हिन्दी:** v0.9 से `बाइट` और `लघुपूर्णांक` bitwise संक्रिया से पहले signed 64-bit `पूर्णांक` बनते हैं। shift और complement के 64-bit नियम लागू हैं। छोटे प्रकार में रखने के लिए `परिवर्तित<बाइट>` या `परिवर्तित<लघुपूर्णांक>` दें। सीमा जाँची जाती है।
