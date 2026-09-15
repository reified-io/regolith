# Security

What Regolith defends against, what confines a sandbox, and where that confinement ends.

## Threat model

A sandbox runs code nobody reviewed: written by a model, fetched by it, or planted in something it
read. That code may try to reach the host, other sandboxes, the network the host sits on, or the
internet in ways that hurt someone. Regolith's job is that it reaches only its own sandbox and the
network its policy allows, and that it cannot exhaust what other sandboxes need.

Where that job ends:

- **Sandboxes share the host kernel.** A kernel or Docker flaw that escapes a container escapes a
  sandbox too — the limit of every container-based sandbox.
- **The server drives Docker; sandboxes do not.** The server process can do on the host whatever
  Docker can, and none of that reaches a sandbox: no Docker socket, no host mounts, no capabilities.
  The API token gives access to sandboxes and nothing more, so apart from the case above, only a
  flaw in the server itself would reach the host.

A machine of its own keeps either case away from anything else, and is the recommended setup.

For strangers' code, add a stronger boundary: a VM per tenant, each running its own Regolith server.
A gVisor or Kata runtime for sessions is [planned](architecture.md#what-v1-leaves-out), not available
yet.

## What confines a sandbox

| Layer | Setting |
|---|---|
| Identity | uid and gid 1000; neither sandbox image carries a setuid binary — the full one strips Chromium's sandbox helper too |
| Privileges | Every capability dropped, `no-new-privileges`, Docker's default seccomp profile |
| Filesystem | A read-only root and a small writable `/tmp`; the home is the only persistent write |
| Memory | A hard limit with no swap: a runaway is killed, not paged |
| CPU | A CPU quota and a low scheduling weight, so the host keeps a contested core |
| Processes | A pids limit and an open-files limit |
| Unattended CPU | A process left running after its command ended is stopped with its session once it has burned `REGOLITH_UNATTENDED_CPU_SECONDS` while nothing else ran |
| Neighbours | A bridge with inter-container traffic disabled |
| Network | The host floor and the sandbox's policy — [below](#the-network-floor) |
| Home size | A fixed-size filesystem per sandbox — [below](#homes) |
| Host facts | Nothing about the server — configuration, token, addresses — in a sandbox's environment, mounts or command lines |

The container command lines are built in one place, `ContainerSpec`, and `ContainerSpecTest` asserts
the hardening flags.

## Caller input

- **One token.** Every request but health and `/llms.txt` is authenticated with a bearer token,
  compared in constant time. There is no unauthenticated mode, and the SDK never follows a redirect,
  so the token cannot be relayed to another origin.
- **Arguments, never scripts.** Caller input reaches a container only as a process argument.
  Commands run as `<shell> -c <script>` with the script as one argument, and helper scripts receive
  paths and ids as positional parameters. No server-side shell ever interprets caller text.
- **An image from the catalogue only.** A caller names an image, it is never taken at its word: the
  name is matched against the images the configuration allows, and a session starts on a reference
  from that list or not at all. A sandbox that follows the server therefore moves only where the
  operator's configuration moves, and one that pinned an image stays on it.
- **Files as the sandbox user.** File operations run inside the sandbox as the sandbox user and
  reach exactly what a command could, so a symlink pointing elsewhere gives nothing a command would
  not already have.
- **Bounded reads.** Every read of untrusted bytes is bounded while it happens: request bodies,
  uploads, downloads, command output and helper output. A reader past its cap kills the writer or
  keeps draining. It never simply stops reading, which can deadlock the process writing.

## The network floor

In every mode, a sandbox cannot reach:

- private, shared, loopback, link-local, benchmarking, documentation, multicast and reserved IPv4
  space — which covers cloud metadata endpoints (`PlatformFloor` in the domain lists the networks);
- the host's own addresses, discovered at startup, and anything in `REGOLITH_BLOCKED_CIDRS`;
- the host itself on any address, other sandboxes, or the pool's own subnet;
- outbound SMTP;
- anything over IPv6;
- DNS servers other than the public resolvers the sandbox is given.

On top of the floor, each sandbox has a mode:

| Mode | What it means |
|---|---|
| `public` | The rest of the internet. Names resolve through Docker's embedded resolver |
| `allowlist` | Only the listed networks — even `0.0.0.0/0` cannot reopen the floor, which is checked first. Names still resolve through the embedded resolver, which forwards queries from the Docker daemon itself, so DNS stays a channel out, as in every address-based allowlist |
| `none` | The session is detached from every network. Its only interface is loopback, and the embedded resolver is gone with the network: nothing leaves, DNS included |

A policy change applies to a running session at once. Every transition passes through a moment in
which the session's address has no policy, and an address with no policy reaches nothing.

### How the host firewall works

`HostFirewall` keeps the policy of every live session in memory and renders the complete ruleset of
its namespace on every change (`FirewallRules`). A short-lived **firewall helper** swaps the ruleset
in with one `iptables-restore` and prints a fingerprint. The helper is the server's own image, run
with `--network host`, only `NET_ADMIN` and `NET_RAW`, a read-only root and no new privileges.

- **Outside the sandbox.** The rules live in the host's namespace and match the sandbox bridge.
  Nothing inside a sandbox can see or change them, and a sandbox holds no capability at all.
- **The right netfilter.** The helper uses whichever iptables backend shows Docker's own rules, and
  refuses to run when neither does: rules written to the other backend land in tables nothing
  traverses.
- **Hooked first.** `FORWARD → DOCKER-USER` and `DOCKER-USER → the namespace chain` must be the
  first rules of their chains, and `INPUT → the input chain` the first of INPUT; the helper moves
  them back to the top if something else took it. `ufw` and other frontends coexist, because these
  chains only reject and drop, never accept.
- **Keyed by source address.** Traffic from the bridge is dispatched by source address to the policy
  of that session, and an address with no policy is rejected. A sandbox cannot change its address:
  it has no capability to.
- **Metered per source.** New connections (100/s, burst 200) and packets (10 000/s, burst 20 000)
  are limited per sandbox address, before any rule that can return early, so one sandbox cannot
  spend the pool's budget.
- **Re-read on a schedule.** The network guard compares a fresh fingerprint — every rule of every
  namespace chain, and the position of every hook — with the one the last apply printed. Drift is
  repaired by applying again; a ruleset that cannot be restored latches the `network` health check
  to failing and stops every session.

### Proof at startup

The server does not take the ruleset on trust. Before it listens:

1. A listener starts on the host's side of the bridge and must be seen answering.
2. The host's default gateways are tried from the host, on ports a router serves — 443, 80 and 53,
   connections only. Each one that answers becomes a second control: a real device on the host's
   private network.
3. A throwaway container on the sandbox network, confined like a session and given a `public`
   policy, tries those controls, the metadata address, private addresses and a resolver it was not
   given. Anything that answers stops the server.
4. Its policy is removed, and it must no longer reach the internet. That is checked only when it
   could before, because silence without a control proves nothing.

The log line names the network controls that were used. With none, the floor is proven against the
host only.

### Removing the rules

Rules stay in place while the server is stopped; sessions do not. To remove them, run the helper by
hand with the namespace prefix the server prints at startup:

```bash
docker run --rm --network host --cap-add NET_ADMIN --cap-add NET_RAW \
  --entrypoint /opt/regolith/scripts/firewall.sh <server image> remove <prefix>
```

### Operator notes

- **Published ports bypass host firewalls**, so the API is published on a specific address; see
  [configuration](configuration.md#compose).
- **Hairpin NAT.** A host behind a router that forwards a public address back to it is reachable
  through that public address, which is not one of the host's own. Add it to
  `REGOLITH_BLOCKED_CIDRS`.
- **Upstream firewalls** — a provider's egress rules, `ufw`'s forward policy — can leave sandboxes
  with no way out. The startup probe logs a warning but does not stop the server, because no way out
  is broken safely.

### Why addresses and not domains

A domain allowlist enforced by SNI constrains which hostname a TLS connection negotiates, not which
service answers:

- a client can present an allowed name and send a different `Host` (domain fronting);
- plain HTTP carries no SNI;
- literal addresses bypass name matching;
- a resolver reachable at all is a channel for data.

Address rules have none of these gaps. Domain rules will come with an egress proxy that terminates
TLS for the domains it brokers, documented with the same honesty about what it does not stop.

## Homes

Each sandbox's home is a preallocated ext4 image of its own size (`HomeDisks`).

- **Two volumes.** `<ns>-<id>-disk` holds the image. `<ns>-<id>-home` is a local-driver volume of
  the loop device the image is attached to, mounted by the Docker daemon itself when a session
  starts. Both are named by the sandbox's id, never by anything a caller chose. A sandbox never sees
  the image, so it cannot resize or corrupt it.
- **The home disk helper** attaches and detaches loop devices. It is the server's image with only
  `SYS_ADMIN` and `MKNOD`, access to block-major-7 devices, the daemon's `/dev`, that one disk
  volume, and no network. It runs one fixed script and never sees a mounted home.
- **Bounded in the kernel.** A fast writer, `fallocate`, millions of small files or open-but-deleted
  files all stop at the home's own size. Measured: a 400 MB write into a 256 MB home ends with
  `No space left on device`.
- **Formatted for the sandbox user.** The root inode is owned by uid 1000 and `lost+found` is
  removed. The image is preallocated with `nodiscard`, which would otherwise make it sparse again,
  and copy-on-write is disabled on Btrfs.
- **Never resized or reformatted automatically**, and never replaced by an unbounded volume. A home
  whose image size does not match its sandbox refuses to open.
- **A reserve.** A new home is created only if the host keeps `REGOLITH_MIN_FREE_MB` free
  afterwards.
- **Detached when the session ends.** Attachments left by a crash are released at startup.
- **I/O throttled** per home device (`REGOLITH_HOME_READ_BPS`, `REGOLITH_HOME_WRITE_BPS`).
- **Never forgotten.** A home is found by its sandbox's id, and retention only walks records. If the
  state directory is lost or points somewhere else, every home keeps somebody's files where no call
  reaches them and retention never deletes them. So the server refuses to start while a home disk
  exists that no record claims, and says which. An operator decides, with the server stopped:
  `orphans adopt` gives each home a record again (label `regolith.adopted=true`), and
  `orphans delete` deletes them.
- **Nothing it cannot name.** A home disk whose name holds no sandbox id — one from a server older
  than ids, or made by hand — stops startup the same way. No record can claim it and no command here
  adopts or deletes it, since only an operator knows what it held: `orphans` lists it, and it is
  removed with `docker volume rm`.

## Resources outside any cgroup

Some kernel resources are global, and no container setting fences them — asynchronous I/O contexts
(`io_setup`) among them. Blocking them would mean maintaining a private seccomp profile that
silently breaks tooling as system calls are added, so they remain a documented limit of sharing a
kernel rather than a setting. On a dedicated host, nothing else competes for them.
