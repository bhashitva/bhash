# प्रबंधित नेटिव टूलचेन (Managed native toolchain)

यह VS Code में native support जोड़ने की तैयारी है। इस milestone में extension UI नहीं बदलता। Compiler के `compile --target native`, `run --target native`, `native doctor`, `native inspect` और `native package` एक user-local managed installation खोज सकते हैं।

## आवश्यकता और दायरा (Requirements and scope)

Managed bundle के लिए **Linux x86-64, host glibc 2.39+ और Java 17+** चाहिए। Windows पर VS Code/terminal को Ubuntu 24.04+ WSL workspace में चलाएँ। LLVM, GCC, binutils, C development headers या administrator/root access उपभोक्ता मशीन पर आवश्यक नहीं हैं। Java अभी Bhash compiler/language server के लिए आवश्यक है; बने हुए executable को Java/LLVM की जरूरत नहीं होती। ARM, musl, native Windows और macOS compiler hosts इस bundle में समर्थित नहीं हैं। पुराने glibc वाले Linux पर compatible system toolchain विकल्प बना रहता है।

Bundle में matching LLVM 18 clang/opt, LLD linker, llvm-readelf, इन tools की private runtime libraries, Clang resource files तथा glibc/GCC/Linux headers और startup/link libraries का sysroot है। केवल LLVM libraries देना पर्याप्त नहीं होता। Tools की private libraries `LD_LIBRARY_PATH` में केवल उनके child process के लिए लगती हैं; generated application में LLVM dependency नहीं जुड़ती। Managed clang system include/library override variables साफ करके अपने sysroot का प्रयोग करता है।

## स्थापना (Installation)

इस milestone का परीक्षण किया हुआ bundle और matching sources **Bhash managed native toolchain** CI artifact में बनते हैं। यह अभी automatic public toolchain release/catalog नहीं है। भविष्य की extension installation उसी installer को stable published release URL और pinned digest दे सकती है। CI artifact download करने के बाद compiler JAR, binary ZIP और उसकी `.sha256` file मिलेंगे। SHA-256 को trusted release/build output से मिलाएँ।

```sh
java -jar bhash-compiler.jar native toolchain install \
  --archive bhash-linux-x86_64-llvm18-v1.zip --sha256 <trusted-64-digit-sha256>
java -jar bhash-compiler.jar native toolchain status --json
java -jar bhash-compiler.jar native toolchain verify
java -jar bhash-compiler.jar native doctor --json
java -jar bhash-compiler.jar compile hello.bhash --target native -o hello
java -jar bhash-compiler.jar run hello.bhash --target native
```

Hosted release asset होने पर:

```sh
java -jar bhash-compiler.jar native toolchain install \
  --url https://your-publisher.example/releases/toolchain.zip --sha256 <trusted-64-digit-sha256>
```

यह illustrative URL है, available Bhash download endpoint नहीं। Installer HTTPS redirects को अधिकतम पाँच बार follow करता है; HTTP downgrade, credentials और fragments स्वीकार नहीं करता। TLS certificate verification चालू रहती है। Download size/time और ZIP extraction limits लागू होते हैं। Offline installation के लिए `--archive` है। स्थापित होने के बाद build/run/package कोई toolchain download नहीं करते।

## Cache और चयन (Cache and selection)

Default cache `$XDG_CACHE_HOME/bhash/toolchains` या `~/.cache/bhash/toolchains` है। `BHASH_TOOLCHAIN_HOME` से absolute private cache directory चुन सकते हैं। Extension भविष्य में अपने storage directory का path दे सकेगा। यह setting system directories में packages स्थापित नहीं करती।

| विकल्प | व्यवहार |
| --- | --- |
| `BHASH_NATIVE_TOOLCHAIN=auto` (default) | Installed managed toolchain, अन्यथा system tools। |
| `BHASH_NATIVE_TOOLCHAIN=managed` | Managed installation जरूरी; अनुपस्थित/corrupt installation पर स्पष्ट error। |
| `BHASH_NATIVE_TOOLCHAIN=system` | पहले वाला system clang-18/opt-18 workflow। |
| `BHASH_CLANG`, `BHASH_LLVM_OPT`, `BHASH_READELF` | Explicit overrides auto mode में system selection चुनते हैं; managed mode में ambiguity error मिलता है। |

