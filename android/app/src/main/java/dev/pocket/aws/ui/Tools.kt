package dev.pocket.aws.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.rounded.ListAlt
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.BookmarkAdd
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.pocket.aws.BuildConfig
import dev.pocket.aws.core.Aws
import dev.pocket.aws.core.Container
import dev.pocket.aws.core.Probe
import dev.pocket.aws.core.Proc
import dev.pocket.aws.core.Ssh
import dev.pocket.aws.core.Store
import dev.pocket.aws.core.Unit_
import kotlinx.coroutines.launch

// ------------------------------------------------------------------ Tools tab

@Composable
fun ToolsTab() {
    val stats by Live.stats.collectAsState()
    var power by remember { mutableStateOf(false) }
    val server = Ssh.server
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { Header("Tools", server?.let { "${it.user}@${it.host}" }) }
        item { ToolRow(Icons.Rounded.Memory, "Processes", "What's using CPU and memory") { Nav.push(Route.Procs) } }
        item { ToolRow(Icons.AutoMirrored.Rounded.ListAlt, "Services & logs", "systemd units, Docker, live logs, restart") { Nav.push(Route.Services) } }
        item { ToolRow(Icons.Rounded.Terminal, "Run a command", "One-tap snippets, output on screen") { Nav.push(Route.Run) } }
        item { ToolRow(Icons.Rounded.SwapHoriz, "Port forwards", "Use any server port from other apps on this phone") { Nav.push(Route.Forward) } }
        item { ToolRow(Icons.Rounded.Settings, "Settings", "Notifications, web view, servers") { Nav.push(Route.Settings) } }
        item { ToolRow(Icons.Rounded.PowerSettingsNew, "Power", if (server?.hasAws == true) "Stop the instance (stops billing for compute)" else "Reboot or shut down", P.bad) { power = true } }
        stats?.let { s ->
            item {
                Label("This server")
                Surface {
                    Kv("Host", s.host); Kv("System", s.os); Kv("Kernel", s.kernel); Kv("CPU", "${s.cores} cores · load ${s.load.joinToString(" ")}")
                    Kv("Memory", bytes(s.memTotal)); Kv("Uptime", dur(s.uptime))
                }
            }
        }
        item { Text("Pocket ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.bodySmall, color = P.faint, modifier = Modifier.padding(top = 16.dp, start = 4.dp)) }
    }
    if (power && server != null) PowerDialog(server.hasAws) { power = false }
}

@Composable
private fun ToolRow(icon: ImageVector, title: String, sub: String, tint: Color = P.accent, onClick: () -> Unit) {
    Surface(onClick = onClick, pad = 14.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Tile(icon, tint); Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium, color = P.text)
                Text(sub, style = MaterialTheme.typography.bodySmall, color = P.muted)
            }
            Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = P.faint)
        }
    }
}

@Composable
private fun PowerDialog(aws: Boolean, close: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    fun run(what: String, f: suspend () -> Unit) {
        close()
        scope.launch {
            runCatching { f() }.onSuccess { Toast.makeText(ctx, what, Toast.LENGTH_LONG).show(); Ssh.disconnect() }
                .onFailure { Toast.makeText(ctx, it.message, Toast.LENGTH_LONG).show() }
        }
    }
    AlertDialog(
        onDismissRequest = close, containerColor = P.surface,
        title = { Text("Power") },
        text = { Text(if (aws) "Stopping the instance ends any running job (it resumes later from its cache). You can start it again from the server list." else "Running jobs are interrupted. Shutting down an EC2 instance stops it; start it again from the AWS console.") },
        confirmButton = {
            Column(horizontalAlignment = Alignment.End) {
                if (aws) TextButton(onClick = { run("Stopping the instance…") { Aws.stop(Ssh.server!!) } }) { Text("Stop instance", color = P.bad) }
                else TextButton(onClick = { run("Shutting down…") { Ssh.exec("sudo -n shutdown -h now") } }) { Text("Shut down", color = P.bad) }
                TextButton(onClick = { run("Rebooting… reconnect in a minute") { Ssh.exec("sudo -n systemctl reboot") } }) { Text("Reboot", color = P.warn) }
                TextButton(onClick = close) { Text("Cancel") }
            }
        },
    )
}

// ------------------------------------------------------------------ Processes

