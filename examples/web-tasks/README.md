# Bhash task board

A real Bhash HTTP server, router, UTF-8 JSON API and SQLite CRUD application.
[हिन्दी में पूरा अभ्यास](../../tutorial/web-applications/README.md) ·
[HTTP contract and deployment limits](../../docs/web-applications.md)

**हिन्दी:** यह वास्तविक भाष HTTP सर्वर, मार्गक, UTF-8 JSON API और SQLite की कार्य-सूची है। [हिन्दी में पूरा अभ्यास](../../tutorial/web-applications/README.md) और [HTTP नियम व तैनाती सीमाएँ](../../docs/web-applications.md) देखें।

Build from the repository root with the new compiler:

**हिन्दी:** नए संकलक से रिपॉज़िटरी की जड़ में निर्माण करें:

```sh
mvn clean package -DskipTests
java -jar target/bhash-compiler-0.1.0-SNAPSHOT.jar compile examples/web-tasks --target jvm -o examples/web-tasks/tasks.jar
cd examples/web-tasks
java -jar tasks.jar
```

Open <http://127.0.0.1:8080/>. Run the executable from this directory so `index.html`
is found, or pass `--page=/absolute/path/index.html`. On Windows use `curl.exe`
when following curl examples in PowerShell.

**हिन्दी:** <http://127.0.0.1:8080/> खोलें। executable इसी फ़ोल्डर से चलाएँ ताकि `index.html` मिल जाए। दूसरे फ़ोल्डर से `--page=/absolute/path/index.html` दें। Windows PowerShell में `curl.exe` चलाएँ।

All options are ASCII `--name=value` arguments:
`--host=127.0.0.1`, `--port=8080`, `--db=tasks.db`, `--page=index.html`.
`--requests=0` serves until Ctrl+C/SIGTERM; a positive value is a bounded test run.
Port 0 asks the OS for a free port; stdout prints `READY <ASCII-port>`.

**हिन्दी:** सभी विकल्प ASCII `--name=value` तर्क हैं। `--host=127.0.0.1`, `--port=8080`, `--db=tasks.db` और `--page=index.html` पते और फ़ाइलें तय करते हैं। `--requests=0` पर सर्वर Ctrl+C/SIGTERM तक चलता है। धनात्मक मान परीक्षण के अनुरोध सीमित करता है। पोर्ट ० से OS खाली पोर्ट चुनता है और `READY <ASCII-port>` छपता है।

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

**हिन्दी:** JSON अनुरोध में `Content-Type: application/json` दें। पहचान न मिलने पर 404, दोहराने पर 409 और गलत क्षेत्र पर 422 मिलता है। ब्राउज़र शीर्षक `textContent` से दिखाता है। SQL में पहचान समेत हर मान मापदंड से बँधता है। बदलाव संसाधन दायरे में commit होते हैं और अपवाद पर वापस होते हैं। SQLite फ़ाइल पुनः शुरू करने पर भी रहती है। यह एक उपयोगकर्ता का शिक्षण ऐप है। सार्वजनिक उपयोग से पहले उदाहरण reverse proxy में प्रवेश नियंत्रण जोड़ें।

Test without opening a port:

**हिन्दी:** पोर्ट खोले बिना परीक्षण करें:

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

**हिन्दी:** Managed और native एकीकरण परीक्षण वास्तविक सॉकेट, गलत HTTP framing, समयसीमा, JSON, SQLite, फिर चलाने पर डेटा और forced GC जाँचते हैं। `WebServerFixture` देखें। Linux के लिए `native package` से executable बनाएँ, `/opt/bhash-tasks` में `index.html` के साथ रखें और `deploy/` की फ़ाइलें अनुकूलित करें। निर्माण के लिए Java और नेटिव उपकरण चाहिए। प्राप्त होस्ट पर बना native ऐप Java, .NET या LLVM/C उपकरण के बिना चलता है।
