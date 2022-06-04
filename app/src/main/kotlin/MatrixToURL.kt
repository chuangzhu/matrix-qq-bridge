package land.melty.matrixappserviceqq

import java.net.URL
import java.net.URLDecoder
import java.net.URLEncoder
import net.folivo.trixnity.core.model.EventId
import net.folivo.trixnity.core.model.MatrixId

/** @see https://spec.matrix.org/v1.2/appendices/#matrixto-navigation */
class MatrixToURL {
    val id: MatrixId
    val extraParameter: EventId?

    constructor(id: MatrixId, extraParameter: EventId? = null) {
        this.id = id
        this.extraParameter = extraParameter
    }

    constructor(url: URL) {
        val list = URLDecoder.decode(url.ref, "utf8").split('/')
        this.id = MatrixId.of(list[1])
        this.extraParameter = list.getOrNull(2)?.let { EventId(it) }
    }

    constructor(url: String) : this(URL(url))

    fun toURL() =
            URL(
                    "https",
                    "matrix.to",
                    "/#/" +
                            URLEncoder.encode(id.full, "utf8") +
                            if (extraParameter != null)
                                    "/" + URLEncoder.encode(extraParameter.full, "utf8")
                            else ""
            )
    override fun toString() = toURL().toString()

    companion object {
        fun isMatrixToURL(url: URL) = url.protocol == "https" && url.authority == "matrix.to"
        fun isMatrixToURL(url: String) = isMatrixToURL(URL(url))
        fun fromStringOrNull(url: String) = if (isMatrixToURL(url)) MatrixToURL(url) else null
    }
}
