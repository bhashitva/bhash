# नेटिव निर्माण और वितरण (Native builds and distribution)

Bhash का समर्थित नेटिव लक्ष्य अभी **Linux x86-64 + glibc** है। निर्माण के लिए Java 17+, मिलते हुए LLVM 18 `clang`/`opt`, C17 headers, system linker और binutils चाहिए। बने हुए प्रोग्राम को चलाने के लिए Java, .NET या LLVM की आवश्यकता नहीं है। Windows/macOS, ARM और musl इस milestone में समर्थित नहीं हैं; Windows पर x86-64 Linux/WSL में निर्माण करें।

## स्थापना की जाँच (Toolchain doctor)

Ubuntu 24.04 पर:

```sh
sudo apt-get update
sudo apt-get install clang-18 llvm-18 build-essential binutils
mvn --batch-mode --no-transfer-progress package
java -jar target/bhash-compiler-0.1.0-SNAPSHOT.jar native doctor
java -jar target/bhash-compiler-0.1.0-SNAPSHOT.jar native doctor --json
```

Doctor host/target, clang और opt का पूरा `18.x.y` संस्करण, runtime ABI, linker, glibc तथा C17 headers/math-library linking जाँचता है। दोनों LLVM tools का patch संस्करण भी मिलना चाहिए। जरूरत पड़ने पर `BHASH_CLANG`, `BHASH_LLVM_OPT` और `BHASH_READELF` में executable का नाम या पूरा path दें। Relative paths compiler के अस्थायी working directory से resolve होते हैं; किसी चुनी हुई installation के लिए absolute paths प्रयोग करें। Doctor प्रोग्राम को चलाता नहीं है।

| Code | अर्थ / अगला कदम |
| --- | --- |
| BH9101 | Host समर्थित नहीं; Linux x86-64 प्रयोग करें। |
| BH9102 | Tool नहीं मिला/चल नहीं सका; installation और executable path जाँचें। |
| BH9103 | LLVM गलत संस्करण या clang/opt अलग संस्करण; एक ही installation चुनें। |
| BH9104 | clang का target Linux x86-64 glibc नहीं है। |
| BH9105 | C headers/system linker/math-library probe असफल; build-essential/libc development files जाँचें। |
| BH9106 | Executable, dependencies, metadata या glibc सीमा असंगत है। |
| BH9108 | Compiler के bundled runtime का ABI गलत है; compiler फिर बनाएँ/स्थापित करें। |

## दोहराने योग्य निर्माण (Reproducible builds)

```sh
export SOURCE_DATE_EPOCH=1700000000
java -jar target/bhash-compiler-0.1.0-SNAPSHOT.jar compile examples/native/hello.bhash \
  --target native --source-root "$PWD" -o target/hello
```

`--source-root` सभी loaded source files को `/bhash/source/<relative-path>` नाम देता है। Runtime stack traces और optional DWARF में यही स्थिर नाम आते हैं। Root के बाहर sources, symlink से बाहर आने वाले sources समेत, अस्वीकार होते हैं; ऐसे dependencies के लिए सभी sources का साझा root चुनें। Dependencies को lockfile में pin करें और release से पहले `deps verify` चलाएँ। साधारण compile/run में यह flag वैकल्पिक है; `native package` इसे project root से अपने आप लागू करता है।

Compiler के temporary paths `/bhash/build` बनते हैं। CPU baseline `x86-64` है; build host के विशेष instructions अपने आप नहीं चुने जाते। ELF build ID deterministic SHA-1 linker ID है, integrity/security signature नहीं। Compiler JAR का archive timestamp POM में स्थिर है; package का timestamp `SOURCE_DATE_EPOCH` से आता है (default `0`, अनुमत 0..8589934591)।

**सीमा:** समान source, compiler JAR, JDK, LLVM patch, linker, glibc/sysroot तथा flags के साथ समान bytes का लक्ष्य है। अलग toolchain/sysroot से समान bytes का वादा नहीं है। Releases के लिए builder container को immutable image digest और dependency/tool package versions पर pin करें। वर्तमान CI Ubuntu 24.04 integration builder है; वह अपने आप hermetic, हमेशा समान package repository नहीं बनता। Network से mutable latest packages लेकर केवल timestamp बदलना पर्याप्त नहीं है।

