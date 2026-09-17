package dev.thiagosindra.cloudlug.provider.fake

import dev.thiagosindra.cloudlug.model.AccountId
import dev.thiagosindra.cloudlug.provider.CloudObjectId
import dev.thiagosindra.cloudlug.provider.CloudProvider

/**
 * Runs the §31.2 contract against [FakeCloudProvider] with its default,
 * well-behaved capabilities.
 */
class FakeCloudProviderContractTest : ProviderContractTest() {

    override fun newProvider(): CloudProvider = FakeCloudProvider(totalQuotaBytes = 10L * 1024 * 1024 * 1024)

    override fun account(provider: CloudProvider): AccountId = AccountId("fake-account")

    override suspend fun rootFolder(provider: CloudProvider): CloudObjectId =
        (provider as FakeCloudProvider).storage.folder("root")

    override suspend fun seedFolder(
        provider: CloudProvider,
        parent: CloudObjectId,
        name: String,
    ): CloudObjectId = (provider as FakeCloudProvider).storage.folder(name, parent)

    override suspend fun seedFile(
        provider: CloudProvider,
        parent: CloudObjectId,
        name: String,
        content: ByteArray,
    ): CloudObjectId = (provider as FakeCloudProvider).storage.file(name, content, parent)
}

/**
 * The same contract against a provider shaped like the awkward end of the
 * spectrum: case-insensitive names, duplicate siblings allowed, no range
 * download, no resumable upload and no server hash. Every §31.2 property must
 * still hold, or the engine cannot claim to be provider-neutral (spec §32.7).
 */
class AwkwardFakeProviderContractTest : ProviderContractTest() {

    override fun newProvider(): CloudProvider = FakeCloudProvider(
        capabilities = FakeCloudProvider.defaultCapabilities(
            nativeHashAlgorithm = null,
            supportsServerHash = false,
            caseSensitiveNames = false,
            allowsDuplicateSiblingNames = true,
            supportsRangeDownload = false,
            supportsResumableUpload = false,
            uploadChunkAlignment = 1,
        ),
    )

    override fun account(provider: CloudProvider): AccountId = AccountId("fake-account")

    override suspend fun rootFolder(provider: CloudProvider): CloudObjectId =
        (provider as FakeCloudProvider).storage.folder("root")

    override suspend fun seedFolder(
        provider: CloudProvider,
        parent: CloudObjectId,
        name: String,
    ): CloudObjectId = (provider as FakeCloudProvider).storage.folder(name, parent)

    override suspend fun seedFile(
        provider: CloudProvider,
        parent: CloudObjectId,
        name: String,
        content: ByteArray,
    ): CloudObjectId = (provider as FakeCloudProvider).storage.file(name, content, parent)
}
