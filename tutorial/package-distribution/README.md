# संकुल प्रकाशन, वितरण और पुनः उपयोग (Package publishing, distribution and reuse)

[English guide](../../docs/package-distribution.en.md)

संकुल (Package) संस्करण वाला Bhash स्रोत पुस्तकालय है। उसके साथ प्रकारयुक्त C
binding भी हो सकती है। नई `package` commands नाम खोजना, विवरण देखना, जोड़ना,
बदलना, संग्रह बनाना और स्थानीय सूची में प्रकाशित करना देती हैं। छोटी JSON सूची
GitHub या अन्य HTTPS स्थान पर रख सकते हैं; अलग registry server आवश्यक नहीं है।
[आधिकारिक सूची](../../packages/index.json) और अपनी संस्था की सूची दोनों चलती हैं।

ये commands इस milestone के compiler स्रोत में हैं। सार्वजनिक compiler/extension/
kernel release में आने तक इस revision से बना JAR उपयोग करें। नीचे
`bhash-compiler.jar` उसी JAR का नाम है।

## १. पुस्तकालय खोजना (Discovery)

```sh
java -jar bhash-compiler.jar package search text
java -jar bhash-compiler.jar package info bhash-text-utils
java -jar bhash-compiler.jar package info bhash-text-utils@1.0.1
```

खोज नाम और हिन्दी/English विवरण से होती है। हर संकुल का सबसे नया stable
संस्करण दिखता है। `info` स्रोत repository, पूरा commit, directory, license और
स्रोत/manifest fingerprints दिखाता है। संस्करण semantic-version क्रम से चुने
जाते हैं, शब्दकोश क्रम से नहीं। Prerelease चाहिए तो पूरा संस्करण दें। Version
ranges और चुपचाप होने वाले upgrades इस चरण में नहीं हैं।

अपनी सूची के लिए `--index https://example.org/index.json` या स्थानीय फ़ाइल दें।
`--index-sha256 HASH` से सूची के ठीक उन्हीं bytes को pin कर सकते हैं। पहले लाई
गई HTTPS सूची `--offline` में cache से खुलती है; cache न हो तो त्रुटि है। Java
system property `bhash.packages.cache` से cache directory बदल सकते हैं।

## २. निश्चित संस्करण जोड़ना (Add an exact version)

नीचे का Bhash code `my-app/src/main.bhash` में रखें। `my-app/bhash.project` में
संगत strict profile दें:

```properties
name=my-app
sources=src
nullSafety=strict
```

फिर dependency जोड़ें और application चलाएँ:

```sh
java -jar bhash-compiler.jar package add bhash-text-utils@1.0.1 --project my-app --alias text
java -jar bhash-compiler.jar deps verify my-app --offline
java -jar bhash-compiler.jar run my-app --target jvm
```

जोड़ना (Add) सूची से सही entry लेकर pinned Git स्रोत लाता और manifest/source
fingerprints मिलाता है। परियोजना में सामान्य exact Git dependency तथा `bhash.lock`
लिखे जाते हैं। आगे की dependencies पुराने resolver से आती हैं। C provider हो
तो मौजूदा host, checksum, HTTPS artifact URL और license जाँच लागू रहती है।
HTTPS सूची स्थानीय `file:` repository पढ़ने की अनुमति नहीं दे सकती। स्थानीय
परीक्षण की अपनी index फ़ाइल explicit `file:` Git repository उपयोग कर सकती है।

Application में सामान्य Bhash लिखें:

```bhash
आयात उपयोगीपाठ.अभिवादन;
आयात उपयोगीपाठ.दोहराओ;
फलन मुख्य() -> शून्य {
    लिखो(अभिवादन("भारत"));
    लिखो(दोहराओ("भाष!", ३));
}
```

```text
नमस्ते, भारत!
भाष!भाष!भाष!
```

