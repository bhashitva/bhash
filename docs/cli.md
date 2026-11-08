# Bhash command-line compiler / कमांड लाइन संकलक

Download `bhash-compiler-0.20.0.jar` and its checksum from the
[Bhash 0.20.0 release](https://github.com/bhashitva/bhash/releases/tag/bhash-0.20.0).
Install a full JDK 17 or newer and make sure `java -version` reports that JDK.
The JAR includes the compiler, standard library, and dependencies. Maven is
needed only when building the compiler from source.

**हिन्दी:** रिलीज़ से संकलक JAR और उसका SHA-256 चेकसम लें। पूरा JDK 17 या नया
संस्करण स्थापित करें। अपने भाष स्रोत को UTF-8 में सहेजें और नीचे का आदेश चलाएँ।

Compare the published checksum with `sha256sum -c bhash-compiler-0.20.0.jar.sha256`
on Linux, `shasum -a 256 -c bhash-compiler-0.20.0.jar.sha256` on macOS, or
`Get-FileHash .\bhash-compiler-0.20.0.jar -Algorithm SHA256` in PowerShell.

Save this as `hello.bhash`:

```bhash
फलन मुख्य() -> शून्य {
    लिखो("नमस्ते, दुनिया!");
}
```

```sh
java -jar bhash-compiler-0.20.0.jar run hello.bhash --target jvm
java -jar bhash-compiler-0.20.0.jar compile hello.bhash --target jvm -o hello.jar
java -jar hello.jar
```

Use `check hello.bhash` for syntax diagnostics or `targets` to list the
targets. You can pass a project directory or `bhash.project` in place of
the source file. The same compiler supports additional targets with their
respective toolchains. See [.NET](clr-target.md),
[native](managed-native-toolchain.md), and [browser WebAssembly](wasm-browser.md).

This release includes the configurable async HTTP and WebSocket capacity
from [PR #88](https://github.com/bhashitva/bhash/pull/88). See the
[server guide](async-http-server.md) and [WebSocket guide](websocket.md).
