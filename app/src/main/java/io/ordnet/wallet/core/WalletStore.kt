package io.ordnet.wallet.core

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.fragment.app.FragmentActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * Central wallet state — the Android counterpart of the extension's in-memory
 * state (_accounts/_active/_wif/_address) plus all wallet operations; a 1-on-1
 * port of the iOS WalletStore. Keys are held in memory ONLY while unlocked;
 * the persistent copy lives in the hardware-encrypted vault (biometric gated).
 */
class WalletStore(context: Context) {

    enum class Phase { LOADING, SETUP, LOCKED, UNLOCKED }

    private val appContext = context.applicationContext
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    var phase by mutableStateOf(Phase.LOADING)
        private set
    var accounts by mutableStateOf<List<Account>>(emptyList())
        private set
    var active by mutableStateOf(0)
        private set
    var balance by mutableStateOf<Balance?>(null)
        private set
    var usdRate by mutableStateOf<Double?>(null)
        private set
    var holdings by mutableStateOf<List<Holding>>(emptyList())
        private set
    var indexerOk by mutableStateOf(true)
        private set
    /**
     * OpNS index reachable? Kept SEPARATE from indexerOk so a broken OpNS API
     * degrades only the OpNS tab — SNS/BSVmaps stay exactly as they were.
     */
    var opnsOk by mutableStateOf(true)
        private set
    var addressBook by mutableStateOf<List<BookEntry>>(emptyList())
        private set
    /** session-only, like chrome.storage.session */
    var connectedSites by mutableStateOf<Map<String, Boolean>>(emptyMap())
        private set
    var pendingProviderRequest by mutableStateOf<ProviderRequest?>(null)
    /** v3.2 — cross-tab request: ORD/ner asks the Browser tab to open a TXID */
    var browserOpenRequest by mutableStateOf<String?>(null)
    /** v3.3 — cross-tab request: Wallet asks the Domains tab to open a domain
     *  detail ("Manage domain listing" on a domain-listed SNS name) */
    var domainsOpenRequest by mutableStateOf<String?>(null)
    /** v3.2 — BRC-100 permission prompt (native sheet + biometrics) */
    var pendingBrc100Permission by mutableStateOf<Brc100PermissionRequest?>(null)
    /** v3.2 — BRC-100 per-transaction confirmation (money ≠ grant: never persisted) */
    var pendingBrc100TxConfirm by mutableStateOf<Brc100TxConfirmRequest?>(null)

    /**
     * recovery phrases entered/created THIS session, keyed by address —
     * memory only, never persisted (only the WIF is stored). Port of _sessionPhrases.
     */
    val sessionPhrases = HashMap<String, String>()

    private var lastBackgrounded: Long? = null

    val engine: WalletEngine get() = WalletEngine.shared

    private val prefs: SharedPreferences =
        appContext.getSharedPreferences("ordplug_prefs", Context.MODE_PRIVATE)

    companion object {
        const val AUTOLOCK_KEY = "ordplug_autolock_min"
        const val ADDRESSBOOK_KEY = "ordplug_addressbook"
        const val INSCRIPTIONS_KEY = "ordnet_inscriptions_v1"
        /**
         * resolver key management: pre-pinned key (resolver v1.3, verified live
         * 03-08-2026); a proven succession chain may move the pin — nothing else.
         */
        const val SNS_PRE_PINNED_PUBKEY = "03088f1da3bfc998c1bc7bbc1ffcb7d96c47e094624a52d78406f8c3105b0d0b46"
        const val SNS_PIN_KEY = "ordplug_sns_pinned_pubkey"
        // v3.2 — chain mechanism + BRC-100 storage keys (parity with iOS)
        const val CHAIN_TIPS_KEY = "ordplug_chain_tips_v1"
        const val SPENT_GUARD_KEY = "ordplug_spent_guard_v1"
        const val BRC100_GRANTS_KEY = "ordplug_brc100_grants_v1"
        const val BRC100_ACTIONS_KEY = "ordplug_brc100_actions_v1"
        const val BRC100_RELINQUISHED_KEY = "ordplug_brc100_relinquished_v1"
    }

    val snsPinnedPubkey: String
        get() = prefs.getString(SNS_PIN_KEY, null) ?: SNS_PRE_PINNED_PUBKEY

    val activeAccount: Account?
        get() = accounts.getOrNull(active)
    val address: String get() = activeAccount?.address ?: ""
    val wif: String get() = activeAccount?.wif ?: ""

    var autolockMinutes: Int
        get() = prefs.getInt(AUTOLOCK_KEY, 15)
        set(value) { prefs.edit().putInt(AUTOLOCK_KEY, value).apply() }

    init {
        phase = if (Vault.vaultExists(appContext)) Phase.LOCKED else Phase.SETUP
        loadAddressBook()
        loadChainState()
    }

    // MARK: - chain mechanism (v3.2) — consecutive TXs without waiting
    //
    // After every successful broadcast the wallet registers its own change /
    // split outputs as immediately-spendable "chain tips" and puts the inputs
    // it just spent in a spent-guard. utxos() then serves: WoC list minus the
    // guard, plus the tips WoC doesn't know yet. Result: Send, Inscribe,
    // ordinal transfers and the UTXO tools can run back-to-back without
    // "no spendable UTXOs". 1-sat outputs are NEVER tips (ordinal protection
    // lives in txSpendInfo and shapeUtxos alike).

    data class ChainTip(val txid: String, val vout: Int, val satoshis: Long)

    private var chainTips = HashMap<String, MutableList<ChainTip>>()
    private var spentGuard = HashMap<String, MutableSet<String>>()

    private fun loadChainState() {
        try {
            prefs.getString(CHAIN_TIPS_KEY, null)?.let { raw ->
                val dict = JSONObject(raw)
                val map = HashMap<String, MutableList<ChainTip>>()
                for (key in dict.keys()) {
                    val arr = dict.optJSONArray(key) ?: continue
                    val list = ArrayList<ChainTip>()
                    for (i in 0 until arr.length()) {
                        val o = arr.optJSONObject(i) ?: continue
                        val txid = o.optString("txid")
                        if (txid.isEmpty()) continue
                        list.add(ChainTip(txid, o.optInt("vout", 0), o.optLong("satoshis", 0)))
                    }
                    map[key] = list
                }
                chainTips = map
            }
            prefs.getString(SPENT_GUARD_KEY, null)?.let { raw ->
                val dict = JSONObject(raw)
                val map = HashMap<String, MutableSet<String>>()
                for (key in dict.keys()) {
                    val arr = dict.optJSONArray(key) ?: continue
                    val set = HashSet<String>()
                    for (i in 0 until arr.length()) set.add(arr.optString(i))
                    map[key] = set
                }
                spentGuard = map
            }
        } catch (e: Exception) { }
    }

    private fun saveChainState() {
        try {
            val tipsDict = JSONObject()
            for ((k, v) in chainTips) {
                val arr = JSONArray()
                for (t in v) arr.put(JSONObject().put("txid", t.txid).put("vout", t.vout).put("satoshis", t.satoshis))
                tipsDict.put(k, arr)
            }
            val guardDict = JSONObject()
            for ((k, v) in spentGuard) guardDict.put(k, JSONArray(v.toList()))
            prefs.edit()
                .putString(CHAIN_TIPS_KEY, tipsDict.toString())
                .putString(SPENT_GUARD_KEY, guardDict.toString())
                .apply()
        } catch (e: Exception) { }
    }

