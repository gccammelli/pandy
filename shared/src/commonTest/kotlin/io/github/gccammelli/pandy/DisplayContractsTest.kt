package io.github.gccammelli.pandy

import io.github.gccammelli.pandy.ui.*
import kotlin.test.*

class DisplayContractsTest {
    @Test fun unavailableStagesStayUnavailableAndUnknownStagesRemainVisible() {
        for (state in listOf(null,"idle","offline","unknown")) assertNull(stageText(state))
        assertEquals("Heating hotend",stageText("heating_hotend"))
        assertEquals("Paused",stageText("paused_user"))
        assertEquals("Auto bed leveling",stageText("auto_bed_leveling"))
        assertEquals("Calibrating extrusion",stageText("calibrating_extrusion_flow"))
        assertEquals("Future firmware state",stageText("future_firmware_state"))
        assertEquals("--",minutes(null)); assertEquals("1h 5min",minutes(65.8))
        assertEquals("High flow hardened steel",nozzleType("high_flow_hardened_steel"))
        assertEquals("",nozzleType(null)); assertEquals("New alloy",nozzleType("new_alloy"))
    }
    @Test fun firmwarePreparationStagesHaveReadableLabels() {
        for ((stage,label) in listOf(2 to "Heatbed preheating",9 to "Scanning bed surface",10 to "Inspecting first layer",
            11 to "Identifying build plate type",14 to "Cleaning nozzle tip",13 to "Homing toolhead",4 to "Changing filament")) {
            assertEquals(label,stageText(stageName(stage)))
        }
    }

}
