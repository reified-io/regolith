# Architecture

How Regolith is put together: the modules, the server's layers, the lifecycle of sessions and
commands, what is stored where, and what the server does at startup. It is written for people
changing the code; to use Regolith, the [API](api.md) and [configuration](configuration.md) are
enough.

## Modules

```
protocol   wire types of /v1 and of the pages intake — the contract
server     the control plane
pages      the public role: published sites and the intake that receives them
sdk        Kotlin client (protocol + Ktor client)
koog       Koog ShellCommandExecutor over the sdk
images     the server image, and the base and full sandbox images
skills     the Agent Skill for using a Regolith server
```

`protocol` depends on nothing but kotlinx.serialization, so any JVM client can use the types without
the SDK. The server's API tests drive it through the real SDK, which keeps the two honest with each
other.

## Server layers

```
   http                docker · store · publish
    │                            │
    ▼                            │ implement
   app ─────────▶ ports ◀────────┘
    │
    ▼
   domain · output · config
```

Dependencies point inward:

- `domain` is the model and its invariants, with no I/O.
- `ports` are the interfaces the application needs from the world.
- `app` holds the use cases.
- The adapters implement ports or call the application, and never each other: `http` (Ktor, the only
  layer that knows the `/v1` wire types), `docker`, `store` and `publish` (the pages intake, over
  `protocol.pages`).

`Main.kt` builds every object once, by hand, and dispatches the server's commands; `ops` holds the
operator commands that are not the API. Both may use every layer, and no layer may use them.
`ArchitectureTest` checks the imports.

| Port | Production adapter | What it owns |
|---|---|---|
| `SandboxRuntime` | `DockerRuntime` | Session containers, the commands in them, their files, network attachment |
| `HomeStore` | `HomeDisks` | Fixed-size homes on loop devices |
| `NetworkEnforcer` | `HostFirewall` | The host network floor and each sandbox's policy |
| `StateStore` | `FileStateStore` | Records and output logs |
| `SitePublisher` | `PagesPublisher` | Releases sent to the pages role |

`HomeDisks` and `HostFirewall` do their privileged work through **helper containers** (`Helpers`):
short runs of the server's own image with exactly the capability one fixed script needs. The server
resolves that image to an id at startup — from `REGOLITH_HELPER_IMAGE`, or by inspecting the
container it runs in — so what it checks and what it runs come from one build.
[Security](sandbox-security.md) describes both.

## Sandboxes and sessions

`Sandboxes` is the registry: records in memory, persisted on every change, one lock per sandbox.
`Sessions` owns running containers, at most one per sandbox and `REGOLITH_MAX_SESSIONS` in total.

Starting a session:

1. Check the health gates.
2. Resolve the sandbox's image against the catalogue.
3. If the pool is full, reclaim an idle session.
4. Open the home.
5. Start the container on that image with an idle entrypoint, attached to the sandbox network — or,
   for `none`, to nothing.
6. Apply the network policy to its address.
7. Publish the session.

Nothing of the caller's can run before step 6. Commands only arrive through a lease on a published
session, and until its policy is applied the container's address matches no policy and reaches
nothing. If the policy cannot be applied, the container is removed and the request fails.

**The image.** A sandbox records an image policy, not an image: the server's default, a repository
it tracks, or one pinned reference. Step 2 applies it against `ImageCatalog`, which answers with a
reference from the configured images and nothing else, so an upgraded server hands its sandboxes a
newer image at their next session while a pinned sandbox stays where it is. The running session
keeps the image it started on, and `SandboxInfo` reports both that one and the next. A tracked
repository the configuration stopped offering resolves to nothing and the session start fails,
rather than quietly starting on another image; `doctor` names such sandboxes.

**Leases.** Work holds a `Lease` while it runs: an exec from its start to its recorded outcome, a
file operation for its duration. A session with a lease is never reclaimed or stopped for being idle
— only stopped explicitly, by deletion, at its maximum lifetime, or by the network guard. A
background process started by a finished command holds no lease, and ends with its idle session.

**Changing the policy of a running session.** To `none`, the policy is released and the session
detached. From `none`, the session is attached and the policy applied to its new address. Otherwise
the policy is replaced in place.

