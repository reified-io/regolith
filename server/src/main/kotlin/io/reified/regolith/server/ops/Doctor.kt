package io.reified.regolith.server.ops

import io.reified.regolith.server.app.OrphanHomes
import io.reified.regolith.server.app.OrphanSites
import io.reified.regolith.server.config.ServerConfig
import io.reified.regolith.server.docker.DockerCli
import io.reified.regolith.server.docker.DockerHost
import io.reified.regolith.server.docker.FirewallRules
import io.reified.regolith.server.docker.Helpers
import io.reified.regolith.server.docker.HomeDisks
import io.reified.regolith.server.domain.Cidr
import io.reified.regolith.server.domain.PlatformFloor
import io.reified.regolith.server.domain.RegolithError
import io.reified.regolith.server.ports.SitePublisher
import io.reified.regolith.server.publish.PagesPublisher
import io.reified.regolith.server.store.FileStateStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.readText

/**
 * Every precondition the server checks at startup, as named read-only checks with the fix in the message.
 *
 * It changes nothing: no firewall rule, no loop attachment, no pull, no lock. It can run beside a live
 * server. A failing check is one the server would refuse to start on; a warning is one it would start
 * with but that an operator should know about.
 */
class Doctor(private val config: ServerConfig, private val docker: DockerCli) {
    enum class Status { OK, WARN, FAIL }

    data class Check(val id: String, val status: Status, val detail: String)

