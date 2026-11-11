// SPDX-License-Identifier: MIT
// Copyright (c) Giancarlo Cammelli
package io.github.gccammelli.pandy.studio.ui

import io.github.gccammelli.pandy.ui.*

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.gccammelli.pandy.studio.slicer.Setting
import io.github.gccammelli.pandy.studio.studio.Studio

/**
 * Every process setting, on Bambu Studio's own pages and groups (Quality, Strength, Speed, Support, Others), with
 * Bambu's names and limits. Changed settings carry an orange mark, as in Bambu Studio, and can be reset one by one.
 */
@Composable
fun SettingsScreen(studio: Studio, nav: StudioNav, startPage: Int) {
    val schema = studio.schema
    var page by remember { mutableIntStateOf(startPage) }
    var query by remember { mutableStateOf("") }
    var editing by remember { mutableStateOf<String?>(null) }
    val pages = schema?.layout?.process.orEmpty().filter { it.page != "Dependencies" }
    Page("Print settings", { nav.back() }, action = {
        if (studio.overrides.isNotEmpty()) T("Reset all", 14.sp, C.Accent, FontWeight.Medium, Modifier.clickable { studio.overrides.clear(); studio.invalidate() }.padding(8.dp))
    }) {
        Column(Modifier.background(C.Card).padding(horizontal = 16.dp, vertical = 8.dp)) {
            SearchField(query, "Search ${schema?.options?.size ?: ""} settings") { query = it }
            Gap(8.dp)
            if (query.isBlank()) Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                pages.forEachIndexed { i, p ->
                    val changed = p.groups.sumOf { g -> g.options.count { it in studio.overrides } }
                    Chip(null, p.page + if (changed > 0) " · $changed" else "", i == page) { page = i }
                }
            }
        }
        val visible: List<Pair<String, List<String>>> = if (query.isNotBlank()) {
            val q = query.trim().lowercase()
            listOf("Results" to pages.flatMap { p -> p.groups.flatMap { it.options } }.distinct().filter { k ->
                k.contains(q) || schema?.get(k)?.label?.lowercase()?.contains(q) == true || schema?.get(k)?.tooltip?.lowercase()?.contains(q) == true
            })
        } else pages.getOrNull(page)?.groups?.map { it.title to it.options }.orEmpty()
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
            for ((title, options) in visible) {
                val shown = options.filter { it !in io.github.gccammelli.pandy.studio.slicer.BLOCKED_OPTIONS && (studio.process?.get(it) != null || it in studio.overrides) }
                if (shown.isEmpty()) continue
                item(key = "h$title") { T(title, 13.sp, C.Sub, FontWeight.Medium, Modifier.padding(start = 24.dp, top = 18.dp, bottom = 6.dp)) }
                item(key = "g$title") {
                    Column(Modifier.padding(horizontal = 12.dp).clip(RoundedCornerShape(12.dp)).background(C.Card)) {
                        shown.forEachIndexed { i, k ->
                            if (i > 0) Box(Modifier.padding(start = 16.dp).fillMaxWidth().height(1.dp).background(C.Line))
                            OptionRow(studio, k) { editing = k }
                        }
                    }
                }
            }
        }
    }
    editing?.let { OptionSheet(studio, it) { editing = null } }
}

@Composable
private fun OptionRow(studio: Studio, key: String, onEdit: () -> Unit) {
    val def = studio.schema?.get(key)
    val v = studio.value(key)
    val changed = key in studio.overrides
    Row(Modifier.fillMaxWidth().clickable(onClick = onEdit).padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        if (changed) { Box(Modifier.size(8.dp).clip(CircleShape).background(C.Orange)); Spacer(Modifier.width(8.dp)) }
        Column(Modifier.weight(1f)) {
            T(def?.label ?: key, 15.sp, maxLines = 2)
            if (def?.tooltip != null) T(def.tooltip, 12.sp, C.Faint, maxLines = 1)
        }
        Spacer(Modifier.width(8.dp))
        if (def?.isBool == true) Toggle(v?.first == "1") { on -> studio.set(key, withValue(v, if (on) "1" else "0")) }
        else {
            val text = v?.let { s -> if (def?.isEnum == true) def.labelOf(s.first) else s.first + (def?.sidetext?.takeIf { !s.first.endsWith("%") }?.let { " $it" } ?: "") } ?: "–"
            T(text, 14.sp, if (changed) C.Orange else C.Sub, maxLines = 1, modifier = Modifier.widthIn(max = 150.dp))
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, Modifier.size(20.dp), tint = C.Faint)
        }
    }
}

@Composable
fun AboutScreen(studio: Studio, nav: StudioNav) {
    val e = studio.engine ?: io.github.gccammelli.pandy.studio.slicer.EngineInfo("", "", "")
    Page("About the engine", { nav.back() }) {
        Column(Modifier.padding(16.dp)) {
            Card {
                T("${e.name} ${e.version}", 18.sp, weight = FontWeight.Medium)
                Gap(6.dp)
                T("Studio uses ${e.name}'s command-line slicer, built from its published source with a separate set of profiles and settings. " +
                    "The engine plugin is licensed under AGPL-3.0.", 14.sp, C.Sub, lh = 19.sp)
                Gap(10.dp)
                T("Output is compared with the matching official desktop release. Test coverage and any remaining differences are documented with the engine build.", 14.sp, C.Sub, lh = 19.sp)
            }
            Gap(12.dp)
            Card {
                T("Engine SHA-256", 13.sp, C.Sub); T(e.engineSha256, 12.sp)
                Gap(8.dp)
                T("Profiles SHA-256", 13.sp, C.Sub); T(e.resourcesSha256, 12.sp)
            }
            Gap(12.dp)
            SetupNote("How Pandy slices on the phone and tests its slicing engines.", "docs/studio.md")
        }
    }
}
