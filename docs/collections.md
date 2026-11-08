# Arrays, lists, maps, sets and iteration

These features share one type checker and the same behavior on JVM, .NET CLR and
browser .NET WebAssembly. The experimental Linux native target also supports
the [integer, boolean, text and reference subset](native-collections.md). `प्रत्येक` and `में` are new reserved keywords.
`सरणी`, `सूची` and `मानचित्र` are predeclared generic type names; user types and
import aliases cannot replace them. Generic type parameters can shadow type names.

**हिन्दी:** JVM, CLR और WebAssembly संग्रह के लिए एक ही प्रकार जाँच और व्यवहार उपयोग करते हैं। [नेटिव संग्रह](native-collections.md) भी देखें। `प्रत्येक` और `में` आरक्षित हैं। `सरणी`, `सूची` और `मानचित्र` पहले से घोषित सामान्य प्रकार हैं। नए प्रकार या आयात उपनाम इन्हें नहीं बदल सकते, लेकिन प्रकार मापदंड नाम ढक सकते हैं।

## Arrays

Arrays have fixed length, zero-based indexing and an explicit element type:

**हिन्दी:** सरणी की लंबाई तय है, अनुक्रमण ० से है और तत्व का प्रकार स्पष्ट लिखा जाता है:

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

**हिन्दी:** `सरणी<त>` और `त[]` एक ही प्रकार हैं। दोनों दिखाए गए निर्माता एक जैसी सरणी बनाते हैं। खाली सरणी मान्य है। लंबाई और index `पूर्णांक` हैं। लंबाई 0–2,147,483,647 और उपलब्ध स्मृति तक है। गलत लंबाई या index पर त्रुटि आती है। संख्या चुपचाप नहीं बदलती।

Defaults are `०`, `०.०`, `असत्य`, `""` and `रिक्त` for integers, reals, booleans,
text and reference elements, respectively. `शून्य` is not an element type.

**हिन्दी:** पूर्णांक, दशमलव, तार्किक, पाठ और संदर्भ का डिफ़ॉल्ट क्रमशः `०`, `०.०`, `असत्य`, `""` और `रिक्त` है। `शून्य` तत्व प्रकार नहीं है।

Nested arrays support both rectangular allocation and jagged rows:

**हिन्दी:** भीतर की सरणियाँ समान आयाम या अलग-अलग लंबाई की पंक्तियाँ ले सकती हैं:

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

**हिन्दी:** आयताकार निर्माण में हर पंक्ति अलग है। हर आयाम बाएँ से दाएँ एक बार चलता है। आवंटन से पहले सभी लंबाइयाँ जाँची जाती हैं। नई सरणी पर तुरंत index लगाने के लिए `(नया पूर्णांक[२])[०]` लिखें। लगातार आकार वाले कोष्ठक आयाम बताते हैं।

## Lists

`सूची<त>` is a growable, indexed collection:

**हिन्दी:** `सूची<त>` बढ़ने वाला अनुक्रमित संग्रह है:

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

**हिन्दी:** `नया सूची<त>()` या `नया सूची<त>{...}` दें। हर मान `त` में रखा जा सके। आकार के बराबर index केवल insertion के लिए है। `[]` से लिखने पर पुराना तत्व बदलता है।

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

**हिन्दी:** सरणी में `लो`, `रखो`, `है`, `खाली`, `आकार` और `लंबाई` हैं। उसकी लंबाई नहीं बदलती। सरणी, सूची और मानचित्र में `.लंबाई`/`.आकार` केवल पढ़े जाते हैं। `लंबाई(collection)` आकार और `लंबाई(text)` Unicode scalar गिनती देता है।

## Maps

`मानचित्र<क, व>` associates keys of type `क` with values of type `व`:

**हिन्दी:** `मानचित्र<क, व>` प्रकार `क` की कुंजी को प्रकार `व` के मान से जोड़ता है:

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

**हिन्दी:** खाली या प्रकारयुक्त कुंजी-मान आरंभक दें। hash lookup और जोड़ने का क्रम रहता है। पुरानी कुंजी बदलने पर स्थान रहता है। हटाकर जोड़ने पर अंत में आती है। आरंभक में दोहरी कुंजी पहले स्थान पर नया मान रखती है।

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

**हिन्दी:** पढ़ने में `[]` वही है जो `लो`, लिखने में `रखो`। अनुपस्थित कुंजी को पढ़ना विफल है। संदर्भ मान `रिक्त` हो सकता है। `है` से मौजूद `रिक्त` और अनुपस्थित कुंजी अलग करें। null कुंजी त्रुटि है।

Keys can be primitive values, class/interface references or collections. The
same equality policy is used for map keys and array/list membership:

**हिन्दी:** कुंजी आदिम मान, वर्ग/अनुबंध संदर्भ या संग्रह हो सकती है। कुंजी और सदस्यता में नीचे के समानता नियम हैं:

