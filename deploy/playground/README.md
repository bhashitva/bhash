# Self-hosted Bhash playground — Hostinger VPS pilot

Deployment target: **unused Ubuntu 24.04 / AMD64 VPS, 2 vCPUs, 8 GB RAM,
100 GB NVMe**. The existing site and DNS are separate from this new application.
This package is ready for a first deployment; repository preparation does not
install anything on the VPS or create DNS records.

## Components

- CodeMirror mobile-friendly editor, keyword completion/snippets generated from
  the ANTLR grammar, statement/full-program modes and four starter examples.
- Local draft saving, download/upload, source-in-fragment sharing, cached offline
  editing and a home-screen manifest. Compilation requires a connection.
- Java HTTP API; one compilation child process and up to four queued jobs.
- Java 17 + .NET 10 + wasm-tools are installed in the container image.
- The non-root runtime has a private writable XDG runtime directory and data
  directory for current .NET named synchronization and NuGet migration state.
- SDK dependencies are warmed during image build. Runtime compilation uses only
  local packages, with outbound networking disabled for the compiler container.
- Caddy serves HTTPS at `play.bhashitva.org` and isolates artifacts at
  `run.bhashitva.org`. The Docker socket is not mounted into either container.

Programs execute on the visitor's browser, not the VPS. There is no arbitrary
shell, SDK project, native assembly or package-upload endpoint. The first version
accepts one Bhash source file per submission; imports from other source files and
external dependencies are not accepted through this API. The standalone compiler
retains multi-file support.

## Resource settings for this VPS

| Resource | Pilot setting |
| --- | --- |
| Compiler service CPU | 1.5 vCPU ceiling |
| Compiler service memory | 5 GiB ceiling, including scratch tmpfs |
| Compiler process Java heap | 512 MiB |
| HTTP parent Java heap | 256 MiB |
| Gateway | 0.25 vCPU / 256 MiB ceiling |
| Concurrent / waiting builds | 1 / 4 |
| Build deadline | 180 seconds; child-process tree is terminated |
| Source size | 32,768 UTF-16 code units; 512 KiB encoded request ceiling |
| Anonymous submissions | 10 per client IP per minute |
| Completed cache | At most 8 jobs; expires after an idle hour |
| Maximum bundle | 128 MiB; completed artifact cache is bounded to roughly 1 GiB |
| SDK package cache | Persistent, warmed from the image on version changes |
| Scratch filesystem | 2 GiB tmpfs; cleared on container recreation |
| Browser execution | 15 seconds after runtime initialization; Stop is immediate |
| Browser initialization | 90 seconds, allowing a slower first runtime download |
| Browser output / errors | 65,536 / 8,192 UTF-16 code units |

The limits are pilot defaults, not a throughput guarantee. Memory pressure in a
visitor's browser remains subject to that device/browser; a worker is not a
per-program browser memory quota. Anonymous public access can still exhaust the
available queue. Measure real workload and increase capacity only when needed.

## Information needed before provisioning

The VPS is confirmed unused. Obtain its public IPv4 address and verify console/
SSH access through Hostinger. Verify actual free disk/RAM, OS updates, existing
listeners, and firewall rules before any change. Keep working SSH access when
adding HTTPS rules. Do not recreate the OS or reset the DNS zone.

The Hostinger account/VPS must be identified from an authenticated list before
using management tools. Server installation, firewall changes and DNS changes
need the applicable user confirmation. This repository does not contain credentials.

## Initial deployment procedure

The operator/automation performing the deployment should:

1. Verify the VPS inventory, available space and existing firewall. Install Docker
   Engine and Compose from Docker's official Ubuntu instructions if absent.
2. Check out a reviewed Bhash commit. Copy `deploy/playground/env.example` to
   the checkout root `.env`; set domain names and ACME contact. `.env` is ignored.
3. Build the image. This downloads SDK/workload/reference packages once and can
   take several minutes on two cores. The persistent data volume is initialized
   with the non-root application UID 10001.
4. Add A records `play` and `run` pointing at the VPS public IPv4. Only add AAAA
   records if IPv6 is configured and reachable. Keep the other domain records.
5. Permit incoming TCP 80/443 while retaining the existing SSH management rule.
   UDP 443 is optional for HTTP/3. The API port 8080 is private and is not published.
6. Start the Compose stack, wait for the application health check and HTTPS
   issuance, and run the acceptance checks below.

Reference Compose commands, run by the deployment operator from the checkout:

```sh
cp deploy/playground/env.example .env
# Review .env before startup.
docker compose build
docker compose up -d
docker compose ps
docker compose logs --tail=100 service gateway
```

Hostinger Docker Manager can monitor the resulting project. Do not assume
"Compose from URL" downloads every build-context file and bind-mounted config;
this deployment uses a full checkout. A later release can publish prebuilt images
for a simpler image-only deployment through hPanel.

## Acceptance checks

- Both domains resolve to this VPS and have valid HTTPS.
- The editor loads at `play` and artifact paths are blocked on that origin.
- The `run` origin serves only compiled artifacts, not the editor or API.
- Hello World executes, factorial reads ५ and prints १२०, and records/enums work.
- A typo produces source diagnostics adjusted to the editor's statement lines.
- Stop terminates a running infinite loop; a print loop hits the output ceiling.
- A 390-pixel viewport has no horizontal page scrolling; toolbar insertion works.
- Drafts survive refresh; offline refresh permits editing but disables Run.
- A queued request is rejected politely when full; excessive submissions return 429.
- Containers restart after a VPS reboot; certificates and package caches persist.

CI tests HTTP behavior on Windows/Linux, executes actual WebAssembly through the
playground in Chromium, and builds/runs the rootless offline container. Actual
Hostinger provisioning, DNS propagation and TLS issuance require the real VPS
and are not verified by a local preview or CI.

## Updating and rollback

Review a new compiler commit, rebuild and replace the service image. The compiler
JAR digest is part of cache identity. The entrypoint refreshes package dependencies
when the SDK/compiler version changes. Runtime packages are copied from the image;
there is no runtime package-download path.

The Compose named volumes hold TLS material and NuGet caches. Restarting the
service clears transient jobs/artifacts; source-sharing links still work because
they embed source rather than a job ID. Keep the previous image/checkout for
rollback and retain volumes. Monitor disk space, health status, build failures and
queue pressure. The build endpoint does not log submitted source; source/work
files are removed after compilation. Compiled artifacts can contain program
strings and are accessible to anyone who has their temporary URL.

## Local development

```sh
mvn clean package -DskipTests
npm ci --prefix playground
npm run build --prefix playground
java -cp target/bhash-compiler-0.1.0-SNAPSHOT.jar dev.bhash.playground.PlaygroundServer
```

Use `http://localhost:8080` for the editor and `http://127.0.0.1:8080` for
execution; these are intentionally different origins. Java/.NET SDK/workload
are required on the development machine. `BHASH_WASM_OFFLINE=true` is optional
for a machine whose matching dependencies were restored already.

API requests are form encoded with `source` and `mode` (`scratch` or `program`),
require the configured editor `Origin` and `X-Bhash-Playground: 1`, and return
an asynchronous job. The UI polls `/api/jobs/<opaque-id>`. The service accepts
no arbitrary output path, project file, command line or compiler flags.

## Prepared browser runtime

The image now prepares an immutable browser host once. Public submissions compile
only the changing program and reuse versioned runtime URLs on the execution
origin. See [browser host operation, isolation and profiling](../../docs/browser-host.md).

