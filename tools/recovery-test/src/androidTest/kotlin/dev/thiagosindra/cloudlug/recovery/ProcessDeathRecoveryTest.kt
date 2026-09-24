package dev.thiagosindra.cloudlug.recovery

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Spec §31.4, run for real: kill CloudLug in the middle of a file and watch the
 * transfer finish anyway.
 *
 * What it proves is §2.4's claim that nothing may depend on a worker staying
 * alive. The process that was running the transfer is gone, its coroutine with
 * it, and the only thing left is rows in a database — so launching the app
 * again has to be enough, because `MainActivity` reconciles the schedule from
 * those rows and from nothing else.
 *
 * Writing this found two defects that every JVM test had missed, both in the
 * handoff between what death leaves behind and what the engine expects to find.
 * Pause is not death: pause unwinds, running every `finally` on the way out and
 * leaving rows that describe a transfer which stopped tidily. See
 * `core/transfer`'s own `ProcessDeathRecoveryTest` for the two states that
 * matters for.
 */
@RunWith(AndroidJUnit4::class)
class ProcessDeathRecoveryTest {

    private val app = CloudLug()

    /**
     * The mechanism on its own, kept as a separate test because it is the
     * diagnostic for the one below: if both fail, this says whether the kill or
     * the recovery was at fault, which a single test cannot.
     */
    @Test
    fun cloudlug_can_be_force_stopped_by_a_test_that_outlives_it() {
        app.launch()
        app.forceStop()
        // The assertion that matters is that this line runs at all: from inside
        // :app's androidTest, the force-stop above would have ended the run.
        app.launch()
    }

    /** §31.4's first scenario, end to end. */
    @Test
    fun a_transfer_interrupted_by_process_death_resumes_and_completes() {
        app.launch(pacePerChunkMillis = CloudLug.PACE_MS)
        app.startDemoTransfer()
        app.awaitMidFile()

        app.forceStop()

        // Relaunched at full speed: the point is that it resumes, not that
        // resuming is slow too.
        app.launch()
        app.awaitCleanCompletion(CloudLug.COMPLETION_TIMEOUT)
    }
}
