package dev.pocket.aws

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.pocket.aws.core.Conn
import dev.pocket.aws.core.Ssh
import dev.pocket.aws.core.Store
import dev.pocket.aws.core.Studio
import dev.pocket.aws.core.extractUrl
import dev.pocket.aws.ui.ErrorNote
import dev.pocket.aws.ui.Field
import dev.pocket.aws.ui.P
import dev.pocket.aws.ui.PocketTheme
import dev.pocket.aws.ui.Primary
import dev.pocket.aws.ui.Secondary
import dev.pocket.aws.ui.Surface
import dev.pocket.aws.ui.Tile
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

/** "Share → Make shorts" from YouTube or any app: connects if needed and starts a Studio job. */
class ShareActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val text = intent?.getStringExtra(Intent.EXTRA_TEXT).orEmpty()
        val url = extractUrl(text)
        setContent { PocketTheme { Sheet(url) } }
    }

    @Composable
    private fun Sheet(url: String?) {
        val scope = rememberCoroutineScope()
        var focus by remember { mutableStateOf("") }
        var step by remember { mutableStateOf<String?>(null) }
        var done by remember { mutableStateOf<String?>(null) }
        var err by remember { mutableStateOf(if (url == null) "No link in what was shared." else null) }
        val server = Ssh.server ?: Store.server(Store.settings.value.lastServer) ?: Store.servers.value.firstOrNull()

        fun go() {
            val u = url ?: return
            val s = server ?: run { err = "Add a server in Pocket first."; return }
            err = null
            scope.launch {
                runCatching {
                    if (!Ssh.online) {
                        step = "Connecting to ${s.title}…"
                        Ssh.connect(s)
                        val st = withTimeout(45_000) { Ssh.state.first { it is Conn.Online || it is Conn.Failed } }
                        if (st is Conn.Failed) throw IllegalStateException(st.message)
                    }
                    step = "Starting the job…"
                    Studio.create(u, focus)
                }.onSuccess { done = it.title; step = null }.onFailure { err = it.message; step = null }
            }
        }

        Surface(Modifier.fillMaxWidth().padding(16.dp), pad = 20.dp) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Tile(if (done != null) Icons.Rounded.CheckCircle else Icons.Rounded.Movie, if (done != null) P.good else P.accent)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(if (done != null) "Job started" else "Make shorts", style = MaterialTheme.typography.titleLarge, color = P.text)
                    Text(done ?: url ?: "", style = MaterialTheme.typography.bodySmall, color = P.muted, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
            Spacer(Modifier.height(16.dp))
            err?.let { ErrorNote(it); Spacer(Modifier.height(12.dp)) }
            when {
                done != null -> Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Secondary("Done", { finish() }, Modifier.weight(1f))
                    Primary("Follow it", {
                        startActivity(Intent(this@ShareActivity, MainActivity::class.java).putExtra("route", "shorts").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        finish()
                    }, Modifier.weight(1f))
                }
                step != null -> {
                    Text(step!!, style = MaterialTheme.typography.bodyMedium, color = P.muted)
                    Spacer(Modifier.height(10.dp))
                    LinearProgressIndicator(Modifier.fillMaxWidth(), color = P.accent, trackColor = P.surface2)
                }
                else -> {
                    Field("Focus note (optional)", focus, { focus = it }, placeholder = "what should the AI look for?")
                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Secondary("Cancel", { finish() }, Modifier.weight(1f))
                        Primary("Start on ${server?.title ?: "server"}", { go() }, Modifier.weight(1.6f), enabled = url != null && server != null)
                    }
                }
            }
        }
    }
}
