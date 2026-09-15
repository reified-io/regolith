package io.reified.regolith.server.docker

import io.github.oshai.kotlinlogging.KotlinLogging
import io.reified.regolith.server.domain.RegolithError
import io.reified.regolith.server.domain.SandboxId
import io.reified.regolith.server.ports.HomeMount
import io.reified.regolith.server.ports.HomeStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.Path

/**
 * [HomeStore] as one preallocated ext4 image per sandbox.
 *
 * Two Docker volumes per home: `<ns>-<sandbox>-disk` holds the image and is only ever seen by the home disk
 * helper, which attaches it to a loop device; `<ns>-<sandbox>-home` is a local-driver volume of that device,
 * which the daemon itself mounts when a session starts. A full home stops at its own size, in the kernel,
 * whatever fills it.
 */
class HomeDisks(
    private val docker: DockerCli,
    private val helpers: Helpers,
    private val namespace: String,
    private val stateDir: Path,
    private val reserveMb: Long,
) : HomeStore {

    private val namespaceLabel = "${ContainerSpec.LABEL_PREFIX}.namespace=$namespace"

    override suspend fun recover() {
        val mounts = volumes(ROLE_MOUNT)
        if (mounts.isNotEmpty()) docker.run(listOf("volume", "rm", "--force") + mounts)
        val disks = volumes(ROLE_DISK)
        for (disk in disks) helper(disk, "release")
        log.info { "Home attachments recovered: disks=[${disks.size}]" }
    }

    override suspend fun open(sandbox: SandboxId, sizeMb: Int): HomeMount {
        val disk = diskVolume(sandbox)
        val home = homeVolume(sandbox)
        // a mount volume only names a device, which may be stale after a restart; it is always recreated.
        docker.run(listOf("volume", "rm", "--force", home))

        if (!exists(disk)) {
            docker.run(
                listOf(
                    "volume", "create",
                    "--label", namespaceLabel, "--label", "$ROLE_LABEL=$ROLE_DISK", "--label", "${ContainerSpec.LABEL_PREFIX}.sandbox=$sandbox",
                    disk,
                ),
            )
                .requireOk("Creating home disk volume $disk")
        }

        requireOwned(disk, ROLE_DISK)
        val device = helper(disk, "prepare", sizeMb.toString(), reserveMb.toString())
        check(LOOP_DEVICE.matches(device)) { "The home disk helper returned `$device` instead of a loop device" }
        docker.run(
            listOf(
                "volume", "create",
                "--label", namespaceLabel, "--label", "$ROLE_LABEL=$ROLE_MOUNT",
                "--driver", "local",
                "--opt", "type=ext4",
                "--opt", "device=$device",
                "--opt", "o=rw,nosuid,nodev,nodiscard",
                home,
            ),
        ).requireOk("Creating home mount volume $home")

        return HomeMount(home, device)
    }

    override suspend fun close(sandbox: SandboxId) {
        val disk = diskVolume(sandbox)
        docker.run(listOf("volume", "rm", "--force", homeVolume(sandbox)))
        if (exists(disk)) helper(disk, "release")
    }

    override suspend fun destroy(sandbox: SandboxId) {
        close(sandbox)
        val disk = diskVolume(sandbox)

        if (exists(disk)) {
            requireOwned(disk, ROLE_DISK)
            docker.run(listOf("volume", "rm", disk)).requireOk("Deleting home disk volume $disk")
        }
    }

    override suspend fun hostFreeBytes(): Long = withContext(Dispatchers.IO) { Files.getFileStore(stateDir).usableSpace }

    override suspend fun list(): List<SandboxId> = volumes(ROLE_DISK).mapNotNull { volume ->
        val raw = volume.removePrefix("$namespace-").removeSuffix("-disk")
        runCatching { SandboxId.parse(raw) }
            .onFailure { log.warn { "Ignoring a home disk volume whose name is not a sandbox: volume=[$volume]" } }
            .getOrNull()
    }

    override suspend fun sizeMb(sandbox: SandboxId): Int? {
        val disk = diskVolume(sandbox)
        if (!exists(disk)) return null
        requireOwned(disk, ROLE_DISK)
        val bytes = helper(disk, "size").toLongOrNull() ?: return null

        return (bytes / (1024 * 1024)).toInt()
    }

    private suspend fun helper(disk: String, vararg args: String): String {
        val result = docker.run(helpers.homeDisk(disk, *args))

        if (!result.ok && "not enough host space" in result.stderr) {
            throw RegolithError.Unavailable("The host has no room for another home")
        }

        return result.requireOk("Home disk helper ${args.first()} on $disk").text.trim()
    }

    private suspend fun exists(volume: String): Boolean = docker.run(listOf("volume", "inspect", volume)).ok

    /** Refuses to touch a volume this namespace did not create: a sandbox collision must never destroy a stranger's data. */
    private suspend fun requireOwned(volume: String, role: String) {
        val labels = docker.run(
            listOf("volume", "inspect", "--format", "{{index .Labels \"${ContainerSpec.LABEL_PREFIX}.namespace\"}} {{index .Labels \"$ROLE_LABEL\"}} {{.Driver}}", volume),
        ).requireOk("Inspecting volume $volume").text.trim()
        check(labels == "$namespace $role local") { "Volume $volume does not belong to this server: $labels" }
    }

    private suspend fun volumes(role: String): List<String> =
        docker.run(listOf("volume", "ls", "--quiet", "--filter", "label=$namespaceLabel", "--filter", "label=$ROLE_LABEL=$role"))
            .requireOk("Listing $role volumes").text.lines().filter { it.isNotBlank() }

    private fun diskVolume(sandbox: SandboxId) = "$namespace-$sandbox-disk"

    private fun homeVolume(sandbox: SandboxId) = "$namespace-$sandbox-home"

    private companion object {
        val log = KotlinLogging.logger {}
        const val ROLE_LABEL = "${ContainerSpec.LABEL_PREFIX}.role"
        const val ROLE_DISK = "home-disk"
        const val ROLE_MOUNT = "home-mount"
        val LOOP_DEVICE = Regex("/dev/loop[0-9]+")
    }
}
