package io.github.gccammelli.pandy.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Category
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Print
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.gccammelli.pandy.INTEGRATION_URL
import io.github.gccammelli.pandy.LocalPlatform
import io.github.gccammelli.pandy.Printer
import io.github.gccammelli.pandy.Repo
import io.github.gccammelli.pandy.UiState
import androidx.compose.foundation.layout.Row
import kotlinx.coroutines.launch

sealed interface Screen {
    data object Control : Screen
    data object Motion : Screen
    data object Skip : Screen
    data object LiveFull : Screen
    data object PrinterSettings : Screen
    data object Assistant : Screen
    data object Files : Screen
    data object Filament : Screen
    data object History : Screen
    data object Settings : Screen
    data object About : Screen
    data class Web(val url: String, val title: String) : Screen
    data object AddHa : Screen
    data object Slicer : Screen
}

object Nav {
    val stack = mutableStateListOf<Screen>()
    /** 0 Models, 1 Devices, 2 Me. Devices first, as Handy can be set to open on it. */
    var tab by mutableStateOf(1)
    fun go(s: Screen) { stack += s }
    /** False when there's nothing to go back to (the system then closes the app). */
    fun back(): Boolean = stack.removeLastOrNull() != null
}

/** The printer on Devices: the user's pick, else the first. */
fun UiState.printer(): Printer? = printers?.let { ps -> ps.firstOrNull { it.serial == Repo.selected } ?: ps.firstOrNull() }

@Composable
fun Root() {
    val st by Repo.state.collectAsState()
    if (!st.on) return Connect()
    val p = st.printer()
    if (p == null) return Box(Modifier.fillMaxSize().background(C.Bg), contentAlignment = Alignment.Center) {
        if (st.printers != null) Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(32.dp)) {
            T("Home Assistant has no Bambu Lab printers.", 15.sp, C.Sub); Gap(16.dp); AccentButton("Retry") { Repo.restart() }
        } else if (st.error != null) Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(32.dp)) {
            T(st.error!!, 15.sp, C.Sub); Gap(16.dp); AccentButton("Retry") { Repo.restart() }
            Gap(12.dp); T("Log out", 15.sp, C.Sub, modifier = Modifier.clickable { Repo.logout() }.padding(12.dp))
        } else CircularProgressIndicator(color = C.Accent)
    }
    when (val s = Nav.stack.lastOrNull()) {
        null -> Tabs(st, p)
        Screen.Control -> ControlScreen(p)
        Screen.Motion -> MotionScreen(p)
        Screen.Skip -> SkipScreen(p)
        Screen.LiveFull -> LiveFullScreen(p)
        Screen.PrinterSettings -> PrinterSettingsScreen(p)
        Screen.Assistant -> AssistantScreen(p)
        Screen.Files -> FilesScreen(p)
        Screen.Filament -> FilamentScreen(p)
        Screen.History -> HistoryScreen(p)
        Screen.Settings -> SettingsScreen()
        Screen.About -> AboutScreen()
        is Screen.Web -> WebScreen(s.url, s.title)
        Screen.AddHa -> AddHaScreen()
        Screen.Slicer -> SlicerScreen()
    }
}

// ---------- Tabs ----------

@Composable
fun Tabs(st: UiState, p: Printer) = Column(Modifier.fillMaxSize().background(C.Bg)) {
    val tabs = listOf(
        Triple("Models", Icons.Outlined.Category) @Composable { ModelsTab() },
        Triple("Devices", Icons.Outlined.Print) @Composable { DevicesTab(st, p) },
        Triple("Me", Icons.Outlined.Person) @Composable { MeTab(st) },
    )
    Box(Modifier.weight(1f)) { tabs[Nav.tab].third() }
    Row(Modifier.fillMaxWidth().background(C.Nav).navigationBarsPadding().height(58.dp)) {
        tabs.forEachIndexed { i, (label, icon) ->
            val sel = Nav.tab == i
            Column(Modifier.weight(1f).fillMaxSize().clickable { Nav.tab = i }, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                Icon(icon, label, Modifier.size(24.dp), tint = if (sel) C.Accent else C.Sub)
                T(label, 11.sp, if (sel) C.Accent else C.Sub)
            }
        }
    }
}

