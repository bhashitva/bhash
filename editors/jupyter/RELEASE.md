Version 0.1.11 adds SQL Server 2022 access in notebooks using the Microsoft JDBC driver. You can register both SQL Server and MySQL drivers in the same kernel. [Hindi SQL Server setup and example](https://github.com/bhashitva/bhash/blob/jupyter-0.1.11/editors/jupyter/README.md#jupyter-में-sql-server-2022).

Install or upgrade in the Python environment where you run Jupyter. The wheel includes the compiler and needs a full JDK 17 or newer.

```sh
python -m pip install --upgrade "bhash-jupyter[lab] @ https://github.com/bhashitva/bhash/releases/download/jupyter-0.1.11/bhash_jupyter-0.1.11-py3-none-any.whl"
python -m bhash_jupyter.install
# For SQL Server, register with its driver:
# python -m bhash_jupyter.install --sqlserver-driver "/path/to/mssql-jdbc-12.10.2.jre11.jar"
# For MySQL, register with its driver:
# python -m bhash_jupyter.install --mysql-driver "/path/to/mysql-connector-j.jar"
python -m pip show bhash-jupyter
```

Restart the notebook kernel and select **Bhash (भाष)**. For a fresh copy of the Hindi starter notebook, download `hindi-starter.ipynb` below or pass `--notebook Hindi-Starter.ipynb` to the installer. The installer needs a new filename when copying the notebook. Use `--java` when your JDK is outside `PATH`.
