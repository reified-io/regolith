# Pages

The public half of Regolith. An agent builds something in a sandbox, the control plane takes a
snapshot of it, and this role serves it at `<site>.<domain>` — immutable, replaced atomically, and
reachable by anyone with the link.

It is a **deployment of its own**, normally on a machine with a public address, running the same
image as the control plane: `regolith pages` instead of `regolith serve`. It holds no Docker socket
and runs nobody's code. Visitors can only read; releases come in through a separate listener that
takes a token.

## Setting it up

The role needs a domain of its own, a wildcard DNS record for it — `*.sites.example.com` — pointing at
the machine that serves the sites, and a token, the same value the control plane publishes with:

```dotenv
REGOLITH_PAGES_DOMAIN=sites.example.com
REGOLITH_PAGES_TOKEN=<openssl rand -hex 32>
```

> **Use a domain that carries nothing else.** Published sites are other people's content, and a
> browser treats every name under one registrable domain as one site. An API, a dashboard or a login
> page under that domain shares cookies and trust with whatever a model just published. Put those on
> a different domain — not on a different label of this one.

How visitors reach it is under [TLS](#tls). What is left is how the control plane reaches the intake,
the listener that takes releases, and there are three ways. Each is complete on its own: pick the one
that fits the machines you have.

### On the same machine as the control plane

Put `compose.pages.yaml` beside the control plane's `compose.yaml` and let Compose run both as one
project from one `.env`, holding the control plane's settings and these:

```dotenv
COMPOSE_FILE=compose.yaml:compose.pages.yaml
COMPOSE_PROJECT_NAME=regolith

REGOLITH_PAGES_DOMAIN=sites.example.com
REGOLITH_PAGES_TOKEN=<openssl rand -hex 32>
REGOLITH_PAGES_API_BIND=0.0.0.0
REGOLITH_PAGES_URL=http://pages:8082
```

`docker compose up -d` starts both. The control plane reaches the intake by its service name, on the
project's own network, so nothing is published for it, and both roles read the one token. Sandboxes
never reach it: they run on a network of their own, where private addresses are refused. Leave
`JAVA_OPTS` unset here — both containers would take it, and the flags for a small machine starve the
control plane.

### Over the internet, with HTTPS and the token

On the machine that serves the sites, take `compose.pages.yaml` and `.env.pages.example` as `.env`,
and give the intake TLS of its own:

```dotenv
REGOLITH_PAGES_API_BIND=0.0.0.0
REGOLITH_PAGES_API_PUBLISH=0.0.0.0:8443
REGOLITH_PAGES_API_TLS_CERT=/etc/regolith-pages/certs/pages.pem
REGOLITH_PAGES_API_TLS_KEY=/etc/regolith-pages/certs/pages.key
```

and tell the control plane, in its own `.env`:

```dotenv
REGOLITH_PAGES_URL=https://pages.example.com:8443
REGOLITH_PAGES_TOKEN=<the same token>
```

The certificate has to be one the control plane trusts for that name: from a public certificate
authority, or a CDN's own at its edge. The intake keeps a port of its own because the two listeners
never share one, and the public listener has 443. For an address without a port, put a reverse proxy
or a CDN in front that sends `pages.example.com` to it. The intake takes a bearer token and never a
cookie, so any name will do — the domain note above is about what a browser carries.

### Over a private network or a tunnel

When the two machines share a private network — a provider's, or a tunnel such as WireGuard — the
intake needs no TLS. Publish it on the private address, where only the control plane reaches it:

```dotenv
REGOLITH_PAGES_API_BIND=0.0.0.0
REGOLITH_PAGES_API_PUBLISH=10.0.0.5:8082
```

```dotenv
REGOLITH_PAGES_URL=http://10.0.0.5:8082
REGOLITH_PAGES_TOKEN=<the same token>
```

A tunnel's address exists only once the tunnel is up, and a container started before it cannot
publish there, so start Docker after the tunnel. A tunnel also carries more than this port: let the
control plane's end reach the intake and nothing else of the machine or of the other peers.

### Starting it and checking it

On its own machine, `docker compose -f compose.pages.yaml up -d` starts the role; on the control
plane's, `docker compose up -d` starts both. Then, from the control plane,
`docker compose run --rm regolith doctor` says whether the intake answers, trusts the certificate and
takes the token.

## Two listeners

| Listener | Default | Who reaches it | What it does |
|---|---|---|---|
| public | `0.0.0.0:8081` | visitors, normally through a CDN or a tunnel | reads a release and answers with a file |
| intake | `127.0.0.1:8082` | the control plane, with a token | takes releases and activates them |

They are separate so that publishing has **no public endpoint at all** unless an operator gives it
one. `REGOLITH_PAGES_BIND` and `REGOLITH_PAGES_PORT` move the public listener,
`REGOLITH_PAGES_API_BIND` and `REGOLITH_PAGES_API_PORT` the intake.

## TLS

Each listener either terminates TLS itself or speaks plain HTTP. The public one does it for visitors;
the intake does it only when it is reached [over the internet](#over-the-internet-with-https-and-the-token).

### Terminating it here

The role takes PEM files, the form a certificate authority hands out:

| Setting | Meaning |
|---|---|
| `REGOLITH_PAGES_TLS_CERT` | The certificate chain, leaf first |
| `REGOLITH_PAGES_TLS_KEY` | Its unencrypted PKCS#8 key (`BEGIN PRIVATE KEY`); a PKCS#1 key is refused with the command that converts it |
| `REGOLITH_PAGES_TLS_CLIENT_CA` | Optional: the CA whose client certificates are **required** |

The intake takes the same three as `REGOLITH_PAGES_API_TLS_CERT`, `REGOLITH_PAGES_API_TLS_KEY` and
`REGOLITH_PAGES_API_TLS_CLIENT_CA`, independently of the public listener: a certificate for the
intake's own name, and a client CA only when a CDN sits in front of the intake as well.

The files are read before anything listens. The role refuses to start on a key that belongs to
another certificate, or on a certificate that has expired. No keystore is written to disk and no
password is chosen: both exist only in memory. TLS 1.2 and 1.3 only.

Behind a CDN that pulls from this origin, that is the whole setup, and nothing needs renewing: the
CDN's origin certificate is long-lived. Give its origin-pull CA as `REGOLITH_PAGES_TLS_CLIENT_CA`,
and a visitor who finds the machine's own address is refused at the handshake instead of reaching a
site around the CDN.

### Plain HTTP

Without those settings the role speaks plain HTTP — for use behind a tunnel, or a reverse proxy that
obtains public certificates itself. The role never runs an ACME client.

## How a request is answered

The label in front of the configured domain names the site. Anything else — the apex, an unknown
label, a host outside the domain — gets a plain `404`.

- `/` and any path ending in `/` mean that directory's `index.html`.
- `/about` falls back to `about/index.html`, so clean URLs work without a build step.
- A path no release holds gets the site's own `404.html` when it ships one, otherwise a plain `404`.
- The content type comes from the file's extension, never from what the publisher claimed.
- A file comes with its hash as `ETag` and `Cache-Control: public, max-age=60`; a request that
  repeats the `ETag` gets `304` without the bytes. A `404` is never cached.

Every answer, a `404` or an error included, carries `X-Content-Type-Options: nosniff`,
`Referrer-Policy: no-referrer` and a `Content-Security-Policy` that by default refuses framing and
cross-origin form posts (`REGOLITH_PAGES_CSP`). No response ever sets a cookie.

There is nothing dynamic: no functions, no redirects file, no server-side includes. A site is the
files it published.

## What a site may hold

| Setting | Default | Meaning |
|---|---|---|
| `REGOLITH_PAGES_MAX_FILES` | `2000` | Files in one release |
| `REGOLITH_PAGES_MAX_FILE_MB` | `25` | One file |
| `REGOLITH_PAGES_MAX_SITE_MB` | `256` | Everything one release serves |
| `REGOLITH_PAGES_RELEASES_KEPT` | `5` | Releases kept per site, for rollback |
| `REGOLITH_PAGES_MAX_SITES` | `200` | Sites on this server |
| `REGOLITH_PAGES_RESERVED` | — | Extra labels nobody may publish, comma-separated, on top of `www`, `api`, `mail`, `preview` and the rest |

Every path is checked before a byte is stored. It must stay inside the site, be at most 12 levels
deep, and have no segment that starts with a dot — `.git` and `.env` reach a published tree by
accident far more often than anything needs them.

## Publishing from a sandbox

With the control plane pointed at this role, one call publishes:
`POST /v1/sandboxes/{id}/site` with `{"path": "dist"}` ([API](api.md#publishing)).

1. The control plane lists the directory inside the sandbox and refuses anything past
   [the caps](#what-a-site-may-hold) **before** copying a byte.
2. It streams the directory out of the session as a tar archive that `tar` writes inside it, as the
   sandbox user, and unpacks it on its own disk under the same caps while it arrives: a directory
   that grew since the listing is cut off the moment it passes one. Symlinks are left behind.
3. It hashes every file and sends only the ones this role does not already hold.
4. It activates the release.

The sandbox is never told any of this happened. It holds no token and opens no connection; its
files are read from the outside like any other file operation, as the sandbox user.

A site name is a DNS label, and the control plane sends a random one it made for that sandbox: this
role never learns whose sandbox published, and an address tells a visitor nothing about it.

## Releases

A release is immutable and addressed by content. Under `REGOLITH_PAGES_DIR`:

```
blobs/<first two hex>/<sha-256>
sites/<site>/releases/<release>.json
sites/<site>/current
```

- Publishing the same file twice stores one blob, so changing one page of a site re-uploads one
  page.
- Activation writes `current` through an atomic rename: a visitor sees the old release or the new
  one, never a mixture.
- Rolling back is activating an earlier release that is still kept.
- Blobs no release names any more are swept up after every publish and every takedown.

## The intake API

Everything but `/v1/health` needs `Authorization: Bearer <token>`. Errors are RFC 9457 problem
documents with the same `code` members as the [control plane's API](api.md#errors). The Kotlin types
are in [`protocol.pages`](../protocol/src/main/kotlin/io/reified/regolith/protocol/pages).

| Request | Does |
|---|---|
| `GET /v1/limits` | What a site may hold: files, file size, total size, releases kept, sites. A publisher checks a release against them before uploading anything |
| `POST /v1/sites/{site}/releases` | Starts a release. The body lists every file as `{"path","hash","size"}`; the answer is `{"release","missing"}`, where `missing` holds only the hashes this server does not have yet |
| `PUT /v1/blobs/{hash}` | Stores one file's bytes, hashed as they arrive and refused if they do not match the name |
| `POST /v1/sites/{site}/releases/{release}/activate` | Makes the release the one visitors see; refused while any of its files is missing |
| `GET /v1/sites`, `GET /v1/sites/{site}` | What is published: release, files, bytes, URL |
| `DELETE /v1/sites/{site}` | Takes the site down and forgets its releases |
| `GET /v1/health` | Health; no token |

## Other settings

The TLS, listener and limit settings are in their sections above. The rest:

| Setting | Default | Meaning |
|---|---|---|
| `REGOLITH_PAGES_DOMAIN` | — | Required: the domain sites are served under, as `<site>.<domain>` |
| `REGOLITH_PAGES_TOKEN` | — | Required, at least 32 characters: the intake token, shared with the control plane |
| `REGOLITH_PAGES_TOKEN_FILE` | — | A file holding the token, instead of `REGOLITH_PAGES_TOKEN` |
| `REGOLITH_PAGES_SCHEME` | `https` | Scheme of the site addresses publishing returns: `https` or `http` |
| `REGOLITH_PAGES_CSP` | `frame-ancestors 'none'; form-action 'self'` | The `Content-Security-Policy` of every answer |
| `REGOLITH_PAGES_DIR` | `/var/lib/regolith-pages` | Where releases and blobs are stored |

Compose reads a few of its own:

| Variable | Default | Meaning |
|---|---|---|
| `REGOLITH_VERSION` | — | The image tag; required |
| `REGOLITH_REGISTRY` | `ghcr.io/reified-io` | Where the image comes from |
| `REGOLITH_PAGES_PUBLISH` | `127.0.0.1:8081` | Host address of the public listener; `0.0.0.0:443` when the role terminates TLS itself |
| `REGOLITH_PAGES_API_PUBLISH` | `127.0.0.1:8082` | Host address of the intake listener: a private address, or `0.0.0.0:8443` with TLS of its own |
| `REGOLITH_PAGES_CERT_DIR` | `./certs` | Directory mounted read-only at `/etc/regolith-pages/certs`, for the TLS files |

On a small machine, `JAVA_OPTS` caps the JVM, which otherwise sizes its heap from the host's memory;
`.env.pages.example` has a set of flags that keeps the role at about 140 MB. Not on a machine that
runs the control plane from the same `.env`, which would take them too.
