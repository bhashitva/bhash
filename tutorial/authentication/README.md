# प्रमाणीकरण और सत्र (Authentication and sessions)

**प्रमाणीकरण (Authentication)** बताता है कि अनुरोध किस खाते से आया है।
**प्राधिकरण (Authorization)** उसके बाद तय करता है कि उस खाते को यह काम करने की
अनुमति है या नहीं। सही कूटशब्द दोनों समस्याएँ अपने आप नहीं सुलझाता: browser को
सत्र कुकी चाहिए, सर्वर को उस सत्र की आयु/अनुमति जाँचनी है, और अवस्था बदलने वाले
अनुरोध को CSRF से बचाना है। इस अध्याय में ये भाग साथ काम करते हैं।

पूरा [सुरक्षित टिप्पणियाँ अनुप्रयोग](../../examples/auth-sessions/README.md)
पहले चलाकर देखें। उसमें SQLite खाते, login/logout, दो खातों की अलग टिप्पणियाँ
और व्यवस्थापक मार्ग हैं। फिर नीचे के छोटे संदर्भ-उदाहरण पढ़ें।

## चलाने योग्य संदर्भ (Runnable references)

Repository root से compiler बनाएँ, फिर लक्ष्य चुनें:

```sh
mvn clean package -DskipTests
java -jar target/bhash-compiler-0.1.0-SNAPSHOT.jar run tutorial/authentication/examples/01-passwords.bhash --target jvm
java -jar target/bhash-compiler-0.1.0-SNAPSHOT.jar run tutorial/authentication/examples/02-sessions.bhash --target clr
java -jar target/bhash-compiler-0.1.0-SNAPSHOT.jar run tutorial/authentication/examples/03-authorization.bhash --target native
```

हर फ़ाइल तीनों desktop targets पर चलती है। साथ की `.expected.txt` फ़ाइलें निश्चित
गुणों का परिणाम बताती हैं; वास्तविक random token या घड़ी का अंक निश्चित नहीं है।
Linux native में `libcrypto.so.3` runtime चाहिए; LLVM/C विकास headers केवल compiler
toolchain के लिए हैं, generated application के लिए नहीं। Windows में CNG तथा
macOS में CommonCrypto OS के साथ आते हैं। Browser/WASM में server authentication
स्पष्ट अपवाद देता है; यह browser में password database बनाने का API नहीं है।

## कूटशब्द हैश (Password hashing)

**हैशबनाओ (Hash password)** — `कूटशब्द.हैशबनाओ(कूटशब्द: पाठ) → पाठ` में असली
कूटशब्द दें। यह हर बार नया १६-बाइट salt और PBKDF2-HMAC-SHA-256 का ३२-बाइट परिणाम
बनाता है। ६००,००० iterations से अनुमान लगाने की लागत बढ़ती है। Salt गुप्त नहीं
है; वह algorithm और work factor सहित लौटे हुए पाठ में रहता है। उसी पूरे पाठ को
database में parameterized SQL से रखें। इसे साधारण SHA-256 या seeded random से
बदलना सुरक्षित विकल्प नहीं है।

**जाँचो (Verify password)** — `कूटशब्द.जाँचो(कूटशब्द: पाठ, हैश: पाठ) → तार्किक`
फिर से key derivation करके constant-time तुलना करता है। नया hash बनाकर पाठ की
बराबरी न जाँचें, क्योंकि नया salt अलग होगा। गलत कूटशब्द तथा अज्ञात/अमान्य hash
format पर असत्य मिलता है। कूटशब्द की लंबाई अमान्य हो तो अपवाद आता है।

निम्न पंक्तियाँ [01-passwords.bhash](examples/01-passwords.bhash) के `मुख्य` में हैं;
उस फ़ाइल के आयात और पूरा setup साथ देखें:

```bhash
स्थिर हैश: पाठ = कूटशब्द.हैशबनाओ("लंबा-कूटशब्द-भारत😀");
लिखो("सही कूटशब्द:",कूटशब्द.जाँचो("लंबा-कूटशब्द-भारत😀",हैश));
लिखो("गलत कूटशब्द:",कूटशब्द.जाँचो("दूसरा कूटशब्द",हैश));
```

Library १–१,०२४ **UTF-8 बाइट** स्वीकारती है। उदाहरण provisioning में कम से कम
१२ Unicode code points माँगता है। `भ` एक code point और तीन UTF-8 बाइट है।
कूटशब्द न trim होता है, न छोटे अक्षरों में बदलता है, न Unicode normalization
होता है। हिन्दी, emoji, बीच में NUL और ६४ बाइट से लंबे कूटशब्द का व्यवहार तीनों
targets पर समान है। Input सीमा अपने web form/JSON पर भी लगाएँ।

