package dev.thiagosindra.cloudlug.provider.fake

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The two ways of naming the root have to agree.
 *
 * `:app`'s demo tree seeds with no parent, while the wizard's picker asks for
 * [FakeCloudProvider.rootOf]. In v0.2 those were different places, so the app
 * shipped with a picker that was empty on a real device even though the
 * provider held a full tree and every test passed (ADR-0026).
 */
class FakeRootConventionTest {

    @Test
    fun `objects seeded with no parent are listed under rootOf`() = runTest {
        val provider = FakeCloudProvider()
        provider.storage.folder("photos")
        provider.storage.folder("docs")
        provider.storage.file("notes.txt", "hello".toByteArray())
        val account = provider.authenticate().id

        val names = provider.listChildren(account, provider.rootOf(account)).toList().map { it.name }

        assertEquals(listOf("photos", "docs", "notes.txt"), names)
    }

    @Test
    fun `seeding under rootOf and seeding with no parent reach the same place`() = runTest {
        val provider = FakeCloudProvider()
        val account = provider.authenticate().id
        provider.storage.folder("seeded with null")
        provider.storage.folder("seeded under rootOf", provider.rootOf(account))

        val names = provider.listChildren(account, provider.rootOf(account)).toList().map { it.name }

        assertEquals(listOf("seeded with null", "seeded under rootOf"), names)
    }

    @Test
    fun `children of a nested folder are unaffected by the root convention`() = runTest {
        val provider = FakeCloudProvider()
        val account = provider.authenticate().id
        val photos = provider.storage.folder("photos")
        provider.storage.file("photo1.png", ByteArray(8), photos)

        val top = provider.listChildren(account, provider.rootOf(account)).toList().map { it.name }
        val inside = provider.listChildren(account, photos).toList().map { it.name }

        assertEquals(listOf("photos"), top)
        assertEquals(listOf("photo1.png"), inside)
    }
}
