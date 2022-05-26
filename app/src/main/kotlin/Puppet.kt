package land.melty.matrixappserviceqq

// import net.mamoe.mirai.utils.LoginSolver

import io.ktor.client.request.get
import java.sql.Connection
import kotlinx.serialization.json.Json
import net.folivo.trixnity.client.api.MatrixApiClient
import net.folivo.trixnity.core.model.UserId
import net.mamoe.mirai.BotFactory
import net.mamoe.mirai.event.events.GroupMessageEvent
import net.mamoe.mirai.message.data.MessageSource
import net.mamoe.mirai.message.data.MessageSourceKind
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
        bot.eventChannel.subscribeAlways<GroupMessageEvent> {
            val ghost = Ghost.get(sender, matrixApiClient, config)
            val portal = Portal.get(subject, matrixApiClient, config)
            // TODO: store relationships
            matrixApiClient.rooms.inviteUser(portal.roomId!!, mxid)
            matrixApiClient.rooms.inviteUser(portal.roomId!!, ghost.userId)
            matrixApiClient.rooms.joinRoom(portal.roomId!!, asUserId = ghost.userId)
            // Send the message
            val source = message.get(MessageSource.Key)
            if (Messages.getEventId(source!!, MessageSourceKind.GROUP) != null)
                    return@subscribeAlways
            message.toMessageEventContents(matrixApiClient).forEach { mec ->
                val eventId =
                        matrixApiClient
                                .rooms
                                .sendMessageEvent(portal.roomId!!, mec, asUserId = ghost.userId)
                                .getOrThrow()
                Messages.save(source, eventId, MessageSourceKind.GROUP)
            }
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