    /**
     * on unlock / account switch: drop tips that are provably spent (the
     * direct spent-endpoint; unknown keeps the tip — it fails fast on
     * conflict anyway) and keep the guard bounded.
     */
    suspend fun validateChainTips() {
        val addr = address
        if (addr.isEmpty()) return
        val tips = chainTips[addr] ?: mutableListOf()
        if (tips.isNotEmpty()) {
            val keep = ArrayList<ChainTip>()
            for (t in tips) {
                if (Api.outpointSpent(t.txid, t.vout) == true) continue
                keep.add(t)
            }
            chainTips[addr] = keep
        }
        if ((spentGuard[addr]?.size ?: 0) > 300) spentGuard[addr] = HashSet()
        saveChainState()
    }

    /** bookkeeping after a successful broadcast of OUR OWN tx */
    private suspend fun registerBroadcast(rawtx: String) {
        val info = try {
            engine.dict("txSpendInfo", engine.args("rawtx" to rawtx, "address" to address))
        } catch (e: Exception) { return }
        val g = spentGuard.getOrPut(address) { HashSet() }
        val inputs = info.optJSONArray("inputs") ?: JSONArray()
        for (i in 0 until inputs.length()) {
            val o = inputs.optJSONObject(i) ?: continue
            g.add("${o.optString("txid")}:${o.optInt("vout")}")
        }
        val tips = chainTips.getOrPut(address) { ArrayList() }
        tips.removeAll { g.contains("${it.txid}:${it.vout}") }
        val own = info.optJSONArray("ownOutputs") ?: JSONArray()
        for (i in 0 until own.length()) {
            val o = own.optJSONObject(i) ?: continue
            val txid = o.optString("txid")
            if (txid.isEmpty()) continue
            tips.add(ChainTip(txid, o.optInt("vout", 0), o.optLong("satoshis", 0)))
        }
        saveChainState()
    }

    /**
     * broadcast + chain bookkeeping. On a mempool-conflict the local picture
     * was stale: guard the attempted inputs, drop the tips and ask (inline)
     * for one retry on a fresh set.
     */
    suspend fun broadcastAndRegister(rawtx: String): String {
        try {
            val txid = Api.broadcast(rawtx)
            registerBroadcast(rawtx)
            return txid
        } catch (e: Exception) {
            val m = (e.message ?: "").lowercase()
            if (m.contains("conflict") || m.contains("missing inputs") || m.contains("mempool")) {
                try {
                    val info = engine.dict("txSpendInfo", engine.args("rawtx" to rawtx, "address" to address))
                    val g = spentGuard.getOrPut(address) { HashSet() }
                    val inputs = info.optJSONArray("inputs") ?: JSONArray()
                    for (i in 0 until inputs.length()) {
                        val o = inputs.optJSONObject(i) ?: continue
                        g.add("${o.optString("txid")}:${o.optInt("vout")}")
                    }
                } catch (e2: Exception) { }
                chainTips[address] = ArrayList()
                saveChainState()
                throw WalletEngine.EngineException(
                    (e.message ?: "Broadcast failed.") + " — The wallet dropped its local UTXO chain and will fetch a fresh set. Try again.")
            }
            throw e
        }
    }

    // MARK: - vault lifecycle

    private fun payloadData(): ByteArray = VaultPayload.encode(accounts, active)

    fun saveAccounts() {
        if (phase != Phase.UNLOCKED) throw Vault.VaultException("Wallet is locked.")
        Vault.saveVault(appContext, payloadData())
    }

    private suspend fun apply(stored: List<JSONObject>, activeIdx: Int) {
        val list = ArrayList<Account>(stored.size)
        for (a in stored) {
            val wif = a.optString("wif")
            list.add(Account(
                name = a.optString("name"),
                wif = wif,
                origin = a.optString("origin").ifEmpty { "wif" },
                path = if (a.has("path") && !a.isNull("path")) a.optString("path") else null,
                address = engine.wifToAddress(wif)
            ))
        }
        accounts = list
        active = minOf(activeIdx, maxOf(0, list.size - 1))
    }

    suspend fun unlock(activity: FragmentActivity) {
        Vault.authenticate(activity, "Unlock your ORD/net wallet")
        val data = withContext(Dispatchers.IO) { Vault.readVault(appContext) }
        val (stored, activeIdx) = VaultPayload.decode(data)
        apply(stored, activeIdx)
        phase = Phase.UNLOCKED
        lastBackgrounded = null
        loadInscriptions()
        refreshBalance()
        loadHoldings()
        validateChainTips()
    }

    fun lock() {
        accounts = emptyList()
        active = 0
        balance = null
        holdings = emptyList()
        sessionPhrases.clear()
        pendingProviderRequest = null
        // v3.2: a locked wallet answers no BRC-100 question — resolve any
        // pending prompt as denied so the page gets its rejection, then clear
        pendingBrc100Permission?.deferred?.complete(false)
        pendingBrc100Permission = null
        pendingBrc100TxConfirm?.deferred?.complete(false)
        pendingBrc100TxConfirm = null
        scope.launch { engine.brc100Reset() }   // wipe BRC-100 key material
        phase = Phase.LOCKED
    }

    fun removeWallet() {
        Vault.deleteVault(appContext)
        accounts = emptyList()
        active = 0
        balance = null
        holdings = emptyList()
        sessionPhrases.clear()
        connectedSites = emptyMap()
        // v3.2 (iOS parity) — wipe app data tied to the removed wallet, nothing
        // stays behind: address book, inscription log, chain state and every
        // BRC-100 store (grants, action log, relinquished outpoints)
        addressBook = emptyList()
        inscriptions = emptyList()
        allInscriptions = HashMap()
        chainTips = HashMap()
        spentGuard = HashMap()
        prefs.edit()
            .remove(ADDRESSBOOK_KEY)
            .remove(INSCRIPTIONS_KEY)
            .remove(CHAIN_TIPS_KEY)
            .remove(SPENT_GUARD_KEY)
            .remove(BRC100_GRANTS_KEY)
            .remove(BRC100_ACTIONS_KEY)
            .remove(BRC100_RELINQUISHED_KEY)
            .apply()
        pendingBrc100Permission?.deferred?.complete(false)
        pendingBrc100Permission = null
        pendingBrc100TxConfirm?.deferred?.complete(false)
        pendingBrc100TxConfirm = null
        scope.launch { engine.brc100Reset() }
        phase = Phase.SETUP
    }

    /** auto-lock bookkeeping driven by the process lifecycle */
    fun sceneBackgrounded() {
        if (phase == Phase.UNLOCKED) lastBackgrounded = System.currentTimeMillis()
    }

    fun sceneActivated() {
        if (phase != Phase.UNLOCKED) return
        val t = lastBackgrounded ?: return
        lastBackgrounded = null
        val mins = autolockMinutes
        if (mins > 0 && System.currentTimeMillis() - t > mins * 60_000L) lock()
    }

    // MARK: - create / import

    suspend fun createWallet(mnemonic: String, accountName: String) {
        if (!engine.validateMnemonic(mnemonic)) {
            throw WalletEngine.EngineException("Recovery phrase missing — go back and try again.")
        }
        val wif = engine.wifFromMnemonic(mnemonic, ImportMode.BIP44)
        val addr = engine.wifToAddress(wif)
        accounts = listOf(Account(
            name = accountName.ifEmpty { "Account 1" },
            wif = wif, origin = "bip44", path = Fees.BIP44_PATH, address = addr
        ))
        sessionPhrases[addr] = mnemonic
        active = 0
        phase = Phase.UNLOCKED
        saveAccounts()
    }

