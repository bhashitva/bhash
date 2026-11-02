# सुरक्षित टिप्पणियाँ (Authenticated notes)

यह चलने योग्य उदाहरण कूटशब्द हैश, सुरक्षित सत्र, CSRF, भूमिका-आधारित अनुमति,
और SQLite में प्रत्येक खाते की निजी टिप्पणियाँ साथ दिखाता है। पूरा
[हिन्दी मार्गदर्शन](../../tutorial/authentication/README.md) और
[सुरक्षा अनुबंध](../../docs/authentication.md) भी पढ़ें।

## बनाना (Build)

इस फ़ोल्डर से JVM, CLR या native में से एक लक्ष्य चुनें:

```sh
java -jar ../../target/bhash-compiler-0.1.0-SNAPSHOT.jar compile bhash.project --target jvm -o build/auth.jar
java -jar ../../target/bhash-compiler-0.1.0-SNAPSHOT.jar compile bhash.project --target clr -o build/auth-clr
java -jar ../../target/bhash-compiler-0.1.0-SNAPSHOT.jar compile bhash.project --target native -o build/auth
```

Native के लिए पहले उपयुक्त Bhash SDK तैयार करें। Linux पर कूटशब्द कार्यों के लिए
OpenSSL 3 का `libcrypto.so.3` **runtime** चाहिए; C विकास पैकेज नहीं। Windows में
CNG और macOS में CommonCrypto पहले से OS के साथ हैं।

## पहला खाता (Account provisioning)

कोई डिफ़ॉल्ट खाता या कूटशब्द नहीं है। सर्वर शुरू करने से पहले खाता बनाएँ।
कूटशब्द command-line तर्क में न दें। Bash में बिना दिखाए पढ़कर केवल provisioning
प्रक्रिया को दें, फिर परिवेश से हटा दें:

```sh
read -r -s -p 'कूटशब्द (कम से कम १२ अक्षर): ' BHASH_INITIAL_PASSWORD
printf '\n'
export BHASH_INITIAL_PASSWORD
java -jar build/auth.jar --db=accounts.db --init-user=manoj --role=admin
unset BHASH_INITIAL_PASSWORD
```

PowerShell में `Read-Host -MaskInput` प्रयोग कर सकते हैं:

```powershell
$env:BHASH_INITIAL_PASSWORD = Read-Host 'कूटशब्द (कम से कम १२ अक्षर)' -MaskInput
java -jar build/auth.jar --db=accounts.db --init-user=manoj --role=admin
Remove-Item Env:BHASH_INITIAL_PASSWORD
```

`--role=reader` से सामान्य खाता बनाएँ। `--init-user` केवल नया खाता जोड़ता है;
मौजूदा खाते को बदलता नहीं। कूटशब्द के १२ अक्षर Unicode code points हैं; अधिकतम
१,०२४ UTF-8 बाइट हैं। उत्पादन में परिवेश सहित पूरी provisioning प्रक्रिया को
गोपनीय रखें; कूटशब्द और डेटाबेस को repository में commit न करें।

## स्थानीय उपयोग (Local development)

```sh
java -jar build/auth.jar --local=true --port=8080 --db=accounts.db
```

अब `http://127.0.0.1:8080` खोलें। `localhost` से बदलकर न खोलें: origin और Host
सुरक्षा जाँच में यह अलग नाम है। CLR के लिए `dotnet build/auth-clr/BhashProgram.dll --local=true --port=8080 --db=accounts.db` और native के लिए `./build/auth --local=true --port=8080 --db=accounts.db` चलाएँ। Windows native build में `-o build/auth.exe` और चलाने के लिए `./build/auth.exe` दें।
इस स्पष्ट स्थानीय मोड में कुकी `bhash-local-session` है और Secure छोड़ता है;
इसे सार्वजनिक इंटरनेट के लिए प्रयोग न करें। listener को loopback पर ही रहने दें।

VS Code में यह फ़ोल्डर खोलकर compiler से build करें, फिर integrated terminal में ऊपर का executable command चलाएँ। इस फ़ोल्डर का working directory रखें ताकि तीन स्थिर assets मिलें। `run` compiler command application तर्क आगे नहीं भेजता; इस उदाहरण में arguments देने के लिए बने हुए artifact को सीधे चलाएँ।

## HTTPS पर चलाना (Deployment)

```sh
./build/auth --port=8080 --origin=https://notes.example.org --db=/srv/bhash-notes/accounts.db
```

उसी मशीन पर Caddy जैसे TLS proxy में वास्तविक domain रखें:

```caddyfile
notes.example.org {
    reverse_proxy 127.0.0.1:8080
}
```

Proxy मूल Host बनाए रखे। Backend पोर्ट को सार्वजनिक न करें। Production मोड में
`__Host-bhash-session` कुकी Secure, HttpOnly, SameSite=Lax और Path=/ के साथ आती है।
`X-Forwarded-Proto` भेजकर इन नियमों को बदला नहीं जा सकता। Native वितरण में
application binary चलाएँ; generated application को JVM/.NET नहीं चाहिए।

## अनुरोध क्रम (Request flow)

1. `GET /session` अनाम सत्र और JSON में CSRF token देता है। Session ID केवल
   HttpOnly cookie में रहता है। Token को browser memory में रखें।
2. `POST /login` में JSON `username`/`password`, सही Origin, cookie और
   `X-CSRF-Token` दें। सफल प्रवेश पर दोनों रहस्य बदलते हैं।
3. `GET /api/notes` तथा `POST /api/notes` खाते की अपनी टिप्पणियाँ पढ़ते/जोड़ते हैं।
   POST JSON में `text` दें। किसी client-supplied `owner` से अनुमति नहीं मिलती।
4. `GET /api/admin/users` केवल `admin` भूमिका के लिए है। सामान्य भूमिका को 403 मिलता है।
5. `POST /logout` सही CSRF के साथ सत्र हटाता है और कुकी मिटाता है। GET से logout नहीं होता।

हर अवस्था बदलने वाले अनुरोध में CSRF token और exact Origin चाहिए। `curl` में भी
ये दोनों स्पष्ट दें; यह browser तक सीमित शर्त नहीं है। Invalid credentials और
अनुपस्थित खाते के उत्तर समान हैं। पाँच प्रयास/पाँच मिनट की account सीमा hash से
पहले लगती है। Proxy पर client/global सीमा भी रखें।

सत्र एक process की memory में हैं: restart पर प्रवेश फिर करना होगा। खाते और
टिप्पणियाँ SQLite में रहते हैं। इसे अनेक workers का साझा session store न मानें।
कूटशब्द/भूमिका बदलने पर `उपयोगकर्ताहटाओ` से सभी पुराने सत्र हटाएँ। यह उदाहरण
password reset, सार्वजनिक signup या MFA उपलब्ध नहीं कराता।

## जाँच (Tests)

```sh
java -jar ../../target/bhash-compiler-0.1.0-SNAPSHOT.jar test tests --target jvm
java -jar ../../target/bhash-compiler-0.1.0-SNAPSHOT.jar test tests --target clr
java -jar ../../target/bhash-compiler-0.1.0-SNAPSHOT.jar test tests --target native
```

Repository CI वास्तविक HTTP login/logout, permission failures, CSRF, SQL parameters,
दो खातों का अलग डाटा, persistence और native forced-GC भी जाँचता है।
