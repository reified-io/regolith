package io.reified.regolith.server.ops

import io.reified.regolith.server.domain.RegolithError
import io.reified.regolith.server.ops.Doctor.Check
import io.reified.regolith.server.ops.Doctor.Status
import io.reified.regolith.server.ports.PublishedSite
import io.reified.regolith.server.ports.SiteLimits
import io.reified.regolith.server.ports.SitePublisher
import java.nio.file.Path
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
        val check = Doctor.pagesCheck("http://pages.internal:8082") { refusing }
        assertEquals(Status.WARN, check.status)
        assertContains(check.detail, "REGOLITH_PAGES_TOKEN")

        val answering = stub { SiteLimits(maxFiles = 2000, maxFileBytes = 25L shl 20, maxSiteBytes = 256L shl 20) }
        assertEquals(Status.OK, Doctor.pagesCheck("http://pages.internal:8082") { answering }.status)
        assertEquals(Status.OK, Doctor.pagesCheck(null) { error("nothing to connect to") }.status)
    }

    private fun stub(limits: () -> SiteLimits) = object : SitePublisher {
        override suspend fun limits(): SiteLimits = limits()
        override suspend fun publish(site: String, snapshot: Path): PublishedSite = error("not used")
        override suspend fun published(site: String): PublishedSite? = null
        override suspend fun unpublish(site: String) = Unit
    }
}