    data class ImportResult(
        val wif: String,
        val origin: String,
        val path: String?,
        val phrase: String?
    )

    /** port of wifFromImportInputs + otherWalletResolve */
    suspend fun resolveImport(
        mode: ImportMode, mnemonic: String, wifInput: String,
        presetPath: String? = null, pin: String = ""
    ): ImportResult {
        when (mode) {
            ImportMode.WIF -> {
                val w = wifInput.trim()
                if (w.isEmpty()) throw WalletEngine.EngineException("Enter a private key (WIF).")
                engine.wifToAddress(w) // validates
                return ImportResult(wif = w, origin = "wif", path = null, phrase = null)
            }
            else -> {
                val m = mnemonic.trim().lowercase()
                if (!engine.validateMnemonic(m)) {
                    throw WalletEngine.EngineException("Invalid recovery phrase.")
                }
                return when (mode) {
                    ImportMode.LEGACY -> ImportResult(
                        wif = engine.wifFromMnemonic(m, ImportMode.LEGACY),
                        origin = "legacy", path = null, phrase = m)
                    ImportMode.PATH -> {
                        val p = presetPath ?: Fees.BIP44_PATH
                        val w = engine.wifFromMnemonic(m, ImportMode.PATH, path = p, pin = pin)
                        ImportResult(wif = w, origin = "bip44", path = p, phrase = m)
                    }
                    else -> ImportResult(
                        wif = engine.wifFromMnemonic(m, ImportMode.BIP44),
                        origin = "bip44", path = Fees.BIP44_PATH, phrase = m)
                }
            }
        }
    }

    suspend fun importWallet(r: ImportResult, accountName: String) {
        val addr = engine.wifToAddress(r.wif)
        accounts = listOf(Account(
            name = accountName.ifEmpty { "Account 1" },
            wif = r.wif, origin = r.origin, path = r.path, address = addr
        ))
        if (r.phrase != null) sessionPhrases[addr] = r.phrase
        active = 0
        phase = Phase.UNLOCKED
        saveAccounts()
    }

    // MARK: - accounts

    /** switch the active account (named selectAccount: `setActive` would clash
     *  with the JVM setter generated for the `active` property) */
    fun selectAccount(i: Int) {
        if (i !in accounts.indices) return
        active = i
        try { saveAccounts() } catch (e: Exception) { }
        loadInscriptions()
        scope.launch {
            refreshBalance()
            loadHoldings()
            validateChainTips()
        }
    }

    suspend fun addAccount(name: String, result: ImportResult?) {
        val r = result ?: ImportResult(wif = engine.randomWif(), origin = "random", path = null, phrase = null)
        val addr = engine.wifToAddress(r.wif)
        if (accounts.any { it.address == addr }) {
            throw WalletEngine.EngineException("That account is already in the wallet.")
        }
        val nm = name.ifEmpty { "Account ${accounts.size + 1}" }
        accounts = accounts + Account(name = nm, wif = r.wif, origin = r.origin, path = r.path, address = addr)
        if (r.phrase != null) sessionPhrases[addr] = r.phrase
        saveAccounts()
    }

    fun renameAccount(i: Int, to: String) {
        if (i !in accounts.indices || to.isEmpty()) return
        accounts = accounts.mapIndexed { idx, a -> if (idx == i) a.copy(name = to) else a }
        try { saveAccounts() } catch (e: Exception) { }
    }

    fun removeAccount(i: Int) {
        if (accounts.size <= 1 || i !in accounts.indices) return
        val list = accounts.toMutableList()
        list.removeAt(i)
        accounts = list
        // keep `active` pointing at the SAME account after the indices shift:
        // removing an account BEFORE the active one moves the active account
        // one index down (the old code forgot this and silently switched the
        // wallet to a different account's keys).
        active = when {
            i < active -> active - 1
            i == active -> maxOf(0, i - 1)
            else -> active
        }.coerceIn(0, list.size - 1)
        try { saveAccounts() } catch (e: Exception) { }
    }

    // MARK: - chain data

    suspend fun refreshBalance() {
        if (address.isEmpty()) return
        balance = try { Api.balance(address) } catch (e: Exception) { null }
        usdRate = Api.exchangeRate()
    }

    /**
     * shaped UTXOs for the active account (ordinal-protected, like getUTXOs).
     * v3.2: minus the spent-guard, plus our own chain tips WoC doesn't list
     * yet — so consecutive transactions never starve for funding.
     */
    suspend fun utxos(): JSONArray {
        val raw = Api.rawUnspent(address)
        val shaped = engine.array("shapeUtxos", engine.args("raw" to raw.toString(), "address" to address))
        val guarded: Set<String> = spentGuard[address] ?: emptySet()
        // BRC-100 relinquishOutput: outpoints the wallet must no longer
        // manage are excluded from funding (persisted per address)
        val relinquished = brc100Relinquished()
        val filtered = JSONArray()
        val listed = HashSet<String>()
        for (i in 0 until shaped.length()) {
            val u = shaped.optJSONObject(i) ?: continue
            val key = "${u.optString("txid")}:${u.optInt("vout", -1)}"
            if (guarded.contains(key)) continue
            if (relinquished.isNotEmpty() &&
                relinquished.contains("${u.optString("txid")}.${u.optInt("vout", -1)}")) continue
            filtered.put(u)
            listed.add(key)
        }
        val freshTips = (chainTips[address] ?: emptyList<ChainTip>()).filter {
            !listed.contains("${it.txid}:${it.vout}") && !guarded.contains("${it.txid}:${it.vout}")
        }
        if (freshTips.isNotEmpty()) {
            // shape the tips through the SAME engine path (incl. ordinal filter)
            val tipRaw = JSONArray()
            for (t in freshTips) {
                tipRaw.put(JSONObject().put("tx_hash", t.txid).put("tx_pos", t.vout).put("value", t.satoshis))
            }
            val tipShaped = engine.array("shapeUtxos", engine.args("raw" to tipRaw.toString(), "address" to address))
            for (i in 0 until tipShaped.length()) filtered.put(tipShaped.optJSONObject(i) ?: continue)
        }
        return filtered
    }

    // MARK: - send / inscribe / dApp tx

    suspend fun sendBSV(to: String, amountSat: Long, dataStr: String? = null, feeSat: Long = 0): String {
        val u = utxos()
        val args = engine.args(
            "wif" to wif, "utxos" to u.toString(), "to" to to,
            "amountSat" to amountSat, "feeSat" to feeSat
        )
        if (dataStr != null) args.put("dataStr", dataStr)
        val r = engine.dict("buildSend", args)
        val rawtx = r.optString("rawtx")
        if (rawtx.isEmpty()) throw WalletEngine.EngineException("Engine returned an unreadable response.")
        return broadcastAndRegister(rawtx)
    }

    suspend fun inscribe(contentType: String, dataB64: String, feeSat: Long = 0): String {
        val u = utxos()
        val r = engine.dict("buildInscribe", engine.args(
            "wif" to wif, "utxos" to u.toString(),
            "contentType" to contentType, "dataB64" to dataB64, "feeSat" to feeSat
        ))
        val rawtx = r.optString("rawtx")
        if (rawtx.isEmpty()) throw WalletEngine.EngineException("Engine returned an unreadable response.")
        return broadcastAndRegister(rawtx)
    }

