# Changelog

## 0.25.2

- Bundle Bhash compiler 0.25.2 with Android native variable debug information.

## 0.24.2

- Bundle the native HTTP runtime fix for restarting on the same port on Linux and macOS.
- Verify Linux restart with the same application behind Caddy, Nginx, and Apache.

## 0.24.1

- Recover Android Run when the requested app window is visible after the launch wait times out.
- Verify timeout recovery and app-scoped Logcat on the Android API 29 emulator.

## 0.24.0

- Add **Bhash: New Android Project** with Hindi Offline Task List and Hello World starters.
- Generate projects with the bundled compiler and open them in a new VS Code window.
- Cover Hindi folder names, cancellation, and protection of existing projects in the wizard tests.

## 0.23.0

- Check the Android SDK, NDK and JDK, and choose a connected device from the Command Palette on Windows and Linux x86-64.
- Build a debug APK, install and launch it, and stream Logcat for the running app.
- Bundle the compiler with the Android starter, signed APK and App Bundle builds, and generated Kotlin and JNI bindings.
- Verify the extension workflow end to end on the Android API 29 emulator.

## 0.22.1

- Link to the Hindi book and standard-library API reference from the extension overview.
- Add Command Palette commands to open the book and API reference.
- Bundle the Bhash 0.22.0 compiler.

## 0.22.0

- Bundle the compiler with positional formatting, alignment, numeric precision, and generic sets.
- Show missing-semicolon diagnostics at the insertion point with direct Hindi guidance.
- Include links to the Hindi formatting and Set tutorials in the extension overview.

## 0.21.0

