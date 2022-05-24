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
import net.folivo.trixnity.client.api.MatrixApiClient
import net.mamoe.mirai.contact.User

class Ghost(val qqid: Long, var nick: String, var avatarUrl: String?) {
    // On avatar update events
    suspend fun setAvatar(user: User, matrixApiClient: MatrixApiClient, config: Config) {
        val client = HttpClient(CIO) { install(UserAgent) { agent = "QQClient" } }
        val response: HttpResponse = client.get(user.avatarUrl)
        val bytes: ByteReadChannel = response.receive()
        avatarUrl =
                matrixApiClient
                        .media
                        .upload(bytes, response.contentLength()!!, response.contentType()!!)
                        .getOrThrow()
                        .contentUri
        update()
        val userId = config.getGhostId(qqid)
        matrixApiClient.users.setAvatarUrl(userId, avatarUrl, asUserId = userId)
    }

    // On login (friends) and message events (group members)
    suspend fun setAvatarIfNull(user: User, matrixApiClient: MatrixApiClient, config: Config) {
        if (avatarUrl == null) setAvatar(user, matrixApiClient, config)
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
        // Read
        fun get(qqid: Long): Ghost? {
            val statement = connection!!.prepareStatement("SELECT * FROM ghosts WHERE qqid = ?")
            statement.setLong(1, qqid)
            val rs = statement.executeQuery()
            if (rs.next() == false) return null
            return Ghost(
                    qqid = qqid,
                    nick = rs.getString("nick"),
                    avatarUrl = rs.getString("avatar_url"),
            )
        }
    }
}