    /** returns (txid or null when broadcast=false, rawtx) */
    suspend fun sendComposedTx(params: JSONObject): Pair<String?, String> {
        val u = utxos()
        val r = engine.dict("buildTx", engine.args(
            "wif" to wif, "utxos" to u.toString(), "params" to params.toString()
        ))
        val rawtx = r.optString("rawtx")
        if (rawtx.isEmpty()) throw WalletEngine.EngineException("Engine returned an unreadable response.")
        if (params.has("broadcast") && !params.isNull("broadcast") && !params.optBoolean("broadcast", true)) {
            return Pair(null, rawtx)
        }
        val txid = broadcastAndRegister(rawtx)
        return Pair(txid, rawtx)
    }

    // MARK: - holdings (SNS + BSVmaps + OpNS)

    suspend fun loadHoldings() {
        if (address.isEmpty()) return
        // SNS + BSVmaps: UNCHANGED logic in its own try/catch — an OpNS failure
        // can never touch this, and vice versa (graceful degradation per side)
        var combined: List<Holding>
        try {
            val h = Api.holdings(address).toMutableList()
            indexerOk = true
            // mergeListings(): the global registry knows listed items the indexer doesn't
            // (best effort — an unreachable registry must not blank the holdings)
            val listings = Api.listings() ?: emptyList()
            val mine = listings.filter { it.optString("sellerAddress") == address }
            if (mine.isNotEmpty()) {
                val byDistrict = HashMap<String, JSONObject>()
                for (l in mine) {
                    if (l.has("district") && !l.isNull("district")) {
                        // normalize numeric keys ("17.0" → "17") so they match Holding.district
                        val dv = l.opt("district")
                        val key = if (dv is Number) dv.toLong().toString() else dv.toString()
                        byDistrict[key] = l
                    }
                }
                for (i in h.indices) {
                    if (h[i].kind != HoldingKind.BSVMAP) continue
                    val d = h[i].district ?: continue
                    val l = byDistrict[d.toString()] ?: continue
                    val p: Long = when (val v = l.opt("priceSat")) {
                        is Double -> Math.round(v)
                        is Int -> v.toLong()
                        is Long -> v
                        else -> 0L
                    }
                    h[i] = h[i].copy(status = "listed", priceSat = p)
                }
            }
            // v3.3 (iOS v2.6.1 parity) — Wallet ↔ Domains listing-sync: a name
            // listed on the DOMAIN registry (v2 platform, USD) showed "held"
            // here because the holdings only knew the bsvmap.io ordinal
            // listings. Best effort in its own try/catch: a broken domain
            // registry must never blank the holdings.
            try {
                val myDomains = Api.myDomains(address)
                val listedUsd = HashMap<String, Double>()
                for (d in myDomains) {
                    if (d.isForSale && d.listingPrice != null) listedUsd[d.name.lowercase()] = d.listingPrice
                }
                if (listedUsd.isNotEmpty()) {
                    for (i in h.indices) {
                        if (h[i].kind != HoldingKind.SNS) continue
                        val usd = listedUsd[h[i].name.lowercase()] ?: continue
                        h[i] = h[i].copy(domainListedUsd = usd)
                    }
                }
            } catch (e: Exception) { }
            combined = h
        } catch (e: Exception) {
            indexerOk = false
            combined = emptyList()
        }
        // OpNS: third category, own try/catch + own status flag
        try {
            combined = combined + Api.opnsHoldings(address)
            opnsOk = true
        } catch (e: Exception) {
            opnsOk = false
        }
        holdings = combined
    }

    // MARK: - OpNS payment resolution (the four rules)

    /**
     * Resolve an OpNS name to a VERIFIED payment target:
     * 1. exact match only — a `fallback: true` answer is a DIFFERENT name and
     *    surfaces as an inline "did you mean …?" error, never a payment
     * 2. the current outpoint is checked unspent on WhatsOnChain
     * 3. the holder address is RECOMPUTED from the outpoint's locking script
     *    on chain and must equal what the index claims — trust but verify
     * 4. paymail forms (name@host) are rejected by the caller before this
     */
    suspend fun resolveOpnsPayment(name: String): OpnsPayTarget {
        val n = name.trim().lowercase()
        val lookup = Api.opnsLookup(n)
        val rec = if (!lookup.fallback) lookup.records.firstOrNull { it.name == n } else null
        if (rec == null) {
            val suggestion = lookup.records.firstOrNull()?.name
            if (suggestion != null && suggestion != n) {
                throw WalletEngine.EngineException(
                    "OpNS name \"$n\" does not exist. Did you mean \"$suggestion\"? Nothing was paid.")
            }
            throw WalletEngine.EngineException("OpNS name \"$n\" does not exist. Nothing was paid.")
        }
        if (rec.ambiguous) {
            throw WalletEngine.EngineException(
                "OpNS name \"$n\" is marked ambiguous by the index — not safe to pay.")
        }
        // recompute the holder address from the chain (raw hex is authoritative)
        val hex = Api.txHex(rec.currentTxid)
        val script = engine.string("outputScriptHex",
            engine.args("rawTxHex" to hex, "vout" to rec.currentVout))
        val holder = try {
            engine.call("scriptLockAddress", engine.args("scriptHex" to script)) as? String
        } catch (e: Exception) { null }
        if (holder.isNullOrEmpty()) {
            throw WalletEngine.EngineException(
                "Could not derive the holder address from the chain for \"$n\".")
        }
        if (holder != rec.ownerAddress) {
            throw WalletEngine.EngineException(
                "The OpNS index and the chain disagree about the holder of \"$n\" — refusing to pay. Try again in a moment.")
        }
        // v3.1.2 — spent-check via the dedicated /tx/<txid>/<vout>/spent
        // endpoint (200 = spent, 404 = unspent, else unknown). OpNS stays
        // fail-closed per its briefing: unknown REFUSES too — but with an
        // honest "could not verify" message, never a false spent/stale claim.
        when (Api.outpointSpent(rec.currentTxid, rec.currentVout)) {
            true -> throw WalletEngine.EngineException(
                "The ordinal of \"$n\" was spent — the name may have just changed hands. Re-resolve and try again.")
            null -> throw WalletEngine.EngineException(
                "The spent-status of \"$n\"'s ordinal could not be verified right now — try again in a moment. Nothing was paid.")
            false -> { /* provably unspent — proceed */ }
        }
        return OpnsPayTarget(name = n, holderAddress = holder,
            currentTxid = rec.currentTxid, currentVout = rec.currentVout)
    }

    // MARK: - SNS resolver payment (signed answers, level "prove")

