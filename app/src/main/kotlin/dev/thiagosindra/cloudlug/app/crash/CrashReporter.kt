package dev.thiagosindra.cloudlug.app.crash

import android.content.Context

/**
 * Captures an uncaught exception so it can be read on the next launch.
 *
 * CloudLug has no crash-reporting SDK and will not acquire one: §27 rules out
 * third-party crash reporters and §25 promises that nothing leaves the device.
 * That left exactly one way to learn why the v0.2 APK died on a phone — ask the
 * person to pull logcat over USB. This is the smallest thing that removes that
 * step while keeping the promise: the trace is written to the app's own
 * `filesDir` and shown on next launch with a Share button, and it travels only
 * if the user taps it.
 *
 * Implemented per build variant. The debug variant records; the release variant
 * is a no-op object with no file access and no handler installed, so none of
 * this reaches a Play build (§29 Data Safety).
 */
interface CrashReporter {

    /** Installs the handler. Call once, from `Application.onCreate`. */
    fun install()

    /**
     * Returns a report written by a previous run and forgets it, so a crash is
     * shown once rather than on every launch until it is dismissed.
     */
    fun consumePendingReport(): String?
}

/** Records nothing. The release variant's [crashReporter]. */
object NoCrashReporter : CrashReporter {
    override fun install() = Unit
    override fun consumePendingReport(): String? = null
}
