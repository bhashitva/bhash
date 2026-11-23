# Publishing, finding and reusing Bhash packages

[हिन्दी मार्गदर्शिका](../tutorial/package-distribution/README.md)

A package is a versioned Bhash source library, optionally with a typed C binding.
The compiler provides `package search`, `info`, `add`, `update`, `pack` and
`publish`. A small JSON index can be hosted in a Git repository or on HTTPS;
there is no registry server to install. The built-in index is
[`packages/index.json`](../packages/index.json). Existing local dependencies,
pinned Git dependencies and lockfile formats remain supported.

These commands are in the compiler source for this milestone. Use a compiler JAR
built from this revision until a public compiler/extension/kernel release includes
them. `java -jar bhash-compiler.jar package` displays the available commands.

## 1. Find a package

```sh
java -jar bhash-compiler.jar package search text
java -jar bhash-compiler.jar package info bhash-text-utils
java -jar bhash-compiler.jar package info bhash-text-utils@1.0.1
```

Search matches names and Hindi/English descriptions, displaying the highest
stable version of each match. `info` shows the exact source repository, complete
commit, package directory, license and source/manifest fingerprints. Prereleases
require an explicit version. Versions are compared using semantic-version rules,
not alphabetical order. No version ranges or automatic upgrades are accepted.

For an organization's index, append `--index https://example.org/index.json`.
A local path is also supported. `--index-sha256 HASH` pins the exact index bytes.
After an HTTPS index has been fetched, `--offline` uses its cached validated copy;
it fails when that copy is missing. The optional `bhash.packages.cache` Java
system property selects the index cache directory.

## 2. Add and use an exact version

Create `my-app/src/main.bhash` with the Bhash program below. Create
`my-app/bhash.project` with a matching strict profile:

```properties
name=my-app
sources=src
nullSafety=strict
```

Then add the dependency and run the application:

```sh
java -jar bhash-compiler.jar package add bhash-text-utils@1.0.1 --project my-app --alias text
java -jar bhash-compiler.jar deps verify my-app --offline
java -jar bhash-compiler.jar run my-app --target jvm
```

The command resolves the index entry, fetches the pinned source, compares its
manifest/source fingerprints and writes ordinary exact Git dependency properties
plus `bhash.lock`. All transitive packages use the existing resolver. Prebuilt
C providers use their existing per-host HTTPS URLs, checksums and license checks.
An HTTPS index cannot request a local `file:` repository. Local indexes can use
explicit `file:` Git repositories for offline tests.

The consumer writes normal Bhash:

```bhash
आयात उपयोगीपाठ.अभिवादन;
आयात उपयोगीपाठ.दोहराओ;
फलन मुख्य() -> शून्य {
    लिखो(अभिवादन("भारत"));
    लिखो(दोहराओ("भाष!", ३));
}
```

```text
नमस्ते, भारत!
भाष!भाष!भाष!
```

Review and commit **both** `bhash.project` and `bhash.lock`. Manifest values are
preserved, but dependency editing serializes properties in deterministic order
and does not preserve comments/layout. Existing locked inputs must verify before
an edit. If resolution, fingerprint validation or provider restore fails, the
previous manifest/lock bytes are restored; downloaded cache entries may remain.
Concurrent external edits are not overwritten during rollback. A process crash
between the two file replacements can leave a stale lock; review the manifest
and deliberately run `deps lock` to recover. Source and build hooks are never run
by installation. Compiling/running the application is a separate action.

## 3. Update deliberately and reproduce a clean build

```sh
java -jar bhash-compiler.jar package update bhash-text-utils@1.1.0 --project my-app --alias text
java -jar bhash-compiler.jar deps restore my-app
java -jar bhash-compiler.jar deps verify my-app --offline
```

The update command requires an existing alias and retains its repository,
directory and package identity. An installed exact version cannot be repointed
to another commit, even if an index entry was edited manually. The example version `1.1.0` must first be
published to the selected index. No background lookup or update occurs during
compilation or editor analysis. After a clean checkout, `deps restore` uses the
manifest/lock without consulting the index; use `--offline` if sources/providers
are already cached. Commit lockfile changes after review.

## 4. Author and pack a library

See the [complete example library](../packages/examples/text-utils/) and
[consumer](../examples/package-reuse/). A library manifest has:

```properties
name=bhash-text-utils
kind=library
version=1.0.1
sources=src
nullSafety=strict
```

Use lowercase ASCII distribution names (up to 64 characters; hyphens/dots are
allowed). Bhash package/import identifiers remain Devanagari. Include `LICENSE`
and preferably `README.md`; export source declarations with `सार्वजनिक`.
The consumer's null-safety profile must be compatible with the library.

```sh
java -jar bhash-compiler.jar package pack packages/examples/text-utils -o target/text-utils-release
```

