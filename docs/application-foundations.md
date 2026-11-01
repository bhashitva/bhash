# Application foundations

This milestone provides reusable collection algorithms, buffered binary streams,
portable lexical paths, directory operations, scoped resource cleanup,
configuration, structured logging and a process-isolated Bhash test runner.
Hindi reference examples are in [the tutorial](../tutorial/application-foundations/README.md).

The public classes are bundled source in `मानक`. Imports, aliases and explicit
generic arguments work like user-defined packages. Algorithms and application
policies are written in Bhash; `foundation-api.txt` supplies the small compiler
bindings used by `foundations.bhash`. `मानक.तंत्र` is that implementation layer;
application code should use the public classes below instead of integer handles.
Unused foundation declarations are omitted, including their static initialization.

## Target contract

| Capability | JVM / CLR / native Linux, Windows, macOS | Browser WebAssembly |
| --- | --- | --- |
| Collections, lexical paths, JSON configuration, assertions | Supported | Supported |
| Scoped cleanup of user-defined objects | Supported | Supported |
| File streams and directories | Host filesystem | Catchable unsupported-operation failure |
| Environment configuration | Host environment | Catchable unsupported-operation failure |
| Command-line arguments | Arguments after executable/JAR/DLL | Empty list |
| Logging | UTF-8 JSON lines to standard error | Host console/error capture |
| CLI test runner | JVM, CLR, native | Not a test-runner target |

No host filesystem/environment capability is added to browser programs. This
milestone does not deploy a new playground or publish a VS Code extension.
Windows native argument decoding uses Windows-supplied Shell32, alongside the
existing Kernel32/UCRT dependencies; no Java/.NET/LLVM runtime is introduced.

## Collections

All algorithms accept `सूची<T>` and use explicit type arguments. Callbacks use
ordinary classes implementing interfaces; this does not require lambdas.

| API | Contract |
| --- | --- |
| `तुलनाकार<T>.तुलना(a: T, b: T) → पूर्णांक` | Negative / zero / positive comparison; do not subtract integers if it can overflow |
| `चयनक<T>.चुनो(value: T) → तार्किक` | Predicate |
| `रूपांतरक<A,B>.बदलो(value: A) → B` | Transform or grouping-key selector |
| `संचायक<A,B>.जोड़ो(accumulator: B, value: A) → B` | Fold step |
| `संग्रह<T>.प्रतिलिपि(source) → सूची<T>` | New shallow list in source order |
| `संग्रह<T>.उलटो(source) → सूची<T>` | New reversed shallow list |
| `संग्रह<T>.छाँटो(source, predicate) → सूची<T>` | Matching elements in source order |
| `संग्रह<T>.कोई(source, predicate) → तार्किक` | Short-circuit any; false on empty |
| `संग्रह<T>.सभी(source, predicate) → तार्किक` | Short-circuit all; true on empty |
| `संग्रह<T>.खोजो(source, predicate) → पूर्णांक` | First matching index, or -1 |
| `संग्रह<T>.गिनो(source, predicate) → पूर्णांक` | Number of matches |
| `संग्रह<T>.क्रमित(source, comparator) → सूची<T>` | New list, stable bottom-up merge sort; O(n log n) comparisons and O(n) auxiliary references |
| `संग्रह<T>.द्विआधारीखोज(source, value, comparator) → पूर्णांक` | First equal index or -1; source must already be sorted by that comparator; O(log n) comparisons |
| `रूपांतरण<A,B>.बदलो(source, transformer) → सूची<B>` | New list in source order |
| `रूपांतरण<A,B>.संचित(source, initial, accumulator) → B` | Left fold; initial value for empty input |
| `समूहन<A,B>.समूहित(source, keySelector) → मानचित्र<B,सूची<A>>` | Groups preserve input order; B must be a supported map-key type |
| `पूर्णांकक्रम.तुलना(a, b)` | Ascending 64-bit integer comparator without subtraction overflow |

These operations do not intentionally mutate the source list. Copies share
referenced objects. Callbacks must not mutate the source during iteration and
comparators must supply a consistent ordering. Existing collection mutation
checks and catchable callback errors apply. There are no new collection equality
or hashing rules, implicit conversions or unchecked element accesses.

