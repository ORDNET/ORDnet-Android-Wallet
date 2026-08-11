package io.ordnet.wallet.core

import org.json.JSONArray
import org.json.JSONObject
import java.text.NumberFormat
import java.util.Date
import java.util.Locale

// MARK: - Accounts

data class Account(
    var name: String,
    val wif: String,
    val origin: String,      // bip44 | legacy | wif | random
    val path: String?,
    val address: String
) {
    val id: String get() = address

    val originLabel: String
        get() = when (origin) {
            "bip44" -> "BIP44"
            "legacy" -> "legacy"
            "wif" -> "WIF"
            "random" -> "generated"
            else -> origin
        }
}

/** The encrypted vault payload — same shape as the extension's V11 vault. */
object VaultPayload {
    fun encode(accounts: List<Account>, active: Int): ByteArray {
        val arr = JSONArray()
        for (a in accounts) {
            val o = JSONObject()
            o.put("name", a.name)
            o.put("wif", a.wif)
            o.put("origin", a.origin)
            if (a.path != null) o.put("path", a.path)
            arr.put(o)
        }
        val root = JSONObject()
        root.put("accounts", arr)
        root.put("active", active)
        return root.toString().toByteArray(Charsets.UTF_8)
    }

    /** returns (storedAccounts as [name, wif, origin?, path?], active) */
    fun decode(data: ByteArray): Pair<List<JSONObject>, Int> {
        val root = JSONObject(String(data, Charsets.UTF_8))
        val arr = root.optJSONArray("accounts") ?: JSONArray()
        val list = ArrayList<JSONObject>(arr.length())
        for (i in 0 until arr.length()) list.add(arr.getJSONObject(i))
        return Pair(list, root.optInt("active", 0))
    }
}

// MARK: - Balance / history

data class Balance(val confirmed: Long, val unconfirmed: Long) {
    val total: Long get() = confirmed + unconfirmed
}

data class HistoryTx(val txHash: String, val height: Int) {
    val id: String get() = txHash
    val isPending: Boolean get() = height <= 0
}

// MARK: - Holdings (SNS names + BSVmaps from the ORDnet V30 indexer,
// OpNS names from the OpNS index at search.ordnet.io)

enum class HoldingKind(val raw: String) {
    SNS("sns"), BSVMAP("bsvmap"), OPNS("opns"),
    /** v3.2 — generic inscribed file (ORD/ner): sendable as 1-sat ordinal,
     *  never listable, never shown in the Home holdings tabs */
    INSCRIPTION("inscription")
}

