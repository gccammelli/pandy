package io.github.gccammelli.pandy.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.gccammelli.pandy.INTEGRATION_URL
import io.github.gccammelli.pandy.LocalPlatform
import io.github.gccammelli.pandy.discoverPrinters
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
    data object Calibration : Screen
    data object PrintOptions : Screen
    data object AmsSettings : Screen
    data object LiveviewSettings : Screen
    data object Assistant : Screen
    data object Files : Screen
    data object PrinterFiles : Screen
    data object Filament : Screen
    data object History : Screen
    data object Settings : Screen
    data object About : Screen
    data object AddHa : Screen
    data object AddDirect : Screen
    data object Slicer : Screen
}

object Nav {
    val stack = mutableStateListOf<Screen>()
    /** 0 Models, 1 Devices, 2 Me. Devices first, as Handy can be set to open on it. */
    var tab by mutableStateOf(1)
    /** The model site the Models tab shows; long-pressing MakerWorld (the Models tab or Me's shortcut) switches it. */
    var site by mutableStateOf(io.github.gccammelli.pandy.ModelSite.MakerWorld)
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
            T(if (Repo.direct) "No printer found on the local network." else if (Repo.demo) "The demo printer is unavailable." else "Home Assistant has no Bambu Lab printers.", 15.sp, C.Sub); Gap(16.dp); AccentButton("Retry") { Repo.restart() }
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
        Screen.Calibration -> CalibrationScreen(p)
        Screen.PrintOptions -> PrintOptionsScreen(p)
        Screen.AmsSettings -> AmsSettingsScreen(p)
        Screen.LiveviewSettings -> LiveviewSettingsScreen()
        Screen.Assistant -> AssistantScreen(p)
        Screen.Files -> FilesScreen(p)
        Screen.PrinterFiles -> PrinterFilesScreen()
        Screen.Filament -> FilamentScreen(p)
        Screen.History -> HistoryScreen(p)
        Screen.Settings -> SettingsScreen()
        Screen.About -> AboutScreen()
        Screen.AddHa -> AddHaScreen()
        Screen.AddDirect -> AddDirectScreen()
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
            val tab = Modifier.weight(1f).fillMaxSize()
            val content: @Composable () -> Unit = {
                Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                    Icon(icon, label, Modifier.size(24.dp), tint = if (sel) C.Accent else C.Sub)
                    T(label, 11.sp, if (sel) C.Accent else C.Sub)
                }
            }
            if (i == 0) SiteMenuBox(tab, onClick = { Nav.tab = 0 }, content = content)
            else Box(tab.clickable { Nav.tab = i }) { content() }
        }
    }
}

// ---------- Connect ----------

private enum class ConnectMode { Direct, HomeAssistant, DirectPlusHomeAssistant }

