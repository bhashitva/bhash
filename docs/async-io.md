# Asynchronous HTTP and database adapters

This milestone implements desktop adapters on top of the [task-core contract](async-await-design.md). A task does not turn a synchronous call into asynchronous I/O automatically: use the explicit APIs below. The dispatcher remains the sole executor of Bhash application code.

**हिन्दी:** ये desktop adapters [कार्यफल के नियमों](async-await-design.md) पर बने हैं। तुल्यकालिक कॉल अपने आप असमकालिक नहीं होती। नीचे दी गई स्पष्ट API चुनें। भाष के ऐप कोड को केवल dispatcher चलाता है।

## HTTP

`एचटीटीपी.पाओअसमकालिक(url[, token])` and `एचटीटीपी.भेजोअसमकालिक(request[, token])` return `कार्यफल<एचटीटीपीउत्तर>`. The ordinary factory snapshots request headers/body and starts the deadline at invocation, before queued execution. Await returns the existing buffered response type. HTTP error status codes remain responses; transport/validation errors fail the task.

**हिन्दी:** `एचटीटीपी.पाओअसमकालिक(url[, token])` और `एचटीटीपी.भेजोअसमकालिक(request[, token])` से `कार्यफल<एचटीटीपीउत्तर>` मिलता है। कॉल पर अनुरोध के शीर्ष और सामग्री की प्रति तथा समयसीमा तय होती है, कतार में चलने से पहले। `प्रतीक्षा` सामान्य buffered उत्तर देती है। HTTP त्रुटि स्थिति भी उत्तर है। transport या सत्यापन त्रुटि कार्यफल विफल करती है।

The existing desktop policy remains in shared Bhash code: TLS verification, manual redirect limits, no HTTPS downgrade, no cross-origin body replay, cross-origin credential stripping, duplicate response headers, strict UTF-8 and an 8 MiB response-body limit. Cancellation aborts the transport and drains its registration before releasing the handle. Cancellation does not undo a request already received by a server.

**हिन्दी:** Desktop HTTP में TLS जाँच, redirect की सीमा, HTTPS downgrade और दूसरे origin को body दोबारा भेजने पर रोक, दूसरे origin के लिए credentials हटाना, दोहरे उत्तर शीर्ष, सख्त UTF-8 और 8 MiB उत्तर सीमा लागू हैं। निरस्तीकरण transport रोककर handle छोड़ने से पहले पंजीकरण की सफ़ाई पूरी करता है। सर्वर को पहले पहुँचा अनुरोध वापस नहीं होता।

JVM uses `HttpClient.sendAsync`, CLR uses asynchronous `HttpClient` operations, POSIX native uses libcurl multi polling, and Windows native uses WinHTTP callbacks. Native callbacks own only foreign buffers and atomics; they never retain Bhash heap pointers or invoke application code. Dispatcher-side conversion creates the managed response after completion.

**हिन्दी:** JVM `HttpClient.sendAsync`, CLR असमकालिक `HttpClient`, POSIX native libcurl multi polling और Windows native WinHTTP callbacks उपयोग करते हैं। नेटिव callbacks में केवल बाहरी buffers और atomics हैं। वे भाष heap pointers नहीं रखते और ऐप कोड नहीं चलाते। पूरा होने पर dispatcher भाष उत्तर बनाता है।

At most 32 HTTP registrations are admitted, subject to a separate 64 MiB logical payload reservation budget. Each request reserves 8 MiB for its response plus twice its upload length before copying. This is an admission budget, not an RSS ceiling: TLS/provider buffers and managed representation overhead are additional. Request metadata has the existing separate limits.

**हिन्दी:** एक साथ अधिकतम ३२ HTTP पंजीकरण और सामग्री के लिए अलग 64 MiB आरक्षण है। प्रति अनुरोध उत्तर के लिए 8 MiB और upload के आकार का दुगुना स्थान प्रति लेने से पहले आरक्षित होता है। वास्तविक RSS में TLS और प्रदाता buffers तथा managed overhead अलग जुड़ते हैं। metadata की अपनी सीमाएँ हैं।

## Database resources

Import `मानक.असमडेटा`, `असमडेटासंबंध`, `असमडेटाकथन`, and `असमडेटालेनदेन`. Open using `प्रतीक्षा असमडेटा.एसक्यूलाइट(path[, token])` or `प्रतीक्षा असमडेटा.पोस्टग्रेस(config[, token])`. Use `असमकालिक संसाधन` for every connection, statement and transaction. Synchronous resources cannot be mixed with asynchronous handles.

