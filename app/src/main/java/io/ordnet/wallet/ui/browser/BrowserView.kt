package io.ordnet.wallet.ui.browser

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.util.Base64
import android.webkit.JavascriptInterface
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.DataObject
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.GppBad
import androidx.compose.material.icons.filled.GppMaybe
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Badge
import androidx.compose.material.icons.filled.Pets
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Sell
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import io.ordnet.wallet.core.Brc100
import io.ordnet.wallet.core.ProviderRequest
import io.ordnet.wallet.core.WalletEngine
import io.ordnet.wallet.core.WalletStore
import io.ordnet.wallet.ui.AlertKind
import io.ordnet.wallet.ui.InlineAlert
import io.ordnet.wallet.ui.OrdplugLogo
import io.ordnet.wallet.ui.Theme
import io.ordnet.wallet.ui.components.OrdnetTextField
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.net.URLEncoder

/**
 * The ORDnet Web3 Browser — native port of viewer.html/viewer.js:
 * address bar, app catalog start screen, on-chain content rendering,
 * history, security scanner and the window.ordplug provider bridge.
 */
class BrowserModel(context: Context, val store: WalletStore) {
    /** what the webview is currently showing: on-chain content or a regular website */
    enum class BrowseMode { WEB3, WEB2 }

    var addressText by mutableStateOf("")
    var displayName by mutableStateOf("")
    var loading by mutableStateOf(false)
    var error by mutableStateOf("")
    var securityLevel by mutableStateOf<Int?>(null)
    var showingContent by mutableStateOf(false)
    var canGoBack by mutableStateOf(false)
    var canGoForward by mutableStateOf(false)
    var mode by mutableStateOf(BrowseMode.WEB3)
    /** true while an approval is being executed — blocks reject-on-dismiss */
    var approvalBusy by mutableStateOf(false)

    private val history = ArrayList<String>()
    private var historyIndex = -1
    var pendingFragment = ""

    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val router = Web3SchemeRouter()
    /** v3.2 — the key-free window.CWI shim source (loaded from assets) */
    private var brc100ShimScript: String = ""

    @SuppressLint("SetJavaScriptEnabled")
    val webView: WebView = WebView(context)

    init {
        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.settings.setSupportZoom(true)
        webView.settings.builtInZoomControls = true
        webView.settings.displayZoomControls = false

        // provider bridge (window.ordplug → native approval sheets)
        webView.addJavascriptInterface(ProviderBridge(this), "OrdplugAndroid")
        webView.addJavascriptInterface(NavigateBridge(this), "OrdnetNavigateAndroid")
        // v3.2 — BRC-100 bridge (window.CWI shim → native provider)
        webView.addJavascriptInterface(Brc100Bridge(this), "OrdplugBrc100Android")

        // v3.2 — the key-free window.CWI shim, injected at document start so
        // WalletClient('auto') detects this wallet (first-priority substrate).
        // The ORDnet provider above stays untouched NEXT to it.
        brc100ShimScript = try {
            context.assets.open("brc100-shim.js").bufferedReader().readText()
        } catch (e: Exception) { "" }

        // inject the window.ordplug provider at document start when supported
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            try {
                WebViewCompat.addDocumentStartJavaScript(webView, OrdplugProvider.script, setOf("*"))
                if (brc100ShimScript.isNotEmpty()) {
                    WebViewCompat.addDocumentStartJavaScript(webView, brc100ShimScript, setOf("*"))
                }
            } catch (e: Exception) { }
        }