    /**
     * Resolve `naam.tld` or `mailbox@naam.tld` to a VERIFIED payment target:
     * signed answer → signature against the pinned key (rotation only via a
     * proven succession chain) → expires → holder address derived from the
     * SIGNED holder_script → outpoint checked unspent (freshness, not script
     * equality — custody scripts may differ). Every resolver error carries a
     * readable message; it is thrown for INLINE display, never a popup.
     */
    suspend fun resolveSnsPayment(input: String): SnsPayTarget {
        val q = input.trim().lowercase()
        val (_, body) = Api.snsResolveRaw(q)
        val j = Api.json(body)
            ?: throw WalletEngine.EngineException("The SNS resolver returned an unreadable answer.")
        // error answers: show the resolver's own message inline (not_verified
        // is PERMANENT until the name carries the ✓; no_holder means retry)
        if (!j.optBoolean("ok", false)) {
            val code = j.optString("error").ifEmpty { "resolver_error" }
            val msg = j.optString("message").ifEmpty { "SNS resolver error: $code" }
            throw WalletEngine.EngineException(msg)
        }

        val nowTs = System.currentTimeMillis() / 1000
        var v = engine.dict("snsVerifyAnswer", engine.args(
            "answerJson" to body, "expectedSigner" to snsPinnedPubkey, "nowTs" to nowTs))
        var rotationNote = ""

        // unknown signer → prove the succession chain from the pin; only a
        // closing chain re-pins. Never "accept anyway".
        if (!v.optBoolean("valid", false) && v.optString("reason") == "unknown_signer") {
            val info = Api.snsPubkeyInfo()
            // field verified live 03-08-2026: GET /pubkey -> {ok, signer, seq, rotations:[]}
            val records = info.optJSONArray("rotations") ?: JSONArray()
            val proven = engine.string("snsVerifyRotationChain", engine.args(
                "pinnedPub" to snsPinnedPubkey, "records" to records.toString()))
            if (proven.lowercase() != v.optString("signer")) {
                throw WalletEngine.EngineException(
                    "The resolver signs with a new key, but the succession chain does not prove it — refusing. The pinned key is unchanged.")
            }
            prefs.edit().putString(SNS_PIN_KEY, proven).apply()
            rotationNote = "Resolver key rotated — the succession chain was verified and the new key is now pinned."
            v = engine.dict("snsVerifyAnswer", engine.args(
                "answerJson" to body, "expectedSigner" to proven, "nowTs" to nowTs))
        }

        if (!v.optBoolean("valid", false)) {
            val reason = v.optString("reason").ifEmpty { "invalid" }
            val text = when (reason) {
                "bad_signature" -> "The resolver answer carries an INVALID signature — refusing. Try again; if this persists the resolver may be compromised."
                "expired" -> "The resolver answer expired — resolve again and retry."
                "unsupported_holder_script" -> "The holder script is not a standard P2PKH script — this wallet cannot derive a pay-to address from it safely."
                else -> "The resolver answer could not be verified ($reason)."
            }
            throw WalletEngine.EngineException(text)
        }

        val holder = v.optString("holderAddress", "")
        val curTxid = v.optString("currentTxid", "")
        if (holder.isEmpty() || curTxid.isEmpty()) {
            throw WalletEngine.EngineException("The verified answer misses required fields.")
        }
        val curVout = v.optInt("currentVout", 0)
        val resolvedName = v.optString("name").ifEmpty { q }

        // v3.1.2 — freshness via the dedicated /tx/<txid>/<vout>/spent
        // endpoint. ONLY a provably spent outpoint (HTTP 200) refuses with
        // stale_outpoint; 404 = unspent = proceed; unknown (timeout/5xx)
        // proceeds WITH an inline note — the signed resolver answer
        // (expires, 300 s) is the authority. Unknown is never reported as
        // "spent": the old address-list lookup did exactly that on busy
        // holder addresses (false stale_outpoint on start.web3).
        var spentNote = ""
        when (Api.outpointSpent(curTxid, curVout)) {
            true -> throw WalletEngine.EngineException(
                "stale_outpoint: the inscription of $resolvedName was spent — the name may have just changed hands. Resolve again and retry.")
            null -> spentNote = "Note: the spent-status of the inscription outpoint could not be additionally verified right now — the signed resolver answer (with its 300 s expiry) is the authority for this payment."
            false -> { /* provably unspent — proceed */ }
        }

        var warning = rotationNote
        if (spentNote.isNotEmpty()) {
            warning += (if (warning.isEmpty()) "" else "\n") + spentNote
        }
        if (v.optBoolean("addressMismatch", false)) {
            warning += (if (warning.isEmpty()) "" else "\n") +
                "Note: the resolver's display address differs from the signed script — the wallet pays the SIGNED script's address shown here."
        }
        return SnsPayTarget(
            name = resolvedName,
            mailbox = v.optString("mailbox", ""),
            fallback = v.optBoolean("fallback", false),
            holderAddress = holder,
            currentTxid = curTxid,
            currentVout = curVout,
            expires = v.optLong("expires", 0),
            warning = warning
        )
    }

    // MARK: - BRC-100 permissions (v3.2) — BRC-43 grants, persistent
    //
    // Grants follow the standard, not "per keer": security level 0 = open (no
    // prompt), level 1 = ONE persistent grant per app per protocol, level 2 =
    // per app per protocol per counterparty. The identity key has its own
    // per-app grant. Approval is a NATIVE sheet with biometrics — never an
    // HTML dialog the page could fake.

    private var brc100Grants: Set<String>
        get() = prefs.getStringSet(BRC100_GRANTS_KEY, emptySet()) ?: emptySet()
        set(value) { prefs.edit().putStringSet(BRC100_GRANTS_KEY, value).apply() }

    /**
     * throws a standards-shaped error when the user denies; returns silently
     * when allowed (level 0, an existing grant, or a fresh approval)
     */
    suspend fun requireBrc100Permission(origin: String, method: String, args: JSONObject) {
        val isIdentity = method == "getPublicKey" && args.optBoolean("identityKey", false)
        var level = 0
        var protocolName = "—"
        val p = args.optJSONArray("protocolID")
        if (p != null && p.length() == 2) {
            level = when (val l = p.opt(0)) {
                is Int -> l
                is Long -> l.toInt()
                is Double -> l.toInt()
                else -> 0
            }
            protocolName = p.optString(1).ifEmpty { "—" }
        }
        val counterparty = args.optString("counterparty").ifEmpty { "self" }

        // BRC-43 level 0: open protocol — no permission required
        if (!isIdentity && level == 0) return

        val grantKey = if (isIdentity) "$address|$origin|identity"
            else "$address|$origin|$level|$protocolName" + (if (level >= 2) "|$counterparty" else "")
        if (brc100Grants.contains(grantKey)) return

        val title = when (method) {
            "getPublicKey" -> if (isIdentity) "Share identity key" else "Share a derived public key"
            "encrypt" -> "Encrypt data"
            "decrypt" -> "Decrypt data"
            "createSignature" -> "Create a signature"
            "verifySignature" -> "Verify a signature"
            "createHmac" -> "Create an HMAC"
            "verifyHmac" -> "Verify an HMAC"
            else -> method
        }
        var detail = if (isIdentity)
            "The app asks for your identity key (a public key that identifies this wallet to the app)."
        else "Protocol: $protocolName · security level $level"
        if (!isIdentity && level >= 2) {
            detail += "\nCounterparty: ${Fmt.shortAddress(counterparty)}"
        }

        val deferred = kotlinx.coroutines.CompletableDeferred<Boolean>()
        pendingBrc100Permission = Brc100PermissionRequest(
            origin = origin.ifEmpty { "unknown app" },
            title = title, detail = detail, deferred = deferred)
        val approved = deferred.await()
        if (!approved) {
            throw Brc100.Err("WERR_PERMISSION_DENIED", 1,
                "The user denied ${title.lowercase()} for $origin.")
        }
        brc100Grants = brc100Grants + grantKey
    }

    // MARK: - BRC-100 grants manager (Settings)

