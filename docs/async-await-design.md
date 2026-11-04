# Async/await, tasks and asynchronous HTTP/database operations

**Status: approved design.**

The task-core implementation and its current boundaries are recorded in
[task-core-implementation.md](task-core-implementation.md). The proposals below
also cover later HTTP/database milestones; they are retained as the approved design.

Date: 3 November 2026. Baseline: function values and closures from PR #63, distributed in VS Code 0.16.0 (PR #64). Prepared against the local release source snapshot. The user approved this design before implementation. The syntax and APIs below describe the intended contract, not features available in the current release. The task-core milestone comes first; asynchronous HTTP and database adapters follow separately.

## 1. Decisions for review

असमकालिक निष्पादन (Asynchronous execution) में नेटवर्क या डेटाबेस के उत्तर की प्रतीक्षा करते समय दूसरे काम आगे बढ़ सकते हैं। प्रारम्भ में Bhash का अपना कोड एक निष्पादन थ्रेड पर चलेगा; बाहरी I/O और सीमित सहायक थ्रेड प्रतीक्षा वाला काम करेंगे।

कार्यफल (Task) भविष्य में मिलने वाला परिणाम, विफलता या निरस्तीकरण दर्शाएगा। प्रतीक्षा (Await) वर्तमान फलन को निलम्बित करके दूसरे तैयार काम को अवसर देगी। निरस्तीकरण (Cancellation) रुकने का अनुरोध है; पहले हो चुका HTTP अनुरोध या डेटाबेस परिवर्तन अपने आप वापस नहीं होता।

| Decision | Recommendation | Implication |
| --- | --- | --- |
| Language | Typed tasks and shared compiler-generated state machines | One semantic contract across targets |
| Scheduling | One cooperative Bhash mutator per application | Existing heap/captures stay single-threaded; CPU-heavy code can still stall other work |
| Start/await | Hot tasks with queued bodies; await always yields | Predictable ordering and reentrancy, with some scheduling overhead |
| Lifetimes | Structured ownership of child operations | Scope exit joins work before releasing resources; no detached tasks initially |
| Cancellation | Explicit tokens, monotonic deadlines, cooperative acknowledgement | No thread abort or instant-cancellation guarantee |
| Native GC | Keep precise stop-the-world nonmoving mark/sweep | Trace suspended heap frames; never expose heap pointers to workers |
| HTTP | Async desktop transports; separate browser Fetch profile | Browser restrictions remain visible, not hidden behind false parity |
| Database | Separate async resource types, one operation per connection | Bounded workers for blocking drivers, nonblocking libpq where supported |
| Compatibility | Retain synchronous APIs | Existing code remains valid; blocking calls do not become async automatically |

Native executables remain independent of JVM and .NET. Java remains a compiler dependency. General shared-memory threads, parallel execution of Bhash callbacks, async iterators, streaming HTTP, connection pools, ORM, distributed transactions and automatic retries are outside this initial design.

## 2. Existing contracts

- [Closures](function-values.md) already use generated classes/interfaces and shared mutable capture cells. Their binding semantics must remain unchanged.
- [HTTP](networking.md) is synchronous, buffered and policy-controlled in shared Bhash code. Browser synchronous network calls are rejected.
- [Database providers](database-providers.md) provide typed parameters, conversions and transactions. GC does not close database handles. PostgreSQL uses JDBC on JVM and libpq on CLR/native.
- [Resource scopes](application-foundations.md) lower to try/finally. Cleanup failure replaces a pending body exception or return. Async cleanup must retain that precedence.
- [Web applications](web-applications.md) process one connection/handler at a time. [Authentication](authentication.md) has synchronous password hashing and shared session state; both require an audit before concurrent handlers.
- Native precise roots currently describe active stack slots, static references and object fields. Suspension cannot retain native stack frames or exception-handler addresses.

Historical native milestone documents retain some original subset limitations; current code/tests are authoritative for supported features.

## 3. Proposed syntax and types

Proposed names: `असमकालिक` (async), `प्रतीक्षा` (await), `कार्यफल<T>` (Task<T>), `निरस्तीकरणचिह्न` (CancellationToken), `निरस्तीकरणस्रोत` (CancellationSource), `कार्यसमूह` (TaskGroup). Names require review. No English keyword aliases are proposed.

