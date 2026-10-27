# Changelog

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
