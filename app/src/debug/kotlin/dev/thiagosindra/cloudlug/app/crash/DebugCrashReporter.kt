package dev.thiagosindra.cloudlug.app.crash

import android.content.Context
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.time.Instant

fun crashReporter(context: Context): CrashReporter = DebugCrashReporter(context)

/**
 * Writes an uncaught exception to `filesDir/crash/last-crash.txt`.
 *
 * Three things this deliberately does:
 *
 *  - **Chains to the previous handler.** The process must still die the way
 *    Android expects; swallowing the exception would leave a wedged app and
 *    hide the crash from logcat, which is worse than no reporter.
 *  - **Writes synchronously, guarding everything.** The handler runs on a
 *    process that is already failing, so it cannot assume a looper, a
 *    coroutine scope or a healthy heap, and a throw from inside it would
 *    replace the report with its own noise.
 *  - **Stays inside `filesDir`.** Never `cacheDir` (evictable) and never shared
 *    storage. `filesDir/crash/` sits outside the §15.2 chunk cache and is
 *    excluded from backup by the same rules.
 */
internal class DebugCrashReporter(private val context: Context) : CrashReporter {

    private val file: File get() = File(File(context.filesDir, DIRECTORY), FILE_NAME)

    override fun install() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching { write(thread, error) }
            previous?.uncaughtException(thread, error)
        }
    }

    override fun consumePendingReport(): String? = runCatching {
        val pending = file
        if (!pending.isFile) return@runCatching null
        val text = pending.readText()
        pending.delete()
        text.ifBlank { null }
    }.getOrNull()

    private fun write(thread: Thread, error: Throwable) {
        val trace = StringWriter().also { error.printStackTrace(PrintWriter(it)) }.toString()
        val report = buildString {
            appendLine("CloudLug debug crash report")
            appendLine("when:    ${Instant.now()}")
            appendLine("thread:  ${thread.name}")
            appendLine("device:  ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}")
            appendLine("android: API ${android.os.Build.VERSION.SDK_INT} (${android.os.Build.VERSION.RELEASE})")
            appendLine()
            append(trace)
        }
        file.parentFile?.mkdirs()
        file.writeText(report)
    }

    private companion object {
        const val DIRECTORY = "crash"
        const val FILE_NAME = "last-crash.txt"
    }
}
