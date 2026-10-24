package io.github.gccammelli.pandy

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonObject

class AmsTest {
    private fun mappingPrinter(): Printer {
        val ams=AmsInfo("ams",1,"AMS",emptyMap(),mapOf(1 to "sensor.white",2 to "sensor.green"))
        val info=PrinterInfo("printer","serial","Printer","X1C",null,emptyMap(),listOf(ams),emptyList())
        return Printer(info,listOf("white" to "#FFFFFF","green" to "#00FF00").associate { (name,color) ->
            "sensor.$name" to Entity("sensor.$name","PLA",json.parseToJsonElement("""{"type":"PLA","color":"$color"}""").jsonObject)
        })
    }

    @Test fun slicerMappingPreservesUnusedSlotsAndRefusesUnmatchedFilaments() {
        val p=mappingPrinter()
        assertEquals(listOf(0,-1,1),amsMappingFor(p,listOf(mapOf("id" to "1","type" to "PLA","color" to "#FFFFFF"),
            mapOf("id" to "3","type" to "pla","color" to "#00FF00"))))
        assertEquals(listOf(0),amsMappingFor(p,listOf(mapOf("id" to "bad","type" to "PLA","color" to "#FFFFFF"))))
        assertEquals(listOf(0),amsMappingFor(p,listOf(mapOf("type" to "PLA","color" to "#FFFFFF"))))
        assertNull(amsMappingFor(p,emptyList()))
        assertNull(amsMappingFor(p,listOf(mapOf("type" to "PETG"))))
        val bare=Printer(p.info.copy(ams=emptyList()),emptyMap())
        assertNull(amsMappingFor(bare,listOf(mapOf("type" to "PLA"))))
    }

    @Test
    fun mapsStandardAndAmsHtTrayIds() {
        val standard = AmsInfo("standard", 2, "AMS", emptyMap(), emptyMap())
        assertEquals(4, standard.trayId(0))
        assertEquals(7, standard.trayId(3))
        assertEquals(0, standard.slotForTrayId(4))
        assertNull(standard.slotForTrayId(8))

        val ht = AmsInfo("ht", 1, "AMS HT", emptyMap(), emptyMap())
        assertEquals(128, ht.trayId(0))
        assertEquals(0, ht.slotForTrayId(128))
        assertNull(ht.slotForTrayId(129))
        assertEquals(128, AmsInfo("ht2", 2, "AMS HT", emptyMap(), emptyMap()).trayId(0))
        assertEquals(129, AmsInfo("ht2", 2, "AMS HT", emptyMap(), emptyMap(), protocolId = 129).trayId(0))
    }

    @Test
    fun homeAssistantHtIdDoesNotCountStandardAms() {
        val serial = "TESTSERIAL"
        val devices = JsonArray(listOf(
            json.parseToJsonElement("""{"id":"printer","model":"X1C","name":"X1C","via_device_id":null,"identifiers":[["bambu_lab","$serial"]]}""").jsonObject,
            json.parseToJsonElement("""{"id":"standard","model":"AMS","name":"AMS","via_device_id":"printer","identifiers":[["bambu_lab","${serial}_ams0"]]}""").jsonObject,
            json.parseToJsonElement("""{"id":"ht","model":"AMS HT","name":"AMS HT","via_device_id":"printer","identifiers":[["bambu_lab","${serial}_ams128"]]}""").jsonObject,
        ))
        val entities = JsonArray(listOf(
            json.parseToJsonElement("""{"entity_id":"sensor.standard_tray_1","device_id":"standard","platform":"bambu_lab","translation_key":"tray","unique_id":"${serial}_tray_1"}""").jsonObject,
            json.parseToJsonElement("""{"entity_id":"sensor.ht_tray_1","device_id":"ht","platform":"bambu_lab","translation_key":"tray","unique_id":"${serial}_tray_1"}""").jsonObject,
        ))
        val ams = printersFrom(devices, entities).single().ams
        assertEquals(listOf(0, 128), ams.map { it.amsId() })
    }
}
