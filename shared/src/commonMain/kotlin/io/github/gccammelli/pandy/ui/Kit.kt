package io.github.gccammelli.pandy.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBackIos
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.gccammelli.pandy.LocalPlatform
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** "system", "light" or "dark"; the app's choice, saved by Repo. */
object ThemeMode {
    var mode by mutableStateOf("system")
    /** What's on screen now (set by [AppTheme]). */
    var dark by mutableStateOf(false)
}

/** Colours: layout greys sampled from Bambu Handy's screenshots; dark ones are Pandy's own, in the same spirit. */
object C {
    private fun c(light: Long, dark: Long) = Color(if (ThemeMode.dark) dark else light)
    val Bg get() = c(0xFFF4F4F4, 0xFF111111)
    val Card get() = c(0xFFFFFFFF, 0xFF1D1D1D)
    val Text get() = c(0xFF1F1F1F, 0xFFE8E8E8)
    val Sub get() = c(0xFF8C8C8C, 0xFFA0A0A0)
    val Faint get() = c(0xFFB5B5B5, 0xFF6E6E6E)
    /**
     * Pandy's accent: Bambu green (#00AE42) turned 180° round the colour wheel, same saturation and lightness, so
     * Pandy looks like Handy's mirror image. Selected tab, switches, primary buttons.
     */
    val Accent get() = c(0xFFAE006D, 0xFFE0409C)
    val AccentBg get() = c(0xFFFBEAF4, 0xFF3A1A2D)
    /** Status green: online, done, dry. */
    val Ok get() = c(0xFF00A33E, 0xFF2FC768)
    val Red get() = c(0xFFE5484D, 0xFFFF6B6B)
    val RedBg get() = c(0xFFFFF1F1, 0xFF3A1E1F)
    val Orange get() = c(0xFFFF8A00, 0xFFFFA23A)
    val Blue get() = c(0xFF1677FF, 0xFF5AA2FF)
    /** Grey chips and keys (fan −/+, speed presets). */
    val Fill get() = c(0xFFF2F2F2, 0xFF2A2A2A)
    val Track get() = c(0xFFE0E0E0, 0xFF3A3A3A)
    val Line get() = c(0xFFEDEDED, 0xFF2C2C2C)
    val Nav get() = c(0xFFFFFFFF, 0xFF1A1A1A)
    val Scrim get() = c(0x99000000, 0xB3000000)
    val OnAccent get() = Color.White
}

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    val system = isSystemInDarkTheme()
    ThemeMode.dark = when (ThemeMode.mode) { "light" -> false; "dark" -> true; else -> system }
    val p = LocalPlatform.current
    LaunchedEffect(ThemeMode.dark) { p.setDarkBars(ThemeMode.dark) }
    MaterialTheme(
        colorScheme = (if (ThemeMode.dark) darkColorScheme() else lightColorScheme())
            .copy(primary = C.Accent, onPrimary = C.OnAccent, background = C.Bg, surface = C.Card, onSurface = C.Text, onBackground = C.Text, onSurfaceVariant = C.Sub),
        content = content,
    )
}

@Composable
fun T(text: String, size: TextUnit = 16.sp, color: Color = C.Text, weight: FontWeight = FontWeight.Normal, modifier: Modifier = Modifier, align: TextAlign? = null, lh: TextUnit = TextUnit.Unspecified, maxLines: Int = Int.MAX_VALUE) =
    Text(text, modifier, color = color, style = TextStyle(fontSize = size, fontWeight = weight, lineHeight = lh), textAlign = align, maxLines = maxLines, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)

/** White screen with a centred title and a back chevron, as on Handy's detail pages. */
@Composable
fun Page(title: String, onBack: (() -> Unit)?, bg: Color = C.Bg, action: (@Composable () -> Unit)? = null, bottom: (@Composable () -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxSize().background(bg)) {
        Box(Modifier.fillMaxWidth().background(C.Card).statusBarsPadding().height(56.dp)) {
            if (onBack != null) BackChevron(onBack, Modifier.align(Alignment.CenterStart))
            T(title, 18.sp, weight = FontWeight.Medium, modifier = Modifier.align(Alignment.Center).padding(horizontal = 56.dp), align = TextAlign.Center)
            if (action != null) Box(Modifier.align(Alignment.CenterEnd).padding(end = 12.dp)) { action() }
        }
        Column(Modifier.weight(1f).fillMaxWidth(), content = content)
        if (bottom != null) bottom()
    }
}

@Composable
fun BackChevron(onBack: () -> Unit, modifier: Modifier = Modifier) =
    Box(modifier.size(56.dp).clickable(onClick = onBack), contentAlignment = Alignment.Center) {
        Icon(Icons.AutoMirrored.Filled.ArrowBackIos, "Back", Modifier.size(22.dp).padding(start = 4.dp), tint = C.Text)
    }

@Composable
fun Card(modifier: Modifier = Modifier, radius: Dp = 12.dp, padding: PaddingValues = PaddingValues(16.dp), onClick: (() -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) =
    Column(modifier.fillMaxWidth().clip(RoundedCornerShape(radius)).background(C.Card)
        .let { if (onClick != null) it.clickable(onClick = onClick) else it }.padding(padding), content = content)

/** A settings row: label, grey value, chevron. */
@Composable
fun NavRow(label: String, value: String? = null, labelColor: Color = C.Text, chevron: Boolean = true, leading: (@Composable () -> Unit)? = null, onClick: (() -> Unit)? = null) =
    Row(Modifier.fillMaxWidth().background(C.Card).let { if (onClick != null) it.clickable(onClick = onClick) else it }.padding(horizontal = 24.dp, vertical = 22.dp),
        verticalAlignment = Alignment.CenterVertically) {
        if (leading != null) { leading(); Spacer(Modifier.width(10.dp)) }
        T(label, 15.sp, labelColor, modifier = Modifier.weight(1f))
        if (value != null) T(value, 14.sp, C.Sub)
        if (chevron) Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, Modifier.size(20.dp), tint = C.Faint)
    }

