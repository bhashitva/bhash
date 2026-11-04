Install the Bhash Jupyter kernel and Hindi starter notebook with a full JDK 17 or newer and Python 3.8 or newer. The wheel includes the Bhash compiler. Maven and a source checkout are unnecessary.

```sh
python -m pip install "bhash-jupyter[lab] @ https://github.com/bhashitva/bhash/releases/download/jupyter-0.1.0/bhash_jupyter-0.1.0-py3-none-any.whl"
python -m bhash_jupyter.install --notebook Hindi-Starter.ipynb
python -m jupyterlab Hindi-Starter.ipynb
```

Select **Bhash (भाष)** and run the cells in order. Run the cell that increments `कुल` twice to see `२` and then `४`. Use `--java` when your JDK is outside PATH.

The kernel supports persistent scalar and text bindings, named functions, and top-level await. Interrupting execution clears the session. See the [Jupyter guide](https://github.com/bhashitva/bhash/tree/main/editors/jupyter) for details.
