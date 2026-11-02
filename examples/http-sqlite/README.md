# HTTP और SQLite का साझा उदाहरण (HTTP + SQLite)

`main.bhash` HTTP से JSON लेख पढ़ता है, parameterized SQL और एक transaction से
स्थानीय SQLite संग्रह बदलता है, फिर SQL query का परिणाम दिखाता है। `--offline=true`
देने पर HTTP अनुरोध किए बिना वही संग्रह पढ़ता है।

पूरा हिन्दी विवरण, तीनों targets के आदेश, अपेक्षित परिणाम और rollback प्रयोग
[संदर्भ ट्यूटोरियल](../../tutorial/http-sqlite/README.md) में हैं। Bhash 0.11.0 या नया
compiler उपयोग करें। Playground/browser पर SQLite उपलब्ध नहीं है।

Repository root से एक terminal में fixture चलाएँ:

```sh
python -m http.server 8080 --bind 127.0.0.1 --directory examples/http-sqlite
```

दूसरे terminal में:

```sh
java -jar target/bhash-compiler-0.1.0-SNAPSHOT.jar compile examples/http-sqlite/main.bhash --target jvm -o target/http-sqlite/cache.jar
java -jar target/http-sqlite/cache.jar
java -jar target/http-sqlite/cache.jar --offline=true
```

Default database `article-cache.db` वर्तमान working directory में बनता है। यह
संग्रह बदलने वाला उदाहरण है: प्रत्येक सफल import `cached_posts` की पुरानी पंक्तियाँ
बदलता है। अपनी मौजूदा application database के बजाय इसके लिए अलग फ़ाइल रखें।
