package dev.pocket.aws.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

// ------------------------------------------------------------------ probe results

data class Disk(val mount: String, val total: Long, val free: Long)
data class Stats(
    val host: String, val os: String, val kernel: String, val cores: Int, val uptime: Double, val load: List<Double>,
    val cpuIdle: Long, val cpuTotal: Long, val memTotal: Long, val memAvail: Long, val swapTotal: Long, val swapFree: Long,
    val disks: List<Disk>, val rx: Long, val tx: Long, val procs: Int, val time: Double,
)

data class PortInfo(
    val port: Int, val addrs: List<String>, val process: String?, val pid: Int?, val http: Boolean, val title: String?,
    val public: Boolean, val cmd: String, val cwd: String, val rss: Long, val user: String,
)

data class Proc(val pid: Int, val name: String, val cpu: Double, val rss: Long, val user: String, val cmd: String, val age: Long)
data class Unit_(val unit: String, val active: String, val sub: String, val desc: String)
data class Container(val name: String, val image: String, val state: String, val status: String, val ports: String)

private fun JSONObject.err() { optString("error").takeIf { it.isNotBlank() && it != "null" }?.let { throw IllegalStateException(it) } }
private fun JSONObject.str(k: String) = if (isNull(k)) "" else optString(k)
private inline fun <T> JSONArray.list(f: (JSONObject) -> T) = (0 until length()).map { f(getJSONObject(it)) }

object Probe {
    suspend fun stats(): Stats {
        val o = JSONObject(Ssh.probe("stats")).also { it.err() }
        val cpu = o.getJSONObject("cpu"); val mem = o.getJSONObject("mem"); val swap = o.getJSONObject("swap"); val net = o.getJSONObject("net")
        val la = o.getJSONArray("load")
        return Stats(
            o.str("host"), o.str("os"), o.str("kernel"), o.optInt("cores", 1), o.optDouble("uptime"),
            (0 until la.length()).map { la.getDouble(it) }, cpu.getLong("idle"), cpu.getLong("total"),
            mem.getLong("total"), mem.getLong("available"), swap.getLong("total"), swap.getLong("free"),
            o.getJSONArray("disks").list { Disk(it.str("mount"), it.getLong("total"), it.getLong("free")) },
            net.getLong("rx"), net.getLong("tx"), o.optInt("procs"), o.optDouble("time"),
        )
    }

    suspend fun ports(): List<PortInfo> {
        val o = JSONObject(Ssh.probe("ports")).also { it.err() }
        return o.getJSONArray("ports").list { p ->
            val a = p.getJSONArray("addrs")
            PortInfo(
                p.getInt("port"), (0 until a.length()).map { a.getString(it) }, p.str("process").ifBlank { null },
                if (p.isNull("pid")) null else p.getInt("pid"), p.optBoolean("http"), p.str("title").ifBlank { null },
                p.optBoolean("public"), p.str("cmd"), p.str("cwd"), p.optLong("rss"), p.str("user"),
            )
        }
    }

    suspend fun procs(): List<Proc> {
        val o = JSONObject(Ssh.probe("procs")).also { it.err() }
        return o.getJSONArray("procs").list { Proc(it.getInt("pid"), it.str("name"), it.optDouble("cpu"), it.optLong("rss"), it.str("user"), it.str("cmd"), it.optLong("age")) }
    }

    suspend fun services(): Pair<List<Unit_>, List<Container>> {
        val o = JSONObject(Ssh.probe("services")).also { it.err() }
        return o.getJSONArray("services").list { Unit_(it.str("unit"), it.str("active"), it.str("sub"), it.str("desc")) } to
            o.getJSONArray("containers").list { Container(it.str("name"), it.str("image"), it.str("state"), it.str("status"), it.str("ports")) }
    }

    suspend fun logs(unit: String, n: Int = 300): List<String> {
        val o = JSONObject(Ssh.probe("logs", unit, n.toString())).also { it.err() }
        val a = o.getJSONArray("lines")
        return (0 until a.length()).map { a.getString(it) }
    }

