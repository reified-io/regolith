package io.reified.regolith.server.domain

import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/**
 * Reads a value through its own parser. The serializer generated for a value class calls the
 * constructor, so a record edited by hand, or damaged, would put into a container name, a path or a
 * firewall rule whatever text it holds; a value is valid by construction, and a record is no exception.
 */
internal abstract class ParsedSerializer<T>(
    name: String,
    private val parse: (String) -> T,
    private val text: (T) -> String,
) : KSerializer<T> {

    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("io.reified.regolith.server.domain.$name", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: T) = encoder.encodeString(text(value))

    override fun deserialize(decoder: Decoder): T = parse(decoder.decodeString())
}

internal object SandboxIdSerializer : ParsedSerializer<SandboxId>("SandboxId", SandboxId::parse, SandboxId::value)

internal object AliasSerializer : ParsedSerializer<Alias>("Alias", Alias::parse, Alias::value)

internal object SiteLabelSerializer : ParsedSerializer<SiteLabel>("SiteLabel", SiteLabel::parse, SiteLabel::value)

internal object ExecIdSerializer : ParsedSerializer<ExecId>("ExecId", ExecId::parse, ExecId::value)

internal object CidrSerializer : ParsedSerializer<Cidr>("Cidr", Cidr::parse, Cidr::value)
