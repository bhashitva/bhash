# macOS Apple Silicon native compilation

## लक्ष्य और आवश्यकताएँ (Target and prerequisites)

macOS 14 और बाद के Apple Silicon Mac पर भाष सीधे ARM64 executable बनाता है।
Target `arm64-apple-macosx14.0.0` है। Intel Mac, Rosetta में चलने वाला x64 extension
host, iOS और cross compilation इस milestone में शामिल नहीं हैं। ARM64 VS Code और
ARM64 Java 17+ इस्तेमाल करें। Compiler को Java चाहिए; बने हुए application को JVM, .NET,
LLVM या Apple development tools नहीं चाहिए। Application macOS के `libSystem` से जुड़ता है।

एक बार Apple's Command Line Tools स्थापित करें:

```sh
xcode-select --install
xcrun --sdk macosx --show-sdk-path
```

Apple के installation और license prompts स्वयं पूरे करें। Full Xcode भी उपयुक्त SDK
दे सकता है। Bhash Apple SDK, system headers या Apple linker को अपने download में
redistribute नहीं करता। Managed setup केवल official LLVM 18.1.8 के ARM64 tools,
resource headers/libraries, licenses और provenance देता है।

## VS Code और CLI (Build and run)

VS Code में **Native** चुनें और Build या Run करें। Extension पहले macOS version और
Command Line Tools जाँचता है, फिर pinned SHA-256 वाला Bhash LLVM SDK स्थापित करता है।
Cache `~/Library/Caches/Bhash/toolchains` में रहता है। बाद के builds उसी verified cache
का उपयोग करते हैं; `Bhash: Repair Native Tools` repair कर सकता है।
`bhash.native.toolchainHome` या `BHASH_TOOLCHAIN_HOME` cache का स्थान बदल सकता है।

```sh
java -jar bhash-compiler.jar native doctor --json
java -jar bhash-compiler.jar compile hello.bhash --target native -o hello
./hello
java -jar bhash-compiler.jar run hello.bhash --target native
java -jar bhash-compiler.jar native inspect ./hello --json --require-runtime-match
```

Offline setup में [versioned SDK release](https://github.com/bhashitva/bhash/releases/tag/native-sdk-macos-1.0.0) का ZIP और उसका published SHA-256 इस्तेमाल करें:

```sh
java -jar bhash-compiler.jar native toolchain install \
  --archive bhash-macos-arm64-llvm18-v1.zip --sha256 c92917d04e8514ed497173326fafa138ba1b9767ac17a0f88b568d6b6ee3e22d
```

System mode (`BHASH_NATIVE_TOOLCHAIN=system`) में matching LLVM 18 `clang-18`,
`opt-18`, `llvm-readobj` और debug builds के लिए `dsymutil` चाहिए। `BHASH_CLANG`,
`BHASH_LLVM_OPT`, `BHASH_READELF`, `BHASH_DSYMUTIL` उनके paths चुन सकते हैं। Apple's
Clang और LLVM 18 एक ही version नहीं हैं; केवल `xcode-select --install` करने से system
mode की LLVM 18 आवश्यकता पूरी नहीं होती।

## ABI और स्मृति प्रबंधन (Runtime contract)

Existing native language features ARM64 पर भी वही semantics रखते हैं। Integers और
references 64-bit हैं; runtime interfaces fixed-width C types और LLVM की Darwin ARM64
calling convention इस्तेमाल करते हैं। Precise, stop-the-world, nonmoving collector का
ABI और root contract वही है। Static references, nested collections, text, boxing,
exceptions और exception traces forced collections में सुरक्षित रहने चाहिए। `setjmp`/
`longjmp` के साथ root, resource cleanup और trace frames बहाल होते हैं। JVM/CLR/native
conformance tests और C runtime के optimized/sanitized tests इन contracts को जाँचते हैं।

## वितरण और debugging (Distribution and symbols)

```sh
java -jar bhash-compiler.jar native package hello.bhash \
  --name hello --version 1.0.0 -o hello-macos-arm64.tar.gz
tar -xzf hello-macos-arm64.tar.gz
cd hello-1.0.0-macos-arm64
shasum -a 256 -c SHA256SUMS
./run.sh
```

Archive में executable, manifest, source/runtime fingerprints, notices, host check और
checksums हैं। Static Mach-O inspection target, deployment minimum, runtime ABI और
केवल `/usr/lib/libSystem.B.dylib` dependency स्वीकार करता है। Sanitized binaries,
embedded library search paths और incompatible targets release distribution में अस्वीकार होते हैं।
`--max-glibc` केवल Linux का विकल्प है। Reproducibility समान compiler, LLVM और Apple
SDK/linker inputs पर लागू होती है; अलग Apple SDK versions के बीच समान bytes का दावा नहीं है।

`--debug` adjacent `<executable>.dwarf` symbol file देता है। इसे executable के साथ रखें।
Debug packages में `symbols/<name>.dwarf` रहता है। Direct debug build के लिए:

```sh
java -jar bhash-compiler.jar compile hello.bhash --target native --debug --source-root . -o hello
```

LLDB में symbols और source mapping दें:

```text
target create ./hello
target symbols add ./hello.dwarf
settings set target.source-map /bhash/source /absolute/source/root
breakpoint set --file hello.bhash --line 1
run
```

Local executables और SDK tools ad-hoc signed हैं। यह Developer ID signing या Apple
notarization नहीं है। Public download के लिए distributor को अपने Apple credentials और
Apple की signing/notarization प्रक्रिया अलग से अपनानी होगी। Bhash Gatekeeper settings
नहीं बदलता और quarantine protection नहीं हटाता। Checksums signatures का विकल्प नहीं हैं।

Minimum macOS 14 पर native/JVM/CLR conformance, forced GC और release/debug packaging
जाँचे गए हैं। GitHub के macOS 14 runner retirement के बाद ongoing CI macOS 15 और 26
पर चलता है। SDK और linker स्थानीय Apple installation से आते हैं; अलग SDK versions
के बीच byte-for-byte समान executable का दावा नहीं है।

### macOS 26 sanitizer validation

LLVM 18 का AddressSanitizer runtime macOS 26.4+ पर `main()` से पहले
[deadlock कर सकता है](https://github.com/llvm/llvm-project/issues/200447)।
सामान्य Bhash applications इस sanitizer को link नहीं करते। macOS 26 CI में
managed LLVM 18 से पूरे runtime suite के सामान्य builds और current Apple Clang
(Xcode/Command Line Tools 26.4+) से उसी suite के ASan/UBSan builds दोनों अनिवार्य
हैं। macOS 15 पर LLVM 18 sanitizer validation भी जारी रहती है। इस कारण
forced-GC या sanitizer checks छोड़े नहीं जाते।

macOS 26 पर सारे Bhash/JVM/CLR conformance cases भी production LLVM 18 mode
में चलते हैं; forced-GC और live-root assertions बने रहते हैं। पुराने LLVM
ASan को उन generated programs में link नहीं किया जाता। macOS 14/15 और Linux
पर generated-program sanitizer coverage भी बनी रहती है।
