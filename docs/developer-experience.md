# Diagnostics and editor assistance (extension 0.2.0)

This release changes compiler feedback and editor assistance; the Bhash language
syntax and the JVM/CLR/browser target contract remain unchanged.

## Multiple errors in one pass

The shared managed checker collects up to **50** independent errors across
statements, functions, constructors, field initializers and source files. For example:

```bhash
कार्य मुख्य() -> शून्य {
    लिखो(अज्ञात);
    स्थिर क: पूर्णांक = १;
    क = २;
}
```

Both the unknown name (`BH2001`) and immutable assignment (`BH4001`) are reported.
No executable is produced when an error exists. Recovery restores lexical scopes
and control-flow stacks. A bad initializer with a known declared type retains
that binding for analysis so later uses do not invent undefined-variable errors.
A failed body does not also receive a speculative missing-return error.

Syntax errors are collected before semantics. Incomplete syntax prevents semantic
checking; completion can still use recovered declarations. Import/declaration graph
and generic expansion failures remain fatal to that semantic pass. Recovery is
bounded and intentionally does not try to analyze an inconsistent type graph.

## Public diagnostic codes

Messages retain their source path, 1-based line and code-point column:

```text
main.bhash:2:11: [BH2001] यह नाम घोषित नहीं है। ... — Undefined variable: अज्ञात
```

| Code | Meaning |
| --- | --- |
| BH1001 | Syntax/lexical error |
| BH2001 | Undefined variable |
| BH2002 | Unknown type/class |
| BH2003 | Unknown import |
| BH2004 | Undefined member/function |
| BH3001 | Expected/actual type mismatch |
| BH4001 | Assignment to an immutable binding/field |
| BH4002 | Return value/path error |
| BH4003 | Invalid/unreachable control flow |
| BH5001 | Inaccessible declaration/member |
| BH6001 | Duplicate, reserved or conflicting declaration |
| BH7001 | Argument/overload mismatch or ambiguity |
| BH9001 | Other semantic constraint; read detailed message |

Codes are stable categories, not unique IDs for every individual language rule.
CLI, language server and the compilation service share the messages. LSP diagnostics
also populate the protocol's `code` field. IDE ranges convert code-point columns
to UTF-16 correctly, including text following supplementary Unicode characters.
Toolchain/environment failures retain their own messages.

## VS Code

- Type `वस्तु.` to see members of its declared type; inherited generic types are
  substituted. Static receivers show shared members, instances show instance members.
- Visibility filtering includes private/protected access. Lists/maps, arrays, byte
  buffers, record fields and enum values/properties have typed suggestions.
- Common chains such as `मानचित्र.लो("नाम")[०].` resolve through declared return
  and element types. Unknown/ambiguous expressions produce no invented members.
- `(` and `,` trigger signature help, including parameter types, overload lists and
  the active argument. Inner calls, collection literals and indexing do not count
  as outer argument separators. The displayed active signature initially favors
  an overload with enough parameters; incomplete calls do not promise overload binding.
- Selecting an unimported public project type/function inserts its short name and
  an `आयात` edit. Existing aliases, package declarations, CRLF, BOM and conflicting
  names are respected. If two packages export a name, choose the suggestion whose
  detail identifies the intended package. No alias is silently invented.
- The lightbulb offers an import Quick Fix for a matching unresolved name. Automatic
  imports use only the current project, including unsaved buffers; they do not fetch
  packages, execute code or cross independent workspace project boundaries.
- Hover/F12 can follow resolved members; overload navigation lists declarations.

Semantic rename/references, source debugging, full receiver inference, incremental
semantic compilation and a separate compiler-wide typed AST remain future work.

## Playground

After Run, compiler diagnostics appear as inline marks and an error list. Select
an error button to focus its location. The plain compiler log remains visible for
unlocated/toolchain failures. Editing clears old inline marks; results from a build
whose source changed do not attach stale locations. Statements mode uses the
service's existing line adjustment for its generated `मुख्य` wrapper.

This is error feedback after an explicit Run, not a background language server.
The browser does not submit source on each keystroke. Deploy the new frontend and
compiler/service together to enable the improvements on a hosted playground.

## Release and validation

The VS Code package version is **0.2.0**; the compiler Maven artifact keeps its
existing `0.1.0-SNAPSHOT` name. Build with Maven, run extension tests, then package:

```sh
mvn clean verify
npm ci --prefix editors/vscode
npm test --prefix editors/vscode
npm run package --prefix editors/vscode
npm ci --prefix playground
npm test --prefix playground
npm run build --prefix playground
```

The VSIX is `target/bhash-vscode-0.2.0.vsix`. Publishing the Marketplace update and
redeploying a hosted playground are separate deployment actions; a source commit
or successful package build alone does not update either live installation.
