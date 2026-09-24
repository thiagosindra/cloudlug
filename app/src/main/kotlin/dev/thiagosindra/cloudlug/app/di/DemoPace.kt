package dev.thiagosindra.cloudlug.app.di

import dev.thiagosindra.cloudlug.provider.fake.FakeCloudProvider
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton
import kotlin.time.Duration

/**
 * Makes the demo providers slow, on request, in debug builds (spec §31.4).
 *
 * §31.4's process-death scenarios have to kill CloudLug **mid-file**, and the
 * harness that does the killing runs in a different process entirely: it drives
 * the app through the UI and has no way to reach into the Hilt graph. At full
 * speed the whole demo tree moves in about a second, so a force-stop arriving
 * after the harness has seen a file in flight would land on a transfer that had
 * already finished — the test would pass while proving nothing.
 *
 * So [MainActivity][dev.thiagosindra.cloudlug.app.MainActivity] reads a
 * per-chunk delay off its launch intent and sets it here. Debug builds only,
 * zero by default, and no caller inside the app ever asks: with the extra
 * absent — which is every launch but the harness's — the providers behave
 * exactly as they did before.
 *
 * Both sides are slowed, because only one of them being slow moves the pause
 * point rather than widening it: with an instant destination there is nothing
 * cached to interrupt (see `MidFileInterruptionTest`).
 */
@Singleton
class DemoPace @Inject constructor(
    @param:Named("demo") private val source: FakeCloudProvider,
    @param:Named("destination") private val destination: FakeCloudProvider,
) {

    /** Time spent on each source read and each destination chunk. */
    fun set(perChunk: Duration) {
        source.readDelay = perChunk
        destination.uploadChunkDelay = perChunk
    }
}
