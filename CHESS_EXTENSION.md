# Bhash text extension

This checkout accompanies the native Bhash port of `bhashitva/js-chess-engine`.
Baseline: `2029642dae140e9fe488798f8f81962ef0019624`.

See [docs/text-operations.md](docs/text-operations.md) for the added language
surface and semantics. Compiler changes are limited to predeclared text
functions, checked text concatenation, JVM/CLR runtime implementations and their
shared regression tests. The upstream language syntax is preserved.

Build with JDK 17+ and Maven 3.9+: `mvn verify`.
The generated `target/bhash-compiler-0.1.0-SNAPSHOT.jar` can build the chess
library and its console/protocol entry points. A prebuilt compiler JAR is included
in the local delivery. It is not needed to run the already compiled chess apps.

The upstream compiler has no selected open-source license as of the baseline;
this delivery does not assign one. Third-party ANTLR licensing is documented
separately in `THIRD_PARTY_NOTICES.md`.
