# Bhash (भाष) for Visual Studio Code

Write Bhash on Windows, macOS, Linux, WSL, or a remote VS Code host. This extension
bundles the Bhash compiler and a Java language server. Source analysis stays on
the extension host; it does not send code to the public playground or execute
programs while you edit.

## Community and support / समुदाय और सहायता

Join the [Bhash Discord community](https://discord.gg/qC5zdJy6Qw) to learn with other Bhash users,
share projects, collaborate, and reach Bhashitva support for questions, issues or help.

भाष उपयोगकर्ताओं के साथ सीखें, अपनी रचनाएँ साझा करें और प्रश्नों या समस्याओं में
भाषित्व की सहायता पाने के लिए हमारे [Discord समुदाय](https://discord.gg/qC5zdJy6Qw) से जुड़ें।

In VS Code, run **Bhash: Community & Support (Discord)** from the Command Palette.

## Install

1. Download the `.vsix` package from the **Bhash VS Code extension** GitHub
   Actions artifact for a successful main-branch build.
2. In VS Code choose **Extensions → … → Install from VSIX…** and select the file.
3. Install **Java 17 or later** on the computer running the extension host.
   A full JDK is needed for JVM build/run. In WSL, SSH, or Codespaces, install Java
   on that remote host and install the extension there.
4. Open a folder containing `bhash.project`, or open a standalone `.bhash` file.
   Trust the workspace to enable analysis and compiler tasks.

No Maven or Node installation is needed to use the packaged extension. The extension is
available on the [Visual Studio Marketplace](https://marketplace.visualstudio.com/items?itemName=bhashitva.bhash).
A locally packaged or Actions-built version can be installed before its Marketplace update is published.

## Native debugging on Linux and WSL

Run **Bhash: Debug Native** to save and compile the active Bhash project
with source debug information, then launch VS Code's GDB debugger. Install Microsoft's
**C/C++** extension and **GDB 12+ with Python 3 support** on the Linux/WSL extension
host (`sudo apt install gdb` on Ubuntu). Java 17+ remains required for compilation;
the ordinary managed native SDK supplies LLVM automatically.

Source breakpoints, step into/over/out, Hindi call stacks and scalar/text/object/
collection inspection are supported. Inspection reads memory without calling program
functions or changing GC roots. Windows debugging is also available; macOS editor debugging remains pending. See the [Hindi debugging guide](../../docs/native-vscode-debug.md)
for launch configurations, limits, GC safety and troubleshooting.

## Features

- Highlighting generated from the current ANTLR keyword definitions, with
  Devanagari-aware word boundaries, comments, strings, and both numeric scripts.
- Brackets, indentation, comment toggling, folding, and English/Devanagari snippets:
  `main`, `class`, `interface`, `record`, `enum`, `foreach`, `try`, `switch`, etc.
- Keyword completion with English search aliases, built-ins, lexical declarations,
  same-package types/functions, and explicitly imported names/aliases.
- Whole-project syntax and semantic diagnostics in Problems, including unsaved
  source/manifest buffers and newly created source buffers. Imports and types are
  checked using the same semantic checker as all three managed/browser targets.
- Typed member completion for declared receivers, inherited and generic members,
  records, enums, lists/maps/arrays and byte buffers. Private/protected visibility
  and instance/static receiver rules filter suggestions.
- Signature help on `(` and `,` for functions, overloads, methods and constructors,
  including parameter types and the active argument. Nested calls are tracked.
- Completion of an unimported public type/function adds a file-local `आयात`.
  Existing aliases and name conflicts are respected; unresolved names also offer
  an import Quick Fix when a matching public declaration exists in the project.
- Hover and F12 for lexical, top-level and resolved receiver members. Overloaded
  navigation lists declarations rather than choosing a call's resolved overload.
  Outline and workspace symbols include types, records, enums, interfaces, functions and members.
- Coded diagnostics with Hindi guidance and English detail. Independent body errors
  are collected in one pass (up to 50); declaration/import/type-graph failures stop
  the semantic pass to avoid misleading follow-on errors.
- Independent manifests and multi-root workspaces remain separate compilation
  units. Discovery ignores `.bhash`, `.git`, `node_modules`, `target`, `build`, and `dist`.
- Build JVM, CLR, Native, or browser WebAssembly; run JVM/CLR/Native in an integrated terminal
  with console input. File paths are passed as process arguments, never a shell
  command. Builds use saved files and prompt for the target.
- **Bhash: New Project**, **Bhash: Restart Language Server**, and
  **Bhash: Open Browser Playground** commands.

`स्थिर` means a constant binding. Static class members use **`साझा`**.

## Native on Windows, Linux and WSL

Native builds use the compiler bundled with the extension. Choose `native` from
**Bhash: Build Project or File** or **Bhash: Run Project or File**. The first task
downloads the [versioned SDK](https://github.com/bhashitva/bhash/releases/tag/native-sdk-1.1.0)
(110 MiB), checks its bundled SHA-256, verifies the installation, and activates it
in your user cache. Later builds reuse it, including offline. No separate LLVM,
Maven or Node installation is required. Java 17+ is still required to run the compiler.
On macOS, install Apple’s Command Line Tools separately for the local SDK and linker.

Supported hosts:

- **Windows 10+ x64**: ordinary Windows VS Code, Java 17+, and Bhash are sufficient.
  WSL and Visual Studio are not required. The extension downloads the pinned
  [Windows SDK](https://github.com/bhashitva/bhash/releases/tag/native-sdk-windows-1.0.0)
  (71 MiB) and produces `.exe` applications using Windows UCRT.
- **Linux x86-64, glibc 2.35+**, including Ubuntu 22.04+ in WSL. Install Bhash and
  Java inside WSL when using a WSL workspace; its SDK runs inside that distribution.

- **macOS 14+ Apple Silicon ARM64**: use ARM64 VS Code, ARM64 Java 17+, and Apple’s
  Command Line Tools (`xcode-select --install`). Bhash downloads a pinned LLVM 18
  SDK; Apple’s SDK and linker are not redistributed. See [macOS setup and distribution](https://github.com/bhashitva/bhash/blob/main/docs/native-macos.md).

Intel Macs, other ARM hosts, and musl/Alpine are not supported. Each SDK builds
for its own host; this release does not add cross compilation. Generated applications
need neither Java nor .NET nor LLVM. The compiler continues to require Java 17+.

- **Bhash: Set Up Native Tools** installs tools before the first build.
- **Bhash: Check Native Setup** diagnoses the current installation without downloading.
- **Bhash: Repair Native Tools** downloads and verifies a fresh copy; failed setup
  preserves the previously active SDK. Cancel through the task terminal.
- `bhash.native.toolchainHome` optionally chooses an absolute cache path on the extension host.
  Empty shares the CLI cache (`BHASH_TOOLCHAIN_HOME`, otherwise
  `%LOCALAPPDATA%\Bhash\toolchains` on Windows;
    `$XDG_CACHE_HOME/bhash/toolchains` or `~/.cache/bhash/toolchains` on Linux). Allow about
  1 GB of free disk space during installation; repairs retain previous generations.
- `bhash.native.toolchainMode = system` opts into an existing LLVM 18 toolchain.
  On Windows, system mode requires LLVM-MinGW 18/UCRT, not an MSVC-only LLVM installation.
    In managed mode, ambient `BHASH_CLANG`, `BHASH_LLVM_OPT`, and `BHASH_READELF`
  overrides are ignored for these tasks so they cannot mix incompatible tools.

Native tasks support console input (`पाठपढ़ो()` and `पढ़ो()`) in the integrated
terminal. No SDK download occurs while editing, discovering tasks, or in
Restricted Mode. Dependencies still follow the ordinary lock/restore rules.
Example `.vscode/tasks.json` entry:

```json
{"type":"bhash","label":"Native build","command":"build","project":"bhash.project","target":"native"}
```

Version 0.5.2 replaces the Ubuntu 24.04 SDK shipped in 0.5.0/0.5.1 with a
Jammy-built SDK for glibc 2.35+. Existing installs migrate through the normal
checksum-verified installer; the earlier release assets remain available.

The SDK and corresponding third-party sources are GitHub **Release assets**,
not files committed to Git. Copyright and redistribution notices are retained;
see [managed toolchain details](../../docs/managed-native-toolchain.md).
Generated Linux executables do not require Java or LLVM; their supported runtime
still depends on the recorded Linux/glibc compatibility requirements. Debugger
integration and native compilation of every language feature are separate work.

### हिन्दी में शुरुआत

Linux या WSL में Ubuntu 22.04 या नया संस्करण और Java 17+ स्थापित करें।
VS Code में WSL वाला फ़ोल्डर खोलें और उसी WSL में Bhash extension स्थापित करें।
**Bhash: Run Project or File** में `native` चुनें। पहली बार आवश्यक उपकरण अपने
आप डाउनलोड और सत्यापित होंगे; उसके बाद इंटरनेट के बिना भी निर्माण कर सकते हैं।
उपकरणों की जाँच के लिए **Bhash: Check Native Setup**, और खराब स्थापना को फिर से
स्थापित करने के लिए **Bhash: Repair Native Tools** चलाएँ।

## Standard library

The bundled compiler includes text, math/random, JSON, dates/time, file I/O and
UTF-8/binary data support. It also includes collection algorithms, streams,
paths/directories, scoped resource cleanup (`संसाधन`), configuration, logging,
a Bhash test runner, HTTP networking and SQLite database access.

Use imports such as `आयात मानक.पाठ्य;` or `आयात मानक.एसक्यूलाइट;`, or fully
qualified names. Completion, signature help and automatic imports use the same
catalog as compiler checking; F12 opens a readable declaration document.

SQLite supports parameterized SQL, typed values, transactions and deterministic
cleanup on JVM, CLR and native. The compiler bundles the SQLite driver/source and
verified runtime libraries needed for supported Windows x64, Linux x64 and macOS
ARM64 targets; users do not install SQLite separately. Normal Java/.NET/native
toolchain prerequisites still apply. Generated database applications can run
offline. SQLite is unavailable in browser WASM; local file operations are also
unavailable there, and browser networking follows browser security restrictions.

See [the standard library guide](../../docs/standard-library.md) and the Hindi
reference tutorials for [application foundations](../../tutorial/application-foundations/README.md),
[networking](../../tutorial/networking/README.md) and [SQLite](../../tutorial/sqlite/README.md).

## Reusable source packages

Use **Bhash: Update Dependencies Lock** after intentionally changing dependency
metadata or local library contents. **Bhash: Restore Dependencies** fetches missing
exact Git snapshots without updating the lock; **Bhash: Verify Dependencies**
checks existing local/cached sources offline. These commands require workspace
trust, save Bhash buffers and refresh analysis on success. Ordinary analysis,
build and run do not fetch dependencies or modify locks. Dependency declarations
are highlighted in `bhash.project`; changes to `bhash.lock` refresh diagnostics.
Verified library sources participate in completion, auto-import, signature help
and navigation. Add external local-library folders to the workspace to observe
changes made outside the editor. See [the package guide](../../docs/source-packages.md)
and [Hindi tutorial](../../tutorial/packages/README.md).

## Projects and tasks

Run **Bhash: New Project** from the Command Palette. It creates a new directory
with `bhash.project` and `src/main.bhash`, without overwriting an existing folder.
Opening a file beneath a manifest automatically selects the nearest containing
project for analysis/build/run. Files outside the manifest's source roots are
standalone compilation units.

For a repeatable team build, add a VS Code task:

```json
{
  "version": "2.0.0",
  "tasks": [
    {
      "type": "bhash",
      "label": "Build Bhash JVM",
      "command": "build",
      "project": "bhash.project",
      "target": "jvm",
      "group": { "kind": "build", "isDefault": true }
    }
  ]
}
```

`project` is relative to that workspace folder. `target` accepts `jvm`, `clr`, `native`, or
`wasm-browser`; `command` accepts `build` or `run`. Browser programs must be built
and served over HTTP. CLR/browser builds require .NET 10; browser builds also
require the `wasm-tools` workload. A library-only project can be analyzed without
`मुख्य`; executable builds still require exactly one valid entry point.

## Settings

| Setting | Default | Purpose |
| --- | --- | --- |
| `bhash.javaPath` | `java` | Java executable on the extension host |
| `bhash.compilerPath` | bundled JAR | Optional absolute path to an alternate compiler |
| `bhash.target` | `jvm` | Default target for configured tasks |
| `bhash.trace.server` | `off` | LSP tracing for troubleshooting; verbose traces can contain source |

Java/compiler paths are machine settings. Highlighting and snippets work in
Restricted Mode; the server and tasks require workspace trust. Virtual file
systems have syntax/snippets only. Unsaved untitled documents have syntax/snippets
until saved to a `.bhash` path. No Java runtime is downloaded automatically.

## Current limits

The editor resolves declared types and common expression chains conservatively;
unknown or ambiguous receiver types produce no member suggestions. It is not a
complete semantic binding graph: semantic rename/references, formatting and source
debugging for JVM/CLR remain pending; Linux/WSL native debugging is described above.
Signature help lists overloads and selects a signature
with enough parameters; it does not promise full overload selection while a call
is incomplete. Generic member declarations remain a language limitation.

Syntax diagnostics are collected first. If syntax is invalid, semantic diagnostics
wait until it is repaired; completion can still use recovered declarations.
Declaration/import/generic-expansion failures can still stop at the first error.
Independent statement, function, constructor and field-initializer errors are
recovered without emitting an executable. See [diagnostic codes and behavior](../../docs/developer-experience.md).

Unchanged project indexes are reused. Changed projects are analyzed as a whole;
incremental semantic compilation is pending. Automatic imports search the current
compilation project, not other workspace roots or external binary libraries.
Builds use saved files. No code is executed during completion or diagnostics.

## Development

Use JDK 17+, Maven and Node.js 22 or later with its bundled npm. In WSL,
use Linux installations of Java, Node and npm (check `command -v node` and
`command -v npm`; they should not resolve under `/mnt/c/`).

From the repository root:

```sh
mvn package -DskipTests
npm ci --prefix editors/vscode
npm run build --prefix editors/vscode
npm test --prefix editors/vscode
npm run test:integration --prefix editors/vscode
npm run package --prefix editors/vscode
```

The extension pins minimatch 11.0.1 for GHSA-g3hv-75wg-m249. `npm ci` applies
`patches/vscode-languageclient+9.0.1.patch`: the client's three validity checks use
Minimatch's `comment`/`empty` flags instead of the removed `makeRe()` API; matching
still uses the patched library. Keep this patch until the client supports
minimatch 11 upstream. A failed patch stops installation.

Linux integration tests need an X display (CI uses `xvfb-run -a`). The tests launch
VS Code 1.95.3 by default, exercise the actual extension host and compiler task,
and can target another version with `VSCODE_VERSION`. The Java stdio protocol tests
also cover cross-file unsaved edits, aliases, multi-root isolation, diagnostic
clearing, and UTF-16 positions after supplementary Unicode characters.

The VSIX includes Apache-2.0 Bhash code, ANTLR BSD-3-Clause notices, LSP4J's
EDL-1.0/BSD-3-Clause license option, Gson/Error Prone Apache-2.0 notices, and
Microsoft's MIT client-library notices. See the included license files.

If `npm ci` says no lockfile exists, verify that
`editors/vscode/package-lock.json` is present and readable in your checkout.
The lockfile is committed; do not regenerate it just to bypass the error.
Try installing directly from the extension directory:

```sh
cd editors/vscode
node --version
npm --version
node -p "require('./package-lock.json').lockfileVersion"
npm ci
```

The lockfile version should be `3`. If the same error persists, inspect
`type -a node npm` and the npm debug log for the underlying load error.


## Native debugging on Windows x64

Version 0.9.0 extends native debugging to ordinary Windows VS Code on Windows 10 version 1903 or later. Install Java 17+
(JDK for JVM builds) and Microsoft's C/C++ extension on that host, leave
`bhash.native.gdbPath` empty, then run **Bhash: Debug Native**. The first debug launch
installs a separate checksum-pinned GDB/Python runtime; later launches reuse the
verified cache offline. No WSL, MSYS2, Python, LLVM or C development installation is
required. The existing managed compiler SDK is downloaded only if needed.

Click the gutter or press F9 on executable Bhash lines, then use F10/F11/Shift+F11
for stepping. Hindi stacks and read-only scalar/text/object/collection inspection
share the Linux implementation and GC safety contract. Windows/macOS build/run
support is unchanged; macOS debugger integration remains pending. See the
[Hindi debugger guide](../../docs/native-vscode-debug.md).

The Windows GDB process manifest enables UTF-8 for Hindi paths (Windows 10 1903+).
The manifest patch script is retained with the corresponding sources; debugger
instructions and other upstream binaries are unchanged.

### Windows debug terminals

Version 0.9.2 creates a dedicated integrated terminal for Bhash Windows debugging and starts Microsoft’s Windows debugger launcher directly. WSL, Git Bash, PowerShell, and custom default/automation profiles do not need to be changed. User and workspace terminal settings remain untouched. Existing `cppdbg` configurations with `bhashNative: true` automatically use this path on Windows; ordinary C/C++ sessions keep their existing adapters. Application output and interactive input remain in the integrated terminal.


## Bhash Watch expressions on Windows and Linux/WSL

Version 0.10.0 accepts Bhash syntax in the **Watch** pane and debugger hover. Pause at a
Bhash breakpoint, click **+** under Watch, and enter expressions such as
`वस्तु.अंक + अंक[१]`, `शब्द["साझा"].विरासत`, `सरणी[१]`, or `भिन्न * २.०`.
Fields, arrays/lists/maps/byte buffers, numeric and boolean operators, text equality,
`सत्य`/`असत्य`/`रिक्त`, ASCII/Devanagari literals and parentheses are supported.
Reference results remain expandable. Values refresh in the selected stopped frame.

Evaluation reads the paused process and computes in the debugger. Calls, assignments,
construction, casts, text concatenation, text indexing, static member access and
structural comparisons of distinct records are rejected. There are limits of 4,096
UTF-8 expression bytes, 256 syntax nodes, 48 nesting levels, 1 MiB of heap reads and
1,000 map entries per lookup. Missing variables/keys and invalid indices report errors.
The explicit **Debug Console** continues to accept advanced GDB/C syntax; it does not
use the Bhash Watch grammar. Inferior calls remain disabled. Conditional breakpoints
also retain GDB syntax.

Linux/WSL Bhash sessions now use type `bhash-native`; Windows retains
`bhash-native-windows`. Existing `cppdbg` configurations with `bhashNative: true` route
automatically; no terminal settings need changing. See the
[Hindi Watch reference](../../docs/native-vscode-debug.md#भाष-अभिव्यक्तियाँ-watch-expressions).
