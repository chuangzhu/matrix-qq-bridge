package land.melty.matrixappserviceqq

// import net.mamoe.mirai.utils.LoginSolver
import com.charleskorn.kaml.Yaml
import io.ktor.http.Url
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import java.util.UUID
import kotlin.system.exitProcess
import kotlin.text.removePrefix
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.serializer
import land.melty.matrixappserviceqq.config.Config
import land.melty.matrixappserviceqq.config.RegistrationConfig
import net.folivo.trixnity.appservice.rest.DefaultAppserviceService
import net.folivo.trixnity.appservice.rest.MatrixAppserviceProperties
import net.folivo.trixnity.appservice.rest.event.AppserviceEventTnxService
import net.folivo.trixnity.appservice.rest.matrixAppserviceModule
import net.folivo.trixnity.appservice.rest.room.AppserviceRoomService
import net.folivo.trixnity.appservice.rest.room.CreateRoomParameter
import net.folivo.trixnity.appservice.rest.user.AppserviceUserService
import net.folivo.trixnity.appservice.rest.user.RegisterUserParameter
import net.folivo.trixnity.client.api.MatrixApiClient
import net.folivo.trixnity.core.model.RoomAliasId
import net.folivo.trixnity.core.model.RoomId
import net.folivo.trixnity.core.model.UserId
import net.folivo.trixnity.core.model.events.Event
import net.folivo.trixnity.core.model.events.Event.RoomEvent
import net.folivo.trixnity.core.model.events.m.room.MemberEventContent
import net.folivo.trixnity.core.model.events.m.room.RoomMessageEventContent.TextMessageEventContent
import net.mamoe.mirai.BotFactory
import net.mamoe.mirai.event.events.GroupMessageEvent
import net.mamoe.mirai.message.data.content
import net.mamoe.mirai.network.LoginFailedException
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
    suspend fun connect() {
        bot.login()
        bot.eventChannel.subscribeAlways<GroupMessageEvent> { println(message.content) }
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
        // Read
        suspend fun getPuppet(mxid: UserId): Puppet? {
            if (byMxid.containsKey(mxid)) return byMxid[mxid]!!
            val statement =
                    connection!!.prepareStatement(
                            "SELECT qqid, password, device_json FROM puppets WHERE mxid = ?"
                    )
            statement.setString(1, mxid.toString())
            val rs = statement.executeQuery()
            if (rs.next() == false) return null
            val puppet =
                    Puppet(
                            mxid,
                            rs.getLong("qqid"),
                            rs.getString("password"),
                            rs.getString("device_json")
                    )
            byMxid[mxid] = puppet
            return puppet
        }
    }
}

class EventTnxService : AppserviceEventTnxService {
    override suspend fun eventTnxProcessingState(
            tnxId: String
    ): AppserviceEventTnxService.EventTnxProcessingState {
        return AppserviceEventTnxService.EventTnxProcessingState.NOT_PROCESSED
    }
    override suspend fun onEventTnxProcessed(tnxId: String) {}
}

class UserService(override val matrixApiClient: MatrixApiClient, val config: Config) :
        AppserviceUserService {
    override suspend fun userExistingState(
            userId: UserId
    ): AppserviceUserService.UserExistingState {
        val qqid = userId.localpart.removePrefix(config.appservice.username_prefix)
        println(qqid)
        return AppserviceUserService.UserExistingState.CAN_BE_CREATED
    }
    override suspend fun getRegisterUserParameter(userId: UserId): RegisterUserParameter {
        return RegisterUserParameter(displayName = "Alice (QQ)")
    }
    override suspend fun onRegisteredUser(userId: UserId) {}
}

class RoomService(override val matrixApiClient: MatrixApiClient) : AppserviceRoomService {
    override suspend fun roomExistingState(
            roomAlias: RoomAliasId
    ): AppserviceRoomService.RoomExistingState {
        return AppserviceRoomService.RoomExistingState.CAN_BE_CREATED
    }
    override suspend fun getCreateRoomParameter(roomAlias: RoomAliasId): CreateRoomParameter {
        return CreateRoomParameter()
    }
    override suspend fun onCreatedRoom(roomAlias: RoomAliasId, roomId: RoomId) {}
}

