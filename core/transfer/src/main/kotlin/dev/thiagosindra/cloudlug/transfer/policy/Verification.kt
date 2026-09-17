package dev.thiagosindra.cloudlug.transfer.policy

import dev.thiagosindra.cloudlug.model.ItemStatusReason
import dev.thiagosindra.cloudlug.model.ProviderHash
import dev.thiagosindra.cloudlug.provider.CloudObject
import dev.thiagosindra.cloudlug.provider.ProviderCapabilities

/** Outcome of destination verification (spec §21). */
sealed interface VerificationResult {

    /** The destination object is proven good; the item may reach COMPLETED. */
    data class Verified(val reason: ItemStatusReason) : VerificationResult

    /** The destination object is provably not what CloudLug uploaded. */
    data class Mismatch(val detail: String) : VerificationResult

    /**
     * Nothing available proves the object good. The item does not complete:
     * `COMPLETED` means verification succeeded (invariant §32.3).
     */
    data class Unverifiable(val detail: String) : VerificationResult
}

/**
 * Destination verification, in the order spec §21 lays down:
 *
 *  1. native hash in the `finishUpload` response equals the locally computed one
 *  2. otherwise `resolveMetadata` on the new object and compare
 *  3. size match plus a provider integrity signal
 *  4. metadata only — permitted *only* when the provider declares
 *     `supportsServerHash = false`, and flagged "verified by size only"
 *
 * Step 3 is the one §21 leaves loose. A provider that declares
 * `supportsServerHash = true` but returns no hash for an object has not given
 * an integrity signal, so this reports [VerificationResult.Unverifiable] rather
 * than accepting a size match (docs/decisions.md ADR-0011): a successful upload
 * response is explicitly not sufficient on its own.
 *
 * A size mismatch is always a [VerificationResult.Mismatch], whatever the
 * hashes say, because it proves the object is wrong.
 */
object DestinationVerifier {

    /**
     * @param uploaded the object returned by `finishUpload`
     * @param expectedNativeHash the destination-native hash computed locally
     *   while streaming (spec §19.4), or null when it could not be computed
     * @param expectedSize bytes CloudLug sent, or null when unknown
     * @param resolveMetadata re-reads the destination object; called only when
     *   the finish response carried no hash (step 2)
     */
    suspend fun verify(
        uploaded: CloudObject,
        expectedNativeHash: ProviderHash?,
        expectedSize: Long?,
        capabilities: ProviderCapabilities,
        resolveMetadata: suspend () -> CloudObject,
    ): VerificationResult {
        if (expectedSize != null && uploaded.size != null && uploaded.size != expectedSize) {
            return VerificationResult.Mismatch(
                "destination reports ${uploaded.size} bytes, uploaded $expectedSize",
            )
        }

        if (expectedNativeHash != null) {
            uploaded.providerHash?.let { reported ->
                return compare(reported, expectedNativeHash)
            }
            // Step 2: the finish response omitted the hash, so ask for it.
            val refreshed = resolveMetadata()
            if (refreshed.size != null && expectedSize != null && refreshed.size != expectedSize) {
                return VerificationResult.Mismatch(
                    "destination reports ${refreshed.size} bytes, uploaded $expectedSize",
                )
            }
            refreshed.providerHash?.let { reported ->
                return compare(reported, expectedNativeHash)
            }
        }

        // Step 4: size-only verification, and only where the provider admits it
        // has no server hash to offer.
        if (!capabilities.supportsServerHash) {
            val confirmedSize = uploaded.size ?: return VerificationResult.Unverifiable(
                "provider returned neither a hash nor a size",
            )
            return if (expectedSize == null || confirmedSize == expectedSize) {
                VerificationResult.Verified(ItemStatusReason.VERIFIED_BY_SIZE_ONLY)
            } else {
                VerificationResult.Mismatch("destination reports $confirmedSize bytes, uploaded $expectedSize")
            }
        }

        return VerificationResult.Unverifiable(
            if (expectedNativeHash == null) {
                "no destination-native hash was computed locally"
            } else {
                "provider declares a server hash but reported none for this object"
            },
        )
    }

    private fun compare(reported: ProviderHash, expected: ProviderHash): VerificationResult = when {
        !reported.comparableTo(expected) -> VerificationResult.Unverifiable(
            "destination reported ${reported.algorithm}, computed ${expected.algorithm}",
        )

        reported.matches(expected) -> VerificationResult.Verified(ItemStatusReason.VERIFIED_BY_DESTINATION_HASH)
        else -> VerificationResult.Mismatch("destination ${reported.algorithm} hash differs from the uploaded bytes")
    }
}
