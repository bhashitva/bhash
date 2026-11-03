# वेब सर्वर और कार्य-सूची अनुप्रयोग (Web Server and Task Board)

इस अभ्यास में हम भाष में पूरा छोटा अनुप्रयोग बनाएँगे। ब्राउज़र पृष्ठ दिखाएगा,
भाष का HTTP सर्वर अनुरोध सँभालेगा और SQLite कार्यों को फ़ाइल में सहेजेगा।
अनुप्रयोग बंद करके फिर चलाने पर भी कार्य बचे रहेंगे।

[पूरा स्रोत और चलाने के निर्देश](../../examples/web-tasks/README.md) ·
[सर्वर का व्यवहार और सीमाएँ](../../docs/web-applications.md)

## १. व्यवस्था समझें (Architecture)

ब्राउज़र → HTTP अनुरोध → भाष का सर्वर → मार्ग चुनना → चालक → SQLite → JSON उत्तर।

इन भागों की ज़िम्मेदारियाँ अलग हैं:

- **सर्वर (Server)** पोर्ट पर सुनता है, अनुरोध पढ़ता है और उत्तर भेजता है।
- **मार्ग-निर्धारक (Router)** विधि और पथ देखकर चालक चुनता है।
- **अनुरोध चालक (Request Handler)** अनुप्रयोग का काम करता है। हमारे उदाहरण में वह कार्य जोड़ता, पढ़ता, बदलता और हटाता है।
- **डेटाबेस (Database)** डेटा स्थायी रखता है। ब्राउज़र स्थानीय स्मृति के बजाय सर्वर की SQLite फ़ाइल पर निर्भर है।

यह पहला संस्करण एक समय में एक भाष चालक चलाता है। इससे native GC तथा
SQLite संसाधनों का जीवन चक्र स्पष्ट रहता है। यह सामान्य अभ्यास और छोटे
अनुप्रयोगों का आधार है; लंबे CPU काम या बड़ी संख्या में समानांतर अनुरोधों के
लिए अभी अलग व्यवस्था चाहिए।

## २. तैयारी करें (Prerequisites)

इस सुविधा वाला नया compiler बनाएँ:

```sh
mvn clean package -DskipTests
```

Compiler चलाने के लिए Java 17 या नया चाहिए। JVM अनुप्रयोग को Java,
CLR अनुप्रयोग को .NET 10 और native निर्माण को समर्थित native toolchain चाहिए।
Native executable चलाने वाली मशीन को Java, .NET, LLVM या C compiler नहीं चाहिए।
VS Code extension में सुविधा उसके अगले compiler-सहित release के बाद उपलब्ध होगी।
Playground / browser WASM में सुनने वाला HTTP सर्वर उपलब्ध नहीं है।

स्रोत `examples/web-tasks/src/main.bhash`, ब्राउज़र पृष्ठ `index.html`,
मार्ग परीक्षण `tests/routes.test.bhash` और deployment नमूने `deploy/` में हैं।
`bhash.project` के `sources=src` से मुख्य अनुप्रयोग और परीक्षण अलग रहते हैं।

## ३. पहला निर्माण और प्रारंभ (Build and Run)

Repository के मूल फ़ोल्डर से:

```sh
java -jar target/bhash-compiler-0.1.0-SNAPSHOT.jar compile examples/web-tasks --target jvm -o examples/web-tasks/tasks.jar
cd examples/web-tasks
java -jar tasks.jar
```

अब <http://127.0.0.1:8080/> खोलें। Terminal में `READY 8080` आएगा। पृष्ठ में
नया कार्य लिखकर **जोड़ें** दबाएँ। उसके सामने चिह्न लगाकर पूरा करें या **हटाएँ** दबाएँ।
`tasks.db` फ़ाइल इसी फ़ोल्डर में बनेगी। Ctrl+C से सर्वर रोकें और फिर चलाएँ;
बचे हुए कार्य फिर दिखाई देंगे।

वैकल्पिक निर्माण, repository के मूल फ़ोल्डर से:

```sh
java -jar target/bhash-compiler-0.1.0-SNAPSHOT.jar compile examples/web-tasks --target clr -o examples/web-tasks/tasks-clr
java -jar target/bhash-compiler-0.1.0-SNAPSHOT.jar compile examples/web-tasks --target native -o examples/web-tasks/web-tasks
```

