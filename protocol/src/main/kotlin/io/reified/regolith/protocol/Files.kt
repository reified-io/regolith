package io.reified.regolith.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.time.Instant

@Serializable
public enum class EntryType {
    @SerialName("file")
    FILE,

    @SerialName("directory")
    DIRECTORY,

    @SerialName("symlink")
    SYMLINK,

    @SerialName("other")
    OTHER,
}

/** A filesystem entry inside a sandbox; a symlink is described, not followed. */
@Serializable
public data class FileEntry(
    val path: String,
    val name: String,
    val type: EntryType,
    val size: Long,
    val modifiedAt: Instant,
    val mode: Int,
)

@Serializable
public data class DirectoryListing(
    val path: String,
    val entries: List<FileEntry>,
)
