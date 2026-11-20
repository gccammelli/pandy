package io.github.gccammelli.pandy

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import io.github.gccammelli.pandy.ui.printer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

private const val ALERTS = "alerts"
private const val ALERTS_CRITICAL = "alerts_critical"
private const val WATCH = "watch"

const val ACTION_PRINTER_ACTION = "io.github.gccammelli.pandy.PRINTER_ACTION"
const val EXTRA_SERIAL = "serial"
const val EXTRA_ACTION = "action"
const val EXTRA_NOTIF_ID = "notif_id"

private fun Context.notifications() = getSystemService(NotificationManager::class.java).also { nm ->
    nm.createNotificationChannel(NotificationChannel(ALERTS, "Print alerts", NotificationManager.IMPORTANCE_HIGH))
    val critical = NotificationChannel(ALERTS_CRITICAL, "Critical printer alerts", NotificationManager.IMPORTANCE_HIGH).apply {
        enableVibration(true)
    }
    nm.createNotificationChannel(critical)
    nm.createNotificationChannel(NotificationChannel(WATCH, "Background connection", NotificationManager.IMPORTANCE_LOW))
}

private fun Context.openPandy() = PendingIntent.getActivity(
    this, 0,
    Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
)

fun Context.canNotify() = Build.VERSION.SDK_INT < 33 || checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

private fun actionIntent(ctx: Context, serial: String, action: String, notifId: Int): PendingIntent {
    val intent = Intent(ctx, NotificationActionReceiver::class.java).apply {
        this.action = ACTION_PRINTER_ACTION
        putExtra(EXTRA_SERIAL, serial)
        putExtra(EXTRA_ACTION, action)
        putExtra(EXTRA_NOTIF_ID, notifId)
    }
    val reqCode = (serial.hashCode() * 31 + action.hashCode()) * 31 + notifId
    return PendingIntent.getBroadcast(ctx, reqCode, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
}

/** Handles user actions from notifications (Pause, Resume, Stop). */
class NotificationActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val serial = intent.getStringExtra(EXTRA_SERIAL) ?: return
        val action = intent.getStringExtra(EXTRA_ACTION) ?: return
        val notifId = intent.getIntExtra(EXTRA_NOTIF_ID, -1)
        val app = context.applicationContext
        val pending = goAsync() // keeps the receiver alive until the printer has answered
        Repo.start() // the process may have been killed since the notification was posted
        CoroutineScope(Dispatchers.Default).launch {
            try {
                var error: String? = null
                val known = withTimeoutOrNull(ACTION_TIMEOUT_MS) {
                    Repo.state.first { st -> st.printers?.any { it.serial == serial } == true }
                } != null
                val job = if (known) Repo.actPrinter(serial, action) { error = it } else null
                // The command keeps running if we stop waiting, so a slow answer is not reported as a failure.
                val finished = job != null && withTimeoutOrNull(ACTION_TIMEOUT_MS) { job.join() } != null
                val failure = when {
                    job == null -> "Pandy could not reach the printer"
                    !finished -> "The printer did not confirm in time"
                    else -> error
                }
                if (failure == null) {
                    // Stop dismisses the alert only once the printer accepted it.
                    if (action == "stop" && notifId != -1) app.getSystemService(NotificationManager::class.java).cancel(notifId)
                } else {
                    AndroidNotifier(app).showEvent(PrintEvent("Could not $action the print", failure, PrintEventType.FAILED, serial = serial))
                }
            } finally {
                pending.finish()
            }
        }
    }
}

private const val ACTION_TIMEOUT_MS = 8_000L

class AndroidNotifier(private val ctx: Context) : Notifier {
    private companion object {
        /** The last event shown per notification id, so a different event makes sound again but a repeat does not. */
        val lastEvent = java.util.concurrent.ConcurrentHashMap<Int, String>()
    }
    override fun show(key: String, title: String, text: String) {
        showEvent(PrintEvent(title, text, type = PrintEventType.FINISH, serial = key))
    }

