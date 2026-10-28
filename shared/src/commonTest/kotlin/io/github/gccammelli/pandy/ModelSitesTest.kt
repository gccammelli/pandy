package io.github.gccammelli.pandy

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ModelSitesTest {
    @Test fun sitesOwnTheirPagesAndFilesOnly() {
        assertTrue(ModelSite.Thingiverse.ownsPage("www.thingiverse.com"))
        assertTrue(ModelSite.Printables.ownsPage("PRINTABLES.com"))
        assertFalse(ModelSite.Printables.ownsPage("printables.com.evil.example"))
        assertFalse(ModelSite.Thingiverse.ownsPage("printables.com"))
        assertFalse(ModelSite.Thingiverse.ownsPage(null))
        assertTrue(ModelSite.Thingiverse.ownsFile("https://cdn.thingiverse.com/assets/a/b/c.stl"))
        assertTrue(ModelSite.Printables.ownsFile("https://files.printables.com/media/prints/1/stls/1.stl?x=1"))
        assertTrue(ModelSite.MakerWorld.ownsFile("https://public-cdn.bblmw.com/a.3mf"))
        for (bad in listOf("http://cdn.thingiverse.com/a.stl", "https://thingiverse.com@evil.example/a.stl", "https://evil.example/thingiverse.com/a.stl",
            "https://cdn.thingiverse.com.evil.example/a.stl", "https://cdn.thingiverse.com\\a.stl", "https://cdn.thingiverse.com/a b.stl", "https://evil.example/?h=cdn.thingiverse.com"))
            assertFalse(ModelSite.Thingiverse.ownsFile(bad), bad)
        assertFalse(ModelSite.Printables.ownsFile("https://cdn.thingiverse.com/a.stl"))
    }

    @Test fun othersListsTheTwoOtherSites() {
        assertEquals(listOf(ModelSite.Thingiverse, ModelSite.Printables), ModelSite.MakerWorld.others)
        assertEquals(3, ModelSite.entries.size)
    }

    @Test fun theNewSitesAndTheirSignInLoadInPandy() {
        for (h in listOf("www.thingiverse.com", "cdn.thingiverse.com", "www.printables.com", "account.prusa3d.com")) assertTrue(inAppPage("https", h), h)
        assertFalse(inAppPage("https", "thingiverse.com.evil.example"))
    }

    @Test fun onlyStlAndThreeMfCanBePrinted() {
        for (n in listOf("a.stl", "A.STL", "b.3mf", "c.stl?token=1")) assertTrue(isPrintableModel(n), n)
        for (n in listOf("a.zip", "a.png", "a.step", "stl")) assertFalse(isPrintableModel(n), n)
    }
}
