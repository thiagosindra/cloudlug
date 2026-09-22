package dev.thiagosindra.cloudlug.transfer.pipeline

import dev.thiagosindra.cloudlug.model.NetworkState
import dev.thiagosindra.cloudlug.model.ProviderType
import dev.thiagosindra.cloudlug.provider.CloudProvider
import dev.thiagosindra.cloudlug.storage.StorageSnapshot

/**
 * Resolves a provider adapter by type.
 *
 * The engine holds a registry rather than concrete adapters, which is what lets
 * `:core:transfer` depend on no provider module at all (spec §32.7). The app
 * populates it; tests populate it with the fake.
 */
/**
 * Which providers this build offers the user (spec §9, §24.2 steps 1-2).
 *
 * A named type rather than a bare `Set<ProviderType>`: the wizard needs the
 * distinction between "a provider exists in the registry" and "the user may
 * pick it", and a raw collection injected by type says neither.
 */
data class AvailableProviders(val types: List<ProviderType>) {
    init {
        require(types.isNotEmpty()) { "At least one provider must be available" }
    }

    // destinationsFor(source) lived here until v0.4. It filtered out the
    // source's own provider, which §2.2 no longer prohibits, and nothing
    // called it: the wizard picks accounts now, and §2.2's rule is about the
    // pair of account ids rather than the pair of providers.
}

fun interface ProviderRegistry {
    fun provider(type: ProviderType): CloudProvider
}

/** Current connectivity (spec §16). Implemented on Android by ConnectivityManager. */
fun interface NetworkMonitor {
    fun current(): NetworkState
}

/** Current local storage (spec §15.1). Implemented on Android by StatFs. */
fun interface StorageMonitor {
    fun snapshot(): StorageSnapshot
}
