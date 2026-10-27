# Reusable Bhash source packages

A source package is a versioned directory containing `bhash.project` and Bhash
sources. Applications declare exact dependencies, commit `bhash.lock`, and build
with the same verified source graph on JVM, CLR, or browser WebAssembly.
Language-level `संकुल` and `आयात` keep their existing meanings: manifest names
identify distributions; imports identify public source declarations.

## Local libraries

`util/bhash.project`:

```properties
name=my-util
kind=library
version=1.2.3
sources=src
```

`util/src/math.bhash`:

```text
संकुल उपयोगिता;
सार्वजनिक कार्य दुगुना(अंक: पूर्णांक) -> पूर्णांक { लौटाओ अंक * २; }
```

`app/bhash.project`:

```properties
name=my-app
sources=src
dependency.util.path=../util
dependency.util.version=1.2.3
```

`app/src/main.bhash`:

```text
आयात उपयोगिता.दुगुना;
कार्य मुख्य() -> शून्य { लिखो(दुगुना(२१)); }
```

From the compiler repository root:

```sh
java -jar target/bhash-compiler-0.1.0-SNAPSHOT.jar deps lock app --offline
java -jar target/bhash-compiler-0.1.0-SNAPSHOT.jar run app
```

The expected output is `४२`. Local paths are relative to the declaring manifest
and use forward slashes on every platform. Sibling directories are supported.
Keep the same relative layout on collaborators' machines and CI. Commit the
library's source, or otherwise provision the declared local directory; restore
does not copy a local dependency or revert its edits.

## Exact Git revisions

Replace the path declaration with these fields. The URL and hash below are
placeholders: supply a real repository URL and its **complete commit hash**.

```properties
dependency.util.git=https://github.com/OWNER/REPOSITORY.git
dependency.util.rev=FULL_40_OR_64_CHARACTER_COMMIT_HASH
dependency.util.dir=packages/util
dependency.util.version=1.2.3
```

`dir` selects a package inside a repository and defaults to `.`. `version` must
match that package's manifest. Branches, tags (including annotated tag objects),
short hashes and version ranges are rejected. Obtain a commit hash with
`git rev-parse HEAD` or `git rev-parse 'v1.2.3^{commit}'` in the library repository.
Review and pin the source you intend to consume; a version string is not proof of
publisher identity.

Only credential-free HTTPS URLs and explicit local `file:` repository URIs are
supported. Local Git URIs are useful for testing but contain an absolute machine
location; use HTTPS for a shared lockfile. Git is required for fetching/exporting
snapshots; set `BHASH_GIT` to an executable path if Git is not on PATH. The initial
release does not support authenticated private repositories, SSH, credential
helpers, or interactive sign-in. Git system/global configuration and hooks are
not used. Servers must allow fetching the requested commit.

## Lock, restore, verify, build

| Command | Purpose | Network / writes |
| --- | --- | --- |
| `deps lock <project>` | Intentionally accept current manifest and local dependency contents; resolve exact Git commits and atomically replace the lock | May fetch; writes missing cache and lock |
| `deps lock <project> --offline` | Same operation using local directories and already cached Git snapshots | No network; writes lock |
| `deps restore <project>` | Fetch missing snapshots and validate the entire existing locked graph | May fetch; never updates lock |
| `deps restore <project> --offline` | Validate existing local/cached sources | No network or writes |
| `deps verify <project>` | Validate existing local/cached sources | No network or writes |
| `check`, `compile`, `run`, editor analysis | Read the verified dependency graph | No fetching or lock/cache writes |

`--offline` is also accepted for `verify` (which is always offline). A project
with dependencies requires a lockfile before builds or editor analysis. Existing
projects without dependencies keep working without a lockfile. If a lockfile is
present, it is always checked, including after removing the last dependency.

Commit `bhash.project` and `bhash.lock`. Ignore `.bhash/`: it contains disposable
Git source snapshots keyed by repository URL and commit. In a fresh checkout:

```sh
java -jar target/bhash-compiler-0.1.0-SNAPSHOT.jar deps restore app
java -jar target/bhash-compiler-0.1.0-SNAPSHOT.jar deps verify app
java -jar target/bhash-compiler-0.1.0-SNAPSHOT.jar compile app
```

Changing a local library file intentionally invalidates its consumers' locks.
After reviewing the change, rerun `deps lock` for each consuming root and commit
the updated locks. For Git upgrades, change the full `rev` (and matching `version`
when appropriate), then run `deps lock` and review the lock diff. `restore` never
silently accepts a changed package. An unsuccessful lock operation preserves the
previous lockfile; successfully fetched snapshots may remain in the cache.

