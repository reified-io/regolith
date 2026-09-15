package io.reified.regolith.server.app

import io.github.oshai.kotlinlogging.KotlinLogging
import io.reified.regolith.server.ports.SitePublisher

/**
 * Sites the pages role serves that no sandbox record claims. They appear when the state directory
 * is lost, or when a sandbox was deleted while the pages role was unreachable: the site stays up
 * with nothing left that knows its label, so no call can take it down. The pages role serves one
 * control plane, so every site it holds is either claimed here or orphaned.
 *
 * Unlike an orphaned home, a site stops nothing: it is public content, and the server keeps serving
 * sandboxes while `doctor` and `orphans` report it and `orphans delete` takes it down.
 */
class OrphanSites(private val publisher: SitePublisher?) {
    /** Labels served by the pages role that are not among [claimed], the labels the records hold. */
    suspend fun find(claimed: Set<String>): List<String> =
        publisher?.sites().orEmpty().map { it.site }.filterNot { it in claimed }.sorted()

    /** Takes every orphaned site down and returns the labels it took down. */
    suspend fun delete(claimed: Set<String>): List<String> {
        val pages = publisher ?: return emptyList()

        return find(claimed).onEach { site ->
            pages.unpublish(site)
            log.info { "Orphaned site taken down: site=[$site]" }
        }
    }

    companion object {
        private val log = KotlinLogging.logger {}

        fun message(sites: List<String>): String =
            "Sites on the pages role that no sandbox claims: ${sites.joinToString()}. They were left by a lost state " +
                "directory or by a sandbox deleted while the pages role was unreachable, and nothing can take them down " +
                "through the API. Run `orphans` to review them, then, with the server stopped, `orphans delete` to take " +
                "them down."
    }
}
