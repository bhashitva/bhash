# प्रकारयुक्त C पुस्तकालय बाइंडिंग (Typed C-library bindings)

[English version](typed-c-library-bindings.en.md)

यह मार्गदर्शिका binding लेखकों के लिए है। [स्वीकृत हिन्दी डिज़ाइन](typed-c-interop-design.md)
और [English design](typed-c-interop-design.en.md) भाषा तथा runtime का अनुबंध हैं।
सामान्य उपयोगकर्ता [संपीड़न ट्यूटोरियल](../tutorial/compression/README.md) से शुरू करें।

## घोषणा और परियोजना (Contract and project)

`bhash.ffi.json` schema 1, ABI 1 में namespace, version, functions, fixed wire
types, ownership, errors और प्रत्येक host के artifact hashes दर्ज होते हैं।
प्रोजेक्ट के `bhash.project` में `ffi=bhash.ffi.json` दें।
सत्यापित उदाहरण [counter contract](../src/test/ffi/counter/bhash.ffi.json),
[C implementation](../src/test/ffi/counter/fixture.c) और
[भाष उपयोग](../src/test/ffi/counter/program.bhash) हैं। यह उदाहरण संपीड़न से
अलग पुस्तकालय है; compiler उसके नाम के लिए विशेष नियम नहीं रखता।

```sh
java -jar bhash-compiler.jar deps lock path/to/project
java -jar bhash-compiler.jar deps restore path/to/project
java -jar bhash-compiler.jar deps verify path/to/project --offline
java -jar bhash-compiler.jar ffi prepare path/to/bhash.ffi.json -o generated-binding
```

लॉकफ़ाइल (Lockfile) format 2 में contract और artifact inventory दोनों की
पहचान रहती है। केवल source packages वाला पुराना format 1 चलता है। source,
signature या artifact बदलने पर जानबूझकर `deps lock` करें। संपादक/compile में
कोई download नहीं होता। स्पष्ट restore HTTPS से घोषित `url` लाता है, SHA-256
मिलने पर ही cache में स्वीकार करता है। `--offline` और verify कभी network
नहीं चलाते। `ffi verify manifest` static तथा shared host assets जाँचता है।

आर्टिफ़ैक्ट (Artifact) में `target`, `kind` (`static` या `shared`), `path`,
`sha256`, `sourceSha256`, `license`, तथा वैकल्पिक HTTPS `url` हैं। स्थानीय
फ़ाइल मौजूद हो तो उसी का hash मिलना चाहिए; खराब फ़ाइल को चुपचाप बदलना नहीं
होता। URL बदलना या नया checksum स्वीकार करना एक reviewed dependency change है।
भाष declaration बनाते समय manifest की जानकारी से compiler AST बनाता है;
खाली application functions को parsing करके बाहरी API होने का भ्रम नहीं देता।

## स्वामित्व और सीमाएँ (Ownership and limits)

मापदंड signed fixed-width integers, byte, boolean, 64-bit double, UTF-8,
bytes या opaque owned resource हैं। copy-borrow buffers केवल call के लिए
हैं। UTF-8 में embedded NUL लंबाई सहित सुरक्षित है। कोई Bhash heap pointer,
raw pointer, callback, varargs, C++ ABI या arbitrary struct प्रकाशित नहीं है।
enum की declared values जाँची जाती हैं; bool ठीक 0/1 होना चाहिए। bounded
`length` wire 0..16 MiB है, सामान्य unsigned64 नहीं।

`bhffi_v1_call_begin/end` का gateway detached native buffers और limits सँभालता
है। C implementation context allocator का प्रयोग करे। लौटे owned output को
उसी घोषित release function से मुक्त किया जाता है, conversion विफल होने पर
भी। resource constructor पहले cleanup record reserve करे, फिर vendor allocation
करे। generated gateway ownership का handoff ठीक एक बार करता है; shim खुद
`resource_transfer` न बुलाए। पहले से लौटाया गया handle या उधार input buffer
फिर owned output बनाकर लौटाना contract error है। negative-test fixture में
`दुबारा` विधि जानबूझकर यह गलती करती है, सामान्य binding का उदाहरण नहीं है।
परिणाम का native token stale pointer नहीं है। explicit close idempotent
है; aliases, boxing या collections में रखे उसी resource पर उपयोग फिर असफल है।