If a managed snapshot is damaged, move that snapshot directory out of `.bhash/`
and run `deps restore`. Restore does not overwrite an existing tampered snapshot
or an unmanaged directory. Avoid editing cached copies; change the upstream
library or use a local path dependency while developing it.

## Graph and source rules

- Libraries require explicit `name`, exact SemVer 2.0 `version`, and `kind=library`.
  Applications default to `kind=application`; their version is optional.
- Libraries cannot declare a top-level `मुख्य`. They are source inputs to a
  consuming application, not independently compiled executables. Editor analysis
  and syntax checking support libraries without requiring an entry point.
- Dependency aliases use `[A-Za-z][A-Za-z0-9_-]{0,63}`. Each alias has exactly one
  `path` or `git`, an exact `version`, and (for Git) a full `rev` plus optional `dir`.
  Unknown/duplicate manifest properties and incomplete declarations fail early.
- Dependencies may declare further dependencies. Every dependency must be a
  library. Cycles, a shared package name with different locations/versions,
  overlapping source inventories and missing directories are errors. A diamond
  referencing the same physical package includes its sources once.
- A library's own lockfile is not used by consumers. The consuming root lock pins
  the complete transitive graph; library manifests declare their dependencies.
- A Git library can use relative local dependencies within its pinned repository.
  Such paths cannot escape that snapshot. A Git manifest cannot request a local
  `file:` Git repository. Nested HTTPS dependencies require their own exact pins.
- Imports retain public/package-private visibility. All transitive sources are
  in the compilation; this milestone does not enforce direct-dependency-only
  imports or create isolated namespaces per distribution.
- Source roots stay inside each package. Symlinks and Git submodules are rejected.
  Snapshots reject unsafe paths, case-colliding files, Windows reserved names,
  paths with trailing spaces/dots and invalid UTF-8 names. Exported Git snapshots
  are limited to 10,000 files, 16 MiB per file and 64 MiB total; dependency source
  inventories are limited to 10,000 files and 64 MiB. Graphs are capped at 512
  packages. No package install/build hooks are executed.
- The root lock is strict JSON with `lockVersion: 1`. It records canonical
  manifest SHA-256 and sorted dependency source SHA-256 hashes. Source paths are
  relative; leading UTF-8 BOMs and CRLF/CR versus LF are normalized for hashes.
  Comments/property ordering in manifests do not affect their canonical hash.
  Root source edits do not change the lock. Local dependency paths remain
  relative to the root; Git dependencies record their URL, commit and subdirectory.
- Dependency sources, manifests, the root lock and cache are protected against
  compiler output overwrites. Build outputs use the existing project conventions.

Reproducibility here means the same verified **source graph**, not byte-identical
host artifacts: pin the compiler, JDK/.NET/workloads and build options separately.
A lockfile is an integrity record under your version control, not a signature or a
sandbox for untrusted source programs. Target-specific APIs retain their existing
compatibility rules; a browser application cannot gain local filesystem access by
importing a library.

## Editor and diagnostics

VS Code provides **Bhash: Update Dependencies Lock**, **Bhash: Restore Dependencies**
and **Bhash: Verify Dependencies** in a trusted workspace. These commands save
Bhash buffers, run the explicit dependency operation and refresh analysis on
success. Manifest fields are highlighted; changing `bhash.lock` refreshes analysis.
Completion, auto-import, signatures and navigation include verified dependency
sources. Unsaved source buffers participate in analysis without rewriting locks;
the saved dependency files must still match the lock. Build/run uses saved files.
For external local libraries, add their directories as workspace folders to watch
changes made outside the editor, or restart the language server after such edits.
Cache snapshots are excluded from independent workspace discovery.

| Code | Meaning | First action |
| --- | --- | --- |
| `BH8001` | Invalid package metadata / library source or executable misuse | Correct manifest/source or build the consuming app |
| `BH8002` | Missing, stale, malformed or unsupported lockfile | Review manifest changes; run `deps lock` deliberately |
| `BH8003` | Dependency content differs from lock | Restore the original source or review and relock |
| `BH8004` | Cycle, identity/version conflict or invalid package relationship | Fix dependency declarations |
| `BH8005` | Git, cache or portable snapshot-path failure | Check exact revision, Git availability, URL and cache |

Errors include Hindi guidance and English detail. Missing filesystem paths may
also retain the operating system's detailed error.

Try the checked-in [three-package example](../examples/packages/README.md) and the
[Hindi reference tutorial](../tutorial/packages/README.md).

## Deferred work

A public registry, version range solving, credentials/private Git, package
publishing commands, precompiled Bhash libraries and incremental/separate
compilation are outside this first milestone. Single-file playground submissions
remain self-contained; they do not fetch dependencies from a submitted manifest.