@Composable
fun ProcsScreen() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var list by remember { mutableStateOf<List<Proc>?>(null) }
    var err by remember { mutableStateOf<String?>(null) }
    var byMem by remember { mutableStateOf(false) }
    var q by remember { mutableStateOf("") }
    var pick by remember { mutableStateOf<Proc?>(null) }
    Poll(3000) { runCatching { Probe.procs() }.onSuccess { list = it; err = null }.onFailure { err = it.message } }
    val shown = remember(list, byMem, q) {
        list.orEmpty().filter { q.isBlank() || it.cmd.contains(q, true) || it.name.contains(q, true) || it.pid.toString() == q }
            .let { l -> if (byMem) l.sortedByDescending { it.rss } else l }
    }
    Column(Modifier.fillMaxSize()) {
        Header("Processes", list?.let { "${it.size} busiest · refreshes every 3 s" }, onBack = { Nav.pop() })
        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Field("Filter", q, { q = it }, placeholder = "name, command or pid")
            Seg(listOf("CPU", "Memory"), if (byMem) 1 else 0) { byMem = it == 1 }
            err?.let { ErrorNote(it) }
        }
        if (list == null && err == null) Loading()
        LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(shown, key = { it.pid }) { p ->
                Surface(onClick = { pick = p }, pad = 12.dp) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(p.name, style = MaterialTheme.typography.titleMedium, color = P.text, maxLines = 1)
                            Text("pid ${p.pid} · ${p.user} · ${dur(p.age.toDouble())}", style = MaterialTheme.typography.bodySmall, color = P.muted)
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            Text("${"%.1f".format(p.cpu)}%", style = MaterialTheme.typography.labelLarge, color = if (p.cpu > 80) P.warn else P.text)
                            Text(bytes(p.rss), style = MaterialTheme.typography.bodySmall, color = P.muted)
                        }
                    }
                }
            }
        }
    }
    pick?.let { p ->
        fun kill(hard: Boolean) {
            pick = null
            scope.launch { runCatching { Probe.kill(p.pid, hard) }.onSuccess { Toast.makeText(ctx, "Signal sent to ${p.pid}", Toast.LENGTH_SHORT).show() }.onFailure { Toast.makeText(ctx, it.message, Toast.LENGTH_LONG).show() } }
        }
        AlertDialog(
            onDismissRequest = { pick = null }, containerColor = P.surface,
            title = { Text("${p.name} · ${p.pid}") },
            text = { SelectionContainer { Text(p.cmd, fontFamily = Mono, fontSize = 12.sp, color = P.muted) } },
            confirmButton = { Row { TextButton(onClick = { kill(true) }) { Text("Force kill", color = P.bad) }; TextButton(onClick = { kill(false) }) { Text("Terminate", color = P.warn) } } },
            dismissButton = { TextButton(onClick = { pick = null }) { Text("Close") } },
        )
    }
}

@Composable
fun Seg(options: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        options.forEachIndexed { i, l ->
            SegmentedButton(selected == i, { onSelect(i) }, SegmentedButtonDefaults.itemShape(i, options.size),
                colors = SegmentedButtonDefaults.colors(activeContainerColor = P.accent.copy(alpha = 0.14f), activeContentColor = P.accent, inactiveContainerColor = P.surface, inactiveContentColor = P.muted, activeBorderColor = P.border, inactiveBorderColor = P.border)) { Text(l) }
        }
    }
}

// ------------------------------------------------------------------ Services