```bhash
// Proposed syntax; this does not compile with the current release.
असमकालिक फलन दोगुना(अंक: पूर्णांक) -> पूर्णांक {
    प्रतीक्षा मानक.कार्य.विलंब(१);
    लौटाओ अंक * २;
}

असमकालिक फलन मुख्य() -> शून्य {
    स्थिर पहला: कार्यफल<पूर्णांक> = दोगुना(२१);
    लिखो(प्रतीक्षा पहला); // ४२
}
```

The declaration's return type is the body result T. Calling an async function declared `-> T` produces `कार्यफल<T>`; `लौटाओ` returns T, not a task. No implicit nested-task flattening. `कार्यफल<शून्य>` is a compiler-supported unit specialization, not permission to store `शून्य` arbitrarily.

A corresponding function value has type `फलन(पूर्णांक) -> कार्यफल<पूर्णांक>`. Async lambdas use `असमकालिक फलन(...) -> T { ... }`. Existing invariant signature, capture, generic specialization and lexical access-control rules apply. Async-ness is a body/declaration property; callers see a task-returning callable. Overrides/interfaces must match that effective signature; async cannot distinguish otherwise identical overloads.

`प्रतीक्षा expression` evaluates its task operand once, then yields T or raises its stored failure/cancellation. Null tasks produce the ordinary null-reference failure. It has unary precedence: `प्रतीक्षा अ + ब` means `(प्रतीक्षा अ) + ब`; parenthesize compound task expressions.

Await is permitted in async function/lambda bodies, including catch/finally and loops. Reject it in constructors, field/static initializers and synchronous functions. Return from finally remains forbidden. There is exactly one entry point, either synchronous or async `मुख्य()->शून्य`. Desktop launchers drive the scheduler through root completion and cleanup; browser hosting awaits the entry task without blocking JavaScript.

No public blocking `.Result`/`.Wait`, nested event-loop pump or general sync-to-async bridge initially. Add Hindi diagnostics for invalid await operands/contexts, mismatched task results, ignored task expressions and invalid async disposal. Allocate codes through the existing catalog during implementation.

## 4. Task and scheduling semantics

### States and ordering

A task is pending, succeeded(T), failed(exception), or cancelled(reason). Terminal transition occurs exactly once on the Bhash scheduler. Repeated and multiple awaits are permitted; reference results/exceptions retain identity.

Calling an async function evaluates arguments, constructs/registers its task, and queues its body. The body does not run inline. Argument-expression errors are synchronous; body exceptions become task failures. Async I/O validation failures become failed tasks after argument evaluation. Fatal allocation/runtime failures retain existing exclusions.

Await always yields, even for an already completed task. Continuations enter a FIFO ready queue; independent external events have no promised global order. Never run Bhash continuations inside foreign callbacks. Process a bounded ready batch, then service I/O/timers to avoid starvation.

An await-free CPU loop is not preempted. Provide `मानक.कार्य.छोड़ो()` (yield) and explicit token checks. Do not inject cancellation at arbitrary instructions. Synchronous library calls inside async bodies still block the Bhash dispatcher.

### Structured ownership

Every task belongs to its current lifetime owner. An async invocation owns its children. An async resource scope and explicit task group establish nested owners. Normal exit joins children; exceptional/cancelled exit requests cancellation and joins them. No task is detached by dropping its handle.

A task handle may be stored/passed/returned, but does not transfer operation ownership. Returning a child handle therefore returns a settled task after the owner drains. Transfer/detach APIs are deferred. Resource scopes drain their children before closing resources, including existing synchronous `संसाधन` inside async bodies.

Await marks an outcome observed. At normal implicit join, an unobserved child failure fails the owner; choose the earliest registered failed child and cancel/drain unfinished siblings. An existing owner failure/cancellation remains primary unless cleanup replaces it under existing finally rules. Secondary outcomes go to bounded diagnostics, not an automatic public aggregate exception. Child failures are not injected into a parent's running instructions.

Do not claim complete lifetime analysis: a child created outside a resource scope can still receive an escaping resource reference. Diagnose obvious escapes and retain runtime closed/busy checks. Tutorials must keep resource use and awaited operations within their owning scope.

Ignoring a task-valued expression is a diagnostic error. Groups accept callbacks returning typed tasks and own their execution; callbacks never run as Bhash code on foreign workers. No fire-and-forget API initially.

### Initial API and capacity

Provide completed/failed task construction, delay, yield, cancellation sources/tokens, linking, explicit cancellation check and task groups. Promise resolvers remain runtime-internal until callback interoperability and ownership are proven; a separate public `then` API is unnecessary initially.

