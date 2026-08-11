package io.ordnet.wallet.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * Network layer — the same three services the extension talks to:
 * WhatsOnChain (chain data + broadcast), bsvmap.io (ORDnet V30 indexer +
 * marketplace) and domains.ordnet.io (the ORDnet v2 domain registry).
 */
object Api {
    const val wocBase = "https://api.whatsonchain.com/v1/bsv/main"
    const val holdingsBase = "https://bsvmap.io/api"
    // v3.0 — domain management + resolver on the ORDnet v2 platform,
    // main domain since the cutover (one constant, one switch)
    const val namesBase = "https://domains.ordnet.io"
    // v3.1 — OpNS index (bare names, tree 0). Endpoints verified against the
    // live API on 03-08-2026: /names?q= (search, default match=exact,
    // fallback:true = prefix fallback), /name/<name>, /owner/<address>.
    const val opnsBase = "https://search.ordnet.io/api/opns"
    // v3.1 — SNS resolver (signed answers, resolver v1.3). Endpoints verified
    // live on 03-08-2026: /resolve/<name|mailbox@name>, /pubkey, /health.
    const val snsBase = "https://sns.ordnet.io"
    // v3.2 — ORD/ner file index (1Sat/GorillaPool, same source as ord-app v42).
    // Endpoint verified live 03-08-2026: /api/txos/address/<addr>/unspent
    const val ordnerBase = "https://ordinals.gorillapool.io/api"

