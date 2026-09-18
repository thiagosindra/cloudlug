package dev.thiagosindra.cloudlug.provider

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StorageQuotaTest {

    @Test
    fun `insufficient available bytes is detected`() {
        assertTrue(StorageQuota(totalBytes = 100, usedBytes = 90, availableBytes = 10).cannotFit(11))
        assertFalse(StorageQuota(totalBytes = 100, usedBytes = 90, availableBytes = 10).cannotFit(10))
    }

    @Test
    fun `available bytes are derived from total and used when not reported`() {
        val quota = StorageQuota(totalBytes = 100, usedBytes = 95, availableBytes = null)
        assertTrue(quota.cannotFit(6))
        assertFalse(quota.cannotFit(5))
    }

    @Test
    fun `unknown quota never blocks a transfer`() {
        val unknown = StorageQuota(totalBytes = null, usedBytes = null, availableBytes = null)
        assertFalse(unknown.cannotFit(Long.MAX_VALUE))
    }
}
