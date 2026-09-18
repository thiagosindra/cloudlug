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
