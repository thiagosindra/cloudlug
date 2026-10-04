package dev.thiagosindra.cloudlug.network

import okio.Buffer
import java.io.ByteArrayInputStream
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class StreamingRequestBodyTest {

    private val content = ByteArray(1_000_000) { (it % 251).toByte() }

    @Test
    fun `it sends exactly length bytes, even from a longer source`() {
        val body = StreamingRequestBody(600_000) { ByteArrayInputStream(content) }
        val sent = Buffer().also(body::writeTo)

        assertEquals(600_000, body.contentLength())
        assertContentEquals(content.copyOf(600_000), sent.readByteArray())
    }

    @Test
    fun `every attempt opens the source again and sends the same bytes`() {
        var opened = 0
        val body = StreamingRequestBody(content.size.toLong()) { opened++; ByteArrayInputStream(content) }

        val first = Buffer().also(body::writeTo).readByteArray()
        val second = Buffer().also(body::writeTo).readByteArray()

        assertEquals(2, opened)
        assertContentEquals(first, second)
    }

    @Test
    fun `a source shorter than length is an error, not a short upload`() {
        // A truncated chunk file sent as if whole would be acknowledged short
        // and then verified against the wrong hash; failing here says why.
        val body = StreamingRequestBody(content.size + 1L) { ByteArrayInputStream(content) }

        assertFailsWith<IOException> { body.writeTo(Buffer()) }
    }
}
