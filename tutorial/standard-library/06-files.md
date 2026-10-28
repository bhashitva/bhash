# फ़ाइलों के साथ काम (File I/O)

इन उदाहरणों को खाली अभ्यास फ़ोल्डर में चलाएँ। वे target/tutorial-files के भीतर निश्चित नामों की फ़ाइलें बनाते या बदलते हैं; उसी नाम की पुरानी फ़ाइल overwrite होगी। parent directory अपने-आप नहीं बनती। JVM/CLR और native Linux text operations पर OS permissions और साधारण path/symlink व्यवहार लागू हैं। Browser WASM में समर्थित() असत्य है और हर दूसरी फ़ाइल operation फ़ाइल तक पहुँचने से पहले अपवाद देती है।

[मानक पुस्तकालय का सूचकांक](README.md) · [पूरा चलाने योग्य उदाहरण](examples/06-files.bhash) · [पूरे उदाहरण का अपेक्षित आउटपुट](examples/06-files.expected.txt)

नीचे हर छोटा कोड-खंड अलग उदाहरण है। उसे इस ढाँचे में मुख्य() के भीतर रखें; एक साथ सभी उदाहरण चलाने के लिए ऊपर दी गई पूरी फ़ाइल प्रयोग करें।

```bhash
आयात मानक.फ़ाइल;
आयात मानक.पाठ्य;
कार्य मुख्य() -> शून्य {
    // नीचे दिए गए किसी एक उदाहरण का कोड यहाँ रखें।
}
```

<a id="file-capability-is-supported"></a>

## समर्थित (File Capability / Is Supported)

बताता है कि वर्तमान target फ़ाइल API उपलब्ध कराता है या नहीं। इस क्षमता-जाँच के आधार पर फ़ाइल वाले हिस्से को सुरक्षित ढंग से अलग करें।

**हस्ताक्षर:**

- `फ़ाइल.समर्थित() → तार्किक`

```bhash
लिखो(फ़ाइल.समर्थित());
```

**अपेक्षित आउटपुट — JVM/CLR:**

```text
सत्य
```

यह आउटपुट JVM/CLR का है; playground/browser पर असत्य मिलेगा। समर्थित होने का अर्थ यह नहीं कि हर पथ पर permission है। पूरा उदाहरण browser में समझाने वाला संदेश देकर लौट जाता है।

<a id="file-exists-is-regular-file"></a>

## है (File Exists / Is Regular File)

जाँचता है कि पथ एक नियमित फ़ाइल की ओर जाता है या नहीं। directory के लिए असत्य मिलता है। अनुपस्थित या जाँच में inaccessible फ़ाइल OS के अनुसार असत्य दे सकती है; invalid path पर अपवाद आता है।

**हस्ताक्षर:**

- `फ़ाइल.है(पथ: पाठ) → तार्किक`

```bhash
फ़ाइल.पाठलिखो("target/tutorial-files/exists.txt", "नमस्ते");
लिखो(फ़ाइल.है("target/tutorial-files/exists.txt"));
लिखो(फ़ाइल.है("target/tutorial-files"));
```

**अपेक्षित आउटपुट — JVM/CLR:**

```text
सत्य
असत्य
```

पहली पंक्ति केवल उदाहरण की fixture फ़ाइल बनाती है। है() के बाद अगली operation तक फ़ाइल बदल सकती है; इसलिए वास्तविक पढ़ने/लिखने पर भी अपवाद सँभालें।

<a id="read-text-file"></a>

## पाठपढ़ो (Read Text File)

पूरी फ़ाइल को strict UTF-8 पाठ के रूप में पढ़ता है। newline अपने-आप नहीं बदलता; अमान्य UTF-8 bytes पर अपवाद आता है।

**हस्ताक्षर:**

- `फ़ाइल.पाठपढ़ो(पथ: पाठ) → पाठ`

```bhash
फ़ाइल.पाठलिखो("target/tutorial-files/read.txt", "नमस्ते😀");
लिखो(फ़ाइल.पाठपढ़ो("target/tutorial-files/read.txt"));
```

**अपेक्षित आउटपुट — JVM/CLR:**

```text
नमस्ते😀
```

