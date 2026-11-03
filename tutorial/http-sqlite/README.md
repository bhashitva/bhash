# HTTP से SQLite संग्रह तक (HTTP + SQLite)

(Application example) इस उदाहरण में हम HTTP से लेखों की JSON सूची लाते हैं,
उसे अपनी SQLite फ़ाइल में सहेजते हैं और SQL query से पढ़ते हैं। अगली बार नेटवर्क
के बिना भी सहेजे हुए लेख देख सकते हैं। पूरा चलने योग्य क्रमानुदेश
[`examples/http-sqlite/main.bhash`](../../examples/http-sqlite/main.bhash) में है;
यहाँ उसी क्रमानुदेश के चरण और निर्णय समझाए गए हैं।

(Prerequisites) Bhash 0.11.0 या नया compiler/VS Code extension चाहिए। JVM के लिए
JDK 17+, CLR के लिए .NET 10 और native के लिए समर्थित managed/system toolchain
चाहिए। SQLite अलग से स्थापित नहीं करना है। एक ही Bhash स्रोत JVM, CLR और native
Linux/WSL, Windows x64 तथा macOS ARM64 पर चलता है। Playground/browser में SQLite
उपलब्ध नहीं है। नीचे दिए सभी आदेश repository root से चलाएँ।

## पहले स्थानीय API चलाएँ (Local HTTP fixture)

(Test data) [`posts.json`](../../examples/http-sqlite/posts.json) में दो लेख हैं।
हर लेख की `id` और `title` **पाठ** हैं। पहचान को जानबूझकर JSON string रखा है;
इस उदाहरण में JSON number को पूर्णांक में बदलने की जरूरत नहीं पड़ती। एक शीर्षक
में हिन्दी/emoji है; दूसरे में apostrophe और SQL जैसा पाठ है।

(Local server) पहले terminal में Python 3 से केवल loopback पर server चलाएँ:

```sh
python -m http.server 8080 --bind 127.0.0.1 --directory examples/http-sqlite
```

(Endpoint) अब `http://127.0.0.1:8080/posts.json` से सूची मिलेगी। इस अभ्यास को किसी
सार्वजनिक API, API key या बाहरी इंटरनेट की जरूरत नहीं है। Port 8080 व्यस्त हो तो
दूसरा port चुनें और application को उसी पते का `--url=...` तर्क दें।

## तीन targets पर चलाना (Build and run)

(JVM) दूसरे terminal में compiler JAR बनाएँ, यदि अभी नहीं बनाया है:

```sh
mvn package -DskipTests
java -jar target/bhash-compiler-0.1.0-SNAPSHOT.jar compile examples/http-sqlite/main.bhash --target jvm -o target/http-sqlite/cache.jar
java -jar target/http-sqlite/cache.jar --db=article-cache.db
```

(.NET / CLR) उसी स्रोत को CLR के लिए बनाएँ और चलाएँ:

```sh
java -jar target/bhash-compiler-0.1.0-SNAPSHOT.jar compile examples/http-sqlite/main.bhash --target clr -o target/http-sqlite/clr
dotnet target/http-sqlite/clr/BhashProgram.dll --db=article-cache.db
```

(Native — Linux/WSL/macOS) native executable इस तरह बनाएँ:

```sh
java -jar target/bhash-compiler-0.1.0-SNAPSHOT.jar compile examples/http-sqlite/main.bhash --target native -o target/http-sqlite/cache
./target/http-sqlite/cache --db=article-cache.db
```

(Native — Windows PowerShell) Windows पर `.exe` नाम दें:

```powershell
java -jar target/bhash-compiler-0.1.0-SNAPSHOT.jar compile examples/http-sqlite/main.bhash --target native -o target/http-sqlite/cache.exe
& .\target\http-sqlite\cache.exe --db=article-cache.db
```

(Expected output) तीनों targets पर परिणाम समान है। `id` मूल JSON पाठ है, जबकि
गिनती Bhash की देवनागरी संख्या में छपती है:

```text
सहेजी गई पंक्तियाँ: २
1 | नमस्ते, भारत😀
2 | O'Brien; DROP TABLE cached_posts; --
कुल पंक्तियाँ: २
```

