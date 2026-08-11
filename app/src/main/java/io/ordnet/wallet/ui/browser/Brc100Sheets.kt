package io.ordnet.wallet.ui.browser

import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.material3.Icon
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.ordnet.wallet.core.Brc100PermissionRequest
import io.ordnet.wallet.core.Brc100TxConfirmRequest
import io.ordnet.wallet.core.Fmt
import io.ordnet.wallet.core.Vault
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

/**
 * BRC-100 permission sheet (v3.2) — NATIVE, deliberately outside the page's
 * reach (an HTML dialog could be faked by the requesting app). Allow runs
 * biometrics first; the grant is then persisted per app + protocol (BRC-43),
 * so the same app never re-prompts for the same protocol. Deny returns a
 * standards-shaped WERR_PERMISSION_DENIED rejection to the app.
 */
@Composable
fun Brc100PermissionSheetContent(
    store: WalletStore,
    activity: AppCompatActivity,
    request: Brc100PermissionRequest
) {
    var error by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var resolved by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    fun finish(approved: Boolean) {
        if (resolved) return
        resolved = true
        request.deferred.complete(approved)
        store.pendingBrc100Permission = null
    }

    // safety net: never leak the deferred if the sheet leaves composition
    DisposableEffect(request.id) {
        onDispose { request.deferred.complete(false) }
    }

    Column(
        verticalArrangement = Arrangement.spacedBy(14.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Text("Permission", fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = Theme.ink())

        FormSection(header = "App") {
            KVRow(k = "Origin", v = request.origin, mono = true)
            KVRow(k = "Request", v = request.title)
        }
        FormSection {
            Text(request.detail, fontSize = 13.sp, color = Theme.secondaryText())
            Text(
                "Allowing stores this permission for this app and protocol — it will not ask again. You approve with your fingerprint or face.",
                fontSize = 12.sp, color = Theme.secondaryText()
            )
        }
        FormSection {
            InlineAlert(AlertKind.ERROR, error)
            OrdnetProminentButton(
                onClick = {
                    error = ""
                    busy = true
                    scope.launch {
                        try {
                            Vault.authenticate(activity, "Allow ${request.origin}", request.title)
                            busy = false
                            finish(true)
                        } catch (e: Exception) {
                            busy = false
                            error = e.message ?: "Authentication failed."
                        }
                    }
                },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth()
            ) {
                if (busy) ButtonSpinner() else Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Filled.Fingerprint, contentDescription = null, modifier = Modifier.size(16.dp))
                    Text("Allow", fontWeight = FontWeight.SemiBold)
                }
            }
            OrdnetOutlineButton(onClick = { finish(false) }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                Text("Deny")
            }
        }
        Spacer(Modifier.size(12.dp))
    }
}

/**
 * BRC-100 transaction confirmation (v3.2, fase 3) — NATIVE and deliberately
 * outside the page's reach, like the permission sheet. Hard rule 2 of the
 * fase-3 briefing: money ≠ grant. NOTHING here is ever persisted — every
 * transaction shows this sheet again, approval always runs biometrics, and
 * Reject returns a standards-shaped WERR_PERMISSION_DENIED rejection.
 */
@Composable
fun Brc100TxConfirmSheetContent(
    store: WalletStore,
    activity: AppCompatActivity,
    request: Brc100TxConfirmRequest
) {
    var error by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var resolved by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    fun usd(sats: Long): String {
        val rate = store.usdRate ?: return ""
        return String.format(java.util.Locale.US, " · $%.2f", sats / 1e8 * rate)
    }

    fun finish(approved: Boolean) {
        if (resolved) return
        resolved = true
        request.deferred.complete(approved)
        store.pendingBrc100TxConfirm = null
    }

    // safety net: never leak the deferred if the sheet leaves composition
    DisposableEffect(request.id) {
        onDispose { request.deferred.complete(false) }
    }

    Column(
        verticalArrangement = Arrangement.spacedBy(14.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Text(request.title, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = Theme.ink())

        FormSection(header = "App") {
            KVRow(k = "Origin", v = request.origin, mono = true)
            KVRow(k = "Action", v = request.description)
        }
        FormSection(header = if (request.incoming) "Incoming outputs" else "Pays to") {
            for (line in request.lines) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp), modifier = Modifier.fillMaxWidth()) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        Text(line.dest, fontSize = 12.sp, fontFamily = FontFamily.Monospace,
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                            color = Theme.ink(), modifier = Modifier.weight(1f))
                        Text("${Fmt.sats(line.sats)} sats", fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold, color = Theme.ink())
                    }
                    if (line.note.isNotEmpty()) {
                        Text(line.note, fontSize = 11.sp, color = Theme.secondaryText())
                    }
                }
            }
        }
        FormSection(header = "Total") {
            KVRow(
                k = if (request.incoming) "You receive" else "Outputs total",
                v = "${Fmt.sats(request.totalSat)} sats (${Fmt.bsv(request.totalSat)} BSV)${usd(request.totalSat)}"
            )
            if (!request.incoming) {
                KVRow(k = "Miner fee (est.)", v = "${Fmt.sats(request.minerFeeEstimate)} sats")
                KVRow(k = "Service fees", v = "${Fmt.sats(request.serviceFees)} sats")
            }
        }
        Text(
            if (request.incoming)
                "This accepts an incoming payment into your wallet. Nothing leaves your wallet."
            else
                "This signs and broadcasts a transaction from your wallet. You approve every transaction separately with your fingerprint or face — this is never stored as a permission.",
            fontSize = 12.sp, color = Theme.secondaryText()
        )
        FormSection {
            InlineAlert(AlertKind.ERROR, error)
            OrdnetProminentButton(
                onClick = {
                    error = ""
                    busy = true
                    scope.launch {
                        try {
                            Vault.authenticate(activity,
                                request.title,
                                "${Fmt.sats(request.totalSat)} sats — ${request.origin}")
                            busy = false
                            finish(true)
                        } catch (e: Exception) {
                            busy = false
                            error = e.message ?: "Authentication failed."
                        }
                    }
                },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth()
            ) {
                if (busy) ButtonSpinner() else Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Filled.Fingerprint, contentDescription = null, modifier = Modifier.size(16.dp))
                    Text(if (request.incoming) "Accept" else "Approve & sign", fontWeight = FontWeight.SemiBold)
                }
            }
            OrdnetOutlineButton(onClick = { finish(false) }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                Text("Reject")
            }
        }
        Spacer(Modifier.size(12.dp))
    }
}
