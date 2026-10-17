@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlin.io.encoding.ExperimentalEncodingApi::class)

package io.github.gccammelli.pandy

import io.github.gccammelli.pandy.res.Res
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.CPointerVar
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.ULongVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.asStableRef
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.pointed
import kotlinx.cinterop.ptr
import kotlinx.cinterop.readValue
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.sizeOf
import kotlinx.cinterop.staticCFunction
import kotlinx.cinterop.StableRef
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import platform.CoreFoundation.CFArrayAppendValue
import platform.CoreFoundation.CFArrayCreateMutable
import platform.CoreFoundation.CFRelease
import platform.CoreFoundation.CFStringGetCString
import platform.CoreFoundation.CFStringRefVar
import platform.CoreFoundation.kCFStringEncodingUTF8
import platform.CoreFoundation.kCFTypeArrayCallBacks
import platform.Foundation.NSLock
import platform.Foundation.timeIntervalSince1970
import platform.Security.SSLConnectionType
import platform.Security.SSLCreateContext
import platform.Security.SSLContextRef
import platform.Security.SSLClose
import platform.Security.SSLHandshake
import platform.Security.SSLRead
import platform.Security.SSLSetConnection
import platform.Security.SSLSetIOFuncs
import platform.Security.SSLSetPeerID
import platform.Security.SSLSetProtocolVersionMax
import platform.Security.SSLSetProtocolVersionMin
import platform.Security.SSLSetSessionOption
import platform.Security.SSLWrite
import platform.Security.SecCertificateCopyCommonName
import platform.Security.SecCertificateCreateWithData
import platform.Security.SecCertificateRef
import platform.Security.SecTrustEvaluate
import platform.Security.SecTrustGetCertificateAtIndex
import platform.Security.SecTrustRefVar
import platform.Security.SecTrustSetAnchorCertificates
import platform.Security.SecTrustSetAnchorCertificatesOnly
import platform.Security.SecTrustSetNetworkFetchAllowed
import platform.Security.SSLCopyPeerTrust
import platform.Security.errSSLClosedGraceful
import platform.Security.errSSLClosedNoNotify
import platform.Security.errSSLServerAuthCompleted
import platform.Security.errSSLWouldBlock
import platform.Security.errSecSuccess
import platform.Security.kSSLSessionOptionBreakOnServerAuth
import platform.Security.kTLSProtocol12
import platform.Security.kSecTrustResultProceed
import platform.Security.kSecTrustResultUnspecified
import platform.Security.SSLProtocolSide
import platform.CoreFoundation.CFDataCreate
import platform.darwin.inet_addr
import platform.posix.AF_UNSPEC
import platform.posix.AF_INET
import platform.posix.EAGAIN
import platform.posix.EWOULDBLOCK
import platform.posix.IPPROTO_IP
import platform.posix.IP_ADD_MEMBERSHIP
import platform.posix.INADDR_ANY
import platform.posix.POLLIN
import platform.posix.SO_RCVTIMEO
import platform.posix.SO_REUSEADDR
import platform.posix.SO_SNDTIMEO
import platform.posix.SOCK_DGRAM
import platform.posix.SOCK_STREAM
import platform.posix.SOL_SOCKET
import platform.posix.SHUT_RDWR
import platform.posix.addrinfo
import platform.posix.bind
import platform.posix.close
import platform.posix.connect
import platform.posix.freeaddrinfo
import platform.posix.getaddrinfo
import platform.posix.ip_mreq
import platform.posix.poll
import platform.posix.pollfd
import platform.posix.recv
import platform.posix.recvfrom
import platform.posix.send
import platform.posix.setsockopt
import platform.posix.shutdown
import platform.posix.sockaddr
import platform.posix.sockaddr_in
import platform.posix.socket
import platform.posix.timeval
import kotlinx.cinterop.UIntVar

private val TLS_READ = staticCFunction(::tlsRead)
private val TLS_WRITE = staticCFunction(::tlsWrite)

private val CERTS = listOf("bambu.cert", "bambu_h2c_251122.cert", "bambu_p2s_250626.cert", "bambu_x2c_260425.cert")

/** SecureTransport's peer ID is the session-cache key; sharing it makes FTPS data TLS resume the control session. */
internal suspend fun openTls(c: LanConfig, port: Int, peerId: String = "pandy-${c.serial}-${c.ip}:$port"): IosTlsSocket =
    IosTlsSocket.open(c.ip, port, c.serial, peerId)

