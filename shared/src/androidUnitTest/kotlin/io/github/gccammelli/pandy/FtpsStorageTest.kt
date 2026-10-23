package io.github.gccammelli.pandy

import java.net.InetAddress
import java.net.SocketException
import java.security.KeyStore
import java.util.concurrent.ConcurrentLinkedQueue
import javax.net.ssl.*
import kotlin.concurrent.thread
import kotlin.test.*
import kotlinx.coroutines.runBlocking
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** A real, loopback-only TLS control/data peer; no printer or network credentials are used. */
@RunWith(RobolectricTestRunner::class)
class FtpsStorageTest {
    private class Peer(
        val payload: ByteArray = "printer file contents".toByteArray(),
        val completion: Int = 226,
        val passwordAccepted: Boolean = true,
        val rejectTransfer: Boolean = false,
    ) : AutoCloseable {
        val commands = ConcurrentLinkedQueue<String>()
        val failures = ConcurrentLinkedQueue<Throwable>()
        var uploaded = byteArrayOf()
        private val context = testTlsContext()
        private val server = listen()
        val port: Int get() = server.localPort
        val factory: SSLSocketFactory get() = context.socketFactory
        @Volatile private var closing = false
        private fun listen() = (context.serverSocketFactory.createServerSocket(0, 1, InetAddress.getLoopbackAddress()) as SSLServerSocket).apply {
            enabledProtocols = arrayOf("TLSv1.2"); soTimeout = 5000
        }
        private val worker = thread(isDaemon = true, name = "test-ftps-peer") {
            try {
                (server.accept() as SSLSocket).use { socket ->
                    socket.soTimeout = 5000
                    val input = socket.inputStream.bufferedReader()
                    val output = socket.outputStream.bufferedWriter()
                    fun reply(line: String) { output.write("$line\r\n"); output.flush() }
                    var data: SSLServerSocket? = null
                    var offset = 0
                    reply("220-Test file server"); reply("220 Ready")
                    try {
                        while (true) {
                            val command = input.readLine() ?: break
                            commands.add(command)
                            when (command.substringBefore(' ')) {
                                "USER" -> reply("331 Password required")
                                "PASS" -> reply(if (passwordAccepted) "230 Welcome" else "530 Access denied")
                                "PBSZ", "PROT", "TYPE" -> reply("200 OK")
                                "PASV" -> {
                                    data = listen()
                                    val p = data.localPort
                                    reply("227 Entering passive mode (127,0,0,1,${p / 256},${p % 256})")
                                }
                                "REST" -> { offset = command.substringAfter(' ').toInt(); reply("350 Restart accepted") }
                                "LIST", "RETR", "STOR" -> {
                                    if (rejectTransfer) { reply("550 File unavailable"); continue }
                                    reply("150 Opening protected data connection")
                                    (data!!.accept() as SSLSocket).use { transfer ->
                                        transfer.soTimeout = 5000
                                        when (command.substringBefore(' ')) {
                                            "LIST" -> transfer.outputStream.write((
                                                "drwxr-xr-x 1 printer printer 0 Jan 01 2025 cache\r\n" +
                                                "-rw-r--r-- 1 printer printer 42 Jan 02 2025 model.3mf\r\n" +
                                                "invalid listing line\r\n").toByteArray())
                                            "RETR" -> transfer.outputStream.write(payload.copyOfRange(offset, payload.size))
                                            "STOR" -> uploaded = transfer.inputStream.readBytes()
                                        }
                                    }
                                    data.close(); data = null
                                    reply("$completion Transfer finished")
                                }
                                "QUIT" -> break
                                else -> error("Unexpected command: $command")
                            }
                        }
                    } finally { data?.close() }
                }
            } catch (error: Throwable) {
                if (!(closing && error is SocketException)) failures.add(error)
            }
        }
        fun storage() = FtpsStorage(LanConfig("127.0.0.1", "TEST-$port", "test-access-code", "X1C"), { factory }, port)
        override fun close() {
            closing = true; server.close(); worker.join(6000)
            check(!worker.isAlive) { "FTPS test peer did not stop" }
            failures.firstOrNull()?.let { throw AssertionError("FTPS test peer failed", it) }
        }
    }

