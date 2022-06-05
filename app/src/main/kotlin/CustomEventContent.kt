package land.melty.matrixqqbridge

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import net.folivo.trixnity.core.model.events.*
import net.folivo.trixnity.core.model.events.MessageEventContent
import net.folivo.trixnity.core.model.events.RelatesTo
import net.folivo.trixnity.core.model.events.m.room.EncryptedFile
import net.folivo.trixnity.core.model.events.m.room.ImageInfo
import net.folivo.trixnity.core.serialization.events.EventContentSerializerMapping
import net.folivo.trixnity.core.serialization.events.EventContentSerializerMapping.Companion.of
import net.folivo.trixnity.core.serialization.events.EventContentSerializerMappings

@Serializable
data class StickerMessageEventContent(
        @SerialName("body") val body: String,
        @SerialName("info") val info: ImageInfo? = null,
        @SerialName("url") val url: String? = null,
        @SerialName("m.relates_to") override val relatesTo: RelatesTo? = null,
) : MessageEventContent

object CustomEventContentSerializerMappings : EventContentSerializerMappings {
    override val message: Set<EventContentSerializerMapping<out MessageEventContent>> =
            setOf(of<StickerMessageEventContent>("m.sticker"))
    override val state: Set<EventContentSerializerMapping<out StateEventContent>> = setOf()
    override val ephemeral: Set<EventContentSerializerMapping<out EphemeralEventContent>> = setOf()
    override val toDevice: Set<EventContentSerializerMapping<out ToDeviceEventContent>> = setOf()
    override val globalAccountData:
            Set<EventContentSerializerMapping<out GlobalAccountDataEventContent>> =
            setOf()
    override val roomAccountData:
            Set<EventContentSerializerMapping<out RoomAccountDataEventContent>> =
            setOf()
}
