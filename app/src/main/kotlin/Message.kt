package land.melty.matrixqqbridge

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
import io.ktor.utils.io.jvm.javaio.toInputStream
import java.sql.Connection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import net.folivo.trixnity.client.api.MatrixApiClient
import net.folivo.trixnity.client.api.MediaApiClient
import net.folivo.trixnity.core.model.EventId
import net.folivo.trixnity.core.model.RoomId
import net.folivo.trixnity.core.model.UserId
import net.folivo.trixnity.core.model.events.Event
import net.folivo.trixnity.core.model.events.MessageEventContent
import net.folivo.trixnity.core.model.events.RelatesTo
import net.folivo.trixnity.core.model.events.m.room.FileInfo
import net.folivo.trixnity.core.model.events.m.room.ImageInfo
import net.folivo.trixnity.core.model.events.m.room.RoomMessageEventContent as RMEC
import net.mamoe.mirai.Bot
import net.mamoe.mirai.contact.Contact
import net.mamoe.mirai.contact.FileSupported
import net.mamoe.mirai.message.data.At
import net.mamoe.mirai.message.data.AtAll
import net.mamoe.mirai.message.data.Face
import net.mamoe.mirai.message.data.FileMessage
import net.mamoe.mirai.message.data.ForwardMessage
import net.mamoe.mirai.message.data.Image
import net.mamoe.mirai.message.data.Image.Key.queryUrl
import net.mamoe.mirai.message.data.ImageType
import net.mamoe.mirai.message.data.Message
import net.mamoe.mirai.message.data.MessageChain
import net.mamoe.mirai.message.data.MessageContent
import net.mamoe.mirai.message.data.MessageSource
import net.mamoe.mirai.message.data.MessageSourceKind
import net.mamoe.mirai.message.data.PlainText
import net.mamoe.mirai.message.data.QuoteReply
import net.mamoe.mirai.message.data.buildMessageSource
import net.mamoe.mirai.message.data.content
import net.mamoe.mirai.message.data.contentsList
import net.mamoe.mirai.message.data.messageChainOf
import net.mamoe.mirai.message.data.toMessageChain
import net.mamoe.mirai.utils.ExternalResource.Companion.uploadAsImage
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import org.jsoup.safety.Cleaner
import org.jsoup.safety.Safelist

/**
 * A wrapper for TextMessageEventContent, can also hold a plain text message
 *
 * TODO: eventually replace all direct calls of TMEC to HMEC, and add it in (de)serializer
 */
data class HtmlMessageEventContent(
        val body: String,
        val formattedBody: List<Node>? = null,
        override val relatesTo: RelatesTo? = null
) : MessageEventContent {
    fun toTextMessageEventContent() =
            RMEC.TextMessageEventContent(
                    body = body,
                    format = if (formattedBody == null) null else "org.matrix.custom.html",
                    formattedBody = formattedBody?.map { it.toString() }?.joinToString(""),
                    relatesTo = relatesTo
            )
    operator fun plus(another: HtmlMessageEventContent) =
            HtmlMessageEventContent(
                    this.body + another.body,
                    // Null if both are plain text, concat if either has formatted_body
                    if (this.formattedBody == null && another.formattedBody == null) null
                    else
                            (this.formattedBody
                                    ?: listOf(TextNode(this.body))) +
                                    (another.formattedBody ?: listOf(TextNode(another.body))),
                    this.relatesTo ?: another.relatesTo
            )
}