        webView.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                val url = request.url
                if (url.scheme == Web3.scheme) {
                    return router.handle(url)
                }
                return null
            }

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val url = request.url
                val scheme = url.scheme ?: return false
                // ordweb3 + http(s) stay inside the browser; everything else is refused
                return scheme != Web3.scheme && scheme != "http" && scheme != "https"
            }

            override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                if (mode == BrowseMode.WEB2) loading = true
                // fallback provider injection for WebViews without DOCUMENT_START_SCRIPT
                if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
                    view.evaluateJavascript(OrdplugProvider.script, null)
                    if (brc100ShimScript.isNotEmpty()) view.evaluateJavascript(brc100ShimScript, null)
                }
            }

            override fun onPageFinished(view: WebView, url: String?) {
                // click-interceptor + providers are (re)injected into every
                // rendered page (both shims are idempotent)
                view.evaluateJavascript(OrdplugProvider.script, null)
                if (brc100ShimScript.isNotEmpty()) view.evaluateJavascript(brc100ShimScript, null)
                view.evaluateJavascript(Web3.interceptorScript, null)

                if (mode == BrowseMode.WEB2 && url != null && !url.startsWith("${Web3.scheme}:")) {
                    loading = false
                    error = ""
                    val u = android.net.Uri.parse(url)
                    displayName = u.host ?: displayName
                    addressText = url
                    securityLevel = if (u.scheme == "https") 0 else 1
                }
                updateNavState()
                flushPendingFragment(view)
            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, err: WebResourceError) {
                if (mode != BrowseMode.WEB2 || !request.isForMainFrame) return
                loading = false
                // ERR_ABORTED maps to ERROR_UNKNOWN — a cancelled load is not an error
                // (parity with iOS: NSURLErrorCancelled is suppressed)
                if (err.errorCode != ERROR_UNKNOWN) {
                    error = err.description?.toString() ?: "Could not load the page."
                }
                updateNavState()
            }
        }
    }

    /** JS → native: full-page web3/txid navigation from the click interceptor */
    private class NavigateBridge(val model: BrowserModel) {
        @JavascriptInterface
        fun postMessage(target: String, fragment: String) {
            model.scope.launch {
                model.pendingFragment = fragment
                model.addressText = target
                model.load(target)
            }
        }
    }

    /** JS → native: window.ordplug requests */
    private class ProviderBridge(val model: BrowserModel) {
        @JavascriptInterface
        fun postMessage(json: String) {
            val body = try { JSONObject(json) } catch (e: Exception) { return }
            model.scope.launch { model.handleProviderMessage(body) }
        }
    }

    /** JS → native: window.CWI (BRC-100) requests — v3.2 */
    private class Brc100Bridge(val model: BrowserModel) {
        @JavascriptInterface
        fun postMessage(json: String) {
            val body = try { JSONObject(json) } catch (e: Exception) { return }
            model.scope.launch { model.handleBrc100Message(body) }
        }
    }

    // MARK: BRC-100 bridge (v3.2)

    suspend fun handleBrc100Message(body: JSONObject) {
        val id = body.optString("id")
        val method = body.optString("method")
        if (id.isEmpty() || method.isEmpty()) return
        val argsJson = body.optString("args").ifEmpty { "{}" }
        // H4 (external audit, 11 Aug 2026) — the originator MUST be the real
        // page origin, not a value the page supplies. Grants and daily budgets
        // key on `address|origin|level|protocol`, so a page passing
        // `originator: "https://trusted.dapp"` inherited that dApp's grants and
        // budget. The window.ordplug path already uses currentOrigin; use it
        // here too and ignore the page-supplied field.
        val originator = currentOrigin
        try {
            val result = Brc100.handle(method = method, argsJson = argsJson,
                originator = originator, store = store)
            deliverBrc100(id, ok = true, result = result, error = null)
        } catch (e: Brc100.Err) {
            deliverBrc100(id, ok = false, result = null, error = e)
        } catch (e: Exception) {
            deliverBrc100(id, ok = false, result = null,
                error = Brc100.Err("WERR_UNKNOWN", 1, e.message ?: "Unknown wallet error."))
        }
    }

    /** ok:true resolves; ok:false REJECTS in the page with a WERR_* error —
     *  never a resolved error-object (BRC-100 error contract) */
    fun deliverBrc100(id: String, ok: Boolean, result: JSONObject?, error: Brc100.Err?) {
        val payload = JSONObject().put("id", id).put("ok", ok)
        if (result != null) payload.put("result", result)
        if (error != null) payload.put("error", JSONObject()
            .put("name", error.werrName).put("code", error.code).put("message", error.message))
        // same U+2028/U+2029 sanitization as deliver(): org.json leaves them
        // raw and they are illegal inside a JS source line
        val js = payload.toString()
            .replace("\u2028", "\\u2028")
            .replace("\u2029", "\\u2029")
        webView.post {
            webView.evaluateJavascript("window.__brc100Deliver($js);", null)
        }
    }

    // MARK: navigation

    /**
     * classify what the user typed: on-chain (.web3/TXID), a regular web2 URL,
     * or free text (routed to a search engine — normal browser behaviour)
     */
    private fun classify(q: String): Pair<Boolean, String?> {
        if (Web3.isValidTxid(q) || Web3.hasWeb3TLD(q)) return Pair(true, null)
        val lower = q.lowercase()
        if (lower.startsWith("http://") || lower.startsWith("https://")) return Pair(false, q)
        // bare domain like ordnet.io or ordnet.io/path (no spaces, has a dot)
        if (!q.contains(" ") && q.contains(".") &&
            Regex("^[a-z0-9][a-z0-9.-]*\\.[a-z]{2,}(/.*)?$", RegexOption.IGNORE_CASE).matches(q)) {
            return Pair(false, "https://$q")
        }
        // free text -> search
        val enc = URLEncoder.encode(q, "UTF-8")
        return Pair(false, "https://duckduckgo.com/?q=$enc")
    }

    suspend fun load(input: String, addToHistory: Boolean = true) {
        val q = input.trim()
        if (q.isEmpty()) return
        loading = true
        error = ""

        val (isWeb3, web2URL) = classify(q)
        if (addToHistory) {
            while (history.size > historyIndex + 1) history.removeAt(history.size - 1)
            history.add(q)
            historyIndex = history.size - 1
        }

        if (isWeb3) {
            try {
                val (txid, content) = Web3.load(q)
                mode = BrowseMode.WEB3
                displayName = q
                addressText = q
                render(content, txid)
            } catch (e: Exception) {
                error = e.message ?: "Could not load."
                securityLevel = null
                if (addToHistory) {   // failed load shouldn't pollute history
                    history.removeAt(history.size - 1)
                    historyIndex = history.size - 1
                }
            }
        } else if (web2URL != null) {
            mode = BrowseMode.WEB2
            showingContent = true
            displayName = android.net.Uri.parse(web2URL).host ?: q
            addressText = q
            securityLevel = if (web2URL.startsWith("https")) 0 else 1
            webView.loadUrl(web2URL)
        }
        loading = false
        updateNavState()
    }

    private fun updateNavState() {
        canGoBack = (mode == BrowseMode.WEB2 && webView.canGoBack()) || historyIndex > 0
        canGoForward = (mode == BrowseMode.WEB2 && webView.canGoForward()) || historyIndex < history.size - 1
    }

    fun goBack() {
        // inside a web2 site, use the webview's own history first
        if (mode == BrowseMode.WEB2 && webView.canGoBack()) {
            webView.goBack()
            return
        }
        if (historyIndex <= 0) return
        historyIndex -= 1
        scope.launch { load(history[historyIndex], addToHistory = false) }
    }

    fun goForward() {
        if (mode == BrowseMode.WEB2 && webView.canGoForward()) {
            webView.goForward()
            return
        }
        if (historyIndex >= history.size - 1) return
        historyIndex += 1
        scope.launch { load(history[historyIndex], addToHistory = false) }
    }

    fun goHome() {
        showingContent = false
        addressText = ""
        displayName = ""
        securityLevel = null
        mode = BrowseMode.WEB3
        webView.loadDataWithBaseURL(null, "", "text/html", "utf-8", null)
    }

    private suspend fun render(content: Web3.Content, txid: String) {
        showingContent = true
        val base = "${Web3.scheme}://ord/${txid}_0/"
        if (content.isHTML) {
            val html = String(content.data, Charsets.UTF_8)
            securityLevel = try {
                WalletEngine.shared.int("scanSecurity", WalletEngine.shared.args("html" to html))
            } catch (e: Exception) { 0 }
            webView.loadDataWithBaseURL(base, Web3.preprocess(html), "text/html", "utf-8", null)
        } else {
            securityLevel = 0
            val b64 = Base64.encodeToString(content.data, Base64.NO_WRAP)
            val ct = content.contentType
            val body = when {
                ct.startsWith("image/") ->
                    "<img src=\"data:$ct;base64,$b64\" style=\"max-width:100%;max-height:100vh;object-fit:contain\"/>"
                ct.startsWith("video/") ->
                    "<video controls autoplay playsinline style=\"max-width:100%;max-height:100vh\"><source src=\"data:$ct;base64,$b64\" type=\"$ct\"></video>"
                ct.startsWith("audio/") ->
                    "<audio controls autoplay style=\"width:90%\"><source src=\"data:$ct;base64,$b64\" type=\"$ct\"></audio>"
                else -> {
                    // strict UTF-8 decode: invalid bytes → binary fallback (parity with iOS)
                    val text = try {
                        Charsets.UTF_8.newDecoder()
                            .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                            .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                            .decode(java.nio.ByteBuffer.wrap(content.data)).toString()
                    } catch (e: Exception) { "(binary content, ${content.data.size} bytes)" }
                    val esc = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                    "<pre style=\"white-space:pre-wrap;word-wrap:break-word;padding:16px;font-family:monospace;color:#eee\">$esc</pre>"
                }
            }
            val page = "<html><head><meta name=viewport content=\"width=device-width,initial-scale=1\"></head>" +
                "<body style=\"margin:0;display:flex;justify-content:center;align-items:center;min-height:100vh;background:#111\">$body</body></html>"
            webView.loadDataWithBaseURL(base, page, "text/html", "utf-8", null)
        }
    }

    /** scroll to a pending fragment after load */
    private fun flushPendingFragment(view: WebView) {
        if (pendingFragment.isEmpty()) return
        val frag = pendingFragment
        pendingFragment = ""
        view.postDelayed({
            // U+2028 and U+2029 terminate a line inside a JavaScript string
            // literal, so escaping only backslash and quote leaves a way out —
            // the same gap iOS closed in v2.7.0 with the note "Android escaped
            // this; iOS did not". deliver() and deliverBrc100() in this file
            // already handle them; this path did not.
            val safeFrag = frag
                .replace("\\", "\\\\")
                .replace("'", "\\'")
                .replace("\u2028", "\\u2028")
                .replace("\u2029", "\\u2029")
            val js = """
                (function(){ try{ var id=decodeURIComponent('${safeFrag}'.substring(1));
                var el=document.getElementById(id)||document.querySelector('[name="'+id+'"]');
                if(el) el.scrollIntoView({behavior:'smooth',block:'start'}); }catch(e){} })();
            """.trimIndent()
            view.evaluateJavascript(js, null)
        }, 400)
    }

    // MARK: window.ordplug provider

    val currentOrigin: String
        get() {
            if (mode == BrowseMode.WEB2) {
                val url = webView.url
                if (url != null) {
                    val u = android.net.Uri.parse(url)
                    val host = u.host
                    if (host != null) return "${u.scheme ?: "https"}://$host"
                }
            }
            return if (displayName.isEmpty()) "web3://unknown" else "web3://${displayName.lowercase()}"
        }

    suspend fun handleProviderMessage(body: JSONObject) {
        val id = body.optString("id")
        val method = body.optString("method")
        if (id.isEmpty() || method.isEmpty()) return
        val params = body.optJSONObject("params") ?: JSONObject()
        val origin = currentOrigin
        val request = ProviderRequest(id = id, method = method, params = params, origin = origin)

        // read methods skip the approval sheet when the origin is already connected
        val readMethods = listOf("connect", "getAddress", "getPublicKey", "getBalance")
        if (readMethods.contains(method) && store.isConnected(origin)) {
            try {
                val result = OrdplugProvider.performRead(method, store)
                deliver(id, true, result, null)
            } catch (e: Exception) {
                deliver(id, false, null, e.message)
            }
            return
        }
        // one approval sheet at a time: a second request must NOT silently
        // replace the first (that orphaned the first request's callback until
        // the dApp's 5-minute timeout) — reject the newcomer immediately
        if (store.pendingProviderRequest != null) {
            deliver(id, false, null, "Another ORDnet Wallet request is already awaiting approval — approve or dismiss it first.")
            return
        }
        OrdplugProvider.pendingDelivery[id] = { ok, result, err ->
            deliver(id, ok, result, err)
        }
        store.pendingProviderRequest = request
    }

    fun deliver(id: String, ok: Boolean, result: JSONObject?, error: String?) {
        val payload = JSONObject()
        payload.put("id", id)
        payload.put("ok", ok)
        if (result != null) payload.put("result", result)
        if (error != null) payload.put("error", error)
        // org.json does not escape U+2028/U+2029, which are illegal inside a JS
        // source line — unescaped they turn the whole statement into a silent
        // syntax error and the dApp promise hangs until timeout
        val js = payload.toString()
            .replace("\u2028", "\\u2028")
            .replace("\u2029", "\\u2029")
        webView.post {
            webView.evaluateJavascript("window.__ordplugDeliver($js);", null)
        }
    }

    /** dismissed without action = rejected (ignored while a signed action is in flight,
     *  the counterpart of iOS interactiveDismissDisabled(busy)) */
    fun rejectPending(request: ProviderRequest) {
        if (approvalBusy) return
        OrdplugProvider.pendingDelivery.remove(request.id)?.invoke(false, null, "User rejected the request")
        store.pendingProviderRequest = null
    }
}

