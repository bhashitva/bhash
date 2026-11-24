# Bhash Jupyter kernel 0.1.17

Bundles compiler **0.30.0**, including package distribution and reuse. Existing keyword/construct and standard-library lookups remain included. Package management is a compiler CLI workflow, not notebook cell syntax; configure project dependencies outside the kernel. Restart the kernel after upgrading.

**हिन्दी:** संकलक 0.30.0 में निश्चित संस्करण, lockfile और जाँच-योग्य स्रोत संग्रह वाला पैकेज कार्यप्रवाह है। पैकेज कमांड टर्मिनल में चलाएँ; वे notebook कक्ष के भाषा-वाक्य नहीं हैं। शब्द/ढाँचे और पुस्तकालय खोज शामिल रहती हैं।

[English guide](https://github.com/bhashitva/bhash/blob/main/docs/package-distribution.en.md) · [हिन्दी अभ्यास](https://github.com/bhashitva/bhash/blob/main/tutorial/package-distribution/README.md)

## Install or upgrade / स्थापना और अद्यतन

Run these commands in the Python environment that starts the Jupyter **server**. **Before registration, record any custom settings you want to keep.**

If you registered a custom compiler, Java executable or database drivers, repeat your previous `--compiler`, `--java`, `--mysql-driver` and `--sqlserver-driver` options when running `python -m bhash_jupyter.install`. `--compiler` overrides the bundled compiler; repeat it only if you want to keep using your own JAR. Registration replaces the kernelspec, so omitting those options may remove the earlier settings. Save downloaded notebooks under new names to preserve existing work.

**हिन्दी:** पंजीकरण से पहले अपना पुराना `--compiler`, `--java`, `--mysql-driver` और `--sqlserver-driver` विकल्प लिख लें और नीचे पंजीकरण करते समय फिर दें। `--compiler` अपना JAR चुनता है; शामिल संकलक चाहिए तो वह विकल्प न दें।

```sh
python -m pip install --upgrade "bhash-jupyter[lab] @ https://github.com/bhashitva/bhash/releases/download/jupyter-0.1.17/bhash_jupyter-0.1.17-py3-none-any.whl"
python -m bhash_jupyter.install
python -m pip show bhash-jupyter
```

For Notebook 7, replace `[lab]` with `[notebook]` in the first command. Download `hindi-starter.ipynb` and `compression.ipynb` from this release; both are also included in the wheel. `SHA256SUMS` covers the wheel, notebooks and `release-info.json`. The provenance file records the exact source revision and bundled compiler/catalog hashes; the release tag points to that source revision.

**हिन्दी:** पैकेज केवल किसी दूसरे कर्नेल के Python वातावरण में लगाने से browser के नियंत्रण नहीं बदलेंगे। Jupyter सर्वर वाले वातावरण में लगाएँ। **Jupyter सर्वर बंद करके दोबारा चलाएँ और browser पृष्ठ reload करें**; केवल कर्नेल restart करना पर्याप्त नहीं है। फिर **Bhash (भाष)** कर्नेल चुनें। `pip show` में `0.1.17` दिखना चाहिए। `python -m jupyter labextension list` में `@bhashitva/library-lookup v0.1.17` enabled दिखेगा।

**हिन्दी:** पुराना Notebook 6/NbClassic यह browser विस्तार नहीं चलाता। VS Code की notebook UI भी JupyterLab विस्तार नहीं लोड करती; वहाँ Bhash VS Code extension का **Bhash: Standard Library Lookup** उपयोग करें। JupyterLab में lookup अलग पैनल में और Notebook 7 में दाएँ sidebar में खुलता है।

## Try it / पहला प्रयोग

Search `class`, `वर्ग`, `foreach` or `प्रत्येक` in **⌨ शब्द/ढाँचे**. Use **Keywords/Constructs** and topic filters, select a result, inspect its preview and insert it into the selected Bhash code cell. `print` under Constructs inserts `लिखो();` with the cursor inside the parentheses; fill it with `"नमस्ते!"` and run the cell yourself.

**हिन्दी:** सूची और उदाहरण offline हैं। छोटे पैनल में परिणाम चुनने पर विवरण खुलता है; **← खोज के नतीजे** से वापस जाएँ। केवल शब्द, पूरा ढाँचा या पुस्तकालय की कॉल डालना source संपादन है; उससे कोई कक्ष अपने-आप नहीं चलता।

Search `absolute` or `निरपेक्ष`, choose the integer overload and insert the call. Replace the selected placeholder with `-३`, then run the cell yourself:

```bhash
मानक.गणित.निरपेक्ष(-३)
```

The result is `३`. Search `text split`, `समुच्चय` or `जेसन` to explore other APIs. Example fragments may need the declarations, imports or resource setup described in their reference pages.

Full installed-kernel client smoke tests, all 57 keywords, the Hindi starter and forced-GC compression tests run on Linux, Windows and macOS before publication. The exact shipped wheel also passes browser tests on current and minimum-supported JupyterLab/Notebook versions, including all 57 keywords and 25 constructs, selection replacement, Undo, indentation, example contents, read-only/stale-cell protection, mode switching, reopening, real execution, offline search and responsive themes.

**हिन्दी:** कर्नेल का भाषा व्यवहार नहीं बदला है। अदिश बंधन, प्रकार और फलन अगले कक्षों में रहते हैं; वस्तु bindings उसी कक्ष तक सीमित हैं। Strict रिक्त-सुरक्षा वैकल्पिक रहती है। पूरी जानकारी [कर्नेल मार्गदर्शिका](https://github.com/bhashitva/bhash/blob/jupyter-0.1.17/editors/jupyter/README.md) में पढ़ें।
