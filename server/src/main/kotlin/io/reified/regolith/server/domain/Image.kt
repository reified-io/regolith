package io.reified.regolith.server.domain

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Reading an image reference: a repository, with an optional tag or digest after it. */
object ImageRef {
    /** The reference without its tag or digest — what a sandbox names when it tracks an image. */
    fun repositoryOf(reference: String): String {
        val withoutDigest = reference.substringBefore('@')
        val tag = withoutDigest.lastIndexOf(':')

        // a registry port lives in the first segment, so only a colon after the last slash is a tag.
        return if (tag > withoutDigest.lastIndexOf('/')) withoutDigest.take(tag) else withoutDigest
    }

    /** Whether the reference is exact: a digest, or a tag other than `latest`. */
    fun pinned(reference: String): Boolean =
        '@' in reference || (repositoryOf(reference) != reference && !reference.endsWith(":latest"))
}

/**
 * How a sandbox chooses the image its sessions run. The choice is resolved again at the start of
 * every session, so a sandbox that follows the server moves to the image the server offers now,
 * without being recreated and without touching its home.
 */
@Serializable
sealed interface ImagePolicy {

    /** The server's default image, whichever that is when a session starts. */
    @Serializable
    @SerialName("default")
    data object Default : ImagePolicy

    /** The image the server offers for one repository, whichever version that is. */
    @Serializable
    @SerialName("track")
    data class Track(val repository: String) : ImagePolicy {
        init {
            requireValid(repository.length in 1..MAX_REFERENCE) { "An image repository is 1 to $MAX_REFERENCE characters" }
        }
    }

    /** One exact reference, for a sandbox whose tools must not change under its caller. */
    @Serializable
    @SerialName("pin")
    data class Pin(val reference: String) : ImagePolicy {
        init {
            requireValid(reference.length in 1..MAX_REFERENCE) { "An image reference is 1 to $MAX_REFERENCE characters" }
        }
    }

    companion object {
        const val MAX_REFERENCE = 256
    }
}

/**
 * The images a server offers: the [default] a sandbox gets when it asks for nothing, and every
 * reference a caller may name. One image per repository, so tracking one is never ambiguous.
 *
 * Resolution only ever answers with a reference from this list, so what a caller sends is matched
 * here and never becomes a command line of its own.
 */
data class ImageCatalog(val default: String, val allowed: List<String>) {
    init {
        check(default in allowed) { "The default image `$default` is not among the allowed images" }
        check(allowed.all(ImageRef::pinned)) { "Every allowed image must name a tag other than `latest`, or a digest" }
        val repositories = allowed.map(ImageRef::repositoryOf)
        val duplicate = repositories.groupBy { it }.entries.firstOrNull { it.value.size > 1 }
        check(duplicate == null) { "Two allowed images name the repository `${duplicate?.key}`; a sandbox tracking it could not tell them apart" }
    }

    /** Throws [RegolithError.Invalid] unless the policy names something this server offers. */
    fun requireAllowed(policy: ImagePolicy) {
        when (policy) {
            ImagePolicy.Default -> Unit
            // a tag here is the likely mistake: tracking follows a repository, pinning takes the tag.
            is ImagePolicy.Track -> requireValid(offered(policy.repository) != null) {
                "This server offers no image of `${policy.repository}`" +
                    if (ImageRef.pinned(policy.repository)) "; tracking takes a repository, without its tag or digest" else ""
            }
            is ImagePolicy.Pin -> requireValid(policy.reference in allowed) {
                "Image `${policy.reference}` is not allowed on this server"
            }
        }
    }

    /** The reference a session started now would run, or null when the server no longer offers it. */
    fun resolveOrNull(policy: ImagePolicy): String? = when (policy) {
        ImagePolicy.Default -> default
        is ImagePolicy.Track -> offered(policy.repository)
        is ImagePolicy.Pin -> policy.reference
    }

    /** The reference a session starts on; only a tracked repository the server dropped has none. */
    fun resolve(policy: ImagePolicy): String = when (policy) {
        ImagePolicy.Default -> default
        is ImagePolicy.Track -> offered(policy.repository)
            ?: throw RegolithError.Unavailable("This server no longer offers an image of `${policy.repository}`")
        is ImagePolicy.Pin -> policy.reference
    }

    private fun offered(repository: String): String? = allowed.firstOrNull { ImageRef.repositoryOf(it) == repository }
}