Stored format `$bhash$pbkdf2-sha256$600000$<salt>$<key>` में salt/key lowercase hex
हैं। Library मनमाने iteration count को नहीं चलाती; database में दूषित record
असीमित CPU काम नहीं माँग सकता। Hash और plaintext दोनों application log में न लिखें।
प्रदाता की असफलता में सुरक्षित fallback का अनुमान लगाने के बजाय अपवाद आता है।

**Algorithm का चुनाव (Algorithm choice)** — OWASP सामान्यतः Argon2id को प्राथमिकता
देता है। इस शुरुआती portable API में सभी platforms के स्थापित crypto providers
से PBKDF2 लिया गया है; अपना cryptographic algorithm नहीं लिखा गया। यह FIPS
प्रमाणन का दावा नहीं है। भविष्य में नया algorithm जोड़ते समय पुराने encoded
hash को पहचानकर सफल login पर migration की नीति जोड़ी जा सकती है।

## गुप्त टोकन (Secure tokens)

**टोकन (Secure random token)** — `सुरक्षा.टोकन() → पाठ` ३२ random बाइट को ६४
lowercase hex अक्षरों में देता है। यह password salt, session ID और CSRF के लिए
cryptographic random स्रोत है। `यादृच्छिक.बीज` का इससे कोई संबंध नहीं है।

**समान (Constant-time equality)** — `सुरक्षा.समान(पहला: पाठ, दूसरा: पाठ) → तार्किक`
गुप्त पाठ की UTF-8 बाइटों की fixed-time तुलना करता है। Content मिलने पर जल्दी
लौटने वाली साधारण तुलना से बचें। अलग लंबाई पर असत्य आता है; प्रत्येक input
८,१९२ UTF-8 बाइट तक सीमित है। Password के लिए यह password hashing का विकल्प नहीं है।

**मिलीसेकंड (Monotonic milliseconds)** — `सुरक्षाघड़ी.मिलीसेकंड() → पूर्णांक`
आगे बढ़ती monotonic घड़ी का अंक देता है। इसे calendar date
या अलग machines के समय का तुलनीय प्रमाण न मानें। पहला उदाहरण केवल आगे बढ़ने का
गुण जाँचता है। वास्तविक अंक print करके `.expected.txt` में स्थिर न करें।

## सत्र जीवनचक्र (Session lifecycle)

**सत्रप्रबंधक (Session manager)** — `नया सत्रप्रबंधक(मूल: पाठ)` में वास्तविक
public HTTPS origin दें, जैसे `https://app.example`। Path/trailing slash न दें।
Default capacity १,०२४, idle सीमा ३० मिनट और absolute सीमा ८ घंटे है। संसाधन block
समाप्त होने पर सत्रों के GC references छोड़ दिए जाते हैं।

**अनुकूलित नीति (Configurable policy)** — दूसरा constructor
`नया सत्रप्रबंधक(मूल: पाठ, क्षमता: पूर्णांक, निष्क्रियसीमा: पूर्णांक,
पूर्णसीमा: पूर्णांक, घड़ी: सत्रघड़ी)` है। समय milliseconds में है। Capacity
१–१०,००० और absolute सीमा अधिकतम ७ दिन है; वह idle सीमा से छोटी नहीं हो सकती।
[02-sessions.bhash](examples/02-sessions.bhash) छोटी घड़ी से expiry का निश्चित
उदाहरण देता है; उस घड़ी को production में न लगाएँ।

**सत्रघड़ी (Session clock)** — इस अनुबंध का `अब() → पूर्णांक` कार्य tests में
समय नियंत्रित करने देता है। **वास्तविकसत्रघड़ी (System session clock)** का
`नया वास्तविकसत्रघड़ी()` और `अब()` default monotonic स्रोत प्रयोग करता है।
घड़ी पीछे जाए तो प्रबंधक चुपचाप सत्र की आयु नहीं बढ़ाता, अपवाद देता है।

**आरंभ (Create session)** — `म.आरंभ(उपयोगकर्ता: पाठ, भूमिकाएँ: सूची<पाठ>) → सत्र`
नई session/CSRF पहचान बनाता है। खाली उपयोगकर्ता और खाली roles से anonymous
pre-login सत्र बनाएँ। Authenticated खाता और roles केवल विश्वसनीय database/identity
स्रोत से दें, request JSON से नहीं। Capacity भर जाए तो HTTP 503 आता है; पुराने
live सत्र को मनमाने ढंग से निकालकर जगह नहीं बनाई जाती।

