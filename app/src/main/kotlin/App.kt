package land.melty.matrixappserviceqq

// import net.mamoe.mirai.utils.LoginSolver
import com.charleskorn.kaml.Yaml
import io.ktor.http.Url
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import kotlin.system.exitProcess
import kotlin.text.removePrefix
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.serializer
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
import net.folivo.trixnity.core.model.events.Event.RoomEvent
import net.folivo.trixnity.core.model.events.m.room.MemberEventContent
import net.folivo.trixnity.core.model.events.m.room.RoomMessageEventContent.TextMessageEventContent

class Portal(mxid: RoomId, qqid: Int) {}

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
        val qqid = userId.localpart.removePrefix(config.appservice.usernamePrefix)
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

fun dbInit(connection: Connection) {
    ManagementRoom.dbInit(connection)
    Puppet.dbInit(connection)
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
                        RegistrationConfig.generate(config)
                )
        )
        return
    }
    val registrationConfig =
            Yaml.default.decodeFromString<RegistrationConfig>(
                    RegistrationConfig.serializer(),
                    File(args[1]).readText()
            )
    val connection = DriverManager.getConnection("jdbc:${config.appservice.database}")
    dbInit(connection)
    val matrixApiClient =
            MatrixApiClient(baseUrl = Url(config.homeserver.address)).apply {
                accessToken.value = registrationConfig.asToken
            }
    val botUserId = UserId(config.appservice.botUsername, config.homeserver.domain)
    runBlocking {
        matrixApiClient.authentication.register(
                isAppservice = true,
                username = config.appservice.botUsername
        )
        matrixApiClient.users.setDisplayName(userId = botUserId, displayName = "QQ Bridge")
        Puppet.loadAll(matrixApiClient, config)
    }
    val eventTnxService = EventTnxService()
    val userService = UserService(matrixApiClient, config)
    val roomService = RoomService(matrixApiClient)
    val appserviceService = DefaultAppserviceService(eventTnxService, userService, roomService)
    appserviceService.subscribe<TextMessageEventContent> {
        ManagementRoom.handleTextMessage(it, matrixApiClient, config)
    }
    appserviceService.subscribe<MemberEventContent> {
        it as RoomEvent<MemberEventContent>
        if (it.content.membership == MemberEventContent.Membership.INVITE &&
                        it.content.isDirect == true &&
                        it.sender != botUserId
        ) {
            val room = ManagementRoom(it.roomId, it.sender)
            room.insert()
            matrixApiClient.rooms.joinRoom(roomId = it.roomId)
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
                        MatrixAppserviceProperties(registrationConfig.hsToken),
                        appserviceService
                )
            }
    engine.start(wait = true)
}