fun generateRegistration(config: Config) =
        RegistrationConfig(
                id = config.appservice.id,
                as_token = UUID.randomUUID().toString(),
                hs_token = UUID.randomUUID().toString(),
                url = config.appservice.address,
                sender_localpart = config.appservice.bot_username,
                namespaces =
                        RegistrationConfig.Namespaces(
                                users =
                                        listOf(
                                                RegistrationConfig.Namespaces.Pattern(
                                                        exclusive = true,
                                                        regex =
                                                                "@${config.appservice.username_prefix}.*:${config.homeserver.domain}"
                                                ),
                                                // The bot itself
                                                RegistrationConfig.Namespaces.Pattern(
                                                        exclusive = true,
                                                        regex =
                                                                "@${config.appservice.bot_username}:${config.homeserver.domain}"
                                                )
                                        ),
                                aliases =
                                        listOf(
                                                RegistrationConfig.Namespaces.Pattern(
                                                        exclusive = true,
                                                        regex =
                                                                "#${config.appservice.alias_prefix}.*:${config.homeserver.domain}"
                                                )
                                        )
                        )
        )

class DirectRoom(val roomId: RoomId, var userId: UserId, var state: Command? = Command.DEFAULT) {
    enum class Command(val value: Int) {
        DEFAULT(0),
        LOGIN(1);
        companion object {
            fun fromInt(value: Int) = Command.values().first { it.value == value }
        }
    }
    // Create
    fun insert() {
        val statement =
                connection!!.prepareStatement("INSERT OR IGNORE INTO direct_rooms VALUES (?, ?, ?)")
        statement.setString(1, roomId.toString())
        statement.setString(2, userId.toString())
        statement.setInt(3, state!!.value)
        statement.executeUpdate()
    }
    // Update
    fun update() {
        val statement =
                connection!!.prepareStatement(
                        """UPDATE direct_rooms SET user_id = ?, state = ? WHERE room_id = ?"""
                )
        statement.setString(1, userId.toString())
        statement.setInt(2, state!!.value)
        statement.setString(3, roomId.toString())
        statement.executeUpdate()
    }
    companion object {
        var connection: Connection? = null
        fun dbInit(connection: Connection) {
            this.connection = connection
            connection
                    .createStatement()
                    .executeUpdate(
                            """
                                CREATE TABLE IF NOT EXISTS direct_rooms (
                                    room_id TEXT PRIMARY KEY NOT NULL,
                                    user_id TEXT NOT NULL,
                                    state INTEGER NOT NULL
                                )
                            """
                    )
        }
        // Read
        fun getDirectRoom(roomId: RoomId): DirectRoom? {
            val statement =
                    connection!!.prepareStatement("SELECT * FROM direct_rooms WHERE room_id = ?")
            statement.setString(1, roomId.toString())
            val rs = statement.executeQuery()
            if (rs.next() == false) return null
            return DirectRoom(
                    roomId = RoomId(rs.getString("room_id")),
                    userId = UserId(rs.getString("user_id")),
                    state = Command.fromInt(rs.getInt("state")),
            )
        }
    }
}

fun dbInit(connection: Connection) {
    DirectRoom.dbInit(connection)
    Puppet.dbInit(connection)
}