data class Holding(
    val kind: HoldingKind,
    val name: String,
    val district: Int?,
    val claimHeight: Int,
    var status: String,            // held | listed | contract | ...
    val currentTxid: String,
    val currentVout: Int,
    var priceSat: Long?,
    /** v3.3 — SNS name listed on the DOMAIN registry (v2 platform, USD).
     *  A separate marketplace from the bsvmap.io ordinal listings: this app
     *  deliberately never offers a second (bsvmap) listing for such a name. */
    var domainListedUsd: Double? = null
) {
    val id: String get() = "${kind.raw}:$name:${currentTxid}_$currentVout"
    val isListed: Boolean get() = status == "listed"
    /** listed on EITHER marketplace (bsvmap sats-listing or domain USD-listing) */
    val isForSaleAnywhere: Boolean get() = isListed || domainListedUsd != null
    val utxoShort: String
        get() = if (currentTxid.length != 64) currentTxid
        else "${currentTxid.take(10)}…${currentTxid.takeLast(6)}_$currentVout"

    /** full type label ("Item / Type" rows) — one place for all categories */
    val kindLabel: String
        get() = when (kind) {
            HoldingKind.SNS -> "SNS name (1Sat Ordinal)"
            HoldingKind.BSVMAP -> "BSVmap district (1Sat Ordinal)"
            HoldingKind.OPNS -> "OpNS name (1Sat Ordinal)"
            HoldingKind.INSCRIPTION -> "Inscribed file (1Sat Ordinal)"
        }

    /** short label for button/title text ("Send …") */
    val shortKindLabel: String
        get() = when (kind) {
            HoldingKind.SNS -> "SNS name"
            HoldingKind.BSVMAP -> "BSVmap"
            HoldingKind.OPNS -> "OpNS name"
            HoldingKind.INSCRIPTION -> "inscription"
        }

    companion object {
        /** tolerant to indexer field naming — port of listedPriceSats() */
        fun priceSats(dict: JSONObject): Long? {
            for (key in listOf("priceSat", "priceSats", "listPriceSat", "listPrice", "price")) {
                if (dict.has(key) && !dict.isNull(key)) {
                    val v = dict.opt(key)
                    when (v) {
                        is Int -> if (v > 0) return v.toLong()
                        is Long -> if (v > 0) return v
                        is Double -> if (v > 0) return Math.round(v)
                        is String -> v.toLongOrNull()?.let { if (it > 0) return it }
                    }
                }
            }
            return null
        }

        /**
         * map one record of the OpNS index (GET /api/opns/owner/<address>) — the
         * field names are the OpNS API's own (owner_address, current_txid, …).
         * No claim height exists in OpNS responses; claimHeight stays 0 and the
         * row shows just "OpNS".
         */
        fun fromOpns(dict: JSONObject): Holding? {
            val name = dict.optString("name", "")
            val txid = dict.optString("current_txid", "")
            if (name.isEmpty() || txid.isEmpty()) return null
            return Holding(
                kind = HoldingKind.OPNS,
                name = name,
                district = null,
                claimHeight = 0,
                status = "held",
                currentTxid = txid,
                currentVout = dict.optInt("current_vout", 0),
                priceSat = null
            )
        }

        fun from(dict: JSONObject, kind: HoldingKind): Holding? {
            val name = when {
                dict.has("name") && !dict.isNull("name") -> dict.optString("name")
                dict.has("district") && !dict.isNull("district") -> "bsvmap ${dict.opt("district")}"
                else -> return null
            }
            val txid = if (dict.has("currentTxid") && !dict.isNull("currentTxid"))
                dict.optString("currentTxid") else return null
            val district: Int? = when (val d = dict.opt("district")) {
                is Int -> d
                is Long -> d.toInt()
                is Double -> d.toInt()
                is String -> d.toIntOrNull()
                else -> null
            }
            return Holding(
                kind = kind,
                name = name,
                district = district,
                claimHeight = dict.optInt("claimHeight", 0),
                status = dict.optString("status", "held").ifEmpty { "held" },
                currentTxid = txid,
                currentVout = dict.optInt("currentVout", 0),
                priceSat = priceSats(dict)
            )
        }
    }
}

// MARK: - ORD/ner (v3.2 — on-chain file browser, 1Sat index)

/**
 * one file in ORD/ner: an inscription outpoint the address currently holds.
 * `origin*` locates the CONTENT (preview / open in browser); `current*` is
 * the outpoint a Send must spend (sat-following).
 */
data class OrdnerFile(
    val originTxid: String,
    val originVout: Int,
    val currentTxid: String,
    val currentVout: Int,
    val contentType: String,
    val size: Int,
    val height: Int?,            // null = unconfirmed
    val name: String? = null,    // filename from the app's inscription log, if known
    val sentLabel: Boolean = false // log-only item the address no longer holds
) {
    val id: String get() = "${currentTxid}_$currentVout"
    val displayName: String
        get() = name ?: "${originTxid.take(12)}…${originTxid.takeLast(6)}"
    val typeLabel: String
        get() {
            val ct = contentType.substringBefore(";")
            return when {
                ct.startsWith("image/") -> "Image"
                ct.startsWith("video/") -> "Video"
                ct.startsWith("audio/") -> "Audio"
                ct.startsWith("text/html") -> "HTML"
                ct.startsWith("text/plain") -> "Text"
                ct.contains("json") -> "JSON"
                else -> ct.substringAfterLast("/", "File").ifEmpty { "File" }
            }
        }
    val sizeLabel: String
        get() = when {
            size <= 0 -> "—"
            size < 1024 -> "$size B"
            size < 1024 * 1024 -> String.format(Locale.US, "%.1f KB", size / 1024.0)
            else -> String.format(Locale.US, "%.1f MB", size / 1024.0 / 1024.0)
        }
}

// MARK: - BRC-100 permission prompt (v3.2)