/** First screen: choose direct LAN, Home Assistant, the combined setup, or the demo. */
@Composable
fun Connect() {
    var mode by remember { mutableStateOf(ConnectMode.Direct) }
    Column(Modifier.fillMaxSize().background(C.Card).statusBarsPadding().imePadding().verticalScroll(rememberScrollState()).padding(24.dp)) {
        Gap(24.dp)
        PandaMark(Modifier.size(64.dp))
        Gap(16.dp)
        T("Pandy", 32.sp, weight = FontWeight.Bold)
        Gap(8.dp)
        T("Choose how Pandy gets printer data. Home Assistant can be used on its own or added to a direct connection for extra features.", 15.sp, C.Sub, lh = 21.sp)
        Gap(24.dp)
        ConnectionComparison()
        Gap(20.dp)
        T("Connection mode", 16.sp, weight = FontWeight.Medium)
        Gap(8.dp)
        ConnectModeButton("Pandy Only", "Control the printer directly over your LAN. Needs LAN Only and Developer Mode.", mode == ConnectMode.Direct) { mode = ConnectMode.Direct }
        ConnectModeButton("HA Only", "Use Home Assistant as the printer connection. Works remotely when Home Assistant is reachable.", mode == ConnectMode.HomeAssistant) { mode = ConnectMode.HomeAssistant }
        ConnectModeButton("Pandy + HA", "Use direct control, plus Home Assistant history, cached files, timelapses and MakerWorld printing.", mode == ConnectMode.DirectPlusHomeAssistant) { mode = ConnectMode.DirectPlusHomeAssistant }
        Gap(16.dp)
        when (mode) {
            ConnectMode.Direct -> {
                T("You can add Home Assistant later in Me › Settings › Home Assistant.", 13.sp, C.Sub, lh = 18.sp)
                Gap(12.dp)
                PrinterForm()
            }
            ConnectMode.HomeAssistant -> {
                T("You can add the direct LAN connection later in Me › Settings › Direct LAN connection.", 13.sp, C.Sub, lh = 18.sp)
                Gap(12.dp)
                HaForm(asPrinter = true)
            }
            ConnectMode.DirectPlusHomeAssistant -> {
                T("Step 1 of 2: connect Pandy Direct", 16.sp, weight = FontWeight.Medium)
                Gap(4.dp)
                T("Step 2 asks for your Home Assistant address and token right after this.", 13.sp, C.Sub, lh = 18.sp)
                Gap(12.dp)
                PrinterForm { Nav.go(Screen.AddHa) }
            }
        }
        Gap(28.dp)
        T("Explore without a printer", 15.sp, weight = FontWeight.Medium)
        Gap(8.dp)
        ConnectActionButton("Try the demo", "Explore a pretend X1 Carbon halfway through a print, with an AMS. Nothing to set up.") { Repo.startDemo() }
    }
}

@Composable
private fun ConnectionComparison() {
    T("Help me choose", 16.sp, weight = FontWeight.Medium)
    Gap(4.dp)
    T("Pick the setup that matches how you want to use your printer.", 13.sp, C.Sub, lh = 18.sp)
    Gap(8.dp)
    val shape = RoundedCornerShape(12.dp)
    Column(Modifier.fillMaxWidth().clip(shape).border(1.dp, C.Line, shape)) {
        Row(Modifier.fillMaxWidth().background(C.Fill).padding(12.dp), verticalAlignment = Alignment.Top) {
            T("Feature", 11.sp, C.Sub, weight = FontWeight.Medium, modifier = Modifier.weight(1.45f))
            ComparisonHeaderCell("Pandy\nOnly", 0.85f)
            ComparisonHeaderCell("HA\nOnly", 0.85f)
            ComparisonHeaderCell("Pandy +\nHA", 0.85f)
        }
        ComparisonFeatureRow("Printer data and control", true, true, true)
        ComparisonDivider()
        ComparisonFeatureRow("Direct LAN connection", true, false, true)
        ComparisonDivider()
        ComparisonFeatureRow("Works without Home Assistant", true, false, false)
        ComparisonDivider()
        ComparisonFeatureRow("Remote access*", false, true, true)
        ComparisonDivider()
        ComparisonFeatureRow("HA history, cache and timelapses", false, true, true)
        ComparisonDivider()
        ComparisonFeatureRow("LAN Only + Developer Mode", true, false, true)
    }
    Gap(4.dp)
    T("* Remote use depends on Home Assistant being reachable. Some HA controls depend on what its Bambu integration exposes.", 12.sp, C.Sub, lh = 16.sp)
}

@Composable
private fun RowScope.ComparisonHeaderCell(label: String, weight: Float) = T(
    label, 11.sp, C.Sub, weight = FontWeight.Medium, align = TextAlign.Center, modifier = Modifier.weight(weight)
)

@Composable
private fun ComparisonFeatureRow(feature: String, vararg enabled: Boolean) {
    Row(Modifier.fillMaxWidth().background(C.Card).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
        T(feature, 12.sp, modifier = Modifier.weight(1.45f))
        enabled.forEach { ComparisonMark(it, Modifier.weight(0.85f)) }
    }
}

@Composable
private fun ComparisonMark(enabled: Boolean, modifier: Modifier) = T(
    if (enabled) "✓" else "—", 16.sp, if (enabled) C.Ok else C.Faint, weight = FontWeight.Bold,
    align = TextAlign.Center, modifier = modifier
)

