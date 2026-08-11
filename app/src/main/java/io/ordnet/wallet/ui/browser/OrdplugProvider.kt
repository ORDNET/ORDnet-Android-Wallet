package io.ordnet.wallet.ui.browser

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.QuestionMark
import androidx.compose.material.icons.filled.Sell
import androidx.compose.material.icons.filled.ShoppingBag
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.ordnet.wallet.core.Api
import io.ordnet.wallet.core.Fees
import io.ordnet.wallet.core.Fmt
import io.ordnet.wallet.core.ProviderRequest
import io.ordnet.wallet.core.WalletEngine
import io.ordnet.wallet.core.WalletStore
import io.ordnet.wallet.ui.AlertKind
import io.ordnet.wallet.ui.FormSection
import io.ordnet.wallet.ui.InlineAlert
import io.ordnet.wallet.ui.KVRow
import io.ordnet.wallet.ui.OrdnetOutlineButton
import io.ordnet.wallet.ui.OrdnetProminentButton
import io.ordnet.wallet.ui.Theme
import io.ordnet.wallet.ui.components.ButtonSpinner
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * The window.ordplug provider — same method set as the extension's inpage.js:
 * connect, getAddress, getPublicKey, getBalance, pay, inscribe, signMessage,
 * purchase, listOrdinal, buyOrdinal, sendTx. Every call returns a Promise;
 * approvals happen in a native sheet instead of the extension popup.
 */
object OrdplugProvider {

    /** per-request delivery callbacks, keyed by request id */
    val pendingDelivery = HashMap<String, (Boolean, JSONObject?, String?) -> Unit>()

    val script = """
    (function(){
      if (window.ordplug) return;
      var _id = 0;
      var _pending = {};
      function request(method, params){
        return new Promise(function(resolve, reject){
          var id = 'op_' + (++_id) + '_' + Date.now();
          _pending[id] = { resolve: resolve, reject: reject };
          try {
            OrdplugAndroid.postMessage(JSON.stringify({ id: id, method: method, params: params || {} }));
          } catch(e) { delete _pending[id]; reject(new Error('ORD/net bridge unavailable')); return; }
          setTimeout(function(){
            if(_pending[id]){ _pending[id].reject(new Error('ORD/net request timed out')); delete _pending[id]; }
          }, 5 * 60 * 1000);
        });
      }
      window.__ordplugDeliver = function(msg){
        var p = _pending[msg.id];
        if (!p) return;
        delete _pending[msg.id];
        if (msg.ok) p.resolve(msg.result);
        else p.reject(new Error(msg.error || 'Request failed'));
      };
      window.ordplug = {
        isOrdPlug: true,
        version: '1.0.0',
        platform: 'android',
        connect:      function(){ return request('connect'); },
        getAddress:   function(){ return request('getAddress'); },
        getPublicKey: function(){ return request('getPublicKey'); },
        getBalance:   function(){ return request('getBalance'); },
        pay:          function(params){ return request('pay', params); },
        inscribe:     function(params){ return request('inscribe', params); },
        signMessage:  function(params){ return request('signMessage', typeof params === 'string' ? { message: params } : params); },
        purchase:     function(params){ return request('purchase', params); },
        listOrdinal:  function(params){ return request('listOrdinal', params); },
        buyOrdinal:   function(params){ return request('buyOrdinal', params); },
        sendTx:       function(params){ return request('sendTx', params); },
        request:      request
      };
      window.dispatchEvent(new Event('ordplug#initialized'));
    })();
    """.trimIndent()

    /** read-only methods (auto-resolved when the origin is already connected) */
    suspend fun performRead(method: String, store: WalletStore): JSONObject {
        return when (method) {
            "getAddress", "connect" -> JSONObject().put("address", store.address)
            "getPublicKey" -> JSONObject()
                .put("pubkey", store.engine.wifToPubKey(store.wif))
                .put("address", store.address)
            "getBalance" -> {
                val b = Api.balance(store.address)
                JSONObject().put("confirmed", b.confirmed).put("unconfirmed", b.unconfirmed)
            }
            else -> throw WalletEngine.EngineException("Not a read method")
        }
    }

