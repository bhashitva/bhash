# Authentication and sessions

The desktop JVM, CLR and native targets share the policy implementation in
`authentication.bhash`. Only the password KDF, secure random source, constant-time
comparison and monotonic clock are platform bindings. Server authentication is
explicitly unavailable in browser/WASM programs. It does not use `यादृच्छिक`.

## Password contract

`मानक.कूटशब्द.हैशबनाओ(password)` generates a 16-byte cryptographic salt and a
32-byte PBKDF2-HMAC-SHA-256 output, with 600,000 iterations. The stored form is:

```text
$bhash$pbkdf2-sha256$600000$<32 lowercase hex salt characters>$<64 lowercase hex key characters>
```

`जाँचो(password, encoded)` verifies this exact encoding with a constant-time
derived-key comparison. Malformed, unknown, uppercase-hex, truncated or oversized
encodings return false before invoking the KDF. The work factor is deliberately
fixed and bounded; a database value cannot demand arbitrarily expensive work.
Retain the complete encoded string, including its algorithm and work factor, so
future versions can support migrations. Never store or log a plaintext password.

Passwords are **exact UTF-8**, without trimming, case folding, Unicode
normalization or NUL termination. Embedded NUL and passwords exceeding SHA-256's
block size are supported. The library accepts 1–1024 UTF-8 bytes and rejects
invalid lengths with `कूटशब्द १ से १०२४ UTF-8 बाइट होना चाहिए।`. Applications set
their own password-quality policy; the example requires at least 12 code points
when provisioning an account. Native, JVM and CLR reference vectors include Hindi,
emoji, NUL and long passwords.

Providers:

| Target | KDF / random source | Additional runtime distribution |
| --- | --- | --- |
| JVM | JCA `PBKDF2WithHmacSHA256` / `SecureRandom` | JDK/JRE standard provider |
| CLR | `Rfc2898DeriveBytes.Pbkdf2` / `RandomNumberGenerator` | .NET runtime |
| Native Windows x64 | Windows CNG / `BCryptGenRandom` | System `bcrypt.dll` |
| Native macOS ARM64 | CommonCrypto / `CCRandomGenerateBytes` | System libraries |
| Native Linux x64 | OpenSSL 3 PBKDF2 / Linux `getrandom` | **`libcrypto.so.3` runtime**, e.g. Ubuntu 22.04 `libssl3`, Ubuntu 24.04 `libssl3t64`; development packages are unnecessary |

Native Linux loads the fixed OpenSSL ABI on first password operation, without
development headers or a build-time OpenSSL dependency. Native distribution
metadata records `authentication` and declares the runtime in `dynamic_libraries`.
Non-authentication programs have no new provider requirement. Native executables
remain independent of JVM and .NET. Provider failures fail closed with
`सुरक्षा प्रदाता उपलब्ध नहीं है।`; there is no weak random/hash fallback.
Sensitive temporary provider buffers are cleared where accessible; immutable
Bhash/JVM/CLR strings and copies made by crypto providers cannot be guaranteed
to be erased. Do not log request bodies, passwords, cookie IDs or CSRF tokens.

**Choice of algorithm:** OWASP generally prefers Argon2id. PBKDF2 is this initial
interoperable baseline because maintained platform providers cover every supported
desktop target without shipping another native library. This is not a claim of
FIPS validation. Argon2id remains an appropriate future algorithm addition, using
an independently reviewed implementation rather than custom cryptography.

## Session contract

`new सत्रप्रबंधक("https://app.example")` creates a process-local memory store:

- 1,024 entries; idle expiry 30 minutes; absolute expiry 8 hours.
- A separate 32-byte CSPRNG session ID and 32-byte CSRF secret, encoded as
  lowercase hex. IDs are accepted only from the session cookie, never a URL.
- `__Host-bhash-session=<id>; Path=/; Secure; HttpOnly; SameSite=Lax`.
  No Domain attribute; the cookie is session-only rather than persistent.
- Lookup rejects expired entries at the exact deadline. Touching a session resets
  only its idle timer. The monotonic clock cannot move backwards.
- `बदलो` removes the old entry and creates fresh session and CSRF secrets after
  successful authentication or a privilege change. It does not adopt an incoming
  unknown ID. Failure while creating the replacement leaves the old ID revoked.
- `हटाओ`, `उपयोगकर्ताहटाओ`, `समाप्त` and `बंदकरो` revoke access immediately.
  Creating/cookie-setting with an object that is not the exact stored session is
  rejected. The session defensively copies role strings from the input list.
- `साफकरो` reclaims expired entries. A full live store returns HTTP 503 rather
  than evicting an authenticated user's session. Closing is idempotent and clears
  all references. Native roots trace stored sessions, roles, IDs and CSRF strings.

The configurable constructor takes `(origin, capacity, idleMilliseconds,
absoluteMilliseconds, clock)`. Capacity is 1–10,000, idle time is positive,
absolute time is at least idle time and at most 7 days. A custom `सत्रघड़ी` is
primarily for deterministic expiry tests; use the default clock in applications.

