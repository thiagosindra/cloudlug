package dev.thiagosindra.cloudlug.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CloudPathTest {

    @Test
    fun `parse ignores leading trailing and repeated separators`() {
        assertEquals(listOf("photos", "2026", "april"), CloudPath.parse("/photos//2026/april/").segments)
    }

    @Test
    fun `root has no name and no parent`() {
        assertTrue(CloudPath.ROOT.isRoot)
        assertNull(CloudPath.ROOT.name)
        assertNull(CloudPath.ROOT.parent)
    }

    @Test
    fun `child and parent are inverse`() {
        val path = CloudPath.of("photos", "2026")
        assertEquals(path, path.child("april").parent)
    }

    @Test
    fun `resolve concatenates relative paths`() {
        assertEquals(
            CloudPath.parse("photos/2026/april/1.png"),
            CloudPath.of("photos", "2026").resolve(CloudPath.parse("april/1.png")),
        )
    }

    @Test
    fun `traversal segments are rejected rather than normalised`() {
        assertFailsWith<IllegalArgumentException> { CloudPath.parse("photos/../../etc/passwd") }
        assertFailsWith<IllegalArgumentException> { CloudPath.parse("photos/./2026") }
        assertFailsWith<IllegalArgumentException> { CloudPath.ROOT.child("..") }
    }

    @Test
    fun `case folded key detects siblings that collide on a case-insensitive destination`() {
        assertEquals(
            CloudPath.parse("Photos/Summer.JPG").caseFoldedKey(),
            CloudPath.parse("photos/summer.jpg").caseFoldedKey(),
        )
    }

    @Test
    fun `equality is by segments`() {
        assertEquals(CloudPath.parse("a/b"), CloudPath.of("a", "b"))
        assertEquals(CloudPath.parse("a/b").hashCode(), CloudPath.of("a", "b").hashCode())
    }
}