- Integers and booleans compare by value; text compares ordinally, without normalization.

  **हिन्दी:** पूर्णांक और तार्किक मान से मिलते हैं। पाठ बिना सामान्यीकरण के क्रमिक रूप से मिलता है।
- Floating-point positive and negative zero are equal, and all NaNs are equal to
  each other for collection membership/key lookup. Ordinary numeric `==` keeps its
  existing IEEE behavior, so NaN is still unequal to itself in a numeric expression.

  **हिन्दी:** सदस्यता/कुंजी में दोनों signed zero समान और सभी NaN आपस में समान हैं। संख्यात्मक `==` में IEEE नियम हैं, इसलिए NaN स्वयं से भी असमान है।
- Class/interface and collection references compare by identity, not by their contents.
  Mutating an object's fields or a collection used as a key does not change its identity.

  **हिन्दी:** वर्ग, अनुबंध और संग्रह संदर्भ पहचान से मिलते हैं। कुंजी वस्तु या संग्रह की सामग्री बदलने से उसकी पहचान नहीं बदलती।

`कुंजियाँ()` and `मूल्य()` return independent membership snapshots, with shallow
references to objects/collections. They are not live views of the map.

**हिन्दी:** `कुंजियाँ()` और `मूल्य()` स्वतंत्र snapshots हैं। वस्तु/संग्रह के संदर्भ साझा रहते हैं। वे मानचित्र के साथ अपने आप नहीं बदलते।

## Sets

Import `मानक.समुच्चय` and construct a set with `नया समुच्चय<त>()`.
It follows the map key equality rules above, including the error for `रिक्त`.
`जोड़ो` and `हटाओ` report whether membership changed, and `है` tests
membership. `आकार()`, `खाली()` and `साफ़()` query or clear the set.
`संघ`, `प्रतिच्छेद` and `अंतर` return new sets of the same element type.
`तत्व()` returns a new list in insertion order. A one-binding `प्रत्येक`
over a set uses that same snapshot behavior.

**हिन्दी:** `मानक.समुच्चय<त>` एक प्रकार के अद्वितीय सदस्य रखता है। जोड़ने,
हटाने, क्रम से पढ़ने और दो समुच्चयों की तुलना करने के उदाहरण
[समुच्चय की मार्गदर्शिका](../tutorial/standard-library/09-sets.md) में हैं।

## Collection iteration

One binding iterates array, list or set elements, or map keys. Two bindings iterate map
keys and values. Binding types are explicit and must accept the corresponding
source types; ordinary class/interface upcasts are permitted.

**हिन्दी:** एक बंधन सरणी, सूची या समुच्चय के तत्व या मानचित्र की कुंजी पढ़ता है। दो बंधन कुंजी और मान पढ़ते हैं। स्पष्ट प्रकार दें। संगत मूल वर्ग या अनुबंध प्रकार मान्य हैं।

```text
प्रत्येक (नया पूर्णांक[]{१,२,३} में अंक: पूर्णांक) {
    लिखो(अंक);
}

प्रत्येक (अंक में कुंजी: पाठ, मानक: पूर्णांक) {
    लिखो(कुंजी, मानक);
}
```

The source expression evaluates once. Bindings are immutable and scoped to the
loop; object contents remain mutable. `विराम` exits the nearest loop/selection,
`जारी` continues the nearest loop, and `लौटाओ` returns from the enclosing function.
An empty collection performs zero iterations. Return analysis therefore does not
assume that a collection loop executes.

**हिन्दी:** संग्रह व्यंजक एक बार चलता है। अचल बंधन चक्र तक हैं, लेकिन वस्तु सामग्री बदल सकती है। `विराम` निकटतम चक्र/चयन छोड़ता है, `जारी` चक्र आगे बढ़ाता है और `लौटाओ` फलन छोड़ता है। खाली संग्रह में चक्र शून्य बार चलता है, इसलिए वापसी जाँच उसके चलने पर निर्भर नहीं करती।

Array iteration observes current elements. List/map iteration rejects structural
mutation when iteration advances: adding/removing elements or clearing a nonempty
collection produces a Bhash diagnostic. Replacing a list element or updating the
value of an existing map key is allowed and visible to subsequent iterations.
Breaking/returning immediately does not advance the iterator again.
Set iteration takes a shallow snapshot of the members at the start of the loop.

**हिन्दी:** सरणी में वर्तमान तत्व दिखते हैं। सूची/मानचित्र iterator आगे बढ़ते समय जोड़ना, हटाना या गैर-खाली संग्रह साफ़ करना त्रुटि देता है। मौजूद मान बदल सकते हैं और अगली बारी उसे देखती है। तुरंत `विराम` या `लौटाओ` पर iterator फिर नहीं बढ़ता।
समुच्चय में चक्र की शुरुआत में सदस्यों की अलग सूची बनती है।

