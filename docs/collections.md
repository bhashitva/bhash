# Arrays, lists, maps and iteration (v0.7)

These features share one type checker and the same behavior on JVM, .NET CLR and
browser .NET WebAssembly. The experimental Linux native target also supports
the [integer, boolean, text and reference subset](native-collections.md). `प्रत्येक` and `में` are new reserved keywords.
`सरणी`, `सूची` and `मानचित्र` are predeclared generic type names; user types and
import aliases cannot replace them. Generic type parameters can shadow type names.

## Arrays

Arrays have fixed length, zero-based indexing and an explicit element type:

```text
स्थिर अंक: पूर्णांक[] = नया पूर्णांक[३];
अंक[०] = ७;
अंक[१] = ११;
लिखो(अंक[०], अंक.लंबाई);  // ७ ३

स्थिर आरंभ: पूर्णांक[] = नया पूर्णांक[]{१,२,३};
स्थिर खाली: पाठ[] = नया पाठ[]{};
```

`सरणी<त>` is an alias for `त[]`: `नया सरणी<पूर्णांक>(३)` constructs the same type
as `नया पूर्णांक[३]`. Empty arrays are valid. Lengths and indices use `पूर्णांक`;
lengths must be between 0 and 2,147,483,647, and actual allocation is additionally
limited by the target's available memory. Negative or oversized lengths and
out-of-range indices produce Bhash runtime diagnostics, with no narrowing/wrapping.

Defaults are `०`, `०.०`, `असत्य`, `""` and `रिक्त` for integers, reals, booleans,
text and reference elements, respectively. `शून्य` is not an element type.

Nested arrays support both rectangular allocation and jagged rows:

```text
स्थिर जाल: पूर्णांक[][] = नया पूर्णांक[२][३];
जाल[१][२] = ४२;

स्थिर अधूरा: पूर्णांक[][] = नया पूर्णांक[२][]; // Two initially-null rows
अधूरा[०] = नया पूर्णांक[]{१,२};
अधूरा[१] = नया पूर्णांक[]{३};

स्थिर पंक्तियाँ: पूर्णांक[][] = नया पूर्णांक[][]{
    नया पूर्णांक[]{१}, नया पूर्णांक[]{२,३}
};
```

Rectangular allocation creates distinct rows; changing one does not change another.
All dimension expressions evaluate once, from left to right, and all lengths are
validated before allocating. To index an array immediately after sized allocation,
parenthesize it: `(नया पूर्णांक[२])[०]`. Consecutive sized brackets in an allocation
specify dimensions.

## Lists

`सूची<त>` is a growable, indexed collection:

```text
स्थिर चालें: सूची<पूर्णांक> = नया सूची<पूर्णांक>{१,३};
चालें.डालो(१,२);
चालें.जोड़ो(४);
चालें[०] = ९;
लिखो(चालें.हटाओ(०)); // ९; list now contains २,३,४
```

Use `नया सूची<त>()` or `नया सूची<त>{...}`. Values must be assignable to `त`.
An index equal to the current size is permitted only for insertion; bracket writes
replace existing elements rather than extending the list.

| Method | Arguments | Result |
| --- | --- | --- |
| `जोड़ो` | value | Append; `शून्य` |
| `डालो` | index, value | Insert before index, including at the end; `शून्य` |
| `लो` | index | Element at index |
| `रखो` | index, value | Replace element; `शून्य` |
| `हटाओ` | index | Remove and return that element |
| `है` | value | Membership test; `तार्किक` |
| `साफ़` | none | Remove all elements; `शून्य` |
| `खाली` | none | Whether size is zero; `तार्किक` |
| `आकार` or `लंबाई` | none | Size as `पूर्णांक` |

Arrays also provide `लो`, `रखो`, `है`, `खाली`, `आकार` and `लंबाई`, but do not grow
or support insertion, removal or clearing. For arrays, lists and maps, `.लंबाई` and
`.आकार` are read-only properties, and global `लंबाई(collection)` also returns size.
Existing `लंबाई(text)` continues to count Unicode scalar values.

## Maps

`मानचित्र<क, व>` associates keys of type `क` with values of type `व`:

```text
स्थिर अंक: मानचित्र<पाठ,पूर्णांक> = नया मानचित्र<पाठ,पूर्णांक>{"एक":१,"दो":२};
अंक["तीन"] = ३;
अंक.रखो("दो",२०);
लिखो(अंक["दो"], अंक.लो("अनजान",०)); // २० ०
```

Use `नया मानचित्र<क,व>()` or a typed `{key: value, ...}` initializer. Maps use
hash lookup and preserve insertion order. Replacing an existing key's value keeps
its position; removing and reinserting that key places it at the end. Duplicate
initializer keys update the existing entry, keeping the first insertion position.

