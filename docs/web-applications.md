# HTTP server and small web framework

The framework is implemented in Bhash (`stdlib/web.bhash`). JVM, CLR and native
provide only a small synchronous IPv4 socket transport. All parsing, URL decoding,
routing, response validation, JSON policy and exception boundaries are shared.

**हिन्दी:** framework भाष की `stdlib/web.bhash` में है। JVM, CLR और native छोटा तुल्यकालिक IPv4 transport देते हैं। parsing, URL decoding, routing, उत्तर जाँच, JSON नियम और अपवाद सीमा साझा हैं।

## Public surface

- `वेबचालक.संभालो(वेबअनुरोध) -> वेबउत्तर`: an interface implemented by ordinary Bhash classes.

  **हिन्दी:** साधारण भाष वर्ग `वेबचालक.संभालो(वेबअनुरोध) -> वेबउत्तर` अनुबंध लागू करता है।
- `वेबअनुप्रयोग.जोड़ो(method, path, handler)`: up to 128 ordered routes. `:name` captures
  one nonempty segment. The first matching route/method wins; HEAD can use GET.

  **हिन्दी:** `वेबअनुप्रयोग.जोड़ो` में अधिकतम १२८ क्रमबद्ध मार्ग हैं। `:name` एक गैर-खाली खंड पकड़ता है। विधि और पथ से पहला मेल चुना जाता है। HEAD के लिए GET भी उपयोग हो सकता है।
- `वेबअनुरोध`: method, raw origin-form target, raw path, ordered header pairs, byte body,
  decoded `पथमान`; `प्रश्नमान` and `शीर्षमान` retain duplicate values. `पाठ्यांश` decodes
  strict UTF-8; `जेसनलो` requires application/json and maps parsing failures to 400.

  **हिन्दी:** `वेबअनुरोध` में विधि, मूल लक्ष्य और पथ, शीर्षों के क्रमबद्ध जोड़े, bytes और decoded `पथमान` हैं। `प्रश्नमान` व `शीर्षमान` दोहरे मान रखते हैं। `पाठ्यांश` सख्त UTF-8 पढ़ता है। `जेसनलो` को application/json चाहिए और खराब JSON पर 400 मिलता है।
- `वेबउत्तर(status, bytes)` and `शीर्षरखो`: validated response headers. `वेब.पाठउत्तर`,
  `.एचटीएमएल`, `.जेसन` produce UTF-8 responses; `.त्रुटि` produces a generic JSON error.

  **हिन्दी:** `वेबउत्तर` और `शीर्षरखो` उत्तर शीर्ष जाँचते हैं। `वेब.पाठउत्तर`, `.एचटीएमएल` और `.जेसन` UTF-8 देते हैं। `.त्रुटि` सामान्य JSON त्रुटि देता है।
- `वेबत्रुटि(status, message)`: expected application failure. The HTTP body exposes only
  a generic message and status; arbitrary internal exception messages are never sent.

  **हिन्दी:** `वेबत्रुटि(status, message)` अपेक्षित ऐप विफलता है। HTTP उत्तर में सामान्य संदेश और स्थिति आती है। अंदरूनी अपवाद संदेश उजागर नहीं होते।
- `एचटीटीपीसर्वर(host, port)` implements `बंदनीय`. `पोर्ट()` reports the bound port.
  `चलाओ(handler)` runs until stopped. `अगला(handler)` serves at most one accepted
  connection, returning false on an idle poll. `रोको()` requests cooperative shutdown;
  `रुकाहै()` also observes OS stop signals; `बंदकरो()` is idempotent.

  **हिन्दी:** `एचटीटीपीसर्वर` बंद किया जा सकता है। `पोर्ट()` वास्तविक पोर्ट देता है। `चलाओ` रोकने तक चलता है। `अगला` अधिकतम एक संबंध सँभालता और खाली poll पर असत्य देता है। `रोको` सहकारी रोक माँगता है। `रुकाहै` OS संकेत भी देखता है। `बंदकरो` दोबारा बुला सकते हैं।
- Helpers `वेब.विकूटित(text, formPlus)`, `.अंक`, `.संख्या`, `.मानलो` implement explicit
  percent decoding, ASCII protocol numbers and ordered pair lookup.

  **हिन्दी:** ये helpers प्रतिशत decoding, HTTP के ASCII अंक और क्रमबद्ध जोड़ों से मान ढूँढने के लिए हैं।

## Protocol and resource contract

