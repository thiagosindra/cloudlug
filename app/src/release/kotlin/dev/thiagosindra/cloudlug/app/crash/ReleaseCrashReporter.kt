package dev.thiagosindra.cloudlug.app.crash

import android.content.Context

/**
 * Release builds record nothing.
 *
 * Declared per variant rather than behind a `BuildConfig.DEBUG` branch so the
 * recording code is not merely skipped but absent: there is no file write and
 * no handler to reason about when someone audits a Play build against §25 and
 * §29.
 */
fun crashReporter(context: Context): CrashReporter = NoCrashReporter
