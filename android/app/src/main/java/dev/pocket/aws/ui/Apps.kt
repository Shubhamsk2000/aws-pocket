package dev.pocket.aws.ui

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.OpenInBrowser
import androidx.compose.material.icons.rounded.PushPin
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material.icons.rounded.Web
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
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
import dev.pocket.aws.core.AppPref
import dev.pocket.aws.core.PortInfo
import dev.pocket.aws.core.Probe
import dev.pocket.aws.core.Ssh
import dev.pocket.aws.core.Store
import kotlinx.coroutines.launch

private data class Row_(val port: Int, val live: PortInfo?, val pref: AppPref?)

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun AppsTab() {
    val ports by Live.ports.collectAsState()
    val err by Live.portsError.collectAsState()
    val settings by Store.settings.collectAsState()
    val apps by Store.apps.collectAsState()
    val fwd by Ssh.forwards.collectAsState()
    val sid = Ssh.server?.id ?: ""
    val prefs = apps[sid].orEmpty()
    var sheet by remember { mutableStateOf<Row_?>(null) }
    var adding by remember { mutableStateOf(false) }
    Poll(8000) { Live.refreshPorts() }
    val scope = rememberCoroutineScope()

    val rows = remember(ports, prefs, settings) {
        val live = ports.orEmpty().associateBy { it.port }
        (live.keys + prefs.keys).distinct().map { Row_(it, live[it], prefs[it]) }
            .filter { r -> settings.showHidden || r.pref?.hidden != true }
            .filter { r -> !settings.webOnly || r.live?.http == true || r.pref?.pinned == true || r.live == null }
            .sortedWith(compareByDescending<Row_> { it.pref?.pinned == true }.thenByDescending { it.live?.http == true }.thenBy { it.port })
    }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Header("Apps", ports?.let { "${it.count { p -> p.http }} web apps · ${it.size} open ports" } ?: "Scanning ports…") {
                IconButton(onClick = { adding = true }) { Icon(Icons.Rounded.Add, "Add port", tint = P.text) }
                IconButton(onClick = { scope.launch { Live.ports.value = null; Live.refreshPorts() } }) { Icon(Icons.Rounded.Refresh, "Rescan", tint = P.text) }
            }
            FlowRow(Modifier.padding(bottom = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Chip("Web apps only", settings.webOnly) { Store.updateSettings { it.copy(webOnly = !it.webOnly) } }
                Chip("Show hidden", settings.showHidden) { Store.updateSettings { it.copy(showHidden = !it.showHidden) } }
            }
        }
        err?.let { e -> item { ErrorNote(e) } }
        if (ports == null && err == null) item { Loading() }
        if (ports != null && rows.isEmpty()) item {
            Empty(Icons.Rounded.Web, "No web apps listening", "Start a web server on the instance, or turn off “Web apps only” to see every open port.")
        }
        items(rows, key = { it.port }) { r ->
            val name = appName(r.pref, r.live)
            val up = r.live != null
            Surface(onClick = { if (up && (r.live!!.http || r.pref != null)) Nav.push(Route.Web(r.port, r.pref?.path ?: "/", name)) else sheet = r }, pad = 14.dp) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Letter(name, if (!up) P.faint else if (r.live!!.http) P.accent else P.muted)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(name, style = MaterialTheme.typography.titleMedium, color = if (up) P.text else P.muted, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                            if (r.port in fwd) { Spacer(Modifier.width(8.dp)); Dot(P.good, 6.dp) }
                        }
                        val bits = listOfNotNull(":${r.port}", r.live?.process, if (r.live?.public == true) "public" else null, if (!up) "offline" else null, if (r.pref?.hidden == true) "hidden" else null)
                        Text(bits.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = if (r.live?.public == true) P.warn else P.muted, maxLines = 1)
                    }
                    IconButton(onClick = {
                        val p = r.pref ?: AppPref(r.port)
                        Store.setApp(sid, p.copy(pinned = !p.pinned))
                    }) {
                        Icon(if (r.pref?.pinned == true) Icons.Rounded.PushPin else Icons.Outlined.PushPin, "Pin", tint = if (r.pref?.pinned == true) P.accent else P.faint)
                    }
                    TextButton(onClick = { sheet = r }) { Text("•••", color = P.muted) }
                }
            }
        }
    }

    sheet?.let { r ->
        val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(onDismissRequest = { sheet = null }, sheetState = state, containerColor = P.surface) {
            AppSheet(sid, r.port, r.live, r.pref) { sheet = null }
        }
    }
    if (adding) AddPortDialog(sid) { adding = false }
}

@Composable
private fun Chip(text: String, on: Boolean, onClick: () -> Unit) = FilterChip(
    on, onClick, { Text(text) }, shape = SmallShape,
    colors = FilterChipDefaults.filterChipColors(selectedContainerColor = P.accent.copy(alpha = 0.14f), selectedLabelColor = P.accent, labelColor = P.muted, containerColor = P.surface),
    border = FilterChipDefaults.filterChipBorder(enabled = true, selected = on, borderColor = P.border, selectedBorderColor = P.accent.copy(alpha = 0.3f)),
)

