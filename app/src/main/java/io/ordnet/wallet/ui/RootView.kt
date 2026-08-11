package io.ordnet.wallet.ui

import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Sell
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.ordnet.wallet.core.WalletStore
import io.ordnet.wallet.ui.browser.ApprovalSheetContent
import io.ordnet.wallet.ui.browser.Brc100PermissionSheetContent
import io.ordnet.wallet.ui.browser.Brc100TxConfirmSheetContent
import io.ordnet.wallet.ui.browser.BrowserModel
import io.ordnet.wallet.ui.browser.BrowserView
import io.ordnet.wallet.ui.domains.DomainsView
import io.ordnet.wallet.ui.home.HomeView
import io.ordnet.wallet.ui.onboarding.SetupView
import io.ordnet.wallet.ui.ordner.OrdnerView
import io.ordnet.wallet.ui.wallet.UploadView
import kotlinx.coroutines.launch

@Composable
fun RootView(store: WalletStore, activity: AppCompatActivity) {
    when (store.phase) {
        WalletStore.Phase.LOADING -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = Theme.ink())
        }
        WalletStore.Phase.SETUP -> SetupView(store)
        WalletStore.Phase.LOCKED -> UnlockView(store, activity)
        WalletStore.Phase.UNLOCKED -> MainTabView(store, activity)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainTabView(store: WalletStore, activity: AppCompatActivity) {
    var tab by rememberSaveable { mutableStateOf(0) }
    val stateHolder = rememberSaveableStateHolder()
    // the browser (and its WebView) survives tab switches, like the iOS TabView
    val browserModel = remember { BrowserModel(activity, store) }

    // ORD/ner "Open in Browser" → switch to the Browser tab (which loads it)
    LaunchedEffect(store.browserOpenRequest) {
        if (store.browserOpenRequest != null) tab = 1
    }
    // v3.3 — "Manage domain listing" → switch to the Domains tab (which opens
    // the domain detail)
    LaunchedEffect(store.domainsOpenRequest) {
        if (store.domainsOpenRequest != null) tab = 2
    }

    // v3.2 — five tabs (the user's layout, iOS v2.3.2 parity): Wallet ·
    // Browser · Domains · Upload · ORD/ner. Settings and the UTXO tools
    // moved to the top bar of the Wallet screen (see HomeView).
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            NavigationBar(containerColor = Theme.bgSecondary()) {
                val items = listOf(
                    Triple("Wallet", Icons.Filled.AccountBalanceWallet, 0),
                    Triple("Browser", Icons.Filled.Public, 1),
                    Triple("Domains", Icons.Filled.Sell, 2),
                    Triple("Upload", Icons.Filled.Upload, 3),
                    Triple("ORD/ner", Icons.Filled.Folder, 4)
                )
                for ((label, icon, idx) in items) {
                    NavigationBarItem(
                        selected = tab == idx,
                        onClick = { tab = idx },
                        icon = { Icon(icon, contentDescription = label) },
                        label = { Text(label, fontSize = 11.sp) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = Theme.ink(),
                            selectedTextColor = Theme.ink(),
                            unselectedIconColor = Theme.secondaryText(),
                            unselectedTextColor = Theme.secondaryText(),
                            indicatorColor = Theme.ink().copy(alpha = 0.10f)
                        )
                    )
                }
            }
        }
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            stateHolder.SaveableStateProvider("tab$tab") {
                when (tab) {
                    0 -> HomeView(store, activity)
                    1 -> BrowserView(store, browserModel)
                    2 -> DomainsView(store)
                    3 -> UploadView(store)
                    4 -> OrdnerView(store, onOpenInBrowser = { tab = 1 })
                }
            }
        }
    }

    // v3.2 — BRC-100 permission prompt: native sheet + biometrics, outside
    // the page's reach (per-app/per-protocol grants, BRC-43)
    val brcPermission = store.pendingBrc100Permission
    if (brcPermission != null) {
        val sheetState = rememberModalBottomSheetState(
            skipPartiallyExpanded = true,
            confirmValueChange = { it != SheetValue.Hidden }   // only the two buttons decide
        )
        ModalBottomSheet(
            onDismissRequest = {
                brcPermission.deferred.complete(false)
                store.pendingBrc100Permission = null
            },
            sheetState = sheetState,
            containerColor = Theme.bgPrimary()
        ) {
            Brc100PermissionSheetContent(store = store, activity = activity, request = brcPermission)
        }
    }

    // v3.2 — BRC-100 fase 3: per-transactie bevestiging (geld ≠ grant,
    // nooit persistent, altijd biometrie)
    val brcTxConfirm = store.pendingBrc100TxConfirm
    if (brcTxConfirm != null) {
        val sheetState = rememberModalBottomSheetState(
            skipPartiallyExpanded = true,
            confirmValueChange = { it != SheetValue.Hidden }   // only the two buttons decide
        )
        ModalBottomSheet(
            onDismissRequest = {
                brcTxConfirm.deferred.complete(false)
                store.pendingBrc100TxConfirm = null
            },
            sheetState = sheetState,
            containerColor = Theme.bgPrimary()
        ) {
            Brc100TxConfirmSheetContent(store = store, activity = activity, request = brcTxConfirm)
        }
    }

    // dApp approval requests (from the .web3 browser) surface as a sheet
    val request = store.pendingProviderRequest
    if (request != null) {
        // while a signed action is executing the sheet must not be swipe-dismissable
        // (the Android counterpart of iOS interactiveDismissDisabled): dismissing it
        // mid-flight left an invisible-but-composed sheet and a stuck browser
        val sheetState = rememberModalBottomSheetState(
            skipPartiallyExpanded = true,
            confirmValueChange = { target ->
                !(browserModel.approvalBusy && target == SheetValue.Hidden)
            }
        )
        ModalBottomSheet(
            onDismissRequest = {
                browserModel.rejectPending(request)
            },
            sheetState = sheetState,
            containerColor = Theme.bgPrimary()
        ) {
            ApprovalSheetContent(store = store, model = browserModel, request = request)
        }
    }
}

