# API v1

This page is the contract of Regolith's HTTP API. The Kotlin types in
[`protocol/`](../protocol/src/main/kotlin/io/reified/regolith/protocol) implement it, and the SDK
wraps it.

## At a glance

| Method | Path | Does |
|---|---|---|
| **[Server](#server)** | | |
| `GET` | `/v1/health` | Whether the server can serve; no token |
| `GET` | `/v1/info` | Version, defaults and every limit |
| `GET` | `/llms.txt` | This API condensed for a model; no token |
| **[Sandboxes](#sandboxes)** | | |
| `POST` | `/v1/sandboxes` | Create a sandbox, or return the one an alias already has |
| `GET` | `/v1/sandboxes/{id}` | One sandbox |
| `GET` | `/v1/sandboxes` | The sandbox of one alias, or sandboxes filtered by label |
| `PATCH` | `/v1/sandboxes/{id}` | Change its image policy, network, lifecycle, environment or labels |
| `POST` | `/v1/sandboxes/{id}/start` | Start a session ahead of time |
| `POST` | `/v1/sandboxes/{id}/stop` | End the session; the home stays |
| `DELETE` | `/v1/sandboxes/{id}` | Delete the sandbox and its home |
| **[Execs](#execs)** | | |
| `POST` | `/v1/sandboxes/{id}/execs` | Start a command |
| `GET` | `/v1/sandboxes/{id}/execs` | Recent commands |
| `GET` | `/v1/sandboxes/{id}/execs/{exec}` | One command, optionally waiting for it to finish |
| `GET` | `/v1/sandboxes/{id}/execs/{exec}/output` | Its output, from any offset |
| `POST` | `/v1/sandboxes/{id}/execs/{exec}/stdin` | Write to its stdin |
| `POST` | `/v1/sandboxes/{id}/execs/{exec}/cancel` | Stop it |
| **[Files](#files)** | | |
| `GET` | `/v1/sandboxes/{id}/files/content` | Read a file |
| `PUT` | `/v1/sandboxes/{id}/files/content` | Write a file atomically |
| `GET` | `/v1/sandboxes/{id}/files/entries` | List a directory |
| `GET` | `/v1/sandboxes/{id}/files/stat` | Describe one path |
| `DELETE` | `/v1/sandboxes/{id}/files` | Delete a file or a directory |
| **[Publishing](#publishing)** | | |
| `POST` | `/v1/sandboxes/{id}/site` | Publish a directory to the web |
| `GET` | `/v1/sandboxes/{id}/site` | What is published |
| `DELETE` | `/v1/sandboxes/{id}/site` | Take it down |

## A first command

With the server's address in `$URL` and its token in `$TOKEN`:

```bash
# the sandbox for your own name for it, created or handed back
curl -X POST -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d '{"alias": "user-23"}' "$URL/v1/sandboxes"

# everything else is addressed by the id that answer carries
curl -X POST -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d '{"shell": "uname -a"}' "$URL/v1/sandboxes/<id>/execs"

# read what it printed, waiting up to 30 seconds for output
curl -H "Authorization: Bearer $TOKEN" "$URL/v1/sandboxes/<id>/execs/<exec>/output?waitSeconds=30"

# and how it ended
curl -H "Authorization: Bearer $TOKEN" "$URL/v1/sandboxes/<id>/execs/<exec>?waitSeconds=30"
```

Starting a command never waits for it. Its output stays on the server, so it can be read again, from
any point, by a client that was not even connected when it ran.

## Requests and responses

- Every endpoint but `/llms.txt` lives under `/v1`.
- Every request but `GET /v1/health` and `GET /llms.txt` needs `Authorization: Bearer <token>`.
- Bodies are JSON unless noted.
- Requests are decoded strictly: an unknown field is an error, so a misspelled option fails instead
  of silently taking a default.
- Responses may grow. Clients should ignore fields they do not know, and accept `reason` values they
  do not know.
- Timestamps are ISO 8601 in UTC. Durations are whole seconds or days, named in the field:
  `idleStopSeconds`, `retainDays`.

## Errors

Every error response is an [RFC 9457](https://www.rfc-editor.org/rfc/rfc9457) problem document,
served as `application/problem+json`:

```json
{
  "type": "urn:regolith:error:busy",
  "title": "Sandbox busy",
  "status": 409,
  "detail": "Sandbox `5f2b9c7d1e3a4f6b8c0d2e4f6a8b0c1d` already runs 8 commands",
  "code": "busy"
}
```

Branch on `code`: it is stable, and `type` is the same code as a URI. `title` names the kind of
problem; `detail` explains this occurrence, for people.

| Status | Code | Meaning |
|---|---|---|
| 400 | `invalid_request` | A malformed body, an invalid value, or a value outside a limit |
| 401 | `unauthorized` | Missing or wrong token |
| 404 | `not_found` | No such sandbox, exec, file, published site or endpoint |
| 409 | `conflict` | The request contradicts current state: stdin already closed, a directory not empty |
| 409 | `busy` | The sandbox already runs as many commands as allowed |
| 413 | `payload_too_large` | A body, file or output beyond its limit |
| 507 | `insufficient_storage` | The disk a file is written to is full, usually the sandbox's home |
| 500 | `internal` | A server fault, logged on the server |
| 501 | `not_implemented` | A capability this server does not have |
| 503 | `capacity_exhausted` | Every session slot is busy; retry after `Retry-After` |
| 503 | `unavailable` | A health check is failing, or the session ended under the request |

## Server

### `GET /v1/health`

No token. Answers `200` when every check passes and `503` when one fails, with the state of each:

```json
{
  "status": "failing",
  "checks": {
    "network": {"status": "ok"},
    "storage": {"status": "failing", "detail": "The host has 1073741824 bytes free, below the 2147483648 reserve"}
  }
}
```

`network` is the host network floor; `storage` is free space for homes.

### `GET /v1/info`

What the server runs, what a new sandbox gets, and every limit. Clients read limits here instead of
hard-coding them.

```json
{
  "version": "0.3.0",
  "protocol": 1,
  "defaults": {
    "image": "ghcr.io/reified-io/regolith-sandbox:0.3.0",
    "resources": {"cpus": 1.0, "memoryMb": 1024, "homeMb": 4096},
    "network": {"mode": "public", "allow": []},
    "lifecycle": {"idleStopSeconds": 900, "maxSessionSeconds": 86400, "retainDays": 30},
    "execTimeoutSeconds": 120
  },
  "limits": {
    "images": ["ghcr.io/reified-io/regolith-sandbox:0.3.0"],
    "maxCpus": 2.0,
    "maxMemoryMb": 4096,
    "maxHomeMb": 16384,
    "maxSessionSeconds": 86400,
    "maxRetainDays": 90,
    "maxExecTimeoutSeconds": 3600,
    "maxFileBytes": 67108864,
    "maxOutputBytes": 8388608,
    "maxLabels": 32,
    "unattendedCpuSeconds": 600
  },
  "publishing": false
}
```

`defaults.image` is what a sandbox that follows the server runs, and `limits.images` is every image
this server offers: a sandbox [pins one of them or tracks one](#which-image-a-sandbox-runs) and can
run nothing else.

`publishing` says whether this server has a [pages role](pages.md). With `false`, `publish` answers
`not_implemented`, and a client can leave publishing out of what it offers instead of trying.

### `GET /llms.txt`

No token. This API condensed for a model, as plain text: what to rely on, every endpoint with an
example body, and the errors worth handling. It ships inside the server, so it describes the build
that serves it.

[`skills/regolith`](../skills/regolith/SKILL.md) is the same for agents that load Agent Skills, with
the rules that save a failed attempt.

## Sandboxes

A **sandbox** is the durable part: an id, a configuration and a home. Its running container is a
**session**. A session starts on first use and ends when idle, at its maximum lifetime, on `stop`,
or when the server restarts. Files in the home survive every session; processes do not.

The **id** is the server's, 32 hex characters, and addresses the sandbox everywhere; an exec and a
release are named the same way. The **alias** is the caller's: the identity the sandbox stands for in
its own world — `user-23`, `ci-run-9913`, `telegram:123456789` — so an application finds its sandbox
again without keeping a table of ids. It is opaque text of up to
200 characters, with no control characters and no space at either end, and no two sandboxes share
one. Nothing public is built from either.

### `POST /v1/sandboxes`

Creates a sandbox and returns it. With an `alias` nothing else holds, it answers `201`; with one that
already has a sandbox it returns that sandbox **unchanged** with `200`, so a caller can send this
before every piece of work. Without an alias it always creates one, and the caller keeps its id.

The body may be empty; whatever it leaves out takes the default from `GET /v1/info`.

```json
{
  "alias": "user-23",
  "imagePolicy": {"mode": "default"},
  "resources": {"cpus": 1.0, "memoryMb": 1024, "homeMb": 4096},
  "network": {"mode": "public"},
  "lifecycle": {"idleStopSeconds": 900, "maxSessionSeconds": 86400, "retainDays": 30},
  "env": {"LANG": "C.UTF-8"},
  "labels": {"team": "research"}
}
```

- That is the whole body: there is no other field, and an unknown one is
  [refused](#requests-and-responses). The values shown are the stock defaults — `GET /v1/info` gives
  the ones the server you are talking to actually uses — except `alias`, `env` and `labels`, which are
  empty unless you set them.
- The body is applied only when the sandbox is created. For an existing one, change settings with
  [`PATCH`](#patch-v1sandboxesid).
- `imagePolicy` picks which image the sandbox runs — see [below](#which-image-a-sandbox-runs).
  `network` is a [network policy](#network-policy). `resources` are fixed for the sandbox's life.
- `lifecycle.retainDays: 0` makes the sandbox ephemeral: the lifecycle sweep deletes it once no
  session runs and `idleStopSeconds` have passed since it was last used — right after an idle stop,
  and up to that window after an explicit `stop`, so one created just before its first command is
  not swept in between. A sandbox with a published site is never swept, whatever its retention.
- `env` applies to every command. Everything in the sandbox can read it, so it is no place for
  secrets. The server adds `REGOLITH_MEMORY_MB`, `REGOLITH_CPUS` and `REGOLITH_HOME_MB`, since `free`
  and `nproc` inside a container report the whole machine rather than the sandbox's own share; an
  `env` of your own with those names wins.
- `labels` are yours to choose; `GET /v1/sandboxes` filters by them.

The response is a `SandboxInfo`:

```json
{
  "id": "5f2b9c7d1e3a4f6b8c0d2e4f6a8b0c1d",
  "alias": "user-23",
  "image": "ghcr.io/reified-io/regolith-sandbox:0.3.0",
  "imagePolicy": {"mode": "default"},
  "resources": {"cpus": 1.0, "memoryMb": 1024, "homeMb": 4096},
  "network": {"mode": "public", "allow": []},
  "lifecycle": {"idleStopSeconds": 900, "maxSessionSeconds": 86400, "retainDays": 30},
  "env": {},
  "labels": {"team": "research"},
  "state": "running",
  "session": {
    "startedAt": "2026-09-13T12:00:00Z",
    "lastActiveAt": "2026-09-13T12:04:10Z",
    "expiresAt": "2026-09-14T12:00:00Z",
    "image": "ghcr.io/reified-io/regolith-sandbox:0.2.1"
  },
  "lastSessionEnd": {"reason": "idle", "at": "2026-09-13T11:40:00Z"},
  "createdAt": "2026-09-01T09:30:00Z",
  "lastUsedAt": "2026-09-13T12:04:10Z",
  "deleteAfter": "2026-10-13T12:04:10Z"
}
```

- `state` is `stopped` or `running`. `session` is present only while there is one.
- `image` is the exact reference the sandbox's next session runs, `session.image` the one the
  running session started on, and `imagePolicy` the rule that picked them. The first two differ, as
  above, when the server has been offered a newer image since that session started.
- `deleteAfter` is when retention deletes the sandbox, unless it is used before then. It is absent
  while the sandbox has a [site](#publishing) up: the address was handed to people who never touch
  the sandbox, so retention leaves both alone until the site is taken down.
- `lastSessionEnd` says how the previous session ended — see below.

### Which image a sandbox runs

A sandbox runs one of the images its server offers, which `GET /v1/info` lists under
`limits.images`; an image outside that list is refused, and a caller cannot bring one of its own.
Which of them it runs is decided by its `imagePolicy`, applied again at the start of every session,
so an upgraded server hands its sandboxes a newer image without them being recreated, and without
touching a home.

```json
{"mode": "default"}
{"mode": "track", "image": "ghcr.io/reified-io/regolith-sandbox-full"}
{"mode": "pin", "image": "ghcr.io/reified-io/regolith-sandbox-full:0.3.0"}
```

- `default` — the server's default image, whichever that is when a session starts. This is what a
  sandbox created without an `imagePolicy` gets, and what most callers want.
- `track` — the image the server offers for one repository, named as an image from `limits.images`
  without its tag or digest. A sandbox that needs the tools of another image, but not a particular
  version of it, tracks that image.
- `pin` — one exact image from `limits.images`, which never moves. For a sandbox whose tools must
  not change under its caller; it stays there even after the server stops offering that image.

A change takes effect at the next session: the running one keeps the image it started on. Nothing in
a home is touched, but tools a sandbox installed for itself can be bound to the version of an
interpreter the image carried — a Python virtual environment, a native npm module — so an agent that
keeps such a thing in its home should be ready to build it again.

### How the last session ended

| `lastSessionEnd.reason` | Meaning |
|---|---|
| `stopped` | `stop` was called |
| `idle` | No command or transfer for `idleStopSeconds` |
| `session_expired` | The session reached `maxSessionSeconds` |
| `capacity` | Reclaimed while idle, to start another sandbox's session |
| `cpu_limit` | Processes left behind by earlier commands burned more than `unattendedCpuSeconds` of CPU while no command ran |
| `policy_failed` | The network floor could not be restored |
| `container_exited` | Its container exited on its own: a command ended the idle process that keeps a session alive, such as with `pkill sleep` or `kill -9 -1` |
| `unresponsive` | Nothing more could be started in it, typically because processes left behind filled its process limit |

The server keeps this in memory, so after a restart `lastSessionEnd` is absent until a session of
that sandbox ends again.

### `GET /v1/sandboxes/{id}`, `GET /v1/sandboxes`

One sandbox, or a page of them ordered by id. The list takes:

- `alias=user-23` — the sandbox filed under that alias, alone, or an empty page when there is none;
- `label=key=value` — repeatable; all must match;
- `limit` — 1–500, default 100;
- `cursor` — the previous page's `nextCursor`.

It answers `{"sandboxes": [SandboxInfo, ...], "nextCursor": "..."}`, with no `nextCursor` on the
last page.

### `PATCH /v1/sandboxes/{id}`

Changes `alias`, `imagePolicy`, `network`, `lifecycle`, `env` or `labels`; fields left out stay as
they are. An alias another sandbox holds is refused, and moving one here leaves that sandbox with no
alias to be found by.

```json
{"network": {"mode": "none"}}
{"imagePolicy": {"mode": "pin", "image": "ghcr.io/reified-io/regolith-sandbox:0.3.0"}}
{"imagePolicy": {"mode": "default"}}
```

A network policy applies to the running session immediately — install dependencies with `public`,
then switch to `none` before running untrusted code. Everything else applies from the next session,
so pinning a sandbox that is running takes hold once its session ends; `stop` makes that now.

### `POST /v1/sandboxes/{id}/start`, `POST /v1/sandboxes/{id}/stop`

`start` warms a session ahead of the first command; any operation would start one anyway. `stop`
ends the session and interrupts its commands; the home stays. Both return `SandboxInfo`.

### `DELETE /v1/sandboxes/{id}`

Deletes the sandbox, its home, its exec records and the site it published, and answers `204`. There
is no undo, and the site's address is never handed out again.

## Network policy

A sandbox's `network`, set when it is created and changed with `PATCH`:

```json
{"mode": "public"}
{"mode": "none"}
{"mode": "allowlist", "allow": [{"cidr": "140.82.112.0/20"}, {"cidr": "1.1.1.1"}]}
```

- `public` — the internet, resolving names through Docker's resolver.
- `allowlist` — only the listed networks. Names still resolve through Docker's resolver, which is a
  channel out, as with any address allowlist.
- `none` — detached from every network: loopback only, no DNS.

Under every mode sits the same floor. Private, shared, loopback, link-local (cloud metadata),
multicast and reserved IPv4 space, the host itself and outbound SMTP are unreachable, and IPv6 is
off. An allowlist entry that lies entirely inside that space is refused.

Domain rules are not supported — [security](sandbox-security.md#why-addresses-and-not-domains)
explains why.

## Execs

An **exec** is one command in a sandbox. It runs in the sandbox's session, starting one if needed,
and its output is recorded on the server whatever the client does.

### `POST /v1/sandboxes/{id}/execs`

Starts a command and returns at once, with `201` and an `ExecInfo`. Give either `shell` or `argv`:

```json
{"shell": "npm test", "cwd": "app", "env": {"CI": "1"}, "timeoutSeconds": 600}
{"argv": ["python3", "main.py"], "stdin": true}
```

- `shell` is a script for the sandbox shell; `argv` is a program and its arguments, run without a
  shell.
- A relative `cwd` resolves against the home, which is also the default.
- `timeoutSeconds` defaults to `execTimeoutSeconds` from `GET /v1/info`.
- Without `stdin: true` the command reads empty input, so it cannot hang waiting for it.
- An `Idempotency-Key: <1–128 characters>` header makes the start safe to retry: a repeat with the
  same key returns the exec the first attempt started instead of running the command again.

The response is an `ExecInfo`:

```json
{
  "id": "5b0c3b2e9f7d4c1a8e6f0a1b2c3d4e5f",
  "shell": "npm test",
  "cwd": "/home/sandbox/app",
  "status": "finished",
  "outcome": {"type": "exited", "exitCode": 1},
  "startedAt": "2026-09-13T12:00:00Z",
  "finishedAt": "2026-09-13T12:01:12Z",
  "outputEnd": 48213,
  "outputTruncated": false,
  "stdinOpen": false
}
```

- `status` is `running` or `finished`; `outcome` and `finishedAt` appear once it has finished.
- `outputEnd` is the offset just past the output recorded so far.

### How an exec ends

An exec ends in exactly one `outcome`:

| `outcome.type` | Meaning |
|---|---|
| `exited` | The command exited on its own; `exitCode` holds the code |
| `timed_out` | It ran past `timeoutSeconds` and was stopped |
| `cancelled` | `cancel` stopped it |
| `interrupted` | Its session ended under it; `reason` is `stopped`, `session_expired`, `sandbox_deleted`, `server_restarted`, `policy_failed`, `container_exited` or `unresponsive` |

A non-zero exit can carry a `reason` too, when a session limit explains it:

```json
{"type": "exited", "exitCode": 137, "reason": "oom_killed"}
```

| `reason` | Meaning |
|---|---|
| `oom_killed` | The kernel killed a process for exceeding the session's memory limit while the command ran; typically `exitCode` 137 |
| `pids_limited` | The session hit its process limit while the command ran, so a fork failed |

The server compares the session's cgroup event counters at the command's start and end. Commands
running at the same time share those counters, so a kill caused by one can be reported on another
that failed in the same window.

### `GET /v1/sandboxes/{id}/execs`, `GET /v1/sandboxes/{id}/execs/{exec}`

The list answers `{"execs": [ExecInfo, ...]}`, newest first; the server keeps the last 50 finished
execs per sandbox.

A single exec takes `waitSeconds`, up to 30: the request is held until the exec finishes or the wait
ends.

### `GET /v1/sandboxes/{id}/execs/{exec}/output`

Recorded output, from `offset` (default 0). Output is kept on the server whatever the client does,
so a client that lost its connection resumes from the last offset it saw. It comes in two forms.

**As JSON**, the default. `waitSeconds` (up to 30) holds the request until new output or the end
arrives; without it the page holds what is recorded now, which may be nothing. `maxBytes` bounds the
page's text, though a page always carries at least one frame when there is one, so a reader can move
past a frame larger than its bound.

```json
{
  "frames": [
    {"kind": "stdout", "text": "compiling...\n", "end": 20},
    {"kind": "stderr", "text": "warning: unused variable\n", "end": 51}
  ],
  "nextOffset": 51,
  "complete": false,
  "exec": {"id": "5b0c3b2e9f7d4c1a8e6f0a1b2c3d4e5f", "status": "running", "outputEnd": 51, "...": "..."}
}
```

Ask again with `offset` set to `nextOffset` until `complete` is `true`: the exec has finished and
nothing more will appear.

`exec` is the exec as the server saw it while it built the page — the same document
[`GET .../execs/{exec}`](#get-v1sandboxesidexecsexec) returns. A client following a command reads its
status, its outcome and its exit code from the page it is already asking for, rather than a second
request after every one.

**As a stream**, with `Accept: text/event-stream`. Each frame is one event, whose `id` is the offset
to resume from, and an `end` event closes the stream with the finished `ExecInfo`:

```
event: stdout
id: 20
data: {"kind":"stdout","text":"compiling...\n","end":20}

event: end
id: 51
data: {"id":"5b0c3b2e9f7d4c1a8e6f0a1b2c3d4e5f","status":"finished",...}
```

Reconnecting with `Last-Event-ID` resumes after that event.

Either way:

- Frames of one stream keep their order. Stdout and stderr are separate pipes, so the two interleave
  in the order the server read them, which can differ slightly from the order they were written.
- Output beyond the server's cap (`maxOutputBytes`) is dropped from the middle. The log keeps its
  start and, once the exec finishes, a `gap` frame with `droppedBytes` followed by the last part.
  `outputTruncated` is true from the moment that happens.
- Text is UTF-8; malformed bytes arrive as U+FFFD.

### `POST /v1/sandboxes/{id}/execs/{exec}/stdin`

Raw bytes for the command's stdin, up to 1 MiB per request; `?close=true` closes stdin after
writing. Answers `204`, or `409 conflict` when the exec was started without stdin, has finished, or
its stdin is already closed.

### `POST /v1/sandboxes/{id}/execs/{exec}/cancel`

Sends SIGTERM to every process the exec started, detached ones included, then SIGKILL after five
seconds. Returns at once with `ExecInfo`; cancelling a finished exec changes nothing.

A process that cleared its own environment escapes the signal. `stop` ends everything.

The signal is delivered by a process started inside the session, so when not even that can start —
the session's process limit is full — cancel stops the session instead, as a timeout does. The exec
still ends `cancelled` or `timed_out`; any other command in that session ends `interrupted` with
`unresponsive`.

A session the server can no longer reach is not kept. When its container has exited, the first command
that runs into it ends `interrupted` with `container_exited`, or the lifecycle sweep notices within
15 seconds; when nothing can be started in it with no other command running, the command ends
`interrupted` with `unresponsive`. Either way the next use starts a fresh session on the same home.

## Files

File operations run inside the session, as the sandbox user, and reach exactly what a command could.
Paths are absolute or relative to the home. Like any other use, a file operation starts a session.

| Request | Does |
|---|---|
| `GET /v1/sandboxes/{id}/files/content?path=&maxBytes=` | The file's bytes, as `application/octet-stream` |
| `PUT /v1/sandboxes/{id}/files/content?path=` | Replaces the file atomically with the body, creating parent directories; returns a `FileEntry` |
| `GET /v1/sandboxes/{id}/files/entries?path=` | `{"path": ..., "entries": [FileEntry, ...]}` for a directory |
| `GET /v1/sandboxes/{id}/files/stat?path=` | One `FileEntry`; a symlink is described, not followed |
| `DELETE /v1/sandboxes/{id}/files?path=&recursive=true` | Deletes a file, an empty directory, or with `recursive` a whole tree; `204` |

A `FileEntry`:

```json
{
  "path": "/home/sandbox/notes/today.txt",
  "name": "today.txt",
  "type": "file",
  "size": 17,
  "modifiedAt": "2026-09-13T12:00:00Z",
  "mode": 420
}
```

`mode` is the permission bits as a decimal number: 420 is `0644`.

Files are capped at `maxFileBytes`. A read may set a lower bound of its own with `maxBytes`: a file
larger than that is refused with `payload_too_large` before any of it is sent, just as one past
`maxFileBytes` is. A file that grows past the bound while it is sent ends the response early. A file
the sandbox user cannot open is refused with `invalid_request` the same way, before the response
starts. The home itself cannot be replaced or deleted — delete the sandbox for that.

A write lands only once its whole body has arrived: a failed upload leaves the file as it was. A home
is a fixed-size disk, and a write it has no room for is refused with `insufficient_storage`; deleting
files makes room, while a larger `payload_too_large` limit would not.

## Publishing

A sandbox can put a directory on the web through the server's [pages role](pages.md). Without one,
these endpoints answer `501 not_implemented`, and `GET /v1/info` says `"publishing": false`.

### `POST /v1/sandboxes/{id}/site`

Publishes a directory of the sandbox and returns its public address.

```json
{"path": "dist"}
```

`path` is a directory inside the sandbox, relative to the home unless it is absolute. The response is
a `PublishedSite`:

```json
{
  "url": "https://k7m2q9xwtp.sites.example.com",
  "release": "0f1e2d3c4b5a69788796a5b4c3d2e1f0",
  "files": 12,
  "bytes": 48213,
  "publishedAt": "2026-09-13T12:00:00Z",
  "hasIndex": true
}
```

`hasIndex` says whether the directory held an `index.html` at its top, the page the address opens.
A directory without one publishes fine and its link then shows nothing, which is the one mistake
the other numbers cannot reveal: publish the directory that holds the page, not the one above it.

The address is the server's to choose, and it is random on purpose: it is public, so an address built
from the sandbox or from a caller's own identity would hand that identity to everyone with the link
and make every other site on the server guessable from one. It belongs to this sandbox until the site
is taken down, so publishing again keeps the link alive and nobody has to record it.

What goes out is a **snapshot taken now**, not the home. The sandbox keeps changing, its session
stops, and the site stays exactly as it was published.

- Publishing again replaces the site atomically; earlier releases are kept on the pages side for
  rollback.
- The pages role's caps — files, file size, total size — are checked before anything is copied out
  of the sandbox, and again while it is: a directory that grows under the copy is refused the moment
  it passes a cap. A directory whose files change while they are copied is refused with `conflict`;
  publish once the build has finished.
- The files are read as the sandbox user, so the snapshot holds exactly what a command could read.
  A file or directory whose name starts with a dot fails the whole publish with `invalid_request`.
  Symlinks are left behind, not followed.
- A sandbox has one site, and a site belongs to one sandbox: there is no name to collide over, and
  nothing can replace a site it did not publish.

### `GET /v1/sandboxes/{id}/site`, `DELETE /v1/sandboxes/{id}/site`

What this sandbox has published, and taking it down. Both answer `404 not_found` when it has nothing.
`DELETE` answers `204` and leaves the sandbox and its files untouched; what was served is gone, and
publishing again gives the sandbox a new address.

- While the site is up, retention leaves the sandbox alone, home included: only a takedown or
  deleting the sandbox ends a site.
## Not in v1

Left out on purpose, each with room in the protocol: domain rules and credential brokering in
network policy, a start command with a readiness probe, filesystem snapshots and clones, preview
URLs for ports, an archived state for idle homes, lifecycle webhooks, PTY over WebSocket, and an MCP
adapter. [Architecture](architecture.md#what-v1-leaves-out) sketches how each would fit.
