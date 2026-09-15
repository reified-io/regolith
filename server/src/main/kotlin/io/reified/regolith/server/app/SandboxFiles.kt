package io.reified.regolith.server.app

import io.reified.regolith.server.config.ServerConfig
import io.reified.regolith.server.domain.EntryType
import io.reified.regolith.server.domain.FileEntry
import io.reified.regolith.server.domain.RegolithError
import io.reified.regolith.server.domain.SandboxId
import io.reified.regolith.server.ports.SandboxRuntime
import java.io.InputStream
import java.io.OutputStream

/**
 * Files inside a sandbox. Every operation runs inside the session as the sandbox user, so it can
 * reach exactly what a command could and nothing more; the sandbox is the boundary, not the path.
 */
class SandboxFiles(
    private val sandboxes: Sandboxes,
    private val sessions: Sessions,
    private val runtime: SandboxRuntime,
    private val health: Health,
    private val config: ServerConfig,
) {

    suspend fun stat(id: SandboxId, path: String): FileEntry = withSession(id) { runtime.stat(id, resolvePath(path)) }

    suspend fun list(id: SandboxId, path: String): List<FileEntry> =
        withSession(id) { runtime.list(id, resolvePath(path), MAX_ENTRIES) }

    /**
     * Checks that [path] is a readable regular file within [maxBytes], or the server's own limit when that is
     * lower or no bound is given, before any byte is sent. Returns the bound the read itself must keep to.
     */
    suspend fun openForRead(id: SandboxId, path: String, maxBytes: Long?): Long {
        val limit = minOf(maxBytes ?: Long.MAX_VALUE, config.limits.maxFileBytes)
        val entry = stat(id, path)
        if (entry.type == EntryType.DIRECTORY) throw RegolithError.Invalid("`${entry.path}` is a directory")

        if (entry.size > limit) {
            throw RegolithError.TooLarge("`${entry.path}` is larger than $limit bytes")
        }

        return limit
    }

    /** Streams the file into [sink], stopping the read the moment it grows past [maxBytes]. */
    suspend fun read(id: SandboxId, path: String, sink: OutputStream, maxBytes: Long) =
        withSession(id) { runtime.read(id, resolvePath(path), sink, maxBytes) }

    suspend fun write(id: SandboxId, path: String, source: InputStream, declaredBytes: Long?): FileEntry {
        val max = config.limits.maxFileBytes
        if (declaredBytes != null && declaredBytes > max) throw RegolithError.TooLarge("Files are limited to $max bytes")
        health.require(Health.STORAGE)

        return withSession(id) { runtime.write(id, resolvePath(path), source, max) }
    }

    suspend fun delete(id: SandboxId, path: String, recursive: Boolean) =
        withSession(id) { runtime.delete(id, resolvePath(path), recursive) }

    private suspend fun <T> withSession(id: SandboxId, action: suspend () -> T): T {
        val sandbox = sandboxes.markUsed(id)

        return sessions.withLease(sandbox) { action() }
    }

    private companion object {
        const val MAX_ENTRIES = 10_000
    }
}