**सत्र विवरण (Session identity)** — `स.पहचान() → पाठ`, `स.उपयोगकर्ता() → पाठ`,
`स.सीएसआरएफ() → पाठ` और `स.भूमिकाहै(भूमिका: पाठ) → तार्किक` पहचान, account,
अलग CSRF secret और role जाँच देते हैं। Session role list की प्रतिलिपि रखता है;
मूल list को बाद में बदलने से अधिकार नहीं बदलते। `पहचान()` को log/JSON/URL में न दें।
CSRF token को same-origin JSON में browser memory तक पहुँचाएँ; session cookie
HttpOnly होने से JavaScript को session ID पढ़ने की आवश्यकता नहीं है।

**पाओ (Look up session)** — `म.पाओ(अनुरोध: वेबअनुरोध) → सत्र` cookie से ID पढ़ता
है। अज्ञात/समाप्त ID पर `रिक्त` आता है। Valid lookup idle समय आगे करता है,
absolute जीवनकाल नहीं। इसका परिणाम रिक्त हो सकता है, इसलिए सीधे उसके सदस्य न पढ़ें।

**बदलो (Rotate session)** — `म.बदलो(पुराना: सत्र, उपयोगकर्ता: पाठ,
भूमिकाएँ: सूची<पाठ>) → सत्र` सफल credential verification के बाद बुलाएँ। नया
session ID और नया CSRF secret मिलता है; पुराना ID और token फिर स्वीकार नहीं होते।
यह session fixation रोकने का आवश्यक चरण है। नया सत्र cookie में फिर रखें।

**हटाओ (Revoke session)** — `म.हटाओ(मानक: सत्र) → शून्य` एक सत्र हटाता है।
**उपयोगकर्ताहटाओ (Revoke all user sessions)** —
`म.उपयोगकर्ताहटाओ(उपयोगकर्ता: पाठ) → शून्य` सभी उपकरणों के सत्र हटाता है।
Password या permissions बदलते समय दूसरे कार्य का उपयोग करें; roles session में
snapshot हैं। केवल cookie मिटाने से सर्वर का सत्र नहीं हटता।

**साफकरो (Purge expired sessions)** — `म.साफकरो() → शून्य` समाप्त entries
हटाता है। **आकार (Live session count)** — `म.आकार() → पूर्णांक` सफाई के बाद
live count देता है। Idle/absolute deadline के ठीक बराबर समय पर सत्र समाप्त है।

**बंदकरो (Dispose session manager)** — `म.बंदकरो() → शून्य` सभी entries छोड़ता
है। यह idempotent है। सामान्यतः `संसाधन` इसका आह्वान करता है; बंद प्रबंधक फिर
उपयोग न करें। [02-sessions.expected.txt](examples/02-sessions.expected.txt) में
rotation, expiry और सभी-device revocation का परिणाम देखें।

`सत्र` का constructor implementation/testing के लिए है; उसी ID से अपनी `नया सत्र`
वस्तु बनाने पर प्रबंधक उसे स्वीकार नहीं करता। हमेशा `आरंभ`/`बदलो` का लौटाया सत्र
प्रयोग करें। `सत्रप्रविष्टि` और `प्रवेशप्रविष्टि` library के storage helpers हैं;
application उन्हें सीधे न बनाए।

## सुरक्षित कुकी (Secure cookies)

**कुकीरखो (Issue session cookie)** — `म.कुकीरखो(उत्तर: वेबउत्तर, मानक: सत्र) → शून्य`
केवल अपने live stored session की cookie जोड़ता है:

```text
__Host-bhash-session=<id>; Path=/; Secure; HttpOnly; SameSite=Lax
```

Secure से public HTTPS, HttpOnly से JavaScript की सीधी cookie पहुँच, host-only
नाम/Path से scope और SameSite=Lax से अतिरिक्त CSRF रक्षा मिलती है। Domain नहीं
जोड़ा जाता। यह session-only browser cookie है; असली expiry सर्वर की policy है।

**कुकीमिटाओ (Delete session cookie)** — `म.कुकीमिटाओ(उत्तर: वेबउत्तर) → शून्य`
उसी नाम/path पर `Max-Age=0` भेजता है। यह केवल browser cookie हटाता है; server
entry भी हटाएँ, या नीचे का संयुक्त `समाप्त` प्रयोग करें।

**सुरक्षितजोड़ो (Add secure cookie)** — `कुकी.सुरक्षितजोड़ो(उत्तर: वेबउत्तर,
नाम: पाठ, मानक: पाठ, आयु: पूर्णांक) → शून्य` दूसरी cookie जोड़ सकता है। Flags
ऊपर जैसे ही हैं। आयु seconds में -१ session-only, ० deletion, अधिकतम २,६७८,४००
(३१ दिन) है। यह password या authorization data cookie में रखने का सुझाव नहीं है।

