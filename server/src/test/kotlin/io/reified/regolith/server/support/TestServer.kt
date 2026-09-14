package io.reified.regolith.server.support

import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import io.reified.regolith.sdk.RegolithClient
import io.reified.regolith.server.app.Execs
import io.reified.regolith.server.app.Health
import io.reified.regolith.server.app.SandboxFiles
import io.reified.regolith.server.app.SitePublishing
import io.reified.regolith.server.app.Sandboxes
import io.reified.regolith.server.app.Services
import io.reified.regolith.server.app.Sessions
import io.reified.regolith.server.app.Sweeper
import io.reified.regolith.server.config.ServerConfig
import io.reified.regolith.server.http.regolithApi
import io.reified.regolith.server.store.FileStateStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import java.nio.file.Files
import java.nio.file.Path

const val TEST_TOKEN = "test-token-that-is-long-enough-for-regolith"

/** A complete application over fakes and a real state store in a temporary directory. */
class TestServer(
    env: Map<String, String> = emptyMap(),
    val clock: MutableClock = MutableClock(),
) : AutoCloseable {

    val stateDir: Path = Files.createTempDirectory("regolith-test")
    val config = ServerConfig.fromEnvironment(
        mapOf("REGOLITH_TOKEN" to TEST_TOKEN, "REGOLITH_STATE_DIR" to stateDir.toString()) + env,
        version = "test",
    )
    val runtime = FakeRuntime()
    val homes = FakeHomes()
    val enforcer = FakeEnforcer()
    val health = Health()
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val store = FileStateStore.open(stateDir, config.namespace)
    val sessions = Sessions(runtime, homes, enforcer, health, clock, config.maxSessions)
    val execs = Execs(store, sessions, runtime, config, clock, scope)
    val sandboxes = Sandboxes(store, sessions, execs, homes, config, clock)
    val files = SandboxFiles(sandboxes, sessions, runtime, health, config)
    val sweeper = Sweeper(sandboxes, sessions, clock)
    val publisher = FakePublisher()
    val sites = SitePublishing(sandboxes, sessions, runtime, config, publisher)
    val services = Services(config, "test", health, sandboxes, sessions, execs, files, sites)

    override fun close() {
        scope.cancel()
        store.close()
        stateDir.toFile().deleteRecursively()
    }
}

/** Runs [block] against the api of a fresh [TestServer], through the real sdk. */
fun apiTest(
    env: Map<String, String> = emptyMap(),
    block: suspend ApplicationTestBuilder.(server: TestServer, client: RegolithClient) -> Unit,
) {
    TestServer(env).use { server ->
        testApplication {
            application { regolithApi(server.services) }
            val client = RegolithClient("http://localhost", TEST_TOKEN, createClient {})
            block(server, client)
        }
    }
}