@Composable
private fun AppSheet(sid: String, port: Int, live: PortInfo?, pref: AppPref?, close: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var p by remember { mutableStateOf(pref ?: AppPref(port)) }
    var confirmKill by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 20.dp).padding(bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(appName(p, live), style = MaterialTheme.typography.titleLarge, color = P.text)
        if (live != null) {
            Surface(pad = 12.dp) {
                Kv("Port", ":$port on ${live.addrs.joinToString()}")
                Kv("Process", "${live.process ?: "?"} (pid ${live.pid ?: "?"}) · ${live.user}")
                if (live.rss > 0) Kv("Memory", bytes(live.rss))
                if (live.cwd.isNotBlank()) Kv("Folder", live.cwd)
                if (live.cmd.isNotBlank()) Text(live.cmd, style = MaterialTheme.typography.bodySmall.copy(fontFamily = Mono), color = P.muted, maxLines = 4, overflow = TextOverflow.Ellipsis)
                if (live.public) { Spacer(Modifier.height(6.dp)); Text("Listens on all interfaces. Keep the security group closed for this port.", style = MaterialTheme.typography.bodySmall, color = P.warn) }
            }
        } else Text("Not listening right now. It will light up when the app starts.", style = MaterialTheme.typography.bodyMedium, color = P.muted)
        Field("Name on your phone", p.label, { p = p.copy(label = it) }, placeholder = live?.title ?: live?.process ?: "My app")
        Field("Open at path", p.path, { p = p.copy(path = it.ifBlank { "/" }) }, placeholder = "/", mono = true)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Secondary(if (p.pinned) "Unpin" else "Pin to Home", { p = p.copy(pinned = !p.pinned) }, Modifier.weight(1f), icon = Icons.Rounded.PushPin)
            Secondary(if (p.hidden) "Unhide" else "Hide", { p = p.copy(hidden = !p.hidden) }, Modifier.weight(1f), icon = if (p.hidden) Icons.Rounded.Visibility else Icons.Rounded.VisibilityOff)
        }
        if (live != null) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Secondary("Phone browser", {
                scope.launch {
                    runCatching { Ssh.forward(port) }.onSuccess { lp ->
                        ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("http://127.0.0.1:$lp${p.path}")))
                    }.onFailure { Toast.makeText(ctx, it.message, Toast.LENGTH_LONG).show() }
                }
            }, Modifier.weight(1f), icon = Icons.Rounded.OpenInBrowser)
            if (live.pid != null) Secondary("Stop process", { confirmKill = true }, Modifier.weight(1f), icon = Icons.Rounded.Terminal, color = P.bad)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (pref != null && live == null) Secondary("Remove", { Store.removeApp(sid, port); close() }, Modifier.weight(1f), color = P.bad)
            Primary("Save", { Store.setApp(sid, p); close() }, Modifier.weight(1f))
        }
    }
    if (confirmKill && live?.pid != null) AlertDialog(
        onDismissRequest = { confirmKill = false },
        title = { Text("Stop ${live.process}?") },
        text = { Text("Sends SIGTERM to pid ${live.pid}. A systemd service may start it again; use Tools › Services to stop it for good.") },
        confirmButton = { TextButton(onClick = {
            confirmKill = false
            scope.launch {
                runCatching { Probe.kill(live.pid, false) }
                    .onSuccess { Toast.makeText(ctx, "Stopped", Toast.LENGTH_SHORT).show(); Live.refreshPorts(); close() }
                    .onFailure { Toast.makeText(ctx, it.message, Toast.LENGTH_LONG).show() }
            }
        }) { Text("Stop", color = P.bad) } },
        dismissButton = { TextButton(onClick = { confirmKill = false }) { Text("Cancel") } },
        containerColor = P.surface,
    )
}

@Composable
fun Kv(k: String, v: String) = Row(Modifier.padding(vertical = 3.dp)) {
    Text(k, style = MaterialTheme.typography.bodySmall, color = P.muted, modifier = Modifier.width(76.dp))
    Text(v, style = MaterialTheme.typography.bodySmall, color = P.text)
}

@Composable
private fun AddPortDialog(sid: String, close: () -> Unit) {
    var port by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = close, containerColor = P.surface,
        title = { Text("Add an app") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Pin a port even while its app is stopped. It shows on Home and lights up when it's running.", style = MaterialTheme.typography.bodySmall, color = P.muted)
                Field("Port", port, { port = it.filter(Char::isDigit).take(5) }, keyboard = KeyboardType.Number, placeholder = "3000")
                Field("Name", name, { name = it }, placeholder = "Dashboard")
            }
        },
        confirmButton = { TextButton(enabled = (port.toIntOrNull() ?: 0) in 1..65535, onClick = {
            Store.setApp(sid, AppPref(port.toInt(), name, "/", pinned = true)); close()
        }) { Text("Add") } },
        dismissButton = { TextButton(onClick = close) { Text("Cancel") } },
    )
}

