package dev.pocket.aws.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Dashboard
import androidx.compose.material.icons.rounded.LinkOff
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.PushPin
import androidx.compose.material.icons.rounded.Widgets
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.pocket.aws.core.AppPref
import dev.pocket.aws.core.Conn
import dev.pocket.aws.core.PortInfo
import dev.pocket.aws.core.Ssh
import dev.pocket.aws.core.Store

private data class Tab(val label: String, val icon: ImageVector)
private val tabs = listOf(Tab("Home", Icons.Rounded.Dashboard), Tab("Apps", Icons.Rounded.Apps), Tab("Shorts", Icons.Rounded.Movie), Tab("Tools", Icons.Rounded.Widgets))

@Composable
fun MainScreen() {
    val conn by Ssh.state.collectAsState()
    Column(Modifier.fillMaxSize()) {
        AnimatedVisibility(conn is Conn.Reconnecting) {
            Row(Modifier.fillMaxWidth().background(P.warn.copy(alpha = 0.14f)).statusBarsPadding().padding(horizontal = 20.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Dot(P.warn); Spacer(Modifier.width(10.dp))
                Text("Reconnecting… (attempt ${(conn as? Conn.Reconnecting)?.attempt ?: 1})", style = MaterialTheme.typography.labelMedium, color = P.warn)
            }
        }
        Box(Modifier.weight(1f)) {
            Crossfade(Nav.tab, animationSpec = tween(160), label = "tab") { t ->
                when (t) {
                    0 -> HomeTab()
                    1 -> AppsTab()
                    2 -> ShortsTab()
                    else -> ToolsTab()
                }
            }
        }
        BottomBar()
    }
}

@Composable
private fun BottomBar() {
    Row(
        Modifier.fillMaxWidth().background(P.bg).navigationBarsPadding().padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceAround,
    ) {
        tabs.forEachIndexed { i, t ->
            val on = Nav.tab == i
            Column(
                Modifier.weight(1f).clip(RoundedCornerShape(14.dp))
                    .clickable(remember { MutableInteractionSource() }, null) { Nav.tab = i }.padding(vertical = 6.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(Modifier.clip(CircleShape).background(if (on) P.accent.copy(alpha = 0.14f) else Color.Transparent).padding(horizontal = 18.dp, vertical = 5.dp)) {
                    Icon(t.icon, t.label, tint = if (on) P.accent else P.muted, modifier = Modifier.size(22.dp))
                }
                Spacer(Modifier.height(3.dp))
                Text(t.label, style = MaterialTheme.typography.labelSmall, color = if (on) P.text else P.muted)
            }
        }
    }
}

// ------------------------------------------------------------------ Home

@Composable
fun HomeTab() {
    val stats by Live.stats.collectAsState()
    val cpu by Live.cpu.collectAsState()
    val mem by Live.mem.collectAsState()
    val net by Live.net.collectAsState()
    val ports by Live.ports.collectAsState()
    val jobs by Live.jobs.collectAsState()
    val err by Live.statsError.collectAsState()
    val apps by Store.apps.collectAsState()
    val server = Ssh.server
    Poll(2500) { Live.refreshStats() }
    Poll(15_000) { Live.refreshPorts() }
    Poll(6000) { Live.refreshJobs() }

    val prefs = apps[server?.id].orEmpty()
    val pinned = prefs.values.filter { it.pinned }.sortedBy { it.port }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp)) {
        item {
            Header(server?.title ?: "Server", stats?.let { "Up ${dur(it.uptime)} · ${it.os}" } ?: "Connected") {
                IconButton(onClick = { Ssh.disconnect() }) { Icon(Icons.Rounded.LinkOff, "Disconnect", tint = P.muted) }
            }
        }
        err?.let { e -> item { ErrorNote(e) } }
        item {
            val s = stats
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Gauge("CPU", cpu.lastOrNull()?.let { pct(it.toDouble()) } ?: "–", s?.let { "load ${it.load.firstOrNull() ?: 0.0} · ${it.cores} cores" } ?: "", cpu, P.accent, Modifier.weight(1f))
                Gauge("Memory", mem.lastOrNull()?.let { pct(it.toDouble()) } ?: "–", s?.let { "${bytes(it.memTotal - it.memAvail)} of ${bytes(it.memTotal)}" } ?: "", mem, P.good, Modifier.weight(1f))
            }
        }
        item {
            val s = stats ?: return@item
            Spacer(Modifier.height(10.dp))
            Surface {
                s.disks.forEachIndexed { i, d ->
                    if (i > 0) Spacer(Modifier.height(12.dp))
                    val used = 1.0 - d.free.toDouble() / d.total
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Disk ${d.mount}", style = MaterialTheme.typography.labelLarge, color = P.text, modifier = Modifier.weight(1f))
                        Text("${bytes(d.free)} free", style = MaterialTheme.typography.bodySmall, color = P.muted)
                    }
                    Spacer(Modifier.height(8.dp))
                    Bar(used.toFloat(), if (used > 0.9) P.bad else if (used > 0.75) P.warn else P.text.copy(alpha = 0.8f))
                }
                if (s.swapTotal > 0) {
                    Spacer(Modifier.height(12.dp))
                    Row {
                        Text("Swap", style = MaterialTheme.typography.labelLarge, color = P.text, modifier = Modifier.weight(1f))
                        Text("${bytes(s.swapTotal - s.swapFree)} used", style = MaterialTheme.typography.bodySmall, color = P.muted)
                    }
                }
                Spacer(Modifier.height(12.dp))
                Row {
                    Stat("↓ ${bytes(net.first.toLong())}/s", "network in", Modifier.weight(1f))
                    Stat("↑ ${bytes(net.second.toLong())}/s", "network out", Modifier.weight(1f))
                    Stat("${s.procs}", "processes", Modifier.weight(1f))
                }
            }
        }
        jobs?.firstOrNull { it.active }?.let { j ->
            item {
                Label("Shorts")
                Surface(onClick = { Nav.tab = 2 }) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Tile(Icons.Rounded.Movie, P.accent, 36.dp)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(j.title, style = MaterialTheme.typography.titleMedium, color = P.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text("${j.current?.label ?: j.status.replaceFirstChar { it.uppercase() }} · ${pct(j.progress)}", style = MaterialTheme.typography.bodySmall, color = P.muted)
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    Bar(j.progress.toFloat())
                }
            }
        }
        item {
            Label("Pinned apps", action = { Text("Edit in Apps", style = MaterialTheme.typography.labelMedium, color = P.accent, modifier = Modifier.clickable { Nav.tab = 1 }) })
        }
        if (pinned.isEmpty()) item {
            Surface(onClick = { Nav.tab = 1 }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Tile(Icons.Rounded.PushPin, P.muted, 36.dp); Spacer(Modifier.width(12.dp))
                    Text("Pin the web apps you use most. They show up here, one tap to open.", style = MaterialTheme.typography.bodyMedium, color = P.muted)
                }
            }
        } else items(pinned.chunked(2)) { row ->
            Row(Modifier.fillMaxWidth().padding(bottom = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEach { p -> AppTile(p, ports?.firstOrNull { it.port == p.port }, Modifier.weight(1f)) }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun Gauge(title: String, value: String, sub: String, hist: List<Float>, color: Color, modifier: Modifier) {
    Surface(modifier) {
        Text(title, style = MaterialTheme.typography.labelMedium, color = P.muted)
        Text(value, style = MaterialTheme.typography.headlineMedium, color = P.text)
        Spacer(Modifier.height(8.dp))
        Spark(hist, color, Modifier.fillMaxWidth().height(44.dp))
        Spacer(Modifier.height(8.dp))
        Text(sub, style = MaterialTheme.typography.bodySmall, color = P.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun Stat(value: String, label: String, modifier: Modifier) = Column(modifier) {
    Text(value, style = MaterialTheme.typography.labelLarge, color = P.text, maxLines = 1)
    Text(label, style = MaterialTheme.typography.bodySmall, color = P.muted)
}

@Composable
fun AppTile(p: AppPref, live: PortInfo?, modifier: Modifier) {
    val name = appName(p, live)
    val up = live != null
    Surface(modifier, onClick = { if (up) Nav.push(Route.Web(p.port, p.path, name)) }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Letter(name, if (up) P.accent else P.faint)
            Spacer(Modifier.weight(1f))
            Dot(if (up) P.good else P.faint)
        }
        Spacer(Modifier.height(14.dp))
        Text(name, style = MaterialTheme.typography.titleMedium, color = if (up) P.text else P.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(if (up) ":${p.port}" else ":${p.port} · offline", style = MaterialTheme.typography.bodySmall, color = P.muted)
    }
}

@Composable
fun Letter(name: String, color: Color, size: androidx.compose.ui.unit.Dp = 38.dp) {
    Box(Modifier.size(size).clip(RoundedCornerShape(11.dp)).background(color.copy(alpha = 0.14f)).border(1.dp, color.copy(alpha = 0.18f), RoundedCornerShape(11.dp)), contentAlignment = Alignment.Center) {
        Text(name.firstOrNull { it.isLetterOrDigit() }?.uppercase() ?: "•", color = color, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleMedium)
    }
}

fun appName(p: AppPref?, live: PortInfo?): String =
    p?.label?.takeIf { it.isNotBlank() } ?: live?.title?.takeIf { it.isNotBlank() } ?: live?.process?.replaceFirstChar { it.uppercase() } ?: "Port ${p?.port ?: live?.port}"

