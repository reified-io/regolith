package io.reified.regolith.pages

import kotlinx.serialization.Serializable
import kotlin.time.Instant

/**
 * Anything a caller can fix, carried to the API as an RFC 9457 problem document. The public listener
 * never raises these: it only ever answers with a page or a 404.
 */
sealed class PagesError(message: String) : RuntimeException(message) {
    class Invalid(message: String) : PagesError(message)

    class NotFound(message: String) : PagesError(message)

    class TooLarge(message: String) : PagesError(message)

    class Conflict(message: String) : PagesError(message)
}

internal inline fun requireValid(value: Boolean, message: () -> String) {
    if (!value) throw PagesError.Invalid(message())
}

/**
 * The name of one site. It is the label of its hostname, so the same rules as a sandbox name apply:
 * a site built in sandbox `user-23` is published at `user-23.<domain>` without a second encoding.
 */
@JvmInline
value class SiteName private constructor(val value: String) {
    override fun toString(): String = value

    companion object {
        private val shape = Regex("[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?")

        fun parse(raw: String): SiteName {
            requireValid(shape.matches(raw)) { "A site name is 1-63 lowercase letters, digits and inner hyphens" }

            return SiteName(raw)
        }
    }
}

/** A server-generated release identifier. */
@JvmInline
value class ReleaseId private constructor(val value: String) {
    override fun toString(): String = value

    companion object {
        private val shape = Regex("[0-9a-f]{32}")

        fun random(): ReleaseId = ReleaseId(java.util.UUID.randomUUID().toString().replace("-", ""))

        fun parse(raw: String): ReleaseId {
            requireValid(shape.matches(raw)) { "Unknown release" }

            return ReleaseId(raw)
        }
    }
}

/**
 * One file of a release: where it is served, what it holds, and the type it is served as. The hash is
 * the whole identity — two releases naming the same bytes share one blob, and a path is never used to
 * reach the filesystem.
 */
@Serializable
data class FileEntry(val path: String, val hash: String, val size: Long, val contentType: String)

/** The complete list of files a release serves. Nothing outside it is reachable. */
@Serializable
data class Manifest(val files: List<FileEntry>) {
    val bytes: Long get() = files.sumOf { it.size }

    // built once: serving is a map lookup, never a walk over the list or over a filesystem.
    private val byPath: Map<String, FileEntry> = files.associateBy { it.path }

    /** The entry at [path], or null. Directory requests resolve to `index.html` before this is called. */
    fun find(path: String): FileEntry? = byPath[path]
}

/** A release as it is recorded: the manifest, when it was made, and whether it was ever activated. */
@Serializable
data class Release(val id: String, val site: String, val manifest: Manifest, val createdAt: Instant)