## Resource scopes and streams

`संसाधन (name: Type = expression) { ... }` evaluates the initializer once and
requires an accessible instance `बंदकरो() → शून्य`. The binding is immutable and
exists only inside the scope. `बंदनीय` is the shared interface for this contract;
structurally matching classes are also accepted. A null result fails before the
body runs. Failed initialization does not invoke cleanup.

The compiler lowers the body to the existing try/finally machinery. Cleanup runs
on fallthrough, return, break, continue and Bhash exception unwinding. Nested
scopes close in reverse acquisition order, including when a later acquisition
fails. Native bindings remain precise GC roots through cleanup and unwinding.
As with existing `अंततः`, a cleanup exception replaces a pending body exception
or return; suppressed-exception aggregation is not introduced. A process kill or
fatal runtime/host failure cannot guarantee cleanup.

| `प्रवाह` API | Contract |
| --- | --- |
| `पठन(path: पाठ) → प्रवाह` | Open existing file for buffered binary input |
| `लेखन(path: पाठ) → प्रवाह` | Create/truncate file for buffered binary output; parents must exist |
| `पढ़ो(count: पूर्णांक) → बाइटबफ़र` | Read up to count bytes, stopping at EOF; count 0–1,048,576; empty buffer at EOF |
| `बाइटलिखो(bytes: बाइटबफ़र) → शून्य` | Write up to 1,048,576 bytes per call |
| `पाठलिखो(text: पाठ) → शून्य` | Strict UTF-8 encoding followed by binary write; same encoded-byte limit |
| `भेजो() → शून्य` | Flush output buffering; not an fsync/durability promise |
| `बंदकरो() → शून्य` | Close and flush; repeated close is harmless; subsequent operations fail |

Streams do not have the whole-file helpers' 16 MiB total-file limit. Repeated
bounded operations support larger files without loading them in full. A zero-byte
read does not establish EOF. UTF-8 sequences can straddle read boundaries: do not
independently decode arbitrary chunks with `यूटीएफ८पाठ`. This first edition
provides binary streams and text writes, not a stateful text reader or seeking.
Open-file sharing/locking follows the OS; do not rely on concurrent modification.

At most 1,024 streams may be open in one program. The runtime registry holds only
OS resources/opaque integer identities, never managed object pointers. Explicit
scopes provide deterministic close; GC finalization is not a substitute for them.
Bindings release temporary directory enumeration handles during failures too.

## Paths and directories

| API | Contract |
| --- | --- |
| `पथ.सामान्य(path) → पाठ` | Lexical cleanup of repeated `/` and `.` components; preserves `..` and rooted/drive-root paths |
| `पथ.जोड़ो(base, relative) → पाठ` | Join relative child; reject rooted child or colon in child |
| `पथ.नाम(path) → पाठ` | Last normalized component; empty name for slash root |
| `पथ.जनक(path) → पाठ` | Parent; `.` for an unrooted single component, root for root |
| `निर्देशिका.है(path) → तार्किक` | Whether a directory exists |
| `निर्देशिका.बनाओ(path) → शून्य` | Create one directory; existing target or absent parent fails |
| `निर्देशिका.नाम(path) → सूची<पाठ>` | Immediate child names, including hidden entries; excludes `.` / `..`; order unspecified; at most 65,536 names |
| `निर्देशिका.हटाओ(path) → शून्य` | Remove an empty actual directory; no recursive deletion |
| `निर्देशिका.फ़ाइलहटाओ(path) → शून्य` | Remove an existing regular file |

The lexical helpers use `/` on all targets and reject backslashes, UNC/device
prefixes, empty paths and embedded NUL. Raw stream/directory bindings still accept
host-native paths. Lexical cleanup performs no filesystem access, realpath
resolution, symlink following or containment/security checks. In particular,
preserving `..` avoids silently changing symlink traversal semantics. Do not
accept untrusted paths using a string-prefix test.

