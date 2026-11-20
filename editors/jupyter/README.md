# Bhash for Jupyter

The first Bhash kernel supports stateful scalar and text bindings, named functions,
imports, statements and trailing expression results. It runs Bhash on a local JVM
worker and speaks the standard Jupyter kernel protocol.

**हिन्दी:** भाष कर्नेल अदिश और पाठ बंधन, नामित फलन, आयात, कथन और अंतिम व्यंजक का परिणाम देता है। वह स्थानीय JVM worker में भाष चलाता और मानक Jupyter कर्नेल प्रोटोकॉल उपयोग करता है।

## Standard-library lookup / मानक पुस्तकालय खोज

The 0.1.15 package includes a prebuilt browser extension for JupyterLab 4.2+ (4.x) and Notebook 7.2+ (7.x). In a Bhash notebook, click **📚 पुस्तकालय** or run **Bhash: Standard Library Lookup · मानक पुस्तकालय** from the Command Palette. Search Hindi/English names, inspect signatures, parameters, return types and examples, insert a call into the selected code cell, or add an example as a new cell. The first argument is selected for editing. Nothing executes automatically. Offline browsing, clipboard copy, theme colors and narrow panes are supported.

**हिन्दी:** 0.1.15 पैकेज में तैयार ब्राउज़र विस्तार है। Bhash code कक्ष में स्थान चुनकर **📚 पुस्तकालय** खोलें। हिन्दी/English खोज से वर्ग, निर्माता, फलन, तर्क, फल का प्रकार और उदाहरण पढ़ें। कॉल डालें या उदाहरण का नया कक्ष बनाएँ; कोई कक्ष अपने आप नहीं चलता। बदला हुआ, बंद या केवल पढ़ने वाला कक्ष सुरक्षित रहता है। [पूरा हिन्दी अभ्यास](../../tutorial/jupyter-library-lookup/README.md) पढ़ें।

Install the wheel in the Python environment running the Jupyter **server**, then restart that server and reload the browser. The wheel needs no Node.js/npm or JupyterLab rebuild. Java remains required to execute Bhash cells. Classic Notebook 6/NbClassic and VS Code do not load JupyterLab extensions; use the Bhash VS Code extension's lookup in VS Code. Full documentation links require internet; the catalog and examples are bundled.

**हिन्दी:** wheel Jupyter सर्वर वाले Python वातावरण में लगाएँ, फिर सर्वर और पृष्ठ दोबारा खोलें। उपयोगकर्ता को Node.js/npm या frontend build नहीं चाहिए। Bhash चलाने के लिए Java चाहिए। Notebook 6 में यह नियंत्रण उपलब्ध नहीं है। VS Code में उसका Bhash lookup उपयोग करें। शामिल सूची और उदाहरण offline हैं; पूरे दस्तावेज़ के लिंक को इंटरनेट चाहिए।

## Compression, tagged unions and Result / संपीड़न, विकल्पसमूह और परिणाम

Kernel 0.1.14 bundles compiler 0.28.0 with typed C bindings and bounded zlib/gzip compression, including verified Linux x64, Windows x64 and Apple Silicon providers. Download `compression.ipynb` from the release for the Hindi walkthrough. See the [Hindi compression tutorial](../../tutorial/compression/README.md). It also includes tagged unions (`विकल्पसमूह`), exhaustive pattern matching (`मिलान`), and `मानक.परिणाम<त, द>`. See the [Hindi tutorial](../../tutorial/tagged-unions/README.md). Type and function declarations persist across cells; object bindings remain cell-local, so create and match a Result in the same cell or use a persistent factory function. Restart the kernel after upgrading.

**हिन्दी:** 0.1.14 में `मानक.संपीड़न` से सीमित जेडलिब/गज़िप डेटा बनाएँ और खोलें। Linux x64, Windows x64 और Apple Silicon के प्रदाता कर्नेल में शामिल हैं; C विकास उपकरण अलग से नहीं चाहिए। रिलीज़ से `compression.ipynb` लेकर हिन्दी अभ्यास करें। `विकल्पसमूह` से बन्द विकल्प और `मिलान` से हर अवस्था की जाँच करें। `मानक.परिणाम<त, द>` सफलता और अपेक्षित दोष को अलग प्रकारों में रखता है। वर्ग/विकल्पसमूह और फलन की घोषणाएँ अगले कक्षों में मिलती हैं; वस्तु bindings उसी कक्ष तक सीमित हैं। [हिन्दी अभ्यास](../../tutorial/tagged-unions/README.md) पढ़ें।

## Null safety / रिक्त-सुरक्षा

For strict null safety, put `// bhash:null-safety=strict` in the first cell before defining bindings. The profile and nullable scalar bindings persist across later cells. See the [Hindi null-safety tutorial](../../tutorial/null-safety/README.md).

