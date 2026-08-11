package io.ordnet.wallet.ui.wallet

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.ordnet.wallet.core.Fees
import io.ordnet.wallet.core.Fmt
import io.ordnet.wallet.core.Holding
import io.ordnet.wallet.core.HoldingKind
import io.ordnet.wallet.core.WalletStore
import io.ordnet.wallet.ui.AlertKind
import io.ordnet.wallet.ui.FormSection
import io.ordnet.wallet.ui.InlineAlert
import io.ordnet.wallet.ui.KVRow
import io.ordnet.wallet.ui.OrdnetOutlineButton
import io.ordnet.wallet.ui.OrdnetProminentButton
import io.ordnet.wallet.ui.Theme
import io.ordnet.wallet.ui.components.ButtonSpinner
import io.ordnet.wallet.ui.components.OrdnetScreen
import io.ordnet.wallet.ui.components.OrdnetTextField
import io.ordnet.wallet.ui.components.rememberQrScanner
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// MARK: - Send ordinal (SNS name / BSVmap) — true 1Sat transfer

@Composable
fun SendOrdinalView(store: WalletStore, holding: Holding, onBack: () -> Unit) {
    var to by remember { mutableStateOf("") }
    var error by remember { mutableStateOf("") }
    var success by remember { mutableStateOf("") }
    var ownerWarning by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var fees by remember { mutableStateOf<Fees?>(null) }
    val scope = rememberCoroutineScope()

    val kindLabel = holding.shortKindLabel
    val scan = rememberQrScanner { code -> to = code }

    LaunchedEffect(Unit) {
        fees = try { store.engine.fees() } catch (e: Exception) { null }
        // up-front ownership check, port of the soOwnerWarn block
        val owner = store.ordinalOwner(holding)
        if (owner != null && owner != store.address) {
            ownerWarning = "This ordinal is owned by $owner, not your active wallet (${store.address}). " +
                "You must import the seed/key that controls $owner before you can send it."
        }
    }

    fun send() {
        error = ""; success = ""
        val addr = to.trim()
        if (addr.isEmpty()) { error = "Enter a recipient address."; return }
        if (addr == store.address) { error = "That is your own address — the ordinal is already there."; return }
        if (holding.status == "contract") { error = "This ordinal sits in a contract output and cannot be sent from here."; return }
        busy = true
        scope.launch {
            try {
                if (!store.engine.validateAddress(addr)) {
                    error = "That is not a valid BSV address."; busy = false; return@launch
                }
                val txid = store.sendOrdinal(holding, addr)
                success = "Sent! ${holding.name} is on its way. TXID: $txid"
                delay(1500)
                store.loadHoldings()
            } catch (e: Exception) {
                error = e.message ?: "Send failed."
            }
            busy = false
        }
    }

    OrdnetScreen(title = "Send $kindLabel", onBack = onBack) {
        FormSection(header = "Item") {
            KVRow(k = "Name", v = holding.name)
            KVRow(k = "Type", v = holding.kindLabel)
            KVRow(k = "Ordinal UTXO", v = holding.utxoShort, mono = true)
            KVRow(k = "Status", v = holding.status)
            KVRow(k = "From wallet", v = Fmt.shortAddress(store.address), mono = true)
        }

        if (ownerWarning.isNotEmpty()) {
            InlineAlert(AlertKind.ERROR, ownerWarning)
        }

        if (holding.kind == HoldingKind.OPNS) {
            // paymail bindings are signed by the CURRENT holder and die on
            // transfer — warn inline, before the send
            InlineAlert(AlertKind.WARNING,
                "If this OpNS name has a paymail binding (${holding.name}@host), that binding expires when the name is transferred. The new owner must create a new binding.")
        }

        FormSection(header = "Recipient") {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                OrdnetTextField(
                    value = to, onValueChange = { to = it },
                    placeholder = "BSV address of the new owner",
                    mono = true, modifier = Modifier.weight(1f)
                )
                IconButton(onClick = { scan() }) {
                    Icon(Icons.Filled.QrCodeScanner, contentDescription = "Scan QR", tint = Theme.ink())
                }
            }
            fees?.let { f ->
                Text(
                    "Fee: ~${Fmt.bsv(f.ordinalMinerFee)} BSV network + ${Fmt.bsv(f.totalServiceFees)} BSV service",
                    fontSize = 12.sp, color = Theme.secondaryText()
                )
            }
        }

        FormSection {
            InlineAlert(AlertKind.ERROR, error)
            InlineAlert(AlertKind.SUCCESS, success)
            OrdnetProminentButton(
                onClick = { send() },
                enabled = !busy && ownerWarning.isEmpty(),
                modifier = Modifier.fillMaxWidth()
            ) {
                if (busy) ButtonSpinner() else Text("Send $kindLabel", fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

// MARK: - List for sale (Optie-1 atomic swap, two-step confirm)

@Composable
fun ListOrdinalView(store: WalletStore, holding: Holding, onBack: () -> Unit) {
    var priceBSV by remember { mutableStateOf("") }
    var confirming by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var success by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    fun priceSats(): Long {
        val v = priceBSV.replace(",", ".").toDoubleOrNull() ?: 0.0
        return if (v > 0) Math.round(v * 1e8) else 0L
    }

    fun toConfirm() {
        error = ""
        if (priceSats() < 1) { error = "Enter a price in BSV (minimum 0.00000001)."; return }
        // OpNS: display, resolve and send only — no marketplace flows
        if (holding.kind == HoldingKind.OPNS) {
            error = "OpNS names cannot be listed for sale from this wallet."; return
        }
        if (holding.kind != HoldingKind.BSVMAP) {
            error = "Marketplace listing is currently for BSVmaps. SNS listings coming soon."; return
        }
        confirming = true
    }

    fun list() {
        error = ""; busy = true
        scope.launch {
            try {
                store.listRequest(holding, priceSats())
                // trust-but-verify: HTTP 200 does not guarantee the global registry got it
                val missing = store.verifyListedInRegistry(listOf(holding))
                if (missing.isNotEmpty()) {
                    throw Exception(
                        "The server accepted the listing (HTTP 200) and wrote the district record, but after ~30 seconds of re-checking it still hasn't appeared in the global GET /listings registry — the registry may be full or out of sync SERVER-side. Pull to refresh in a minute; if it stays unlisted, the server needs a fix.")
                }
                success = "Listed for ${Fmt.bsv(priceSats())} BSV — verified present in the marketplace registry! Turns green on bsvmap.io within a minute."
                delay(1500)
                store.loadHoldings()
            } catch (e: Exception) {
                error = e.message ?: "Listing failed."
            }
            busy = false
        }
    }

    OrdnetScreen(title = "List for sale", onBack = onBack) {
        FormSection(header = "Item") {
            KVRow(k = "Name", v = holding.name)
            KVRow(k = "Type", v = holding.kindLabel)
            KVRow(k = "Ordinal UTXO", v = holding.utxoShort, mono = true)
        }

        if (!confirming) {
            FormSection(header = "Price") {
                OrdnetTextField(
                    value = priceBSV, onValueChange = { priceBSV = it },
                    placeholder = "Price in BSV (e.g. 0.0001)",
                    keyboardType = androidx.compose.ui.text.input.KeyboardType.Decimal
                )
                if (priceSats() >= 1) {
                    Text("= ${Fmt.sats(priceSats())} sats", fontSize = 12.sp, color = Theme.secondaryText())
                }
                Text(
                    "You sign a one-sided atomic swap. The ordinal stays in your wallet until a buyer pays your price.",
                    fontSize = 12.sp, color = Theme.secondaryText()
                )
            }
            FormSection {
                InlineAlert(AlertKind.ERROR, error)
                OrdnetProminentButton(onClick = { toConfirm() }, modifier = Modifier.fillMaxWidth()) {
                    Text("Continue", fontWeight = FontWeight.SemiBold)
                }
            }
        } else {
            FormSection(header = "Confirm — exactly this will be signed") {
                KVRow(k = "Item", v = holding.name)
                KVRow(k = "Price", v = "${Fmt.bsv(priceSats())} BSV (${Fmt.sats(priceSats())} sats)")
                KVRow(k = "Paid to", v = Fmt.shortAddress(store.address), mono = true)
                KVRow(k = "Ordinal", v = holding.utxoShort, mono = true)
            }
            FormSection {
                InlineAlert(AlertKind.ERROR, error)
                InlineAlert(AlertKind.SUCCESS, success)
                if (success.isEmpty()) {
                    OrdnetProminentButton(onClick = { list() }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                        if (busy) ButtonSpinner() else Text("Confirm & sign", fontWeight = FontWeight.SemiBold)
                    }
                    OrdnetOutlineButton(onClick = { confirming = false }, enabled = !busy) {
                        Text("Back")
                    }
                }
            }
        }
    }
}

// MARK: - Delist (signed instruction + verify-gone)

@Composable
fun DelistView(store: WalletStore, holding: Holding, onBack: () -> Unit) {
    var error by remember { mutableStateOf("") }
    var success by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    fun delist() {
        error = ""; busy = true
        scope.launch {
            try {
                store.delistRequest(holding)
                val still = store.verifyStillListed(listOf(holding))
                if (still.isNotEmpty()) {
                    val whereStr = still.first().second
                    throw Exception(
                        "The server answered OK but after re-checking with a grace period the listing is still present in the $whereStr. The server-side delist must clear BOTH the global registry and the per-district record.")
                }
                success = "Listing removed and verified gone from the registry — ${holding.name} is no longer for sale."
                delay(1200)
                store.loadHoldings()
            } catch (e: Exception) {
                error = e.message ?: "Delist failed."
            }
            busy = false
        }
    }

    OrdnetScreen(title = "Remove listing", onBack = onBack) {
        FormSection(header = "Listing") {
            KVRow(k = "Item", v = holding.name)
            KVRow(k = "Type", v = holding.kindLabel)
            val p = holding.priceSat
            if (p != null && p > 0) {
                KVRow(k = "Price", v = "${Fmt.bsv(p)} BSV (${Fmt.sats(p)} sats)")
            }
            KVRow(k = "Ordinal UTXO", v = holding.utxoShort, mono = true)
            KVRow(k = "Seller", v = Fmt.shortAddress(store.address), mono = true)
        }
        FormSection {
            Text(
                "You sign a delist instruction with your seller key — no coins move. The wallet then verifies the listing is really gone from BOTH server stores.",
                fontSize = 12.sp, color = Theme.secondaryText()
            )
            InlineAlert(AlertKind.ERROR, error)
            InlineAlert(AlertKind.SUCCESS, success)
            if (success.isEmpty()) {
                OrdnetProminentButton(onClick = { delist() }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                    if (busy) ButtonSpinner() else Text("Sign & remove listing", fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

// MARK: - Bulk list / delist (max 300 per run, rate-limit friendly, trust-but-verify)

enum class BulkKind { LIST, DELIST }

@Composable
fun BulkActionSheet(
    store: WalletStore,
    kind: BulkKind,
    items: List<Holding>,
    onDone: () -> Unit
) {
    var priceBSV by remember { mutableStateOf("") }
    var progress by remember { mutableStateOf("") }
    var error by remember { mutableStateOf("") }
    var success by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    fun priceSats(): Long {
        val v = priceBSV.replace(",", ".").toDoubleOrNull() ?: 0.0
        return if (v > 0) Math.round(v * 1e8) else 0L
    }

    fun run() {
        error = ""; success = ""; busy = true
        val isDelist = (kind == BulkKind.DELIST)
        val price = priceSats()
        scope.launch {
            var done = 0
            val failed = ArrayList<String>()
            val okItems = ArrayList<Holding>()
            for ((i, it) in items.withIndex()) {
                progress = "${if (isDelist) "Delisting" else "Listing"} ${it.name} (${i + 1}/${items.size})…"
                if (i > 0) delay(250)   // stay under API rate limits
                try {
                    if (isDelist) store.delistRequest(it) else store.listRequest(it, price)
                    done += 1
                    okItems.add(it)
                } catch (e: Exception) {
                    failed.add("${it.name} (${e.message})")
                }
            }
            if (isDelist && okItems.isNotEmpty()) {
                progress = "Verifying removal on the server…"
                val still = store.verifyStillListed(okItems)
                if (still.isNotEmpty()) {
                    done -= still.size
                    for ((it, whereStr) in still) failed.add("${it.name} (still in the $whereStr)")
                }
            }
            if (!isDelist && okItems.isNotEmpty()) {
                progress = "Verifying listings in the marketplace registry…"
                val missing = store.verifyListedInRegistry(okItems)
                if (missing.isNotEmpty()) {
                    done -= missing.size
                    for (it in missing) failed.add("${it.name} (accepted by the server but NOT in the global registry — registry full/out of sync server-side)")
                }
            }
            progress = ""
            if (failed.isNotEmpty()) {
                error = "$done ${if (isDelist) "delisted" else "listed"}, ${failed.size} failed: " +
                    failed.take(4).joinToString(", ") + (if (failed.size > 4) " …" else "")
            } else {
                success = if (isDelist) "All $done listings removed."
                else "All $done items listed for ${Fmt.bsv(price)} BSV each! Turning green on bsvmap.io within a minute."
            }
            store.loadHoldings()
            busy = false
        }
    }

    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
            .padding(bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text(
                if (kind == BulkKind.LIST) "Bulk list" else "Bulk delist",
                fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = Theme.ink(),
                modifier = Modifier.weight(1f)
            )
            OrdnetOutlineButton(onClick = { if (!busy) onDone() }, enabled = !busy) {
                Text(if (busy) "Working…" else "Close")
            }
        }

        Text(
            "${items.size} BSVmap${if (items.size == 1) "" else "s"} selected",
            fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Theme.ink()
        )

        if (kind == BulkKind.LIST) {
            FormSection(header = "Price per item") {
                OrdnetTextField(
                    value = priceBSV, onValueChange = { priceBSV = it },
                    placeholder = "Price in BSV per item",
                    keyboardType = androidx.compose.ui.text.input.KeyboardType.Decimal
                )
                if (priceSats() >= 1) {
                    Text(
                        "= ${Fmt.sats(priceSats())} sats per item · ${Fmt.bsv(priceSats() * items.size)} BSV total if all sell",
                        fontSize = 12.sp, color = Theme.secondaryText()
                    )
                }
            }
        }

        if (progress.isNotEmpty()) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                if (busy) {
                    CircularProgressIndicator(color = Theme.ink(), strokeWidth = 2.dp, modifier = Modifier.size(16.dp))
                }
                Text(progress, fontSize = 13.sp, color = Theme.ink())
            }
        }
        InlineAlert(AlertKind.ERROR, error)
        InlineAlert(AlertKind.SUCCESS, success)
        if (success.isEmpty()) {
            OrdnetProminentButton(
                onClick = { run() },
                enabled = !busy && !(kind == BulkKind.LIST && priceSats() < 1) && items.isNotEmpty(),
                modifier = Modifier.fillMaxWidth()
            ) {
                if (busy) ButtonSpinner()
                else Text(
                    if (kind == BulkKind.LIST) "Sign ${items.size} listing${if (items.size == 1) "" else "s"}"
                    else "Sign ${items.size} delisting${if (items.size == 1) "" else "s"}",
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }
}
