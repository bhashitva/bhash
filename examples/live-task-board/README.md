# साझा कार्यपट

ऐलिस और बॉब एक ही कार्यपट देखते हैं। ऐलिस कोई काम जोड़ती है तो बॉब के ब्राउज़र में वह कुछ ही क्षण में दिखता है। बॉब काम पूरा करता है तो ऐलिस को उसकी नई स्थिति दिखती है। चार्ली का अलग कार्यपट है। उसका खाता पहले कार्यपट का डेटा या उसकी सूचनाएँ नहीं पा सकता।

## १. संकलन और स्थानीय अभ्यास

भंडार की मुख्य निर्देशिका से compiler JAR बनाएँ और कार्यपट संकलित करें। उदाहरण में JVM चुना गया है।

```sh
mvn -DskipTests package
java -jar target/bhash-compiler-0.1.0-SNAPSHOT.jar compile \
  examples/live-task-board/bhash.project --target jvm -o target/live-task-board.jar
```

सर्वर चलाने वाली शेल में तीन अलग कूटशब्द रखें। प्रत्येक में कम से कम १२ अक्षर रखें। नीचे के मान स्थानीय अभ्यास के लिए हैं। सार्वजनिक सर्वर पर अपने गुप्त मान दें।

```sh
export BHASH_BOARD_ALICE_PASSWORD='alice-local-example'
export BHASH_BOARD_BOB_PASSWORD='bob-local-example'
export BHASH_BOARD_CHARLIE_PASSWORD='charlie-local-example'
java -jar target/live-task-board.jar --local=true --port=8080 \
  --assets=examples/live-task-board --db=target/live-board.db
```