Proposed configurable desktop defaults: 1,024 live tasks; 128 active HTTP requests; 4 blocking database workers; 64 queued database operations; 64 MiB aggregate foreign I/O buffers. Existing per-request/registry limits also apply, with the stricter limit winning. Reserve capacity before dispatch/copy; release on all outcomes. Exhaustion is a catchable capacity error, not an unbounded queue. Reserve separate control capacity for cancellation/cleanup so saturation cannot prevent shutdown. These are starting proposals, not measured tuning results.

## 5. Cancellation, deadlines and exceptions

Cancellation sources signal read-only tokens. Linked tokens combine parent requests and the earliest monotonic deadline. Disposing a source removes registrations, not silently cancelling unrelated work. User async functions accept tokens explicitly; structured shutdown additionally signals the runtime-owned operation cancellation channel.

Await does not cancel a shared task merely because one observer stops waiting. An explicit wait-with-token operation cancels only that observer's subscription; the underlying operation remains owned and must settle. Passing a request token through I/O is preferable to abandoning waits.

Cancellation before dispatch prevents dispatch. After dispatch it requests provider cancellation and can race with successful remote execution. The dispatcher arbitrates local terminal outcomes: an already published outcome wins. Once cancellation/timeout is accepted for an unsettled operation, discard later provider success, reclaim/drain its resources, then publish cancellation/timeout. This does not establish that remote side effects were undone.

Use distinct proposed exception subtypes: `निरस्तअपवाद` for cancellation and `समयसीमाअपवाद` for timeout (a failure). Both remain catchable as `अपवाद`. A task is cancelled only for a recognized token/runtime cancellation outcome, not an arbitrary exception with similar text. Catching cancellation and returning normally succeeds once owned work and cleanup settle.

Deadlines start at API invocation and include admission/queue delay, connection establishment, body transfer and redirects. Provider statement/lock timeouts remain additional defenses. No hard real-time settlement guarantee: blocking drivers or cleanup can delay cancellation acknowledgement. A wait-only timeout can return sooner but must keep the operation owned and its handle unavailable until safely settled.

Await rethrows the original exception object. Keep a task-owned logical stack of Bhash function names and suspension source locations; do not mutate a shared exception's stored stack per awaiter. Reports distinguish original throw and await boundaries. Bound diagnostic history and avoid logging SQL, credentials or request bodies.

## 6. Compiler lowering and GC safety

### Suspended execution

Use a shared typed state-machine representation after semantic resolution/generic specialization, coordinated with closure lowering. Capture decisions must precede selection of spilled fields. Determine exact pass placement in an implementation spike; suspension should not be implemented by textual substitution alone.

A frame stores its state tag, live locals/temporaries, captures, pending result/exception, handler/finally state, awaited task and ownership/cancellation links. Await roots/registers its operand, saves state, then returns to the scheduler. Resume establishes fresh stack roots and exception frames before dispatching saved state. Never save a native stack address, `setjmp` context or borrowed argument address across suspension.

Preserve expression order, short-circuiting, loop capture identities, catch order, virtual/interface dispatch and source mapping. Finally can suspend; its pending outcome must remain rooted. Clear dead spills and continuation links promptly. Debuggers should present Bhash frames/locals rather than generated state-machine internals.

### Precise roots and foreign ownership

Keep precise stop-the-world nonmoving mark/sweep and one Bhash mutator. Workers are not heap mutators. Root categories include ready tasks, owner registries, operation registrations, timers/cancellation subscriptions, suspended frames and active resume frames, in addition to ordinary roots. Task results, exceptions, cells and continuation edges are descriptor-traced fields; reference stores retain the existing barrier boundary.

Publish an operation's task root before exposing it to foreign code. On completion, construct/root the result on the dispatcher, store it in the task, queue rooted continuations, then remove the operation registration. On suspension publish the rooted heap frame before unwinding stack roots. On resumption reload references after allocation-capable calls. Root slots remain updateable for future relocation.

Foreign threads/OS callbacks carry only generation-tagged operation IDs, provider handles, atomic scalars and foreign-owned byte/scalar buffers. They never retain, read, allocate or mutate Bhash heap objects. Completion queues use foreign allocations; the dispatcher resolves IDs to rooted registrations. Ownership of payloads transfers exactly once; stale/duplicate/late completions free their payload safely. Generation tags prevent a callback matching a reused slot.

