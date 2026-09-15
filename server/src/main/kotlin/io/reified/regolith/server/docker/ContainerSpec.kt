package io.reified.regolith.server.docker

import io.reified.regolith.server.domain.ExecCommand
import io.reified.regolith.server.domain.ExecId
import io.reified.regolith.server.domain.NetworkPolicy
import io.reified.regolith.server.domain.Sandbox
import io.reified.regolith.server.domain.SandboxLayout
import io.reified.regolith.server.domain.SandboxName
import io.reified.regolith.server.ports.ExecSpec
import io.reified.regolith.server.ports.HomeMount
import io.reified.regolith.server.ports.Signal
import java.util.Locale

/**
 * Every docker command line the runtime issues, built without I/O so the hardening can be read and
 * tested in one place.
 */
class ContainerSpec(
    private val namespace: String,
    private val shell: String,
    private val homeReadBps: String? = null,
    private val homeWriteBps: String? = null,
) {

    val network: String = "$namespace-sandboxes"

    val namespaceLabel: String = "$LABEL_PREFIX.namespace=$namespace"

    fun container(name: SandboxName): String = "$namespace-$name"

    fun pull(image: String): List<String> = listOf("pull", "--quiet", image)

    fun networkCreate(): List<String> = listOf(
        "network", "create",
        "--driver", "bridge",
        "--label", namespaceLabel,
        // sandboxes never talk to each other; the host policy does the rest.
        "--opt", "com.docker.network.bridge.enable_icc=false",
        network,
    )

    /**
     * The session container. It runs as the sandbox uid with no capabilities, no privilege
     * escalation, a read-only root and an idle entrypoint: nothing of the caller's runs until the
     * first exec, which is what lets the network policy be applied after start and before use.
     */
    fun run(sandbox: Sandbox, home: HomeMount, image: String): List<String> {
        val memory = sandbox.resources.memoryMb
        val args = mutableListOf(
            "run", "--detach",
            "--name", container(sandbox.name),
            "--label", namespaceLabel,
            "--label", "$LABEL_PREFIX.sandbox=${sandbox.name}",
            "--init",
            "--user", "${SandboxLayout.UID}:${SandboxLayout.UID}",
            "--cap-drop", "ALL",
            "--security-opt", "no-new-privileges",
            "--read-only",
            "--tmpfs", "/tmp:rw,exec,nosuid,nodev,size=${minOf(memory / 2, MAX_TMP_MB)}m",
            "--memory", "${memory}m",
            // equal to --memory: no swap, so a runaway process is killed instead of thrashing the host.
            "--memory-swap", "${memory}m",
            "--cpus", String.format(Locale.ROOT, "%.2f", sandbox.resources.cpus),
            // a lower weight than anything else on the host, so a busy sandbox yields a contested cpu.
            "--cpu-shares", "256",
            "--pids-limit", PIDS_LIMIT.toString(),
            "--ulimit", "nofile=$NOFILE:$NOFILE",
            "--sysctl", "net.ipv6.conf.all.disable_ipv6=1",
            "--sysctl", "net.ipv6.conf.default.disable_ipv6=1",
            "--hostname", "sandbox",
            "--log-driver", "none",
            "--mount", "type=volume,src=${home.volume},dst=${SandboxLayout.HOME}",
            "--workdir", SandboxLayout.HOME,
            "--env", "HOME=${SandboxLayout.HOME}",
        )

        // a `none` sandbox starts with no interface at all; attaching later goes through connect.
        if (sandbox.network == NetworkPolicy.None) {
            args += listOf("--network", "none")
        } else {
            args += listOf("--network", network)
            PublicResolvers.addresses.forEach { args += listOf("--dns", it) }
        }

        home.device?.let { device ->
            homeReadBps?.let { args += listOf("--device-read-bps", "$device:$it") }
            homeWriteBps?.let { args += listOf("--device-write-bps", "$device:$it") }
        }
        for ((key, value) in sandbox.env) args += listOf("--env", "$key=$value")
        args += listOf("--entrypoint", "sleep", image, "infinity")

        return args
    }

    fun connect(name: SandboxName): List<String> = listOf("network", "connect", network, container(name))

    fun disconnect(name: SandboxName): List<String> = listOf("network", "disconnect", "--force", network, container(name))

    fun address(name: SandboxName): List<String> =
        listOf("inspect", "--format", "{{with index .NetworkSettings.Networks \"$network\"}}{{.IPAddress}}{{end}}", container(name))

    /** An exec. [EXEC_MARKER] identifies every process it starts, so [signal] can find descendants too. */
    fun exec(name: SandboxName, spec: ExecSpec): List<String> {
        val args = mutableListOf("exec")
        if (spec.stdin) args += "--interactive"
        args += listOf("--user", "${SandboxLayout.UID}:${SandboxLayout.UID}", "--workdir", spec.cwd)
        args += listOf("--env", "$EXEC_MARKER=${spec.id}")
        for ((key, value) in spec.env) args += listOf("--env", "$key=$value")
        args += container(name)
        args += when (val command = spec.command) {
            is ExecCommand.Shell -> listOf(shell, "-c", command.script)
            is ExecCommand.Argv -> command.args
        }

        return args
    }

    /**
     * Signals every process whose environment carries the exec's marker. A process can shed the marker
     * by clearing its environment; stopping the session is the backstop that reaches everything.
     */
    fun signal(name: SandboxName, exec: ExecId, signal: Signal): List<String> = helper(
        name,
        """for p in /proc/[0-9]*; do if grep -qxzF "$EXEC_MARKER=${'$'}1" "${'$'}p/environ" 2>/dev/null; then kill -s "${'$'}2" "${'$'}{p#/proc/}" 2>/dev/null; fi; done; exit 0""",
        exec.value,
        signal.name,
    )

    fun stat(name: SandboxName, path: String): List<String> =
        helperArgs(name, listOf("stat", "--printf", """%F\0%s\0%Y\0%a\0""", "--", path))

    fun list(name: SandboxName, path: String): List<String> =
        helperArgs(name, listOf("find", path, "-mindepth", "1", "-maxdepth", "1", "-printf", """%f\0%y\0%s\0%T@\0%m\0"""))

    fun read(name: SandboxName, path: String): List<String> = helperArgs(name, listOf("cat", "--", path))

    /** Every regular file under a directory, as size and path relative to it, NUL-terminated. */
    fun tree(name: SandboxName, path: String): List<String> =
        helper(name, """find "${'$'}1" -type f -printf '%s\0%P\0'""", path)

    /** Copies a directory's contents out of the session; the daemon writes them where the server asks. */
    fun copyOut(name: SandboxName, path: String, destination: String): List<String> =
        listOf("cp", "--follow-link=false", "${container(name)}:${path.trimEnd('/')}/.", destination)

    /** The session's cgroup counters, readable by the sandbox user from inside its own container. */
    fun cpuStat(name: SandboxName): List<String> = helperArgs(name, listOf("cat", "/sys/fs/cgroup/cpu.stat"))

    /** Both limit event files, memory first, separated by a `--` line: each has a `max` key of its own. */
    fun limitEvents(name: SandboxName): List<String> =
        helper(name, "cat /sys/fs/cgroup/memory.events; echo --; cat /sys/fs/cgroup/pids.events")

    /** Streams stdin into a temporary sibling and renames it over [path], so a reader never sees half a file. */
    fun write(name: SandboxName, path: String, temporaryName: String): List<String> = listOf(
        "exec", "--interactive", "--user", "${SandboxLayout.UID}:${SandboxLayout.UID}", container(name),
        "/bin/sh", "-c",
        """set -e; dir=${'$'}(dirname -- "${'$'}1"); mkdir -p -- "${'$'}dir"; trap 'rm -f -- "${'$'}dir/${'$'}2"' EXIT; cat > "${'$'}dir/${'$'}2"; mv -f -- "${'$'}dir/${'$'}2" "${'$'}1"""",
        "regolith-write", path, temporaryName,
    )

    fun delete(name: SandboxName, path: String, recursive: Boolean, directory: Boolean): List<String> = helperArgs(
        name,

        when {
            recursive -> listOf("rm", "-rf", "--", path)
            directory -> listOf("rmdir", "--", path)
            else -> listOf("rm", "-f", "--", path)
        },
    )

    private fun helper(name: SandboxName, script: String, vararg positional: String): List<String> =
        helperArgs(name, listOf("/bin/sh", "-c", script, "regolith-helper") + positional)

    private fun helperArgs(name: SandboxName, command: List<String>): List<String> =
        listOf("exec", "--user", "${SandboxLayout.UID}:${SandboxLayout.UID}", container(name)) + command

    companion object {
        const val LABEL_PREFIX = "io.reified.regolith"
        const val EXEC_MARKER = "REGOLITH_EXEC_ID"
        const val PIDS_LIMIT = 512
        const val NOFILE = 4096
        const val MAX_TMP_MB = 512
    }
}
