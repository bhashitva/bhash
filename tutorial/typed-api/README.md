# प्रकारयुक्त वेब API (Typed Web API)

इस अभ्यास में आप JSON से कार्य बनाएँगे, उसे डेटाबेस में सहेजेंगे और HTTP से
वापस पढ़ेंगे। सेवा को प्राप्त JSON पहले `नयाकार्य` अभिलेख (Record) में बदलता
है। उसके बाद SQL और व्यावसायिक नियम नामित, प्रकारयुक्त क्षेत्रों जैसे
`नया_कार्य.प्राथमिकता` के साथ काम करते हैं।

## १. अभिलेख और JSON (Record and JSON)

[अनुबंध की स्रोत फ़ाइल](../../examples/typed-api/schema.json) में JSON के नाम
`id`, `title` और `priority` हैं। भाष में ये `पहचान`, `शीर्षक` और
`प्राथमिकता` हैं। प्राथमिकता १ से ५ तक का पूर्णांक है। `2` स्वीकार होता है
और `2.0` दशमलव होने से अस्वीकार होता है। शीर्षक १ से २०० Unicode वर्ण लेता
है। हर क्षेत्र आवश्यक है। अतिरिक्त क्षेत्र, `null` और गलत प्रकार अस्वीकार
होते हैं। उत्तर में सर्वर से निर्धारित `done` भी आता है।

रिपॉज़िटरी की जड़ से पहले बिना सर्वर का उदाहरण चलाएँ। पूरा JDK 17+ और
निर्मित कंपाइलर JAR चाहिए। अपने JAR का पथ अलग हो तो उसे नीचे बदलें।

```sh
java -jar target/bhash-compiler-0.1.0-SNAPSHOT.jar run examples/typed-api/model-demo --target jvm
```

`--target clr` के लिए .NET 10 और `--target native` के लिए अपने होस्ट का
[नेटिव SDK](../../docs/managed-native-toolchain.md) चाहिए। सभी में यह परिणाम
मिलेगा।

```json
{"id":1,"title":"भारत😀","priority":3,"done":false}
```

- **Playground** में [models.bhash](models.bhash) पूरा चिपकाएँ। **पूरा क्रमानुदेश (Full program)** चुनकर चलाएँ।
- **Jupyter** में [models.bhash](models.bhash) को एक नए Bhash कक्ष में पूरा चलाएँ। नया कक्ष एक पूर्ण क्रमानुदेश के रूप में इसे चलाता है। कर्नेल 0.1.4 या बाद का संस्करण उपयोग करें।
- **Browser WebAssembly** के लिए .NET 10 का `wasm-tools` workload स्थापित करें और नीचे के आदेश चलाएँ। फिर स्थानीय HTTP पते पर पृष्ठ खोलें।

```sh
java -jar target/bhash-compiler-0.1.0-SNAPSHOT.jar compile examples/typed-api/model-demo --target wasm-browser -o target/typed-models-web
python -m http.server 8000 --bind 127.0.0.1 --directory target/typed-models-web
```

HTTP सेवा और डेटाबेस परीक्षण के लिए JVM, desktop .NET या native चुनें।
ब्राउज़र उदाहरण JSON और अभिलेख तक सीमित है क्योंकि ब्राउज़र में सर्वर
सॉकेट और स्थानीय डेटाबेस उपलब्ध नहीं हैं।

## २. SQLite सेवा (Local API)

[app](../../examples/typed-api/app/bhash.project) सीधे
[service](../../examples/typed-api/service/bhash.project) पर निर्भर है।
सेवा अपने JSON अभिलेख [contract](../../examples/typed-api/contract/bhash.project)
से आयात करती है। लॉकफ़ाइलें साथ हैं, इसलिए तीनों फ़ोल्डर अपनी वर्तमान
सापेक्ष जगह पर रखें। VS Code में ऐप की `src/main.bhash` खोलकर
**Bhash: Run Project or File** से लक्ष्य चुन सकते हैं।

कमांड लाइन से निर्माण और चलाने के लिए अपने लक्ष्य की जोड़ी चुनें।
Windows PowerShell में `curl` के स्थान पर `curl.exe` चलाएँ। Native
निर्माण में Windows पर `.exe` नाम दें।