// MARK: - Unlock

@Composable
fun UnlockView(store: WalletStore, activity: AppCompatActivity) {
    var error by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var confirmRemove by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    fun unlock() {
        if (busy) return
        busy = true
        error = ""
        scope.launch {
            try {
                store.unlock(activity)
            } catch (e: Exception) {
                error = e.message ?: "Could not unlock."
            }
            busy = false
        }
    }

    // prompt biometrics immediately on appear
    LaunchedEffect(Unit) { unlock() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(22.dp)
    ) {
        Spacer(Modifier.weight(1f))
        OrdplugLogo(size = 72.dp)
        Text("ORDnet Wallet", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Theme.ink())
        Text(
            "Unlock with your fingerprint or face — or your device PIN as fallback",
            fontSize = 15.sp,
            color = Theme.secondaryText(),
            textAlign = TextAlign.Center
        )

        InlineAlert(AlertKind.ERROR, error)

        OrdnetProminentButton(
            onClick = { unlock() },
            enabled = !busy,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 32.dp)
                .height(48.dp)
        ) {
            if (busy) {
                CircularProgressIndicator(
                    color = Theme.bgPrimary(),
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp
                )
            } else {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Filled.Fingerprint, contentDescription = null)
                    Text("Unlock", fontWeight = FontWeight.SemiBold)
                }
            }
        }

        Spacer(Modifier.weight(1f))

        if (confirmRemove) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.padding(16.dp)
            ) {
                Text(
                    "Removing the wallet deletes the keys from this device. Coins are only recoverable with your recovery phrase or WIF backup.",
                    fontSize = 13.sp,
                    color = Theme.statusRed,
                    textAlign = TextAlign.Center
                )
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OrdnetOutlineButton(onClick = { confirmRemove = false }) {
                        Text("Keep wallet")
                    }
                    OrdnetProminentButton(onClick = { store.removeWallet() }, destructive = true) {
                        Text("Remove wallet")
                    }
                }
            }
        } else {
            Text(
                "Can't unlock? Remove wallet…",
                fontSize = 13.sp,
                color = Theme.secondaryText(),
                modifier = Modifier
                    .padding(bottom = 12.dp)
                    .tap { confirmRemove = true }
            )
        }
    }
}

/** small helper: plain clickable */
fun Modifier.tap(onClick: () -> Unit): Modifier =
    this.clickable(onClick = onClick)
