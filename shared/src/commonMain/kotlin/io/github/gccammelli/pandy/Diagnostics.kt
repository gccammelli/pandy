package io.github.gccammelli.pandy

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

private const val MASK = "[redacted]"

/** Removes identifiers and credentials before diagnostics leave the process. */
fun redact(text: String, secrets: List<String> = emptyList()): String {
    var result = text
    secrets.filter { it.isNotEmpty() }.sortedByDescending { it.length }.forEach { secret ->
        result = result.replace(secret, MASK, ignoreCase = true)
    }
    val patterns = listOf(
        "(?i)\\bBearer\\s+[^\\s,;]+",
        "\\beyJ[A-Za-z0-9_-]+(?:\\.[A-Za-z0-9_-]+){1,2}\\b",
        "(?i)(?:https?://|rtsp://)[^\\s/@]+@",
        "(?i)(?<![a-z0-9])(?:[a-z0-9]+[_-])*(?:code|password|pass|access(?:[_ -]?(?:code|token))?|token|ssid|wi[- ]?fi(?:[_ -]?ssid)?)[\"']?\\s*[:=]\\s*(?:\"(?:\\\\.|[^\"\\\\])*\"|'(?:\\\\.|[^'\\\\])*'|[^\\s,;}]+)",
        "(?i)\\b(?:[0-9a-f]{2}[:-]){5}[0-9a-f]{2}\\b",
        "(?i)\\b[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}\\b",
        "\\b(?:\\d{1,3}\\.){3}\\d{1,3}\\b",
        "(?i)\\b[A-Z0-9]{15}\\b",
        "(?i)\\b(?:[a-z0-9-]+\\.)+[a-z][a-z0-9-]*\\b"
    )
    patterns.forEach { result = Regex(it).replace(result, MASK) }
    return result
}

/** Bounded, thread-safe in-memory diagnostics; no data is persisted. */
object AppLog {
    private const val LIMIT = 300
    private val entries = MutableStateFlow<List<String>>(emptyList())

    fun log(level: String, tag: String, message: String, secrets: List<String> = emptyList()) {
        val line = redact("$level $tag: $message", secrets).replace('\n', ' ').replace('\r', ' ')
        entries.update { (it + line).takeLast(LIMIT) }
    }

    fun snapshot(): List<String> = entries.value
    fun clear() { entries.value = emptyList() }
}

/** Only these fields are eligible for inclusion in an exported report. */
data class DiagnosticsInfo(
    val appVersion: String,
    val platform: String,
    val osVersion: String?,
    val mode: String,
    val savedPrinters: Int,
    val model: String?,
    val firmware: String?,
    val amsTypes: List<String>,
    val cameraType: String?
)

fun diagnosticsReport(info: DiagnosticsInfo, lines: List<String>, secrets: List<String> = emptyList()): String {
    val report = buildString {
        appendLine("Pandy diagnostics")
        appendLine("App version: ${info.appVersion}")
        appendLine("Platform: ${info.platform}")
        appendLine("OS version: ${info.osVersion ?: "Unknown"}")
        appendLine("Connection mode: ${info.mode}")
        appendLine("Saved printers: ${info.savedPrinters}")
        appendLine("Printer model: ${info.model ?: "Unknown"}")
        appendLine("Firmware: ${info.firmware ?: "Unknown"}")
        appendLine("AMS units: ${info.amsTypes.size}")
        appendLine("AMS types: ${info.amsTypes.joinToString().ifEmpty { "None" }}")
        appendLine("Camera type: ${info.cameraType ?: "Unknown"}")
        appendLine()
        appendLine("Log:")
        lines.forEach { appendLine(it) }
    }
    return redact(report, secrets)
}