/** QQ -> Matrix */
suspend fun MessageContent.toHtmlMessageEventContent(
        matrixApiClient: MatrixApiClient,
        config: Config
): HtmlMessageEventContent =
        when (this) {
            is At -> {
                val ghost = Ghost.get(this.target, matrixApiClient, config)
                if (ghost == null) HtmlMessageEventContent(this.content)
                else {
                    val link = Element("a")
                    link.attr("href", MatrixToURL(ghost.userId).toString())
                    link.appendText(ghost.nick)
                    HtmlMessageEventContent(ghost.nick, listOf(link))
                }
            }
            is AtAll -> HtmlMessageEventContent("@room ")
            is Face -> {
                val shortcode = ":${FaceInfos.shortcodes.getOrElse(id) { "qq_emoji_$id" }}:"
                val url = FaceInfos.urls.getOrElse(id) { null }
                if (url != null) {
                    val img = Element("img")
                    img.attr("data-mx-emoticon", "")
                    img.attr("src", url)
                    img.attr("alt", shortcode)
                    img.attr("title", shortcode)
                    img.attr("height", "32")
                    HtmlMessageEventContent(shortcode, listOf(img))
                } else HtmlMessageEventContent(shortcode)
            }
            is ForwardMessage -> {
                val ul = Element("ul")
                val source = "Forwarded from ${this.source}"
                var fallback = source
                this.nodeList.forEach { node ->
                    val senderId = config.getGhostId(node.senderId)
                    val url = MatrixToURL(senderId).toString()
                    val li = Element("li")
                    val mentionLink = Element("a")
                    mentionLink.attr("href", url)
                    mentionLink.appendText(node.senderName)
                    li.appendChild(mentionLink)
                    li.appendText(": ")
                    node.messageChain.contentsList().forEach { msg ->
                        val hmec = msg.toHtmlMessageEventContent(matrixApiClient, config)
                        li.appendChildren(hmec.formattedBody ?: listOf(TextNode(hmec.body)))
                        hmec.body.split("\n").forEach { fallback += "\n> <${senderId}> ${it}" }
                    }
                    ul.appendChild(li)
                }
                HtmlMessageEventContent(fallback, listOf(TextNode(source), ul))
            }
            // Only used in forwarded messages
            is Image -> {
                val result = matrixApiClient.media.uploadUrlAsMxc(this.queryUrl())
                val img = Element("img")
                if (this.isEmoji) img.attr("data-mx-emoticon", "")
                img.attr("src", result.mxc)
                img.attr("alt", this.content)
                HtmlMessageEventContent(this.content, listOf(img))
            }
            else -> HtmlMessageEventContent(this.content)
        }

/** QQ -> Matrix */
suspend fun MessageContent.toMessageEventContent(
        matrixApiClient: MatrixApiClient,
        config: Config,
        subject: FileSupported?
): MessageEventContent {
    if (this is Image) {
        val result = matrixApiClient.media.uploadUrlAsMxc(this.queryUrl())
        val info =
                ImageInfo(
                        height = this.height,
                        width = this.width,
                        size = this.size.toInt(),
                        mimeType = result.type
                )
        if (this.isEmoji)
                return StickerMessageEventContent(this.content, info = info, url = result.mxc)
        return RMEC.ImageMessageEventContent(this.content, info = info, url = result.mxc)
    }
    if (this is FileMessage && subject != null) {
        val absoluteFile = this.toAbsoluteFile(subject)
        if (absoluteFile != null) {
            val url = absoluteFile.getUrl()
            if (url != null) {
                val result = matrixApiClient.media.uploadUrlAsMxc(url)
                return RMEC.FileMessageEventContent(
                        this.name,
                        fileName = this.name,
                        info = FileInfo(size = absoluteFile.size.toInt(), mimeType = result.type),
                        url = result.mxc
                )
            }
        }
    }
    return this.toHtmlMessageEventContent(matrixApiClient, config)
}

data class MxcResult(val mxc: String, val type: String)

/* QQ -> Matrix */
suspend fun MediaApiClient.uploadUrlAsMxc(url: String): MxcResult {
    val client = HttpClient(CIO) { install(UserAgent) { agent = "QQClient" } }
    val response: HttpResponse = client.get(url)
    val bytes: ByteReadChannel = response.receive()
    val contentType = response.contentType()!!
    return MxcResult(
            this.upload(bytes, response.contentLength()!!, contentType).getOrThrow().contentUri,
            "${contentType.contentType}/${contentType.contentSubtype}"
    )
}

/**
 * QQ -> Matrix
 *
 * Should only be called in bot.eventChannel.subscribeAlways<MessageEvent>
 */
suspend fun MessageChain.toMessageEventContents(
        matrixApiClient: MatrixApiClient,
        messageSourceKind: MessageSourceKind,
        roomId: RoomId,
        config: Config,
        subject: FileSupported?,
): List<MessageEventContent> {
    val contents = this.filterIsInstance<MessageContent>()
    val result = mutableListOf<MessageEventContent>()
    for (content in contents) {
        val previous = result.lastOrNull()
        val current = content.toMessageEventContent(matrixApiClient, config, subject)
        if (result.size == 0) result.add(current)
        // Concat previous and current if both m.text
        else if (previous is HtmlMessageEventContent && current is HtmlMessageEventContent)
                result[result.size - 1] = previous + current
        else result.add(current)
    }
    // Add rich reply
    val quoteReply = this.filterIsInstance<QuoteReply>().firstOrNull()
    if (quoteReply != null) {
        val eventId = Messages.getEventId(quoteReply.source, messageSourceKind)
        if (eventId != null) {
            @OptIn(ExperimentalSerializationApi::class)
            val event = matrixApiClient.rooms.getEvent(roomId, eventId).getOrThrow()
            if (event is Event.MessageEvent && event.content is MessageEventContent)
                    result.map {
                        @Suppress("UNCHECKED_CAST")
                        if (it is HtmlMessageEventContent)
                                it.addReplyTo(event as Event.MessageEvent<MessageEventContent>)
                        else it
                    }
        }
    }
    return result.map { if (it is HtmlMessageEventContent) it.toTextMessageEventContent() else it }
}

