package io.reified.regolith.server.store

import io.reified.regolith.server.domain.Exec
import io.reified.regolith.server.domain.ExecCommand
import io.reified.regolith.server.domain.ExecId
import io.reified.regolith.server.domain.ExecOutcome
import io.reified.regolith.server.domain.ImagePolicy
import io.reified.regolith.server.domain.Lifecycle
import io.reified.regolith.server.domain.NetworkPolicy
import io.reified.regolith.server.domain.RegolithError
import io.reified.regolith.server.domain.Alias
import io.reified.regolith.server.domain.Cidr
import io.reified.regolith.server.domain.Resources
import io.reified.regolith.server.domain.Sandbox
import io.reified.regolith.server.domain.SandboxId
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

class FileStateStoreTest {
    private val root = Files.createTempDirectory("regolith-store")

    @AfterTest
    fun cleanUp() {
        root.toFile().deleteRecursively()
    }

    private val sandbox = Sandbox(
        id = SandboxId.random(),
        alias = Alias.parse("keeper"),
        imagePolicy = ImagePolicy.Pin("example/sandbox:1"),
        resources = Resources(1.5, 1024, 4096),
        network = NetworkPolicy.Allowlist(listOf(Cidr.parse("140.82.112.0/20"))),
        lifecycle = Lifecycle(15.minutes, 1.days, 14.days),
        env = mapOf("LANG" to "C.UTF-8"),
        labels = mapOf("team" to "core"),
        createdAt = Instant.parse("2026-09-13T12:00:00Z"),
        lastUsedAt = Instant.parse("2026-09-13T12:30:00Z"),
    )

    @Test
    fun `records survive a reopen and deletion takes everything`() = runBlocking {
        val exec = Exec(
            id = ExecId.random(),
            sandbox = sandbox.id,
            command = ExecCommand.Argv(listOf("make", "test")),
            cwd = "/home/sandbox",
            env = emptyMap(),
            timeout = 2.minutes,
            stdin = false,
            idempotencyKey = "k1",
            startedAt = sandbox.lastUsedAt,
            outcome = ExecOutcome.Exited(2),
        )
        FileStateStore.open(root, "regolith").use { store ->
            store.save(sandbox)
            store.save(exec)
            Files.writeString(store.outputFile(sandbox.id, exec.id), "log")
        }

        FileStateStore.open(root, "regolith").use { store ->
            assertEquals(listOf(sandbox), store.sandboxes())
            assertEquals(listOf(exec), store.execs(sandbox.id))
            store.delete(sandbox.id)
            assertEquals(emptyList(), store.sandboxes())
            assertTrue(!Files.exists(store.outputFile(sandbox.id, exec.id)))
        }
    }

    @Test
    fun `a record edited by hand is read through the same parsers as a request`() = runBlocking {
        FileStateStore.open(root, "regolith").use { store ->
            store.save(sandbox)
            val record = root.resolve("sandboxes").resolve(sandbox.id.value).resolve("sandbox.json")
            Files.writeString(record, Files.readString(record).replace("140.82.112.0/20", "140.82.112.0 -j RETURN\\n-A X -s 1.1.1.1/20"))

            assertFailsWith<RegolithError.Invalid> { store.sandboxes() }
        }
    }

    @Test
    fun `one directory serves one server and one namespace`() {
        FileStateStore.open(root, "regolith").use {
            assertFailsWith<IllegalStateException> { FileStateStore.open(root, "regolith") }
        }
        assertFailsWith<IllegalStateException> { FileStateStore.open(root, "other") }
    }
}
