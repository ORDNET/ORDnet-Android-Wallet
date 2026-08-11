package io.ordnet.wallet.ui.wallet

import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.CompareArrows
import androidx.compose.material.icons.filled.Book
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.ordnet.wallet.core.Api
import io.ordnet.wallet.core.Fees
import io.ordnet.wallet.core.Fmt
import io.ordnet.wallet.core.HistoryTx
import io.ordnet.wallet.core.OpnsPayTarget
import io.ordnet.wallet.core.SnsPayTarget
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
import io.ordnet.wallet.ui.components.QRCodeView
import io.ordnet.wallet.ui.components.SpinnerRow
import io.ordnet.wallet.ui.components.rememberQrScanner
import io.ordnet.wallet.ui.settings.AddressBookScreen
import kotlinx.coroutines.launch

/**
 * Send BSV — port of the extension's send view, including the safety layer:
 * first-time-address warning, near-full-balance warning, self-send detection,
 * clipboard paste verification and send-max.
 */
@Composable
fun SendView(store: WalletStore, onBack: () -> Unit) {
    var to by remember { mutableStateOf("") }
    var amount by remember { mutableStateOf("") }
    var error by remember { mutableStateOf("") }
    var success by remember { mutableStateOf("") }
    var warnings by remember { mutableStateOf<List<String>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }
    var lastSentAddr by remember { mutableStateOf<String?>(null) }
    var showSaveToBook by remember { mutableStateOf(false) }
    var fees by remember { mutableStateOf<Fees?>(null) }
    var showBook by remember { mutableStateOf(false) }
    /** v3.1 — verified OpNS payment target (two-tap confirm: first Send tap
     *  resolves + verifies, second tap re-verifies and pays) */
    var opnsTarget by remember { mutableStateOf<OpnsPayTarget?>(null) }
    /** v3.1 — verified SNS payment target (same two-tap pattern; signed
     *  resolver answers, level "prove") */
    var snsTarget by remember { mutableStateOf<SnsPayTarget?>(null) }

    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current

    if (showBook && lastSentAddr != null) {
        AddressBookScreen(store, prefillAddress = lastSentAddr ?: "", onBack = { showBook = false })
        return
    }

    /** amount is entered in BSV and converted to sats internally */
    fun amountSats(): Long {
        val v = amount.replace(",", ".").toDoubleOrNull() ?: 0.0
        return if (v > 0) Math.round(v * 1e8) else 0L
    }

    // MARK: name recognition (v3.1 — OpNS + SNS, iOS v2.1/v2.2 parity)

    /** bare OpNS name candidate: a-z, 0-9, hyphen — and NO dot (a dotted name
     *  is SNS, never OpNS) and no @ (paymail is not a payment target here).
     *  Callers ALSO exclude valid BSV addresses via the (suspend) engine check. */
    fun opnsNameCandidate(s: String): String? {
        val t = s.trim().lowercase()
        if (t.isEmpty() || t.contains(".") || t.contains("@")) return null
        return if (Regex("^[a-z0-9-]+$").matches(t)) t else null
    }

    /** SNS candidate: `naam.tld` or `mailbox@naam.tld` — a dot in the domain
     *  part is what separates SNS from OpNS. ASCII lowercase only by
     *  construction, so homograph/mixed-script inputs never reach the
     *  resolver from here. The TLD list is NOT hardcoded: the resolver itself
     *  answers unknown_tld/retired_tld with a readable inline message. */
    fun snsInputCandidate(s: String): String? {
        val t = s.trim().lowercase()
        val re = Regex("^(?:[a-z0-9][a-z0-9._-]{0,63}@)?(?:[a-z0-9][a-z0-9-]{0,62}\\.)+[a-z][a-z0-9-]{1,24}$")
        return if (re.matches(t)) t else null
    }

    // MARK: safety — port of evaluateSendSafety()
    fun evaluateSafety() {
        scope.launch {
            val notes = ArrayList<String>()
            val addr = to.trim()
            if (addr.isNotEmpty() && !store.engine.validateAddress(addr)) {
                if (snsInputCandidate(addr) != null) {
                    notes.add("This looks like an SNS name${if (addr.contains("@")) " mailbox" else ""}. Press Send to resolve it via the signed SNS resolver — you confirm the verified holder address before anything is paid.")
                } else if (opnsNameCandidate(addr) != null) {
                    notes.add("This looks like a bare OpNS name (no dot = not SNS). Press Send to resolve it — exact match only, and you confirm the verified holder address before anything is paid.")
                }
            }
            if (addr.isNotEmpty() && store.engine.validateAddress(addr)) {
                if (addr == store.address) {
                    notes.add("This is your own active address — the coins will not leave this wallet.")
                } else if (store.bookLabel(addr) == null && store.accounts.none { it.address == addr }) {
                    notes.add("First time sending to this address. Double-check it character by character — BSV transfers cannot be reversed.")
                } else {
                    store.bookLabel(addr)?.let { lbl ->
                        notes.add("Recipient: \"$lbl\" from your address book.")
                    }
                }
            }
            val amt = amountSats()
            val bal = store.balance
            val f = fees
            if (amt > 0 && bal != null && f != null) {
                val spendable = bal.confirmed - (f.sendMinerFee + f.totalServiceFees)
                if (amt >= spendable && spendable > 0) {
                    notes.add("This sends essentially your entire spendable balance.")
                }
            }
            warnings = notes
        }
    }

    val scan = rememberQrScanner { code ->
        to = code
        opnsTarget = null   // input changed → stale confirmations die
        snsTarget = null
        evaluateSafety()
    }

    /** clipboard paste with verification — defends against clipboard-hijack malware */
    fun pasteVerified() {
        error = ""
        val txt = clipboard.getText()?.text?.trim() ?: ""
        if (txt.isEmpty()) {
            error = "Clipboard is empty."
            return
        }
        scope.launch {
            if (!store.engine.validateAddress(txt)) {
                error = "Clipboard does not contain a valid BSV address."
                return@launch
            }
            to = txt
            opnsTarget = null   // input changed → stale confirmations die
            snsTarget = null
            evaluateSafety()
        }
    }

    fun sendMax() {
        error = ""
        scope.launch {
            store.refreshBalance()
            val bal = store.balance
            val f = fees
            if (bal == null || f == null) {
                error = "Could not read balance for max."
                return@launch
            }
            val spendable = bal.confirmed // only confirmed sats are safely spendable
            val max = spendable - (f.sendMinerFee + f.totalServiceFees)
            if (max < 1) {
                error = "Balance too low to cover the network + service fee."
                amount = ""
            } else {
                amount = Fmt.bsv(max)
                evaluateSafety()
            }
        }
    }

    /** the actual broadcast + aftercare — shared by the address path and the
     *  verified SNS/OpNS paths */
    suspend fun performSend(addr: String, amt: Long) {
        try {
            val txid = store.sendBSV(to = addr, amountSat = amt)
            success = "Sent! TXID: $txid"
            lastSentAddr = addr
            if (store.bookLabel(addr) == null && store.accounts.none { it.address == addr }) {
                showSaveToBook = true
            }
            to = ""; amount = ""; warnings = emptyList()
            store.refreshBalance()
        } catch (e: Exception) {
            error = e.message ?: "Send failed."
        }
    }

    fun send() {
        error = ""; success = ""
        val addr = to.trim()
        if (addr.isEmpty()) { error = "Enter a recipient address."; return }
        val amt = amountSats()
        busy = true
        scope.launch {
            try {
                // plain BSV address — the original path, unchanged
                if (store.engine.validateAddress(addr)) {
                    opnsTarget = null
                    snsTarget = null
                    if (amt < 1) {
                        error = "Enter an amount in BSV (minimum 0.00000001)."; busy = false; return@launch
                    }
                    performSend(addr, amt)
                    busy = false; return@launch
                }

                // v3.1 — SNS name or mailbox (naam.tld / mailbox@naam.tld):
                // resolve via the SIGNED resolver, two-tap confirm, re-verified
                // at signing. Freshness/expires checks live in resolveSnsPayment.
                val snsInput = snsInputCandidate(addr)
                if (snsInput != null) {
                    if (amt < 1) {
                        error = "Enter an amount in BSV (minimum 0.00000001)."; busy = false; return@launch
                    }
                    try {
                        val target = store.resolveSnsPayment(snsInput)
                        val seen = snsTarget
                        if (seen != null &&
                            (seen == target || (seen.name == target.name && seen.holderAddress == target.holderAddress))
                        ) {
                            // same verified holder; freshness fields may have moved
                            // (expires/outpoint re-issued) — safe to pay
                            performSend(target.holderAddress, amt)
                            snsTarget = null
                        } else if (seen != null) {
                            snsTarget = target
                            error = "The verified details of ${target.name} changed while you were confirming — review them and press the button again. Nothing was paid."
                        } else {
                            snsTarget = target
                        }
                    } catch (e: Exception) {
                        snsTarget = null
                        error = e.message ?: "SNS resolve failed."
                    }
                    busy = false; return@launch
                }

                // v3.1 — not an address, not SNS: OpNS name or paymail?
                if (addr.contains("@")) {
                    error = "Paymail (name@host) is not accepted as a payment target: any host can serve any name and bindings expire on transfer. Enter the bare OpNS name, an SNS mailbox (mailbox@naam.tld) or a BSV address."
                    busy = false; return@launch
                }
                val name = opnsNameCandidate(addr)
                if (name == null) {
                    error = "That is not a valid BSV address."
                    busy = false; return@launch
                }
                if (amt < 1) {
                    error = "Enter an amount in BSV (minimum 0.00000001)."; busy = false; return@launch
                }

                // two-tap confirm; the resolve (exact match + on-chain recompute +
                // unspent outpoint) runs on EVERY tap, so the confirm tap
                // re-verifies right before broadcasting — never a cached address
                try {
                    val target = store.resolveOpnsPayment(name)
                    val seen = opnsTarget
                    if (seen != null && seen == target) {
                        performSend(target.holderAddress, amt)
                        opnsTarget = null
                    } else if (seen != null) {
                        opnsTarget = target
                        error = "The verified details of \"${target.name}\" changed while you were confirming — review them and press the button again. Nothing was paid."
                    } else {
                        opnsTarget = target
                    }
                } catch (e: Exception) {
                    opnsTarget = null
                    error = e.message ?: "OpNS resolve failed."
                }
            } catch (e: Exception) {
                error = e.message ?: "Send failed."
            }
            busy = false
        }
    }

    LaunchedEffect(Unit) {
        fees = try { store.engine.fees() } catch (e: Exception) { null }
        store.refreshBalance()
    }

    OrdnetScreen(title = "Send BSV", onBack = onBack) {
        FormSection(header = "From") {
            Text(
                "${store.activeAccount?.name ?: "Account"} · ${Fmt.shortAddress(store.address)}",
                fontSize = 13.sp, color = Theme.secondaryText()
            )
        }

        FormSection(header = "Recipient") {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                OrdnetTextField(
                    value = to,
                    onValueChange = {
                        to = it
                        opnsTarget = null   // input changed → stale confirmations die
                        snsTarget = null
                        evaluateSafety()
                    },
                    placeholder = "BSV address, SNS or OpNS name",
                    mono = true,
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = { pasteVerified() }) {
                    Icon(Icons.Filled.ContentPaste, contentDescription = "Paste", tint = Theme.ink())
                }
                IconButton(onClick = { scan() }) {
                    Icon(Icons.Filled.QrCodeScanner, contentDescription = "Scan QR", tint = Theme.ink())
                }
            }
            if (store.addressBook.isNotEmpty()) {
                var bookOpen by remember { mutableStateOf(false) }
                Box {
                    Text(
                        "— pick from address book —",
                        fontSize = 13.sp, color = Theme.secondaryText(),
                        modifier = Modifier.clickable { bookOpen = true }.padding(vertical = 4.dp)
                    )
                    DropdownMenu(expanded = bookOpen, onDismissRequest = { bookOpen = false },
                        containerColor = Theme.bgPrimary()) {
                        store.addressBook.sortedBy { it.name }.forEach { e ->
                            DropdownMenuItem(
                                text = {
                                    Text("${e.name} · ${e.address.take(8)}…${e.address.takeLast(4)}",
                                        color = Theme.ink(), fontSize = 13.sp)
                                },
                                onClick = {
                                    to = e.address
                                    opnsTarget = null   // input changed → stale confirmations die
                                    snsTarget = null
                                    bookOpen = false
                                    evaluateSafety()
                                }
                            )
                        }
                    }
                }
            }
        }

        FormSection(header = "Amount") {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OrdnetTextField(
                    value = amount,
                    onValueChange = { amount = it; evaluateSafety() },
                    placeholder = "Amount in BSV (e.g. 0.001)",
                    keyboardType = androidx.compose.ui.text.input.KeyboardType.Decimal,
                    modifier = Modifier.weight(1f)
                )
                Text("Max", fontSize = 14.sp, fontWeight = FontWeight.Medium, color = Theme.ink(),
                    modifier = Modifier.clickable { sendMax() }.padding(8.dp))
            }
            if (amountSats() >= 1) {
                Text("= ${Fmt.sats(amountSats())} sats", fontSize = 12.sp, color = Theme.secondaryText())
            }
            fees?.let { f ->
                Text(
                    "Fee: ~${Fmt.bsv(f.sendMinerFee)} BSV network + ${Fmt.bsv(f.totalServiceFees)} BSV service",
                    fontSize = 12.sp, color = Theme.secondaryText()
                )
            }
        }

        if (warnings.isNotEmpty()) {
            InlineAlert(AlertKind.WARNING, warnings.joinToString("\n"))
        }

        // v3.1 — SNS confirmation: signed answer verified against the
        // pinned resolver key; the pay-to address comes from the SIGNED
        // holder_script, never from the unsigned holder_address field
        snsTarget?.let { t ->
            FormSection(header = "Confirm SNS payment") {
                KVRow(k = "Name", v = t.name)
                if (t.mailbox.isNotEmpty()) {
                    KVRow(k = "Mailbox", v = "${t.mailbox}@${t.name}")
                }
                KVRow(k = "Holder address", v = t.holderAddress, mono = true)
                KVRow(k = "Inscription UTXO",
                    v = "${t.currentTxid.take(10)}…${t.currentTxid.takeLast(6)}_${t.currentVout}", mono = true)
                if (t.fallback) {
                    InlineAlert(AlertKind.WARNING,
                        "Mailbox \"${t.mailbox}\" is unknown — the payment goes to the holder of ${t.name}.")
                }
                if (t.warning.isNotEmpty()) {
                    InlineAlert(AlertKind.WARNING, t.warning)
                }
                Text(
                    "Signed resolver answer verified against the pinned key; the inscription outpoint was checked unspent. Everything is re-verified the moment you confirm.",
                    fontSize = 12.sp, color = Theme.secondaryText()
                )
            }
        }

        // v3.1 — OpNS confirmation: ALWAYS the exact name + the verified
        // holder address, inline, before anything is paid (intermediate
        // names like "alexande" vs "alexander" can have different owners)
        opnsTarget?.let { t ->
            FormSection(header = "Confirm OpNS payment") {
                KVRow(k = "Exact name", v = t.name)
                KVRow(k = "Holder address", v = t.holderAddress, mono = true)
                KVRow(k = "Ordinal UTXO",
                    v = "${t.currentTxid.take(10)}…${t.currentTxid.takeLast(6)}_${t.currentVout}", mono = true)
                Text(
                    "Verified on-chain: the holder address was recomputed from the current outpoint's locking script and the outpoint checked unspent. It is re-verified again the moment you confirm.",
                    fontSize = 12.sp, color = Theme.secondaryText()
                )
            }
        }

        FormSection {
            InlineAlert(AlertKind.ERROR, error)
            InlineAlert(AlertKind.SUCCESS, success)
            if (showSaveToBook && lastSentAddr != null) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.clickable { showBook = true }
                ) {
                    Icon(Icons.Filled.Book, contentDescription = null,
                        tint = Theme.ink(), modifier = Modifier.size(16.dp))
                    Text("Save ${lastSentAddr?.take(8)}… to address book",
                        fontSize = 13.sp, color = Theme.ink())
                }
            }
            val confirmLabel = snsTarget?.let { t ->
                "Confirm & pay \"${if (t.mailbox.isEmpty()) t.name else "${t.mailbox}@${t.name}"}\""
            } ?: opnsTarget?.let { t -> "Confirm & pay \"${t.name}\"" } ?: "Send"
            OrdnetProminentButton(onClick = { send() }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                if (busy) ButtonSpinner() else Text(confirmLabel, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

// MARK: - Receive

@Composable
fun ReceiveView(store: WalletStore, onBack: () -> Unit) {
    var copied by remember { mutableStateOf("") }
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current

    OrdnetScreen(title = "Receive", onBack = onBack) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
        ) {
            Text(
                "${store.activeAccount?.name ?: "Account"} · BSV mainnet",
                fontSize = 13.sp, color = Theme.secondaryText()
            )
            Box(Modifier.fillMaxWidth(0.72f)) {
                QRCodeView(text = store.address)
            }
            androidx.compose.foundation.text.selection.SelectionContainer {
                Text(
                    store.address,
                    fontSize = 15.sp,
                    fontFamily = FontFamily.Monospace,
                    textAlign = TextAlign.Center,
                    color = Theme.ink(),
                    modifier = Modifier.padding(horizontal = 16.dp)
                )
            }
            InlineAlert(AlertKind.SUCCESS, copied)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OrdnetProminentButton(onClick = {
                    clipboard.setText(AnnotatedString(store.address))
                    copied = "Address copied to clipboard."
                }) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                        Text("Copy address")
                    }
                }
                OrdnetOutlineButton(onClick = {
                    val intent = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, store.address)
                    }
                    context.startActivity(Intent.createChooser(intent, "Share address"))
                }) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Share, contentDescription = null, modifier = Modifier.size(16.dp))
                        Text("Share")
                    }
                }
            }
            Text(
                "Only send BSV or 1Sat Ordinals to this address.",
                fontSize = 12.sp, color = Theme.secondaryText()
            )
        }
    }
}

