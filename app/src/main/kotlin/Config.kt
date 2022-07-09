package land.melty.matrixqqbridge

import java.util.UUID
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import net.folivo.trixnity.core.model.RoomAliasId
import net.folivo.trixnity.core.model.UserId

@Serializable
data class Config(val homeserver: Homeserver, val appservice: Appservice, val bridge: Bridge) {
    @Serializable data class Homeserver(val address: String, val domain: String)
    @Serializable
    data class Appservice(
            val hostname: String = "127.0.0.1",
            val port: Int = 8245,
            val address: String = "http://$hostname:$port",
            val database: String,
            val id: String = "qq",
            @SerialName("bot_username") val botUsername: String = "qqbridge",
            @SerialName("username_prefix") val usernamePrefix: String = "_qq_",
            @SerialName("alias_prefix") val aliasPrefix: String = "_qq_"
    )
    @Serializable
    data class Bridge(val permissions: Map<String, Permission>) {
        @Serializable
        enum class Permission {
            @SerialName("none") NONE,
            @SerialName("user") USER,
            @SerialName("admin") ADMIN
        }
        fun getPermission(userId: UserId): Permission {
            permissions.forEach {
                if (it.key == userId.toString() || it.key == userId.localpart) return it.value
            }
            return Permission.NONE
        }
    }
    val botUserId = UserId(appservice.botUsername, homeserver.domain)
    fun getGhostId(qqid: Long): UserId {
        val username = "${appservice.usernamePrefix}${qqid}"
        return UserId(username, homeserver.domain)
    }
    fun getGhostQqId(mxid: UserId): Long {
        return mxid.localpart.removePrefix(appservice.usernamePrefix).toLong()
    }
    fun getGhostQqIdOrNull(mxid: UserId): Long? {
        return mxid.localpart.removePrefix(appservice.usernamePrefix).toLongOrNull()
    }
    fun getPortalAliasId(qqid: Long): RoomAliasId {
        val alias = "${appservice.aliasPrefix}${qqid}"
        return RoomAliasId(alias, homeserver.domain)
    }
    fun getPortalQqId(mxid: RoomAliasId): Long {
        return mxid.localpart.removePrefix(appservice.aliasPrefix).toLong()
    }
    fun getPortalQqIdOrNull(mxid: RoomAliasId): Long? {
        return mxid.localpart.removePrefix(appservice.aliasPrefix).toLongOrNull()
    }
    fun isGhost(userId: UserId) =
            userId.domain != this.homeserver.domain &&
                    userId.localpart.startsWith(this.appservice.usernamePrefix)
    fun isPortal(roomAliasId: RoomAliasId) =
            roomAliasId.domain != this.homeserver.domain &&
                    roomAliasId.localpart.startsWith(this.appservice.aliasPrefix)
}

@Serializable
data class RegistrationConfig(
        val id: String,
        @SerialName("as_token") val asToken: String,
        @SerialName("hs_token") val hsToken: String,
        val url: String,
        @SerialName("sender_localpart") val senderLocalpart: String,
        val namespaces: Namespaces
) {
    @Serializable
    data class Namespaces(val users: List<Pattern>, val aliases: List<Pattern>) {
        @Serializable data class Pattern(val exclusive: Boolean, val regex: String)
    }

    companion object {
        fun generate(config: Config) =
                RegistrationConfig(
                        id = config.appservice.id,
                        asToken = UUID.randomUUID().toString(),
                        hsToken = UUID.randomUUID().toString(),
                        url = config.appservice.address,
                        senderLocalpart = config.appservice.botUsername,
                        namespaces =
                                Namespaces(
                                        users =
                                                listOf(
                                                        Namespaces.Pattern(
                                                                exclusive = true,
                                                                regex =
                                                                        "@${config.appservice.usernamePrefix}.*:${config.homeserver.domain}"
                                                        ),
                                                        // The bot itself
                                                        Namespaces.Pattern(
                                                                exclusive = true,
                                                                regex =
                                                                        "@${config.appservice.botUsername}:${config.homeserver.domain}"
                                                        )
                                                ),
                                        aliases =
                                                listOf(
                                                        Namespaces.Pattern(
                                                                exclusive = true,
                                                                regex =
                                                                        "#${config.appservice.aliasPrefix}.*:${config.homeserver.domain}"
                                                        )
                                                )
                                )
                )
    }
}