private suspend fun trustAnchors(): List<SecCertificateRef> = CERTS.map { name ->
    val pem = Res.readBytes("files/certs/$name").decodeToString()
    val der = kotlin.io.encoding.Base64.Default.decode(
        pem.substringAfter("-----BEGIN CERTIFICATE-----").substringBefore("-----END CERTIFICATE-----").filterNot(Char::isWhitespace)
    )
    memScoped {
        val data = der.usePinned { CFDataCreate(null, it.addressOf(0).reinterpret(), der.size.toLong()) }
        checkNotNull(data)
        SecCertificateCreateWithData(null, data).also { CFRelease(data) }
    } ?: error("Couldn't load $name")
}

internal class IosTlsSocket private constructor(private val fd: Int, private val ssl: SSLContextRef) {
    private val ref = StableRef.create(this)
    private var closed = false

    init {
        check(SSLSetIOFuncs(ssl, TLS_READ, TLS_WRITE) == errSecSuccess) { "Couldn't configure TLS I/O" }
        check(SSLSetConnection(ssl, ref.asCPointer()) == errSecSuccess) { "Couldn't configure TLS socket" }
    }

    companion object {
        suspend fun open(host: String, port: Int, serial: String, peerId: String): IosTlsSocket {
            return fromFd(tcpConnect(host, port), serial, peerId)
        }

        internal suspend fun fromFd(fd: Int, serial: String, peerId: String): IosTlsSocket {
            var socket: IosTlsSocket? = null
            return try {
                val ssl = checkNotNull(SSLCreateContext(null, SSLProtocolSide.kSSLClientSide, SSLConnectionType.kSSLStreamType))
                socket = IosTlsSocket(fd, ssl)
                val peer = peerId.encodeToByteArray()
                peer.usePinned {
                    check(SSLSetPeerID(ssl, it.addressOf(0), peer.size.toULong()) == errSecSuccess) { "Couldn't configure TLS session" }
                }
                check(SSLSetProtocolVersionMin(ssl, kTLSProtocol12) == errSecSuccess) { "Couldn't configure TLS" }
                check(SSLSetProtocolVersionMax(ssl, kTLSProtocol12) == errSecSuccess) { "Couldn't configure TLS" }
                val anchors = trustAnchors()
                try { socket!!.handshake(serial, anchors) } finally { anchors.forEach(::CFRelease) }
                socket!!
            } catch (e: Exception) {
                socket?.close() ?: close(fd)
                throw e
            }
        }
    }

    private fun handshake(serial: String, anchors: List<SecCertificateRef>) {
        check(SSLSetSessionOption(ssl, kSSLSessionOptionBreakOnServerAuth, true) == errSecSuccess) {
            "Couldn't configure printer certificate verification"
        }
        var status = SSLHandshake(ssl)
        var verified = false
        if (status == errSSLWouldBlock) status = SSLHandshake(ssl)
        if (status == errSSLServerAuthCompleted) {
            verifyPeer(serial, anchors)
            verified = true
        }
        else if (status != errSecSuccess) error("TLS handshake failed ($status)")
        if (status == errSSLServerAuthCompleted) {
            check(SSLSetSessionOption(ssl, kSSLSessionOptionBreakOnServerAuth, false) == errSecSuccess) {
                "Couldn't continue TLS handshake"
            }
            status = SSLHandshake(ssl)
        }
        if (status != errSecSuccess) error("TLS handshake failed ($status)")
        if (!verified) verifyPeer(serial, anchors)
    }

