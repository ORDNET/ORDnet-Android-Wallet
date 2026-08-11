package io.ordnet.wallet.core

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.io.ByteArrayInputStream
import kotlinx.coroutines.CancellableContinuation
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * The ORD/plug crypto engine: runs the battle-tested bsv.min.js + wallet-core.js
 * (byte-identical copies of the iOS/Chrome-extension engine) inside an invisible,
 * network-isolated WebView JS VM. The WebView is never attached to any window and
 * never loads a page; it only evaluates the engine scripts, so every byte of
 * transaction-building logic is identical to the extension and the iOS app.
 *
 * (Android has no JavaScriptCore; the WebView V8 runtime provides the same pure-JS
 * environment plus a REAL `crypto.getRandomValues` backed by OS entropy, so no
 * randomness polyfill is needed. All network loads are blocked.)
 */
class WalletEngine private constructor(context: Context) {

    class EngineException(message: String) : Exception(message)

    private val appContext = context.applicationContext
    private lateinit var webView: WebView
    @Volatile private var ready = CompletableDeferred<Unit>()
    private val mutex = Mutex()   // serialize calls, like the iOS engine queue
    private val mainHandler = Handler(Looper.getMainLooper())
    // the (single, mutex-serialized) evaluateJavascript call in flight — resumed
    // exceptionally if the renderer dies, so the mutex is never held forever
    private var inFlight: CancellableContinuation<String>? = null   // main thread only

    companion object {
        @Volatile private var instance: WalletEngine? = null

        /** must be called once from the main thread (Application.onCreate) */
        fun init(context: Context): WalletEngine {
            return instance ?: synchronized(this) {
                instance ?: WalletEngine(context).also {
                    it.boot()
                    instance = it
                }
            }
        }

        val shared: WalletEngine
            get() = instance ?: throw IllegalStateException("WalletEngine.init() not called")
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun boot() {
        webView = WebView(appContext)
        webView.settings.javaScriptEnabled = true
        webView.settings.blockNetworkLoads = true
        webView.settings.allowFileAccess = false
        webView.settings.allowContentAccess = false
        // belt & braces: refuse every resource/navigation request
        webView.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse {
                return WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
            }
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean = true
            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                // the OS reclaimed the renderer: rebuild the engine instead of
                // letting the default behavior kill the whole app
                mainHandler.post {
                    // a call in flight would otherwise never get its callback —
                    // its continuation (and the engine mutex) would hang forever
                    inFlight?.let {
                        inFlight = null
                        if (it.isActive) it.resumeWithException(
                            EngineException("The crypto engine was restarted by the system — please try again."))
                    }
                    try { view.destroy() } catch (e: Exception) { }
                    ready = CompletableDeferred()
                    boot()
                }
                return true
            }
        }

