package land.melty.matrixqqbridge

import io.ktor.client.request.get
import io.ktor.http.ContentType
import io.ktor.utils.io.ByteReadChannel
import java.sql.Connection
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import kotlinx.serialization.json.Json
import net.folivo.trixnity.client.api.MatrixApiClient
import net.folivo.trixnity.core.model.UserId
import net.folivo.trixnity.core.model.events.m.room.RoomMessageEventContent
import net.mamoe.mirai.Bot
import net.mamoe.mirai.BotFactory
import net.mamoe.mirai.contact.Group
import net.mamoe.mirai.contact.User
import net.mamoe.mirai.event.events.GroupMessageEvent
import net.mamoe.mirai.event.events.MessageRecallEvent
import net.mamoe.mirai.event.events.NudgeEvent
import net.mamoe.mirai.event.events.OtherClientMessageEvent
import net.mamoe.mirai.message.data.MessageSource
import net.mamoe.mirai.message.data.MessageSourceKind
import net.mamoe.mirai.utils.BotConfiguration.MiraiProtocol.ANDROID_PAD
import net.mamoe.mirai.utils.DeviceInfo
import net.mamoe.mirai.utils.LoginSolver
import org.jsoup.nodes.Element
import org.jsoup.nodes.TextNode

class PuppetLoginSolver(val matrixApiClient: MatrixApiClient, val mxid: UserId) : LoginSolver() {
    val room = ManagementRoom.getManagementRoom(mxid)!!
    override suspend fun onSolvePicCaptcha(bot: Bot, data: ByteArray): String? {
        matrixApiClient.rooms.sendMessageEvent(
                room.roomId,
                RoomMessageEventContent.TextMessageEventContent(
                        "Captcha required, send the code in the image:"
                )
        )
        val mxc =
                matrixApiClient
                        .media
                        .upload(ByteReadChannel(data), data.size.toLong(), ContentType.Image.PNG)
                        .getOrThrow()
                        .contentUri
        matrixApiClient.rooms.sendMessageEvent(
                room.roomId,
                RoomMessageEventContent.ImageMessageEventContent("captcha.png", url = mxc)
        )
        room.state = ManagementRoom.Command.CAPTCHA
        room.update()
        return suspendCoroutine { coroutine -> room.onCaptcha = { coroutine.resume(it) } }
    }
    override val isSliderCaptchaSupported = true
    override suspend fun onSolveSliderCaptcha(bot: Bot, url: String): String {
        matrixApiClient.rooms.sendMessageEvent(
                room.roomId,
                RoomMessageEventContent.TextMessageEventContent(
                        "Slider captcha required, please:\n\n" +
                                "1. Open your browser. Then open DevTools using Ctrl+Shift+I.\n" +
                                "2. Open this URL ${url} in the tab and solve the captcha.\n" +
                                "3. Open the Network tab in DevTools. Find the request named `cap_union_new_verify`.\n" +
                                "4. Send me the value of the `ticket` field (without quotation marks)."
                )
        )
        room.state = ManagementRoom.Command.CAPTCHA
        room.update()
        return suspendCoroutine { coroutine -> room.onCaptcha = { coroutine.resume(it) } }
    }
    override suspend fun onSolveUnsafeDeviceLoginVerify(bot: Bot, url: String): String {
        matrixApiClient.rooms.sendMessageEvent(
                room.roomId,
                RoomMessageEventContent.TextMessageEventContent(
                        "Device lock / unauthorized device login detected, " +
                                "please open ${url} in your browser and complete the challege. " +
                                "Tell me you've done using the `!done` command."
                )
        )
        room.state = ManagementRoom.Command.CAPTCHA
        room.update()
        return suspendCoroutine { coroutine -> room.onCaptcha = { coroutine.resume("") } }
    }
}

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
                loginSolver = PuppetLoginSolver(matrixApiClient!!, mxid)
            }

    suspend fun connect(matrixApiClient: MatrixApiClient, config: Config) {
        bot.login()
        bot.eventChannel.subscribeAlways<GroupMessageEvent> {
            val ghost = Ghost.get(sender, matrixApiClient, config)
            val portal = Portal.get(subject, matrixApiClient, config)
            // Invite and set permission for the puppet,
            // before the duplicated message check to make sure all puppets in the group are invited
            portal.addMember(subject.botAsMember, matrixApiClient, config, mxid = mxid)

            // Prevent message being sent multiple times with multiple puppets logged in
            val source = message.get(MessageSource.Key)
            if (Messages.getEventId(source!!, MessageSourceKind.GROUP) != null)
                    return@subscribeAlways

            // Invite and set permission for the sender, after the duplicated message check to
            // prevent the ghost of another puppet from getting invited
            portal.addMember(sender, matrixApiClient, config)
            // QQ message -> Matrix messages
            message.toMessageEventContents(
                            matrixApiClient,
                            MessageSourceKind.GROUP,
                            portal.roomId!!,
                            config,
                            subject
                    )
                    .forEach { mec ->
                        val eventId =
                                matrixApiClient
                                        .rooms
                                        .sendMessageEvent(
                                                portal.roomId!!,
                                                mec,
                                                asUserId = ghost.userId
                                        )
                                        .getOrThrow()
                        Messages.save(source, eventId, MessageSourceKind.GROUP)
                    }
        }
        bot.eventChannel.subscribeAlways<NudgeEvent> {
            val fromId =
                    if (from is User) Ghost.get(from as User, matrixApiClient, config).userId
                    else return@subscribeAlways
            data class Result(val id: UserId, val nick: String)
            val (targetId, targetNick) =
                    if (target is User) {
                        val ghost = Ghost.get(target as User, matrixApiClient, config)
                        Result(ghost.userId, ghost.nick)
                    } else {
                        val puppet = Puppet.getPuppet(target.id)
                        val nick = matrixApiClient.users.getDisplayName(puppet!!.mxid).getOrNull()
                        Result(puppet.mxid, nick ?: puppet.mxid.toString())
                    }
            val actions =
                    (if (action == "") "" else " and ${action}") +
                            (if (suffix == "") "" else ", ${suffix}")
            val roomId =
                    if (subject is Group)
                            Portal.get(subject as Group, matrixApiClient, config).roomId!!
                    else return@subscribeAlways
            val link = Element("a")
            link.attr("href", MatrixToURL(targetId).toString())
            link.appendText(targetNick)
            matrixApiClient
                    .rooms
                    .sendMessageEvent(
                            roomId,
                            RoomMessageEventContent.EmoteMessageEventContent(
                                    "nudges ${targetNick}${actions}",
                                    "org.matrix.custom.html",
                                    "nudges ${link}${TextNode(actions)}"
                            ),
                            asUserId = fromId
                    )
                    .getOrThrow()
        }
        bot.eventChannel.subscribeAlways<MessageRecallEvent.GroupRecall> {}
        bot.eventChannel.subscribeAlways<OtherClientMessageEvent> {}
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
        Puppet.byMxId[mxid] = this
        Puppet.byQqId[qqid] = this
    }

    companion object {
        val byMxId = mutableMapOf<UserId, Puppet>()
        val byQqId = mutableMapOf<Long, Puppet>()
        var connection: Connection? = null
        var matrixApiClient: MatrixApiClient? = null
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
        fun getPuppet(mxid: UserId): Puppet? =
                if (byMxId.containsKey(mxid)) byMxId[mxid]!! else null
        fun getPuppet(qqid: Long): Puppet? = if (byQqId.containsKey(qqid)) byQqId[qqid]!! else null
        // Read
        suspend fun loadAll(matrixApiClient: MatrixApiClient, config: Config) {
            val rs =
                    connection!!
                            .createStatement()
                            .executeQuery("SELECT mxid, qqid, password, device_json FROM puppets")
            while (rs.next()) {
                val mxid = UserId(rs.getString("mxid"))
                val qqid = rs.getLong("qqid")
                val puppet =
                        Puppet(mxid, qqid, rs.getString("password"), rs.getString("device_json"))
                puppet.connect(matrixApiClient, config)
                byMxId[mxid] = puppet
                byQqId[qqid] = puppet
            }
        }
    }
}