`सत्रप्रबंधक.स्थानीय("http://127.0.0.1:8080")` is an **explicit** development mode
for loopback `127.0.0.1` or `localhost`. It uses `bhash-local-session`, retains
HttpOnly/SameSite/Path, and omits Secure. It rejects other hosts, HTTPS, paths,
fragments and queries. The example additionally requires a loopback listener.
Production constructors never infer this mode from forwarded request headers.

`कुकी.पढ़ो` is bounded to 8,192 header characters/64 pairs and rejects malformed
cookie syntax or duplicate requested names, including duplicates across Cookie
headers. Cookie names are case-sensitive. `कुकी.सुरक्षितजोड़ो` adds a separate
Set-Cookie header, always with Secure/HttpOnly/Lax/root path and no Domain. Its
max-age parameter is seconds, -1 for session-only, 0 to delete, up to 31 days.
`वेबउत्तर.शीर्षजोड़ो` retains repeated headers; do not join Set-Cookie with commas.

## CSRF, authorization and deployment

Wrap the whole router in `सुरक्षितवेब(manager, router)`. It validates the configured
Host and any Origin, requires an exact Origin plus one `X-CSRF-Token` on every
method except GET/HEAD/OPTIONS, rejects duplicate Origin/token headers, and checks
the token against the current live session using constant-time comparison.
Cross-site Fetch Metadata is also rejected on state-changing methods. SameSite
is defense in depth, not the only CSRF control. Login and logout are protected.
Never mutate application data from GET, HEAD or OPTIONS.

The public origin must be a canonical ASCII HTTP origin without path, query,
fragment or trailing slash, such as `https://app.example` or
`https://app.example:8443`. Preserve that Host when forwarding from a trusted TLS
proxy. Host includes a nondefault port. Origin is mandatory on state-changing
requests, including non-browser clients; those clients must obtain a session/CSRF
token first. Forwarded/X-Forwarded-* headers are not trusted to select the origin,
disable Secure or grant authorization. Do not expose the backend plaintext port.

The wrapper applies `Cache-Control: no-store`, `X-Content-Type-Options: nosniff`
and `Referrer-Policy: same-origin`, including policy-generated 4xx responses.
Application exceptions still go through the server's sanitized error handling.
The example adds a restrictive CSP and never renders user data with `innerHTML`.

`अधिकृत(request, role)` returns a live authenticated session or raises 401; a
missing nonempty role raises 403. Empty role means any authenticated user.
`अधिकृतचालक` applies this check before calling a route handler. Authorization
must also scope resource access: the example's parameterized SQL takes the owner
from the session, never an input owner field. Roles are snapshots; revoke all
sessions on password/permission changes with `उपयोगकर्ताहटाओ`, then reauthenticate.

`प्रवेशरक्षक` bounds login attempts per application-supplied account key, before
database lookup/KDF: default 5 attempts per 5 minutes, at most 1,024 active keys.
Capacity/rate exhaustion returns 429. Expired windows are reclaimed; keys cannot
evict active counters. Success consumes an attempt too. Use the same canonical
account identifier as the account store. Unknown accounts must also perform a
valid dummy-hash verification and return the same response as a wrong password.
The example does both. Add reverse-proxy per-client/global rate and connection
limits as well; per-account limits alone can be used to deny login to a victim.

This milestone supports **one application process**. Sessions/rate counters are
not durable or shared across workers; restarting signs everyone out. SQLite
accounts and owned notes persist independently. Do not run multiple workers or
promise shared session state. Session-store interfaces/durable distributed stores,
account recovery, MFA and external identity providers are separate follow-up work.
Use HTTPS across the public site, secure database permissions and backups, and
an explicit authorization policy for each sensitive route.

## Validation

JVM/CLR fixtures and native forced-GC programs use the same crypto vectors and
policy tests. They cover expiry boundaries, backwards clocks, capacity, rotation,
role copying, revocation, cookie/header injection and duplication, missing/wrong/
replayed CSRF tokens, Origin/Host/Fetch Metadata rejection, resource closure and
rate limits. The real HTTP demonstration tests login, unknown-user handling,
SQL-injection input, owned data isolation, admin authorization, logout, restart
and persistence. Standalone native C tests run at O0/O2 and with sanitizers on
supported runners, including malformed passwords and live-root retention.

References: [OWASP password storage](https://cheatsheetseries.owasp.org/cheatsheets/Password_Storage_Cheat_Sheet.html),
[session management](https://cheatsheetseries.owasp.org/cheatsheets/Session_Management_Cheat_Sheet.html),
[CSRF prevention](https://cheatsheetseries.owasp.org/cheatsheets/Cross-Site_Request_Forgery_Prevention_Cheat_Sheet.html),
[OpenSSL PBKDF2](https://docs.openssl.org/3.1/man3/PKCS5_PBKDF2_HMAC/),
[Windows CNG](https://learn.microsoft.com/en-us/windows/win32/api/bcrypt/nf-bcrypt-bcryptderivekeypbkdf2),
[Apple CommonCrypto](https://github.com/apple-oss-distributions/CommonCrypto).