        // ---- load engine scripts from assets (verbatim copies) off the main thread ----
        // v3.2: bsv-sdk-bundle.js = @bsv/sdk v2.2.18 as one IIFE (globalThis.
        // BSVSDK) — the BRC-100 crypto core (KeyDeriver/ProtoWallet), loaded
        // AFTER bsv.min + wallet-core, the same order as the iOS engine boots
        // them. The WebView's V8 provides TextEncoder/TextDecoder and
        // crypto.getRandomValues natively, so the iOS JSC polyfills are not
        // needed here. Keys never leave this engine.
        val target = ready
        Thread {
            try {
                val bsv = appContext.assets.open("engine/bsv.min.js").bufferedReader().readText()
                val core = appContext.assets.open("engine/wallet-core.js").bufferedReader().readText()
                val sdk = appContext.assets.open("engine/bsv-sdk-bundle.js").bufferedReader().readText()
                mainHandler.post {
                    webView.evaluateJavascript(bsv) { }
                    webView.evaluateJavascript(core) { }
                    webView.evaluateJavascript(sdk) { }
                    // verify boot: all three globals must exist
                    webView.evaluateJavascript(
                        "(function(){return String(typeof bsv==='object' && typeof OrdplugCore==='object' && typeof BSVSDK==='object');})()"
                    ) { result ->
                        if (result?.contains("true") == true) {
                            target.complete(Unit)
                        } else {
                            target.completeExceptionally(EngineException("Engine boot failed: bsv/OrdplugCore/BSVSDK missing"))
                        }
                    }
                }
            } catch (e: Exception) {
                target.completeExceptionally(EngineException("Engine boot failed: ${e.message}"))
            }
        }.start()
    }

    private suspend fun evaluate(expr: String): String = withContext(Dispatchers.Main) {
        suspendCancellableCoroutine { cont ->
            inFlight = cont
            webView.evaluateJavascript(expr) { result ->
                if (inFlight === cont) inFlight = null
                if (cont.isActive) cont.resume(result ?: "null")
            }
            cont.invokeOnCancellation { mainHandler.post { if (inFlight === cont) inFlight = null } }
        }
    }

    /**
     * Call a wallet-core API function. Thread-safe (serialized on a mutex).
     * All wallet-core functions take one JSON object and return {ok, result|error}.
     * Returns String / Boolean / Int / Long / Double / JSONObject / JSONArray / null.
     */
    suspend fun call(function: String, args: JSONObject = JSONObject()): Any? {
        ready.await()
        return mutex.withLock {
            val argsLiteral = JSONObject.quote(args.toString())
            val fnLiteral = JSONObject.quote(function)
            val expr = """
                (function(){
                  try {
                    var core = window.OrdplugCore;
                    var fn = core && core[$fnLiteral];
                    if (typeof fn !== 'function') {
                      return JSON.stringify({ok:false, error:'Unknown engine function: ' + $fnLiteral});
                    }
                    return fn($argsLiteral);
                  } catch (e) {
                    return JSON.stringify({ok:false, error:String((e && e.message) || e)});
                  }
                })()
            """.trimIndent()
            val raw = evaluate(expr)
            // evaluateJavascript returns the JS value JSON-encoded; the engine
            // returns a JSON *string*, so the raw result is a quoted string.
            val inner = JSONTokener(raw).nextValue() as? String
                ?: throw EngineException("Engine returned an unreadable response.")
            val obj = JSONTokener(inner).nextValue() as? JSONObject
                ?: throw EngineException("Engine returned an unreadable response.")
            if (obj.optBoolean("ok", false)) {
                val r = obj.opt("result")
                if (r == JSONObject.NULL) null else r
            } else {
                throw EngineException(obj.optString("error").ifEmpty { "Engine call failed." })
            }
        }
    }

    fun args(vararg pairs: Pair<String, Any?>): JSONObject {
        val o = JSONObject()
        for ((k, v) in pairs) o.put(k, v ?: JSONObject.NULL)
        return o
    }

    // ---- typed conveniences ----

    suspend fun string(function: String, args: JSONObject = JSONObject()): String =
        call(function, args) as? String ?: throw EngineException("Engine returned an unreadable response.")

    suspend fun bool(function: String, args: JSONObject = JSONObject()): Boolean =
        call(function, args) as? Boolean ?: throw EngineException("Engine returned an unreadable response.")

    suspend fun dict(function: String, args: JSONObject = JSONObject()): JSONObject =
        call(function, args) as? JSONObject ?: throw EngineException("Engine returned an unreadable response.")

    suspend fun array(function: String, args: JSONObject = JSONObject()): JSONArray =
        call(function, args) as? JSONArray ?: throw EngineException("Engine returned an unreadable response.")

    suspend fun int(function: String, args: JSONObject = JSONObject()): Int =
        when (val v = call(function, args)) {
            is Int -> v
            is Long -> v.toInt()
            is Double -> v.toInt()
            else -> throw EngineException("Engine returned an unreadable response.")
        }

    // ---- domain-specific helpers (identical to the iOS WalletEngine) ----

    suspend fun generateMnemonic(): String = string("generateMnemonic")
    suspend fun validateMnemonic(m: String): Boolean = bool("validateMnemonic", args("mnemonic" to m))
    suspend fun wifToAddress(wif: String): String = string("wifToAddress", args("wif" to wif))
    suspend fun wifToPubKey(wif: String): String = string("wifToPubKey", args("wif" to wif))
    suspend fun randomWif(): String = string("randomWif")
    suspend fun validateAddress(a: String): Boolean =
        try { bool("validateAddress", args("address" to a)) } catch (e: Exception) { false }

    suspend fun wifFromMnemonic(m: String, mode: ImportMode, path: String? = null, pin: String = ""): String {
        return when (mode) {
            ImportMode.BIP44 -> string("mnemonicToWifBip44", args("mnemonic" to m))
            ImportMode.LEGACY -> string("mnemonicToWifLegacy", args("mnemonic" to m))
            ImportMode.PATH -> string("mnemonicToWifPath",
                args("mnemonic" to m, "path" to (path ?: Fees.BIP44_PATH), "passphrase" to pin))
            ImportMode.WIF -> throw EngineException("Not a mnemonic mode")
        }
    }

    suspend fun fees(inscribeBytes: Int = 0): Fees {
        val d = dict("fees", args("bytes" to inscribeBytes))
        return Fees(
            sendMinerFee = d.optInt("sendMinerFee", 97),
            inscribeMinerFee = d.optInt("inscribeMinerFee", 105),
            ordinalMinerFee = d.optInt("ordinalMinerFee", 117),
            totalServiceFees = d.optInt("totalServiceFees", 3996)
        )
    }

    suspend fun signMessage(wif: String, message: String): Pair<String, String> {
        val d = dict("signMessage", args("wif" to wif, "message" to message))
        val s = d.optString("signature")
        val p = d.optString("pubkey")
        if (s.isEmpty() || p.isEmpty()) throw EngineException("Engine returned an unreadable response.")
        return Pair(s, p)   // (signature, pubkey)
    }

    // ---- BRC-100 (v3.2) ----

    /**
     * BRC-100 fase 2: run one ProtoWallet method in the engine. ProtoWallet is
     * async on the microtask queue; V8 drains that queue between evaluate
     * calls, so start → poll resolves almost immediately (the loop is a safety
     * margin, never a busy-wait). Errors surface as Brc100.Err so the page
     * gets the standards-shaped WERR_* rejection.
     */
    suspend fun callBrc100(method: String, argsJson: String, wif: String): JSONObject {
        call("brc100Init", args("wif" to wif))
        val callId = "b${System.currentTimeMillis()}-${(0..99999).random()}"
        call("brc100Start", args("callId" to callId, "method" to method, "argsJson" to argsJson))
        repeat(200) {
            val r = call("brc100Poll", args("callId" to callId)) as? JSONObject
            if (r != null && r.optBoolean("done", false)) {
                if (r.optBoolean("ok", false)) {
                    return r.optJSONObject("result") ?: JSONObject()
                }
                val e = r.optJSONObject("error")
                throw Brc100.Err(
                    e?.optString("name")?.ifEmpty { null } ?: "WERR_UNKNOWN",
                    e?.optInt("code", 1) ?: 1,
                    e?.optString("message")?.ifEmpty { null } ?: "Unknown engine error.")
            }
            kotlinx.coroutines.delay(5)
        }
        throw Brc100.Err("WERR_UNKNOWN", 1, "The BRC-100 engine call timed out.")
    }

    /** wipe BRC-100 key material from the engine (called on wallet lock) */
    suspend fun brc100Reset() {
        try { call("brc100Reset") } catch (e: Exception) { }
    }
}

enum class ImportMode(val raw: String) {
    BIP44("bip44"), LEGACY("legacy"), WIF("wif"), PATH("path")
}

data class Fees(
    val sendMinerFee: Int,
    val inscribeMinerFee: Int,
    val ordinalMinerFee: Int,
    val totalServiceFees: Int
) {
    companion object {
        const val BIP44_PATH = "m/44'/236'/0'/0/0"
    }
}