**शीर्षजोड़ो (Append response header)** — `उत्तर.शीर्षजोड़ो(नाम: पाठ, मानक: पाठ) → शून्य`
पहले header को मिटाए बिना दूसरा जोड़ता है। दो Set-Cookie को comma से जोड़ना
सही नहीं है। `शीर्षरखो` उसी नाम का पिछला मान बदलने के लिए है। CRLF injection
दोनों में रोकी जाती है।

**पढ़ो (Read cookie)** — `कुकी.पढ़ो(अनुरोध: वेबअनुरोध, नाम: पाठ) → पाठ`
नाम न मिलने पर खाली पाठ देता है। एक ही नाम दो बार, अलग Cookie headers में भी,
HTTP 400 देता है। नाम case-sensitive हैं। Header आकार/pair count सीमित हैं।

**शीर्षलो (Read request header values)** — `कुकी.शीर्षलो(अनुरोध: वेबअनुरोध,
नाम: पाठ) → सूची<पाठ>` में lowercase header नाम दें। यह request fields के case
को सामान्य करता है और सभी मान देता है; sensitive header पर ambiguity को caller
पहचान सकता है। सुरक्षा policy इसका उपयोग करती है।

**नामजाँचो (Validate cookie name)** — `कुकी.नामजाँचो(नाम: पाठ) → शून्य` और
**मानजाँचो (Validate cookie value)** — `कुकी.मानजाँचो(मानक: पाठ) → शून्य` वैध
ASCII cookie syntax जाँचते हैं। `;`, CRLF, quotes और Unicode को value में सीधे
न डालें। नाम/value दूषित हों तो अपवाद आता है। ये जाँचें secure setter भी करता है।

**स्थानीय (Loopback development mode)** —
`सत्रप्रबंधक.स्थानीय(मूल: पाठ) → सत्रप्रबंधक` केवल `http://127.0.0.1[:port]`
या `http://localhost[:port]` स्वीकारता है। यहाँ अलग `bhash-local-session` नाम है
और Secure छोड़ा जाता है। यह production constructor का स्वतः downgrade नहीं है।
उदाहरण application में `--local=true` स्पष्ट दें और loopback listener रखें।

## अनुरोध रक्षा और अनुमति (CSRF and authorization)

**मूलजाँचो (Validate request origin)** — `म.मूलजाँचो(अनुरोध: वेबअनुरोध) → शून्य`
configured Host और किसी उपस्थित Origin की exact जाँच करता है। Origin में
trailing slash/path न दें। Proxy को सही Host रखना है; X-Forwarded-* से विश्वास,
cookie flags या authorization नहीं बदलते। सामान्य cross-site navigation के safe
GET को केवल Fetch Metadata के कारण नहीं रोकते; अवस्था बदलने वाली cross-site
request अस्वीकार होती है।

**सीएसआरएफजाँचो (Validate CSRF token)** — `म.सीएसआरएफजाँचो(अनुरोध: वेबअनुरोध) → शून्य`
GET/HEAD/OPTIONS को छोड़कर हर method के लिए live session, एक सही Origin और
एक `X-CSRF-Token` माँगता है। Token उसी session का होना चाहिए। Token/Origin के
दो headers, गलत origin, missing token या दूसरे session का token अस्वीकार हैं।
SameSite अकेला CSRF नियंत्रण नहीं है। GET से data या logout न बदलें।

**सुरक्षितवेब (Security middleware)** — `नया सुरक्षितवेब(सत्रमान: सत्रप्रबंधक,
चालक: वेबचालक)` से पूरे router को लपेटें। इसका `संभालो(अनुरोध) → वेबउत्तर`
पहले policy जाँचता है, फिर inner handler चलाता है। 4xx policy errors सहित responses
में no-store/nosniff/referrer-policy लगती है। Login और logout को इसके बाहर न रखें।

**अधिकृत (Authorize request)** — `म.अधिकृत(अनुरोध: वेबअनुरोध, भूमिका: पाठ) → सत्र`
authenticated session लौटाता है। अनुपस्थित/anonymous सत्र पर 401, भूमिका न होने
पर 403 है। खाली भूमिका का अर्थ किसी भी authenticated खाते को अनुमति है। इसमें
state-changing request की CSRF जाँच भी होती है।

