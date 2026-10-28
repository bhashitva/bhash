# मूल-मशीन फ़ाइलें (Native File I/O)

Linux x86-64 / LLVM 18 पर `मानक.फ़ाइल` के text operations उपलब्ध हैं। बने हुए
executable को JVM या .NET की आवश्यकता नहीं है। [पूरा उदाहरण](../examples/native/files.bhash)
UTF-8 JSON फ़ाइल पढ़ता/लिखता है और failures में `अंततः` चलाता है।

## समर्थित (Is Supported), है (Is Regular File)

`फ़ाइल.समर्थित() -> तार्किक` native पर सत्य लौटाता है।
`फ़ाइल.है(पथ: पाठ) -> तार्किक` नियमित फ़ाइल के लिए सत्य, directory या missing path
के लिए असत्य देता है। Symlinks OS के साधारण नियमों से follow होते हैं। Inaccessible
paths भी OS के अनुसार असत्य दे सकते हैं। `है` और अगली operation के बीच फ़ाइल बदल सकती है।
Browser में समर्थित असत्य है; बाकी फ़ाइल operations catchable unsupported error देती हैं।

## पाठपढ़ो (Read Text), पाठलिखो (Write Text)

```bhash
आयात मानक.फ़ाइल उपनाम फ;
कार्य मुख्य() -> शून्य {
    फ.पाठलिखो("state.txt", "भारत😀\r\n");
    लिखो(फ.पाठपढ़ो("state.txt"));
}
```

`पाठपढ़ो(पथ: पाठ) -> पाठ` पूरी फ़ाइल strict UTF-8 में पढ़ता है।
`पाठलिखो(पथ: पाठ, मानक: पाठ) -> शून्य` नई फ़ाइल बनाता या पुरानी सामग्री replace करता है।
यह append या atomic replacement नहीं है। BOM/newline अपने-आप न जुड़ते हैं न हटते हैं;
CRLF, embedded NUL और supplementary Unicode characters सुरक्षित रहते हैं।
Paths में Unicode मान्य है, लेकिन खाली path और embedded NUL अमान्य हैं। Relative paths
process की working directory से resolve होते हैं। Parent directory पहले बनाएँ।

सीमा **16 MiB encoded bytes** है, characters नहीं; ठीक 16 MiB मान्य है। Read limit actual
stream पर लागू है, केवल पहले मिले file size पर नहीं। Write input/path validation truncation
से पहले होती है। OS write/close failure से partial output रह सकता है; durability/fsync की गारंटी नहीं है।
OS permissions और symlink behavior लागू हैं; यह API filesystem sandbox नहीं है।

## त्रुटियाँ (Errors)

ये catchable `अपवाद` messages JVM/CLR से मेल खाते हैं:

| स्थिति | संदेश |
| --- | --- |
| खाली/NUL path | `अमान्य फ़ाइल पथ।` |
| missing/inaccessible/unreadable source | `फ़ाइल पढ़ी नहीं जा सकी।` |
| invalid UTF-8 file | `अमान्य UTF-8 बाइटबफ़र।` |
| encoded file >16 MiB | `फ़ाइल १६ MiB सीमा से बड़ी है।` |
| destination/open/write/close failure | `फ़ाइल लिखी नहीं जा सकी।` |

OS-specific permissions, special files और concurrent changes का परिणाम बदल सकता है। Error
category/message portable हैं; host OS error strings public contract नहीं हैं।

## स्मृति और handles (Memory and Handles)

Read buffers और path copies GC-managed हैं और precise writable roots से सुरक्षित हैं।
Read handle exception-cleanup stack में पंजीकृत है और limit/validation failures तथा
unwinding पर बंद होता है। Write में validation/allocation open से पहले पूरी होती है;
write और close के बीच GC allocation नहीं होती। Success और failure दोनों पर handles बंद होते हैं।
Handles user code तक नहीं पहुँचते; programmer को manual close/free नहीं करना पड़ता।

`बाइटपढ़ो`, `बाइटलिखो` और `बाइटबफ़र` अब native पर भी उपलब्ध हैं;
[बाइट और binary I/O guide](native-bytes.md) देखें। वही 16 MiB और path/error नियम लागू हैं।

## जाँच (Validation)

Linux CI native/JVM/CLR output, Unicode filenames, BOM/NUL/CRLF, overwrite/empty files,
missing paths, directories, malformed UTF-8 और exact/over-limit byte sizes की तुलना करती है।
Runtime tests debug/optimized और ASan/UBSan में strict UTF-8 rejection, repeated oversized
reads, `/dev/full` close failures, descriptor counts और forced-GC cleanup जाँचते हैं।
Standalone JSON/file example clean container में JVM/.NET के बिना चलता है।