    private fun verifyPeer(serial: String, anchors: List<SecCertificateRef>) = memScoped {
        val trustRef = alloc<SecTrustRefVar>()
        check(SSLCopyPeerTrust(ssl, trustRef.ptr) == errSecSuccess && trustRef.value != null) { "Printer sent no certificate" }
        val trust = trustRef.value!!
        try {
            val anchorArray = checkNotNull(CFArrayCreateMutable(null, anchors.size.toLong(), kCFTypeArrayCallBacks.ptr))
            try {
                anchors.forEach { CFArrayAppendValue(anchorArray, it) }
                check(SecTrustSetAnchorCertificates(trust, anchorArray) == errSecSuccess) { "Couldn't configure printer trust" }
                check(SecTrustSetAnchorCertificatesOnly(trust, true) == errSecSuccess) { "Couldn't configure printer trust" }
                check(SecTrustSetNetworkFetchAllowed(trust, false) == errSecSuccess) { "Couldn't configure printer trust" }
            } finally { CFRelease(anchorArray) }
            val result = alloc<UIntVar>()
            check(SecTrustEvaluate(trust, result.ptr) == errSecSuccess &&
                (result.value == kSecTrustResultProceed || result.value == kSecTrustResultUnspecified)) { "Printer certificate is not trusted" }
            val leaf = SecTrustGetCertificateAtIndex(trust, 0) ?: error("Printer sent no leaf certificate")
            val commonName = memScoped {
                val cn = alloc<CFStringRefVar>()
                check(SecCertificateCopyCommonName(leaf, cn.ptr) == errSecSuccess && cn.value != null) { "Printer certificate has no common name" }
                val name = cn.value!!
                try {
                    val text = allocArray<ByteVar>(256)
                    check(CFStringGetCString(name, text, 256, kCFStringEncodingUTF8)) { "Couldn't read printer certificate name" }
                    text.toKString()
                } finally { CFRelease(name) }
            }
            check(commonName.equals(serial, ignoreCase = true)) { "Printer certificate is for $commonName, not $serial" }
        } finally { CFRelease(trust) }
    }

    fun read(dst: ByteArray, offset: Int = 0, length: Int = dst.size - offset): Int = memScoped {
        require(offset >= 0 && length >= 0 && offset + length <= dst.size)
        val n = alloc<ULongVar>()
        val status = dst.usePinned { SSLRead(ssl, it.addressOf(offset), length.toULong(), n.ptr) }
        when {
            status == errSecSuccess -> n.value.toInt().takeIf { it > 0 } ?: -1
            status == errSSLClosedGraceful || status == errSSLClosedNoNotify -> -1
            status == errSSLWouldBlock -> throw ApiException("The printer connection timed out")
            else -> throw ApiException("The printer connection failed ($status)")
        }
    }

    fun readFully(dst: ByteArray) {
        var at = 0
        while (at < dst.size) {
            val n = read(dst, at, dst.size - at)
            if (n < 0) throw ApiException("The printer closed the connection")
            at += n
        }
    }

    fun writeFully(bytes: ByteArray) {
        var at = 0
        while (at < bytes.size) {
            val n = write(bytes, at, bytes.size - at)
            if (n <= 0) throw ApiException("The printer closed the connection")
            at += n
        }
    }

    private fun write(bytes: ByteArray, offset: Int, length: Int): Int = memScoped {
        val n = alloc<ULongVar>()
        val status = bytes.usePinned { SSLWrite(ssl, it.addressOf(offset), length.toULong(), n.ptr) }
        when {
            status == errSecSuccess -> n.value.toInt()
            status == errSSLWouldBlock -> throw ApiException("The printer connection timed out")
            else -> throw ApiException("The printer connection failed ($status)")
        }
    }

    fun close() {
        if (closed) return
        closed = true
        runCatching { SSLClose(ssl) }
        shutdown(fd, SHUT_RDWR)
        close(fd)
        ref.dispose()
    }

    internal fun nativeFd() = fd
}

internal fun tcpConnect(host: String, port: Int): Int = memScoped {
    val hints = alloc<addrinfo>().apply { ai_family = AF_UNSPEC; ai_socktype = SOCK_STREAM }
    val result = alloc<CPointerVar<addrinfo>>()
    check(getaddrinfo(host, port.toString(), hints.ptr, result.ptr) == 0) { "Couldn't resolve printer address" }
    var current = result.value
    var fd = -1
    while (current != null) {
        val info = current.pointed
        val candidate = socket(info.ai_family, info.ai_socktype, info.ai_protocol)
        if (candidate >= 0) {
            if (connect(candidate, info.ai_addr, info.ai_addrlen) == 0) {
                setTimeouts(candidate)
                fd = candidate
                break
            }
            close(candidate)
        }
        current = info.ai_next
    }
    freeaddrinfo(result.value)
    check(fd >= 0) { "Couldn't connect to printer" }
    fd
}

