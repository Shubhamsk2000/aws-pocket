package dev.pocket.aws

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import dev.pocket.aws.core.Conn
import dev.pocket.aws.core.Ssh
import dev.pocket.aws.ui.EditorScreen
import dev.pocket.aws.ui.ForwardScreen
import dev.pocket.aws.ui.Live
import dev.pocket.aws.ui.LogsScreen
import dev.pocket.aws.ui.MainScreen
import dev.pocket.aws.ui.Nav
import dev.pocket.aws.ui.P
import dev.pocket.aws.ui.PocketTheme
import dev.pocket.aws.ui.ProcsScreen
import dev.pocket.aws.ui.Route
import dev.pocket.aws.ui.RunScreen
import dev.pocket.aws.ui.ServersScreen
import dev.pocket.aws.ui.ServicesScreen
import dev.pocket.aws.ui.SettingsScreen
import dev.pocket.aws.ui.WebCache
import dev.pocket.aws.ui.WebScreen

class MainActivity : ComponentActivity() {
    private var fileCallback: ValueCallback<Array<Uri>>? = null
    private val picker = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        fileCallback?.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(r.resultCode, r.data))
        fileCallback = null
    }

    /** Called by the web view when a page wants a file (e.g. Studio's video upload). */
    fun pickFiles(cb: ValueCallback<Array<Uri>>, params: WebChromeClient.FileChooserParams): Boolean {
        fileCallback?.onReceiveValue(null)
        fileCallback = cb
        return runCatching { picker.launch(params.createIntent()); true }.getOrElse { fileCallback = null; false }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 33) registerForActivityResult(ActivityResultContracts.RequestPermission()) {}.launch(Manifest.permission.POST_NOTIFICATIONS)
        handle(intent)
        setContent { PocketTheme { Root() } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    private fun handle(i: Intent?) {
        val r = i?.getStringExtra("route") ?: return
        if (r.startsWith("shorts")) { Nav.stack.clear(); Nav.tab = 2 }
    }

    override fun onDestroy() {
        if (isFinishing) WebCache.detach()
        super.onDestroy()
    }
}

@Composable
private fun Root() {
    val conn by Ssh.state.collectAsState()
    val up = conn is Conn.Online || conn is Conn.Reconnecting
    LaunchedEffect(up, Ssh.server?.id) {
        Live.bind(if (up) Ssh.server?.id else null)
        if (!up) { Nav.stack.removeAll { it !is Route.Editor && it !is Route.Settings }; WebCache.clear() }
    }
    val top = Nav.stack.lastOrNull()
    BackHandler(enabled = top != null) { if (!(top is Route.Web && WebCache.goBack(top.port))) Nav.pop() }

    Box(Modifier.fillMaxSize().background(P.bg)) {
        AnimatedContent(
            targetState = top to up,
            transitionSpec = {
                val forward = (targetState.first != null && initialState.first == null) || Nav.stack.size > 1 && targetState.first != initialState.first
                if (forward) (slideInHorizontally(tween(260)) { it / 6 } + fadeIn(tween(200))) togetherWith fadeOut(tween(140))
                else (fadeIn(tween(200))) togetherWith (slideOutHorizontally(tween(220)) { it / 6 } + fadeOut(tween(160)))
            },
            label = "nav",
        ) { (route, online) ->
            when (route) {
                null -> if (online) MainScreen() else ServersScreen()
                is Route.Editor -> EditorScreen(route.id)
                is Route.Web -> WebScreen(route)
                is Route.Logs -> LogsScreen(route)
                Route.Procs -> ProcsScreen()
                Route.Services -> ServicesScreen()
                Route.Run -> RunScreen()
                Route.Settings -> SettingsScreen()
                Route.Forward -> ForwardScreen()
            }
        }
    }
}
