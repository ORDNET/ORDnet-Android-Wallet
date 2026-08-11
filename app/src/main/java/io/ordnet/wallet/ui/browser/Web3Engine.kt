package io.ordnet.wallet.ui.browser

import android.util.Base64
import android.webkit.WebResourceResponse
import io.ordnet.wallet.core.Api
import io.ordnet.wallet.core.WalletEngine
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import java.io.ByteArrayInputStream

/**
 * Native port of the extension's viewer engine + service-worker blockchain
 * router (sw.js). Instead of a service worker, the WebViewClient's
 * shouldInterceptRequest serves `ordweb3://ord/<domain>.<tld>[/subpath]` and
 * `ordweb3://ord/<txid>_<vout>` straight from the chain (domains.ordnet.io +
 * WhatsOnChain), with an in-memory cache — so internal links between .web3
 * pages work exactly like in Chrome and on iOS.
 */
object Web3 {
    const val scheme = "ordweb3"
    val supportedTLDs = listOf("web3", "bitcoin", "bsv", "ordinal", "sat", "crypto", "nft", "x", "sats", "ord")

    fun isValidTxid(s: String): Boolean = Regex("^[a-fA-F0-9]{64}$").matches(s)

    /**
     * true only when the host's LAST label is a web3 TLD. A contains-check
     * would misfire: "api.ordnet.io" contains ".ord" but is a normal website.
     */
    fun hasWeb3TLD(s: String): Boolean {
        var host = s.lowercase()
        val idx = host.indexOf("://")
        if (idx >= 0) host = host.substring(idx + 3)
        host = host.substringBefore("/").substringBefore("?").substringBefore("#")
        val labels = host.split(".")
        if (labels.size < 2) return false
        return supportedTLDs.contains(labels.last())
    }

    data class Content(val contentType: String, val data: ByteArray) {
        val isHTML: Boolean get() = contentType.startsWith("text/html")
    }

    /** resolve + fetch + parse — port of loadContent(); returns (txid, content) */
    suspend fun load(input: String): Pair<String, Content> {
        var txid = input.trim()
        if (!isValidTxid(txid)) {
            if (!hasWeb3TLD(txid)) {
                throw Api.ApiException(0, "Enter a valid .web3 domain or 64-character TXID")
            }
            txid = Api.resolve(txid.lowercase())
        }
        val hex = Api.txHex(txid)
        val ord = WalletEngine.shared.call("extractOrd",
            WalletEngine.shared.args("rawTxHex" to hex)) as? JSONObject
            ?: throw Api.ApiException(0, "No 1SatOrdinals inscription found")
        val ct = ord.optString("ct")
        val b64 = ord.optString("dataB64")
        val data = try { Base64.decode(b64, Base64.DEFAULT) } catch (e: Exception) { null }
        if (ct.isEmpty() || data == null) {
            throw Api.ApiException(0, "No 1SatOrdinals inscription found")
        }
        return Pair(txid, Content(ct, data))
    }

    /** fetch a specific output — used by the scheme router for /txid_N */
    suspend fun loadOutput(txid: String, vout: Int): Content {
        val hex = Api.txHex(txid)
        val ord = WalletEngine.shared.call("extractOrd",
            WalletEngine.shared.args("rawTxHex" to hex, "vout" to vout)) as? JSONObject
            ?: throw Api.ApiException(0, "no inscription")
        val ct = ord.optString("ct")
        val b64 = ord.optString("dataB64")
        val data = try { Base64.decode(b64, Base64.DEFAULT) } catch (e: Exception) { null }
        if (ct.isEmpty() || data == null) throw Api.ApiException(0, "no inscription")
        return Content(ct, data)
    }

    /**
     * HTML preprocessor — port of preprocessHtml(): rewrites protocol-relative
     * web3 links so they resolve through the scheme router.
     */
    fun preprocess(html: String): String {
        val tlds = supportedTLDs.joinToString("|")
        val re = Regex("(href|src)=([\"'])//(([a-z0-9][a-z0-9-]*\\.)+($tlds)([/?][^\"']*)?)", RegexOption.IGNORE_CASE)
        return re.replace(html) { m ->
            "${m.groupValues[1]}=${m.groupValues[2]}/${m.groupValues[3]}"
        }
    }

