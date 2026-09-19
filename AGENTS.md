# AGENTS.md

Regolith is a self-hosted sandbox service for AI agents: persistent, named Linux sandboxes on one
Docker host behind an HTTP API, with a Kotlin SDK and a Koog integration. These are the rules for
working on it (`CLAUDE.md` imports this file); the product is in `README.md` and `docs/`.

Pre-1.0 and unpublished: change the protocol, the storage schema and the module layout freely, and
prefer a clean removal to a compatibility shim. Read [`docs/architecture.md`](docs/architecture.md)
before changing request flow, sessions, execs, output, storage or startup, and
[`docs/sandbox-security.md`](docs/sandbox-security.md) before touching what creates, confines or
reaches into a container.

## Modules

| Module | Holds | Depends on |
|---|---|---|
| `protocol/` | Wire types of `/v1` and, in `protocol.pages`, of the pages intake | kotlinx.serialization only |
| `server/` | The control plane | `protocol`, `pages` |
| `pages/` | The public role: published sites and the intake that receives them | `protocol` |
| `sdk/` | The Kotlin client | `protocol`, Ktor client |
| `koog/` | Koog `ShellCommandExecutor` over the SDK | `sdk`, Koog `agents-ext` |
| `images/` | The server image and the sandbox images | — |
| `skills/` | The Agent Skill for using a Regolith server | — |
| `buildSrc/` | The convention plugins every module applies | — |

- `protocol`, `sdk` and `koog` are libraries built with `explicitApi()`: every public declaration
  says `public` and carries KDoc. The server keeps default visibility. They are what a release
  publishes to Maven Central, as `io.reified.regolith:<module>`, so a module added to that list needs
  a `description` — the POM is refused without one.
- A module's build file applies one convention plugin — `regolith.library` for a published library,
  `regolith.jvm` for the rest — and never repeats the toolchain or test wiring they hold.
- The server image builds from an allowlist in `.dockerignore`: the build files, the convention
  plugins, each module's `src/main` and `images/server/scripts`. A new module, or anything else the
  image needs, has to be named there or it never reaches the build.

## Commands

- Run `./gradlew build detekt` before finishing a code change (`maxIssues: 0`); if a check cannot
  run, report the exact command and why. `./gradlew :server:run` starts the server.
- Gradle runs on JDK 21: the build uses `jvmToolchain(21)`, and detekt 1.23.x breaks on newer JDKs.
- `REGOLITH_DOCKER_TESTS=1 ./gradlew :server:test --tests "*DockerRuntimeIntegrationTest*"` drives
  the runtime against a real Docker daemon. Run it after changing anything in `server/.../docker/`.
- `REGOLITH_HOST_TESTS=1 ./gradlew :server:test --tests "*HostIntegrationTest*"` checks homes and
  the host firewall on this machine: it installs iptables chains that match only its own bridge and
  attaches a loop device, then removes both. Run it after changing `HostFirewall`, `FirewallRules`,
  `HomeDisks`, `Helpers` or `images/server/scripts/`, and say that you did: it touches the machine.
