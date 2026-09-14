package io.reified.regolith.server.docker

import java.nio.file.Files
import java.nio.file.Path

/**
 * Short-lived trusted containers that do what the server itself must not: change the host's firewall
 * and attach loop devices. Each runs one fixed script from [image] with only the capability that
 * script needs, no network unless the script works on the host's, and no mount but its own.
 */
class Helpers(private val image: String, private val namespace: String) {
    private val label = "${ContainerSpec.LABEL_PREFIX}.namespace=$namespace"

    fun firewall(vararg args: String): List<String> = listOf(
        "run", "--rm", "--interactive", "--pull=never",
        "--label", label,
        "--network=host",
        "--read-only",
        "--cap-drop=ALL", "--cap-add=NET_ADMIN", "--cap-add=NET_RAW",
        "--security-opt=no-new-privileges",
        "--pids-limit=64", "--memory=128m",
        "--entrypoint", "$SCRIPTS/firewall.sh",
        image,
    ) + args

    fun homeDisk(diskVolume: String, vararg args: String): List<String> = listOf(
        "run", "--rm", "--pull=never",
        "--label", label,
        "--network=none",
        "--read-only",
        "--tmpfs", "/tmp:size=16m",
        "--cap-drop=ALL", "--cap-add=SYS_ADMIN", "--cap-add=MKNOD",
        "--security-opt=no-new-privileges",
        "--pids-limit=32", "--memory=256m", "--memory-swap=256m",
        // loop devices are created on demand, so the helper needs the daemon's own /dev to see new nodes.
        "--device-cgroup-rule", "b 7:* rwm",
        "--device", "/dev/loop-control",
        "--mount", "type=bind,src=/dev,dst=/dev",
        "--mount", "type=volume,src=$diskVolume,dst=/storage",
        "--entrypoint", "$SCRIPTS/homedisk.sh",
        image,
    ) + args

    /** Asks the home disk helper for a free loop device, without any disk: a read-only check. */
    fun homeDiskProbe(): List<String> = listOf(
        "run", "--rm", "--pull=never",
        "--label", label,
        "--network=none",
        "--read-only",
        "--cap-drop=ALL", "--cap-add=SYS_ADMIN", "--cap-add=MKNOD",
        "--security-opt=no-new-privileges",
        "--pids-limit=16", "--memory=64m",
        "--device-cgroup-rule", "b 7:* rwm",
        "--device", "/dev/loop-control",
        "--mount", "type=bind,src=/dev,dst=/dev",
        "--entrypoint", "$SCRIPTS/homedisk.sh",
        image, "probe",
    )

    /** Whether [address]:[port] answers from the host's own network namespace; a connection only. */
    fun hostReach(address: String, port: Int): List<String> = listOf(
        "run", "--rm", "--pull=never",
        "--label", label,
        "--network=host",
        "--read-only",
        "--user=65534:65534",
        "--cap-drop=ALL",
        "--security-opt=no-new-privileges",
        "--pids-limit=16", "--memory=64m",
        "--entrypoint", "nc",
        image, "-z", "-w", "2", address, port.toString(),
    )

    /** A listener on the host's side of the bridge, for the network probe to fail to reach. */
    fun controlListener(name: String, address: String, port: Int): List<String> = listOf(
        "run", "--detach", "--rm", "--pull=never",
        "--name", name,
        "--label", label,
        "--network=host",
        "--read-only",
        "--user=65534:65534",
        "--cap-drop=ALL",
        "--security-opt=no-new-privileges",
        "--pids-limit=16", "--memory=64m",
        "--entrypoint", "nc",
        image, "-l", "-k", address, port.toString(),
    )

    /** A throwaway sandbox-shaped container on the sandbox network, confined like a real session. */
    fun probe(name: String, network: String): List<String> = listOf(
        "run", "--detach", "--rm", "--pull=never",
        "--name", name,
        "--label", label,
        "--network", network,
        "--dns", PublicResolvers.addresses[0], "--dns", PublicResolvers.addresses[1],
        "--sysctl", "net.ipv6.conf.all.disable_ipv6=1",
        "--sysctl", "net.ipv6.conf.default.disable_ipv6=1",
        "--read-only",
        "--user=1000:1000",
        "--cap-drop=ALL",
        "--security-opt=no-new-privileges",
        "--pids-limit=64", "--memory=128m",
        "--entrypoint", "sleep",
        image, "600",
    )

    companion object {
        const val SCRIPTS = "/opt/regolith/scripts"

        /**
         * The image helpers run: [configured] when set, otherwise the image of the container this server
         * runs in, read from its own mount table. Resolved to an image id, so a retagged image cannot change
         * what a helper runs.
         */
        suspend fun resolveImage(docker: DockerCli, configured: String?): String {
            val reference = configured ?: ownImage(docker)
                ?: error("REGOLITH_HELPER_IMAGE is required when the server does not run in a container it can inspect")

            return docker.run(listOf("image", "inspect", "--format", "{{.Id}}", reference))
                .requireOk("Resolving the helper image $reference").text.trim()
        }

        private suspend fun ownImage(docker: DockerCli): String? {
            val mounts = runCatching { Files.readAllLines(Path.of("/proc/self/mountinfo")) }.getOrDefault(emptyList())
            val container = mounts.firstNotNullOfOrNull { CONTAINER_ID.find(it)?.groupValues?.get(1) } ?: return null

            return docker.run(listOf("inspect", "--format", "{{.Image}}", container)).takeIf { it.ok }?.text?.trim()
        }

        private val CONTAINER_ID = Regex("/containers/([0-9a-f]{64})/")
    }
}
