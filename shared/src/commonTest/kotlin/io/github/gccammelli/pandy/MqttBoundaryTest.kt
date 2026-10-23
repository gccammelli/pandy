package io.github.gccammelli.pandy

import kotlin.test.*

class MqttBoundaryTest {
    private fun read(bytes: ByteArray,maximum: Int=1024): MqttPacket {
        var offset=0
        return MqttReader(maxPacketSize=maximum,read={ count ->
            bytes.copyOfRange(offset,minOf(offset+count,bytes.size)).also { offset+=it.size }
        }).next()
    }

    @Test fun connectionAndSubscriptionRejectValuesOutsideTheirWireRanges() {
        for (keepAlive in listOf(-1,65_536)) assertFailsWith<IllegalArgumentException> { Mqtt.connect("client","user","code",keepAlive) }
        for (id in listOf(0,65_536)) assertFailsWith<IllegalArgumentException> { Mqtt.subscribe(id,"topic") }
        assertFailsWith<IllegalArgumentException> { Mqtt.publish("x".repeat(65_536),"data") }
        assertEquals(3,read(Mqtt.publish("topic","message")).type)
        assertEquals("message",Mqtt.decodePublish(read(Mqtt.publish("topic","message"))).payload.decodeToString())
        assertEquals(0, Mqtt.decodeConnack(MqttPacket(2,0,byteArrayOf(0,0))).returnCode)
        assertFalse(Mqtt.decodeConnack(MqttPacket(2,0,byteArrayOf(0,5))).sessionPresent)
        assertEquals(5,Mqtt.decodeConnack(MqttPacket(2,0,byteArrayOf(0,5))).returnCode)
    }

    @Test fun malformedAcknowledgementsAndPublishHeadersFailBeforeReadingPayloads() {
        for (packet in listOf(MqttPacket(3,0,byteArrayOf(0,0)),MqttPacket(2,0,byteArrayOf(0)))) {
            assertFailsWith<IllegalArgumentException> { Mqtt.decodeConnack(packet) }
        }
        for (packet in listOf(MqttPacket(2,0,byteArrayOf(0,0)),MqttPacket(3,0,byteArrayOf(0)),
            MqttPacket(3,0,byteArrayOf(0,2,65)),MqttPacket(3,2,byteArrayOf(0,1,65,0)))) {
            assertFailsWith<IllegalArgumentException> { Mqtt.decodePublish(packet) }
        }
        assertFailsWith<IllegalStateException> { Mqtt.decodePublish(MqttPacket(3,6,byteArrayOf(0,1,65))) }
    }

    @Test fun qosPacketIdentifiersAreNotIncludedInThePublishedPayload() {
        for (qos in listOf(1,2)) {
            val publication=Mqtt.decodePublish(MqttPacket(3,qos shl 1,byteArrayOf(0,1,65,0,7,9,8)))
            assertEquals("A",publication.topic); assertContentEquals(byteArrayOf(9,8),publication.payload)
        }
    }

    @Test fun truncatedOversizeAndOverlongPacketsAreRejectedWithoutUnboundedReads() {
        for (bytes in listOf(byteArrayOf(),byteArrayOf(0x30),byteArrayOf(0x30,3,1),
            byteArrayOf(0x30,0x80.toByte(),0x80.toByte(),0x80.toByte(),0x80.toByte(),0))) {
            assertFailsWith<IllegalArgumentException> { read(bytes) }
        }
        assertFailsWith<IllegalArgumentException> { read(byteArrayOf(0x30,10),maximum=9) }
    }
}
