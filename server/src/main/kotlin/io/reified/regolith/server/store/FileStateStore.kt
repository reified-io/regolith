package io.reified.regolith.server.store

import io.reified.regolith.server.domain.Exec
import io.reified.regolith.server.domain.ExecId
import io.reified.regolith.server.domain.ImagePolicy
import io.reified.regolith.server.domain.Sandbox
import io.reified.regolith.server.domain.SandboxId
import io.reified.regolith.server.ports.StateStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.readText

/**
 * Records as JSON files under the state directory:
 *
 * ```
 * lock, namespace
 * sandboxes/<sandbox>/sandbox.json
 * sandboxes/<sandbox>/execs/<id>.json, <id>.log
 * ```
 *
 * Each file is the domain model wrapped with [SCHEMA]. There are no migrations: a change to a
 * persisted domain type raises [SCHEMA], and a server refuses to start on records of another one.
 * Writes go through a temporary file and an atomic rename, so a crash leaves the old record or the
 * new one, never half of either.
 */
class FileStateStore private constructor(private val root: Path, private val lock: FileLock) : StateStore, AutoCloseable {
    @Serializable
    private data class Stored<T>(val schema: Int, val value: T)

    override suspend fun sandboxes(): List<Sandbox> = io {
        val dir = root.resolve("sandboxes")
        if (!dir.exists()) return@io emptyList()
        dir.listDirectoryEntries().filter { it.isDirectory() }.mapNotNull { sandboxDir ->
            sandboxDir.resolve(SANDBOX_FILE).takeIf { it.exists() }?.let { readRecord(it, Sandbox.serializer()) }
        }
    }

    override suspend fun save(sandbox: Sandbox) = io {
        writeRecord(sandboxDir(sandbox.id).resolve(SANDBOX_FILE), sandbox, Sandbox.serializer())
    }

    override suspend fun delete(sandbox: SandboxId) = io {
        val dir = sandboxDir(sandbox)
        if (dir.exists()) dir.toFile().deleteRecursively()
        Unit
    }

    override suspend fun execs(sandbox: SandboxId): List<Exec> = io {
        val dir = execDir(sandbox)
        if (!dir.exists()) return@io emptyList()
        dir.listDirectoryEntries("*.json").map { readRecord(it, Exec.serializer()) }
    }

    override suspend fun save(exec: Exec) = io {
        writeRecord(execDir(exec.sandbox).resolve("${exec.id}.json"), exec, Exec.serializer())
    }

    override suspend fun deleteExec(sandbox: SandboxId, id: ExecId) = io {
        Files.deleteIfExists(execDir(sandbox).resolve("$id.json"))
        Files.deleteIfExists(outputFile(sandbox, id))
        Unit
    }

    override fun outputFile(sandbox: SandboxId, id: ExecId): Path = execDir(sandbox).resolve("$id.log")

    override fun close() {
        lock.release()
        lock.channel().close()
    }

    private fun sandboxDir(sandbox: SandboxId): Path = root.resolve("sandboxes").resolve(sandbox.value)

    private fun execDir(sandbox: SandboxId): Path = sandboxDir(sandbox).resolve("execs")

    private fun <T> writeRecord(file: Path, value: T, serializer: KSerializer<T>) {
        Files.createDirectories(file.parent)
        val temporary = file.resolveSibling("${file.name}.tmp")
        Files.writeString(temporary, json.encodeToString(Stored.serializer(serializer), Stored(SCHEMA, value)))
        Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    }

    private suspend fun <T> io(action: () -> T): T = withContext(Dispatchers.IO) { action() }

    companion object {
        /** Version of the persisted records; see the class comment. */
        const val SCHEMA = 3

        private const val SANDBOX_FILE = "sandbox.json"
        private val json = Json { encodeDefaults = true }

        /** Ids of the sandboxes recorded under [root], read without taking the lock. */
        fun recordedIds(root: Path): List<String> {
            val dir = root.resolve("sandboxes")
            if (!dir.exists()) return emptyList()

            return dir.listDirectoryEntries().filter { it.resolve(SANDBOX_FILE).exists() }.map { it.name }.sorted()
        }

        /** How every recorded sandbox picks its image, by sandbox, read without taking the lock. */
        fun recordedImagePolicies(root: Path): Map<String, ImagePolicy> {
            val dir = root.resolve("sandboxes")
            if (!dir.exists()) return emptyMap()

            return dir.listDirectoryEntries()
                .filter { it.resolve(SANDBOX_FILE).exists() }
                .associate { it.name to readRecord(it.resolve(SANDBOX_FILE), Sandbox.serializer()).imagePolicy }
        }

        private fun <T> readRecord(file: Path, serializer: KSerializer<T>): T {
            val stored = json.decodeFromString(Stored.serializer(serializer), file.readText())
            check(stored.schema == SCHEMA) {
                "State file ${file.name} has schema ${stored.schema}, this server reads schema $SCHEMA"
            }

            return stored.value
        }

        /** Whether another process holds the lock on [root]. */
        fun inUse(root: Path): Boolean {
            val file = root.resolve("lock")
            if (!file.exists()) return false
            FileChannel.open(file, StandardOpenOption.WRITE).use { channel ->
                val lock = try {
                    channel.tryLock()
                } catch (_: OverlappingFileLockException) {
                    return true
                }
                lock?.release()
                return lock == null
            }
        }

        /**
         * Opens [root] for exclusive use by this process and pins it to [namespace]: two servers on one
         * state directory, or one directory reused under another namespace, would each believe they
         * own the other's sessions.
         */
        fun open(root: Path, namespace: String): FileStateStore {
            Files.createDirectories(root)
            val channel = FileChannel.open(root.resolve("lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE)
            // another process holding the lock yields null; this process holding it throws instead.
            val lock = try {
                channel.tryLock()
            } catch (_: OverlappingFileLockException) {
                null
            }

            if (lock == null) {
                channel.close()
                error("Another regolith server is using $root")
            }

            val pin = root.resolve("namespace")

            if (pin.exists()) {
                val pinned = pin.readText().trim()
                check(pinned == namespace) { "$root belongs to namespace `$pinned`, not `$namespace`" }
            } else {
                Files.writeString(pin, namespace)
            }

            return FileStateStore(root, lock)
        }
    }
}
