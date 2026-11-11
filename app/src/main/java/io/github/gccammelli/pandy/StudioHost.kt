package io.github.gccammelli.pandy

import io.github.gccammelli.pandy.studio.Platform
import io.github.gccammelli.pandy.studio.slicer.EngineInfo
import io.github.gccammelli.pandy.studio.slicer.StudioEngine

/** What the Studio tab runs on: the slicer plugin for profiles and slicing, Pandy for files and settings. */
class StudioHost(private val activity: MainActivity, private val plugin: StudioPluginClient) : Platform, StudioEngine by plugin {
    private var loadedCapabilities: org.json.JSONObject? = null
    override suspend fun pickModels() = activity.pickModels()
    override suspend fun engineInfo() = plugin.capabilities().let {
        loadedCapabilities = it
        EngineInfo(it.optString("engine_version"), it.optString("engine_sha256"), it.optString("resource_version"), plugin.engineId, if (plugin.engineId == "orca") "OrcaSlicer" else "Bambu Studio")
    }
    override suspend fun engines() = (loadedCapabilities ?: plugin.capabilities()).let { caps ->
        val items = caps.optJSONArray("engines")
        if (items == null) listOf(io.github.gccammelli.pandy.studio.slicer.EngineChoice("bambu", "Bambu Studio", caps.optString("engine_version")))
        else (0 until items.length()).map { i -> items.getJSONObject(i) }.filter { it.getString("id") in setOf("bambu", "orca") }.map {
            io.github.gccammelli.pandy.studio.slicer.EngineChoice(it.getString("id"), it.getString("name"), it.getString("version"))
        }
    }
    override fun forEngine(id: String) = StudioHost(activity, plugin.forEngine(id))
    private val settingPrefix get() = if (plugin.engineId == "bambu") "studio_" else "studio_${plugin.engineId}_"
    override fun loadSetting(key: String) = Repo.setting("$settingPrefix$key")
    override fun saveSetting(key: String, value: String) = Repo.setSetting("$settingPrefix$key", value)
}
