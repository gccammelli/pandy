@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class, kotlin.time.ExperimentalTime::class)

package io.github.gccammelli.pandy

import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.datetime.TimeZone
import kotlinx.datetime.number
import kotlinx.datetime.todayIn
import kotlin.time.Clock
import platform.Foundation.NSData
import platform.Foundation.NSCachesDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSUserDomainMask
import platform.Foundation.NSURL
import platform.Foundation.create
import platform.Foundation.writeToFile

actual fun printerStorage(c: LanConfig): PrinterStorage = IosFtpsStorage(c)

actual fun inflateRaw(data: ByteArray, size: Int): ByteArray {
    require(size >= 0)
    if (size == 0) return ByteArray(0)
    val inputBytes = if (data.isEmpty()) ByteArray(1) else data
    return inputBytes.usePinned { input ->
        val output = ByteArray(size)
        output.usePinned { out ->
            kotlinx.cinterop.memScoped {
                val stream = alloc<platform.zlib.z_stream>()
                stream.next_in = input.addressOf(0).reinterpret()
                stream.avail_in = data.size.toUInt()
                stream.next_out = out.addressOf(0).reinterpret()
                stream.avail_out = size.toUInt()
                check(platform.zlib.inflateInit2(stream.ptr, -15) == platform.zlib.Z_OK) { "Couldn't initialize zlib" }
                try {
                    check(platform.zlib.inflate(stream.ptr, platform.zlib.Z_FINISH) == platform.zlib.Z_STREAM_END) { "Couldn't inflate file" }
                } finally { platform.zlib.inflateEnd(stream.ptr) }
            }
        }
        output
    }
}

actual suspend fun cacheFile(name: String, bytes: ByteArray): String = withContext(Dispatchers.Default) {
    val root = NSSearchPathForDirectoriesInDomains(NSCachesDirectory, NSUserDomainMask, true).firstOrNull() as? String
        ?: throw ApiException("The app has no cache directory")
    val dir = "$root/printer"
    check(NSFileManager.defaultManager.createDirectoryAtPath(dir, true, null, null)) { "Couldn't create app cache" }
    val path = "$dir/${name.replace('/', '_')}"
    val source = if (bytes.isEmpty()) ByteArray(1) else bytes
    val data = source.usePinned { NSData.create(bytes = it.addressOf(0), length = bytes.size.toULong()) }
    check(data.writeToFile(path, true)) { "Couldn't write app cache" }
    NSURL.fileURLWithPath(path).absoluteString ?: "file://$path"
}

private class IosFtpsStorage(private val c: LanConfig) : PrinterStorage {
    private val lock = lockFor(c.serial)
    private val peerId = "pandy-ftps-${c.serial}-${c.ip}:990"

    private class Lines(private val socket: IosTlsSocket) {
        private val buffer = ByteArray(4096)
        private var at = 0
        private var end = 0
        private var progress = getTimeMillis()

        private fun byte(): Int {
            while (at == end) {
                val n = socket.read(buffer)
                if (n < 0) return -1
                if (n == 0) {
                    if (getTimeMillis() - progress >= 45_000) throw ApiException("The printer response timed out")
                    continue
                }
                progress = getTimeMillis()
                at = 0; end = n
            }
            return buffer[at++].toInt() and 0xFF
        }

        fun readLine(): String {
            progress = getTimeMillis()
            val out = ArrayList<Byte>()
            while (true) {
                val b = byte()
                if (b < 0) throw ApiException("The printer closed the file connection")
                if (b == '\n'.code) return out.toByteArray().decodeToString().trimEnd('\r')
                if (out.size >= 1_048_576) throw ApiException("The printer sent an unexpectedly long FTP line")
                out += b.toByte()
            }
        }
    }

    private class Session(private val c: LanConfig, val control: IosTlsSocket, private val peerId: String) {
        private val lines = Lines(control)
        private var dataSocket: IosTlsSocket? = null

        fun close() {
            dataSocket?.close()
            control.close()
        }

        fun reply(): Pair<Int, String> {
            var line = lines.readLine()
            val code = line.take(3).toIntOrNull() ?: throw ApiException("Printer storage: invalid FTP reply")
            if (line.getOrNull(3) == '-') while (true) {
                line = lines.readLine()
                if (line.startsWith("$code ")) break
            }
            return code to line
        }

        fun cmd(command: String, ok: IntRange = 200..399): String {
            require('\r' !in command && '\n' !in command) { "Invalid FTP command" }
            control.writeFully("$command\r\n".encodeToByteArray())
            val (code, line) = reply()
            if (code !in ok) throw PrinterStorageException(code, line.drop(4).ifBlank { command.substringBefore(' ') + " failed" })
            return line
        }

