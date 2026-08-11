package io.ordnet.wallet.ui.domains

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.ordnet.wallet.core.Api
import io.ordnet.wallet.core.DomainListing
import io.ordnet.wallet.core.DomainRecord
import io.ordnet.wallet.core.DomainWhois
import io.ordnet.wallet.core.Fmt
import io.ordnet.wallet.core.MyDomain
import io.ordnet.wallet.core.WalletEngine
import io.ordnet.wallet.core.WalletStore
import io.ordnet.wallet.ui.AlertKind
import io.ordnet.wallet.ui.FormSection
import io.ordnet.wallet.ui.InlineAlert
import io.ordnet.wallet.ui.KVRow
import io.ordnet.wallet.ui.OrdnetOutlineButton
import io.ordnet.wallet.ui.OrdnetProminentButton
import io.ordnet.wallet.ui.Theme
import io.ordnet.wallet.ui.components.OrdnetScreen
import io.ordnet.wallet.ui.components.OrdnetTextField
import io.ordnet.wallet.ui.components.SpinnerRow
import io.ordnet.wallet.ui.home.PagerBar
import java.util.Locale
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * "My .web3 domains" — port of the extension's browse + domain detail views:
 * registry list, whois, signed set-target, subdomains, routes, marketplace
 * (list/update/delist in USD) and domain transfer. All writes are signed
 * wallet actions (key = ownership) in the exact `ordnet-registry|…` format.
 */
@Composable
fun DomainsView(store: WalletStore) {
    var detail by remember { mutableStateOf<String?>(null) }

    // v3.3 — cross-tab request from the Wallet tab ("Manage domain listing"):
    // consume on appear and on change, like the browser-open request
    androidx.compose.runtime.LaunchedEffect(store.domainsOpenRequest) {
        val q = store.domainsOpenRequest ?: return@LaunchedEffect
        store.domainsOpenRequest = null
        detail = q
    }

    val d = detail
    if (d != null) {
        DomainDetailView(store, name = d, onBack = { detail = null })
    } else {
        DomainsList(store, onOpen = { detail = it })
    }
}

@Composable
private fun DomainsList(store: WalletStore, onOpen: (String) -> Unit) {
    var domains by remember { mutableStateOf<List<MyDomain>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf("") }
    // v2.1 — zoekveld + paginering (10 per pagina), balk BOVEN de lijst
    var search by remember { mutableStateOf("") }
    var page by remember { mutableStateOf(0) }

    suspend fun load() {
        error = ""
        try {
            domains = Api.myDomains(store.address)
        } catch (e: Exception) {
            error = "Could not load your domains right now."
        }
        loading = false
    }

    LaunchedEffect(store.address) { load() }

    OrdnetScreen(title = "Domains") {
        FormSection(
            header = "My .web3 domains",
            footer = "Domains owned by ${Fmt.shortAddress(store.address)}"
        ) {
            when {
                loading -> SpinnerRow()
                error.isNotEmpty() -> Text(error, fontSize = 13.sp, color = Theme.secondaryText())
                domains.isEmpty() -> Text(
                    "No .web3 domains on this wallet yet — claim one via ORD/domains in the Browser tab.",
                    fontSize = 13.sp, color = Theme.secondaryText()
                )
                else -> {
                    // v2.1 — volgorde: zoekveld -> pagineringsbalk -> domeinen (SNS-patroon)
                    val q = search.trim().lowercase()
                    val filtered = if (q.isEmpty()) domains else domains.filter { it.name.lowercase().contains(q) }
                    val perPage = 10
                    val pages = maxOf(1, (filtered.size + perPage - 1) / perPage)
                    val safePage = page.coerceIn(0, pages - 1)
                    val pageItems = filtered.drop(safePage * perPage).take(perPage)

                    OrdnetTextField(value = search, onValueChange = { search = it; page = 0 },
                        placeholder = "Search…")
                    if (pages > 1) {
                        PagerBar(
                            page = safePage, pages = pages, total = filtered.size,
                            onPrev = { page = safePage - 1 },
                            onNext = { page = safePage + 1 }
                        )
                    }
                    if (filtered.isEmpty()) {
                        Text("No domains match \"$search\".", fontSize = 13.sp, color = Theme.secondaryText())
                    } else pageItems.forEach { d ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().clickable { onOpen(d.name) }.padding(vertical = 6.dp)
                    ) {
                        Text(d.name, fontSize = 15.sp, fontWeight = FontWeight.Medium, color = Theme.ink())
                        Spacer(Modifier.weight(1f))
                        if (d.isForSale) {
                            Text(
                                "For sale" + (d.listingPrice?.let { String.format(Locale.US, " · $%.0f", it) } ?: ""),
                                fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
                                color = Theme.statusGreen,
                                modifier = Modifier
                                    .clip(CircleShape)
                                    .background(Theme.statusGreen.copy(alpha = 0.15f))
                                    .padding(horizontal = 7.dp, vertical = 3.dp)
                            )
                        } else {
                            Text(
                                d.status,
                                fontSize = 11.sp,
                                color = Theme.secondaryText(),
                                modifier = Modifier
                                    .clip(CircleShape)
                                    .background(Theme.secondaryText().copy(alpha = 0.12f))
                                    .padding(horizontal = 7.dp, vertical = 3.dp)
                            )
                        }
                    }
                    }
                }
            }
        }
    }
}

