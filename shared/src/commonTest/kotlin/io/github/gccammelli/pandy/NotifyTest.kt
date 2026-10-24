package io.github.gccammelli.pandy

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.serialization.json.JsonObject

class NotifyTest {
    private val ids = mapOf("sensor.print_status" to "s", "sensor.subtask_name" to "t", "binary_sensor.hms_errors" to "h", "binary_sensor.print_error" to "e")
    private fun printer(status: String, hms: Int = 0, error: Boolean = false, code: (Int) -> String = { "C$it" }, job: String? = "Benchy"): Printer {
        val attrs = if (hms == 0) JsonObject(emptyMap()) else json.parseToJsonElement(
            """{"Count":"$hms",""" + (1..hms).joinToString(",") { """"$it-Code":"${code(it)}","$it-Error":"Problem $it"""" } + "}").let { it as JsonObject }
        val states = mapOf(
            "s" to Entity("s", status, JsonObject(emptyMap())), "t" to Entity("t", job ?: "unknown", JsonObject(emptyMap())),
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
    fun routineNoticesOnlyWhenThePrintStops() {
        val notice: (Int) -> String = { "HMS_0C00_0300_0003_000B" } // "Inspecting the first layer: please wait a moment."
        assertNull(printEvent(printer("running"), printer("running", hms = 1, code = notice)))
        assertEquals("Problem 1", printEvent(printer("running"), printer("pause", hms = 1, code = notice))!!.text)
        assertEquals("Problem 1", printEvent(printer("running"), printer("running", hms = 1, code = { "HMS_0300_0100_0002_0007" }))!!.text)
    }

    @Test
    fun quietOtherwise() {
        assertNull(printEvent(null, printer("finish")))       // first reading after opening the app
        assertNull(printEvent(printer("idle"), printer("finish"))) // finished long ago
        assertNull(printEvent(printer("running"), printer("pause")))
        assertNull(printEvent(printer("running"), printer("running")))
    }

    @Test fun missingJobNamesUseTheLastKnownJobOrAnExplicitGenericLabel() {
        assertEquals("Benchy is done on X1C",printEvent(printer("running"),printer("finish",job=null))!!.text)
        assertEquals("The print is done on X1C",printEvent(printer("running",job=null),printer("finish",job=null))!!.text)
        assertEquals("The print (X1C)",printEvent(printer("running",job=null),printer("failed",job=null))!!.text)
    }

    @Test fun hmsSeverityAndMalformedCodesNeverHideAnActionableNewError() {
        for(code in listOf("HMS_0C00_0300_0001_000B","HMS_0C00_0300_0002_000B","HMS_0C00_0300_invalid_000B","unrecognized")) {
            assertEquals("Problem 1",printEvent(printer("running"),printer("running",hms=1,code={code}))!!.text)
        }
        for(code in listOf("HMS_0C00_0300_0003_000B","HMS_0C00_0300_0004_000B")) {
            assertNull(printEvent(printer("running"),printer("running",hms=1,code={code})))
            assertEquals("Problem 1",printEvent(printer("running"),printer("pause",hms=1,code={code}))!!.text)
        }
    }
}
