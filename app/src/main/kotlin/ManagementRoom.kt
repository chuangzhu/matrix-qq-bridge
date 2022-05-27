package land.melty.matrixappserviceqq

import java.sql.Connection
import net.folivo.trixnity.client.api.MatrixApiClient
import net.folivo.trixnity.core.model.RoomId
import net.folivo.trixnity.core.model.UserId
import net.folivo.trixnity.core.model.events.Event
import net.folivo.trixnity.core.model.events.Event.RoomEvent
import net.folivo.trixnity.core.model.events.m.room.RoomMessageEventContent.TextMessageEventContent
import net.mamoe.mirai.network.LoginFailedException
import org.jsoup.nodes.TextNode

class ManagementRoom(val roomId: RoomId, var userId: UserId, var state: Command = Command.DEFAULT) {

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
                connection!!.prepareStatement(
                        "INSERT OR IGNORE INTO management_rooms VALUES (?, ?, ?)"
                )
        statement.setString(1, roomId.toString())
        statement.setString(2, userId.toString())
        statement.setInt(3, state.value)
        statement.executeUpdate()
    }

    // Update
    fun update() {
        val statement =
                connection!!.prepareStatement(
                        """UPDATE management_rooms SET user_id = ?, state = ? WHERE room_id = ?"""
                )
        statement.setString(1, userId.toString())
        statement.setInt(2, state.value)
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
                                CREATE TABLE IF NOT EXISTS management_rooms (
                                    room_id TEXT PRIMARY KEY NOT NULL,
                                    user_id TEXT NOT NULL,
                                    state INTEGER NOT NULL
                                )
                            """
                    )
        }

        // Read
        fun getManagementRoom(roomId: RoomId): ManagementRoom? {
            val statement =
                    connection!!.prepareStatement(
                            "SELECT * FROM management_rooms WHERE room_id = ?"
                    )
            statement.setString(1, roomId.toString())
            val rs = statement.executeQuery()
            if (rs.next() == false) return null
            return ManagementRoom(
                    roomId = RoomId(rs.getString("room_id")),
                    userId = UserId(rs.getString("user_id")),
                    state = Command.fromInt(rs.getInt("state")),
            )
        }

        suspend fun handleTextMessage(
                event: Event<TextMessageEventContent>,
                matrixApiClient: MatrixApiClient,
                config: Config
        ) {
            event as RoomEvent<TextMessageEventContent>
            val room = getManagementRoom(event.roomId)
            val botUserId = UserId(config.appservice.botUsername, config.homeserver.domain)
            if (room != null && event.sender != botUserId) {
                if (config.bridge.getPermission(room.userId) < Config.Bridge.Permission.USER) {
                    matrixApiClient.rooms.sendMessageEvent(
                            event.roomId,
                            TextMessageEventContent(
                                    "You don't have the permission to use management room."
                            )
                    )
                    return
                }
                if (event.content.body == "!cancel") {
                    matrixApiClient.rooms.sendMessageEvent(
                            event.roomId,
                            TextMessageEventContent("Command canceled.")
                    )
                    room.state = Command.DEFAULT
                    room.update()
                } else if (room.state == Command.DEFAULT) {
                    if (event.content.body == "!help") {
                        matrixApiClient.rooms.sendMessageEvent(
                                event.roomId,
                                TextMessageEventContent(
                                        "!login - Get instruction to login to QQ.\n" +
                                                "!listclient - List other clients of current QQ account.\n" +
                                                "!cancel - Cancel an ongoing action",
                                        format = "org.matrix.custom.html",
                                        formattedBody =
                                                "<ul><li><code>!login</code> - Get instruction to login to QQ.</li>" +
                                                        "<li><code>!listclient</code> - List other clients of current QQ account.</li>" +
                                                        "<li><code>!cancel</code> - Cancel an ongoing action</li></ul>"
                                )
                        )
                    } else if (event.content.body == "!login") {
                        val puppet = Puppet.getPuppet(room.userId)
                        if (puppet != null) {
                            matrixApiClient.rooms.sendMessageEvent(
                                    event.roomId,
                                    TextMessageEventContent("You've already logged in.")
                            )
                        } else {
                            matrixApiClient.rooms.sendMessageEvent(
                                    event.roomId,
                                    TextMessageEventContent(
                                            "Please send your QQ number in the first line, " +
                                                    "and your password in the second line. " +
                                                    "Optionally, you can send a minified device.json in the third line."
                                    )
                            )
                            room.state = Command.LOGIN
                            room.update()
                        }
                    } else if (event.content.body == "!listclients") {
                        val puppet = Puppet.getPuppet(room.userId)
                        if (puppet != null) {
                            matrixApiClient.rooms.sendMessageEvent(
                                    event.roomId,
                                    TextMessageEventContent(
                                            "### Other clients" +
                                                    puppet.bot
                                                            .otherClients
                                                            .map {
                                                                "\n* ${it.info.deviceName}" +
                                                                        "\n  Platform: ${it.info.platform}" +
                                                                        "\n  Kind: ${it.info.deviceKind}"
                                                            }
                                                            .joinToString(""),
                                            format = "org.matrix.custom.html",
                                            formattedBody =
                                                    "<h3>Other clients</h3><ul>${
                                                        puppet.bot.otherClients.map {
                                                            "<li>${TextNode(it.info.deviceName)}<br>" +
                                                            "Platform: ${TextNode(it.info.platform.toString())}<br>" +
                                                            "Kind: ${TextNode(it.info.deviceKind)}</li>"
                                                        }.joinToString("")
                                                    }</ul>"
                                    )
                            )
                        } else {
                            matrixApiClient.rooms.sendMessageEvent(
                                    event.roomId,
                                    TextMessageEventContent("You've not logged in.")
                            )
                        }
                    }
                } else if (room.state == Command.LOGIN) {
                    val list = event.content.body.trim().split("\n")
                    if (list.size < 2 || list.size > 3) {
                        matrixApiClient.rooms.sendMessageEvent(
                                event.roomId,
                                TextMessageEventContent("Invalid input.")
                        )
                        return
                    }
                    val qqid =
                            try {
                                list[0].toLong()
                            } catch (e: NumberFormatException) {
                                matrixApiClient.rooms.sendMessageEvent(
                                        event.roomId,
                                        TextMessageEventContent(
                                                "Invalid QQ number `${list[0]}`.",
                                                format = "org.matrix.custom.html",
                                                formattedBody =
                                                        "Invalid QQ number <code>${TextNode(list[0])}</code>."
                                        )
                                )
                                return
                            }
                    val puppet =
                            if (list.size == 2) Puppet(room.userId, qqid, list[1])
                            else Puppet(room.userId, qqid, list[1], list[2])
                    try {
                        puppet.connect(matrixApiClient, config)
                    } catch (e: LoginFailedException) {
                        matrixApiClient.rooms.sendMessageEvent(
                                event.roomId,
                                TextMessageEventContent("Login failed.")
                        )
                        return
                    }
                    puppet.insert()
                    room.state = Command.DEFAULT
                    room.update()
                    matrixApiClient.rooms.sendMessageEvent(
                            event.roomId,
                            TextMessageEventContent("Successfully logged in.")
                    )
                }
            }
        }
    }
}
