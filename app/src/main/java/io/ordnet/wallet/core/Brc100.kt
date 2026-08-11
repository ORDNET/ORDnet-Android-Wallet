package io.ordnet.wallet.core

import org.json.JSONArray
import org.json.JSONObject

/**
 * BRC-100 provider — native side (v3.2, iOS v2.6.0 parity).
 *
 * The wallet is the BRC-100 *provider* (substrate); apps in the WebView are
 * clients. The page only ever sees the key-free shim (brc100-shim.js); this
 * object decides what each method does. Everything unsupported fails
 * EXPLICITLY with a standards-shaped WalletError (name WERR_*, code, message)
 * that the shim turns into a promise REJECTION — an app must never mistake a
 * refusal for success.
 *
 * Phasing (per the BRC-100 briefing):
 *   fase 1: getVersion, getNetwork, getHeight, isAuthenticated,
 *           waitForAuthentication (getHeaderForHeight: explicit error)
 *   fase 2: keys/crypto via the bundled @bsv/sdk ProtoWallet, behind native
 *           permission sheets (per app, per protocol — BRC-43 grants)
 *   fase 3: money — createAction (outputs-only), internalizeAction,
 *           listActions, listOutputs, relinquishOutput; signAction/abortAction
 *           refuse explicitly until the signableTransaction path really exists
 *   fase 4: certificates + the two privacy-sensitive linkage methods (absent)
 */
object Brc100 {

    /** standards-shaped error (mirrors @bsv/sdk WalletError semantics) */
    class Err(val werrName: String, val code: Int, message: String) : Exception(message)

    const val VERSION_STRING = "ordplug-1.0.0"

    /** fase 2: keys & crypto — permission-gated, executed by the bundled
     *  @bsv/sdk ProtoWallet inside the engine (keys never leave it) */
    val phase2Methods = setOf(
        "getPublicKey", "encrypt", "decrypt",
        "createSignature", "verifySignature", "createHmac", "verifyHmac"
    )

    suspend fun handle(method: String, argsJson: String, originator: String, store: WalletStore?): JSONObject {
        return when {

            // ---- fase 1: informative, no keys, no money ----
            method == "getVersion" -> JSONObject().put("version", VERSION_STRING)
            method == "getNetwork" -> JSONObject().put("network", "mainnet")
            method == "getHeight" -> {
                try {
                    JSONObject().put("height", Api.chainHeight())
                } catch (e: Exception) {
                    throw Err("WERR_UNKNOWN", 1,
                        "Could not read the chain height right now: ${e.message}")
                }
            }
            method == "isAuthenticated" || method == "waitForAuthentication" ->
                // the in-app browser is only reachable while the wallet is unlocked
                JSONObject().put("authenticated", true)

            // ---- fase 2: keys & crypto via ProtoWallet, behind grants ----
            phase2Methods.contains(method) -> {
                val s = unlockedStore(store)
                val args = try { JSONObject(argsJson) } catch (e: Exception) { JSONObject() }
                // BRC-43 grants: level 0 open; level 1 per app+protocol; level 2
                // + counterparty; identity key has its own per-app grant. Native
                // biometric sheet on first use, persistent afterwards.
                s.requireBrc100Permission(origin = originator, method = method, args = args)
                s.engine.callBrc100(method = method, argsJson = argsJson, wif = s.wif)
            }

            // ---- fase 3: geld — per-transactie biometrie, geld ≠ grant ----
            method == "createAction" -> createAction(argsJson, originator, store)
            method == "internalizeAction" -> internalizeAction(argsJson, originator, store)
            method == "listActions" -> listActions(argsJson, store)
            method == "listOutputs" -> listOutputs(argsJson, store)
            method == "relinquishOutput" -> relinquishOutput(argsJson, store)
            method == "signAction" ->
                // regel 1: het signableTransaction-pad bestaat pas als het er ECHT is
                throw Err("WERR_UNSUPPORTED_ACTION", 2,
                    "signAction is not supported yet: this wallet only processes outputs-only createAction calls (no signableTransaction path).")
            method == "abortAction" ->
                // zonder signableTransaction-pad is er nooit een af te breken actie
                throw Err("WERR_INVALID_PARAMETER", 3,
                    "abortAction: no abortable action exists for this reference — this wallet fully processes actions at createAction time.")

            // ---- privacy-sensitive: explicitly unsupported ----
            method == "revealCounterpartyKeyLinkage" || method == "revealSpecificKeyLinkage" ->
                throw Err("WERR_UNSUPPORTED_ACTION", 2,
                    "$method is privacy-sensitive and not supported by the ORDnet wallet.")

            // ---- everything else: explicit, standards-shaped refusal ----
            else -> throw Err("WERR_UNSUPPORTED_ACTION", 2,
                "$method is not yet supported by the ORDnet wallet. Supported today: fase 1 (getVersion, getNetwork, getHeight, isAuthenticated, waitForAuthentication), fase 2 (getPublicKey, encrypt, decrypt, createSignature, verifySignature, createHmac, verifyHmac) and fase 3 (createAction, internalizeAction, listActions, listOutputs, relinquishOutput).")
        }
    }

