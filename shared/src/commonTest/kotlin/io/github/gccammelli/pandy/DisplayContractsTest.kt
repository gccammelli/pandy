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

    @Test fun speedHumidityAndFileSizesUseTheirDocumentedDisplayBoundaries() {
        for((key,label) in listOf("silent" to "Silent","standard" to "Standard","sport" to "Sport","ludicrous" to "Ludicrous",null to "--","future" to "--")) assertEquals(label,speedLabel(key))
        assertEquals(listOf("0min","59min","1h 0min","1h 59min"),listOf(0.0,59.9,60.0,119.9).map(::minutes))
        assertEquals(listOf("0 B","1023 B","1 KB","1023 KB","1.0 MB"),listOf(0L,1023L,1024L,1_048_575L,1_048_576L).map(::sizeText))
        assertEquals(androidx.compose.ui.graphics.Color(0xFF00A33E),humidityColor(2))
        assertEquals(androidx.compose.ui.graphics.Color(0xFFFFB020),humidityColor(3))
        assertEquals(androidx.compose.ui.graphics.Color(0xFFE5484D),humidityColor(4))
        assertEquals("",stageText("")); assertEquals("",nozzleType(""))
        for((raw,label) in listOf("hardened_steel" to "Hardened steel","stainless_steel" to "Stainless steel","brass" to "Brass","tungsten_carbide" to "Tungsten carbide","high_flow_stainless_steel" to "High flow stainless steel")) assertEquals(label,nozzleType(raw))
    }

}
