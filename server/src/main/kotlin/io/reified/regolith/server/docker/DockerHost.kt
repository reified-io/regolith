package io.reified.regolith.server.docker

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Whether rules in this host's firewall say anything about where a sandbox can connect. The floor is
 * iptables rules beside Docker's own, in the namespace the helper calls the host's; on a daemon whose
 * containers leave through somewhere else those rules can be installed, read back and proven against
 * private space, and still leave the machine's own public addresses open.
 */
object DockerHost {
    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }

    /** Why the daemon that printed [info] — `docker info` as JSON — cannot be policed, each with its fix; empty when it can. */
    fun refusals(info: String): List<String> {
        val host = json.decodeFromString<Info>(info)

        return buildList {
            if (host.osType != "linux" || "Docker Desktop" in host.operatingSystem) {
                add("Docker Desktop keeps containers in a VM, so rules there say nothing about this machine's network; run Docker Engine on Linux")
            }
            if (host.securityOptions.any { "rootless" in it }) {
                add("Rootless Docker routes container traffic through a user-space network the host firewall never sees; run a rootful daemon")
            }
            if (host.firewallBackend?.driver == "nftables") {
                add("Docker runs its native nftables backend, which has no DOCKER-USER chain; set \"firewall-backend\": \"iptables\" in daemon.json")
            }
        }
    }

    suspend fun refusals(docker: DockerCli): List<String> =
        refusals(docker.run(listOf("info", "--format", "{{json .}}")).requireOk("Reading docker info").text)

    @Serializable
    private data class Info(
        @SerialName("OperatingSystem") val operatingSystem: String = "",
        @SerialName("OSType") val osType: String = "linux",
        @SerialName("SecurityOptions") val securityOptions: List<String> = emptyList(),
        @SerialName("FirewallBackend") val firewallBackend: FirewallBackend? = null,
    )

    @Serializable
    private data class FirewallBackend(@SerialName("Driver") val driver: String = "")
}
