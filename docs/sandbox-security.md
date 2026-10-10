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
| Unattended CPU | A process left running after its command ended is stopped with its session once it has burned `REGOLITH_UNATTENDED_CPU_SECONDS` while nothing else ran. Leftovers that fill the process limit hide the counter, so an idle session unreadable on two ticks running is stopped as well |
| Neighbors | A bridge with inter-container traffic disabled |
| Network | The host floor and the sandbox's policy — [below](#the-network-floor) |
| Home size | A fixed-size filesystem per sandbox — [below](#homes) |
| Host facts | Nothing about the server — configuration, token, addresses, the host's own resolvers and search domain — in a sandbox's environment, mounts, command lines or `resolv.conf` |

The container command lines are built in one place, `ContainerSpec`, and `ContainerSpecTest` asserts
the hardening flags.

## Caller input

- **One token.** Every request but health and `/llms.txt` is authenticated with a bearer token,
  compared in constant time. There is no unauthenticated mode, and the SDK never follows a redirect,
  so the token cannot be relayed to another origin.
- **Arguments, never scripts.** Caller input reaches a container only as a process argument.
  Commands run as `<shell> -c <script>` with the script as one argument, and helper scripts receive
  paths and ids as positional parameters. No server-side shell ever interprets caller text.
- **An image from the catalog only.** A caller names an image, it is never taken at its word: the
  name is matched against the images the configuration allows, and a session starts on a reference
  from that list or not at all. A sandbox that follows the server therefore moves only where the
  operator's configuration moves, and one that pinned an image stays on it.
- **Files as the sandbox user.** File operations run inside the sandbox as the sandbox user and
  reach exactly what a command could, so a symlink pointing elsewhere gives nothing a command would
  not already have. A snapshot for publishing is the same: `tar` run as the sandbox user, never the
  daemon's `docker cp`, which reads as root. These helpers, and the ones that read the CPU counter
  and signal a command, run with a `PATH` of the read-only root alone, so a program a sandbox leaves
  in its home cannot stand in for one of them.
- **Bounded reads.** Every read of untrusted bytes is bounded while it happens: request bodies,
  uploads, downloads, command output, helper output and the snapshot a publish copies out. A reader
  past its cap kills the writer or keeps draining. It never simply stops reading, which can deadlock
  the process writing.

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
| `allowlist` | Only what is listed — even `0.0.0.0/0` cannot reopen the floor, which is checked first. With networks alone, names still resolve: the two public resolvers a sandbox is given answer on port 53, so DNS stays a channel out, as in every address-based allowlist. With any domain listed, DNS and ports 80 and 443 go through the [egress proxy](#domain-rules) instead, and only covered names resolve |
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
- **A host it can police.** The server refuses a daemon whose containers leave past the host's
  firewall: Docker Desktop, which keeps them in a VM, a rootless daemon, which routes them through a
  user-space network, and Docker's native nftables backend, which has no `DOCKER-USER` chain. On
  each of them the rules would install, read back clean and hold against private space, while the
  machine's own public addresses stayed open.
- **The right netfilter.** The helper uses whichever iptables backend shows Docker's own rules, and
  refuses to run when neither does: rules written to the other backend land in tables nothing
  traverses.
- **Hooked first.** `FORWARD → DOCKER-USER` and `DOCKER-USER → the namespace chain` must be the
  first rules of their chains, `INPUT → the input chain` the first of INPUT, `OUTPUT → the output
  chain` the first of OUTPUT, and the redirect chain the first of the nat table's PREROUTING; the
  helper moves them back to the top if something else took it. `ufw` and other frontends coexist,
  because these chains reject and drop and accept only one thing: a sandbox with domain rules
  reaching the egress proxy, below.
- **Keyed by source address.** Traffic from the bridge is dispatched by source address to the policy
  of that session, and an address with no policy is rejected. A sandbox cannot change its address:
  it has no capability to.
- **Metered per source.** New connections (100/s, burst 200) and packets (10 000/s, burst 20 000)
  are limited per sandbox address, before any rule that can return early, so one sandbox cannot
  spend the pool's budget.
- **The egress proxy.** A sandbox whose policy names domains has its DNS and its ports 80 and 443
  redirected, by source address, to the [egress proxy](#domain-rules) on the gateway of its bridge.
  The input chain accepts exactly that — that address, the gateway, the proxy's three ports — ahead
  of a host firewall that would drop it by default, and drops everything else a sandbox sends the
  host. The proxy is the server's own program from its own image, run in the host's network
  namespace under a uid of its own with no capability; the output chain puts the floor back on that
  uid, which reaches nothing but the given resolvers on port 53 and other addresses on ports 80 and
  443, and IPv6 not at all.
- **Re-read on a schedule.** The network guard compares a fresh fingerprint — every rule of every
  namespace chain, and the position of every hook — with the one the last apply printed, and checks
  that the egress proxy still runs. Drift is repaired by applying again, and a proxy that went is
  started again with the policy of every live session. A ruleset that cannot be restored stops every
  session and is installed and proven again from the start, on the sandbox network made again if it
  was removed; one that cannot be re-established either latches the `network` health check to
  failing.

### Proof at startup

The server does not take the ruleset on trust. Before it listens:

1. A listener starts on the host's side of the bridge and must be seen answering.
2. The host's default gateways are tried from the host, on ports a router serves — 443, 80 and 53,
   connections only. Each one that answers becomes a second control: a real device on the host's
   private network.
3. A throwaway container on the sandbox network, confined like a session and given a `public`
   policy, tries those controls, the metadata address, private addresses and a resolver it was not
   given. Anything that answers stops the server.
4. Its policy becomes a single domain. The egress proxy must answer a plain request for another
   host, and refuse it; another name must not resolve; a TLS hello for another name sent to an
   allowed address must get nowhere; a port the proxy does not take must stay closed. The allowed
   name must get through when the probe could reach the internet at all; when it cannot, the server
   warns and goes on, since nothing then gets through.
5. The egress proxy's uid, in the host's namespace, tries the control listener and the host's
   network controls, which answered the host: it must reach none of them.
6. Its policy is removed, and it must no longer reach the internet — a resolver included, since an
   allowance for names is the likeliest way through for an address with no policy. That is checked
   only when it could before, because silence without a control proves nothing.

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
- **The egress proxy** listens on the sandbox network's gateway, ports 49532 to 49534, and runs as
  uid 60531 in the host's network namespace. The output chain confines that uid on the whole host,
  so give no host account of your own that uid.
- **Upstream firewalls** — a provider's egress rules, `ufw`'s forward policy — can leave sandboxes
  with no way out. The startup probe logs a warning but does not stop the server, because no way out
  is broken safely.

### Domain rules

A domain in an allowlist is enforced by the egress proxy, not by matching addresses, because the
addresses behind a name change and a shared one serves many names. The proxy closes the gaps a
name-matching firewall usually leaves:

- **Literal addresses and chosen resolvers.** A connection goes to an address the proxy resolved,
  through the sandbox's own public resolvers, for the name the connection opened with; the address
  the sandbox dialed is never used. A connection that names nothing, an address literal included,
  goes nowhere.
- **Plain HTTP.** A request is matched by its `Host`, and refused when it has none, has two, asks
  for a tunnel, or names another host in its target.
- **DNS.** Every query the sandbox sends, to whichever resolver, is answered by the proxy, which
  asks upstream only for a name a rule covers and answers any other as a name that does not exist.
- **Private space.** A covered name that resolves into the floor reaches nothing: the proxy refuses
  the floor's networks, the host's addresses and the sandbox subnet, and the output chain refuses
  them again for its uid in the kernel.

What it does not stop, and does not claim to:

- **What is inside a connection.** Past the TLS hello, or the head of the first request on a plain
  connection, nothing is read. A server that answers many names on one address — a CDN — can be
  asked inside TLS, or in a later request on the same plain connection, for another site it serves;
  that is domain fronting, and only a proxy that terminates TLS would see it.
- **DNS below a wildcard.** `*.example.com` lets a query for any name below it reach that domain's
  name servers, so a name can carry data to whoever runs them. A rule for one name does not.
- **Other ports.** Domain rules cover ports 80 and 443; anything else to a name needs its addresses
  as a `cidr`.
- **Credentials.** A token a sandbox uses is a token it holds; there is no brokering that would let
  it use one it never sees.

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
- **Never shrunk or reformatted**, and never replaced by an unbounded volume. A home grows only when
  its sandbox is given a bigger one, and only between sessions: the helper extends the image before
  attaching it — the added range preallocated like the rest, under the same reserve — and then the
  filesystem, offline. A grow cut short between the two is finished at the next attach. A home larger
  than its sandbox refuses to open.
- **A reserve.** A new home is created only if the host keeps `REGOLITH_MIN_FREE_MB` free
  afterward.
- **Detached when the session ends.** Attachments left by a crash are released at startup.
- **I/O throttled** per home device (`REGOLITH_HOME_READ_BPS`, `REGOLITH_HOME_WRITE_BPS`).
- **Never forgotten.** A home is found by its sandbox's id, and retention only walks records. If the
  state directory is lost or points somewhere else, every home keeps somebody's files where no call
  reaches them and retention never deletes them. So the server refuses to start while a home disk
  exists that no record claims, and says which. `orphans` lists them beside a running server; an
  operator then decides, with the server stopped: `orphans adopt` gives each home a record again
  (label `regolith.adopted=true`), and `orphans delete` deletes them.
- **Nothing it cannot name.** A home disk whose name holds no sandbox id — one from a server older
  than ids, or made by hand — stops startup the same way. No record can claim it and no command here
  adopts or deletes it, since only an operator knows what it held: `orphans` lists it, and it is
  removed with `docker volume rm`.

## Resources outside any cgroup

Some kernel resources are global, and no container setting fences them — asynchronous I/O contexts
(`io_setup`) among them. Blocking them would mean maintaining a private seccomp profile that
silently breaks tooling as system calls are added, so they remain a documented limit of sharing a
kernel rather than a setting. On a dedicated host, nothing else competes for them.
