# Bhash for Jupyter

The first Bhash kernel supports stateful scalar and text bindings, named functions,
imports, statements and trailing expression results. It runs Bhash on a local JVM
worker and speaks the standard Jupyter kernel protocol.

## Install

For the prebuilt compiler and notebook, follow the [0.1.2 release instructions](https://github.com/bhashitva/bhash/releases/tag/jupyter-0.1.2). The source build below is for development.

Build the compiler with a full JDK 17 or newer and Maven. In the Python environment
that runs Jupyter, install the adapter and register its kernelspec.

```sh
mvn package -DskipTests
python -m pip install ./editors/jupyter
bhash-jupyter-install --compiler target/bhash-compiler-0.1.0-SNAPSHOT.jar
jupyter kernelspec list
```

Pass `--java /path/to/jdk/bin/java` if the JDK is outside PATH. In JupyterLab or
Jupyter Notebook, create a notebook and select **Bhash (भाष)**. In VS Code, use
the Jupyter extension's kernel picker on the host where you registered the
kernelspec. To use a terminal, run `jupyter console --kernel bhash`.

## पहली नोटबुक

[पहली-नोटबुक.ipynb](पहली-नोटबुक.ipynb) को Jupyter में खोलें और कक्षों को क्रम से चलाएँ।

पहले कक्ष में एक मान घोषित करें। इस मान को अगले कक्षों में भी उपयोग कर सकते हैं।

```bhash
मान कुल: पूर्णांक = ०;
```

दूसरे कक्ष को चलाएँ। अंतिम व्यंजक का मान आउटपुट में दिखेगा। इसे दोबारा चलाने पर
`४` दिखेगा, क्योंकि पिछली बार का मान इसी सत्र में सुरक्षित है।

```bhash
कुल = कुल + २;
कुल
```

प्रतीक्षा (Await) से समयबद्ध काम पूरा होने तक रुक सकते हैं।

```bhash
प्रतीक्षा मानक.कार्य.विलंब(२५);
लिखो("तैयार");
```

## Cell behavior

Place imports first, then named functions and type declarations, then variable
declarations, statements, and an optional final scalar expression. A type
declared with `वर्ग`, `अभिलेख`, `अनुबंध`, or `परिगणना` is available to later
cells. Rerun an identical declaration freely. Restart the kernel to edit a
previously declared type or clear the session.

Scalar bindings before the first cell-local declaration persist across cells.
Object, array, collection, function-value, and awaited declarations are
cell-local, as are any declarations that follow them in the same cell.
Compatible scalar redeclarations reinitialize a slot. Replacing a function
with the same parameter and return types affects calls in later cells.

A cell containing `मुख्य` or `संकुल` runs as a standalone, single-file Bhash
program. Include its imports and declarations in that cell. It has its own
scope, and its output appears in the notebook.

A compile error runs nothing. A runtime error preserves updates already made
to existing scalar bindings and external resources. New declarations and imports
from that cell are discarded. Earlier executable cells never replay.

The kernel recompiles declarations for each cell and copies scalar state into
a fresh class loader. Class static state starts fresh in each cell. Persistent
objects, collections, closures, and provider handles require the shared runtime
described in the design. Diagnostics currently refer to generated cell source
lines. Interrupting the worker clears the session. Completion and inspection
cover committed session names and scalar values.

The adapter runs your code with the permissions of the Jupyter user. Notebook
files can save source and output, so keep credentials out of cells and output.

Run the protocol smoke test from the repository root after installing the adapter.

```sh
python editors/jupyter/test_kernel.py target/bhash-compiler-0.1.0-SNAPSHOT.jar
```

[Design and planned stages](../../docs/jupyter-notebook-design.md)
