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
        assertFalse(ModelSite.Thingiverse.ownsFile("https:///a.stl")) // no host
        assertTrue(ModelSite.Thingiverse.ownsFile("https://thingiverse.com:443/a.stl#x"))
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

    @Test fun siteFileRowsGetPandysOwnPrintButtonAndKeepTheSitesDownloadPlain() {
        assertTrue(SITE_DOWNLOAD_JS.contains("pandy-print-btn")) // the magenta Print button with the bear, beside the site's own buttons
        assertTrue(SITE_DOWNLOAD_JS.contains("window.__pandyPrintUntil = Date.now()")) // it presses the site's Download and says so for the next few seconds
        assertFalse(SITE_DOWNLOAD_JS.contains("nodeValue = want")) // the site's own Download is no longer relabelled to Print
        assertTrue(PRINT_INTENT_JS.contains("__pandyPrintUntil")) // the platform asks the page the same thing when that download arrives
    }

    @Test fun aDownloadAsksIfPrintWasPressedOnlyWhenItCouldBecomeAPrint() {
        assertTrue(asksPrintIntent(alreadyPrint = false, fromSite = true, name = "a.stl", canPrint = true))
        assertFalse(asksPrintIntent(alreadyPrint = true, fromSite = true, name = "a.stl", canPrint = true)) // MakerWorld's own prints
        assertFalse(asksPrintIntent(alreadyPrint = false, fromSite = false, name = "a.stl", canPrint = true))
        assertFalse(asksPrintIntent(alreadyPrint = false, fromSite = true, name = "a.zip", canPrint = true))
        assertFalse(asksPrintIntent(alreadyPrint = false, fromSite = true, name = "a.stl", canPrint = false))
    }
}