        suspend fun data(command: String, rest: Long = 0): IosTlsSocket {
            val pasv = cmd("PASV")
            val values = Regex("""(\d+),(\d+),(\d+),(\d+),(\d+),(\d+)""").find(pasv)?.groupValues?.drop(1)?.map { it.toInt() }
                ?.takeIf { it.size == 6 && it.all { value -> value in 0..255 } }
                ?: throw ApiException("Printer storage: invalid PASV reply")
            val fd = tcpConnect(c.ip, values[4] * 256 + values[5])
            try {
                if (rest > 0) cmd("REST $rest", 300..399)
                cmd(command, 100..199)
            } catch (e: Exception) {
                runCatching { platform.posix.close(fd) }
                throw e
            }
            return IosTlsSocket.fromFd(fd, c.serial, peerId).also { dataSocket = it }
        }
    }

    private suspend fun <T> session(block: suspend Session.() -> T): T = lock.withLock {
        withContext(Dispatchers.Default) {
            val control = openTls(c, 990, peerId)
            try {
                val s = Session(c, control, peerId)
                val cancellation = currentCoroutineContext()[Job]?.invokeOnCompletion { s.close() }
                try {
                    s.reply()
                    s.cmd("USER bblp", 300..399)
                    s.cmd("PASS ${c.code}", 200..299)
                    s.cmd("PBSZ 0")
                    s.cmd("PROT P")
                    s.cmd("TYPE I")
                    try { s.block() } finally { runCatching { s.cmd("QUIT") } }
                } finally { cancellation?.dispose() }
            } finally { control.dispose() }
        }
    }

    override suspend fun list(dir: String): List<FileEntry> = session {
        val data = data("LIST $dir")
        val bytes = try { data.readAll() } finally { data.dispose() }
        val (code, line) = reply()
        if (code !in 200..299) throw PrinterStorageException(code, line.drop(4))
        val now = Clock.System.todayIn(TimeZone.currentSystemDefault())
        parseList(bytes.decodeToString().lineSequence().toList(), now.year, now.month.number)
    }

    override suspend fun read(path: String, offset: Long, length: Int): ByteArray = session {
        val data = data("RETR $path", offset)
        val out = try {
            if (length >= 0) {
                val bytes = ByteArray(length)
                var at = 0
                var progress = getTimeMillis()
                while (at < bytes.size) {
                    val n = data.read(bytes, at, bytes.size - at)
                    if (n < 0) break
                    if (n == 0) {
                        if (getTimeMillis() - progress >= 45_000) throw ApiException("The printer read timed out")
                    } else {
                        progress = getTimeMillis()
                    }
                    at += n
                }
                bytes.copyOf(at)
            } else data.readAll()
        } finally { data.dispose() }
        runCatching { reply() }
        out
    }

    override suspend fun write(path: String, data: ByteArray, progress: (Float) -> Unit) = session {
        val output = data("STOR $path")
        try {
            var sent = 0
            while (sent < data.size) {
                val n = minOf(256 * 1024, data.size - sent)
                output.writeFully(data.copyOfRange(sent, sent + n))
                sent += n
                progress(sent.toFloat() / data.size)
            }
            if (data.isEmpty()) progress(1f)
        } finally { output.dispose() }
        val (code, line) = reply()
        if (code !in 200..299) throw PrinterStorageException(code, line.drop(4))
    }

    override suspend fun delete(path: String) = session { cmd("DELE $path", 200..299); Unit }

    private fun IosTlsSocket.readAll(): ByteArray {
        val out = GrowingBytes()
        val buffer = ByteArray(64 * 1024)
        var progress = getTimeMillis()
        while (true) {
            val n = read(buffer)
            if (n == 0) {
                if (getTimeMillis() - progress >= 45_000) throw ApiException("The printer read timed out")
                continue
            }
            if (n < 0) return out.toByteArray()
            out.append(buffer, n)
            progress = getTimeMillis()
        }
    }

    private class GrowingBytes {
        private var bytes = ByteArray(64 * 1024)
        private var size = 0

        fun append(source: ByteArray, count: Int) {
            if (size + count > bytes.size) bytes = bytes.copyOf(maxOf(bytes.size * 2, size + count))
            source.copyInto(bytes, size, 0, count)
            size += count
        }

        fun toByteArray() = bytes.copyOf(size)
    }

    companion object {
        private val locks = mutableMapOf<String, Mutex>()
        private val locksLock = platform.Foundation.NSLock()

        private fun lockFor(serial: String): Mutex {
            locksLock.lock()
            return try { locks.getOrPut(serial) { Mutex() } } finally { locksLock.unlock() }
        }
    }
}