Existence follows directory targets; deletion rejects symlink/reparse-point leaf
entries. Creation requires exclusive use of the intended target name; the CLR's
check/create sequence is not an atomic exclusivity guarantee under competing
processes. Errors have portable catchable Hindi messages; OS error codes and
permission details are not exposed in this edition.

## Configuration, logging and assertions

| API | Contract |
| --- | --- |
| `नया विन्यास()` | Empty string-to-string configuration |
| `विन्यास.रखो(name, value)` | Set/replace a value |
| `विन्यास.पढ़ो(name, fallback) → पाठ` | Retrieve value, preserving empty strings; use fallback only when absent |
| `विन्यास.जेसनपढ़ो(text) → विन्यास` | Flat JSON object with string values only; existing JSON validation applies |
| `विन्यास.तर्कपढ़ो(list: सूची<पाठ>) → विन्यास` | Parse `--name=value`, splitting at first `=`; nonempty name; last duplicate wins |
| `विन्यास.तर्क() → विन्यास` | Parse this program's arguments, excluding executable/JAR/DLL name |
| `विन्यास.परिवेश(name, fallback) → पाठ` | Read environment; reject empty name, NUL or `=` |
| `लेखा.स्तर(level)` | Threshold 0 debug, 1 info (default), 2 warning, 3 error, 4 off |
| `लेखा.घटनालिखो(level, message, fields: मानचित्र<पाठ,सर्ववस्तु>)` | Emit one JSON object with `level`, `message`, `fields` to stderr when enabled |
| `लेखा.सूचना(message)` | Info event with empty fields |
| `परीक्षण.सत्यहै(condition, message)` | Throw catchable `अपवाद` when false |
| `परीक्षण.बराबर(expected, actual)` | Exact equality overloads for पूर्णांक, पाठ, तार्किक |

Configuration layering is explicit: choose defaults, load values, then overwrite
using the application’s desired precedence. Values are not implicitly converted
and environment variables are not interpolated. Do not log secrets automatically.
Log fields obey JSON's supported value/cycle rules. Logging is synchronous,
process-local and has no background writer, file rotation or multi-process
ordering guarantee. JSON escaping keeps embedded newlines inside one record.
These APIs do not add asynchronous/concurrent execution.

## Test runner

```sh
java -jar target/bhash-compiler-0.1.0-SNAPSHOT.jar test examples/foundations --target jvm
java -jar target/bhash-compiler-0.1.0-SNAPSHOT.jar test examples/foundations --target clr
java -jar target/bhash-compiler-0.1.0-SNAPSHOT.jar test examples/foundations --target native --timeout 30
```

Directory discovery sorts `*.test.bhash` programs and `*.test/bhash.project`
projects; build, target, .git and .bhash cache paths are excluded, with no symlink
traversal. An explicit source file or project manifest is also accepted. Each
case is a complete Bhash program with its own `मुख्य`; a test project can use
ordinary package dependencies to exercise a shared library without copying it.
There is no annotation-based method discovery or host JUnit dependency.

Each case is compiled, then runs in a separate process with empty stdin and its
source/project directory as cwd. Exit zero passes; assertion failures, uncaught
exceptions, compilation failures, nonzero exits and timeouts fail. Later cases
still run. The runtime timeout is 30 seconds by default (1–600 configurable);
compiler/toolchain timeouts remain their existing contracts. Timeout cleanup
terminates the test process and its observed descendants. This is process-state
isolation, not a security sandbox or filesystem isolation.

Failed cases show captured stdout/stderr, capped at 64 KiB per stream when
displayed; capture files can grow during the test. Passing output is suppressed.
The summary counts programs/projects, not individual assertions. Exit status is
0 when all pass, 1 for failed cases, 2 for invocation/discovery errors or no cases,
and 130 for interruption. Build outputs are temporary and cleaned afterward.

## Validation

Shared Bhash fixtures exercise algorithms, configuration, resource flow and
UTF-8 streams/directories on JVM/CLR/native. Native conformance additionally
forces collection and checks final root/heap cleanup. Arguments/logging have
cross-target checks, including Unicode Windows native arguments. The CLI runner
tests cover assertion/compiler failures, continued execution, timeout and fresh
static state. Browser restrictions remain guarded in the CLR bindings.