**हिन्दी:** `मानक.असमडेटा`, `असमडेटासंबंध`, `असमडेटाकथन` और `असमडेटालेनदेन` आयात करें। `प्रतीक्षा असमडेटा.एसक्यूलाइट(path[, token])` या `प्रतीक्षा असमडेटा.पोस्टग्रेस(config[, token])` से खोलें। हर संबंध, कथन और लेनदेन के लिए `असमकालिक संसाधन` दें। तुल्यकालिक और असमकालिक handles अलग रखें।

- `प्रतीक्षा connection.तैयार(sql[, token])` prepares one parameterized statement.

  **हिन्दी:** `प्रतीक्षा connection.तैयार(sql[, token])` एक मापदंडयुक्त कथन तैयार करता है।
- Existing typed binding names are retained: `पूर्णांकरखो`, `दशमलवरखो`, `तार्किकरखो`, `पाठरखो`, `बाइटरखो`, `रिक्तरखो`. Bindings use one-based indices. Byte buffers are copied.

  **हिन्दी:** ऊपर दिए प्रकारयुक्त बंधन नाम वही हैं। मापदंड की गिनती १ से शुरू होती है। byte buffer की प्रति बनती है।
- `प्रतीक्षा statement.चलाओ([token])` executes a non-row statement and returns the affected-row count.

  **हिन्दी:** `प्रतीक्षा statement.चलाओ([token])` बिना पंक्ति लौटाने वाला कथन चलाकर प्रभावित पंक्तियों की गिनती देता है।
- `प्रतीक्षा statement.अगला([token])` reads a bounded result on the first call, then advances buffered rows. Column indices are zero-based. `स्तंभसंख्या`/`स्तंभनाम` describe the loaded result, so inspect them after the first await, including an empty result.

  **हिन्दी:** `प्रतीक्षा statement.अगला([token])` पहली बार सीमित परिणाम पढ़ता है और बाद में buffered पंक्तियाँ आगे बढ़ाता है। स्तंभ ० से गिने जाते हैं। पहली प्रतीक्षा के बाद `स्तंभसंख्या` और `स्तंभनाम` पढ़ें, खाली परिणाम में भी।
- Row getters are synchronous and never perform I/O: `प्रकार`, `रिक्तहै`, `पूर्णांकलो`, `दशमलवलो`, `तार्किकलो`, `पाठलो`, `बाइटलो`. Returned buffers are copies.

  **हिन्दी:** ऊपर दिए पंक्ति getters तुल्यकालिक हैं और I/O नहीं करते। लौटाए byte buffers प्रतियाँ हैं।
- `प्रतीक्षा statement.रीसेट()` releases buffered rows and permits another execution; bindings remain until `बंधनमिटाओ()`.

  **हिन्दी:** `प्रतीक्षा statement.रीसेट()` पंक्तियाँ छोड़कर पुनः निष्पादन की अनुमति देता है। `बंधनमिटाओ()` तक मापदंड रहते हैं।
- `प्रतीक्षा connection.लेनदेन([token])` creates a scope-owned transaction. Await `सहेजो()` to commit or `वापसलो()` to roll back. Closing an uncommitted transaction rolls back. Migrations remain synchronous in this milestone.

  **हिन्दी:** `प्रतीक्षा connection.लेनदेन([token])` दायरे का लेनदेन बनाता है। पुष्टि के लिए `सहेजो()` और वापस लेने के लिए `वापसलो()` की प्रतीक्षा करें। पुष्टि से पहले बंद करने पर rollback होता है। माइग्रेशन तुल्यकालिक हैं।

There is one in-flight provider operation per connection. Overlap on the same connection fails clearly; use separate connections for independent operations. A transaction belongs to the initiating task scope and its descendants. Provider errors mark a transaction rollback-only. Do not retry individual statements in a failed transaction.

**हिन्दी:** प्रति संबंध एक समय में केवल एक प्रदाता संक्रिया चलती है। स्वतंत्र संक्रियाओं के लिए अलग संबंध लें। लेनदेन उसी कार्य दायरे और उसके वंशजों का है। प्रदाता त्रुटि के बाद लेनदेन केवल rollback हो सकता है। उसी में किसी कथन को फिर न चलाएँ।

Cancellation before commit dispatch prevents dispatch. After dispatch, commit acknowledgement is deliberately not interrupted by cancellation. Loss of a reliable acknowledgement raises `लेनदेनपरिणामअज्ञातअपवाद`; **do not automatically retry**. Reconcile against application-level idempotency keys or durable state using a new connection. Cancellation of a dispatched ordinary operation conservatively discards that connection after provider work/control has drained.

**हिन्दी:** commit भेजने से पहले निरस्तीकरण उसे रोकता है। भेजने के बाद पुष्टि के इंतज़ार में निरस्तीकरण दखल नहीं देता। भरोसेमंद पुष्टि खोने पर `लेनदेनपरिणामअज्ञातअपवाद` मिलता है। अपने आप पुनः प्रयास न करें। नए संबंध से स्थायी अवस्था या idempotency key जाँचें। भेजी गई सामान्य संक्रिया निरस्त हो तो provider की सफ़ाई पूरी होने के बाद संबंध छोड़ दिया जाता है।

