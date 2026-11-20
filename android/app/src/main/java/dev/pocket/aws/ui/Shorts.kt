package dev.pocket.aws.ui

import android.content.ClipboardManager
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Replay
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.pocket.aws.core.ShortsJob
import dev.pocket.aws.core.Ssh
import dev.pocket.aws.core.Studio
import dev.pocket.aws.core.extractUrl
import kotlinx.coroutines.launch

@Composable
fun ShortsTab() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val jobs by Live.jobs.collectAsState()
    val err by Live.jobsError.collectAsState()
    val studioPort = Ssh.server?.studioPort ?: 0
    var url by remember { mutableStateOf("") }
    var focus by remember { mutableStateOf("") }
    var showFocus by remember { mutableStateOf(false) }
    var only by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    val anyActive = jobs?.any { it.active } == true
    Poll(if (anyActive) 2500 else 8000, anyActive) { Live.refreshJobs() }

    if (studioPort <= 0) {
        Column(Modifier.fillMaxSize()) {
            Header("Shorts")
            Empty(Icons.Rounded.Movie, "Studio is off for this server", "Set the Studio port in the server settings (8000 by default).")
        }
        return
    }

    fun submit() {
        val u = extractUrl(url) ?: url.trim()
        busy = true
        scope.launch {
            runCatching { Studio.create(u, focus, only) }
                .onSuccess { url = ""; focus = ""; showFocus = false; Toast.makeText(ctx, "Started: ${it.title}", Toast.LENGTH_SHORT).show(); Live.refreshJobs() }
                .onFailure { Toast.makeText(ctx, it.message, Toast.LENGTH_LONG).show() }
            busy = false
        }
    }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Header("Shorts", "Long video → vertical shorts") {
                IconButton(onClick = { Nav.push(Route.Web(studioPort, "/", "Shorts Studio")) }) { Icon(Icons.Rounded.OpenInNew, "Open Studio", tint = P.text) }
            }
        }
        item {
            Surface(Modifier.animateContentSize()) {
                Field("Video link", url, { url = it }, placeholder = "https://youtube.com/watch?v=…", keyboard = KeyboardType.Uri, trailing = {
                    IconButton(onClick = {
                        val clip = ctx.getSystemService(ClipboardManager::class.java).primaryClip?.getItemAt(0)?.coerceToText(ctx)?.toString().orEmpty()
                        extractUrl(clip)?.let { url = it } ?: Toast.makeText(ctx, "No link on the clipboard", Toast.LENGTH_SHORT).show()
                    }) { Icon(Icons.Rounded.ContentPaste, "Paste", tint = P.muted) }
                })
                AnimatedVisibility(showFocus) {
                    Column {
                        Spacer(Modifier.height(10.dp))
                        Field("What should the AI look for?", focus, { focus = it }, singleLine = false, minLines = 2, placeholder = "every time they talk about money")
                        Spacer(Modifier.height(10.dp))
                        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                            listOf("These first", "Only these").forEachIndexed { i, l ->
                                SegmentedButton(only == (i == 1), { only = i == 1 }, SegmentedButtonDefaults.itemShape(i, 2),
                                    colors = SegmentedButtonDefaults.colors(activeContainerColor = P.accent.copy(alpha = 0.14f), activeContentColor = P.accent, inactiveContainerColor = P.surface, inactiveContentColor = P.muted, activeBorderColor = P.border, inactiveBorderColor = P.border)) { Text(l) }
                            }
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { showFocus = !showFocus }) {
                        Icon(Icons.Rounded.AutoAwesome, null, tint = P.accent); Spacer(Modifier.width(6.dp))
                        Text(if (showFocus) "No focus note" else "Focus note", color = P.accent)
                    }
                    Spacer(Modifier.weight(1f))
                    Primary("Make shorts", ::submit, enabled = url.isNotBlank(), busy = busy)
                }
            }
        }
        item { Text("Tip: share a video from the YouTube app to “Make shorts” to start a job without opening Pocket.", style = MaterialTheme.typography.bodySmall, color = P.faint, modifier = Modifier.fillMaxWidth()) }
        err?.let { e -> item { ErrorNote("Studio: $e") } }
        if (jobs == null && err == null) item { Loading() }
        jobs?.let { list ->
            if (list.isEmpty()) item { Empty(Icons.Rounded.Movie, "No projects yet", "Paste a link above to start your first one.") }
            else item { Label("Projects · ${list.size}") }
            items(list, key = { it.id }) { j -> JobCard(j, studioPort) }
        }
    }
}