// MARK: - BrowserView

private data class CatalogApp(val name: String, val icon: ImageVector, val q: String)

/**
 * the full ORDnet ecosystem (order of ordnet.io, browser omitted — you're in it),
 * all linking to the .io addresses
 */
private val catalogApps = listOf(
    CatalogApp("ORD/domains", Icons.Filled.Sell, "https://domains.ordnet.io"),
    CatalogApp("ORD/app", Icons.Filled.CloudUpload, "https://app.ordnet.io"),
    CatalogApp("ORD/mail", Icons.Filled.Email, "https://mail.ordnet.io"),
    CatalogApp("ORD/search", Icons.Filled.Search, "https://search.ordnet.io"),
    CatalogApp("ORD/whois", Icons.Filled.Badge, "https://whois.ordnet.io"),
    CatalogApp("ORD/templates", Icons.Filled.GridView, "https://templates.ordnet.io"),
    CatalogApp("ORD/nodes", Icons.Filled.Hub, "https://nodes.ordnet.io"),
    CatalogApp("ORD/api", Icons.Filled.DataObject, "https://api.ordnet.io"),
    CatalogApp("ORD/swap", Icons.Filled.SwapHoriz, "https://swap.ordnet.io"),
    CatalogApp("ORD/clawd", Icons.Filled.Pets, "https://clawdbot.ordnet.io"),
    CatalogApp("ORD/mcp", Icons.Filled.Dns, "https://mcp.ordnet.io")
)

