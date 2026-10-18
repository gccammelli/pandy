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
    val fresh = now.hms.firstOrNull { it !in before.hms } ?: return null
    return PrintEvent("${now.name} needs attention", fresh.second)
}
