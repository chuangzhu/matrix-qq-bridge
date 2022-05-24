package land.melty.matrixappserviceqq

import io.ktor.client.HttpClient
import io.ktor.client.call.receive
import io.ktor.client.engine.cio.CIO
import io.ktor.client.features.UserAgent
import io.ktor.client.request.get
import io.ktor.client.statement.HttpResponse
import io.ktor.http.contentLength
import io.ktor.http.contentType
import io.ktor.utils.io.ByteReadChannel
import java.sql.Connection
import kotlinx.serialization.json.Json
import net.folivo.trixnity.client.api.MatrixApiClient
import net.folivo.trixnity.core.model.EventId
import net.folivo.trixnity.core.model.RoomAliasId
import net.folivo.trixnity.core.model.UserId
import net.folivo.trixnity.core.model.events.m.room.RoomMessageEventContent.TextMessageEventContent
import net.mamoe.mirai.Bot
import net.mamoe.mirai.BotFactory
import net.mamoe.mirai.event.events.GroupMessageEvent
import net.mamoe.mirai.message.data.MessageSource
import net.mamoe.mirai.message.data.MessageSourceKind
import net.mamoe.mirai.message.data.buildMessageSource
import net.mamoe.mirai.message.data.content
import net.mamoe.mirai.utils.BotConfiguration.MiraiProtocol.ANDROID_PAD
import net.mamoe.mirai.utils.DeviceInfo

// class PuppetLoginSolver(matrixApiClient: MatrixApiClient) : LoginSolver() {
//     val matrixApiClient = matrixApiClient
//     override suspend fun onSolvePicCaptcha(bot: Bot, data: ByteArray): String? {
//         matrixApiClient.rooms.sendMessageEvent(
//                 roomId,
//                 TextMessageEventContent("Captcha required, send the code from the image:")
//         )
//         matrixApiClient.media.upload(data, contentLength, contentType)
//     }
//     override val isSliderCaptchaSupported = true
// }

class Puppet(
        val mxid: UserId,
        val qqid: Long,
        val password: String,
        var deviceJson: String? = null
) {
    val bot =
            BotFactory.newBot(qqid, password) {
                if (deviceJson == null) {
                    deviceInfo = {
                        DeviceInfo.random().also {
                            deviceJson = Json.encodeToString(DeviceInfo.serializer(), it)
                        }
                    }
                } else loadDeviceInfoJson(deviceJson as String)
                protocol = ANDROID_PAD
            }

    suspend fun connect(matrixApiClient: MatrixApiClient, config: Config) {
        bot.login()
        bot.groups.forEach {
            val roomAliasId =
                    RoomAliasId(
                            "${config.appservice.aliasPrefix}${it.id}",
                            config.homeserver.domain
                    )
            val result = matrixApiClient.rooms.getRoomAlias(roomAliasId)
            if (result.isFailure) {
                matrixApiClient.rooms.createRoom(
                        name = it.name,
                        roomAliasId = roomAliasId,
                        invite = setOf(mxid),
                        isDirect = false,
                )
            } else {
                matrixApiClient.rooms.inviteUser(
                        roomId = result.getOrNull()!!.roomId,
                        userId = mxid,
                        reason = "You are in this QQ group.",
                )
            }
        }
        bot.eventChannel.subscribeAlways<GroupMessageEvent> {
            // Registration
            // TODO: replace this with Ghost
            val username = "${config.appservice.usernamePrefix}${sender.id}"
            matrixApiClient.authentication.register(username = username, isAppservice = true)
            val userId = UserId(username, config.homeserver.domain)
            matrixApiClient.users.setDisplayName(
                    userId = userId,
                    displayName = "${sender.nick} (QQ)",
                    asUserId = userId
            )
            // Avatar
            val client = HttpClient(CIO) { install(UserAgent) { agent = "QQClient" } }
            val response: HttpResponse = client.get(sender.avatarUrl)
            val channel: ByteReadChannel = response.receive()
            val avatarUrl =
                    matrixApiClient
                            .media
                            .upload(channel, response.contentLength()!!, response.contentType()!!)
                            .getOrNull()
            matrixApiClient.users.setAvatarUrl(userId, avatarUrl!!.contentUri, asUserId = userId)
            // Join the room
            // TODO: replace this with Portal
            val roomAliasId =
                    RoomAliasId(
                            "${config.appservice.aliasPrefix}${subject.id}",
                            config.homeserver.domain
                    )
            val roomId = matrixApiClient.rooms.getRoomAlias(roomAliasId).getOrThrow().roomId
            matrixApiClient.rooms.inviteUser(roomId, userId)
            matrixApiClient.rooms.joinRoom(roomId, asUserId = userId)
            // Send the message
            val source = message.get(MessageSource.Key)
            if (getMessage(source!!, MessageSourceKind.GROUP) != null) return@subscribeAlways
            val eventId =
                    matrixApiClient
                            .rooms
                            .sendMessageEvent(
                                    roomId,
                                    TextMessageEventContent(message.content),
                                    asUserId = userId
                            )
                            .getOrThrow()
            saveMessage(source, eventId, MessageSourceKind.GROUP)
        }
    }

    // Create
    suspend fun insert() {
        val statement =
                connection!!.prepareStatement("INSERT OR IGNORE INTO puppets VALUES (?, ?, ?, ?)")
        statement.setString(1, mxid.toString())
        statement.setLong(2, qqid)
        statement.setString(3, password)
        statement.setString(4, deviceJson)
        statement.executeUpdate()
    }

    fun getMessage(source: MessageSource, kind: MessageSourceKind): EventId? {
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
    fun getMessage(eventId: EventId, bot: Bot): MessageSource? {
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
    fun saveMessage(source: MessageSource, eventId: EventId, kind: MessageSourceKind) {
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

    companion object {
        val byMxid = mutableMapOf<UserId, Puppet>()
        var connection: Connection? = null
        fun dbInit(connection: Connection) {
            this.connection = connection
            val statement = connection.createStatement()
            statement.executeUpdate(
                    """
                        CREATE TABLE IF NOT EXISTS puppets (
                            mxid TEXT PRIMARY KEY NOT NULL,
                            qqid INTEGER NOT NULL,
                            password TEXT NOT NULL,
                            device_json TEXT NOT NULL
                        )
                    """
            )
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
        suspend fun getPuppet(mxid: UserId): Puppet? =
                if (byMxid.containsKey(mxid)) byMxid[mxid]!! else null
        // Read
        suspend fun loadAll(matrixApiClient: MatrixApiClient, config: Config) {
            val rs =
                    connection!!
                            .createStatement()
                            .executeQuery("SELECT mxid, qqid, password, device_json FROM puppets")
            while (rs.next()) {
                val mxid = UserId(rs.getString("mxid"))
                val puppet =
                        Puppet(
                                mxid,
                                rs.getLong("qqid"),
                                rs.getString("password"),
                                rs.getString("device_json")
                        )
                puppet.connect(matrixApiClient, config)
                byMxid[mxid] = puppet
            }
        }
    }
}
