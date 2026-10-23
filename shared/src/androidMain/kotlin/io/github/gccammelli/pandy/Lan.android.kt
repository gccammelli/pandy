package io.github.gccammelli.pandy

import io.github.gccammelli.pandy.res.Res
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken
import org.eclipse.paho.client.mqttv3.MqttCallback
import org.eclipse.paho.client.mqttv3.MqttClient
import org.eclipse.paho.client.mqttv3.MqttConnectOptions
import org.eclipse.paho.client.mqttv3.MqttMessage
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence
import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.net.DatagramPacket
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.KeyStore
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager
import kotlin.coroutines.resume

/**
 * TLS to a Bambu printer: its certificate must chain to one of Bambu's device CAs (bundled, from the Home Assistant
 * integration) and name the printer's serial. The address isn't checked: printers are reached by IP.
 */
object BambuTls {
    private val CERTS = listOf("bambu.cert", "bambu_h2c_251122.cert", "bambu_p2s_250626.cert", "bambu_x2c_260425.cert")
    private var trust: X509TrustManager? = null
    private val contexts = mutableMapOf<String, SSLSocketFactory>()
    val sslContexts = mutableMapOf<String, SSLContext>()

    /**
     * One context per printer, kept: its session cache lets FTP data connections resume the control connection's TLS
     * session, which the printer requires.
     */
    suspend fun factory(serial: String): SSLSocketFactory = contexts[serial] ?: newFactory(serial).also { contexts[serial] = it }

    private suspend fun newFactory(serial: String): SSLSocketFactory {
        val base = trust ?: run {
            val ks = KeyStore.getInstance(KeyStore.getDefaultType()).apply { load(null) }
            val cf = CertificateFactory.getInstance("X.509")
            CERTS.forEach { n -> ks.setCertificateEntry(n, cf.generateCertificate(ByteArrayInputStream(Res.readBytes("files/certs/$n")))) }
            val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(ks) }
            (tmf.trustManagers.first { it is X509TrustManager } as X509TrustManager).also { trust = it }
        }
        val tm = serialTrustManager(base, serial)
        return SSLContext.getInstance("TLS").apply { init(null, arrayOf(tm), null) }.also { sslContexts[serial] = it }.socketFactory
    }
}

internal fun serialTrustManager(base: X509TrustManager, serial: String): X509TrustManager = object : X509TrustManager {
    override fun getAcceptedIssuers(): Array<X509Certificate> = base.acceptedIssuers
    override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) = base.checkClientTrusted(chain, authType)
    override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
        base.checkServerTrusted(chain, authType)
        val cn = Regex("""CN=([^,]+)""").find(chain[0].subjectX500Principal.name)?.groupValues?.get(1)
        if (!cn.equals(serial, ignoreCase = true)) throw CertificateException("This printer's certificate is for $cn, not $serial")
    }
}


actual fun lanLink(c: LanConfig): LanLink = PahoLink(c)

internal class PahoLink(
    private val c: LanConfig,
    private val socketFactory: suspend (String) -> SSLSocketFactory = BambuTls::factory,
    private val port: Int = 8883,
) : LanLink {
    private var client: MqttClient? = null

    override suspend fun run(onConnected: () -> Unit, onMessage: (String) -> Unit) {
        val factory = socketFactory(c.serial)
        withContext(Dispatchers.IO) {
            val cl = MqttClient("ssl://${c.ip}:$port", "pandy-${System.currentTimeMillis()}", MemoryPersistence())
            client = cl
            suspendCancellableCoroutine { cont ->
                cl.setCallback(object : MqttCallback {
                    override fun connectionLost(cause: Throwable?) { if (cont.isActive) cont.resume(Unit) }
                    override fun messageArrived(topic: String?, message: MqttMessage) { onMessage(String(message.payload)) }
                    override fun deliveryComplete(token: IMqttDeliveryToken?) {}
                })
                try {
                    cl.connect(MqttConnectOptions().apply {
                        userName = "bblp"; password = c.code.toCharArray()
                        socketFactory = factory
                        isHttpsHostnameVerificationEnabled = false
                        isCleanSession = true; keepAliveInterval = 30; connectionTimeout = 10
                        isAutomaticReconnect = false
                    })
                    cl.subscribe("device/${c.serial}/report", 0)
                    onConnected()
                } catch (e: Exception) {
                    cont.resumeWith(Result.failure(ApiException(when ((e as? org.eclipse.paho.client.mqttv3.MqttException)?.reasonCode) {
                        4, 5 -> "The printer refused the access code"
                        else -> e.cause?.message ?: e.message ?: "Couldn't connect to the printer"
                    })))
                }
                cont.invokeOnCancellation { runCatching { cl.disconnectForcibly(500, 500) } }
            }
        }
    }

    override fun publish(payload: String) {
        val cl = client?.takeIf { it.isConnected } ?: throw ApiException("Not connected to the printer")
        cl.publish("device/${c.serial}/request", MqttMessage(payload.toByteArray()).apply { qos = 0 })
    }

    override fun close() { runCatching { client?.disconnectForcibly(200, 200); client?.close() } }
}

