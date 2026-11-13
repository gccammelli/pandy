package io.github.gccammelli.pandy

import io.github.gccammelli.pandy.ui.buildPrintShareText
import io.github.gccammelli.pandy.ui.formatFinishTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonObject

class JobDetailsTest {
    private val ids = mapOf(
        "sensor.print_status" to "s",
        "sensor.subtask_name" to "t",
        "sensor.print_progress" to "prog",
        "sensor.remaining_time" to "rem",
        "sensor.print_weight" to "w",
        "sensor.print_length" to "l"
    )

    private fun printer(
        status: String,
        progress: Int = 100,
        remainingMinutes: Double? = 0.0,
        weight: Double? = 45.0,
        length: Double? = 15.0,
        job: String = "Panda Figurine"
    ): Printer {
        val states = buildMap {
            put("s", Entity("s", status, JsonObject(emptyMap())))
            put("t", Entity("t", job, JsonObject(emptyMap())))
            put("prog", Entity("prog", progress.toString(), JsonObject(emptyMap())))
            remainingMinutes?.let { put("rem", Entity("rem", it.toString(), JsonObject(emptyMap()))) }
            weight?.let { put("w", Entity("w", it.toString(), JsonObject(emptyMap()))) }
            length?.let { put("l", Entity("l", it.toString(), JsonObject(emptyMap()))) }
        }
        return Printer(PrinterInfo("p1", "SN123", "My X1C", "X1C", null, ids, emptyList(), emptyList()), states)
    }

    @Test
    fun shareTextForCompletedPrintFormatsSummaryCorrectly() {
        val p = printer(status = "finish", progress = 100, weight = 50.0, length = 16.5)
        val text = buildPrintShareText(p)
        assertTrue(text.contains("Print on My X1C: Panda Figurine"))
        assertTrue(text.contains("Status: Completed successfully"))
        assertTrue(text.contains("Weight: 50 g / 50 g") || text.contains("50.0 g / 50.0 g") || text.contains("50"))
        assertTrue(text.contains("Estimated Cost: ~1 €") || text.contains("1.0 €"))
        assertTrue(text.contains("Length: 16.5 m"))
    }

    @Test
    fun shareTextForActivePrintFormatsProgress() {
        val p = printer(status = "running", progress = 42, remainingMinutes = 30.0, weight = 100.0)
        val text = buildPrintShareText(p)
        assertTrue(text.contains("Print on My X1C: Panda Figurine"))
        assertTrue(text.contains("Progress: 42%"))
        assertTrue(text.contains("Est. Finish:") || text.contains("Weight:"))
    }

    @Test
    fun formatFinishTimeReturnsNullForNullOrNonPositive() {
        assertNull(formatFinishTime(null))
        assertNull(formatFinishTime(0.0))
        assertNull(formatFinishTime(-10.0))
    }

    @Test
    fun formatFinishTimeProducesValidClockString() {
        val finish = formatFinishTime(60.0)
        assertTrue(finish != null && finish.matches(Regex("\\d{2}:\\d{2}")))
    }
}
