package io.github.gccammelli.pandy.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.LocalCafe
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.material.icons.outlined.ViewInAr
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.gccammelli.pandy.CachedFile
import io.github.gccammelli.pandy.LocalPlatform
import io.github.gccammelli.pandy.Printer
import io.github.gccammelli.pandy.REPO_URL
import io.github.gccammelli.pandy.Repo
import io.github.gccammelli.pandy.UiState
import io.github.gccammelli.pandy.VideoPlayer
import io.github.gccammelli.pandy.autoMapping
import io.github.gccammelli.pandy.ensureOnPrinter
import io.github.gccammelli.pandy.isSingleSlot
import io.github.gccammelli.pandy.slotForTrayId
import io.github.gccammelli.pandy.startPrint
import io.github.gccammelli.pandy.printHistory
import io.github.gccammelli.pandy.sliceInfo
import io.github.gccammelli.pandy.timelapses

// ---------- Me ----------

@Composable
fun MeTab(st: UiState) {
    val pl = LocalPlatform.current
    Column(Modifier.fillMaxSize().background(C.Bg).statusBarsPadding().verticalScroll(rememberScrollState()).padding(14.dp)) {
        Gap(8.dp)
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(C.Card).clickable { Nav.go(Screen.Settings) }.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            PandaMark(Modifier.size(52.dp))
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                T(if (Repo.demo) "Demo" else if (Repo.direct) "Direct connection" else "Home Assistant", 18.sp, weight = FontWeight.Bold)
                T(if (Repo.demo) "A pretend printer" else if (Repo.direct) (Repo.lan?.ip ?: "") + if (Repo.haUrl != null) " + Home Assistant" else "" else Repo.haUrl ?: "", 12.sp, C.Sub)
            }
            T("${st.printers?.size ?: 0} printer${if (st.printers?.size == 1) "" else "s"}", 12.sp, C.Sub)
        }
        Gap(12.dp)
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(C.Card).padding(vertical = 14.dp)) {
            Shortcut(Icons.Outlined.History, "Printing\nHistory", Modifier.weight(1f)) { Nav.go(Screen.History) }
            Shortcut(Icons.Outlined.Videocam, "Timelapse", Modifier.weight(1f)) { Nav.go(Screen.Files) }
            Shortcut(Icons.Outlined.ViewInAr, "MakerWorld", Modifier.weight(1f)) { Nav.tab = 0 }
            Shortcut(Icons.Outlined.Folder, "Filament", Modifier.weight(1f)) { Nav.go(Screen.Filament) }
        }
        Gap(12.dp)
        Group {
            NavRow("Learning", leading = { SmallIcon(Icons.AutoMirrored.Outlined.MenuBook, C.Text, 20.dp) }) { pl.openUrl("https://wiki.bambulab.com") }
            NavRow("Support Center", leading = { SmallIcon(Icons.AutoMirrored.Outlined.HelpOutline, C.Text, 20.dp) }) { pl.openUrl("$REPO_URL/issues") }
            NavRow("Buy me a coffee", leading = { SmallIcon(Icons.Outlined.LocalCafe, C.Text, 20.dp) }) { pl.openUrl(io.github.gccammelli.pandy.DONATE_URL) }
            NavRow("About Pandy", leading = { SmallIcon(Icons.Outlined.Info, C.Text, 20.dp) }) { Nav.go(Screen.About) }
            NavRow("Settings", leading = { SmallIcon(Icons.Outlined.Settings, C.Text, 20.dp) }) { Nav.go(Screen.Settings) }
        }
    }
}

