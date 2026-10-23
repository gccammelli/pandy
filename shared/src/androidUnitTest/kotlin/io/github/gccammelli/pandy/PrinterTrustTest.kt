package io.github.gccammelli.pandy

import java.security.KeyStore
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager
import kotlin.test.*
import kotlinx.coroutines.runBlocking
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PrinterTrustTest {
    private fun store() = testKeyStore()
    private fun trust(store: KeyStore) = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(store) }
        .trustManagers.filterIsInstance<X509TrustManager>().single()

    @Test fun printerSerialIsCheckedAfterTheCertificateChain() {
        val store = store()
        val chain = arrayOf(store.getCertificate("test-peer") as X509Certificate)
        val base = trust(store)
        val manager = serialTrustManager(base, "PANDY-TEST")
        manager.checkServerTrusted(chain, "RSA") // Serial matching is deliberately case insensitive.
        assertEquals(base.acceptedIssuers.toList(), manager.acceptedIssuers.toList())
        manager.checkClientTrusted(chain, "RSA")
        val wrongPrinter = serialTrustManager(base, "ANOTHER-PRINTER")
        assertTrue(assertFailsWith<CertificateException> { wrongPrinter.checkServerTrusted(chain, "RSA") }.message!!.contains("ANOTHER-PRINTER"))
        val unrelatedCa = trust(KeyStore.getInstance(KeyStore.getDefaultType()).apply { load(null) })
        assertFailsWith<CertificateException> {
            serialTrustManager(unrelatedCa, "pandy-test").checkServerTrusted(chain, "RSA")
        }
    }

    @Test fun productionFactoriesLoadBundledAuthoritiesAndReuseThePrinterContext() = runBlocking {
        @Suppress("UNCHECKED_CAST")
        val provider = Class.forName("org.jetbrains.compose.resources.AndroidContextProvider") as Class<android.content.ContentProvider>
        org.robolectric.Robolectric.buildContentProvider(provider).create()
        val first = BambuTls.factory("COVERAGE-TEST-ONLY")
        assertSame(first, BambuTls.factory("COVERAGE-TEST-ONLY"))
        assertTrue("TLSv1.2" in BambuTls.sslContexts.getValue("COVERAGE-TEST-ONLY").supportedSSLParameters.protocols)
    }
}
