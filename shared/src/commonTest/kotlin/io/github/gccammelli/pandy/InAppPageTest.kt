package io.github.gccammelli.pandy

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class InAppPageTest {
    @Test fun makerWorldAndSignInStayInPandy() {
        for (h in listOf("makerworld.com", "www.makerworld.com", "bambulab.com", "public-cdn.bblmw.com", "appleid.apple.com"))
            assertTrue(inAppPage("https", h), h)
        assertTrue(inAppPage("about", null))
        assertTrue(inAppPage("blob", "makerworld.com"))
    }

    @Test fun everythingElseOpensInTheBrowser() {
        for (h in listOf("evil.com", "makerworld.com.evil.com", "notmakerworld.com", "example.org")) assertFalse(inAppPage("https", h), h)
        assertFalse(inAppPage("http", "makerworld.com"))
        assertFalse(inAppPage("https", null))
    }
}
