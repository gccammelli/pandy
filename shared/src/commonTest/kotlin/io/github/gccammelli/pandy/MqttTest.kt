package io.github.gccammelli.pandy

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MqttTest {
    @Test
    fun encodesConnect() {
        val packet = Mqtt.connect("client", "bblp", "code")
        assertEquals(0x10, packet[0].toInt() and 0xFF)
        assertEquals(30, packet[1].toInt() and 0xFF)
        assertContentEquals(byteArrayOf(0, 4, 'M'.code.toByte(), 'Q'.code.toByte(), 'T'.code.toByte(), 'T'.code.toByte()), packet.copyOfRange(2, 8))
        assertEquals(4, packet[8].toInt())
        assertEquals(0xC2, packet[9].toInt() and 0xFF)
        assertEquals(0, packet[10].toInt() and 0xFF)
        assertEquals(30, packet[11].toInt() and 0xFF)
    }

    @Test
    fun readsConnackAndSubscribe() {
        val connack = read(byteArrayOf(0x20, 2, 1, 0))
        val decoded = Mqtt.decodeConnack(connack)
        assertTrue(decoded.sessionPresent)
        assertEquals(0, decoded.returnCode)

        val subscribe = Mqtt.subscribe(7, "device/report")
        assertEquals(0x82, subscribe[0].toInt() and 0xFF)
        assertEquals(7, subscribe[3].toInt() and 0xFF)
        assertEquals(0, subscribe[4].toInt() and 0xFF)
    }

    @Test
    fun readsLargePublishWithMultiByteLength() {
        val payload = ByteArray(200_000) { (it and 0xFF).toByte() }
        val encoded = Mqtt.publish("device/report", payload)
        assertTrue(encoded[1].toInt() and 0x80 != 0)
        val packet = read(encoded)
        val decoded = Mqtt.decodePublish(packet)
        assertEquals("device/report", decoded.topic)
        assertContentEquals(payload, decoded.payload)
    }

    @Test
    fun pingreqIsFixedLength() {
        assertContentEquals(byteArrayOf(0xC0.toByte(), 0), Mqtt.pingreq())
        val packet = read(Mqtt.pingreq())
        assertEquals(12, packet.type)
        assertEquals(0, packet.body.size)
        assertEquals(0, packet.flags)
    }

    private fun read(bytes: ByteArray): MqttPacket {
        var at = 0
        return MqttReader(read = { size -> bytes.copyOfRange(at, at + size).also { at += size } }).next()
    }
}
