# Windows x64 native compilation

## लक्ष्य और निर्णय (Target and decisions)

Windows 10 और बाद के x64 Windows पर भाष अब सीधे `.exe` बना सकता है। Compiler को
Java 17+ चाहिए; बने हुए application को JVM, .NET, LLVM, WSL या Visual Studio नहीं चाहिए।
Linux/WSL support जारी रहता है। macOS, ARM और cross compilation इस milestone में नहीं आते।

- Target `x86_64-w64-windows-gnu` है। SDK official LLVM-MinGW `20240619`, LLVM
  `18.1.8` और Windows UCRT इस्तेमाल करता है। Microsoft SDK को repack नहीं किया जाता।
- Windows का C data model LLP64 है: `long` 32-bit हो सकता है। Runtime interface
  fixed-width `int64_t`, `uint64_t` और 64-bit pointers इस्तेमाल करता है। LLVM/C
  calling convention Windows target से चुनी जाती है; Linux calling convention नहीं थोपी जाती।
- Precise, stop-the-world, nonmoving collector और root contracts वही हैं। References,
  static roots, exception traces और nested collections को allocation stress के साथ जाँचा जाता है।
- Exception boundary C `setjmp`/`longjmp` रखती है। Protected callback से लौटते समय
  roots, resource cleanups और Bhash trace frames बहाल होते हैं। LLVM Windows unwind
  information बनाता है; Linux unwind implementation को copy नहीं किया जाता।
- Windows SDK में separate `opt` नहीं है। LLVM IR compilation में Clang का
  `-Xclang -llvm-verify-each` verifier चलता है। Linux का existing `opt` verification भी बना रहता है।

## स्वतः स्थापना (Automatic setup)

VS Code में Native चुनकर Build या Run करें। पहली बार extension Windows SDK का permanent
GitHub Release asset download करके उसका pinned SHA-256 और प्रत्येक file का hash जाँचती है।
Tools user cache `%LOCALAPPDATA%\Bhash\toolchains` में रहते हैं। Administrator access,
PATH modification और अलग C development packages की आवश्यकता नहीं है। WSL workspace
में Linux SDK ही चुना जाता है। Repair असफल होने पर previous active SDK सुरक्षित रहता है।

CLI से offline installation भी संभव है:

```powershell
java -jar bhash-compiler.jar native toolchain install --archive bhash-windows-x86_64-llvm18-v1.zip --sha256 b0d5934ea134e500ff598f737c40b3076400e85e5760a22bfcc28f7aaf321f3f
java -jar bhash-compiler.jar native doctor --json
java -jar bhash-compiler.jar compile hello.bhash --target native
.\hello-native.exe
java -jar bhash-compiler.jar run hello.bhash --target native
```

`BHASH_TOOLCHAIN_HOME` या VS Code `bhash.native.toolchainHome` cache बदल सकता है।
`system` mode के लिए matching LLVM-MinGW 18/UCRT toolchain चाहिए। MSVC-targeting
`clang` अकेला पर्याप्त नहीं है; doctor target mismatch बताता है। `BHASH_CLANG` और
`BHASH_READELF` system mode में compiler और `llvm-readobj.exe` के paths चुन सकते हैं।
Managed mode में tool overrides मिलाने की अनुमति नहीं है।

VS Code launcher compiler arguments को UTF-8 JSON/ASCII envelope में भेजता है।
इससे Windows Java launcher का ANSI code-page conversion Hindi paths को नहीं बदलता।
Compiler JAR को उसके directory से relative name के साथ launch किया जाता है और
application का original working directory सुरक्षित रहता है। Windows regional settings
बदलने की आवश्यकता नहीं है। Windows cache directory names में checksum का छोटा prefix
और unique ID रहता है, ताकि executable paths छोटे रहें; receipts और verification पूरा
SHA-256 रखते हैं।

## पाठ और फ़ाइलें (Text and files)

भाष text UTF-8 रहता है। Windows console input/output को UTF-8 code page में चलाया जाता है;
redirected streams binary mode रखते हैं ताकि bytes और LF line endings न बदलें। `पाठपढ़ो()`
CRLF input भी स्वीकारता है। Unicode file paths OS boundary पर UTF-16 में बदलकर
`_wfopen`/Windows wide APIs को दिए जाते हैं। File contents UTF-8 या requested binary
bytes रहते हैं। Temporary path buffers exception उठाने से पहले मुक्त होते हैं।

## वितरण और जाँच (Distribution and validation)

```powershell
java -jar bhash-compiler.jar native inspect hello-native.exe --json --require-runtime-match
java -jar bhash-compiler.jar native package hello.bhash -o hello.zip --name hello --version 1.0 --source-root .
```

Windows package deterministic ZIP है; Linux package `.tar.gz` बना रहता है। ZIP में `.exe`,
manifest, hashes, Bhash licenses और MinGW runtime notices आते हैं। `--max-glibc` केवल
Linux के लिए है। Executable में MinGW runtime notices भी embedded रहते हैं और
`--bhash-licenses` से देखे जा सकते हैं।

Inspector PE32+ x64 console executable, Windows/UCRT imports, Bhash metadata और
runtime fingerprints पढ़ता है; input program को execute नहीं करता। JVM/.NET, LLVM
या MinGW private DLL dependencies वाले release binaries अस्वीकार होते हैं। SDK tools
के अपने private DLLs SDK के अंदर रहते हैं; वे generated application की dependencies नहीं हैं।

PE timestamp हटाया जाता है, build/source paths map होते हैं और command-line paths debug
producer में record नहीं होते। `--debug` Windows GNU/DWARF symbols रखता है।
Windows 10 version 1903+ पर VS Code extension 0.9.0 से source breakpoints, stepping
और read-only variable inspection के साथ [Windows native debugging](native-vscode-debug.md)
उपलब्ध है। GDB/Python अपने आप अलग verified cache में स्थापित होते हैं। PDB/MSVC
debugger delivery अभी शामिल नहीं है।

CI pinned Python 3.11.9 से SDK दो बार बनाकर catalog SHA मिलाती है। Windows JVM/CLR/native
conformance tests, standalone C runtime tests और forced-GC examples चलते हैं। अलग
Windows Server Core container में WSL, LLVM, Visual Studio और C development headers
की अनुपस्थिति assert होती है, फिर SDK setup, build/run, Unicode paths/input, offline
cache reuse और failed-install rollback जाँचे जाते हैं। तीसरा container Java/.NET/Node/SDK
mount किए बिना generated `.exe` चलाता है।

Builder: `python scripts/build_windows_toolchain.py upstream.zip target/windows-sdk`.
Upstream binary archive पहले pinned SHA से verify होता है; redistribution में LLVM और
MinGW license notices सुरक्षित रहते हैं। SDK provenance file upstream download और
source locations दर्ज करती है। Toolchain version बदलने पर नया immutable asset और
catalog hash आवश्यक हैं।
