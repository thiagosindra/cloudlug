package dev.thiagosindra.cloudlug.network

import okhttp3.MediaType
import okhttp3.RequestBody
import okio.BufferedSink
import okio.source
import java.io.IOException
import java.io.InputStream

/**
 * A request body that streams exactly [length] bytes from [open], a segment at
 * a time, and holds none of them itself.
 *
 * `ByteArray.toRequestBody` is the alternative, and for an upload it costs two
 * copies of the chunk: the array the caller filled, and then Okio's segments
 * when `RealBufferedSink.write(ByteArray)` copies the whole array into its
 * buffer before emitting any of it. With 8 MiB chunks that is 16 MiB per
 * request, and it is where the v0.6.1 `OutOfMemoryError` was thrown.
 *
 * [open] is called once per attempt, so OkHttp's own retry of a failed
 * connection, and the engine's retry of a failed chunk, both send the same
 * bytes again. Callers must not assume the stream is opened only once, or
 * that the body is buffered anywhere: if [open]'s source changes between
 * attempts, so does what is sent.
 */
class StreamingRequestBody(
    private val length: Long,
    private val contentType: MediaType? = null,
    private val open: () -> InputStream,
) : RequestBody() {

    init {
        require(length >= 0) { "length must not be negative" }
    }

    override fun contentType(): MediaType? = contentType

    override fun contentLength(): Long = length

    override fun writeTo(sink: BufferedSink) {
        open().source().use { source ->
            var remaining = length
            while (remaining > 0) {
                val read = source.read(sink.buffer, remaining)
                if (read == -1L) throw IOException("chunk content ended ${length - remaining} bytes into $length")
                remaining -= read
                // Handed on in slabs, not per 8 KiB read: under HTTP/2 every
                // hand-off takes the stream's and the connection's locks, and
                // per-read hand-offs made a MockWebServer HTTP/2 upload 2.3x
                // slower than the whole-array body. Slabs brought it level. A
                // slab is still a thirty-second of a chunk.
                if (sink.buffer.size >= SLAB) sink.emitCompleteSegments()
            }
            sink.emitCompleteSegments()
        }
    }

    private companion object {
        const val SLAB = 256L * 1024
    }
}