@Composable
private fun ComparisonDivider() = Box(Modifier.fillMaxWidth().height(1.dp).background(C.Line))

@Composable
private fun ConnectModeButton(title: String, description: String, selected: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(if (selected) C.AccentBg else C.Fill)
        .border(1.dp, if (selected) C.Accent else C.Line, RoundedCornerShape(14.dp)).clickable(onClick = onClick).padding(16.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            T(title, 16.sp, weight = FontWeight.Medium)
            Gap(4.dp)
            T(description, 13.sp, C.Sub, lh = 18.sp)
        }
        if (selected) T("✓", 20.sp, C.Accent, weight = FontWeight.Bold, modifier = Modifier.padding(start = 12.dp))
    }
    Gap(8.dp)
}

@Composable
private fun ConnectActionButton(title: String, description: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().height(64.dp).clip(RoundedCornerShape(14.dp)).background(C.Fill)
        .border(1.dp, C.Line, RoundedCornerShape(14.dp)).clickable(onClick = onClick).padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            T(title, 16.sp, C.Accent, weight = FontWeight.Medium)
            Gap(2.dp)
            T(description, 12.sp, C.Sub, maxLines = 2)
        }
        T("›", 26.sp, C.Accent, modifier = Modifier.padding(start = 12.dp))
    }
}

/** Direct mode: printers found on the Wi-Fi to pick from, or the IP, serial number and access code typed in. */
@Composable
fun PrinterForm(
    discover: suspend (Long) -> List<io.github.gccammelli.pandy.FoundPrinter> = ::discoverPrinters,
    connect: suspend (io.github.gccammelli.pandy.LanConfig) -> Unit = { Repo.connectPrinter(it) },
    done: () -> Unit = {},
) {
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
        found = runCatching { discover(8_000) }.getOrDefault(emptyList())
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
        visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
    Gap(8.dp)
    T("The access code is on the printer's screen: Settings › Network (X1, H2) or Settings › WLAN (P1, A1). Turn on LAN Only mode and Developer Mode there too.", 12.sp, C.Sub, lh = 17.sp)
    if (err != null) { Gap(12.dp); T(err!!, 14.sp, C.Red) }
    Gap(20.dp)
    AccentButton(if (busy) "Connecting…" else "Connect", enabled = !busy && ip.isNotBlank() && serial.length >= 8 && code.isNotBlank()) {
        busy = true; err = null
        val c = io.github.gccammelli.pandy.LanConfig(ip, serial, code, model ?: io.github.gccammelli.pandy.modelName(null, serial), name)
        scope.launch { runCatching { connect(c); done() }.onFailure { err = it.message ?: it.toString() }; busy = false }
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
        visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
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

/** Me › Settings › Direct LAN connection: add the printer's own connection to Home Assistant mode. */
@Composable
fun AddDirectScreen() = Page("Direct LAN connection", { Nav.back() }) {
    Column(Modifier.verticalScroll(rememberScrollState()).imePadding().padding(20.dp)) {
        T(if (Repo.direct) "Add a printer on your local network." else "Pandy is using Home Assistant for this printer. A direct LAN connection adds every printer command and the live view, and keeps working when Home Assistant is down.", 14.sp, C.Sub, lh = 20.sp)
        Gap(20.dp)
        PrinterForm { Nav.back() }
    }
}


/**
 * Tap does [onClick]; a long press offers the model sites (MakerWorld, Thingiverse, Printables) and opens the chosen one
 * in the Models tab.
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun SiteMenuBox(modifier: Modifier, onClick: () -> Unit, content: @Composable () -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box(modifier.combinedClickable(onClick = onClick, onLongClick = { open = true })) {
        content()
        androidx.compose.material3.DropdownMenu(open, { open = false }, containerColor = C.Card) {
            for (site in io.github.gccammelli.pandy.ModelSite.entries) {
                androidx.compose.material3.DropdownMenuItem(
                    text = { T(if (site == Nav.site) "${site.label} ✓" else site.label, 15.sp, if (site == Nav.site) C.Accent else C.Text) },
                    onClick = { open = false; Nav.site = site; Nav.tab = 0 },
                )
            }
        }
    }
}
