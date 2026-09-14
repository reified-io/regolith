package io.reified.regolith.server

import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.reified.regolith.server.app.CpuGuard
import io.reified.regolith.server.app.Execs
import io.reified.regolith.server.app.Health
import io.reified.regolith.server.app.NetworkGuard
import io.reified.regolith.server.app.OrphanHomes
import io.reified.regolith.server.app.SandboxFiles
import io.reified.regolith.server.app.SitePublishing
import io.reified.regolith.server.app.Sandboxes
import io.reified.regolith.server.app.Services
import io.reified.regolith.server.app.Sessions
import io.reified.regolith.server.app.StorageGuard
import io.reified.regolith.server.app.Sweeper
import io.reified.regolith.server.app.every
import io.reified.regolith.server.config.ServerConfig
import io.reified.regolith.server.docker.ContainerSpec
import io.reified.regolith.server.docker.DockerCli
import io.reified.regolith.server.docker.DockerRuntime
import io.reified.regolith.server.docker.Helpers
import io.reified.regolith.server.docker.HomeDisks
import io.reified.regolith.server.docker.HostFirewall
import io.reified.regolith.pages.PagesConfig
import io.reified.regolith.pages.runPages
import io.reified.regolith.server.domain.StopReason
import io.reified.regolith.server.http.regolithApi
import io.reified.regolith.server.publish.PagesPublisher
import io.reified.regolith.server.ops.Doctor
import io.reified.regolith.server.store.FileStateStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlin.system.exitProcess
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

private val log = KotlinLogging.logger {}

private const val USAGE = """usage: server [serve | pages | doctor | orphans [adopt | delete]]

  serve            run the control plane API (the default)
  pages            run the public role: serve published sites and take in releases
  doctor           check this host against every startup precondition, changing nothing
  orphans          list homes no sandbox record claims
  orphans adopt    give each of them a record again, keeping its files
  orphans delete   delete them and their files

Each role reads its own REGOLITH_* variables; see docs/configuration.md."""

fun main(args: Array<String>) {
    when (args.toList()) {
        emptyList<String>(), listOf("serve") -> serve(serverConfig())
        listOf("pages") -> runPages(configured("pages") { PagesConfig.fromEnvironment() })
        listOf("doctor") -> exitProcess(doctor(serverConfig()))
        listOf("orphans"), listOf("orphans", "adopt"), listOf("orphans", "delete") -> exitProcess(orphans(serverConfig(), args.getOrNull(1)))
        else -> {
            System.err.println(USAGE)
            exitProcess(2)
        }
    }
}

private fun serverConfig(): ServerConfig = configured("the server") { ServerConfig.fromEnvironment() }

/** Reads one role's configuration; an invalid value stops the process instead of starting half of it. */
private fun <T> configured(role: String, read: () -> T): T = try {
    read()
} catch (e: IllegalStateException) {
    log.error { "Invalid configuration for $role: ${e.message}" }
    exitProcess(2)
}

/** Everything the application is made of, built once, by hand, in dependency order. */
private class Application(val config: ServerConfig, val store: FileStateStore) {
    val version = ServerConfig::class.java.`package`?.implementationVersion ?: "dev"
    val clock = Clock.System
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val health = Health()
    val docker = DockerCli(config.docker)
    val runtime = DockerRuntime(docker, ContainerSpec(config.namespace, config.shell, config.homeReadBps, config.homeWriteBps))
    val helpers = Helpers(runBlocking { Helpers.resolveImage(docker, config.helperImage) }, config.namespace)
    val homes = HomeDisks(docker, helpers, config.namespace, config.stateDir, reserveMb = config.minFreeMb)
    val enforcer = HostFirewall(docker, helpers, config.namespace, config.blockedCidrs)
    val sessions = Sessions(runtime, homes, enforcer, health, clock, config.maxSessions)
    val execs = Execs(store, sessions, runtime, config, clock, scope)
    val sandboxes = Sandboxes(store, sessions, execs, homes, config, clock)
    val files = SandboxFiles(sandboxes, sessions, runtime, health, config)
    val orphans = OrphanHomes(sandboxes, homes)
    // optional: without a pages role configured, the publish endpoints answer `not_implemented`.
    val publisher = config.pagesUrl?.let { PagesPublisher(it, checkNotNull(config.pagesToken)) }
    val sites = SitePublishing(sandboxes, sessions, runtime, config, publisher)
}

