package dev.pocket.aws.core

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Secrets (private keys, AWS keys) are encrypted with a hardware-backed Android Keystore key. */
object Vault {
    private const val ALIAS = "pocket.vault"
    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256).build()
        )
        return gen.generateKey()
    }

    fun seal(plain: String): String {
        if (plain.isEmpty()) return ""
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        val out = c.iv + c.doFinal(plain.toByteArray())
        return Base64.encodeToString(out, Base64.NO_WRAP)
    }

    fun open(sealed: String): String {
        if (sealed.isEmpty()) return ""
        return try {
            val raw = Base64.decode(sealed, Base64.NO_WRAP)
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, raw, 0, 12))
            String(c.doFinal(raw, 12, raw.size - 12))
        } catch (e: Exception) { "" }
    }
}

data class Server(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "",
    val host: String = "",
    val port: Int = 22,
    val user: String = "ubuntu",
    val key: String = "",            // PEM / OpenSSH private key (plain in memory, sealed on disk)
    val passphrase: String = "",
    val studioPort: Int = 8000,      // Shorts Studio; 0 = off
    val awsRegion: String = "",
    val awsInstance: String = "",
    val awsAccess: String = "",
    val awsSecret: String = "",
) {
    val hasAws get() = awsRegion.isNotBlank() && awsInstance.isNotBlank() && awsAccess.isNotBlank() && awsSecret.isNotBlank()
    val title get() = name.ifBlank { host }

    fun toJson() = JSONObject().apply {
        put("id", id); put("name", name); put("host", host); put("port", port); put("user", user)
        put("key", Vault.seal(key)); put("pass", Vault.seal(passphrase)); put("studio", studioPort)
        put("awsRegion", awsRegion); put("awsInstance", awsInstance)
        put("awsAccess", Vault.seal(awsAccess)); put("awsSecret", Vault.seal(awsSecret))
    }

    companion object {
        fun from(o: JSONObject) = Server(
            id = o.getString("id"), name = o.optString("name"), host = o.optString("host"),
            port = o.optInt("port", 22), user = o.optString("user", "ubuntu"),
            key = Vault.open(o.optString("key")), passphrase = Vault.open(o.optString("pass")),
            studioPort = o.optInt("studio", 8000), awsRegion = o.optString("awsRegion"),
            awsInstance = o.optString("awsInstance"), awsAccess = Vault.open(o.optString("awsAccess")),
            awsSecret = Vault.open(o.optString("awsSecret")),
        )
    }
}

/** How a port shows up in Apps: renamed, pinned to Home, hidden, opened at a sub-path. */
data class AppPref(val port: Int, val label: String = "", val path: String = "/", val pinned: Boolean = false, val hidden: Boolean = false) {
    fun toJson() = JSONObject().put("port", port).put("label", label).put("path", path).put("pinned", pinned).put("hidden", hidden)
    companion object {
        fun from(o: JSONObject) = AppPref(o.getInt("port"), o.optString("label"), o.optString("path", "/"), o.optBoolean("pinned"), o.optBoolean("hidden"))
    }
}

data class Settings(
    val webOnly: Boolean = true,        // Apps tab: only ports that answer HTTP
    val showHidden: Boolean = false,
    val notifyJobs: Boolean = true,
    val keepAlive: Boolean = true,      // keep the tunnel open in the background
    val desktopMode: Boolean = false,   // WebView requests the desktop layout
    val lastServer: String = "",
)

object Store {
    private lateinit var p: SharedPreferences
    private val _servers = MutableStateFlow<List<Server>>(emptyList())
    val servers: StateFlow<List<Server>> = _servers
    private val _settings = MutableStateFlow(Settings())
    val settings: StateFlow<Settings> = _settings
    private val _apps = MutableStateFlow<Map<String, Map<Int, AppPref>>>(emptyMap())
    val apps: StateFlow<Map<String, Map<Int, AppPref>>> = _apps
    private val _snippets = MutableStateFlow<List<String>>(emptyList())
    val snippets: StateFlow<List<String>> = _snippets

