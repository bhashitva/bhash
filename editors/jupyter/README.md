# Bhash for Jupyter

The first Bhash kernel supports stateful scalar and text bindings, named functions,
imports, statements and trailing expression results. It runs Bhash on a local JVM
worker and speaks the standard Jupyter kernel protocol.

**हिन्दी:** भाष कर्नेल अदिश और पाठ बंधन, नामित फलन, आयात, कथन और अंतिम व्यंजक का परिणाम देता है। वह स्थानीय JVM worker में भाष चलाता और मानक Jupyter कर्नेल प्रोटोकॉल उपयोग करता है।

## Install

For the prebuilt compiler and notebook, follow the [0.1.8 release instructions](https://github.com/bhashitva/bhash/releases/tag/jupyter-0.1.8). The source build below is for development.

**हिन्दी:** पहले से बना संकलक और नोटबुक स्थापित करने के लिए [Jupyter 0.1.8 के रिलीज़ निर्देश](https://github.com/bhashitva/bhash/releases/tag/jupyter-0.1.8) देखें। नीचे का स्रोत निर्माण विकास के लिए है।

For Devanagari name equivalence, joiner diagnostics, and examples, see the [Hindi identifier guide](../../tutorial/unicode-identifiers/README.md).

Build the compiler with a full JDK 17 or newer and Maven. In the Python environment
that runs Jupyter, install the adapter and register its kernelspec.

**हिन्दी:** पूरा JDK 17+ और Maven से संकलक बनाएँ। जिस Python वातावरण में Jupyter चलता है उसमें adapter स्थापित करें और उसका kernelspec पंजीकृत करें।

```sh
mvn package -DskipTests
python -m pip install ./editors/jupyter
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
