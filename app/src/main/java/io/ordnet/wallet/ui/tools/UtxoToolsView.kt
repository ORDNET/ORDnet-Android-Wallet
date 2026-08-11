package io.ordnet.wallet.ui.tools

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import io.ordnet.wallet.core.Fees
import io.ordnet.wallet.core.Fmt
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
import io.ordnet.wallet.ui.components.SpinnerRow
import kotlinx.coroutines.launch

/**
 * UTXO tools (v3.2) — split & combine, reachable from the Wallet screen's top
 * bar (user layout). Both operate on the ordinal-protected UTXO set (1-sat
 * inscriptions can never be spent here) and carry the ORDnet service fees
 * like every other transaction in the app. Two-tap confirm, errors inline.
 */
@Composable
fun UtxoToolsView(store: WalletStore, onBack: () -> Unit) {
    var utxoCount by remember { mutableStateOf(0) }
    var utxoTotal by remember { mutableStateOf(0L) }
    var utxoLargest by remember { mutableStateOf(0L) }
    var loading by remember { mutableStateOf(true) }

    // split form
    var countText by remember { mutableStateOf("10") }
    var satsText by remember { mutableStateOf("") }
    var confirmingSplit by remember { mutableStateOf(false) }
    // combine
    var confirmingCombine by remember { mutableStateOf(false) }

    var error by remember { mutableStateOf("") }
    var success by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var fees by remember { mutableStateOf<Fees?>(null) }

    val scope = rememberCoroutineScope()

    val splitCount = countText.toIntOrNull() ?: 0
    val splitSats = satsText.replace(".", "").toLongOrNull() ?: 0L
    val splitTotal = splitCount * splitSats

    suspend fun refresh() {
        loading = true
        try {
            val u = store.utxos()
            var count = 0
            var total = 0L
            var largest = 0L
            for (i in 0 until u.length()) {
                val o = u.optJSONObject(i) ?: continue
                val s = o.optLong("satoshis", 0)
                count++; total += s
                if (s > largest) largest = s
            }
            utxoCount = count; utxoTotal = total; utxoLargest = largest
        } catch (e: Exception) {
            utxoCount = 0; utxoTotal = 0; utxoLargest = 0
        }
        loading = false
    }

    fun prepareSplit() {
        error = ""; success = ""
        confirmingCombine = false
        if (splitCount < 2 || splitCount > 200) { error = "Choose between 2 and 200 UTXOs."; return }
        if (splitSats < 547) { error = "Each UTXO needs at least 547 sats (above dust)."; return }
        val f = fees ?: run { error = "Fee schedule not loaded yet — try again."; return }
        val needed = splitTotal + f.totalServiceFees
        if (utxoTotal <= needed) {
            error = "Insufficient spendable balance: this split needs ~${Fmt.sats(needed)} sats + miner fee, you have ${Fmt.sats(utxoTotal)}."
            return
        }
        confirmingSplit = true
    }

    fun runSplit() {
        error = ""; success = ""; busy = true
        scope.launch {
            try {
                val txid = store.splitUtxos(count = splitCount, satsEach = splitSats)
                success = "Split done! $splitCount × ${Fmt.sats(splitSats)} sats created. TXID: $txid"
                confirmingSplit = false
                refresh()
                store.refreshBalance()
            } catch (e: Exception) {
                error = e.message ?: "Split failed."
            }
            busy = false
        }
    }

    fun runCombine() {
        error = ""; success = ""; busy = true
        scope.launch {
            try {
                val (txid, outSat) = store.combineUtxos()
                success = "Combined into one UTXO of ${Fmt.sats(outSat)} sats. TXID: $txid"
                confirmingCombine = false
                refresh()
                store.refreshBalance()
            } catch (e: Exception) {
                error = e.message ?: "Combine failed."
            }
            busy = false
        }
    }

    LaunchedEffect(Unit) {
        fees = try { store.engine.fees() } catch (e: Exception) { null }
        refresh()
    }

    OrdnetScreen(title = "UTXO tools", onBack = onBack) {
        FormSection(header = "Your spendable UTXOs") {
            if (loading) {
                SpinnerRow()
            } else {
                KVRow(k = "Count", v = "$utxoCount")
                KVRow(k = "Total", v = "${Fmt.bsv(utxoTotal)} BSV (${Fmt.sats(utxoTotal)} sats)")
                KVRow(k = "Largest", v = "${Fmt.sats(utxoLargest)} sats")
                Text("1-sat ordinal inscriptions are excluded by design — they can never be spent here.",
                    fontSize = 12.sp, color = Theme.secondaryText())
            }
        }

        FormSection(header = "Split — make N UTXOs of X sats each") {
            OrdnetTextField(
                value = countText,
                onValueChange = { countText = it; confirmingSplit = false },
                placeholder = "Number of UTXOs (2–200)",
                keyboardType = androidx.compose.ui.text.input.KeyboardType.Number
            )
            OrdnetTextField(
                value = satsText,
                onValueChange = { satsText = it; confirmingSplit = false },
                placeholder = "Sats per UTXO (min 547)",
                keyboardType = androidx.compose.ui.text.input.KeyboardType.Number
            )
            val f = fees
            if (splitCount >= 2 && splitSats >= 547 && f != null) {
                Text("= ${Fmt.sats(splitTotal)} sats into outputs + ~miner fee + ${Fmt.bsv(f.totalServiceFees)} BSV service",
                    fontSize = 12.sp, color = Theme.secondaryText())
            }
            if (confirmingSplit) {
                KVRow(k = "Confirm", v = "$splitCount × ${Fmt.sats(splitSats)} sats → your own address")
                OrdnetProminentButton(onClick = { runSplit() }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                    if (busy) ButtonSpinner() else Text("Confirm & split", fontWeight = FontWeight.SemiBold)
                }
            } else {
                OrdnetOutlineButton(onClick = { prepareSplit() }, enabled = !busy && !loading, modifier = Modifier.fillMaxWidth()) {
                    Text("Split…")
                }
            }
        }

        FormSection(header = "Combine — merge everything into one UTXO") {
            Text("Spends ALL spendable UTXOs into a single output to your own address. Useful after many small transactions.",
                fontSize = 12.sp, color = Theme.secondaryText())
            if (confirmingCombine) {
                KVRow(k = "Confirm", v = "$utxoCount UTXOs → 1 output to your own address")
                OrdnetProminentButton(onClick = { runCombine() }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                    if (busy) ButtonSpinner() else Text("Confirm & combine", fontWeight = FontWeight.SemiBold)
                }
            } else {
                OrdnetOutlineButton(onClick = {
                    error = ""; success = ""
                    if (utxoCount < 2) {
                        error = "Nothing to combine — you have $utxoCount spendable UTXO${if (utxoCount == 1) "" else "s"}."
                    } else {
                        confirmingCombine = true
                        confirmingSplit = false
                    }
                }, enabled = !busy && !loading, modifier = Modifier.fillMaxWidth()) {
                    Text("Combine…")
                }
            }
        }

        FormSection {
            InlineAlert(AlertKind.ERROR, error)
            InlineAlert(AlertKind.SUCCESS, success)
        }
    }
}