CI अलग workspace paths (spaces और हिन्दी समेत) में release और debug executables तथा archives की byte-for-byte समानता जाँचता है।

## Runtime संगतता (Compatibility inspection)

```sh
java -jar target/bhash-compiler-0.1.0-SNAPSHOT.jar native inspect target/hello --json
java -jar target/bhash-compiler-0.1.0-SNAPSHOT.jar native inspect target/hello --require-runtime-match
./target/hello --bhash-build-info
```

Inspector `readelf` से ELF header, loader, dynamic libraries, GLIBC symbol requirements और `.bhash.build` section पढ़ता है। वह input executable या `ldd` नहीं चलाता। `--bhash-build-info` executable का अपना विकल्प है: वह JSON provenance देता है, user का मुख्य कार्यक्रम नहीं चलाता।

Metadata में format/target, internal ABI **15**, runtime C/header SHA-256, compiler version/JAR SHA-256, LLVM patch, linker, build glibc और debug/release mode हैं। यह embedded C runtime है; अलग shared Bhash runtime installation की जरूरत नहीं। `bh_runtime_abi_v15` link guard compiler/runtime version का गलत संयोजन link करते समय रोकता है। ABI बदलने पर पुराने executable को वर्तमान compiler से inspect/package करने के बजाय फिर build करें।

Allowed system dependencies: libc, libm, libgcc_s, libpthread, libdl, librt। Sanitizer builds वितरण में स्वीकार नहीं होते। Runtime fingerprints का अंतर सामान्य inspection में report होता है; `--require-runtime-match` और packaging में अस्वीकार होता है। Metadata/checksums provenance बताते हैं, publisher authentication नहीं; इन्हें digital signature न मानें। Inspector loader/dependencies/ABI की जाँच है, arbitrary program की सुरक्षा या प्रत्येक Linux kernel की compatibility का प्रमाण नहीं।

## वितरण पैकेज (Distribution package)

```sh
java -jar target/bhash-compiler-0.1.0-SNAPSHOT.jar native package examples/native/hello.bhash \
  --name hello --version 1.0.0 -o target/hello-1.0.0-linux-x86_64.tar.gz
# किसी project के लिए: native package ./my-app ...
# Debug archive: ऊपर के command में --debug जोड़ें।
```

Archive बनाने से पहले executable की static compatibility और bundled runtime fingerprints जाँचे जाते हैं। `--max-glibc` का default `2.39` है। इससे ज्यादा नया glibc चाहिए तो निर्माण विफल होता है; older Linux के लिए सही पुराने sysroot/builder में build करें और उचित सीमा दें। यह विकल्प executable की वास्तविक dependencies बदलता नहीं है।

Distribution name/version 1–32 ASCII letters/digits/dot/dash/underscore हैं; version में `+` भी अनुमत है। ये archive/binary के नाम हैं; आपके source identifiers और file names हिन्दी रह सकते हैं। Archive में executable, manifest, source/executable hashes, licenses, `SHA256SUMS`, `check-host.sh` और `run.sh` आते हैं। सभी entries का क्रम, modes, uid/gid और timestamps स्थिर हैं। सफल होने पर CLI पूरे `.tar.gz` का SHA-256 भी छापता है। असफल build/package पहले से मौजूद output को नहीं बदलता। Compiler/package output source files को overwrite नहीं कर सकता।

Receiving Linux machine पर:

```sh
tar -xzf hello-1.0.0-linux-x86_64.tar.gz
cd hello-1.0.0-linux-x86_64
sha256sum -c SHA256SUMS
./check-host.sh
./run.sh
```

Launcher Linux/x86-64, glibc loader और minimum glibc version जाँचकर कार्यक्रम को arguments के साथ चलाता है। Java/.NET/LLVM की जरूरत नहीं। glibc system dependency बनी रहती है; executable पूर्ण static binary नहीं है। Archive का top-level SHA-256 trusted channel से मिलाएँ; अंदर के checksums corruption पहचानते हैं। Signing, automatic installer/update, public binary releases, cross-compilation और fully pinned builder image आगे के काम हैं। CI archive review/testing artifact है; यह कोई production deployment या automatic public release नहीं करता।

Debug source paths के लिए:

```gdb
set substitute-path /bhash/source /absolute/path/to/source-root
```

आगे की debugger जानकारी [native diagnostics](native-diagnostics.md) में है।