    /**
     * click-interceptor injected into every rendered page (port of the inline
     * script that viewer.js appended) — full-page web3/txid navigations are
     * forwarded to the native browser via the OrdnetNavigateAndroid bridge.
     */
    val interceptorScript: String
        get() {
            val tlds = supportedTLDs.joinToString("|")
            return """
            (function(){
              if (window.__ordnetInterceptor) return;
              window.__ordnetInterceptor = true;
              var tldPattern=new RegExp("^/?(([a-z0-9][a-z0-9-]*\\.)+($tlds))(/[^?#]*)?(\\?[^#]*)?(#.*)?${'$'}","i");
              var txidPattern=/^\/?([a-f0-9]{64})(?:_(\d+))?(#.*)?${'$'}/i;
              var fragmentPattern=/^\/?(#.+)${'$'}/;
              function scrollToFragment(frag){
                try{
                  var id=decodeURIComponent(frag.substring(1));
                  var el=document.getElementById(id)||document.querySelector('[name="'+id+'"]');
                  if(el){el.scrollIntoView({behavior:"smooth",block:"start"});}
                }catch(err){}
              }
              document.addEventListener("click",function(e){
                var link=e.target.closest("a[href]");
                if(!link)return;
                var href=link.getAttribute("href");
                if(!href)return;
                var fragOnly=href.match(fragmentPattern);
                if(fragOnly){ e.preventDefault(); e.stopPropagation(); scrollToFragment(fragOnly[1]); return; }
                var domainMatch=href.match(tldPattern);
                var txidMatch=href.match(txidPattern);
                if(domainMatch||txidMatch){
                  e.preventDefault(); e.stopPropagation();
                  var target,frag;
                  if(domainMatch){ target=domainMatch[1]+(domainMatch[4]||""); frag=domainMatch[6]||""; }
                  else { target=txidMatch[1]; frag=txidMatch[3]||""; }
                  OrdnetNavigateAndroid.postMessage(target, frag);
                }
              },true);
            })();
            """.trimIndent()
        }
}

/**
 * The blockchain router: serves web3 content to the WebView for subresource
 * requests and internal navigations, replacing sw.js one-to-one (incl. cache
 * semantics — tx content is immutable). Called from shouldInterceptRequest,
 * which runs on a background thread, so blocking here is safe.
 */
class Web3SchemeRouter {
    private val cache = HashMap<String, Pair<String, ByteArray>>()

    fun handle(url: android.net.Uri): WebResourceResponse {
        val path = url.path ?: return fail("bad url")

        // /<txid>_<vout>
        val txidMatch = Regex("/([a-f0-9]{64})_(\\d+)$", RegexOption.IGNORE_CASE).find(path)
        if (txidMatch != null) {
            val txid = txidMatch.groupValues[1].lowercase()
            val vout = txidMatch.groupValues[2].toIntOrNull() ?: 0
            return serve("${txid}_$vout") {
                val c = Web3.loadOutput(txid, vout)
                Pair(c.contentType, c.data)
            }
        }

        // /<domain>.<tld>[/subpath]
        val tlds = Web3.supportedTLDs.joinToString("|")
        val domainMatch = Regex("^/([a-z0-9][a-z0-9.-]*\\.($tlds))(/[^?]*)?", RegexOption.IGNORE_CASE).find(path)
        if (domainMatch != null) {
            val matched = domainMatch.value.removePrefix("/")   // domain[/subpath]
            return serve("d_$matched") {
                val txid = Api.resolve(matched)
                val hex = Api.txHex(txid)
                val ord = WalletEngine.shared.call("extractOrd",
                    WalletEngine.shared.args("rawTxHex" to hex)) as? JSONObject
                    ?: throw Api.ApiException(404, "no inscription")
                val ct = ord.optString("ct")
                val b64 = ord.optString("dataB64")
                val data = try { Base64.decode(b64, Base64.DEFAULT) } catch (e: Exception) { null }
                if (ct.isEmpty() || data == null) throw Api.ApiException(404, "no inscription")
                Pair(ct, data)
            }
        }

        return fail("not a web3 path")
    }

    private fun serve(key: String, fetch: suspend () -> Pair<String, ByteArray>): WebResourceResponse {
        synchronized(cache) { cache[key] }?.let { (ct, data) ->
            return respond(ct, data)
        }
        return try {
            val (ct, data) = runBlocking { fetch() }
            synchronized(cache) { cache[key] = Pair(ct, data) }
            respond(ct, data)
        } catch (e: Exception) {
            respond("text/plain", "Error: ${e.message}".toByteArray(), 404)
        }
    }

    private fun respond(contentType: String, data: ByteArray, status: Int = 200): WebResourceResponse {
        val mime = contentType.substringBefore(";").trim().ifEmpty { "application/octet-stream" }
        val resp = WebResourceResponse(mime, "utf-8", ByteArrayInputStream(data))
        resp.setStatusCodeAndReasonPhrase(status, if (status == 200) "OK" else "Not Found")
        resp.responseHeaders = mapOf(
            "Cache-Control" to "public,max-age=31536000",
            "Access-Control-Allow-Origin" to "*"
        )
        return resp
    }

    private fun fail(message: String): WebResourceResponse =
        respond("text/plain", "Error: $message".toByteArray(), 404)
}
