# VS Code में मूल-मशीन डिबगिंग (Native Debugging)

Windows x64, Linux x86-64 और WSL में Bhash programs पर source breakpoints लगाएँ, functions में
प्रवेश करें (Step Into), अगली पंक्ति पर जाएँ (Step Over), caller में लौटें (Step Out),
और Hindi call stacks तथा variables देखें। Generated application को JVM या .NET
नहीं चाहिए। Compiler को अभी Java 17+ चाहिए। macOS के native build/run उपलब्ध रहते हैं; macOS editor debugging अभी शामिल नहीं है।

## तैयारी (Setup)

1. Bhash extension और Java 17+ उसी Windows/Linux/WSL host पर स्थापित करें जहाँ folder खुला है।
2. Microsoft का **C/C++** extension (`ms-vscode.cpptools`) उसी host पर स्थापित करें।
   इसका GDB adapter उपयोग होता है; C/C++ compiler स्थापित करना आवश्यक नहीं।
3. Windows x64 में `bhash.native.gdbPath` खाली रखें। पहली debug launch पर pinned
   GDB और Python runtime अलग user cache में अपने आप डाउनलोड और SHA-256 से सत्यापित
   होते हैं। MSYS2, Python, WSL, Visual Studio या C development tools अलग से स्थापित
   नहीं करने पड़ते। Linux/WSL में Python 3 support वाला GDB 12+ चाहिए:
   Ubuntu में `sudo apt install gdb`। अपने debugger के लिए `bhash.native.gdbPath`
   में उसकी absolute executable path रखें।
4. WSL में **WSL: Open Folder in WSL** करें। Windows `C:\...` Java/GDB paths की
   जगह Linux paths उपयोग करें। Folder को trust दें।

Extension GDB/Python की जाँच पहले करती है। फिर सामान्य managed SDK setup का उपयोग
करके `--target native --debug` build बनाती है; अलग LLVM/C development packages
आवश्यक नहीं हैं। System toolchain mode भी सामान्य build जैसा काम करता है। Download
या compilation editor खोलते समय नहीं, debug launch पर होता है। Progress notification
से setup/build cancel कर सकते हैं।

## पहला सत्र (First Session)

[debug-inspection.bhash](../examples/native/debug-inspection.bhash) खोलें। `DEBUG_BEFORE`
और `DEBUG_AFTER` वाली executable lines के बाएँ gutter में क्लिक करें, या उस line पर
cursor रखकर **F9** दबाएँ। 0.8.1 से Bhash files के लिए यह सामान्य editor सुविधा
सक्षम है। यदि 0.8.0 में breakpoint नहीं लग रहा है, तो update करें; अस्थायी रूप से
workspace Settings में **Debug: Allow Breakpoints Everywhere** सक्षम कर सकते हैं।
इस विकल्प को 0.8.1 में रखने की जरूरत नहीं है। Command
Palette से **Bhash: Debug Native** चलाएँ। Project के अंदर की source file
खोलने पर nearest `bhash.project` build होता है। Dirty Bhash project buffers पहले save
होते हैं; invalid source या cancelled build पर पुराना executable launch नहीं होता।

Run and Debug view में Call Stack तथा Variables खोलें। F11 function के अंदर, F10
अगली line, Shift+F11 caller में और F5 अगले breakpoint पर ले जाता है। Pause और Stop
VS Code के सामान्य debugger controls हैं। Console input/output integrated terminal में
होते हैं। `stopAtEntry` चुनने पर compiler-generated entry frame दिखाई दे सकती है;
सीधे Bhash code पर रुकने के लिए executable source line पर breakpoint लगाएँ।

स्थायी `.vscode/launch.json` configuration भी उपयोग कर सकते हैं:

```json
{
  "version": "0.2.0",
  "configurations": [{
    "type": "cppdbg",
    "request": "launch",
    "name": "Bhash: Native",
    "bhashNative": true,
    "bhashInput": "${workspaceFolder}/bhash.project",
    "program": "${workspaceFolder}/.bhash/debug/program",
    "cwd": "${workspaceFolder}",
    "stopAtEntry": false,
    "args": [],
    "environment": [
      {"name": "BHASH_GC_STRESS", "value": "1"},
      {"name": "BHASH_GC_STATS", "value": "1"}
    ]
  }]
}
```

`program` C/C++ configuration schema का placeholder है। `bhashNative: true` होने पर
Bhash provider इसे हर launch के नए temporary executable से बदलती है; इस path पर
file लिखने या existing application overwrite करने की जरूरत नहीं है। Source file के
लिए `bhashInput` में उसकी path रखें। Arguments, environment, working directory और
breakpoints VS Code/GDB adapter संभालते हैं। `bhashNative` के बिना C/C++ configurations
नहीं बदलतीं। Session खत्म होने पर उसके temporary build files हटते हैं।

## मान देखना (Variable Inspection)

- Scalars: signed integers, bytes, booleans और binary64 decimals GDB के format में
  दिखते हैं; integer display सामान्यतः ASCII digits में है।
- Text: UTF-8 Hindi/emoji तथा embedded NUL सुरक्षित preview में दिखते हैं। Preview
  पहले 1,024 encoded bytes तक सीमित है; लंबा text `…` के साथ दिखता है।
- Objects/records: dynamic class name, original Hindi field names और expandable
  field values मिलते हैं। Inherited fields शामिल हैं; duplicate field names के साथ
  layout offset दिया जाता है। Null `रिक्त` है। Cycles lazy expansion से inspect होते हैं।
- Arrays/lists: size और indexed elements; maps: insertion order में key/value pairs;
  packed byte buffers: byte values। एक expansion में पहले 100 entries दिखती हैं।
- `सर्ववस्तु`: boxed scalar/text के अंदर `मूल्य` expand करें; boxed object references
  उनके वास्तविक object/collection की तरह दिखते हैं।

Hindi names और original source locations DWARF से आते हैं। Runtime plumbing call-stack
filter से छिपती है; protected Bhash callbacks `प्रयास/पकड़ो/अंततः` label रखते हैं ताकि
उनके locals उपलब्ध रहें। Breakpoints executable lines पर ही रुकते हैं। Comments,
blank lines या generated constructor paths पर GDB नज़दीकी executable location चुन सकता है।
अलग lexical scopes में same-name locals का display अभी existing compiler debug-scope
सीमाओं के अधीन है।

Protected `प्रयास`/`पकड़ो`/`अंततः` bodies generated callbacks में चलती हैं। उनके अंदर
source breakpoint लगाकर locals inspect कर सकते हैं। Runtime boundary पर सामान्य GDB
stepping पूरा protected body चला सकती है; Bhash expression evaluation और exact lexical
scope reconstruction इस initial integration में नहीं जोड़े गए हैं।

## संग्रहकर्ता सुरक्षा (GC Safety)

Printer केवल stopped process की memory पढ़ती है। वह inferior functions नहीं बुलाती,
target में allocate नहीं करती, collection नहीं चलाती, GC roots नहीं जोड़ती/बदलती और
reference slots में नहीं लिखती। Mutable managed addresses stops के बीच cache नहीं
होते; continue/step के बाद GDB नई values से printers बनाता है। Immutable executable
schema ही cache होती है और executable/exit events पर cache खाली होती है।

Automatic Watch evaluation में inferior function calls disabled हैं। Reference editing,
arbitrary Bhash expression evaluation, conditional expressions that call functions,
और Debug Console से runtime calls इस milestone का हिस्सा नहीं हैं। GDB C expression
syntax को Bhash expression syntax न समझें। Manual debugger writes/commands से program
की invariants बदल सकती हैं; इस workflow में inspection उपयोग करें। Software breakpoints
के लिए GDB की code-memory writes चालू रहती हैं।