/**
 * one pending permission question for the native sheet; the deferred is
 * completed exactly once (Allow after biometrics, or Deny)
 */
class Brc100PermissionRequest(
    val origin: String,
    val title: String,
    val detail: String,
    val deferred: kotlinx.coroutines.CompletableDeferred<Boolean>
) {
    val id: String = java.util.UUID.randomUUID().toString()
}

// MARK: - BRC-100 fase 3 (v3.2): geld

/** one output line on the native transaction-confirmation sheet */
data class Brc100TxLine(
    val dest: String,            // P2PKH address, or a script description
    val sats: Long,
    val note: String             // the app's outputDescription
)

/**
 * per-transaction confirmation (money ≠ grant: NEVER persisted, every
 * transaction asks again with biometrics — hard rule 2 of fase 3)
 */
class Brc100TxConfirmRequest(
    val origin: String,
    val title: String,           // "Approve payment" / "Accept incoming payment"
    val description: String,     // the app's action description
    val lines: List<Brc100TxLine>,
    val minerFeeEstimate: Long,
    val serviceFees: Long,
    val totalSat: Long,
    val incoming: Boolean,       // internalizeAction: money flows TO this wallet
    val deferred: kotlinx.coroutines.CompletableDeferred<Boolean>
) {
    val id: String = java.util.UUID.randomUUID().toString()
}

/**
 * one entry in the local BRC-100 action log (per address, like the
 * inscription log) — feeds listActions; only actions made via this app
 */
data class Brc100ActionRecord(
    val txid: String,
    val description: String,
    val labels: List<String>,
    val satoshis: Long,          // action outputs total (excl. fees)
    val origin: String,
    val ts: Double,              // ms since epoch
    val status: String,          // "completed" — only fully-processed actions exist
    val isOutgoing: Boolean
) {
    val id: String get() = txid

    fun toJson(): JSONObject = JSONObject()
        .put("txid", txid).put("description", description)
        .put("labels", JSONArray(labels)).put("satoshis", satoshis)
        .put("origin", origin).put("ts", ts)
        .put("status", status).put("isOutgoing", isOutgoing)

    companion object {
        fun from(o: JSONObject): Brc100ActionRecord? {
            val txid = o.optString("txid")
            if (txid.isEmpty()) return null
            val labelsArr = o.optJSONArray("labels") ?: JSONArray()
            val labels = ArrayList<String>(labelsArr.length())
            for (i in 0 until labelsArr.length()) labels.add(labelsArr.optString(i))
            return Brc100ActionRecord(
                txid = txid,
                description = o.optString("description"),
                labels = labels,
                satoshis = o.optLong("satoshis", 0),
                origin = o.optString("origin"),
                ts = o.optDouble("ts", 0.0),
                status = o.optString("status", "completed").ifEmpty { "completed" },
                isOutgoing = o.optBoolean("isOutgoing", true)
            )
        }
    }
}

/**
 * one granted BRC-100 permission, decoded from the stored grant key —
 * shown and revocable in Settings (grants manager)
 */
data class Brc100GrantInfo(
    val key: String,             // raw stored key (revoke handle)
    val origin: String,
    val detail: String           // "Identity key" / "Level 1 · protocol …"
) {
    val id: String get() = key
}

// MARK: - OpNS (bare names, tree 0 — index at search.ordnet.io/api/opns)

/**
 * one record as returned by the OpNS index (verified live 03-08-2026:
 * name, origin_txid/origin_vout, owner_address, current_txid/current_vout,
 * ambiguous, lineage_verified — NO block height field)
 */
data class OpnsRecord(
    val name: String,
    val ownerAddress: String,
    val currentTxid: String,
    val currentVout: Int,
    val ambiguous: Boolean,
    val lineageVerified: Boolean
) {
    companion object {
        fun from(dict: JSONObject): OpnsRecord? {
            val name = dict.optString("name", "")
            val owner = dict.optString("owner_address", "")
            val txid = dict.optString("current_txid", "")
            if (name.isEmpty() || owner.isEmpty() || txid.isEmpty()) return null
            return OpnsRecord(
                name = name,
                ownerAddress = owner,
                currentTxid = txid,
                currentVout = dict.optInt("current_vout", 0),
                ambiguous = dict.optBoolean("ambiguous", false),
                lineageVerified = dict.optBoolean("lineage_verified", false)
            )
        }
    }
}

