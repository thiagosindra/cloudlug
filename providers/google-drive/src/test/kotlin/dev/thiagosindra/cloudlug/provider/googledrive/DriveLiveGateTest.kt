package dev.thiagosindra.cloudlug.provider.googledrive

import kotlin.test.Test
import kotlin.test.assertFailsWith

/** The live gate's refusals, which run everywhere because they need no account. */
class DriveLiveGateTest {

    @Test
    fun `the whole of My Drive is never a test root`() {
        assertFailsWith<IllegalStateException> { DriveLive.requireSafeRoot("root") }
        assertFailsWith<IllegalStateException> { DriveLive.requireSafeRoot("ROOT") }
    }

    @Test
    fun `a configured run with no root fails rather than skips`() {
        assertFailsWith<IllegalStateException> { DriveLive.requireSafeRoot(null) }
    }

    @Test
    fun `a folder id passes`() {
        DriveLive.requireSafeRoot("FIXTURE0004xxxxxxxxxxxxxxxxxxxxxx")
    }
}