@Composable
private fun JobCard(j: ShortsJob, studioPort: Int) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val color = when (j.status) { "done" -> P.good; "failed" -> P.bad; "interrupted" -> P.warn; "running" -> P.accent; else -> P.muted }
    val label = when (j.status) { "done" -> if (j.clips > 0) "${j.clips} clips" else "Done"; "interrupted" -> "Paused"; else -> j.status.replaceFirstChar { it.uppercase() } }
    fun act(f: suspend () -> Unit) = scope.launch { runCatching { f() }.onFailure { Toast.makeText(ctx, it.message, Toast.LENGTH_LONG).show() }; Live.refreshJobs() }

    Surface(Modifier.animateContentSize(), onClick = { Nav.push(Route.Web(studioPort, "/jobs/${j.id}", j.title)) }) {
        Row(verticalAlignment = Alignment.Top) {
            Text(j.title.ifBlank { j.id }, style = MaterialTheme.typography.titleMedium, color = P.text, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Spacer(Modifier.width(10.dp))
            Pill(if (j.stopping) "Stopping" else label, color)
        }
        if (j.active || j.status == "interrupted") {
            Spacer(Modifier.height(12.dp))
            Bar(j.progress.toFloat(), color)
            Spacer(Modifier.height(8.dp))
            val st = j.current
            Row {
                Text(listOfNotNull(st?.label ?: if (j.status == "queued") "Waiting in queue" else null, st?.message).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall, color = P.muted, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                Text(pct(j.progress) + (st?.eta?.takeIf { it > 0 }?.let { " · ${dur(it)} left" } ?: ""), style = MaterialTheme.typography.bodySmall, color = P.text)
            }
            // stage strip: one segment per stage
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                j.stages.filter { it.status != "planned" }.forEach { s ->
                    Bar(when (s.status) { "done" -> 1f; "running" -> (s.progress ?: 0.0).toFloat(); else -> 0f }, if (s.status == "failed") P.bad else color.copy(alpha = 0.7f), Modifier.weight(1f), height = 3.dp)
                }
            }
        }
        if (j.exportStatus == "running" || j.exportStatus == "queued") {
            Spacer(Modifier.height(10.dp))
            Text("Exporting HD · ${pct(j.exportProgress ?: 0.0)}", style = MaterialTheme.typography.bodySmall, color = P.accent)
        }
        if (j.status == "done" && j.clips > 0) {
            Spacer(Modifier.height(6.dp))
            Text("${j.approved} approved · ${j.exported} exported", style = MaterialTheme.typography.bodySmall, color = P.muted)
        }
        j.error?.takeIf { j.status == "failed" }?.let { Spacer(Modifier.height(8.dp)); Text(it, style = MaterialTheme.typography.bodySmall, color = P.bad, maxLines = 3, overflow = TextOverflow.Ellipsis) }
        if (j.status in setOf("queued", "running", "failed", "interrupted")) {
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (j.status == "queued" || j.status == "running") Secondary("Stop", { act { Studio.stop(j.id) } }, icon = Icons.Rounded.Stop, enabled = !j.stopping)
                else Secondary(if (j.status == "interrupted") "Resume" else "Retry", { act { Studio.retry(j.id) } }, icon = Icons.Rounded.Replay)
            }
        }
    }
}
