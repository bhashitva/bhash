Install the Bhash Jupyter kernel and Hindi starter notebook with a full JDK 17 or newer and Python 3.8 or newer. The wheel includes the Bhash compiler. Maven and a source checkout are unnecessary.

```sh
python -m pip install "bhash-jupyter[lab] @ https://github.com/bhashitva/bhash/releases/download/jupyter-0.1.3/bhash_jupyter-0.1.3-py3-none-any.whl"
python -m bhash_jupyter.install --notebook Hindi-Starter.ipynb
python -m jupyterlab Hindi-Starter.ipynb
```

Select **Bhash (भाष)** and run the cells in order. Run the cell that increments `कुल` twice to see `२` and then `४`. Use `--java` when your JDK is outside PATH.

Top-level scalar bindings now persist across cells regardless of declaration position. Initializers run in source order, so prompts appear before input is requested. Block-local declarations stay local, and an early return skips later declarations without overwriting existing bindings.