@Composable
fun BrowserView(store: WalletStore, model: BrowserModel) {
    // v3.2 — ORD/ner asks us to render a TXID; the tab may mount AFTER the
    // request was set, so consume it both on appear and on change
    androidx.compose.runtime.LaunchedEffect(store.browserOpenRequest) {
        val q = store.browserOpenRequest ?: return@LaunchedEffect
        store.browserOpenRequest = null
        model.addressText = q
        model.load(q)
    }
    Column(Modifier.fillMaxSize()) {
        // address bar
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp)
        ) {
            IconButton(onClick = { model.goBack() }, enabled = model.canGoBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back",
                    tint = if (model.canGoBack) Theme.ink() else Theme.secondaryText().copy(alpha = 0.4f))
            }
            IconButton(onClick = { model.goForward() }, enabled = model.canGoForward) {
                Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = "Forward",
                    tint = if (model.canGoForward) Theme.ink() else Theme.secondaryText().copy(alpha = 0.4f))
            }
            // v2.1 — home- en zoekknop verwijderd zodat het invoerveld breder is;
            // navigeren = Enter/Go op het toetsenbord (imeAction hieronder)
            Box(Modifier.weight(1f)) {
                var text by androidx.compose.runtime.remember { mutableStateOf(model.addressText) }
                // keep local field in sync with model-driven navigation
                androidx.compose.runtime.LaunchedEffect(model.addressText) { text = model.addressText }
                OrdnetTextField(
                    value = text,
                    onValueChange = { text = it; model.addressText = it },
                    placeholder = "domain.web3, website or search",
                    keyboardType = androidx.compose.ui.text.input.KeyboardType.Uri,
                    imeAction = androidx.compose.ui.text.input.ImeAction.Go,
                    onImeAction = { model.scope.launch { model.load(model.addressText) } }
                )
            }
            val lvl = model.securityLevel
            if (lvl != null) {
                Icon(
                    when (lvl) {
                        0 -> Icons.Filled.Lock
                        1 -> Icons.Filled.LockOpen
                        2 -> Icons.Filled.Warning
                        3 -> Icons.Filled.GppMaybe
                        else -> Icons.Filled.GppBad
                    },
                    contentDescription = "Security level $lvl",
                    tint = when (lvl) {
                        0 -> Theme.statusGreen
                        1 -> Theme.secondaryText()
                        2 -> Theme.statusYellow
                        else -> Theme.statusRed
                    },
                    modifier = Modifier.size(18.dp)
                )
            }
        }

        if (model.loading) {
            Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), horizontalArrangement = Arrangement.Center) {
                CircularProgressIndicator(color = Theme.ink(), strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
            }
        }
        if (model.error.isNotEmpty()) {
            InlineAlert(AlertKind.ERROR, model.error,
                modifier = Modifier.padding(horizontal = 10.dp).padding(top = 6.dp))
        }

        if (model.showingContent) {
            AndroidView(
                factory = {
                    // the long-lived WebView may still be attached to a previous holder
                    (model.webView.parent as? android.view.ViewGroup)?.removeView(model.webView)
                    model.webView
                },
                modifier = Modifier.fillMaxSize()
            )
        } else {
            StartScreen(model)
        }
    }
}

