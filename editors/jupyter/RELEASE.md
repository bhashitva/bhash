Version 0.1.10 adds MySQL 8.4 access to Bhash notebooks. Supply a trusted Connector/J JAR with `--mysql-driver` when registering the kernel. See the [Hindi setup and query example](https://github.com/bhashitva/bhash/blob/jupyter-0.1.10/editors/jupyter/README.md#jupyter-में-mysql-84).

Install or upgrade in the Python environment where you run Jupyter. The wheel includes the compiler and needs a full JDK 17 or newer.

```sh
python -m pip install --upgrade "bhash-jupyter[lab] @ https://github.com/bhashitva/bhash/releases/download/jupyter-0.1.10/bhash_jupyter-0.1.10-py3-none-any.whl"
python -m bhash_jupyter.install
# For MySQL, register with the driver instead:
# python -m bhash_jupyter.install --mysql-driver "/path/to/mysql-connector-j.jar"
python -m pip show bhash-jupyter
```

Restart the notebook kernel and select **Bhash (भाष)**. For a fresh copy of the Hindi starter notebook, download `hindi-starter.ipynb` below or pass `--notebook Hindi-Starter.ipynb` to the installer. The installer needs a new filename when copying the notebook. Use `--java` when your JDK is outside `PATH`.
