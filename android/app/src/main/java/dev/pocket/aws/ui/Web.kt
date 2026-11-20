package dev.pocket.aws.ui

import android.annotation.SuppressLint
import android.app.DownloadManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.MutableContextWrapper
import android.graphics.Bitmap
import android.net.Uri
import android.os.Environment
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.DesktopWindows
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.OpenInBrowser
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import dev.pocket.aws.MainActivity
import dev.pocket.aws.core.Ssh
import dev.pocket.aws.core.Store

/** Live page state for the slim top bar. */
class Page {
    var title by mutableStateOf("")
    var url by mutableStateOf("")
    var progress by mutableFloatStateOf(0f)
    var error by mutableStateOf<String?>(null)
}

/** One WebView per forwarded port, kept alive between visits (instant reopen, scroll and login kept). */
object WebCache {
    private class Entry(val view: WebView, val ctx: MutableContextWrapper, val page: Page)
    private val views = mutableMapOf<Int, Entry>()

    fun page(port: Int) = views[port]?.page

    @SuppressLint("SetJavaScriptEnabled")
    fun get(activity: Context, port: Int): Pair<WebView, Page> {
        views[port]?.let { e -> e.ctx.baseContext = activity; return e.view to e.page }
        val ctx = MutableContextWrapper(activity)
        val page = Page()
        val wv = WebView(ctx)
        wv.settings.apply {
            javaScriptEnabled = true; domStorageEnabled = true; databaseEnabled = true
            mediaPlaybackRequiresUserGesture = false; loadWithOverviewMode = true; useWideViewPort = true
            builtInZoomControls = true; displayZoomControls = false; cacheMode = WebSettings.LOAD_DEFAULT
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
        }
        applyDesktop(wv, Store.settings.value.desktopMode)
        CookieManager.getInstance().setAcceptThirdPartyCookies(wv, true)
        wv.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) { page.url = url; page.error = null }
            override fun onPageFinished(view: WebView, url: String) { page.url = url; page.title = view.title.orEmpty() }
            override fun onReceivedError(view: WebView, req: WebResourceRequest, err: WebResourceError) {
                if (req.isForMainFrame) page.error = "${err.description}. The app may have stopped, or the tunnel is reconnecting."
            }
            override fun shouldOverrideUrlLoading(view: WebView, req: WebResourceRequest): Boolean {
                val u = req.url
                if (u.host == "127.0.0.1" || u.host == "localhost") return false
                // external links (YouTube, docs...) go to the real browser
                runCatching { view.context.startActivity(Intent(Intent.ACTION_VIEW, u).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                return true
            }
        }
        wv.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, p: Int) { page.progress = p / 100f }
            override fun onReceivedTitle(view: WebView, title: String?) { page.title = title.orEmpty() }
            override fun onShowFileChooser(view: WebView, cb: ValueCallback<Array<Uri>>, params: FileChooserParams): Boolean {
                val act = ctx.baseContext.findActivity() ?: return false
                return act.pickFiles(cb, params)
            }
        }
        wv.setDownloadListener { url, ua, cd, mime, _ ->
            runCatching {
                val name = URLUtil.guessFileName(url, cd, mime)
                val req = DownloadManager.Request(Uri.parse(url)).setTitle(name).setMimeType(mime)
                    .addRequestHeader("User-Agent", ua)
                    .addRequestHeader("Cookie", CookieManager.getInstance().getCookie(url) ?: "")
                    .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                    .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name)
                ctx.getSystemService(DownloadManager::class.java).enqueue(req)
                Toast.makeText(ctx, "Downloading $name", Toast.LENGTH_SHORT).show()
            }.onFailure { Toast.makeText(ctx, "Download failed: ${it.message}", Toast.LENGTH_LONG).show() }
        }
        views[port] = Entry(wv, ctx, page)
        return wv to page
    }

    fun applyDesktop(wv: WebView, on: Boolean) {
        val ua = WebSettings.getDefaultUserAgent(wv.context)
        wv.settings.userAgentString = if (on) ua.replace(Regex("Android [^;)]*;?"), "X11; Linux x86_64;").replace(" Mobile", "") else ua
    }

    fun setDesktopAll(on: Boolean) = views.values.forEach { applyDesktop(it.view, on); it.view.reload() }

    fun goBack(port: Int): Boolean {
        val v = views[port]?.view ?: return false
        if (v.canGoBack()) { v.goBack(); return true }
        return false
    }

    fun close(port: Int) { views.remove(port)?.view?.let { (it.parent as? ViewGroup)?.removeView(it); it.destroy() } }

    fun clear() = views.keys.toList().forEach { close(it) }

    /** The activity is going away: don't leak it through the cached views. */
    fun detach() = views.values.forEach { e -> e.ctx.baseContext = e.view.context.applicationContext }

    val openPorts: Set<Int> get() = views.keys
}

