<p align="center">
  <img src="https://reified.io/projects/regolith/lockup.svg" width="280" alt="Regolith">
</p>

<p align="center">
  <a href="https://github.com/reified-io/regolith/actions/workflows/build.yml"><img src="https://github.com/reified-io/regolith/actions/workflows/build.yml/badge.svg" alt="build"></a>
  <a href="https://central.sonatype.com/search?namespace=io.reified.regolith"><img src="https://img.shields.io/maven-central/v/io.reified.regolith/sdk?label=maven%20central" alt="Maven Central"></a>
  <a href="https://kotlinlang.org"><img src="https://img.shields.io/badge/dynamic/toml?url=https%3A%2F%2Fraw.githubusercontent.com%2Freified-io%2Fregolith%2Fmain%2Fgradle%2Flibs.versions.toml&query=%24.versions.kotlin&logo=kotlin&label=kotlin&color=blue" alt="Kotlin"></a>
  <a href="https://github.com/orgs/reified-io/packages/container/package/regolith"><img src="https://img.shields.io/badge/ghcr-regolith-blue?logo=docker" alt="GHCR"></a>
  <img src="https://img.shields.io/badge/status-pre--release-orange" alt="Status: pre-release">
</p>

<h1></h1>

Self-hosted sandboxes for AI agents.

Each sandbox is a persistent Linux home behind an HTTP API, where an agent runs commands and keeps
its files. Regolith runs on any Docker host — no KVM, no cloud account — and refuses to serve a
sandbox it cannot confine.

> **Pre-release.** It works end to end; multi-tenancy, domain rules and snapshots are
> [still ahead](docs/architecture.md#what-v1-leaves-out).

## What it does

- **Sandboxes that remember.** Give one your own name for it, and its files are still there next time.
- **Commands you can reconnect to.** Output stays on the server, so a dropped client just resumes.
- **A network boundary that checks itself.** No way to the host or your LAN, in any mode.
- **Cleans up after itself.** Idle sessions stop, and sandboxes nobody uses are deleted, unless
  they have a site up.
- **Upgrades reach existing sandboxes.** A new image takes hold at the next session, unless a
  sandbox pinned the one it has.
- **Failures a model can act on.** `oom_killed` instead of a bare exit code 137.
- **Publishing, if you want it.** What an agent built, on the web at an address that gives nobody away.
- **Made for Kotlin and for agents.** A coroutine SDK, Koog, `/llms.txt` and an
  [Agent Skill](skills/regolith/SKILL.md).

## Quick start

### 1. Run the server

The server runs as one container next to Docker. Sandboxes are locked down on every layer Docker
offers, so what remains is trusting Docker's own isolation; a dedicated machine is recommended.
[Security](docs/sandbox-security.md) has the details.

```bash
mkdir regolith && cd regolith
curl -fsSLO https://raw.githubusercontent.com/reified-io/regolith/main/compose.yaml
curl -fsSL https://raw.githubusercontent.com/reified-io/regolith/main/.env.example -o .env
```

Put a token into `.env` as `REGOLITH_TOKEN` — `openssl rand -hex 32` makes one. Check the host, then
start:

```bash
docker compose run --rm regolith doctor
docker compose up -d
```

The API now answers on `127.0.0.1:8080`.

### 2. Use a sandbox

From Kotlin:

```kotlin
dependencies {
    implementation("io.reified.regolith:sdk:0.3.0")
}
```

```kotlin
RegolithClient("http://127.0.0.1:8080", token).use { client ->
    // the alias is your own name for it; the same alias finds this sandbox again next time
    val sandbox = client.getOrCreate("field-notes")

    val result = sandbox.run("python3 -c 'import platform; print(platform.system())'")
    println(result.stdout) // Linux

    sandbox.files.write("notes/today.txt", "remember the milk")

    val server = sandbox.startExec(ExecRequest(shell = "python3 -m http.server 8000"))
    server.output().collect { frame -> print(frame.text) }
}
```

With [Koog](https://github.com/JetBrains/koog) and `io.reified.regolith:koog`, an agent's shell tool
runs inside a sandbox instead of on the machine running the agent:

```kotlin
val executor = RegolithShellCommandExecutor(client.getOrCreate("agent-7"))
val shell = ExecuteShellCommandTool(executor, PrintShellCommandConfirmationHandler())
```

From anything else, the [API](docs/api.md) is plain HTTP and JSON.

## Documentation

- [API](docs/api.md) — every endpoint, what it means, and the errors it returns.
- [Configuration](docs/configuration.md) — every setting and its default.
- [Pages](docs/pages.md) — publishing a sandbox's files to the web.
- [Security](docs/sandbox-security.md) — what confines a sandbox, and what does not.
- [Architecture](docs/architecture.md) — how it is built, for people changing it.

## Building from source

Gradle runs on JDK 21.

```bash
./gradlew build detekt
```

The server image and the two sandbox images — `base`, and `full` with
[media and document tools](docs/configuration.md#sandbox-images):

```bash
docker build -f images/server/Dockerfile -t regolith .
docker build --target base -t regolith-sandbox images/sandbox
docker build --target full -t regolith-sandbox-full images/sandbox
```

Two test suites run only when asked. The first drives a real Docker daemon; the second adds firewall
chains and a loop device on this machine, then removes them.

```bash
REGOLITH_DOCKER_TESTS=1 ./gradlew :server:test --tests "*DockerRuntimeIntegrationTest*"
REGOLITH_HOST_TESTS=1 ./gradlew :server:test --tests "*HostIntegrationTest*"
```
