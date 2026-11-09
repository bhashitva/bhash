# Android-first mobile development with Bhash

**Status: approved for the first implementation.** The [Hindi offline task list](../examples/android-offline-task-list/README.md) includes build and test instructions. The C sketch below describes the design. The generated headers define the implemented ABI.

In ABI v1, application functions are selected by a typed export manifest. Exported values are signed 64-bit integers, one-byte booleans, UTF-8 views or owned buffers, and opaque handles to concrete Bhash classes and records. Each UTF-8 argument or result is limited to 8 MiB. The sample represents filters as integers, invalid titles as an ID of zero, and missing tasks as a false result. Database failures cross the C boundary as Bhash exceptions. The sample closes its SQLite store and releases its handle in two calls from the JNI adapter. Schema version 1 is applied transactionally using SQLite's `user_version`.

Builds include the C headers, an ABI manifest and detached debug symbols. The C fixture uses `native library` on Linux x86-64. Android uses `compile --target android` with NDK r28 or later. Both emit an ELF shared library. The runtime supports one owning thread and terminal shutdown. Callers initialize output buffers to empty, free returned bytes with `bhash_bytes_free` before reuse, and explicitly release object handles. Recoverable Bhash exceptions are returned as statuses. Exhausting the managed heap still follows the existing runtime's fatal allocation behavior.

## 1. Decisions for review

| Decision | Proposed choice | Implication |
| --- | --- | --- |
| App architecture | Kotlin and Jetpack Compose host, Bhash domain logic and SQLite | Android owns screens and lifecycle. The task rules and data layer can move to an iOS host. |
| Compiler output | Explicit Android shared-library output with generated C headers | A host calls named Bhash exports. Existing desktop executables retain their entry points. |
| Export declarations | A small, typed export manifest alongside `bhash.project` | A developer can export ordinary Bhash functions without new language syntax. |
| Runtime ownership | One embedded runtime per process, on one dedicated worker | Reuses the single-mutator collector and serializes calls. Independent runtimes and parallel calls can be designed later. |
| Cross-language data | Fixed-width scalars, length-delimited UTF-8, and opaque handles | Kotlin and Swift can copy values or retain handles. The collector remains free to move objects in a future implementation. |
| Cancellation | Remove queued reads, discard stale results, and let started writes reach a transaction outcome | Rotation and navigation are safe. Cancelling a UI wait does not reverse a committed change. |
| First platform matrix | Android ARM64 phone, x86-64 emulator, proposed minimum API 29 | The two builds share an ABI contract and use the Android NDK. Final minimum support depends on device tests and symbol checks. |

The compiler runs on the development machine or CI and continues to require Java. The Android UI runs on ART. Compiled Bhash code runs inside the app with its own native runtime and bundled SQLite. The first milestone covers Android, with iOS used as a constraint on the shared interface. Device APIs, cloud sync, a Bhash UI toolkit, and store submission belong to later milestones.

## 2. Compiler and Android toolchain

Today [`NativeCompiler.java`](../src/main/java/dev/bhash/backend/llvm/NativeCompiler.java) selects a desktop link path and [`LlvmEmitter.java`](../src/main/java/dev/bhash/backend/llvm/LlvmEmitter.java) emits a C-style `main` that configures the runtime, runs the Bhash entry point, and shuts down. [`NativePlatform.java`](../src/main/java/dev/bhash/backend/llvm/NativePlatform.java) models the current desktop hosts. Keep that behavior for executable builds. Extract the common initialization and lowering used by both output kinds. Library output takes its entry roots from the declared exports and accepts a project without `मुख्य`. It has no CLI argument or license-printing entry point. It must still ship the applicable notices.

Proposed build interface, with names open for review:

```text
bhash compile bhash.project --target android --abi arm64-v8a \
  --exports bhash.exports --android-api 29 -o build/android/arm64-v8a
```

