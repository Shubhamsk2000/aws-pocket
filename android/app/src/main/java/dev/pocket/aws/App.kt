package dev.pocket.aws

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import androidx.core.content.ContextCompat
import dev.pocket.aws.core.Conn
import dev.pocket.aws.core.Ssh
import dev.pocket.aws.core.Store
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.launch
import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.security.Security

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        // Android ships a cut-down "BC" provider; sshj needs the full one (ed25519, curve25519, chacha20...)
        Security.removeProvider("BC")
        Security.insertProviderAt(BouncyCastleProvider(), 1)

        Store.init(this)
        Ssh.init(this)

        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH_TUNNEL, "Connection", NotificationManager.IMPORTANCE_LOW).apply {
            description = "Shown while the tunnel to your server is open"; setShowBadge(false)
        })
        nm.createNotificationChannel(NotificationChannel(CH_JOBS, "Shorts jobs", NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = "A shorts job finished or failed"
        })

        // the tunnel service lives exactly as long as a connection is wanted
        CoroutineScope(SupervisorJob() + Dispatchers.Main).launch {
            Ssh.state.distinctUntilChangedBy { it is Conn.Online || it is Conn.Reconnecting }.collect { s ->
                val up = s is Conn.Online || s is Conn.Reconnecting
                val i = Intent(this@App, TunnelService::class.java)
                if (up) runCatching { ContextCompat.startForegroundService(this@App, i) } else stopService(i)
            }
        }
    }

    companion object {
        const val CH_TUNNEL = "tunnel"
        const val CH_JOBS = "jobs"
    }
}
