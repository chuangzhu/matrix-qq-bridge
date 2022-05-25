package land.melty.matrixappserviceqq

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
import net.folivo.trixnity.appservice.rest.user.AppserviceUserService
import net.folivo.trixnity.appservice.rest.user.RegisterUserParameter
import net.folivo.trixnity.client.api.MatrixApiClient
import net.folivo.trixnity.core.model.UserId
import net.mamoe.mirai.Bot
import net.mamoe.mirai.contact.User

class Ghost(
        val qqid: Long,
        var nick: String,
        var avatarUrl: String?,
        val matrixApiClient: MatrixApiClient,
        val config: Config
) {
    val userId = config.getGhostId(qqid)
    suspend fun register() {
        matrixApiClient.authentication.register(username = userId.localpart, isAppservice = true)
        matrixApiClient.users.setDisplayName(
                userId = userId,
                displayName = "${nick} (QQ)",
                asUserId = userId
        )
    }

    suspend fun setAvatar(user: User) {
        val client = HttpClient(CIO) { install(UserAgent) { agent = "QQClient" } }
        val response: HttpResponse = client.get(user.avatarUrl)
        val bytes: ByteReadChannel = response.receive()
        avatarUrl =
                matrixApiClient
                        .media
                        .upload(bytes, response.contentLength()!!, response.contentType()!!)
                        .getOrThrow()
                        .contentUri
        matrixApiClient.users.setAvatarUrl(userId, avatarUrl, asUserId = userId)
    }

    // Update
    fun update() {
        val statement =
                connection!!.prepareStatement(
                        "UPDATE ghosts SET nick = ?, avatar_url = ? WHERE qqid = ?"
                )
        statement.setString(1, nick)
        statement.setString(2, avatarUrl)
        statement.setLong(3, qqid)
        statement.executeUpdate()
    }
    // Create
    fun insert() {
        val statement =
                connection!!.prepareStatement("INSERT OR IGNORE INTO ghosts VALUES (?, ?, ?)")
        statement.setLong(1, qqid)
        statement.setString(2, nick)
        statement.setString(3, avatarUrl)
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
                                CREATE TABLE IF NOT EXISTS ghosts (
                                    qqid INTEGET PRIMARY KEY NOT NULL,
                                    nick TEXT NOT NULL,
                                    avatar_url TEXT
                                )
                            """
                    )
        }
        suspend fun get(user: User, matrixApiClient: MatrixApiClient, config: Config): Ghost {
            // Read
            val statement = connection!!.prepareStatement("SELECT * FROM ghosts WHERE qqid = ?")
            statement.setLong(1, user.id)
            val rs = statement.executeQuery()
            if (rs.next())
                    return Ghost(
                            qqid = user.id,
                            nick = rs.getString("nick"),
                            avatarUrl = rs.getString("avatar_url"),
                            matrixApiClient,
                            config
                    )
            // Create
            val ghost = Ghost(user.id, user.nick, avatarUrl = null, matrixApiClient, config)
            ghost.register()
            ghost.setAvatar(user)
            ghost.insert()
            return ghost
        }
        suspend fun get(qqid: Long, matrixApiClient: MatrixApiClient, config: Config): Ghost? {
            Bot.instances.forEach { bot ->
                val user = bot.getFriend(qqid) ?: bot.getStranger(qqid)
                if (user != null) return get(user, matrixApiClient, config)
                bot.groups.forEach { group ->
                    group.members.forEach { member ->
                        if (member.id == qqid) return get(member, matrixApiClient, config)
                    }
                }
            }
            return null
        }
        suspend fun get(userId: UserId, matrixApiClient: MatrixApiClient, config: Config): Ghost? {
            val qqid =
                    userId.localpart.removePrefix(config.appservice.usernamePrefix).toLongOrNull()
                            ?: return null
            return Ghost.get(qqid, matrixApiClient, config)
        }
    }
}

class UserService(override val matrixApiClient: MatrixApiClient, val config: Config) :
        AppserviceUserService {
    override suspend fun userExistingState(
            userId: UserId
    ): AppserviceUserService.UserExistingState {
        Ghost.get(userId, matrixApiClient, config)
                ?: return AppserviceUserService.UserExistingState.DOES_NOT_EXISTS
        return AppserviceUserService.UserExistingState.EXISTS
    }
    // Stub
    override suspend fun getRegisterUserParameter(userId: UserId): RegisterUserParameter {
        return RegisterUserParameter()
    }
    override suspend fun onRegisteredUser(userId: UserId) {}
}
