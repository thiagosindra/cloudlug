package dev.thiagosindra.cloudlug.network

import okhttp3.Interceptor
import okhttp3.Response

/** Where a redacted line goes. Android supplies Logcat; tests collect. */
fun interface NetworkLogSink {
    fun log(line: String)

    companion object {
        /** §26: no automatic remote logging, and nothing at all unless asked for. */
        val None = NetworkLogSink { }
    }
}

/**
 * The single interceptor §26 asks for.
 *
 * It logs the request line, the status and the timing — never a body, never an
 * `Authorization` header, never a token in a query string. Bodies are excluded
 * structurally rather than by a flag someone can flip: this class has no branch
 * that would print one, so the only way to log a response body is to write new
 * code and be seen doing it in review.
 */
class RedactingLogInterceptor(
    private val sink: NetworkLogSink = NetworkLogSink.None,
    private val clock: () -> Long = System::nanoTime,
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val started = clock()
        sink.log("--> ${request.method} ${Redaction.url(request.url.toString())}${headers(request.headers)}")

        val response = try {
            chain.proceed(request)
        } catch (failure: Exception) {
            // The message can carry a host and a reason; it cannot carry a body.
            sink.log("<-- ${request.method} ${Redaction.url(request.url.toString())} failed: ${failure.javaClass.simpleName}")
            throw failure
        }

        val elapsedMillis = (clock() - started) / 1_000_000
        sink.log(
            "<-- ${response.code} ${request.method} " +
                "${Redaction.url(request.url.toString())} (${elapsedMillis}ms)${headers(response.headers)}",
        )
        return response
    }

    private fun headers(headers: okhttp3.Headers): String {
        if (headers.size == 0) return ""
        return headers.names().joinToString(prefix = " [", postfix = "]") { name ->
            "$name=${Redaction.header(name, headers[name].orEmpty())}"
        }
    }
}
