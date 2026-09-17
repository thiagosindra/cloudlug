package dev.thiagosindra.cloudlug.model

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NetworkPolicyTest {

    @Test
    fun `unmetered only rejects metered and offline networks`() {
        val policy = TransferNetworkPolicy.UNMETERED_ONLY
        assertTrue(policy.allows(NetworkState.UNMETERED))
        assertFalse(policy.allows(NetworkState.METERED))
        assertFalse(policy.allows(NetworkState.OFFLINE))
    }

    @Test
    fun `any network still requires a connection`() {
        val policy = TransferNetworkPolicy.ANY_NETWORK
        assertTrue(policy.allows(NetworkState.METERED))
        assertTrue(policy.allows(NetworkState.UNMETERED))
        assertFalse(policy.allows(NetworkState.OFFLINE))
    }
}
