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

@Composable
fun Connect() {
    var url by remember { mutableStateOf("http://") }
    var token by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var err by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val p = LocalPlatform.current
    Column(Modifier.fillMaxSize().background(C.Card).statusBarsPadding().imePadding().verticalScroll(rememberScrollState()).padding(24.dp)) {
        Gap(32.dp)
        PandaMark(Modifier.size(64.dp))
        Gap(16.dp)
        T("Pandy", 32.sp, weight = FontWeight.Bold)
        Gap(8.dp)
        T("Your Bambu Lab printer, through your own Home Assistant. Works in LAN-only mode: Pandy never needs the printer to reach Bambu's cloud.", 15.sp, C.Sub, lh = 21.sp)
        Gap(28.dp)
        OutlinedTextField(url, { url = it }, Modifier.fillMaxWidth(), label = { T("Home Assistant address", 14.sp, C.Sub) }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
        Gap(12.dp)
        OutlinedTextField(token, { token = it }, Modifier.fillMaxWidth(), label = { T("Long-lived access token", 14.sp, C.Sub) }, singleLine = true,
            visualTransformation = PasswordVisualTransformation())
        Gap(10.dp)
        T("Create the token in Home Assistant: your profile → Security → Long-lived access tokens.", 13.sp, C.Sub, lh = 18.sp)
        if (err != null) { Gap(12.dp); T(err!!, 14.sp, C.Red) }
        Gap(24.dp)
        AccentButton(if (busy) "Connecting…" else "Connect", enabled = !busy && url.length > 8 && token.isNotBlank()) {
            busy = true; err = null
            scope.launch { runCatching { Repo.connect(url, token) }.onFailure { err = it.message ?: it.toString() }; busy = false }
        }
        Gap(20.dp)
        Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).border(1.dp, C.Line, RoundedCornerShape(10.dp)).clickable { p.openUrl(INTEGRATION_URL) }.padding(16.dp)) {
            T("Needs the Bambu Lab integration in Home Assistant (install it from HACS and add your printer there).", 13.sp, C.Blue, lh = 18.sp)
        }
        Gap(28.dp)
        T("Just looking?", 15.sp, weight = FontWeight.Medium)
        Gap(8.dp)
        Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).border(1.dp, C.Line, RoundedCornerShape(10.dp)).clickable { Repo.startDemo() }.padding(16.dp)) {
            T("Try the demo: a pretend X1 Carbon halfway through a print, with an AMS. No Home Assistant needed.", 14.sp, C.Text, lh = 19.sp)
        }
    }
}
