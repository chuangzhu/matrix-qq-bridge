package land.melty.matrixappserviceqq.config

import kotlinx.serialization.Serializable

@Serializable
data class Config(val homeserver: Homeserver, val appservice: Appservice) {
    @Serializable data class Homeserver(val address: String, val domain: String)
    @Serializable
    data class Appservice(
            val hostname: String = "127.0.0.1",
            val port: Int = 8245,
            val address: String = "http://$hostname:$port",
            val database: String,
            val id: String = "qq",
            val bot_username: String = "qqbridge",
            val username_prefix: String = "_qq_",
            val alias_prefix: String = "_qq_"
    )
}

@Serializable
data class RegistrationConfig(
        val id: String,
        val as_token: String,
        val hs_token: String,
        val url: String,
        val sender_localpart: String,
        val namespaces: Namespaces
) {
    @Serializable
    data class Namespaces(val users: List<Pattern>, val aliases: List<Pattern>) {
        @Serializable data class Pattern(val exclusive: Boolean, val regex: String)
    }
}