    class ApiException(val code: Int, message: String) : Exception(message) {
        companion object {
            fun http(code: Int, body: String): ApiException {
                val trimmed = body.trim()
                return ApiException(code, if (trimmed.isEmpty()) "HTTP $code" else trimmed)
            }
            fun unreachable(what: String) = ApiException(0, "$what is unreachable — check your connection.")
            fun rateLimited() = ApiException(429, "Rate-limited by WhatsOnChain (429) — wait a few seconds and try again.")
        }
    }

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(25, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .writeTimeout(25, TimeUnit.SECONDS)
        .build()

    private val jsonMedia = "application/json; charset=utf-8".toMediaType()

    // MARK: - primitives

    suspend fun get(url: String): Pair<Int, String> = withContext(Dispatchers.IO) {
        val req = Request.Builder().url(url).get()
            .header("Cache-Control", "no-cache")
            .build()
        client.newCall(req).execute().use { resp ->
            Pair(resp.code, resp.body?.string() ?: "")
        }
    }

    suspend fun postJSON(url: String, body: JSONObject): Pair<Int, String> = withContext(Dispatchers.IO) {
        val req = Request.Builder().url(url)
            .post(body.toString().toRequestBody(jsonMedia))
            .build()
        client.newCall(req).execute().use { resp ->
            Pair(resp.code, resp.body?.string() ?: "")
        }
    }

    fun json(data: String): JSONObject? =
        try { JSONTokener(data).nextValue() as? JSONObject } catch (e: Exception) { null }

    fun jsonArray(data: String): JSONArray? =
        try { JSONTokener(data).nextValue() as? JSONArray } catch (e: Exception) { null }

    fun enc(s: String): String = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    // MARK: - WhatsOnChain

    /**
     * port of fetchUnspent(): confirmed endpoint first, plain unspent fallback.
     * 429-aware with backoff (like txHex) and NEVER silently returns an empty
     * list on network failure — an unreachable/rate-limited API must surface as
     * an honest error, not as "No spendable UTXOs" while the balance is fine.
     */
    suspend fun rawUnspent(address: String): JSONArray {
        var sawRateLimit = false
        var delayMs = 500L
        repeat(4) { attempt ->
            var reachable = false
            for (path in listOf("/address/$address/confirmed/unspent", "/address/$address/unspent")) {
                try {
                    val (code, data) = get(wocBase + path)
                    if (code == 429) { sawRateLimit = true; continue }
                    if (code != 200) continue
                    var list: JSONArray = jsonArray(data) ?: JSONArray()
                    if (list.length() == 0) {
                        val obj = json(data)
                        val inner = obj?.optJSONArray("result")
                        if (inner != null) list = inner
                    }
                    reachable = true
                    val filtered = JSONArray()
                    for (i in 0 until list.length()) {
                        val u = list.optJSONObject(i) ?: continue
                        val txHash = u.optString("tx_hash", "")
                        if (txHash.isEmpty()) continue
                        if (u.optBoolean("isSpentInMempoolTx", false)) continue
                        filtered.put(u)
                    }
                    if (filtered.length() > 0) return filtered
                } catch (e: Exception) {
                    // try next endpoint / next attempt
                }
            }
            // both endpoints answered but the wallet is genuinely empty
            if (reachable) return JSONArray()
            if (attempt < 3) { delay(delayMs); delayMs *= 2 }
        }
        throw if (sawRateLimit) ApiException.rateLimited() else ApiException.unreachable("WhatsOnChain")
    }

    suspend fun balance(address: String): Balance {
        val (code, data) = get("$wocBase/address/$address/balance")
        val j = if (code == 200) json(data) else null
        j ?: throw ApiException.unreachable("WhatsOnChain")
        return Balance(
            confirmed = j.optLong("confirmed", 0),
            unconfirmed = j.optLong("unconfirmed", 0)
        )
    }

    suspend fun history(address: String): List<HistoryTx> {
        val (code, data) = get("$wocBase/address/$address/history")
        val arr = if (code == 200) jsonArray(data) else null
        arr ?: throw ApiException.unreachable("WhatsOnChain")
        val txs = ArrayList<HistoryTx>()
        for (i in 0 until arr.length()) {
            val d = arr.optJSONObject(i) ?: continue
            val h = d.optString("tx_hash", "")
            if (h.isEmpty()) continue
            txs.add(HistoryTx(txHash = h, height = d.optInt("height", 0)))
        }
        // newest first; pending (height<=0) on top
        txs.sortByDescending { if (it.height > 0) it.height.toLong() else 1_000_000_000_000L }
        return txs
    }

    /**
     * Raw tx-hex with in-memory cache + 429 retry/backoff (500ms → 1s → 2s → 4s),
     * exactly like the extension's fetchTxHexRetry().
     */
    private val txHexCache = HashMap<String, String>()
    private val cacheLock = Any()

    private fun cachedTxHex(txid: String): String? = synchronized(cacheLock) { txHexCache[txid] }
    private fun storeTxHex(txid: String, hex: String) = synchronized(cacheLock) { txHexCache[txid] = hex }

    suspend fun txHex(txid: String): String {
        cachedTxHex(txid)?.let { return it }

        var delayMs = 500L
        repeat(5) {
            val (code, data) = get("$wocBase/tx/$txid/hex")
            if (code == 200) {
                val hex = data.trim()
                if (hex.isNotEmpty()) {
                    storeTxHex(txid, hex)
                    return hex
                }
            }
            if (code != 429) throw ApiException.http(code,
                if (code == 200) "Empty transaction response from WhatsOnChain."
                else "Could not fetch the transaction. (HTTP $code)")
            delay(delayMs)
            delayMs *= 2
        }
        throw ApiException.rateLimited()
    }

    suspend fun broadcast(rawtx: String): String {
        val (code, data) = postJSON("$wocBase/tx/raw", JSONObject().put("txhex", rawtx))
        if (code != 200) throw ApiException.http(code, data)
        return data.replace("\"", "").trim()
    }

    suspend fun exchangeRate(): Double? {
        return try {
            val (code, data) = get("$wocBase/exchangerate")
            if (code != 200) return null
            val j = json(data) ?: return null
            when (val r = j.opt("rate")) {
                is Double -> r
                is Int -> r.toDouble()
                is Long -> r.toDouble()
                is String -> r.toDoubleOrNull()
                else -> null
            }
        } catch (e: Exception) { null }
    }

    // MARK: - bsvmap.io (holdings indexer + marketplace)

    suspend fun holdings(address: String): List<Holding> {
        val (code, data) = get("$holdingsBase/address/$address/holdings")
        val j = if (code == 200) json(data) else null
        j ?: throw ApiException.unreachable("the ORDnet indexer at bsvmap.io")
        val out = ArrayList<Holding>()
        val sns = j.optJSONArray("sns") ?: JSONArray()
        for (i in 0 until sns.length()) {
            sns.optJSONObject(i)?.let { d -> Holding.from(d, HoldingKind.SNS)?.let { out.add(it) } }
        }
        val maps = j.optJSONArray("bsvmaps") ?: JSONArray()
        for (i in 0 until maps.length()) {
            maps.optJSONObject(i)?.let { d -> Holding.from(d, HoldingKind.BSVMAP)?.let { out.add(it) } }
        }
        return out
    }

    /**
     * global listings registry — merged into holdings like mergeListings().
     * Returns null when the registry is UNREACHABLE (network error / non-200 /
     * unparseable), so callers can tell "registry down" apart from "registry
     * empty" — conflating the two made every successful listing look failed.
     */
    suspend fun listings(): List<JSONObject>? {
        return try {
            val (code, data) = get("$holdingsBase/listings")
            if (code != 200) return null
            val j = json(data) ?: return null
            val arr = j.optJSONArray("listings") ?: return null
            val out = ArrayList<JSONObject>(arr.length())
            for (i in 0 until arr.length()) arr.optJSONObject(i)?.let { out.add(it) }
            out
        } catch (e: Exception) { null }
    }

    suspend fun districtState(district: Int): JSONObject? {
        return try {
            val (code, data) = get("$holdingsBase/map/$district")
            if (code != 200) null else json(data)
        } catch (e: Exception) { null }
    }

    suspend fun postList(district: Int, body: JSONObject) {
        val (code, data) = postJSON("$holdingsBase/map/$district/list", body)
        if (code != 200) {
            val j = json(data)
            throw ApiException.http(code, j?.optString("error")?.ifEmpty { null } ?: "listing failed")
        }
    }

    suspend fun postDelist(district: Int, body: JSONObject) {
        val (code, data) = postJSON("$holdingsBase/map/$district/delist", body)
        if (code != 200 || json(data) == null) {
            throw ApiException.http(code, json(data)?.optString("error")?.ifEmpty { null }
                ?: "delist endpoint unavailable ($code)")
        }
    }

    // MARK: - chain info (BRC-100 fase 1)

    /**
     * current block height — GET /chain/info, field "blocks"
     * (endpoint + field verified against the WhatsOnChain docs 04-08-2026)
     */
    suspend fun chainHeight(): Int {
        val (code, data) = get("$wocBase/chain/info")
        val j = if (code == 200) json(data) else null
        val h = j?.opt("blocks")
        return when (h) {
            is Int -> h
            is Long -> h.toInt()
            is Double -> h.toInt()
            else -> throw ApiException.unreachable("WhatsOnChain")
        }
    }

    // MARK: - ORD/ner (1Sat index — inscriptions an address currently holds)

    /**
     * all unspent inscription outpoints on an address, paged (100 per call,
     * max 500 like a sane cap). Same filter as ord-app v42: only items with
     * origin.data.insc. Throws on failure so ORD/ner can degrade inline.
     */
    suspend fun ordnerFiles(address: String): List<OrdnerFile> {
        val out = ArrayList<OrdnerFile>()
        var offset = 0
        for (page in 0 until 5) {
            val (code, data) = get("$ordnerBase/txos/address/$address/unspent?limit=100&offset=$offset")
            val arr = if (code == 200) jsonArray(data) else null
            arr ?: throw ApiException.unreachable("the 1Sat index at ordinals.gorillapool.io")
            for (i in 0 until arr.length()) {
                val item = arr.optJSONObject(i) ?: continue
                val origin = item.optJSONObject("origin") ?: continue
                val odata = origin.optJSONObject("data") ?: continue
                val insc = odata.optJSONObject("insc") ?: continue
                val oOut = origin.optString("outpoint", "").split("_")
                val cOut = item.optString("outpoint", "").split("_")
                if (oOut.isEmpty() || oOut[0].isEmpty() || cOut.isEmpty() || cOut[0].isEmpty()) continue
                val file = insc.optJSONObject("file")
                out.add(OrdnerFile(
                    originTxid = oOut[0],
                    originVout = if (oOut.size > 1) oOut[1].toIntOrNull() ?: 0 else 0,
                    currentTxid = cOut[0],
                    currentVout = if (cOut.size > 1) cOut[1].toIntOrNull() ?: 0 else 0,
                    contentType = file?.optString("type")?.ifEmpty { null } ?: "unknown",
                    size = file?.optInt("size", 0) ?: 0,
                    height = if (item.has("height") && !item.isNull("height")) item.optInt("height") else null
                ))
            }
            if (arr.length() < 100) break
            offset += 100
        }
        return out
    }

    // MARK: - OpNS index (search.ordnet.io/api/opns)

    /**
     * all OpNS names on an address — portfolio view, third holdings category.
     * Throws on failure so the caller can degrade WITHOUT touching SNS/BSVmaps.
     */
    suspend fun opnsHoldings(address: String): List<Holding> {
        val (code, data) = get("$opnsBase/owner/$address")
        val j = if (code == 200) json(data) else null
        if (j == null || !j.optBoolean("ok", false)) {
            throw ApiException.unreachable("the OpNS index at search.ordnet.io")
        }
        val arr = j.optJSONArray("results") ?: JSONArray()
        val out = ArrayList<Holding>()
        for (i in 0 until arr.length()) {
            arr.optJSONObject(i)?.let { d -> Holding.fromOpns(d)?.let { out.add(it) } }
        }
        return out
    }

    data class OpnsLookup(val fallback: Boolean, val records: List<OpnsRecord>)

    /**
     * name lookup via /names?q= — the API defaults to match=exact and falls
     * back to prefix with `fallback: true`. The fallback flag is passed
     * through UNTOUCHED: a fallback answer is a DIFFERENT name than the user
     * typed and must never be paid silently.
     */
    suspend fun opnsLookup(name: String): OpnsLookup {
        val (code, data) = get("$opnsBase/names?q=${enc(name)}")
        val j = if (code == 200) json(data) else null
        if (j == null || !j.optBoolean("ok", false)) {
            throw ApiException.unreachable("the OpNS index at search.ordnet.io")
        }
        val fallback = if (j.has("fallback") && !j.isNull("fallback")) j.optBoolean("fallback", false)
            else j.optString("match") != "exact"
        val arr = j.optJSONArray("results") ?: JSONArray()
        val records = ArrayList<OpnsRecord>()
        for (i in 0 until arr.length()) {
            arr.optJSONObject(i)?.let { d -> OpnsRecord.from(d)?.let { records.add(it) } }
        }
        return OpnsLookup(fallback, records)
    }

    /**
     * v3.1.2 — is this exact outpoint SPENT on chain? Dedicated WhatsOnChain
     * endpoint GET /tx/<txid>/<vout>/spent (iOS v2.2.3 parity):
     *   200 = SPENT (body carries the spending txid)
     *   404 = UNSPENT — this is the SUCCESS outcome, NOT an error
     *   timeout / 5xx / network error = UNKNOWN (null) — must never be
     *   reported as "spent"; 429 gets a short backoff (3 attempts,
     *   400 ms → doubling), then still UNKNOWN.
     * The old address-unspent-list check is gone ENTIRELY: WhatsOnChain
     * silently truncates that list on busy addresses (fee addresses, custody,
     * marketplaces), so absence in the list proved nothing and produced false
     * stale_outpoint refusals — live-proven with start.web3 on 03-08-2026
     * (holder = the busy ORDnet fee address 1EXupec…vLv8).
     */
    suspend fun outpointSpent(txid: String, vout: Int): Boolean? {
        var delayMs = 400L
        repeat(3) {
            val (code, _) = try {
                get("$wocBase/tx/$txid/$vout/spent")
            } catch (e: Exception) {
                return null                      // network error → unknown
            }
            if (code == 200) return true         // provably spent
            if (code == 404) return false        // provably unspent — success
            if (code != 429) return null         // 5xx etc. → unknown
            delay(delayMs)
            delayMs *= 2
        }
        return null
    }

    // MARK: - SNS resolver (sns.ordnet.io)

    /**
     * raw resolver answer — the BODY STRING goes to the JS engine untouched
     * so the signature is verified over exactly what the server sent.
     * Error answers (not_verified, no_holder, …) also arrive as JSON here.
     */
    suspend fun snsResolveRaw(input: String): Pair<Int, String> {
        val (code, body) = get("$snsBase/resolve/${enc(input)}")
        if (body.isEmpty()) throw ApiException.unreachable("the SNS resolver at sns.ordnet.io")
        return Pair(code, body)
    }

    /**
     * current key + chain of succession deeds (GET /pubkey) — used ONLY when
     * an answer carries an unknown signer; the engine proves the chain.
     */
    suspend fun snsPubkeyInfo(): JSONObject {
        val (code, data) = get("$snsBase/pubkey")
        val j = if (code == 200) json(data) else null
        return j ?: throw ApiException.unreachable("the SNS resolver at sns.ordnet.io")
    }

    // MARK: - domains.ordnet.io (ORDnet v2 registry)

    suspend fun resolve(name: String): String {
        val (code, data) = get("$namesBase/resolve?name=${enc(name)}")
        val j = if (code == 200) json(data) else null
        val txid = j?.optString("txid") ?: ""
        if (txid.isEmpty()) throw ApiException.http(code, "Domain not found: $name")
        return txid.lowercase()
    }

    suspend fun myDomains(address: String): List<MyDomain> {
        val (code, data) = get("$namesBase/api/owner/$address")
        val j = if (code == 200) json(data) else null
        j ?: throw ApiException.unreachable("the ORDnet registry")
        val arr = j.optJSONArray("domains") ?: JSONArray()
        val out = ArrayList<MyDomain>()
        for (i in 0 until arr.length()) {
            val d = arr.optJSONObject(i) ?: continue
            val n = d.optString("name", "")
            if (n.isEmpty()) continue
            val price: Double? = when (val p = d.opt("listing_price")) {
                is Double -> p
                is Int -> p.toDouble()
                is Long -> p.toDouble()
                is String -> p.toDoubleOrNull()
                else -> null
            }
            out.add(MyDomain(
                name = n,
                status = d.optString("status", "claimed").ifEmpty { "claimed" },
                listingStatus = if (d.has("listing_status") && !d.isNull("listing_status"))
                    d.optString("listing_status") else null,
                listingPrice = price
            ))
        }
        return out
    }

    suspend fun whois(name: String): DomainWhois {
        val (code, data) = get("$namesBase/whois/${enc(name)}")
        val j = if (code == 200) json(data) else null
        j ?: throw ApiException.http(code, "Could not load domain details.")
        var txid: String? = null
        var vout: Int? = null
        when (val t = j.opt("target")) {
            is JSONObject -> {
                txid = t.optString("txid").ifEmpty { null }
                if (t.has("vout") && !t.isNull("vout")) vout = t.optInt("vout")
            }
            is String -> if (t.isNotEmpty()) txid = t
        }
        val registered = if (j.has("registered_at") && !j.isNull("registered_at"))
            j.optString("registered_at").take(10) else null
        return DomainWhois(
            status = j.optString("status", "—").ifEmpty { "—" },
            owner = j.optString("owner", "—").ifEmpty { "—" },
            targetTxid = txid,
            targetVout = vout,
            registeredAt = registered
        )
    }

    data class DomainRecords(
        val subs: List<DomainRecord>,
        val routes: List<DomainRecord>,
        val listing: DomainListing?
    )

    suspend fun domainRecords(name: String): DomainRecords {
        val (code, data) = get("$namesBase/api/domain/${enc(name)}/records")
        val j = if (code == 200) json(data) else null
        j ?: throw ApiException.http(code, "Could not load records.")
        val subs = ArrayList<DomainRecord>()
        val subsArr = j.optJSONArray("subdomains") ?: JSONArray()
        for (i in 0 until subsArr.length()) {
            val d = subsArr.optJSONObject(i) ?: continue
            val s = d.optString("subdomain", "")
            if (s.isEmpty()) continue
            subs.add(DomainRecord(subdomain = s, path = null, txid = d.optString("txid", "")))
        }
        val routes = ArrayList<DomainRecord>()
        val routesArr = j.optJSONArray("routes") ?: JSONArray()
        for (i in 0 until routesArr.length()) {
            val d = routesArr.optJSONObject(i) ?: continue
            val p = d.optString("path", "")
            if (p.isEmpty()) continue
            val sub = if (d.has("subdomain") && !d.isNull("subdomain"))
                d.optString("subdomain").ifEmpty { null } else null
            routes.add(DomainRecord(subdomain = sub, path = p, txid = d.optString("txid", "")))
        }
        var listing: DomainListing? = null
        val l = j.optJSONObject("listing")
        if (l != null) {
            val price: Double = when (val p = l.opt("price_usd")) {
                is Double -> p
                is Int -> p.toDouble()
                is Long -> p.toDouble()
                is String -> p.toDoubleOrNull() ?: 0.0
                else -> 0.0
            }
            listing = DomainListing(priceUsd = price)
        }
        return DomainRecords(subs, routes, listing)
    }

    /**
     * port of walletPost(): signed registry action. `auth` comes from the engine's
     * signAction and already contains ts/address/signature/pubkey.
     */
    suspend fun walletPost(pathname: String, body: JSONObject, auth: JSONObject): JSONObject {
        val merged = JSONObject(body.toString())
        merged.put("ts", auth.opt("ts"))
        merged.put("address", auth.opt("address"))
        merged.put("signature", auth.opt("signature"))
        merged.put("pubkey", auth.opt("pubkey"))
        val (code, data) = postJSON(namesBase + pathname, merged)
        val j = json(data) ?: JSONObject()
        if (code != 200 || (j.has("error") && !j.isNull("error"))) {
            throw ApiException.http(code, j.optString("error").ifEmpty { "HTTP $code" })
        }
        return j
    }
}
