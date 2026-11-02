# HTTP server and small web framework

The framework is implemented in Bhash (`stdlib/web.bhash`). JVM, CLR and native
provide only a small synchronous IPv4 socket transport. All parsing, URL decoding,
routing, response validation, JSON policy and exception boundaries are shared.

## Public surface

- `वेबचालक.संभालो(वेबअनुरोध) -> वेबउत्तर`: an interface implemented by ordinary Bhash classes.
- `वेबअनुप्रयोग.जोड़ो(method, path, handler)`: up to 128 ordered routes. `:name` captures
  one nonempty segment. The first matching route/method wins; HEAD can use GET.
- `वेबअनुरोध`: method, raw origin-form target, raw path, ordered header pairs, byte body,
  decoded `पथमान`; `प्रश्नमान` and `शीर्षमान` retain duplicate values. `पाठ्यांश` decodes
  strict UTF-8; `जेसनलो` requires application/json and maps parsing failures to 400.
- `वेबउत्तर(status, bytes)` and `शीर्षरखो`: validated response headers. `वेब.पाठउत्तर`,
  `.एचटीएमएल`, `.जेसन` produce UTF-8 responses; `.त्रुटि` produces a generic JSON error.
- `वेबत्रुटि(status, message)`: expected application failure. The HTTP body exposes only
  a generic message and status; arbitrary internal exception messages are never sent.
- `एचटीटीपीसर्वर(host, port)` implements `बंदनीय`. `पोर्ट()` reports the bound port.
  `चलाओ(handler)` runs until stopped. `अगला(handler)` serves at most one accepted
  connection, returning false on an idle poll. `रोको()` requests cooperative shutdown;
  `रुकाहै()` also observes OS stop signals; `बंदकरो()` is idempotent.
- Helpers `वेब.विकूटित(text, formPlus)`, `.अंक`, `.संख्या`, `.मानलो` implement explicit
  percent decoding, ASCII protocol numbers and ordered pair lookup.

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

HEAD preserves the response Content-Length without sending a body. 204/304 bodies
are suppressed. 405 includes Allow. Header names/values are validated before any
response bytes are sent; framing headers are transport-owned. Invalid handler results
become 500. Request errors and handler exceptions release the connection before the
next accept. Socket write failures are logged and closed; a response cannot be promised
after the connection deadline or a disconnect.

A slow Bhash handler can still block dispatch: the socket deadline does **not** preempt
user code. Keep handlers short and bound database work. Request logs record status
without request bodies, query strings, SQL or exception secrets. No CORS headers,
authentication, cookies, sessions or CSRF framework are added implicitly.

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

The native runtime fingerprint includes `bhash_server.inc`. Only server programs link
Winsock on Windows; server-only programs do not require libcurl/WinHTTP. SQLite uses
the previously bundled, version-locked runtime. No new third-party server dependency.

## Develop, test, deploy

See `examples/web-tasks` and the Hindi tutorial. Route handlers can be tested directly
without listening. `JvmWebTest` and `ClrWebTest` drive real generated servers;
`NativeBackendTest#nativeWeb*` repeats the protocol and database lifecycle under forced
GC, including repeated requests, exception recovery, graceful stop and zero live objects.
The full platform matrix also lowers and builds all examples.

Linux production layout: native executable plus `index.html` under `/opt/bhash-tasks`,
SQLite under `/var/lib/bhash-tasks`; systemd supervises the process and Caddy terminates
HTTPS and authenticates users. Templates are supplied, not deployed automatically.
Use the existing `native package` runtime compatibility checks and build on the oldest
supported libc. The container template expects a Linux executable with glibc <= 2.35;
copying a binary from a newer host does not make it portable. Cross-OS cross-compilation
and a built-in authentication/session framework remain future work.

Authentication, secure cookies, authorization and CSRF are described in the [authentication contract](authentication.md); the [authenticated notes example](../examples/auth-sessions/README.md) builds on this server and router.
