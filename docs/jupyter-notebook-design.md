# Bhash in Jupyter

Design proposal for review. The first milestone is a local Bhash kernel for JupyterLab, Jupyter Notebook and Jupyter Console. VS Code should use the same installed kernel through its Jupyter extension. Users should be able to enter a Bhash expression, see its value, define functions and variables, and use them in later cells without declaring a `मुख्य` function. Notebook files use the ordinary `.ipynb` format.

## What using it would look like

Select **Bhash (भाष)** as the notebook kernel and run these as separate cells. These examples describe proposed notebook syntax.

```bhash
मान कुल: पूर्णांक = ०;
```

```bhash
कुल = कुल + २;
कुल
```

The second cell displays `२`. Running it again displays `४`. An expression on the final line displays its value in the output area. A statement ending in a semicolon has no implicit result. Explicit output from `लिखो` still appears as a stream. Users can mix Bhash cells with Markdown, run cells out of order, and restart the kernel and run all cells from the beginning. Saved notebooks retain source and recorded outputs. Live objects, handles and task state exist only in the running kernel.

## Architecture

Start with a thin Python adapter built on the Jupyter kernel machinery and a persistent JVM worker that owns the Bhash session. Python handles Jupyter messaging only. All parsing, type checking and execution of user cells occurs in the Bhash worker. Install the adapter into the Jupyter environment and use a full JDK 17 or newer on the machine hosting the kernel. A Python-free Java kernel could later reuse the worker protocol if distribution warrants the extra transport work.

