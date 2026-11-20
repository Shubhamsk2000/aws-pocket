package dev.pocket.aws.ui

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.FileOpen
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import dev.pocket.aws.core.Aws
import dev.pocket.aws.core.Conn
import dev.pocket.aws.core.Instance
import dev.pocket.aws.core.Server
import dev.pocket.aws.core.Ssh
import dev.pocket.aws.core.Store
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun ServersScreen() {
    val servers by Store.servers.collectAsState()
    val conn by Ssh.state.collectAsState()
    val inst = remember { mutableStateMapOf<String, Instance?>() }
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current

    Poll(10_000, servers.map { it.id }) {
        servers.filter { it.hasAws }.forEach { s -> runCatching { Aws.describe(s) }.onSuccess { inst[s.id] = it } }
    }

    Column(Modifier.fillMaxSize()) {
        Header("Pocket", "Your servers, one tap away") {
            IconButton(onClick = { Nav.push(Route.Settings) }) { Icon(Icons.Rounded.Settings, "Settings", tint = P.muted) }
        }
        LazyColumn(Modifier.weight(1f), contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            val c = conn
            if (c is Conn.Failed) item {
                Surface {
                    Text("Couldn't connect to ${c.server.title}", style = MaterialTheme.typography.titleMedium, color = P.text)
                    Spacer(Modifier.height(6.dp))
                    Text(c.message, style = MaterialTheme.typography.bodyMedium, color = P.muted)
                    if (c.hostKeyChanged != null) {
                        Spacer(Modifier.height(8.dp))
                        Text("New fingerprint: ${c.hostKeyChanged}\nThis is expected if you rebuilt the instance. Otherwise don't trust it.",
                            style = MaterialTheme.typography.bodySmall, color = P.warn)
                        Spacer(Modifier.height(10.dp))
                        Secondary("Trust the new key", { Ssh.connect(c.server, trustNewKey = true) }, color = P.warn)
                    } else {
                        Spacer(Modifier.height(10.dp))
                        Secondary("Try again", { Ssh.connect(c.server) })
                    }
                }
            }
            if (servers.isEmpty()) item {
                Empty(Icons.Rounded.Dns, "Add your first server",
                    "Host, user and the .pem key you use for SSH. Pocket opens an encrypted tunnel, so nothing new is exposed on the server.") {
                    Spacer(Modifier.height(8.dp))
                    Primary("Add server", { Nav.push(Route.Editor(null)) }, icon = Icons.Rounded.Add)
                }
            }
            items(servers, key = { it.id }) { s ->
                val busy = (c as? Conn.Connecting)?.server?.id == s.id
                val i = inst[s.id]
                Surface(onClick = { if (!busy) Ssh.connect(s) }) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Tile(if (s.hasAws) Icons.Rounded.Cloud else Icons.Rounded.Dns)
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text(s.title, style = MaterialTheme.typography.titleMedium, color = P.text)
                            Text("${s.user}@${s.host}" + if (s.port != 22) ":${s.port}" else "", style = MaterialTheme.typography.bodySmall, color = P.muted)
                        }
                        if (i != null) Pill(i.state, when (i.state) { "running" -> P.good; "stopped" -> P.muted; else -> P.warn })
                        IconButton(onClick = { Nav.push(Route.Editor(s.id)) }) { Icon(Icons.Rounded.Edit, "Edit", tint = P.muted) }
                    }
                    AnimatedVisibility(busy) {
                        Column {
                            Spacer(Modifier.height(12.dp))
                            Text((c as? Conn.Connecting)?.step ?: "", style = MaterialTheme.typography.bodySmall, color = P.muted)
                            Spacer(Modifier.height(6.dp))
                            androidx.compose.material3.LinearProgressIndicator(Modifier.fillMaxWidth(), color = P.accent, trackColor = P.surface2)
                        }
                    }
                    if (s.hasAws && i != null && (i.state == "stopped" || i.state == "stopping")) {
                        Spacer(Modifier.height(12.dp))
                        Secondary(if (i.state == "stopping") "Stopping…" else "Start instance", {
                            scope.launch {
                                runCatching { Aws.start(s) }.onSuccess {
                                    Toast.makeText(ctx, "Starting… connecting when it's up", Toast.LENGTH_SHORT).show()
                                    inst[s.id] = i.copy(state = "pending")
                                    // wait for running + sshd, then connect
                                    repeat(40) {
                                        delay(4000)
                                        val now = runCatching { Aws.describe(s) }.getOrNull() ?: return@repeat
                                        inst[s.id] = now
                                        if (now.state == "running") { delay(8000); Ssh.connect(s); return@launch }
                                    }
                                }.onFailure { Toast.makeText(ctx, it.message, Toast.LENGTH_LONG).show() }
                            }
                        }, icon = Icons.Rounded.PowerSettingsNew, color = P.good, enabled = i.state == "stopped")
                    }
                }
            }
        }
        if (servers.isNotEmpty()) Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp)) {
            Primary("Add server", { Nav.push(Route.Editor(null)) }, Modifier.fillMaxWidth(), icon = Icons.Rounded.Add)
        }
    }
}

