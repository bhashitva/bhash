# Bhash command-line compiler / कमांड लाइन संकलक

Download `bhash-compiler-0.21.0.jar` and its checksum from the
[Bhash 0.21.0 release](https://github.com/bhashitva/bhash/releases/tag/bhash-0.21.0).
Install a full JDK 17 or newer and make sure `java -version` reports that JDK.
The JAR includes the compiler, standard library, and dependencies. Maven is
needed only when building the compiler from source.

**हिन्दी:** रिलीज़ से संकलक JAR और उसका SHA-256 चेकसम लें। पूरा JDK 17 या नया
संस्करण स्थापित करें। अपने भाष स्रोत को UTF-8 में सहेजें और नीचे का आदेश चलाएँ।

Compare the published checksum with `sha256sum -c bhash-compiler-0.21.0.jar.sha256`
on Linux, `shasum -a 256 -c bhash-compiler-0.21.0.jar.sha256` on macOS, or
`Get-FileHash .\bhash-compiler-0.21.0.jar -Algorithm SHA256` in PowerShell.

Save this as `hello.bhash`:

```bhash
फलन मुख्य() -> शून्य {
    लिखो("नमस्ते, दुनिया!");
}
```

```sh
java -jar bhash-compiler-0.21.0.jar run hello.bhash --target jvm
java -jar bhash-compiler-0.21.0.jar compile hello.bhash --target jvm -o hello.jar
java -jar hello.jar
```

Use `check hello.bhash` for syntax diagnostics or `targets` to list the
targets. You can pass a project directory or `bhash.project` in place of
the source file. The same compiler supports additional targets with their
respective toolchains. See [.NET](clr-target.md),
[native](managed-native-toolchain.md), and [browser WebAssembly](wasm-browser.md).

This release includes command-line argument support from
[PR #90](https://github.com/bhashitva/bhash/pull/90).

## Command-line arguments

Declare `फलन मुख्य(तर्क: पाठ[]) -> शून्य` to receive the arguments in order,
starting at index zero and excluding the executable, JAR or DLL name. Empty
strings are kept. Without arguments the array is empty. The same parameter is
available on `असमकालिक फलन मुख्य`. For an entry point that takes no arguments,
keep `फलन मुख्य() -> शून्य`.

**हिन्दी:** कमांड लाइन के तर्क पाने के लिए `फलन मुख्य(तर्क: पाठ[]) -> शून्य`
लिखें। पहला तर्क `तर्क[०]` है। फ़ाइल का नाम सरणी में शामिल नहीं होता। खाली
पाठ भी एक तर्क है। कोई तर्क न देने पर सरणी खाली है। असमकालिक `मुख्य` भी
यह मापदंड ले सकता है।

```bhash
फलन मुख्य(तर्क: पाठ[]) -> शून्य {
    प्रत्येक (तर्क में तर्कांश: पाठ) {
        लिखो(तर्कांश);
    }
}
```

Save the example as `args.bhash`. Place compiler options before `--`, and quote a value that contains spaces.

**हिन्दी:** उदाहरण को `args.bhash` में सहेजें। `--` से पहले संकलक के विकल्प और
उसके बाद क्रमानुदेश के तर्क दें। रिक्त स्थान वाला तर्क उद्धरण में रखें।

```sh
java -jar bhash-compiler-0.21.0.jar run args.bhash --target jvm -- input.txt 'two words'
java -jar bhash-compiler-0.21.0.jar compile args.bhash --target jvm -o args.jar
java -jar args.jar input.txt 'two words'
```

Both launches print `input.txt` and `two words` on separate lines. For .NET, append
the arguments after `dotnet path/to/BhashProgram.dll`. For native, append them after
the executable. The native launcher reserves `--bhash-license` and
`--bhash-build-info` when supplied as the sole argument. The Playground and
Jupyter supply an empty array. On Windows,
`java.exe` can replace characters outside its active code page before Bhash receives
the arguments. A directly launched .NET or native build accepts Unicode arguments
through the Windows Unicode command line.

**हिन्दी:** दोनों आदेश अलग पंक्तियों पर `input.txt` और `two words` लिखते हैं।
.NET में DLL के नाम के बाद और नेटिव में executable के नाम के बाद तर्क दें।
नेटिव में अकेला `--bhash-license` या `--bhash-build-info` देने पर उसी विकल्प
की जानकारी मिलती है।
Playground और Jupyter में सरणी खाली रहती है। Windows में `java.exe` अपनी
कोड पेज से बाहर के अक्षर बदल सकता है। सीधे चलाए गए .NET और नेटिव निर्माण
Windows से यूनिकोड तर्क ले सकते हैं।
