package io.reified.regolith.server.domain

import kotlin.contracts.ExperimentalContracts
import kotlin.contracts.contract

/**
 * Failures a caller can act on. The HTTP adapter maps each subtype to one status and one stable
 * error code; anything else thrown inside the server is an internal error.
 */
sealed class RegolithError(message: String) : RuntimeException(message) {
    class Invalid(message: String) : RegolithError(message)
    class NotFound(message: String) : RegolithError(message)
    class Conflict(message: String) : RegolithError(message)
    class Busy(message: String) : RegolithError(message)
    class CapacityExhausted(message: String) : RegolithError(message)
    class TooLarge(message: String) : RegolithError(message)
    class InsufficientStorage(message: String) : RegolithError(message)
    class Unavailable(message: String) : RegolithError(message)
    class NotImplemented(message: String) : RegolithError(message)
}

/** Like `require`, but fails with [RegolithError.Invalid] so the caller gets a 400 with the message. */
@OptIn(ExperimentalContracts::class)
inline fun requireValid(condition: Boolean, message: () -> String) {
    contract { returns() implies condition }

    if (!condition) throw RegolithError.Invalid(message())
}
