# Asynchronous task core

The [approved design](async-await-design.md) is saved for future reference.
This milestone implements language integration, scheduling and hosting.
HTTP/database transports remain synchronous; async adapters follow separately.

## Compiler contract

`असमकालिक फलन f(...) -> T` has callable result `कार्यफल<T>`; `प्रतीक्षा`
yields T. `कार्यफल<शून्य>` uses a compiler-owned unit type. Functions, methods,
generic class specializations and closures share these rules. An async
`मुख्य()->शून्य` is the application root. Synchronous helpers can create tasks
while an async root is running; there is no public blocking wait API.

`AsyncSignatures` normalizes effective signatures before package, generic and
closure resolution. `AsyncSourceLowering` consumes the checked tree with binding
identities and full types, emits ordinary Bhash heap-frame classes and resume
helpers, then checks them again before JVM/CLR source or native Core generation.
All targets share this suspension implementation. Generated states retain source
tokens for diagnostics. Operands, receivers and indices evaluate once, left to
right; short circuit behavior is preserved. Return/throw/break/continue run
finally/resource cleanup even when cleanup suspends. Cleanup failure replaces
pending outcomes, following existing language semantics.

## Scheduler, cancellation and lifetimes

`stdlib/tasks.bhash` implements queued starts and always-yielding await. One
cooperative dispatcher mutates the graph; no Bhash code runs on foreign workers.
Each invocation owns its children. Resource scopes establish nested owners:
children drain before close, failure requests cancellation, and normal joins
propagate the earliest registered unobserved child failure after draining.
Successful and observed completed children are removed from the owner list.
Self, ancestor and dependency-cycle awaits are rejected.

`मानक.कार्य` exposes `विलंब(ms)`, `विलंब(ms,token)`, `छोड़ो()` and
`वर्तमानचिह्न()`. Cancellation sources support independent tokens, parent-token
linking, or monotonic deadlines. Delay bounds are 0..2147483647 milliseconds.
`साथप्रतीक्षा(token)` cancels a wait without cancelling its producer. An already
terminal producer wins when the wrapper is polled. Cancellation is cooperative.
Disposing a source stops its future deadline, without clearing existing requests.

Resource cleanup is shielded from implicit runtime cancellation; explicitly
passing a cancelled token still cancels an operation. Capacity is 1024 live tasks
plus 32 reserved cleanup slots; exhaustion is catchable. Timers and cancellable
waits are polled between FIFO turns. Timer precision is best-effort.

Internal `आंतरिक…` fields and scheduler methods are unsupported implementation
details. Explicit task-group and completion-source convenience APIs are not
exposed in this milestone; invocation/resource scopes provide structured owners.

## GC and hosts

Suspension leaves the host stack entirely. Typed heap-frame fields use existing
precise native object layouts. Captures, locals, results, pending exceptions and
cleanup operands remain traced. Conservative state reachability clears references
after their last possible use, including exceptional paths; terminal frames clear
spill slots. Results/faults remain alive while task handles are reachable.
Completed waiter registrations and timer polling entries are removed.

Desktop roots drive bounded turns, sleeping briefly between them. Browser hosts
export promise-returning `Run` and `Cancel`. CLR `Task.Delay` yields to JavaScript
between batches of at most 64 ready tasks. Each run retains a fresh worker and
prepared hosts remain single-use. Stop requests cancellation and allows cleanup
output, with forced termination after a one-second grace period. Await-free
loops and blocking synchronous operations cannot stop cooperatively; forced
termination cannot guarantee cleanup.

Native ABI 16 adds monotonic-clock and driver-sleep operations. Runtime hashes
invalidate prepared browser hosts and native caches. Existing LLVM SDKs remain
usable: the compiler bundles updated runtime sources, with no new system library.

## Diagnostics and tests

BH8001: await outside async; BH8002: non-task operand; BH8003: ignored task-valued
statement; BH8004: invalid async resource context/signature. Native traces retain
mapped locations; reconstructed logical stacks across suspended callers remain
future work.

`AsyncFixtures` runs queued starts, timers, operand ordering, short circuit,
collection iteration, generic methods, nested tasks, closures, failure identity,
deadlines, wait cancellation, resource order and suspending cleanup on JVM, CLR
and native. Forced-GC tests check zero terminal live objects and bounded growth
for repeated cyclic allocations. `TaskCoreFixtures` independently checks protocol
invariants. Browser smoke tests exercise standalone/prebuilt hosts, cleanup,
restart and isolation. See the [Hindi tutorial](../tutorial/async/README.md).
