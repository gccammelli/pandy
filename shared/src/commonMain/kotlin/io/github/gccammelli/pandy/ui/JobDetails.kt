package io.github.gccammelli.pandy.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.ContentCut
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.gccammelli.pandy.LocalPlatform
import io.github.gccammelli.pandy.PreviewMesh
import io.github.gccammelli.pandy.Printer
import io.github.gccammelli.pandy.Repo
import io.github.gccammelli.pandy.activePlate
import io.github.gccammelli.pandy.activeSpool
import io.github.gccammelli.pandy.button
import io.github.gccammelli.pandy.printHistory
import io.github.gccammelli.pandy.readProject
import io.github.gccammelli.pandy.setSpeed
import io.github.gccammelli.pandy.slicePlateInfo
import kotlinx.coroutines.launch
import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.math.roundToInt

/** Formats the print summary into human-readable text for sharing. */
fun buildPrintShareText(p: Printer): String {
    val model = p.task ?: "3D Print"
    val printerName = p.name.ifBlank { p.model }
    val totalG = p.printWeight
    val totalM = p.printLength
    val lines = mutableListOf<String>()
    lines += "Print on $printerName: $model"
    if (p.printing) {
        lines += "• Progress: ${p.progress}%"
        val left = formatFinishTime(p.remaining)
        if (left != null) lines += "• Est. Finish: $left"
    } else if (p.status == "finish") {
        lines += "• Status: Completed successfully"
    }
    if (totalG != null && totalG > 0) {
        val usedG = formatDec(totalG * (p.progress / 100.0))
        lines += "• Weight: $usedG g / ${formatDec(totalG)} g"
        val rate = Repo.spoolPricePerKg(p.activeSpool()?.second)
        if (rate != null) {
            val cost = formatDec((totalG / 1000.0) * rate, 2)
            lines += "• Estimated Cost: ~$cost ${Repo.filamentCurrency}"
        }
    }
    if (totalM != null && totalM > 0) {
        lines += "• Length: ${formatDec(totalM)} m"
    }
    return lines.joinToString("\n")
}

/** Formats the expected finish time on the user's clock (e.g. "17:45"). */
fun formatFinishTime(remainingMinutes: Double?): String? {
    if (remainingMinutes == null || remainingMinutes <= 0.0) return null
    val finishMs = Clock.System.now().toEpochMilliseconds() + (remainingMinutes * 60_000).toLong()
    val local = Instant.fromEpochMilliseconds(finishMs).toLocalDateTime(TimeZone.currentSystemDefault())
    val hh = local.hour.toString().padStart(2, '0')
    val mm = local.minute.toString().padStart(2, '0')
    return "$hh:$mm"
}

private fun formatDec(v: Double, decimals: Int = 1): String {
    val factor = if (decimals == 2) 100.0 else 10.0
    return ((v * factor).roundToInt() / factor).toString()
}

private const val MAX_PREVIEW_3MF_BYTES = 16L * 1024 * 1024

private data class ExtraJobData(
    val coverUrl: String? = null,
    val modelPath: String? = null,
    val weightGrams: Double? = null,
    val lengthMeters: Double? = null,
    val loaded: Boolean = true
)

/**
 * Detailed Print Job Inspector: expanded model preview with contrast selector,
 * progress breakdown with clock completion time, on-the-fly speed controls,
 * material consumption and estimated print costs.
 */
