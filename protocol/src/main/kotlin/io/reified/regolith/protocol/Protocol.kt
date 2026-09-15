package io.reified.regolith.protocol

import kotlinx.serialization.json.Json

/**
 * The wire contract's version, reported as `protocol` by `GET /v1/info`.
 *
 * It rises whenever a change would break a client written against the version before it — a field a
 * response no longer carries, a path that moved, a request shape that means something else. The
 * `/v1` prefix does not move with it: the prefix marks the family of endpoints, while this number is
 * what a client checks before trusting what it decodes.
 */
public const val PROTOCOL_VERSION: Int = 3

/** Request header carrying a client-chosen key that makes starting an exec safe to retry. */
public const val IDEMPOTENCY_KEY_HEADER: String = "Idempotency-Key"

/** JSON settings shared by the server and every Kotlin client. */
public object RegolithJson {
    /**
     * For requests read by the server: an unknown field is rejected, so a misspelled option fails
     * loudly instead of silently falling back to a default.
     */
    public val strict: Json = Json {
        encodeDefaults = true
        explicitNulls = false
    }

    /** For responses read by a client: fields added by a newer server are ignored. */
    public val lenient: Json = Json {
        encodeDefaults = true
        explicitNulls = false
        ignoreUnknownKeys = true
    }
}