    @Test fun listsFilesUsingProtectedPassiveDataAndMultilineGreeting() = runBlocking {
        Peer().use { peer ->
            val files = peer.storage().list("/")
            assertEquals(listOf("cache", "model.3mf"), files.map { it.name })
            assertTrue(files[0].dir); assertEquals(42L, files[1].size)
            assertTrue(peer.commands.containsAll(listOf("USER bblp", "PASS test-access-code", "PBSZ 0", "PROT P", "TYPE I", "LIST /")))
        }
    }

    @Test fun readsFullFileAndPartialRangeAcceptingEarlyTransferCompletion() = runBlocking {
        Peer().use { peer -> assertContentEquals(peer.payload, peer.storage().read("/model.3mf", 0, -1)) }
        Peer(completion = 426).use { peer ->
            assertContentEquals(peer.payload.copyOfRange(3, 8), peer.storage().read("/model.3mf", 3, 5))
            assertTrue(peer.commands.contains("REST 3"))
        }
        Peer().use { peer -> assertContentEquals(byteArrayOf(), peer.storage().read("/empty", 0, 0)) }
    }

    @Test fun uploadsInChunksReportingProgressAndChecksFinalReply() = runBlocking {
        val payload = ByteArray(600_000) { (it % 251).toByte() }
        Peer().use { peer ->
            val progress = mutableListOf<Float>()
            peer.storage().write("/new.3mf", payload) { progress += it }
            assertContentEquals(payload, peer.uploaded)
            assertEquals(3, progress.size); assertEquals(1f, progress.last())
            assertTrue(progress.zipWithNext().all { (a, b) -> a < b })
        }
        Peer(completion = 451).use { peer ->
            val error = assertFailsWith<ApiException> { peer.storage().write("/full", byteArrayOf(1)) {} }
            assertTrue(error.message!!.contains("Transfer finished"))
        }
    }

    @Test fun authenticationListingAndTransferFailuresAreReported() = runBlocking {
        Peer(passwordAccepted = false).use { peer ->
            assertEquals(530, assertFailsWith<PrinterStorageException> { peer.storage().list("/") }.code)
            assertFalse(peer.commands.contains("PASV"))
        }
        Peer(completion = 451).use { peer ->
            assertEquals(451, assertFailsWith<PrinterStorageException> { peer.storage().list("/") }.code)
        }
        Peer(rejectTransfer = true).use { peer ->
            assertEquals(550, assertFailsWith<PrinterStorageException> { peer.storage().read("/missing", 0, -1) }.code)
        }
    }
}

// Generate a disposable identity instead of publishing a private key, even a test key.
private val loopbackIdentity: KeyStore by lazy {
    val directory = java.nio.file.Files.createTempDirectory("pandy-tls-")
    val path = directory.resolve("identity.p12")
    try {
        val process = ProcessBuilder(
            java.io.File(System.getProperty("java.home"), "bin/keytool").path,
            "-genkeypair", "-alias", "test-peer", "-keyalg", "RSA", "-keysize", "2048",
            "-dname", "CN=pandy-test", "-validity", "365", "-storetype", "PKCS12",
            "-keystore", path.toString(), "-storepass", "test-only", "-keypass", "test-only", "-noprompt",
        ).redirectErrorStream(true).start()
        check(process.waitFor(30, java.util.concurrent.TimeUnit.SECONDS)) {
            process.destroyForcibly()
            "Timed out creating the loopback TLS identity"
        }
        check(process.exitValue() == 0) { process.inputStream.bufferedReader().readText() }
        KeyStore.getInstance("PKCS12").apply {
            java.nio.file.Files.newInputStream(path).use { load(it, "test-only".toCharArray()) }
        }
    } finally {
        java.nio.file.Files.deleteIfExists(path)
        java.nio.file.Files.deleteIfExists(directory)
    }
}

internal fun testKeyStore(): KeyStore = loopbackIdentity

internal fun testTlsContext(): SSLContext = SSLContext.getInstance("TLSv1.2").apply {
    val store = testKeyStore()
    val keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(store, "test-only".toCharArray()) }
    val trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(store) }
    init(keys.keyManagers, trust.trustManagers, null)
}