Output is a **new** directory containing a deterministic source ZIP,
`SHA256SUMS` and `release-info.json`. Only the manifest, discovered Bhash sources,
binding manifest, `LICENSE`, optional `README.md` and declared provider licenses
are included. `.env`, `.git`, build outputs and install scripts are not swept
into the archive. Source text line endings are normalized to LF. The ZIP has a
file-hash inventory and fixed timestamps; unchanged input produces identical
archive bytes. Existing output directories are never overwritten. The limits are
10,000 source files, 16 MiB per file and 64 MiB total text inventory.

Archives are downloadable source releases for inspection/manual vendoring. The
package resolver installs **pinned Git source**, not ZIP files or precompiled
Bhash libraries. To vendor an archive, verify its checksum, extract into a local
library directory and use an existing local-path dependency. Checksums identify
bytes, not publisher identity or code safety.

## 5. Publish an immutable version

Commit the library, README and license first. Use the complete commit hash,
not a branch/tag name. In the following command, replace `OWNER`, `REPOSITORY`
and `FULL_COMMIT` with the actual repository and commit:

```sh
java -jar bhash-compiler.jar package publish packages/examples/text-utils --index target/my-index.json --git https://github.com/OWNER/REPOSITORY.git --rev FULL_COMMIT --dir packages/examples/text-utils --description "Text helpers" --license Apache-2.0 -o target/text-utils-published
```

For Hindi/non-ASCII metadata on Windows, put the description in a UTF-8 file and
use `--description-file description.txt` instead of `--description`; this avoids
Windows Java command-line encoding differences.

`publish` verifies the local package against the committed source, including
release text/licenses, and validates the transitive graph under Git repository
boundaries. It creates release assets and atomically adds an entry to the
**local** index. The same name/version cannot be repointed or have its metadata
changed; increment the version. Repeat publication of an identical entry is
allowed with a fresh output directory. Uncommitted source/license changes are
rejected. Relative local dependencies must remain inside the pinned repository;
use exact Git dependencies for separately hosted libraries.

Hosting remains an explicit step. For example, upload the generated ZIP and
checksum as permanent GitHub Release assets, then submit/commit the index update
to the index owner's repository. The CLI does not implicitly create repositories,
push commits, upload releases or grant publisher rights. Example upload using an
already authenticated GitHub CLI, with your actual values:

```sh
gh release create package-text-utils-1.0.1 target/text-utils-published/bhash-text-utils-1.0.1.zip target/text-utils-published/SHA256SUMS target/text-utils-published/release-info.json --repo OWNER/REPOSITORY --target FULL_COMMIT --title "bhash-text-utils 1.0.1"
```

An index owner reviews additions and controls publishing through ordinary Git
permissions/reviews. It can serve `index.json` from GitHub raw HTTPS or another
HTTPS host. The built-in index accepts contributions by PR; another organization
can host its own index without modifying the compiler. This initial workflow
does not provide a central account service, signatures, namespaces owned through
a registry, version-range solving, automatic advisories or package execution isolation.

## 6. Private indexes and repositories

For a private HTTPS index, supply `BHASH_PACKAGES_HOST` (hostname only) and
`BHASH_PACKAGES_TOKEN` through your local credential/secret manager. The token is
sent as Bearer authorization only to the selected host and original port; it is
not forwarded to a different redirect origin. For private HTTPS Git sources use
`BHASH_GIT_HOST` and `BHASH_GIT_TOKEN`; the token is passed to Git through scoped
environment configuration, not URLs, command-line arguments or lockfiles. Git
redirects are disabled for authenticated requests. Tokens are never obtained from
an index, and a dependency cannot select its own credentials. Use least-privilege
read access and never commit tokens. SSH/interactive credential-helper workflows
remain unsupported. An index token does not authenticate provider artifact URLs;
those retain the existing artifact-download contract.

## 7. Platforms and validation

Source reuse applies to JVM, CLR, native and browser builds when the package's
API/capabilities are supported by that target. Native code retains the normal GC
root/ownership contracts. C bindings still need matching per-host artifacts and
are not enabled for browser/Android by package installation. Git is required for
source snapshots; application users do not need a C compiler when consuming
complete prebuilt JVM/CLR providers.

Run the self-contained end-to-end fixture with a built compiler:

```sh
python scripts/test_package_distribution.py --target jvm --target clr
python scripts/test_package_distribution.py --target native
```

It publishes a local Git fixture/index, searches, installs, executes the same
consumer, restores in a fresh location and verifies offline reuse. Native runs
use forced GC. CI runs the fixture on Linux and Windows; macOS validation stays
in the weekly workflow. [Existing dependency rules](source-packages.md) describe
graph conflicts, locks, source boundaries and explicit restore in more detail.
