package dev.pocket.aws

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import dev.pocket.aws.core.Conn
import dev.pocket.aws.core.ShortsJob
import dev.pocket.aws.core.Ssh
import dev.pocket.aws.core.Store
import dev.pocket.aws.core.Studio
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Keeps the SSH connection (and its port forwards) alive in the background; shows Shorts progress. */
class TunnelService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val seen = mutableMapOf<String, String>()   // job id -> last status
    private var running: ShortsJob? = null
    private var started = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_DISCONNECT) {
            Ssh.disconnect(); stopSelf(); return START_NOT_STICKY
        }
        val type = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        ServiceCompat.startForeground(this, ID, build(), type)
        if (!started) {
            started = true
            scope.launch { Ssh.state.collect { refresh() } }
            scope.launch { Ssh.forwards.collect { refresh() } }
            scope.launch { pollJobs() }
        }
        return START_STICKY
    }

    private suspend fun pollJobs() {
        var first = true
        while (scope.isActive) {
            val s = Ssh.server
            if (Ssh.online && s != null && s.studioPort > 0) {
                runCatching { Studio.jobs() }.onSuccess { jobs ->
                    running = jobs.firstOrNull { it.status == "running" }
                    jobs.forEach { j ->
                        val prev = seen[j.id]
                        if (!first && prev != null && prev != j.status && Store.settings.value.notifyJobs) {
                            if (j.status == "done") notifyJob(j, "Shorts ready", "${j.clips} clips to review · ${j.title}")
                            if (j.status == "failed") notifyJob(j, "Job failed", j.error ?: j.title)
                        }
                        seen[j.id] = j.status
                    }
                    first = false
                    refresh()
                }
            }
            delay(if (running != null) 4000 else 12000)
        }
    }

    @SuppressLint("MissingPermission")
    private fun notifyJob(j: ShortsJob, title: String, text: String) {
        val n = NotificationCompat.Builder(this, App.CH_JOBS)
            .setSmallIcon(R.drawable.ic_stat).setContentTitle(title).setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(openApp("shorts:${j.id}")).setAutoCancel(true).build()
        getSystemService(NotificationManager::class.java).notify(j.id.hashCode(), n)
    }

    private fun openApp(route: String?): PendingIntent {
        val i = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP).putExtra("route", route)
        return PendingIntent.getActivity(this, route.hashCode(), i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    private fun refresh() {
        val s = Ssh.state.value
        if (s !is Conn.Online && s !is Conn.Reconnecting) return
        getSystemService(NotificationManager::class.java).notify(ID, build())
    }

    private fun build(): Notification {
        val st = Ssh.state.value
        val name = Ssh.server?.title ?: "server"
        val n = Ssh.forwards.value.size
        val b = NotificationCompat.Builder(this, App.CH_TUNNEL)
            .setSmallIcon(R.drawable.ic_stat)
            .setOngoing(true).setSilent(true).setShowWhen(false)
            .setContentIntent(openApp(null))
            .addAction(0, "Disconnect", PendingIntent.getService(this, 1,
                Intent(this, TunnelService::class.java).setAction(ACTION_DISCONNECT), PendingIntent.FLAG_IMMUTABLE))
        val j = running
        when {
            st is Conn.Reconnecting -> b.setContentTitle("Reconnecting to $name…").setContentText("Attempt ${st.attempt}")
            j != null -> {
                val stage = j.current
                b.setContentTitle(j.title.ifBlank { "Shorts job" })
                    .setContentText("${stage?.label ?: "Working"} · ${(j.progress * 100).toInt()}%")
                    .setProgress(1000, (j.progress * 1000).toInt(), false)
                    .setContentIntent(openApp("shorts:${j.id}"))
            }
            else -> b.setContentTitle("Connected to $name").setContentText(if (n == 0) "Tunnel open" else "$n app${if (n == 1) "" else "s"} forwarded")
        }
        return b.build()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val ID = 7
        const val ACTION_DISCONNECT = "dev.pocket.aws.DISCONNECT"
    }
}