Cancellation cannot free callback context until final callback/worker acknowledgement. Publish completions with proper synchronization. Foreign bytes count against explicit budgets even though GC cannot see them. Copy managed request/parameter bytes before dispatch: no pinning or permanent-address promise. This keeps a path open to moving/generational GC without making that collector change now.

### Resource cleanup

Propose `असमकालिक संसाधन (...) { ... }`, permitted only in async bodies, with an async-close method returning `कार्यफल<शून्य>`. Async database resources use this contract; a synchronous close must not secretly block on worker completion. Existing `बंदनीय` remains unchanged.

Drain scope children before closing resources, once, in reverse acquisition order. Failed acquisition does not trigger disposal. Cleanup uses an internal cancellation shield and separate shutdown policy, so a cancelled user token cannot skip rollback/close. Cleanup failure replaces the pending outcome, matching current finally semantics. Repeated close observes the same close operation.

A close deadline invalidates the connection and initiates safe disposal, but does not free memory a worker still accesses. Quarantine foreign state until acknowledgement. A supervisor may ultimately terminate a stuck process; force-kill, OOM and fatal runtime failures cannot guarantee cleanup. GC never commits, rolls back or closes application resources.

## 7. Cross-target adapters and distribution

| Target | Dispatcher | HTTP | Database |
| --- | --- | --- | --- |
| JVM Java 17+ | One Bhash dispatcher; callbacks enqueue | JDK HttpClient asynchronous completion | Pinned SQLite/PostgreSQL JDBC on bounded workers |
| Desktop CLR | One dispatcher, no inherited arbitrary synchronization context | HttpClient async APIs | SQLite workers; libpq nonblocking adapter |
| Native Linux x64 / macOS ARM64 | Single mutator/event loop plus foreign workers | libcurl multi | SQLite workers; libpq nonblocking adapter |
| Native Windows x64 | Single mutator and completion queue | WinHTTP async handles | SQLite workers; libpq nonblocking adapter |
| Browser CLR/WASM | Cooperative dispatcher integrated with JS turns | Separate Fetch profile | Direct database access unsupported |

Platform Task/CompletableFuture semantics must not leak into Bhash ordering/cancellation behavior. Native threads require OS primitives and runtime ABI changes, not JVM/.NET. Verify dynamically loaded symbols against supported versions; preserve libpq 14+ and existing libcurl baselines where feasible. Do not silently call newer cancellation APIs.

Update runtime fingerprints, link guards, manifests and SDK compatibility checks. Validate native execution on runtime-only Windows x64, Linux glibc 2.35+ and macOS ARM64 hosts without Java/.NET/LLVM/C development tools. Browser hosting must keep the root task alive and deliver Stop through cooperative cancellation followed by worker/frame termination if unresponsive, without weakening isolation.

## 8. Asynchronous HTTP

### Desktop

Propose `एचटीटीपी.भेजोअसमकालिक(request, token) -> कार्यफल<एचटीटीपीउत्तर>` and GET convenience APIs. Snapshot URL, method, headers, body and policies before returning the task; later mutation cannot change an in-flight request. Snapshot memory counts against per-request and aggregate limits.

Reuse shared policy as suspendable code: verified TLS, no implicit cookie jar/proxy inheritance, redirect credential stripping, no HTTPS downgrade, duplicate headers, strict UTF-8 and fully buffered owned responses. HTTP 4xx/5xx remain response values. No retries. One admission slot/deadline spans the complete redirect chain. Cancellation closes/removes transport work and drains callbacks before releasing ownership.

Keep synchronous APIs synchronous, without nested event-loop pumping. Validate mutation-after-call, cancellation during connection/TLS/body/redirect, size limits, late completions and successful reuse after errors.

### Browser

Use a separately named browser HTTP profile/API. Fetch imposes CORS, forbidden headers, browser TLS/proxy behavior and response-header visibility restrictions. Opaque redirects prevent reproducing desktop manual redirect policy. Initially reject redirects, omit credentials, and require explicit host-approved destinations. Reject unsupported caller options and document browser-controlled headers. Enforce a body limit while reading, not after unlimited buffering. AbortController cannot undo remote effects.

The playground network sandbox stays unchanged. Enabling fetch there requires a separate reviewed deployment change covering CSP connect-src, runtime origin, allowed destinations, CORS tests and abuse limits. Browser database use goes through an authenticated HTTP backend; never embed PostgreSQL credentials in WASM. A browser HTTP test host can validate capabilities without granting public playground egress.

## 9. Asynchronous databases

### API and isolation

