package land.melty.matrixqqbridge

import io.ktor.client.HttpClient
import io.ktor.client.call.*
import io.ktor.client.engine.cio.CIO
import io.ktor.client.features.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.http.content.*
import io.ktor.utils.io.*
import java.sql.Connection
import net.folivo.trixnity.appservice.rest.room.AppserviceRoomService
import net.folivo.trixnity.appservice.rest.room.CreateRoomParameter
import net.folivo.trixnity.client.api.MatrixApiClient
import net.folivo.trixnity.core.model.RoomAliasId
import net.folivo.trixnity.core.model.RoomId
import net.folivo.trixnity.core.model.events.Event.MessageEvent
import net.folivo.trixnity.core.model.events.m.room.AvatarEventContent
import net.folivo.trixnity.core.model.events.m.room.RoomMessageEventContent.TextMessageEventContent
import net.folivo.trixnity.core.model.events.MessageEventContent
import net.mamoe.mirai.Bot
import net.mamoe.mirai.contact.Group
import net.mamoe.mirai.message.data.MessageSourceKind

class Portal(
        val qqid: Long,
        var roomId: RoomId?,
        var name: String,
        var avatarUrl: String?,
        val matrixApiClient: MatrixApiClient,
        val config: Config
) {
    val roomAliasId = config.getPortalAliasId(qqid)
    suspend fun create() {
        roomId =
                matrixApiClient
                        .rooms
                        .createRoom(
                                name = name,
                                roomAliasId = roomAliasId,
                                isDirect = false,
                        )
                        .getOrThrow()
    }

    suspend fun setAvatar(group: Group) {
        val client = HttpClient(CIO) { install(UserAgent) { agent = "QQClient" } }
        val response: HttpResponse = client.get(group.avatarUrl)
        val bytes: ByteReadChannel = response.receive()
        avatarUrl =
                matrixApiClient
                        .media
                        .upload(bytes, response.contentLength()!!, response.contentType()!!)
                        .getOrThrow()
                        .contentUri
        matrixApiClient.rooms.sendStateEvent(roomId!!, AvatarEventContent(avatarUrl!!))
    }

    // Update
    fun update() {
        val statement =
                connection!!.prepareStatement(
                        "UPDATE portal SET room_id = ?, name = ?, avatar_url = ? WHERE qqid = ?"
                )
        statement.setString(1, name)
        statement.setString(2, avatarUrl)
        statement.setLong(3, qqid)
        statement.executeUpdate()
    }
    // Create
    fun insert() {
        val statement =
                connection!!.prepareStatement("INSERT OR IGNORE INTO portal VALUES (?, ?, ?, ?)")
        statement.setLong(1, qqid)
        statement.setString(2, roomId!!.toString())
        statement.setString(3, name)
        statement.setString(4, avatarUrl)
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
                                CREATE TABLE IF NOT EXISTS portal (
                                    qqid INTEGER PRIMARY KEY NOT NULL,
                                    room_id TEXT NOT NULL,
                                    name TEXT NOT NULL,
                                    avatar_url TEXT
                                )
                            """
                    )
        }
        suspend fun get(group: Group, matrixApiClient: MatrixApiClient, config: Config): Portal {
            // Read
            val statement = connection!!.prepareStatement("SELECT * FROM portal WHERE qqid = ?")
            statement.setLong(1, group.id)
            val rs = statement.executeQuery()
            if (rs.next())
                    return Portal(
                            qqid = group.id,
                            roomId = RoomId(rs.getString("room_id")),
                            name = rs.getString("name"),
                            avatarUrl = rs.getString("avatar_url"),
                            matrixApiClient,
                            config
                    )
            // Create
            val portal =
                    Portal(
                            group.id,
                            roomId = null,
                            group.name,
                            avatarUrl = null,
                            matrixApiClient,
                            config
                    )
            portal.create()
            portal.setAvatar(group)
            portal.insert()
            return portal
        }
        suspend fun get(qqid: Long, matrixApiClient: MatrixApiClient, config: Config): Portal? {
            Bot.instances.forEach { bot ->
                val group = bot.getGroup(qqid)
                if (group != null) return get(group, matrixApiClient, config)
            }
            return null
        }
        suspend fun get(
                roomAliasId: RoomAliasId,
                matrixApiClient: MatrixApiClient,
                config: Config
        ): Portal? {
            val qqid =
                    roomAliasId
                            .localpart
                            .removePrefix(config.appservice.usernamePrefix)
                            .toLongOrNull()
                            ?: return null
            return Portal.get(qqid, matrixApiClient, config)
        }
        suspend fun get(roomId: RoomId, matrixApiClient: MatrixApiClient, config: Config): Portal? {
            // Read
            val statement = connection!!.prepareStatement("SELECT * FROM portal WHERE room_id = ?")
            statement.setString(1, roomId.toString())
            val rs = statement.executeQuery()
            if (rs.next())
                    return Portal(
                            qqid = rs.getLong("qqid"),
                            roomId = roomId,
                            name = rs.getString("name"),
                            avatarUrl = rs.getString("avatar_url"),
                            matrixApiClient,
                            config
                    )
            return null
        }

        // Matrix -> QQ
        suspend fun handleMatrixMessage(
                event: MessageEvent<MessageEventContent>,
                matrixApiClient: MatrixApiClient,
                config: Config
        ) {
            // Ignore if not a portal
            val portal = Portal.get(event.roomId, matrixApiClient, config) ?: return
            // Ignore ghosts' messages
            if (config.getGhostQqIdOrNull(event.sender) != null) return
            val puppet = Puppet.getPuppet(event.sender) ?: return
            // Ignore messages already sent to QQ
            if (Messages.getMessageSource(event.id, puppet.bot) != null) return
            // A user in the room with puppet may not be in the QQ group, ignore
            val group = puppet.bot.getGroup(portal.qqid) ?: return
            val receipt = group.sendMessage(event.content.toMessage(matrixApiClient, group, config) ?: return)
            Messages.save(receipt.source, event.id, MessageSourceKind.GROUP)
        }
    }
}

class RoomService(override val matrixApiClient: MatrixApiClient, val config: Config) :
        AppserviceRoomService {
    override suspend fun roomExistingState(
            roomAlias: RoomAliasId
    ): AppserviceRoomService.RoomExistingState {
        Portal.get(roomAlias, matrixApiClient, config)
                ?: return AppserviceRoomService.RoomExistingState.DOES_NOT_EXISTS
        return AppserviceRoomService.RoomExistingState.EXISTS
    }
    // Stub
    override suspend fun getCreateRoomParameter(roomAlias: RoomAliasId): CreateRoomParameter {
        return CreateRoomParameter()
    }
    override suspend fun onCreatedRoom(roomAlias: RoomAliasId, roomId: RoomId) {}
}