GC (Garbage collection) managed arguments को परिणाम पूरा बनने तक जीवित रखता
है। finalizer केवल पहले से आरक्षित native cleanup record कतार में रखता है।
owner thread ही vendor destructor चलाता है। अलग runtime/thread, reentry और
busy resource उपयोग अस्वीकार हैं। native modules process भर लोड रहते हैं;
साझे context में ABI के साथ runtime implementation hash भी मिलना चाहिए।
status 7 तथा अज्ञात status contract failures हैं, business error नहीं।

## प्रदाता बनाना (Provider build)

संपीड़न producer के लिए पहले JAR बनाएँ, फिर sources तैयार करें। डाउनलोड किए
zlib 1.4 archive का pinned SHA-256 build script extraction से पहले जाँचता है।
आवश्यक producer tools C17 compiler, deterministic archiver और JDK headers हैं;
उपयोगकर्ता के JVM/.NET app को ये tools नहीं चाहिए।

```sh
java -jar target/bhash-compiler-0.1.0-SNAPSHOT.jar ffi prepare --builtin compression -o target/compression-binding
python scripts/build_ffi.py --archive zlib-1.4.tar.gz --bindings target/compression-binding --java-home /path/to/jdk --shared --test --catalog target/ffi-catalog
mvn -DskipTests package
java -jar target/bhash-compiler-0.1.0-SNAPSHOT.jar ffi doctor compression
```

Windows में llvm-mingw compiler/archiver के लिए `--cc` और `--ar` दें। Linux
provider baseline glibc 2.35 है, इसलिए producer को Ubuntu 22.04-compatible
environment में बनाएँ। macOS producer Apple Silicon पर बनाएँ। तीनों host
catalogs मिलाने के बाद ही सार्वभौमिक compiler वितरण बनाएँ। provider.json,
provenance.json, स्रोत hash, binary hash और zlib license साथ रखें। परीक्षण
वाली `BHFFI_TESTING`/`BHFFI_GC_STRESS` build को प्रकाशित न करें।

C नामस्थान (C symbol namespace) में shim के export और vendor के अंदरूनी
symbols को अपना package/version prefix दें। static archives में hidden visibility
अकेली पर्याप्त नहीं है। संपीड़न producer zlib के linked symbols को versioned
prefix देता है; दूसरे system zlib से अनजाने में link नहीं होता। दो bindings
के घोषित shim symbols टकराने पर native compiler link से पहले त्रुटि देता है।

वितरण (Distribution) में catalog तथा private artifacts JAR संसाधन हैं;
native executable static provider लिंक करता है, JVM/CLR ऐप private shared
provider ले जाते हैं। system PATH से समान नाम की DLL नहीं चुनी जाती। Git में
native binaries न रखें; reviewed permanent release assets और checksums दें।
कोई module unload/hot-reload नहीं है। नया provider लगाने के बाद runtime पुनः
शुरू करें। Android और browser target अभी unsupported हैं।

## परीक्षण (Validation)

`ForeignManifestTest`, `ForeignArtifactsTest`, `ForeignCommandsTest`,
`ForeignCGeneratorTest`, `ForeignJvmTest`, `ForeignNativeTest` और
`ForeignResourceTest` declarations, checksums, cross-target Result तथा opaque
resources जाँचते हैं। C harnesses memory limits, every-allocation failure,
corrupt streams, exact output caps और cleanup queues जाँचते हैं।
`scripts/ffi_wire_test.py` स्वतंत्र codec से compressed bytes जाँचता है।
पूर्ण Jupyter और browser tests के अतिरिक्त host-specific native validation
आवश्यक है; स्थानीय Windows परिणाम को Linux/macOS की सफलता न मानें।
