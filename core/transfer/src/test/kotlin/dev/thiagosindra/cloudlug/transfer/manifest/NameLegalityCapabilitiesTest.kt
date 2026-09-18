package dev.thiagosindra.cloudlug.transfer.manifest

import dev.thiagosindra.cloudlug.model.CloudPath
import dev.thiagosindra.cloudlug.model.ItemStatusReason
import dev.thiagosindra.cloudlug.provider.fake.FakeCloudProvider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Spec §5 and §20.4, added in v1.2: `disallowsTrailingSpaceOrDot` and
 * `maxPathLength`. ADR-0013 deferred both for want of a capability flag.
 */
class NameLegalityCapabilitiesTest {

    private val permissive = FakeCloudProvider.defaultCapabilities()

    private val windowsLike = FakeCloudProvider.defaultCapabilities(
        maxNameLength = 32,
        maxPathLength = 40,
        disallowsTrailingSpaceOrDot = true,
    )

    @Test
    fun `a provider that allows trailing dots and spaces still accepts them`() {
        assertNull(DestinationNameLegality.check("report.", permissive))
        assertNull(DestinationNameLegality.check("report ", permissive))
    }

    @Test
    fun `a trailing dot or space is a conflict where the provider forbids it`() {
        assertEquals(
            ItemStatusReason.CONFLICT_ILLEGAL_NAME,
            DestinationNameLegality.check("report.", windowsLike),
        )
        assertEquals(
            ItemStatusReason.CONFLICT_ILLEGAL_NAME,
            DestinationNameLegality.check("report ", windowsLike),
        )
        // An interior dot is ordinary, and an extension is not a trailing dot.
        assertNull(DestinationNameLegality.check("report.txt", windowsLike))
    }

    @Test
    fun `path length is checked against the whole path, not the name`() {
        // Every individual name here is legal; only the assembled path is not.
        val deep = CloudPath.parse("photos/2026/April/holiday/beach/sunset.png")
        assertEquals(
            ItemStatusReason.CONFLICT_ILLEGAL_NAME,
            DestinationNameLegality.checkPath(deep, windowsLike),
        )
        assertNull(DestinationNameLegality.check(deep.name.orEmpty(), windowsLike))
    }

    @Test
    fun `a short path passes and an absent limit never fails`() {
        assertNull(DestinationNameLegality.checkPath(CloudPath.parse("a/b.png"), windowsLike))
        assertNull(
            DestinationNameLegality.checkPath(
                CloudPath.parse("photos/2026/April/holiday/beach/sunset.png"),
                permissive,
            ),
        )
    }
}
