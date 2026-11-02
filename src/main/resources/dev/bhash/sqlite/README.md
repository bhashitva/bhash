# Pinned SQLite runtime inputs

SQLite 3.54.1 comes from the official amalgamation archive named in
`sqlite.lock.json`. Both its archive and `sqlite3.c` SHA3-256 digests are checked
before extraction. SQLite is public-domain software; see
https://www.sqlite.org/copyright.html and the notices in its source.

`python scripts/build_sqlite_runtime.py --output DIRECTORY` rebuilds the native
shared runtime for the current platform. CI builds twice and compares hashes.
Supported distribution targets are Linux x86-64 (glibc 2.35+), Windows x64 and
macOS ARM64. Windows uses the static MSVC runtime; no developer tools are needed
when running the resulting library. The compiler packages these libraries with
SQLite-using CLR applications. Native executables compile this same source
statically; JVM applications bundle the pinned SQLite JDBC driver.

The distribution manifests record source identity, compile options, output hash,
and platform. Binaries are loaded only from the application output directory.
Loadable SQLite extensions are disabled in the native/CLR build.