**हिन्दी:** कर्नेल को फिर शुरू करें और पहले कक्ष के आरम्भ में `// bhash:null-safety=strict` लिखें। बाद के कक्षों में भी यही profile रहता है। `मान विकल्प: पूर्णांक? = रिक्त;` के बाद अगले कक्ष में `विकल्प ?? ७` का परिणाम `७` होगा। पूर्ण व्यवहार और सीमाएँ [हिन्दी ट्यूटोरियल](../../tutorial/null-safety/README.md) में पढ़ें।

## Install

For the prebuilt compiler and notebook, follow the [0.1.14 release instructions](https://github.com/bhashitva/bhash/releases/tag/jupyter-0.1.14). The source build below is for development.

**हिन्दी:** पहले से बना संकलक और नोटबुक स्थापित करने के लिए [Jupyter 0.1.14 के रिलीज़ निर्देश](https://github.com/bhashitva/bhash/releases/tag/jupyter-0.1.14) देखें। नीचे का स्रोत निर्माण विकास के लिए है।

For Devanagari name equivalence, joiner diagnostics, and examples, see the [Hindi identifier guide](../../tutorial/unicode-identifiers/README.md).

Build the compiler with a full JDK 17 or newer and Maven. Building the browser
extension from source additionally needs Node.js 22/npm and JupyterLab. In the
Python environment that runs Jupyter, build/install the wheel and register its kernelspec.

**हिन्दी:** पूरा JDK 17+ और Maven से संकलक बनाएँ। स्रोत से ब्राउज़र विस्तार बनाने के लिए Node.js 22/npm और JupyterLab भी चाहिए। जिस Python वातावरण में Jupyter चलता है उसमें wheel बनाएँ/लगाएँ और kernelspec पंजीकृत करें। तैयार wheel उपयोगकर्ताओं को ये build उपकरण नहीं चाहिए।

```sh
mvn package -DskipTests
python -m pip install build "jupyterlab==4.6.6"
python scripts/package_jupyter.py
python -m pip install target/jupyter-dist/bhash_jupyter-0.1.15-py3-none-any.whl
bhash-jupyter-install --compiler target/bhash-compiler-0.1.0-SNAPSHOT.jar
jupyter kernelspec list
```

Pass `--java /path/to/jdk/bin/java` if the JDK is outside PATH. In JupyterLab or
Jupyter Notebook, create a notebook and select **Bhash (भाष)**. In VS Code, use
the Jupyter extension's kernel picker on the host where you registered the
kernelspec. To use a terminal, run `jupyter console --kernel bhash`.

**हिन्दी:** JDK PATH में न हो तो `--java /path/to/jdk/bin/java` दें। JupyterLab या Notebook में नई नोटबुक बनाकर **Bhash (भाष)** चुनें। VS Code में उस होस्ट पर Jupyter का kernel picker खोलें जहाँ kernelspec पंजीकृत है। टर्मिनल में `jupyter console --kernel bhash` चलाएँ।

## पहली नोटबुक

[पहली-नोटबुक.ipynb](पहली-नोटबुक.ipynb) को Jupyter में खोलें और कक्षों को क्रम से चलाएँ।

पहले कक्ष में एक मान घोषित करें। इस मान को अगले कक्षों में भी उपयोग कर सकते हैं।

```bhash
मान कुल: पूर्णांक = ०;
```

दूसरे कक्ष को चलाएँ। अंतिम व्यंजक का मान आउटपुट में दिखेगा। इसे दोबारा चलाने पर
`४` दिखेगा, क्योंकि पिछली बार का मान इसी सत्र में सुरक्षित है।

```bhash
कुल = कुल + २;
कुल
```

प्रतीक्षा (Await) से समयबद्ध काम पूरा होने तक रुक सकते हैं।

```bhash
प्रतीक्षा मानक.कार्य.विलंब(२५);
लिखो("तैयार");
```

## Cell behavior

Place imports first, then named functions and type declarations. After those,
write declarations and statements in execution order, followed by an optional
final scalar expression. A type declared with `वर्ग`, `अभिलेख`, `अनुबंध`,
or `परिगणना` is available to later
cells. Rerun an identical declaration freely. Restart the kernel to edit a
previously declared type or clear the session.

**हिन्दी:** पहले आयात, फिर नामित फलन और प्रकार घोषणाएँ रखें। उसके बाद निष्पादन क्रम में घोषणाएँ और कथन लिखें। अंत में चाहें तो एक अदिश व्यंजक दें। `वर्ग`, `अभिलेख`, `अनुबंध` और `परिगणना` आगे के कक्षों में उपलब्ध रहते हैं। वही घोषणा दोबारा चला सकते हैं। घोषित प्रकार बदलने या सत्र साफ़ करने के लिए कर्नेल फिर शुरू करें।

Top-level scalar bindings persist across cells, including declarations after
statements and declarations with awaited initializers. Initializers execute in
source order. An early return skips any later declarations and preserves the
previous value of any skipped redeclaration. Declarations inside blocks follow
normal block scope. Object, array, collection, and function-value bindings
remain cell-local. Compatible scalar redeclarations reinitialize a slot.
Replacing a function with the same parameter and return types affects calls
in later cells.

**हिन्दी:** ऊपरी स्तर के अदिश बंधन आगे के कक्षों में रहते हैं, चाहे उनकी घोषणा कथन के बाद हो या आरंभक में `प्रतीक्षा` हो। आरंभक स्रोत क्रम में चलते हैं। जल्दी `लौटाओ` के बाद की घोषणाएँ नहीं चलतीं और छूटी पुनर्घोषणा का पुराना मान बचता है। ब्लॉक की घोषणाएँ अपने दायरे में रहती हैं। वस्तु, सरणी, संग्रह और फलन मान के बंधन उस कक्ष तक रहते हैं। संगत अदिश पुनर्घोषणा उसी स्थान को फिर आरंभ करती है। समान मापदंड और वापसी प्रकार वाला नया फलन आगे की कॉल में उपयोग होता है।

A cell containing `मुख्य` or `संकुल` runs as a standalone, single-file Bhash
program. Include its imports and declarations in that cell. It has its own
scope, and its output appears in the notebook.

**हिन्दी:** `मुख्य` या `संकुल` वाला कक्ष अलग एक-फ़ाइल भाष क्रमानुदेश की तरह चलता है। उसके आयात और घोषणाएँ उसी कक्ष में रखें। उसका अपना दायरा होता है और आउटपुट नोटबुक में आता है।

A compile error runs nothing. A runtime error preserves updates already made
to existing scalar bindings and external resources. New declarations and imports
from that cell are discarded. Earlier executable cells never replay.

**हिन्दी:** संकलन त्रुटि पर कक्ष का कोई हिस्सा नहीं चलता। रनटाइम त्रुटि से पहले मौजूदा अदिश बंधन और बाहरी संसाधनों में हुए बदलाव बने रहते हैं। उस कक्ष की नई घोषणाएँ और आयात हट जाते हैं। पुराने निष्पादन कक्ष फिर नहीं चलते।

The kernel recompiles declarations for each cell and copies scalar state into
a fresh class loader. Class static state starts fresh in each cell. Persistent
objects, collections, closures, and provider handles require the shared runtime
described in the design. Diagnostics currently refer to generated cell source
lines. Interrupting the worker clears the session. Completion and inspection
cover committed session names and scalar values.

**हिन्दी:** हर कक्ष के लिए कर्नेल घोषणाएँ फिर संकलित करता है और अदिश अवस्था नए class loader में देता है। वर्ग के साझा सदस्यों की अवस्था नए कक्ष में फिर शुरू होती है। वस्तु, संग्रह, संवृति और प्रदाता हैंडल को स्थायी रखने के लिए साझा रनटाइम चाहिए। निदान अभी निर्मित कक्ष स्रोत की पंक्तियाँ बताते हैं। worker को रोकने पर सत्र साफ़ होता है। सुझाव और निरीक्षण स्वीकृत सत्र नाम और अदिश मान दिखाते हैं।

The adapter runs your code with the permissions of the Jupyter user. Notebook
files can save source and output, so keep credentials out of cells and output.

**हिन्दी:** adapter Jupyter उपयोगकर्ता की अनुमतियों से क्रमानुदेश चलाता है। नोटबुक में स्रोत और आउटपुट सहेजे जा सकते हैं। कक्षों और आउटपुट में पासवर्ड न रखें।

Run the protocol smoke test from the repository root after installing the adapter.

**हिन्दी:** adapter स्थापित करके रिपॉज़िटरी की जड़ से प्रोटोकॉल परीक्षण चलाएँ।

```sh
python editors/jupyter/test_kernel.py target/bhash-compiler-0.1.0-SNAPSHOT.jar
```

[Design and planned stages](../../docs/jupyter-notebook-design.md)

**हिन्दी:** [डिज़ाइन और आगामी चरण](../../docs/jupyter-notebook-design.md)

## Jupyter में MySQL 8.4

[MySQL Connector/J](https://dev.mysql.com/downloads/connector/j/) का विश्वसनीय JAR
डाउनलोड करें और जिस Python परिवेश में Jupyter चलता है, उसमें कर्नेल पंजीकृत करें।

```sh
python -m bhash_jupyter.install --mysql-driver "/path/to/mysql-connector-j-9.3.0.jar"
```

Jupyter शुरू करने से पहले उसके परिवेश में `BHASH_MYSQL_PASSWORD` निर्धारित करें।
यदि सर्वर के लिए अपनी प्रमाणपत्र संस्था उपयोग होती है, तो कर्नेल वाले JDK के
truststore में उसका प्रमाणपत्र रखें। MySQL में `bhash` डेटाबेस बनाकर उपयुक्त
उपयोगकर्ता को पढ़ने की अनुमति दें। फिर **Bhash (भाष)** चुनें और यह सेल चलाएँ।
अपने सर्वर के अनुसार मेज़बान और उपयोगकर्ता बदलें।

```bhash
आयात मानक.डेटा;
आयात मानक.माईएसक्यूएलविन्यास;
आयात मानक.डेटासंबंध;
आयात मानक.डेटाकथन;
आयात मानक.विन्यास;

{
    स्थिर विकल्प: माईएसक्यूएलविन्यास = नया माईएसक्यूएलविन्यास();
    विकल्प.मेज़बान = "db.example.org";
    विकल्प.डेटाबेस = "bhash";
    विकल्प.उपयोगकर्ता = "bhash";
    विकल्प.गुप्तशब्द = विन्यास.परिवेश("BHASH_MYSQL_PASSWORD", "");
    विकल्प.सुरक्षा = "verify-full";
    संसाधन (संबंध: डेटासंबंध = डेटा.माईएसक्यूएल(विकल्प)) {
        संसाधन (कथन: डेटाकथन = संबंध.तैयार("SELECT ?")) {
            कथन.पाठरखो(१, "नमस्ते, MySQL!");
            यदि (कथन.अगला()) {
                लिखो(कथन.पाठलो(०));
            }
        }
    }
}
```

`?` पहले मापदंड के लिए स्थान रखता है। `पाठरखो` उसका मान देता है और
`पाठलो(०)` परिणाम का पहला स्तंभ पढ़ता है। सेल में ही संबंध और कथन बंद
होते हैं। [MySQL अभ्यास](../../tutorial/database-providers/mysql.md) में
लेनदेन सहेजने और वापस लेने के उदाहरण हैं।


## Jupyter में SQL Server 2022

[Microsoft JDBC Driver for SQL Server](https://learn.microsoft.com/sql/connect/jdbc/download-microsoft-jdbc-driver-for-sql-server)
का विश्वसनीय `jre11` JAR डाउनलोड करें। Jupyter वाले Python परिवेश में
दोबारा कर्नेल पंजीकृत करें। MySQL भी उपयोग करते हों तो इसी कमांड में
`--mysql-driver` और उसका JAR पथ भी दें।

```sh
python -m bhash_jupyter.install --sqlserver-driver "/path/to/mssql-jdbc-12.10.2.jre11.jar"
```

Jupyter शुरू करने से पहले उसके परिवेश में `BHASH_SQLSERVER_URL` को
Microsoft JDBC संबंधपाठ दें। अपना मेज़बान, डेटाबेस और उपयोगकर्ता भरें।
गुप्तशब्द सहित संबंधपाठ नोटबुक, लॉग या Git में न रखें।
सर्वर का प्रमाणपत्र Java के विश्वसनीय प्रमाणपत्र भंडार में होना चाहिए।

```text
jdbc:sqlserver://sql.example.org:1433;databaseName=school;user=school_app;password={replace-me};encrypt=true;trustServerCertificate=false
```

Jupyter फिर शुरू करें, **Bhash (भाष)** कर्नेल चुनें और यह सेल चलाएँ।

```bhash
आयात मानक.डेटा;
आयात मानक.डेटासंबंध;
आयात मानक.डेटाकथन;
आयात मानक.विन्यास;

{
    स्थिर पता: पाठ = विन्यास.परिवेश("BHASH_SQLSERVER_URL", "");
    संसाधन (ड: डेटासंबंध = डेटा.एसक्यूएलसर्वर(पता)) {
        संसाधन (क: डेटाकथन = ड.तैयार("SELECT CAST(? AS NVARCHAR(120)) AS संदेश")) {
            क.पाठरखो(१, "नमस्ते, SQL Server!");
            यदि (क.अगला()) { लिखो(क.पाठलो(०)); }
        }
    }
}
```

`?` पहले मापदंड की जगह रखता है। `पाठरखो` उसका मान भेजता है और
`पाठलो(०)` पहली पंक्ति का पहला स्तंभ पढ़ता है। बदलाव करने के लिए
`डेटालेनदेन` को `संसाधन` में खोलें और बदलाव रखने के लिए `सहेजो()`
चलाएँ। उसके बिना दायरा बंद होने पर बदलाव वापस हो जाते हैं।
[SQL Server का पूरा हिन्दी अभ्यास](../../tutorial/database-providers/sqlserver.md)
में चालक, संबंधपाठ और ORM की जानकारी है।
