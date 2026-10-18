package io.github.gccammelli.pandy

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import io.github.gccammelli.pandy.ui.printer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

private const val ALERTS = "alerts"
private const val WATCH = "watch"

private fun Context.notifications() = getSystemService(NotificationManager::class.java).also {
    it.createNotificationChannel(NotificationChannel(ALERTS, "Print alerts", NotificationManager.IMPORTANCE_HIGH))
    it.createNotificationChannel(NotificationChannel(WATCH, "Background connection", NotificationManager.IMPORTANCE_LOW))
}

private fun Context.openPandy() = PendingIntent.getActivity(this, 0,
    Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

fun Context.canNotify() = Build.VERSION.SDK_INT < 33 || checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

class AndroidNotifier(private val ctx: Context) : Notifier {
    override fun show(key: String, title: String, text: String) {
        if (!ctx.canNotify()) return
        ctx.notifications().notify(key.hashCode(), NotificationCompat.Builder(ctx, ALERTS)
            .setSmallIcon(R.drawable.ic_stat).setContentTitle(title).setContentText(text)
            .setContentIntent(ctx.openPandy()).setAutoCancel(true).setCategory(Notification.CATEGORY_STATUS).build())
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
        fun note(text: String) = NotificationCompat.Builder(this, WATCH).setSmallIcon(R.drawable.ic_stat).setContentTitle("Pandy")
            .setContentText(text).setContentIntent(openPandy()).setOngoing(true).setOnlyAlertOnce(true).build()
        ServiceCompat.startForeground(this, 1, note("Watching your printer"), ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        Repo.start()
        job = CoroutineScope(Dispatchers.Main).launch {
            Repo.state.map { st ->
                val p = st.printer()
                when {
                    !st.on -> null
                    p == null -> "Connecting…"
                    p.printing -> "${p.name}: ${p.status} ${p.progress}%"
                    else -> "${p.name}: ${if (p.online) "idle" else "offline"}"
                }
            }.distinctUntilChanged().collect { text ->
                if (text == null) stopSelf() else nm.notify(1, note(text))
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