@Composable
private fun Shortcut(icon: ImageVector, label: String, modifier: Modifier, onClick: () -> Unit) =
    Column(modifier.clickable(onClick = onClick).padding(vertical = 4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(icon, null, Modifier.size(24.dp), tint = C.Text)
        Gap(6.dp)
        T(label, 11.sp, C.Text, align = androidx.compose.ui.text.style.TextAlign.Center, lh = 13.sp)
    }

// ---------- Print history ----------

/**
 * Printing History: the prints the integration cached (its file cache must be on). Tap one to print it again: the
 * integration copies it back to the printer if needed, then starts it.
 */
@Composable
fun HistoryScreen(p: Printer) {
    var reload by remember { mutableStateOf(0) }
    val files by produceState<Result<List<CachedFile>>?>(null, p.serial, reload) { value = runCatching { needHa().printHistory(p.serial) } }
    var open by remember { mutableStateOf<CachedFile?>(null) }
    Page("History", { Nav.back() }) {
        when (val r = files) {
            null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = C.Accent) }
            else -> r.fold({ list ->
                if (list.isEmpty()) Column { Empty("No prints yet"); SetupNote("Pandy lists the prints Home Assistant's Bambu Lab integration keeps. Turn on its file cache: Settings → Devices → Bambu Lab → Configure.", "docs/setup.md#file-cache", Modifier.padding(16.dp)) }
                else androidx.compose.foundation.lazy.LazyColumn(contentPadding = PaddingValues(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(list.size, key = { list[it].path }) { i -> HistoryRow(list[i]) { open = list[i] } }
                }
            }, { e -> if (Repo.extras == null) NeedsHa("Printing history") else Empty(e.message ?: "Couldn't load the history") })
        }
    }
    open?.let { f -> ReprintSheet(p, f) { open = null } }
}

@Composable
private fun HistoryRow(f: CachedFile, onClick: () -> Unit) = Card(padding = PaddingValues(10.dp), onClick = onClick) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(76.dp).clip(RoundedCornerShape(8.dp)).background(C.Fill), contentAlignment = Alignment.Center) {
            if (f.thumbUrl != null) HaImage(f.thumbUrl, Modifier.fillMaxSize().padding(4.dp))
            else T("3MF", 12.sp, C.Faint, FontWeight.Bold)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            T(f.title, 15.sp, weight = FontWeight.Medium, maxLines = 2)
            Gap(4.dp)
            T(f.modified.take(16).replace('T', ' '), 12.sp, C.Sub)
            T(sizeText(f.size), 12.sp, C.Sub)
        }
    }
}

fun sizeText(b: Long) = when { b >= 1_048_576 -> "${(b / 104_857.6).toInt() / 10.0} MB"; b >= 1024 -> "${b / 1024} KB"; else -> "$b B" }