    /** sats as a safe integer — port of satNum/purchaseSats */
    fun satNum(v: Any?): Long = when (v) {
        is Int -> maxOf(0, v).toLong()
        is Long -> maxOf(0L, v)
        is Double -> maxOf(0L, Math.round(v))
        is String -> v.toDoubleOrNull()?.let { maxOf(0L, Math.round(it)) } ?: 0L
        else -> 0L
    }

    fun purchaseSats(p: JSONObject): Long {
        if (p.has("amountSat") && !p.isNull("amountSat")) return satNum(p.opt("amountSat"))
        if (p.has("amount") && !p.isNull("amount")) {
            return when (val a = p.opt("amount")) {
                is Double -> Math.round(a * 1e8)
                is Int -> Math.round(a.toDouble() * 1e8)
                is Long -> Math.round(a.toDouble() * 1e8)
                else -> 0L
            }
        }
        return 0L
    }

    private fun str(p: JSONObject, key: String): String =
        if (p.has(key) && !p.isNull(key)) p.opt(key).toString() else ""

    /** execute an APPROVED request — mirror of the extension's approveRequest() */
    suspend fun perform(req: ProviderRequest, store: WalletStore): JSONObject {
        val p = req.params
        return when (req.method) {
            "connect", "getAddress", "getPublicKey", "getBalance" -> {
                store.connectSite(req.origin)
                performRead(req.method, store)
            }

            "pay" -> {
                val to = str(p, "to")
                val amount = satNum(p.opt("amount"))
                val data: String? = if (p.has("data") && !p.isNull("data")) p.opt("data").toString() else null
                val fee = if (p.has("fee") && !p.isNull("fee")) satNum(p.opt("fee")) else 0L
                val txid = store.sendBSV(to = to, amountSat = amount, dataStr = data, feeSat = fee)
                JSONObject().put("txid", txid)
            }

            "inscribe" -> {
                val dataStr = str(p, "data")
                val b64 = android.util.Base64.encodeToString(dataStr.toByteArray(Charsets.UTF_8), android.util.Base64.NO_WRAP)
                val ct = if (p.has("contentType") && !p.isNull("contentType")) p.optString("contentType") else "text/plain"
                val fee = if (p.has("fee") && !p.isNull("fee")) satNum(p.opt("fee")) else 0L
                val txid = store.inscribe(contentType = ct, dataB64 = b64, feeSat = fee)
                JSONObject().put("txid", txid).put("address", store.address)
            }

            "signMessage" -> {
                val msg = str(p, "message")
                val (signature, pubkey) = store.engine.signMessage(store.wif, msg)
                JSONObject().put("signature", signature).put("pubkey", pubkey).put("address", store.address)
            }

            "purchase" -> {
                val sats = purchaseSats(p)
                if (sats < 1) throw WalletEngine.EngineException("Invalid amount.")
                val to = str(p, "to")
                if (!store.engine.validateAddress(to)) throw WalletEngine.EngineException("Invalid seller address.")
                val msg = store.engine.string("purchaseMessage", store.engine.args(
                    "shop" to p.optString("shop", ""), "itemTitle" to p.optString("itemTitle", ""),
                    "orderId" to p.optString("orderId", ""), "amountSat" to sats, "to" to to
                ))
                val (signature, pubkey) = store.engine.signMessage(store.wif, msg)
                val reference = when {
                    p.has("reference") && !p.isNull("reference") -> p.opt("reference").toString()
                    p.has("opReturn") && !p.isNull("opReturn") -> p.opt("opReturn").toString()
                    else -> msg
                }
                var opret = "$reference | sig:$signature"
                if (opret.length > 900) opret = opret.take(900)
                val feeSat = store.engine.int("purchaseFee",
                    store.engine.args("opReturnByteLength" to opret.toByteArray(Charsets.UTF_8).size))
                val txid = store.sendBSV(to = to, amountSat = sats, dataStr = opret, feeSat = feeSat.toLong())
                JSONObject().put("txid", txid).put("address", store.address)
                    .put("signature", signature).put("pubkey", pubkey).put("message", msg)
            }

            "listOrdinal" -> {
                val price = satNum(p.opt("priceSat"))
                if (price < 1) throw WalletEngine.EngineException("Invalid price.")
                val ordTxid = str(p, "ordinalTxid")
                val ordVout = p.optInt("ordinalVout", 0)
                val ordHex = Api.txHex(ordTxid)
                val ordScriptHex = store.engine.string("outputScriptHex",
                    store.engine.args("rawTxHex" to ordHex, "vout" to ordVout))
                val r = store.engine.dict("buildListingPartial", store.engine.args(
                    "wif" to store.wif, "ordTxid" to ordTxid, "ordVout" to ordVout,
                    "ordScriptHex" to ordScriptHex, "priceSat" to price
                ))
                JSONObject()
                    .put("partialTx", r.optString("partialTx"))
                    .put("payScriptHex", r.optString("payScriptHex"))
                    .put("sellerAddress", store.address)
                    .put("priceSat", price)
            }

            "buyOrdinal" -> {
                val txid = store.buyOrdinal(
                    partialTx = str(p, "partialTx"),
                    priceSat = satNum(p.opt("priceSat")),
                    sellerAddress = str(p, "sellerAddress"),
                    payScriptHex = str(p, "payScriptHex")
                )
                JSONObject().put("txid", txid).put("address", store.address)
            }

            "sendTx" -> {
                val (txid, rawtx) = store.sendComposedTx(p)
                val out = JSONObject().put("rawtx", rawtx).put("address", store.address)
                out.put("txid", txid ?: JSONObject.NULL)
                out
            }

            else -> throw WalletEngine.EngineException("Unknown method: ${req.method}")
        }
    }
}