SQLite runs on bounded workers. JVM PostgreSQL uses JDBC workers and reserved cancellation workers. CLR/native PostgreSQL use nonblocking libpq; DNS initialization and cancellation use bounded control/work threads. No worker executes Bhash callbacks. Native roots live in task frames, while foreign registrations contain copied parameters, opaque provider handles, and detached result buffers.

**हिन्दी:** SQLite सीमित workers पर चलता है। JVM PostgreSQL JDBC और अलग निरस्तीकरण workers उपयोग करता है। CLR/native में nonblocking libpq है तथा DNS और निरस्तीकरण सीमित नियंत्रण workers पर हैं। worker भाष callbacks नहीं चलाता। नेटिव GC roots कार्य frames में और बाहरी पंजीकरण में प्रतिलिपि किए मापदंड, handle तथा परिणाम buffers हैं।

Limits: 32 connections, 128 statements, 64 operation registrations, 999 parameters, 256 columns, 1,024 buffered rows and 8 MiB of logical result payload per query. Database operations share a 64 MiB payload reservation budget, separate from HTTP. Up to four blocking data workers and two cleanup/control workers are used. Provider internal buffers are not an RSS guarantee. Use bounded SQL/pagination for larger results; streaming cursors are not exposed yet.

**हिन्दी:** सीमाएँ ३२ संबंध, १२८ कथन, ६४ चालू पंजीकरण, ९९९ मापदंड, २५६ स्तंभ, १,०२४ buffered पंक्तियाँ और प्रति query 8 MiB परिणाम हैं। डेटाबेस सामग्री के लिए HTTP से अलग साझा 64 MiB आरक्षण है। अधिकतम चार blocking data और दो नियंत्रण workers हैं। अंदरूनी provider buffers वास्तविक RSS बढ़ा सकते हैं। बड़े परिणाम के लिए pagination करें।

## Distribution and browser restrictions

SQLite distribution is unchanged: JVM embeds the verified JDBC driver/native resources, CLR packages the verified SQLite libraries, and native links the pinned amalgamation. PostgreSQL JVM output includes the pinned JDBC driver; CLR/native require the libpq 14+ client runtime and its transitive dependencies, not development headers. Native runtime ABI is now **17**; rebuild applications and compiler/runtime pairs together.

**हिन्दी:** JVM में जाँचा SQLite JDBC driver और native संसाधन जुड़ते हैं, CLR में जाँची libraries और native में pinned स्रोत link होता है। PostgreSQL में JVM का driver साथ है। CLR/native में libpq 14+ runtime और उसकी निर्भरताएँ चाहिए। Native ABI 17 के लिए ऐप तथा compiler/runtime जोड़ी साथ दोबारा बनाएँ।

Desktop HTTP/database adapters remain unavailable in browser/WASM. The separately gated Fetch profile described in the design is a subsequent browser-specific adapter; this change does not grant playground network egress or embed database credentials in browser output. Existing browser task hosting, cancellation, and denied-capability behavior must continue to compile and pass.

**हिन्दी:** ये desktop HTTP/डेटाबेस adapters ब्राउज़र में उपलब्ध नहीं हैं। अलग अनुमति वाला Fetch profile आगे का विषय है। Playground को बाहर नेटवर्क की पहुँच नहीं मिलती और database credentials ब्राउज़र में नहीं जाते। ब्राउज़र कार्यफल, निरस्तीकरण और अस्वीकृत क्षमताओं की जाँच जारी है।

## Validation

The test suite covers request snapshots, overlapping HTTP requests, cancellation and reuse, parameterized SQLite/PostgreSQL operations, transactions, typed rows, bounded results, pre-dispatch cancellation, in-flight cancellation, and zero remaining provider registrations. Native versions run under forced GC. PostgreSQL cases join the existing dedicated TLS integration fixture and runtime packaging workflow. See the [Hindi walkthrough](../tutorial/async-io/README.md).

**हिन्दी:** परीक्षण HTTP प्रति, साथ चलने वाले अनुरोध, निरस्तीकरण, फिर उपयोग, SQLite/PostgreSQL मापदंड, लेनदेन, प्रकारयुक्त और सीमित परिणाम, भेजने से पहले/बाद का निरस्तीकरण और शून्य बचे पंजीकरण देखते हैं। नेटिव में forced GC है। PostgreSQL TLS fixture और packaging workflow का उपयोग करता है। [हिन्दी अभ्यास](../tutorial/async-io/README.md) देखें।