Introduce async connection/statement/transaction types over the common provider foundation. Await open, prepare, execute, result advance/batch retrieval, reset, commit, rollback and close. Binding parameters and inspecting an already buffered row can remain synchronous. Preserve parameterization, SQL lexical restrictions, numeric/text/blob conversions and redacted SQLSTATE errors.

Allow one in-flight operation per connection across all its statements. Reject concurrent use with a stable busy error instead of silently interleaving unrelated transactions. A transaction holds a logical lease tied to its owner scope, not an OS thread. Other tasks cannot use that connection during the lease. Do not expose synchronous and asynchronous wrappers over the same live handle.

Copy SQL/parameters before dispatch; only the dispatcher converts foreign results into rooted Bhash objects. Enforce bounds before allocating large results. Proposed initial result limit: 1,024 rows / 8 MiB per query result; applications paginate. Streaming cursors are deferred. libpq adapters must enforce this during result consumption, for example with single-row mode; buffering an unlimited PGresult and checking afterward does not satisfy the limit. JDBC must likewise bound provider buffering or disclose/reject the unsupported path.

### Provider operations and cancellation

SQLite runs on bounded workers with serialized connection access. Progress/interrupt cancellation protects the connection lifetime so it cannot target a closed/reused handle. Reconcile automatic provider rollback and mark affected transactions rollback-only. Queue cancellation prevents undispatched driver work.

JDBC remains blocking behind the task API. Use bounded workers, driver timeouts and a separate bounded cancellation/control path. Never kill a worker thread. The connection stays unavailable until both operation and cancellation control work settle. Preserve provider-required thread affinity.

libpq uses nonblocking connect/send/flush/consume/result states, with one PGconn owner. Drain required results before another command. Its 14-compatible cancellation call can block, so run that control operation off-dispatcher with its separate cancel object. Retain the lease until control completion and discard a connection after dispatched cancellation, avoiding a late cancel hitting a later query. Discard on failed protocol resynchronization too.

### Transactions and uncertain outcomes

An operation error/cancellation makes the transaction rollback-only. Resource exit awaits rollback/close with cancellation shielding and reconciles any provider automatic rollback. No nested transactions, savepoints or pooling initially.

Before commit dispatch, accepted cancellation prevents commit and rolls back. After dispatch, cancellation cannot establish rollback. Wait for acknowledgement under an independent commit/cleanup policy: acknowledged success means committed; known rejection follows provider state; lost connection/unresolved deadline yields a distinct **transaction outcome unknown** error and invalidates the connection. Never retry automatically. Autocommit writes and HTTP requests have analogous side-effect uncertainty.

Keep migrations exclusive startup work initially. Later async migration entry points reuse immutable plans, checksums, advisory locking and atomic-batch rules. Do not interleave application queries on the migration connection.

## 10. Async web servers and shared state

Implement after task/client correctness. Add an explicit async handler contract returning `कार्यफल<वेबउत्तर>` and async server mode; preserve synchronous handlers/server behavior. Accept task-returning function values directly as well as an explicit interface. Adapting a sync handler does not make its blocking work nonblocking.

Use nonblocking accepted sockets, preserve current framing/body/header validation and reverse-proxy TLS. Proposed initial limit: 16 active requests, no unbounded application queue, 503 on overload when possible. Separate socket deadlines and handler deadline. Disconnect signals cancellation when detected. Shutdown stops acceptance, cancels/drains handlers, closes database resources and sockets; supervisor hard deadlines still apply.

One mutator prevents simultaneous writes, but logical races remain across awaits. Audit session validation/rotation/revocation, CSRF, routing and logging. Keep security-critical transitions non-suspending and reconsider authorization after an intervening await when needed. Never share a transaction across requests. Password hashing still blocks unless explicitly moved behind a dedicated bounded crypto adapter; arbitrary Bhash callback execution on workers is out of scope. Document that limitation until the adapter exists.

Hindi demonstration: fetch bounded JSON first; validate it; open an async database resource; acquire a transaction; prepare parameterized SQL; bind copied values; await execution/commit; close in reverse order. Do not keep a database transaction open while waiting for unrelated HTTP. Demonstrate cancellation, timeout and uncertain commit outcomes separately. Examples must become executable cross-target tests rather than remain pseudocode.

## 11. Validation and acceptance criteria

