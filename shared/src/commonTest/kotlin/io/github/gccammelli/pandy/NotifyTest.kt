package io.github.gccammelli.pandy

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.serialization.json.JsonObject

class NotifyTest {
    private val ids = mapOf("sensor.print_status" to "s", "sensor.subtask_name" to "t", "binary_sensor.hms_errors" to "h", "binary_sensor.print_error" to "e")
    private fun printer(status: String, hms: Int = 0, error: Boolean = false): Printer {
        val attrs = if (hms == 0) JsonObject(emptyMap()) else json.parseToJsonElement(
            """{"Count":"$hms",""" + (1..hms).joinToString(",") { """"$it-Code":"C$it","$it-Error":"Problem $it"""" } + "}").let { it as JsonObject }
        val states = mapOf(
            "s" to Entity("s", status, JsonObject(emptyMap())), "t" to Entity("t", "Benchy", JsonObject(emptyMap())),
            "h" to Entity("h", if (hms == 0) "off" else "on", attrs),
            "e" to Entity("e", if (error) "on" else "off", json.parseToJsonElement("""{"error":"Filament ran out"}""") as JsonObject))
        return Printer(PrinterInfo("p", "SN", "X1C", "X1C", null, ids, emptyList(), emptyList()), states)
    }

    @Test
    fun finishAfterPrinting() {
        val e = printEvent(printer("running"), printer("finish"))!!
        assertEquals("Print finished", e.title)
        assertEquals("Benchy is done on X1C", e.text)
    }

    @Test
    fun failureCarriesTheReason() {
        val e = printEvent(printer("running"), printer("failed", error = true))!!
        assertEquals("Print failed", e.title)
        assertEquals("Filament ran out (X1C)", e.text)
    }

    @Test
    fun newHmsErrorOnlyOnce() {
        val e = printEvent(printer("running"), printer("pause", hms = 1))!!
        assertEquals("Problem 1", e.text)
        assertNull(printEvent(printer("pause", hms = 1), printer("pause", hms = 1)))
    }

    @Test
    fun quietOtherwise() {
        assertNull(printEvent(null, printer("finish")))       // first reading after opening the app
        assertNull(printEvent(printer("idle"), printer("finish"))) // finished long ago
        assertNull(printEvent(printer("running"), printer("pause")))
        assertNull(printEvent(printer("running"), printer("running")))
    }
}