@Composable
fun ServicesScreen() {
    var units by remember { mutableStateOf<List<Unit_>?>(null) }
    var containers by remember { mutableStateOf<List<Container>>(emptyList()) }
    var err by remember { mutableStateOf<String?>(null) }
    var q by remember { mutableStateOf("") }
    var tab by remember { mutableStateOf(0) }
    Poll(10_000) { runCatching { Probe.services() }.onSuccess { units = it.first; containers = it.second; err = null }.onFailure { err = it.message } }
    Column(Modifier.fillMaxSize()) {
        Header("Services", units?.let { "${it.count { u -> u.sub == "running" }} running · ${containers.size} containers" }, onBack = { Nav.pop() })
        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Field("Filter", q, { q = it }, placeholder = "studio, nginx, docker…")
            Seg(listOf("systemd", "Docker"), tab) { tab = it }
            err?.let { ErrorNote(it) }
        }
        if (units == null && err == null) Loading()
        LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (tab == 0) {
                // failed first, then running, then the rest
                val shown = units.orEmpty().filter { q.isBlank() || it.unit.contains(q, true) || it.desc.contains(q, true) }
                    .sortedWith(compareBy<Unit_> { when { it.active == "failed" -> 0; it.sub == "running" -> 1; else -> 2 } }.thenBy { it.unit })
                items(shown, key = { it.unit }) { u ->
                    Surface(onClick = { Nav.push(Route.Logs(u.unit, u.unit.removeSuffix(".service"))) }, pad = 12.dp) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Dot(when { u.active == "failed" -> P.bad; u.sub == "running" -> P.good; else -> P.faint })
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(u.unit.removeSuffix(".service"), style = MaterialTheme.typography.titleMedium, color = P.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(u.desc, style = MaterialTheme.typography.bodySmall, color = P.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            Text(u.sub, style = MaterialTheme.typography.labelMedium, color = P.muted)
                        }
                    }
                }
            } else {
                if (containers.isEmpty() && units != null) item { Empty(Icons.Rounded.Memory, "No containers", "Docker isn't running anything (or this user can't reach it).") }
                items(containers.filter { q.isBlank() || it.name.contains(q, true) || it.image.contains(q, true) }, key = { it.name }) { c ->
                    Surface(onClick = { Nav.push(Route.Logs("docker:${c.name}", c.name)) }, pad = 12.dp) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Dot(if (c.state == "running") P.good else if (c.state == "exited") P.faint else P.warn)
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(c.name, style = MaterialTheme.typography.titleMedium, color = P.text, maxLines = 1)
                                Text("${c.image} · ${c.status}", style = MaterialTheme.typography.bodySmall, color = P.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                if (c.ports.isNotBlank()) Text(c.ports, style = MaterialTheme.typography.bodySmall, color = P.faint, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            }
        }
    }
}

// ------------------------------------------------------------------ Logs

@Composable
fun LogsScreen(r: Route.Logs) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var lines by remember { mutableStateOf<List<String>?>(null) }
    var err by remember { mutableStateOf<String?>(null) }
    var follow by remember { mutableStateOf(true) }
    var q by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf<String?>(null) }
    val listState = rememberLazyListState()
    suspend fun load() { runCatching { Probe.logs(r.unit, 400) }.onSuccess { lines = it; err = null }.onFailure { err = it.message } }
    Poll(if (follow) 3000 else 3_600_000, follow) { load() }
    val shown = remember(lines, q) { lines.orEmpty().filter { q.isBlank() || it.contains(q, true) } }
    LaunchedEffect(shown.size) { if (follow && shown.isNotEmpty()) listState.scrollToItem(shown.lastIndex) }

    Column(Modifier.fillMaxSize()) {
        Header(r.title, if (follow) "Live · ${shown.size} lines" else "${shown.size} lines", onBack = { Nav.pop() }) {
            IconButton(onClick = { follow = !follow }) { Icon(if (follow) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, "Follow", tint = P.text) }
            IconButton(onClick = { scope.launch { load() } }) { Icon(Icons.Rounded.Refresh, "Refresh", tint = P.text) }
        }
        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Secondary("Restart", { confirm = "restart" }, icon = Icons.Rounded.RestartAlt)
                Secondary("Stop", { confirm = "stop" }, icon = Icons.Rounded.Stop, color = P.bad)
                Secondary("Start", { confirm = "start" }, icon = Icons.Rounded.PlayArrow, color = P.good)
                Secondary("Copy", {
                    ctx.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("logs", shown.joinToString("\n")))
                    Toast.makeText(ctx, "Copied", Toast.LENGTH_SHORT).show()
                }, icon = Icons.Rounded.ContentCopy)
            }
            Field("Filter lines", q, { q = it }, placeholder = "error, warning…")
            err?.let { ErrorNote(it) }
        }
        if (lines == null && err == null) Loading()
        SelectionContainer(Modifier.weight(1f)) {
            LazyColumn(Modifier.fillMaxSize().padding(top = 10.dp).background(P.surface), state = listState, contentPadding = PaddingValues(12.dp)) {
                items(shown) { l ->
                    val c = when {
                        l.contains("error", true) || l.contains("traceback", true) || l.contains("failed", true) -> P.bad
                        l.contains("warn", true) -> P.warn
                        else -> P.text.copy(alpha = 0.85f)
                    }
                    // drop the date and host from journal lines: time is enough on a phone
                    val m = Regex("^\\d{4}-\\d\\d-\\d\\dT(\\d\\d:\\d\\d:\\d\\d)\\S*\\s+\\S+\\s+(.*)$").find(l)
                    Row(Modifier.padding(vertical = 2.dp)) {
                        if (m != null) Text(m.groupValues[1] + "  ", fontFamily = Mono, fontSize = 11.sp, color = P.faint)
                        Text(m?.groupValues?.get(2) ?: l, fontFamily = Mono, fontSize = 11.sp, color = c, lineHeight = 15.sp)
                    }
                }
            }
        }
    }
    confirm?.let { action ->
        AlertDialog(
            onDismissRequest = { confirm = null }, containerColor = P.surface,
            title = { Text("${action.replaceFirstChar { it.uppercase() }} ${r.title}?") },
            text = { Text(if (r.title.contains("studio", true) && action != "start") "A running Shorts job pauses; press Resume in Studio afterwards." else "Uses sudo on the server.") },
            confirmButton = { TextButton(onClick = {
                confirm = null
                scope.launch {
                    runCatching { Probe.svc(action, r.unit) }.onSuccess { Toast.makeText(ctx, "Done", Toast.LENGTH_SHORT).show(); load() }
                        .onFailure { Toast.makeText(ctx, it.message, Toast.LENGTH_LONG).show() }
                }
            }) { Text(action.replaceFirstChar { it.uppercase() }, color = if (action == "stop") P.bad else P.accent) } },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("Cancel") } },
        )
    }
}

