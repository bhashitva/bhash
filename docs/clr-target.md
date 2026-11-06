# .NET CLR target

## Build and run

Install a .NET 10 SDK, JDK 17+ and Maven 3.9+. From the repository root:

**हिन्दी:** .NET 10 SDK, JDK 17+ और Maven 3.9+ स्थापित करें। रिपॉज़िटरी की जड़ से चलाएँ:

```bash
mvn clean verify
java -jar target/bhash-compiler-0.1.0-SNAPSHOT.jar compile examples/factorial-console.bhash --target clr -o target/factorial-clr
dotnet target/factorial-clr/BhashProgram.dll
```

Or compile/run in one step:

**हिन्दी:** या एक ही आदेश में संकलन और निष्पादन करें:

```bash
java -jar target/bhash-compiler-0.1.0-SNAPSHOT.jar run examples/factorial-console.bhash --target clr
```

The Bhash source is unchanged from the JVM example. Enter `5` or `५` to receive
`गुणनफल: १२०`. The factorial example guards its 0–20 input domain; the language's
integer type is signed 64-bit. Malformed input and EOF produce a concise UTF-8
error with exit code 1. Both backends use one integer per line, either digit script,
and Devanagari integer output.

**हिन्दी:** JVM वाला भाष स्रोत यहाँ भी चलता है। `5` या `५` देने पर `गुणनफल: १२०` मिलता है। उदाहरण ०–२० की सीमा जाँचता है। पूर्णांक signed 64-bit है। गलत इनपुट या EOF पर छोटा UTF-8 त्रुटि संदेश और exit code 1 मिलता है। दोनों लक्ष्य प्रति पंक्ति एक पूर्णांक लेते हैं और पूर्णांक देवनागरी में दिखाते हैं।

## Output and prerequisites

The compiler remains written in Java. It lowers type-checked Bhash into C# and
invokes `dotnet publish` to emit CIL in `BhashProgram.dll`. Copy the complete output
folder, including `.deps.json` and `.runtimeconfig.json`, when deploying it.
The result runs on the .NET 10 runtime without Java, Maven or the .NET SDK.
This is modern cross-platform .NET, not Windows-only .NET Framework or native AOT.

**हिन्दी:** Java में लिखा संकलक जाँचे हुए भाष स्रोत से C# बनाता है और `dotnet publish` से `BhashProgram.dll` में CIL तैयार करता है। तैनाती में `.deps.json` और `.runtimeconfig.json` समेत पूरा आउटपुट फ़ोल्डर ले जाएँ। ऐप को .NET 10 रनटाइम चाहिए। Java, Maven और .NET SDK निर्माण के उपकरण हैं। यह कई ऑपरेटिंग सिस्टम पर चलने वाला आधुनिक .NET है।

Use `dotnet --list-sdks` to check that a 10.x SDK is installed. If dotnet is outside
PATH, set `BHASH_DOTNET` to the full executable path. For example in PowerShell:

**हिन्दी:** `dotnet --list-sdks` से 10.x SDK की जाँच करें। `dotnet` PATH में न हो तो `BHASH_DOTNET` में executable का पूरा पथ दें। PowerShell का उदाहरण:

```powershell
$env:BHASH_DOTNET = 'C:\Program Files\dotnet\dotnet.exe'
```

The default target remains JVM. CLR's `-o` names an output **directory**, not a DLL.
Without `-o`, the directory is `<source-stem>-clr` beside the input file.
Rebuilding is supported for directories made by Bhash; a `.bhash-clr-output` marker
tracks ownership. Unmanaged directories, unrelated contents and symlink outputs
are rejected. Failed builds preserve earlier output. Newly generated C# and build
logs are temporary and are not included in the deployed output.

