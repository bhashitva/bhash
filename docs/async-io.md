# Asynchronous HTTP and database adapters

This milestone implements desktop adapters on top of the [task-core contract](async-await-design.md). A task does not turn a synchronous call into asynchronous I/O automatically: use the explicit APIs below. The dispatcher remains the sole executor of Bhash application code.

## HTTP

`एचटीटीपी.पाओअसमकालिक(url[, token])` and `एचटीटीपी.भेजोअसमकालिक(request[, token])` return `कार्यफल<एचटीटीपीउत्तर>`. The ordinary factory snapshots request headers/body and starts the deadline at invocation, before queued execution. Await returns the existing buffered response type. HTTP error status codes remain responses; transport/validation errors fail the task.

The existing desktop policy remains in shared Bhash code: TLS verification, manual redirect limits, no HTTPS downgrade, no cross-origin body replay, cross-origin credential stripping, duplicate response headers, strict UTF-8 and an 8 MiB response-body limit. Cancellation aborts the transport and drains its registration before releasing the handle. Cancellation does not undo a request already received by a server.

JVM uses `HttpClient.sendAsync`, CLR uses asynchronous `HttpClient` operations, POSIX native uses libcurl multi polling, and Windows native uses WinHTTP callbacks. Native callbacks own only foreign buffers and atomics; they never retain Bhash heap pointers or invoke application code. Dispatcher-side conversion creates the managed response after completion.

At most 32 HTTP registrations are admitted, subject to a separate 64 MiB logical payload reservation budget. Each request reserves 8 MiB for its response plus twice its upload length before copying. This is an admission budget, not an RSS ceiling: TLS/provider buffers and managed representation overhead are additional. Request metadata has the existing separate limits.

## Database resources

Import `मानक.असमडेटा`, `असमडेटासंबंध`, `असमडेटाकथन`, and `असमडेटालेनदेन`. Open using `प्रतीक्षा असमडेटा.एसक्यूलाइट(path[, token])` or `प्रतीक्षा असमडेटा.पोस्टग्रेस(config[, token])`. Use `असमकालिक संसाधन` for every connection, statement and transaction. Synchronous resources cannot be mixed with asynchronous handles.

- `प्रतीक्षा connection.तैयार(sql[, token])` prepares one parameterized statement.
- Existing typed binding names are retained: `पूर्णांकरखो`, `दशमलवरखो`, `तार्किकरखो`, `पाठरखो`, `बाइटरखो`, `रिक्तरखो`. Bindings use one-based indices. Byte buffers are copied.
- `प्रतीक्षा statement.चलाओ([token])` executes a non-row statement and returns the affected-row count.
- `प्रतीक्षा statement.अगला([token])` reads a bounded result on the first call, then advances buffered rows. Column indices are zero-based. `स्तंभसंख्या`/`स्तंभनाम` describe the loaded result, so inspect them after the first await, including an empty result.
- Row getters are synchronous and never perform I/O: `प्रकार`, `रिक्तहै`, `पूर्णांकलो`, `दशमलवलो`, `तार्किकलो`, `पाठलो`, `बाइटलो`. Returned buffers are copies.
- `प्रतीक्षा statement.रीसेट()` releases buffered rows and permits another execution; bindings remain until `बंधनमिटाओ()`.
- `प्रतीक्षा connection.लेनदेन([token])` creates a scope-owned transaction. Await `सहेजो()` to commit or `वापसलो()` to roll back. Closing an uncommitted transaction rolls back. Migrations remain synchronous in this milestone.

There is one in-flight provider operation per connection. Overlap on the same connection fails clearly; use separate connections for independent operations. A transaction belongs to the initiating task scope and its descendants. Provider errors mark a transaction rollback-only. Do not retry individual statements in a failed transaction.

Cancellation before commit dispatch prevents dispatch. After dispatch, commit acknowledgement is deliberately not interrupted by cancellation. Loss of a reliable acknowledgement raises `लेनदेनपरिणामअज्ञातअपवाद`; **do not automatically retry**. Reconcile against application-level idempotency keys or durable state using a new connection. Cancellation of a dispatched ordinary operation conservatively discards that connection after provider work/control has drained.

SQLite runs on bounded workers. JVM PostgreSQL uses JDBC workers and reserved cancellation workers. CLR/native PostgreSQL use nonblocking libpq; DNS initialization and cancellation use bounded control/work threads. No worker executes Bhash callbacks. Native roots live in task frames, while foreign registrations contain copied parameters, opaque provider handles, and detached result buffers.

Limits: 32 connections, 128 statements, 64 operation registrations, 999 parameters, 256 columns, 1,024 buffered rows and 8 MiB of logical result payload per query. Database operations share a 64 MiB payload reservation budget, separate from HTTP. Up to four blocking data workers and two cleanup/control workers are used. Provider internal buffers are not an RSS guarantee. Use bounded SQL/pagination for larger results; streaming cursors are not exposed yet.

## Distribution and browser restrictions

SQLite distribution is unchanged: JVM embeds the verified JDBC driver/native resources, CLR packages the verified SQLite libraries, and native links the pinned amalgamation. PostgreSQL JVM output includes the pinned JDBC driver; CLR/native require the libpq 14+ client runtime and its transitive dependencies, not development headers. Native runtime ABI is now **17**; rebuild applications and compiler/runtime pairs together.

Desktop HTTP/database adapters remain unavailable in browser/WASM. The separately gated Fetch profile described in the design is a subsequent browser-specific adapter; this change does not grant playground network egress or embed database credentials in browser output. Existing browser task hosting, cancellation, and denied-capability behavior must continue to compile and pass.

## Validation

The test suite covers request snapshots, overlapping HTTP requests, cancellation and reuse, parameterized SQLite/PostgreSQL operations, transactions, typed rows, bounded results, pre-dispatch cancellation, in-flight cancellation, and zero remaining provider registrations. Native versions run under forced GC. PostgreSQL cases join the existing dedicated TLS integration fixture and runtime packaging workflow. See the [Hindi walkthrough](../tutorial/async-io/README.md).
