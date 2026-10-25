# Bitwise operations (v0.6)

JVM, .NET CLR and browser .NET WebAssembly support the same seven operators on
`पूर्णांक`. Both binary operands, including a shift count, must be 64-bit integers;
`~` takes one integer. Results are integers. There are no implicit conversions or
bitwise Boolean overloads; use `&&`, `||` and `!` for Boolean logic.

| Operator | Meaning | Example | Result |
| --- | --- | --- | --- |
| `&` | Bitwise AND | `५ & ३` | `१` |
| `\|` | Bitwise OR | `५ \| २` | `७` |
| `^` | Bitwise XOR | `५ ^ ३` | `६` |
| `~` | Complement all 64 bits | `~०` | `-१` |
| `<<` | Left shift, discard high bits and fill low bits with zero | `१ << ३` | `८` |
| `>>` | Arithmetic right shift, fill high bits with the sign bit | `-८ >> २` | `-२` |
| `>>>` | Logical right shift, fill high bits with zero | `-१ >>> १` | `९२२३३७२०३६८५४७७५८०७` |

## Integer representation and shift counts

Integers remain signed 64-bit two's-complement values. A bitboard uses all 64 bits;
setting bit 63 produces a negative displayed value but preserves the entire bit
pattern. No new unsigned type or binary/hexadecimal literal syntax is introduced.

The effective shift distance is **`count & 63`**, in the range 0–63, for all three
shift operators. Counts are not range errors: 64 means zero, 65 means one, and
-1 means 63. Wide counts are masked before narrowing to a host count type.
A zero-distance shift preserves the original signed value, including `-१ >>> ६४`.

```text
कार्य मुख्य() -> शून्य {
    लिखो(१ << ६३);        // -९२२३३७२०३६८५४७७५८०८
    लिखो(-१ >> १);       // -१
    लिखो(-१ >>> १);      // ९२२३३७२०३६८५४७७५८०७
    लिखो(१ << ६४);       // १
    लिखो(१ << -१);       // -९२२३३७२०३६८५४७७५८०८
}
```

Operands evaluate once, from left to right. `&`, `|` and `^` always evaluate both
operands; `&&` and `||` retain their short-circuit behavior.

## Precedence and parsing

Highest to lowest: grouping/calls/member access; unary `! ~ + -`; `* / %`;
`+ -`; `<< >> >>>`; `< <= > >=`; `== !=`; `&`; `^`; `|`; `&&`; `||`.
Binary operators associate left to right. Thus `१ + १ << ३` is `(१ + १) << ३`,
and `३२ >> १ >> २` is `(३२ >> १) >> २`.

Parenthesize a mask before comparing it:

```text
यदि ((पट्ट & मुखौटा) != ०) { लिखो("बिट मौजूद है"); }
```

Without those parentheses, `पट्ट & मुखौटा != ०` compares `मुखौटा` to zero first,
then attempts integer AND with a Boolean, which is a type error.

Characters within a shift operator must be adjacent: `८ >> १` is valid;
`८ > > १` and `८ >/*comment*/>१` are invalid. Nested generic closing angles
remain valid, including `पात्र<पात्र<पूर्णांक>>` and deeper `>>>` closers.
Expression type arguments must be followed by a call or static receiver, so
`क < ख >> १` compares `क` with `(ख >> १)` rather than treating `ख` as a type.
The parser recognizes contiguous shift operators in expression context while
retaining separate angle tokens for generic types. Package lowering and generic
specialization preserve the joined operator spelling and original source locations.

Compound assignments such as `&=` or `<<=` are not part of this addition. Write
`पट्ट = पट्ट & मुखौटा;`. Selection labels remain literals, not bitwise expressions.

## Run the bitboard example

Update and rebuild the compiler before using the new syntax:

```bash
git pull --ff-only
mvn clean package -DskipTests
java -jar target/bhash-compiler-0.1.0-SNAPSHOT.jar run examples/bitboards.bhash
java -jar target/bhash-compiler-0.1.0-SNAPSHOT.jar run examples/bitboards.bhash --target clr
java -jar target/bhash-compiler-0.1.0-SNAPSHOT.jar compile examples/bitboards.bhash --target wasm-browser -o target/bitboards-web
```

The [example](../examples/bitboards.bhash) sets bits 0 and 63, tests both squares,
counts occupied squares, toggles bit 0, and demonstrates both right shifts:

```text
-९२२३३७२०३६८५४७७५८०७
सत्य सत्य
२
१ १
-१ -१ ९२२३३७२०३६८५४७७५८०७
१ २
```

Serve browser output over HTTP as described in [the browser guide](wasm-browser.md).
JVM compilation requires a full JDK 17+; CLR/browser toolchain requirements are
unchanged.

As of v0.9, `बाइट` and `लघुपूर्णांक` operands promote to signed 64-bit
`पूर्णांक` before bitwise operations. The existing 64-bit shift/complement
semantics apply; explicitly narrow a result with `परिवर्तित<बाइट>` or
`परिवर्तित<लघुपूर्णांक>` when storing it in a smaller type. Narrowing is checked.