@Composable
fun JobDetailsScreen(p: Printer) {
    val err = errorToast()
    val pl = LocalPlatform.current
    val scope = rememberCoroutineScope()
    var confirmStop by remember { mutableStateOf(false) }
    // 0 = Default Theme, 1 = High-contrast Light, 2 = High-contrast Dark
    var contrastMode by remember { mutableStateOf(0) }
    var show3d by remember { mutableStateOf(false) }

    val extraJobData by produceState<ExtraJobData?>(null, p.task, p.serial) {
        val extras = Repo.extras
        if (extras != null) {
            val history = runCatching { extras.printHistory(p.serial) }.getOrNull().orEmpty()
            val match = p.task?.let { t ->
                val clean = t.removeSuffix(".3mf").removeSuffix(".gcode").replace('_', ' ')
                history.firstOrNull { h ->
                    h.title.equals(clean, ignoreCase = true) || h.name.contains(clean, ignoreCase = true)
                }
            }

            if (match != null) {
                val sliceXml = runCatching {
                    extras.get("/api/bambu_lab/file_cache/" + match.path.removeSuffix(".3mf") + ".slice_info.config")
                }.getOrNull()
                val plate = p.activePlate()
                val plateInfo = sliceXml?.let { slicePlateInfo(it, plate) }

                value = ExtraJobData(
                    coverUrl = match.thumbUrl,
                    modelPath = match.path.takeIf { match.size in 1..MAX_PREVIEW_3MF_BYTES },
                    weightGrams = plateInfo?.weightGrams,
                    lengthMeters = plateInfo?.lengthMeters,
                    loaded = true
                )
            } else {
                value = ExtraJobData(loaded = true)
            }
        } else {
            value = ExtraJobData(loaded = true)
        }
    }

    var meshLoading by remember { mutableStateOf(false) }
    val lazyMesh by produceState<PreviewMesh?>(p.mesh, show3d, p.task, extraJobData?.modelPath) {
        if (!show3d || p.mesh != null) {
            value = p.mesh
            meshLoading = false
            return@produceState
        }
        meshLoading = true
        val directMesh = Repo.directPrinter?.let { runCatching { it.loadJobMesh() }.getOrNull() }
        val extrasMesh = if (directMesh == null && extraJobData?.modelPath != null) {
            val path = extraJobData!!.modelPath!!
            runCatching {
                val modelBytes = Repo.extras?.bytes("/api/bambu_lab/file_cache/$path")
                if (modelBytes != null && modelBytes.size <= MAX_PREVIEW_3MF_BYTES) readProject(modelBytes).mesh else null
            }.getOrNull()
        } else null
        value = directMesh ?: extrasMesh
        meshLoading = false
    }

    val mesh = lazyMesh ?: p.mesh
    val canShow3d = mesh != null || extraJobData?.modelPath != null || Repo.directPrinter != null
    val coverImg = p.coverImage ?: extraJobData?.coverUrl
    val totalG = p.printWeight ?: extraJobData?.weightGrams
    val totalM = p.printLength ?: extraJobData?.lengthMeters
    val isExtraLoaded = extraJobData?.loaded == true || (mesh != null && totalG != null)

    Page("Print Details", { Nav.back() }) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
            Gap(12.dp)

            // ---------- 1. Model Preview with Contrast Toggle & 3D Toggle ----------
            val previewBg = when (contrastMode) {
                1 -> Color(0xFFF7F7F7)
                2 -> Color(0xFF141414)
                else -> C.Fill
            }
            val activeSpool = p.activeSpool()?.second
            val spoolColor = activeSpool?.color ?: 0xFF888888L
            Box(
                Modifier.fillMaxWidth().height(210.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(previewBg)
                    .border(1.dp, C.Line, RoundedCornerShape(12.dp))
                    .clickable {
                        if (canShow3d) show3d = !show3d
                    },
                contentAlignment = Alignment.Center
            ) {
                when {
                    show3d && mesh != null -> {
                        PreviewMeshCanvas(mesh, listOf(spoolColor), showPlate = true)
                    }
                    show3d && meshLoading -> {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator(color = C.Accent, modifier = Modifier.size(32.dp), strokeWidth = 2.5.dp)
                            Gap(10.dp)
                            T("Loading 3D preview...", 12.sp, C.Sub)
                        }
                    }
                    coverImg != null -> {
                        HaImage(coverImg, Modifier.fillMaxSize().padding(12.dp))
                    }
                    mesh != null -> {
                        PreviewMeshCanvas(mesh, listOf(spoolColor), showPlate = true)
                    }
                    !isExtraLoaded -> {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator(color = C.Accent, modifier = Modifier.size(32.dp), strokeWidth = 2.5.dp)
                            Gap(10.dp)
                            T("Loading model preview...", 12.sp, C.Sub)
                        }
                    }
                    else -> {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            PrinterArt(Modifier.size(72.dp), ams = false, lit = false)
                            Gap(8.dp)
                            T(p.task ?: "Current Print", 13.sp, C.Text, FontWeight.Medium)
                            T("3D preview not included in print file", 11.sp, C.Sub)
                        }
                    }
                }

                Row(
                    Modifier.align(Alignment.BottomEnd).padding(10.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    if (canShow3d) {
                        Box(
                            Modifier.clip(RoundedCornerShape(16.dp))
                                .background(if (show3d) C.Accent else C.Card.copy(alpha = 0.85f))
                                .border(1.dp, if (show3d) C.Accent else C.Line, RoundedCornerShape(16.dp))
                                .clickable { show3d = !show3d }
                                .padding(horizontal = 10.dp, vertical = 5.dp)
                        ) {
                            T(if (show3d) "3D (Active)" else "View 3D", 11.sp, if (show3d) C.OnAccent else C.Text, FontWeight.Bold)
                        }
                    }

                    Box(
                        Modifier.clip(RoundedCornerShape(16.dp))
                            .background(C.Card.copy(alpha = 0.85f))
                            .border(1.dp, C.Line, RoundedCornerShape(16.dp))
                            .clickable { contrastMode = (contrastMode + 1) % 3 }
                            .padding(horizontal = 10.dp, vertical = 5.dp)
                    ) {
                        val label = when (contrastMode) {
                            1 -> "Contrast: Light"
                            2 -> "Contrast: Dark"
                            else -> "Contrast: Auto"
                        }
                        T(label, 11.sp, C.Text, FontWeight.Medium)
                    }
                }
            }

            Gap(14.dp)
            T(p.task ?: "Current Print", 20.sp, weight = FontWeight.Bold, maxLines = 2)
            Gap(2.dp)
            val subDetails = listOfNotNull(
                p.model,
                stageText(p.stage),
                p.layer?.let { "Layer $it of ${p.layers ?: "?"}" }
            ).joinToString(" · ")
            T(subDetails, 13.sp, C.Sub)

            Gap(14.dp)

            // ---------- 2. Progress & Timing Card ----------
            Card(padding = androidx.compose.foundation.layout.PaddingValues(16.dp)) {
                Row(verticalAlignment = Alignment.Bottom) {
                    T("${p.progress}", 28.sp, weight = FontWeight.Bold)
                    T("%", 14.sp, C.Sub, modifier = Modifier.padding(bottom = 4.dp))
                    Spacer(Modifier.weight(1f))
                    val finish = formatFinishTime(p.remaining)
                    if (finish != null) {
                        T("Ends at $finish", 13.sp, C.Accent, FontWeight.Medium)
                    } else {
                        T(if (p.paused) "Paused" else "About ${minutes(p.remaining)} left", 13.sp, C.Sub)
                    }
                }
                Gap(8.dp)
                Bar(p.progress / 100f, C.Accent, height = 6.dp)
                Gap(10.dp)
                Row {
                    T("Remaining: ${minutes(p.remaining)}", 12.sp, C.Sub)
                    Spacer(Modifier.weight(1f))
                    T("Layer ${p.layer ?: "?"}/${p.layers ?: "?"}", 12.sp, C.Sub)
                }
            }

            if (p.printing) {
                Gap(14.dp)

                // ---------- 3. Print Speed Selector (On-the-Fly) ----------
                T("Print Speed", 15.sp, weight = FontWeight.Bold)
                Gap(8.dp)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    val orderedSpeeds = listOf("silent" to "Silent\n50%", "standard" to "Standard\n100%", "sport" to "Sport\n124%", "ludicrous" to "Ludicrous\n166%")
                    orderedSpeeds.forEach { (key, title) ->
                        val sel = p.speed == key
                        Box(
                            Modifier.weight(1f).clip(RoundedCornerShape(10.dp))
                                .background(if (sel) C.Accent else C.Fill)
                                .border(1.dp, if (sel) C.Accent else C.Line, RoundedCornerShape(10.dp))
                                .clickable { p.setSpeed(key, err) }
                                .padding(vertical = 10.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            T(title, 12.sp, if (sel) C.OnAccent else C.Text, weight = if (sel) FontWeight.Bold else FontWeight.Normal, align = androidx.compose.ui.text.style.TextAlign.Center)
                        }
                    }
                }
            }

            Gap(16.dp)

            // ---------- 4. Filament & Cost Estimation Card ----------
            T("Material & Cost", 15.sp, weight = FontWeight.Bold)
            Gap(8.dp)
            Card(padding = androidx.compose.foundation.layout.PaddingValues(16.dp)) {
                val feeding = p.activeSpool()
                if (feeding != null) {
                    val (slotName, spool) = feeding
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(12.dp).clip(CircleShape).background(Color(spool.color)))
                        Spacer(Modifier.width(8.dp))
                        T("$slotName · ${spool.name.ifBlank { spool.type }}", 14.sp, weight = FontWeight.Medium)
                        Spacer(Modifier.weight(1f))
                        if (spool.remain in 0..100) {
                            T("${spool.remain}% remain", 12.sp, C.Sub)
                        }
                    }
                    Gap(12.dp)
                }

                if (totalG != null && totalG > 0) {
                    val usedG = totalG * (p.progress / 100.0)
                    DetailMetricRow("Weight", "${formatDec(usedG)} g / ${formatDec(totalG)} g")
                    Gap(8.dp)
                } else if (!isExtraLoaded) {
                    DetailMetricRow("Weight", "Calculating from slice data...")
                    Gap(8.dp)
                } else {
                    DetailMetricRow("Weight", "Not specified in print file")
                    Gap(8.dp)
                }
                if (totalM != null && totalM > 0) {
                    val usedM = totalM * (p.progress / 100.0)
                    DetailMetricRow("Length", "${formatDec(usedM)} m / ${formatDec(totalM)} m")
                    Gap(8.dp)
                }

                val rate = Repo.spoolPricePerKg(feeding?.second)
                val currency = Repo.filamentCurrency
                if (totalG != null && totalG > 0 && rate != null) {
                    val costUsed = (totalG * (p.progress / 100.0) / 1000.0) * rate
                    val costTotal = (totalG / 1000.0) * rate
                    DetailMetricRow("Estimated Cost (${formatDec(rate)} $currency/kg)", "${formatDec(costUsed, 2)} $currency used (${formatDec(costTotal, 2)} $currency total)")
                } else if (!isExtraLoaded) {
                    DetailMetricRow("Estimated Cost", "Calculating...")
                } else {
                    DetailMetricRow("Status", stageText(p.stage) ?: if (p.status == "finish") "Completed" else "Printing")
                }
            }

            Gap(16.dp)

            // ---------- 5. Job Actions (Skip / Pause / Stop) ----------
            if (p.printing) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    if (p.objects.size > 1) {
                        JobButton(Icons.Outlined.ContentCut, "Skip Parts", C.Text) { Nav.go(Screen.Skip) }
                    }
                    if (p.paused) {
                        JobButton(Icons.Default.PlayArrow, "Resume", C.Text) { p.button("resume", err) }
                    } else {
                        JobButton(Icons.Default.Pause, "Pause", C.Text) { p.button("pause", err) }
                    }
                    JobButton(Icons.Default.Stop, "Stop", C.Red) { confirmStop = true }
                }
            }

            Gap(28.dp)
        }
    }

    if (confirmStop) {
        AlertDialog(
            { confirmStop = false },
            containerColor = C.Card,
            title = { T("Stop printing?", 17.sp, weight = FontWeight.Bold) },
            text = { T("The print can't be resumed after it's stopped.", 14.sp, C.Sub) },
            confirmButton = { TextButton({ confirmStop = false; p.button("stop", err) }) { T("Stop", 15.sp, C.Red, FontWeight.Medium) } },
            dismissButton = { TextButton({ confirmStop = false }) { T("Cancel", 15.sp, C.Sub) } }
        )
    }
}

@Composable
private fun DetailMetricRow(label: String, value: String) = Row(verticalAlignment = Alignment.CenterVertically) {
    T(label, 13.sp, C.Sub)
    Spacer(Modifier.weight(1f))
    T(value, 13.sp, C.Text, FontWeight.Medium)
}
