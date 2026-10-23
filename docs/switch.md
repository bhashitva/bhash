# Selection statements (v0.4)

`चयन` selects a section by value, `विकल्प` introduces a case, and `शेष` supplies an
optional default. This statement works on JVM, CLR and browser WebAssembly.

```text
चयन (संख्या) {
    विकल्प ०:
        लिखो("शून्य");
        विराम;
    विकल्प १:
    विकल्प २:
        लिखो("एक या दो");
    शेष:
        लिखो("अन्य");
}
```

For `संख्या = १`, this prints only `एक या दो`. Sections do not fall through, so
`विराम` at the end of a section is optional. Consecutive `विकल्प` labels share one
body. A section may contain ordinary statements or an explicit braced block.

## Values and labels

- The selector is evaluated exactly once, even for an empty selection.
- Supported selector types are `पूर्णांक`, `पाठ` and `तार्किक`. Integers use their full
  signed 64-bit range. Reals, objects/interfaces, `रिक्त` and void results are rejected.
- A label must be a literal of the selector's exact type: a signed/unsigned integer
  literal, a string literal, `सत्य`, or `असत्य`. Integer `+` and `-` signs are allowed.
  Variables, constant bindings, arithmetic expressions, ranges and patterns are not labels.
- Integer labels are compared by numeric value. `१`, `1` and `+१` are duplicates;
  `-०` and `०` are duplicates. Range checks include the minimum signed integer literal.
- Text labels compare exact decoded Unicode values, using the same ordinal/value equality
  as ordinary text comparisons. Escaped and directly written forms of the same string
  are duplicates. Strings are not NFC-normalized. Unpaired surrogate escapes are rejected.
- Duplicate labels and multiple `शेष` sections are compile-time errors.
- `शेष` can appear anywhere, but runs only when no case matches. Without it, an unmatched
  value does nothing. An empty selection is valid. `शेष` does not group with case labels;
  an empty case before `शेष` does nothing when matched.

## Scope and control flow

Each section has its own scope. Two sections may declare a local with the same name;
those bindings are unavailable outside their section. Existing shadowing rules apply.

`विराम` exits the nearest enclosing `चयन` or `जबतक`. A loop nested in a selection
therefore receives its own `विराम`; a selection nested in a loop receives its own.
`जारी` always continues the nearest enclosing loop, including when used inside a selection.
A selection without an enclosing loop cannot use `जारी`. Neither keyword is valid as a
standalone statement outside an eligible construct.

`लौटाओ` exits the enclosing function as usual. Return-path analysis recognizes a selection
as returning on every path only when it has a `शेष` section and every section returns.
A section containing a reachable `विराम` can complete normally. Even a boolean selection
with both boolean labels requires `शेष` for this conservative return-path analysis.
Statements following an unconditional return, break or continue in a section are unreachable
and rejected. Code after a selection whose every section returns is also unreachable.

## Example

[The console example](../examples/switch-console.bhash) reads an integer, uses grouped
integer labels to return a description, then selects again using the returned text.

```sh
mvn package
java -jar target/bhash-compiler-0.1.0-SNAPSHOT.jar run examples/switch-console.bhash
java -jar target/bhash-compiler-0.1.0-SNAPSHOT.jar run examples/switch-console.bhash --target clr
java -jar target/bhash-compiler-0.1.0-SNAPSHOT.jar compile examples/switch-console.bhash --target wasm-browser -o target/switch-web
```

Serve the browser output over HTTP as described in [browser target](wasm-browser.md).
The new words `चयन`, `विकल्प` and `शेष` are reserved in v0.4; rename existing identifiers
with those exact spellings. Longer identifiers such as `शेषराशि` remain valid.
