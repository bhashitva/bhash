# मूल-मशीन कंसोल इनपुट (Native Console Input)

Linux x86-64 / LLVM 18 पर मौजूदा global functions `पाठपढ़ो()` और `पढ़ो()` उपलब्ध हैं।
Applications को JVM/.NET की आवश्यकता नहीं है। [पूरा उदाहरण](../examples/native/console.bhash)
और [interactive factorial](../examples/factorial-console.bhash) native executable बन सकते हैं।

## पाठपढ़ो (Read Text Line)

`पाठपढ़ो() -> पाठ` standard input की अगली UTF-8 पंक्ति देता है। Spaces, tabs, emoji,
combining characters, embedded NUL और शुरुआत का BOM सुरक्षित रहते हैं। LF, CRLF और CR
line endings हटाए जाते हैं; खाली पंक्ति का परिणाम खाली पाठ है। अंतिम पंक्ति बिना line
ending के भी लौटती है। उसके बाद हर call पर catchable `इनपुट समाप्त: पाठ अपेक्षित था।`
मिलता है। Input पढ़ने से पहले output flush होता है, ताकि prompts दिखाई दें। CR पर
call तुरंत लौटता है; अगले byte की प्रतीक्षा करके CRLF पहचानने की आवश्यकता नहीं है।

Malformed UTF-8 या I/O failure पर `UTF-8 पाठ पढ़ा नहीं जा सका।` मिलता है।
UTF-8 decoding केवल पूरी पढ़ी हुई पंक्ति पर होती है। Malformed पंक्ति consume होती है;
अगला call अगली पंक्ति पढ़ सकता है। JVM/CLR में भी यही line-local decoding लागू है:
पहले buffered decoders बाद की malformed पंक्ति के कारण पहले valid read को fail कर सकते थे।
Browser CLR input अभी भी host से दिए गए decoded text को पढ़ता है।

## पढ़ो (Read Integer Line)

`पढ़ो() -> पूर्णांक` एक पूरी पंक्ति से signed 64-bit integer पढ़ता है। ASCII `+`/`-`
optional हैं। Digits या तो सभी ASCII (`0–9`) या सभी Devanagari (`०–९`) हों:
`+42`, `-१२३`, `०` मान्य हैं; `1२`, `१2`, `1.5`, `0x10`, underscores और Arabic-Indic
अंक मान्य नहीं हैं। Range `-9223372036854775808` से `9223372036854775807` inclusive है।
Leading zeros स्वीकार हैं। सीधे `लिखो` करने पर सामान्य Devanagari numeric display मिलता है।

दोनों सिरों पर Java `Character.isWhitespace` के अनुरूप whitespace हटता है। NBSP
(U+00A0), figure space (U+2007), narrow NBSP (U+202F) और BOM whitespace नहीं माने जाते।
बीच का whitespace स्वीकार नहीं है। यह console का मौजूदा JVM/CLR contract है;
standard-library text trimming के Unicode White_Space contract से अलग है।

| परिस्थिति | Catchable Hindi error |
|---|---|
| खाली पंक्ति, गलत syntax या mixed digit scripts | `अमान्य इनपुट: एक पंक्ति में एक पूर्णांक लिखें (0-9 या ०-९)।` |
| मान signed 64-bit range से बाहर | `पूर्णांक 64-बिट सीमा से बाहर है।` |
| EOF, कोई अगली पंक्ति नहीं | `इनपुट समाप्त: एक पूर्णांक अपेक्षित था।` |
| Malformed UTF-8 या input I/O failure | `कंसोल से UTF-8 इनपुट पढ़ा नहीं जा सका।` |

पहले syntax जाँचा जाता है, फिर range: बहुत बड़े digits के बाद गलत character होने पर
syntax error मिलता है। Failed numeric read भी पूरी पंक्ति consume करता है।

```bhash
कार्य मुख्य() -> शून्य {
    लिखो("नाम लिखें:");
    स्थिर नाम: पाठ = पाठपढ़ो();
    प्रयास {
        स्थिर संख्या: पूर्णांक = पढ़ो();
        लिखो(नाम,संख्या);
    } पकड़ो (गलती: अपवाद) {
        लिखो(गलती.संदेश);
    }
}
```

```sh
java -jar target/bhash-compiler-0.1.0-SNAPSHOT.jar compile examples/native/console.bhash --target native -o target/console
printf 'भारत😀\n+४२\n' | ./target/console
```

## स्मृति और जाँच (Memory and Validation)

Native reader का growable UTF-8 buffer precise GC roots में रहता है। Buffer growth,
returned text, nested calls और allocating `अंततः` में live text सुरक्षित है। Exceptions
पर temporary roots unwind होते हैं; raw malloc scratch या borrowed interior pointers
allocation के पार नहीं रहते। stdin application का है; runtime उसे बंद नहीं करता। कोई
नई arbitrary line-length limit नहीं जोड़ी गई; उपलब्ध memory और existing text limits लागू हैं।

Shared exact-output fixtures JVM/CLR/native पर line endings, EOF, malformed UTF-8,
error recovery, digit scripts, whitespace और integer boundaries जाँचते हैं। 100,000-code-point
line तथा 1,000 successful/failed reads forced GC के साथ चलते हैं। अलग pipe test CR के
बाद अगला byte भेजे बिना output की प्रतीक्षा करता है, ताकि interactive blocking का पता चले।
C runtime tests debug/optimized और Linux ASan/UBSan में closed-stdin failures तथा root
cleanup जाँचते हैं। Clean deployment container में redirected input JVM/.NET के बिना चलता है।