/**
 * verified payment target for an OpNS name: the holder address has been
 * recomputed from the chain (locking script of the current outpoint) and the
 * outpoint checked unspent — never pay a cached or unverified address.
 */
data class OpnsPayTarget(
    val name: String,
    val holderAddress: String,
    val currentTxid: String,
    val currentVout: Int
)

// MARK: - SNS resolver (sns.ordnet.io — signed answers)

/**
 * verified SNS payment target: signature checked against the pinned resolver
 * key, holder address derived from the SIGNED holder_script (never the
 * unsigned holder_address field), outpoint checked unspent right before pay.
 */
data class SnsPayTarget(
    val name: String,            // resolved name, e.g. "ordnet.web3"
    val mailbox: String,         // "" for a bare name
    val fallback: Boolean,       // true = mailbox unknown, paid to the name's holder
    val holderAddress: String,   // derived from the signed script
    val currentTxid: String,
    val currentVout: Int,
    val expires: Long,
    val warning: String          // inline notices (address_mismatch, key rotation)
)

// MARK: - .web3 domains (ORDnet registry)

data class MyDomain(
    val name: String,
    val status: String,
    val listingStatus: String?,
    val listingPrice: Double?
) {
    val id: String get() = name
    val isForSale: Boolean get() = listingStatus == "active"
}

data class DomainWhois(
    val status: String,
    val owner: String,
    val targetTxid: String?,
    val targetVout: Int?,
    val registeredAt: String?
)

data class DomainRecord(
    val subdomain: String?,
    val path: String?,
    val txid: String
) {
    val id: String get() = "${subdomain ?: ""}/${path ?: ""}/$txid"
}

data class DomainListing(val priceUsd: Double)

// MARK: - Inscriptions made via this app (Upload tab)

data class InscriptionRecord(
    val txid: String,
    val contentType: String,
    val filename: String,
    val bytes: Int,
    val ts: Double            // ms since epoch
) {
    val id: String get() = txid
    val date: Date get() = Date((ts).toLong())
    val sizeLabel: String
        get() = when {
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> String.format(Locale.US, "%.1f KB", bytes / 1024.0)
            else -> String.format(Locale.US, "%.2f MB", bytes / 1024.0 / 1024.0)
        }

    fun toJson(): JSONObject = JSONObject()
        .put("txid", txid).put("contentType", contentType)
        .put("filename", filename).put("bytes", bytes).put("ts", ts)

    companion object {
        fun from(o: JSONObject): InscriptionRecord? {
            val txid = o.optString("txid")
            if (txid.isEmpty()) return null
            return InscriptionRecord(
                txid = txid,
                contentType = o.optString("contentType"),
                filename = o.optString("filename"),
                bytes = o.optInt("bytes", 0),
                ts = o.optDouble("ts", 0.0)
            )
        }
    }
}

// MARK: - Address book

data class BookEntry(val name: String, val address: String, val ts: Double) {
    val id: String get() = address
}

// MARK: - dApp provider requests (window.ordplug)

data class ProviderRequest(
    val id: String,
    val method: String,
    val params: JSONObject,
    val origin: String
)

// MARK: - formatting

object Fmt {
    /** port of bsvFmt(): trims trailing zeros of an 8-decimal BSV amount */
    fun bsv(sats: Long): String {
        var s = String.format(Locale.US, "%.8f", sats / 1e8)
        while (s.endsWith("0")) s = s.dropLast(1)
        if (s.endsWith(".")) s = s.dropLast(1)
        return s
    }

    fun bsv(sats: Int): String = bsv(sats.toLong())

    fun sats(n: Long): String = NumberFormat.getNumberInstance(Locale.US).format(n)
    fun sats(n: Int): String = sats(n.toLong())

    fun shortAddress(a: String): String =
        if (a.length <= 16) a else "${a.take(10)}…${a.takeLast(6)}"

    fun shortTxid(t: String): String =
        if (t.length != 64) t else "${t.take(10)}…${t.takeLast(6)}"
}
