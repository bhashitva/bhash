# Linux पर भाष वेब अनुप्रयोग का परिनियोजन

कार्यपट एक Linux प्रक्रिया है। Caddy, Nginx या Apache सार्वजनिक HTTPS स्वीकार करता है और अनुरोध उसी मशीन पर `127.0.0.1:8080` को भेजता है। भाष के मार्ग और executable तीनों प्रॉक्सी में समान हैं। WebSocket के लिए प्रॉक्सी पीछे के संबंध पर HTTP/1.1 Upgrade भेजता है।

## १. एक बार संकलित करें

Linux पर [नेटिव टूलचेन](../../docs/managed-native-toolchain.md) तैयार करें। भंडार की मुख्य निर्देशिका से चलाएँ।

```sh
mvn -DskipTests package
java -jar target/bhash-compiler-0.1.0-SNAPSHOT.jar compile \
  examples/live-task-board/bhash.project --target native -o target/live-task-board
```

Executable और `index.html`, `app.js`, `style.css` को `/opt/bhash-board` में रखें। SQLite के लिए अलग लिखने योग्य निर्देशिका रखें। नीचे `board.example.org` की जगह अपना DNS नाम दें। कार्यपट को सेवा खाते से चलाएँ। उस खाते के लिए तीन अलग कूटशब्द, कम से कम १२ अक्षर के, `BHASH_BOARD_ALICE_PASSWORD`, `BHASH_BOARD_BOB_PASSWORD`, और `BHASH_BOARD_CHARLIE_PASSWORD` में रखें।

```sh
/opt/bhash-board/live-task-board --port=8080 \
  --origin=https://board.example.org --assets=/opt/bhash-board \
  --db=/var/lib/bhash-board/board.db
```

सार्वजनिक सेवा में `--local=true` छोड़ दें। ऐप का HTTPS मूल `--origin` से तय होता है। प्रॉक्सी मूल `Host` और ब्राउज़र का `Origin` पहुँचाए। ऐप मूल या पहचान तय करने के लिए `X-Forwarded-Proto` या `X-Forwarded-For` पर निर्भरता नहीं रखता। यदि अपना ऐप इन शीर्षों का उपयोग करे, तो केवल अपने भरोसेमंद प्रॉक्सी से आए मान स्वीकार करें। प्रवेश कुकी में `Secure`, `HttpOnly` और `SameSite=Lax` हैं।

## २. सार्वजनिक HTTPS के लिए एक प्रॉक्सी चुनें

### Caddy

```caddy
board.example.org {
    reverse_proxy 127.0.0.1:8080
}
```

Caddy अपने HTTPS विन्यास के अनुसार सार्वजनिक प्रमाणपत्र प्राप्त और नवीनीकृत करता है। [Caddy reverse proxy](https://caddyserver.com/docs/caddyfile/directives/reverse_proxy)

### Nginx

`map` को `http` खंड में रखें। प्रमाणपत्र और कुंजी के पथ अपने TLS विन्यास से लें।

```nginx
map $http_upgrade $connection_upgrade {
    default upgrade;
    '' close;
}
server {
    listen 443 ssl;
    server_name board.example.org;
    ssl_certificate /etc/tls/board/fullchain.pem;
    ssl_certificate_key /etc/tls/board/privkey.pem;

    location / {
        proxy_pass http://127.0.0.1:8080;
        proxy_http_version 1.1;
        proxy_set_header Host $http_host;
        proxy_set_header Upgrade $http_upgrade;
        proxy_set_header Connection $connection_upgrade;
        proxy_read_timeout 300s;
    }
}
```

[Nginx WebSocket proxying](https://nginx.org/en/docs/http/websocket.html)

### Apache HTTP Server

Apache 2.4.47 या बाद का संस्करण और `ssl`, `proxy`, `proxy_http` मॉड्यूल उपयोग करें।

```apache
<VirtualHost *:443>
    ServerName board.example.org
    SSLEngine on
    SSLCertificateFile /etc/tls/board/fullchain.pem
    SSLCertificateKeyFile /etc/tls/board/privkey.pem
    ProxyRequests Off
    ProxyPreserveHost On
    ProxyPass / http://127.0.0.1:8080/ upgrade=websocket retry=0
    ProxyPassReverse / http://127.0.0.1:8080/
</VirtualHost>
```

[Apache mod_proxy और WebSocket Upgrade](https://httpd.apache.org/docs/2.4/mod/mod_proxy.html#protoupgrade)

## ३. स्वास्थ्य जाँचें और सेवा फिर चलाएँ

```sh
curl --fail https://board.example.org/healthz
```

`200` और `ok` मिलने पर प्रॉक्सी और ऐप दोनों उत्तर दे रहे हैं। दो स्वतंत्र ब्राउज़र सत्रों में ऐलिस और बॉब से प्रवेश करें। एक सत्र में काम जोड़ें और दूसरे में बदलाव देखें।

लंबे समय तक चलाने के लिए [systemd उदाहरण](../web-tasks/deploy/bhash-tasks.service) के सेवा खाते, पथ और तर्क अपने कार्यपट के अनुसार बदलें। कूटशब्द सेवा खाते के लिए पढ़ने योग्य सुरक्षित परिवेश फ़ाइल में रखें। रोकने के लिए systemd का सामान्य `SIGTERM` और कम से कम १५ सेकंड का समय दें। फिर चलाने पर ब्राउज़र फिर प्रवेश करता है, नया WebSocket बनाता है और SQLite से सहेजे हुए काम देखता है। यह उदाहरण एक ऐप प्रक्रिया का है। सत्र और लाइव सूचनाएँ उसी प्रक्रिया की स्मृति में रहती हैं।

## ४. तीनों प्रॉक्सी की जाँच दोहराएँ

Ubuntu पर Caddy, Nginx, Apache, OpenSSL और `libnss3-tools` स्थापित करें। सामान्य Linux उपयोगकर्ता के रूप में चलाएँ।

```sh
python -m pip install playwright==1.58.0
python -m playwright install --with-deps chromium
python examples/live-task-board/verify_deployment.py target/live-task-board
```

परीक्षण तीनों प्रॉक्सी के लिए अलग अस्थायी SQLite फ़ाइल और प्रमाणपत्र बनाता है। Chromium का अस्थायी प्रोफ़ाइल परीक्षण CA पर भरोसा करता है। प्रत्येक प्रॉक्सी पर HTTPS, सुरक्षित कुकी, दो ग्राहकों के WSS अपडेट और खुले सॉकेट के साथ `SIGTERM` जाँचे जाते हैं। फिर उसी पोर्ट पर ऐप लौटता है और पुराने कार्य नए प्रवेश के बाद मिलते हैं। [GitHub Actions जाँच](../../.github/workflows/deployment-portability.yml)