// MARK: - Approval sheet (native counterpart of the extension approval popup)

@Composable
fun ApprovalSheetContent(store: WalletStore, model: BrowserModel, request: ProviderRequest) {
    // keyed to the request id so a replacing request never shows stale state
    var error by remember(request.id) { mutableStateOf("") }
    var busy by remember(request.id) { mutableStateOf(false) }
    var fees by remember(request.id) { mutableStateOf<Fees?>(null) }
    var inscribeFee by remember(request.id) { mutableStateOf(0) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(request.id) {
        fees = try { store.engine.fees() } catch (e: Exception) { null }
        if (request.method == "inscribe") {
            val bytes = request.params.optString("data", "").toByteArray(Charsets.UTF_8).size
            inscribeFee = try { store.engine.fees(bytes).inscribeMinerFee } catch (e: Exception) { 0 }
        }
    }

    val (title, icon) = when (request.method) {
        "connect", "getAddress", "getPublicKey", "getBalance" -> Pair("Connect wallet", Icons.Filled.Link)
        "pay" -> Pair("Approve payment", Icons.AutoMirrored.Filled.Send)
        "inscribe" -> Pair("Approve inscription", Icons.Filled.Edit)
        "signMessage" -> Pair("Sign message", Icons.Filled.Verified)
        "purchase" -> Pair("Approve purchase", Icons.Filled.ShoppingCart)
        "listOrdinal" -> Pair("List for sale", Icons.Filled.Sell)
        "buyOrdinal" -> Pair("Buy ordinal", Icons.Filled.ShoppingBag)
        "sendTx" -> Pair(
            request.params.optJSONObject("meta")?.optString("title")?.ifEmpty { null } ?: "Approve transaction",
            Icons.AutoMirrored.Filled.Send
        )
        else -> Pair("Wallet request", Icons.Filled.QuestionMark)
    }

    val approveLabel = when (request.method) {
        "connect", "getAddress", "getPublicKey", "getBalance" -> "Connect"
        "pay" -> "Approve & send"
        "inscribe" -> "Approve & inscribe"
        "signMessage" -> "Sign"
        "purchase" -> "Sign & pay"
        "listOrdinal" -> "Sign listing"
        "buyOrdinal" -> "Approve & buy"
        "sendTx" -> "Approve & send"
        else -> "Approve"
    }

    fun approve() {
        busy = true
        model.approvalBusy = true
        error = ""
        scope.launch {
            try {
                val result = OrdplugProvider.perform(request, store)
                OrdplugProvider.pendingDelivery.remove(request.id)?.invoke(true, result, null)
                model.approvalBusy = false
                store.pendingProviderRequest = null
            } catch (e: Exception) {
                error = e.message ?: "Request failed."
                model.approvalBusy = false
            }
            busy = false
        }
    }

    fun reject() {
        model.rejectPending(request)
    }

    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
            .padding(bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Theme.secondaryText().copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, contentDescription = null, tint = Theme.ink(), modifier = Modifier.size(24.dp))
            }
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = Theme.ink())
                Text("Source: ${request.origin}", fontSize = 12.sp, color = Theme.secondaryText())
            }
        }

        FormSection(header = "Details") {
            ApprovalDetails(store, request, fees, inscribeFee)
        }

        InlineAlert(AlertKind.ERROR, error)
        OrdnetProminentButton(onClick = { approve() }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
            if (busy) ButtonSpinner() else Text(approveLabel, fontWeight = FontWeight.SemiBold)
        }
        OrdnetOutlineButton(onClick = { reject() }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
            Text("Reject", color = Theme.statusRed)
        }
    }
}