@Composable
fun EditorScreen(id: String?) {
    val ctx = LocalContext.current
    val orig = remember(id) { id?.let { Store.server(it) } ?: Server() }
    var s by remember(id) { mutableStateOf(orig) }
    var showSecret by remember { mutableStateOf(false) }
    var aws by remember { mutableStateOf(orig.hasAws) }
    var confirmDelete by remember { mutableStateOf(false) }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) runCatching {
            ctx.contentResolver.openInputStream(uri)!!.use { it.readBytes().toString(Charsets.UTF_8) }
        }.onSuccess { text ->
            if ("PRIVATE KEY" in text) s = s.copy(key = text.trim()) else Toast.makeText(ctx, "That file isn't a private key", Toast.LENGTH_LONG).show()
        }
    }
    val valid = s.host.isNotBlank() && s.user.isNotBlank() && "PRIVATE KEY" in s.key

    Column(Modifier.fillMaxSize().imePadding()) {
        Header(if (id == null) "New server" else "Edit server", onBack = { Nav.pop() })
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Field("Name", s.name, { s = s.copy(name = it) }, placeholder = "Shorts box")
            Field("Host", s.host, { s = s.copy(host = it.trim()) }, placeholder = "16.171.x.x or ec2-….compute.amazonaws.com", keyboard = KeyboardType.Uri)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Field("User", s.user, { s = s.copy(user = it.trim()) }, Modifier.weight(2f))
                Field("Port", s.port.toString(), { v -> v.toIntOrNull()?.let { s = s.copy(port = it) } ?: run { if (v.isEmpty()) s = s.copy(port = 22) } }, Modifier.weight(1f), keyboard = KeyboardType.Number)
            }
            Label("Private key", action = {
                TextButton(onClick = { pick.launch(arrayOf("*/*")) }) {
                    Icon(Icons.Rounded.FileOpen, null, Modifier.padding(end = 6.dp)); Text("Import .pem")
                }
            })
            Field("Paste the key", if (showSecret || s.key.isEmpty()) s.key else s.key.lineSequence().firstOrNull().orEmpty() + "\n••••••••", { if (showSecret || s.key.isEmpty()) s = s.copy(key = it) },
                mono = true, singleLine = false, minLines = 3, placeholder = "-----BEGIN RSA PRIVATE KEY-----",
                trailing = { IconButton(onClick = { showSecret = !showSecret }) { Icon(if (showSecret) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility, "Show") } })
            Field("Key passphrase (if any)", s.passphrase, { s = s.copy(passphrase = it) }, secret = true)
            Text("Stored encrypted with this phone's hardware keystore. Never leaves the device except to sign in.", style = MaterialTheme.typography.bodySmall, color = P.muted)

            Label("Shorts Studio")
            Field("Studio port (0 = off)", s.studioPort.toString(), { v -> s = s.copy(studioPort = v.toIntOrNull() ?: 0) }, keyboard = KeyboardType.Number)

            Label("AWS instance control (optional)", action = {
                androidx.compose.material3.Switch(aws, { aws = it }, colors = switchColors())
            })
            AnimatedVisibility(aws) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Start and stop the instance from Pocket, and follow its new public IP after every start. " +
                        "Use an IAM user that can only call ec2:DescribeInstances, ec2:StartInstances and ec2:StopInstances.",
                        style = MaterialTheme.typography.bodySmall, color = P.muted)
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Field("Region", s.awsRegion, { s = s.copy(awsRegion = it.trim()) }, Modifier.weight(1f), placeholder = "eu-north-1")
                        Field("Instance ID", s.awsInstance, { s = s.copy(awsInstance = it.trim()) }, Modifier.weight(1.4f), placeholder = "i-0abc…")
                    }
                    Field("Access key ID", s.awsAccess, { s = s.copy(awsAccess = it.trim()) }, mono = true)
                    Field("Secret access key", s.awsSecret, { s = s.copy(awsSecret = it.trim()) }, mono = true, secret = true)
                }
            }
            if (id != null) {
                Spacer(Modifier.height(4.dp))
                Secondary("Delete server", { confirmDelete = true }, Modifier.fillMaxWidth(), color = P.bad)
            }
            Spacer(Modifier.height(12.dp))
        }
        Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            val final = if (aws) s else s.copy(awsRegion = "", awsInstance = "", awsAccess = "", awsSecret = "")
            Secondary("Save", { Store.saveServer(final); Nav.pop() }, Modifier.weight(1f), enabled = valid)
            Primary("Save & connect", { Store.saveServer(final); Nav.pop(); Ssh.connect(final) }, Modifier.weight(1.6f), enabled = valid, icon = Icons.Rounded.Bolt)
        }
    }
    if (confirmDelete) AlertDialog(
        onDismissRequest = { confirmDelete = false },
        title = { Text("Delete ${s.title}?") },
        text = { Text("The key and settings for this server are removed from the phone.") },
        confirmButton = { TextButton(onClick = { Store.deleteServer(s.id); confirmDelete = false; Nav.pop() }) { Text("Delete", color = P.bad) } },
        dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        containerColor = P.surface,
    )
}

@Composable
fun switchColors() = androidx.compose.material3.SwitchDefaults.colors(
    checkedTrackColor = P.accent, checkedThumbColor = P.onAccent, uncheckedTrackColor = P.surface2, uncheckedBorderColor = P.border, uncheckedThumbColor = P.muted,
)
