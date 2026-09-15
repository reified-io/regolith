package io.reified.regolith.server.domain

import kotlinx.serialization.Serializable
import java.security.SecureRandom
import java.util.UUID

/**
 * A sandbox identifier, made by the server. Callers find a sandbox by its [Alias]; this is what
 * addresses it in the API, in its container and in its records, and it says nothing about whose it is.
 */
@Serializable
@JvmInline
value class SandboxId private constructor(val value: String) {
    override fun toString(): String = value

    companion object {
        fun random(): SandboxId = SandboxId(randomId())

        fun parse(raw: String): SandboxId {
            requireValid(ID_SHAPE.matches(raw)) { "Unknown sandbox id" }

            return SandboxId(raw)
        }
    }
}

/**
 * A caller's own name for one sandbox, unique on the server: the identity in the caller's world —
 * a user, a project, a task — so it never has to keep a table of its own. It is opaque text and
 * reaches nothing public.
 */
@Serializable
@JvmInline
value class Alias private constructor(val value: String) {
    override fun toString(): String = value

    companion object {
        const val MAX_CHARS = 200

        fun parse(raw: String): Alias {
            requireValid(raw.isNotEmpty() && raw.length <= MAX_CHARS) { "An alias is 1 to $MAX_CHARS characters" }
            requireValid(raw.trim() == raw) { "An alias has no leading or trailing whitespace" }
            requireValid(raw.none { it.isISOControl() }) { "An alias holds no control characters" }

            return Alias(raw)
        }
    }
}

/**
 * The label a published site is served at, made by the server. It is random rather than taken from
 * the sandbox, because it is public: an address built from a caller's own identity hands that
 * identity to everyone with the link, and makes every other site guessable from one.
 */
@Serializable
@JvmInline
value class SiteLabel private constructor(val value: String) {
    override fun toString(): String = value

    companion object {
        // no vowels and no look-alikes: a label is read aloud and typed by hand, and spells nothing.
        private const val ALPHABET = "23456789bcdfghjkmnpqrstvwxz"
        private const val LENGTH = 10
        private val shape = Regex("[$ALPHABET]{$LENGTH}")
        private val random = SecureRandom()

        fun random(): SiteLabel = SiteLabel((1..LENGTH).map { ALPHABET[random.nextInt(ALPHABET.length)] }.joinToString(""))

        fun parse(raw: String): SiteLabel {
            requireValid(shape.matches(raw)) { "Unknown site" }

            return SiteLabel(raw)
        }
    }
}

/** A server-generated exec identifier. */
@Serializable
@JvmInline
value class ExecId private constructor(val value: String) {
    override fun toString(): String = value

    companion object {
        fun random(): ExecId = ExecId(randomId())

        fun parse(raw: String): ExecId {
            requireValid(ID_SHAPE.matches(raw)) { "Unknown exec id" }

            return ExecId(raw)
        }
    }
}

/*
 * one shape for every identifier the server makes. the domain names no wire type, so `protocol.Ids`
 * states the same shape for clients and `DomainTest` holds the two together.
 */
private val ID_SHAPE = Regex("[0-9a-f]{32}")

private fun randomId(): String = UUID.randomUUID().toString().replace("-", "")
