package io.github.gccammelli.pandy

import kotlin.test.*

class PlatformDefaultsTest {
    @Test fun aPlatformThatOnlyImplementsTheBasicsOffersNoBackgroundAlerts() {
        val platform = object : PlatformUi {
            override val appVersion = "test"
            override fun openUrl(url: String) = Unit
            override fun toast(message: String) = Unit
            override fun setDarkBars(dark: Boolean) = Unit
            override fun pickImage(done: (ByteArray?) -> Unit) = Unit
            override fun saveImage(jpeg: ByteArray, name: String, done: (Throwable?) -> Unit) = Unit
            override fun saveFile(name: String, mime: String, bytes: ByteArray, done: (Throwable?) -> Unit) = Unit
            override fun openApp(androidPackage: String, storeUrl: String) = Unit
            override fun landscape(on: Boolean) = Unit
        }
        assertFalse(platform.canNotify)
        platform.requestNotifications(); platform.watchInBackground(true); platform.watchInBackground(false)
    }

    @Test fun theAndroidStorageFactoryBuildsAnFtpsClientWithoutConnecting() {
        assertNotNull(printerStorage(LanConfig("192.0.2.1", "SERIAL", "code", "X1C")))
    }
}