/*
 * startup runs its preconditions before the api listens, and any failure among them exits instead of
 * serving sandboxes without a network floor, bounded homes, or records for every home.
 */
private fun serve(config: ServerConfig) {
    log.info { "Regolith starting: namespace=[${config.namespace}]" }
    val app = open(config)
    val networkGuard = NetworkGuard(app.enforcer, app.sandboxes, app.sessions, app.health)
    val storageGuard = StorageGuard(app.homes, app.health, config.minFreeMb * 1024 * 1024)
    val sweeper = Sweeper(app.sandboxes, app.sessions, app.clock)
    val cpuGuard = CpuGuard(app.sessions, app.runtime, app.clock, config.limits.unattendedCpu)

    startOrExit(app) {
        app.sandboxes.load()
        app.execs.recover(app.sandboxes.all())
        val network = app.runtime.initialize()
        app.homes.recover()
        app.orphans.requireNone()
        networkGuard.install(network)
        storageGuard.tick()
    }

    app.scope.every(15.seconds, "lifecycle sweep") { sweeper.tick() }
    app.scope.every(10.seconds, "storage guard") { storageGuard.tick() }
    app.scope.every(5.minutes, "network guard") { networkGuard.tick() }
    app.scope.every(30.seconds, "cpu guard") { cpuGuard.tick() }

    val services = Services(config, app.version, app.health, app.sandboxes, app.sessions, app.execs, app.files, app.sites)
    val server = embeddedServer(CIO, port = config.port, host = config.bind) { regolithApi(services) }
    Runtime.getRuntime().addShutdownHook(
        Thread {
            log.info { "Regolith stopping" }
            server.stop(gracePeriodMillis = 1_000, timeoutMillis = 5_000)
            runBlocking {
                for (sandbox in app.sandboxes.all()) app.execs.interrupt(sandbox.name, StopReason.SERVER_RESTARTED)
                app.sessions.stopAll(StopReason.SERVER_RESTARTED)
            }
            app.scope.cancel()
            app.store.close()
        },
    )
    log.info { "Regolith serving: version=[${app.version}] port=[${config.port}]" }
    server.start(wait = true)
}

private fun doctor(config: ServerConfig): Int {
    val checks = runBlocking { Doctor(config, DockerCli(config.docker)).run() }
    println(Doctor.render(checks))

    return Doctor.exitCode(checks)
}

/** Resolves orphaned homes; needs the state lock, so the server must be stopped. */
private fun orphans(config: ServerConfig, action: String?): Int {
    val app = open(config)

    return try {
        runBlocking {
            app.sandboxes.load()
            when (action) {
                "adopt" -> app.orphans.adopt().forEach { println("adopted  ${it.name}  ${it.resources.homeMb} MB") }
                "delete" -> app.orphans.delete().forEach { println("deleted  $it") }
                else -> app.orphans.find().also { found ->
                    if (found.isEmpty()) println("no orphaned homes")
                    found.forEach { println("orphan   $it  ${app.homes.sizeMb(it) ?: "?"} MB") }
                }
            }
        }
        0
    } catch (e: Exception) {
        System.err.println(e.message)
        1
    } finally {
        app.scope.cancel()
        app.store.close()
    }
}

private fun open(config: ServerConfig): Application {
    val store = try {
        FileStateStore.open(config.stateDir, config.namespace)
    } catch (e: IllegalStateException) {
        log.error { "Cannot open the state directory: ${e.message}" }
        exitProcess(1)
    }

    return try {
        Application(config, store)
    } catch (e: Exception) {
        log.error(e) { "Regolith refuses to start" }
        store.close()
        exitProcess(1)
    }
}

private fun startOrExit(app: Application, preconditions: suspend () -> Unit) {
    try {
        runBlocking { preconditions() }
    } catch (e: Exception) {
        log.error(e) { "Regolith refuses to start" }
        app.store.close()
        exitProcess(1)
    }
}
