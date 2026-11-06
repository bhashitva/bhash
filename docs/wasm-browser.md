# Browser WebAssembly target

Bhash can publish the current language, including classes, constructors, single inheritance and virtual methods, as a browser application backed by the .NET 10 WebAssembly runtime. The compiler remains Java. The browser runs the generated application; it does not compile Bhash source.

**हिन्दी:** भाष वर्ग, निर्माता, एकल विरासत और आभासी विधियों समेत क्रमानुदेश को .NET 10 WebAssembly रनटाइम पर चलने वाला ब्राउज़र ऐप बना सकता है। Java संकलक पहले ऐप बनाता है, फिर ब्राउज़र निर्मित ऐप चलाता है।

## Build and run

Install JDK 17+, Maven and the .NET 10 SDK. Install the WebAssembly workload once:

**हिन्दी:** JDK 17+, Maven और .NET 10 SDK स्थापित करें। WebAssembly workload एक बार स्थापित करें:

```sh
dotnet workload install wasm-tools
mvn package
java -jar target/bhash-compiler-0.1.0-SNAPSHOT.jar compile examples/factorial-objects.bhash --target wasm-browser -o target/factorial-web
python -m http.server 8000 --directory target/factorial-web
```

Open <http://localhost:8000>, enter `५` and select **Run / चलाएँ**. The output includes `गुणनफल: १२०`. Both factorial examples work without source changes. Enter `20` to verify the exact 64-bit result `२४३२९०२००८१७६६४००००`.

**हिन्दी:** <http://localhost:8000> खोलें, `५` दें और **चलाएँ** चुनें। परिणाम में `गुणनफल: १२०` मिलेगा। दोनों गुणनफल उदाहरण बिना स्रोत बदले चलते हैं। `20` पर सटीक 64-bit परिणाम `२४३२९०२००८१७६६४००००` देखें।

`--target wasm` is an alias. The default output directory is `<source-stem>-web`. `BHASH_DOTNET` can select the dotnet executable. Builds require the SDK/workload, and initial restores may need NuGet access. Users of the published page need only a modern browser with WebAssembly and module workers; no installed Java or .NET runtime is needed.

**हिन्दी:** `--target wasm` भी वही लक्ष्य चुनता है। डिफ़ॉल्ट आउटपुट `<source-stem>-web` है। `BHASH_DOTNET` से dotnet का पथ दें। निर्माण के लिए SDK और workload चाहिए। पहली बार NuGet तक पहुँच लग सकती है। प्रकाशित पृष्ठ के उपयोगकर्ता को WebAssembly और module workers वाला आधुनिक ब्राउज़र चाहिए।

The entire output directory is deployable static content. Serve JavaScript as `text/javascript` and WASM as `application/wasm`. Use HTTP locally or HTTPS remotely; opening `index.html` through `file://` does not work. Blazor, a backend server and cross-origin isolation headers are not required by this single-threaded target.

**हिन्दी:** पूरे आउटपुट फ़ोल्डर को स्थिर वेब सामग्री की तरह परोसें। JavaScript के लिए `text/javascript` और WASM के लिए `application/wasm` दें। स्थानीय रूप से HTTP और दूरस्थ सेवा पर HTTPS उपयोग करें। `file://` से `index.html` नहीं चलता। इस एकल-थ्रेड लक्ष्य को Blazor, बैकएंड या cross-origin isolation headers नहीं चाहिए।

On Linux/WSL, generated browser directories have mode `0755` and files have mode
`0644`, so a separate HTTP-server user can read the bundle. Build as your normal
user, without `sudo`; output belongs to the account running the compiler. In
VS Code Remote WSL, launch VS Code from a normal-user terminal (`code .`). If an
earlier root build left your project's `build` directory owned by root, run the
following once from the project root, as your normal user:

**हिन्दी:** Linux/WSL पर बने फ़ोल्डर `0755` और फ़ाइलें `0644` होती हैं ताकि HTTP सर्वर खाता उन्हें पढ़ सके। सामान्य उपयोगकर्ता से संकलन करें और VS Code Remote WSL को उसी टर्मिनल से `code .` द्वारा खोलें। यदि पुराने root निर्माण से `build` का स्वामी root है, तो परियोजना की जड़ से सामान्य उपयोगकर्ता के रूप में नीचे का आदेश एक बार चलाएँ:

```sh
sudo chown -R "$(id -un):$(id -gn)" build
```

Parent directories must also permit the server account to traverse them. The
compiler only sets permissions within its generated output directory.

**हिन्दी:** HTTP सर्वर खाते को ऊपर के फ़ोल्डरों से होकर जाने की अनुमति भी चाहिए। संकलक केवल अपने आउटपुट फ़ोल्डर के भीतर अनुमतियाँ तय करता है।

## Input, output and execution

Supply all input before running, with one integer per line for each `पढ़ो()` call. ASCII and Devanagari digits follow the same validation as JVM/CLR. Exhausted input produces the existing Bhash EOF diagnostic; it does not wait for more input.

**हिन्दी:** चलाने से पहले सारा इनपुट दें। हर `पढ़ो()` के लिए एक पंक्ति में एक पूर्णांक रखें। ASCII और देवनागरी अंक JVM/CLR जैसे जाँचे जाते हैं। इनपुट खत्म होने पर EOF निदान मिलता है।

Each run starts a fresh worker and managed runtime. **Stop** terminates that worker, including a program in an infinite loop. Output and errors are captured as text and displayed after the program finishes. Output before a stopped run is not available. Reruns start with fresh program state. The host transfers strings and exit codes, so 64-bit Bhash values never pass through JavaScript numbers.

