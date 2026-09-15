package io.reified.regolith.protocol.pages

import kotlinx.serialization.Serializable
import kotlin.time.Instant

/**
 * What the intake listener of the pages role reports about a site: the body of `GET /v1/sites/{site}`
 * and the answer to activating a release. [hasIndex] is whether the release serves an `index.html`
 * at its top, the page [url] opens.
 */
@Serializable
public data class SiteInfo(
    val name: String,
    val url: String,
    val release: String,
    val files: Int,
    val bytes: Long,
    val publishedAt: Instant,
    val hasIndex: Boolean = true,
)

/** Body of `GET /v1/sites`: every site the pages role serves. */
@Serializable
public data class SiteList(val sites: List<SiteInfo>)

/**
 * Body of `GET /v1/limits`: what the pages role lets a site hold. A publisher reads them here and
 * refuses a release before uploading it, instead of keeping a copy.
 */
@Serializable
public data class PagesLimits(
    val maxFiles: Int,
    val maxFileBytes: Long,
    val maxSiteBytes: Long,
    val releasesKept: Int,
    val maxSites: Int,
)

/** Body of `POST /v1/sites/{site}/releases`: every file the release will serve, by hash. */
@Serializable
public data class ReleaseRequest(val files: List<ReleaseFile>)

/** One file of a [ReleaseRequest]: the path it is served at, the SHA-256 of its bytes as hex, and its size. */
@Serializable
public data class ReleaseFile(val path: String, val hash: String, val size: Long)

/**
 * Answer to a started release: its id, and the hashes of the blobs the server does not hold yet. Only
 * those are uploaded, with `PUT /v1/blobs/{hash}`, before the release is activated.
 */
@Serializable
public data class ReleaseStarted(val release: String, val missing: List<String>)