private fun Context.findActivity(): MainActivity? {
    var c: Context? = this
    while (c is ContextWrapper) { if (c is MainActivity) return c; c = c.baseContext }
    return null
}

@Composable
fun WebScreen(r: Route.Web) {
    val ctx = LocalContext.current
    var local by remember { mutableStateOf<Int?>(null) }
    var err by remember { mutableStateOf<String?>(null) }
    val fwd by Ssh.forwards.collectAsState()
    LaunchedEffect(r.port, fwd[r.port]) {
        runCatching { Ssh.forward(r.port) }.onSuccess { local = it; err = null }.onFailure { err = it.message }
    }
    val (view, page) = remember(r.port) { WebCache.get(ctx, r.port) }
    var menu by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().background(P.bg)) {
        Row(Modifier.fillMaxWidth().statusBarsPadding().height(52.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { Nav.pop() }) { Icon(Icons.Rounded.Close, "Close", tint = P.text) }
            Column(Modifier.weight(1f)) {
                Text(page.title.ifBlank { r.title }, style = MaterialTheme.typography.titleMedium, color = P.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(":${r.port}" + page.url.substringAfter("127.0.0.1:${local ?: ""}", "").let { if (it.isBlank() || it == "/") "" else "  $it" },
                    style = MaterialTheme.typography.bodySmall, color = P.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            IconButton(onClick = { view.reload() }) { Icon(Icons.Rounded.Refresh, "Reload", tint = P.text) }
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.MoreVert, "More", tint = P.text) }
                DropdownMenu(menu, { menu = false }) {
                    DropdownMenuItem({ Text("Open in browser") }, leadingIcon = { Icon(Icons.Rounded.OpenInBrowser, null) }, onClick = {
                        menu = false
                        runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(view.url ?: "http://127.0.0.1:$local/"))) }
                    })
                    DropdownMenuItem({ Text("Copy local address") }, leadingIcon = { Icon(Icons.Rounded.ContentCopy, null) }, onClick = {
                        menu = false
                        ctx.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("url", view.url ?: "http://127.0.0.1:$local/"))
                    })
                    val desktop = Store.settings.collectAsState().value.desktopMode
                    DropdownMenuItem({ Text(if (desktop) "Mobile layout" else "Desktop layout") }, leadingIcon = { Icon(Icons.Rounded.DesktopWindows, null) }, onClick = {
                        menu = false
                        Store.updateSettings { it.copy(desktopMode = !desktop) }
                        WebCache.setDesktopAll(!desktop)
                    })
                }
            }
        }
        Box(Modifier.fillMaxWidth().height(2.dp)) {
            if (page.progress in 0.01f..0.99f) LinearProgressIndicator(progress = { page.progress }, Modifier.fillMaxWidth(), color = P.accent, trackColor = P.bg)
        }
        Box(Modifier.weight(1f).fillMaxWidth().navigationBarsPadding().imePadding()) {
            val lp = local
            when {
                err != null && lp == null -> Box(Modifier.padding(16.dp)) { ErrorNote(err!!) }
                lp == null -> Loading()
                else -> {
                    AndroidView(
                        factory = { (view.parent as? ViewGroup)?.removeView(view); view },
                        modifier = Modifier.fillMaxSize(),
                        update = { wv ->
                            val base = "http://127.0.0.1:$lp"
                            // reload only for a new tunnel port or a different deep link; otherwise keep the page as it was
                            if (wv.url?.startsWith(base) != true || wv.tag != r.path) wv.also { it.tag = r.path }.loadUrl(base + r.path.ifBlank { "/" }.let { if (it.startsWith("/")) it else "/$it" })
                        },
                        onRelease = { (it.parent as? ViewGroup)?.removeView(it) },
                    )
                    page.error?.let { e -> Box(Modifier.align(Alignment.BottomCenter).padding(16.dp)) { ErrorNote(e) } }
                }
            }
        }
    }
}
