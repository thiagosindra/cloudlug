package dev.thiagosindra.cloudlug.transfer.policy

import dev.thiagosindra.cloudlug.model.TransferStatus
import dev.thiagosindra.cloudlug.provider.CloudErrorKind
import dev.thiagosindra.cloudlug.provider.CloudException
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class RetryPolicyTest {

    private val policy = RetryPolicy()

    private fun error(kind: CloudErrorKind, retryAfter: kotlin.time.Duration? = null) =
        CloudException(kind, kind.name, retryAfter)

    @Test
    fun `throttling and transient network errors are retried`() {
        listOf(CloudErrorKind.THROTTLED, CloudErrorKind.TRANSIENT_NETWORK).forEach { kind ->
            assertIs<RetryDecision.Retry>(policy.decide(error(kind), attempt = 0), "$kind must be retried")
        }
    }

    @Test
    fun `authentication failures hold the transfer instead of looping`() {
        val decision = policy.decide(error(CloudErrorKind.AUTH_REQUIRED), attempt = 0)
        assertEquals(TransferStatus.AUTH_REQUIRED, assertIs<RetryDecision.Hold>(decision).status)
    }

    @Test
    fun `a full destination holds for storage rather than retrying`() {
        val decision = policy.decide(error(CloudErrorKind.DESTINATION_STORAGE_FULL), attempt = 0)
        assertEquals(TransferStatus.WAITING_FOR_STORAGE, assertIs<RetryDecision.Hold>(decision).status)
    }

    @Test
    fun `an expired upload session restarts the upload from cached chunks`() {
        assertEquals(
            RetryDecision.RestartUpload,
            policy.decide(error(CloudErrorKind.UPLOAD_SESSION_EXPIRED), attempt = 0),
        )
    }

    @Test
    fun `permanent errors fail the item immediately`() {
        listOf(CloudErrorKind.PERMANENT, CloudErrorKind.NOT_FOUND, CloudErrorKind.UNSUPPORTED).forEach { kind ->
            assertEquals(RetryDecision.Fail, policy.decide(error(kind), attempt = 0), "$kind")
        }
    }

    @Test
    fun `the retry budget is bounded so a transfer can still finish`() {
        assertIs<RetryDecision.Retry>(policy.decide(error(CloudErrorKind.THROTTLED), attempt = 3))
        assertEquals(RetryDecision.Fail, policy.decide(error(CloudErrorKind.THROTTLED), attempt = 4))
        assertEquals(RetryDecision.Fail, policy.decide(error(CloudErrorKind.THROTTLED), attempt = 99))
    }

    @Test
    fun `backoff grows exponentially and stays inside the jitter window`() {
        val random = Random(seed = 7)
        var previousFloor = kotlin.time.Duration.ZERO
        repeat(5) { attempt ->
            val delay = policy.delayFor(attempt, random = random)
            val expectedCap = minOf(policy.baseDelay * Math.pow(2.0, attempt.toDouble()), policy.maximumDelay)
            assertTrue(delay <= expectedCap, "attempt $attempt delay $delay exceeded cap $expectedCap")
            assertTrue(delay >= expectedCap / 2, "attempt $attempt delay $delay below the jitter floor")
            assertTrue(delay >= previousFloor || attempt == 0, "backoff should not shrink between attempts")
            previousFloor = expectedCap / 2
        }
    }

    @Test
    fun `backoff is capped at a few minutes`() {
        assertTrue(policy.delayFor(attempt = 30) <= 5.minutes)
    }

    @Test
    fun `a provider Retry-After wins over computed backoff`() {
        val decision = policy.decide(error(CloudErrorKind.THROTTLED, retryAfter = 42.seconds), attempt = 0)
        assertEquals(42.seconds, assertIs<RetryDecision.Retry>(decision).delay)
    }

    @Test
    fun `a Retry-After beyond the cap is clamped`() {
        val decision = policy.decide(error(CloudErrorKind.THROTTLED, retryAfter = 2.minutes * 60), attempt = 0)
        assertEquals(5.minutes, assertIs<RetryDecision.Retry>(decision).delay)
    }

    @Test
    fun `jitter actually varies between attempts`() {
        val delays = (1..20).map { policy.delayFor(attempt = 4, random = Random(it)) }.toSet()
        assertTrue(delays.size > 1, "full-jitter backoff must not be deterministic")
    }
}