- The program takes a command: `serve` (default), `pages`, `doctor`, `orphans [adopt | delete]` —
  see [architecture](docs/architecture.md#commands). `doctor` is read-only and the first thing to
  run on a host that misbehaves.
- CI: `.github/workflows/build.yml` runs `./gradlew build detekt` on every push and pull request,
  with actions pinned by commit and bumped by Dependabot. A GitHub release publishes all three
  images through `.github/workflows/images.yml`, each tagged with the version, because a server
  pointed at a sandbox image from another build fails only when a command does. Only the server also
  takes `latest` and `major.minor`, since a moving sandbox image changes every running sandbox. The
  same release signs the three libraries and uploads them through `.github/workflows/libraries.yml`,
  which runs in the `maven-central` environment — the one place the Central token and the signing key
  exist, and it admits release tags and no branch. The upload only stages the deployment: a version
  published to Maven Central can never be replaced or removed, so releasing it stays a person's click
  in the Portal. The job refuses a release whose tag and `gradle.properties` version disagree, since
  images take their tag from git and jars take their version from the build.

## Server architecture

Layered, with dependencies pointing inward; `ArchitectureTest` enforces the imports.

- `domain/` — the model and its invariants: names, sandboxes, execs, network policy, errors. No I/O,
  coroutines or framework; a value is valid by construction, its factory throwing
  `RegolithError.Invalid`.
- `ports/` — interfaces to the outside: `SandboxRuntime`, `HomeStore`, `NetworkEnforcer`,
  `StateStore`, `SitePublisher`. Plain types in and out; adapters never call back in.
- `app/` — the use cases: `Sandboxes`, `Sessions`, `Execs`, `SandboxFiles`, `SitePublishing`,
  `Sweeper`, the guards. Only `domain`, `ports`, `config` and `output` — no adapter, no Ktor.
- `output/` — the exec output log format, pure file I/O.
- `config/` — `ServerConfig`, the one reader of `REGOLITH_*` variables, read once.
- Adapters, which never import each other: `http/` (Ktor, the only one that knows the `/v1` wire
  types), `docker/`, `store/`, and `publish/` (the pages intake, over `protocol.pages`).
- `Main.kt` wires everything by hand, with no DI framework, and dispatches the commands; `ops/`
  holds operator commands such as `Doctor`. Both may use every layer; no layer imports them.

Rules that keep it that way:

- One owner per concern: sessions start and stop only through `Sessions`, an exec's lifetime belongs
  to `Execs`, and container command lines live in `ContainerSpec` and nowhere else.
- Interrupt before you stop: whoever ends a session marks its execs with `Execs.interrupt` first, so
  an outcome records why it ended instead of a killed process's exit code.
- Work inside a session holds a `Sessions.Lease` for as long as it runs; idle means no lease.
- A sandbox stores an image policy, never a resolved image: `ImageCatalog` answers it at each
  session start, and only with a reference the configuration names.
- Errors a caller can act on are `RegolithError` subtypes, mapped to status, code and title in
  `http/Api.kt` only; anything else is a 500 and is logged. `/v1` wire types map to domain types in
  `http/Mapping.kt` only.
- A new startup precondition gets a read-only `Doctor` check with the fix in its message.
- Add an abstraction only when it removes real complexity or a second implementation exists.

## The pages role

`pages/` is the public half — see [`docs/pages.md`](docs/pages.md). A second deployable from the
same image, it shares the project's conventions and nothing else.

- It holds no Docker socket, starts no container and runs no caller's code; anything that would
  execute belongs in the control plane.
- Two listeners, never merged: the public one only reads, the intake one takes the token. A route
  that changes state on the public listener must never exist.
- A release is immutable and addressed by content: a request path becomes a manifest key and the
  blob is read by hash. Nothing is served from a path a request supplies.
- Bytes are verified while they arrive — hashed as they are written, stopped at the file cap — and a
  release goes live only once every file it names is stored.
- It owns its limits (`REGOLITH_PAGES_*`) and state directory; it shares no file with the control
  plane.
- The control plane reaches it only over HTTP, with the types in `protocol.pages`. In the server
  only `Main.kt` imports `pages`, to run the role; `ArchitectureTest` enforces it.
- It runs on Netty, because CIO cannot terminate TLS. A trust store on the SSL connector makes Netty
  require client certificates, which origin pulls rely on — keep that the only switch for it.
  Certificates come from files; never add an ACME client.

## Protocol rules

- The contract is `protocol/` plus [`docs/api.md`](docs/api.md) — for the intake, `protocol.pages`
  plus [its section in `docs/pages.md`](docs/pages.md#the-intake-api). Change both together.
- Requests are decoded strictly (unknown fields fail), responses leniently in clients.
- Errors are RFC 9457 problem documents (`ErrorBody`, `application/problem+json`). Error codes are
  strings in `ErrorCodes`, never an enum a client could fail to decode.
- Reasons (`ExecOutcome.reason`, `lastSessionEnd.reason`) are open sets of lowercase strings; fields
  that may grow variants are objects (`NetworkAllow`).
- The server is authoritative about defaults and limits and advertises them in `GET /v1/info`; no
  client keeps a copy.
- Timestamps are `kotlin.time.Instant`; durations in wire types are whole seconds or days, named in
  the field (`idleStopSeconds`, `retainDays`).

## Security invariants

These hold for every change; [`docs/sandbox-security.md`](docs/sandbox-security.md) explains each.

- Fail closed. A precondition that cannot be established — the network floor, bounded homes, the
  state lock — stops the server. Never add a flag, fallback or degraded mode that serves sandboxes
  without one, and never swap `HomeDisks` or `HostFirewall` for something weaker to get it started.
- No unauthenticated mode. The token guards every endpoint but health and `/llms.txt`, is compared
  in constant time, and the SDK never follows redirects.
- A sandbox runs as uid 1000 with no capabilities, no privilege escalation, a read-only root, and
  bounded memory, CPU and pids. Neither the server's Docker socket nor anything about the host —
  configuration, secrets, addresses — reaches its environment, mounts or command lines.
- Caller input reaches a container only as a process argument, never interpolated into a script. A
  helper script takes paths and ids as positional parameters (`$1`).
- Every read of untrusted bytes is bounded while it happens, not checked after. A reader past its
  cap keeps draining or kills the writer; it never just stops reading a pipe.
- A session is published only after its network policy is applied, and nothing of the caller's runs
  in it before that. An address with no policy reaches nothing; `none` means detached, never
  attached with a deny rule, because Docker's resolver answers from outside any rule.
- The firewall ruleset is rendered whole in `FirewallRules` and swapped atomically: never edit rules
  incrementally, and never let the helper touch a chain outside its namespace prefix.
- Helpers run the server's own image with only the capability their one script needs. A new
  privileged operation is a new fixed script, never a shell command assembled in Kotlin.

## Kotlin style

- Properties, `require`/`check`/`error`, null-safe chains over null ladders, no `!!`. Caller input
  is validated with `requireValid` or a `RegolithError`, not `require`.
- Never swallow `CancellationException`: catch it first and rethrow, or catch a narrower type.
- Blocking I/O runs on `Dispatchers.IO`; a blocking read is ended by closing its stream.
- Comment sparingly and only on why — non-obvious constraints, invariants, traps — and leave no
  commented-out code. `//` comments are lowercase throughout; KDoc is ordinary prose.
- Class loggers and constants live in a `private companion object`; log values as `key=[value]`.
- Members follow the Kotlin conventions' order: properties and initializers, secondary constructors,
  methods, and the companion object last.
- Blank lines carry meaning. A wrapped class header is followed by one. A `return` that ends a
  function body has one above it whenever there is work above it, but a `return` inside a small
  block stays where it is. A multi-line `if`, `when`, `try`, `for` or `while` at the top of a
  function body is separated from what surrounds it. Never two in a row.
- Trailing commas at declaration sites; never a star import, which detekt refuses anyway.

## Tests

- `kotlin.test` assertions; suspend tests use `runBlocking` or `testApplication`.
- API behaviour is tested through the real SDK against the real Ktor module over fakes
  (`server/src/test/.../support`), so the server and the SDK are checked as one contract.
- Test paths mirror production paths; never widen visibility or add production overloads for tests.
- Fixture text is invented English.

## Documentation

Update in the same change as the behaviour:

- `docs/api.md` — any endpoint, field, error code or semantic change, and with it the two texts
  written for agents: `server/src/main/resources/llms.txt` (served at `/llms.txt`; the root
  `llms.txt` is a symlink to it) and `skills/regolith/SKILL.md`. Keep both short.
- `docs/configuration.md` and `.env.example` — any `REGOLITH_*` variable added, removed, renamed or
  given a new default. A commented-out line in an env example holds the variable's default.
- `docs/pages.md` and `.env.pages.example` — anything `pages` serves, stores, limits or is
  configured with.
- `docs/architecture.md` — layers, lifecycle, output format, storage layout, startup.
- `docs/sandbox-security.md` — what creates, confines or reaches into a container, the network
  floor, homes, the token.
- `README.md` — a user-visible capability, in one line; the detail belongs in `docs/`.

The docs are written for people: sentence-case headings, prose wrapped at 100 columns, and invented
example names such as `user-23`, never a real product.

## Commits

- Subject `scope: imperative lowercase phrase`, no trailing period, at most ~65 characters.
- Scopes: `protocol`, `server`, `pages`, `sdk`, `koog`, `images`, `skills`, `docs`, `build`, `ci`;
  omit for repo-wide changes.
- The subject alone is usually enough; add a body wrapped at 72 characters only when the why is not
  obvious from the diff. Never mix unrelated work in one commit.