```sh
# JVM
java -jar target/bhash-compiler-0.1.0-SNAPSHOT.jar compile examples/typed-api/app --target jvm -o target/tasks-api.jar
java -jar target/tasks-api.jar --db=typed-tasks.db --port=8080

# .NET
java -jar target/bhash-compiler-0.1.0-SNAPSHOT.jar compile examples/typed-api/app --target clr -o target/tasks-api-clr
dotnet target/tasks-api-clr/BhashProgram.dll --db=typed-tasks.db --port=8080

# Native Linux / macOS
java -jar target/bhash-compiler-0.1.0-SNAPSHOT.jar compile examples/typed-api/app --target native -o target/tasks-api
./target/tasks-api --db=typed-tasks.db --port=8080

# Native Windows
java -jar target/bhash-compiler-0.1.0-SNAPSHOT.jar compile examples/typed-api/app --target native -o target/tasks-api.exe
./target/tasks-api.exe --db=typed-tasks.db --port=8080
```

सेवा `READY 8080` छापती है और केवल `127.0.0.1` पर सुनती है। पहली बार
माइग्रेशन तालिका बनाते हैं। दोबारा चलाने पर पहले से लागू माइग्रेशन
दोहराए नहीं जाते। केवल स्थानीय अभ्यास के लिए इस सेवा में प्रवेश नियंत्रण
नहीं है। सार्वजनिक सेवा बनाते समय [प्रमाणीकरण और सत्र](../authentication/README.md)
और HTTPS वाला reverse proxy जोड़ें।

दूसरे टर्मिनल से OpenAPI दस्तावेज़ पढ़ें और कार्य बनाएँ। अनुरोध के लिए
नीचे दी गई [UTF-8 फ़ाइल](task.json) उपयोग करें।

```json
{"id":1,"title":"भारत😀","priority":3}
```

```sh
curl http://127.0.0.1:8080/openapi.json
curl -i -H "Content-Type: application/json" --data-binary "@tutorial/typed-api/task.json" http://127.0.0.1:8080/api/tasks
curl -i http://127.0.0.1:8080/api/tasks/1
```

पहला POST `201` देता है। GET से `200` और वही कार्य मिलता है। उसी ID को
फिर भेजने पर `409` मिलता है। `priority` को `9` करके नई ID भेजें। सेवा
`422` देती है और वह कार्य सहेजती नहीं है। टूटा हुआ JSON `400` और गलत
`Content-Type` `415` देता है।

## ३. एकीकरण परीक्षण (Integration testing)

`routes.test` उत्पादन सेवा का वही मार्गक (Router) बनाता है और HTTP
अनुरोध वस्तुएँ सीधे उसे भेजता है। इससे वास्तविक SQLite, SQL, JSON,
माइग्रेशन और HTTP स्थिति को बिना खुला पोर्ट चलाए जाँच सकते हैं। प्रत्येक
परीक्षण क्रमानुदेश नए स्मृति डेटाबेस से शुरू होता है। परीक्षण पहले
माइग्रेशन में एक पुराना कार्य डालता है और जाँचता है कि दूसरे माइग्रेशन
के बाद उसकी प्राथमिकता `3` है।

```sh
java -jar target/bhash-compiler-0.1.0-SNAPSHOT.jar test examples/typed-api --target jvm
java -jar target/bhash-compiler-0.1.0-SNAPSHOT.jar test examples/typed-api --target clr
java -jar target/bhash-compiler-0.1.0-SNAPSHOT.jar test examples/typed-api --target native
```

सॉकेट सहित जाँच के लिए Python 3.10+ में
`python examples/typed-api/verify.py --target jvm --target clr --target native`
चलाएँ। यह चार HTTP अनुरोध भेजता है और सर्वर को सामान्य रूप से बंद होने देता
है। Native में यह हर आवंटन पर GC का दबाव डालता है और अंत में जीवित
आवंटन शून्य होने की जाँच करता है। पोर्ट की अनुमति न हो तो
`--routes-only` से केवल मार्गक और डेटाबेस परीक्षण चलाएँ।

## ४. PostgreSQL और वितरण (Provider and packaging)

PostgreSQL के लिए नया डेटाबेस और भूमिका बनाएँ। भूमिका को उस डेटाबेस में
तालिका बनाने की अनुमति दें। `BHASH_PG_DATABASE`, `BHASH_PG_USER` और
`BHASH_PG_PASSWORD` परिवेश से दें। अलग होस्ट या पोर्ट पर
`BHASH_PG_HOST` और `BHASH_PG_PORT` भी दें। डिफ़ॉल्ट TLS मोड
`verify-full` है। निजी CA के लिए `BHASH_PG_CA` में प्रमाणपत्र पथ दें।
क्रेडेंशियल को स्रोत, लॉकफ़ाइल या कमांड के तर्कों में न रखें।

