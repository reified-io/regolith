package io.reified.regolith.server.app

import io.reified.regolith.server.domain.Sandbox
import io.reified.regolith.server.domain.SandboxName
import io.reified.regolith.server.ports.HomeStore

/**
 * Homes no sandbox record claims. They appear when the state directory is lost or points somewhere
 * else, and they are dangerous twice over: retention walks records, so they are never deleted, and
 * opening a home reuses a disk of the same name, so a new sandbox that happens to take an old name
 * would get the previous owner's files. The server therefore refuses to start while any exist, and
 * only an explicit, one-shot command resolves them.
 */
class OrphanHomes(private val sandboxes: Sandboxes, private val homes: HomeStore) {
    suspend fun find(): List<SandboxName> = homes.list().filter { sandboxes.find(it) == null }.sortedBy { it.value }

    /** Throws when any orphaned home exists, naming them and the commands that resolve them. */
    suspend fun requireNone() {
        val orphans = find()
        check(orphans.isEmpty()) { message(orphans.map { it.value }) }
    }

    /** Recreates a record for every orphaned home, keeping its files and its size. */
    suspend fun adopt(): List<Sandbox> = find().map { name ->
        val size = checkNotNull(homes.sizeMb(name)) { "The home of `$name` has no disk image to adopt" }
        sandboxes.adopt(name, size)
    }

    /** Deletes every orphaned home with its files. */
    suspend fun delete(): List<SandboxName> = find().onEach { homes.destroy(it) }

    companion object {
        fun message(orphans: List<String>): String =
            "Homes with no sandbox record: ${orphans.joinToString()}. The state directory may be lost or wrong, and " +
                "serving now would hand these homes to any new sandbox that takes one of their names. With the " +
                "server stopped, run `orphans` to review them, then `orphans adopt` to give them records again " +
                "or `orphans delete` to delete them and their files."
    }
}