// ------------------------------------------------------------------ Run

private data class Ran(val cmd: String, val code: Int, val out: String, val ms: Long)

@OptIn(ExperimentalFoundationApi::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun RunScreen() {
    val scope = rememberCoroutineScope()
    val snippets by Store.snippets.collectAsState()
    var cmd by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    val history = remember { mutableStateListOf<Ran>() }
    fun run(c: String) {
        if (c.isBlank() || busy) return
        busy = true
        scope.launch {
            val t = System.currentTimeMillis()
            val r = runCatching { Ssh.exec(c, timeoutS = 60) }
            history.add(0, r.fold({ Ran(c, it.code, (it.out + it.err).trimEnd(), System.currentTimeMillis() - t) }, { Ran(c, -1, it.message ?: "failed", 0) }))
            if (history.size > 20) history.removeAt(history.lastIndex)
            busy = false
        }
    }
    Column(Modifier.fillMaxSize().imePadding()) {
        Header("Run", "Runs as ${Ssh.server?.user ?: "you"} in the home folder", onBack = { Nav.pop() })
        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Field("Command", cmd, { cmd = it }, mono = true, placeholder = "df -h", trailing = {
                IconButton(onClick = { if (cmd.isNotBlank() && cmd !in snippets) Store.setSnippets(snippets + cmd.trim()) }) { Icon(Icons.Rounded.BookmarkAdd, "Save snippet", tint = P.muted) }
            })
            Primary("Run", { run(cmd) }, Modifier.fillMaxWidth(), enabled = cmd.isNotBlank(), busy = busy, icon = Icons.Rounded.PlayArrow)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                snippets.forEach { s ->
                    Text(s, fontFamily = Mono, fontSize = 12.sp, color = P.text, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.background(P.surface2, SmallShape)
                            .combinedClickable(onClick = { cmd = s; run(s) }, onLongClick = { Store.setSnippets(snippets - s) })
                            .padding(horizontal = 10.dp, vertical = 7.dp))
                }
            }
            Text("Tap a snippet to run it · long-press to remove", style = MaterialTheme.typography.bodySmall, color = P.faint)
        }
        LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(history) { h ->
                Surface(pad = 12.dp) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("$ ${h.cmd}", fontFamily = Mono, fontSize = 12.sp, color = P.accent, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Pill(if (h.code == 0) "${h.ms} ms" else "exit ${h.code}", if (h.code == 0) P.good else P.bad)
                    }
                    if (h.out.isNotBlank()) {
                        Spacer(Modifier.height(8.dp))
                        SelectionContainer {
                            Text(h.out.takeLast(20_000), fontFamily = Mono, fontSize = 11.sp, lineHeight = 15.sp, color = P.text.copy(alpha = 0.85f),
                                modifier = Modifier.horizontalScroll(rememberScrollState()))
                        }
                    }
                }
            }
        }
    }
}

