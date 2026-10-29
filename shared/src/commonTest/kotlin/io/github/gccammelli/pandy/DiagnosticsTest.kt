package io.github.gccammelli.pandy

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DiagnosticsTest {
    @Test fun masksIdentifiersAndSecrets() {
        val samples = listOf(
            "10.20.30.40", "printer.example.local", "00M00A000000000",
            "device/00M00A000000000/report", "password=12345678", "code: 87654321",
            "https://demo:secret@printer.example.local", "Bearer abcdefghijklmnopqrstuvwxyz",
            "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxIn0.signature", "aa:bb:cc:dd:ee:ff",
            "demo@example.com", "SSID=ExampleNetwork"
        )
        samples.forEach { assertTrue("[redacted]" in redact(it), it) }
        assertFalse(redact("custom secret", listOf("custom secret")).contains("custom secret"))
    }

    @Test fun masksQuotedAndPrefixedCredentialsAtBothLogAndExport() {
        val samples = listOf(
            """{"access_token":"secret-with-\"escape"}""",
            "ha_token=secret-token", "'password': 'secret phrase'", "ACCESS-TOKEN: secret-value"
        )
        val info = DiagnosticsInfo("1", "test", null, "demo", 0, null, null, emptyList(), null)
        for (sample in samples) {
            AppLog.clear()
            AppLog.log("INFO", "test", sample)
            val log = AppLog.snapshot().single()
            assertFalse(log.contains("secret"), log)
            assertFalse(diagnosticsReport(info, listOf(sample)).contains("secret"), sample)
        }
        AppLog.clear()
    }

    @Test fun preservesOrdinaryMeasurements() {
        val text = "version 1.2.3, 2026-10-29, 210°C, 42%"
        assertEquals(text, redact(text))
    }

    @Test fun keepsOnlyLastThreeHundredSanitisedLines() {
        AppLog.clear()
        repeat(305) { AppLog.log("INFO", "test", "event $it at 10.20.30.40") }
        val lines = AppLog.snapshot()
        assertEquals(300, lines.size)
        assertTrue(lines.first().contains("event 5"))
        assertTrue(lines.last().contains("event 304"))
        assertFalse(lines.any { it.contains("10.20.30.40") })
        AppLog.clear()
    }
    @Test fun reportRedactsAgainAndContainsOnlySuppliedFields() {
        val info = DiagnosticsInfo("1.2.3", "Android", null, "direct", 2, "Demo model", "1.0", listOf("AMS"), null)
        val report = diagnosticsReport(info, listOf("device/00M00A000000000/report 10.20.30.40 code=12345678 custom-token"), listOf("custom-token"))
        listOf("00M00A000000000", "10.20.30.40", "12345678", "custom-token").forEach { assertFalse(report.contains(it)) }
        assertTrue(report.contains("Printer model: Demo model"))
        assertTrue(report.contains("AMS units: 1"))
        assertTrue(report.contains("OS version: Unknown"))
    }
}
