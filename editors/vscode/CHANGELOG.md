# Changelog

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