Here `bhash` stands for the existing compiler CLI invocation. A library build emits a PIC shared object, the C header, an ABI manifest, notices, and separate debug symbols. The manifest records export signatures, bridge ABI version, target ABI and API level, compiler revision, NDK revision, and the pinned SQLite source hash. Generate wrappers from a checked export list with stable ASCII symbol names. Diagnose ambiguous overloads, unsupported types, and name collisions during compilation. Keep generated function names, GC internals, and SQLite symbols out of the public export table.

Separate the **build host** from the **output target**. Use the NDK’s compiler, linker, and Bionic sysroot for the selected Android ABI and API level. Check LLVM IR compatibility with the pinned NDK toolchain. Use the Android ABI's target data layout and audit lowering assumptions for alignment, pointer widths, and calls. The current runtime uses `timespec_get(TIME_UTC)`, available in Bionic from API 29. Confirm the full required symbol set before committing to that minimum. Select a pinned NDK revision in the r28-or-later series and verify 16 KB alignment in both the ELF segments and packaged APK. [Android ABIs](https://developer.android.com/ndk/guides/abis) · [Bionic time API](https://android.googlesource.com/platform/bionic/+/main/libc/include/time.h) · [16 KB Android builds](https://developer.android.com/guide/practices/page-sizes)

Use a pinned Gradle wrapper to build the Compose host and its thin JNI adapter. Gradle packages the matching Bhash library for each ABI. Build the pinned SQLite amalgamation for those same ABIs. The initial capability profile includes portable computation and SQLite. Reject reachable desktop-only operations, including console input, process execution, and desktop networking or database drivers, with a source diagnostic. Audit ELF dependencies and unresolved symbols to catch host libraries pulled in by mistake.

## 3. Shared C interface

The **embedding interface** handles runtime lifecycle, error reporting, and buffer ownership. The **generated application interface** exposes typed operations. Kotlin calls it through JNI. Swift can import the same header when an iOS target exists. The following sketch shows the shared rules and the task-list surface. It does not expose Bhash object layouts.

```c
#include <stddef.h>
#include <stdint.h>

typedef int32_t bhash_status;
typedef uint64_t bhash_handle;
typedef struct { const uint8_t *data; size_t len; } bhash_utf8_view;
typedef struct { uint8_t *data; size_t len; } bhash_bytes;
typedef struct { bhash_bytes message; } bhash_error;

uint32_t bhash_embed_abi_version(void);
bhash_status bhash_runtime_start(bhash_error *error);
bhash_status bhash_runtime_stop(bhash_error *error);
void bhash_bytes_free(bhash_bytes *value);

bhash_status bhash_tasks_open(bhash_utf8_view app_files_dir,
                             bhash_handle *store, bhash_error *error);
bhash_status bhash_tasks_add(bhash_handle store, bhash_utf8_view title,
                            int64_t *id, bhash_error *error);
bhash_status bhash_tasks_set_done(bhash_handle store, int64_t id,
                                 uint8_t done, bhash_error *error);
bhash_status bhash_tasks_list(bhash_handle store, uint32_t filter,
                             bhash_bytes *json, bhash_error *error);
bhash_status bhash_tasks_close(bhash_handle store, bhash_error *error);
```