    val defaultSnippets = listOf("df -h /", "free -h", "uptime", "systemctl --failed --no-pager", "docker ps", "tail -n 40 /var/log/syslog")

    fun init(ctx: Context) {
        p = ctx.getSharedPreferences("pocket", Context.MODE_PRIVATE)
        _servers.value = JSONArray(p.getString("servers", "[]")).let { a -> (0 until a.length()).mapNotNull { runCatching { Server.from(a.getJSONObject(it)) }.getOrNull() } }
        val s = JSONObject(p.getString("settings", "{}")!!)
        _settings.value = Settings(
            webOnly = s.optBoolean("webOnly", true), showHidden = s.optBoolean("showHidden"),
            notifyJobs = s.optBoolean("notifyJobs", true), keepAlive = s.optBoolean("keepAlive", true),
            desktopMode = s.optBoolean("desktopMode"), lastServer = s.optString("lastServer"),
        )
        val a = JSONObject(p.getString("apps", "{}")!!)
        _apps.value = a.keys().asSequence().associateWith { sid ->
            val arr = a.getJSONArray(sid)
            (0 until arr.length()).map { AppPref.from(arr.getJSONObject(it)) }.associateBy { it.port }
        }
        _snippets.value = p.getString("snippets", null)?.let { j -> JSONArray(j).let { arr -> (0 until arr.length()).map { arr.getString(it) } } } ?: defaultSnippets
    }

    fun server(id: String) = _servers.value.firstOrNull { it.id == id }

    fun saveServer(s: Server) {
        val list = _servers.value.toMutableList()
        val i = list.indexOfFirst { it.id == s.id }
        if (i >= 0) list[i] = s else list += s
        _servers.value = list
        p.edit().putString("servers", JSONArray(list.map { it.toJson() }).toString()).apply()
    }

    fun deleteServer(id: String) {
        _servers.value = _servers.value.filterNot { it.id == id }
        p.edit().putString("servers", JSONArray(_servers.value.map { it.toJson() }).toString()).remove("hostkey.$id").apply()
        _apps.value = _apps.value - id
        persistApps()
    }

    fun updateSettings(f: (Settings) -> Settings) {
        val s = f(_settings.value)
        _settings.value = s
        p.edit().putString("settings", JSONObject().put("webOnly", s.webOnly).put("showHidden", s.showHidden)
            .put("notifyJobs", s.notifyJobs).put("keepAlive", s.keepAlive).put("desktopMode", s.desktopMode)
            .put("lastServer", s.lastServer).toString()).apply()
    }

    fun appPrefs(serverId: String): Map<Int, AppPref> = _apps.value[serverId].orEmpty()

    fun setApp(serverId: String, pref: AppPref) {
        val m = appPrefs(serverId).toMutableMap()
        m[pref.port] = pref
        _apps.value = _apps.value + (serverId to m)
        persistApps()
    }

    fun removeApp(serverId: String, port: Int) {
        _apps.value = _apps.value + (serverId to (appPrefs(serverId) - port))
        persistApps()
    }

    private fun persistApps() {
        val o = JSONObject()
        _apps.value.forEach { (sid, m) -> o.put(sid, JSONArray(m.values.map { it.toJson() })) }
        p.edit().putString("apps", o.toString()).apply()
    }

    fun setSnippets(list: List<String>) {
        _snippets.value = list
        p.edit().putString("snippets", JSONArray(list).toString()).apply()
    }

    // Host key pinning (trust on first use), keyed by server id.
    fun hostKey(serverId: String): String? = p.getString("hostkey.$serverId", null)
    fun setHostKey(serverId: String, fp: String?) = p.edit().apply { if (fp == null) remove("hostkey.$serverId") else putString("hostkey.$serverId", fp) }.apply()
}
