package io.github.gccammelli.pandy

import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import kotlin.test.*

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class DemoControlsTest {
    private suspend fun FakeHomeAssistant.state(domain: String, key: String) =
        json.parseToJsonElement(get("/api/states/$domain.x1c_demo_$key")).jsonObject

    private suspend fun FakeHomeAssistant.command(service: String, vararg data: Pair<String, String>) =
        post("/api/services/$service", buildJsonObject { data.forEach { (k, v) -> put(k, v) } }.toString())

    @Test fun registryAndReadEndpointsDescribeADemoPrinter() = runTest {
        val ha = FakeHomeAssistant()
        val printer = ha.printers().single()
        assertEquals("00M000000000000", printer.serial)
        assertEquals(1, printer.ams.size)
        assertEquals("running", ha.state("sensor", "print_status").s("state"))
        assertTrue(ha.get("/api/bambu_lab/print_history").contains("Benchy"))
        assertTrue(ha.get("/api/bambu_lab/videos").contains(".mp4"))
        assertTrue(ha.get("model.slice_info.config").contains("filament"))
        assertTrue(ha.get("/api/config").contains("UTC"))
        assertEquals("[]", ha.get("/api/history/period"))
        assertEquals("[]", ha.get("/unknown"))
        assertFailsWith<ApiException> { ha.get("/api/states/sensor.missing") }
        assertFailsWith<ApiException> { ha.ws(listOf(buildJsonObject { put("type", "camera/stream") })) }
        assertEquals(listOf(JsonNull), ha.ws(listOf(buildJsonObject { put("type", "unknown") })))
        assertTrue(ha.bytes("/unknown").isEmpty())
    }

    @Test fun controlsUpdateBothControlAndReportedState() = runTest {
        val ha = FakeHomeAssistant()
        ha.command("light/turn_off")
        assertEquals("off", ha.state("light", "chamber_light").s("state"))
        ha.command("light/toggle")
        assertEquals("on", ha.state("light", "chamber_light").s("state"))
        ha.command("light/toggle"); ha.command("light/turn_on")
        assertEquals("on", ha.state("light", "chamber_light").s("state"))
        for ((key, sensor, value) in listOf(Triple("nozzle", "nozzle", "205"), Triple("bed", "bed", "60"))) {
            ha.command("number/set_value", "entity_id" to "number.x1c_demo_target_${key}_temperature", "value" to value)
            assertEquals(value, ha.state("number", "target_${key}_temperature").s("state"))
            assertEquals(value, ha.state("sensor", "target_${sensor}_temp").s("state"))
        }
        ha.command("select/select_option", "option" to "silent")
        assertEquals("silent", ha.state("select", "printing_speed").s("state"))
        assertEquals("silent", ha.state("sensor", "speed_profile").s("state"))
        for ((service, pct) in listOf("set_percentage" to "35", "turn_off" to "0", "turn_on" to "100")) {
            val args = mutableListOf("entity_id" to "fan.x1c_demo_aux_fan")
            if (service == "set_percentage") args += "percentage" to pct
            ha.command("fan/$service", *args.toTypedArray())
            assertEquals(pct, ha.state("sensor", "aux_fan_speed").s("state"))
            assertEquals(if (pct == "0") "off" else "on", ha.state("fan", "aux_fan").s("state"))
        }
        ha.command("switch/turn_off", "entity_id" to "switch.x1c_demo_camera")
        assertEquals("off", ha.state("switch", "camera").s("state"))
        ha.command("switch/turn_on", "entity_id" to "switch.x1c_demo_camera")
        assertEquals("on", ha.state("switch", "camera").s("state"))
    }

    @Test fun pauseResumeStopAndNewPrintHaveConsistentState() = runTest {
        val ha = FakeHomeAssistant()
        suspend fun press(key: String) = ha.command("button/press", "entity_id" to "button.x1c_demo_$key")
        press("pause"); press("pause")
        assertEquals("pause", ha.state("sensor", "print_status").s("state"))
        press("resume"); press("resume")
        assertEquals("running", ha.state("sensor", "print_status").s("state"))
        ha.command("bambu_lab/skip_objects", "objects" to "101,102,invalid,101")
        assertEquals("2", ha.state("sensor", "skipped_objects").s("state"))
        press("stop")
        assertEquals("failed", ha.state("sensor", "print_status").s("state"))
        assertEquals("idle", ha.state("sensor", "stage").s("state"))
        assertEquals("0", ha.state("sensor", "remaining_time").s("state"))
        assertEquals("0", ha.state("sensor", "target_nozzle_temp").s("state"))
        ha.command("bambu_lab/print_project_file", "filepath" to "cache/Cube.3mf")
        assertEquals("Cube", ha.state("sensor", "subtask_name").s("state"))
        assertEquals("running", ha.state("sensor", "print_status").s("state"))
        assertEquals("0", ha.state("sensor", "skipped_objects").s("state"))
        assertEquals("220", ha.state("sensor", "target_nozzle_temp").s("state"))
        ha.command("bambu_lab/calibration")
        assertEquals("calibrating", ha.state("sensor", "stage").s("state"))
    }

    @Test fun optionsAndAmsSettingsAreSessionLocal() = runTest {
        val ha = FakeHomeAssistant()
        ha.command("bambu_lab/xcam_control_set", "module_name" to "first_layer_inspector", "enable" to "false")
        assertEquals("off", ha.state("switch", "first_layer_inspector").s("state"))
        ha.command("bambu_lab/print_option", "auto_recovery" to "false")
        assertEquals("off", ha.state("switch", "auto_recovery").s("state"))
        ha.command("bambu_lab/ams_user_setting", "startup_read_option" to "false", "tray_read_option" to "true", "calibrate_remain_flag" to "false")
        assertEquals("off", ha.state("switch", "AMS_1_ams_read_on_startup").s("state"))
        assertEquals("on", ha.state("switch", "AMS_1_ams_read_on_insertion").s("state"))
        assertEquals("off", ha.state("switch", "AMS_1_ams_remaining_estimation").s("state"))
        assertEquals("on", FakeHomeAssistant().state("switch", "auto_recovery").s("state"))
        assertEquals("{\"status\":\"present\"}", ha.post("/api/bambu_lab/ensure_cache_file", "{}"))
    }

    @Test fun streamFiltersSnapshotAndSubsequentChanges() = runTest {
        val ha = FakeHomeAssistant()
        val events = mutableListOf<JsonObject>()
        val id = "light.x1c_demo_chamber_light"
        val job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            ha.stream(buildJsonObject { put("entity_ids", JsonArray(listOf(JsonPrimitive(id)))) }) { events += it }
        }
        assertEquals(setOf(id), events.first().getValue("a").jsonObject.keys)
        ha.command("light/turn_off")
        assertEquals("off", events.last().getValue("c").jsonObject.getValue(id).jsonObject.getValue("+").jsonObject.s("s"))
        val count = events.size
        ha.command("switch/turn_off", "entity_id" to "switch.x1c_demo_camera")
        assertEquals(count, events.size)
        job.cancel()
    }
}
