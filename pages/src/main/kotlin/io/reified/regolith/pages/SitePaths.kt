package io.reified.regolith.pages

/**
 * The rules a path inside a site obeys. They are checked when a release is started, so serving can be
 * a plain map lookup: a path that got this far cannot name anything outside its own manifest.
 *
 * Segments starting with a dot are refused outright — `.git` and `.env` reach a published tree by
 * accident far more often than anything needs them, and nothing here answers ACME challenges.
 */
object SitePaths {
    const val MAX_CHARS = 400
    const val MAX_SEGMENT_CHARS = 120
    const val MAX_DEPTH = 12

    private const val INDEX = "index.html"

    fun clean(raw: String): String {
        requireValid(raw.isNotBlank() && raw.length <= MAX_CHARS) { "A file path is 1-$MAX_CHARS characters" }
        requireValid(!raw.startsWith("/") && '\\' !in raw) { "A file path is relative and uses `/`: `$raw`" }
        requireValid(raw.none { it.code < 0x20 || it.code == 0x7f }) { "A file path holds no control characters" }
        val parts = raw.split('/').filter { it.isNotEmpty() && it != "." }
        requireValid(parts.isNotEmpty()) { "A file path must name a file inside the site" }
        requireValid(parts.size <= MAX_DEPTH) { "A file path is at most $MAX_DEPTH levels deep: `$raw`" }

        for (part in parts) {
            requireValid(part != "..") { "A file path must stay inside the site: `$raw`" }
            requireValid(!part.startsWith(".")) { "A path segment must not start with a dot: `$raw`" }
            requireValid(part.length <= MAX_SEGMENT_CHARS) { "A path segment is at most $MAX_SEGMENT_CHARS characters" }
        }

        return parts.joinToString("/")
    }

    /** The manifest key a request path asks for: a directory, or the site root, means its `index.html`. */
    fun requested(path: String): String {
        val trimmed = path.removePrefix("/")

        return when {
            trimmed.isEmpty() -> INDEX
            trimmed.endsWith("/") -> trimmed + INDEX
            else -> trimmed
        }
    }

    /** A second guess for a clean URL: `/about` may mean `about/index.html`. */
    fun directoryIndex(path: String): String = "$path/$INDEX"

    const val NOT_FOUND_PAGE: String = "404.html"
}

/**
 * The type a file is served as, taken from its extension and nothing else. What the publisher claims
 * is never used: a `.png` the caller labelled `text/html` would be a stored cross-site script.
 */
object ContentTypes {
    private const val DEFAULT = "application/octet-stream"

    private val byExtension = mapOf(
        "html" to "text/html; charset=utf-8",
        "htm" to "text/html; charset=utf-8",
        "css" to "text/css; charset=utf-8",
        "js" to "text/javascript; charset=utf-8",
        "mjs" to "text/javascript; charset=utf-8",
        "json" to "application/json; charset=utf-8",
        "map" to "application/json; charset=utf-8",
        "txt" to "text/plain; charset=utf-8",
        "md" to "text/plain; charset=utf-8",
        "xml" to "application/xml; charset=utf-8",
        "csv" to "text/csv; charset=utf-8",
        "svg" to "image/svg+xml",
        "png" to "image/png",
        "jpg" to "image/jpeg",
        "jpeg" to "image/jpeg",
        "gif" to "image/gif",
        "webp" to "image/webp",
        "avif" to "image/avif",
        "ico" to "image/x-icon",
        "woff" to "font/woff",
        "woff2" to "font/woff2",
        "ttf" to "font/ttf",
        "otf" to "font/otf",
        "mp4" to "video/mp4",
        "webm" to "video/webm",
        "mp3" to "audio/mpeg",
        "ogg" to "audio/ogg",
        "wav" to "audio/wav",
        "wasm" to "application/wasm",
        "pdf" to "application/pdf",
        "zip" to "application/zip",
    )

    fun of(path: String): String = byExtension[path.substringAfterLast('.', "").lowercase()] ?: DEFAULT
}
