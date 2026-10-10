package io.reified.regolith.server.ops

import io.reified.regolith.server.domain.Exec
import io.reified.regolith.server.domain.ExecCommand
import io.reified.regolith.server.domain.ExecId
import io.reified.regolith.server.domain.ExecOutcome
import io.reified.regolith.server.domain.ImagePolicy
import io.reified.regolith.server.domain.Lifecycle
import io.reified.regolith.server.domain.NetworkPolicy
import io.reified.regolith.server.domain.RegolithError
import io.reified.regolith.server.domain.Resources
import io.reified.regolith.server.domain.Sandbox
import io.reified.regolith.server.domain.SandboxId
import io.reified.regolith.server.ops.Doctor.Check
import io.reified.regolith.server.ops.Doctor.Status
import io.reified.regolith.server.ports.PublishedSite
import io.reified.regolith.server.ports.SiteLimits
import io.reified.regolith.server.ports.SitePublisher
import io.reified.regolith.server.store.FileStateStore
import java.nio.file.Files
import java.nio.file.Path
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlinx.coroutines.runBlocking

class DoctorTest {
    @Test
    fun `a report lines up and only a failure makes the exit code non-zero`() {
        val checks = listOf(
            Check("docker", Status.OK, "Docker 29.8.0"),
            Check("lan-control", Status.WARN, "No private gateway answers from this host"),
        )
        assertEquals(
            "ok    docker          Docker 29.8.0\nwarn  lan-control     No private gateway answers from this host",
            Doctor.render(checks),
        )
        assertEquals(0, Doctor.exitCode(checks))
        assertEquals(1, Doctor.exitCode(checks + Check("loop-devices", Status.FAIL, "no loop devices")))
    }

    // a wrong token would otherwise surface only when somebody first publishes, long after the deploy
    @Test
    fun `a pages role that refuses the token is a warning with the fix in it`() = runBlocking {
        val refusing = stub { throw RegolithError.Unavailable("The pages role refused this server's token") }
        val check = Doctor.pagesCheck("http://pages.internal:8082", emptySet()) { refusing }
        assertEquals(Status.WARN, check.status)
        assertContains(check.detail, "REGOLITH_PAGES_TOKEN")

        val answering = stub { SiteLimits(maxFiles = 2000, maxFileBytes = 25L shl 20, maxSiteBytes = 256L shl 20) }
        assertEquals(Status.OK, Doctor.pagesCheck("http://pages.internal:8082", emptySet()) { answering }.status)
        assertEquals(Status.OK, Doctor.pagesCheck(null, emptySet()) { error("nothing to connect to") }.status)
    }

    // a site nobody's record claims is served until somebody notices; this is where somebody notices
    @Test
    fun `a site the pages role serves for no sandbox is a warning that names it`() = runBlocking {
        val serving = stub(sites = listOf("k7m2q9xwtp", "b4d2f8g3h5")) { SiteLimits(maxFiles = 10, maxFileBytes = 1, maxSiteBytes = 1) }

        val check = Doctor.pagesCheck("http://pages.internal:8082", claimed = setOf("k7m2q9xwtp")) { serving }

        assertEquals(Status.WARN, check.status)
        assertContains(check.detail, "b4d2f8g3h5")
        assertContains(check.detail, "orphans delete")
        assertEquals(Status.OK, Doctor.pagesCheck("http://pages.internal:8082", setOf("k7m2q9xwtp", "b4d2f8g3h5")) { serving }.status)
    }

    // the doctor of a new release runs beside the server of the one before, on its records; it has to say
    // what stops the upgrade, not fall over on it.
    @Test
    fun `records of an older schema fail the state check with their count, every exec record included`() = runBlocking<Unit> {
        val root = Files.createTempDirectory("regolith-doctor")
        val sandbox = Sandbox(
            SandboxId.random(), alias = null, site = null, ImagePolicy.Default, Resources(1.0, 512, 1024), NetworkPolicy.Public,
            Lifecycle(15.minutes, 1.days, 14.days), emptyMap(), emptyMap(), Instant.parse("2026-09-13T12:00:00Z"), Instant.parse("2026-09-13T12:00:00Z"),
        )
        val exec = Exec(
            ExecId.random(), sandbox.id, ExecCommand.Shell("make test"), "/home/sandbox", emptyMap(), 2.minutes, stdin = false,
            idempotencyKey = null, startedAt = sandbox.createdAt, outcome = ExecOutcome.Canceled,
        )
        FileStateStore.open(root, "regolith").use { store ->
            store.save(sandbox)
            store.save(exec)
        }
        val records = Files.walk(root).use { paths -> paths.filter { it.toString().endsWith(".json") }.toList() }

        assertEquals(Status.OK, Doctor.stateCheck(root, "regolith").status)
        records.forEach { Files.writeString(it, Files.readString(it).replace("\"schema\":${FileStateStore.SCHEMA},", "\"schema\":4,")) }
        val check = Doctor.stateCheck(root, "regolith")

        assertEquals(Status.FAIL, check.status)
        assertContains(check.detail, "2 records")
        assertContains(check.detail, "schema 4")
        assertEquals(Status.FAIL, Doctor.stateCheck(root, "other").status)
        root.toFile().deleteRecursively()
    }

    @Test
    fun `a pages role is still checked when the records cannot be read, only not against them`() = runBlocking {
        val serving = stub(sites = listOf("k7m2q9xwtp")) { SiteLimits(maxFiles = 10, maxFileBytes = 1, maxSiteBytes = 1) }

        val check = Doctor.pagesCheck("http://pages.internal:8082", claimed = null) { serving }

        assertEquals(Status.OK, check.status)
        assertContains(check.detail, "not compared")
    }

    private fun stub(sites: List<String> = emptyList(), limits: () -> SiteLimits) = object : SitePublisher {
        override suspend fun limits(): SiteLimits = limits()
        override suspend fun publish(site: String, snapshot: Path): PublishedSite = error("not used")
        override suspend fun published(site: String): PublishedSite? = null
        override suspend fun sites(): List<PublishedSite> =
            sites.map { PublishedSite(it, "https://$it.example.test", "r1", 1, 2, Instant.parse("2026-09-01T00:00:00Z"), hasIndex = true) }
        override suspend fun unpublish(site: String) = Unit
    }
}