    /**
     * decode the stored grant keys for the ACTIVE address into rows the
     * Settings screen can show — the raw key doubles as the revoke handle
     */
    fun brc100GrantsList(): List<Brc100GrantInfo> {
        return brc100Grants.mapNotNull { key ->
            val parts = key.split("|")
            if (parts.size < 3 || parts[0] != address) return@mapNotNull null
            val origin = parts[1]
            val detail = when {
                parts[2] == "identity" -> "Identity key"
                parts.size >= 4 -> "Level ${parts[2]} · protocol “${parts[3]}”" +
                    (if (parts.size >= 5) " · counterparty ${Fmt.shortAddress(parts[4])}" else "")
                else -> parts[2]
            }
            Brc100GrantInfo(key = key, origin = origin, detail = detail)
        }.sortedWith(compareBy({ it.origin }, { it.detail }))
    }

    fun brc100RevokeGrant(key: String) {
        brc100Grants = brc100Grants - key
    }

    fun brc100RevokeAllGrants(origin: String) {
        brc100Grants = brc100Grants.filter { !it.startsWith("$address|$origin|") }.toSet()
    }

    // MARK: - BRC-100 fase 3 (v3.2): geld — bevestiging, actielog, relinquish

    /**
     * per-transaction biometric confirmation. Money ≠ grant (hard rule 2):
     * nothing is persisted, every transaction asks again. Throws the
     * standards-shaped rejection on deny.
     */
    suspend fun requireBrc100TxConfirm(
        origin: String, title: String, description: String,
        lines: List<Brc100TxLine>, minerFeeEstimate: Long,
        serviceFees: Long, totalSat: Long, incoming: Boolean
    ) {
        val deferred = kotlinx.coroutines.CompletableDeferred<Boolean>()
        pendingBrc100TxConfirm = Brc100TxConfirmRequest(
            origin = origin.ifEmpty { "unknown app" },
            title = title, description = description, lines = lines,
            minerFeeEstimate = minerFeeEstimate, serviceFees = serviceFees,
            totalSat = totalSat, incoming = incoming, deferred = deferred)
        val approved = deferred.await()
        if (!approved) {
            throw Brc100.Err("WERR_PERMISSION_DENIED", 1,
                "The user rejected the transaction for $origin.")
        }
    }

    /**
     * local action log per address (pattern of the inscription log) — feeds
     * listActions; contains exactly the BRC-100 actions made via this app
     */
    fun brc100Actions(): List<Brc100ActionRecord> {
        return try {
            val raw = prefs.getString(BRC100_ACTIONS_KEY, null) ?: return emptyList()
            val dict = JSONObject(raw)
            val arr = dict.optJSONArray(address) ?: return emptyList()
            val out = ArrayList<Brc100ActionRecord>()
            for (i in 0 until arr.length()) {
                arr.optJSONObject(i)?.let { o -> Brc100ActionRecord.from(o)?.let { out.add(it) } }
            }
            out
        } catch (e: Exception) { emptyList() }
    }

    fun brc100LogAction(rec: Brc100ActionRecord) {
        try {
            val dict = prefs.getString(BRC100_ACTIONS_KEY, null)?.let { JSONObject(it) } ?: JSONObject()
            val arr = dict.optJSONArray(address) ?: JSONArray()
            val newArr = JSONArray()
            newArr.put(rec.toJson())   // newest first
            for (i in 0 until arr.length()) newArr.put(arr.opt(i))
            dict.put(address, newArr)
            prefs.edit().putString(BRC100_ACTIONS_KEY, dict.toString()).apply()
        } catch (e: Exception) { }
    }

    /**
     * outpoints the wallet was asked to stop managing (relinquishOutput) —
     * persisted per address and excluded from funding in utxos()
     */
    fun brc100Relinquished(): Set<String> {
        return try {
            val raw = prefs.getString(BRC100_RELINQUISHED_KEY, null) ?: return emptySet()
            val arr = JSONObject(raw).optJSONArray(address) ?: return emptySet()
            val out = HashSet<String>()
            for (i in 0 until arr.length()) out.add(arr.optString(i))
            out
        } catch (e: Exception) { emptySet() }
    }

    fun brc100Relinquish(outpoint: String) {
        try {
            val dict = prefs.getString(BRC100_RELINQUISHED_KEY, null)?.let { JSONObject(it) } ?: JSONObject()
            val set = HashSet<String>()
            dict.optJSONArray(address)?.let { arr ->
                for (i in 0 until arr.length()) set.add(arr.optString(i))
            }
            set.add(outpoint)
            dict.put(address, JSONArray(set.toList()))
            prefs.edit().putString(BRC100_RELINQUISHED_KEY, dict.toString()).apply()
        } catch (e: Exception) { }
    }

    // MARK: - UTXO tools (v3.2): split & combine — service fees like everywhere

    /** N equal outputs to self via the existing (verified) buildTx path */
    suspend fun splitUtxos(count: Int, satsEach: Long): String {
        if (count < 2 || count > 200) throw WalletEngine.EngineException("Choose between 2 and 200 UTXOs.")
        if (satsEach < 547) throw WalletEngine.EngineException("Each UTXO needs at least 547 sats (above dust).")
        val u = utxos()
        val outs = JSONArray()
        repeat(count) {
            outs.put(JSONObject().put("type", "p2pkh").put("address", address).put("satoshis", satsEach))
        }
        val params = JSONObject().put("outputs", outs)
        val r = engine.dict("buildTx", engine.args(
            "wif" to wif, "utxos" to u.toString(), "params" to params.toString()))
        val rawtx = r.optString("rawtx")
        if (rawtx.isEmpty()) throw WalletEngine.EngineException("Engine returned an unreadable response.")
        return broadcastAndRegister(rawtx)
    }

    /** ALL spendable (ordinal-protected) UTXOs into one output to self */
    suspend fun combineUtxos(): Pair<String, Long> {
        val u = utxos()
        val r = engine.dict("buildConsolidate", engine.args("wif" to wif, "utxos" to u.toString()))
        val rawtx = r.optString("rawtx")
        val outSat = r.optLong("outputSat", -1)
        if (rawtx.isEmpty() || outSat < 0) throw WalletEngine.EngineException("Engine returned an unreadable response.")
        val txid = broadcastAndRegister(rawtx)
        return Pair(txid, outSat)   // (txid, outputSat)
    }

    // MARK: - ordinal transfer

    suspend fun sendOrdinal(holding: Holding, to: String): String {
        // raw hex is byte-for-byte authoritative — never the WoC verbose endpoint
        val ordHex = Api.txHex(holding.currentTxid)
        val ordScriptHex = engine.string("outputScriptHex",
            engine.args("rawTxHex" to ordHex, "vout" to holding.currentVout))

        val fees = engine.fees()
        val all = utxos()
        val filtered = JSONArray()
        for (i in 0 until all.length()) {
            val u = all.optJSONObject(i) ?: continue
            if (u.optString("txid") == holding.currentTxid && u.optInt("vout") == holding.currentVout) continue
            filtered.put(u)
        }
        if (filtered.length() == 0) {
            throw WalletEngine.EngineException("No spendable funding UTXOs for the fee. Your balance may be locked in pending transactions.")
        }
        val required = fees.ordinalMinerFee + fees.totalServiceFees
        val sel = engine.call("selectFunding",
            engine.args("utxos" to filtered.toString(), "requiredSat" to required)) as? JSONArray
            ?: throw WalletEngine.EngineException("Insufficient balance for fee + service fee.")
        // fetch the REAL locking script of every funding input
        for (i in 0 until sel.length()) {
            val u = sel.optJSONObject(i) ?: continue
            val txid = u.optString("txid")
            if (txid.isEmpty()) continue
            try {
                val hex = Api.txHex(txid)
                val real = engine.string("outputScriptHex",
                    engine.args("rawTxHex" to hex, "vout" to u.optInt("vout")))
                u.put("realScriptHex", real)
            } catch (e: Exception) { }
        }
        val r = engine.dict("buildOrdinalTransfer", engine.args(
            "wif" to wif, "ordTxid" to holding.currentTxid, "ordVout" to holding.currentVout,
            "ordScriptHex" to ordScriptHex, "funding" to sel.toString(), "to" to to
        ))
        val rawtx = r.optString("rawtx")
        if (rawtx.isEmpty()) throw WalletEngine.EngineException("Engine returned an unreadable response.")
        return broadcastAndRegister(rawtx)
    }

