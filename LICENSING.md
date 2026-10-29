# Bhash licensing

Unless a file states otherwise, Bhash project materials in this repository, including
the compiler, ANTLR grammar, runtime helpers, examples, scripts and documentation,
are licensed under the **Apache License, Version 2.0** (`Apache-2.0`). The authoritative
terms are in [LICENSE](LICENSE); project attribution is in [NOTICE](NOTICE).
Existing contributors retain their respective copyrights. This licensing statement
does not assign ownership of their contributions to another person or organization.

## Programs written in Bhash

You choose the license for your own application code. Using the Bhash compiler does
not, by itself, require you to license your application under Apache 2.0 or disclose
its source code. Commercial and proprietary applications are permitted.

Bhash runtime helpers and other Bhash support code included in generated applications
remain Apache-2.0 licensed. Generated JVM JARs include the license, notice and this
guide under `META-INF/bhash/`; generated CLR and browser output folders include them
at the output root. Keep the applicable license and attribution material when
redistributing those components, and follow the modification/notice requirements
in Section 4 of the license. These obligations do not change the license you select
for your own application code.

## Third-party components

Third-party code is not relicensed by this project. The compiler includes the ANTLR
4.13.2 runtime under BSD-3-Clause; its copyright and full terms are preserved in
[THIRD_PARTY_NOTICES.md](https://github.com/bhashitva/bhash/blob/main/THIRD_PARTY_NOTICES.md) and `META-INF/LICENSE-antlr.txt`.
The compiler/language server also includes Eclipse LSP4J and JSON-RPC 0.24.0
under the Eclipse Distribution License 1.0 (BSD-3-Clause) option and Gson 2.13.2
under Apache-2.0. Their notices are in THIRD_PARTY_NOTICES.md. Generated Bhash
applications do not include ANTLR, LSP4J or Gson. The VS Code extension bundles
MIT-licensed Microsoft language client libraries with their own notices.

Java/.NET SDKs, runtime distributions and other platform components retain their
own licenses. Browser WebAssembly bundles include the .NET runtime; preserve the
applicable platform and third-party notices when distributing them. Apache 2.0 for
Bhash does not replace those licenses.

## Contributions and branding

Contributions intentionally submitted for inclusion are provided under Apache 2.0,
unless explicitly stated otherwise, as described in Section 5. See
[CONTRIBUTING.md](https://github.com/bhashitva/bhash/blob/main/CONTRIBUTING.md). Apache 2.0 does not provide a general trademark
license for the Bhash or Nij Bhasha Sanstha names or logos; see Section 6.

Fundraising is voluntary and does not add conditions to the software license.

## Optional managed native toolchain

Managed toolchain archives separately redistribute LLVM/Clang/LLD and selected Linux system development/runtime components under their respective licenses. Their per-package notices are retained in `licenses/`, versions in `packages.json`, and matching corresponding sources in the companion `-sources.zip` identified by `sources.json`. Redistributors must make that companion archive and notices available alongside the binary toolchain. See [managed toolchains](docs/managed-native-toolchain.md); the Bhash Apache license does not replace those component licenses.