**अधिकृतचालक (Authorized handler)** — `नया अधिकृतचालक(सत्रमान: सत्रप्रबंधक,
भूमिका: पाठ, चालक: वेबचालक)` किसी route को सुरक्षित करता है। इसका
`संभालो(अनुरोध) → वेबउत्तर` अनुमति के बाद ही inner handler चलाता है।
[03-authorization.bhash](examples/03-authorization.bhash) दोनों wrappers साथ प्रयोग
करता है। Role मिलने के बाद भी SQL में resource owner से scope करें; केवल login
होना दूसरे खाते की rows पढ़ने का अधिकार नहीं देता।

**समाप्त (CSRF-protected logout)** — `म.समाप्त(अनुरोध: वेबअनुरोध, उत्तर: वेबउत्तर) → शून्य`
state-changing method और CSRF जाँचकर server entry हटाता तथा cookie मिटाता है।
GET/HEAD/OPTIONS से इसे बुलाने पर 405 मिलता है। Login पर प्राप्त नया CSRF token
logout में भेजें; pre-login token अब अमान्य है।

## प्रवेश प्रयास की सीमा (Login attempt limiting)

**प्रवेशरक्षक (Login rate limiter)** — `नया प्रवेशरक्षक()` पाँच प्रयास/पाँच मिनट
और अधिकतम १,०२४ active account keys रखता है। दूसरा constructor
`नया प्रवेशरक्षक(सीमा: पूर्णांक, अवधि: पूर्णांक, क्षमता: पूर्णांक, घड़ी: सत्रघड़ी)`
tests/विशेष policy के लिए है: सीमा १–१००, अवधि १–३,६००,००० milliseconds,
capacity १–१०,०००। घड़ी `सत्रघड़ी` जैसी monotonic हो।

**प्रयासकरो (Consume login attempt)** — `रक्षक.प्रयासकरो(कुंजी: पाठ) → शून्य`
database lookup और महँगे hash से पहले बुलाएँ। समान canonical account key दें।
वर्तमान window में सीमा या capacity भरने पर 429 आता है; active counter हटाकर
attacker को नया प्रयास नहीं मिलता। Success भी प्रयास गिनता है।

**बंदकरो (Dispose limiter)** — `रक्षक.बंदकरो() → शून्य` memory छोड़ता है और
idempotent है; `संसाधन` से स्वतः बुलाया जा सकता है। केवल account सीमा पर्याप्त
नहीं: proxy पर client/global सीमा भी लगाएँ, क्योंकि हमलावर किसी दूसरे खाते की
attempt window भर सकता है। किसी अनुपस्थित खाते पर भी वैध dummy hash जाँचें और
गलत password जैसा ही status/body लौटाएँ। Demo में ये दोनों व्यवहार मौजूद हैं।

## पूर्ण अनुप्रयोग में क्रम (Application walkthrough)

1. [Provisioning निर्देश](../../examples/auth-sessions/README.md) से पहले account
   बनाएँ। Plaintext command arguments में न दें; एक बार का environment secret दें
   और फिर हटाएँ। Salted hash SQLite में parameters और transaction से लिखा जाता है।
2. Browser `GET /session` करता है। उसे anonymous cookie और JSON CSRF token मिलता है।
   Token केवल memory में है; cookie HttpOnly है, localStorage की आवश्यकता नहीं।
3. Login form JSON POST भेजता है। Browser same-origin Origin भेजता है; JavaScript
   CSRF header जोड़ता है। Middleware असफल हो तो credential handler चलता ही नहीं।
4. Handler attempt limit लगाता है, parameterized SELECT से stored hash/role पढ़ता
   है और सही password पर `बदलो` बुलाता है। नया cookie और नया CSRF token मिलते हैं।
5. Notes route session user को SQL owner parameter में डालता है। Client का `owner`
   field अनदेखा रहता है। `admin` route पर अलग role wrapper है। HTML में user text
   `textContent` से दिखता है; CSP inline script और framing को रोकता है।
6. POST logout server entry और browser cookie दोनों हटाता है। Restart पर memory
   sessions मिटते हैं, जबकि SQLite accounts/notes रहते हैं।

Production में HTTPS reverse proxy, सीमित backend पहुँच, सुरक्षित database file
permissions/backups और per-client/global limits रखें। यह store **एक process** के
लिए है; multiple workers में साझा durable sessions उपलब्ध नहीं हैं। Password
reset/MFA/external identity provider इस उदाहरण का हिस्सा नहीं हैं।

Native tests हर allocation पर collector चलाकर जीवित session IDs, role lists,
CSRF secrets, exception unwinding और disposal जाँचते हैं। JVM/CLR वही fixtures
चलाते हैं। [विस्तृत अनुबंध और test सूची](../../docs/authentication.md) देखें।
