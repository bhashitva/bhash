# Database providers and migrations

`मानक.डेटा` supplies `डेटासंबंध`, `डेटाकथन`, and `डेटालेनदेन` over SQLite and PostgreSQL. Existing SQLite APIs remain supported. This shared resource/type contract also underpins the [generated ORM layer](orm.md).

## PostgreSQL contract

PostgreSQL server/client 14+; JVM uses the vendored SHA-256-verified pgjdbc 42.7.14. CLR and native load libpq dynamically. No native build headers are required. Native releases list libpq in `manifest.json` and describe installation in README. They do not embed a PostgreSQL server or libpq. On Debian/Ubuntu install `libpq5`; macOS/Windows use the official PostgreSQL client distribution. `BHASH_LIBPQ` must be an absolute library path when supplied. Keep the accompanying client dependencies and licenses. A missing runtime produces a catchable Bhash exception. Existing SQLite-only deployments need no PostgreSQL server.

TLS defaults to `verify-full`; configure a CA certificate and hostname. `disable` is an explicit local-test option. Configuration has separate host/port/database/user/password fields, not a concatenated connection URL. Passwords/SQL/server error details are excluded from exceptions; SQLSTATE is retained for server statement failures. Connection failures use `XXXXX` consistently because libpq provides no SQLSTATE for them. Connection and server-side statement/lock timeouts are configurable. These do not promise a wall-clock network cancellation deadline.

Use anonymous `?` parameters and explicit casts for otherwise ambiguous PostgreSQL types, e.g. `SELECT ?::bigint`. Binding indices start at one; result columns at zero. Values are never interpolated into SQL. Ordinary quoted strings/identifiers and nested comments are supported. Dollar-quoted SQL, E escape strings, numbered/named parameters, JSON question-mark operators, multiple statements, COPY and direct transaction/session commands are deliberately outside the initial portable API. Use transaction methods instead.

Integers are signed 64-bit, floating values finite binary doubles, booleans typed, bytea copied buffers, text UTF-8 without embedded NUL. NUMERIC is exact text (`सटीकदशमलव`), never silently converted to double. Dates/times are text (`समय`), with UTC/ISO session settings. JSON/UUID can be read as text; explicitly cast other PostgreSQL-specific types. The SQL dialect, schema types and timestamp rendering remain provider-specific.

Limits: 32 connections, 128 statements, 999 parameters, 256 columns, 64 KiB SQL, 8 MiB bound text/bytes and result row. Results are buffered by the client: use bounded queries/pagination for large datasets. There is no streaming cursor or connection pool in this milestone. Parameter/result allocations are owned by the driver/libpq, not the Bhash collector. Returned text/buffers are independent managed copies. Use `संसाधन` for deterministic closure; GC does not close database handles.

An uncommitted transaction rolls back on close. Server errors make it rollback-only. Commit/rollback invalidate active result cursors. Reset before executing again; reset retains bindings, clear removes them. PostgreSQL transaction DDL behavior differs from SQLite statement preparation; avoid changing a schema while holding prepared statements over it.

## Migrations

An ordered immutable plan snapshots version/name/SQL lists. Versions must be positive and increasing. SHA-256 covers version, name and byte-length-framed SQL. Applied history must be an exact prefix; missing/reordered/edited/newer history is rejected. Changes are forward-only explicit CREATE/ALTER/DROP/INSERT/UPDATE/DELETE statements, not inferred schema differences. Use transactional DDL (no CREATE INDEX CONCURRENTLY, database creation, external side effects).

All pending changes and history inserts commit in one transaction. Failure rolls back the entire pending batch. PostgreSQL takes a transaction advisory lock; SQLite takes a write lock before inspecting history. Status/validation also initialize history if absent, and therefore require write permission. Do not nest migration operations inside an application transaction. Take backups and test upgrades on a copy of production data.

See [Hindi tutorial](../tutorial/database-providers/README.md). Schema migrations remain explicit. See the [generated ORM tutorial](../tutorial/orm/README.md) for typed records and repositories on top of this API.

## Validation

`DatabaseProviderTest` checks SQLite upgrades, checksums, lexical rules and native lowering. `PostgresIntegrationTest` always checks fixture compilation/driver packaging; real server execution is mandatory in the PostgreSQL CI workflow (`BHASH_REQUIRE_POSTGRES=true`) and otherwise explicitly skipped. The fixture uses temporary tables, preserving unrelated data. Native runs use forced GC and assert zero live allocations after shutdown. Native deployment tests execute in a runtime-only image with libpq, without Java/.NET/LLVM/C tools.
