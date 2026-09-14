package io.reified.regolith.server.app

import io.reified.regolith.server.domain.SandboxName
import io.reified.regolith.server.support.TestServer
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class OrphanHomesTest {
    private val kept = SandboxName.parse("kept")
    private val lost = SandboxName.parse("lost")

    private fun TestServer.orphans() = OrphanHomes(sandboxes, homes)

    @Test
    fun `a home without a record stops startup and names the way out`() = runBlocking {
        TestServer().use { server ->
            server.sandboxes.getOrCreate(kept, SandboxRequest())
            server.homes.disks[kept] = 4096
            server.homes.disks[lost] = 2048

            assertEquals(listOf(lost), server.orphans().find())
            val refusal = assertFailsWith<IllegalStateException> { server.orphans().requireNone() }
            assertTrue("lost" in refusal.message.orEmpty() && "orphans adopt" in refusal.message.orEmpty())
        }
    }

    @Test
    fun `adopting keeps the files and the size, deleting removes the home`() = runBlocking {
        TestServer().use { server ->
            server.homes.disks[lost] = 2048
            val adopted = server.orphans().adopt().single()

            assertEquals(2048, adopted.resources.homeMb)
            assertEquals("true", adopted.labels[Sandboxes.ADOPTED_LABEL])
            server.orphans().requireNone()
            assertTrue(server.homes.destroyed.isEmpty())

            server.homes.disks[SandboxName.parse("stray")] = 512
            assertEquals(listOf(SandboxName.parse("stray")), server.orphans().delete())
            assertEquals(listOf(SandboxName.parse("stray")), server.homes.destroyed)
            server.orphans().requireNone()
        }
    }
}