    /** owning address of an ordinal's locking script — up-front ownership check */
    suspend fun ordinalOwner(holding: Holding): String? {
        return try {
            val hex = Api.txHex(holding.currentTxid)
            val script = engine.string("outputScriptHex",
                engine.args("rawTxHex" to hex, "vout" to holding.currentVout))
            engine.call("scriptLockAddress", engine.args("scriptHex" to script)) as? String
        } catch (e: Exception) { null }
    }

    // MARK: - marketplace: list / delist (with the extension's trust-but-verify)

    private suspend fun listingPartial(h: Holding, priceSat: Long): Pair<String, String> {
        val ordHex = Api.txHex(h.currentTxid)
        val ordScriptHex = engine.string("outputScriptHex",
            engine.args("rawTxHex" to ordHex, "vout" to h.currentVout))
        val r = engine.dict("buildListingPartial", engine.args(
            "wif" to wif, "ordTxid" to h.currentTxid, "ordVout" to h.currentVout,
            "ordScriptHex" to ordScriptHex, "priceSat" to priceSat
        ))
        val p = r.optString("partialTx")
        val s = r.optString("payScriptHex")
        if (p.isEmpty() || s.isEmpty()) throw WalletEngine.EngineException("Engine returned an unreadable response.")
        return Pair(p, s)   // (partialTx, payScriptHex)
    }

    suspend fun delistRequest(h: Holding) {
        val district = h.district ?: throw WalletEngine.EngineException("Not a BSVmap.")
        val ts = System.currentTimeMillis()
        val msg = engine.string("delistMessage", engine.args(
            "district" to district, "ordinalTxid" to h.currentTxid,
            "ordinalVout" to h.currentVout, "ts" to ts
        ))
        val (signature, pubkey) = engine.signMessage(wif, msg)
        Api.postDelist(district, JSONObject()
            .put("sellerAddress", address).put("district", district)
            .put("ordinalTxid", h.currentTxid).put("ordinalVout", h.currentVout)
            .put("timestamp", ts).put("message", msg)
            .put("signature", signature).put("pubkey", pubkey))
    }

    /** list one item incl. SELF-HEAL for stuck server state (stale per-district listing) */
    suspend fun listRequest(h: Holding, priceSat: Long) {
        val district = h.district
            ?: throw WalletEngine.EngineException("Marketplace listing is currently for BSVmaps. SNS listings coming soon.")
        val st = Api.districtState(district)
        if (st != null && st.has("listing") && !st.isNull("listing")) {
            try { delistRequest(h) } catch (e: Exception) { }   // best effort — proceed to list
        }
        val (partialTx, payScriptHex) = listingPartial(h, priceSat)
        Api.postList(district, JSONObject()
            .put("sellerAddress", address).put("priceSat", priceSat)
            .put("ordinalTxid", h.currentTxid).put("ordinalVout", h.currentVout)
            .put("partialTx", partialTx).put("payScriptHex", payScriptHex))
    }

    /** districts of THIS address present in the global registry — null if unreachable */
    private suspend fun registryDistricts(): Set<String>? {
        // Api.listings() itself signals unreachable with null; previously this
        // checked indexerOk — the health flag of a DIFFERENT endpoint — so a
        // failed listings fetch was misread as "registry is empty".
        val ls = Api.listings() ?: return null
        val set = HashSet<String>()
        for (l in ls) {
            if (l.optString("sellerAddress") != address) continue
            if (l.has("district") && !l.isNull("district")) {
                // normalize numeric keys ("17.0" → "17") so they match
                // Holding.district — same normalization as mergeListings()
                val dv = l.opt("district")
                set.add(if (dv is Number) dv.toLong().toString() else dv.toString())
            }
        }
        return set
    }

    /**
     * which of these items are STILL listed in EITHER server store? -> [(item, where)]
     * Re-checks with a grace period (0s / 4s / 8s): the server needs a moment to
     * propagate a delist through both stores, and a zero-delay check right after
     * the POST reported perfectly fine delists as failed.
     */
    suspend fun verifyStillListed(items: List<Holding>): List<Pair<Holding, String>> {
        var remaining = items
        var out = ArrayList<Pair<Holding, String>>()
        for (round in 0 until 3) {
            if (round > 0) delay(4000L * round)   // 4s, then 8s propagation grace
            out = ArrayList()
            val reg = registryDistricts()
            for ((i, it) in remaining.withIndex()) {
                val district = it.district ?: continue
                if (i > 0) delay(120)   // be gentle on the API
                val st = Api.districtState(district)
                val inDistrict = st != null && st.has("listing") && !st.isNull("listing")
                val inRegistry = reg?.contains(district.toString()) ?: false
                if (inDistrict || inRegistry) {
                    val whereStr = when {
                        inDistrict && inRegistry -> "global registry + district record"
                        inDistrict -> "per-district record (district page still shows it for sale)"
                        else -> "global registry"
                    }
                    out.add(Pair(it, whereStr))
                }
            }
            if (out.isEmpty()) return out
            remaining = out.map { it.first }
        }
        return out
    }

    /**
     * trust-but-verify for LIST: which freshly-listed items did NOT reach the registry?
     * The registry propagates asynchronously (the UI itself says "within a minute"),
     * so this polls with a grace period (~30s total) instead of failing on a single
     * zero-delay check. An unreachable registry means "cannot verify" — we then
     * trust the server's HTTP 200 instead of reporting a false failure.
     */
    suspend fun verifyListedInRegistry(items: List<Holding>): List<Holding> {
        var missing = items.filter { it.district != null }
        for (round in 0 until 4) {
            if (round > 0) delay(5000L * round)   // 5s, 10s, 15s propagation grace
            val reg = registryDistricts() ?: return emptyList()   // unreachable — cannot verify
            missing = missing.filter { !reg.contains(it.district.toString()) }
            if (missing.isEmpty()) return emptyList()
        }
        return missing
    }

    // MARK: - atomic swap purchase (dApp buyOrdinal)

