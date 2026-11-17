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

    @Test
    fun eventTypesAndActionButtonsAreProvidedForPrintStates() {
        val finish = printEvent(printer("running"), printer("finish"))!!
        assertEquals(PrintEventType.FINISH, finish.type)
        assertEquals(listOf(PrintAction.SHARE), finish.actions)
        assertEquals("SN", finish.serial)

        val failed = printEvent(printer("running"), printer("failed", error = true))!!
        assertEquals(PrintEventType.FAILED, failed.type)
        assertEquals(emptyList(), failed.actions)
        assertEquals("SN", failed.serial)

        val pausedAttention = printEvent(printer("running"), printer("pause", hms = 1))!!
        assertEquals(PrintEventType.ATTENTION, pausedAttention.type)
        assertEquals(listOf(PrintAction.RESUME, PrintAction.STOP), pausedAttention.actions)
        assertEquals("SN", pausedAttention.serial)

        val runningAttention = printEvent(printer("running"), printer("running", hms = 1, code = { "HMS_0300_0100_0002_0007" }))!!
        assertEquals(PrintEventType.ATTENTION, runningAttention.type)
        assertEquals(listOf(PrintAction.PAUSE, PrintAction.STOP), runningAttention.actions)
        assertEquals("SN", runningAttention.serial)

        val dispatched = mutableListOf<Triple<String, String, String>>()
        val notifier = object : Notifier {
            override fun show(key: String, title: String, text: String) {
                dispatched += Triple(key, title, text)
            }
        }
        notifier.showEvent(pausedAttention)
        assertEquals(listOf(Triple("SN", "X1C needs attention", "Problem 1")), dispatched)

        val eventWithoutSerial = PrintEvent(title = "General", text = "Alert", serial = null)
        notifier.showEvent(eventWithoutSerial)
        assertEquals(listOf(Triple("SN", "X1C needs attention", "Problem 1"), Triple("pandy", "General", "Alert")), dispatched)

        PrintAction.values().forEach {
            kotlin.test.assertTrue(it.id.isNotBlank())
            kotlin.test.assertTrue(it.label.isNotBlank())
            assertEquals(it, PrintAction.valueOf(it.name))
        }
        PrintEventType.values().forEach {
            assertEquals(it, PrintEventType.valueOf(it.name))
        }
    }

    @Test
    fun finishAndFailurePreferCameraSnapshotWithCoverFallback() {
        val withCamIds = ids + ("camera.camera" to "cam") + ("image.cover_image" to "cov")
        val withCamStates = mapOf(
            "s" to Entity("s", "finish", JsonObject(emptyMap())),
            "t" to Entity("t", "Benchy", JsonObject(emptyMap())),
            "cam" to Entity("cam", "streaming", JsonObject(emptyMap())),
            "cov" to Entity("cov", "v1", JsonObject(emptyMap()))
        )
        val runningWithCam = Printer(PrinterInfo("p", "SN", "X1C", "X1C", null, withCamIds, emptyList(), emptyList()),
            withCamStates + ("s" to Entity("s", "running", JsonObject(emptyMap()))))
        val finishedWithCam = Printer(PrinterInfo("p", "SN", "X1C", "X1C", null, withCamIds, emptyList(), emptyList()), withCamStates)

        val ev = printEvent(runningWithCam, finishedWithCam)!!
        assertEquals("/api/camera_proxy/cam", ev.imagePath)
        assertEquals("/api/image_proxy/cov?v=v1", ev.fallbackImagePath)

        // Without camera, falls back directly to coverImage as primary and null fallback
        val noCamIds = ids + ("image.cover_image" to "cov")
        val runningNoCam = Printer(PrinterInfo("p", "SN", "X1C", "X1C", null, noCamIds, emptyList(), emptyList()),
            withCamStates + ("s" to Entity("s", "running", JsonObject(emptyMap()))) - "cam")
        val finishedNoCam = Printer(PrinterInfo("p", "SN", "X1C", "X1C", null, noCamIds, emptyList(), emptyList()),
            withCamStates - "cam")

        val evNoCam = printEvent(runningNoCam, finishedNoCam)!!
        assertEquals("/api/image_proxy/cov?v=v1", evNoCam.imagePath)
        assertNull(evNoCam.fallbackImagePath)
    }
}
