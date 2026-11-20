package dev.pocket.aws.core

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.util.Base64
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import net.schmizz.keepalive.KeepAliveProvider
import net.schmizz.sshj.DefaultConfig
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.common.Buffer
import net.schmizz.sshj.connection.channel.direct.Parameters
import net.schmizz.sshj.transport.verification.HostKeyVerifier
import net.schmizz.sshj.userauth.password.PasswordUtils
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.security.MessageDigest
import java.security.PublicKey
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

sealed interface Conn {
    data object Idle : Conn
    data class Connecting(val server: Server, val step: String) : Conn
    data class Online(val server: Server, val since: Long) : Conn
    data class Reconnecting(val server: Server, val attempt: Int) : Conn
    data class Failed(val server: Server, val message: String, val hostKeyChanged: String? = null) : Conn
}

class ExecResult(val code: Int, val out: String, val err: String) {
    val ok get() = code == 0
}

/** One SSH connection to one server: command exec and local port forwards that survive reconnects. */
object Ssh {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Mutex()
    private var client: SSHClient? = null
    private var watchdog: Job? = null
    private var appCtx: Context? = null

    private val _state = MutableStateFlow<Conn>(Conn.Idle)
    val state: StateFlow<Conn> = _state

    /** remote port -> local port on the phone's loopback */
    private val _forwards = MutableStateFlow<Map<Int, Int>>(emptyMap())
    val forwards: StateFlow<Map<Int, Int>> = _forwards
    private val sockets = mutableMapOf<Int, ServerSocket>()

    val server: Server? get() = when (val s = _state.value) {
        is Conn.Online -> s.server; is Conn.Reconnecting -> s.server; is Conn.Connecting -> s.server; else -> null
    }
    val online get() = _state.value is Conn.Online

    private var probePath: String? = null
    @Volatile private var wanted = false   // false after Disconnect: stops any reconnect loop