**Guards.** Two loops stop sessions from outside the request flow:

- The lifecycle sweep ends idle and expired sessions.
- The **CPU guard** reads each session's cgroup counter every 30 seconds and adds up only the CPU
  spent over intervals with no activity at all — no lease at the tick, none taken or released since
  the last one. A command's own work never counts, but a process it left running does. Past
  `REGOLITH_UNATTENDED_CPU_SECONDS`, the session is stopped if it is still idle.

Every stop records its reason, which `SandboxInfo.lastSessionEnd` reports.

## Execs

```
start ──▶ lease session ──▶ docker exec ──▶ pumps: stdout, stderr ──▶ output log
                                  │
               exit / timeout / cancel / session ended
                                  │
            drain window ──▶ detach ──▶ finish log ──▶ record outcome ──▶ release lease
```

- **Outcome precedence.** An interrupt reason set before a session is stopped wins, then a cancel,
  then the timeout, then the exit code. So a stopped session records `interrupted: stopped`, not the
  137 its killed processes report.
- **Exit causes.** When a command starts, the session's `memory.events` and `pids.events` counters
  are read in the background; when it exits non-zero, they are read again. A grown `oom_kill` count
  makes the outcome `exited: oom_killed`, otherwise a grown `max` count makes it `pids_limited`. The
  counters belong to the whole session, so a concurrent command can take the blame for its
  neighbour. A failed read only leaves the reason out.
- **Signals.** Each exec carries `REGOLITH_EXEC_ID` in its environment. Cancel and timeout send
  SIGTERM, then SIGKILL after a grace period, to every process in the session carrying that marker —
  detached descendants included. Stopping the session is the backstop for anything that shed the
  marker.
- **Drain window.** A detached child inherits the output pipes and can hold them open forever. After
  the command itself ends, output gets two seconds to drain; then the local client is detached and
  the log finished.
- **Idempotency.** A start with a known key returns the existing exec. Keys are kept with the exec
  record, so they last as long as the record.
- **Retention.** The last 50 finished execs per sandbox are kept, records and logs.
- **Restart.** Sessions do not survive a server restart. On startup, unfinished records are closed
  as `interrupted: server_restarted`, and their logs are cut back to the last whole frame.

## The output log

One file per exec, holding a sequence of frames:

```
0xFE  kind (1 = stdout, 2 = stderr, 3 = gap)  length (u32, big-endian)  payload
```

- An offset is the byte position of a frame boundary. Clients resume from any frame's `end`.
- Stdout and stderr payloads are always valid UTF-8: the writer holds back a character split across
  two reads and replaces malformed bytes. `0xFE` never occurs in UTF-8, so a reader rejects an
  offset that does not start a frame.
- Readers read only up to the end the writer has published, never to the file's length, which may
  include a frame still being written.
- The log is capped. Output fills it from the start until only the tail budget remains; after that
  the newest bytes are kept in memory, and on finish the writer appends a gap frame with the dropped
  count, then that tail. A failing command explains itself at the end, so the end is never what gets
  dropped.

## State

`FileStateStore` keeps JSON records under `REGOLITH_STATE_DIR`:

```
lock                              exclusive: one server per directory
namespace                         the namespace this directory belongs to
sandboxes/<name>/sandbox.json
sandboxes/<name>/execs/<id>.json
sandboxes/<name>/execs/<id>.log
```

Each record is the domain model wrapped with a schema number. There are no migrations: changing a
persisted domain type raises `FileStateStore.SCHEMA`, and a server refuses records of another
schema. Writes go to a temporary file and are renamed into place.

## Startup and shutdown

Startup runs its preconditions before the API listens, and any failure exits:

1. Open the state directory: take the lock, check the namespace.
2. Resolve the helper image.
3. Load sandboxes; close unfinished exec records.
4. Initialize the runtime: remove containers left by a previous run of this namespace, and ensure
   the sandbox network exists.
