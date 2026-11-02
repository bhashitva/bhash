# SQLite examples

`values.bhash` covers all bind/read methods, NULL, empty blobs, metadata, reset and close.
`transactions.bhash` demonstrates commit, rollback, scope cleanup and exception cleanup.
`offline.test.bhash` runs with `bhash test examples/sqlite --target jvm|clr|native`.
The examples use independent in-memory databases and need no network or pre-existing data.
See [Hindi tutorial](../../tutorial/sqlite/README.md) and [contract](../../docs/sqlite.md).