फिर `examples/web-tasks` में `dotnet tasks-clr/BhashProgram.dll` या
`./web-tasks` चलाएँ। Windows पर native नाम `web-tasks.exe` रखें और
`./web-tasks.exe` चलाएँ। `--page=/पूरा/पथ/index.html` देने पर किसी अन्य फ़ोल्डर
से भी चला सकते हैं। Windows Java 17 के सीधे command-line arguments में
हिन्दी फ़ाइल-पथ प्रभावित हो सकते हैं; सरल ASCII पथ उपयोग करें। HTTP और डेटाबेस
के हिन्दी/emoji मान UTF-8 में चलते हैं।

## ४. चालक बनाएँ (Request Handler)

`वेबचालक` एक अनुबंध है। उसमें केवल एक फलन लागू करना है:

```bhash
आयात मानक.वेब;
आयात मानक.वेबचालक;
आयात मानक.वेबअनुरोध;
आयात मानक.वेबउत्तर;

वर्ग अभिवादन पालन वेबचालक {
    सार्वजनिक फलन संभालो(अनुरोध: वेबअनुरोध) -> वेबउत्तर {
        लौटाओ वेब.पाठउत्तर(२००, "नमस्ते!");
    }
}
```

`वेबअनुरोध` इनपुट देता है और `वेबउत्तर` परिणाम बताता है। चालक साधारण भाष
वर्ग है; lambda या किसी दूसरे runtime की callback लिखने की आवश्यकता नहीं है।
`वेब.पाठउत्तर`, `वेब.एचटीएमएल` और `वेब.जेसन` उचित Content-Type और UTF-8
सामग्री बनाते हैं। Binary उत्तर के लिए `नया वेबउत्तर(२००, बफ़र)` प्रयोग करें।
`उत्तर.शीर्षरखो("X-Example", "value")` अतिरिक्त शीर्ष रखता है; framing शीर्ष
सर्वर स्वयं तय करता है।

## ५. मार्ग जोड़ें (Routing)

मुख्य अनुप्रयोग में:

```bhash
स्थिर अनुप्रयोग: वेबअनुप्रयोग = नया वेबअनुप्रयोग();
अनुप्रयोग.जोड़ो("GET", "/api/tasks", चालक);
अनुप्रयोग.जोड़ो("POST", "/api/tasks", चालक);
अनुप्रयोग.जोड़ो("GET", "/api/tasks/:id", चालक);
```

`:id` एक पथ-खंड पकड़ता है। `/api/tasks/one` के लिए
`अनुरोध.पथमान["id"]` का मान `one` होगा। विधि और पथ दोनों महत्वपूर्ण हैं।
उसी पथ पर गलत विधि देने से 405 और अनजान पथ पर 404 मिलता है।
HEAD अनुरोध GET चालक का उपयोग कर सकता है, लेकिन सर्वर शरीर नहीं भेजता।
मार्ग पंजीकरण के क्रम में जाँचे जाते हैं; सामान्य `:id` मार्गों से पहले विशेष
स्थिर मार्ग रखें।

**प्रश्न तर्क (Query Parameters)** के लिए `अनुरोध.प्रश्नमान("q")` सूची देता है।
`?q=one&q=two` में दोनों मान रहते हैं। प्रश्न में `+` रिक्त स्थान बनता है;
पथ में `+` वैसा ही रहता है। प्रतिशत कूट एक बार खोला जाता है। गलत UTF-8,
नियंत्रण अक्षर और कूटित पथ-विभाजक अस्वीकार होते हैं।

## ६. JSON पढ़ें और जाँचें (JSON and Validation)

Browser POST में `{ "id": "one", "title": "पहला कार्य" }` भेजता है।
`अनुरोध.जेसनलो()` पहले Content-Type जाँचता है और फिर JSON पढ़ता है।
खराब JSON पर 400 तथा अनुपयुक्त Content-Type पर 415 आता है।

Demo का `वस्तुलो` जाँचता है कि मूल मान JSON वस्तु है। `पाठलो` आवश्यक पाठ
क्षेत्र, खाली मान और लंबाई की सीमा जाँचता है। PUT में `done` सचमुच तार्किक
होना चाहिए। ये अनुप्रयोग के नियम हैं; इन्हें framework स्वयं अनुमान नहीं लगाता।
अमान्य क्षेत्र पर चालक `नया वेबत्रुटि(४२२, "...")` फेंकता है।