private fun setTimeouts(fd: Int) = memScoped {
    val timeout = alloc<timeval>().apply { tv_sec = 30; tv_usec = 0 }
    setsockopt(fd, SOL_SOCKET, SO_RCVTIMEO, timeout.ptr, sizeOf<timeval>().toUInt())
    setsockopt(fd, SOL_SOCKET, SO_SNDTIMEO, timeout.ptr, sizeOf<timeval>().toUInt())
}

private fun tlsRead(connection: CPointer<out kotlinx.cinterop.CPointed>?, data: CPointer<out kotlinx.cinterop.CPointed>?, length: CPointer<ULongVar>?): Int {
    val socket = connection!!.asStableRef<IosTlsSocket>().get()
    val requested = length!!.pointed.value.toInt()
    val n = recv(socket.nativeFd(), data, requested.toULong(), 0)
    return when {
        n > 0L -> { length.pointed.value = n.toULong(); errSecSuccess }
        n == 0L -> { length.pointed.value = 0uL; errSSLClosedGraceful }
        platform.posix.posix_errno() == EAGAIN || platform.posix.posix_errno() == EWOULDBLOCK -> errSSLWouldBlock
        else -> errSSLClosedNoNotify
    }
}

private fun tlsWrite(connection: CPointer<out kotlinx.cinterop.CPointed>?, data: CPointer<out kotlinx.cinterop.CPointed>?, length: CPointer<ULongVar>?): Int {
    val socket = connection!!.asStableRef<IosTlsSocket>().get()
    val requested = length!!.pointed.value.toInt()
    val n = send(socket.nativeFd(), data, requested.toULong(), 0)
    return if (n >= 0) {
        length.pointed.value = n.toULong()
        errSecSuccess
    } else if (platform.posix.posix_errno() == EAGAIN || platform.posix.posix_errno() == EWOULDBLOCK) errSSLWouldBlock else errSSLClosedNoNotify
}

actual fun lanLink(c: LanConfig): LanLink = IosMqttLink(c)

private class IosMqttLink(private val c: LanConfig) : LanLink {
    private var socket: IosTlsSocket? = null
    private val writeLock = NSLock()

    override suspend fun run(onConnected: () -> Unit, onMessage: (String) -> Unit) = withContext(Dispatchers.Default) {
        val s = openTls(c, 8883)
        socket = s
        try {
            send(Mqtt.connect("pandy-${c.serial}", "bblp", c.code))
            val connack = Mqtt.decodeConnack(MqttReader(read = { size -> ByteArray(size).also(s::readFully) }).next())
            when (connack.returnCode) {
                0 -> Unit
                4, 5 -> throw ApiException("The printer refused the access code")
                else -> throw ApiException("The printer refused MQTT connection (${connack.returnCode})")
            }
            send(Mqtt.subscribe(1, "device/${c.serial}/report"))
            onConnected()
            val pinger = launch {
                while (isActive) {
                    delay(20_000)
                    send(Mqtt.pingreq())
                }
            }
            try {
                val reader = MqttReader(read = { size -> ByteArray(size).also(s::readFully) })
                while (currentCoroutineContext().isActive) {
                    val packet = reader.next()
                    if (packet.type == 3) {
                        val message = Mqtt.decodePublish(packet)
                        if (message.topic == "device/${c.serial}/report") onMessage(message.payload.decodeToString())
                    }
                }
            } finally { pinger.cancel() }
        } finally {
            if (socket === s) socket = null
            s.close()
        }
    }

    override fun publish(payload: String) = send(Mqtt.publish("device/${c.serial}/request", payload))

    private fun send(packet: ByteArray) {
        val s = socket ?: throw ApiException("Not connected to the printer")
        writeLock.lock()
        try { s.writeFully(packet) } finally { writeLock.unlock() }
    }

    override fun close() { socket?.close() }
}

