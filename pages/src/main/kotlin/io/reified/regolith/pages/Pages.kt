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
 * intake one binds to the loopback address by default, so a release can be pushed over a tunnel or a
 * private network without the write surface ever facing a visitor.
 */
fun runPages(config: PagesConfig) {
    val store = ReleaseStore(config.dir, config.limits)
    // read before anything listens: a certificate that cannot serve stops the role rather than a visitor.
    val tls = config.tls?.let { TlsMaterial.load(it) }
    val intake = embeddedServer(Netty, port = config.apiPort, host = config.apiBind) { pagesIntake(config, store) }
    val public = publicServer(config, tls) { publicSites(config, store) }

    Runtime.getRuntime().addShutdownHook(
        Thread {
            log.info { "Regolith pages stopping" }
            intake.stop(gracePeriodMillis = 1_000, timeoutMillis = 5_000)
            public.stop(gracePeriodMillis = 1_000, timeoutMillis = 5_000)
        },
    )

    intake.start(wait = false)
    val scheme = when {
        tls == null -> "http"
        tls.trustStore != null -> "https, client certificates required"
        else -> "https"
    }
    log.info { "Regolith pages serving: domain=[${config.domain}] port=[${config.port}] scheme=[$scheme] intake=[${config.apiBind}:${config.apiPort}] dir=[${config.dir}]" }
    tls?.let { log.info { "Certificate expires: at=[${it.expiresAt}]" } }
    public.start(wait = true)
}

/** The public listener: plain HTTP behind a tunnel or a proxy, or TLS it terminates itself. */
internal fun publicServer(config: PagesConfig, tls: TlsMaterial?, module: io.ktor.server.application.Application.() -> Unit) =
    embeddedServer(
        Netty,
        configure = {
            if (tls == null) {
                connector {
                    host = config.bind
                    port = config.port
                }
            } else {
                sslConnector(tls.keyStore, TlsMaterial.ALIAS, tls::password, tls::password) {
                    host = config.bind
                    port = config.port
                    // a trust store makes the engine require a client certificate signed by it.
                    trustStore = tls.trustStore
                    enabledProtocols = listOf("TLSv1.3", "TLSv1.2")
                }
            }
        },
        module = module,
    )