    fun init(ctx: Context) {
        appCtx = ctx.applicationContext
        val cm = ctx.getSystemService(ConnectivityManager::class.java)
        cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                // network switched (Wi-Fi <-> mobile): the old TCP socket is dead, reconnect right away
                if (_state.value is Conn.Online || _state.value is Conn.Reconnecting) scope.launch { healthCheck(force = true) }
            }
        })
    }

    fun connect(s: Server, trustNewKey: Boolean = false) {
        scope.launch {
            wanted = true
            lock.withLock {
                closeClient()
                _state.value = Conn.Connecting(s, "Connecting to ${s.host}")
                var target = s
                try {
                    if (s.hasAws) {
                        _state.value = Conn.Connecting(s, "Checking the instance")
                        val inst = Aws.describe(s)
                        if (inst.state == "stopped" || inst.state == "stopping") throw IllegalStateException("The instance is ${inst.state}. Start it first.")
                        if (inst.publicIp != null && inst.publicIp != s.host) {
                            target = s.copy(host = inst.publicIp); Store.saveServer(target)
                        }
                    }
                    open(target, trustNewKey)
                    Store.updateSettings { it.copy(lastServer = target.id) }
                    _state.value = Conn.Online(target, System.currentTimeMillis())
                    startWatchdog()
                } catch (e: HostKeyChanged) {
                    _state.value = Conn.Failed(target, "The server's identity changed.", e.fp)
                } catch (e: Exception) {
                    _state.value = Conn.Failed(target, friendly(e))
                }
            }
        }
    }

    fun disconnect() {
        wanted = false
        watchdog?.cancel(); watchdog = null
        scope.launch {
            lock.withLock {
                closeClient()
                sockets.values.forEach { runCatching { it.close() } }
                sockets.clear()
                _forwards.value = emptyMap()
                _state.value = Conn.Idle
            }
        }
    }

    private class HostKeyChanged(val fp: String) : Exception()

    private fun fingerprint(k: PublicKey): String {
        val blob = Buffer.PlainBuffer().putPublicKey(k).compactData
        return "SHA256:" + Base64.encodeToString(MessageDigest.getInstance("SHA-256").digest(blob), Base64.NO_WRAP or Base64.NO_PADDING)
    }

    private fun open(s: Server, trustNewKey: Boolean) {
        val cfg = DefaultConfig().apply { keepAliveProvider = KeepAliveProvider.KEEP_ALIVE }
        val c = SSHClient(cfg)
        c.connectTimeout = 10_000
        c.timeout = 30_000
        c.addHostKeyVerifier(object : HostKeyVerifier {
            override fun verify(hostname: String, port: Int, key: PublicKey): Boolean {
                val fp = fingerprint(key)
                val known = Store.hostKey(s.id)
                if (known == null || trustNewKey) { Store.setHostKey(s.id, fp); return true }
                if (known == fp) return true
                throw HostKeyChanged(fp)
            }
            override fun findExistingAlgorithms(hostname: String, port: Int): List<String> = emptyList()
        })
        try {
            c.connect(s.host.trim(), s.port)
            c.connection.keepAlive.keepAliveInterval = 10
            _state.value = Conn.Connecting(s, "Signing in as ${s.user}")
            val keys = c.loadKeys(s.key.trim() + "\n", null, s.passphrase.takeIf { it.isNotEmpty() }?.let { PasswordUtils.createOneOff(it.toCharArray()) })
            c.authPublickey(s.user.trim(), keys)
        } catch (e: Exception) {
            runCatching { c.disconnect() }
            // sshj wraps verifier exceptions in a TransportException
            var t: Throwable? = e
            while (t != null) { if (t is HostKeyChanged) throw t; t = t.cause }
            throw e
        }
        client = c
        probePath = null
        // bring back every forward on the same local port, so open pages keep working
        val wanted = _forwards.value
        sockets.values.forEach { runCatching { it.close() } }
        sockets.clear()
        val fresh = mutableMapOf<Int, Int>()
        wanted.forEach { (remote, local) -> runCatching { fresh[remote] = startForward(c, remote, local) } }
        _forwards.value = fresh
    }

    private fun closeClient() {
        client?.let { c -> runCatching { c.disconnect() } }
        client = null
    }

    private fun startWatchdog() {
        watchdog?.cancel()
        watchdog = scope.launch {
            while (isActive) {
                delay(4000)
                healthCheck(force = false)
            }
        }
    }

    private suspend fun healthCheck(force: Boolean) {
        val s = server ?: return
        if (!wanted) return
        val c = client
        val alive = !force && c != null && c.isConnected && c.isAuthenticated
        if (alive) return
        if (force && c != null && c.isConnected) {
            // network changed: test the socket with a tiny command
            val ok = runCatching { withTimeout(4000) { exec("true") }.ok }.getOrDefault(false)
            if (ok) return
        }
        lock.withLock {
            var attempt = 1
            while (wanted) {
                _state.value = Conn.Reconnecting(s, attempt)
                closeClient()
                try {
                    var target = s
                    if (s.hasAws) runCatching { Aws.describe(s) }.getOrNull()?.publicIp?.let { ip ->
                        if (ip != s.host) { target = s.copy(host = ip); Store.saveServer(target) }
                    }
                    open(target, false)
                    _state.value = Conn.Online(target, System.currentTimeMillis())
                    return
                } catch (e: HostKeyChanged) {
                    _state.value = Conn.Failed(s, "The server's identity changed.", e.fp); watchdog?.cancel(); return
                } catch (e: Exception) {
                    if (attempt >= 30) { _state.value = Conn.Failed(s, friendly(e)); watchdog?.cancel(); return }
                    delay((1000L * attempt).coerceAtMost(8000))
                    attempt++
                }
            }
        }
    }

    // ------------------------------------------------------------------ exec

    suspend fun exec(command: String, stdin: ByteArray? = null, timeoutS: Long = 30): ExecResult = withContext(Dispatchers.IO) {
        val c = client ?: throw IllegalStateException("Not connected")
        c.startSession().use { session ->
            val cmd = session.exec(command)
            if (stdin != null) { cmd.outputStream.write(stdin); cmd.outputStream.flush() }
            runCatching { cmd.outputStream.close() }
            val err = async { cmd.errorStream.readBytes() }
            val out = cmd.inputStream.readBytes()
            val e = err.await()
            cmd.join(timeoutS, TimeUnit.SECONDS)
            ExecResult(cmd.exitStatus ?: -1, String(out), String(e))
        }
    }

    /** Runs a probe.py command; uploads the script once per connection (path keyed by content hash). */
    suspend fun probe(vararg args: String): String {
        val path = probePath ?: run {
            val script = appCtx!!.assets.open("probe.py").use { it.readBytes() }
            val hash = MessageDigest.getInstance("SHA-1").digest(script).joinToString("") { "%02x".format(it) }.take(10)
            val p = "\$HOME/.cache/aws-pocket/probe-$hash.py"
            val r = exec("test -s \"$p\" || { mkdir -p \"\$HOME/.cache/aws-pocket\" && cat > \"$p\"; }", script)
            if (!r.ok) throw IllegalStateException(r.err.ifBlank { "Could not install the probe" })
            probePath = p; p
        }
        val r = exec("python3 \"$path\" " + args.joinToString(" ") { q(it) })
        if (r.out.isBlank()) throw IllegalStateException(r.err.lineSequence().lastOrNull { it.isNotBlank() } ?: "python3 is missing on the server")
        return r.out
    }

    fun q(s: String) = "'" + s.replace("'", "'\\''") + "'"

    // ------------------------------------------------------------------ forwards

    /** Opens (or reuses) a forward and returns the local port to use with http://127.0.0.1:<port>. */
    suspend fun forward(remotePort: Int): Int = withContext(Dispatchers.IO) {
        _forwards.value[remotePort]?.let { local -> if (sockets[remotePort]?.isClosed == false) return@withContext local }
        val c = client ?: throw IllegalStateException("Not connected")
        val preferred = if (remotePort in 1..45000) 20000 + remotePort else 0   // stable port = the page keeps its storage
        val local = startForward(c, remotePort, preferred)
        _forwards.value = _forwards.value + (remotePort to local)
        local
    }

    fun closeForward(remotePort: Int) {
        sockets.remove(remotePort)?.let { runCatching { it.close() } }
        _forwards.value = _forwards.value - remotePort
    }

    private fun startForward(c: SSHClient, remotePort: Int, preferredLocal: Int): Int {
        val ss = ServerSocket().apply { reuseAddress = true }
        try {
            ss.bind(InetSocketAddress("127.0.0.1", preferredLocal))
        } catch (e: Exception) {
            ss.bind(InetSocketAddress("127.0.0.1", 0))
        }
        val local = ss.localPort
        val params = Parameters("127.0.0.1", local, "127.0.0.1", remotePort)
        val fwd = c.newLocalPortForwarder(params, ss)
        sockets[remotePort] = ss
        thread(name = "fwd-$remotePort", isDaemon = true) { runCatching { fwd.listen() } }
        return local
    }

    private fun friendly(e: Throwable): String {
        val m = (e.message ?: e.javaClass.simpleName)
        return when {
            e is java.net.UnknownHostException -> "Can't find ${m}. Check the host."
            e is java.net.SocketTimeoutException || m.contains("timed out", true) ->
                "Timed out. Is the instance running, and does its security group allow SSH from this network?"
            e is java.net.ConnectException -> "Connection refused on that port."
            m.contains("Exhausted available authentication", true) || m.contains("auth", true) && m.contains("fail", true) ->
                "The server rejected the key. Check the user name (ubuntu / ec2-user) and the key."
            m.contains("password", true) || m.contains("decrypt", true) -> "The key needs a passphrase (or the passphrase is wrong)."
            else -> m
        }
    }
}