    override fun showEvent(event: PrintEvent) {
        if (!ctx.canNotify()) return
        val nm = ctx.notifications()
        val notifId = (event.serial ?: event.title).hashCode()
        val isCritical = event.type == PrintEventType.ATTENTION || event.type == PrintEventType.FAILED
        val channel = if (isCritical) ALERTS_CRITICAL else ALERTS
        val eventKey = "${event.type}|${event.title}|${event.text}"
        val repeat = lastEvent.put(notifId, eventKey) == eventKey

        val builder = NotificationCompat.Builder(ctx, channel)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(event.title)
            .setContentText(event.text)
            .setContentIntent(ctx.openPandy())
            .setAutoCancel(true)
            .setOnlyAlertOnce(repeat)
            .setCategory(if (isCritical) Notification.CATEGORY_ALARM else Notification.CATEGORY_STATUS)
            .setPriority(NotificationCompat.PRIORITY_HIGH)

        for (action in event.actions) {
            val actIntent = if (action.id == "share") {
                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_SUBJECT, event.title)
                    putExtra(Intent.EXTRA_TEXT, "${event.title}\n${event.text}")
                }
                val chooser = Intent.createChooser(shareIntent, "Share print").apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                PendingIntent.getActivity(ctx, notifId, chooser, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            } else {
                actionIntent(ctx, event.serial.orEmpty(), action.id, notifId)
            }
            val actionBuilder = NotificationCompat.Action.Builder(R.drawable.ic_stat, action.label, actIntent)
            if (action.id == "stop") {
                actionBuilder.setAuthenticationRequired(true)
            }
            builder.addAction(actionBuilder.build())
        }

        nm.notify(notifId, builder.build())

        val imgPath = event.imagePath
        val fallbackPath = event.fallbackImagePath
        if (imgPath != null || fallbackPath != null) {
            CoroutineScope(Dispatchers.IO).launch {
                suspend fun load(path: String?): Bitmap? {
                    if (path == null) return null
                    val bytes = runCatching { Repo.ha?.bytes(path) }.getOrNull()?.takeIf { it.isNotEmpty() } ?: return null
                    return BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                }
                val bitmap = load(imgPath) ?: load(fallbackPath)
                if (bitmap != null && nm.activeNotifications.any { it.id == notifId }) {
                    builder.setOnlyAlertOnce(true) // adding the picture must not alert again
                    builder.setLargeIcon(bitmap)
                    builder.setStyle(NotificationCompat.BigPictureStyle().bigPicture(bitmap).bigLargeIcon(null as Bitmap?))
                    nm.notify(notifId, builder.build())
                }
            }
        }
    }
}

/**
 * Keeps Pandy connected with the screen off so [Repo] can send print alerts. Its ongoing notification shows the
 * printer's state. Stops itself when the user disconnects.
 * shortcut: not restarted after a reboot, so open Pandy once after one; add a boot receiver if people ask.
 */
class WatchService : Service() {
    private var job: Job? = null

    override fun onBind(intent: Intent?) = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (job != null) return START_STICKY
        val nm = notifications()

        fun note(text: String, p: Printer? = null): Notification {
            val builder = NotificationCompat.Builder(this, WATCH)
                .setSmallIcon(R.drawable.ic_stat)
                .setContentTitle("Pandy")
                .setContentText(text)
                .setContentIntent(openPandy())
                .setOngoing(true)
                .setOnlyAlertOnce(true)

            if (p != null && p.printing) {
                builder.setProgress(100, p.progress, false)
                if (p.paused) {
                    builder.addAction(R.drawable.ic_stat, "Resume", actionIntent(this, p.serial, "resume", 1))
                } else {
                    builder.addAction(R.drawable.ic_stat, "Pause", actionIntent(this, p.serial, "pause", 1))
                }
                val stopAction = NotificationCompat.Action.Builder(
                    R.drawable.ic_stat,
                    "Stop",
                    actionIntent(this, p.serial, "stop", 1)
                ).setAuthenticationRequired(true).build()
                builder.addAction(stopAction)
            }
            return builder.build()
        }

        ServiceCompat.startForeground(this, 1, note("Watching your printer"), ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        Repo.start()
        job = CoroutineScope(Dispatchers.Main).launch {
            Repo.state.map { it.on to it.printer() }.distinctUntilChanged().collect { (on, p) ->
                val text = when {
                    !on -> null
                    p == null -> "Connecting…"
                    p.printing -> "${p.name}: ${p.status} ${p.progress}%"
                    else -> "${p.name}: ${if (p.online) "idle" else "offline"}"
                }
                if (text == null) stopSelf() else nm.notify(1, note(text, p))
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        job?.cancel()
        job = null
        if (!Repo.visible && !Repo.watching) Repo.stop() // nothing else is using the connection
        super.onDestroy()
    }
}
