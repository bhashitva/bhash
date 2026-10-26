# Changelog

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
