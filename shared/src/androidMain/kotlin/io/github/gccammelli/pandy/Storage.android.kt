package io.github.gccammelli.pandy

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetSocketAddress
import java.net.Socket
import java.util.Calendar
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

actual fun printerStorage(c: LanConfig): PrinterStorage = FtpsStorage(c)


/** Android's TLS leaves session tickets off; the printer's FTP server resumes sessions with them. */
private fun SSLSocket.tickets() {
    runCatching { javaClass.getMethod("setUseSessionTickets", Boolean::class.javaPrimitiveType).invoke(this, true) }
        .onFailure { android.util.Log.d("PandyFtp", "no setUseSessionTickets: $it") }
}

actual fun inflateRaw(data: ByteArray, size: Int): ByteArray {
    require(size >= 0)
    val inf = java.util.zip.Inflater(true)
    inf.setInput(data)
    val out = ByteArray(size)
    var n = 0
    while (n < size && !inf.finished()) { val r = inf.inflate(out, n, size - n); if (r == 0 && (inf.needsInput() || inf.needsDictionary())) break; n += r }
    val complete = inf.finished()
    inf.end()
    check(complete && n == size) { "Couldn't inflate file" }
    return out
}

actual suspend fun cacheFile(name: String, bytes: ByteArray): String = withContext(Dispatchers.IO) {
    val dir = java.io.File(appContext!!.cacheDir, "printer").apply { mkdirs() }
    val f = java.io.File(dir, name.replace('/', '_'))
    f.writeBytes(bytes)
    android.net.Uri.fromFile(f).toString()
}

/**
 * A small implicit-FTPS client (TLS from the first byte on port 990), just what Pandy needs: LIST, RETR (with a start
 * offset and length), STOR. Data connections reuse the control connection's TLS session: they are wrapped as if to
 * port 990 so the session cache resumes it. One operation at a time per printer.
 */
internal class FtpsStorage(
    private val c: LanConfig,
    private val socketFactory: suspend (String) -> SSLSocketFactory = BambuTls::factory,
    private val controlPort: Int = 990,
) : PrinterStorage {
    private val lock = locks.getOrPut(c.serial) { Mutex() }

    private class Session(val sock: SSLSocket, val factory: SSLSocketFactory, val host: String, val controlPort: Int) {
        val r = BufferedReader(InputStreamReader(sock.inputStream, Charsets.UTF_8))
        val w = sock.outputStream

        /** Reads one reply (multi-line ones too): its code and last line. */
        fun reply(): Pair<Int, String> {
            var line = r.readLine() ?: throw ApiException("The printer closed the file connection")
            val code = line.take(3)
            if (line.getOrNull(3) == '-') while (true) { line = r.readLine() ?: break; if (line.startsWith("$code ")) break }
            return code.toInt() to line
        }

        fun cmd(c: String, ok: IntRange = 200..399): String {
            w.write("$c\r\n".toByteArray()); w.flush()
            val (code, line) = reply()
            if (code !in ok) throw PrinterStorageException(code, line.drop(4).ifBlank { c.substringBefore(' ') + " failed" })
            return line
        }

        /** PASV, REST [rest] if any, then [command]; returns the data connection, TLS resumed from the control connection's session. */
        fun data(command: String, rest: Long = 0): SSLSocket {
            val pasv = cmd("PASV")
            val n = Regex("""(\d+),(\d+),(\d+),(\d+),(\d+),(\d+)""").find(pasv)!!.groupValues.drop(1).map { it.toInt() }
            // Android's TLS finds the session to resume by the socket's own port, so this one reports the control port.
            val plain = object : Socket() { override fun getPort() = controlPort }.apply { connect(InetSocketAddress(host, n[4] * 256 + n[5]), 10_000); soTimeout = 30_000 }
            if (rest > 0) cmd("REST $rest", 300..399)
            cmd(command, 100..199)
            return (factory.createSocket(plain, host, controlPort, true) as SSLSocket).apply { enabledProtocols = arrayOf("TLSv1.2"); tickets(); soTimeout = 30_000; startHandshake() }
        }
    }

    private suspend fun <T> session(block: Session.() -> T): T = lock.withLock {
        withContext(Dispatchers.IO) {
            val f = socketFactory(c.serial)
            // Connected with host and port known, so the session is cached under ip:990 for the data connections to resume.
            // TLS 1.2: its session-ID resumption is what the printer's FTP server checks reliably.
            val sock = (Socket().apply { connect(InetSocketAddress(c.ip, controlPort), 10_000) }.let { f.createSocket(it, c.ip, controlPort, true) } as SSLSocket).apply { enabledProtocols = arrayOf("TLSv1.2"); tickets(); soTimeout = 30_000; startHandshake() }
            sock.use {
                val s = Session(sock, f, c.ip, controlPort)
                s.reply()
                s.cmd("USER bblp", 300..399)
                s.cmd("PASS ${c.code}", 200..299)
                s.cmd("PBSZ 0"); s.cmd("PROT P"); s.cmd("TYPE I")
                try { s.block() } finally { runCatching { s.w.write("QUIT\r\n".toByteArray()); s.w.flush() } }
            }
        }
    }

    override suspend fun list(dir: String): List<FileEntry> = session {
        val d = data("LIST $dir")
        val lines = d.use { it.inputStream.bufferedReader().readLines() }
        val (code, line) = reply()
        if (code !in 200..299) throw PrinterStorageException(code, line.drop(4))
        val now = Calendar.getInstance()
        parseList(lines, now.get(Calendar.YEAR), now.get(Calendar.MONTH) + 1)
    }

    override suspend fun read(path: String, offset: Long, length: Int): ByteArray = session {
        val d = data("RETR $path", offset)
        val out = java.io.ByteArrayOutputStream(if (length > 0) length else 64 * 1024)
        d.use { s ->
            val input = s.inputStream
            val buf = ByteArray(64 * 1024)
            while (length < 0 || out.size() < length) {
                val want = if (length < 0) buf.size else minOf(buf.size, length - out.size())
                val r = input.read(buf, 0, want)
                if (r < 0) break
                out.write(buf, 0, r)
            }
        }
        // Stopping early ends the transfer with an error reply (426); either answer is fine.
        runCatching { reply() }
        out.toByteArray()
    }

    override suspend fun write(path: String, data: ByteArray, progress: (Float) -> Unit) = session {
        val d = data("STOR $path")
        d.use { s ->
            val o = s.outputStream
            var sent = 0
            while (sent < data.size) {
                val n = minOf(256 * 1024, data.size - sent)
                o.write(data, sent, n); sent += n
                progress(sent.toFloat() / data.size)
            }
            o.flush()
        }
        val (code, line) = reply()
        if (code !in 200..299) throw ApiException("Printer storage: ${line.drop(4)}")
    }

    companion object { private val locks = mutableMapOf<String, Mutex>() }
}
