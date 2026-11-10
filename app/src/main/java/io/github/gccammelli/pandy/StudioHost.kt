package io.github.gccammelli.pandy

import io.github.gccammelli.pandy.studio.Platform
import io.github.gccammelli.pandy.studio.slicer.EngineInfo
import io.github.gccammelli.pandy.studio.slicer.StudioEngine

/** What the Studio tab runs on: the slicer plugin for profiles and slicing, Pandy for files and settings. */
class StudioHost(private val activity: MainActivity, private val plugin: StudioPluginClient) : Platform, StudioEngine by plugin {
    override suspend fun pickModels() = activity.pickModels()
    override suspend fun engineInfo() = plugin.capabilities().let {
        EngineInfo(it.optString("engine_version"), it.optString("engine_sha256"), it.optString("resource_version"))
    }
    override fun loadSetting(key: String) = Repo.setting("studio_$key")
    override fun saveSetting(key: String, value: String) = Repo.setSetting("studio_$key", value)
}
