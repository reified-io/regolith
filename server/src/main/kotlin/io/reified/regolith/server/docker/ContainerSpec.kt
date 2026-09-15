package io.reified.regolith.server.docker

import io.reified.regolith.server.domain.ExecCommand
import io.reified.regolith.server.domain.ExecId
import io.reified.regolith.server.domain.NetworkPolicy
import io.reified.regolith.server.domain.Sandbox
import io.reified.regolith.server.domain.SandboxLayout
import io.reified.regolith.server.domain.SandboxId
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

    fun container(sandbox: SandboxId): String = "$namespace-$sandbox"

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
        val cpus = String.format(Locale.ROOT, "%.2f", sandbox.resources.cpus)
        val args = mutableListOf(
            "run", "--detach",
            "--name", container(sandbox.id),
            "--label", namespaceLabel,
            "--label", "$LABEL_PREFIX.sandbox=${sandbox.id}",
            "--init",
            "--user", "${SandboxLayout.UID}:${SandboxLayout.UID}",
            "--cap-drop", "ALL",
            "--security-opt", "no-new-privileges",
            "--read-only",
            "--tmpfs", "/tmp:rw,exec,nosuid,nodev,size=${minOf(memory / 2, MAX_TMP_MB)}m",
            "--memory", "${memory}m",
            // equal to --memory: no swap, so a runaway process is killed instead of thrashing the host.
            "--memory-swap", "${memory}m",
            "--cpus", cpus,
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
            // its own bounds, nothing of the host's: `free` and `nproc` in here report the machine,
            // so a sandbox that plans around them plans around memory and cores it does not have.
            "--env", "REGOLITH_MEMORY_MB=$memory",
            "--env", "REGOLITH_CPUS=$cpus",
            "--env", "REGOLITH_HOME_MB=${sandbox.resources.homeMb}",
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

    fun connect(sandbox: SandboxId): List<String> = listOf("network", "connect", network, container(sandbox))

    fun disconnect(sandbox: SandboxId): List<String> = listOf("network", "disconnect", "--force", network, container(sandbox))

    fun address(sandbox: SandboxId): List<String> =
        listOf("inspect", "--format", "{{with index .NetworkSettings.Networks \"$network\"}}{{.IPAddress}}{{end}}", container(sandbox))

    /** An exec. [EXEC_MARKER] identifies every process it starts, so [signal] can find descendants too. */
    fun exec(sandbox: SandboxId, spec: ExecSpec): List<String> {
        val args = mutableListOf("exec")
        if (spec.stdin) args += "--interactive"
        args += listOf("--user", "${SandboxLayout.UID}:${SandboxLayout.UID}", "--workdir", spec.cwd)
        args += listOf("--env", "$EXEC_MARKER=${spec.id}")
        for ((key, value) in spec.env) args += listOf("--env", "$key=$value")
        args += container(sandbox)
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
    fun signal(sandbox: SandboxId, exec: ExecId, signal: Signal): List<String> = helper(
        sandbox,
        """for p in /proc/[0-9]*; do if grep -qxzF "$EXEC_MARKER=${'$'}1" "${'$'}p/environ" 2>/dev/null; then kill -s "${'$'}2" "${'$'}{p#/proc/}" 2>/dev/null; fi; done; exit 0""",
        exec.value,
        signal.name,
    )

    fun stat(sandbox: SandboxId, path: String): List<String> =
        helperArgs(sandbox, listOf("stat", "--printf", """%F\0%s\0%Y\0%a\0""", "--", path))

    fun list(sandbox: SandboxId, path: String): List<String> =
        helperArgs(sandbox, listOf("find", path, "-mindepth", "1", "-maxdepth", "1", "-printf", """%f\0%y\0%s\0%T@\0%m\0"""))

    fun read(sandbox: SandboxId, path: String): List<String> = helperArgs(sandbox, listOf("cat", "--", path))

    /** Whether the sandbox user can open [path]; its mode alone does not decide, ownership does too. */
    fun readable(sandbox: SandboxId, path: String): List<String> = helper(sandbox, """test -r "${'$'}1"""", path)

    /** Every regular file under a directory, as size and path relative to it, NUL-terminated. */
    fun tree(sandbox: SandboxId, path: String): List<String> =
        helper(sandbox, """find "${'$'}1" -type f -printf '%s\0%P\0'""", path)

    /** Copies a directory's contents out of the session; the daemon writes them where the server asks. */
    fun copyOut(sandbox: SandboxId, path: String, destination: String): List<String> =
        listOf("cp", "--follow-link=false", "${container(sandbox)}:${path.trimEnd('/')}/.", destination)

    /** The session's cgroup counters, readable by the sandbox user from inside its own container. */
    fun cpuStat(sandbox: SandboxId): List<String> = helperArgs(sandbox, listOf("cat", "/sys/fs/cgroup/cpu.stat"))

    /** Both limit event files, memory first, separated by a `--` line: each has a `max` key of its own. */
    fun limitEvents(sandbox: SandboxId): List<String> =
        helper(sandbox, "cat /sys/fs/cgroup/memory.events; echo --; cat /sys/fs/cgroup/pids.events")

    /**
     * Streams stdin into a temporary sibling of [path], removing it when the write itself fails. It never
     * renames the file: an input that ends early looks like a finished one from in here, so only the
     * server, which knows the whole body arrived, puts it in place with [move].
     */
    fun write(sandbox: SandboxId, path: String, temporaryName: String): List<String> = listOf(
        "exec", "--interactive", "--user", "${SandboxLayout.UID}:${SandboxLayout.UID}", container(sandbox),
        "/bin/sh", "-c",
        """set -e; dir=${'$'}(dirname -- "${'$'}1"); mkdir -p -- "${'$'}dir"; if ! cat > "${'$'}dir/${'$'}2"; then rm -f -- "${'$'}dir/${'$'}2"; exit 1; fi""",
        "regolith-write", path, temporaryName,
    )

    /** Renames the temporary sibling [write] filled over [path], so a reader never sees half a file. */
    fun move(sandbox: SandboxId, path: String, temporaryName: String): List<String> =
        helper(sandbox, """dir=${'$'}(dirname -- "${'$'}1"); mv -fT -- "${'$'}dir/${'$'}2" "${'$'}1"""", path, temporaryName)

    fun delete(sandbox: SandboxId, path: String, recursive: Boolean, directory: Boolean): List<String> = helperArgs(
        sandbox,

        when {
            recursive -> listOf("rm", "-rf", "--", path)
            directory -> listOf("rmdir", "--", path)
            else -> listOf("rm", "-f", "--", path)
        },
    )

    private fun helper(sandbox: SandboxId, script: String, vararg positional: String): List<String> =
        helperArgs(sandbox, listOf("/bin/sh", "-c", script, "regolith-helper") + positional)

    private fun helperArgs(sandbox: SandboxId, command: List<String>): List<String> =
        listOf("exec", "--user", "${SandboxLayout.UID}:${SandboxLayout.UID}", container(sandbox)) + command

    companion object {
        const val LABEL_PREFIX = "io.reified.regolith"
        const val EXEC_MARKER = "REGOLITH_EXEC_ID"
        const val PIDS_LIMIT = 512
        const val NOFILE = 4096
        const val MAX_TMP_MB = 512
    }
}