// ------------------------------------------------------------------ Forwards

@Composable
fun ForwardScreen() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val fwd by Ssh.forwards.collectAsState()
    var port by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize().imePadding()) {
        Header("Port forwards", "Server port → this phone's localhost", onBack = { Nav.pop() })
        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Open a server port here and use it from any app on the phone (a database client, an API tool, another browser). Forwards stay up while Pocket is connected.",
                style = MaterialTheme.typography.bodySmall, color = P.muted)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Field("Server port", port, { port = it.filter(Char::isDigit).take(5) }, Modifier.weight(1f), keyboard = KeyboardType.Number, placeholder = "5432")
                Primary("Open", {
                    val p = port.toIntOrNull() ?: return@Primary
                    scope.launch { runCatching { Ssh.forward(p) }.onSuccess { port = "" }.onFailure { Toast.makeText(ctx, it.message, Toast.LENGTH_LONG).show() } }
                }, enabled = (port.toIntOrNull() ?: 0) in 1..65535, icon = Icons.Rounded.Add)
            }
        }
        LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (fwd.isEmpty()) item { Empty(Icons.Rounded.SwapHoriz, "No forwards open", "Opening an app also opens its forward.") }
            items(fwd.entries.sortedBy { it.key }.toList(), key = { it.key }) { (remote, local) ->
                Surface(pad = 12.dp, onClick = {
                    ctx.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("addr", "127.0.0.1:$local"))
                    Toast.makeText(ctx, "Copied 127.0.0.1:$local", Toast.LENGTH_SHORT).show()
                }) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Dot(P.good); Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text("Server :$remote", style = MaterialTheme.typography.titleMedium, color = P.text)
                            Text("127.0.0.1:$local on this phone · tap to copy", style = MaterialTheme.typography.bodySmall.copy(fontFamily = Mono), color = P.muted)
                        }
                        IconButton(onClick = { WebCache.close(remote); Ssh.closeForward(remote) }) { Icon(Icons.Rounded.Close, "Close", tint = P.muted) }
                    }
                }
            }
        }
    }
}

// ------------------------------------------------------------------ Settings

@Composable
fun SettingsScreen() {
    val s by Store.settings.collectAsState()
    val servers by Store.servers.collectAsState()
    Column(Modifier.fillMaxSize()) {
        Header("Settings", onBack = { Nav.pop() })
        LazyColumn(contentPadding = PaddingValues(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item { Label("Notifications") }
            item { Toggle("Shorts job finished or failed", "Checked by the background connection", s.notifyJobs) { v -> Store.updateSettings { it.copy(notifyJobs = v) } } }
            item { Label("Web view") }
            item { Toggle("Desktop layout", "Ask apps for their full desktop page", s.desktopMode) { v -> Store.updateSettings { it.copy(desktopMode = v) }; WebCache.setDesktopAll(v) } }
            item { Toggle("Apps: web apps only", "Hide ports that don't answer HTTP", s.webOnly) { v -> Store.updateSettings { it.copy(webOnly = v) } } }
            item { Label("Servers") }
            items(servers, key = { it.id }) { sv ->
                Surface(onClick = { Nav.push(Route.Editor(sv.id)) }, pad = 14.dp) {
                    Text(sv.title, style = MaterialTheme.typography.titleMedium, color = P.text)
                    Text(Store.hostKey(sv.id)?.let { "Host key $it" } ?: "Host key not seen yet", style = MaterialTheme.typography.bodySmall.copy(fontFamily = Mono), color = P.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            item {
                Label("About")
                Text("Pocket ${BuildConfig.VERSION_NAME}. Everything goes through your own SSH connection: no account, no cloud relay, no analytics. Keys are encrypted with the Android Keystore.",
                    style = MaterialTheme.typography.bodySmall, color = P.muted, modifier = Modifier.navigationBarsPadding().padding(bottom = 24.dp))
            }
        }
    }
}

@Composable
private fun Toggle(title: String, sub: String, on: Boolean, set: (Boolean) -> Unit) {
    Surface(onClick = { set(!on) }, pad = 14.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium, color = P.text)
                Text(sub, style = MaterialTheme.typography.bodySmall, color = P.muted)
            }
            Switch(on, set, colors = switchColors())
        }
    }
}