| Area | Required tests |
| --- | --- |
| Language | Typed/unit/generic tasks, async lambdas, virtual/interface calls, invalid contexts and Hindi diagnostics |
| Scheduling | Queued start, always-yield await, multiple awaiters, repeated awaits, child observation/error selection, scope joins |
| Control flow | Suspension in loops/catch/finally, evaluation order, return/break/continue, cleanup error precedence |
| Cancellation | Before start, queue saturation, I/O races, linked tokens, deadline precedence, wait-only cancellation, shutdown |
| Native GC | Forced collection at frame creation/suspend/resume/completion/conversion/cleanup; captures, nested JSON/blobs, cycles, failures |
| Foreign lifetime | Late/duplicate completions, generation reuse, close/interrupt races, cancellation payload cleanup, no heap access by workers |
| HTTP | Existing policy suite plus overlapping slow requests, snapshot mutation, abort, limits and reuse |
| Database | Both providers, type parity, leases/busy errors, rollback-only state, bounded results, connection discard, commit-loss fixtures |
| Browser | Responsive host, CORS/redirect/credentials restrictions, abort, denied capabilities and unchanged playground isolation |
| Distribution | Runtime-only native OS matrix, symbol/version checks, ABI mismatch and offline execution |

Compare values, error categories/messages, cleanup order and specified partial orders, not nondeterministic completion order or platform thread IDs. Use loopback HTTP/disposable PostgreSQL, controllable scheduler clocks and deterministic race injection. Never depend on public endpoint timing. Native stress tests check final managed live=0, empty operation/root registries, foreign bytes and open handles; managed heap counts alone cannot prove I/O cleanup. Add sanitizers where supported.

Extend the Bhash test runner to recognize task-returning tests and await cleanup before reporting completion. A test must not pass while its assertions are still queued. Editor tooling needs keywords, task types, diagnostics, source mappings and logical async stack presentation.

Measure before optimization: time to first response, throughput, p50/p95/p99 latency, CPU, peak RSS, managed/foreign bytes, queue depth, connection count, cancellation-to-settlement latency and GC pauses. Compare sync/async at concurrency 1, 4 and 16 with identical workloads, recording revisions/environment and separating cold/warm runs. Require overlap and bounded resources; do not promise improved single-request latency.

## 12. Implementation sequence and review boundary

1. **Approve this design:** spellings, one-mutator model, ordering, ownership, cleanup and cancellation semantics. No implementation before review.
2. **Task core:** typed state machines, scheduler, delay/yield, ownership, tokens, async entry/cleanup and stack traces; cross-target/GC tests without networking.
3. **Async HTTP:** desktop adapters/shared policy, load/cancellation tests; browser profile separately gated without enabling playground egress automatically.
4. **Async databases:** SQLite workers, then PostgreSQL JDBC/libpq; leases, bounded results, cancellation/uncertain commit, runtime packaging and integration tests.
5. **Async server:** bounded handlers/sockets, authentication/state audit, request cancellation, graceful shutdown, Hindi application walkthrough.
6. **Release:** editor/test-runner/debugger support, Hindi reference/tutorials, packaging checks and comparison report. Marketplace/playground deployment remains a separate release action.

This is several reviewable milestones, not a keyword-only patch. Greatest risks: suspension through cleanup, native callback lifetimes, database cancellation and browser policy differences. Estimate implementation after a focused lowering/runtime spike rather than promising a schedule now.

**Review questions:** Are the Hindi names acceptable? Is cooperative single-mutator execution acceptable initially? Do queued-start/always-yield and structured draining match expectations? Is explicit async-resource syntax acceptable? Are separate browser semantics and conservative database connection discard/unknown-commit reporting acceptable? Capacity defaults can change without altering these core contracts.

## 13. Provider references

Reviewed 3 November 2026. These inform adapter design, not Bhash language semantics:

- [PostgreSQL 14 asynchronous commands](https://www.postgresql.org/docs/14/libpq-async.html): explicit readiness and result processing.
- [PostgreSQL 14 cancellation](https://www.postgresql.org/docs/14/libpq-cancel.html): dispatching cancellation does not establish the query's final outcome; preserve the existing version baseline.
- [SQLite interrupt](https://www.sqlite.org/c3ref/interrupt.html): protect connection lifetime and reconcile transaction effects.
- [libcurl multi](https://curl.se/libcurl/c/libcurl-multi.html): multiple transfers can progress without one Bhash worker per request.
- [Fetch standard](https://fetch.spec.whatwg.org/): browser credentials, headers, CORS and redirect visibility differ from desktop transports.

JDK, CLR and WinHTTP adapter details require platform-specific API verification/prototypes during implementation. This document does not assume identical provider cancellation guarantees.
