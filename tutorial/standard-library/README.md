# मानक पुस्तकालय (Standard Library)

भाष का मानक पुस्तकालय मानक संकुल में compiler के साथ आता है। यह सामग्री compiler revision cf46f367d67b0236e5a0aa7a434d98679d5b723b के catalog पर आधारित है। अलग package download नहीं करना पड़ता; तैयार कार्यक्रम अपने runtime support के साथ चलते हैं। यह संदर्भ पहले से थोड़ा प्रोग्रामिंग जानने वाले पाठक को भी हर फ़ंक्शन का अर्थ, सही call और सीमाएँ समझाता है।

## विषय-सूची (Contents)

| विषय | अलग फ़ंक्शन |
| --- | --- |
| [पाठ के साथ काम (Text and Strings)](01-text.md) | 17 |
| [संख्यात्मक गणना (Mathematics)](02-math.md) | 13 |
| [यादृच्छिक अनुक्रम (Pseudorandom Numbers)](03-random.md) | 3 |
| [जेसन डेटा पढ़ना और बनाना (JSON Parsing and Serialization)](04-json.md) | 2 |
| [समय और दिनांक (UTC Date and Time)](05-time.md) | 6 |
| [फ़ाइलों के साथ काम (File I/O)](06-files.md) | 6 |
| [त्रुटियाँ और संयोजन (Errors and Recipes)](07-errors-and-recipes.md) | सामान्य गलतियाँ और संयुक्त उपयोग |

कुल **४७ अलग फ़ंक्शन और ५० हस्ताक्षर** हैं। निरपेक्ष, न्यूनतम और अधिकतम में पूर्णांक/दशमलव के दो-दो ओवरलोड हैं। नीचे का पूर्ण सूचकांक हर फ़ंक्शन तक सीधे पहुँचाता है।

## शुरुआत (Getting Started)