@Composable
private fun ApprovalDetails(store: WalletStore, request: ProviderRequest, fees: Fees?, inscribeFee: Int) {
    val p = request.params
    val svc = fees?.totalServiceFees ?: 3996

    when (request.method) {
        "connect", "getAddress", "getPublicKey", "getBalance" -> {
            Text("This page wants to see your wallet address.", fontSize = 13.sp, color = Theme.ink())
            KVRow(k = "Account", v = store.activeAccount?.name ?: "Account")
            KVRow(k = "Address", v = store.address, mono = true)
        }
        "pay" -> {
            KVRow(k = "From", v = store.activeAccount?.name ?: "Account")
            KVRow(k = "To", v = p.opt("to")?.toString() ?: "", mono = true)
            val amt = OrdplugProvider.satNum(p.opt("amount"))
            KVRow(k = "Amount", v = "${Fmt.bsv(amt)} BSV (${Fmt.sats(amt)} sats)")
            if (p.has("data") && !p.isNull("data")) {
                KVRow(k = "OP_RETURN", v = p.opt("data").toString().take(80))
            }
            val fee = p.optInt("fee", 0).let { if (it > 0) it else fees?.sendMinerFee ?: 97 }
            KVRow(k = "Miner fee", v = "${Fmt.bsv(fee)} BSV")
            KVRow(k = "Service fee", v = "${Fmt.bsv(svc)} BSV")
        }
        "inscribe" -> {
            val dataStr = p.optString("data", "")
            val bytes = dataStr.toByteArray(Charsets.UTF_8).size
            val ct = if (p.has("contentType") && !p.isNull("contentType")) p.optString("contentType") else "text/plain"
            KVRow(k = "Content type", v = ct)
            if (ct == "text/plain" && bytes < 64) {
                KVRow(k = "Data", v = dataStr)
            }
            KVRow(k = "Size", v = "${Fmt.sats(bytes)} bytes")
            KVRow(k = "Inscribe to", v = store.address, mono = true)
            KVRow(k = "Miner fee", v = "${Fmt.bsv(inscribeFee)} BSV")
            KVRow(k = "Service fee", v = "${Fmt.bsv(svc)} BSV")
        }
        "signMessage" -> {
            Text("Sign this message with your key. No coins move.", fontSize = 13.sp, color = Theme.ink())
            KVRow(k = "Message", v = p.optString("message", "").take(200))
        }
        "purchase" -> {
            KVRow(k = "Item", v = p.optString("itemTitle").ifEmpty { "Order" })
            if (p.has("shop") && !p.isNull("shop")) KVRow(k = "Shop", v = p.optString("shop"))
            KVRow(k = "Seller", v = p.opt("to")?.toString() ?: "", mono = true)
            val sats = OrdplugProvider.purchaseSats(p)
            KVRow(k = "Amount", v = "${Fmt.bsv(sats)} BSV (${Fmt.sats(sats)} sats)")
            KVRow(k = "Miner fee", v = "${Fmt.bsv(fees?.sendMinerFee ?: 97)} BSV")
            KVRow(k = "Service fee", v = "${Fmt.bsv(svc)} BSV")
            Text(
                "You sign the order and pay in one step. Your signature and the order reference are written on-chain.",
                fontSize = 12.sp, color = Theme.secondaryText()
            )
        }
        "listOrdinal" -> {
            Text(
                "Sign a one-sided atomic swap. The ordinal stays in your wallet until a buyer pays your price.",
                fontSize = 13.sp, color = Theme.ink()
            )
            KVRow(k = "Ordinal", v = "${p.optString("ordinalTxid").take(10)}…_${p.optInt("ordinalVout", 0)}", mono = true)
            val price = OrdplugProvider.satNum(p.opt("priceSat"))
            KVRow(k = "Price", v = "${Fmt.bsv(price)} BSV (${Fmt.sats(price)} sats)")
            KVRow(k = "Paid to", v = store.address, mono = true)
        }
        "buyOrdinal" -> {
            Text(
                "Complete the swap: pay the seller and receive the ordinal in one transaction.",
                fontSize = 13.sp, color = Theme.ink()
            )
            val price = OrdplugProvider.satNum(p.opt("priceSat"))
            KVRow(k = "Price to seller", v = "${Fmt.bsv(price)} BSV (${Fmt.sats(price)} sats)")
            KVRow(k = "Seller", v = p.opt("sellerAddress")?.toString() ?: "", mono = true)
            KVRow(k = "Miner fee", v = "${Fmt.bsv(fees?.ordinalMinerFee ?: 117)} BSV")
            KVRow(k = "Service fee", v = "${Fmt.bsv(svc)} BSV")
            KVRow(k = "Received to", v = store.address, mono = true)
        }
        "sendTx" -> {
            val outs = p.optJSONArray("outputs") ?: org.json.JSONArray()
            p.optJSONObject("meta")?.optString("shop")?.ifEmpty { null }?.let { shop ->
                KVRow(k = "Shop", v = shop)
            }
            for (i in 0 until outs.length()) {
                val o = outs.optJSONObject(i) ?: continue
                when (o.optString("type", "?")) {
                    "inscription" -> KVRow(
                        k = "#$i Inscription",
                        v = "${o.opt("data")?.toString()?.take(40) ?: ""} → ${maxOf(1L, OrdplugProvider.satNum(o.opt("satoshis")))} sat"
                    )
                    "p2pkh" -> KVRow(
                        k = "#$i Payment",
                        v = "${Fmt.sats(OrdplugProvider.satNum(o.opt("satoshis")))} sats → ${o.opt("address")?.toString()?.take(16) ?: ""}…"
                    )
                    "opreturn" -> {
                        val parts = o.optJSONArray("data") ?: org.json.JSONArray()
                        val joined = (0 until parts.length()).joinToString(" ") { parts.optString(it) }
                        KVRow(k = "#$i OP_RETURN", v = joined.take(60))
                    }
                    "script" -> KVRow(
                        k = "#$i Script",
                        v = "${Fmt.sats(OrdplugProvider.satNum(o.opt("satoshis")))} sats"
                    )
                    else -> KVRow(k = "#$i", v = o.optString("type", "?"))
                }
            }
            val inclSvc = !(p.has("includeServiceFees") && !p.isNull("includeServiceFees") && !p.optBoolean("includeServiceFees", true))
            KVRow(k = "Service fee", v = "${Fmt.bsv(if (inclSvc) svc else 0)} BSV")
            Text(
                "Review every output above — you sign and broadcast in one step.",
                fontSize = 12.sp, color = Theme.secondaryText()
            )
        }
        else -> Text("Unknown request.", fontSize = 13.sp, color = Theme.ink())
    }
}
