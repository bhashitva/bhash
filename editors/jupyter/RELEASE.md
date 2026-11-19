Version 0.1.12 bundles compiler 0.26.0 with opt-in null safety and the safer everyday programming features. A strict first-cell directive selects the profile for the notebook session, including nullable scalar bindings that persist across cells. Restart the kernel before selecting strict mode in an existing session.

**हिन्दी:** यह कर्नेल nullable प्रकार (`T?`), सुरक्षित पहुँच (`?.`), lazy विकल्प (`??`) और flow diagnostics वाला संकलक देता है। कर्नेल को फिर शुरू करके पहले कक्ष की शुरुआत में `// bhash:null-safety=strict` लिखें। [हिन्दी रिक्त-सुरक्षा ट्यूटोरियल](https://github.com/bhashitva/bhash/blob/jupyter-0.1.12/tutorial/null-safety/README.md) पढ़ें।

Install or upgrade in the Python environment where you run Jupyter. A full JDK 17+ is required; Maven is not required.

```sh
python -m pip install --upgrade "bhash-jupyter[lab] @ https://github.com/bhashitva/bhash/releases/download/jupyter-0.1.12/bhash_jupyter-0.1.12-py3-none-any.whl"
python -m bhash_jupyter.install
python -m pip show bhash-jupyter
```

Re-register any external database drivers with `--mysql-driver` or `--sqlserver-driver` as before. Restart the notebook kernel and select **Bhash (भाष)**. Download `hindi-starter.ipynb` directly from the release assets. To copy it with the installer, pass `--notebook Hindi-Starter.ipynb` together with the same `--mysql-driver`, `--sqlserver-driver` and `--java` options used when registering the kernel; the installer replaces the kernelspec on every invocation. Use a new destination filename. Use `--java` for a JDK outside PATH.