@Composable
fun ToggleRow(label: String, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) =
    Row(Modifier.fillMaxWidth().background(C.Card).padding(horizontal = 24.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        T(label, 17.sp, if (enabled) C.Text else C.Sub, modifier = Modifier.weight(1f))
        Toggle(checked, enabled, onChange)
    }

@Composable
fun Toggle(checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) = Switch(checked, onChange, enabled = enabled,
    colors = SwitchDefaults.colors(checkedTrackColor = C.Accent, checkedThumbColor = Color.White, uncheckedTrackColor = C.Track,
        uncheckedThumbColor = Color.White, uncheckedBorderColor = C.Track, checkedBorderColor = C.Accent,
        disabledCheckedTrackColor = C.Accent.copy(alpha = .4f), disabledUncheckedTrackColor = C.Fill))

/** The grey gap between grouped white sections. */
@Composable
fun Gap(h: Dp = 12.dp) = Spacer(Modifier.height(h))

@Composable
fun AccentButton(text: String, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) =
    Box(modifier.fillMaxWidth().height(48.dp).clip(RoundedCornerShape(24.dp)).background(if (enabled) C.Accent else C.Track)
        .clickable(enabled = enabled, onClick = onClick), contentAlignment = Alignment.Center) {
        T(text, 16.sp, if (enabled) C.OnAccent else C.Sub, FontWeight.Medium) // disabled: muted, but readable in both themes
    }

@Composable
fun OutlineButton(text: String, color: Color, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) =
    Box(modifier.fillMaxWidth().height(56.dp).clip(RoundedCornerShape(8.dp)).border(1.dp, color, RoundedCornerShape(8.dp)).background(C.Card)
        .clickable(enabled = enabled, onClick = onClick), contentAlignment = Alignment.Center) {
        T(text, 16.sp, if (enabled) color else C.Sub)
    }

/** Handy's bottom sheet: centred title, close X top right, content, then an optional green button. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Sheet(onDismiss: () -> Unit, title: String? = null, centeredTitle: Boolean = false, confirm: String? = "Confirm", onConfirm: () -> Unit = {}, confirmEnabled: Boolean = true, content: @Composable ColumnScope.() -> Unit) {
    ModalBottomSheet(onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = C.Card,
        dragHandle = null, shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 24.dp)) {
            Box(Modifier.fillMaxWidth().height(64.dp)) {
                if (title != null && centeredTitle) T(title, 17.sp, weight = FontWeight.Medium, modifier = Modifier.align(Alignment.Center))
                Icon(Icons.Default.Close, "Close", Modifier.align(Alignment.CenterEnd).size(28.dp).clickable(onClick = onDismiss), tint = C.Text)
            }
            if (title != null && !centeredTitle) T(title, 18.sp, weight = FontWeight.Medium, modifier = Modifier.padding(bottom = 12.dp))
            content()
            if (confirm != null) { Gap(24.dp); AccentButton(confirm, enabled = confirmEnabled, onClick = onConfirm); Gap(20.dp) }
        }
    }
}

/** A thin rounded progress bar (Status card). */
@Composable
fun Bar(fraction: Float, color: Color, modifier: Modifier = Modifier, track: Color = C.Line, height: Dp = 5.dp) =
    Box(modifier.fillMaxWidth().height(height).clip(CircleShape).background(track)) {
        Box(Modifier.fillMaxWidth(fraction.coerceIn(0f, 1f)).height(height).clip(CircleShape).background(color))
    }

@Composable
fun SmallIcon(v: ImageVector, tint: Color = C.Text, size: Dp = 20.dp) = Icon(v, null, Modifier.size(size), tint = tint)

@Composable
fun Radio(selected: Boolean, label: String, enabled: Boolean = true, onClick: () -> Unit) =
    Row(Modifier.fillMaxWidth().background(C.Card).clickable(enabled = enabled, onClick = onClick).padding(horizontal = 24.dp, vertical = 20.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(Modifier.size(18.dp).clip(CircleShape).border(1.dp, if (selected) C.Accent else C.Faint, CircleShape), contentAlignment = Alignment.Center) {
            if (selected) Box(Modifier.size(10.dp).clip(CircleShape).background(C.Accent))
        }
        T(label, 16.sp, if (selected && enabled) C.Text else C.Faint)
    }

/** What an optional part needs before it works, with its setup guide (a path in Pandy's repository). */
@Composable
fun SetupNote(text: String, guidePath: String, modifier: Modifier = Modifier) {
    val p = io.github.gccammelli.pandy.LocalPlatform.current
    Column(modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).border(1.dp, C.Line, RoundedCornerShape(12.dp))
        .clickable { p.openUrl(io.github.gccammelli.pandy.guide(guidePath)) }.padding(16.dp)) {
        T(text, 14.sp, C.Sub, lh = 19.sp)
        Gap(6.dp)
        T("Setup guide ›", 14.sp, C.Blue, FontWeight.Medium)
    }
}
