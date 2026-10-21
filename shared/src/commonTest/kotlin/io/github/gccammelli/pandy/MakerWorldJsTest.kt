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
        // "Download 3MF" stays a download: on models without a Handy option Pandy adds its own Print above it.
        assertTrue(!PRINT_BUTTON_JS.contains("|download 3mf|") && !PRINT_BUTTON_JS.contains("|print|download 3mf)"))
        assertTrue(PRINT_BUTTON_JS.contains("pandy-print-button"))
        assertTrue(PRINT_BUTTON_JS.contains("button,[role=\"button\"],a"))
        assertTrue(PRINT_BUTTON_JS.contains("span[class*=\"primaryButton\"]"))
        assertTrue(PRINT_BUTTON_JS.contains("html.pandy-print .pandy-split"))
        assertTrue(PRINT_BUTTON_JS.contains("download and open"))
        assertTrue(PRINT_BUTTON_JS.contains("MuiPopover-paper"))
        assertTrue(PRINT_BUTTON_JS.contains("makerlab-selector"))
        assertTrue(PRINT_BUTTON_JS.contains("overflow-y', 'auto'"))
        // A printer that takes commands only from Bambu's apps leaves MakerWorld untouched.
        assertTrue(PRINT_BUTTON_JS.contains("window.__pandyCanPrint !== false"))
        assertTrue(PRINT_BUTTON_JS.contains("target !== action && !label.contains(target)"))
        assertTrue(PRINT_BUTTON_JS.contains("textContent = 'Print'"))
        assertTrue(PRINT_BUTTON_JS.contains("get the full app experience"))
        assertTrue(PRINT_BUTTON_JS.contains("You are browsing Bambu Lab MakerWorld"))
        assertTrue(PRINT_BUTTON_JS.contains(">OK!<"))
    }
}
