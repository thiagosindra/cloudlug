package dev.thiagosindra.cloudlug.provider.fake

import dev.thiagosindra.cloudlug.model.AccountId
import dev.thiagosindra.cloudlug.provider.Chunk
import dev.thiagosindra.cloudlug.provider.CloudErrorKind
import dev.thiagosindra.cloudlug.provider.CloudException
import dev.thiagosindra.cloudlug.provider.UploadRequest
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Each failure spec §31.3 lists, asserted to behave the way the engine's tests
 * rely on: the right [CloudErrorKind], and one-shot injections that stop firing
 * so recovery can be observed rather than only failure.
 */
class FailureInjectionTest {

    private val account = AccountId("fake-account")

    private fun provider() = FakeCloudProvider(totalQuotaBytes = 1_000_000)

    private fun read(provider: FakeCloudProvider, id: dev.thiagosindra.cloudlug.provider.CloudObjectId) =
        provider.storage.contentOf(id.opaqueId)

    @Test
    fun `a disconnect stops the stream partway through`() = runTest {
        val provider = provider()
        val content = ByteArray(1_000) { it.toByte() }
        val id = provider.storage.file("data.bin", content)
        provider.inject(
            FailureInjection(
                FailureInjection.Fault.DISCONNECT_AFTER_BYTES,
                FailureInjection.Operation.READ,
                afterBytes = 400,
            ),
        )

        val download = provider.openDownload(account, id)
        val buffer = ByteArray(1_000)
        val first = download.read(buffer, 0, buffer.size)

        assertEquals(400, first, "the stream delivers what arrived before the reset")
        assertFailsWith<CloudException> { download.read(buffer, 0, buffer.size) }
    }

    @Test
    fun `429 and Drive's 403 rate-limit reason both surface as throttling`() = runTest {
        listOf(
            FailureInjection.Fault.TOO_MANY_REQUESTS to "429",
            FailureInjection.Fault.RATE_LIMIT_403 to "userRateLimitExceeded",
        ).forEach { (fault, code) ->
            val provider = provider()
            provider.inject(FailureInjection(fault, FailureInjection.Operation.QUOTA))

            val error = assertFailsWith<CloudException> { provider.quota(account) }
            assertEquals(CloudErrorKind.THROTTLED, error.kind, "spec §23 treats $code as throttling")
            assertEquals(code, error.code)
        }
    }

    @Test
    fun `a 500 is a transient network error`() = runTest {
        val provider = provider()
        provider.inject(FailureInjection(FailureInjection.Fault.SERVER_ERROR, FailureInjection.Operation.QUOTA))

        assertEquals(CloudErrorKind.TRANSIENT_NETWORK, assertFailsWith<CloudException> { provider.quota(account) }.kind)
    }

    @Test
    fun `an expired token never looks retryable`() = runTest {
        val provider = provider()
        provider.inject(
            FailureInjection(FailureInjection.Fault.TOKEN_EXPIRED, FailureInjection.Operation.RESOLVE_METADATA),
        )
        val id = provider.storage.file("data.bin", byteArrayOf(1))

        val error = assertFailsWith<CloudException> { provider.resolveMetadata(account, id) }
        assertEquals(CloudErrorKind.AUTH_REQUIRED, error.kind)
        assertFalse(error.kind.isRetryable)
    }

    @Test
    fun `a full destination is not retried automatically`() = runTest {
        val provider = provider()
        provider.inject(
            FailureInjection(FailureInjection.Fault.DESTINATION_FULL, FailureInjection.Operation.UPLOAD_CHUNK),
        )
        val session = provider.beginUpload(
            account,
            UploadRequest(account, provider.storage.folder("root"), "x.bin", 8, null),
        )

        val error = assertFailsWith<CloudException> {
            provider.uploadChunk(session, Chunk(0, ByteArray(8), isFinal = true))
        }
        assertEquals(CloudErrorKind.DESTINATION_STORAGE_FULL, error.kind)
        assertFalse(error.kind.isRetryable)
    }

    @Test
    fun `an expired upload session is distinguishable from other failures`() = runTest {
        val provider = provider()
        provider.inject(
            FailureInjection(FailureInjection.Fault.UPLOAD_SESSION_EXPIRED, FailureInjection.Operation.UPLOAD_CHUNK),
        )
        val session = provider.beginUpload(
            account,
            UploadRequest(account, provider.storage.folder("root"), "x.bin", 8, null),
        )

        assertEquals(
            CloudErrorKind.UPLOAD_SESSION_EXPIRED,
            assertFailsWith<CloudException> {
                provider.uploadChunk(session, Chunk(0, ByteArray(8), isFinal = true))
            }.kind,
        )
    }

