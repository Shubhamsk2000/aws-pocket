package dev.pocket.aws.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import dev.pocket.aws.core.PortInfo
import dev.pocket.aws.core.ShortsJob
import dev.pocket.aws.core.Ssh
import dev.pocket.aws.core.Stats
import dev.pocket.aws.core.Studio
import dev.pocket.aws.core.Probe
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow

/** Last known results, so every screen paints instantly and refreshes in the background. */
object Live {
    val stats = MutableStateFlow<Stats?>(null)
    val cpu = MutableStateFlow<List<Float>>(emptyList())
    val mem = MutableStateFlow<List<Float>>(emptyList())
    val net = MutableStateFlow(0.0 to 0.0)            // rx, tx bytes per second
    val ports = MutableStateFlow<List<PortInfo>?>(null)
    val jobs = MutableStateFlow<List<ShortsJob>?>(null)
    val statsError = MutableStateFlow<String?>(null)
    val portsError = MutableStateFlow<String?>(null)
    val jobsError = MutableStateFlow<String?>(null)
    private var prev: Stats? = null
    private var owner: String? = null

    /** Drop everything cached when the connected server changes. */
    fun bind(serverId: String?) {
        if (serverId == owner) return
        owner = serverId
        stats.value = null; cpu.value = emptyList(); mem.value = emptyList(); ports.value = null; jobs.value = null
        prev = null
    }

    suspend fun refreshStats() {
        if (!Ssh.online) return
        runCatching { Probe.stats() }.onSuccess { s ->
            val p = prev
            if (p != null && s.cpuTotal > p.cpuTotal) {
                val busy = 1f - (s.cpuIdle - p.cpuIdle).toFloat() / (s.cpuTotal - p.cpuTotal)
                cpu.value = (cpu.value + busy.coerceIn(0f, 1f)).takeLast(60)
                val dt = (s.time - p.time).coerceAtLeast(0.5)
                net.value = (s.rx - p.rx) / dt to (s.tx - p.tx) / dt
            }
            mem.value = (mem.value + (1f - s.memAvail.toFloat() / s.memTotal)).takeLast(60)
            prev = s; stats.value = s; statsError.value = null
        }.onFailure { statsError.value = it.message }
    }

    suspend fun refreshPorts() {
        if (!Ssh.online) return
        runCatching { Probe.ports() }.onSuccess { ports.value = it; portsError.value = null }.onFailure { portsError.value = it.message }
    }

    suspend fun refreshJobs() {
        if (!Ssh.online || (Ssh.server?.studioPort ?: 0) <= 0) return
        runCatching { Studio.jobs() }
            .onSuccess { jobs.value = it.sortedByDescending { j -> j.updatedAt }; jobsError.value = null }
            .onFailure { jobsError.value = it.message }
    }
}

/** Runs [block] every [everyMs] while the screen is visible (stops in background: no wasted battery). */
@Composable
fun Poll(everyMs: Long, key: Any? = Unit, block: suspend () -> Unit) {
    val owner = LocalLifecycleOwner.current
    val current by rememberUpdatedState(block)
    LaunchedEffect(owner, key) {
        owner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) { current(); delay(everyMs) }
        }
    }
}

sealed interface Route {
    data class Editor(val id: String?) : Route
    data class Web(val port: Int, val path: String, val title: String) : Route
    data class Logs(val unit: String, val title: String) : Route
    data object Procs : Route
    data object Services : Route
    data object Run : Route
    data object Settings : Route
    data object Forward : Route
}

object Nav {
    val stack = mutableStateListOf<Route>()
    var tab by mutableStateOf(0)
    fun push(r: Route) { stack.add(r) }
    fun pop(): Boolean = if (stack.isNotEmpty()) { stack.removeAt(stack.lastIndex); true } else false
    fun reset() { stack.clear(); tab = 0 }
}