/** @see https://spec.matrix.org/v1.2/client-server-api/#rich-replies */
fun HtmlMessageEventContent.addReplyTo(
        event: Event.MessageEvent<MessageEventContent>
): HtmlMessageEventContent {
    val content = event.content
    val fallback: String =
            when (content) {
                is RMEC.TextMessageEventContent ->
                        content.body.asFallbackRemoveReply().replace("\n", "\n> ")
                is RMEC -> content.body
                is StickerMessageEventContent -> content.body
                else -> return this
            }
    // Build the new formatted body
    val mxReply = Element("mx-reply")
    val blockquote = Element("blockquote")
    val eventLink = Element("a")
    val senderLink = Element("a")
    eventLink.attr("href", MatrixToURL(event.roomId, event.id).toString())
    eventLink.appendText("In reply to")
    blockquote.appendChild(eventLink)
    senderLink.attr("href", MatrixToURL(event.sender).toString())
    senderLink.appendText(event.sender.toString())
    blockquote.appendChild(eventLink)
    blockquote.appendChild(senderLink)
    blockquote.appendChild(Element("br"))
    // Clean the original formatted body
    when (content) {
        is RMEC.TextMessageEventContent ->
                if (content.formattedBody != null)
                        blockquote.appendChildren(content.formattedBody!!.asFormattedBodyToNodes())
                else blockquote.appendChild(TextNode(content.body))
        is RMEC -> blockquote.appendChild(TextNode(content.body))
        is StickerMessageEventContent -> blockquote.appendChild(TextNode(content.body))
        else -> return this
    }
    mxReply.appendChild(blockquote)
    return HtmlMessageEventContent(
            "> <${event.sender}> ${fallback}\n\n",
            listOf(mxReply),
            // https://gitlab.com/trixnity/trixnity/-/blob/v1.1.9/trixnity-core/src/commonTest/kotlin/net/folivo/trixnity/core/serialization/events/EventSerializerTest.kt#L619-631
            RelatesTo.Unknown(
                    JsonObject(
                            mapOf(
                                    "m.in_reply_to" to
                                            JsonObject(
                                                    mapOf(
                                                            "event_id" to
                                                                    JsonPrimitive(event.id.full)
                                                    )
                                            )
                            )
                    )
            )
    ) + this
}

/**
 * > <@alice:example.org> This is the first line > This is the second line
 *
 * This is the reply
 */
fun String.asFallbackRemoveReply(): String =
        this.split("\n").filter { !it.startsWith("> ") }.joinToString("\n").trim()

/** Convert string as formatted body to Jsoup nodes, and clean it */
fun String.asFormattedBodyToNodes(safelist: Safelist = CustomSafelists.matrix()): List<Node> {
    val parsed = Jsoup.parseBodyFragment(this)
    // Jsoup cleaner does not remove the inner HTML of mx-reply
    // Manually remove here
    parsed.select("mx-reply").remove()
    return Cleaner(safelist).clean(parsed).body().childNodes()
}

