Version 0.1.14 bundles compiler 0.28.0 with typed C-library interoperability and bounded zlib/gzip compression. Verified Linux x64 (glibc 2.35+), Windows x64 and macOS 14+ Apple Silicon providers are included. A full JDK 17+ is required; Maven, zlib headers and C development tools are not required for notebook compression.

**हिन्दी:** `मानक.संपीड़न` से जेडलिब/गज़िप डेटा बनाएँ और खोलें। सफलता या अपेक्षित विफलता को `मानक.परिणाम` में सँभालें। प्रदाता कर्नेल में शामिल हैं; code अपने-आप डाउनलोड नहीं होता। [हिन्दी संपीड़न अभ्यास](https://github.com/bhashitva/bhash/blob/jupyter-0.1.14/tutorial/compression/README.md) पढ़ें।

Install or upgrade in the Python environment where you run Jupyter:

```sh
python -m pip install --upgrade "bhash-jupyter[lab] @ https://github.com/bhashitva/bhash/releases/download/jupyter-0.1.14/bhash_jupyter-0.1.14-py3-none-any.whl"
python -m bhash_jupyter.install
python -m pip show bhash-jupyter
```

Restart the notebook kernel and select **Bhash (भाष)**. Download `hindi-starter.ipynb` and `compression.ipynb` from this release; the installed wheel includes both. Full installed-kernel smoke tests, including compression, run on Linux, Windows and macOS before publication.

**हिन्दी:** यदि पहले अलग Java या database drivers चुने थे, `python -m bhash_jupyter.install` में वही `--java`, `--mysql-driver` और `--sqlserver-driver` विकल्प दोहराएँ। हर पंजीकरण kernelspec बदलता है; विकल्प छोड़ने पर पुरानी सेटिंग हट सकती है। नई नोटबुक को अलग फ़ाइल में सहेजें।

पहले कक्ष में यह उदाहरण चलाएँ:

```bhash
आयात मानक.संपीड़न;
आयात मानक.संपीड़नप्रारूप;
मिलान (संपीड़न.संपीड़ितकरो(यूटीएफ८बफ़र("नमस्ते भारत 😀"), संपीड़नप्रारूप.गज़िप, ६)) {
    विकल्प मानक.परिणाम.सफल(स्थिर पैक): { लिखो("संपीड़न सफल"); }
    विकल्प मानक.परिणाम.असफल(स्थिर दोष): { लिखो(दोष.संदेश); }
    विकल्प रिक्त: { लिखो("अप्रत्याशित रिक्त परिणाम"); }
}
```

Scalar bindings, types and functions persist across cells. Object bindings remain cell-local; create and consume buffers/Results in the same cell, or use a persistent function. Strict null safety remains opt-in. Browser/Playground and Android targets do not support C bindings. Native compilation uses the supported SDK; generated native executables remain independent of JVM/.NET.