5. Release home attachments left by a previous run.
6. Refuse to start while a home exists that no sandbox record claims (see
   [security](sandbox-security.md#homes)); `orphans` resolves it.
7. Install the network floor, and prove it with a control listener and a throwaway probe.
8. Check host storage.

Then four loops run in the background:

| Loop | Every | Does |
|---|---|---|
| Lifecycle sweep | 15 s | Stops idle and expired sessions, deletes sandboxes past retention |
| Storage guard | 10 s | Fails the `storage` health check, and with it new sessions and uploads, while free space is below the reserve |
| CPU guard | 30 s | Stops sessions that burn CPU with nothing of their own running |
| Network guard | 5 min | Re-reads the firewall and repairs drift in place |

Once, alongside them, the server pulls every image in its catalogue. A session start would pull what
it needs anyway, but it holds the session capacity while it does, so after an upgrade that changed
the images each sandbox in turn would wait for a download.

A floor the network guard cannot restore latches the `network` health check to failing and stops
every session. The latch does not clear on its own.

On shutdown the API stops accepting requests, running execs are marked `server_restarted`, and every
session is stopped.

## Commands

The server image runs one program, which takes a command:

| Command | Does |
|---|---|
| `serve` (the default) | Runs the control plane API |
| `pages` | Runs the public role: serves published sites and takes releases in (see [pages](pages.md)) |
| `doctor` | Checks the host against every startup precondition and prints each check with its fix; exits 1 if one would stop the server |
| `orphans` | Lists homes that no sandbox record claims |
| `orphans adopt` | Gives each orphaned home a record again: default settings, its own size, label `regolith.adopted=true` |
| `orphans delete` | Deletes orphaned homes and their files |

`doctor` changes nothing — no rule, no loop attachment, no pull, no lock — so it can run beside a
live server. It checks:

- the Docker daemon, and cgroup v2 with memory, pids and CPU limits;
- the helper image and the sandbox images;
- the netfilter backend and hook positions, and a LAN control for the network proof;
- loop devices and free space;
- the state directory, its namespace, and orphaned homes;
- when one is configured, whether the pages role answers and accepts this server's token.

`orphans` takes the state lock, so the server must be stopped first.

## Pages

The `pages` role is a second deployable from the same image, normally on a machine with a public
address. It shares the project's conventions — a token, problem documents, atomic writes, a file
store — and nothing else: it holds no Docker socket, starts no container, and has no route that runs
anything.

```
visitors       ──▶ public listener ──▶ manifest lookup ──▶ blob
control plane  ──▶ intake listener ──▶ release         ──▶ activate (atomic pointer)
```

- **Content-addressed.** A release is a manifest of `path → sha-256, size, content type`, plus blobs
  named by their hash. Publishing the same file twice stores one blob, and a rollback is a pointer.
- **Serving is a map lookup.** A request path becomes a manifest key; nothing maps it onto a
  filesystem path, so traversal, symlinks and dotfiles are not a class of bug that exists here.
- **Two listeners.** The public one only reads. The intake takes the token and binds to the loopback
  address by default, so publishing has no public endpoint unless an operator gives it one.
- **Verified while it arrives.** Bytes are hashed as they are written and refused if they do not
  match the name they were sent under; a release goes live only once every file it names is stored.

[Pages](pages.md) covers deploying and operating it.

## What v1 leaves out

Each of these can be added without reshaping what exists:

- **Domain rules and credential brokering** in network policy — an egress proxy on the host that
  matches SNI and, for brokered domains, terminates TLS with a per-sandbox CA and injects headers,
  so a sandbox uses a token it never sees. `NetworkAllow` is an object so these can join it.
- **A start command with a readiness probe** — a process started with every session, its output an
  ordinary exec, ready when a TCP port answers or a command succeeds.
- **Filesystem snapshots and clones** — a home is one filesystem image, so a snapshot is a copy of
  it.
- **An archived state** — compress an idle home instead of keeping it preallocated.
- **Preview URLs** for ports, **lifecycle webhooks**, **PTY** over WebSocket, and an **MCP**
  adapter.
- **A stronger runtime** (`runsc`, Kata) for session containers, selectable per server. Helpers keep
  the default runtime: host networking, loop devices and `iptables-restore` are what a sandboxed
  kernel does not do. The cgroup counters and `/proc` that the CPU guard, exit causes and signals
  read have to be proven under it first.
