package dev.thiagosindra.cloudlug.transfer.policy

import dev.thiagosindra.cloudlug.model.NetworkState
import dev.thiagosindra.cloudlug.model.TransferNetworkPolicy
import dev.thiagosindra.cloudlug.model.TransferStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NetworkPolicyGateTest {

    @Test
    fun `an unmetered-only transfer never runs on a metered network`() {
        assertEquals(
            NetworkDecision.HOLD,
            NetworkPolicyGate.evaluate(TransferNetworkPolicy.UNMETERED_ONLY, NetworkState.METERED),
        )
        assertEquals(
            TransferStatus.WAITING_FOR_WIFI,
            NetworkPolicyGate.holdStatusFor(TransferNetworkPolicy.UNMETERED_ONLY, NetworkState.METERED),
        )
    }

    @Test
    fun `losing connectivity holds even when any network is allowed`() {
        assertEquals(
            TransferStatus.WAITING_FOR_WIFI,
            NetworkPolicyGate.holdStatusFor(TransferNetworkPolicy.ANY_NETWORK, NetworkState.OFFLINE),
        )
    }

    @Test
    fun `an allowed network does not hold the transfer`() {
        assertNull(NetworkPolicyGate.holdStatusFor(TransferNetworkPolicy.UNMETERED_ONLY, NetworkState.UNMETERED))
        assertNull(NetworkPolicyGate.holdStatusFor(TransferNetworkPolicy.ANY_NETWORK, NetworkState.METERED))
    }

    @Test
    fun `a held transfer resumes only once an allowed network returns`() {
        assertFalse(NetworkPolicyGate.canResume(TransferNetworkPolicy.UNMETERED_ONLY, NetworkState.METERED))
        assertFalse(NetworkPolicyGate.canResume(TransferNetworkPolicy.UNMETERED_ONLY, NetworkState.OFFLINE))
        assertTrue(NetworkPolicyGate.canResume(TransferNetworkPolicy.UNMETERED_ONLY, NetworkState.UNMETERED))
    }
}
