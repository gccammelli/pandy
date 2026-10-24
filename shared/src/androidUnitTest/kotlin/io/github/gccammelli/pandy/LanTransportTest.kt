package io.github.gccammelli.pandy

import java.io.DataInputStream
import java.net.InetAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentLinkedQueue
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket
import kotlin.concurrent.thread
import kotlin.test.*
import kotlinx.coroutines.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class LanTransportTest {
    private val config = LanConfig("127.0.0.1", "TEST-PRINTER", "test-access-code", "P1S")
    private class Peer(connections: Int = 1, block: (SSLSocket) -> Unit) : AutoCloseable {
        val context = testTlsContext()
        private val listener = (context.serverSocketFactory.createServerSocket(0, 1, InetAddress.getLoopbackAddress()) as SSLServerSocket).apply { soTimeout = 5000 }
        val port get() = listener.localPort
        private val failures = ConcurrentLinkedQueue<Throwable>()
        private val worker = thread(isDaemon = true, name = "test-printer-tls") {
            try { repeat(connections) { (listener.accept() as SSLSocket).use { it.soTimeout = 5000; block(it) } } }
            catch (error: Throwable) { failures.add(error) }
        }
        override fun close() {
            listener.close(); worker.join(6000)
            check(!worker.isAlive) { "Test peer did not stop" }
            failures.firstOrNull()?.let { throw AssertionError("Test peer failed", it) }
        }
    }

    private fun DataInputStream.packet(): Pair<Int, ByteArray> {
        val type = readUnsignedByte()
        var length = 0; var multiplier = 1
        do { val b = readUnsignedByte(); length += (b and 127) * multiplier; multiplier *= 128 } while (b and 128 != 0)
        return type to ByteArray(length).also { readFully(it) }
    }

    @Test fun mqttAuthenticatesSubscribesReceivesAndPublishesWithoutAPrinter() = runBlocking<Unit> {
        val published = CompletableDeferred<String>()
        val peer = Peer { socket ->
            val input = DataInputStream(socket.inputStream)
            val output = socket.outputStream
            val connect = input.packet()
            assertEquals(0x10, connect.first)
            assertTrue(String(connect.second).contains("bblp"))
            assertTrue(String(connect.second).contains(config.code))
            output.write(byteArrayOf(0x20, 2, 0, 0)); output.flush()
            val subscribe = input.packet()
            assertEquals(0x82, subscribe.first)
            assertTrue(String(subscribe.second).contains("device/${config.serial}/report"))
            output.write(byteArrayOf(0x90.toByte(), 3, subscribe.second[0], subscribe.second[1], 0)); output.flush()
            val topic = "device/${config.serial}/report".toByteArray()
            val message = "{\"print\":{\"gcode_state\":\"IDLE\"}}".toByteArray()
            val bytes = byteArrayOf((topic.size shr 8).toByte(), topic.size.toByte()) + topic + message
            output.write(byteArrayOf(0x30, bytes.size.toByte()) + bytes); output.flush()
            val publish = input.packet()
            assertEquals(0x30, publish.first)
            val topicSize = ((publish.second[0].toInt() and 255) shl 8) + (publish.second[1].toInt() and 255)
            assertEquals("device/${config.serial}/request", String(publish.second, 2, topicSize))
            published.complete(String(publish.second, 2 + topicSize, publish.second.size - 2 - topicSize))
        }
        peer.use {
            val link = PahoLink(config, { peer.context.socketFactory }, peer.port)
            val connected = CompletableDeferred<Unit>(); val received = CompletableDeferred<String>()
            val run = launch(Dispatchers.Default) { link.run({ connected.complete(Unit) }, { received.complete(it) }) }
            try {
                withTimeout(5000) { connected.await() }
                assertTrue(withTimeout(5000) { received.await() }.contains("IDLE"))
                link.publish("{\"test\":true}")
                assertEquals("{\"test\":true}", withTimeout(5000) { published.await() })
                withTimeout(5000) { run.join() } // Peer disconnect is delivered through connectionLost.
                assertFailsWith<ApiException> { link.publish("disconnected") }
            } finally { run.cancelAndJoin(); link.close() }
        }
    }

    @Test fun closingAnActiveMqttConnectionDisconnectsAndStopsFurtherPublishing() = runBlocking<Unit> {
        val disconnected = CompletableDeferred<Unit>()
        Peer { socket ->
            val input = DataInputStream(socket.inputStream)
            val output = socket.outputStream
            assertEquals(0x10, input.packet().first)
            output.write(byteArrayOf(0x20, 2, 0, 0)); output.flush()
            val subscription = input.packet()
            output.write(byteArrayOf(0x90.toByte(), 3, subscription.second[0], subscription.second[1], 0)); output.flush()
            val goodbye = input.packet()
            assertEquals(0xe0, goodbye.first)
            assertTrue(goodbye.second.isEmpty())
            disconnected.complete(Unit)
        }.use { peer ->
            val link = PahoLink(config, { peer.context.socketFactory }, peer.port)
            val connected = CompletableDeferred<Unit>()
            val job = launch(Dispatchers.Default) { link.run({ connected.complete(Unit) }, {}) }
            try {
                withTimeout(5000) { connected.await() }
                withContext(Dispatchers.IO) { link.close() }
                withTimeout(5000) { disconnected.await() }
                assertFailsWith<ApiException> { link.publish("after closing") }
            } finally { job.cancelAndJoin(); link.close() }
        }
    }

    @Test fun mqttRejectedAccessCodeHasAnActionableError() = runBlocking<Unit> {
        Peer(connections = 2) { socket ->
            DataInputStream(socket.inputStream).packet()
            socket.outputStream.write(byteArrayOf(0x20, 2, 0, 5)); socket.outputStream.flush()
        }.use { peer ->
            val link = PahoLink(config, { peer.context.socketFactory }, peer.port)
            try {
                val error = assertFailsWith<ApiException> { withTimeout(5000) { link.run({}, {}) } }
                assertEquals("The printer refused the access code", error.message)
            } finally { link.close() }
        }
    }

    @Test fun cameraReadsLittleEndianFramesAndRejectsInvalidSizes() = runBlocking<Unit> {
        val frames = mutableListOf<ByteArray>()
        val image = byteArrayOf(0xff.toByte(), 0xd8.toByte(), 1, 2, 0xff.toByte(), 0xd9.toByte())
        Peer { socket ->
            val auth = ByteArray(80); DataInputStream(socket.inputStream).readFully(auth)
            assertEquals(0x40, ByteBuffer.wrap(auth).order(ByteOrder.LITTLE_ENDIAN).int)
            assertEquals("bblp", String(auth.copyOfRange(16, 48)).trimEnd('\u0000'))
            assertEquals(config.code, String(auth.copyOfRange(48, 80)).trimEnd('\u0000'))
            val output = socket.outputStream
            output.write(ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN).putInt(image.size).array())
            output.write(image)
            output.write(ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN).putInt(5_000_001).array())
            output.flush()
        }.use { peer ->
            val error = assertFailsWith<ApiException> {
                readLanJpegStream(config, peer.context.socketFactory, peer.port) { frames += it }
            }
            assertEquals("The camera sent something unexpected", error.message)
            assertContentEquals(image, frames.single())
        }
    }

    @Test fun otherMqttRefusalsAndUnreachablePrintersAreDescribedWithoutLeakingDetails() = runBlocking<Unit> {
        Peer(connections = 2) { socket ->
            DataInputStream(socket.inputStream).packet()
            socket.outputStream.write(byteArrayOf(0x20, 2, 0, 4)); socket.outputStream.flush() // bad user name or password
        }.use { peer ->
            val link = PahoLink(config, { peer.context.socketFactory }, peer.port)
            try {
                assertEquals("The printer refused the access code", assertFailsWith<ApiException> { withTimeout(5000) { link.run({}, {}) } }.message)
            } finally { link.close() }
        }
        val closed = java.net.ServerSocket(0).use { it.localPort } // nothing listens here
        val factory = testTlsContext().socketFactory
        val unreachable = PahoLink(config, { factory }, closed)
        try {
            val message = assertFailsWith<ApiException> { withTimeout(10_000) { unreachable.run({}, {}) } }.message!!
            assertTrue(message.isNotBlank() && !message.contains("access code"))
        } finally { unreachable.close() }
        assertEquals("Not connected to the printer", assertFailsWith<ApiException> { unreachable.publish("{}") }.message)
    }

    @Test fun anEmptyCameraFrameIsRejectedLikeAnOversizedOne() = runBlocking<Unit> {
        Peer { socket ->
            DataInputStream(socket.inputStream).readFully(ByteArray(80))
            socket.outputStream.write(ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN).putInt(0).array()); socket.outputStream.flush()
        }.use { peer ->
            assertEquals("The camera sent something unexpected", assertFailsWith<ApiException> { readLanJpegStream(config, peer.context.socketFactory, peer.port) { } }.message)
        }
    }
}
