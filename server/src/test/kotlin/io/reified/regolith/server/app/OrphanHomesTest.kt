package io.reified.regolith.server.app

import io.reified.regolith.server.domain.SandboxId
import io.reified.regolith.server.support.TestServer
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class OrphanHomesTest {
    private val lost = SandboxId.random()

    private fun TestServer.orphans() = OrphanHomes(sandboxes, homes)

    @Test
    fun `a home without a record stops startup and names the way out`() = runBlocking {
        TestServer().use { server ->
            val kept = server.sandbox("kept").id
            server.homes.disks[kept] = 4096
            server.homes.disks[lost] = 2048

            assertEquals(listOf(lost), server.orphans().find())
            val refusal = assertFailsWith<IllegalStateException> { server.orphans().requireNone() }
            assertTrue(lost.value in refusal.message.orEmpty() && "orphans adopt" in refusal.message.orEmpty())
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

            val stray = SandboxId.random()
            server.homes.disks[stray] = 512
            assertEquals(listOf(stray), server.orphans().delete())
            assertEquals(listOf(stray), server.homes.destroyed)
            server.orphans().requireNone()
        }
    }

    // ids are random, so such a home is taken over by nobody; it is refused so it is not forgotten either
    @Test
    fun `a home from before sandbox ids stops startup, and nothing here touches it`() = runBlocking {
        TestServer().use { server ->
            server.homes.unrecognized += "regolith-user-23-disk"

            val refusal = assertFailsWith<IllegalStateException> { server.orphans().requireNone() }

            assertTrue("regolith-user-23-disk" in refusal.message.orEmpty() && "docker volume rm" in refusal.message.orEmpty())
            assertEquals(emptyList(), server.orphans().adopt())
            assertEquals(emptyList(), server.orphans().delete())
            assertTrue(server.homes.destroyed.isEmpty())
        }
    }
}