| Method | Arguments | Result |
| --- | --- | --- |
| `लो` | key | Stored value; missing key is a runtime error |
| `लो` | key, fallback | Stored value if present, otherwise fallback |
| `रखो` | key, value | Insert or replace; `शून्य` |
| `है` | key | Key membership; `तार्किक` |
| `हटाओ` | key | Remove if present; return whether removed |
| `कुंजियाँ` | none | A new list of keys in insertion order |
| `मूल्य` | none | A new list of values in insertion order |
| `साफ़` | none | Remove all entries; `शून्य` |
| `खाली` | none | Whether size is zero; `तार्किक` |
| `आकार` or `लंबाई` | none | Number of entries as `पूर्णांक` |

Brackets are equivalent to `लो` for reads and `रखो` for writes. Missing bracket
reads fail rather than returning a default. Class/interface and collection value
types can store `रिक्त`; `है` distinguishes these stored values from missing keys. Keys cannot be `रिक्त`; using a null
reference key produces a Bhash runtime diagnostic.

Keys can be primitive values, class/interface references or collections. The
same equality policy is used for map keys and array/list membership:

- Integers and booleans compare by value; text compares ordinally, without normalization.
- Floating-point positive and negative zero are equal, and all NaNs are equal to
  each other for collection membership/key lookup. Ordinary numeric `==` keeps its
  existing IEEE behavior, so NaN is still unequal to itself in a numeric expression.
- Class/interface and collection references compare by identity, not by their contents.
  Mutating an object's fields or a collection used as a key does not change its identity.

`कुंजियाँ()` and `मूल्य()` return independent membership snapshots, with shallow
references to objects/collections. They are not live views of the map.

## Collection iteration

One binding iterates array/list elements or map keys. Two bindings iterate map
keys and values. Binding types are explicit and must accept the corresponding
source types; ordinary class/interface upcasts are permitted.

```text
प्रत्येक (अंक: पूर्णांक में नया पूर्णांक[]{१,२,३}) {
    लिखो(अंक);
}

प्रत्येक (कुंजी: पाठ, मानक: पूर्णांक में अंक) {
    लिखो(कुंजी, मानक);
}
```

The source expression evaluates once. Bindings are immutable and scoped to the
loop; object contents remain mutable. `विराम` exits the nearest loop/selection,
`जारी` continues the nearest loop, and `लौटाओ` returns from the enclosing function.
An empty collection performs zero iterations. Return analysis therefore does not
assume that a collection loop executes.

Array iteration observes current elements. List/map iteration rejects structural
mutation when iteration advances: adding/removing elements or clearing a nonempty
collection produces a Bhash diagnostic. Replacing a list element or updating the
value of an existing map key is allowed and visible to subsequent iterations.
Breaking/returning immediately does not advance the iterator again.

To remove map entries while iterating, iterate a key snapshot:

```text
प्रत्येक (कुंजी: पाठ में अंक.कुंजियाँ()) { अंक.हटाओ(कुंजी); }
```

## Types, references and evaluation

Arrays, lists and maps are invariant reference types. `बच्चा[]` cannot be assigned
to `मूल[]`, and `सूची<बच्चा>` cannot be assigned to `सूची<मूल>`. Alias types such as
`सरणी<पूर्णांक>` and `पूर्णांक[]` have the same identity. Separate allocations are
different references; assigning a reference does not copy the collection.
A `स्थिर` binding prevents rebinding, not modification of its elements.

Collection element/key/value types may themselves be arrays, collections or user
generic types. These types work in fields, parameters, returns, interfaces,
overloads and generic specializations across source files and packages. Initializing
fields with allocation expressions remains subject to the existing field-initializer
rules; initialize mutable collection fields in a constructor.

Receivers, indices/keys and assigned values evaluate once, from left to right.
A null receiver fails before evaluating the index/key or assigned value. Bounds
and key validity are checked inside the operation, after its argument expressions.
Initializer expressions evaluate left to right before constructing the collection.
`लो(key, fallback)` evaluates fallback eagerly, like an ordinary method argument.

Collections cannot be printed as a whole by `लिखो`; print their elements or sizes.
Constructors accept an array length, or empty/list/map initializers as above; there
are no collection conversion/copy constructors, slices or user-defined iterator
protocols in this version. These can be added without changing the core contracts.

## Run the multi-file example

```bash
git pull --ff-only
mvn clean package -DskipTests
java -jar target/bhash-compiler-0.1.0-SNAPSHOT.jar run examples/collections
java -jar target/bhash-compiler-0.1.0-SNAPSHOT.jar run examples/collections --target clr
java -jar target/bhash-compiler-0.1.0-SNAPSHOT.jar compile examples/collections --target wasm-browser -o target/collections-web
```

See the [three-file example](../examples/collections/) and its [expected output](../examples/collections/expected.txt).
Serve browser output over HTTP using the existing browser-target instructions.

## Additional value types (v0.9)

Arrays/lists/maps can contain `बाइट`, `लघुपूर्णांक`, enums, records and
`सर्ववस्तु`. Byte and int32 scalars have value equality while remaining distinct
boxed types in general collections. Records have structural equality/hashing;
ordinary classes and collections retain reference identity. `बाइटबफ़र` is a
separate packed byte sequence with indexing and iteration. Runtime tests and
casts preserve invariant collection type arguments, including nested arrays and
snapshots. See [types and conversions](types-conversions.md).
