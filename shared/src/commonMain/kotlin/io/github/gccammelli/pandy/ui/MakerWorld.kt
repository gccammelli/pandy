package io.github.gccammelli.pandy.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.gccammelli.pandy.WebPage
import io.github.gccammelli.pandy.WebState

const val MAKERWORLD = "https://makerworld.com/en"

/**
 * MakerWorld, as on makerworld.com. Downloading a model's 3MF ("Download" on a print profile) hands it to Pandy,
 * which offers to slice and print it on the user's printer.
 */
@Composable
fun MakerWorld(modifier: Modifier) {
    val web = remember { WebState() }
    var download by remember { mutableStateOf<Triple<String, String, String?>?>(null) }
    WebPage(MAKERWORLD, modifier, onDownload = { u, n, c -> download = Triple(u, n, c) }, state = web)
    download?.let { (u, n, c) -> PrintModelSheet(u, n, c) { download = null } }
}

@Composable
fun PrintModelSheet(url: String, name: String, cookies: String?, close: () -> Unit) = Sheet(close, name, centeredTitle = true, confirm = null) {
    T("Printing MakerWorld models needs the Pandy Slicer add-on in Home Assistant.", 14.sp, C.Sub, lh = 20.sp)
    Gap(20.dp)
}