actual suspend fun lanJpegStream(c: LanConfig, onFrame: (ByteArray) -> Unit) = withContext(Dispatchers.Default) {
    val socket = openTls(c, 6000)
    try {
        val login = ByteArray(80)
        fun intLE(at: Int, value: Int) {
            login[at] = value.toByte(); login[at + 1] = (value ushr 8).toByte()
            login[at + 2] = (value ushr 16).toByte(); login[at + 3] = (value ushr 24).toByte()
        }
        intLE(0, 0x40); intLE(4, 0x3000)
        "bblp".encodeToByteArray().copyInto(login, 16)
        val code = c.code.encodeToByteArray()
        code.copyInto(login, 48, endIndex = minOf(32, code.size))
        socket.writeFully(login)
        val header = ByteArray(16)
        while (currentCoroutineContext().isActive) {
            socket.readFully(header)
            val size = (header[0].toInt() and 0xFF) or ((header[1].toInt() and 0xFF) shl 8) or
                ((header[2].toInt() and 0xFF) shl 16) or (header[3].toInt() shl 24)
            if (size <= 0 || size > 5_000_000) throw ApiException("The camera sent something unexpected")
            val image = ByteArray(size)
            socket.readFully(image)
            onFrame(image)
        }
    } finally { socket.close() }
}

/** iOS multicast may require an entitlement unavailable to sideloaded apps; the user can enter the printer IP. */
actual suspend fun discoverPrinters(millis: Long): List<FoundPrinter> = withContext(Dispatchers.Default) {
    if (millis <= 0) return@withContext emptyList()
    val found = linkedMapOf<String, FoundPrinter>()
    val fd = socket(AF_INET, SOCK_DGRAM, 0)
    if (fd < 0) return@withContext emptyList()
    try {
        memScoped {
            val reuse = alloc<IntVar>().apply { value = 1 }
            setsockopt(fd, SOL_SOCKET, SO_REUSEADDR, reuse.ptr, sizeOf<IntVar>().toUInt())
            val bindAddress = alloc<sockaddr_in>().apply {
                sin_len = sizeOf<sockaddr_in>().toUByte(); sin_family = AF_INET.toUByte(); sin_port = shortNetwork(2021); sin_addr.s_addr = INADDR_ANY.toUInt()
            }
            if (bind(fd, bindAddress.ptr.reinterpret(), sizeOf<sockaddr_in>().toUInt()) != 0) return@withContext emptyList()
            val membership = alloc<ip_mreq>().apply {
                imr_multiaddr.s_addr = inet_addr("239.255.255.250")
                imr_interface.s_addr = INADDR_ANY.toUInt()
            }
            if (setsockopt(fd, IPPROTO_IP, IP_ADD_MEMBERSHIP, membership.ptr, sizeOf<ip_mreq>().toUInt()) != 0) return@withContext emptyList()
            val end = getTimeMillis() + millis
            val poller = alloc<pollfd>().apply { this.fd = fd; events = POLLIN.toShort() }
            val data = ByteArray(4096)
            while (getTimeMillis() < end) {
                currentCoroutineContext().ensureActive()
                val wait = minOf(500L, end - getTimeMillis()).toInt()
                if (poll(poller.ptr, 1u, wait) <= 0) continue
                val sender = alloc<sockaddr_in>()
                val senderLength = alloc<UIntVar>().apply { value = sizeOf<sockaddr_in>().toUInt() }
                val n = data.usePinned { recvfrom(fd, it.addressOf(0), data.size.toULong(), 0, sender.ptr.reinterpret(), senderLength.ptr) }
                if (n <= 0) continue
                val headers = data.decodeToString(0, n.toInt()).lines().mapNotNull { line ->
                    line.split(":", limit = 2).takeIf { it.size == 2 }?.let { it[0].trim().lowercase() to it[1].trim() }
                }.toMap()
                val serial = headers["usn"] ?: continue
                if (headers["nt"]?.contains("bambulab", true) != true && headers["st"]?.contains("bambulab", true) != true) continue
                val source = platform.darwin.inet_ntoa(sender.sin_addr.readValue())?.toKString()
                val location = headers["location"]?.let { it.substringAfter("://", "").substringBefore('/').substringBefore(':') }
                val ip = location?.takeIf { it.isNotBlank() } ?: source ?: continue
                found[serial] = FoundPrinter(ip, serial, modelName(headers["devmodel.bambu.com"], serial), headers["devname.bambu.com"] ?: serial)
            }
        }
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (_: Exception) {
        return@withContext emptyList()
    } finally { close(fd) }
    found.values.toList()
}

private fun shortNetwork(value: Int) = (((value ushr 8) and 0xFF) or ((value and 0xFF) shl 8)).toUShort()

private fun getTimeMillis() = platform.Foundation.NSDate().timeIntervalSince1970.toLong() * 1000