// MARK: - History

@Composable
fun HistoryView(store: WalletStore, onBack: () -> Unit) {
    var txs by remember { mutableStateOf<List<HistoryTx>>(emptyList()) }
    var error by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(true) }
    val uriHandler = LocalUriHandler.current

    LaunchedEffect(Unit) {
        try {
            txs = Api.history(store.address)
        } catch (e: Exception) {
            error = "Could not load history from WhatsOnChain."
        }
        loading = false
    }

    OrdnetScreen(title = Fmt.shortAddress(store.address), onBack = onBack) {
        when {
            loading -> SpinnerRow()
            error.isNotEmpty() -> Text(error, fontSize = 13.sp, color = Theme.secondaryText())
            txs.isEmpty() -> Text(
                "No transactions on this address yet.",
                fontSize = 13.sp, color = Theme.secondaryText()
            )
            else -> {
                txs.take(50).forEach { t ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { uriHandler.openUri("https://whatsonchain.com/tx/${t.txHash}") }
                            .padding(vertical = 6.dp)
                    ) {
                        Icon(Icons.AutoMirrored.Filled.CompareArrows, contentDescription = null,
                            tint = Theme.secondaryText(), modifier = Modifier.size(18.dp))
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(Fmt.shortTxid(t.txHash), fontSize = 15.sp,
                                fontFamily = FontFamily.Monospace, color = Theme.ink())
                            Text(
                                if (t.isPending) "pending (mempool)" else "block ${t.height}",
                                fontSize = 11.sp,
                                color = if (t.isPending) Theme.statusYellow else Theme.secondaryText()
                            )
                        }
                    }
                }
            }
        }
    }
}
