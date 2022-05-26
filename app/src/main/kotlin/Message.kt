package land.melty.matrixappserviceqq

import io.ktor.client.HttpClient
import io.ktor.client.call.*
import io.ktor.client.engine.cio.CIO
import io.ktor.client.features.*
import io.ktor.client.request.*
import io.ktor.client.request.get
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.http.content.*
import io.ktor.utils.io.*
import java.sql.Connection
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.serializer
import net.folivo.trixnity.client.api.MatrixApiClient
import net.folivo.trixnity.core.model.EventId
import net.folivo.trixnity.core.model.events.MessageEventContent
import net.folivo.trixnity.core.model.events.RelatesTo
import net.folivo.trixnity.core.model.events.UnknownMessageEventContent
import net.folivo.trixnity.core.model.events.m.room.EncryptedFile
import net.folivo.trixnity.core.model.events.m.room.ImageInfo
import net.folivo.trixnity.core.model.events.m.room.RoomMessageEventContent as RMEC
import net.mamoe.mirai.Bot
import net.mamoe.mirai.message.data.Face
import net.mamoe.mirai.message.data.Image
import net.mamoe.mirai.message.data.Image.Key.queryUrl
import net.mamoe.mirai.message.data.MessageChain
import net.mamoe.mirai.message.data.MessageContent
import net.mamoe.mirai.message.data.MessageSource
import net.mamoe.mirai.message.data.MessageSourceKind
import net.mamoe.mirai.message.data.buildMessageSource
import net.mamoe.mirai.message.data.content

@Serializable
data class RawStickerMessageEventContent(
        @SerialName("body") val body: String,
        @SerialName("info") val info: ImageInfo? = null,
        @SerialName("url") val url: String? = null,
        @SerialName("file") val file: EncryptedFile? = null,
        @SerialName("m.relates_to") val relatesTo: RelatesTo? = null,
)

fun concatNullableStrings(a: String?, b: String?): String? =
        if (a == null) b else if (b == null) a else a + b

suspend fun MessageContent.toMessageEventContent(
        matrixApiClient: MatrixApiClient
): MessageEventContent =
        when (this) {
            is Image -> {
                val client = HttpClient(CIO) { install(UserAgent) { agent = "QQClient" } }
                val response: HttpResponse = client.get(this.queryUrl())
                val bytes: ByteReadChannel = response.receive()
                val url =
                        matrixApiClient
                                .media
                                .upload(bytes, response.contentLength()!!, response.contentType()!!)
                                .getOrThrow()
                                .contentUri
                // HACK: m.sticker is currently not supported in Trixnity
                if (isEmoji)
                        UnknownMessageEventContent(
                                raw =
                                        Json.encodeToJsonElement(
                                                RawStickerMessageEventContent.serializer(),
                                                RawStickerMessageEventContent(
                                                        this.content,
                                                        url = url
                                                )
                                        ) as
                                                JsonObject,
                                eventType = "m.sticker"
                        )
                else RMEC.ImageMessageEventContent(this.content, url = url)
            }
            is Face -> RMEC.TextMessageEventContent(this.content)
            else -> RMEC.TextMessageEventContent(this.content)
        }

suspend fun MessageChain.toMessageEventContents(
        matrixApiClient: MatrixApiClient
): List<MessageEventContent> {
    val contents = this.filterIsInstance<MessageContent>()
    val result = mutableListOf<MessageEventContent>()
    for (content in contents) {
        val mec = content.toMessageEventContent(matrixApiClient)
        val last = result.lastOrNull()
        when {
            result.size == 0 -> result.add(mec)
            last is RMEC.TextMessageEventContent && mec is RMEC.TextMessageEventContent -> {
                result[result.size - 1] =
                        RMEC.TextMessageEventContent(
                            body = last.body + mec.body,
                            format = last.format,
                            formattedBody = concatNullableStrings(last.formattedBody, mec.formattedBody),
                            relatesTo = last.relatesTo
                        )
            }
            else -> result.add(mec)
        }
    }
    return result
}

// suspend fun MessageEventContent.toMessageChain(): MessageChain {}

object Messages {
    var connection: Connection? = null
    fun dbInit(connection: Connection) {
        this.connection = connection
        val statement = connection.createStatement()
        statement.executeUpdate(
                """
                    CREATE TABLE IF NOT EXISTS messages (
                        kind TEXT NOT NULL,
                        sender INTEGER NOT NULL,
                        target INTEGER NOT NULL,
                        id INTEGER NOT NULL,
                        internal_id INTEGER NOT NULL,
                        time INTEGER NOT NULL,
                        event_id TEXT NOT NULL
                    )
                """
        )
    }

    fun getEventId(source: MessageSource, kind: MessageSourceKind): EventId? {
        val statement =
                connection!!.prepareStatement(
                        """
                            SELECT event_id FROM messages
                                WHERE kind = ?
                                AND sender = ?
                                AND target = ?
                                AND id = ?
                                AND internal_id = ?
                                AND time = ?
                        """
                )
        statement.setString(1, kind.toString())
        statement.setLong(2, source.fromId)
        statement.setLong(3, source.targetId)
        statement.setInt(4, source.ids[0])
        statement.setInt(5, source.internalIds[0])
        statement.setInt(6, source.time)
        val rs = statement.executeQuery()
        if (rs.next() == false) return null
        return EventId(rs.getString("event_id"))
    }

    fun getMessageSource(eventId: EventId, bot: Bot): MessageSource? {
        val statement = connection!!.prepareStatement("SELECT * FROM messages WHERE event_id = ?")
        statement.setString(1, eventId.toString())
        val rs = statement.executeQuery()
        if (rs.next() == false) return null
        return bot.buildMessageSource(MessageSourceKind.valueOf(rs.getString("kind"))) {
            sender(rs.getLong("sender"))
            target(rs.getLong("target"))
            id(rs.getInt("id"))
            internalId(rs.getInt("internal_id"))
            time(rs.getInt("time"))
        }
    }

    fun save(source: MessageSource, eventId: EventId, kind: MessageSourceKind) {
        val statement =
                connection!!.prepareStatement("INSERT INTO messages VALUES (?, ?, ?, ?, ?, ?, ?)")
        statement.setString(1, kind.toString())
        statement.setLong(2, source.fromId)
        statement.setLong(3, source.targetId)
        statement.setInt(4, source.ids[0])
        statement.setInt(5, source.internalIds[0])
        statement.setInt(6, source.time)
        statement.setString(7, eventId.full)
        statement.executeUpdate()
    }
}
