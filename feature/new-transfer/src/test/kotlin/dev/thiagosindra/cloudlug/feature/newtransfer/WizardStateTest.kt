package dev.thiagosindra.cloudlug.feature.newtransfer

import dev.thiagosindra.cloudlug.model.CloudObjectType
import dev.thiagosindra.cloudlug.model.ProviderType
import dev.thiagosindra.cloudlug.provider.CloudObject
import dev.thiagosindra.cloudlug.provider.CloudObjectId
import dev.thiagosindra.cloudlug.provider.SelectionRoot
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Where the §9 browser thinks it is, and what that means for §10.
 *
 * `CloudObject` carries identity, not a path (§6), so the ancestors of a
 * selected object exist nowhere except in the browser that walked to it. If
 * this arithmetic is wrong, a selection of `photos/2025/July` lands at the
 * destination as a bare `July` and §10's promise to reproduce the source's own
 * ancestors is quietly broken — with no error anywhere, because every
 * individual component did what it was asked.
 *
 * The journey test proves the same thing through the real screens, which is
 * the only place the *wiring* can be proven. It needs an emulator. This does
 * not, so it is where a wrong path gets caught first.
 */
class WizardStateTest {

    @Test
    fun `a root selected at the top level lands under its own name`() {
        val state = WizardState().withSelection(obj("photos", CloudObjectType.FOLDER))

        assertEquals(listOf("photos"), state.selectedSources.values.map { it.displayPath.toString() })
    }

    @Test
    fun `a root selected two levels down carries the walk that found it`() {
        val state = WizardState()
            .copy(sourcePath = listOf(obj("photos", CloudObjectType.FOLDER), obj("2025", CloudObjectType.FOLDER)))
            .withSelection(obj("July", CloudObjectType.FOLDER))
            .withSelection(obj("photo1.png", CloudObjectType.FILE))

        // §10: `/photos/2025/July` lands at `photos/2025/July`, not `July`.
        assertEquals(
            listOf("photos/2025/July", "photos/2025/photo1.png"),
            state.selectedSources.values.map { it.displayPath.toString() },
            "the order roots were picked in is the order the review step lists them",
        )
    }

    @Test
    fun `a selection made at one level survives navigating to another`() {
        val photos = obj("photos", CloudObjectType.FOLDER)
        val deep = WizardState()
            .copy(sourcePath = listOf(photos, obj("2025", CloudObjectType.FOLDER)))
            .withSelection(obj("July", CloudObjectType.FOLDER))

        // Walking out of the folder is not unpicking what is in it: a browser
        // that forgot would make a selection spanning two folders impossible.
        val backUp = deep.copy(sourcePath = emptyList(), sourceChildren = emptyList())

        assertEquals(1, backUp.selectedSources.size)
        assertEquals("photos/2025/July", backUp.selectedSources.values.single().displayPath.toString())
        assertTrue(backUp.canContinue, "step 3 continues on what is selected, not on what is on screen")
    }

    @Test
    fun `selecting the same object twice removes it`() {
        val july = obj("July", CloudObjectType.FOLDER)
        val state = WizardState().withSelection(july).withSelection(july)

        assertTrue(state.selectedSources.isEmpty())
        assertFalse(state.canContinue)
    }

    @Test
    fun `the location reads as a path, and the account root is the root`() {
        assertEquals("/", WizardState().sourceLocation)
        assertEquals(
            "/photos/2025",
            WizardState()
                .copy(sourcePath = listOf(obj("photos", CloudObjectType.FOLDER), obj("2025", CloudObjectType.FOLDER)))
                .sourceLocation,
        )
    }

    @Test
    fun `the destination is the level being stood on, the account root included`() {
        val root = CloudObjectId(ProviderType.FAKE, "account-root")
        val atRoot = WizardState(step = WizardStep.PICK_DESTINATION, destinationAccountRoot = root)

        // §10 creates its own enclosing folder inside whatever is chosen, so
        // the account root is a legitimate destination and has to be choosable.
        assertEquals(root, atRoot.destinationHere)

        val myDrive = obj("My Drive", CloudObjectType.FOLDER)
        assertEquals(myDrive.id, atRoot.copy(destinationPath = listOf(myDrive)).destinationHere)
    }

    // ------------------------------------------------------------------ helpers

    /** Mirrors what `toggleSourceSelection` does, without a ViewModel. */
    private fun WizardState.withSelection(obj: CloudObject): WizardState {
        val next = selectedSources.toMutableMap()
        if (next.remove(obj.id.opaqueId) == null) next[obj.id.opaqueId] = SelectionRoot(obj, displayPathOf(obj))
        return copy(selectedSources = next, step = WizardStep.PICK_SOURCE)
    }

    private fun obj(name: String, type: CloudObjectType) = CloudObject(
        id = CloudObjectId(ProviderType.FAKE, "id-$name"),
        name = name,
        type = type,
        parentId = null,
        size = if (type == CloudObjectType.FILE) 1L else null,
        modifiedAt = null,
        providerHash = null,
        revision = null,
        mimeType = null,
    )
}