**हिन्दी:** हर रन नया worker और रनटाइम शुरू करता है। **रोकें** अनंत चक्र वाले worker को भी समाप्त करता है। सामान्य समापन पर आउटपुट और त्रुटि पाठ के रूप में दिखते हैं। रोके गए रन का पिछला आउटपुट उपलब्ध नहीं होता। अगला रन ताज़ी अवस्था से शुरू होता है। 64-bit मान JavaScript संख्याओं से होकर नहीं गुजरते क्योंकि होस्ट पाठ और exit code भेजता है।

The output is a .NET runtime/application bundle, not a standalone WASI module. This initial target uses the managed interpreter on the WebAssembly runtime, with AOT and native relinking disabled. There is no browser DOM API in Bhash yet. `run --target wasm-browser` reports the compile/serve instructions instead of launching a command-line process.

**हिन्दी:** आउटपुट .NET ऐप और रनटाइम का बंडल है। यह WebAssembly रनटाइम पर managed interpreter उपयोग करता है। AOT और native relinking बंद हैं, तथा भाष में अभी DOM API नहीं है। `run --target wasm-browser` स्थानीय प्रक्रिया शुरू करने के बजाय संकलन और HTTP से परोसने के निर्देश देता है।

Generated output has a `.bhash-wasm-output` asset manifest. Rebuilds replace only unchanged Bhash-managed output; added or edited files cause replacement to be refused. Keep custom hosting files outside this generated directory.

**हिन्दी:** आउटपुट में `.bhash-wasm-output` asset manifest होता है। दोबारा संकलन केवल बिना बदली, Bhash द्वारा निर्मित सामग्री बदल सकता है। अपनी होस्टिंग फ़ाइलें इस फ़ोल्डर से बाहर रखें।

## Verification

The browser workflow builds the compiler and eight browser applications, then executes Chromium tests covering both factorial programs, Devanagari input, 20!, input errors, EOF, constructors/inheritance, virtual dispatch, null errors, division, signed arithmetic, exact 64-bit values, text rendering, reruns and stop. The combined v0.3 example also exercises generic
interfaces/classes/functions, overloads and static members. Selection examples cover grouped
labels, default behavior, 64-bit values, Unicode, booleans and nested break/continue. Its artifact includes runnable bundles and the compiler JAR.

**हिन्दी:** ब्राउज़र CI संकलक और उदाहरण बनाकर Chromium में परीक्षण चलाता है। इनमें गुणनफल, देवनागरी इनपुट, EOF, वर्ग, विरासत, गतिशील विधियाँ, null त्रुटियाँ, 64-bit गणना, पाठ, पुनः चलाना और रोकना शामिल हैं। सामान्य प्रकार, ओवरलोड, साझा सदस्य और चयन की अलग जाँचें हैं। runnable bundles और संकलक JAR artifact में मिलते हैं।

To run locally after `mvn package`:

**हिन्दी:** `mvn package` के बाद स्थानीय परीक्षण चलाएँ:

```sh
npm install --no-save --package-lock=false playwright@1.56.1
npx playwright install chromium
node scripts/browser_smoke_test.cjs
```

Published bundles include Bhash `LICENSE`, `NOTICE` and `LICENSING.md` files. Preserve
these and applicable platform notices when redistributing; see [licensing](../LICENSING.md).

**हिन्दी:** प्रकाशित बंडल के `LICENSE`, `NOTICE`, `LICENSING.md` और लागू प्लेटफ़ॉर्म सूचनाएँ पुनर्वितरण में साथ रखें। [लाइसेंस निर्देश](../LICENSING.md) देखें।

## Self-hosted interactive playground

The [playground package](../deploy/playground/README.md) adds an editable,
mobile-friendly UI and an asynchronous compilation API around this backend.
Statement mode supplies the entry function automatically. The API compiles on
the server and the resulting program runs in an isolated browser worker.

**हिन्दी:** [Playground पैकेज](../deploy/playground/README.md) इस लक्ष्य के चारों ओर संपादक और असमकालिक संकलन API देता है। सरल मोड प्रवेश फलन खुद बनाता है। API सर्वर पर संकलित करता है और बना क्रमानुदेश अलग ब्राउज़र worker में चलता है।

Browser output is capped at 65,536 UTF-16 code units and errors at 8,192 to bound
common print loops. Reaching the output limit raises a Bhash failure. This limit
applies to browser bundles; console JVM/CLR output is unchanged. Output remains
buffered until completion. The playground adds a 15-second execution timeout
and a separate 90-second runtime-load deadline; it does not provide live stdin.

**हिन्दी:** ब्राउज़र बंडल में आउटपुट की सीमा 65,536 UTF-16 इकाइयाँ और त्रुटि पाठ की 8,192 है। सीमा पहुँचने पर भाष त्रुटि मिलती है। आउटपुट पूरा होने तक जमा होता है। Playground निष्पादन के लिए १५ सेकंड और रनटाइम लोड के लिए अलग ९० सेकंड देता है। चलते समय नया stdin नहीं लिया जाता।

`BHASH_WASM_OFFLINE=true` disables NuGet package sources during publication.
Matching SDK/workload/reference packages must already be installed/restored.
The deployment image warms those dependencies before disabling runtime egress.

**हिन्दी:** `BHASH_WASM_OFFLINE=true` प्रकाशन में NuGet स्रोत बंद करता है। मेल खाते SDK, workload और reference packages पहले तैयार हों। तैनाती image इन्हें पहले भरता है, फिर रनटाइम के बाहरी नेटवर्क की अनुमति बंद करता है।
