# मूल-मशीन बिट क्रियाएँ और चयन (Native Bitwise Operations and Selection)

Linux x86-64 / LLVM 18 पर bitwise operators, shifts और `चयन` statements उपलब्ध हैं।
Generated executables को JVM/.NET की आवश्यकता नहीं है। [पूरा GC उदाहरण](../examples/native/bitwise-selection.bhash),
[bitboard उदाहरण](../examples/bitboards.bhash) और [interactive चयन](../examples/switch-console.bhash) देखें।

## बिट क्रियाएँ (Bitwise Operations)

`&` (AND), `|` (OR), `^` (XOR) और `~` (NOT) signed 64-bit `पूर्णांक` के सभी bits पर
काम करते हैं। `बाइट` operands पहले `पूर्णांक` में promote होते हैं। परिणाम भी `पूर्णांक`
है; इसलिए `~परिवर्तित<बाइट>(२५५)` का परिणाम `-२५६` है। Boolean, decimal, text और
object operands के लिए मौजूदा shared type checker वही errors देता है।

```bhash
कार्य मुख्य() -> शून्य {
    स्थिर पट्ट: पूर्णांक = (१ << ०) | (१ << ६३);
    लिखो((पट्ट & १)!=०,पट्ट >>> ६३,~०);
}
```

```text
सत्य १ -१
```

Binary bitwise operators दोनों operands को एक बार, बाएँ से दाएँ evaluate करते हैं।
`&&`/`||` का मौजूदा short-circuit व्यवहार अलग है। Precedence और associativity JVM/CLR
के मौजूदा Bhash parser/checker से मिलते हैं; parentheses से intent स्पष्ट कर सकते हैं।

## सरकाव (Shifts)

| Operator | व्यवहार |
|---|---|
| `<<` (Left Shift) | बाईं ओर shift; ऊपरी bits गिरते हैं और नीचे zero आते हैं |
| `>>` (Arithmetic Right Shift) | दाईं ओर shift; sign bit दोहरता है |
| `>>>` (Logical Right Shift) | दाईं ओर shift; ऊपर zero आते हैं |

Distance हमेशा `गिनती & 63` है, इसलिए `१ << ६४` = `१`, `१ << ६५` = `२`, और
`१ << -१` = `-९२२३३७२०३६८५४७७५८०८`। `-१ >> १` = `-१`, जबकि
`-१ >>> १` = `९२२३३७२०३६८५४७७५८०७`। LLVM shift से पहले explicit mask लगाया जाता है,
जिससे negative या बहुत बड़े counts भी defined हैं। Left shift पर signed-overflow
assumptions नहीं जोड़ी जातीं; सभी 64 bits का JVM/CLR व्यवहार सुरक्षित रहता है।

## चयन (Selection / Switch)

`चयन` selector को केवल एक बार evaluate करता है। Supported selectors `पूर्णांक`,
`बाइट`, `पाठ` और `तार्किक` हैं। `विकल्प` constant labels हैं; लगातार labels एक body
साझा कर सकते हैं। चुनी हुई body समाप्त होने पर selection स्वतः समाप्त होता है।
`शेष` केवल तब चलता है जब कोई विकल्प नहीं मिलता, और वह source में कहीं भी हो सकता है।
No-match तथा बिना `शेष` का चयन सामान्य रूप से आगे बढ़ता है। खाली चयन भी selector
को evaluate करता है। प्रत्येक section का अलग local scope है।

```bhash
कार्य वर्णन(क: पूर्णांक) -> पाठ {
    चयन (क) {
        शेष: लौटाओ "अन्य";
        विकल्प १:
        विकल्प २: लौटाओ "एक या दो";
    }
}
कार्य मुख्य() -> शून्य { लिखो(वर्णन(२)); }
```

Text matching exact ordinal value equality है: heap text और literal text समान value
पर match करते हैं। Empty text, embedded NUL और emoji सुरक्षित हैं; normalization
नहीं होता। Duplicate labels, incompatible label types, duplicate defaults, scope leaks
और invalid control flow पर मौजूदा JVM/CLR diagnostics सुरक्षित हैं। Enum declarations
और user-defined generics इस native milestone में शामिल नहीं हैं।

## विराम, जारी और सफाई (Break, Continue and Cleanup)

`विराम` सबसे पास के enclosing loop या selection से निकलता है। `जारी` सबसे पास के
loop की अगली iteration पर जाता है; बीच के selections उसके target नहीं बनते। Nested
`जबतक` और `प्रत्येक` में भी यही नियम हैं। `लौटाओ`, `फेंको`, `पकड़ो` और `अंततः` के
existing completion rules लागू रहते हैं; allocating cleanup pending text return को
सुरक्षित रखता है और cleanup का नया throw पुराने completion को replace कर सकता है। `अंततः` से return
करने पर मौजूदा shared checker error देता है।

Selector के लिए compiler अलग binding और, text के लिए, writable GC root रखता है।
Case locals तथा selector roots सामान्य exit और break/continue पर साफ होते हैं। Protected
callbacks से बाहर जाने वाले jumps existing completion dispatch से जाते हैं, ताकि intervening
`अंततः` चलें और abandoned roots हटें। Selection केवल break completion स्वीकार करता है।

## जाँच (Validation)

Shared fixtures JVM/CLR/native outputs को golden results से मिलाते हैं। Shift tests में
zero, signed limits, high-bit patterns और 96 value/count combinations हैं। अन्य cases
precedence, eager left-to-right evaluation, grouped labels, default position, single
selector evaluation, scopes, nested selection/loops और foreach targets जाँचते हैं।

1,000 iterations का GC example text selection, returns, throws, catches, breaks और
allocating finally blocks को मिलाता है। Linux tests LLVM verification तथा ASan/UBSan
के साथ चलते हैं। Clean deployment container में example JVM/.NET के बिना चलता है।
