# Building every example

From the repository root, build the compiler and then the examples:

**हिन्दी:** रिपॉज़िटरी की जड़ से पहले संकलक बनाएँ, फिर उदाहरण संकलित करें:

```sh
mvn clean package -DskipTests
python scripts/build_examples.py
```

The script discovers every `.bhash` file under `examples/`. A directory containing
`bhash.project` is one compilation unit: its source files are built together, rather
than attempting to compile helper classes individually. Library packages such as
`packages/core` and `packages/greetings` are application dependencies; they have no
standalone executable. `packages/app` builds with both dependencies and its committed lockfile.
Dependency caches and generated build directories are excluded from discovery.

**हिन्दी:** स्क्रिप्ट `examples/` में सभी `.bhash` फ़ाइलें खोजती है। जहाँ `bhash.project` है, उस परियोजना के स्रोत एक साथ संकलित होते हैं। `packages/core` और `packages/greetings` जैसे लाइब्रेरी संकुल निर्भरताएँ हैं और उनका अपना चलने वाला आउटपुट नहीं बनता। `packages/app` दोनों को अपनी लॉकफ़ाइल के अनुसार उपयोग करता है। खोज में निर्भरता कैश और निर्माण फ़ोल्डर छोड़ दिए जाते हैं।

Applications are compiled, **not executed**. Interactive examples will not wait for
console input, and file-writing examples will not create their demonstration files.
Lessons and tutorial transcripts keep their separate verification scripts.

**हिन्दी:** स्क्रिप्ट उदाहरणों को संकलित करती है। वह उन्हें चलाती नहीं है, इसलिए कंसोल इनपुट नहीं माँगा जाता और उदाहरण फ़ाइलें नहीं लिखते। पाठों और ट्यूटोरियल के अपेक्षित आउटपुट के लिए उनकी अपनी जाँच स्क्रिप्ट हैं।

## Output directories

```text
target/examples/
  jvm/
    hello.jar
    native/hello.jar
    multi-file.jar
    packages/app.jar
  dotnet/
    hello/BhashProgram.dll
    native/hello/BhashProgram.dll
    multi-file/BhashProgram.dll
    packages/app/BhashProgram.dll
  native/
    hello
    native/hello
    packages/app
  logs/                 # compiler diagnostics, separated by target and example
  build-report.json     # every selected target/unit and its result
```

Relative source paths are preserved, so `examples/hello.bhash` and
`examples/native/hello.bhash` have separate outputs. .NET folders contain the DLL,
runtime configuration and licensing files required to run the application.
The report's `artifact` paths are relative to `output_root`; its `sources` entries
show the files grouped into each project. A successful compiler exit must also
produce the expected JAR, DLL or executable.

**हिन्दी:** स्रोत के सापेक्ष पथ बने रहते हैं, इसलिए `examples/hello.bhash` और `examples/native/hello.bhash` के आउटपुट अलग हैं। .NET फ़ोल्डर में DLL, रनटाइम विन्यास और लाइसेंस फ़ाइलें हैं। रिपोर्ट में `artifact` पथ `output_root` के सापेक्ष है और `sources` बताता है कि परियोजना में कौन सी फ़ाइलें थीं। सफल संकलन के लिए अपेक्षित JAR, DLL या executable बनना भी ज़रूरी है।

```sh
java -jar target/examples/jvm/native/hello.jar
dotnet target/examples/dotnet/native/hello/BhashProgram.dll
./target/examples/native/native/hello
```

## Target availability and failures

JVM builds require a JDK (17+). .NET builds require the .NET 10 SDK; a missing SDK
or compilation error fails the build. `JAVA_HOME` and `BHASH_DOTNET` are respected.

**हिन्दी:** JVM के लिए JDK 17+ और .NET के लिए .NET 10 SDK चाहिए। आवश्यक SDK न मिलने या संकलन त्रुटि पर निर्माण विफल है। `JAVA_HOME` और `BHASH_DOTNET` से उपकरण चुन सकते हैं।

Native builds currently require Linux x86-64 and LLVM 18 (`clang-18`, `opt-18`, or
`BHASH_CLANG` / `BHASH_LLVM_OPT`). The default command records native as unavailable
on other hosts or when these tools are missing, while still building JVM/.NET.
Use `--require-native` to make native toolchain availability mandatory:

