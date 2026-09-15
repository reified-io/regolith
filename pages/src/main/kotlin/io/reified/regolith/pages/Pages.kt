package io.reified.regolith.pages

import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.server.engine.connector
import io.ktor.server.engine.embeddedServer
import io.ktor.server.engine.sslConnector
import io.ktor.server.netty.Netty

private val log = KotlinLogging.logger {}

/**
 * Runs the pages role: one listener for visitors and one for releases.
 *
 * They are separate on purpose. The public one is reachable from the internet and can only read; the
 * intake one binds to the loopback address by default, so a release is pushed from the same machine,
 * over a private network or a tunnel, or over TLS of its own, without the write surface ever sharing a
 * port with a visitor.
 */
fun runPages(config: PagesConfig) {
    val store = ReleaseStore(config.dir, config.limits)
    // read before anything listens: a certificate that cannot serve stops the role rather than a request.
    val tls = config.tls?.let { TlsMaterial.load(it) }
    val apiTls = config.apiTls?.let { TlsMaterial.load(it) }
    val intake = listener(config.apiBind, config.apiPort, apiTls) { pagesIntake(config, store) }
    val public = listener(config.bind, config.port, tls) { publicSites(config, store) }

    Runtime.getRuntime().addShutdownHook(
        Thread {
            log.info { "Regolith pages stopping" }
            intake.stop(gracePeriodMillis = 1_000, timeoutMillis = 5_000)
            public.stop(gracePeriodMillis = 1_000, timeoutMillis = 5_000)
        },
    )

    intake.start(wait = false)
    log.info {
        "Regolith pages serving: domain=[${config.domain}] port=[${config.port}] scheme=[${scheme(tls)}] " +
            "intake=[${config.apiBind}:${config.apiPort}] intakeScheme=[${scheme(apiTls)}] dir=[${config.dir}]"
    }
    tls?.let { log.info { "Certificate expires: listener=[public] at=[${it.expiresAt}]" } }
    apiTls?.let { log.info { "Certificate expires: listener=[intake] at=[${it.expiresAt}]" } }
    public.start(wait = true)
}

private fun scheme(tls: TlsMaterial?): String = when {
    tls == null -> "http"
    tls.trustStore != null -> "https, client certificates required"
    else -> "https"
}

/** One listener: plain HTTP on a private address or behind a tunnel or a proxy, or TLS it terminates itself. */
internal fun listener(host: String, port: Int, tls: TlsMaterial?, module: io.ktor.server.application.Application.() -> Unit) =
    embeddedServer(
        Netty,
        configure = {
            if (tls == null) {
                connector {
                    this.host = host
                    this.port = port
                }
            } else {
                sslConnector(tls.keyStore, TlsMaterial.ALIAS, tls::password, tls::password) {
                    this.host = host
                    this.port = port
                    // a trust store makes the engine require a client certificate signed by it.
                    trustStore = tls.trustStore
                    enabledProtocols = listOf("TLSv1.3", "TLSv1.2")
                }
            }
        },
        module = module,
    )