[Playground](https://play.bhashitva.org/) में पूरी example file चिपकाएँ और दूसरे dropdown **लेखन** में **पूरा कार्यक्रम · Full program** चुनें। imports और मुख्य() सहित कार्यक्रम को Statements मोड में देने पर वह दोबारा मुख्य() में लपेटा जाएगा और त्रुटि आएगी। केवल कथन लिखते समय fully qualified नाम, जैसे मानक.गणित.वर्गमूल(८१.०), इस्तेमाल कर सकते हैं।

```bhash
आयात मानक.पाठ्य;
आयात मानक.गणित;
कार्य मुख्य() -> शून्य {
    लिखो(पाठ्य.संख्यापाठ(गणित.वर्गमूल(८१.०), २));
}
```

आउटपुट **9.00** है। लिखो आम संख्याएँ देवनागरी अंकों में दिखाता है; संख्यापाठ और JSON serialization ASCII अंक लौटाते हैं। कथाओं में दोनों का अंतर जानबूझकर दिखाया गया है।

आयात मानक.पाठ्य उपनाम प; से alias बनाया जा सकता है। उसके बाद प.लंबाई("क😀ख") मान्य है। ये static library methods हैं; नया पाठ्य() बनाना आवश्यक या समर्थित नहीं है। साधारण global लंबाई/अंश और library के methods अलग नाम-स्थान में हैं।

## स्थानीय उपयोग (Local Execution)

VS Code में standard-library वाला Bhash extension और JDK 17+ रखें। .bhash example खोलकर **Bhash: Run Project or File** से JVM चुनें। CLR के लिए .NET 10 SDK भी चाहिए। source से compiler बनाने के लिए Maven 3.9+ चाहिए। नीचे के आदेश repository root से हैं:

```sh
mvn package -DskipTests
java -jar target/bhash-compiler-0.1.0-SNAPSHOT.jar run tutorial/standard-library/examples/01-text.bhash
java -jar target/bhash-compiler-0.1.0-SNAPSHOT.jar run tutorial/standard-library/examples/02-math.bhash --target clr
```

फ़ाइल वाले अध्याय के लिए पहले parent directory बनाएँ। ये निश्चित नामों की फ़ाइलें बदल सकते हैं; खाली अभ्यास directory इस्तेमाल करें:

```sh
mkdir -p target/tutorial-files
java -jar target/bhash-compiler-0.1.0-SNAPSHOT.jar run tutorial/standard-library/examples/06-files.bhash
```

PowerShell में पहली पंक्ति का विकल्प:

```powershell
New-Item -ItemType Directory -Force target/tutorial-files
```

## Target के अंतर (Target Differences)

पाठ, गणित, यादृच्छिक, JSON और UTC समय JVM, CLR और browser WASM में उपलब्ध हैं। फ़ाइल.समर्थित() JVM/CLR पर सत्य और browser पर असत्य है; बाकी फ़ाइल methods browser पर अपवाद देती हैं। browser कार्यक्रम अपने सर्वर या आपके computer की disk तक इस API से नहीं पहुँचते।

ब्राउज़र के लिए स्थानीय build में .NET 10 SDK और wasm-tools workload चाहिए। उदाहरण:

```sh
java -jar target/bhash-compiler-0.1.0-SNAPSHOT.jar compile tutorial/standard-library/examples/01-text.bhash --target wasm-browser -o target/tutorial-web
python -m http.server 8000 --directory target/tutorial-web
```

फिर http://localhost:8000 खोलें और तैयार पृष्ठ का Run दबाएँ। सीधे file:// से खोलने पर module workers नहीं चलते। Playground में runtime installation की आवश्यकता नहीं है।

## उदाहरणों की जाँच (Verification)

Node.js के साथ catalog और snippets की जाँच करें, या compiler बनाकर सात example programs और कथाओं में दिए गए छह पूरे कार्यक्रमों के आउटपुट मिलाएँ। runner फ़ाइलों को अलग temporary sandbox में लिखता है और वास्तविक उपयोगकर्ता फ़ाइलें नहीं बदलता।

```sh
node tutorial/standard-library/verify.mjs --coverage-only
node tutorial/standard-library/verify.mjs --target jvm
node tutorial/standard-library/verify.mjs --target clr
```

Compiler का default पथ target/bhash-compiler-0.1.0-SNAPSHOT.jar है। BHASH_COMPILER, BHASH_JAVA और BHASH_DOTNET से executables/पथ बदले जा सकते हैं। JVM/CLR expected files ठीक-ठीक मिलाई जाती हैं; browser में फ़ाइल अध्याय का अलग संदेश अपेक्षित है।

## पूरा फ़ंक्शन सूचकांक (Complete Function Index)

| module | फ़ंक्शन (English) |
| --- | --- |
| मानक.पाठ्य | [लंबाई (Length)](01-text.md#length) |
| मानक.पाठ्य | [अंश (Substring / Slice)](01-text.md#substring-slice) |
| मानक.पाठ्य | [खोजो (Index Of / Find)](01-text.md#index-of-find) |
| मानक.पाठ्य | [है (Contains)](01-text.md#contains) |
| मानक.पाठ्य | [आरंभ (Starts With)](01-text.md#starts-with) |
| मानक.पाठ्य | [अंत (Ends With)](01-text.md#ends-with) |
| मानक.पाठ्य | [बदलो (Replace)](01-text.md#replace) |
| मानक.पाठ्य | [बाँटो (Split)](01-text.md#split) |
| मानक.पाठ्य | [जोड़ो (Join)](01-text.md#join) |
| मानक.पाठ्य | [छाँटो (Trim)](01-text.md#trim) |
| मानक.पाठ्य | [बड़ाअक्षर (To Upper Case)](01-text.md#to-upper-case) |
| मानक.पाठ्य | [छोटाअक्षर (To Lower Case)](01-text.md#to-lower-case) |
| मानक.पाठ्य | [दोहराओ (Repeat)](01-text.md#repeat) |
| मानक.पाठ्य | [संख्यापाठ (Fixed Decimal Formatting / To Fixed)](01-text.md#fixed-decimal-formatting-to-fixed) |
| मानक.पाठ्य | [अंकदेवनागरी (Convert Digits to Devanagari)](01-text.md#convert-digits-to-devanagari) |
| मानक.पाठ्य | [यूटीएफ८ (Encode UTF-8)](01-text.md#encode-utf-8) |
| मानक.पाठ्य | [यूटीएफ८पाठ (Decode UTF-8)](01-text.md#decode-utf-8) |
| मानक.गणित | [निरपेक्ष (Absolute Value / Abs)](02-math.md#absolute-value-abs) |
| मानक.गणित | [न्यूनतम (Minimum / Min)](02-math.md#minimum-min) |
| मानक.गणित | [अधिकतम (Maximum / Max)](02-math.md#maximum-max) |
| मानक.गणित | [वर्गमूल (Square Root / Sqrt)](02-math.md#square-root-sqrt) |
| मानक.गणित | [घात (Power / Pow)](02-math.md#power-pow) |
| मानक.गणित | [फर्श (Floor)](02-math.md#floor) |
| मानक.गणित | [छत (Ceiling / Ceil)](02-math.md#ceiling-ceil) |
| मानक.गणित | [निकटतम (Round to Nearest / Ties to Even)](02-math.md#round-to-nearest-ties-to-even) |
| मानक.गणित | [ज्या (Sine / Sin)](02-math.md#sine-sin) |
| मानक.गणित | [कोज्या (Cosine / Cos)](02-math.md#cosine-cos) |
| मानक.गणित | [लघुगणक (Natural Logarithm / Log)](02-math.md#natural-logarithm-log) |
| मानक.गणित | [घातांक (Exponential / Exp)](02-math.md#exponential-exp) |
| मानक.गणित | [पाई (Pi)](02-math.md#pi) |
| मानक.यादृच्छिक | [बीज (Seed)](03-random.md#seed) |
| मानक.यादृच्छिक | [पूर्णांकचुनो (Random Integer / Next Int)](03-random.md#random-integer-next-int) |
| मानक.यादृच्छिक | [अंश (Random Fraction / Next Double)](03-random.md#random-fraction-next-double) |
| मानक.जेसन | [पढ़ो (Parse JSON)](04-json.md#parse-json) |
| मानक.जेसन | [पाठबनाओ (Stringify JSON / Serialize)](04-json.md#stringify-json-serialize) |
| मानक.समय | [अभी (Current Time / Now)](05-time.md#current-time-now) |
| मानक.समय | [पढ़ो (Parse UTC Timestamp)](05-time.md#parse-utc-timestamp) |
| मानक.समय | [पाठबनाओ (Format UTC Timestamp)](05-time.md#format-utc-timestamp) |
| मानक.समय | [वर्ष (Year)](05-time.md#year) |
| मानक.समय | [माह (Month)](05-time.md#month) |
| मानक.समय | [दिन (Day of Month)](05-time.md#day-of-month) |
| मानक.फ़ाइल | [समर्थित (File Capability / Is Supported)](06-files.md#file-capability-is-supported) |
| मानक.फ़ाइल | [है (File Exists / Is Regular File)](06-files.md#file-exists-is-regular-file) |
| मानक.फ़ाइल | [पाठपढ़ो (Read Text File)](06-files.md#read-text-file) |
| मानक.फ़ाइल | [पाठलिखो (Write Text File)](06-files.md#write-text-file) |
| मानक.फ़ाइल | [बाइटपढ़ो (Read Bytes)](06-files.md#read-bytes) |
| मानक.फ़ाइल | [बाइटलिखो (Write Bytes)](06-files.md#write-bytes) |

विस्तृत machine-level अनुबंध और मूल catalog के लिए [तकनीकी reference](../../docs/standard-library.md) देखें। ये ट्यूटोरियल उस reference की हिन्दी, उदाहरण-आधारित साथी सामग्री हैं।
