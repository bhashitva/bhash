# HTTP and HTTPS client

`मानक.एचटीटीपी` is a synchronous, buffered HTTP/1.1 client for JVM, CLR and native
Linux/Windows/macOS applications. Request/response policy is bundled Bhash source;
one-hop transports use JDK HttpClient, .NET HttpClient, WinHTTP (Windows native),
or dynamically loaded system libcurl (Linux/macOS native).

```bhash
आयात मानक.एचटीटीपी;
आयात मानक.एचटीटीपीअनुरोध;
कार्य मुख्य() -> शून्य {
    स्थिर अ: एचटीटीपीअनुरोध = नया एचटीटीपीअनुरोध("POST", "https://example.com/api");
    अ.पाठरखो("{\"नाम\":\"भारत😀\"}");
    अ.शीर्षरखो("Content-Type", "application/json; charset=utf-8");
    अ.समयसीमा = ५०००;
    स्थिर उ: मानक.एचटीटीपीउत्तर = एचटीटीपी.भेजो(अ);
    लिखो(उ.स्थिति(), उ.पाठ्यांश());
}
```

The example endpoint is illustrative; use an endpoint that accepts your request.
Hindi reference examples are in [the tutorial](../tutorial/networking/README.md).

## API

| API | Behavior |
| --- | --- |
| `नया एचटीटीपीअनुरोध(method, url)` | Mutable request; empty body/headers; 10,000 ms timeout and five redirects |
| `request.विधि`, `.पता` | Method and absolute HTTP/HTTPS URI |
| `request.शीर्ष` | Alternating header-name/value strings; prefer the methods below |
| `request.सामग्री` | Request byte buffer |
| `request.समयसीमा` | Total monotonic deadline across all redirect hops, 1–120,000 ms |
| `request.अनुसरणसीमा` | 0 disables redirects; otherwise 1–10 hops |
| `request.शीर्षजोड़ो(name, value)` | Append a field, retaining duplicates |
| `request.शीर्षरखो(name, value)` | Replace all fields with that case-insensitive name |
| `request.पाठरखो(text)` | Strict UTF-8 encode; set `Content-Type: text/plain; charset=utf-8` |
| `request.बाइटरखो(buffer)` | Set binary body; preserve explicit content type |
| `एचटीटीपी.भेजो(request)` | Execute and return a fully buffered response |
| `एचटीटीपी.पाओ(url)` | GET convenience method |
| `एचटीटीपी.कूटित(text)` | Percent-encode one UTF-8 URI component; spaces become `%20`, not `+` |
| `response.स्थिति()` | Numeric HTTP status; 4xx/5xx are normal responses |
| `response.अंतिमपता()` | Final canonical URI after redirects |
| `response.शीर्षमान(name)` | New list of values for that case-insensitive field; absent means empty list |
| `response.सामग्री()` | Owned response byte buffer; the buffer is mutable |
| `response.पाठ्यांश()` | Strict UTF-8 decode, independent of any declared response charset |

`एचटीटीपीउत्तर` also has a public constructor `(status, url, alternatingHeaders,
body)` for application test doubles. Its fields are accessed through the methods
above. Reading the body/header values never reopens a socket. No explicit response
close is needed: temporary transport handles are closed before `भेजो` returns,
including failures while copying data into Bhash heap objects.

`मानक.जालतंत्र`, `जालनियम` and `जालउत्तरप्रवाह` are compiler/runtime implementation
surfaces, not stable application APIs.

## Portable behavior and limits

* Methods: GET, HEAD, POST, PUT, PATCH, DELETE, OPTIONS; case normalized to uppercase.
  HEAD requires an empty body.
* URIs are ASCII HTTP/HTTPS URIs, up to 8,192 bytes. User information in an authority
  and raw spaces, controls, backslashes and non-ASCII URI characters are rejected.
  Use `कूटित` for individual path/query components, or an ASCII IDNA hostname.
  IPv6 literals use brackets. Fragments are omitted; literal dot segments normalize.
* Request field names are HTTP tokens, at most 256 characters. Values permit
  printable ASCII and horizontal tabs, at most 8,192 characters. CR/LF injection is
  rejected. Up to 64 caller-supplied pairs and 64 KiB total headers are allowed.
* Host, Content-Length, Transfer-Encoding, Connection, Expect, Upgrade, TE, Trailer,
  Proxy-Connection and Proxy-Authorization belong to the transport and cannot be set.
* Default `Accept: */*` and `User-Agent: Bhash/1` are added if absent.
* Bodies are bounded to 8 MiB per request and per response, including error and
  redirect responses. Responses permit at most 256 header pairs / 64 KiB parsed
  fields; underlying transports may reject malformed HTTP earlier.
* Response values retain duplicate fields. Global header ordering is unspecified.
  Trailers are not exposed. No automatic decompression is performed; body bytes are
  the representation supplied by the server after HTTP transfer framing is removed.
