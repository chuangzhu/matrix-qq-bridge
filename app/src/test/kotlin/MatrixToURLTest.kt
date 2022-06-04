package land.melty.matrixappserviceqq

import kotlin.test.Test
import kotlin.test.assertEquals
import net.folivo.trixnity.core.model.EventId
import net.folivo.trixnity.core.model.RoomAliasId

class MatrixToURLTest {
    @Test
    fun testEncodeRoomAliasId() {
        val url = MatrixToURL(RoomAliasId("somewhere", "example.org"))
        val expected = "https://matrix.to/#/%23somewhere%3Aexample.org"
        assertEquals(url.toString(), expected)
    }

    @Test
    fun testEncodeEventId() {
        val url =
                MatrixToURL(
                        RoomAliasId("somewhere", "example.org"),
                        EventId("${'$'}nTapjJQnJUCUC3DwV-Rt2Uux8ZoctuhugRsGIhvT14s")
                )
        val expected =
                "https://matrix.to/#/%23somewhere%3Aexample.org/%24nTapjJQnJUCUC3DwV-Rt2Uux8ZoctuhugRsGIhvT14s"
        assertEquals(url.toString(), expected)
    }

    @Test
    fun testDecodeRoomAliasId() {
        val url = MatrixToURL("https://matrix.to/#/%23somewhere%3Aexample.org")
        val expected = RoomAliasId("somewhere", "example.org")
        assertEquals(url.id, expected)
    }

    @Test
    fun testDecodeEventId() {
        val url =
                MatrixToURL(
                        "https://matrix.to/#/%23somewhere%3Aexample.org/%24nTapjJQnJUCUC3DwV-Rt2Uux8ZoctuhugRsGIhvT14s"
                )
        val roomAliasId = RoomAliasId("somewhere", "example.org")
        assertEquals(url.id, roomAliasId, "room alias should be the same")
        val eventId = EventId("${'$'}nTapjJQnJUCUC3DwV-Rt2Uux8ZoctuhugRsGIhvT14s")
        assertEquals(url.extraParameter, eventId, "event id should be the same")
    }
}
