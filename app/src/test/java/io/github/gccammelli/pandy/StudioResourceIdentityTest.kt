package io.github.gccammelli.pandy

import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class StudioResourceIdentityTest {
    private val zip = "ab".repeat(32)
    private val tree = "cd".repeat(32)
    private fun orca() = JSONObject().put("version", 1).put("engine_id", "orca").put("profile_root", "pandy/editor")
        .put("sha256", zip).put("resource_version", zip)
    @Test fun orcaRequiresItsOwnMatchingVersionedArchive() {
        assertEquals(zip, studioResourceVersion("orca", orca(), zip, zip))
        for (bad in listOf(orca().put("engine_id", "bambu"), orca().put("profile_root", "resources"),
            orca().put("resource_version", ""), orca().put("resource_version", tree), orca().put("sha256", tree))) {
            assertFailsWith<IllegalArgumentException> { studioResourceVersion("orca", bad, zip, null) }
        }
        assertFailsWith<IllegalArgumentException> { studioResourceVersion("orca", orca(), zip, tree) }
    }
    @Test fun legacyBambuKeepsItsSeparateTreeDigestAndCannotBeUsedAsOrca() {
        val old = JSONObject().put("version", 1).put("sha256", zip)
        assertEquals(tree, studioResourceVersion("bambu", old, zip, tree))
        assertNull(studioResourceVersion("bambu", old, zip, null))
        assertFailsWith<IllegalArgumentException> { studioResourceVersion("orca", old, zip, tree) }
        assertFailsWith<IllegalArgumentException> { studioResourceVersion("bambu", old, tree, null) }
    }
}