Corrupt managed installation पर auto mode silently system tools पर नहीं जाता। `native toolchain verify` पूरी file inventory/hash/permissions जाँचता है। Doctor tools के versions/target और वास्तविक C17 compilation/linking जाँचता है। हर ordinary build में पूरी बड़ी SDK को फिर hash नहीं किया जाता; manifest receipt और actual tool versions जाँचे जाते हैं। Cache user-owned होना चाहिए; checksums publisher signatures या दूसरे local users के विरुद्ध security boundary नहीं हैं।

## अखंडता और recovery (Integrity and recovery)

Installer archive SHA-256 जाँचने के बाद एक temporary directory में files निकालता है। Absolute paths, traversal, reserved/duplicate files, missing/extra files, wrong targets और mismatched file hashes अस्वीकार होते हैं। ZIP symlinks बनाए नहीं जाते। सभी files verify होने पर candidate toolchain का doctor चलता है। उसके सफल होने के बाद एक नया installation directory और atomic `active.json` replacement इस्तेमाल होता है।

Failed download, extraction या doctor पुराना active pointer नहीं बदलते। उसी trusted archive को फिर install करके corrupt cache से recovery कर सकते हैं। पुराने installation directories रखे जाते हैं ताकि चल रही builds प्रभावित न हों; इस milestone में automatic garbage collection/uninstall नहीं है। Unused directories तभी हटाएँ जब कोई build उन्हें इस्तेमाल न कर रही हो।

`BH9110` installation/cache/integrity errors का code है। `BH9111` managed tools के लिए बहुत पुराने host glibc का code है। LLVM/system setup के existing `BH9101`–`BH9108` diagnostics भी लागू हैं।

## Bundle निर्माण और licenses (Building and redistribution)

```sh
docker build -f scripts/native-toolchain.Dockerfile -t bhash-toolchain-builder .
mkdir -p target/managed-toolchain
docker run --rm -v "$PWD/target/managed-toolchain:/out" bhash-toolchain-builder
```

Builder Ubuntu package snapshot `20261001T000000Z` पर dependencies resolve करता है, package/source versions दर्ज करता है और हर copied file का hash रखता है। LLVM patch versions clang/opt में match होने चाहिए। ZIP ordering, permissions metadata और timestamps स्थिर हैं; builder समान payload दो बार archive करके hashes compare करता है। यह पूरा Docker image byte-for-byte reproducible होने का दावा नहीं है। Bundle byte identity verified artifact SHA से pin होती है।

Binary ZIP के साथ **matching `-sources.zip`** और checksum files बनते हैं। Source archive में bundled third-party package versions के corresponding upstream/Ubuntu sources आते हैं। `licenses/`, `packages.json` और `sources.json` notices और source archive digest रखते हैं। Public release/extension distribution में दोनों archives और notices साथ उपलब्ध रखें; Bhash का Apache license third-party licenses को replace नहीं करता। Source archive install/runtime के लिए नहीं चाहिए। CI artifacts अस्थायी हैं; production extension delivery से पहले permanent release assets प्रकाशित करना जरूरी है।

## Clean-machine सत्यापन (Clean-machine validation)

CI consumer Ubuntu image में केवल Java runtime स्थापित करता है, root से अलग user चलता है और container network disabled रहता है। Test पहले system clang/opt/GCC/ld/readelf और C headers/development packages की अनुपस्थिति assert करता है। फिर archive install करके native build/run/package, inspection, real text/objects/generics/records/JSON/bytes/files/math/exceptions programs तथा forced-GC cleanup जाँचता है। अलग cache path पर debug executable के identical bytes और failed installation के बाद पुराने active pointer का बने रहना भी जाँचा जाता है।

Executable वितरण की जानकारी [native releases](native-releases.md) में है।