(Configuration) `विन्यास.तर्क()` command-line तर्क पढ़ता है। Default URL स्थानीय
fixture का है और default database `article-cache.db` है। Relative database पथ
application की working directory के सापेक्ष है। किसी दूसरे अनुकूल HTTP/HTTPS API
का पता `--url=https://...` से दे सकते हैं; उसका JSON भी यही schema रखे। `--db=...`
और `--offline=true` **बने हुए application** के तर्क हैं; compiler के `run` आदेश
को ये तर्क देने का दावा नहीं किया गया है।

(Windows Java 17) सीधे Java launcher को दिए गैर-ASCII application arguments कुछ
Windows installations पर बदल सकते हैं। इस अभ्यास में default `article-cache.db`
जैसा ASCII filename उपयोग करें। HTTP/JSON और database के हिन्दी/emoji **data** पर
यह सीमा लागू नहीं होती; तीनों targets पर वही data जाँचा जाता है।

(VS Code) Python server चलता रहने दें, `main.bhash` खोलें और **Bhash: Run Project
or File** से JVM, CLR या Native चुनें। Default URL के कारण अतिरिक्त launch arguments
नहीं चाहिए। Offline/custom path वाले प्रयोग के लिए ऊपर के executable आदेश चलाएँ।

## हर चरण क्या करता है? (Walkthrough)

(HTTP fetch) `लाओ` एक `एचटीटीपीअनुरोध("GET", पता)` बनाता है। पाँच सेकंड की समयसीमा
रखकर `एचटीटीपी.भेजो` से उत्तर लेता है। HTTP 404/500 अपने-आप exception नहीं हैं,
इसलिए क्रमानुदेश `उत्तर.स्थिति() == २००` की जाँच करता है। `पाठ्यांश()` strict UTF-8
पढ़ता है और `जेसन.पढ़ो` JSON बनाता है। मूल मान सूची न हो तो स्पष्ट अपवाद मिलता है।
नेटवर्क और JSON parsing transaction शुरू करने से **पहले** पूरे होते हैं।

(Schema) `CREATE TABLE IF NOT EXISTS` केवल उदाहरण की `cached_posts` तालिका बनाता
है। `id` primary key है; दो समान पहचान होने पर import विफल होगा। सफल import एक
पूरा नया snapshot रखता है: पुराने लेख हटते हैं और नई सूची जुड़ती है। इसलिए इस
अभ्यास के लिए अलग database फ़ाइल रखें; अपने application का live data न दें।

(Parameterized SQL) `INSERT INTO cached_posts(id, title) VALUES (?, ?)` में SQL
ढाँचा स्थिर है। `पाठरखो(१, ...)` और `पाठरखो(२, ...)` values बाँधते हैं; SQL string
में API का पाठ जोड़कर query नहीं बनती। Apostrophe या `DROP TABLE` जैसा शीर्षक
सिर्फ data रहता है। Binding का सूचकांक **१ से**, query column का **० से** शुरू होता
है। हर पंक्ति के बाद `रीसेट()` और `बंधनमिटाओ()` कथन को अगली पंक्ति के लिए तैयार
करते हैं। ये parameters values के लिए हैं; table/column identifiers स्थिर रखे गए हैं।

(Transaction) `सहेजो` में `ड.लेनदेन()` पुराने snapshot को हटाने और सभी नए लेख
जोड़ने को एक इकाई बनाता है। हर लेख map होना चाहिए और `id`/`title` पाठ होने चाहिए।
पूरा loop सफल होने के बाद ही `ल.सहेजो()` commit करता है। दूसरी पंक्ति खराब निकले,
या duplicate primary key मिले, तो पहली नई पंक्ति भी स्थायी नहीं होगी। खाली JSON
सूची `[]` एक वैध **खाली snapshot** है और पुराना संग्रह खाली करती है।

(Resource cleanup) `संसाधन` scope तैयार कथन, transaction और connection के लिए
`बंदकरो()` बुलाता है; exception पर भी यही होता है। Commit से पहले scope छूटने पर
transaction rollback होता है। इसलिए `पकड़ो` block उसी खुले connection से पुराना
संग्रह दिखा सकता है। फिर `फेंको ग` मूल त्रुटि आगे भेजता है: application असफल exit
status देता है और database connection भी बंद होता है। `एचटीटीपीउत्तर` पूरा buffered
उत्तर है; उस पर अलग `बंदकरो()` नहीं बुलाना है।