// MARK: - Domain detail

@Composable
fun DomainDetailView(store: WalletStore, name: String, onBack: () -> Unit) {
    var whois by remember { mutableStateOf<DomainWhois?>(null) }
    var subs by remember { mutableStateOf<List<DomainRecord>>(emptyList()) }
    var routes by remember { mutableStateOf<List<DomainRecord>>(emptyList()) }
    var listing by remember { mutableStateOf<DomainListing?>(null) }

    var targetTxid by remember { mutableStateOf("") }
    var targetVout by remember { mutableStateOf("") }
    var subNew by remember { mutableStateOf("") }
    var subTx by remember { mutableStateOf("") }
    var rtPath by remember { mutableStateOf("") }
    var rtSub by remember { mutableStateOf("") }
    var rtTx by remember { mutableStateOf("") }
    var mktPrice by remember { mutableStateOf("") }
    var trAddr by remember { mutableStateOf("") }
    var trConfirm by remember { mutableStateOf("") }

    var error by remember { mutableStateOf("") }
    var ok by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    suspend fun load() {
        whois = try { Api.whois(name) } catch (e: Exception) { null }
        whois?.let { w ->
            targetTxid = w.targetTxid ?: ""
            targetVout = w.targetVout?.toString() ?: ""
        }
        try {
            val recs = Api.domainRecords(name)
            subs = recs.subs
            routes = recs.routes
            listing = recs.listing
        } catch (e: Exception) { }
    }

    LaunchedEffect(name) { load() }

    fun signedAction(run: suspend () -> String) {
        error = ""; ok = ""; busy = true
        scope.launch {
            try {
                ok = run()
                load()
            } catch (e: Exception) {
                error = e.message ?: "Action failed."
            }
            busy = false
        }
    }

    /** port of parseTx(): TXID with optional :vout */
    fun parseTx(v: String): Pair<String, Int>? {
        val s = v.trim().lowercase()
        val m = Regex("^([0-9a-f]{64})(?::(\\d+))?$").find(s) ?: return null
        return Pair(m.groupValues[1], m.groupValues[2].toIntOrNull() ?: 0)
    }

    fun validName(s: String): Boolean =
        Regex("^[a-z0-9][a-z0-9-]{0,62}[a-z0-9]?$").matches(s)

    /**
     * format a number exactly like JavaScript's String(n) — the signed message
     * must match the extension byte-for-byte ("25", not "25.0")
     */
    fun jsNum(v: Double): String =
        if (v == Math.rint(v) && Math.abs(v) < 1e15) v.toLong().toString() else v.toString()

    fun saveTarget() {
        error = ""; ok = ""
        val txid = targetTxid.trim().lowercase()
        val vout = targetVout.toIntOrNull() ?: 0
        if (!Regex("^[0-9a-f]{64}$").matches(txid)) {
            error = "Enter a valid 64-character transaction ID."
            return
        }
        if (vout < 0) { error = "Output index must be 0 or higher."; return }
        busy = true
        scope.launch {
            try {
                store.setDomainTarget(domain = name, txid = txid, vout = vout)
                ok = "Target updated ✓"
                load()
            } catch (e: Exception) {
                error = e.message ?: "Could not save."
            }
            busy = false
        }
    }

    fun addSubdomain() {
        error = ""; ok = ""
        val sd = subNew.trim().lowercase()
        if (!validName(sd)) { error = "Invalid subdomain name (alphanumeric, hyphens)."; return }
        val tx = parseTx(subTx) ?: run { error = "Enter a valid TXID, optionally as TXID:vout."; return }
        signedAction {
            store.signedRegistryPost("/wallet/subdomain", action = "subdomain",
                fields = listOf(name, sd, tx.first, tx.second.toString()),
                body = JSONObject().put("domain", name).put("subdomain", sd)
                    .put("txid", tx.first).put("vout", tx.second))
            subNew = ""; subTx = ""
            "Subdomain saved ✓"
        }
    }

    fun addRoute() {
        error = ""; ok = ""
        var pth = rtPath.trim().lowercase()
        while (pth.startsWith("/")) pth = pth.drop(1)
        val sub = rtSub.trim().lowercase()
        if (!validName(pth)) { error = "Invalid path (alphanumeric, hyphens)."; return }
        val tx = parseTx(rtTx) ?: run { error = "Enter a valid TXID, optionally as TXID:vout."; return }
        signedAction {
            try {
                store.signedRegistryPost("/wallet/route", action = "route",
                    fields = listOf(name, sub, pth, tx.first, tx.second.toString()),
                    body = JSONObject().put("domain", name)
                        .put("subdomain", if (sub.isEmpty()) JSONObject.NULL else sub)
                        .put("path", pth).put("txid", tx.first).put("vout", tx.second))
            } catch (e: Exception) {
                if ((e.message ?: "").contains("subdomain_not_found")) {
                    throw WalletEngine.EngineException("That subdomain does not exist yet — create it first.")
                }
                throw e
            }
            rtPath = ""; rtSub = ""; rtTx = ""
            "Route saved ✓"
        }
    }

    fun listOrUpdate(update: Boolean) {
        error = ""; ok = ""
        val price = mktPrice.toDoubleOrNull()
        if (price == null || price <= 0) { error = "Enter a valid price in USD."; return }
        signedAction {
            try {
                if (update) {
                    store.signedRegistryPost("/wallet/listing-update", action = "listing-update",
                        fields = listOf(name, jsNum(price)),
                        body = JSONObject().put("domain", name).put("price_usd", price))
                } else {
                    store.signedRegistryPost("/wallet/list", action = "list",
                        fields = listOf(name, jsNum(price)),
                        body = JSONObject().put("domain", name).put("price_usd", price))
                }
            } catch (e: Exception) {
                val m = e.message ?: ""
                if (m.contains("invalid_price")) throw WalletEngine.EngineException("Price is below the minimum listing price.")
                if (m.contains("has_pending_order")) throw WalletEngine.EngineException("A purchase is in progress — listing is locked.")
                throw e
            }
            if (update) "Price updated ✓" else "Listed for sale ✓"
        }
    }

    fun transfer() {
        error = ""; ok = ""
        val to = trAddr.trim()
        if (!Regex("^1[a-km-zA-HJ-NP-Z1-9]{25,34}$").matches(to)) {
            error = "Enter a valid BSV address for the new owner."
            return
        }
        if (trConfirm.trim().lowercase() != name) {
            error = "Type the domain name exactly to confirm the transfer."
            return
        }
        signedAction {
            try {
                store.signedRegistryPost("/wallet/transfer", action = "transfer",
                    fields = listOf(name, to),
                    body = JSONObject().put("domain", name).put("new_owner", to))
            } catch (e: Exception) {
                if ((e.message ?: "").contains("listed_delist_first")) {
                    throw WalletEngine.EngineException("This domain is listed for sale — delist it first.")
                }
                throw e
            }
            trAddr = ""; trConfirm = ""
            "Domain transferred ✓"
        }
    }

    OrdnetScreen(title = name, onBack = onBack) {
        FormSection(header = "Domain") {
            val w = whois
            if (w != null) {
                KVRow(k = "Status", v = w.status)
                KVRow(k = "Owner", v = Fmt.shortAddress(w.owner), mono = true)
                KVRow(k = "Target", v = w.targetTxid?.let { it.take(16) + "…" } ?: "not set", mono = true)
                KVRow(k = "Registered", v = w.registeredAt ?: "—")
            } else {
                SpinnerRow()
            }
        }

        InlineAlert(AlertKind.ERROR, error)
        InlineAlert(AlertKind.SUCCESS, ok)

        FormSection(header = "Content target (TXID the domain points to)") {
            OrdnetTextField(value = targetTxid, onValueChange = { targetTxid = it },
                placeholder = "Transaction ID (64 hex chars)", mono = true)
            OrdnetTextField(value = targetVout, onValueChange = { targetVout = it },
                placeholder = "Output index (vout, default 0)",
                keyboardType = androidx.compose.ui.text.input.KeyboardType.Number)
            OrdnetProminentButton(onClick = { saveTarget() }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                Text("Sign & save target")
            }
            OrdnetOutlineButton(onClick = {
                signedAction {
                    // v3.2 (iOS v2.5.2 parity): same fix as set-target — the
                    // target handlers identify the domain by the canonical
                    // `name` field
                    store.signedRegistryPost("/wallet/remove-target", action = "remove-target",
                        fields = listOf(name), body = JSONObject().put("name", name).put("domain", name))
                    targetTxid = ""; targetVout = ""
                    "Target removed ✓"
                }
            }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                Text("Remove target", color = Theme.statusRed)
            }
        }

        FormSection(header = "Subdomains") {
            subs.forEach { r ->
                RecordRow(label = r.subdomain ?: "", txid = r.txid, enabled = !busy) {
                    signedAction {
                        store.signedRegistryPost("/wallet/subdomain-delete", action = "subdomain-delete",
                            fields = listOf(name, r.subdomain ?: ""),
                            body = JSONObject().put("domain", name).put("subdomain", r.subdomain ?: ""))
                        "Subdomain removed ✓"
                    }
                }
            }
            OrdnetTextField(value = subNew, onValueChange = { subNew = it },
                placeholder = "subdomain (e.g. blog)")
            OrdnetTextField(value = subTx, onValueChange = { subTx = it },
                placeholder = "TXID or TXID:vout", mono = true)
            OrdnetOutlineButton(onClick = { addSubdomain() }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                Text("Add subdomain")
            }
        }

        FormSection(header = "Routes (paths)") {
            routes.forEach { r ->
                RecordRow(
                    label = "${r.subdomain?.let { "$it · " } ?: ""}/${r.path ?: ""}",
                    txid = r.txid, enabled = !busy
                ) {
                    signedAction {
                        store.signedRegistryPost("/wallet/route-delete", action = "route-delete",
                            fields = listOf(name, r.subdomain ?: "", r.path ?: ""),
                            body = JSONObject().put("domain", name)
                                .put("subdomain", r.subdomain ?: JSONObject.NULL)
                                .put("path", r.path ?: ""))
                        "Route removed ✓"
                    }
                }
            }
            OrdnetTextField(value = rtPath, onValueChange = { rtPath = it },
                placeholder = "path (e.g. about)")
            OrdnetTextField(value = rtSub, onValueChange = { rtSub = it },
                placeholder = "subdomain (optional)")
            OrdnetTextField(value = rtTx, onValueChange = { rtTx = it },
                placeholder = "TXID or TXID:vout", mono = true)
            OrdnetOutlineButton(onClick = { addRoute() }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                Text("Add route")
            }
        }

        FormSection(header = "Marketplace") {
            val l = listing
            if (l != null) {
                KVRow(k = "Listed", v = String.format(Locale.US, "$%.0f", l.priceUsd))
                OrdnetTextField(value = mktPrice, onValueChange = { mktPrice = it },
                    placeholder = "New price USD",
                    keyboardType = androidx.compose.ui.text.input.KeyboardType.Number)
                OrdnetOutlineButton(onClick = { listOrUpdate(update = true) }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                    Text("Update price")
                }
                OrdnetOutlineButton(onClick = {
                    signedAction {
                        store.signedRegistryPost("/wallet/delist", action = "delist",
                            fields = listOf(name), body = JSONObject().put("domain", name))
                        "Delisted ✓"
                    }
                }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                    Text("Delist", color = Theme.statusRed)
                }
            } else {
                OrdnetTextField(value = mktPrice, onValueChange = { mktPrice = it },
                    placeholder = "Price USD",
                    keyboardType = androidx.compose.ui.text.input.KeyboardType.Number)
                OrdnetOutlineButton(onClick = { listOrUpdate(update = false) }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                    Text("List for sale")
                }
            }
        }

        FormSection(header = "Transfer domain") {
            OrdnetTextField(value = trAddr, onValueChange = { trAddr = it },
                placeholder = "New owner BSV address", mono = true)
            OrdnetTextField(value = trConfirm, onValueChange = { trConfirm = it },
                placeholder = "Type the domain name to confirm")
            OrdnetProminentButton(onClick = { transfer() }, enabled = !busy, destructive = true,
                modifier = Modifier.fillMaxWidth()) {
                Text("Sign & transfer domain", fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
private fun RecordRow(label: String, txid: String, enabled: Boolean, onDelete: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(label, fontSize = 15.sp, color = Theme.ink())
        Spacer(Modifier.weight(1f))
        Text(txid.take(12) + "…", fontSize = 11.sp, fontFamily = FontFamily.Monospace,
            color = Theme.secondaryText())
        IconButton(onClick = onDelete, enabled = enabled) {
            Icon(Icons.Filled.Delete, contentDescription = "Delete",
                tint = Theme.statusRed, modifier = Modifier.size(18.dp))
        }
    }
}
