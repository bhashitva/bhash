# SQLite database access

`मानक.एसक्यूलाइट`, `डेटाबेस`, `कथन` and `लेनदेन` provide synchronous,
parameterized SQLite access on JVM, CLR and native Linux x64, Windows x64 and
macOS ARM64. All use **SQLite 3.54.1**. No ORM, connection pool or database server
is introduced. [Hindi reference and complete examples](../tutorial/sqlite/README.md).

## Connections and statements

`एसक्यूलाइट.खोलो(path)` (or `नया डेटाबेस(path)`) opens/creates a local database;
`:memory:` creates an isolated temporary database. Relative paths use the process
working directory. Parent directories are not created. Paths are literal UTF-8,
1–4096 bytes, without NUL, `?`, `#`, `file:` or `jdbc:` prefixes. URI filenames,
remote databases, ATTACH/DETACH and extension loading are unavailable. This is
normal process file access, not a filesystem sandbox. Foreign keys are enabled.
`एसक्यूलाइट.संस्करण()` returns `3.54.1`.

`ड.तैयार(sql)` prepares one SQL statement, with optional trailing semicolon and
comments. SQL uses SQLite syntax/English keywords. Strings, quoted identifiers
and comments are respected by the shared lexer. Scripts and trigger definitions
containing multiple semicolons are outside this initial API. Transaction control
SQL is rejected: use the typed transaction scope. SQL must be nonempty, NUL-free,
at most 64 KiB UTF-8; a leading SQL keyword is required (no leading BOM).

Use anonymous `?` parameters. Named and numbered parameters (`:x`, `@x`, `$x`,
`?1`) are rejected. Bind **every** parameter before execution, including explicit
NULLs. Binding indices are **1-based**; result-column indices are **0-based**.
Parameters represent values; they cannot substitute table names, column names or
SQL operators. Do not concatenate untrusted input into SQL.

## Type conversion

| Bhash method | SQLite storage class | Reading rule |
| --- | --- | --- |
| `पूर्णांकरखो` / `पूर्णांकलो` | INTEGER | Exact signed 64-bit integer; no REAL/text coercion |
| `दशमलवरखो` / `दशमलवलो` | REAL | Binary64; integer-to-real conversion allowed, potentially rounding beyond 2^53; nonfinite rejected |
| `तार्किकरखो` / `तार्किकलो` | INTEGER 0/1 | Other integers rejected |
| `पाठरखो` / `पाठलो` | TEXT | Strict UTF-8, including Hindi, emoji and embedded NUL values |
| `बाइटरखो` / `बाइटलो` | BLOB | Byte-for-byte copies; empty blob differs from NULL |
| `रिक्तरखो` / `रिक्तहै` | NULL | Test explicitly before reading; getters throw on NULL |

`प्रकार(column)` reports the actual row storage class as `पूर्णांक`, `दशमलव`,
`पाठ`, `बाइटबफ़र` or `रिक्त`. SQLite column affinity may change a bound value's
storage class when inserting into a table. Bind/read methods do not override
SQLite affinity. Text/blob inputs are copied at binding; returned blobs are
independent copies that remain valid after reset/close. Malformed UTF-8 stored by
SQL casts or another application fails `पाठलो`, rather than replacing bytes.

## Execution and state

`चलाओ()` executes a statement with no result columns and returns the connection's
`total_changes64` delta (including trigger/cascade effects; DDL is usually zero).
It is not the last inserted row id. Use `SELECT last_insert_rowid()` if needed.
`अगला()` steps a result-producing statement, returning true for each row and false
at completion. `RETURNING` is a row-producing statement: use `अगला`, not `चलाओ`.
Repeated `अगला()` after completion stays false and never re-executes the query.

`बंधनसंख्या`, `स्तंभसंख्या` and `स्तंभनाम` inspect metadata. Row getters require a
current row. `रीसेट()` abandons a cursor and allows re-execution while preserving
bindings. `बंधनमिटाओ()` requires a reset/ready statement and marks every parameter
unbound. Bind only while ready. After an engine error, reset before reuse; invalid
API preconditions do not execute SQL. Constraint errors are catchable, and a
reset statement can be rebound and executed again.

