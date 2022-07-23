package land.melty.matrixqqbridge

import com.charleskorn.kaml.Yaml
import io.ktor.application.install
import io.ktor.features.CallLogging
import io.ktor.http.Url
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import kotlin.system.exitProcess
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.serializer
import net.folivo.trixnity.appservice.rest.DefaultAppserviceService
import net.folivo.trixnity.appservice.rest.MatrixAppserviceProperties
import net.folivo.trixnity.appservice.rest.event.AppserviceEventTnxService
import net.folivo.trixnity.appservice.rest.matrixAppserviceModule
import net.folivo.trixnity.client.api.MatrixApiClient
import net.folivo.trixnity.client.api.createMatrixApiClientEventContentSerializerMappings
import net.folivo.trixnity.core.model.events.Event.MessageEvent
import net.folivo.trixnity.core.model.events.Event.StateEvent
import net.folivo.trixnity.core.model.events.MessageEventContent
import net.folivo.trixnity.core.model.events.m.room.MemberEventContent
import net.folivo.trixnity.core.model.events.m.room.RoomMessageEventContent.TextMessageEventContent

class EventTnxService : AppserviceEventTnxService {
    override suspend fun eventTnxProcessingState(
            tnxId: String
    ): AppserviceEventTnxService.EventTnxProcessingState {
        return AppserviceEventTnxService.EventTnxProcessingState.NOT_PROCESSED
    }
    override suspend fun onEventTnxProcessed(tnxId: String) {}
}

fun dbInit(connection: Connection) {
    ManagementRoom.dbInit(connection)
    Puppet.dbInit(connection)
    Ghost.dbInit(connection)
    Portal.dbInit(connection)
    Messages.dbInit(connection)
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
            MatrixApiClient(
                            baseUrl = Url(config.homeserver.address),
                            // Register custom event types in MatrixApiClient
                            eventContentSerializerMappings =
                                    createMatrixApiClientEventContentSerializerMappings(
                                            CustomEventContentSerializerMappings
                                    ),
                    )
                    .apply { accessToken.value = registrationConfig.asToken }
    Puppet.matrixApiClient = matrixApiClient
    runBlocking {
        matrixApiClient.authentication.register(
                isAppservice = true,
                username = config.appservice.botUsername
        )
        matrixApiClient.users.setDisplayName(userId = config.botUserId, displayName = "QQ Bridge")
        Puppet.loadAll(matrixApiClient, config)
    }
    val eventTnxService = EventTnxService()
    val userService = UserService(matrixApiClient, config)
    val roomService = RoomService(matrixApiClient, config)
    val appserviceService = DefaultAppserviceService(eventTnxService, userService, roomService)
    appserviceService.subscribe<TextMessageEventContent> {
        it as MessageEvent<TextMessageEventContent>
        ManagementRoom.handleTextMessage(it, matrixApiClient, config)
    }
    appserviceService.subscribe<MessageEventContent> {
        it as MessageEvent<MessageEventContent>
        Portal.handleMatrixMessage(it, matrixApiClient, config)
    }
    appserviceService.subscribe<MemberEventContent> {
        it as StateEvent<MemberEventContent>
        if (it.content.membership == MemberEventContent.Membership.INVITE &&
                        it.content.isDirect == true &&
                        it.sender != config.botUserId
        ) {
            val room = ManagementRoom(it.roomId, it.sender)
            room.insert()
            matrixApiClient.rooms.joinRoom(roomId = it.roomId)
            matrixApiClient.rooms.sendMessageEvent(
                    it.roomId,
                    TextMessageEventContent(
                            body = "Hello, I'm a QQ bridge bot.\nUse `!help` for help.",
                            format = "org.matrix.custom.html",
                            formattedBody =
                                    "Hello, I'm a QQ bridge bot.<br>Use <code>!help</code> for help."
                    )
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
                install(CallLogging) { level = org.slf4j.event.Level.WARN }
                matrixAppserviceModule(
                        MatrixAppserviceProperties(registrationConfig.hsToken),
                        appserviceService,
                        customMappings = CustomEventContentSerializerMappings
                )
            }
    engine.start(wait = true)
}
