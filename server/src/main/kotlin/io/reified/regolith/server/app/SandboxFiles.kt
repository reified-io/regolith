package io.reified.regolith.server.app

import io.reified.regolith.server.config.ServerConfig
import io.reified.regolith.server.domain.EntryType
import io.reified.regolith.server.domain.FileEntry
import io.reified.regolith.server.domain.RegolithError
import io.reified.regolith.server.domain.SandboxName
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

    suspend fun stat(name: SandboxName, path: String): FileEntry = withSession(name) { runtime.stat(name, resolvePath(path)) }

    suspend fun list(name: SandboxName, path: String): List<FileEntry> =
        withSession(name) { runtime.list(name, resolvePath(path), MAX_ENTRIES) }

    /** Checks that [path] is a readable regular file within the limit, before any byte is sent. */
    suspend fun openForRead(name: SandboxName, path: String): FileEntry {
        val entry = stat(name, path)
        if (entry.type == EntryType.DIRECTORY) throw RegolithError.Invalid("`${entry.path}` is a directory")

        if (entry.size > config.limits.maxFileBytes) {
            throw RegolithError.TooLarge("`${entry.path}` is larger than ${config.limits.maxFileBytes} bytes")
        }

        return entry
    }

    suspend fun read(name: SandboxName, path: String, sink: OutputStream) =
        withSession(name) { runtime.read(name, resolvePath(path), sink, config.limits.maxFileBytes) }

    suspend fun write(name: SandboxName, path: String, source: InputStream, declaredBytes: Long?): FileEntry {
        val max = config.limits.maxFileBytes
        if (declaredBytes != null && declaredBytes > max) throw RegolithError.TooLarge("Files are limited to $max bytes")
        health.require(Health.STORAGE)

        return withSession(name) { runtime.write(name, resolvePath(path), source, max) }
    }

    suspend fun delete(name: SandboxName, path: String, recursive: Boolean) =
        withSession(name) { runtime.delete(name, resolvePath(path), recursive) }

    private suspend fun <T> withSession(name: SandboxName, action: suspend () -> T): T {
        val sandbox = sandboxes.markUsed(name)

        return sessions.withLease(sandbox) { action() }
    }

    private companion object {
        const val MAX_ENTRIES = 10_000
    }
}
