// SPDX-License-Identifier: MIT
// Copyright (c) Giancarlo Cammelli
package io.github.gccammelli.pandy.studio.slicer

/** The engine build the app carries, as the equivalence report identifies it. */
data class EngineInfo(val version: String, val engineSha256: String, val resourcesSha256: String, val id: String = "bambu", val name: String = "Bambu Studio")

data class EngineChoice(val id: String, val name: String, val version: String)

/** Boundary implemented by the Android plugin and, later, an iOS engine. */
interface StudioEngine {
    suspend fun profiles(): ProfileSource
    suspend fun slice(job: SliceJob, onLog: (String) -> Unit): SliceRun
}

/** A finished engine run, platform-neutral: what it reported, the sliced 3MF's bytes and the time it took. */
class SliceRun(val outcome: SliceOutcome?, val exitCode: Int, val sliced: ByteArray?, val millis: Long, val log: List<String>, val handle: Any?) {
    val ok get() = exitCode == 0 && outcome?.ok == true && sliced != null
}
