package io.reified.regolith.protocol

/**
 * The shape of every identifier a Regolith server makes: a sandbox's, an exec's, a release's.
 *
 * A caller never invents one — it comes back from the server and is handed on unchanged — but it
 * ends up in a request path, so a client checks it before building one rather than sending whatever
 * it was given. The server keeps the same shape in its own domain, where no wire type may reach.
 */
public object Ids {
    /** How many characters an identifier has. */
    public const val LENGTH: Int = 32

    private val shape = Regex("[0-9a-f]{$LENGTH}")

    /** Whether [raw] could be an identifier this server made. */
    public fun isId(raw: String): Boolean = shape.matches(raw)
}
