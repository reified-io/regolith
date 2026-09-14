# Configuration

The server is configured with `REGOLITH_*` environment variables, read once at startup; an invalid
value stops it before it listens. With [`compose.yaml`](../compose.yaml) they live in `.env` beside
it — start from [`.env.example`](../.env.example).

A pair such as `REGOLITH_CPUS` / `REGOLITH_MAX_CPUS` gives the default a sandbox or command gets and
the ceiling it may ask for.

The `pages` role has settings of its own, `REGOLITH_PAGES_*`, in the `.env` of its own deployment;
they are listed in [the pages guide](pages.md). The two roles never share a file.

## Required

| Variable | Meaning |
|---|---|
| `REGOLITH_TOKEN` | The API token, at least 32 characters; clients send it as `Authorization: Bearer` |
| `REGOLITH_TOKEN_FILE` | A file holding the token, instead of `REGOLITH_TOKEN`; set one, not both |

There is no unauthenticated mode. `openssl rand -hex 32` makes a token.

## Compose

These are read by Compose, not by the server.

| Variable | Default | Meaning |
|---|---|---|
| `REGOLITH_VERSION` | — | The server image tag; required |
| `REGOLITH_REGISTRY` | `ghcr.io/reified-io` | Where the images come from |
| `REGOLITH_PUBLISH` | `127.0.0.1:8080` | The host address the API is published on |

Docker's published ports bypass host firewalls such as ufw. Publish on a specific address, never
`0.0.0.0`, unless something in front of the server filters it.

## Server

| Variable | Default | Meaning |
|---|---|---|
| `REGOLITH_BIND` | `0.0.0.0` | Address the API listens on inside the container |
| `REGOLITH_PORT` | `8080` | Port the API listens on |
| `REGOLITH_STATE_DIR` | `/var/lib/regolith` | Sandbox and exec records and output logs; one server per directory |
| `REGOLITH_NAMESPACE` | `regolith` | Prefix of every container, network and label this server owns; fixed for a state directory |
| `REGOLITH_DOCKER` | `docker` | The Docker-compatible CLI to drive |
| `REGOLITH_SHELL` | `/bin/bash` | Shell that runs `shell` commands, called as `<shell> -c <script>` |
| `REGOLITH_HELPER_IMAGE` | the server's own image | Image of the firewall and home disk helpers. Unset, the server finds it by inspecting the container it runs in; set it when the server runs outside a container |

## Host

How much of the host sandboxes may take, and what else on its network they must never reach.

| Variable | Default | Meaning |
|---|---|---|
| `REGOLITH_MAX_SESSIONS` | `2` | Sessions running at once. When the pool is full, the least recently active idle session is stopped; when none is idle, a new one is refused |
| `REGOLITH_MIN_FREE_MB` | `2048` | Host disk space kept free. A home is created only if this much remains afterwards, and below it new sessions and uploads are refused |
| `REGOLITH_HOME_READ_BPS` | `100mb` | Read rate per home device, as a Docker byte rate, or `none` |
| `REGOLITH_HOME_WRITE_BPS` | `50mb` | Write rate per home device, the same way |
| `REGOLITH_BLOCKED_CIDRS` | — | Extra IPv4 addresses or networks no sandbox may reach, comma-separated — typically the public address a router forwards back to this host |

## Sandbox defaults and ceilings

A sandbox takes the default for anything its creator leaves out and can never exceed a ceiling. Both
are advertised by `GET /v1/info`.

| Variable | Default | Meaning |
|---|---|---|
| `REGOLITH_IMAGE` | `ghcr.io/reified-io/regolith-sandbox:<server version>` | Default sandbox image |
| `REGOLITH_ALLOWED_IMAGES` | — | Further images a sandbox may ask for, comma-separated |
| `REGOLITH_NETWORK` | `public` | Default network mode: `public` or `none` |
| `REGOLITH_CPUS` / `REGOLITH_MAX_CPUS` | `1` / `2` | CPUs per session |
| `REGOLITH_MEMORY_MB` / `REGOLITH_MAX_MEMORY_MB` | `1024` / `4096` | Memory per session, with no swap |
| `REGOLITH_HOME_MB` / `REGOLITH_MAX_HOME_MB` | `4096` / `16384` | Home size, fixed when a sandbox is created |

Every image must name a tag other than `latest`, or a digest: an image that changes under a running
server changes every sandbox with it.

### Sandbox images

Two images are built from [`images/sandbox`](../images/sandbox/Dockerfile):

- **`regolith-sandbox`** is small on purpose — shell tools, Python, Node and Git — so an agent
  installs what its task needs into its own home.
- **`regolith-sandbox-full`** adds what nobody can install without root: FFmpeg, ImageMagick,
  Pandoc, SQLite, ripgrep, and a headless Chromium that runs without its own sandbox, because the
  container already is one. Point `REGOLITH_IMAGE` at it for agents that convert media or render
  pages.

## Lifecycle

| Variable | Default | Meaning |
|---|---|---|
| `REGOLITH_IDLE_STOP_SECONDS` | `900` | A session with no running command or transfer stops after this long |
| `REGOLITH_MAX_SESSION_SECONDS` | `86400` | Default and ceiling for a session's lifetime, busy or not |
| `REGOLITH_RETAIN_DAYS` / `REGOLITH_MAX_RETAIN_DAYS` | `30` / `90` | A sandbox unused this long is deleted with its home; `0` makes it ephemeral |
| `REGOLITH_UNATTENDED_CPU_SECONDS` | `600` | CPU seconds a session may burn while none of its commands runs — a process left behind in the background — before it is stopped. Commands' own CPU never counts |

## Commands and transfers

| Variable | Default | Meaning |
|---|---|---|
| `REGOLITH_EXEC_TIMEOUT_SECONDS` / `REGOLITH_MAX_EXEC_TIMEOUT_SECONDS` | `120` / `3600` | Command timeout |
| `REGOLITH_MAX_EXECS_PER_SANDBOX` | `8` | Commands running at once in one sandbox |
| `REGOLITH_MAX_FILE_MB` | `64` | Largest file read or written through the API |
| `REGOLITH_MAX_OUTPUT_MB` | `8` | Output kept per exec; beyond it, the middle is dropped |

## Publishing

| Variable | Default | Meaning |
|---|---|---|
| `REGOLITH_PAGES_URL` | — | Address of the pages role's intake listener; unset, `publish` answers `not_implemented` |
| `REGOLITH_PAGES_TOKEN` | — | The intake token: the value that role was started with |
| `REGOLITH_PAGES_TOKEN_FILE` | — | A file holding it, instead of `REGOLITH_PAGES_TOKEN` |

Setting up the other end is in [pages](pages.md#setting-it-up).