यह उदाहरण पहले अपनी fixture लिखता है। पढ़ने की सीमा १६ MiB है। फ़ाइल में UTF-8 BOM हो तो वह decode किए गए पाठ में बना रहता है।

<a id="write-text-file"></a>

## पाठलिखो (Write Text File)

पाठ को strict UTF-8 में नई या मौजूदा फ़ाइल में लिखता है। पुरानी सामग्री replace होती है; यह append नहीं है। कोई मान वापस नहीं मिलता।

**हस्ताक्षर:**

- `फ़ाइल.पाठलिखो(पथ: पाठ, मानक: पाठ) → शून्य`

```bhash
फ़ाइल.पाठलिखो("target/tutorial-files/write.txt", "पुराना");
फ़ाइल.पाठलिखो("target/tutorial-files/write.txt", "नया😀");
लिखो(फ़ाइल.पाठपढ़ो("target/tutorial-files/write.txt"));
```

**अपेक्षित आउटपुट — JVM/CLR:**

```text
नया😀
```

नया BOM या newline अपने-आप नहीं जुड़ता। encoded bytes की सीमा १६ MiB है; parent directory पहले बनाएँ। validation से पहले अस्वीकार हुई सामग्री पुरानी फ़ाइल को नहीं बदलती, पर बीच में I/O failure से partial write हो सकता है।

<a id="read-bytes"></a>

## बाइटपढ़ो (Read Bytes)

पूरी फ़ाइल का raw बाइटबफ़र लौटाता है। हर तत्व बाइट है और आकार bytes में है, characters में नहीं।

**हस्ताक्षर:**

- `फ़ाइल.बाइटपढ़ो(पथ: पाठ) → बाइटबफ़र`

```bhash
स्थिर मूल: बाइटबफ़र = नया बाइटबफ़र{परिवर्तित<बाइट>(०), परिवर्तित<बाइट>(२५५)};
फ़ाइल.बाइटलिखो("target/tutorial-files/read.bin", मूल);
स्थिर पढ़ी: बाइटबफ़र = फ़ाइल.बाइटपढ़ो("target/tutorial-files/read.bin");
लिखो(पढ़ी.आकार, पढ़ी[०], पढ़ी[१]);
```

**अपेक्षित आउटपुट — JVM/CLR:**

```text
२ ० २५५
```

पढ़ने की सीमा १६ MiB है। यह किसी encoding का अनुमान नहीं लगाता; UTF-8 चाहिए तो पाठ्य.यूटीएफ८पाठ से स्पष्ट decode करें।

<a id="write-bytes"></a>

## बाइटलिखो (Write Bytes)

बाइटबफ़र को ज्यों का त्यों फ़ाइल में लिखता है। नई फ़ाइल बन सकती है और मौजूदा सामग्री replace होती है। कोई मान वापस नहीं मिलता।

**हस्ताक्षर:**

- `फ़ाइल.बाइटलिखो(पथ: पाठ, मानक: बाइटबफ़र) → शून्य`

```bhash
स्थिर बाइटें: बाइटबफ़र = पाठ्य.यूटीएफ८("भारत😀");
फ़ाइल.बाइटलिखो("target/tutorial-files/write.bin", बाइटें);
लिखो(पाठ्य.यूटीएफ८पाठ(फ़ाइल.बाइटपढ़ो("target/tutorial-files/write.bin")));
```

**अपेक्षित आउटपुट — JVM/CLR:**

```text
भारत😀
```

सीमा १६ MiB है। यह atomic replacement, streaming या append API नहीं है। फ़ाइल handles सफलता और failure दोनों पर बंद किए जाते हैं।

## मूल-मशीन लक्ष्य (Native Target)

Native Linux पर `समर्थित`, `है`, `पाठपढ़ो` और `पाठलिखो` यही text व्यवहार देते हैं।
`बाइटबफ़र` और binary operations भी native Linux पर उपलब्ध हैं;
[Native byte guide](../../docs/native-bytes.md) देखें। [Native text-only उदाहरण](../../examples/native/files.bhash)
और [सीमाएँ व त्रुटियाँ](../../docs/native-files.md) देखें।
