package io.ordnet.wallet.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.ordnet.wallet.core.Brc100GrantInfo
import io.ordnet.wallet.core.WalletStore
import io.ordnet.wallet.ui.AlertKind
import io.ordnet.wallet.ui.FormSection
import io.ordnet.wallet.ui.InlineAlert
import io.ordnet.wallet.ui.Theme
import io.ordnet.wallet.ui.components.OrdnetScreen

/**
 * BRC-100 grants manager (v3.2) — the persistent BRC-43 permissions from
 * fase 2 (per app, per protocol, plus the per-app identity-key grant),
 * grouped per app-origin, each revocable. Revoking means the app simply
 * asks again (native biometric sheet) on next use — nothing breaks.
 * NOTE: money is deliberately absent here — transactions are confirmed
 * per transaction and never stored as a permission (fase-3 rule 2).
 */
@Composable
fun Brc100GrantsView(store: WalletStore, onBack: () -> Unit) {
    var grants by remember { mutableStateOf<List<Brc100GrantInfo>>(emptyList()) }
    var note by remember { mutableStateOf("") }

    fun reload() { grants = store.brc100GrantsList() }
    LaunchedEffect(Unit) { reload() }

    val byOrigin = grants.groupBy { it.origin }.toList().sortedBy { it.first }

    OrdnetScreen(title = "BRC-100 permissions", onBack = onBack) {
        FormSection(
            footer = "Money is never a stored permission: every transaction is confirmed separately with your fingerprint or face."
        ) {
            InlineAlert(AlertKind.SUCCESS, note)
            if (grants.isEmpty()) {
                Text(
                    "No BRC-100 permissions granted on this account yet. When an app first asks for keys or crypto, a native biometric sheet appears — grants you allow show up here.",
                    fontSize = 13.sp, color = Theme.secondaryText()
                )
            }
        }

        for ((origin, items) in byOrigin) {
            FormSection(header = origin) {
                for (g in items) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(g.detail, fontSize = 14.sp, color = Theme.ink(), modifier = Modifier.weight(1f))
                        IconButton(onClick = {
                            store.brc100RevokeGrant(g.key)
                            note = "Permission revoked — the app will ask again on next use."
                            reload()
                        }) {
                            Icon(Icons.Filled.Delete, contentDescription = "Revoke",
                                tint = Theme.statusRed, modifier = Modifier.size(18.dp))
                        }
                    }
                }
                Text(
                    "Revoke all for this app",
                    fontSize = 14.sp, color = Theme.statusRed,
                    modifier = Modifier
                        .clickable {
                            store.brc100RevokeAllGrants(origin)
                            note = "All permissions for $origin revoked."
                            reload()
                        }
                        .padding(vertical = 6.dp)
                )
                Spacer(Modifier.size(2.dp))
            }
        }
    }
}