// ---------- Connect ----------

/** First screen: find the printer on the Wi-Fi and enter its access code (direct mode), or use Home Assistant, or the demo. */
@Composable
fun Connect() {
    var useHa by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().background(C.Card).statusBarsPadding().imePadding().verticalScroll(rememberScrollState()).padding(24.dp)) {
        Gap(24.dp)
        PandaMark(Modifier.size(64.dp))
        Gap(16.dp)
        T("Pandy", 32.sp, weight = FontWeight.Bold)
        Gap(8.dp)
        T("Your Bambu Lab printer on your own network: works in LAN-only mode, no Bambu account needed for the printer.", 15.sp, C.Sub, lh = 21.sp)
        Gap(24.dp)
        if (useHa) {
            HaForm(asPrinter = true)
            Gap(16.dp)
            T("‹ Connect to the printer directly instead", 14.sp, C.Blue, modifier = Modifier.clickable { useHa = false }.padding(vertical = 8.dp))
        } else {
            PrinterForm()
            Gap(16.dp)
            Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).border(1.dp, C.Line, RoundedCornerShape(10.dp)).clickable { useHa = true }.padding(16.dp)) {
                T("Use Home Assistant instead: works away from home too, if your Home Assistant is reachable from outside. Needs its Bambu Lab integration.", 13.sp, C.Text, lh = 18.sp)
            }
        }
        Gap(28.dp)
        T("Just looking?", 15.sp, weight = FontWeight.Medium)
        Gap(8.dp)
        Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).border(1.dp, C.Line, RoundedCornerShape(10.dp)).clickable { Repo.startDemo() }.padding(16.dp)) {
            T("Try the demo: a pretend X1 Carbon halfway through a print, with an AMS. Nothing to set up.", 14.sp, C.Text, lh = 19.sp)
        }
    }
}