## Transactions and cleanup

`ड.लेनदेन()` starts **BEGIN DEFERRED**. Only one transaction may be active per
connection; nested transactions/savepoints are not exposed. `.सहेजो()` commits;
`.वापसलो()` rolls back. An uncommitted `.बंदकरो()` rolls back. Use `संसाधन` for
connections, statements and transactions, including exception and early-return
paths. Commit failures leave the scope active so cleanup can attempt rollback.
A completed/stale scope cannot roll back a newer transaction on the same connection.

Beginning, committing or rolling back invalidates already executed/active cursors;
call `रीसेट()` before reusing them. Idle prepared statements remain ready. Closing
a connection finalizes all its statements, rolls back pending changes and
invalidates child operations. Close is idempotent, including closing a statement
after its connection. Shared state prevents use-after-close and handle reuse.
GC manages Bhash memory, but database resources need scoped cleanup; GC is not a
substitute for `संसाधन` and is not a transaction commit mechanism.

## Limits and errors

32 simultaneous connections, 128 simultaneous statements per process, 999 parameters,
256 columns, 8 MiB per value and combined text/blob bytes per row; SQLite's record
length limit also applies. Busy timeout is 5000 ms. Operations are synchronous and
not cancellable through this first API. Do not use SQL built-ins such as enormous
recursive CTEs on a UI thread. Releasing resources permits further operations;
these are simultaneous limits, not lifetime quotas.

All adapters map SQLite primary codes to the same Hindi `अपवाद` messages: busy/locked,
constraint, read-only, corrupt database, oversized value and file I/O, with a
stable generic statement failure for other engine errors. Engine/version-specific
English detail is not part of the API. Opening failures report `SQLite डेटाबेस खोला
नहीं जा सका।`; type/index/state errors have shared Hindi messages. Database access
on browser/WASM throws `ब्राउज़र लक्ष्य पर SQLite उपलब्ध नहीं है।` and cannot access
the playground server filesystem. No implicit IndexedDB substitution is attempted.

## Offline distribution and reproducibility

* Native: compile verified pinned `sqlite3.c` as a separate object and statically
  link only for database programs. Executables need no JVM, .NET or SQLite package.
  Native build metadata records SQLite version and source SHA3-256; release bundles
  carry the public-domain attribution. Sanitizer builds instrument this object too.
* JVM: database JARs include Xerial sqlite-jdbc 3.54.1.0, native resources and original
  licenses. The driver extracts its platform library to a writable temporary
  directory at runtime. There is no network download or separate classpath setup.
  Java 17+ remains required; `java -jar` declares JNI native access on recent JDKs.
* CLR: copy the **whole** generated output directory. It includes three verified
  native libraries and SQLITE_RUNTIME.json; the adapter hashes the matching file
  before loading it from the application directory and verifies the SQLite version.
  No system-SQLite fallback, NuGet restore dependency or runtime download. .NET 10
  remains required. Supported SQL platforms: glibc 2.35+ Linux x64, Windows x64,
  macOS ARM64. Unrelated CLR apps receive no SQLite binaries.

Source, lockfile and runtime manifests: `src/main/resources/dev/bhash/sqlite/`.
`python scripts/build_sqlite_runtime.py --output target/sqlite-runtime` rebuilds
on the matching host using an installed C compiler (MSVC x64 environment on Windows).
The CI workflow builds twice on each supported OS and compares bytes. The checked-in
libraries are the verified artifacts of run 41368892339; SHA-256 manifests record
identity. Updating SQLite requires updating source hash, JDBC version, all libraries,
conversion/transaction tests and notices together. Compiler builds consume these
assets offline after ordinary Maven dependency resolution.

An execution error inside a transaction marks the scope rollback-only. Further execution and commit fail until rollback/close. This prevents silent autocommit after SQLite automatically aborts a transaction (for example INSERT OR ROLLBACK). Reset alone does not recover a failed transaction.
