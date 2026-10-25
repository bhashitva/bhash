# Bhash (भाष) for Visual Studio Code

Write Bhash on Windows, macOS, Linux, WSL, or a remote VS Code host. This extension
bundles the Bhash compiler and a Java language server. Source analysis stays on
the extension host; it does not send code to the public playground or execute
programs while you edit.

## Install

1. Download `bhash-vscode-0.1.1.vsix` from the **Bhash VS Code extension** GitHub
   Actions artifact for a successful main-branch build.
2. In VS Code choose **Extensions → … → Install from VSIX…** and select the file.
3. Install **Java 17 or later** on the computer running the extension host.
   A full JDK is needed for JVM build/run. In WSL, SSH, or Codespaces, install Java
   on that remote host and install the extension there.
4. Open a folder containing `bhash.project`, or open a standalone `.bhash` file.
   Trust the workspace to enable analysis and compiler tasks.

No Maven or Node installation is needed to use the packaged extension. There is
no Marketplace listing yet; `bhashitva` is the intended publisher identifier, not
a claim that a Marketplace publisher has been registered.

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
- Hover and F12 for lexical declarations and imported/same-package top-level
  types/functions. Overloaded function navigation lists declarations; it does not
  select the resolved overload. Outline and workspace symbol search include types,
  records, enums, interfaces, functions, and members.
- Independent manifests and multi-root workspaces remain separate compilation
  units. Discovery ignores `.git`, `node_modules`, `target`, `build`, and `dist`.
- Build JVM, CLR, or browser WebAssembly; run JVM/CLR in an integrated terminal
  with console input. File paths are passed as process arguments, never a shell
  command. Builds use saved files and prompt for the target.
- **Bhash: New Project**, **Bhash: Restart Language Server**, and
  **Bhash: Open Browser Playground** commands.

`स्थिर` means a constant binding. Static class members use **`साझा`**.

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

`project` is relative to that workspace folder. `target` accepts `jvm`, `clr`, or
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

This is the first compiler-backed tooling release. Arbitrary receiver/member type
inference, automatic import edits, call signature help, semantic rename/references,
formatting, and source debugging are not implemented. F12 deliberately avoids
claiming a binding for `object.member`. The checker reports all syntax errors and
the first semantic failure in each project per pass. Unchanged project indexes are reused. Changed projects are reanalyzed as a whole
rather than doing incremental semantic compilation;
large repositories should measure latency before adopting it as their only IDE.
Nested project source roots should not overlap: each manifest owns its source
files. External binary package management is not added by the extension.

## Development

From the repository root:

```sh
mvn package -DskipTests
npm ci --prefix editors/vscode
npm run build --prefix editors/vscode
npm test --prefix editors/vscode
npm run test:integration --prefix editors/vscode
npm run package --prefix editors/vscode
```

Linux integration tests need an X display (CI uses `xvfb-run -a`). The tests launch
VS Code 1.95.3 by default, exercise the actual extension host and compiler task,
and can target another version with `VSCODE_VERSION`. The Java stdio protocol tests
also cover cross-file unsaved edits, aliases, multi-root isolation, diagnostic
clearing, and UTF-16 positions after supplementary Unicode characters.

The VSIX includes Apache-2.0 Bhash code, ANTLR BSD-3-Clause notices, LSP4J's
EDL-1.0/BSD-3-Clause license option, Gson/Error Prone Apache-2.0 notices, and
Microsoft's MIT client-library notices. See the included license files.
