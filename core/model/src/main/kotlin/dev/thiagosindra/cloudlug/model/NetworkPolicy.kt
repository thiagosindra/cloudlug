package dev.thiagosindra.cloudlug.model

/**
 * Which networks a transfer may use (spec §16).
 *
 * The default is [UNMETERED_ONLY]. A transfer must never silently fall back to a
 * disallowed network (spec §32.5); it holds in
 * [TransferStatus.WAITING_FOR_WIFI] instead.
 */
enum class TransferNetworkPolicy {
    UNMETERED_ONLY,
    ANY_NETWORK,
}

/**
 * What the device is currently connected to.
 *
 * Modelled as metered/unmetered rather than "Wi-Fi", because a metered Wi-Fi
 * hotspot must be treated as cellular (spec §16).
 */
data class NetworkState(
    val connected: Boolean,
    val metered: Boolean,
) {
    companion object {
        val UNMETERED = NetworkState(connected = true, metered = false)
        val METERED = NetworkState(connected = true, metered = true)
        val OFFLINE = NetworkState(connected = false, metered = false)
    }
}

/** True when [state] satisfies this policy. */
fun TransferNetworkPolicy.allows(state: NetworkState): Boolean = when {
    !state.connected -> false
    this == TransferNetworkPolicy.ANY_NETWORK -> true
    else -> !state.metered
}