@Composable
private fun StartScreen(model: BrowserModel) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(3),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxSize().padding(16.dp)
    ) {
        item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(3) }) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxWidth().padding(top = 24.dp, bottom = 8.dp)
            ) {
                OrdplugLogo(size = 56.dp)
                Text("Browse web3 and the regular web", fontSize = 17.sp,
                    fontWeight = FontWeight.SemiBold, color = Theme.ink())
                Text(
                    "Enter a .web3 domain or TXID for on-chain content, a normal website (e.g. ordnet.io), or just search.",
                    fontSize = 13.sp, color = Theme.secondaryText(), textAlign = TextAlign.Center
                )
                Text(
                    "ORDNET",
                    fontSize = 12.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.5.sp,
                    color = Theme.secondaryText(),
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                )
            }
        }
        items(catalogApps) { app ->
            // identical card for every app — fixed height so all tiles line up
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
                modifier = Modifier
                    .height(82.dp)
                    .clip(RoundedCornerShape(26.dp))
                    .background(Theme.bgPrimary())
                    .border(1.5.dp, Theme.ink(), RoundedCornerShape(26.dp))
                    .clickable {
                        model.addressText = app.q
                        model.scope.launch { model.load(app.q) }
                    }
                    .padding(horizontal = 6.dp)
            ) {
                Icon(app.icon, contentDescription = null, tint = Theme.ink(), modifier = Modifier.size(24.dp))
                Text(app.name, fontSize = 12.sp, fontWeight = FontWeight.Medium,
                    maxLines = 1, color = Theme.ink())
            }
        }
    }
}