    suspend fun buyOrdinal(partialTx: String, priceSat: Long, sellerAddress: String, payScriptHex: String): String {
        val fees = engine.fees()
        val need = priceSat + 1 + fees.ordinalMinerFee + fees.totalServiceFees
        val all = utxos()
        val sel = engine.call("selectFunding",
            engine.args("utxos" to all.toString(), "requiredSat" to need)) as? JSONArray
            ?: throw WalletEngine.EngineException("Insufficient balance for price + fee + service fee.")
        for (i in 0 until sel.length()) {
            val u = sel.optJSONObject(i) ?: continue
            val txid = u.optString("txid")
            if (txid.isEmpty()) continue
            try {
                val hex = Api.txHex(txid)
                val real = engine.string("outputScriptHex",
                    engine.args("rawTxHex" to hex, "vout" to u.optInt("vout")))
                u.put("realScriptHex", real)
            } catch (e: Exception) { }
        }
        val r = engine.dict("buildPurchaseFromPartial", engine.args(
            "wif" to wif, "partialHex" to partialTx, "priceSat" to priceSat,
            "sellerAddress" to sellerAddress, "payScriptHex" to payScriptHex, "funding" to sel.toString()
        ))
        val rawtx = r.optString("rawtx")
        if (rawtx.isEmpty()) throw WalletEngine.EngineException("Engine returned an unreadable response.")
        return broadcastAndRegister(rawtx)
    }

    // MARK: - .web3 domain registry (signed wallet actions — key = ownership)

    suspend fun signedRegistryPost(pathname: String, action: String, fields: List<String>, body: JSONObject) {
        if (address.isEmpty() || wif.isEmpty()) throw WalletEngine.EngineException("Unlock your wallet first.")
        val ts = System.currentTimeMillis()
        val auth = engine.dict("signAction", engine.args(
            "wif" to wif, "address" to address, "action" to action,
            "fields" to JSONArray(fields), "ts" to ts
        ))
        Api.walletPost(pathname, body, auth)
    }

    /**
     * v3.2 FIX (iOS v2.5.2 parity) — root-domain set-target returned
     * `invalid_domain` while the subdomain/route handlers accepted the exact
     * same domain string. The v2 /wallet/set-target handler identifies the
     * domain by the platform's canonical `name` field (exactly like /whois and
     * /resolve?name=), so a body with only `domain` fell through as an empty
     * name → `invalid_domain`, regardless of how valid the domain was. We now
     * send `name` (and keep `domain` for compatibility with any older handler
     * that reads it). The signed message is unchanged — it was already correct.
     */
    suspend fun setDomainTarget(domain: String, txid: String, vout: Int) {
        val ts = System.currentTimeMillis()
        val msg = listOf("ordnet-registry", "set-target", domain, txid, vout.toString(), ts.toString())
            .joinToString("|")
        val (signature, pubkey) = engine.signMessage(wif, msg)
        val (code, data) = Api.postJSON("${Api.namesBase}/wallet/set-target", JSONObject()
            .put("name", domain).put("domain", domain).put("txid", txid).put("vout", vout).put("ts", ts)
            .put("address", address).put("signature", signature).put("pubkey", pubkey))
        val j = Api.json(data) ?: JSONObject()
        if (code != 200 || (j.has("error") && !j.isNull("error"))) {
            val e = j.optString("error").ifEmpty { "HTTP $code" }
            throw Api.ApiException.http(code,
                if (e == "invalid_signature") "Signature rejected — is this domain owned by the active wallet?"
                else "Could not save: $e")
        }
    }

    // MARK: - inscription log (Upload tab: everything inscribed via this app)

    var inscriptions by mutableStateOf<List<InscriptionRecord>>(emptyList())
        private set
    private var allInscriptions = HashMap<String, MutableList<InscriptionRecord>>()   // per address

    fun loadInscriptions() {
        try {
            val raw = prefs.getString(INSCRIPTIONS_KEY, null)
            if (raw != null) {
                val dict = JSONObject(raw)
                val map = HashMap<String, MutableList<InscriptionRecord>>()
                for (key in dict.keys()) {
                    val arr = dict.optJSONArray(key) ?: continue
                    val list = ArrayList<InscriptionRecord>()
                    for (i in 0 until arr.length()) {
                        arr.optJSONObject(i)?.let { o -> InscriptionRecord.from(o)?.let { list.add(it) } }
                    }
                    map[key] = list
                }
                allInscriptions = map
            }
        } catch (e: Exception) { }
        inscriptions = allInscriptions[address]?.toList() ?: emptyList()
    }

    /** v3.2 — ORD/ner reads the log of ANY account (folders per account) */
    fun inscriptionLog(addr: String): List<InscriptionRecord> {
        return try {
            val raw = prefs.getString(INSCRIPTIONS_KEY, null) ?: return emptyList()
            val arr = JSONObject(raw).optJSONArray(addr) ?: return emptyList()
            val list = ArrayList<InscriptionRecord>()
            for (i in 0 until arr.length()) {
                arr.optJSONObject(i)?.let { o -> InscriptionRecord.from(o)?.let { list.add(it) } }
            }
            list
        } catch (e: Exception) { emptyList() }
    }

    fun recordInscription(txid: String, contentType: String, filename: String, bytes: Int) {
        val rec = InscriptionRecord(txid = txid, contentType = contentType, filename = filename,
            bytes = bytes, ts = System.currentTimeMillis().toDouble())
        val list = allInscriptions.getOrPut(address) { ArrayList() }
        list.add(0, rec)   // newest first
        inscriptions = list.toList()
        try {
            val dict = JSONObject()
            for ((k, v) in allInscriptions) {
                val arr = JSONArray()
                for (r in v) arr.put(r.toJson())
                dict.put(k, arr)
            }
            prefs.edit().putString(INSCRIPTIONS_KEY, dict.toString()).apply()
        } catch (e: Exception) { }
    }

    // MARK: - address book

    fun loadAddressBook() {
        try {
            val raw = prefs.getString(ADDRESSBOOK_KEY, null) ?: return
            val arr = JSONArray(raw)
            val list = ArrayList<BookEntry>()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val addr = o.optString("address")
                if (addr.isEmpty()) continue
                list.add(BookEntry(
                    name = o.optString("name"), address = addr, ts = o.optDouble("ts", 0.0)))
            }
            addressBook = list
        } catch (e: Exception) { }
    }

    private fun saveAddressBook() {
        try {
            val arr = JSONArray()
            for (e in addressBook) {
                arr.put(JSONObject().put("name", e.name).put("address", e.address).put("ts", e.ts))
            }
            prefs.edit().putString(ADDRESSBOOK_KEY, arr.toString()).apply()
        } catch (e: Exception) { }
    }

    fun bookLabel(addr: String): String? = addressBook.firstOrNull { it.address == addr }?.name

    suspend fun bookAdd(name: String, addr: String) {
        if (!engine.validateAddress(addr)) {
            throw WalletEngine.EngineException("That is not a valid BSV address.")
        }
        val nm = name.trim().ifEmpty { "Saved ${addressBook.size + 1}" }
        val idx = addressBook.indexOfFirst { it.address == addr }
        addressBook = if (idx >= 0) {
            addressBook.mapIndexed { i, e -> if (i == idx) e.copy(name = nm) else e }
        } else {
            addressBook + BookEntry(name = nm, address = addr, ts = System.currentTimeMillis().toDouble())
        }
        saveAddressBook()
    }

    fun bookRemove(addr: String) {
        addressBook = addressBook.filter { it.address != addr }
        saveAddressBook()
    }

    // MARK: - connected sites

    fun connectSite(origin: String) { connectedSites = connectedSites + (origin to true) }
    fun disconnectSite(origin: String) { connectedSites = connectedSites - origin }
    fun isConnected(origin: String): Boolean = connectedSites[origin] == true
}
