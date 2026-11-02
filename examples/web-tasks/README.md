# Bhash task board

A real Bhash HTTP server, router, UTF-8 JSON API and SQLite CRUD application.
[हिन्दी में पूरा अभ्यास](../../tutorial/web-applications/README.md) ·
[HTTP contract and deployment limits](../../docs/web-applications.md)

Build from the repository root with the new compiler:

```sh
mvn clean package -DskipTests
java -jar target/bhash-compiler-0.1.0-SNAPSHOT.jar compile examples/web-tasks --target jvm -o examples/web-tasks/tasks.jar
cd examples/web-tasks
java -jar tasks.jar
```

Open <http://127.0.0.1:8080/>. Run the executable from this directory so `index.html`
is found, or pass `--page=/absolute/path/index.html`. On Windows use `curl.exe`
when following curl examples in PowerShell.

All options are ASCII `--name=value` arguments:
`--host=127.0.0.1`, `--port=8080`, `--db=tasks.db`, `--page=index.html`.
`--requests=0` serves until Ctrl+C/SIGTERM; a positive value is a bounded test run.
Port 0 asks the OS for a free port; stdout prints `READY <ASCII-port>`.

| Method | Path | Behavior |
| --- | --- | --- |
| GET / HEAD | `/` | Hindi browser interface |
| GET / HEAD | `/health` | Health check |
| GET / HEAD | `/api/tasks` | First 100 tasks ordered by id |
| POST | `/api/tasks` | Create `{ "id": "one", "title": "पहला कार्य" }` |
| GET / HEAD | `/api/tasks/:id` | Read one task |
| PUT | `/api/tasks/:id` | Replace title and done state `{ "title": "कार्य", "done": true }` |
| DELETE | `/api/tasks/:id` | Delete one task |

JSON requests require `Content-Type: application/json`. Missing ids return 404,
duplicates 409, invalid fields 422. The browser uses `textContent` for task titles.
SQL values are bound parameters, including ids. Mutations commit inside resource
scopes; exceptions roll back and release statements. The SQLite file survives restarts.
This is an intentionally small **single-user teaching app** with no login system.
The deployment proxy example adds access control before exposing it.

Test without opening a port:

```sh
java -jar target/bhash-compiler-0.1.0-SNAPSHOT.jar test examples/web-tasks/tests --target jvm
# Repeat with --target clr or --target native.
```

Managed and native integration tests also exercise real sockets, malformed framing,
timeouts, JSON, SQLite, restart persistence and forced collection. See `WebServerFixture`.
For Linux deployment, package a native executable with the existing `native package`
command, place it with `index.html` in `/opt/bhash-tasks`, and adapt the files in `deploy/`.
The generated native application needs neither Java nor .NET; building it still needs Java
and the supported native toolchain. No LLVM/C tools are needed on the destination.
