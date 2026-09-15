---
name: regolith
description: Run commands and keep files in Regolith sandboxes — persistent, named Linux homes on a self-hosted server — over its HTTP API, the Kotlin SDK, or the Koog shell executor. Use when an agent needs somewhere safe to execute code, install tools, build projects or keep a workspace between conversations on a Regolith server.
---

# Regolith

A Regolith server gives each name a Linux sandbox with a persistent home. You create it once, run
commands in it, move files in and out, and find it unchanged next time. The server runs on the user's own
machine; ask the user for its URL and token, conventionally `REGOLITH_URL` and `REGOLITH_TOKEN`.

`GET $REGOLITH_URL/llms.txt` returns the complete endpoint reference in plain text, without a token. Read
it when you need a detail this file leaves out.

## Rules that save you a failed attempt

- **File the sandbox under what it belongs to** — a user, a project, a task: `user-23`,
  `ci-build-17`. That is the sandbox's `alias`, any text you like; the server answers with the `id`
  that addresses it everywhere else, and `GET /v1/sandboxes?alias=user-23` finds it again.
- **Always create first.** `POST /v1/sandboxes` with `{"alias":"user-23"}` creates the sandbox or
  returns the existing one unchanged, so it is safe at the start of every conversation. The rest of the
  body only counts on creation: change `imagePolicy`, `network`, `lifecycle`, `env` or `labels` later
  with `PATCH`; CPUs, memory and home size stay.
- **Keep what matters in `/home/sandbox`.** It is the only thing that survives a session. `/tmp` and
  running processes are gone when the session stops (idle for 15 minutes by default).
- **There is no root.** Commands run as uid 1000. Install tools into the home: a virtual environment
  for Python (`python3 -m venv ~/venv`; there is no system `pip`, the venv has one), `npm install -g`
  (the default image points it at the home), or binaries unpacked into `~/.local/bin`, which is on the
  `PATH`. The image can move to a newer version between sessions unless the sandbox pinned one, so if
  such a tool stops working after a break, rebuild it rather than assuming the home is broken.
- **A tool you cannot install may be in another image.** `GET /v1/info` lists what the server offers
  under `limits.images`. If one of them carries what the task needs (FFmpeg, Chromium, Pandoc), send
  `PATCH {"imagePolicy":{"mode":"track","image":"<that image without its tag>"}}` and then `stop` the
  sandbox: the next session starts on it, and the home is untouched.
- **Nothing runs unattended for long.** A process left in the background after its command ends stops
  with the session once it is idle, or sooner if it keeps using CPU. Keep long work inside a command.
- **Commands get no stdin** unless you start them with `"stdin": true`. An interactive prompt ends instead
  of hanging — pass flags like `-y` instead.
- **Read limits from `GET /v1/info`** (timeouts, file size, memory) instead of guessing. Inside a
  sandbox, `free` and `nproc` report the host: its own share is in `REGOLITH_MEMORY_MB`,
  `REGOLITH_CPUS` and `REGOLITH_HOME_MB`.
- **The network may be closed.** Private addresses and the host are never reachable; with mode `none`
  nothing is, DNS included. A download failing with a name-resolution error in a `none` sandbox is the
  policy, not a bug.
- **Output is kept on the server.** If a connection drops, read the output again from the last `end`
  offset you saw; nothing is lost.
- **A page of output says how the command is doing.** Its `exec` is the exec as of that page, so
  following a command needs no second request for its status between pages.

## Run a command

```bash
BOX=$(curl -s -X POST -H "Authorization: Bearer $REGOLITH_TOKEN" -H 'Content-Type: application/json' \
  -d '{"alias":"user-23"}' "$REGOLITH_URL/v1/sandboxes" | jq -r .id)

EXEC=$(curl -s -X POST -H "Authorization: Bearer $REGOLITH_TOKEN" -H 'Content-Type: application/json' \
  -d '{"shell":"python3 -c \"import platform; print(platform.system())\"","timeoutSeconds":120}' \
  "$REGOLITH_URL/v1/sandboxes/$BOX/execs" | jq -r .id)

# loop on nextOffset until complete; the last page carries the finished exec in `exec`
curl -s -H "Authorization: Bearer $REGOLITH_TOKEN" \
  "$REGOLITH_URL/v1/sandboxes/$BOX/execs/$EXEC/output?waitSeconds=20" | jq '.exec.outcome'
```