/** Print again: plate, filament mapping to the AMS, and Handy's print options. */
@Composable
fun ReprintSheet(p: Printer, f: CachedFile, close: () -> Unit) {
    val err = errorToast()
    val infoState by produceState<Pair<Boolean, io.github.gccammelli.pandy.SliceInfo?>>(false to null, f.path) {
        value = true to Repo.extras?.sliceInfo(f)
    }
    val infoLoaded = infoState.first
    val info = infoState.second
    var timelapse by remember { mutableStateOf(Repo.timelapseDefault) }
    var leveling by remember { mutableStateOf(true) }
    var flow by remember { mutableStateOf(true) } // on by default, as in Handy
    var busy by remember { mutableStateOf(false) }
    val filaments = info?.filaments.orEmpty()
    val mapping = info?.let { autoMapping(p, it.filaments) }
    val missingMapping = filaments.isNotEmpty() && mapping == null
    val canSend = infoLoaded && info != null && !busy && !p.printing && !missingMapping
    Sheet(close, "Print", centeredTitle = true, confirm = if (busy) "Sending…" else "Send", confirmEnabled = canSend, onConfirm = {
        if (busy) return@Sheet
        if (p.printing) { err("The printer is busy"); return@Sheet }
        if (!infoLoaded || info == null) { err("Print settings are not available yet"); return@Sheet }
        if (missingMapping) { err("No loaded filament matches this print"); return@Sheet }
        busy = true
        Repo.act({ err(it); busy = false }) {
            val path = needHa().ensureOnPrinter(p.serial, f)
            startPrint(p, path, info.plate, mapping, timelapse, leveling, flow)
            busy = false
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) { close(); Nav.stack.clear(); Nav.tab = 1 }
        }
    }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(64.dp).clip(RoundedCornerShape(8.dp)).background(C.Fill)) { HaImage(f.thumbUrl, Modifier.fillMaxSize().padding(4.dp)) }
            Spacer(Modifier.width(12.dp))
            Column { T(f.title, 15.sp, weight = FontWeight.Medium, maxLines = 2); T("Plate ${info?.plate ?: 1}", 12.sp, C.Sub) }
        }
        if (!infoLoaded) T("Reading print settings…", 13.sp, C.Sub, modifier = Modifier.padding(top = 12.dp))
        else if (info == null) T("Print settings could not be read, so this print is disabled.", 13.sp, C.Orange, modifier = Modifier.padding(top = 12.dp))
        else if (missingMapping) T("Load matching filament before sending this print.", 13.sp, C.Orange, modifier = Modifier.padding(top = 12.dp))
        val fil = filaments
        if (fil.isNotEmpty()) {
            Gap(16.dp)
            T("Filament", 14.sp, weight = FontWeight.Medium)
            Gap(8.dp)
            val map = mapping
            fil.forEachIndexed { i, x ->
                Row(Modifier.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(18.dp).clip(CircleShape).border(1.dp, C.Line, CircleShape).background(Color(io.github.gccammelli.pandy.hexColor(x["color"]))))
                    Spacer(Modifier.width(8.dp)); T(x["type"] ?: "?", 13.sp, modifier = Modifier.width(60.dp))
                    T("→", 13.sp, C.Sub); Spacer(Modifier.width(8.dp))
                    val t = map?.getOrNull(i)
                    if (t == null || t == 254) T("External spool", 13.sp, C.Sub)
                    else {
                        val match = p.ams.asSequence().mapNotNull { (a, spools) -> a.slotForTrayId(t)?.let { a to spools.getOrNull(it) } }.firstOrNull()
                        val a = match?.first
                        val s = match?.second
                        val slot = a?.slotForTrayId(t)
                        Box(Modifier.size(18.dp).clip(CircleShape).border(1.dp, C.Line, CircleShape).background(Color(s?.color ?: 0xFFDDDDDD)))
                        Spacer(Modifier.width(8.dp)); T(if (a?.isSingleSlot() == true) "HT ${s?.type.orEmpty()}" else "${a?.let { 'A' + it.index - 1 }}${slot?.plus(1) ?: ""} ${s?.type.orEmpty()}", 13.sp)
                    }
                }
            }
        }
        Gap(12.dp)
        ToggleLine("Bed leveling", leveling) { leveling = it }
        ToggleLine("Flow dynamics calibration", flow) { flow = it }
        ToggleLine("Timelapse", timelapse) { timelapse = it }
    }
}

@Composable
fun ToggleLine(label: String, on: Boolean, enabled: Boolean = true, change: (Boolean) -> Unit) = Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
    T(label, 14.sp, if (enabled) C.Text else C.Sub, modifier = Modifier.weight(1f)); Toggle(on, enabled, change)
}

// ---------- Files (timelapses and models) ----------