ऊपर के JVM, .NET या native लॉन्च आदेश में `--provider=postgresql` जोड़ें।
.NET और native में PostgreSQL की `libpq` क्लाइंट लाइब्रेरी भी चाहिए।
अलग, **खाली परीक्षण डेटाबेस** पर हर लक्ष्य के लिए
`BHASH_TYPED_API_TEST_PROVIDER=postgresql` सेट करके `test` आदेश चलाएँ।
एक ही परीक्षण डेटाबेस को दूसरे रन के लिए फिर उपयोग करने पर प्रारंभिक
माइग्रेशन की जाँच असफल होगी। अधिक विन्यास के लिए
[PostgreSQL प्रदाता](../../docs/database-providers.md) देखें।

Linux native पैकेज में दोनों स्रोत संकुलों को समेटने के लिए साझा
`--source-root` दें।

```sh
java -jar target/bhash-compiler-0.1.0-SNAPSHOT.jar native package examples/typed-api/app --source-root examples/typed-api --name tasks-api --version 1.0.0 -o target/tasks-api-1.0.0.tar.gz
```

प्राप्त होस्ट पर archive निकालें, checksum और `check-host.sh` चलाएँ और
`run.sh --provider=postgresql` से सेवा शुरू करें। PostgreSQL वाले होस्ट के
लिए `libpq` उपलब्ध कराएँ। Windows और macOS पर उपयुक्त
[नेटिव वितरण निर्देश](../../docs/native-releases.md) देखें।

## ५. एक नया क्षेत्र जोड़ें (Schema evolution)

`priority` का बदलाव उदाहरण में पूरा है। अगला अभ्यास `estimate` नाम का
पूर्णांक क्षेत्र जोड़ना है। `CreateTask` और `Task` की `fields` सूची के
अंत में यह क्षेत्र जोड़ें।

```json
{"key":"estimate","name":"अनुमान","type":"integer","minimum":0,"maximum":40}
```

1. पुराने माइग्रेशन रखें। [सेवा](../../examples/typed-api/service/src/tasks.bhash)
   में संस्करण ३ का नया माइग्रेशन जोड़ें, जिसमें पुराने कार्यों के लिए
   डिफ़ॉल्ट `0` हो। उदाहरण SQL है
   `ALTER TABLE typed_tasks ADD COLUMN estimate INTEGER NOT NULL DEFAULT 0 CHECK(estimate BETWEEN 0 AND 40)`।
2. उसी फ़ाइल के parameterized `INSERT` और `SELECT` तथा अभिलेख निर्माण में
   नया क्षेत्र शामिल करें। [मॉडल उदाहरण](../../examples/typed-api/model-demo/src/main.bhash)
   के अनुरोध JSON और उत्तर अभिलेख में भी नया मान दें। परीक्षण में पुरानी
   पंक्ति के लिए `0` और नए अनुरोध में `41` के लिए `422` जाँचें।
3. `python examples/typed-api/generate.py` चलाएँ। इससे अभिलेख, JSON
   पाठक और लेखक, OpenAPI और Playground का स्रोत साथ बदलेंगे। नमूना
   `task.json` और अपेक्षित आउटपुट में भी `estimate` जोड़ें।
4. नीचे के आदेश से स्थानीय निर्भरताओं का नया लॉक बनाएँ और परीक्षण दोहराएँ।
   `generate.py --check` से जाँचें कि बनी फ़ाइलें स्रोत अनुबंध से मेल खाती हैं।

```sh
java -jar target/bhash-compiler-0.1.0-SNAPSHOT.jar deps lock examples/typed-api/service --offline
java -jar target/bhash-compiler-0.1.0-SNAPSHOT.jar deps lock examples/typed-api/app --offline
java -jar target/bhash-compiler-0.1.0-SNAPSHOT.jar deps lock examples/typed-api/routes.test --offline
java -jar target/bhash-compiler-0.1.0-SNAPSHOT.jar deps lock examples/typed-api/model-demo --offline
```

इस शुरुआती उदाहरण में JSON अभिलेखों के अनिवार्य पाठ, पूर्णांक और तार्किक
क्षेत्र हैं। SQL और माइग्रेशन स्पष्ट रूप से लिखे जाते हैं। नया प्रकार जोड़ते
समय उसके JSON नियम, Bhash रूप और परीक्षण साथ जोड़ें।
