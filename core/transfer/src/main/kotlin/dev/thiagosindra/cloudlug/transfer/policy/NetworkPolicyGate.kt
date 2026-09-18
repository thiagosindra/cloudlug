package dev.thiagosindra.cloudlug.transfer.policy

import dev.thiagosindra.cloudlug.model.NetworkState
import dev.thiagosindra.cloudlug.model.TransferNetworkPolicy
import dev.thiagosindra.cloudlug.model.TransferStatus
import dev.thiagosindra.cloudlug.model.allows

/** Whether a transfer may use the network it currently has (spec §16). */
enum class NetworkDecision {
    RUN,
    HOLD,
}

/**
 * Applies the transfer's network policy (spec §16, invariant §32.5).
 *
 * The platform layer also expresses the policy as a JobScheduler/WorkManager
 * constraint so the OS enforces it, but the engine never relies on that alone:
 * a transfer that finds itself on a disallowed network holds rather than
 * continuing.
 */
object NetworkPolicyGate {

    fun evaluate(policy: TransferNetworkPolicy, state: NetworkState): NetworkDecision =
        if (policy.allows(state)) NetworkDecision.RUN else NetworkDecision.HOLD

    /**
     * The status a RUNNING transfer should move to for [state], or null when it
     * may keep running.
     *
     * Both "no network at all" and "only a metered network under
     * UNMETERED_ONLY" map to WAITING_FOR_WIFI: §13.1 has one waiting state for
     * connectivity, and both clear the same way — when an allowed network comes
     * back (docs/decisions.md ADR-0010).
     */
    fun holdStatusFor(policy: TransferNetworkPolicy, state: NetworkState): TransferStatus? =
        if (evaluate(policy, state) == NetworkDecision.RUN) null else TransferStatus.WAITING_FOR_WIFI

    /** True when a held transfer may resume (spec §16: it resumes automatically). */
    fun canResume(policy: TransferNetworkPolicy, state: NetworkState): Boolean =
        evaluate(policy, state) == NetworkDecision.RUN
}
