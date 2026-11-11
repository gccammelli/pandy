// SPDX-License-Identifier: MIT
// Copyright (c) Giancarlo Cammelli
package io.github.gccammelli.pandy

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The phone-slicer protocol's remaining refusals, and what a platform without the plugin says. */
class PhoneSlicerEdgeTest {
    private val result = """{"version":1,"source_plate":2,"printer":"printer","bed_type":"textured","project_preserved":true,"output_plate":1,"filaments":[{"id":1,"type":"PLA","color":"#AABBCC"}],"applied_settings":{"wall_loops":"3"}}"""
    private val file = zipStored(listOf("Metadata/plate_1.gcode" to "G28\nG29\n".encodeToByteArray()))

    private suspend fun message(metadata: String, bytes: ByteArray? = file): String =
        assertFailsWith<ApiException> { phoneSliceResult(PhoneSlicerResponse(metadata, bytes), mapOf("wall_loops" to "3"), 2, "printer", "textured") }.message.orEmpty()

    @Test fun theRequestOmitsFilamentFieldsWhenNoneAreChosen() {
        val o = json.parseToJsonElement(phoneSliceRequest(1, "Mochi", "p", "b", null, null, emptyMap())) as JsonObject
        assertNull(o["filament_type"]); assertNull(o["filament_color"]); assertEquals("Mochi", o.s("name"))
        val c = json.parseToJsonElement(phoneSliceRequest(1, "Mochi", "p", "b", "PETG", "#000000", mapOf("a" to "1"))) as JsonObject
        assertEquals("PETG", c.s("filament_type")); assertEquals("#000000", c.s("filament_color"))
    }

    @Test fun settingsComeFromTheCapabilityDocument() {
        assertTrue("unsupported settings" in assertFailsWith<ApiException> { phoneSettings(PhoneSlicerResponse("""{"version":1,"settings":[]}""", null)) }.message.orEmpty())
        assertTrue("invalid response" in assertFailsWith<ApiException> { phoneSettings(PhoneSlicerResponse("not json", null)) }.message.orEmpty())
    }

    @Test fun filamentInformationMustBeCompleteAndValid() = runTest {
        fun withFilaments(f: String) = result.replace("""[{"id":1,"type":"PLA","color":"#AABBCC"}]""", f)
        val invalid = "invalid filament information"
        for (bad in listOf("""["text"]""", """[{"id":0,"type":"PLA","color":"#AABBCC"}]""", """[{"id":17,"type":"PLA","color":"#AABBCC"}]""", """[{"type":"PLA","color":"#AABBCC"}]""",
            """[{"id":1,"type":" ","color":"#AABBCC"}]""", """[{"id":1,"type":"${"x".repeat(81)}","color":"#AABBCC"}]""", """[{"id":1,"type":"PLA"}]""",
            """[{"id":1,"type":"PLA","color":"#AABBCC"},{"id":1,"type":"PETG","color":"#112233"}]""")) {
            assertTrue(invalid in message(withFilaments(bad)), bad)
        }
        assertTrue("no filament information" in message(result.replace(""","filaments":[{"id":1,"type":"PLA","color":"#AABBCC"}]""", "")))
    }

    @Test fun outputPlateAndFileSizeAreChecked() = runTest {
        assertTrue("no output plate" in message(result.replace("\"output_plate\":1", "\"output_plate\":0")))
        assertTrue("no output plate" in message(result.replace("\"output_plate\":1", "\"output_plate\":\"x\"")))
        assertTrue("no output plate" in message(result.replace(",\"output_plate\":1", "")))
        assertTrue("no sliced file" in message(result, ByteArray(0)))
        assertTrue("build plate" in message(result.replace("textured", "other")))
    }

    @Test fun twoFilamentsAndAnEightDigitColourAreAccepted() = runTest {
        val two = result.replace("""[{"id":1,"type":"PLA","color":"#AABBCC"}]""", """[{"id":1,"type":"PLA","color":"AABBCC"},{"id":2,"type":"PETG","color":"#AABBCCDD"}]""")
        val (_, plate) = phoneSliceResult(PhoneSlicerResponse(two, file), mapOf("wall_loops" to "3"), 2, "printer", "textured")
        assertEquals(listOf("1", "2"), plate.filaments.map { it["id"] })
    }

    private class Ui : PlatformUi {
        override val appVersion = "t"; override fun openUrl(url: String) = Unit; override fun toast(message: String) = Unit; override fun setDarkBars(dark: Boolean) = Unit
        override fun pickImage(done: (ByteArray?) -> Unit) = Unit; override fun saveImage(jpeg: ByteArray, name: String, done: (Throwable?) -> Unit) = Unit
        override fun saveFile(name: String, mime: String, bytes: ByteArray, done: (Throwable?) -> Unit) = Unit
        override fun openApp(androidPackage: String, storeUrl: String) = Unit; override fun landscape(on: Boolean) = Unit
        override fun requestNotifications() = Unit; override fun watchInBackground(on: Boolean) = Unit
    }

    @Test fun aPlatformWithoutThePluginRefusesPhoneSlicingAndInstallation() = runTest {
        val ui = Ui()
        assertFalse(ui.phoneSlicerSupported); assertFalse(ui.phoneSlicerAvailable()); assertNull(ui.studio); assertFalse(ui.canNotify)
        assertEquals("The slicer plugin is not available on this device.", assertFailsWith<ApiException> { ui.phoneSlicer("SLICE_V1", "{}") }.message)
        assertEquals("The slicer plugin is not available on this device.", assertFailsWith<ApiException> { ui.installSlicerPlugin { } }.message)
    }
}
