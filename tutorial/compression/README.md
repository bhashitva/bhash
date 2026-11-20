# संपीड़न और प्रकारयुक्त बाहरी पुस्तकालय (Compression and typed C libraries)

संपीड़न (Compression) डेटा को कम बाइटों में रखने की प्रक्रिया है। भाष का
`मानक.संपीड़न` एक ही zlib 1.4 प्रदाता से JVM, .NET और native पर जेडलिब तथा
गज़िप डेटा बनाता और खोलता है। यह केवल byte buffers पर काम करता है; फ़ाइल,
HTTP और डेटाबेस से मिले बफ़र भी इसी API में दिए जा सकते हैं।

इस सुविधा के लिए compiler वितरण में आपके लक्ष्य के सत्यापित provider assets
होने चाहिए। `java -jar bhash-compiler.jar ffi doctor compression` से सामग्री
जाँचें। यह आदेश कोई native code नहीं चलाता। अधूरी developer build में BH8102
मिले तो provider सहित वितरण लें; compiler अपने आप इंटरनेट से code नहीं लाता।

## पहला पूरा उदाहरण (Complete example)

[चलाने योग्य उदाहरण](roundtrip.bhash) पाठ को UTF-8 बफ़र में
बदलता है, गज़िप में संपीड़ित करता है और फिर मूल पाठ वापस प्राप्त करता है।
Result का हर विकल्प संभालना आवश्यक है; त्रुटि पर खाली बफ़र को सफलता मानना
उचित नहीं है।

```bhash
// bhash:null-safety=strict
आयात मानक.संपीड़न;
आयात मानक.संपीड़नप्रारूप;
फलन मुख्य() -> शून्य {
    स्थिर मूल = यूटीएफ८बफ़र("नमस्ते भारत 😀");
    मिलान (संपीड़न.संपीड़ितकरो(मूल, संपीड़नप्रारूप.गज़िप, ६)) {
        विकल्प मानक.परिणाम.असफल(स्थिर दोष): { लिखो(दोष.संदेश); }
        विकल्प मानक.परिणाम.सफल(स्थिर पैक): {
            मिलान (संपीड़न.विसंपीड़ितकरो(पैक, संपीड़नप्रारूप.गज़िप, १०२४)) {
                विकल्प मानक.परिणाम.असफल(स्थिर दोष): { लिखो(दोष.संदेश); }
                विकल्प मानक.परिणाम.सफल(स्थिर खुला): { लिखो(यूटीएफ८पाठ(खुला)); }
            }
        }
    }
}
```

```sh
java -jar bhash-compiler.jar run tutorial/compression/roundtrip.bhash --target jvm
java -jar bhash-compiler.jar run tutorial/compression/roundtrip.bhash --target clr
java -jar bhash-compiler.jar run tutorial/compression/roundtrip.bhash --target native
```

## विधियाँ और मापदंड (Methods and parameters)

`संपीड़ितकरो(डेटा: बाइटबफ़र, प्रारूप: संपीड़नप्रारूप, स्तर: पूर्णांक)`
`परिणाम<बाइटबफ़र, संपीड़नदोष>` लौटाता है। डेटा अधिकतम 8 MiB है; खाली बफ़र
स्वीकार है। स्तर 0 से 9 तक है: 0 बिना संपीड़न के framing बनाता है, 9 अधिक
संपीड़न का प्रयास करता है। निर्गत अधिकतम 16 MiB है। लौटे बफ़र का स्वामित्व
सामान्य भाष GC के पास है; उसे C release API से मुक्त नहीं करना पड़ता।

`विसंपीड़ितकरो(डेटा: बाइटबफ़र, प्रारूप: संपीड़नप्रारूप, अधिकतम: पूर्णांक)`
भी वही Result प्रकार लौटाता है। अधिकतम 1 से 16 MiB की **बाइट सीमा** है,
अक्षरों की सीमा नहीं। अविश्वसनीय compressed data के लिए अपनी जरूरत की छोटी
सीमा दें। खाली decoded data सफल है, लेकिन खाली compressed input दूषित है।

प्रारूप (Format) `संपीड़नप्रारूप.जेडलिब` या `संपीड़नप्रारूप.गज़िप` है।
गज़िप header में समय 0, OS 255 और कोई नाम/comment नहीं होता; समान input,
स्तर और प्रदाता से समान output मिलता है। यह ZIP archive या raw DEFLATE API
नहीं है। ठीक एक stream/member स्वीकार है; उसके बाद बची बाइटें, दूसरा gzip
member, खराब checksum और अधूरा input त्रुटि हैं।