(Query) `दिखाओ` `SELECT ... ORDER BY id` चलाता है। `अगला()` हर row पर `सत्य` देता
है; `पाठलो(०)` और `पाठलो(१)` SQLite TEXT पढ़ते हैं। Query खत्म होने पर कथन अपने
scope से बंद होता है। क्रम SQL के `ORDER BY` से तय होता है, HTTP सूची के क्रम से नहीं।

## सर्वर बंद करके पढ़ें (Offline cache)

(Offline mode) पहले सफल online run करें। फिर server वाले terminal में Ctrl+C दबाएँ:

```sh
java -jar target/http-sqlite/cache.jar --db=article-cache.db --offline=true
dotnet target/http-sqlite/clr/BhashProgram.dll --db=article-cache.db --offline=true
```

(Native offline) Native executable को भी यही `--db=article-cache.db --offline=true`
तर्क दें। अब HTTP अनुरोध नहीं होगा। केवल दोनों लेख और `कुल पंक्तियाँ: २` छपेंगे;
`सहेजी गई पंक्तियाँ` नहीं छपेगा। यदि database पहले बनाया ही नहीं गया, तो यह उदाहरण
खाली तालिका बनाकर `कुल पंक्तियाँ: ०` दिखाता है।

## विफल import देखें (Rollback experiment)

(Rollback) fixture की प्रति में दूसरी पंक्ति की `id` भी `"1"` कर दें और local
server दोबारा चलाएँ। पहले किसी सफल import से बना database ही उपयोग करें। अगला
online run duplicate key पर विफल होगा। Catch block में `पिछला संग्रह:` के बाद
पुराने दोनों लेख दिखेंगे; फिर exception/stack trace stderr पर आएगा और exit status
असफल होगा। `title` को संख्या बनाने पर भी पूरा import वापस होता है। अपनी मूल
fixture फ़ाइल बाद में बहाल करें।

(HTTP errors) `--url=http://127.0.0.1:8080/missing.json` से 404 प्रयोग करें। पुराने
लेख फिर भी दिखेंगे और import असफल रहेगा। JSON syntax खराब हो या root `{}` हो,
तो transaction शुरू ही नहीं होता। Timeout/connection/TLS की त्रुटियाँ भी सफल
import नहीं मानी जातीं। Offline mode चुनने पर इन सभी HTTP प्रयासों को छोड़ा जाता है।

## स्वचालित सत्यापन (Validation)

(Cross-target tests) `HttpSqliteExampleFixture` किसी public service के बजाय
loopback server चलाता है और **इसी असंशोधित main.bhash** को compile/run करता है।
Tests हिन्दी/emoji और SQL-जैसे पाठ, दोबारा import, offline read, duplicate key,
बीच में गलत प्रकार, HTTP 500, खराब JSON, गलत JSON root और सफल snapshot replacement
जाँचते हैं। Failure के catch block में पुराना data उसी connection से पढ़ा जाना
rollback का प्रमाण है। Offline run server बंद होने पर भी जाँचा जाता है।

```sh
mvn "-Dtest=JvmSqliteTest#combinedHttpSqliteExampleCachesAndRollsBack,ClrSqliteTest#combinedHttpSqliteExampleCachesAndRollsBack" test
mvn "-Dtest=NativeBackendTest#nativeSqliteHttpExampleCachesAndRollsBackWithForcedGc" test
```

(GC safety) Native test forced GC के साथ सफल और असफल दोनों प्रक्रियाओं के अंत में
live Bhash heap objects शून्य होने की जाँच करता है। Native CI उपलब्ध platform के
अनुसार sanitizer policy भी लागू करती है। HTTP fixture/child processes हर test के
बाद बंद होते हैं और database JUnit के temporary directory में रहता है।

(Related references) पूरा API विवरण [HTTP](../networking/README.md),
[SQLite](../sqlite/README.md) और [संसाधन/विन्यास](../application-foundations/README.md)
के संदर्भों में है। यह छोटा synchronous client/cache उदाहरण है; background refresh,
ORM, database migrations या browser SQLite का implementation नहीं है।
