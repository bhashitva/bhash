# मूल-मशीन निदान और स्रोत डिबगिंग (Native Diagnostics and Source Debugging)

Linux x86-64 / LLVM 18 executables अब uncaught Bhash exceptions के लिए function names
और original `.bhash` file/line/column वाला stack trace लिखते हैं। इसके लिए JVM/.NET,
debugger अथवा debug symbols आवश्यक नहीं हैं। Caught exceptions अपना existing व्यवहार
रखती हैं; केवल uncaught failure पर stderr में trace छपता है।

## अपवाद स्टैक (Exception Stack)

[उदाहरण](../examples/native/diagnostics.bhash) division failure को virtual interface call,
generic function और allocating `अंततः` के माध्यम से बाहर लाता है। यह जानबूझकर exit 1
करता है और stdout में `साफ😀` लिखता है। Trace के names अंदर से बाहर `भाग`, `गणक.लो`,
`परत`, `मुख्य` हैं। पहली stderr line का existing `भाष: file:line:column: message` format
रहता है; अगली lines का format `  में नाम (file:line:column)` है।

- Trace **first throw** पर capture होता है, exception construction पर नहीं। वही exception
  बाद में throw/rethrow करने पर original stack रहता है, cached class-initialization failure सहित।
- `अंततः` नया exception फेंके तो नए exception का अपना stack मिलता है।
- Constructor और class-initialization frames meaningful names रखते हैं। Generated factory,
  try/catch/finally callback तथा runtime helper names user-facing trace में नहीं आते।
- Package lowering और generic specialization के बाद भी original Hindi declaration names
  और source locations रहते हैं। Overloads/specializations source name साझा कर सकती हैं;
  declaration/call-site locations उन्हें अलग पहचानती हैं।
- सबसे नज़दीकी **256 frames** रखी जाती हैं; अधिक depth पर स्पष्ट truncation line आती है।
  यह exception tracing है; native signals, stack overflow और fatal allocation failures का
  portable crash reporter इस milestone में नहीं जुड़ता।

## डिबग निर्माण (Debug Build)

```sh
java -jar target/bhash-compiler-0.1.0-SNAPSHOT.jar compile examples/native/debugging.bhash --target native --debug -o build/debugging
# run भी --debug स्वीकार करता है:
java -jar target/bhash-compiler-0.1.0-SNAPSHOT.jar run examples/native/debugging.bhash --target native --debug
```

`--debug` केवल native target के लिए है। यह LLVM DWARF metadata, original source files,
Hindi function/variable names, scalar parameter/local descriptions और unoptimized code देता है।
Functions तथा protected callbacks noinline/optnone हैं; normal builds optimized रहते हैं और
फिर भी exception traces देते हैं। Compiler temporary IR हटाने के बाद executable में debug
information रहती है। Source listing के लिए original source files उपलब्ध होनी चाहिए।

## स्रोत स्तर पर चलाना (Source-level Debugging)

```text
gdb build/debugging
(gdb) set pagination off
(gdb) skip -rfu ^bh_
(gdb) break debugging.bhash:4
(gdb) run
(gdb) info args
(gdb) info locals
(gdb) next
(gdb) finish
(gdb) backtrace
(gdb) continue
```

इस breakpoint पर `मूल = 21` और `उत्तर = 42` दिखाई देते हैं। `step`, `next` और `finish`
से call में प्रवेश, अगली source line और caller पर वापसी करें। `skip` runtime helpers में
अनावश्यक stepping रोकता है। Hindi names पर debugger expression parsing अलग हो सकती है;
`info locals`/`info args` नाम और values बिना expression लिखे दिखाते हैं।

Source tree दूसरी जगह हो तो GDB का `set substitute-path OLD NEW` उपयोग करें। Debug
metadata source paths रखता है; source files executable में embed नहीं होतीं।

पहला debug scope named scalar parameters/locals (integer widths, boolean, decimal) और
reference slots है। Text, objects तथा collections opaque native pointers हैं; pretty-printers,
object-field expansion, Bhash expression evaluation और VS Code debug adapter अभी शामिल नहीं
हैं। Generated protected callbacks machine backtrace में artificial helper frames के रूप में
दिख सकती हैं; user-facing exception trace उन्हें अलग रखता है। Lexical shadowing वाली locals
फिलहाल function/helper scope में दिखाई देती हैं। Debugger से references बदलने के बजाय
इस milestone में inspection उपयोग करें।

## स्मृति अनुबंध (Memory Contract)

Logical frame records native stack पर हैं और केवल compiler-owned immortal name/location
text रखते हैं। Protected C boundaries unwind से पहले इस chain को restore करती हैं। Exception
का immutable snapshot managed heap में है और exception header के precise reference से trace
होता है; caught/stored exceptions snapshot जीवित रखती हैं। Snapshot entries precise text references हैं (आमतौर पर compiler-owned immortal text);
dynamic location text भी trace होती है। Arbitrary native stack memory scan नहीं होती। Pending exception capture
allocation के पहले global root बनता है, और allocation के बाद reload होता है। Capture का
location argument भी allocation के दौरान root रहता है।

Internal ABI **v14** header reference तथा trace-frame calls जोड़ता है। Payload exception
fields और user layouts नहीं बदलते। Future relocation/generations में header reference को
payload reference slots की तरह update/barrier करना होगा। Matching bundled runtime के साथ
native source rebuild करें।

## जाँच (Validation)

C runtime tests forced-GC retention, first-stack preservation, rethrow, 300-level recursion,
256-frame truncation और shutdown cleanup जाँचते हैं। Native program tests virtual/generic
calls, finally replacement और source names जाँचते हैं। Linux CI sanitizers, LLVM verification
और `scripts/test_native_debugger.py` के actual GDB breakpoint/step/next/finish तथा named-variable
checks चलाता है; transcript `target/native-debugger/gdb.log` में मिलता है।
