Version 0.1.8 bundles Bhash 0.22.0 with [string formatting](https://github.com/bhashitva/bhash/blob/main/tutorial/standard-library/08-formatting.md), [generic sets](https://github.com/bhashitva/bhash/blob/main/tutorial/standard-library/09-sets.md), and more precise missing-semicolon diagnostics. The installed wheel is tested with the Hindi starter notebook on Linux, Windows, and macOS.

Install or upgrade in the Python environment where you run Jupyter. The wheel includes the compiler and needs a full JDK 17 or newer.

```sh
python -m pip install --upgrade "bhash-jupyter[lab] @ https://github.com/bhashitva/bhash/releases/download/jupyter-0.1.8/bhash_jupyter-0.1.8-py3-none-any.whl"
python -m bhash_jupyter.install
python -m pip show bhash-jupyter
```

Restart the notebook kernel and select **Bhash (भाष)**. For a fresh copy of the Hindi starter notebook, download `hindi-starter.ipynb` below or pass `--notebook Hindi-Starter.ipynb` to the installer. The installer needs a new filename when copying the notebook. Use `--java` when your JDK is outside `PATH`.
