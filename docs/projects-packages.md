# Multi-file projects, packages and imports (v0.5)

All three implemented targets—JVM, .NET CLR and browser .NET WebAssembly—use
one project resolver and semantic checker. They accept the same multi-file source.
Existing single-file commands remain supported.

## Project manifest and commands

Put a UTF-8 `bhash.project` file at the project root:

```properties
# A filename for default build outputs; optional, defaults to the folder name.
name=payroll
# Comma-separated source folders relative to this manifest; defaults to src.
sources=src,shared
```

The format is Java properties, read as UTF-8. Use forward slashes in paths;
backslashes introduce properties escapes. Unknown and duplicate keys, missing or
empty source folders, overlapping roots that discover the same source file, and
projects without `.bhash` files are errors. Roots must stay inside the project.
Symbolic links inside source roots are rejected; source discovery does not follow
linked directories. Only files ending in `.bhash` are compilation units, recursively
and in sorted relative-path order. Files outside declared roots are not compiled.

Commands accept either the directory containing `bhash.project`, the manifest
itself, or one `.bhash` file. A single-file command does not implicitly discover
neighboring files or a parent project's manifest.

From the Bhash repository root:

```bash
# Syntax-check every project file; this does not resolve imports or check types.
java -jar target/bhash-compiler-0.1.0-SNAPSHOT.jar check examples/multi-file

# Compile and run with JVM or CLR.
java -jar target/bhash-compiler-0.1.0-SNAPSHOT.jar run examples/multi-file
java -jar target/bhash-compiler-0.1.0-SNAPSHOT.jar run examples/multi-file --target clr

# Compile without immediately executing.
java -jar target/bhash-compiler-0.1.0-SNAPSHOT.jar compile examples/multi-file --target jvm -o target/multi-file.jar
java -jar target/bhash-compiler-0.1.0-SNAPSHOT.jar compile examples/multi-file --target clr -o target/multi-file-clr
java -jar target/bhash-compiler-0.1.0-SNAPSHOT.jar compile examples/multi-file --target wasm-browser -o target/multi-file-web
```

Without `-o`, project outputs go under `<project>/build/`: `<name>.jar`,
`<name>-clr/` or `<name>-web/`. JVM is still the default target. Browser output
must be served over HTTP; see [browser instructions](wasm-browser.md).
`parse` continues to accept one source file only.

A build must not overwrite any discovered source file or the manifest. A generated
output directory must not replace a source root or a directory containing an input.
Existing backend rules also protect unmanaged CLR/browser output folders.

## Packages and exported declarations

A file may begin with exactly one package declaration:

```text
संकुल संस्था.पहचान;

सार्वजनिक वर्ग ग्राहक {
    निजी मान नाम: पाठ;
    निर्माता(नाम: पाठ) { यह.नाम = नाम; }
    सार्वजनिक कार्य परिचय() -> पाठ { लौटाओ यह.नाम; }
}
```

Files without a package declaration belong to the unnamed package. All files
compiled in the same package can refer to each other's declarations, regardless
of folder or declaration order. Package declarations, rather than directory paths,
establish type identity. Mirroring packages with directories is recommended but
not enforced. A package cannot contain duplicate type names; two different packages
may each declare a class named `ग्राहक`.

Top-level classes, interfaces and functions are package-private unless marked
`सार्वजनिक`. A declaration used from another package must be public, whether
referenced through an import or its fully qualified name. `निजी` and `संरक्षित`
remain member modifiers, not top-level visibility modifiers. Existing member
visibility and override rules are unchanged; public top-level visibility does not
make private members public.

Top-level function overloads may span files in the same package. Every overload
of a given function name must use the same top-level visibility. Duplicate
parameter signatures remain errors; return types do not distinguish overloads.
Built-in function names remain reserved in every package.

An executable project must have exactly one global `कार्य मुख्य() -> शून्य`
across all packages and source roots. Each file does not need an entry point.
The entry point may be package-private; it is not imported under a short alias.
This version builds executables, not separately compiled library artifacts.

## Imports, aliases and qualified references

Imports follow the optional package declaration and precede declarations:

```text
संकुल संस्था.विक्रय;
आयात संस्था.पहचान.ग्राहक;

सार्वजनिक वर्ग आदेश {
    निजी मान खरीदार: ग्राहक;
    निर्माता(व्यक्ति: ग्राहक) { यह.खरीदार = व्यक्ति; }
    कार्य ग्राहकनाम() -> पाठ { लौटाओ यह.खरीदार.परिचय(); }
}
```

Imports name a type or an entire top-level function overload group. They are
lexical to the importing file: an import in one file does not expose that name in
another file in the same package. There are no wildcard imports, relative file
imports, re-exports, or import-time execution. Missing or inaccessible imports
are errors even when unused.

Aliases distinguish equal short names:

```text
आयात संस्था.पहचान.ग्राहक उपनाम स्थानीयग्राहक;
आयात भागीदार.पहचान.ग्राहक उपनाम बाहरीग्राहक;
```

Duplicate aliases/imports and imports conflicting with another declaration in
the current package are errors. An alias cannot replace a built-in or `मुख्य`.
Within type annotations, type parameters take precedence over imported type names.
Within expressions, local bindings and members take precedence over imported
names, matching existing lexical shadowing rules. A visible short type name also
takes precedence over an equally named root package in a static receiver expression.

Qualified references need no import:

```text
स्थिर ग्राहकका: संस्था.पहचान.ग्राहक = नया संस्था.पहचान.ग्राहक("मनोज");
लिखो(संस्था.गणित.उपयोगिता.उत्तर());
लिखो(संस्था.गणित.प्रतिलिपि<पूर्णांक>(४२));
```

The second line assumes a public class `उपयोगिता` with a static method `उत्तर`;
the third assumes a public generic top-level function `प्रतिलिपि`.
Qualified generic type receivers, such as `संस्था.संग्रह.पात्र<पूर्णांक>.गिनती`,
are supported. Generic member methods remain unsupported.
Names and every package segment use the existing Devanagari identifier profile;
NFC-equivalent spellings identify the same package/type/function. Keywords cannot
be package segments. Strings and filesystem names are not rewritten or normalized.

## Cross-file type checking and runtime identity

The compiler parses each file independently, builds a project-wide declaration
table, checks imports and visibility, resolves type references, then specializes
generics and checks signatures, method bodies, overloads and control flow. Files
can contain mutual object references; inheritance cycles remain errors.

The same fully qualified type has one runtime identity throughout the build.
Importing it under two aliases does not create two types or copy an object.
Generic specialization is shared across files: the same closed generic type has
one specialization and one set of static fields. Diagnostics retain the original
file, line and column through package lowering and generic expansion.

The [seven-file factorial project](../examples/multi-file/) demonstrates a generic
interface, inheritance and overriding across packages, a generic object container,
a package-private helper in another file, function overloads split between files,
import aliases, qualified static access and shared object identity. For input `५`:

```text
० से २० तक एक पूर्णांक लिखें:
गुणनफल: १२०
सत्य १
संख्या ठीक
```

## Scope of this milestone

Source roots form one executable build. They can contain shared Bhash source
checked into the project, but this version does not fetch dependencies or consume
precompiled Bhash libraries, JARs, .NET assemblies or NuGet packages. There is no
package registry, dependency lockfile, separate compilation ABI or incremental
build cache yet. Package/import names do not fetch network resources.

A build lowers the resolved project into one host program and executable artifact;
it does not generate an independently reusable host assembly for each Bhash file.
Future source-package distribution and native Java/.NET interoperability need
explicit dependency metadata and target compatibility rules.