/** P1/A1 camera: TLS on port 6000, an 80-byte login, then frames of a 16-byte header (size, little-endian) and a JPEG. */
actual suspend fun lanJpegStream(c: LanConfig, onFrame: (ByteArray) -> Unit) {
    val factory = BambuTls.factory(c.serial)
    readLanJpegStream(c, factory, 6000, onFrame)
}

internal suspend fun readLanJpegStream(c: LanConfig, factory: SSLSocketFactory, port: Int, onFrame: (ByteArray) -> Unit) {
    withContext(Dispatchers.IO) {
        val sock = factory.createSocket() as SSLSocket
        try {
            sock.connect(InetSocketAddress(c.ip, port), 10_000)
            sock.soTimeout = 15_000
            val auth = ByteBuffer.allocate(80).order(ByteOrder.LITTLE_ENDIAN).putInt(0x40).putInt(0x3000).putInt(0).putInt(0)
            auth.put("bblp".toByteArray().copyOf(32)).put(c.code.toByteArray().copyOf(32))
            sock.outputStream.write(auth.array()); sock.outputStream.flush()
            val input = DataInputStream(sock.inputStream)
            val header = ByteArray(16)
            while (true) {
                currentCoroutineContext().ensureActive()
                runInterruptible { input.readFully(header) }
                val size = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN).int
                if (size <= 0 || size > 5_000_000) throw ApiException("The camera sent something unexpected")
                val img = ByteArray(size)
                runInterruptible { input.readFully(img) }
                onFrame(img)
            }
        } finally { runCatching { sock.close() } }
    }
}

/** Bambu printers announce themselves every few seconds (SSDP NOTIFY to 239.255.255.250 on port 2021). */
actual suspend fun discoverPrinters(millis: Long): List<FoundPrinter> = receivePrinterAnnouncements(millis) { MulticastSocket(2021) }

internal suspend fun receivePrinterAnnouncements(millis: Long, openSocket: () -> MulticastSocket): List<FoundPrinter> = withContext(Dispatchers.IO) {
    val found = linkedMapOf<String, FoundPrinter>()
    val lock = (appContext?.applicationContext?.getSystemService(android.content.Context.WIFI_SERVICE) as? android.net.wifi.WifiManager)
        ?.createMulticastLock("pandy-discovery")?.apply { setReferenceCounted(false); acquire() }
    try {
        openSocket().use { s ->
            runCatching { s.joinGroup(java.net.InetAddress.getByName("239.255.255.250")) }
            s.soTimeout = 500
            val end = System.currentTimeMillis() + millis
            val buf = ByteArray(2048)
            while (System.currentTimeMillis() < end) {
                currentCoroutineContext().ensureActive()
                val p = DatagramPacket(buf, buf.size)
                try { s.receive(p) } catch (e: java.net.SocketTimeoutException) { continue }
                val h = String(p.data, 0, p.length).lines().mapNotNull { l -> l.split(":", limit = 2).takeIf { it.size == 2 }?.let { it[0].trim().lowercase() to it[1].trim() } }.toMap()
                val serial = h["usn"] ?: continue
                if (h["nt"]?.contains("bambulab") != true && h["st"]?.contains("bambulab") != true) continue
                found[serial] = FoundPrinter(h["location"] ?: p.address.hostAddress ?: continue, serial, modelName(h["devmodel.bambu.com"], serial), h["devname.bambu.com"] ?: serial)
            }
        }
    } finally { runCatching { lock?.release() } }
    found.values.toList()
}

/** Set by the app at start, for the Wi-Fi multicast lock. */
var appContext: android.content.Context? = null
