# Bhash (भाष) for Visual Studio Code

Write Bhash on Windows, macOS, Linux, WSL, or a remote VS Code host. This extension
bundles the Bhash compiler and a Java language server. Source analysis stays on
the extension host; it does not send code to the public playground or execute
programs while you edit.

**हिन्दी:** Windows, macOS, Linux, WSL और दूरस्थ VS Code होस्ट पर भाष लिखें। एक्सटेंशन में भाष संकलक और Java भाषा सर्वर है। संपादन का विश्लेषण उसी होस्ट पर होता है। कोड सार्वजनिक Playground पर नहीं भेजा जाता और संपादन के समय चलता नहीं है।

## Hindi documentation / हिन्दी प्रलेखन

- [भाष में प्रोग्रामिंग (Hindi book)](https://github.com/bhashitva/bhash/blob/main/book/README.md) has guided chapters, a downloadable PDF, and an offline companion ZIP.
- [मानक पुस्तकालय का हिन्दी API संदर्भ (Standard library API reference)](https://github.com/bhashitva/bhash/blob/main/tutorial/standard-library/reference/README.md) lists types, constructors, methods, parameters, return values, and examples.

In the Command Palette, run **Bhash: Open Hindi Book** or **Bhash: Open Hindi API Reference**.

कमांड पैलेट से **Bhash: Open Hindi Book** या **Bhash: Open Hindi API Reference** चलाएँ।

## What's new in 0.23.0 / इस रिलीज़ में

- Check your Android setup, select an emulator or phone, build a debug APK, run the app, and view its Logcat output from the Command Palette on Windows and Linux x86-64.
- Start with the [Hindi Android tutorial](https://github.com/bhashitva/bhash/blob/main/tutorial/android/README.md). The bundled compiler supports generating Kotlin and JNI bindings and building signed APKs and App Bundles from the CLI.

**हिन्दी:** Windows और Linux x86-64 पर कमांड पैलेट से Android के औज़ार जाँचें, एमुलेटर या फ़ोन चुनें, डीबग APK बनाएँ, ऐप चलाएँ और उसके Logcat संदेश देखें। [हिन्दी Android ट्यूटोरियल](https://github.com/bhashitva/bhash/blob/main/tutorial/android/README.md) से शुरू करें।

## Compiler 0.22.0 highlights / संकलक 0.22.0 की मुख्य बातें

- Format values with positional placeholders, alignment, and numeric precision using [`मानक.पाठस्वरूप.बनाओ`](../../tutorial/standard-library/08-formatting.md).
- Use [`मानक.समुच्चय<त>`](../../tutorial/standard-library/09-sets.md) for unique values, insertion-order iteration, union, intersection, and difference.
- Missing-semicolon diagnostics point to the insertion location and explain the fix in Hindi.

**हिन्दी:** `मानक.पाठस्वरूप.बनाओ` से स्थानचिह्न, संरेखण और दशमलव के अंकों वाला पाठ बनाएँ। `मानक.समुच्चय<त>` में हर सदस्य एक बार रखें और संघ, प्रतिच्छेद या अंतर निकालें। छूटा अर्धविराम अब उसी जगह दिखता है जहाँ उसे लगाना है।

See [Bhash Downloads](https://github.com/bhashitva/bhash/blob/main/DOWNLOADS.md) for standalone compiler releases and the [changelog](CHANGELOG.md) for extension releases.

## Hindi syntax / नया हिन्दी शब्दक्रम

Declare functions with **`फलन`**
and write the collection first in **`प्रत्येक`** loops:

**हिन्दी:** घोषणा में `फलन` और `प्रत्येक` में पहले संग्रह लिखें:

```bhash
फलन मुख्य() -> शून्य {
    स्थिर अंक: पूर्णांक[] = नया पूर्णांक[]{१,२,३};
    प्रत्येक (अंक में मानक: पूर्णांक) { लिखो(मानक); }
}
```

**Source update required:** change function declarations from `कार्य` to `फलन`,
and change `प्रत्येक (तत्व: प्रकार में संग्रह)` to
`प्रत्येक (संग्रह में तत्व: प्रकार)`. Map key/value bindings both follow `में`.
The old declaration and loop forms are rejected. Update source dependencies too;
regenerate their lockfiles through the normal dependency workflow when needed.

**हिन्दी:** पुराना `कार्य` बदलकर `फलन` करें। चक्र में `प्रत्येक (संग्रह में तत्व: प्रकार)` लिखें। मानचित्र की कुंजी और मान दोनों `में` के बाद हैं। स्रोत निर्भरताएँ भी बदलें और ज़रूरत पर सामान्य प्रक्रिया से लॉकफ़ाइल फिर बनाएँ।

फलन (Function) घोषित करने के लिए अब `फलन` लिखें। प्रत्येक (For-each) में पहले
संग्रह, फिर `में`, फिर तत्व का नाम और प्रकार लिखें। पुराने स्रोत को बदलकर फिर
संकलित करें। टिप्पणियों, संदेशों और साधारण हिन्दी गद्य में `कार्य` बदलना आवश्यक
नहीं है। पहले से बने अनुप्रयोग चलते रहेंगे।

See the [Hindi syntax and migration guide](../../docs/hindi-syntax.md).
Highlighting, snippets, completion, project templates and the bundled compiler
use the same syntax. Java 17+ and the existing target/toolchain requirements apply.

**हिन्दी:** [हिन्दी वाक्यरचना और माइग्रेशन](../../docs/hindi-syntax.md) देखें। रंग, snippets, सुझाव, परियोजना templates और संकलक वही रूप उपयोग करते हैं। Java 17+ और लक्ष्य के उपकरण चाहिए।

## Community and support / समुदाय और सहायता

Join the [Bhash Discord community](https://discord.gg/qC5zdJy6Qw) to learn with other Bhash users,
share projects, collaborate, and reach Bhashitva support for questions, issues or help.

भाष उपयोगकर्ताओं के साथ सीखें, अपनी रचनाएँ साझा करें और प्रश्नों या समस्याओं में
भाषित्व की सहायता पाने के लिए हमारे [Discord समुदाय](https://discord.gg/qC5zdJy6Qw) से जुड़ें।

In VS Code, run **Bhash: Community & Support (Discord)** from the Command Palette.

**हिन्दी:** Command Palette में **Bhash: Community & Support (Discord)** चलाएँ।

## Install

1. Install **Bhash (भाष)** from the [Visual Studio Marketplace](https://marketplace.visualstudio.com/items?itemName=bhashitva.bhash).

   **हिन्दी:** [Visual Studio Marketplace](https://marketplace.visualstudio.com/items?itemName=bhashitva.bhash) से **Bhash (भाष)** स्थापित करें।
2. For offline installation, get a `.vsix` from [Bhash Downloads](https://github.com/bhashitva/bhash/blob/main/DOWNLOADS.md#vs-code-extension) and choose **Extensions → … → Install from VSIX…**.

   **हिन्दी:** ऑफ़लाइन स्थापना के लिए [भाष डाउनलोड](https://github.com/bhashitva/bhash/blob/main/DOWNLOADS.md#vs-code-extension) से `.vsix` लें और VS Code में **Extensions → … → Install from VSIX…** चुनें।
3. Install **Java 17 or later** on the computer running the extension host.
   A full JDK is needed for JVM build/run. In WSL, SSH, or Codespaces, install Java
   on that remote host and install the extension there.

   **हिन्दी:** एक्सटेंशन जिस होस्ट पर चलता है वहाँ Java 17+ दें। JVM निर्माण/निष्पादन के लिए पूरा JDK चाहिए। WSL, SSH या Codespaces में वहीं Java और एक्सटेंशन स्थापित करें।
4. Open a folder containing `bhash.project`, or open a standalone `.bhash` file.
   Trust the workspace to enable analysis and compiler tasks.

   **हिन्दी:** `bhash.project` वाला फ़ोल्डर या स्वतंत्र `.bhash` फ़ाइल खोलें। विश्लेषण और संकलन tasks के लिए workspace पर भरोसा दें।

No Maven or Node installation is needed to use the packaged extension. The extension is
available on the [Visual Studio Marketplace](https://marketplace.visualstudio.com/items?itemName=bhashitva.bhash).
A locally packaged or Actions-built version can be installed before its Marketplace update is published.

**हिन्दी:** पैकेज वाला एक्सटेंशन उपयोग करने के लिए Maven या Node नहीं चाहिए। [Visual Studio Marketplace](https://marketplace.visualstudio.com/items?itemName=bhashitva.bhash) से स्थापित करें। नया Marketplace अपडेट आने से पहले स्थानीय या Actions का VSIX लगा सकते हैं।

## Android projects

Open the folder created by `bhash android init` in desktop VS Code on Windows or Linux x86-64. The extension uses the Android SDK, NDK and JDK 17 or 21 on the VS Code extension host. Start the emulator in Android Studio or connect an authorized phone. See the [Android setup guide](https://github.com/bhashitva/bhash/blob/main/tutorial/android/README.md).

| Command Palette command | What it does |
| --- | --- |
| **Bhash: Android Check Setup** | Runs the CLI checks for the JDK, SDK, pinned compiler and ADB. |
| **Bhash: Android Select Device** | Chooses a ready emulator or phone for the open project. Run and Logcat reuse the selection while that device is connected. |
| **Bhash: Android Build** | Saves project files and builds the debug APK with the project's Gradle wrapper. |
| **Bhash: Android Run** | Saves, builds, installs and launches the app on the selected device. With one ready device, the extension can select it automatically. |
| **Bhash: Android Logcat** | Streams logs from the running app's main process into a task terminal. Use **Tasks: Terminate Task** or Ctrl+C to stop. Restart the Logcat task after restarting the app. |

**हिन्दी:** `android init` से बना फ़ोल्डर खोलें। **Bhash: Android Check Setup** से औज़ार जाँचें। **Android Select Device** से यंत्र चुनें, **Android Run** से ऐप चलाएँ और **Android Logcat** से चल रहे ऐप के संदेश देखें। [हिन्दी ऑफ़लाइन कार्य सूची](https://github.com/bhashitva/bhash/blob/main/tutorial/android/task-list.md) में पूरा उदाहरण है।

## Native debugging on Linux and WSL

Run **Bhash: Debug Native** to save and compile the active Bhash project
with source debug information, then launch VS Code's GDB debugger. Install Microsoft's
**C/C++** extension and **GDB 12+ with Python 3 support** on the Linux/WSL extension
host (`sudo apt install gdb` on Ubuntu). Java 17+ remains required for compilation;
the ordinary managed native SDK supplies LLVM automatically.

**हिन्दी:** **Bhash: Debug Native** सक्रिय प्रोजेक्ट सहेजता और debug जानकारी के साथ बनाकर VS Code का GDB शुरू करता है। Linux/WSL होस्ट पर Microsoft **C/C++** और Python 3 वाला **GDB 12+** चाहिए। Ubuntu पर `sudo apt install gdb` उपयोग करें। Java 17+ चाहिए और प्रबंधित SDK LLVM देता है।

Source breakpoints, step into/over/out, Hindi call stacks and scalar/text/object/
collection inspection are supported. Inspection reads memory without calling program
functions or changing GC roots. Windows debugging is also available; macOS editor debugging remains pending. See the [Hindi debugging guide](../../docs/native-vscode-debug.md)
for launch configurations, limits, GC safety and troubleshooting.

**हिन्दी:** स्रोत breakpoint, step into/over/out, हिन्दी call stack और अदिश, पाठ, वस्तु व संग्रह निरीक्षण हैं। निरीक्षण प्रोग्राम फलन नहीं बुलाता और GC roots नहीं बदलता। Windows डिबगिंग भी है। macOS संपादक डिबगिंग अभी लंबित है। [हिन्दी डिबगिंग](../../docs/native-vscode-debug.md) में विन्यास और समस्या समाधान हैं।

## Features

- Highlighting generated from the current ANTLR keyword definitions, with
  Devanagari-aware word boundaries, comments, strings, and both numeric scripts.

  **हिन्दी:** ANTLR की वर्तमान कीवर्ड सूची से देवनागरी सीमाएँ, टिप्पणियाँ, पाठ और दोनों अंक लिपियों के रंग बनते हैं।
- Brackets, indentation, comment toggling, folding, and English/Devanagari snippets:
  `main`, `class`, `interface`, `record`, `enum`, `foreach`, `try`, `switch`, etc.

  **हिन्दी:** कोष्ठक, indentation, टिप्पणी बदलना, folding और अंग्रेज़ी/देवनागरी snippets उपलब्ध हैं।
- Keyword completion with English search aliases, built-ins, lexical declarations,
  same-package types/functions, and explicitly imported names/aliases.

  **हिन्दी:** अंग्रेज़ी खोज उपनाम, अंतर्निहित नाम, स्थानीय घोषणाएँ, उसी संकुल और आयात से सुझाव मिलते हैं।
- Whole-project syntax and semantic diagnostics in Problems, including unsaved
  source/manifest buffers and newly created source buffers. Imports and types are
  checked using the same semantic checker as all three managed/browser targets.

  **हिन्दी:** Problems में पूरे प्रोजेक्ट की वाक्यरचना और अर्थ त्रुटियाँ हैं। असहेजी और नई फ़ाइलें भी शामिल हैं। आयात/प्रकार साझा checker जाँचता है।
- Typed member completion for declared receivers, inherited and generic members,
  records, enums, lists/maps/arrays and byte buffers. Private/protected visibility
  and instance/static receiver rules filter suggestions.

  **हिन्दी:** घोषित receiver, विरासत, सामान्य सदस्य, अभिलेख, परिगणना, संग्रह और buffers के प्रकारयुक्त सुझाव हैं। पहुँच और instance/shared नियम छाँटते हैं।
- Signature help on `(` and `,` for functions, overloads, methods and constructors,
  including parameter types and the active argument. Nested calls are tracked.

  **हिन्दी:** `(` और `,` पर फलन, विधि, निर्माता और ओवरलोड का हस्ताक्षर, प्रकार और सक्रिय तर्क मिलता है। अंदर की कॉल अलग रहती हैं।
- Completion of an unimported public type/function adds a file-local `आयात`.
  Existing aliases and name conflicts are respected; unresolved names also offer
  an import Quick Fix when a matching public declaration exists in the project.

  **हिन्दी:** बिना आयात वाले सार्वजनिक प्रकार या फलन के सुझाव से उसी फ़ाइल का `आयात` जुड़ता है। उपनाम और नाम टकराव देखे जाते हैं। मेल मिलने पर अनसुलझे नाम का Quick Fix है।
- Hover and F12 for lexical, top-level and resolved receiver members. Overloaded
  navigation lists declarations rather than choosing a call's resolved overload.
  Outline and workspace symbols include types, records, enums, interfaces, functions and members.

  **हिन्दी:** Hover और F12 स्थानीय, ऊपरी तथा resolved सदस्यों पर हैं। ओवरलोड की घोषणाएँ सूची में आती हैं। Outline और workspace symbols में प्रकार और सदस्य हैं।
- Coded diagnostics with Hindi guidance and English detail. Independent body errors
  are collected in one pass (up to 50); declaration/import/type-graph failures stop
  the semantic pass to avoid misleading follow-on errors.

  **हिन्दी:** निदान में कोड, हिन्दी सलाह और अंग्रेज़ी विवरण हैं। अधिकतम ५० स्वतंत्र body त्रुटियाँ इकट्ठी होती हैं। घोषणा, आयात या प्रकार graph की विफलता गलत बाद के संदेश रोकती है।
- Independent manifests and multi-root workspaces remain separate compilation
  units. Discovery ignores `.bhash`, `.git`, `node_modules`, `target`, `build`, and `dist`.

  **हिन्दी:** हर manifest या workspace root की संकलन इकाई अलग है। ऊपर के build और cache फ़ोल्डर खोज से बाहर हैं।
- Build JVM, CLR, Native, or browser WebAssembly; run JVM/CLR/Native in an integrated terminal
  with console input. File paths are passed as process arguments, never a shell
  command. Builds use saved files and prompt for the target.

  **हिन्दी:** JVM, CLR, native या WebAssembly बनाएँ। पहले तीन में terminal इनपुट के साथ चला सकते हैं। फ़ाइल पथ प्रक्रिया के तर्क हैं। निर्माण सहेजी फ़ाइल और चुना लक्ष्य लेता है।
- **Bhash: New Project**, **Bhash: Restart Language Server**, and
  **Bhash: Open Browser Playground** commands.

  **हिन्दी:** नई परियोजना, भाषा सर्वर फिर शुरू करना और ब्राउज़र Playground खोलना Command Palette से करें।

`स्थिर` means a constant binding. Static class members use **`साझा`**.

**हिन्दी:** `स्थिर` अचल बंधन और `साझा` वर्ग का static सदस्य है।

## Native on Windows, Linux and WSL

Native builds use the compiler bundled with the extension. Choose `native` from
**Bhash: Build Project or File** or **Bhash: Run Project or File**. The first task
downloads the [versioned SDK](https://github.com/bhashitva/bhash/releases/tag/native-sdk-1.1.0)
(110 MiB), checks its bundled SHA-256, verifies the installation, and activates it
in your user cache. Later builds reuse it, including offline. No separate LLVM,
Maven or Node installation is required. Java 17+ is still required to run the compiler.
On macOS, install Apple’s Command Line Tools separately for the local SDK and linker.

**हिन्दी:** **Bhash: Build Project or File** या **Bhash: Run Project or File** में `native` चुनें। पहला काम SDK डाउनलोड कर SHA-256 और स्थापना जाँचता है तथा user cache में सक्रिय करता है। बाद के निर्माण offline भी उसका उपयोग करते हैं। Java 17+ चाहिए। macOS में Apple Command Line Tools अलग दें। LLVM, Maven और Node अलग स्थापित नहीं करने पड़ते।

Supported hosts:

**हिन्दी:** समर्थित होस्ट:

- **Windows 10+ x64**: ordinary Windows VS Code, Java 17+, and Bhash are sufficient.
  WSL and Visual Studio are not required. The extension downloads the pinned
  [Windows SDK](https://github.com/bhashitva/bhash/releases/tag/native-sdk-windows-1.0.0)
  (71 MiB) and produces `.exe` applications using Windows UCRT.

  **हिन्दी:** Windows 10+ x64 में सामान्य VS Code, Java 17+ और Bhash दें। WSL या Visual Studio की जरूरत नहीं। प्रबंधित Windows SDK से UCRT वाली `.exe` बनती है।
- **Linux x86-64, glibc 2.35+**, including Ubuntu 22.04+ in WSL. Install Bhash and
  Java inside WSL when using a WSL workspace; its SDK runs inside that distribution.

  **हिन्दी:** Linux x86-64/glibc 2.35+ में WSL Ubuntu 22.04+ भी है। WSL workspace में Java और Bhash उसी वितरण में स्थापित करें।

- **macOS 14+ Apple Silicon ARM64**: use ARM64 VS Code, ARM64 Java 17+, and Apple’s
  Command Line Tools (`xcode-select --install`). Bhash downloads a pinned LLVM 18
  SDK; Apple’s SDK and linker are not redistributed. See [macOS setup and distribution](https://github.com/bhashitva/bhash/blob/main/docs/native-macos.md).

  **हिन्दी:** Apple Silicon macOS 14+ में ARM64 VS Code, ARM64 Java 17+ और `xcode-select --install` दें। Bhash पिन किया LLVM 18 SDK लेता है। Apple SDK या linker साथ वितरित नहीं होते।

Intel Macs, other ARM hosts, and musl/Alpine are not supported. Each SDK builds
for its own host; this release does not add cross compilation. Generated applications
need neither Java nor .NET nor LLVM. The compiler continues to require Java 17+.

**हिन्दी:** समर्थन सूची में Intel Mac, अन्य ARM और musl/Alpine नहीं हैं। SDK अपने होस्ट के लिए बनाता है। बना ऐप JVM, .NET या LLVM के बिना चलता है। संकलक को Java 17+ चाहिए।

- **Bhash: Set Up Native Tools** installs tools before the first build.

  **हिन्दी:** **Bhash: Set Up Native Tools** पहले निर्माण से पहले उपकरण तैयार करता है।
- **Bhash: Check Native Setup** diagnoses the current installation without downloading.

  **हिन्दी:** **Bhash: Check Native Setup** डाउनलोड के बिना मौजूदा स्थापना देखता है।
- **Bhash: Repair Native Tools** downloads and verifies a fresh copy; failed setup
  preserves the previously active SDK. Cancel through the task terminal.

  **हिन्दी:** **Bhash: Repair Native Tools** नई प्रति जाँचकर स्थापित करता है। विफलता में पुरानी सक्रिय SDK बचती है। task terminal से रोक सकते हैं।
- `bhash.native.toolchainHome` optionally chooses an absolute cache path on the extension host.
  Empty shares the CLI cache (`BHASH_TOOLCHAIN_HOME`, otherwise
  `%LOCALAPPDATA%\Bhash\toolchains` on Windows;
    `$XDG_CACHE_HOME/bhash/toolchains` or `~/.cache/bhash/toolchains` on Linux). Allow about
  1 GB of free disk space during installation; repairs retain previous generations.

  **हिन्दी:** `bhash.native.toolchainHome` में होस्ट का पूरा cache पथ दें। खाली रखने पर CLI cache साझा है। स्थापना में लगभग 1 GB जगह रखें। मरम्मत पिछली प्रतियाँ रखती है।
- `bhash.native.toolchainMode = system` opts into an existing LLVM 18 toolchain.
  On Windows, system mode requires LLVM-MinGW 18/UCRT, not an MSVC-only LLVM installation.
    In managed mode, ambient `BHASH_CLANG`, `BHASH_LLVM_OPT`, and `BHASH_READELF`
  overrides are ignored for these tasks so they cannot mix incompatible tools.

  **हिन्दी:** `bhash.native.toolchainMode = system` से मौजूद LLVM 18 लें। Windows में LLVM-MinGW 18/UCRT चाहिए। managed task परिवेश के `BHASH_CLANG`, `BHASH_LLVM_OPT` और `BHASH_READELF` नहीं लेता।

Native tasks support console input (`पाठपढ़ो()` and `पढ़ो()`) in the integrated
terminal. No SDK download occurs while editing, discovering tasks, or in
Restricted Mode. Dependencies still follow the ordinary lock/restore rules.
Example `.vscode/tasks.json` entry:

**हिन्दी:** native task integrated terminal में `पाठपढ़ो()` और `पढ़ो()` लेता है। संपादन, task खोज और Restricted Mode में SDK डाउनलोड नहीं होता। निर्भरताओं के सामान्य lock/restore नियम हैं। `.vscode/tasks.json` का उदाहरण:

```json
{"type":"bhash","label":"Native build","command":"build","project":"bhash.project","target":"native"}
```

Version 0.5.2 replaces the Ubuntu 24.04 SDK shipped in 0.5.0/0.5.1 with a
Jammy-built SDK for glibc 2.35+. Existing installs migrate through the normal
checksum-verified installer; the earlier release assets remain available.

**हिन्दी:** 0.5.2 में Ubuntu 24.04 की जगह glibc 2.35+ का Jammy SDK आया। checksum वाला installer पुराने cache को बदलता है। पहले के release assets उपलब्ध हैं।

The SDK and corresponding third-party sources are GitHub **Release assets**,
not files committed to Git. Copyright and redistribution notices are retained;
see [managed toolchain details](../../docs/managed-native-toolchain.md).
Generated Linux executables do not require Java or LLVM; their supported runtime
still depends on the recorded Linux/glibc compatibility requirements. Debugger
integration and native compilation of every language feature are separate work.

**हिन्दी:** SDK और संबंधित स्रोत GitHub Releases में हैं। [प्रबंधित उपकरण](../../docs/managed-native-toolchain.md) में लाइसेंस व वितरण देखें। बनी Linux फ़ाइल को Java या LLVM नहीं चाहिए। उसकी Linux/glibc संगतता manifest पर निर्भर है।

### हिन्दी में शुरुआत

Linux या WSL में Ubuntu 22.04 या नया संस्करण और Java 17+ स्थापित करें।
VS Code में WSL वाला फ़ोल्डर खोलें और उसी WSL में Bhash extension स्थापित करें।
**Bhash: Run Project or File** में `native` चुनें। पहली बार आवश्यक उपकरण अपने
आप डाउनलोड और सत्यापित होंगे; उसके बाद इंटरनेट के बिना भी निर्माण कर सकते हैं।
उपकरणों की जाँच के लिए **Bhash: Check Native Setup**, और खराब स्थापना को फिर से
स्थापित करने के लिए **Bhash: Repair Native Tools** चलाएँ।

## Standard library

The bundled compiler includes text, math/random, JSON, dates/time, file I/O and
UTF-8/binary data support. It also includes collection algorithms, streams,
paths/directories, scoped resource cleanup (`संसाधन`), configuration, logging,
a Bhash test runner, HTTP client/server networking, a small web application framework
and SQLite database access, plus password hashing, authentication and sessions.

**हिन्दी:** संकलक में पाठ, गणित, यादृच्छिक, JSON, समय, फ़ाइल, UTF-8/binary, संग्रह कलन, प्रवाह, पथ, सफ़ाई, विन्यास, लॉग, परीक्षण, HTTP client/server, SQLite, कूटशब्द hashing, प्रमाणीकरण और सत्र हैं।

Use imports such as `आयात मानक.पाठ्य;` or `आयात मानक.एसक्यूलाइट;`, or fully
qualified names. Completion, signature help and automatic imports use the same
catalog as compiler checking; F12 opens a readable declaration document.

**हिन्दी:** `आयात मानक.पाठ्य;`, `आयात मानक.एसक्यूलाइट;` या पूरा नाम दें। सुझाव, हस्ताक्षर, auto-import और संकलक एक सूची उपयोग करते हैं। F12 पढ़ने योग्य घोषणा खोलता है।

SQLite supports parameterized SQL, typed values, transactions and deterministic
cleanup on JVM, CLR and native. The compiler bundles the SQLite driver/source and
verified runtime libraries needed for supported Windows x64, Linux x64 and macOS
ARM64 targets; users do not install SQLite separately. Normal Java/.NET/native
toolchain prerequisites still apply. Generated database applications can run
offline. SQLite is unavailable in browser WASM; local file operations are also
unavailable there, and browser networking follows browser security restrictions.

**हिन्दी:** SQLite में JVM, CLR और native पर मापदंड, प्रकार, लेनदेन व सफ़ाई हैं। समर्थित होस्टों के driver, स्रोत और runtime साथ हैं। सामान्य उपकरण चाहिए। बना ऐप offline चल सकता है। WebAssembly में स्थानीय फ़ाइल/SQLite नहीं हैं। ब्राउज़र नेटवर्क ब्राउज़र नियम मानता है।

See [the standard library guide](../../docs/standard-library.md) and the Hindi
reference tutorials for [application foundations](../../tutorial/application-foundations/README.md),
[networking](../../tutorial/networking/README.md) and [SQLite](../../tutorial/sqlite/README.md).

**हिन्दी:** [मानक पुस्तकालय](../../docs/standard-library.md) और [आधार](../../tutorial/application-foundations/README.md), [नेटवर्क](../../tutorial/networking/README.md) तथा [SQLite](../../tutorial/sqlite/README.md) के हिन्दी उदाहरण देखें।

## HTTP servers and web applications

The bundled compiler supports `मानक.वेब`, `वेबअनुप्रयोग`, `वेबचालक` and
`एचटीटीपीसर्वर` on JVM, CLR and native Linux/Windows x64 and macOS ARM64.
Use the existing build/run target choices; the same Bhash source provides routing,
path/query values, UTF-8 JSON, validation, logging, resource cleanup and cooperative
shutdown. Completion, signature help, automatic imports and F12 include these APIs.

**हिन्दी:** `मानक.वेब` और ऊपर के server प्रकार JVM, CLR व Linux/Windows x64 और macOS ARM64 native पर हैं। साझा स्रोत routing, URL मान, UTF-8 JSON, सत्यापन, लॉग, सफ़ाई और सहकारी shutdown देता है। संपादक सहायता में ये API शामिल हैं।

Start with the [SQLite task-board example](../../examples/web-tasks/README.md)
and its [step-by-step Hindi walkthrough](../../tutorial/web-applications/README.md).
Open the example folder containing `bhash.project` and `index.html`, then build/run
with the desired target. The example serves <http://127.0.0.1:8080/> and keeps data
in a local SQLite file. Running the compiled application from another directory
requires an explicit `--page` path as documented in the example.

**हिन्दी:** [SQLite कार्यसूची](../../examples/web-tasks/README.md) और [हिन्दी अभ्यास](../../tutorial/web-applications/README.md) से शुरू करें। `bhash.project` और `index.html` वाला फ़ोल्डर खोलें। लक्ष्य पर बनाकर चलाएँ। <http://127.0.0.1:8080/> खोलें। दूसरे फ़ोल्डर से लॉन्च पर `--page` का पथ दें।

The initial server runs one handler at a time, accepts fixed-length bodies up to
1 MiB, and closes each connection after its response. Use a reverse proxy for
HTTPS; listening is unavailable in browser/WASM. The demo is a single-user
teaching app; the supplied proxy template adds access control for deployment.
See the [server contract and deployment limits](../../docs/web-applications.md).

**हिन्दी:** server एक बार में एक handler चलाता है, तय लंबाई की 1 MiB तक body लेता है और उत्तर के बाद संबंध बंद करता है। HTTPS के लिए reverse proxy लगाएँ। यह एक उपयोगकर्ता का शिक्षण ऐप है। [नियम](../../docs/web-applications.md) देखें।

A separate [HTTP and SQLite cache example](../../examples/http-sqlite/README.md)
shows importing remote JSON into a transactional offline cache.

**हिन्दी:** [HTTP और SQLite](../../examples/http-sqlite/README.md) दूर का JSON लेनदेन वाले offline cache में रखता है।

## Authentication and sessions

The bundled compiler supports `मानक.कूटशब्द`, `सुरक्षा`, `सत्रप्रबंधक`,
`सुरक्षितवेब`, `अधिकृतचालक` and `प्रवेशरक्षक` on desktop JVM, CLR and native
Linux/Windows x64 and macOS ARM64. The APIs provide provider-backed PBKDF2 password
hashing, cryptographic tokens, secure cookies, bounded server-side sessions,
idle/absolute expiry, rotation and revocation, role authorization, CSRF protection
and login attempt limits. Completion, signature help, automatic imports and F12
use the bundled library declarations.

**हिन्दी:** ऊपर के प्रमाणीकरण प्रकार JVM, CLR और native में PBKDF2, सुरक्षित tokens और cookies, सीमित server sessions, expiry, rotation, revocation, भूमिका अनुमति, CSRF और login सीमा देते हैं। संपादक सार्वजनिक घोषणाओं से सहायता देता है।

Start with the [Hindi authentication reference](../../tutorial/authentication/README.md)
and [HTTP/SQLite login and notes application](../../examples/auth-sessions/README.md).
The walkthrough explains explicit account provisioning, parameterized SQL,
transactions, resource cleanup and per-user data access. Build using the existing
target choices, then run the generated application with the arguments documented
in the example; build/run tasks do not forward application arguments.

**हिन्दी:** [हिन्दी प्रमाणीकरण](../../tutorial/authentication/README.md) और [लॉगिन व नोट्स ऐप](../../examples/auth-sessions/README.md) में खाता बनाना, parameterized SQL, लेनदेन, सफ़ाई और प्रति उपयोगकर्ता डेटा देखें। ऐप बनाकर दिए तर्कों के साथ सीधे चलाएँ। VS Code tasks ऐप तर्क नहीं भेजते।

Public deployments require an HTTPS reverse proxy. Sessions and login attempt
counters are process-local; restarting signs users out. Plain HTTP is available
only through the explicit loopback development mode. Windows uses OS CNG and
macOS uses CommonCrypto; native Linux password operations require the OpenSSL 3
`libcrypto.so.3` runtime (no development headers). Server authentication is
unavailable in browser/WASM. See the [security and runtime contract](../../docs/authentication.md).

**हिन्दी:** सार्वजनिक तैनाती में HTTPS reverse proxy चाहिए। सत्र और login गिनती प्रक्रिया की हैं। restart पर sign-out होता है। plain HTTP केवल स्पष्ट loopback mode में है। Windows CNG, macOS CommonCrypto और Linux native में OpenSSL 3 `libcrypto.so.3` है। WebAssembly में server प्रमाणीकरण नहीं है।

## Reusable source packages

Use **Bhash: Update Dependencies Lock** after intentionally changing dependency
metadata or local library contents. **Bhash: Restore Dependencies** fetches missing
exact Git snapshots without updating the lock; **Bhash: Verify Dependencies**
checks existing local/cached sources offline. These commands require workspace
trust, save Bhash buffers and refresh analysis on success. Ordinary analysis,
build and run do not fetch dependencies or modify locks. Dependency declarations
are highlighted in `bhash.project`; changes to `bhash.lock` refresh diagnostics.
Verified library sources participate in completion, auto-import, signature help
and navigation. Add external local-library folders to the workspace to observe
changes made outside the editor. See [the package guide](../../docs/source-packages.md)
and [Hindi tutorial](../../tutorial/packages/README.md).

**हिन्दी:** निर्भरता बदलने पर **Bhash: Update Dependencies Lock** चलाएँ। **Restore Dependencies** पिन किया Git स्रोत लाता है और lock नहीं बदलता। **Verify Dependencies** स्थानीय या cached स्रोत offline जाँचता है। इन्हें भरोसे वाला workspace चाहिए। सफल होने पर संपादक विश्लेषण फिर करता है। सामान्य build/run dependency नहीं लाते। `bhash.lock` बदलने पर निदान फिर आते हैं। बाहरी स्थानीय संकुल की फ़ाइलें देखने के लिए उसका फ़ोल्डर workspace में जोड़ें।

## Projects and tasks

Run **Bhash: New Project** from the Command Palette. It creates a new directory
with `bhash.project` and `src/main.bhash`, without overwriting an existing folder.
Opening a file beneath a manifest automatically selects the nearest containing
project for analysis/build/run. Files outside the manifest's source roots are
standalone compilation units.

**हिन्दी:** **Bhash: New Project** से नया फ़ोल्डर, `bhash.project` और `src/main.bhash` बनते हैं। मौजूदा फ़ोल्डर नहीं बदलता। फ़ाइल खोलने पर सबसे पास की लागू परियोजना चुनी जाती है। स्रोत roots के बाहर फ़ाइल स्वतंत्र है।

For a repeatable team build, add a VS Code task:

**हिन्दी:** टीम में वही निर्माण फिर चलाने के लिए VS Code task जोड़ें:

```json
{
  "version": "2.0.0",
  "tasks": [
    {
      "type": "bhash",
      "label": "Build Bhash JVM",
      "command": "build",
      "project": "bhash.project",
      "target": "jvm",
      "group": { "kind": "build", "isDefault": true }
    }
  ]
}
```

`project` is relative to that workspace folder. `target` accepts `jvm`, `clr`, `native`, or
`wasm-browser`; `command` accepts `build` or `run`. Browser programs must be built
and served over HTTP. CLR/browser builds require .NET 10; browser builds also
require the `wasm-tools` workload. A library-only project can be analyzed without
`मुख्य`; executable builds still require exactly one valid entry point.

**हिन्दी:** `project` workspace के सापेक्ष है। `target` के चार मान ऊपर हैं। `command` में `build` या `run` है। ब्राउज़र ऐप HTTP से परोसें। CLR/browser को .NET 10 और browser को `wasm-tools` चाहिए। लाइब्रेरी में `मुख्य` जरूरी नहीं है, executable में ठीक एक चाहिए।

## Settings

| Setting | Default | Purpose |
| --- | --- | --- |
| `bhash.javaPath` | `java` | Java executable on the extension host |
| `bhash.compilerPath` | bundled JAR | Optional absolute path to an alternate compiler |
| `bhash.target` | `jvm` | Default target for configured tasks |
| `bhash.trace.server` | `off` | LSP tracing for troubleshooting; verbose traces can contain source |

Java/compiler paths are machine settings. Highlighting and snippets work in
Restricted Mode; the server and tasks require workspace trust. Virtual file
systems have syntax/snippets only. Unsaved untitled documents have syntax/snippets
until saved to a `.bhash` path. No Java runtime is downloaded automatically.

**हिन्दी:** Java और संकलक पथ machine settings हैं। Restricted Mode में रंग और snippets हैं, language server और tasks को भरोसा चाहिए। आभासी या बिना नाम फ़ाइल में पहले केवल रंग/snippets हैं। Java अपने आप नहीं आता।

## Current limits

The editor resolves declared types and common expression chains conservatively;
unknown or ambiguous receiver types produce no member suggestions. It is not a
complete semantic binding graph: semantic rename/references, formatting and source
debugging for JVM/CLR remain pending; Linux/WSL native debugging is described above.
Signature help lists overloads and selects a signature
with enough parameters; it does not promise full overload selection while a call
is incomplete. Generic member declarations remain a language limitation.

**हिन्दी:** संपादक घोषित प्रकार और सामान्य chains पहचानता है। अज्ञात या अस्पष्ट receiver पर सदस्य सुझाव नहीं हैं। semantic rename/references, formatting और JVM/CLR स्रोत डिबगिंग लंबित हैं। हस्ताक्षर सहायता ओवरलोड सूची दिखाती है और अधूरी कॉल का अंतिम ओवरलोड नहीं तय करती।

Syntax diagnostics are collected first. If syntax is invalid, semantic diagnostics
wait until it is repaired; completion can still use recovered declarations.
Declaration/import/generic-expansion failures can still stop at the first error.
Independent statement, function, constructor and field-initializer errors are
recovered without emitting an executable. See [diagnostic codes and behavior](../../docs/developer-experience.md).

**हिन्दी:** पहले वाक्यरचना देखी जाती है। उसे ठीक करने पर अर्थ जाँचती है। मिले हुए declarations से सुझाव फिर भी हो सकते हैं। घोषणा, आयात या generic विस्तार पहले दोष पर रुक सकता है। अलग body त्रुटियाँ बिना executable बनाए इकट्ठी होती हैं।

Unchanged project indexes are reused. Changed projects are analyzed as a whole;
incremental semantic compilation is pending. Automatic imports search the current
compilation project, not other workspace roots or external binary libraries.
Builds use saved files. No code is executed during completion or diagnostics.

**हिन्दी:** बिना बदले project index फिर उपयोग होते हैं। बदली परियोजना पूरी जाँची जाती है। auto-import वर्तमान परियोजना तक है। निर्माण सहेजी फ़ाइल उपयोग करता है। सुझाव और निदान कोड नहीं चलाते।

## Development

Use JDK 17+, Maven and Node.js 22 or later with its bundled npm. In WSL,
use Linux installations of Java, Node and npm (check `command -v node` and
`command -v npm`; they should not resolve under `/mnt/c/`).

**हिन्दी:** JDK 17+, Maven और Node.js 22+ के साथ उसका npm लें। WSL में Linux Java, Node और npm उपयोग करें। `command -v node` और `command -v npm` का पथ `/mnt/c/` के नीचे न हो।

From the repository root:

**हिन्दी:** रिपॉज़िटरी की जड़ से:

```sh
mvn package -DskipTests
npm ci --prefix editors/vscode
npm run build --prefix editors/vscode
npm test --prefix editors/vscode
npm run test:integration --prefix editors/vscode
npm run package --prefix editors/vscode
```

The extension pins minimatch 11.0.1 for GHSA-g3hv-75wg-m249. `npm ci` applies
`patches/vscode-languageclient+9.0.1.patch`: the client's three validity checks use
Minimatch's `comment`/`empty` flags instead of the removed `makeRe()` API; matching
still uses the patched library. Keep this patch until the client supports
minimatch 11 upstream. A failed patch stops installation.

**हिन्दी:** GHSA-g3hv-75wg-m249 के लिए minimatch 11.0.1 पिन है। `npm ci` patch लगाता है, जिसमें हटे `makeRe()` की जगह `comment`/`empty` flags हैं। upstream client के समर्थन तक patch रखें। patch विफल हो तो स्थापना रुकती है।

Linux integration tests need an X display (CI uses `xvfb-run -a`). The tests launch
VS Code 1.95.3 by default, exercise the actual extension host and compiler task,
and can target another version with `VSCODE_VERSION`. The Java stdio protocol tests
also cover cross-file unsaved edits, aliases, multi-root isolation, diagnostic
clearing, and UTF-16 positions after supplementary Unicode characters.

**हिन्दी:** Linux परीक्षण को X display चाहिए। CI `xvfb-run -a` उपयोग करता है। डिफ़ॉल्ट VS Code 1.95.3 की जगह `VSCODE_VERSION` दे सकते हैं। वास्तविक extension host, task और stdio में असहेजे संपादन, aliases, workspace अलगाव और पूरक Unicode के बाद UTF-16 स्थान जाँचे जाते हैं।

The VSIX includes Apache-2.0 Bhash code, ANTLR BSD-3-Clause notices, LSP4J's
EDL-1.0/BSD-3-Clause license option, Gson/Error Prone Apache-2.0 notices, and
Microsoft's MIT client-library notices. See the included license files.

**हिन्दी:** VSIX में भाष, ANTLR, LSP4J, Gson/Error Prone और Microsoft client की वितरण सूचनाएँ हैं। शामिल लाइसेंस फ़ाइलें देखें।

If `npm ci` says no lockfile exists, verify that
`editors/vscode/package-lock.json` is present and readable in your checkout.
The lockfile is committed; do not regenerate it just to bypass the error.
Try installing directly from the extension directory:

**हिन्दी:** यदि `npm ci` लॉकफ़ाइल नहीं पाता, तो `editors/vscode/package-lock.json` मौजूद और पढ़ने योग्य हो। त्रुटि छिपाने के लिए उसे फिर न बनाएँ। एक्सटेंशन फ़ोल्डर से प्रयास करें:

```sh
cd editors/vscode
node --version
npm --version
node -p "require('./package-lock.json').lockfileVersion"
npm ci
```

The lockfile version should be `3`. If the same error persists, inspect
`type -a node npm` and the npm debug log for the underlying load error.

**हिन्दी:** lockfile version `3` हो। समस्या रहने पर `type -a node npm` और npm debug log देखें।


## Native debugging on Windows x64

Version 0.9.0 extends native debugging to ordinary Windows VS Code on Windows 10 version 1903 or later. Install Java 17+
(JDK for JVM builds) and Microsoft's C/C++ extension on that host, leave
`bhash.native.gdbPath` empty, then run **Bhash: Debug Native**. The first debug launch
installs a separate checksum-pinned GDB/Python runtime; later launches reuse the
verified cache offline. No WSL, MSYS2, Python, LLVM or C development installation is
required. The existing managed compiler SDK is downloaded only if needed.

**हिन्दी:** Windows 10 1903+ में Java 17+ और Microsoft C/C++ दें। `bhash.native.gdbPath` खाली छोड़कर **Bhash: Debug Native** चलाएँ। पहले रन पर checksum-पिन किया GDB/Python cache बनता है। WSL, MSYS2, Python, LLVM या C उपकरण अलग नहीं चाहिए।

Click the gutter or press F9 on executable Bhash lines, then use F10/F11/Shift+F11
for stepping. Hindi stacks and read-only scalar/text/object/collection inspection
share the Linux implementation and GC safety contract. Windows/macOS build/run
support is unchanged; macOS debugger integration remains pending. See the
[Hindi debugger guide](../../docs/native-vscode-debug.md).

**हिन्दी:** चलने वाली पंक्ति के पास क्लिक करें या F9 दें। F10/F11/Shift+F11 से step करें। हिन्दी stack और केवल पढ़ने वाला निरीक्षण Linux की GC सुरक्षा साझा करता है। [हिन्दी डिबगर](../../docs/native-vscode-debug.md) देखें।

The Windows GDB process manifest enables UTF-8 for Hindi paths (Windows 10 1903+).
The manifest patch script is retained with the corresponding sources; debugger
instructions and other upstream binaries are unchanged.

**हिन्दी:** Windows GDB manifest हिन्दी पथों के लिए UTF-8 चालू करता है। उसका patch script संबंधित स्रोतों के साथ है।

### Windows debug terminals

Version 0.9.2 creates a dedicated integrated terminal for Bhash Windows debugging and starts Microsoft’s Windows debugger launcher directly. WSL, Git Bash, PowerShell, and custom default/automation profiles do not need to be changed. User and workspace terminal settings remain untouched. Existing `cppdbg` configurations with `bhashNative: true` automatically use this path on Windows; ordinary C/C++ sessions keep their existing adapters. Application output and interactive input remain in the integrated terminal.

**हिन्दी:** 0.9.2 से Windows डिबगिंग अपना integrated terminal और Microsoft launcher सीधे शुरू करती है। WSL, Git Bash, PowerShell या custom profile बदलना जरूरी नहीं। `cppdbg` और `bhashNative: true` अपने आप इस रास्ते से जाते हैं। ऐप output और input integrated terminal में रहते हैं।


## Bhash Watch expressions on Windows and Linux/WSL

Version 0.10.0 accepts Bhash syntax in the **Watch** pane and debugger hover. Pause at a
Bhash breakpoint, click **+** under Watch, and enter expressions such as
`वस्तु.अंक + अंक[१]`, `शब्द["साझा"].विरासत`, `सरणी[१]`, or `भिन्न * २.०`.
Fields, arrays/lists/maps/byte buffers, numeric and boolean operators, text equality,
`सत्य`/`असत्य`/`रिक्त`, ASCII/Devanagari literals and parentheses are supported.
Reference results remain expandable. Values refresh in the selected stopped frame.

**हिन्दी:** 0.10.0 से Watch और hover में भाष व्यंजक हैं। breakpoint पर रुकें, **+** दबाएँ और ऊपर के उदाहरण दें। क्षेत्र, संग्रह, संख्या, तार्किक संक्रिया, पाठ समानता, null, दोनों अंक लिपियाँ और कोष्ठक चलते हैं। संदर्भ परिणाम खोल सकते हैं। चुने frame के मान दिखते हैं।

Evaluation reads the paused process and computes in the debugger. Calls, assignments,
construction, casts, text concatenation, text indexing, static member access and
structural comparisons of distinct records are rejected. There are limits of 4,096
UTF-8 expression bytes, 256 syntax nodes, 48 nesting levels, 1 MiB of heap reads and
1,000 map entries per lookup. Missing variables/keys and invalid indices report errors.
The explicit **Debug Console** continues to accept advanced GDB/C syntax; it does not
use the Bhash Watch grammar. Inferior calls remain disabled. Conditional breakpoints
also retain GDB syntax.

**हिन्दी:** Watch रुकी प्रक्रिया पढ़ता है। कॉल, assignment, नया आवंटन, cast, पाठ जोड़/अनुक्रमण, साझा सदस्य और अलग अभिलेख की संरचनात्मक तुलना अस्वीकार हैं। सीमा 4,096 UTF-8 bytes, 256 nodes, 48 nesting levels, 1 MiB heap और प्रति lookup 1,000 map entries हैं। Debug Console और conditional breakpoints में GDB वाक्यरचना रहती है।

Linux/WSL Bhash sessions now use type `bhash-native`; Windows retains
`bhash-native-windows`. Existing `cppdbg` configurations with `bhashNative: true` route
automatically; no terminal settings need changing. See the
[Hindi Watch reference](../../docs/native-vscode-debug.md#भाष-अभिव्यक्तियाँ-watch-expressions).

**हिन्दी:** Linux/WSL में `bhash-native` और Windows में `bhash-native-windows` है। पुराने `cppdbg` + `bhashNative: true` विन्यास अपने आप सही रास्ता लेते हैं। [हिन्दी Watch संदर्भ](../../docs/native-vscode-debug.md#भाष-अभिव्यक्तियाँ-watch-expressions) देखें।
