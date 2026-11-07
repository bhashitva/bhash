Version 0.1.5 bundles the current Bhash compiler with the asynchronous HTTP server and RFC 6455 WebSocket library. The installed kernel smoke test runs the [in-memory WebSocket example](https://github.com/bhashitva/bhash/blob/main/examples/websocket/stream.bhash) in a full program cell, including its cancellation and cleanup checks.

Install or upgrade in the Python environment where you run Jupyter. The wheel includes the compiler and needs a full JDK 17 or newer.

```sh
python -m pip install --upgrade "bhash-jupyter[lab] @ https://github.com/bhashitva/bhash/releases/download/jupyter-0.1.5/bhash_jupyter-0.1.5-py3-none-any.whl"
python -m bhash_jupyter.install
python -m pip show bhash-jupyter
```

Restart the notebook kernel and select **Bhash (भाष)**. For a fresh copy of the Hindi starter notebook, download `hindi-starter.ipynb` below or pass `--notebook Hindi-Starter.ipynb` to the installer. The installer needs a new filename when copying the notebook. Use `--java` when your JDK is outside `PATH`.