`bhash.project` **और** `bhash.lock` की समीक्षा करके दोनों commit करें। Manifest
के मूल्यों को बचाकर properties निश्चित क्रम में लिखी जाती हैं; comments और
layout नहीं बचते। पहले से locked graph सही होना चाहिए। Resolution, fingerprint
या provider restore विफल हो तो पिछली दोनों फ़ाइलों के bytes लौटते हैं; डाउनलोड
cache रह सकता है। बाहरी editor ने फ़ाइल बीच में बदल दी हो तो rollback उसे नहीं
मिटाता। दो replacements के बीच process crash से lock पुराना रह सकता है; समीक्षा
के बाद जानबूझकर `deps lock` करें। Installation source/build hooks नहीं चलाती।
Application चलाना अलग चरण है।

## ३. जानबूझकर संस्करण बदलना (Update and reproducibility)

```sh
java -jar bhash-compiler.jar package update bhash-text-utils@1.1.0 --project my-app --alias text
java -jar bhash-compiler.jar deps restore my-app
java -jar bhash-compiler.jar deps verify my-app --offline
```

उदाहरण का `1.1.0` पहले चुनी हुई सूची में प्रकाशित होना चाहिए। Update मौजूदा alias
का repository, directory और package identity बचाता है। सूची हाथ से बदल देने पर
भी स्थापित निश्चित संस्करण को दूसरे commit पर नहीं मोड़ सकते। नया lock diff देखकर
स्वीकार करें। Compilation और editor analysis सूची नहीं खोलते और update नहीं
करते। नई checkout में `deps restore` manifest/lock से स्रोत लाता है; सूची की
आवश्यकता नहीं है। स्रोत/provider cache में हों तो `--offline` जोड़ें।

## ४. अपना स्रोत पुस्तकालय बनाना (Authoring and packing)

[पूरा पुस्तकालय](../../packages/examples/text-utils/) और
[उपयोगकर्ता application](../../examples/package-reuse/) देखें। Manifest:

```properties
name=bhash-text-utils
kind=library
version=1.0.1
sources=src
nullSafety=strict
```

वितरण का नाम lowercase ASCII में अधिकतम ६४ वर्ण का हो; hyphen और dot मान्य हैं।
स्रोत में संकुल/आयात नाम देवनागरी में रहते हैं। `LICENSE` आवश्यक और `README.md`
वांछनीय है। बाहर दिखने वाली घोषणा `सार्वजनिक` रखें। Consumer और library के
null-safety profiles संगत हों।

```sh
java -jar bhash-compiler.jar package pack packages/examples/text-utils -o target/text-utils-release
```

संग्रह बनाना (Pack) नई directory में ZIP, `SHA256SUMS` और `release-info.json`
देता है। Manifest, खोजे गए Bhash स्रोत, binding manifest, license, वैकल्पिक
README/NOTICE और घोषित provider licenses ही शामिल होते हैं। `.env`, `.git`, build
outputs या install scripts पूरे directory के साथ नहीं उठते। UTF-8 पाठ LF में
बदलता है; समय स्थिर और हर फ़ाइल का hash inventory में है। समान input से समान
ZIP बनती है। पुराना output directory overwrite नहीं होता। सीमा १०,००० स्रोत
फ़ाइल, प्रति फ़ाइल १६ MiB और कुल ६४ MiB पाठ है।

ZIP पढ़ने, डाउनलोड और manual vendoring के लिए है। Resolver **pinned Git स्रोत**
स्थापित करता है, ZIP या पहले से compiled Bhash library नहीं। Archive को vendor
करना हो तो checksum जाँचकर local library directory में खोलें और path dependency
दें। Checksum bytes की पहचान है, publisher की विश्वसनीयता या code सुरक्षा का
प्रमाण नहीं।

## ५. अपरिवर्तनीय संस्करण प्रकाशित करना (Publishing)

पहले source, README और license commit करें। Branch/tag के स्थान पर पूरा commit
hash लें। नीचे `OWNER`, `REPOSITORY` और `FULL_COMMIT` वास्तविक मानों से बदलें:

