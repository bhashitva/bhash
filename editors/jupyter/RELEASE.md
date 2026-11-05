Version 0.1.4 bundles the Unicode 18 identifier rules from [PR #72](https://github.com/bhashitva/bhash/pull/72). Equivalent Devanagari spellings and valid ZWJ/ZWNJ variants resolve to the same name across cells. Completion and inspection recognize those variants, and invalid joiners report their source location. See the [Hindi identifier guide and examples](https://github.com/bhashitva/bhash/tree/main/tutorial/unicode-identifiers).

Install or upgrade in the Python environment where you run Jupyter. The wheel includes the compiler and needs a full JDK 17 or newer.

```sh
python -m pip install --upgrade "bhash-jupyter[lab] @ https://github.com/bhashitva/bhash/releases/download/jupyter-0.1.4/bhash_jupyter-0.1.4-py3-none-any.whl"
python -m bhash_jupyter.install
python -m pip show bhash-jupyter
```

Restart the notebook kernel and select **Bhash (भाष)**. For a fresh copy of the Hindi starter notebook, download `hindi-starter.ipynb` below or pass `--notebook Hindi-Starter.ipynb` to the installer. The installer needs a new filename when copying the notebook. Use `--java` when your JDK is outside `PATH`.