    @Test
    fun `a corrupt chunk changes the bytes that arrive`() = runTest {
        val provider = provider()
        val root = provider.storage.folder("root")
        provider.inject(
            FailureInjection(FailureInjection.Fault.CORRUPT_CHUNK, FailureInjection.Operation.UPLOAD_CHUNK),
        )
        val content = "trustworthy".toByteArray()

        val session = provider.beginUpload(account, UploadRequest(account, root, "x.bin", content.size.toLong(), null))
        provider.uploadChunk(session, Chunk(0, content, isFinal = true))
        val stored = provider.finishUpload(session)

        val arrived = read(provider, stored.id)
        assertFalse(content.contentEquals(arrived), "a corrupted chunk must not match the source bytes")
        assertEquals(content.size, arrived?.size, "corruption alters content, not length")
    }

    @Test
    fun `a one-shot injection stops firing so recovery can be observed`() = runTest {
        val provider = provider()
        provider.inject(
            FailureInjection(FailureInjection.Fault.SERVER_ERROR, FailureInjection.Operation.QUOTA, times = 2),
        )

        assertFailsWith<CloudException> { provider.quota(account) }
        assertFailsWith<CloudException> { provider.quota(account) }
        assertEquals(1_000_000, provider.quota(account)?.totalBytes)
    }

    @Test
    fun `a permanent injection keeps failing`() = runTest {
        val provider = provider()
        provider.inject(
            FailureInjection(
                FailureInjection.Fault.NOT_FOUND,
                FailureInjection.Operation.RESOLVE_METADATA,
                times = Int.MAX_VALUE,
            ),
        )
        val id = provider.storage.file("data.bin", byteArrayOf(1))

        repeat(5) {
            assertEquals(
                CloudErrorKind.NOT_FOUND,
                assertFailsWith<CloudException> { provider.resolveMetadata(account, id) }.kind,
            )
        }
    }

    @Test
    fun `an interrupted process refuses every later call until it is restarted`() = runTest {
        val provider = provider()
        val id = provider.storage.file("data.bin", byteArrayOf(1))
        provider.interruptProcess()

        assertFailsWith<CloudException> { provider.resolveMetadata(account, id) }
        assertFailsWith<CloudException> { provider.quota(account) }

        provider.clearInjections()
        assertEquals(1L, provider.resolveMetadata(account, id).size)
    }

    @Test
    fun `an unsupported object refuses to open a byte stream`() = runTest {
        val provider = provider()
        val doc = provider.storage.nativeDocument("Plan")
        val shortcut = provider.storage.shortcut("Link")

        listOf(doc, shortcut).forEach { id ->
            assertEquals(
                CloudErrorKind.UNSUPPORTED,
                assertFailsWith<CloudException> { provider.openDownload(account, id) }.kind,
            )
        }
    }

    @Test
    fun `a provider-native document reports no size and its export formats`() = runTest {
        val provider = provider()
        val id = provider.storage.nativeDocument("Plan", exportFormats = listOf("application/pdf"))

        val obj = provider.resolveMetadata(account, id)
        assertEquals(null, obj.size, "spec §20.1: native documents have no bytes")
        assertEquals(listOf("application/pdf"), obj.exportFormats)
    }

    @Test
    fun `a source edited mid-transfer reports a new revision`() = runTest {
        val provider = provider()
        val id = provider.storage.file("data.bin", byteArrayOf(1), revision = "rev-1")

        provider.storage.mutate(id.opaqueId, byteArrayOf(1, 2, 3), revision = "rev-2")

        val obj = provider.resolveMetadata(account, id)
        assertEquals("rev-2", obj.revision)
        assertContentEquals(byteArrayOf(1, 2, 3), provider.storage.contentOf(id.opaqueId))
    }

    @Test
    fun `an upload that was never finished leaves a session open`() = runTest {
        val provider = provider()
        val session = provider.beginUpload(
            account,
            UploadRequest(account, provider.storage.folder("root"), "x.bin", 8, null),
        )
        assertTrue(provider.hasOpenSessions())

        provider.abortUpload(session)
        assertFalse(provider.hasOpenSessions())
    }
}
