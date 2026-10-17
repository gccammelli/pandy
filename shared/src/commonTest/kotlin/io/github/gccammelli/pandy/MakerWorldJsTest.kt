package io.github.gccammelli.pandy

import kotlin.test.Test
import kotlin.test.assertTrue

class MakerWorldJsTest {
    @Test
    fun printScriptRequestsTheSelectedPrinterVariant() {
        assertTrue(PRINT_BUTTON_JS.contains("otherCompatibilityModelInfo"))
        assertTrue(PRINT_BUTTON_JS.contains("devModelName"))
        assertTrue(PRINT_BUTTON_JS.contains("encodeURIComponent(c.devModelName)"))
        assertTrue(PRINT_BUTTON_JS.contains("__pandyPrint"))
        assertTrue(PRINT_BUTTON_JS.contains("open in bambu handy"))
        assertTrue(PRINT_BUTTON_JS.contains("Download 3MF"))
        assertTrue(!PRINT_BUTTON_JS.contains("|download 3mf|"))
        assertTrue(PRINT_BUTTON_JS.contains("target !== button && !label.contains(target)"))
        assertTrue(PRINT_BUTTON_JS.contains("textContent = 'Print'"))
    }
}
