package dev.thiagosindra.cloudlug.app.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dev.thiagosindra.cloudlug.model.HashAlgorithm
import dev.thiagosindra.cloudlug.model.ProviderType
import dev.thiagosindra.cloudlug.provider.fake.FakeCloudProvider
import dev.thiagosindra.cloudlug.provider.fake.FakeCloudStorage
import dev.thiagosindra.cloudlug.transfer.pipeline.AvailableProviders
import dev.thiagosindra.cloudlug.transfer.pipeline.ProviderRegistry
import javax.inject.Named
import javax.inject.Singleton
import kotlin.random.Random

/**
 * Two [FakeCloudProvider]s standing in for Dropbox and Google Drive (§33 v0.2).
 *
 * The point of running the shell against the fake first is that it exercises the
 * engine's entire public surface — enumeration, manifest review, chunked
 * transfer, verification, pause, resume, cancel — with no OAuth, no network and
 * no account, so anything the UI reveals about those interfaces is found before
 * a real adapter is written (spec §31.3). v0.3 replaces the source binding with
 * `:providers:dropbox` and nothing above this module changes.
 *
 * Each is configured to behave like the provider it stands in for, so the
 * capability-driven paths of §19.3 and §20.3 are genuinely exercised: the
 * Dropbox stand-in uses the block hash, folds case and forbids duplicate
 * siblings; the Drive stand-in uses SHA-256, is case-sensitive and allows them.
 */
@Module
@InstallIn(SingletonComponent::class)
object DemoProvidersModule {

    @Provides
    @Singleton
    @Named("source")
    fun sourceProvider(): FakeCloudProvider {
        val capabilities = FakeCloudProvider.defaultCapabilities(
            nativeHashAlgorithm = HashAlgorithm.DROPBOX_CONTENT_HASH,
            caseSensitiveNames = false,
            allowsDuplicateSiblingNames = false,
            uploadChunkAlignment = 4L * 1024 * 1024,
            disallowsTrailingSpaceOrDot = true,
        )
        val storage = FakeCloudStorage(ProviderType.DROPBOX, capabilities.nativeHashAlgorithm)
        seedDemoTree(storage)
        return FakeCloudProvider(
            type = ProviderType.DROPBOX,
            capabilities = capabilities,
            storage = storage,
        )
    }

    @Provides
    @Singleton
    @Named("destination")
    fun destinationProvider(): FakeCloudProvider {
        val capabilities = FakeCloudProvider.defaultCapabilities(
            nativeHashAlgorithm = HashAlgorithm.SHA256,
            caseSensitiveNames = true,
            allowsDuplicateSiblingNames = true,
            uploadChunkAlignment = 256L * 1024,
        )
        val storage = FakeCloudStorage(ProviderType.GOOGLE_DRIVE, capabilities.nativeHashAlgorithm)
        // Somewhere for the user to pick in wizard step 4.
        storage.folder("My Drive")
        storage.folder("Backups")
        return FakeCloudProvider(
            type = ProviderType.GOOGLE_DRIVE,
            capabilities = capabilities,
            storage = storage,
        )
    }

    @Provides
    @Singleton
    fun providerRegistry(
        @Named("source") source: FakeCloudProvider,
        @Named("destination") destination: FakeCloudProvider,
    ): ProviderRegistry = ProviderRegistry { type ->
        when (type) {
            ProviderType.DROPBOX -> source
            ProviderType.GOOGLE_DRIVE -> destination
            ProviderType.FAKE -> source
        }
    }

    @Provides
    @Singleton
    fun availableProviders() =
        AvailableProviders(listOf(ProviderType.DROPBOX, ProviderType.GOOGLE_DRIVE))

    /**
     * A tree with the awkward cases in it, so the review step has something to
     * report: a native document with no size (§20.1), a shortcut (§20.2), an
     * empty folder (§20.5) and a file large enough to need several chunks.
     */
    private fun seedDemoTree(storage: FakeCloudStorage) {
        val random = Random(20260918)

        val photos = storage.folder("photos")
        val y2025 = storage.folder("2025", photos)
        storage.file("photo1.png", random.nextBytes(64 * 1024), y2025)
        storage.file("photo2.png", random.nextBytes(96 * 1024), y2025)
        val july = storage.folder("July", y2025)
        storage.file("summer.jpg", random.nextBytes(9 * 1024 * 1024), july)
        storage.file("beach.jpg", random.nextBytes(2 * 1024 * 1024), july)

        val y2026 = storage.folder("2026", photos)
        val april = storage.folder("April", y2026)
        repeat(3) { storage.file("${it + 1}.png", random.nextBytes(32 * 1024), april) }

        val docs = storage.folder("docs")
        storage.file("abc.txt", "hello from CloudLug".toByteArray(), docs)
        // No byte stream, no size until export (spec §20.1).
        storage.nativeDocument("Quarterly report", docs)
        // Not followed (spec §20.2).
        storage.shortcut("link to abc.txt", docs)

        // Preserved at the destination even though it holds nothing (spec §20.5).
        storage.folder("empty archive", docs)

        val projects = storage.folder("projects")
        storage.file("code.py", "print('cloudlug')\n".toByteArray(), projects)
    }
}

