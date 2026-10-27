# Three-package application

`app` consumes `greetings` version 1.0.0, which consumes `core` version 1.0.0.
All dependencies are sibling local directories; the app's committed lockfile
covers both libraries. From the compiler repository root:

```sh
java -jar target/bhash-compiler-0.1.0-SNAPSHOT.jar deps verify examples/packages/app
java -jar target/bhash-compiler-0.1.0-SNAPSHOT.jar run examples/packages/app
java -jar target/bhash-compiler-0.1.0-SNAPSHOT.jar run examples/packages/app --target clr
java -jar target/bhash-compiler-0.1.0-SNAPSHOT.jar compile examples/packages/app --target wasm-browser -o target/packages-web
```

Expected output:

```text
नमस्ते, दुनिया! उत्तर: ४२
```

JVM requires a JDK; CLR requires .NET 10; browser compilation additionally needs
`wasm-tools`. The browser output must be served over HTTP as described in
[the browser target guide](../../docs/wasm-browser.md).

After editing a library deliberately:

```sh
java -jar target/bhash-compiler-0.1.0-SNAPSHOT.jar deps lock examples/packages/app --offline
```

Review and commit the source change together with `app/bhash.lock`.
Read the [package guide](../../docs/source-packages.md) or
[Hindi tutorial](../../tutorial/packages/README.md) for Git pins and other rules.