Reading an outcome:

| `outcome` | What to do |
|---|---|
| `exited`, `exitCode` 0 | Done |
| `exited` with `reason: oom_killed` | Out of memory, which is fixed per sandbox: process less at once, or create another sandbox with more `memoryMb` |
| `exited` with `reason: pids_limited` | Too many processes: lower parallelism (`make -j2`, fewer workers) |
| `exited`, other codes | Read the output; it is the command's own failure |
| `timed_out` | Raise `timeoutSeconds`, up to `maxExecTimeoutSeconds` in `GET /v1/info`, or split the work |
| `cancelled` | Someone called `cancel`; run it again only if that was not deliberate |
| `interrupted` | The session ended under it — `reason` says why; run it again |

For long work — a build, a server — start the exec and poll it rather than holding one request open.

## Files

```bash
curl -s -X PUT -H "Authorization: Bearer $REGOLITH_TOKEN" --data-binary @report.csv \
  "$REGOLITH_URL/v1/sandboxes/$BOX/files/content?path=data/report.csv"
curl -s -H "Authorization: Bearer $REGOLITH_TOKEN" \
  "$REGOLITH_URL/v1/sandboxes/$BOX/files/content?path=out/result.png" -o result.png
```

Relative paths start at `/home/sandbox`. Writes replace the file atomically and create parent
directories.

## Publishing a page to the web

If `GET /v1/info` reports `"publishing": true`, one call puts a finished directory on the public
internet:

```bash
curl -s -X POST -H "Authorization: Bearer $REGOLITH_TOKEN" -H 'Content-Type: application/json' \
  -d '{"path":"dist"}' "$REGOLITH_URL/v1/sandboxes/$BOX/site"
# {"url":"https://k7m2q9xwtp.sites.example.com","release":"...","files":12,...}
```

- It publishes a **snapshot**: build first, then publish; the site does not change when the sandbox does.
- **Static files only.** Nothing runs there, so publish the output of a build, not a server.
- **No dotfiles.** A file or directory whose name starts with a dot (`.git`, `.env`, `.well-known`) fails
  the whole publish; remove it from the directory first. Symlinks are skipped.
- The address is the server's and it is random: it gives away neither the sandbox nor whoever it
  belongs to. Publishing again keeps it, so hand out the link as often as you like.
- The URL is **public to anyone with the link**. Say so before publishing anything personal.
- `DELETE /v1/sandboxes/{id}/site` takes it down, and the next publish gets a new address;
  `501 not_implemented` means this server publishes nothing.

## Network

`PATCH /v1/sandboxes/{id}` changes the network of a running sandbox at once:

- `{"network":{"mode":"none"}}` cuts it off — use it after installing dependencies and before running
  code you do not trust;
- `{"network":{"mode":"allowlist","allow":[{"cidr":"140.82.112.0/20"}]}}` allows only listed networks;
- `{"network":{"mode":"public"}}` restores the internet.

## Errors

Errors are RFC 9457 problem documents. Branch on `code`, show `detail` to people:

- `capacity_exhausted`, `unavailable` — retry after the `Retry-After` header.
- `busy` — too many commands already run in this sandbox; wait for one.
- `payload_too_large` — a file, body or site beyond the limits; `detail` names it.
- `insufficient_storage` — the home is full: delete what is no longer needed, then write again.
- `invalid_request` — fix what `detail` says; do not retry unchanged.
- `not_found` — the sandbox, exec, file or site does not exist; `detail` says which. For a sandbox,
  create it again with `POST /v1/sandboxes` and the same alias.

## Kotlin

```kotlin
RegolithClient(System.getenv("REGOLITH_URL"), System.getenv("REGOLITH_TOKEN")).use { client ->
    val sandbox = client.getOrCreate("user-23")
    val result = sandbox.run("python3 -c 'import platform; print(platform.system())'")
    println("${result.exitCode}: ${result.stdout}")
}
```

With Koog, give the agent's shell tool `RegolithShellCommandExecutor(client.getOrCreate("user-23"))`: every
command then runs in the sandbox instead of on the machine running the agent.
