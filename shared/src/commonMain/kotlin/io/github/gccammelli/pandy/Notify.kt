package io.github.gccammelli.pandy

/** Shows a phone notification. Android provides one; iOS can't watch in the background, so it has none. */
interface Notifier {
    /** [key] (the printer's serial) makes a newer notification replace an older one for the same printer. */
    fun show(key: String, title: String, text: String)
}

class PrintEvent(val title: String, val text: String)

/** What changed on one printer between two readings that the user wants to hear about, if anything. [before] null: first reading, so nothing to compare. */
fun printEvent(before: Printer?, now: Printer): PrintEvent? {
    if (before == null) return null
    val job = now.task ?: before.task ?: "The print"
    if (before.printing && now.status == "finish") return PrintEvent("Print finished", "$job is done on ${now.name}")
    if (before.printing && now.status == "failed") return PrintEvent("Print failed", "${now.printError ?: job} (${now.name})")
    // Routine notices ("Inspecting the first layer: please wait a moment") only matter when they stop the print.
    val fresh = now.hms.firstOrNull { it !in before.hms && (now.status == "pause" || (hmsLevel(it.first) ?: 1) <= 2) } ?: return null
    return PrintEvent("${now.name} needs attention", fresh.second)
}

/** An HMS code's level, its third group ("HMS_0C00_0300_0003_000B": 3): 1 fatal, 2 serious, 3 common, 4 info. */
private fun hmsLevel(code: String) = code.split('_').getOrNull(3)?.toIntOrNull(16)