/** Direct mode: printers found on the Wi-Fi to pick from, or the IP, serial number and access code typed in. */
@Composable
fun PrinterForm() {
    var found by remember { mutableStateOf<List<io.github.gccammelli.pandy.FoundPrinter>?>(null) }
    var searching by remember { mutableStateOf(true) }
    var ip by remember { mutableStateOf("") }
    var serial by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var model by remember { mutableStateOf<String?>(null) }
    var name by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var err by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    androidx.compose.runtime.LaunchedEffect(searching) {
        if (!searching) return@LaunchedEffect
        found = runCatching { io.github.gccammelli.pandy.discoverPrinters(8_000) }.getOrDefault(emptyList())
        found?.singleOrNull()?.let { if (ip.isEmpty()) { ip = it.ip; serial = it.serial; model = it.model; name = it.name } }
        searching = false
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        T("Printers on this Wi-Fi", 15.sp, weight = FontWeight.Medium, modifier = Modifier.weight(1f))
        if (searching) CircularProgressIndicator(Modifier.size(16.dp), color = C.Accent, strokeWidth = 2.dp)
        else T("Search again", 13.sp, C.Blue, modifier = Modifier.clickable { searching = true }.padding(4.dp))
    }
    Gap(8.dp)
    val list = found.orEmpty()
    if (list.isEmpty()) T(if (searching) "Looking for printers… (they announce themselves every few seconds)" else "None found. Type its details below.", 13.sp, C.Sub, lh = 18.sp)
    list.forEach { f ->
        val sel = f.serial == serial
        Row(Modifier.fillMaxWidth().padding(vertical = 3.dp).clip(RoundedCornerShape(10.dp)).background(if (sel) C.AccentBg else C.Bg)
            .clickable { ip = f.ip; serial = f.serial; model = f.model; name = f.name }.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            PrinterArt(Modifier.size(36.dp), ams = false, lit = false)
            androidx.compose.foundation.layout.Spacer(Modifier.size(12.dp))
            Column(Modifier.weight(1f)) {
                T(f.name, 15.sp, weight = FontWeight.Medium)
                T("${f.model} · ${f.ip}", 12.sp, C.Sub)
            }
            if (sel) CheckDot(true)
        }
    }
    Gap(16.dp)
    OutlinedTextField(ip, { ip = it.trim() }, Modifier.fillMaxWidth(), label = { T("Printer IP address", 14.sp, C.Sub) }, singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
    Gap(10.dp)
    OutlinedTextField(serial, { serial = it.trim().uppercase(); model = null; name = "" }, Modifier.fillMaxWidth(), label = { T("Serial number", 14.sp, C.Sub) }, singleLine = true)
    Gap(10.dp)
    OutlinedTextField(code, { code = it.trim() }, Modifier.fillMaxWidth(), label = { T("Access code", 14.sp, C.Sub) }, singleLine = true,
        visualTransformation = PasswordVisualTransformation())
    Gap(8.dp)
    T("The access code is on the printer's screen: Settings › Network (X1, H2) or Settings › WLAN (P1, A1). Turn on LAN Only mode and Developer Mode there too.", 12.sp, C.Sub, lh = 17.sp)
    if (err != null) { Gap(12.dp); T(err!!, 14.sp, C.Red) }
    Gap(20.dp)
    AccentButton(if (busy) "Connecting…" else "Connect", enabled = !busy && ip.isNotBlank() && serial.length >= 8 && code.isNotBlank()) {
        busy = true; err = null
        val c = io.github.gccammelli.pandy.LanConfig(ip, serial, code, model ?: io.github.gccammelli.pandy.modelName(null, serial), name)
        scope.launch { runCatching { Repo.connectPrinter(c) }.onFailure { err = it.message ?: it.toString() }; busy = false }
    }
}

/** Home Assistant's address and token. [asPrinter]: Home Assistant mode; otherwise it's added to direct mode for the extras. */
@Composable
fun HaForm(asPrinter: Boolean, done: () -> Unit = {}) {
    var url by remember { mutableStateOf(Repo.haUrl ?: "http://") }
    var token by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var err by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val p = LocalPlatform.current
    OutlinedTextField(url, { url = it }, Modifier.fillMaxWidth(), label = { T("Home Assistant address", 14.sp, C.Sub) }, singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
    Gap(12.dp)
    OutlinedTextField(token, { token = it }, Modifier.fillMaxWidth(), label = { T("Long-lived access token", 14.sp, C.Sub) }, singleLine = true,
        visualTransformation = PasswordVisualTransformation())
    Gap(10.dp)
    T("Create the token in Home Assistant: your profile → Security → Long-lived access tokens.", 13.sp, C.Sub, lh = 18.sp)
    if (err != null) { Gap(12.dp); T(err!!, 14.sp, C.Red) }
    Gap(20.dp)
    AccentButton(if (busy) "Connecting…" else "Connect", enabled = !busy && url.length > 8 && token.isNotBlank()) {
        busy = true; err = null
        scope.launch { runCatching { Repo.connect(url, token, asPrinter); done() }.onFailure { err = it.message ?: it.toString() }; busy = false }
    }
    Gap(14.dp)
    T("Needs the Bambu Lab integration in Home Assistant (from HACS) ›", 13.sp, C.Blue, lh = 18.sp, modifier = Modifier.clickable { p.openUrl(INTEGRATION_URL) })
}

/** Me › Settings › Home Assistant: add it to direct mode for history, files, timelapses and MakerWorld printing. */
@Composable
fun AddHaScreen() = Page("Home Assistant", { Nav.back() }) {
    Column(Modifier.verticalScroll(rememberScrollState()).imePadding().padding(20.dp)) {
        T("Pandy controls your printer directly. Home Assistant (with the Bambu Lab integration) adds what the printer can't give a phone on its own: print history and reprinting, cached files, timelapses, and slicing MakerWorld models.", 14.sp, C.Sub, lh = 20.sp)
        Gap(20.dp)
        HaForm(asPrinter = false) { Nav.back() }
    }
}
