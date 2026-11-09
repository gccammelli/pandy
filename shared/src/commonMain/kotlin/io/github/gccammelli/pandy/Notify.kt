package io.github.gccammelli.pandy

/** Categories of print events to tune importance, sounds, or actions. */
enum class PrintEventType { FINISH, FAILED, PAUSED, ATTENTION }

/** Interactive actions the user can trigger directly from a notification. */
enum class PrintAction(val id: String, val label: String) {
    PAUSE("pause", "Pause"),
    RESUME("resume", "Resume"),
    STOP("stop", "Stop"),
    SHARE("share", "Share")
}

/** Shows a phone notification. Android provides one; iOS can't watch in the background, so it has none. */
interface Notifier {
    /** [key] (the printer's serial) makes a newer notification replace an older one for the same printer. */
    fun show(key: String, title: String, text: String)

    /** Dispatches a full [PrintEvent] with rich metadata and actions. */
    fun showEvent(event: PrintEvent) {
        show(event.serial ?: "pandy", event.title, event.text)
    }
}

class PrintEvent(
    val title: String,
    val text: String,
    val type: PrintEventType = PrintEventType.ATTENTION,
    val actions: List<PrintAction> = emptyList(),
    val serial: String? = null,
    val imagePath: String? = null,
    val fallbackImagePath: String? = null
)

/** What changed on one printer between two readings that the user wants to hear about, if anything. [before] null: first reading, so nothing to compare. */
fun printEvent(before: Printer?, now: Printer): PrintEvent? {
    if (before == null) return null
    val job = now.task ?: before.task ?: "The print"
    val cameraSnap = now.camera?.let { "/api/camera_proxy/$it" }
    if (before.printing && now.status == "finish") {
        return PrintEvent(
            title = "Print finished",
            text = "$job is done on ${now.name}",
            type = PrintEventType.FINISH,
            actions = listOf(PrintAction.SHARE),
            serial = now.serial,
            imagePath = cameraSnap ?: now.coverImage,
            fallbackImagePath = if (cameraSnap != null) now.coverImage else null
        )
    }
    if (before.printing && now.status == "failed") {
        return PrintEvent(
            title = "Print failed",
            text = "${now.printError ?: job} (${now.name})",
            type = PrintEventType.FAILED,
            serial = now.serial,
            imagePath = cameraSnap ?: now.coverImage,
            fallbackImagePath = if (cameraSnap != null) now.coverImage else null
        )
    }
    // Routine notices ("Inspecting the first layer: please wait a moment") only matter when they stop the print.
    val fresh = now.hms.firstOrNull { it !in before.hms && (now.status == "pause" || (hmsLevel(it.first) ?: 1) <= 2) } ?: return null
    val actions = if (now.status == "pause") listOf(PrintAction.RESUME, PrintAction.STOP) else listOf(PrintAction.PAUSE, PrintAction.STOP)
    return PrintEvent(
        title = "${now.name} needs attention",
        text = fresh.second,
        type = PrintEventType.ATTENTION,
        actions = actions,
        serial = now.serial,
        imagePath = cameraSnap ?: now.coverImage,
        fallbackImagePath = if (cameraSnap != null) now.coverImage else null
    )
}

/** An HMS code's level, its third group ("HMS_0C00_0300_0003_000B": 3): 1 fatal, 2 serious, 3 common, 4 info. */
private fun hmsLevel(code: String) = code.split('_').getOrNull(3)?.toIntOrNull(16)