| Item | Initial contract |
| --- | --- |
| Targets | JVM, desktop CLR, native Linux x64 / Windows x64 / macOS ARM64 |
| Browser / WASM | Listening is explicitly unsupported |
| Bind | Numeric IPv4 `127.0.0.1` or explicit `0.0.0.0`; ports 0–65535 |
| HTTP | HTTP/1.0 and HTTP/1.1 requests; HTTP/1.1 responses, Connection: close |
| Methods | GET, HEAD, POST, PUT, PATCH, DELETE, OPTIONS; other methods 501 |
| Framing | Fixed Content-Length only, maximum 1 MiB; absent length means empty body |
| Limits | 4096 bytes per request/header line, 64 fields, 16 KiB total fields; 1 MiB response body |
| Queue | OS listen backlog requested as 16 (OS semantics apply), one accepted active connection |
| Registries | Maximum 32 open server/connection handles per process |
| Deadline | 10 seconds total socket read/write budget from accept; idle accept poll 250 ms |
| Dispatch | One Bhash mutator; no concurrent handlers or unbounded application request queue |
| Shutdown | Ctrl+C / POSIX SIGTERM sets a flag; active request finishes, then resource scopes close |

Transfer-Encoding, Expect/100-continue, upgrades, WebSockets, keep-alive, HTTP/2,
streaming, multipart uploads, TLS termination and async handlers are outside this milestone.
Chunked requests return 501, Expect returns 417. A reverse proxy must buffer/dechunk
requests before forwarding. Content-Length duplicates (even equal) are rejected;
Host is required exactly once for HTTP/1.1. Obsolete folding, non-ASCII header bytes,
invalid field names, malformed percent escapes, invalid UTF-8, controls and encoded
path slashes/backslashes are rejected. Query decoding retains duplicates and treats `+`
as space; path decoding does not. Segments are decoded exactly once, without path
normalization. No filesystem path is derived from a route.

**हिन्दी:** Transfer-Encoding, Expect/100-continue, upgrades, WebSocket, keep-alive, HTTP/2, streaming, multipart, TLS termination और async handlers इस सर्वर के बाहर हैं। chunked पर 501 और Expect पर 417 मिलता है। reverse proxy पहले पूरा अनुरोध तैयार करे। एक जैसे दो Content-Length भी अमान्य हैं। HTTP/1.1 में एक ही Host चाहिए। गलत शीर्ष, प्रतिशत escape, UTF-8, control वर्ण और encoded पथ slash अस्वीकार हैं। query में `+` खाली जगह बनता और दोहरे मान रहते हैं। पथ खंड केवल एक बार decode होते हैं और उनसे filesystem पथ नहीं बनता।

HEAD preserves the response Content-Length without sending a body. 204/304 bodies
are suppressed. 405 includes Allow. Header names/values are validated before any
response bytes are sent; framing headers are transport-owned. Invalid handler results
become 500. Request errors and handler exceptions release the connection before the
next accept. Socket write failures are logged and closed; a response cannot be promised
after the connection deadline or a disconnect.

**हिन्दी:** HEAD में Content-Length रहता है लेकिन body नहीं भेजी जाती। 204 और 304 में body नहीं है। 405 में Allow है। उत्तर के bytes से पहले शीर्ष जाँचे जाते हैं। framing शीर्ष transport का काम हैं। अमान्य handler परिणाम 500 है। अगला संबंध लेने से पहले त्रुटि या अपवाद वाला संबंध छूटता है। लिखने की विफलता लॉग होती है और संबंध बंद होता है। समयसीमा या disconnect के बाद उत्तर की गारंटी नहीं है।

A slow Bhash handler can still block dispatch: the socket deadline does **not** preempt
user code. Keep handlers short and bound database work. Request logs record status
without request bodies, query strings, SQL or exception secrets. No CORS headers,
authentication, cookies, sessions or CSRF framework are added implicitly.

**हिन्दी:** धीमा handler दूसरे काम रोक सकता है। सॉकेट की समयसीमा उपयोगकर्ता कोड को बीच में नहीं रोकती। handler छोटे रखें और डेटाबेस काम की सीमा दें। लॉग में स्थिति आती है, अनुरोध body, query, SQL और निजी अपवाद विवरण नहीं। CORS, प्रमाणीकरण, cookies, सत्र और CSRF स्पष्ट रूप से जोड़ें।

## GC and cleanup

