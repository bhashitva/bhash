# Browser WebAssembly target

Bhash can publish the current language, including classes, constructors, single inheritance and virtual methods, as a browser application backed by the .NET 10 WebAssembly runtime. The compiler remains Java. The browser runs the generated application; it does not compile Bhash source.

## Build and run

Install JDK 17+, Maven and the .NET 10 SDK. Install the WebAssembly workload once:

```sh
dotnet workload install wasm-tools
mvn package
java -jar target/bhash-compiler-0.1.0-SNAPSHOT.jar compile examples/factorial-objects.bhash --target wasm-browser -o target/factorial-web
python -m http.server 8000 --directory target/factorial-web
```

Open <http://localhost:8000>, enter `५` and select **Run / चलाएँ**. The output includes `गुणनफल: १२०`. Both factorial examples work without source changes. Enter `20` to verify the exact 64-bit result `२४३२९०२००८१७६६४००००`.

`--target wasm` is an alias. The default output directory is `<source-stem>-web`. `BHASH_DOTNET` can select the dotnet executable. Builds require the SDK/workload, and initial restores may need NuGet access. Users of the published page need only a modern browser with WebAssembly and module workers; no installed Java or .NET runtime is needed.

The entire output directory is deployable static content. Serve JavaScript as `text/javascript` and WASM as `application/wasm`. Use HTTP locally or HTTPS remotely; opening `index.html` through `file://` does not work. Blazor, a backend server and cross-origin isolation headers are not required by this single-threaded target.

## Input, output and execution

Supply all input before running, with one integer per line for each `पढ़ो()` call. ASCII and Devanagari digits follow the same validation as JVM/CLR. Exhausted input produces the existing Bhash EOF diagnostic; it does not wait for more input.

Each run starts a fresh worker and managed runtime. **Stop** terminates that worker, including a program in an infinite loop. Output and errors are captured as text and displayed after the program finishes. Output before a stopped run is not available. Reruns start with fresh program state. The host transfers strings and exit codes, so 64-bit Bhash values never pass through JavaScript numbers.

The output is a .NET runtime/application bundle, not a standalone WASI module. This initial target uses the managed interpreter on the WebAssembly runtime, with AOT and native relinking disabled. There is no browser DOM API in Bhash yet. `run --target wasm-browser` reports the compile/serve instructions instead of launching a command-line process.

Generated output has a `.bhash-wasm-output` asset manifest. Rebuilds replace only unchanged Bhash-managed output; added or edited files cause replacement to be refused. Keep custom hosting files outside this generated directory.

## Verification

The browser workflow builds the compiler and four browser applications, then executes Chromium tests covering both factorial programs, Devanagari input, 20!, input errors, EOF, constructors/inheritance, virtual dispatch, null errors, division, signed arithmetic, exact 64-bit values, text rendering, reruns and stop. Its artifact includes runnable bundles and the compiler JAR.

To run locally after `mvn package`:

```sh
npm install --no-save --package-lock=false playwright@1.56.1
npx playwright install chromium
node scripts/browser_smoke_test.cjs
```
