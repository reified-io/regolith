package io.reified.regolith.server.domain

import kotlinx.serialization.Serializable
import java.util.UUID

/**
 * A sandbox name, chosen by the caller and unique on the server. It is a DNS label, so it can name
 * a container today and a hostname tomorrow without a second encoding.
 */
@Serializable
@JvmInline
value class SandboxName private constructor(val value: String) {
    override fun toString(): String = value

    companion object {
        private val shape = Regex("[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?")

        fun parse(raw: String): SandboxName {
            requireValid(shape.matches(raw)) {
                "A sandbox name is 1-63 lowercase letters, digits and inner hyphens"
            }

            return SandboxName(raw)
        }
    }
}

/** A server-generated exec identifier. */
@Serializable
@JvmInline
value class ExecId private constructor(val value: String) {
    override fun toString(): String = value

    companion object {
        private val shape = Regex("[0-9a-f]{32}")

        fun random(): ExecId = ExecId(UUID.randomUUID().toString().replace("-", ""))

        fun parse(raw: String): ExecId {
            requireValid(shape.matches(raw)) { "Unknown exec id" }

            return ExecId(raw)
        }
    }
}
