package io.reified.regolith.server.app

import io.reified.regolith.server.domain.Sandbox
import io.reified.regolith.server.domain.SandboxId
import io.reified.regolith.server.ports.HomeStore

/**
 * Homes no sandbox record claims. They appear when the state directory is lost or points somewhere
 * else, and each holds somebody's files that no call can reach and retention never deletes, because
 * retention walks records. The server therefore refuses to start while any exist, and only an
 * explicit, one-shot command resolves them.
 *
 * A home whose name holds no sandbox id is refused the same way, though no command can resolve it:
 * it comes from a server older than ids, and nothing but an operator can decide what it held.
 */
class OrphanHomes(private val sandboxes: Sandboxes, private val homes: HomeStore) {
    suspend fun find(): List<SandboxId> = homes.list().filter { sandboxes.find(it) == null }.sortedBy { it.value }

    /** Homes whose name holds no sandbox id at all; see [HomeStore.unrecognized]. */
    suspend fun unrecognized(): List<String> = homes.unrecognized().sorted()

    /** Throws when any orphaned or unrecognized home exists, naming them and the way to resolve them. */
    suspend fun requireNone() {
        val orphans = find()
        check(orphans.isEmpty()) { message(orphans.map { it.value }) }
        val unrecognized = unrecognized()
        check(unrecognized.isEmpty()) { unrecognizedMessage(unrecognized) }
    }

    /** Recreates a record for every orphaned home, keeping its files and its size. */
    suspend fun adopt(): List<Sandbox> = find().map { id ->
        val size = checkNotNull(homes.sizeMb(id)) { "The home of `$id` has no disk image to adopt" }
        sandboxes.adopt(id, size)
    }

    /** Deletes every orphaned home with its files. */
    suspend fun delete(): List<SandboxId> = find().onEach { homes.destroy(it) }

    companion object {
        fun unrecognizedMessage(volumes: List<String>): String =
            "Home disks whose name holds no sandbox id: ${volumes.joinToString()}. They come from a server older " +
                "than sandbox ids, or were made by hand: no record can claim them, retention never deletes them, and " +
                "nothing here can adopt them. With the server stopped, keep what you need from them and remove each " +
                "with `docker volume rm`."

        fun message(orphans: List<String>): String =
            "Homes with no sandbox record: ${orphans.joinToString()}. The state directory may be lost or wrong, and " +
                "serving now would leave their files where no call reaches them and retention never deletes them. " +
                "Run `orphans` to review them, then, with the server stopped, `orphans adopt` to give them records " +
                "again or `orphans delete` to delete them and their files."
    }
}