[स्थानीय कार्यपट](http://127.0.0.1:8080) को दो स्वतंत्र ब्राउज़र प्रोफ़ाइल में खोलें। एक में `alice` और दूसरे में `bob` से प्रवेश करें। दोनों पर **लाइव · जुड़े हुए** दिखने दें। ऐलिस से एक काम जोड़ें और बॉब से उसे पूरा चिह्नित करें। किसी तीसरी प्रोफ़ाइल में `charlie` से प्रवेश करके स्टूडियो का अलग कार्यपट देखें। निजी विंडो की दो टैब अक्सर एक ही कुकी भंडार साझा करती हैं।

.NET के लिए `--target clr -o target/live-task-board-clr` चुनें और `dotnet target/live-task-board-clr/BhashProgram.dll` से चलाएँ। नेटिव के लिए `--target native -o target/live-task-board` चुनें और बना हुआ executable चलाएँ। Windows पर नेटिव आउटपुट नाम में `.exe` दें। सर्वर के तर्क तीनों लक्ष्य में समान हैं। ऊपर के `export` आदेश Bash/WSL के हैं। PowerShell में परिवेश चर के लिए `$env:BHASH_BOARD_ALICE_PASSWORD = 'alice-local-example'` जैसा रूप प्रयोग करें।

## २. HTTPS और WSS

सर्वर केवल loopback पर सुनता है। TLS के लिए उसी मशीन पर भरोसेमंद रिवर्स प्रॉक्सी रखें। उदाहरण के लिए, अपने वास्तविक DNS नाम के लिए Caddy में यह नियम लगाएँ।

```caddy
board.example.org {
    reverse_proxy 127.0.0.1:8080
}
```

Bhash को `--origin=https://board.example.org --port=8080` दें और `--local=true` हटाएँ। प्रॉक्सी ब्राउज़र का Host और Origin बनाए रखे और WebSocket को HTTP/1.1 Upgrade के रूप में Bhash तक पहुँचाए। HTTPS मोड में सत्र कुकी `Secure`, `HttpOnly`, और `SameSite=Lax` है। कूटशब्द और सत्र कुकी को अनुरोध लॉग में शामिल न करें। डेमो एक Bhash प्रक्रिया के लिए है। सदस्यता उदाहरण में तय है और सर्वर दोबारा चलाने पर प्रवेश सत्र समाप्त होते हैं। डेटाबेस में कार्य बचे रहते हैं। [Caddy reverse proxy](https://caddyserver.com/docs/caddyfile/directives/reverse_proxy) · [Bhash WebSocket मार्गदर्शिका](../../docs/websocket.md)

## ३. बदलाव कैसे पहुँचता है

1. [सर्वर पंजीकरण](src/main.bhash) में `असमवेबअनुप्रयोग` HTTP मार्ग संभालता है और `वेबसॉकेटमार्ग` उसी श्रोता पर `/live/:board` खोलता है। सत्र लाइब्रेरी Host, Origin, कुकी और भूमिका जाँचती है। [कार्यपट चालक](src/board.bhash) किसी खाते की उस विशेष कार्यपट में सदस्यता भी जाँचता है।
2. नया काम `POST /api/boards/:board/tasks` से आता है। पूरा करने या फिर खोलने के लिए ब्राउज़र `PATCH /api/boards/:board/tasks/:id` भेजता है। JSON फ़ील्ड और CSRF टोकन जाँचे जाते हैं। [भंडारण](src/storage.bhash) मापदंडयुक्त SQL में काम और कार्यपट का बढ़ता संस्करण एक ही लेनदेन में सहेजता है।
3. `प्रतीक्षा लेन.सहेजो(चिह्न)` की पुष्टि के बाद ही सर्वर बदलाव की सूचना देता है। हर ब्राउज़र का प्रेषण अपना काम है। यदि बीच में और बदलाव होते हैं, तो धीमे ग्राहक को नवीनतम संस्करण मिल सकता है। हर मध्यवर्ती संस्करण भेजना ज़रूरी नहीं है। यदि commit की पुष्टि खो जाए, तो सूची फिर पढ़कर सहेजी हुई स्थिति जाँचें। ब्राउज़र बदलाव को अपने आप दोबारा नहीं भेजता।
4. [ब्राउज़र](app.js) को पहले `{"v":1,"type":"ready"}` मिलता है। वह उसके बाद HTTP से वर्तमान सूची पढ़ता है। अगली `changed` सूचना में कार्यपट और संस्करण मिलता है। ब्राउज़र केवल नए संस्करण के लिए पूरी सूची फिर पढ़ता है। दोबारा जुड़ते समय नया `ready` आने पर सूची फिर पढ़ने से छूटे बदलाव मिल जाते हैं।
5. फ़ाइल वाला SQLite डिफ़ॉल्ट है। अलग PostgreSQL डेटाबेस के लिए `--provider=postgresql` दें। `BHASH_PG_HOST`, `BHASH_PG_PORT`, `BHASH_PG_DATABASE`, `BHASH_PG_USER`, `BHASH_PG_PASSWORD`, `BHASH_PG_SSLMODE` और `BHASH_PG_CA` से संबंध बनाएँ। डिफ़ॉल्ट SSL नीति `verify-full` है। दोनों में सर्वर सुनना शुरू करने से पहले माइग्रेशन लागू करता है।

## ४. ब्राउज़र और TLS परीक्षण

Linux पर Python 3.11 या बाद का संस्करण, OpenSSL और `libnss3-tools` रखें। भंडार की मुख्य निर्देशिका से चलाएँ।

```sh
python -m pip install playwright==1.58.0
python -m playwright install --with-deps chromium
python examples/live-task-board/verify.py java -jar target/live-task-board.jar
```

परीक्षण हर बार नया स्थानीय प्रमाणपत्र प्राधिकरण बनाता है। उसका भरोसा केवल अस्थायी Chromium प्रोफ़ाइल में जोड़ा जाता है और HTTPS सत्यापन चालू रहता है। तीन अलग कुकी भंडार वाले ब्राउज़र सत्र HTTPS पर प्रवेश करते हैं और असली WSS कनेक्शन बनाते हैं। परीक्षण दोनों साझा ग्राहकों के फ़्रेम और स्क्रीन, दूसरे कार्यपट का अलगाव, CSRF अस्वीकृति, वापस लिए गए लेनदेन, फिर जुड़ने पर सूची और लॉगआउट से पुराने सॉकेट का बंद होना जाँचता है। .NET या नेटिव संकलन चलाने के लिए अंतिम आदेश में उसका executable और तर्क दें। [CI परीक्षण](../../.github/workflows/live-task-board.yml) SQLite को तीनों लक्ष्य पर और PostgreSQL को JVM पर चलाता है।