Debug-only immutable schema version 1, runtime ABI 15 तथा retained reference typedefs
field layouts पहचानते हैं। Normal release executables में यह schema नहीं है। Allocation
layout, collector policy, runtime fingerprint और application ownership contract नहीं
बदलते। Custom `bhash.compilerPath` को इस debug schema वाला compiler चाहिए।

## सत्यापन (Validation)

`scripts/test_native_inspection.py` real GDB में breakpoints, step/next/finish, Hindi
names, text/NUL/emoji, cycles, arrays/lists/maps/buffers, boxing और null जाँचती है। हर
inspection के पहले/बाद allocation/collection counters तथा exact root-slot snapshots
समान होने चाहिए। दूसरे stop से पहले 300 forced collections होती हैं; shutdown पर
`live=0` चाहिए। Inferior calls के निषेध को भी जाँचा जाता है।

`editors/vscode/test/debug-run.cjs` packaged VSIX को Hindi/quoted path में खोलकर actual
VS Code और Microsoft GDB adapter शुरू करता
है, DAP breakpoints/step-in/next/step-out तथा Variables/Call Stack requests जाँचता है,
फिर forced-GC run और per-session build cleanup सत्यापित करता है। Dedicated CI दोनों
Ubuntu 22.04 और 24.04 पर चलता है। Existing native runtime sanitizer tests collector
के memory-safety gates बने रहते हैं।

Debugger architecture: [VS Code debug configuration providers](https://code.visualstudio.com/api/references/vscode-api#DebugConfigurationProvider),
[Microsoft GDB launch settings](https://code.visualstudio.com/docs/cpp/launch-json-reference),
और [GDB Python printers](https://sourceware.org/gdb/current/onlinedocs/gdb.html/Pretty-Printing-API.html)।


## Windows पर स्वतः स्थापना (Automatic Windows Setup)

Compiler SDK और debugger की caches अलग हैं। Build/run करने पर debugger download नहीं
होता। Debug launch workspace trust तथा Microsoft C/C++ extension की जाँच के बाद ही
setup करती है। GDB/Python की archive और हर installed file का SHA-256 जाँचा जाता है;
असफल repair पिछले active installation को सुरक्षित रखती है। अगली launch offline cache
का उपयोग करती है। Setup cancel करने पर नया application launch नहीं होता।

Default debugger cache `%LOCALAPPDATA%\Bhash\debuggers` है; compiler toolchain cache
की custom location हो तो उसके sibling `debuggers` में रहती है। CLI/remote host पर
`BHASH_DEBUGGER_HOME` absolute path से बदल सकते हैं। `bhash.native.gdbPath` खाली रखने
पर verified managed debugger चुना जाता है, PATH पर उपलब्ध कोई अन्य gdb नहीं।

CLI से status और integrity जाँच:

```powershell
java -jar bhash-compiler.jar native debugger status --json
java -jar bhash-compiler.jar native debugger verify --json
```

GDB/Python package में compiler या C headers नहीं हैं। यह MSYS2 के unmodified Windows
packages से बनता है; licenses, package lock तथा corresponding source archives अलग
GitHub debugger release में रहते हैं। GDB की GPL licensing application को JVM/.NET
पर निर्भर नहीं बनाती; generated Bhash application अपने सामान्य runtime contract पर
चलती है। Windows debugger PE/DWARF symbols उपयोग करता है; PDB/MSVC debugger इस
integration में नहीं हैं।

Debugger Python environment ignore करती है और bytecode cache नहीं लिखती। User/system
GDB initialization तथा executable auto-load बंद हैं; केवल bundled Bhash inspector
स्पष्ट रूप से load होता है। Watch से inferior function calls बंद रहती हैं। Existing
Bhash-expression और protected-body stepping सीमाएँ Windows पर भी लागू होती हैं।