/** Matrix -> QQ */
suspend fun List<Node>.toMessageList(
        mediaApiClient: MediaApiClient,
        contact: Contact,
        config: Config,
        olStart: Int? = null
): List<Message> {
    var li = olStart
    val result = mutableListOf<Message>()
    this.forEach { node ->
        suspend fun addChildNodes() =
                result.addAll(node.childNodes().toMessageList(mediaApiClient, contact, config))
        when (node) {
            is TextNode -> result.add(PlainText(node.text()))
            is Element ->
                    when (node.tagName()) {
                        // Block, "\n...\n"
                        "h1",
                        "h2",
                        "h3",
                        "h4",
                        "h5",
                        "h6",
                        "blockquote",
                        "p",
                        "table",
                        "pre",
                        "ul" -> {
                            result.add(PlainText("\n"))
                            addChildNodes()
                            result.add(PlainText("\n"))
                        }
                        "ol" -> {
                            result.add(PlainText("\n"))
                            result.addAll(
                                    node.childNodes()
                                            .toMessageList(
                                                    mediaApiClient,
                                                    contact,
                                                    config,
                                                    olStart = node.attr("start").toIntOrNull() ?: 1
                                            )
                            )
                            result.add(PlainText("\n"))
                        }
                        // Line, "...\n"
                        "tr",
                        "caption" -> {
                            addChildNodes()
                            result.add(PlainText("\n"))
                        }
                        // Atom
                        "hr" -> result.add(PlainText("\n-----\n"))
                        "br" -> result.add(PlainText("\n"))
                        // List, ul>li -> "* ...\n", ol>li -> "1. ...\n"
                        "li" -> {
                            if (li != null) {
                                result.add(PlainText("$li. "))
                                li += 1
                            } else result.add(PlainText("* "))
                            addChildNodes()
                            result.add(PlainText("\n"))
                        }
                        // Special
                        "img" -> {
                            val faceId =
                                    FaceInfos.reversedShortcodes.getOrDefault(
                                            node.attr("alt").trim(':'),
                                            null
                                    )
                            // Emoji
                            if (node.hasAttr("data-mx-emoticon") && faceId != null)
                                    result.add(Face(faceId))
                            else
                                    mediaApiClient.uploadMxcAsImage(contact, node.attr("src"))
                                            ?.also { result.add(it) }
                        }
                        "a" -> {
                            val url = MatrixToURL.fromStringOrNull(node.attr("href"))
                            // Mention
                            if (url != null && url.id is UserId && config.isGhost(url.id)) {
                                config.getGhostQqIdOrNull(url.id)?.also { result.add(At(it)) }
                            } else {
                                addChildNodes()
                                result.add(PlainText("(${node.attr("href")})"))
                            }
                        }
                    }
        }
    }
    if (result.firstOrNull() is PlainText)
            result[0] = PlainText(result.first().content.trimStart('\n'))
    if (result.lastOrNull() is PlainText)
            result[result.size - 1] = PlainText(result.last().content.trimEnd('\n'))
    return result
}

/* Matrix -> QQ */
suspend fun MediaApiClient.uploadMxcAsImage(contact: Contact, mxc: String?): Image? {
    mxc ?: return null
    val response = this.download(mxc).getOrThrow()
    // TODO: convert WEBP to PNG
    if (ImageType.matchOrNull(response.contentType!!.contentSubtype) == null) return null
    return withContext(Dispatchers.IO) {
        response.content
                .toInputStream()
                .uploadAsImage(contact, formatName = response.contentType!!.contentSubtype)
    }
}

/** Matrix -> QQ */
suspend fun MessageEventContent.toMessage(
        matrixApiClient: MatrixApiClient,
        contact: Contact,
        config: Config,
        bot: Bot
): Message? =
        when (this) {
            is RMEC.ImageMessageEventContent ->
                    matrixApiClient.media.uploadMxcAsImage(contact, this.url)
            is StickerMessageEventContent ->
                    matrixApiClient.media.uploadMxcAsImage(contact, this.url)
            is RMEC.TextMessageEventContent -> {
                if (this.format == "org.matrix.custom.html" && this.formattedBody != null)
                        this.formattedBody!!
                                .asFormattedBodyToNodes(CustomSafelists.qq())
                                .toMessageList(matrixApiClient.media, contact, config)
                                .let { if (it.size == 0) null else it.toMessageChain() }
                else messageChainOf(PlainText(body.asFallbackRemoveReply()))
            }
            is RMEC -> PlainText(this.body)
            else -> null
        }?.let { message ->
            // Get replied EventId if exists, and convert it to MessageSource
            // The reverse of HtmlMessageEventContent.addReplyTo
            (if (this.relatesTo is RelatesTo.Unknown)
                            (this.relatesTo as RelatesTo.Unknown).raw["m.in_reply_to"]
                                    ?.let { if (it is JsonObject) it["event_id"] else null }
                                    ?.let {
                                        if (it is JsonPrimitive)
                                                Messages.getMessageSource(
                                                        EventId(it.jsonPrimitive.content),
                                                        bot
                                                )
                                        else null
                                    }
                    else null)
                    // Create QQ reply
                    .let { if (it == null) message else QuoteReply(it) + message }
        }

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
        statement.setString(1, eventId.full)
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