@Composable
fun FilesScreen(p: Printer) {
    var tab by remember { mutableStateOf(0) }
    var reload by remember { mutableStateOf(0) }
    var playing by remember { mutableStateOf<CachedFile?>(null) }
    var open by remember { mutableStateOf<CachedFile?>(null) }
    Page("Files", { Nav.back() }) {
        Row(Modifier.fillMaxWidth().background(C.Card).padding(bottom = 8.dp), horizontalArrangement = Arrangement.Center) {
            listOf("Timelapse", "Model").forEachIndexed { i, l ->
                Column(Modifier.clickable { tab = i }.padding(horizontal = 22.dp, vertical = 6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    T(l, 15.sp, if (tab == i) C.Accent else C.Sub, if (tab == i) FontWeight.Medium else FontWeight.Normal)
                    Gap(4.dp)
                    Box(Modifier.size(24.dp, 2.dp).background(if (tab == i) C.Accent else Color.Transparent))
                }
            }
        }
        val backend = Repo.extras
        val files by produceState<Result<List<CachedFile>>?>(null, backend, tab, p.serial, reload) {
            value = backend?.let { b ->
                try {
                    Result.success(if (tab == 0) b.timelapses(p.serial) else b.printHistory(p.serial))
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Result.failure(e)
                }
            } ?: Result.success(emptyList())
        }
        if (backend == null) NeedsHa("Timelapses and models") else when (val result = files) {
            null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = C.Accent) }
            else -> result.fold(
                onSuccess = { list ->
                    if (list.isEmpty()) Empty("No content yet") else androidx.compose.foundation.lazy.LazyColumn(contentPadding = PaddingValues(14.dp)) {
                        val rows = list.chunked(2)
                        items(rows.size) { ri -> val row = rows[ri]
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                row.forEach { f ->
                                    Column(Modifier.weight(1f).clip(RoundedCornerShape(10.dp)).background(C.Card).clickable { if (tab == 0) playing = f else open = f }) {
                                        Box(Modifier.fillMaxWidth().aspectRatio(if (tab == 0) 16 / 9f else 1f).background(C.Fill)) { HaImage(f.thumbUrl, Modifier.fillMaxSize()) }
                                        Column(Modifier.padding(8.dp)) {
                                            T(f.title, 12.sp, maxLines = 2)
                                            T(sizeText(f.size), 11.sp, C.Sub)
                                        }
                                    }
                                }
                                if (row.size == 1) Spacer(Modifier.weight(1f))
                            }
                            Gap(10.dp)
                        }
                    }
                },
                onFailure = { e ->
                    Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 80.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        T("Couldn't load ${if (tab == 0) "timelapses" else "files"}", 16.sp, weight = FontWeight.Medium)
                        Gap(8.dp)
                        T(e.message ?: "The connection failed.", 13.sp, C.Sub, align = androidx.compose.ui.text.style.TextAlign.Center)
                        Gap(16.dp)
                        AccentButton("Try again") { reload++ }
                    }
                }
            )
        }
    }
    playing?.let { f ->
        // From Home Assistant it streams; from the printer it's copied to the phone first (FTPS can't stream).
        val url by produceState<String?>(null, f) {
            val x = Repo.extras
            value = if (x is io.github.gccammelli.pandy.DirectPrinter) runCatching { x.localVideo(f.path) }.getOrNull() else x?.let { it.base + f.url }
        }
        Sheet({ playing = null }, f.title, centeredTitle = true, confirm = null) {
            Box(Modifier.fillMaxWidth().aspectRatio(16 / 9f).clip(RoundedCornerShape(8.dp)).background(Color.Black)) {
                if (Repo.demo) T("The demo has no video", 13.sp, Color.White, modifier = Modifier.align(Alignment.Center))
                else if (url == null) CircularProgressIndicator(Modifier.align(Alignment.Center), color = C.Accent)
                else VideoPlayer(url!!, muted = true, modifier = Modifier.fillMaxSize(), onError = {}, controls = true, headers = Repo.authHeaders())
            }
            Gap(20.dp)
        }
    }
    open?.let { f -> ReprintSheet(p, f) { open = null } }
}

// ---------- Settings ----------

@Composable
fun SettingsScreen() {
    val pl = LocalPlatform.current
    var timelapseDefault by remember { mutableStateOf(Repo.timelapseDefault) }
    var showUnavailable by remember { mutableStateOf(Repo.showUnavailable) }
    Page("Settings", { Nav.back() }) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(14.dp)) {
            T("Appearance", 13.sp, C.Sub, modifier = Modifier.padding(start = 6.dp, bottom = 6.dp))
            Group {
                listOf("system" to "Follow system", "light" to "Light", "dark" to "Dark").forEach { (k, l) -> Radio(ThemeMode.mode == k, l) { Repo.setTheme(k) } }
                ToggleRow("Show unavailable controls", showUnavailable) {
                    showUnavailable = it
                    Repo.setShowUnavailable(it)
                }
            }
            Gap(16.dp)
            T("Printing", 13.sp, C.Sub, modifier = Modifier.padding(start = 6.dp, bottom = 6.dp))
            Group {
                ToggleRow("Timelapse for new prints", timelapseDefault) {
                    timelapseDefault = it
                    Repo.setTimelapseDefault(it)
                }
                if (pl.canNotify && !Repo.demo) {
                    var notify by remember { mutableStateOf(Repo.notify) }
                    ToggleRow("Print notifications", notify) {
                        notify = it
                        Repo.setNotify(it)
                        if (it) pl.requestNotifications()
                        pl.watchInBackground(it)
                    }
                }
            }
            if (pl.canNotify && !Repo.demo) T("Tells you when a print finishes, fails or needs attention. Pandy stays connected in the background, with a small ongoing notification.", 12.sp, C.Sub, lh = 17.sp, modifier = Modifier.padding(horizontal = 6.dp, vertical = 6.dp))
            Gap(16.dp)
            T("Connection", 13.sp, C.Sub, modifier = Modifier.padding(start = 6.dp, bottom = 6.dp))
            Group {
                if (Repo.direct) Repo.lan?.let { c -> NavRow("Printer", value = "${c.model} · ${c.ip}", chevron = false) }
                if (!Repo.direct && !Repo.demo) NavRow("Direct LAN connection", value = "Add (optional)") { Nav.go(Screen.AddDirect) }
                if (!Repo.demo) {
                    if (Repo.haUrl != null) NavRow("Home Assistant", value = Repo.haUrl + if (Repo.direct) "" else "  (printer too)", chevron = false)
                    else NavRow("Home Assistant", value = "Add (optional)") { Nav.go(Screen.AddHa) }
                    if (Repo.direct && Repo.haUrl != null) NavRow("Remove Home Assistant", labelColor = C.Red, chevron = false) { Repo.removeHa(); Nav.back() }
                }
                if (!Repo.demo) NavRow("Slicer for MakerWorld prints", value = Repo.slicerUrl ?: if (Repo.haApi != null) "Home Assistant add-on" else "Set up") { Nav.go(Screen.Slicer) }
                NavRow(if (Repo.demo) "Leave the demo" else "Disconnect", labelColor = C.Red, chevron = false) { Nav.stack.clear(); Repo.logout() }
            }
        }
    }
}

