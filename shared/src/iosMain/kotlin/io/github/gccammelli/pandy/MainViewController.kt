@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)

package io.github.gccammelli.pandy

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.ComposeUIViewController
import io.github.gccammelli.pandy.ui.AppTheme
import io.github.gccammelli.pandy.ui.Root
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import platform.Foundation.NSBundle
import platform.Foundation.NSData
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSOperationQueue
import platform.Foundation.NSFileManager
import platform.Foundation.NSUUID
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSURL
import platform.Foundation.NSSelectorFromString
import platform.Foundation.writeToFile
import platform.PhotosUI.PHPickerConfiguration
import platform.PhotosUI.PHPickerFilter
import platform.PhotosUI.PHPickerResult
import platform.PhotosUI.PHPickerViewController
import platform.PhotosUI.PHPickerViewControllerDelegateProtocol
import platform.UIKit.UIApplication
import platform.UIKit.UIApplicationWillEnterForegroundNotification
import platform.UIKit.UIApplicationDidEnterBackgroundNotification
import platform.UIKit.UIActivityViewController
import platform.UIKit.UIPopoverPresentationController
import platform.UIKit.UIStatusBarStyleDarkContent
import platform.UIKit.UIStatusBarStyleLightContent
import platform.UIKit.UIViewController
import platform.UIKit.setStatusBarStyle
import platform.darwin.NSObject
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue

/** The iOS entry point, called from the Swift app (iosApp/ContentView.swift). */
fun MainViewController(): UIViewController {
    Repo.init(iosStorage())
    // Live updates only while Pandy is on screen, as on Android.
    NSNotificationCenter.defaultCenter.addObserverForName(UIApplicationWillEnterForegroundNotification, null, NSOperationQueue.mainQueue) { _ -> Repo.start() }
    NSNotificationCenter.defaultCenter.addObserverForName(UIApplicationDidEnterBackgroundNotification, null, NSOperationQueue.mainQueue) { _ -> Repo.stop() }
    Repo.start()
    val ui = IosPlatformUi()
    return ComposeUIViewController {
        CompositionLocalProvider(LocalPlatform provides ui) {
            AppTheme {
                Box(Modifier.fillMaxSize()) {
                    Root()
                    Toast(ui.toastMessage)
                }
            }
        }
    }
}

/** iOS has no toasts: a small message near the bottom that disappears after a few seconds. */
@Composable
private fun Toast(state: MutableStateFlow<String?>) {
    val msg by state.collectAsState()
    LaunchedEffect(msg) { if (msg != null) { delay(3000); state.value = null } }
    msg?.let {
        Box(Modifier.fillMaxSize().navigationBarsPadding().padding(24.dp), contentAlignment = Alignment.BottomCenter) {
            Text(it, color = Color.White, modifier = Modifier.background(Color(0xE6303030), RoundedCornerShape(20.dp)).padding(horizontal = 16.dp, vertical = 10.dp))
        }
    }
}

private class IosPlatformUi : PlatformUi {
    val toastMessage = MutableStateFlow<String?>(null)
    private var pickerDelegate: NSObject? = null // UIKit holds delegates weakly

    override val appVersion get() = NSBundle.mainBundle.infoDictionary?.get("CFBundleShortVersionString") as? String ?: ""

    private fun top(): UIViewController? {
        var vc = UIApplication.sharedApplication.keyWindow?.rootViewController
        while (vc?.presentedViewController != null) vc = vc.presentedViewController
        return vc
    }

    // iOS can't open another app by its identity without its URL scheme: the App Store page opens it (or installs it).
    override fun openApp(androidPackage: String, storeUrl: String) = openUrl(HANDY_APP_STORE)

    override fun openUrl(url: String) {
        NSURL.URLWithString(url)?.let { UIApplication.sharedApplication.openURL(it, emptyMap<Any?, Any>(), null) }
    }

    override fun toast(message: String) { toastMessage.value = message }

    // shortcut: full-screen live view stays in the phone's current orientation (rotate the phone); forcing landscape needs a UIKit scene hook.
    override fun landscape(on: Boolean) {}

    // App-wide style (Info.plist: UIViewControllerBasedStatusBarAppearance = NO), since Compose's controller doesn't expose one.
    override fun setDarkBars(dark: Boolean) =
        UIApplication.sharedApplication.setStatusBarStyle(if (dark) UIStatusBarStyleLightContent else UIStatusBarStyleDarkContent, true)

    /** The system photo picker: no photo-library permission needed, the user hands over just the one picture. */
    /** Adds the photo to the library (asks for "add photos" permission the first time). */
    override fun saveImage(jpeg: ByteArray, name: String, done: (Throwable?) -> Unit) {
        val data = jpeg.toNSData()
        platform.Photos.PHPhotoLibrary.sharedPhotoLibrary().performChanges({
            platform.Photos.PHAssetCreationRequest.creationRequestForAsset()
                .addResourceWithType(platform.Photos.PHAssetResourceTypePhoto, data, null)
        }) { ok, error ->
            dispatch_async(dispatch_get_main_queue()) { done(if (ok) null else Exception(error?.localizedDescription ?: "Photos refused it")) }
        }
    }

    override fun saveFile(name: String, mime: String, bytes: ByteArray, done: (Throwable?) -> Unit) {
        val safe = name.substringAfterLast('/').ifBlank { "download.bin" }
        var path: String? = null
        val result = runCatching {
            path = "${NSTemporaryDirectory()}${NSUUID.UUID().UUIDString}-$safe"
            check(bytes.toNSData().writeToFile(path!!, true)) { "Couldn't prepare the download" }
            val activity = UIActivityViewController(listOf(NSURL.fileURLWithPath(path!!)), null)
            activity.completionWithItemsHandler = { _, completed, _, error ->
                NSFileManager.defaultManager.removeItemAtPath(path!!, null)
                if (completed) dispatch_async(dispatch_get_main_queue()) { done(error?.let { Exception(it.localizedDescription ?: "Couldn't save the download") }) }
            }
            val host = top() ?: error("No screen to save the download")
            // UIActivityViewController is a popover on iPad and needs an anchor before presentation.
            val popover = (activity as UIViewController).performSelector(NSSelectorFromString("popoverPresentationController"))
                as? UIPopoverPresentationController
            popover?.let {
                it.sourceView = host.view
                it.sourceRect = host.view.bounds
            }
            host.presentViewController(activity, true, null)
        }
        result.exceptionOrNull()?.let { error ->
            path?.let { NSFileManager.defaultManager.removeItemAtPath(it, null) }
            done(error)
        }
    }

    override fun pickImage(done: (ByteArray?) -> Unit) {
        val picker = PHPickerViewController(PHPickerConfiguration().apply { filter = PHPickerFilter.imagesFilter; selectionLimit = 1 })
        val delegate = object : NSObject(), PHPickerViewControllerDelegateProtocol {
            override fun picker(picker: PHPickerViewController, didFinishPicking: List<*>) {
                picker.dismissViewControllerAnimated(true, null)
                pickerDelegate = null
                val provider = (didFinishPicking.firstOrNull() as? PHPickerResult)?.itemProvider ?: return done(null)
                provider.loadDataRepresentationForTypeIdentifier("public.image") { data: NSData?, _ ->
                    val bytes = data?.toByteArray()
                    dispatch_async(dispatch_get_main_queue()) { done(bytes) }
                }
            }
        }
        pickerDelegate = delegate
        picker.delegate = delegate
        top()?.presentViewController(picker, true, null)
    }
}