**हिन्दी:** डिफ़ॉल्ट लक्ष्य JVM है। CLR में `-o` आउटपुट फ़ोल्डर का नाम है। इसे छोड़ने पर स्रोत के पास `<source-stem>-clr` बनता है। `.bhash-clr-output` चिह्न से Bhash अपना बनाया फ़ोल्डर पहचानता है। दूसरे फ़ोल्डर, अतिरिक्त फ़ाइलें और symlink आउटपुट अस्वीकार होते हैं। विफल निर्माण पुराना आउटपुट बचाता है। बीच का C# और लॉग तैनाती में नहीं जाते।

The generated project has no external packages and clears NuGet feeds. It uses
reference packs from the installed .NET 10 SDK. Compilation times out after 120
seconds. SDK failures include the build diagnostics; missing dotnet explains how
to install/configure the prerequisite.

**हिन्दी:** निर्मित प्रोजेक्ट SDK में पहले से मौजूद reference packs उपयोग करता है और NuGet feeds साफ़ करता है। संकलन की समयसीमा १२० सेकंड है। SDK विफलता के साथ निर्माण निदान और SDK अनुपलब्ध होने पर स्थापना या विन्यास निर्देश मिलते हैं।

## Semantic portability

The common checker enforces the same entry point, types, immutable bindings,
name resolution and control-flow rules. CLR lowering uses unchecked integer
arithmetic and runtime helpers for division/remainder so wrapping, zero errors
and MIN/-1 behavior match JVM. Text equality is ordinal; console input validates
strict UTF-8 and matches Java's whitespace stripping.

**हिन्दी:** साझा जाँचकर्ता प्रवेश फलन, प्रकार, अचल बंधन, नाम और नियंत्रण प्रवाह के एक जैसे नियम लागू करता है। CLR में पूर्णांक overflow, शून्य से भाग और न्यूनतम पूर्णांक को `-1` से भाग देने का व्यवहार JVM जैसा है। पाठ समानता क्रमिक है और कंसोल इनपुट में सख्त UTF-8 तथा Java जैसे खाली जगह हटाने के नियम हैं।

Binary64 arithmetic works on both targets. Real-number display uses each host's
round-trip digits, normalized to the same punctuation/exponent style; some values
can differ in their last displayed digits from Java 17. This does not affect the
integer factorial program. Arbitrary-precision factorials, exact real formatting
parity, additional .NET versions and native/self-contained deployment are future work.

**हिन्दी:** दोनों लक्ष्य binary64 गणना करते हैं। प्रदर्शन में दोनों के अंक और विराम चिह्न समान ढंग से सजते हैं, लेकिन कुछ मानों के अंतिम अंक Java 17 से अलग हो सकते हैं। पूर्णांक गुणनफल पर इसका असर नहीं है। मनमानी सटीकता, बिल्कुल समान दशमलव प्रदर्शन और अन्य .NET संस्करण आगे के विषय हैं।

The same backend test corpus and packaged factorial checks run under .NET and
Java on Windows and Linux in GitHub Actions. CLR tests skip locally when SDK 10
is absent; `BHASH_REQUIRE_CLR=true` makes missing SDK support fail as it does in CI.

**हिन्दी:** GitHub Actions में Windows और Linux पर Java तथा .NET के साथ वही परीक्षण और packaged गुणनफल जाँच चलती हैं। स्थानीय SDK 10 न होने पर CLR परीक्षण छोड़े जाते हैं। `BHASH_REQUIRE_CLR=true` देने पर अनुपलब्ध SDK को CI जैसी विफलता माना जाता है।

Generated output also includes `LICENSE`, `NOTICE` and `LICENSING.md` for embedded Bhash code.
The v2 ownership marker permits rebuilding earlier v1 outputs only when they contain no
extra files; user-added licensing files in an older output are preserved by refusing replacement.

**हिन्दी:** आउटपुट में अंतर्निहित भाष कोड के `LICENSE`, `NOTICE` और `LICENSING.md` भी मिलते हैं। v2 चिह्न पुराने v1 फ़ोल्डर को तभी दोबारा बना सकता है जब उसमें अतिरिक्त फ़ाइल न हो। उपयोगकर्ता की जोड़ी गई लाइसेंस फ़ाइल होने पर फ़ोल्डर नहीं बदला जाता।