**हिन्दी:** नेटिव निर्माण के लिए समर्थित होस्ट और LLVM 18 चाहिए। वर्तमान समर्थित होस्ट Linux x64, Windows x64 और Apple Silicon macOS हैं। अपना प्रबंधित SDK या उपयुक्त system tools चुनें। नेटिव उपकरण न मिलने पर सामान्य आदेश उसे अनुपलब्ध दर्ज करता है और JVM/.NET निर्माण जारी रखता है। अनिवार्य नेटिव जाँच के लिए `--require-native` दें:

```sh
python3 scripts/build_examples.py --require-native
```

On a supported native host, the script attempts every application. Only an explicit
`BH9001` native-profile diagnostic can mark a general example unsupported. Syntax,
semantic, dependency-lock and tool/linker failures are errors. Every application
under `examples/native/` must compile; even `BH9001` there fails the build.

**हिन्दी:** समर्थित नेटिव होस्ट पर हर ऐप बनाने का प्रयास होता है। सामान्य उदाहरण में केवल स्पष्ट `BH9001` प्रोफ़ाइल निदान उसे असमर्थित मान सकता है। वाक्यरचना, अर्थ, लॉकफ़ाइल और लिंकिंग की समस्याएँ विफलताएँ हैं। `examples/native/` में `BH9001` भी विफलता है।

Temporary compiler projects are created inside `output_root/.tmp` and cleaned up
by the compiler, keeping build intermediates in the workspace.

**हिन्दी:** संकलक अस्थायी परियोजनाएँ `output_root/.tmp` के भीतर बनाता और साफ़ करता है। मध्यवर्ती फ़ाइलें कार्यक्षेत्र के भीतर रहती हैं।

The report records `built`, `unsupported`, `unavailable`, `library` and `failed`
results. Compiler logs explain individual failures. Existing outputs are preserved
on failed or skipped builds; consult the latest report rather than treating an old
artifact as newly built. `mvn clean` removes the default output tree for a fresh build.

**हिन्दी:** रिपोर्ट में `built`, `unsupported`, `unavailable`, `library` और `failed` परिणाम हैं। हर विफलता के लिए संकलक लॉग देखें। छोड़े गए या विफल निर्माण पुराने आउटपुट को बचाते हैं। नवीनतम रिपोर्ट से पता करें कि अभी क्या बना। `mvn clean` डिफ़ॉल्ट आउटपुट हटाकर नए निर्माण की तैयारी करता है।

## Selecting targets and CI

```sh
python scripts/build_examples.py --target jvm --target clr
python3 scripts/build_examples.py --target native --require-native
python scripts/build_examples.py --output-root target/example-build
```

The CLI target name `clr` maps to the `dotnet` output directory. `--compiler` and
`--examples` can select another packaged compiler or example tree. Output trees
must be separate from example sources; conflicting output names are rejected.

**हिन्दी:** CLI में `clr` चुनने पर आउटपुट `dotnet` फ़ोल्डर में जाता है। `--compiler` और `--examples` से दूसरा संकलक या उदाहरण फ़ोल्डर चुनें। स्रोत और आउटपुट के फ़ोल्डर अलग रखें। टकराने वाले आउटपुट नाम अस्वीकार होते हैं।

Managed CI builds every application for JVM and .NET through `smoke_test.py`, then
runs its existing transcript/licensing checks using those artifacts. Native CI builds
every applicable application, inspects all executable dependencies, and retains the
existing clean-container output/GC checks. Both jobs upload the complete output tree
and report as artifacts. Native binaries remain available from the Linux native job.

**हिन्दी:** Managed CI `smoke_test.py` से JVM और .NET ऐप बनाता है, फिर उन्हीं फ़ाइलों पर आउटपुट और लाइसेंस जाँचता है। Native CI लागू उदाहरण बनाता है, executable की निर्भरताएँ देखता है और अलग कंटेनर में आउटपुट तथा GC जाँच रखता है। दोनों आउटपुट और रिपोर्ट GitHub Actions artifacts में देते हैं।

For local smoke checks after a completed example build:

**हिन्दी:** उदाहरण संकलित करने के बाद स्थानीय त्वरित जाँच के लिए:

```sh
python scripts/smoke_test.py --skip-example-build
```