The adapter launches one worker per kernel over a private, versioned, length-framed UTF-8 protocol. Frame types cover requests, stream output, final values, diagnostics, input prompts and lifecycle events. Reserve the protocol stream for frames and route program output through runtime hooks. Use an independent control path for cancellation and keep the adapter responsive while execution is busy. A terminal command such as proposed `bhash repl` can use the same session API with a terminal renderer. See the [Jupyter kernel author guide](https://jupyter-client.readthedocs.io/en/stable/kernels.html).

The current JVM path emits a complete `BhashProgram`, compiles runtime and standard library sources with `javac`, then packages an executable JAR. Reuse the parser, semantic checks, lowering and Java compiler, and add a separate interactive emission mode. Repeatedly launching JARs loses in-memory state. Replaying earlier cells would repeat writes, HTTP requests, initialization and other side effects. See [JvmCompiler.java](../src/main/java/dev/bhash/backend/jvm/JvmCompiler.java) and [ManagedEmitter.java](../src/main/java/dev/bhash/backend/ManagedEmitter.java).

### The interactive compiler

1. Add a cell grammar entry point for imports, declarations, statements and an optional trailing expression. Give cells synthetic source names and keep their original spans for errors, completions and stack traces. Use the parser to distinguish incomplete input from invalid input in the console.
2. Maintain a typed symbol table, committed imports and runtime binding slots in each session. Lower reads and assignments to the same stable slots across cells. Check a cell against a snapshot of the committed symbol table and reject malformed cells before execution. Keep compile-time analysis separate from evaluation.
3. Build a shared interactive JVM runtime once per worker. Emit each cell as a uniquely named Java unit and compile it against the shared runtime and previously committed declarations. Prototype a session class loader and bytecode store before committing to this approach. The current generated program has a single enclosing class, so cross-cell references need stable names and accessible entry points. All cells must share the same runtime type identities. Loading independently packaged runtimes would break references such as lists, exceptions and closures.
4. Start the prototype with primitive and text bindings, expressions, assignments and named functions. Then prove cross-cell use of collections, user types, closures, standard-library providers and async tasks. Track generated code and metadata retained for a session. Limit accumulation and document restart as the way to reclaim the session class loader in the first release.

### State, reruns and failures

- Execute cells serially within a kernel, in submitted order. A new cell sees earlier successful declarations. Re-executing a compatible variable declaration reinitializes the existing slot. Reject type or mutability changes with a restart diagnostic. Allow same-signature function replacement through stable dispatch slots, so later calls from already compiled functions use the new definition. Defer user type redefinition to restart.
- Stage new declarations and imports until execution succeeds. On a runtime error, discard newly introduced bindings while keeping effects on existing variables, objects and external systems. For the first release, forbid function or type declarations that can escape from a cell before their definitions are committed. Test declaration initialization and closure escape explicitly before extending this rule. A syntax or type error executes nothing.
- A cell with top-level `प्रतीक्षा` runs inside an implicit task scope using Bhash's scheduler. Join its children and await scoped cleanup before reporting completion. Keep tasks scoped to one cell initially. Persistent connections or other handles held in notebook variables belong to the session and need explicit disposal or best-effort shutdown cleanup.
- Interrupt an active cell through the task cancellation path and compiler-inserted checks at notebook loop back edges and function entries. Probe CPU loops and blocking provider calls separately. If a cell ignores cancellation beyond the grace period, terminate its worker and tell the frontend that the session was lost. Restart creates a fresh session. Process termination cannot guarantee user cleanup or undo external effects.

## Jupyter integration and installation

Register a user-scoped kernelspec with `display_name` **Bhash (भाष)**, `language` `bhash`, and an `argv` array that invokes the installed adapter with `{connection_file}`. Report Bhash and its `.bhash` file extension in kernel metadata. Validate kernelspec discovery and execution in JupyterLab, Notebook, Console and VS Code. In remote VS Code or Jupyter Server, install the kernel on the execution host. Bundle a version-matched compiler or require an explicitly selected compatible installation and fail clearly on a worker protocol mismatch. See the [VS Code kernel picker documentation](https://code.visualstudio.com/docs/datascience/jupyter-kernel-management).

Implement `kernel_info`, `execute`, `is_complete`, interrupt and shutdown first. Route busy and idle status, execution counts, streams, expression results and diagnostics to the originating request using Jupyter message semantics. Respect silent execution, history and error handling flags. Render results initially as bounded `text/plain`. Support `पाठपढ़ो()` and `पढ़ो()` through Jupyter input requests when the frontend permits stdin, and return a Bhash diagnostic otherwise. Keep heartbeat and control responsive independently of user code. See the [Jupyter messaging specification](https://jupyter-client.readthedocs.io/en/stable/messaging.html).

Add completion and inspection using the Bhash analyzer with the committed session symbols and the current cell. Use UTF-8 for transport and explicitly convert cursor positions between Jupyter's character offsets and the analyzer's UTF-16 offsets. Test Hindi identifiers, combining marks and emoji before the cursor. Later, introduce explicit display APIs for structured MIME output such as images and tables. Native debugging, full IPython magic compatibility and cross-language kernel state can each be considered separately.

Local execution has the privileges of the Jupyter user. Keep kernel connection credentials out of logs and preserve Jupyter's message authentication. Launch via argument arrays, enforce message and output limits, and keep sessions isolated by process. Use existing Bhash dependency locks, with dependency restoration performed as a separate explicit action.

## Delivery and acceptance

| Stage | Deliverable | Acceptance |
| --- | --- | --- |
| 1 | Session engine and terminal prototype | A later cell reads and mutates earlier state. A function defined in one cell runs in another. Side effects occur only for the submitted cell. A rejected cell leaves the prior session usable. |
| 2 | Installable kernel with text output | A clean install opens a Hindi notebook and runs it in JupyterLab, Notebook, Console and VS Code. Test stream ordering, execution counts, final values, errors, input, interrupt and restart with `jupyter_client` and frontend smoke tests. |
| 3 | Broader language and editor support | Verify cross-cell identity for objects and closures, timers and cancellation, completion, inspection, and shared runtime providers. Run on Linux, Windows and macOS. Ship a Hindi getting-started notebook that passes Restart and Run All. |

Track cold startup, warm-cell latency and memory growth over repeated execution. Record baseline results before setting release budgets. Include tests that restart during a pending input, interrupt an awaited timer, terminate a non-cooperative worker, and verify no child workers survive shutdown.

## Future playground exploration

The playground already runs compiled browser WebAssembly programs through a browser host. Explore a notebook editor that uses the same cell semantics and message shape over a persistent browser worker. That requires proving how the WASM runtime can load additional cells while keeping one shared session alive. The JVM class-loading design does not settle that question. Evaluate browser memory and idle limits, per-session isolation, cancellation and the existing browser I/O restrictions before any public deployment. Jupyter server hosting would be a separate architecture with its own authentication, resource quotas and isolation requirements.
