package land.melty.matrixappserviceqq

import java.sql.Connection
import kotlinx.serialization.json.Json
import net.folivo.trixnity.client.api.MatrixApiClient
import net.folivo.trixnity.core.model.RoomAliasId
import net.folivo.trixnity.core.model.UserId
import net.folivo.trixnity.core.model.events.m.room.RoomMessageEventContent.TextMessageEventContent
import net.mamoe.mirai.BotFactory
import net.mamoe.mirai.event.events.GroupMessageEvent
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
            val username = "${config.appservice.usernamePrefix}${sender.id}"
            matrixApiClient.authentication.register(username = username, isAppservice = true)
            val userId = UserId(username, config.homeserver.domain)
            matrixApiClient.users.setDisplayName(
                    userId = userId,
                    displayName = "${sender.nick} (QQ)",
                    asUserId = userId
            )
            val roomAliasId =
                    RoomAliasId(
                            "${config.appservice.aliasPrefix}${subject.id}",
                            config.homeserver.domain
                    )
            val result = matrixApiClient.rooms.getRoomAlias(roomAliasId)
            // TODO: null handling
            val roomId = result.getOrNull()!!.roomId
            matrixApiClient.rooms.inviteUser(roomId, userId)
            matrixApiClient.rooms.joinRoom(roomId, asUserId = userId)
            matrixApiClient.rooms.sendMessageEvent(
                    roomId,
                    TextMessageEventContent(message.content),
                    asUserId = userId
            )
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
    companion object {
        val byMxid = mutableMapOf<UserId, Puppet>()
        var connection: Connection? = null
        fun dbInit(connection: Connection) {
            this.connection = connection
            connection
                    .createStatement()
                    .executeUpdate(
                            """
                                CREATE TABLE IF NOT EXISTS puppets (
                                    mxid TEXT PRIMARY KEY NOT NULL,
                                    qqid INTEGER NOT NULL,
                                    password TEXT NOT NULL,
                                    device_json TEXT NOT NULL
                                )
                            """
                    )
        }
        suspend fun getPuppet(mxid: UserId): Puppet? =
                if (byMxid.containsKey(mxid)) byMxid[mxid]!! else null
        // Read
        suspend fun loadAll(matrixApiClient: MatrixApiClient, config: Config) {
            val statement =
                    connection!!.prepareStatement(
                            "SELECT mxid, qqid, password, device_json FROM puppets"
                    )
            val rs = statement.executeQuery()
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
