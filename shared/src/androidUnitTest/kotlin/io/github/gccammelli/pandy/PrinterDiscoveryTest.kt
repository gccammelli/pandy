package io.github.gccammelli.pandy

import androidx.test.core.app.ApplicationProvider
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.MulticastSocket
import kotlinx.coroutines.runBlocking
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.*

@RunWith(RobolectricTestRunner::class)
class PrinterDiscoveryTest {
    @Test fun discoveryFiltersOtherServicesAndDeduplicatesPrinterAnnouncements() = runBlocking<Unit> {
        val receiver = MulticastSocket(0)
        val previousContext = appContext
        appContext = ApplicationProvider.getApplicationContext()
        try {
            DatagramSocket().use { sender ->
                fun send(message: String) {
                    val bytes = message.toByteArray()
                    sender.send(DatagramPacket(bytes,bytes.size,InetAddress.getLoopbackAddress(),receiver.localPort))
                }
                send("NOTIFY * HTTP/1.1\r\nUSN: ignored-service\r\nNT: unrelated\r\n")
                send("NOTIFY * HTTP/1.1\r\nNT: urn:bambulab-com:device:3dprinter:1\r\n")
                send("NOTIFY * HTTP/1.1\r\nUSN: TEST123\r\nNT: urn:bambulab-com:device:3dprinter:1\r\nLocation: 192.0.2.1\r\nDevModel.bambu.com: BL-P001\r\nDevName.bambu.com: First name\r\n")
                send("NOTIFY * HTTP/1.1\r\nUSN: TEST123\r\nST: urn:bambulab-com:device:3dprinter:1\r\nLocation: 192.0.2.1\r\nDevModel.bambu.com: BL-P001\r\nDevName.bambu.com: Updated name\r\n")
                send("NOTIFY * HTTP/1.1\r\nUSN: FALLBACK\r\nNT: urn:bambulab-com:device:3dprinter:1\r\n")
                send("NOTIFY * HTTP/1.1\r\nUSN: BY-ST\r\nNT: unrelated\r\nST: urn:bambulab-com:device:3dprinter:1\r\nLocation: 192.0.2.7\r\n")
            }
            val found = receivePrinterAnnouncements(100) { receiver }.associateBy { it.serial }
            assertEquals(setOf("TEST123","FALLBACK","BY-ST"),found.keys)
            assertEquals("Updated name",found.getValue("TEST123").name)
            assertEquals("192.0.2.1",found.getValue("TEST123").ip)
            assertEquals("127.0.0.1",found.getValue("FALLBACK").ip)
            assertEquals("FALLBACK",found.getValue("FALLBACK").name)
            assertTrue(receiver.isClosed)
        } finally { receiver.close(); appContext=previousContext }
    }
}