    // MARK: - fase 3 helpers

    /** engine validation results carry {valid:false, werr:{…}} — translate
     *  1-op-1 to the BRC-100 error contract (promise REJECTION in the page) */
    private fun requireValid(r: JSONObject): JSONObject {
        if (r.optBoolean("valid", false)) return r
        val w = r.optJSONObject("werr")
        throw Err(
            w?.optString("name")?.ifEmpty { null } ?: "WERR_INVALID_PARAMETER",
            w?.optInt("code", 3) ?: 3,
            w?.optString("message")?.ifEmpty { null } ?: "Invalid parameters.")
    }

    private fun unlockedStore(store: WalletStore?): WalletStore {
        if (store == null || store.wif.isEmpty()) {
            throw Err("WERR_UNKNOWN", 1, "The wallet is locked.")
        }
        return store
    }

    private fun satLong(v: Any?): Long = when (v) {
        is Int -> v.toLong()
        is Long -> v
        is Double -> Math.round(v)
        is String -> v.toLongOrNull() ?: 0L
        else -> 0L
    }

    /**
     * outputs-only createAction: validate → native biometric sheet (amount +
     * destination, every transaction again) → build via the existing buildTx
     * path (ordinal protection, service fees, change) → broadcast via
     * broadcastAndRegister (chain tips + spent-guard) → action log
     */
    private suspend fun createAction(argsJson: String, originator: String, store: WalletStore?): JSONObject {
        val s = unlockedStore(store)
        val v = requireValid(s.engine.dict("brc100ValidateCreate", s.engine.args("argsJson" to argsJson)))

        val outs = v.optJSONArray("outputs") ?: JSONArray()
        val lines = ArrayList<Brc100TxLine>()
        for (i in 0 until outs.length()) {
            val o = outs.optJSONObject(i) ?: continue
            lines.add(Brc100TxLine(
                dest = (if (o.has("dest") && !o.isNull("dest")) o.optString("dest").ifEmpty { null } else null)
                    ?: "script output (not an address)",
                sats = satLong(o.opt("satoshis")),
                note = o.optString("outputDescription")))
        }
        s.requireBrc100TxConfirm(
            origin = originator,
            title = "Approve payment",
            description = v.optString("description"),
            lines = lines,
            minerFeeEstimate = satLong(v.opt("minerFeeEstimate")),
            serviceFees = satLong(v.opt("serviceFees")),
            totalSat = satLong(v.opt("totalSat")),
            incoming = false)

        val utxos = s.utxos()
        val built = try {
            s.engine.dict("brc100BuildCreate", s.engine.args(
                "wif" to s.wif, "utxos" to utxos.toString(), "argsJson" to argsJson))
        } catch (e: Exception) {
            throw Err("WERR_INSUFFICIENT_FUNDS", 5, e.message ?: "Could not build the transaction.")
        }
        val rawtx = built.optString("rawtx")
        if (rawtx.isEmpty()) {
            throw Err("WERR_UNKNOWN", 1, "The engine returned an unreadable transaction.")
        }
        val txid = try {
            s.broadcastAndRegister(rawtx)
        } catch (e: Exception) {
            throw Err("WERR_UNKNOWN", 1, "Broadcast failed: ${e.message}")
        }
        val labelsArr = v.optJSONArray("labels") ?: JSONArray()
        val labels = ArrayList<String>(labelsArr.length())
        for (i in 0 until labelsArr.length()) labels.add(labelsArr.optString(i))
        s.brc100LogAction(Brc100ActionRecord(
            txid = txid,
            description = v.optString("description"),
            labels = labels,
            satoshis = satLong(v.opt("totalSat")),
            origin = originator,
            ts = System.currentTimeMillis().toDouble(),
            status = "completed",
            isOutgoing = true))
        return JSONObject().put("txid", txid)   // CreateActionResult: tx (BEEF) volgt in een latere fase
    }