- **Versioning.** Check the ABI major version before starting. Assign numeric values to status codes and filters in the generated header. Filter values are all, open, and completed. A breaking signature or ownership change increments the ABI major version.
- **Input.** A view is borrowed for the call and never retained. A null data pointer is valid only at length zero. Validate lengths, UTF-8, boolean values, and enum values before dispatch. Inputs that survive the call must be copied. Android converts between UTF-16 and standard UTF-8 with explicit lengths. JNI's Modified UTF-8 is a different encoding. [Android JNI guidance](https://developer.android.com/ndk/guides/jni-tips)
- **Output.** The caller supplies valid writable output pointers. The adapter initializes outputs to zero or empty before processing. Success fills the result. Failure leaves it empty and may supply an owned UTF-8 diagnostic. The caller copies any returned bytes and frees them through the same library with `bhash_bytes_free`, which clears the buffer struct. Freeing an empty struct is allowed. Buffer ownership is separate from GC ownership. An allocation failure can return a status without allocating a message.
- **Errors.** Define stable statuses for invalid input, missing tasks, invalid handles, runtime state or thread violations, storage failures, out of memory, and uncaught Bhash exceptions. Map expected domain failures to Hindi UI messages by status. Debug logs may carry Bhash source locations without logging task titles or database paths. Protect every Bhash call at the C boundary. Root any caught exception while copying its diagnostics and clear pending exception state before returning. Audit current fatal and console paths so expected application failures never terminate the process.
- **Handles.** Zero is reserved as invalid. A monotonic, non-reused ID resolves to a typed entry in a runtime-owned table. Reject unknown, closed, and wrong-kind handles. The table stores a strong GC root for the live Bhash object. Closing a task store closes its database and invalidates its handle, including on a reported close failure. Handles are process-local and are never persisted. Use copied JSON for a small task-list snapshot, with IDs represented as decimal strings to preserve exact 64-bit values across consumers.

## 4. GC, threads, and application lifecycle

The [current collector](native-compilation-design.md) is precise, stop-the-world, nonmoving mark and sweep. The runtime uses process-wide root, handler, and trace state. Keep one embedded runtime and one owning OS thread for v1. The Android host queues calls on a dedicated single-thread executor. The main thread submits work and observes immutable results. The adapter rejects wrong-thread and reentrant entries before touching collector state. Publish the owning-thread identity safely so this check also works during startup or shutdown. Pure ABI-version queries and freeing detached buffers do not need to enter the runtime. [Native runtime implementation](../src/main/resources/dev/bhash/backend/native/bhash_runtime.c)

