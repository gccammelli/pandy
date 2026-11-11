// SPDX-License-Identifier: MIT
// Copyright (c) Giancarlo Cammelli
package io.github.gccammelli.pandy.studio

import androidx.compose.runtime.staticCompositionLocalOf
import io.github.gccammelli.pandy.studio.slicer.EngineInfo
import io.github.gccammelli.pandy.studio.slicer.StudioEngine

/** What the Studio tab needs from the phone: the slicer plugin, a file picker and a place for its choices. */
interface Platform : StudioEngine {
    /** Lets the user pick STL or 3MF files; returns (file name, contents) for each. */
    suspend fun pickModels(): List<Pair<String, ByteArray>>
    /** The engine build the installed plugin carries. */
    suspend fun engineInfo(): EngineInfo
    suspend fun engines(): List<io.github.gccammelli.pandy.studio.slicer.EngineChoice> = engineInfo().let {
        listOf(io.github.gccammelli.pandy.studio.slicer.EngineChoice(it.id, it.name, it.version))
    }
    /** Returns a host bound to one engine; existing jobs keep their original host. */
    fun forEngine(id: String): Platform = error("Engine selection is unavailable.")
    fun loadSetting(key: String): String?
    fun saveSetting(key: String, value: String)
}

val LocalPlatform = staticCompositionLocalOf<Platform> { error("No platform") }