To remove map entries while iterating, iterate a key snapshot:

**हिन्दी:** हटाते समय मानचित्र की कुंजियों की स्वतंत्र प्रति पर चक्र चलाएँ:

```text
प्रत्येक (अंक.कुंजियाँ() में कुंजी: पाठ) { अंक.हटाओ(कुंजी); }
```

## Types, references and evaluation

Arrays, lists and maps are invariant reference types. `बच्चा[]` cannot be assigned
to `मूल[]`, and `सूची<बच्चा>` cannot be assigned to `सूची<मूल>`. Alias types such as
`सरणी<पूर्णांक>` and `पूर्णांक[]` have the same identity. Separate allocations are
different references; assigning a reference does not copy the collection.
A `स्थिर` binding prevents rebinding, not modification of its elements.

**हिन्दी:** सरणी, सूची और मानचित्र invariant हैं। `बच्चा[]` को `मूल[]` या `सूची<बच्चा>` को `सूची<मूल>` में न रखें। `सरणी<पूर्णांक>` और `पूर्णांक[]` एक हैं। अलग आवंटन अलग संदर्भ हैं। assignment प्रति नहीं बनाता। `स्थिर` बंधन के तत्व बदल सकते हैं।

Collection element/key/value types may themselves be arrays, collections or user
generic types. These types work in fields, parameters, returns, interfaces,
overloads and generic specializations across source files and packages. Initializing
fields with allocation expressions remains subject to the existing field-initializer
rules; initialize mutable collection fields in a constructor.

**हिन्दी:** तत्व, कुंजी और मान खुद सरणी, संग्रह या उपयोगकर्ता सामान्य प्रकार हो सकते हैं। ये फ़ाइलों और संकुलों के पार क्षेत्र, मापदंड, परिणाम, अनुबंध, ओवरलोड और विशेषीकरण में चलते हैं। बदलने वाला संग्रह क्षेत्र निर्माता में आरंभ करें।

Receivers, indices/keys and assigned values evaluate once, from left to right.
A null receiver fails before evaluating the index/key or assigned value. Bounds
and key validity are checked inside the operation, after its argument expressions.
Initializer expressions evaluate left to right before constructing the collection.
`लो(key, fallback)` evaluates fallback eagerly, like an ordinary method argument.

**हिन्दी:** receiver, index/कुंजी और नया मान बाएँ से दाएँ एक बार चलते हैं। null receiver होने पर बाकी नहीं चलते। सीमा और कुंजी की जाँच तर्क चलने के बाद संक्रिया में होती है। आरंभक बाएँ से दाएँ चलते हैं। `लो(key, fallback)` में fallback पहले ही चलता है।

Collections cannot be printed as a whole by `लिखो`; print their elements or sizes.
Constructors accept an array length, or empty/list/map initializers as above; there
are no collection conversion/copy constructors, slices or user-defined iterator
protocols in this version. These can be added without changing the core contracts.

**हिन्दी:** पूरे संग्रह को `लिखो` में देने के बजाय तत्व या आकार छापें। निर्माता लंबाई या ऊपर दिखाए आरंभक लेते हैं। संग्रह बदलने/कॉपी करने वाले निर्माता, slices और उपयोगकर्ता iterator protocol इस संस्करण में उपलब्ध नहीं हैं।

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

**हिन्दी:** [तीन फ़ाइल वाला उदाहरण](../examples/collections/) और [अपेक्षित परिणाम](../examples/collections/expected.txt) देखें। ब्राउज़र आउटपुट HTTP से परोसें।

## Additional value types (v0.9)

Arrays/lists/maps can contain `बाइट`, `लघुपूर्णांक`, enums, records and
`सर्ववस्तु`. Byte and int32 scalars have value equality while remaining distinct
boxed types in general collections. Records have structural equality/hashing;
ordinary classes and collections retain reference identity. `बाइटबफ़र` is a
separate packed byte sequence with indexing and iteration. Runtime tests and
casts preserve invariant collection type arguments, including nested arrays and
snapshots. See [types and conversions](types-conversions.md).

**हिन्दी:** संग्रह में `बाइट`, `लघुपूर्णांक`, परिगणना, अभिलेख और `सर्ववस्तु` भी रख सकते हैं। छोटे पूर्णांक मान से मिलते हैं, boxing में उनके अलग प्रकार बने रहते हैं। अभिलेख संरचनात्मक समानता/hash और साधारण वर्ग व संग्रह संदर्भ पहचान रखते हैं। `बाइटबफ़र` अलग packed क्रम है। runtime जाँच में भीतर की सरणियों और snapshots के प्रकार तर्क भी सुरक्षित हैं।
