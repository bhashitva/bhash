Version **0.1.15** adds an offline standard-library lookup to **JupyterLab 4.2–4.x** and **Jupyter Notebook 7.2–7.x**. It bundles compiler **0.28.0**, the same verified Linux x64, Windows x64 and Apple Silicon compression providers, and the Hindi starter/compression notebooks. A full JDK 17+ is required to execute Bhash cells. End users do not need Maven, Node.js/npm, C development tools or a JupyterLab rebuild.

**हिन्दी:** नोटबुक की toolbar में **📚 पुस्तकालय** दबाएँ, या Command Palette में **Bhash: Standard Library Lookup · मानक पुस्तकालय** खोलें। 131 प्रकार और 940 सदस्यों में हिन्दी या अंग्रेज़ी नाम से खोजें, हस्ताक्षर और उदाहरण पढ़ें, फिर चुने code कक्ष में कॉल डालें। पहला तर्क चुन जाता है; अपने मान भरें। **Undo** से संपादन वापस लें। उदाहरण नया code कक्ष बनाता है। कक्ष अपने-आप नहीं चलता।

The lookup supports Hindi/English search, classes and functions, overloads, parameters, return types, target restrictions, examples, light/dark themes and narrow panes. Changed, closed, read-only and non-Bhash cells are protected. The catalog and examples are included for offline use; full documentation links open online. [Hindi lookup tutorial](https://github.com/bhashitva/bhash/blob/jupyter-0.1.15/tutorial/jupyter-library-lookup/README.md).

## Install or upgrade / स्थापना और अद्यतन

Run these commands in the Python environment that starts the Jupyter **server**:

```sh
python -m pip install --upgrade "bhash-jupyter[lab] @ https://github.com/bhashitva/bhash/releases/download/jupyter-0.1.15/bhash_jupyter-0.1.15-py3-none-any.whl"
python -m bhash_jupyter.install
python -m pip show bhash-jupyter
```

For Notebook 7, replace `[lab]` with `[notebook]` in the first command. Download `hindi-starter.ipynb` and `compression.ipynb` from this release; both are also included in the wheel. `SHA256SUMS` covers the wheel, notebooks and `release-info.json`. The provenance file records the exact source revision and bundled compiler/catalog hashes; the release tag points to that source revision.

**हिन्दी:** पैकेज केवल किसी दूसरे कर्नेल के Python वातावरण में लगाने से browser के नियंत्रण नहीं बदलेंगे। Jupyter सर्वर वाले वातावरण में लगाएँ। **Jupyter सर्वर बंद करके दोबारा चलाएँ और browser पृष्ठ reload करें**; केवल कर्नेल restart करना पर्याप्त नहीं है। फिर **Bhash (भाष)** कर्नेल चुनें। `pip show` में `0.1.15` दिखना चाहिए। `python -m jupyter labextension list` में `@bhashitva/library-lookup v0.1.15` enabled दिखेगा।

If you registered a custom Java executable or database drivers, repeat your previous `--java`, `--mysql-driver` and `--sqlserver-driver` options when running `python -m bhash_jupyter.install`. Registration replaces the kernelspec, so omitting those options may remove the earlier settings. Save downloaded notebooks under new names to preserve existing work.

**हिन्दी:** पुराना Notebook 6/NbClassic यह browser विस्तार नहीं चलाता। VS Code की notebook UI भी JupyterLab विस्तार नहीं लोड करती; वहाँ Bhash VS Code extension का **Bhash: Standard Library Lookup** उपयोग करें। JupyterLab में lookup अलग पैनल में और Notebook 7 में दाएँ sidebar में खुलता है।

## Try it / पहला प्रयोग

Search `absolute` or `निरपेक्ष`, choose the integer overload and insert the call. Replace the selected placeholder with `-३`, then run the cell yourself:

```bhash
मानक.गणित.निरपेक्ष(-३)
```

The result is `३`. Search `text split`, `समुच्चय` or `जेसन` to explore other APIs. Example fragments may need the declarations, imports or resource setup described in their reference pages.

Full installed-kernel client smoke tests, all 57 keywords, the Hindi starter and forced-GC compression tests run on Linux, Windows and macOS before publication. The exact shipped wheel also passes browser tests on current and minimum-supported JupyterLab/Notebook versions, including selection replacement, Undo, example contents, read-only/stale-cell protection, real execution, offline search and responsive themes.

**हिन्दी:** कर्नेल का भाषा व्यवहार नहीं बदला है। अदिश बंधन, प्रकार और फलन अगले कक्षों में रहते हैं; वस्तु bindings उसी कक्ष तक सीमित हैं। Strict रिक्त-सुरक्षा वैकल्पिक रहती है। पूरी जानकारी [कर्नेल मार्गदर्शिका](https://github.com/bhashitva/bhash/blob/jupyter-0.1.15/editors/jupyter/README.md) में पढ़ें।