Native registries store socket descriptors and monotonic integer tokens only. There
are no Bhash pointers in worker threads or OS callbacks. Socket polling and I/O happen
on the mutator; byte buffers are copied into rooted Bhash values after reads. Resource
wrappers are allocated before opening sockets. Request/response graphs, nested JSON,
exceptions and SQLite objects remain under existing precise roots and unwinding.
Native stop handlers set only a signal-safe scalar; managed callbacks only set an
atomic flag. JVM shutdown waits at most 12 seconds for server resource scopes.
OS force-kill cannot run cleanup; SQLite transaction recovery remains responsible for
recovering an interrupted process. Windows Ctrl+C is supported; this is not a Windows
Service host. Service supervisors should allow at least 15 seconds for shutdown.

**हिन्दी:** नेटिव registries में केवल socket descriptors और बढ़ते पूर्णांक token हैं। worker या OS callback में भाष pointers नहीं हैं। mutator पर socket I/O होता है और bytes rooted भाष मानों में प्रतिलिपि होते हैं। socket खोलने से पहले संसाधन wrapper बनता है। अनुरोध, उत्तर, JSON, अपवाद और SQLite सामान्य सटीक roots व unwinding उपयोग करते हैं। नेटिव stop handler signal-safe मान और managed callback atomic flag तय करता है। JVM सफ़ाई के लिए अधिकतम १२ सेकंड देता है। बलपूर्वक बंद करने पर SQLite recovery पर भरोसा है। Windows Ctrl+C समर्थित है। supervisor को कम से कम १५ सेकंड दें।

The native runtime fingerprint includes `bhash_server.inc`. Only server programs link
Winsock on Windows; server-only programs do not require libcurl/WinHTTP. SQLite uses
the previously bundled, version-locked runtime. No new third-party server dependency.

**हिन्दी:** नेटिव runtime fingerprint में `bhash_server.inc` है। Windows server ऐप Winsock link करते हैं। केवल server वाले ऐप को libcurl या WinHTTP नहीं चाहिए। SQLite का pinned runtime वही है। कोई नई बाहरी server निर्भरता नहीं है।

## Develop, test, deploy

See `examples/web-tasks` and the Hindi tutorial. Route handlers can be tested directly
without listening. `JvmWebTest` and `ClrWebTest` drive real generated servers;
`NativeBackendTest#nativeWeb*` repeats the protocol and database lifecycle under forced
GC, including repeated requests, exception recovery, graceful stop and zero live objects.
The full platform matrix also lowers and builds all examples.

**हिन्दी:** `examples/web-tasks` और हिन्दी ट्यूटोरियल देखें। बिना पोर्ट खोले handler जाँच सकते हैं। `JvmWebTest` और `ClrWebTest` वास्तविक server चलाते हैं। `NativeBackendTest#nativeWeb*` forced GC में HTTP, डेटाबेस, अपवाद, व्यवस्थित रोक और शून्य जीवित वस्तुएँ जाँचता है। प्लेटफ़ॉर्म CI उदाहरण भी बनाता है।

Linux production layout: native executable plus `index.html` under `/opt/bhash-tasks`,
SQLite under `/var/lib/bhash-tasks`; systemd supervises the process and Caddy terminates
HTTPS and authenticates users. Templates are supplied, not deployed automatically.
Use the existing `native package` runtime compatibility checks and build on the oldest
supported libc. The container template expects a Linux executable with glibc <= 2.35;
copying a binary from a newer host does not make it portable. Cross-OS cross-compilation
and a built-in authentication/session framework remain future work.

**हिन्दी:** Linux पर native executable और `index.html` `/opt/bhash-tasks` में तथा SQLite `/var/lib/bhash-tasks` में रखें। systemd प्रक्रिया सँभालता है और Caddy HTTPS तथा पहचान जाँच करता है। templates को अनुकूलित करें। `native package` और सबसे पुराने समर्थित libc पर निर्माण से संगतता जाँचें। container template को glibc 2.35 या उससे कम माँगने वाला executable चाहिए। अलग OS के लिए उसी लक्ष्य पर बनाएँ।

Authentication, secure cookies, authorization and CSRF are described in the [authentication contract](authentication.md); the [authenticated notes example](../examples/auth-sessions/README.md) builds on this server and router.

**हिन्दी:** [प्रमाणीकरण अनुबंध](authentication.md) सुरक्षित cookies, अनुमति और CSRF समझाता है। [प्रमाणित नोट्स उदाहरण](../examples/auth-sessions/README.md) इसी server और router पर बना है।