    /**
     * internalizeAction: AtomicBEEF with 'wallet payment' outputs paying the
     * wallet address — verified in the engine, confirmed with biometrics,
     * then broadcast (if needed); already-known transactions count as accepted
     * (the payment already exists on-chain)
     */
    private suspend fun internalizeAction(argsJson: String, originator: String, store: WalletStore?): JSONObject {
        val s = unlockedStore(store)
        val v = requireValid(s.engine.dict("brc100ParseInternalize", s.engine.args(
            "argsJson" to argsJson, "address" to s.address)))
        val outs = v.optJSONArray("outputs") ?: JSONArray()
        val lines = ArrayList<Brc100TxLine>()
        for (i in 0 until outs.length()) {
            val o = outs.optJSONObject(i) ?: continue
            lines.add(Brc100TxLine(
                dest = "${Fmt.shortAddress(s.address)} (this wallet)",
                sats = satLong(o.opt("satoshis")),
                note = "incoming payment output ${o.optInt("vout", 0)}"))
        }
        val desc = try { JSONObject(argsJson).optString("description") } catch (e: Exception) { "" }
        s.requireBrc100TxConfirm(
            origin = originator,
            title = "Accept incoming payment",
            description = desc,
            lines = lines,
            minerFeeEstimate = 0, serviceFees = 0,
            totalSat = satLong(v.opt("totalSat")),
            incoming = true)

        val rawtx = v.optString("rawtx")
        val txid = v.optString("txid")
        if (rawtx.isEmpty() || txid.isEmpty()) {
            throw Err("WERR_UNKNOWN", 1, "The engine returned an unreadable transaction.")
        }
        try {
            s.broadcastAndRegister(rawtx)
        } catch (e: Exception) {
            // een al-bekende transactie is GEEN fout: de betaling staat al on-chain
            val m = (e.message ?: "").lowercase()
            if (!(m.contains("already") || m.contains("txn-mempool-conflict") || m.contains("257"))) {
                throw Err("WERR_UNKNOWN", 1, "Broadcast failed: ${e.message}")
            }
        }
        s.brc100LogAction(Brc100ActionRecord(
            txid = txid, description = desc, labels = emptyList(),
            satoshis = satLong(v.opt("totalSat")),
            origin = originator,
            ts = System.currentTimeMillis().toDouble(),
            status = "completed", isOutgoing = false))
        return JSONObject().put("accepted", true)
    }

    /** listActions from the local action log (only actions made via this app —
     *  documented, honest scope; filters per BRC-100 any/all) */
    private fun listActions(argsJson: String, store: WalletStore?): JSONObject {
        val s = unlockedStore(store)
        val args = try { JSONObject(argsJson) } catch (e: Exception) { JSONObject() }
        var actions = s.brc100Actions()
        val labelsArr = args.optJSONArray("labels")
        if (labelsArr != null && labelsArr.length() > 0) {
            val wanted = HashSet<String>()
            for (i in 0 until labelsArr.length()) wanted.add(labelsArr.optString(i).lowercase())
            val mode = args.optString("labelQueryMode").ifEmpty { "any" }
            if (mode != "any" && mode != "all") {
                throw Err("WERR_INVALID_PARAMETER", 3,
                    "listActions: labelQueryMode must be \"any\" or \"all\".")
            }
            actions = actions.filter { rec ->
                val have = rec.labels.toSet()
                if (mode == "all") have.containsAll(wanted) else wanted.any { have.contains(it) }
            }
        }
        val limit = (args.optInt("limit", 10)).coerceIn(1, 10000)
        val offset = maxOf(args.optInt("offset", 0), 0)
        val page = actions.drop(offset).take(limit)
        val out = JSONArray()
        for (rec in page) {
            out.put(JSONObject()
                .put("txid", rec.txid)
                .put("satoshis", rec.satoshis)
                .put("status", rec.status)
                .put("isOutgoing", rec.isOutgoing)
                .put("description", rec.description)
                .put("labels", JSONArray(rec.labels))
                .put("version", 1)
                .put("lockTime", 0))
        }
        return JSONObject().put("totalActions", actions.size).put("actions", out)
    }

    /** listOutputs over the live, ordinal-protected UTXO set ('default'
     *  basket) — foreign baskets/tags refuse explicitly in the engine */
    private suspend fun listOutputs(argsJson: String, store: WalletStore?): JSONObject {
        val s = unlockedStore(store)
        val utxos = s.utxos()
        val v = requireValid(s.engine.dict("brc100ListOutputs", s.engine.args(
            "utxos" to utxos.toString(), "argsJson" to argsJson)))
        return JSONObject()
            .put("totalOutputs", v.optInt("totalOutputs", 0))
            .put("outputs", v.optJSONArray("outputs") ?: JSONArray())
    }

    /** relinquishOutput: release an existing outpoint from the 'default'
     *  basket — persistently excluded from funding; unknown outpoints refuse */
    private suspend fun relinquishOutput(argsJson: String, store: WalletStore?): JSONObject {
        val s = unlockedStore(store)
        val args = try { JSONObject(argsJson) } catch (e: Exception) { JSONObject() }
        val basket = args.optString("basket").ifEmpty { "default" }
        if (basket != "default") {
            throw Err("WERR_INVALID_PARAMETER", 3,
                "relinquishOutput: basket \"$basket\" is not tracked by this wallet — only \"default\" exists.")
        }
        val outpoint = args.optString("output").lowercase()
        if (!Regex("^[0-9a-f]{64}\\.\\d+$").matches(outpoint)) {
            throw Err("WERR_INVALID_PARAMETER", 3,
                "relinquishOutput: output must be an outpoint like \"txid.vout\".")
        }
        val utxos = s.utxos()
        var known = false
        for (i in 0 until utxos.length()) {
            val u = utxos.optJSONObject(i) ?: continue
            if ("${u.optString("txid")}.${u.optInt("vout", -1)}" == outpoint) { known = true; break }
        }
        if (!known) {
            throw Err("WERR_INVALID_PARAMETER", 3,
                "relinquishOutput: outpoint $outpoint is not a spendable output of this wallet.")
        }
        s.brc100Relinquish(outpoint)
        return JSONObject().put("relinquished", true)
    }
}