* The Bhash layer does not retry requests. Underlying HTTP stacks may perform
  connection recovery; this is not an exactly-once delivery guarantee. No cookie
  jar, default OS credentials, proxy discovery or environment-proxy inheritance
  is enabled. Explicit Authorization/Cookie headers
  are supported. Proxy configuration, streaming downloads, cancellation tokens,
  connection-pool configuration, HTTP/2 and asynchronous APIs are future work.

Timeouts bound the complete network transfer, including response-body reception,
and share the remaining deadline across redirects. Scheduling, runtime setup and
OS cleanup are not hard real-time operations. A timeout does not establish whether
the server already processed a non-idempotent request; callers decide if a retry
is appropriate.

## Redirect rules

301/302 change POST to GET. 303 changes methods other than HEAD to GET. These
changes clear the body and content-related headers. 307/308 preserve method/body.
Relative, absolute-path, query-only and network-path Location references resolve
against the preceding URI.

HTTPS never downgrades to HTTP. Cross-origin redirects are followed only after
the method becomes GET/HEAD with an empty body, with all caller-supplied headers
dropped. Requests carrying a body, including GET bodies, are never replayed to
another origin. An origin includes scheme, normalized
host and effective port. Reaching the hop limit throws; setting the limit to zero
returns the original redirect response.

## TLS and runtime requirements

TLS 1.2+ uses platform certificate trust, chain validation, validity dates and
hostname verification. There is no API to disable certificate checks. JVM uses
its standard Java truststore configuration; CLR and WinHTTP use platform trust.
Native libcurl additionally honors `SSL_CERT_FILE` for an explicit CA file while
continuing to verify the certificate chain and hostname.

Windows native uses the OS-supplied WinHTTP DLL. macOS native loads
`/usr/lib/libcurl.4.dylib`. Linux native needs the `libcurl.so.4` runtime, version
7.68+ with TLS and asynchronous DNS (for bounded DNS timeouts). This is an ordinary
runtime package (`libcurl4`/`libcurl4t64` on Ubuntu), not a development package.
No curl headers, C development environment, JVM or CLR is needed to run a native
HTTP executable. Programs without networking do not link this transport.

Build metadata identifies the network capability/runtime; native release manifests
list dynamically loaded libraries and their README describes the requirement.
Runtime fingerprints include the optional HTTP implementation as well as the base
runtime. A missing/incompatible libcurl produces a catchable failure on first use.

## Errors and browser restrictions

HTTP status codes do not throw. Transport/validation errors are catchable `अपवाद`:

| Message | Meaning |
| --- | --- |
| `अमान्य HTTP पता।` / `अमान्य HTTP विधि।` / `अमान्य HTTP शीर्ष।` | Portable request validation |
| `HTTP अनुरोध का समय समाप्त।` | Total transfer deadline expired |
| `TLS संबंध स्थापित नहीं हो सका।` | Certificate, hostname, validity or TLS handshake failure |
| `HTTP अनुरोध असफल।` | Other connection/protocol/transport failure |
| `HTTP उत्तर की सीमा ८३८८६०८ बाइट है।` | Response body limit |
| `HTTP शीर्षों की सीमा पार हो गई।` | Header limit |
| `HTTP पुनर्निर्देशन सीमा पार हो गई।` | Redirect limit |
| `HTTPS से HTTP पुनर्निर्देशन अस्वीकार है।` | Insecure downgrade |
| `अन्य उद्गम पर HTTP सामग्री पुनः नहीं भेज सकते।` | Cross-origin body replay |

Browser WebAssembly supports request/response data and pure URI helpers, but
`भेजो`/`पाओ` throw `ब्राउज़र लक्ष्य पर तुल्यकालिक HTTP उपलब्ध नहीं है।` without
attempting fetch. The current runner cannot synchronously wait for browser fetch;
networking there needs a later asynchronous API and an explicit host/CORS policy.
This milestone neither deploys the playground nor changes its network isolation.

## Validation

Shared, loopback-only fixtures cover Unicode, binary bytes, duplicated headers,
status errors, all five redirect statuses, cross-origin credential stripping,
cookie/proxy isolation, header/body/redirect limits, slow headers/body/chains,
connection failure and client reuse after failures. TLS fixtures cover trusted,
untrusted, wrong-host and expired certificates, plus HTTPS downgrade rejection.
JVM tests use a private fixture truststore. The Windows native batch trusts the
fixture CA only in its disposable, offline Server Core container. No test changes
a developer's certificate store. Native programs force collection, verify zero
live heap objects and repeat requests beyond the opaque-handle registry size.

Run the shared managed transport fixtures with
`mvn -Dtest=JvmNetworkTest,ClrNetworkTest test`; native coverage is included in
`NativeBackendTest`. These tests require permission to bind loopback sockets.
The offline `NetworkCoreTest` suite also checks policy and native IR lowering
without opening a socket.