    suspend fun svc(action: String, unit: String) {
        val o = JSONObject(Ssh.probe("svc", action, unit))
        if (!o.optBoolean("ok")) throw IllegalStateException(o.str("error").ifBlank { "Failed" })
    }

    suspend fun kill(pid: Int, hard: Boolean) {
        val o = JSONObject(Ssh.probe("kill", pid.toString(), if (hard) "KILL" else "TERM"))
        if (!o.optBoolean("ok")) throw IllegalStateException(o.str("error").ifBlank { "Failed" })
    }
}

// ------------------------------------------------------------------ Shorts Studio

data class Stage(val key: String, val label: String, val status: String, val progress: Double?, val message: String?, val eta: Double?)
data class ShortsJob(
    val id: String, val title: String, val source: String, val status: String, val error: String?, val progress: Double,
    val stages: List<Stage>, val clips: Int, val approved: Int, val exported: Int, val stopping: Boolean,
    val exportStatus: String?, val exportProgress: Double?, val updatedAt: String,
) {
    val current get() = stages.firstOrNull { it.status == "running" }
    val active get() = status == "queued" || status == "running" || exportStatus == "running" || exportStatus == "queued"
}

object Studio {
    private suspend fun http(method: String, path: String, body: JSONObject? = null): String = withContext(Dispatchers.IO) {
        val s = Ssh.server ?: throw IllegalStateException("Not connected")
        if (s.studioPort <= 0) throw IllegalStateException("Shorts Studio is off for this server")
        val local = Ssh.forward(s.studioPort)
        val c = URL("http://127.0.0.1:$local$path").openConnection() as HttpURLConnection
        c.requestMethod = method
        c.connectTimeout = 8000; c.readTimeout = 30000
        if (body != null) {
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json")
            c.outputStream.use { it.write(body.toString().toByteArray()) }
        }
        val code = c.responseCode
        val text = (if (code < 400) c.inputStream else c.errorStream)?.bufferedReader()?.readText().orEmpty()
        if (code >= 400) {
            val detail = runCatching { JSONObject(text).opt("detail") }.getOrNull()
            val msg = when (detail) { is String -> detail; is JSONArray -> detail.optJSONObject(0)?.optString("msg"); else -> null }
            throw IllegalStateException(msg ?: "Studio error $code")
        }
        text
    }

    private fun job(o: JSONObject): ShortsJob {
        val st = o.optJSONObject("stats") ?: JSONObject()
        val ex = o.optJSONObject("export")
        return ShortsJob(
            o.getString("id"), o.str("title"), o.str("source"), o.str("status"), o.str("error").ifBlank { null },
            o.optDouble("progress", 0.0),
            o.getJSONArray("stages").list {
                Stage(it.str("key"), it.str("label"), it.str("status"), if (it.isNull("progress")) null else it.optDouble("progress"),
                    it.str("message").ifBlank { null }, if (it.isNull("eta_s")) null else it.optDouble("eta_s"))
            },
            st.optInt("clips"), st.optInt("approved"), st.optInt("exported"), o.optBoolean("stopping"),
            ex?.str("status")?.ifBlank { null }, ex?.let { if (it.isNull("progress")) null else it.optDouble("progress") }, o.str("updated_at"),
        )
    }

    suspend fun jobs(): List<ShortsJob> = JSONArray(http("GET", "/api/shorts/jobs")).list { job(it) }
    suspend fun create(url: String, focus: String = "", only: Boolean = false): ShortsJob =
        job(JSONObject(http("POST", "/api/shorts/jobs", JSONObject().put("url", url).put("focus", focus).put("focus_mode", if (only) "only" else "prefer"))))
    suspend fun stop(id: String) { http("POST", "/api/shorts/jobs/${enc(id)}/stop") }
    suspend fun retry(id: String) { http("POST", "/api/shorts/jobs/${enc(id)}/retry") }

    private fun enc(s: String) = java.net.URLEncoder.encode(s, "UTF-8").replace("+", "%20")
}

fun extractUrl(text: String): String? = Regex("https?://\\S+").find(text)?.value?.trimEnd('.', ',', ')', '"', '\'')
