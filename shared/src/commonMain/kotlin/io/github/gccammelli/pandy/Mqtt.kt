package io.github.gccammelli.pandy

/** The small MQTT 3.1.1 subset used by Bambu's LAN protocol. */
object Mqtt {
    fun connect(clientId: String, username: String, password: String, keepAlive: Int = 30): ByteArray {
        require(keepAlive in 0..0xFFFF)
        val flags = 0xC2 // username, password and clean session; no will.
        val body = mqttString("MQTT") + byteArrayOf(4, flags.toByte(), (keepAlive ushr 8).toByte(), keepAlive.toByte()) +
            mqttString(clientId) + mqttString(username) + mqttString(password)
        return packet(1, 0, body)
    }

    fun subscribe(packetId: Int, topic: String): ByteArray {
        require(packetId in 1..0xFFFF)
        return packet(8, 2, byteArrayOf((packetId ushr 8).toByte(), packetId.toByte()) + mqttString(topic) + byteArrayOf(0))
    }

    fun publish(topic: String, payload: ByteArray): ByteArray = packet(3, 0, mqttString(topic) + payload)

    fun publish(topic: String, payload: String): ByteArray = publish(topic, payload.encodeToByteArray())

    fun pingreq(): ByteArray = byteArrayOf(0xC0.toByte(), 0)

    fun decodeConnack(packet: MqttPacket): MqttConnack {
        require(packet.type == 2 && packet.body.size >= 2) { "Not a CONNACK packet" }
        return MqttConnack((packet.body[0].toInt() and 1) != 0, packet.body[1].toInt() and 0xFF)
    }

    fun decodePublish(packet: MqttPacket): MqttPublish {
        require(packet.type == 3 && packet.body.size >= 2) { "Not a PUBLISH packet" }
        val topicLength = u16(packet.body, 0)
        var at = 2
        require(topicLength <= packet.body.size - at) { "Invalid PUBLISH topic" }
        val topic = packet.body.copyOfRange(at, at + topicLength).decodeToString()
        at += topicLength
        val qos = (packet.flags ushr 1) and 3
        if (qos == 3) error("Invalid PUBLISH QoS")
        if (qos > 0) {
            require(at + 2 <= packet.body.size) { "Invalid PUBLISH packet identifier" }
            at += 2
        }
        return MqttPublish(topic, packet.body.copyOfRange(at, packet.body.size))
    }

    private fun mqttString(value: String): ByteArray {
        val bytes = value.encodeToByteArray()
        require(bytes.size <= 0xFFFF) { "MQTT string is too long" }
        return byteArrayOf((bytes.size ushr 8).toByte(), bytes.size.toByte()) + bytes
    }

    private fun packet(type: Int, flags: Int, body: ByteArray): ByteArray {
        require(type in 1..14 && flags in 0..15)
        val length = remainingLength(body.size)
        return byteArrayOf(((type shl 4) or flags).toByte()) + length + body
    }

    private fun remainingLength(value: Int): ByteArray {
        require(value >= 0 && value <= 268_435_455)
        var n = value
        val out = ArrayList<Byte>(4)
        do {
            var digit = n % 128
            n /= 128
            if (n > 0) digit = digit or 0x80
            out += digit.toByte()
        } while (n > 0)
        return out.toByteArray()
    }

    private fun u16(bytes: ByteArray, at: Int) =
        ((bytes[at].toInt() and 0xFF) shl 8) or (bytes[at + 1].toInt() and 0xFF)
}

data class MqttPacket(val type: Int, val flags: Int, val body: ByteArray)

data class MqttConnack(val sessionPresent: Boolean, val returnCode: Int)

data class MqttPublish(val topic: String, val payload: ByteArray)

/** Reads complete MQTT packets from a source that returns exactly the requested number of bytes. */
class MqttReader(private val read: (Int) -> ByteArray, private val maxPacketSize: Int = 64 * 1024 * 1024) {
    fun next(): MqttPacket {
        val first = one()
        var multiplier = 1
        var remaining = 0
        var count = 0
        while (true) {
            require(count++ < 4) { "Malformed MQTT remaining length" }
            val digit = one()
            remaining += (digit and 0x7F) * multiplier
            if (digit and 0x80 == 0) break
            multiplier *= 128
        }
        require(remaining <= maxPacketSize) { "MQTT packet is too large" }
        return MqttPacket(first ushr 4, first and 0x0F, exact(remaining))
    }

    private fun one() = exact(1)[0].toInt() and 0xFF

    private fun exact(size: Int): ByteArray = read(size).also { require(it.size == size) { "MQTT stream ended" } }
}
