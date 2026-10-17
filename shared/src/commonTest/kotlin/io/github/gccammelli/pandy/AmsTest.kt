package io.github.gccammelli.pandy

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonObject

class AmsTest {
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
