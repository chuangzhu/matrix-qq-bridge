package land.melty.matrixappserviceqq

import com.charleskorn.kaml.Yaml
import io.ktor.http.Url
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import java.io.File
import java.util.UUID
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
import net.folivo.trixnity.core.model.events.m.room.MemberEventContent
import net.folivo.trixnity.core.model.events.m.room.RoomMessageEventContent.TextMessageEventContent
import net.mamoe.mirai.Bot
import net.mamoe.mirai.BotFactory
import net.mamoe.mirai.event.events.GroupMessageEvent
import net.mamoe.mirai.message.data.content
import net.mamoe.mirai.utils.BotConfiguration.MiraiProtocol.ANDROID_PAD

class Puppet(val qqid: Long, val password: String) {
    val bot: Bot
    init {
        bot =
                BotFactory.newBot(qqid, password) {
                    fileBasedDeviceInfo()
                    protocol = ANDROID_PAD
                }
    }
    suspend fun connect() {
        bot.login()
        bot.eventChannel.subscribeAlways<GroupMessageEvent> { println(message.content) }
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

class UserService(matrixApiClient: MatrixApiClient) : AppserviceUserService {
    override val matrixApiClient = matrixApiClient
    override suspend fun userExistingState(
            userId: UserId
    ): AppserviceUserService.UserExistingState {
        return AppserviceUserService.UserExistingState.CAN_BE_CREATED
    }
    override suspend fun getRegisterUserParameter(userId: UserId): RegisterUserParameter {
        return RegisterUserParameter(displayName = "Alice (QQ)")
    }
    override suspend fun onRegisteredUser(userId: UserId) {}
}

class RoomService(matrixApiClient: MatrixApiClient) : AppserviceRoomService {
    override val matrixApiClient = matrixApiClient
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

fun main(args: Array<String>): Int {
    if (args.size == 0) return 1
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
        return 0
    }
    val registrationConfig =
            Yaml.default.decodeFromString<RegistrationConfig>(
                    RegistrationConfig.serializer(),
                    File(args[1]).readText()
            )
    val matrixApiClient =
            MatrixApiClient(baseUrl = Url(config.homeserver.address)).apply {
                accessToken.value = registrationConfig.hs_token
            }
    val eventTnxService = EventTnxService()
    val userService = UserService(matrixApiClient)
    val roomService = RoomService(matrixApiClient)
    val appserviceService = DefaultAppserviceService(eventTnxService, userService, roomService)
    appserviceService.subscribe<TextMessageEventContent> { println(it.content.body) }
    appserviceService.subscribe<MemberEventContent> {
        println("${it.content.displayName} did ${it.content.membership}")
    }
    val engine =
            embeddedServer(
                    Netty,
                    host = config.appservice.hostname,
                    port = config.appservice.port
            ) {
                matrixAppserviceModule(
                        MatrixAppserviceProperties(registrationConfig.as_token),
                        appserviceService
                )
            }
    engine.start(wait = true)
    return 0
}