```sh
java -jar bhash-compiler.jar package publish packages/examples/text-utils --index target/my-index.json --git https://github.com/OWNER/REPOSITORY.git --rev FULL_COMMIT --dir packages/examples/text-utils --description "Text helpers" --license Apache-2.0 -o target/text-utils-published
```

Windows में हिन्दी विवरण UTF-8 फ़ाइल में लिखकर `--description-file description.txt`
दें। इसे `--description` के बदले चुनें, ताकि Java command-line encoding से पाठ न बिगड़े।

प्रकाशन (Publish) local source, release पाठ और licenses को commit से मिलाता है।
Transitive graph पर Git repository की सीमाएँ जाँची जाती हैं। Assets बनते हैं और
**स्थानीय** index में entry atomically जुड़ती है। उसी नाम/संस्करण का source या
metadata नहीं बदल सकते; नया संस्करण दें। समान entry दोबारा fresh output directory
के साथ स्वीकार है। Uncommitted source/license अस्वीकार हैं। Relative dependencies
उसी pinned repository में रहें; अलग repository के लिए exact Git dependency दें।

Hosting स्पष्ट अलग चरण है: ZIP/checksum को स्थायी GitHub Release assets बनाएँ,
फिर सूची के owner के repository में index update PR/commit करें। CLI अपने आप
repository, release, push या publisher अधिकार नहीं बनाती। पहले से signed-in
GitHub CLI के साथ वास्तविक मान भरकर उदाहरण:

```sh
gh release create package-text-utils-1.0.1 target/text-utils-published/bhash-text-utils-1.0.1.zip target/text-utils-published/SHA256SUMS target/text-utils-published/release-info.json --repo OWNER/REPOSITORY --target FULL_COMMIT --title "bhash-text-utils 1.0.1"
```

सूची का owner Git permissions/reviews से प्रकाशन सँभालता है। सूची GitHub raw HTTPS
या अपने HTTPS host पर रख सकते हैं। आधिकारिक सूची में योगदान PR से होता है। इस
चरण में central account service, publisher signatures, registry-owned namespaces,
version-range solving, automatic advisories या package execution isolation नहीं है।

## ६. निजी सूची और repository (Private sources)

Secret manager से `BHASH_PACKAGES_HOST` में केवल hostname और `BHASH_PACKAGES_TOKEN`
में token दें। Bearer credential चुने host/original port तक जाता है; दूसरे
redirect origin को नहीं। निजी HTTPS Git के लिए `BHASH_GIT_HOST` तथा
`BHASH_GIT_TOKEN` हैं। Git token scoped environment में जाता है, URL, command
arguments या lockfile में नहीं। Authenticated Git redirects बंद हैं। कोई dependency
अपने credentials नहीं चुन सकती। Tokens commit न करें और न्यूनतम read permission
दें। SSH/interactive helper अभी नहीं हैं। Index token provider artifact URL को
authenticate नहीं करता; उसकी मौजूदा download शर्तें अलग हैं।

## ७. लक्ष्य और परीक्षण (Targets and validation)

स्रोत पुस्तकालय JVM, CLR, native और browser में चलता है, यदि उसकी API/capability
उस लक्ष्य में उपलब्ध हो। Native के GC roots/ownership नियम वही हैं। Installation
से C binding browser/Android में उपलब्ध नहीं हो जाती; matching host artifacts
चाहिए। Git स्रोत के लिए Git चाहिए। पूरा prebuilt JVM/CLR provider उपयोग करने
वाले application programmer को C compiler नहीं चाहिए।

```sh
python scripts/test_package_distribution.py --target jvm --target clr
python scripts/test_package_distribution.py --target native
```

यह परीक्षण स्थानीय Git fixture/index प्रकाशित करता, खोजता, जोड़ता, application
चलाता, नई जगह restore करता और offline reuse जाँचता है। Native में forced GC
है। Linux/Windows CI और मौजूदा weekly macOS workflow में यही उदाहरण चलता है।
[पुराने dependency नियम](../../docs/source-packages.md) में graph, lock और boundaries
का विस्तार है।
