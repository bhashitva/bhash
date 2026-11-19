Version 0.1.13 bundles compiler 0.27.0 with tagged unions, exhaustive pattern matching, typed Result and Unit. Requires a full JDK 17+; Maven is not required. The installed wheel is verified through a real Jupyter client on Linux, Windows and macOS before publication.

**हिन्दी:** `विकल्पसमूह`, `मिलान`, `मानक.परिणाम<त, द>` और `मानक.इकाई` अब पहले से बने कर्नेल में मिलते हैं। [हिन्दी ट्यूटोरियल](https://github.com/bhashitva/bhash/blob/jupyter-0.1.13/tutorial/tagged-unions/README.md) पढ़ें।

Install or upgrade in the Python environment where you run Jupyter:

```sh
python -m pip install --upgrade "bhash-jupyter[lab] @ https://github.com/bhashitva/bhash/releases/download/jupyter-0.1.13/bhash_jupyter-0.1.13-py3-none-any.whl"
python -m bhash_jupyter.install
python -m pip show bhash-jupyter
```

Restart the notebook kernel and select **Bhash (भाष)**. Re-register external database drivers with `--mysql-driver` or `--sqlserver-driver` as before. Download `hindi-starter.ipynb`, or use `python -m bhash_jupyter.install --notebook Hindi-Starter.ipynb` with a new destination filename. Use `--java` for a JDK outside PATH.

पहले कक्ष में यह उदाहरण चलाएँ। Strict profile के लिए directive सबसे पहले रखें:

```bhash
// bhash:null-safety=strict
स्थिर उत्तर = मानक.परिणाम<पूर्णांक, पाठ>.सफल(७);
मिलान (उत्तर) {
    विकल्प मानक.परिणाम.सफल(स्थिर मूल्य) => अंकपाठ(मूल्य),
    विकल्प मानक.परिणाम.असफल(स्थिर दोष) => दोष
}
```

यह `७` देता है। घोषणाएँ और फलन अगले कक्षों में रहते हैं; वस्तु bindings उसी कक्ष तक सीमित हैं। Result को उसी कक्ष में बनाएँ और मिलान करें, या अगले कक्ष में बुलाने के लिए लौटाने वाला फलन घोषित करें। Strict null safety remains opt-in and persists for the notebook session.
