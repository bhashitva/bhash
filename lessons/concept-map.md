# Concept bridge: भाष → familiar programming concepts

For teachers: teach the algorithm in the learner's strongest language first,
then introduce the English spelling for the same concept. These are conceptual
mappings, not a promise that replacing keywords produces valid Java or C#.
Student source files in this folder use Bhash only.

| Bhash | English concept | Java / C# bridge and limits |
| --- | --- | --- |
| `कार्य` | function / method | Java/C# usually put methods inside types; Bhash also allows top-level functions. |
| `मुख्य` | entry point | The Bhash entry function; Java uses `main`, C# uses `Main` or top-level statements. |
| `लिखो(...)` | print / output | Similar purpose to `System.out.println` / `Console.WriteLine`; Bhash joins multiple arguments with spaces. |
| `पढ़ो()` | read integer | Similar purpose to reading a line and parsing an integer; it is not a text-input function. |
| `मान` | mutable binding | A variable that can be assigned again; no direct keyword-for-keyword Java/C# replacement. |
| `स्थिर` | constant binding | Closest to Java local `final` / JavaScript `const`; it does not freeze an object's contents. It does **not** mean `static`. |
| `यदि` / `अन्यथा` | if / else | Boolean condition and mutually exclusive branches. |
| `लौटाओ` | return | Ends the current function and optionally supplies its result. It does not print. |
| `जबतक` | while | Tests the condition before each iteration; update state so the loop can finish. |
| `प्रत्येक (... में ...)` | foreach / for-of | Iterates array/list values, or map key/value pairs; map APIs differ by language. |
| `विराम` / `जारी` | break / continue | Stop a loop/selection, or skip to the next loop iteration as permitted by context. |
| `चयन` / `विकल्प` / `शेष` | switch / case / default | Bhash has no implicit case fall-through; Java/JavaScript colon-style switches differ. |
| `पूर्णांक` | signed 64-bit integer | Java/C# `long`, **not** their 32-bit `int`; JavaScript Number is not an exact substitute for every 64-bit integer. |
| `लघुपूर्णांक` / `दशमलव` | int32 / floating point | Java/C# `int` / `double`; see the language spec for conversion rules. |
| `तार्किक` / `पाठ` / `शून्य` | boolean / string / void | Java `boolean`/`String`/`void`; C# `bool`/`string`/`void`. |
| `सत्य` / `असत्य` / `रिक्त` | true / false / null | Boolean values and absence of an object reference. |
| `पूर्णांक[]`, `अंक[०]` | array, indexing | Fixed-length array; index zero is the first element. |
| `सूची<पूर्णांक>` | generic list | Conceptually Java `List<Long>` / C# `List<long>`; method names and boxing differ. |
| `मानचित्र<पाठ, पूर्णांक>` | generic map | Conceptually Java `Map<String, Long>` / C# `Dictionary<string, long>`. |
| `वर्ग` / `नया` | class / new | Define a type; create an instance. |
| `निर्माता` / `यह` | constructor / this | Initialize an instance; refer to the current instance. |
| `सार्वजनिक` / `निजी` / `संरक्षित` | public / private / protected | Visibility controls access to declarations. |
| `साझा` | static member | Belongs to the class, unlike per-instance state. |
| `विस्तार` / `आधार` / `अधिलेखन` | inheritance / base / override | Java `extends`/`super`; C# base type/`base`/`override`. |
| `अनुबंध` / `पालन` | interface / implements | Java `interface`/`implements`; C# `interface` and base-list syntax. |
| `अभिलेख` / `परिगणना` | record / enum | Named data and named alternatives; exact record semantics are language-specific. |
| `प्रयास` / `पकड़ो` / `अंततः` / `फेंको` | try / catch / finally / throw | Structured error handling; Bhash has no Java-style checked-exception declaration requirement. |
| `संकुल` / `आयात` / `उपनाम` | package / import / alias | Java packages/imports or C# namespaces/usings; source-level resolution is supported, not a binary package registry. |

## The same decision and return in Bhash and C#

Bhash (a top-level function):

```bhash
कार्य बड़ा(पहला: पूर्णांक, दूसरा: पूर्णांक) -> पूर्णांक {
    यदि (पहला >= दूसरा) {
        लौटाओ पहला;
    }
    लौटाओ दूसरा;
}
```

C# (a method placed inside a class):

```csharp
static long Max(long first, long second) {
    if (first >= second) {
        return first;
    }
    return second;
}
```

Ask the learner to point to the condition, both return paths, parameter types,
and return type. The algorithm is unchanged. C# `static` above supplies the
class-member context; Bhash's top-level function does not need `साझा`.

## The same loop in Bhash and Java

Bhash (inside `मुख्य`):

```bhash
मान संख्या: पूर्णांक = १;
जबतक (संख्या <= ३) {
    लिखो(संख्या);
    संख्या = संख्या + १;
}
```

Java (inside `main`):

```java
long number = 1;
while (number <= 3) {
    System.out.println(number);
    number = number + 1;
}
```

Both visit the same three values. Bhash formats integer output with Devanagari
digits; the Java example ordinarily prints ASCII digits. Separate numeric value
from its displayed spelling.

## Two bindings, two different ideas

```bhash
स्थिर अंक: पूर्णांक[] = नया पूर्णांक[]{१,२};
अंक[०] = ९;
```

This is legal: the binding stays fixed while the array changes. Reassigning
`अंक` to another array is forbidden. `साझा` instead means a class-level member.
Have students explain these concepts before introducing English `final`,
`const`, `readonly`, and `static`, whose restrictions vary by language.

For authoritative Bhash behavior, consult [the language specification](../docs/language-spec.md).