    suspend fun run(): List<Check> {
        val checks = mutableListOf<Check>()
        val version = docker.run(listOf("version", "--format", "{{.Server.Version}}"))

        if (!version.ok) {
            checks += fail("docker", "The Docker daemon does not answer (${version.stderr.firstLine()}); give the server the Docker socket")
            return checks
        }

        checks += ok("docker", "Docker ${version.text.trim()}")
        checks += attempt("docker-host") { dockerHost() }
        checks += attempt("cgroups") { cgroups() }
        val helpers = try {
            Helpers(Helpers.resolveImage(docker, config.helperImage), config.namespace).also {
                checks += ok("helper-image", "Helpers run ${config.helperImage ?: "this server's own image"}")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            checks += fail("helper-image", "${e.message}; set REGOLITH_HELPER_IMAGE to the server image")
            null
        }
        checks += attempt("sandbox-images") { sandboxImages() }

        if (helpers != null) {
            checks += attempt("netfilter") { netfilter(helpers) }
            checks += attempt("lan-control") { lanControl(helpers) }
            checks += attempt("loop-devices") { loopDevices(helpers) }
        }

        checks += attempt("free-space") { freeSpace() }
        checks += attempt("state") { state() }
        if (helpers != null) checks += attempt("orphan-homes") { orphanHomes(helpers) }
        checks += pagesCheck(config.pagesUrl, claimedSites()) { PagesPublisher(it, checkNotNull(config.pagesToken)) }

        return checks
    }

    private suspend fun claimedSites(): Set<String> =
        withContext(Dispatchers.IO) { FileStateStore.recorded(config.stateDir) }.mapNotNull { it.site?.value }.toSet()

    private suspend fun dockerHost(): Check {
        val refusals = DockerHost.refusals(docker)

        return if (refusals.isEmpty()) {
            ok("docker-host", "A rootful Docker Engine that programs iptables, so host firewall rules police its containers")
        } else {
            fail("docker-host", refusals.joinToString("; "))
        }
    }

    private suspend fun cgroups(): Check {
        // read as json: a go template names struct fields, which differ from the json keys docker documents.
        val info = Json.parseToJsonElement(docker.run(listOf("info", "--format", "{{json .}}")).requireText("docker info")).jsonObject
        fun field(key: String) = info[key]?.jsonPrimitive?.content
        val missing = listOf("MemoryLimit", "PidsLimit", "CpuCfsQuota").filter { field(it) != "true" }

        return when {
            field("CgroupVersion") != "2" -> fail("cgroups", "Docker runs on cgroup ${field("CgroupVersion")}; sandboxes need cgroup v2")
            missing.isNotEmpty() -> fail("cgroups", "Docker cannot enforce ${missing.joinToString()}; sandboxes need memory, process and CPU limits")
            else -> ok("cgroups", "cgroup v2 with memory, process and CPU limits")
        }
    }

    /**
     * Every image a session could start on: the allowed ones, and whatever the recorded sandboxes
     * resolve to — a sandbox pinned to an image that has since left the allowlist still runs it.
     */
    private suspend fun sandboxImages(): Check {
        val recorded = withContext(Dispatchers.IO) { FileStateStore.recorded(config.stateDir) }.associate { it.id.value to it.imagePolicy }
        val stranded = recorded.filterValues { config.images.resolveOrNull(it) == null }.keys
        val references = (config.images.allowed + recorded.values.mapNotNull { config.images.resolveOrNull(it) }).distinct()
        val missing = references.filterNot { docker.run(listOf("image", "inspect", "--format", "{{.Id}}", it)).ok }

        return when {
            stranded.isNotEmpty() -> warn(
                "sandbox-images",
                "These sandboxes track an image this server no longer offers and cannot start: ${stranded.joinToString()}; " +
                    "name it in REGOLITH_ALLOWED_IMAGES again, or move them with PATCH",
            )
            missing.isNotEmpty() -> warn("sandbox-images", "Not on this host yet, pulled by the first session that uses them: ${missing.joinToString()}")
            else -> ok("sandbox-images", "All ${references.size} images a session could start on are on this host")
        }
    }

    private suspend fun netfilter(helpers: Helpers): Check {
        val prefix = FirewallRules.prefixFor(config.namespace)
        val id = docker.run(listOf("network", "inspect", "--format", "{{.Id}}", "${config.namespace}-sandboxes"))
        val bridge = if (id.ok) "br-${id.text.trim().take(12)}" else "lo"
        val result = docker.run(helpers.firewall("check", prefix, bridge))
        if (!result.ok) return fail("netfilter", "${result.stderr.firstLine()}; the server can only police a host whose firewall Docker programs through iptables")
        val positions = result.text.lines().filter { it.startsWith("position ") }
        val backend = positions.firstOrNull()?.split(' ')?.getOrNull(1) ?: "iptables"
        val hooks = positions.filter { it.split(' ')[1] == backend && it.split(' ')[2] != "FORWARD" }
        val ipv6 = if (positions.any { "ip6tables" in it }) "; ip6tables present" else "; no ip6tables, IPv6 stays off inside sandboxes"

        return when {
            hooks.all { it.endsWith(" missing") } -> ok("netfilter", "$backend, prefix $prefix; the rules are installed when the server starts$ipv6")
            hooks.all { it.endsWith(" 2") } -> ok("netfilter", "$backend, prefix $prefix; rules installed and hooked first$ipv6")
            else -> warn("netfilter", "$backend, prefix $prefix; rules installed but not first in every chain, which the network guard repairs$ipv6")
        }
    }

    private suspend fun lanControl(helpers: Helpers): Check {
        val gateways = docker.run(helpers.firewall("gateways")).requireText("reading the host's gateways").lines()
            .filter { it.isNotBlank() && runCatching { PlatformFloor.refuses(Cidr.parse(it)) }.getOrDefault(false) }

        for (gateway in gateways) {
            val port = LAN_PORTS.firstOrNull { docker.run(helpers.hostReach(gateway, it)).ok } ?: continue
            return ok("lan-control", "$gateway:$port answers from the host, so the startup proof also covers the local network")
        }

        return warn("lan-control", "No private gateway answers from this host; the network floor will be proven against the host only")
    }

    private suspend fun loopDevices(helpers: Helpers): Check {
        val result = docker.run(helpers.homeDiskProbe())

        return if (result.ok) {
            ok("loop-devices", "Loop devices available, next free ${result.text.trim()}")
        } else {
            fail("loop-devices", "${result.stderr.firstLine()}; homes need a Docker host whose containers may use /dev/loop-control")
        }
    }

    private suspend fun freeSpace(): Check = withContext(Dispatchers.IO) {
        var path: Path? = config.stateDir.toAbsolutePath()
        while (path != null && !path.exists()) path = path.parent
        val free = Files.getFileStore(checkNotNull(path)).usableSpace / MIB

        if (free >= config.minFreeMb) {
            ok("free-space", "$free MB free where the state directory lives")
        } else {
            fail("free-space", "$free MB free, below REGOLITH_MIN_FREE_MB (${config.minFreeMb}); new sessions and uploads would be refused")
        }
    }

    private suspend fun state(): Check = withContext(Dispatchers.IO) {
        val root = config.stateDir
        val pin = root.resolve("namespace")

        when {
            !root.exists() -> ok("state", "$root does not exist yet; the server creates it")
            pin.exists() && pin.readText().trim() != config.namespace ->
                fail("state", "$root belongs to namespace `${pin.readText().trim()}`, not `${config.namespace}`")
            FileStateStore.inUse(root) -> ok("state", "$root is in use by a running server")
            else -> ok("state", "$root holds ${FileStateStore.recordedIds(root).size} sandboxes; no server is using it")
        }
    }

    private suspend fun orphanHomes(helpers: Helpers): Check {
        val disks = HomeDisks(docker, helpers, config.namespace, config.stateDir, config.minFreeMb)
        val homes = disks.list().map { it.value }
        val unrecognized = disks.unrecognized()
        val recorded = withContext(Dispatchers.IO) { FileStateStore.recordedIds(config.stateDir) }.toSet()
        val orphans = homes.filterNot { it in recorded }

        return when {
            orphans.isNotEmpty() -> fail("orphan-homes", OrphanHomes.message(orphans))
            unrecognized.isNotEmpty() -> fail("orphan-homes", OrphanHomes.unrecognizedMessage(unrecognized))
            else -> ok("orphan-homes", "${homes.size} homes, every one recorded")
        }
    }

    private suspend fun attempt(id: String, check: suspend () -> Check): Check = try {
        check()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        fail(id, "The check itself failed: ${e.message}")
    }

    private fun DockerCli.Result.requireText(what: String): String {
        check(ok) { "$what failed: ${stderr.firstLine()}" }

        return text.trim()
    }

    private fun String.firstLine(): String = lineSequence().firstOrNull { it.isNotBlank() }?.trim() ?: "no output"

    companion object {
        /**
         * Whether the pages role this server publishes to answers, accepts its token, and serves only sites
         * a record in [claimed] holds. A warning, not a failure: the server starts either way, and only
         * publishing is refused when the role is unreachable.
         */
        suspend fun pagesCheck(url: String?, claimed: Set<String>, connect: (String) -> SitePublisher): Check {
            if (url == null) return ok("pages", "No pages role configured; publish answers not_implemented")
            val publisher = connect(url)

            return try {
                val limits = publisher.limits()
                val orphans = OrphanSites(publisher).find(claimed)
                if (orphans.isNotEmpty()) return warn("pages", OrphanSites.message(orphans))
                ok("pages", "$url answers: up to ${limits.maxFiles} files and ${limits.maxSiteBytes / MIB} MB a site")
            } catch (e: CancellationException) {
                throw e
            } catch (e: RegolithError) {
                warn("pages", "${e.message}; check REGOLITH_PAGES_URL and that REGOLITH_PAGES_TOKEN matches the pages role's own")
            } finally {
                (publisher as? AutoCloseable)?.close()
            }
        }

        private const val MIB = 1024L * 1024L
        private val LAN_PORTS = listOf(443, 80, 53)

        private fun ok(id: String, detail: String) = Check(id, Status.OK, detail)

        private fun warn(id: String, detail: String) = Check(id, Status.WARN, detail)

        private fun fail(id: String, detail: String) = Check(id, Status.FAIL, detail)

        fun render(checks: List<Check>): String = checks.joinToString("\n") { check ->
            val label = when (check.status) {
                Status.OK -> "ok  "
                Status.WARN -> "warn"
                Status.FAIL -> "FAIL"
            }
            "$label  ${check.id.padEnd(ID_WIDTH)}  ${check.detail}"
        }

        /** 0 when the server would start, 1 when a failing check would stop it. */
        fun exitCode(checks: List<Check>): Int = if (checks.any { it.status == Status.FAIL }) 1 else 0

        private const val ID_WIDTH = 14
    }
}