suspend fun handleTextMessage(
        it: Event<TextMessageEventContent>,
        matrixApiClient: MatrixApiClient,
        botUserId: UserId
) {
    it as RoomEvent<TextMessageEventContent>
    val room = DirectRoom.getDirectRoom(it.roomId)
    if (room != null && it.sender != botUserId) {
        if (it.content.body == "!cancel") {
            matrixApiClient.rooms.sendMessageEvent(
                    it.roomId,
                    TextMessageEventContent("Command canceled.")
            )
            room.state = DirectRoom.Command.DEFAULT
            room.update()
        } else if (room.state == DirectRoom.Command.DEFAULT) {
            if (it.content.body == "!login") {
                val puppet = Puppet.getPuppet(room.userId)
                if (puppet != null) {
                    matrixApiClient.rooms.sendMessageEvent(
                            it.roomId,
                            TextMessageEventContent("You've already logged in.")
                    )
                } else {
                    matrixApiClient.rooms.sendMessageEvent(
                            it.roomId,
                            TextMessageEventContent(
                                    "Please send your QQ number in the first line, " +
                                            "and your password in the second line. " +
                                            "Optionally, you can send a minified device.json in the third line."
                            )
                    )
                    room.state = DirectRoom.Command.LOGIN
                    room.update()
                }
            }
        } else if (room.state == DirectRoom.Command.LOGIN) {
            val list = it.content.body.trim().split("\n")
            if (list.size < 2 || list.size > 3) {
                matrixApiClient.rooms.sendMessageEvent(
                        it.roomId,
                        TextMessageEventContent("Invalid input.")
                )
                print(list)
                return
            }
            val qqid =
                    try {
                        list[0].toLong()
                    } catch (e: NumberFormatException) {
                        matrixApiClient.rooms.sendMessageEvent(
                                it.roomId,
                                TextMessageEventContent("Invalid QQ number `${list[0]}`.")
                        )
                        return
                    }
            val puppet =
                    if (list.size == 2) Puppet(room.userId, qqid, list[1])
                    else Puppet(room.userId, qqid, list[1], list[2])
            try {
                puppet.connect()
            } catch (e: LoginFailedException) {
                matrixApiClient.rooms.sendMessageEvent(
                        it.roomId,
                        TextMessageEventContent("Login failed.")
                )
                return
            }
            puppet.insert()
        }
    }
}

fun main(args: Array<String>) {
    if (args.size == 0) exitProcess(1)
    val config =
            Yaml.default.decodeFromString<Config>(Config.serializer(), File(args[0]).readText())
    // Generate registration YAML if not provided
    if (args.size == 1) {
        println(
                Yaml.default.encodeToString(
                        RegistrationConfig.serializer(),
                        generateRegistration(config)
                )
        )
        return
    }
    val registrationConfig =
            Yaml.default.decodeFromString<RegistrationConfig>(
                    RegistrationConfig.serializer(),
                    File(args[1]).readText()
            )
    val connection = DriverManager.getConnection("jdbc:sqlite:matrix-appservice-qq.db")
    dbInit(connection)
    val matrixApiClient =
            MatrixApiClient(baseUrl = Url(config.homeserver.address)).apply {
                accessToken.value = registrationConfig.as_token
            }
    val botUserId = UserId(config.appservice.bot_username, config.homeserver.domain)
    runBlocking {
        matrixApiClient.authentication.register(
                isAppservice = true,
                username = config.appservice.bot_username
        )
        matrixApiClient.users.setDisplayName(
                userId = botUserId,
                displayName = "QQ Bridge",
                asUserId = botUserId
        )
    }
    val eventTnxService = EventTnxService()
    val userService = UserService(matrixApiClient, config)
    val roomService = RoomService(matrixApiClient)
    val appserviceService = DefaultAppserviceService(eventTnxService, userService, roomService)
    appserviceService.subscribe<TextMessageEventContent> { handleTextMessage(it, matrixApiClient, botUserId) }
    appserviceService.subscribe<MemberEventContent> {
        it as RoomEvent<MemberEventContent>
        if (it.content.membership == MemberEventContent.Membership.INVITE &&
                        it.content.isDirect == true
        ) {
            val room = DirectRoom(it.roomId, it.sender)
            room.insert()
            matrixApiClient.rooms.joinRoom(roomId = it.roomId, asUserId = botUserId)
            matrixApiClient.rooms.sendMessageEvent(
                    it.roomId,
                    TextMessageEventContent("Hello, I'm a QQ bridge bot.\nUse `!help` for help.")
            )
        }
    }
    appserviceService.subscribeAllEvents { println(it) }
    val engine =
            embeddedServer(
                    Netty,
                    host = config.appservice.hostname,
                    port = config.appservice.port
            ) {
                matrixAppserviceModule(
                        MatrixAppserviceProperties(registrationConfig.hs_token),
                        appserviceService
                )
            }
    engine.start(wait = true)
}
