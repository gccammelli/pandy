package io.github.gccammelli.pandy

import io.github.gccammelli.pandy.ui.buildPrintShareText
import io.github.gccammelli.pandy.ui.formatFinishTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonObject

class JobDetailsTest {
    private val ids = mapOf(
        "sensor.print_status" to "s",
        "sensor.subtask_name" to "t",
        "sensor.gcode_file" to "g",
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
        job: String = "Panda Figurine",
        gcodeFile: String? = null,
        ams: List<Pair<AmsInfo, List<Spool?>>> = emptyList(),
        external: List<Spool> = emptyList(),
    ): Printer {
        val states = buildMap {
            put("s", Entity("s", status, JsonObject(emptyMap())))
            put("t", Entity("t", job, JsonObject(emptyMap())))
            gcodeFile?.let { put("g", Entity("g", it, JsonObject(emptyMap()))) }
            put("prog", Entity("prog", progress.toString(), JsonObject(emptyMap())))
            remainingMinutes?.let { put("rem", Entity("rem", it.toString(), JsonObject(emptyMap()))) }
            weight?.let { put("w", Entity("w", it.toString(), JsonObject(emptyMap()))) }
            length?.let { put("l", Entity("l", it.toString(), JsonObject(emptyMap()))) }
        }
        val info = PrinterInfo("p1", "SN123", "My X1C", "X1C", null, ids, ams.map { it.first }, emptyList())
        return Printer(info, states)
    }

    @Test
    fun shareTextForCompletedPrintHidesCostWhenNoPriceSet() {
        val p = printer(status = "finish", progress = 100, weight = 50.0, length = 16.5)
        val text = buildPrintShareText(p)
        assertTrue(text.contains("Print on My X1C: Panda Figurine"))
        assertTrue(text.contains("• Status: Completed successfully"))
        assertTrue(text.contains("• Weight: 50.0 g / 50.0 g"))
        assertFalse(text.contains("Estimated Cost"))
        assertTrue(text.contains("• Length: 16.5 m"))
    }

    @Test
    fun shareTextForActivePrintFormatsProgressAndFinishTime() {
        val p = printer(status = "running", progress = 42, remainingMinutes = 30.0, weight = 100.0)
        val text = buildPrintShareText(p)
        assertTrue(text.contains("Print on My X1C: Panda Figurine"))
        assertTrue(text.contains("• Progress: 42%"))
        assertTrue(Regex("""• Est\. Finish: \d{2}:\d{2}""").containsMatchIn(text))
        assertTrue(text.contains("• Weight: 42.0 g / 100.0 g"))
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

    @Test
    fun activePlateParsesFromGcodeOrTaskAndSlicePlateInfoAvoidsGuessingMultiPlate() {
        assertEquals(3, printer("running", gcodeFile = "/data/Metadata/plate_3.gcode").activePlate())
        assertEquals(2, printer("running", job = "model_plate_2").activePlate())
        assertNull(printer("running", job = "model_without_plate").activePlate())

        val singlePlateXml = """<config><plate><metadata key="index" value="1"/><metadata key="weight" value="12.5"/></plate></config>"""
        assertEquals(12.5, slicePlateInfo(singlePlateXml, null).weightGrams)

        val multiPlateXml = """<config><plate><metadata key="index" value="1"/><metadata key="weight" value="12.5"/></plate><plate><metadata key="index" value="2"/><metadata key="weight" value="25.0"/></plate></config>"""
        assertNull(slicePlateInfo(multiPlateXml, null).weightGrams)
        assertEquals(25.0, slicePlateInfo(multiPlateXml, 2).weightGrams)
    }
}