**त्रुटि उत्तर (Error Responses)** में generic संदेश और स्थिति आती है।
आंतरिक exception का संदेश, SQL या फ़ाइल-पथ उपयोगकर्ता को नहीं भेजा जाता।
प्रत्याशित त्रुटि के लिए `वेबत्रुटि` और अप्रत्याशित विफलता के लिए 500 का
एक समान व्यवहार है। Production में अपनी आवश्यकता के अनुसार सुरक्षित,
क्षेत्र-विशिष्ट validation उत्तर अलग चालक में बनाए जा सकते हैं।

## ७. SQLite में सहेजें (Parameterized SQL)

आरंभ में अनुप्रयोग `tasks` तालिका बनाता है। हर पंक्ति में पाठ `id`, पाठ `title`
और 0/1 के रूप में `done` है। यह INSERT देखें:

```bhash
संसाधन (क: कथन = डाटा.तैयार("INSERT INTO tasks(id,title,done) VALUES (?,?,0)")) {
    क.पाठरखो(१, पहचान);
    क.पाठरखो(२, शीर्षक);
    क.चलाओ();
}
```

`?` स्थान पर अलग से मान बाँधना **पैरामीटरयुक्त SQL (Parameterized SQL)** है।
शीर्षक में `'` या SQL जैसा पाठ होने पर भी वह डेटा रहता है। उपयोगकर्ता के
मान को SQL string में जोड़कर query न बनाएँ।

**लेनदेन (Transaction)** सभी संबंधित बदलाव एक इकाई में करता है। Demo
INSERT से पहले duplicate id जाँचता है और उसी transaction में INSERT करता है।
सफलता पर `ल.सहेजो()` commit करता है। Exception आने पर scope बंद होते समय
rollback होता है। PUT और DELETE भी transaction में चलते हैं।

**संसाधन सफाई (Resource Cleanup)** के लिए `संसाधन (...)` है। Statements हर
अनुरोध के बाद बंद होते हैं; database connection पूरे सर्वर का जीवन चक्र जीता है।
GC स्मृति सँभालता है, पर फ़ाइल और socket बंद होने का समय इस scope से स्पष्ट
रहता है। Native unwinding के दौरान भी GC roots और संसाधन सही क्रम में हटते हैं।

## ८. Browser का काम समझें (Browser UI)

पृष्ठ का JavaScript `fetch` से JSON API बुलाता है। भाष सर्वर ही business logic
और SQL चलाता है। जोड़ते समय browser नई UUID पहचान बनाता है। चिह्न बदलने
पर PUT और हटाने पर DELETE जाता है। सूची में पहले 100 कार्य दिखाई देते हैं।
यह जानबूझकर छोटा single-user demo है; pagination और multi-user login इसका
भाग नहीं हैं।

कार्य शीर्षक DOM के `textContent` में रखे जाते हैं। उपयोगकर्ता का पाठ HTML के
रूप में नहीं चलाया जाता। पृष्ठ कोई बाहरी script या CDN नहीं लाता। Static पृष्ठ
के लिए केवल `/` का निश्चित, आरंभ में पढ़ा हुआ `index.html` मिलता है; URL को
सीधे filesystem path बनाने की सुविधा यहाँ नहीं जोड़ी गई है।

## ९. API अलग से चलाकर देखें (API Requests)

दूसरे terminal में:

```sh
curl http://127.0.0.1:8080/health
curl -X POST http://127.0.0.1:8080/api/tasks -H 'Content-Type: application/json' -d '{"id":"one","title":"पहला कार्य"}'
curl http://127.0.0.1:8080/api/tasks/one
curl -X PUT http://127.0.0.1:8080/api/tasks/one -H 'Content-Type: application/json' -d '{"title":"पहला कार्य","done":true}'
curl -X DELETE http://127.0.0.1:8080/api/tasks/one
```

ये POSIX shell उदाहरण हैं। Windows PowerShell में `curl.exe` और अपनी shell
के quoting नियम प्रयोग करें; हिन्दी request body के लिए UTF-8 फ़ाइल को
`--data-binary @request.json` देना सुविधाजनक है। Create पर 201, सफल PUT/DELETE
पर 204, duplicate id पर 409 और गायब कार्य पर 404 अपेक्षित है।

## १०. परीक्षण करें (Testing)

Repository के मूल फ़ोल्डर से, बिना पोर्ट खोले:

```sh
java -jar target/bhash-compiler-0.1.0-SNAPSHOT.jar test examples/web-tasks/tests --target jvm
java -jar target/bhash-compiler-0.1.0-SNAPSHOT.jar test examples/web-tasks/tests --target clr
java -jar target/bhash-compiler-0.1.0-SNAPSHOT.jar test examples/web-tasks/tests --target native
```

नमूना परीक्षण सीधे अनुरोध वस्तु बनाकर router को देता है। इससे चालक और routing
का परीक्षण तेज और deterministic रहता है। Repository की JUnit integration
जाँचें वास्तविक generated server चलाकर HTTP, JSON, गलत framing, timeout,
exception के बाद अगला अनुरोध, SQLite persistence तथा native forced-GC जाँचती हैं।

```sh
mvn '-Dtest=WebCoreTest,JvmWebTest,ClrWebTest' test
mvn '-Dtest=NativeBackendTest#nativeWeb*' test
```

`--port=0` और `--requests=N` से automation स्वतंत्र पोर्ट तथा सीमित अनुरोध
संख्या उपयोग करती है। सामान्य उपयोग में `--requests=0` छोड़ें।

## ११. प्रकाशित करें (Deployment)

पहले native executable उसी OS/architecture के लिए बनाएँ जहाँ चलाना है।
Existing `native package` से runtime compatibility की जाँच सहित bundle बनाएँ:

```sh
java -jar target/bhash-compiler-0.1.0-SNAPSHOT.jar native package examples/web-tasks -o tasks-release.tar.gz --name web-tasks --version 1.0.0 --max-glibc 2.35
```

Archive से executable निकालकर `index.html` के साथ `/opt/bhash-tasks` में रखें।
SQLite के लिए अलग लिखने योग्य `/var/lib/bhash-tasks` रखें। Native build और
runtime compatibility का विस्तृत तरीका compiler के native deployment दस्तावेज़
में है; नया glibc वाला binary पुराने Linux पर केवल copy करने से नहीं चलेगा।

`deploy/bhash-tasks.service` systemd का नमूना है। यह अलग उपयोगकर्ता में चलाता
है, database directory अलग रखता है और SIGTERM पर सफाई के लिए समय देता है।
`deploy/Caddyfile` अपने domain, HTTPS तथा authentication का नमूना है।
उसमें वास्तविक password hash और अपना domain भरें। Default Bhash binding
`127.0.0.1` रखें; TLS और बाहरी पहुँच proxy सँभाले। Templates अपने आप install
या deploy नहीं होते।

Container के लिए supplied Dockerfile के पास Linux executable `web-tasks` और
`index.html` रखें। वह Ubuntu 22.04 / glibc 2.35-compatible executable की अपेक्षा
करता है। `/data` का persistent volume रखें और पोर्ट को host loopback पर प्रकाशित
करें; सामने वही authenticated HTTPS proxy रखें।

**समापन (Shutdown)** में Ctrl+C अथवा POSIX SIGTERM एक stop flag रखता है।
सक्रिय अनुरोध पूरा होने पर नया अनुरोध नहीं लिया जाता और resource scopes बंद
होते हैं। User handler को मनमाने समय पर रोका नहीं जाता। Force-kill में scope
cleanup नहीं चल सकता; supervisor को पर्याप्त grace period देना चाहिए।

## १२. वर्तमान सीमाएँ याद रखें (Limits)

सामग्री की सीमा 1 MiB, अधिकतम 64 request headers और स्वीकारे गए संबंध का
कुल socket समय 10 सेकंड है। एक समय में एक चालक चलता है और listen backlog
16 माँगा जाता है; OS उसकी वास्तविक queue तय करता है। बहुत धीमे भेजने वाला
client timeout के बाद बंद होता है। Deadline समाप्त होने पर उत्तर की गारंटी नहीं है।
हर उत्तर के बाद connection बंद होता है। Chunked request body, WebSocket,
HTTP/2, streaming और TLS सीधे भाष सर्वर में इस चरण में नहीं हैं। Proxy को
request body buffer करके Content-Length के साथ भेजना चाहिए। Browser WASM
में listener नहीं चलता। Authentication/session library अगला अलग विषय हो सकता है।

अब आपके पास विकास, प्रत्यक्ष routing परीक्षण, HTTP integration, स्थायी डेटा और
native वितरण को जोड़ने वाला एक चलाने योग्य आधार है। पूरा स्रोत पढ़ते समय पहले
`मुख्य`, फिर `कार्यचालक.संभालो`, और अंत में `index.html` देखें।