@Composable
fun AboutScreen() {
    val pl = LocalPlatform.current
    Page("About Pandy", { Nav.back() }) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Gap(24.dp)
            PandaMark(Modifier.size(84.dp))
            Gap(12.dp)
            T("Pandy", 24.sp, weight = FontWeight.Bold)
            T("Version ${pl.appVersion}", 13.sp, C.Sub)
            Gap(20.dp)
            T("A Bambu Handy look-alike that works directly over the local network or through Home Assistant. Not made by or affiliated with Bambu Lab.", 14.sp, C.Sub,
                align = androidx.compose.ui.text.style.TextAlign.Center, lh = 20.sp)
            Gap(20.dp)
            T("Developed by Giancarlo Cammelli", 14.sp)
            Gap(20.dp)
            T("Source code and setup guide ›", 14.sp, C.Blue, modifier = Modifier.clickable { pl.openUrl(REPO_URL) }.padding(8.dp))
            T("Buy me a coffee ›", 14.sp, C.Blue, modifier = Modifier.clickable { pl.openUrl(io.github.gccammelli.pandy.DONATE_URL) }.padding(8.dp))
        }
    }
}

// ---------- MakerWorld ----------

/** Models: MakerWorld in a web view, signed in with the user's own Bambu account; "Print" goes through Pandy. */
@Composable
fun ModelsTab() = Column(Modifier.fillMaxSize().background(C.Card).statusBarsPadding()) {
    MakerWorld(Modifier.fillMaxSize())
}

@Composable
fun WebScreen(url: String, title: String) = Page(title, { Nav.back() }) {
    io.github.gccammelli.pandy.WebPage(url, Modifier.fillMaxSize(), onDownload = null)
}


fun needHa() = Repo.extras ?: throw io.github.gccammelli.pandy.ApiException(io.github.gccammelli.pandy.DirectPrinter.NEEDS_HA)

/** What a Home Assistant-only feature shows in direct mode, with the way to add Home Assistant. */
@Composable
fun NeedsHa(what: String) = Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
    Gap(60.dp)
    Icon(Icons.Outlined.History, null, Modifier.size(64.dp), tint = C.Track)
    Gap(16.dp)
    T("$what comes from Home Assistant", 16.sp, weight = FontWeight.Medium, align = androidx.compose.ui.text.style.TextAlign.Center)
    Gap(8.dp)
    T("Pandy talks to your printer directly. Add Home Assistant with the Bambu Lab integration to also get print history, cached files, timelapses and printing MakerWorld models.",
        14.sp, C.Sub, align = androidx.compose.ui.text.style.TextAlign.Center, lh = 20.sp)
    Gap(20.dp)
    AccentButton("Add Home Assistant", Modifier.padding(horizontal = 24.dp)) { Nav.go(Screen.AddHa) }
}
