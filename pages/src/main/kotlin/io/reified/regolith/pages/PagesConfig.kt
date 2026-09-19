package io.reified.regolith.pages

import java.nio.file.Path
import kotlin.io.path.Path
import kotlin.io.path.readText

/**
 * Everything the pages role is configured with, read once at startup from `REGOLITH_PAGES_*`.
 *
 * There are two listeners on purpose. The public one answers visitors and never accepts a write; the
 * intake one takes releases and binds to the loopback address by default, so publishing has no public
 * endpoint at all unless an operator deliberately gives it one — a private network, a tunnel, or TLS
 * of its own.
 */
data class PagesConfig(
    val bind: String,
    val port: Int,
    val apiBind: String,
    val apiPort: Int,
    val token: String,
    val domain: String,
    val scheme: String,
    val dir: Path,
    val reserved: Set<String>,
    val csp: String,
    val limits: Limits,
    val tls: Tls? = null,
    val apiTls: Tls? = null,
) {

    /**
     * TLS for one listener: a certificate chain and its PKCS#8 key as PEM files, and optionally the CA
     * whose client certificates are required — a CDN's origin-pull CA, so a visitor who finds the
     * machine's address cannot reach it except through the CDN. Each listener has its own, since the
     * intake is usually reached under another name, and directly by a control plane that presents no
     * client certificate.
     */
    data class Tls(val certificate: Path, val key: Path, val clientCa: Path?)

    /** Ceilings no release can exceed. */
    data class Limits(
        val maxFiles: Int,
        val maxFileBytes: Long,
        val maxSiteBytes: Long,
        val releasesKept: Int,
        val maxSites: Int,
    )

    /** The address a site is served at. */
    fun url(site: SiteName): String = "$scheme://$site.$domain"

    /** A site name the caller asked for, refusing the labels this deployment keeps for itself. */
    fun siteName(raw: String): SiteName {
        val name = SiteName.parse(raw)
        requireValid(name.value !in reserved) { "`$name` is reserved on this server" }

        return name
    }

    override fun toString(): String = "PagesConfig(port=$port, apiPort=$apiPort, domain=$domain, dir=$dir)"

    companion object {
        const val MIN_TOKEN_CHARS = 32

        private const val MIB = 1024L * 1024L

        private val RESERVED = setOf("www", "api", "admin", "mail", "smtp", "imap", "ns", "ns1", "ns2", "cdn", "static", "assets", "status", "preview")

        fun fromEnvironment(env: Map<String, String> = System.getenv()): PagesConfig {
            fun text(name: String): String = env[name]?.trim().orEmpty()
            fun number(name: String, fallback: Int): Int {
                val raw = text(name).ifEmpty { return fallback }

                return raw.toIntOrNull()?.also { check(it > 0) { "$name must be positive" } } ?: error("$name must be a whole number, got `$raw`")
            }

            val domain = text("REGOLITH_PAGES_DOMAIN")
            check(domain.isNotEmpty()) { "REGOLITH_PAGES_DOMAIN is required: sites are served at <site>.<domain>" }
            check(DOMAIN.matches(domain)) { "REGOLITH_PAGES_DOMAIN must be a hostname, got `$domain`" }

            val scheme = text("REGOLITH_PAGES_SCHEME").ifEmpty { "https" }
            check(scheme == "https" || scheme == "http") { "REGOLITH_PAGES_SCHEME must be `https` or `http`" }

            return PagesConfig(
                bind = text("REGOLITH_PAGES_BIND").ifEmpty { "0.0.0.0" },
                port = number("REGOLITH_PAGES_PORT", 8081),
                apiBind = text("REGOLITH_PAGES_API_BIND").ifEmpty { "127.0.0.1" },
                apiPort = number("REGOLITH_PAGES_API_PORT", 8082),
                token = readToken(env),
                domain = domain.lowercase(),
                scheme = scheme,
                dir = Path(text("REGOLITH_PAGES_DIR").ifEmpty { "/var/lib/regolith-pages" }),
                reserved = RESERVED + text("REGOLITH_PAGES_RESERVED").split(',').map { it.trim().lowercase() }.filter { it.isNotEmpty() },
                // a site cannot be framed, and its forms cannot post credentials to another origin.
                csp = text("REGOLITH_PAGES_CSP").ifEmpty { "frame-ancestors 'none'; form-action 'self'" },
                tls = readTls("REGOLITH_PAGES_TLS", ::text),
                apiTls = readTls("REGOLITH_PAGES_API_TLS", ::text),
                limits = Limits(
                    maxFiles = number("REGOLITH_PAGES_MAX_FILES", 2000),
                    maxFileBytes = number("REGOLITH_PAGES_MAX_FILE_MB", 25) * MIB,
                    maxSiteBytes = number("REGOLITH_PAGES_MAX_SITE_MB", 256) * MIB,
                    releasesKept = number("REGOLITH_PAGES_RELEASES_KEPT", 5),
                    maxSites = number("REGOLITH_PAGES_MAX_SITES", 200),
                ),
            )
        }

        private val DOMAIN = Regex("[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?(\\.[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?)+", RegexOption.IGNORE_CASE)

        /** One listener's TLS settings under [prefix]: none of them means plain HTTP. */
        private fun readTls(prefix: String, text: (String) -> String): Tls? {
            val certificate = text("${prefix}_CERT")
            val key = text("${prefix}_KEY")
            val clientCa = text("${prefix}_CLIENT_CA")

            if (certificate.isEmpty() && key.isEmpty()) {
                check(clientCa.isEmpty()) { "${prefix}_CLIENT_CA needs ${prefix}_CERT and ${prefix}_KEY" }
                return null
            }

            check(certificate.isNotEmpty() && key.isNotEmpty()) { "Set both ${prefix}_CERT and ${prefix}_KEY, or neither" }

            return Tls(Path(certificate), Path(key), clientCa.ifEmpty { null }?.let { Path(it) })
        }

        private fun readToken(env: Map<String, String>): String {
            val inline = env["REGOLITH_PAGES_TOKEN"]?.trim().orEmpty()
            val file = env["REGOLITH_PAGES_TOKEN_FILE"]?.trim().orEmpty()
            check(inline.isEmpty() || file.isEmpty()) { "Set REGOLITH_PAGES_TOKEN or REGOLITH_PAGES_TOKEN_FILE, not both" }
            val token = if (file.isNotEmpty()) Path(file).readText().trim() else inline
            // there is no unauthenticated intake: whoever reaches it could otherwise publish as anyone.
            check(token.length >= MIN_TOKEN_CHARS) { "REGOLITH_PAGES_TOKEN must be at least $MIN_TOKEN_CHARS characters" }

            return token
        }
    }
}