- Bundle Bhash command-line argument support from [PR #90](https://github.com/bhashitva/bhash/pull/90).
- Accept one optional `पाठ[]` parameter on synchronous and asynchronous `मुख्य`.
- See the [command-line guide](https://github.com/bhashitva/bhash/blob/main/docs/cli.md#command-line-arguments) for launching with arguments.

## 0.20.0

- Bundle configurable async HTTP and WebSocket capacity from [PR #88](https://github.com/bhashitva/bhash/pull/88).
- Keep the existing connection defaults. See the [configuration guide](https://github.com/bhashitva/bhash/blob/main/docs/websocket.md).
- Refresh the Marketplace overview and link to the standalone command-line compiler.

## 0.19.0

- Bundle the compiler with the asynchronous HTTP server and RFC 6455 WebSocket server library for JVM, .NET, and native applications.
- Use the new server declarations through compiler-backed editor assistance. HTTP/2 Extended CONNECT is available through the separately deployed TLS gateway.
- Try the [WebSocket examples](https://github.com/bhashitva/bhash/blob/main/docs/websocket.md) and the [Hindi ORM tutorial](https://github.com/bhashitva/bhash/blob/main/tutorial/orm/README.md) from the repository.

## 0.18.0

- Bundle the Unicode 18 identifier profile from [PR #72](https://github.com/bhashitva/bhash/pull/72), with canonical name matching and contextual ZWJ/ZWNJ handling.
- Highlight invalid joiners at their source location and offer a quick fix. Completion and native Watch expressions use the same identifier rules.
- Read the [Hindi identifier guide and runnable example](https://github.com/bhashitva/bhash/tree/main/tutorial/unicode-identifiers).

## 0.17.0

- Bundle the compiler with async/await, task cancellation, timers and awaited resource cleanup.
- Add asynchronous HTTP, SQLite and PostgreSQL on JVM, .NET and native targets.
- Update the native debugger to support runtime ABI 17.

## 0.16.0

- Bundle the merged PR #63 compiler with typed function values, lambdas and lexical closures on JVM, CLR/WASM and native targets.
- Support higher-order calls, named function references, escaping/nested closures and shared mutable captures with native GC safety.
- Add lambda and function-value snippets, editor-host regression coverage and links to the Hindi callback/closure tutorial.
- Use क्रमानुदेश consistently in the bundled documentation and compiler messages from PR #62.
- Callbacks are synchronous; async scheduling and automatic conversion to existing callback interfaces remain separate features.

## 0.15.0

- Bundle the merged PR #61 compiler with shared SQLite/PostgreSQL connection, statement and transaction APIs on JVM, CLR and native.
- Include parameterized PostgreSQL access, verified TLS by default, typed values, exact NUMERIC text, sanitized errors and scoped resource cleanup.
- Add ordered, checksum-verified SQLite/PostgreSQL migrations with transactional rollback and Hindi examples.
- Expose provider and migration declarations through completion, signature help, navigation and automatic imports.
- Bundle the verified JDBC payload for JVM programs; document the external libpq 14+ client runtime required by CLR/native PostgreSQL applications. Browser/WASM database access remains unsupported.

## 0.14.0

- Replace the function declaration keyword with `फलन` and use collection-first iteration: `प्रत्येक (संग्रह में तत्व: प्रकार)`; old declaration/loop forms are no longer accepted.
- Update snippets, syntax highlighting, project templates, compiler diagnostics, playground snippets and all bundled standard-library declarations.
- Bundle the compiler from merged PR #59, with matching completion, navigation, build/run and native debugging support.
- See [syntax migration](../../docs/hindi-syntax.md) before rebuilding existing source projects.

## 0.13.0

- Bundle the merged authentication and sessions compiler/library milestone for JVM, CLR and native Linux/Windows x64 and macOS ARM64.
- Include provider-backed PBKDF2 password hashing, cryptographic tokens, secure cookies, bounded sessions, expiry/rotation/revocation, role authorization, CSRF protection and login attempt limits.
- Make the authentication APIs available to completion, signature help, automatic imports and declaration navigation, with extension-host coverage.
- Link the Hindi authentication reference and the HTTP/SQLite login and account-scoped notes demonstration.
- Explain HTTPS deployment, process-local session storage, browser/WASM restrictions and the OpenSSL 3 runtime requirement for native Linux password operations.
- Include the merged Windows debugger probe-log cleanup fix, preserving startup/timeout diagnostics during transient file locks.

## 0.12.0

- Bundle the merged compiler with the Bhash HTTP server and small web application framework on JVM, CLR and native Linux/Windows x64 and macOS ARM64.
- Include routing, path/query values, UTF-8 JSON, validation, bounded request handling, logging, resource cleanup and cooperative shutdown.
- Make the new web APIs available to compiler-backed completion, signature help, imports and declaration navigation.
- Link the Hindi web application walkthrough, SQLite task-board demo, combined HTTP/SQLite cache example and deployment templates.
- Document the initial server limits: serialized handlers, fixed-length bodies up to 1 MiB, reverse-proxy TLS, and no browser/WASM listeners.

## 0.11.0

- Bundle the latest compiler with collection algorithms, streams, paths/directories, scoped resource cleanup, configuration, logging and the Bhash test runner.
- Include HTTP networking with consistent target restrictions and errors.
- Add SQLite access on JVM, CLR and native: parameterized SQL, typed values, transactions and automatic resource cleanup, including schema-change metadata fixes.
- Bundle pinned SQLite runtime assets for supported desktop targets; no separate SQLite installation is required. Browser SQLite access reports an explicit unsupported error.
- Link the Hindi application-foundations, networking and SQLite tutorials from the extension documentation.

## 0.10.1

- Register the native tools task provider and task schema so Windows custom-terminal setup/check/repair tasks can execute and configured tasks can resolve without changing VS Code settings.
- Add extension-host coverage for configured native tools tasks and actual CustomExecution dispatch.


## 0.10.0

- Evaluate read-only Bhash expressions in Watch and hover on Windows x64 and Linux/WSL: Hindi names, fields, collection indices, scalar arithmetic, comparisons and short-circuit logic.
- Keep evaluation inside GDB Python with bounded reads and no inferior calls, writes, allocations or cached heap references. Report unsupported expressions and invalid indices clearly.
- Validate repeated Watch evaluation before and after forced GC, including unchanged allocation counters and exact GC roots, plus actual Windows/Linux VS Code sessions.

## 0.9.3

- Replace affected minimatch versions with 11.0.1 for the language client and extension packaging tools (GHSA-g3hv-75wg-m249).
- Update the packaging tool and compatible security patches in its dependencies; retain VS Code 1.95 and Windows/Linux/macOS support.

## 0.9.2

- Launch Windows native debug sessions in a dedicated integrated terminal using Microsoft’s Windows debugger launcher directly, independent of WSL/Git Bash/default or automation profiles.
- Preserve terminal settings, interactive input, source stepping, and existing Bhash launch configurations. Linux/WSL debugging continues through cppdbg.

## 0.9.1

- Allow a configurable five-minute GDB startup wait during setup and preflight, with cancellation and progress messages for first-run security scans.
- Explain startup timeouts separately from missing debugger/Python failures. Preserve checksum verification and security-software decisions.


## 0.9.0

- Add native Windows x64 debugging with automatic, separate verified GDB/Python setup.
- Support source breakpoints, stepping, Hindi stacks and read-only heap inspection.
- Validate Windows Unicode paths, exact GC roots, offline debugger reuse, failed repair,
  real VS Code sessions and a clean Windows container without WSL/development tools.


## 0.8.1

- Enable normal gutter and F9 breakpoints in Bhash source files without enabling breakpoints for every language.
- Test the editor Toggle Breakpoint command with the global override disabled, alongside native GDB debugging.

## 0.8.0

- Add Linux/WSL native debugging through VS Code's Microsoft C/C++ GDB adapter: source breakpoints, stepping, Hindi call stacks and expandable Bhash variables.
- Build a fresh `--debug` executable for each session, check GDB/Python setup, reuse managed LLVM setup, support cancellation and remove session outputs on termination.
- Add read-only UTF-8 text/object/collection/boxing inspection and real GDB/VS Code tests across forced collections. GDB and Microsoft C/C++ are separate debugger prerequisites.

## 0.7.1

- Add a Discord community/support link and Command Palette shortcut.
- Help Bhash users collaborate and contact Bhashitva support.

## 0.7.0

- Add macOS 14+ Apple Silicon Native build/run and a pinned managed LLVM 18 SDK.
- Check Apple Command Line Tools before setup; Apple SDK/linker remain local prerequisites.
- Support cancellation of native process groups on macOS.
- Bundle macOS ARM64 compilation, release inspection, packaging and separate debug symbols.

## 0.6.4

- Prevent native LLVM wrapper/linker subprocesses from creating console windows: invoke the real Windows compiler and GNU linker directly, keeping compiler jobs integrated.
- Keep UTF-8 input/output and the direct integrated terminal for all targets.

## 0.6.3

- Run Windows tasks directly from the extension into the integrated terminal, removing the desktop Code.exe and PowerShell helper processes.
- Preserve UTF-8 output, line input, backspace, cancellation and exit codes with an extension-owned terminal.

## 0.6.2

- Configure the Windows task terminal for UTF-8 on JVM, CLR and Native targets.
- Suppress separate console windows when the task launcher starts Java subprocesses.
- Replace the displayed encoded PowerShell command with a readable command; task requests remain data in the environment.

## 0.6.1

- Run Windows native tasks through a waiting console launcher so output, console input, and exit status remain attached to the task.
- Set Node mode inside the launcher and pass task requests as UTF-8 base64 data to preserve paths and avoid shell interpretation.
- Test the launcher with desktop VS Code, delayed output, Hindi text, standard input, and failing exit codes.

## 0.6.0

- Native build/run on Windows 10+ x64, without WSL or Visual Studio.
- Automatic pinned LLVM 18/UCRT SDK setup, cache reuse and repair on Windows.
- Windows `.exe` output, UTF-8 console and Unicode file paths, static dependency inspection, and reproducible ZIP application packages.


## 0.5.2 — 2026-10-29

- Support managed native tools on Ubuntu 22.04/WSL with glibc 2.35 using a pinned Jammy LLVM 18 SDK.
- Verify all bundled host binaries meet that baseline, and test clean Ubuntu 22.04 and 24.04 hosts, online setup, offline reuse, and real VS Code tasks.
- Report the detected glibc version in unsupported-host errors.

## 0.5.1 — 2026-10-29

- Restore the Hindi display name and language alias in Marketplace/package metadata.
- Reject packaging when the Hindi name or alias is incorrectly encoded.

## 0.5.0 — 2026-10-29

- Add Native build/run choices and configured native tasks on Linux x86-64 and WSL.
- Automatically install a pinned, checksum-verified LLVM 18 SDK from permanent GitHub Release assets; reuse the CLI cache offline.
- Add setup, check, and repair commands, optional system toolchain mode, and Linux/WSL setup guidance.
- Keep task discovery and untrusted workspaces free of downloads; preserve terminal console input.

## 0.4.1 — 2026-10-27

- Resolve documentation links relative to the extension subdirectory so Marketplace links to the package guide and Hindi tutorials open correctly.

## 0.4.0 — 2026-10-27

- Bundle the compiler with versioned Bhash source packages, relative local dependencies, exact Git revisions, and reproducible integrity lockfiles.
- Add Update Dependencies Lock, Restore Dependencies, and Verify Dependencies commands in trusted workspaces.
- Include verified dependency sources in completion, signature help, automatic imports, diagnostics, and F12 navigation.
- Watch lockfile changes and keep ordinary builds and editor analysis offline.
- Fix dependency resolution through Windows short folder names while preserving physical-path safety checks.

## 0.3.0 — 2026-10-26

- Bundle the first standard library: text, math, seeded random numbers, JSON, UTC time, and desktop file I/O.
- Add standard-library member completion, signatures, automatic imports and readable F12 declarations.
- Preserve the browser file-access boundary through capability checks and catchable unsupported-operation errors.


## 0.2.0 — 2026-10-26

- Report multiple independent semantic errors with stable diagnostic codes, Hindi
  guidance and retained English details; preserve source locations and limit recovery.
- Add typed member completion for classes/inheritance/generics, records, enums,
  arrays/lists/maps and byte buffers, respecting visibility and static receivers.
- Add function/method/constructor signature help, overload lists and active arguments.
- Add automatic import edits on completion and import Quick Fixes for project declarations.
- Extend receiver member hover/navigation and preserve completion in incomplete buffers.
- Keep the existing JVM/CLR/browser build tasks and bundled compiler; no language syntax changes.


## 0.1.2

- Add a Devanagari compiler icon to the extension listing.

## 0.1.1

- Fix Linux/WSL browser output permissions so a separate HTTP-server user can read generated WASM bundles.

## 0.1.0

Initial VS Code extension: Devanagari highlighting/snippets; local Java LSP with
project-aware diagnostics, completion, outline, hover and declaration navigation;
JVM/CLR/browser build tasks; console run tasks; project scaffolding; workspace
trust support; bundled compiler and offline installable VSIX.
