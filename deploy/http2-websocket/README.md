# भाष में HTTP/2 पर WebSocket

इस अभ्यास में एक HTTPS संबंध पर सामान्य HTTP अनुरोध और कई WebSocket साथ चलते हैं। प्रवेशद्वार TLS और HTTP/2 संभालता है। हर स्वीकार किए गए WebSocket के लिए वह उसी मशीन पर भाष सर्वर से एक अलग HTTP/1.1 Upgrade संबंध खोलता है। भाष का मौजूदा `वेबसॉकेटमार्ग` कुकी, Origin, भूमिका और उपप्रोटोकॉल जाँचता है। ऐप चालक, संदेश फ़्रेम और सत्र की सफ़ाई तीनों संकलन लक्ष्यों पर पहले की तरह काम करते हैं। [RFC 8441](https://www.rfc-editor.org/rfc/rfc8441.html) · [भाष WebSocket मार्गदर्शिका](../../docs/websocket.md)

## १. स्वचालित अभ्यास

Linux या WSL पर Python 3.11+, OpenSSL, Java और Maven रखें। भंडार की मुख्य निर्देशिका से चलाएँ।

```sh
python3 -m venv .venv-http2
. .venv-http2/bin/activate
python -m pip install -r deploy/http2-websocket/requirements.txt
mvn -DskipTests package
java -jar target/bhash-compiler-0.1.0-SNAPSHOT.jar compile \
  examples/websocket/server.bhash --target jvm -o target/http2-echo.jar
python examples/websocket/verify_http2.py java -jar target/http2-echo.jar
```

परीक्षण अपना अस्थायी TLS प्रमाणपत्र बनाता है और ग्राहक उसी प्रमाणपत्र पर भरोसा करता है। ग्राहक ALPN में `h2` चुनता है और `SETTINGS_ENABLE_CONNECT_PROTOCOL=1` मिलने की प्रतीक्षा करता है। दो WebSocket धाराएँ खोलने के बाद वह एक की उत्तर विंडो रोकता है। उसी संबंध पर स्वास्थ्य अनुरोध, दूसरे WebSocket पर हिन्दी पाठ और Ping/Pong चलते रहते हैं। रुकी धारा का reset करने के बाद भी दूसरा WebSocket चलता है। परीक्षण गलत authority, Origin, कुकी और संस्करण के उत्तर भी देखता है।

.NET के लिए `--target clr -o target/http2-echo-clr` चुनें और परीक्षण को `dotnet target/http2-echo-clr/BhashProgram.dll` दें। नेटिव के लिए `--target native -o target/http2-echo` चुनें और बना executable दें। [तीनों लक्ष्यों की CI जाँच](../../.github/workflows/websocket-http2.yml)

## २. अपने भाष ऐप के आगे प्रवेशद्वार लगाएँ

[साझा कार्यपट](../../examples/live-task-board/README.md) या किसी दूसरे भाष WebSocket ऐप को `127.0.0.1:8080` पर चलाएँ। ऐप के सत्र प्रबंधक में उदाहरण के लिए `https://board.example.org:8443` सार्वजनिक Origin रखें। अपने वास्तविक नाम और पोर्ट का इस्तेमाल करें। प्रवेशद्वार को उसी authority और उस नाम का TLS प्रमाणपत्र दें।

```sh
python deploy/http2-websocket/gateway.py \
  --authority board.example.org:8443 --backend-port 8080 \
  --bind 0.0.0.0 --port 8443 --cert fullchain.pem --key privkey.pem
```

बाहरी ग्राहक केवल प्रवेशद्वार तक पहुँचें। ऐप का HTTP पोर्ट निजी रखें। प्रवेशद्वार हमेशा `127.0.0.1` पर दिए गए पोर्ट से जुड़ता है। ग्राहक की `:authority` किसी दूसरे सर्वर का पता तय नहीं करती। यह प्रवेशद्वार TLS पर ALPN `h2` स्वीकार करता है। RFC 8441 समर्थित ग्राहक चाहिए। पुराने HTTP/1.1 ग्राहकों के लिए मौजूदा HTTPS reverse proxy वाला रास्ता रखें। ऐप के लिए प्रवेशद्वार अलग प्रक्रिया है, इसलिए नेटिव executable की JVM या .NET पर निर्भरता नहीं जुड़ती।

## ३. एक WebSocket धारा कैसे खुलती है

1. ग्राहक `CONNECT` के साथ `:protocol = websocket`, `:scheme`, `:authority` और `:path` भेजता है। साथ में Origin, सत्र कुकी, संस्करण 13 और चाहें तो उपप्रोटोकॉल का प्रस्ताव जाता है।
2. प्रवेशद्वार HTTPS authority और शीर्ष जाँचता है। वह निजी HTTP/1.1 संबंध के लिए अलग WebSocket कुंजी बनाता है। भाष सर्वर के `101` और सही स्वीकृति की जाँच के बाद ही ग्राहक को HTTP/2 `200` मिलता है। HTTP/2 के उत्तर में HTTP/1.1 की कुंजी, स्वीकृति और Upgrade शीर्ष नहीं जाते।
3. HTTP/2 DATA के भीतर WebSocket फ़्रेम चलते हैं। हर धारा अपनी प्रवाह-विंडो का हिसाब रखती है। प्रवेशद्वार कनेक्शन की साझा विंडो लौटाता है ताकि एक रुकी धारा बाकी धाराओं को रोक न दे। रीसेट केवल उसी धारा का निजी सॉकेट बंद करता है।
4. सामान्य HTTP अनुरोध उसी संबंध पर जा सकते हैं। साधारण HTTP सामग्री की सीमा 1 MiB है। उदाहरण प्रवेशद्वार एक संबंध पर अधिकतम 16 धाराएँ और कुल 16 सक्रिय HTTP/2 संबंध स्वीकार करता है। भाष सर्वर की अपनी प्रवेश सीमाएँ भी लागू रहती हैं।

यह HTTP/2 प्रवेशद्वार एक तय भाष ऐप और authority के लिए है। कई ऐपों की routing, HTTP/3 और WebSocket compression अलग क्षमताएँ हैं। [HTTP/2 धारा और प्रवाह नियंत्रण](https://www.rfc-editor.org/rfc/rfc9113.html#section-5)