1. **Start.** On the worker, configure descriptors, static roots, and the exception boundary before executing application code. Start once per process. If setup fails, report a status and keep the runtime unavailable. The app can repeatedly open and close a store while the runtime is running.
2. **Call.** Marshal and validate inputs. Root managed values before any allocation that could collect. Preserve the existing generated roots and add handle-table entries to the collector's root walk. On return, copy text and snapshots into independent buffers. Native code never retains a JNI local reference or a borrowed pointer to a Kotlin or Bhash object.
3. **Background or rotate.** A screen-level ViewModel retains the repository across configuration changes and observes it with lifecycle-aware collection. Cancel queued reads when superseded and discard obsolete read results by request generation. An in-flight operation finishes on the worker. Once a write starts, publish success only after commit. If the UI loses the response, refresh from SQLite on return. Read results are always disposable, and write requests that have not started may be explicitly cancelled before dispatch. No asynchronous thread interruption enters Bhash or SQLite. [Android ViewModel lifecycle](https://developer.android.com/topic/libraries/architecture/viewmodel) · [Android lifecycle and state collection](https://developer.android.com/guide/components/activities/activity-lifecycle)
4. **Close.** For explicit shutdown, stop accepting work, drain or cancel queued requests according to their state, let the active call finish, close stores, clear root and handle tables, and run final GC on the owning thread. Assert that no handler, cleanup, trace, or temporary root frames remain. Runtime stop is terminal for that process in v1. Android can kill a background process without calling cleanup. Durability comes from committing each mutation during the request, and the next process opens the same database.

Test forced collection before and after wrapper allocation and copying, while a handle is the object's only root, and after releasing it. Test uncaught exceptions, initialization failure, stale handles, rejected thread entry, discarded reads, and the next call after each recoverable error. Verify that a failed Bhash call unwinds roots and native resources before it returns to JNI. Managed callbacks, JNI-to-Bhash reentry, and arbitrary interruption of in-flight SQLite operations are deferred.

## 5. Hindi offline task list

**कार्यसूची** opens to a list of tasks and a field labelled **नया कार्य**. **जोड़ें** saves a task. Each row exposes an accessible completion toggle. Filters are **सभी**, **बाकी**, and **पूरे**. An empty list gives a Hindi prompt for the next action. A failed write preserves the user's draft and gives a retryable error. Compose owns focus, loading state, and saved draft or filter state. Bhash owns title validation, task identity, completion rules, filtering, and ordering.

- **Data.** Use a versioned SQLite schema with a signed 64-bit ID, a title, and a completion flag constrained to 0 or 1. Trim titles and reject empty titles, control characters, and titles beyond a documented Unicode scalar limit. Preserve Hindi combining sequences, emoji, and user-entered spelling. Use deterministic ordering by ID. Prefer setting an explicit completion state over toggling in the bridge so retrying the same state is harmless.
- **Persistence.** The host passes Android's app-private files directory. Bhash chooses a fixed database filename beneath it. Use the existing Bhash SQLite and migration APIs with parameterized SQL and scoped resources. Commit inserts and completion changes before acknowledgement. Handle disk, lock, or migration errors without erasing data. Avoid blind retries of inserts if the prior outcome is unknown.
- **Offline and privacy.** Bundle UI resources and the native library in the APK. Omit network and shared-storage permissions. Exclude the demo database from Android backup and device-transfer rules for this offline-first sample. Task data stays in app storage and survives process restart. [App-specific storage](https://developer.android.com/training/data-storage/app-specific) · [Android backup rules](https://developer.android.com/identity/data/autobackup)
- **Accessibility.** Use Compose semantics for Hindi labels, readable large text, and announced state changes. Test Devanagari keyboard input and display, supplementary Unicode, rotation, dark theme, and TalkBack. Verify the installed app can be used from first launch in airplane mode.

The accompanying Hindi tutorial will follow one task through Compose, JNI, the Bhash rule, and the SQLite transaction. It will show how to build and install the debug APK, run the tests, and read a Bhash diagnostic.

## 6. Implementation and acceptance sequence

| Stage | Deliverable | Acceptance gate |
| --- | --- | --- |
| A. Desktop embedding | Small Linux C harness links a Bhash library, calls exports repeatedly, and owns returned buffers | Existing native CLI behavior passes regression tests. Forced-GC and sanitizer tests cover roots, handles, UTF-8, exceptions, and shutdown. A fresh process can start after a prior process closes. |
| B. Android build | Produce ARM64 and x86-64 libraries plus JNI smoke app | Inspect exports and ELF dependencies, verify 16 KB packaging, and install on an emulator and an ARM64 phone. Run the same boundary fixtures on Android with CheckJNI and forced GC. Validate the supported API floor. |
| C. Task list | Compose app, Bhash persistence, and Hindi tutorial | Automated UI tests add a Hindi task, mark it complete, and filter it. Tests cover invalid input, migration, process restart, and interruption before and after commit. Slow database work leaves the UI responsive. |
| D. Distribution | Rebuildable sample project, debug APK, checksums, notices, and symbol artifacts | A clean Windows or Linux build host with the documented Android SDK, NDK, and Java setup can build and install the sample. Record cold start, APK size, and add-to-visible latency on a named device as baselines. |

CI should run the desktop ABI and GC tests when embedding code changes and Android instrumentation when the mobile target or sample changes. Keep hardware smoke tests separate from per-PR emulator coverage. The acceptance report should distinguish simulator or emulator results from actual device results.

## 7. Review questions

1. **Embedding lifecycle.** Is one runtime per process with a dedicated worker and explicit, terminal shutdown an acceptable initial constraint?
2. **Public interface.** Should exports live in a typed project manifest, with copied UTF-8 values and opaque handles, before adding export syntax to the language?
3. **Offline storage.** Should Bhash own the bundled SQLite database and its migrations, with the Android host supplying the private storage path?
4. **Distribution.** Is a tested, installable debug APK and Hindi tutorial the right first release, with Play Store packaging and iOS following separately?

Approval of this design is the checkpoint before implementation begins.