## त्रुटियाँ (Errors)

`संपीड़नदोष` एक immutable अभिलेख है जिसके `कोड: संपीड़नदोषकोड` और
`संदेश: पाठ` क्षेत्र हैं। कोड पर निर्णय लें, संदेश उपयोगकर्ता को दिखाएँ।

| कोड | अर्थ |
| --- | --- |
| अवैधतर्क | गलत स्तर, सीमा या प्रारूप; legacy mode में null input |
| आगतसीमा | input 8 MiB से बड़ा है |
| निर्गतसीमा | decoded/encoded output की सीमा पार हुई |
| दूषितडेटा | framing, checksum, trailing bytes या truncated stream |
| शब्दकोशअसमर्थित | stream को पहले से तय dictionary चाहिए |
| संसाधनसीमा | प्रदाता का सीमित memory budget उपलब्ध नहीं है |

दूसरा उदाहरण (Error handling): `विसंपीड़ितकरो(पैक, प्रारूप, १)` पर वही मिलान
चलाएँ। एक बाइट से बड़ा मूल पाठ `निर्गतसीमा` देता है और कोई आंशिक output
नहीं लौटता। गलत input को कभी अपने-आप दूसरे format में खोलने का प्रयास नहीं
किया जाता। installation/ABI/contract की समस्या Result का business error नहीं,
BH8102/BH8105 diagnostic है।

त्रुटि दिखाने का फलन (Error presenter) [दूसरे पूरे उदाहरण](errors.bhash) में
भी है:

```bhash
फलन दिखाओ(दोष: संपीड़नदोष) -> शून्य {
    लिखो(दोष.कोड.नाम, ": ", दोष.संदेश);
}
```

## प्लेटफ़ॉर्म और जीवनकाल (Platforms and lifetime)

फ़ाइल उदाहरण (File compression) [files.bhash](files.bhash) को खाली अभ्यास
फ़ोल्डर से चलाएँ। वह `compression-demo-input.txt` और `compression-demo-output.gz`
बनाता या बदलता है। उदाहरण 1 MiB तक पढ़कर एक अतिरिक्त बाइट से सीमा जाँचता है;
पूरी अज्ञात फ़ाइल पहले memory में नहीं लाता। input और output अलग रखें।
`संसाधन` read handle को सफलता, त्रुटि और अपवाद में बंद करता है। यह bounded
whole-buffer compression है; सार्वजनिक streaming compression API अभी नहीं है।

Jupyter उदाहरण (Notebook example) [संपीड़न नोटबुक](../../editors/jupyter/compression.ipynb)
को Bhash kernel से खोलें और कक्ष क्रम से चलाएँ। buffer फलन में स्थानीय हैं;
notebook cells के बीच अभी scalar bindings ही स्थायी हैं।

Linux x64 (glibc 2.35+), Windows x64 (UCRT) और macOS 14+ Apple Silicon
वितरण के लक्ष्य हैं। JVM को Java 17+, .NET को समर्थित runtime चाहिए;
native executable को JVM/.NET नहीं चाहिए। Jupyter JVM provider का प्रयोग
करता है। अभी Android तथा Playground/browser में बाहरी C calls उपलब्ध नहीं
हैं; import/संपादन native module लोड नहीं करता, वास्तविक call पर स्पष्ट
diagnostic मिलता है। यह synchronous bounded operation है; async function
के अंदर call करने से भी dispatcher उस call के दौरान व्यस्त रहेगा।

GC सुरक्षा (GC safety) के लिए सभी बाइटें सीमा पर कॉपी होती हैं। C को भाष
heap का pointer नहीं मिलता। प्रदाता memory प्रति call 32 MiB और प्रति runtime
64 MiB तक सीमित है। opaque resources में explicit `बंदकरो()` सभी aliases
को बंद करता है; भूले संसाधनों की GC cleanup owner thread पर कतार से होती है,
collector के भीतर vendor destructor नहीं चलता।

अपना पुस्तकालय जोड़ने वाले लेखक [बाहरी binding मार्गदर्शिका](../../docs/typed-c-library-bindings.md)
और [स्वीकृत डिज़ाइन](../../docs/typed-c-interop-design.md) पढ़ें।
