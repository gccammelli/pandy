package io.github.gccammelli.pandy

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class DemoCoverageTest {
    private suspend fun FakeHomeAssistant.state(domain: String, key: String) =
        json.parseToJsonElement(get("/api/states/$domain.x1c_demo_$key")).jsonObject

    private suspend fun FakeHomeAssistant.command(service: String, vararg data: Pair<String, String>) =
        post("/api/services/$service", buildJsonObject { data.forEach { (k, v) -> put(k, v) } }.toString())

    @Test fun theSimulatedPrintFinishesAndTemperaturesDriftTowardTheirTargetsOrAmbient() = runTest {
        val ha = FakeHomeAssistant()
        ha.tick()
        assertEquals("running", ha.state("sensor", "print_status").s("state"))
        ha.progress = 99.9
        ha.tick()
        assertEquals("finish", ha.state("sensor", "print_status").s("state"))
        assertEquals("0", ha.state("sensor", "target_nozzle_temp").s("state"))
        ha.tick() // no longer running: only the temperatures move, toward ambient since the targets are 0
        val cooled = ha.state("sensor", "nozzle_temp").s("state")!!.toDouble()
        assertTrue(cooled < 200.0)
        ha.command("number/set_value", "entity_id" to "number.x1c_demo_target_nozzle_temperature", "value" to "250")
        repeat(30) { ha.tick() }
        assertTrue(ha.state("sensor", "nozzle_temp").s("state")!!.toDouble() > cooled)
    }

    @Test fun streamsCanBeLimitedToTheEntitiesAPageSubscribedTo() = runTest {
        val ha = FakeHomeAssistant()
        val events = mutableListOf<JsonObject>()
        val job = launch(UnconfinedTestDispatcher(testScheduler)) {
            ha.stream(buildJsonObject { putJsonArray("entity_ids") { add(JsonPrimitive("light.x1c_demo_chamber_light")) } }) { events += it }
        }
        runCurrent()
        assertEquals(setOf("light.x1c_demo_chamber_light"), events.first().o("a")!!.keys)
        ha.command("light/turn_off")
        ha.command("select/select_option", "option" to "sport") // not subscribed: filtered out of the update
        advanceTimeBy(2_500); runCurrent()
        val changes = events.drop(1).flatMap { it.o("c")!!.keys }.toSet()
        assertTrue("light.x1c_demo_chamber_light" in changes); assertFalse(changes.any { it.contains("printing_speed") })
        job.cancel()
    }

    @Test fun commandsWithMissingOrUnusualFieldsKeepTheDemoConsistent() = runTest {
        val ha = FakeHomeAssistant()
        ha.command("button/press", "entity_id" to "button.x1c_demo_resume") // resuming a running print does nothing
        assertEquals("running", ha.state("sensor", "print_status").s("state"))
        ha.command("button/press", "entity_id" to "button.x1c_demo_pause")
        ha.command("button/press", "entity_id" to "button.x1c_demo_pause") // already paused
        assertEquals("pause", ha.state("sensor", "print_status").s("state"))
        ha.command("button/press", "entity_id" to "button.x1c_demo_resume")
        ha.command("button/press", "entity_id" to "button.x1c_demo_unknown")
        ha.command("button/press", "entity_id" to "button.x1c_demo_stop")
        assertEquals("failed", ha.state("sensor", "print_status").s("state"))
        ha.command("number/set_value", "entity_id" to "number.x1c_demo_target_bed_temperature", "value" to "not a number")
        assertEquals("0", ha.state("number", "target_bed_temperature").s("state"))
        ha.command("bambu_lab/skip_objects", "objects" to "1, x, 2")
        ha.command("bambu_lab/skip_objects", "objects" to "2,3")
        assertEquals("3", ha.state("sensor", "skipped_objects").s("state"))
        ha.command("bambu_lab/skip_objects")
        ha.command("bambu_lab/print_project_file")
        assertEquals("Model", ha.state("sensor", "subtask_name").s("state"))
        ha.command("bambu_lab/print_project_file", "filepath" to "cache/Benchy.3mf")
        assertEquals("Benchy", ha.state("sensor", "subtask_name").s("state"))
        ha.command("bambu_lab/calibration")
        ha.command("bambu_lab/xcam_control_set")
        ha.command("bambu_lab/xcam_control_set", "module_name" to "nonexistent", "enable" to "true")
        ha.command("bambu_lab/xcam_control_set", "module_name" to "printing_monitor", "enable" to "true")
        ha.command("bambu_lab/print_option")
        ha.command("bambu_lab/print_option", "unrelated" to "true")
        ha.command("bambu_lab/print_option", "sound_enable" to "true")
        ha.command("bambu_lab/ams_user_setting")
        ha.command("bambu_lab/ams_user_setting", "ams_id" to "x", "startup_read_option" to "true", "tray_read_option" to "false")
        ha.command("unknown/service")
        for (path in listOf("/api/image_proxy/cover_image", "/api/image_proxy/pick_image", "/api/camera_proxy/x", "/api/bambu_lab/file_cache/x")) {
            runCatching { ha.bytes(path) }
        }
    }
}
