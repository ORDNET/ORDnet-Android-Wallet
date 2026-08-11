package io.ordnet.wallet.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.material.icons.filled.AlternateEmail
import androidx.compose.material.icons.filled.CallSplit
import androidx.compose.material.icons.filled.NorthEast
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckBox
import androidx.compose.material.icons.filled.CheckBoxOutlineBlank
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.QrCode
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.ordnet.wallet.core.Fmt
import io.ordnet.wallet.core.Holding
import io.ordnet.wallet.core.HoldingKind
import io.ordnet.wallet.core.WalletStore
import io.ordnet.wallet.ui.AlertKind
import io.ordnet.wallet.ui.InlineAlert
import io.ordnet.wallet.ui.OrdplugLogo
import io.ordnet.wallet.ui.StatusPill
import io.ordnet.wallet.ui.Theme
import io.ordnet.wallet.ui.card
import io.ordnet.wallet.ui.components.OrdnetScreen
import io.ordnet.wallet.ui.components.OrdnetTextField
import io.ordnet.wallet.ui.components.SegmentedPicker
import io.ordnet.wallet.ui.wallet.BulkActionSheet
import io.ordnet.wallet.ui.wallet.BulkKind
import io.ordnet.wallet.ui.wallet.DelistView
import io.ordnet.wallet.ui.wallet.HistoryView
import io.ordnet.wallet.ui.wallet.ListOrdinalView
import io.ordnet.wallet.ui.wallet.ReceiveView
import io.ordnet.wallet.ui.settings.SettingsView
import io.ordnet.wallet.ui.tools.UtxoToolsView
import io.ordnet.wallet.ui.wallet.SendOrdinalView
import io.ordnet.wallet.ui.wallet.SendView
import io.ordnet.wallet.ui.OrdnetOutlineButton
import io.ordnet.wallet.ui.OrdnetProminentButton
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** internal navigation of the Wallet tab (port of NavigationStack destinations) */
sealed class HomeScreen {
    data object Main : HomeScreen()
    data object Send : HomeScreen()
    data object Receive : HomeScreen()
    data object History : HomeScreen()
    data class SendOrd(val holding: Holding) : HomeScreen()
    data class ListOrd(val holding: Holding) : HomeScreen()
    data class Delist(val holding: Holding) : HomeScreen()
    // v3.2 — Settings and the UTXO tools live on the Wallet screen's top bar
    // (user layout, iOS v2.3.2 parity)
    data object Settings : HomeScreen()
    data object Utxo : HomeScreen()
}

/**
 * Wallet home: balance card + holdings (SNS names / BSVmaps / For sale),
 * port of the extension's idle view.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeView(store: WalletStore, activity: AppCompatActivity) {
    var screen by remember { mutableStateOf<HomeScreen>(HomeScreen.Main) }
    val back = { screen = HomeScreen.Main }

    when (val s = screen) {
        is HomeScreen.Send -> SendView(store, onBack = back)
        is HomeScreen.Receive -> ReceiveView(store, onBack = back)
        is HomeScreen.History -> HistoryView(store, onBack = back)
        is HomeScreen.SendOrd -> SendOrdinalView(store, s.holding, onBack = back)
        is HomeScreen.ListOrd -> ListOrdinalView(store, s.holding, onBack = back)
        is HomeScreen.Delist -> DelistView(store, s.holding, onBack = back)
        is HomeScreen.Settings -> SettingsView(store, activity, onClose = back)
        is HomeScreen.Utxo -> UtxoToolsView(store, onBack = back)
        is HomeScreen.Main -> HomeMain(store, onNavigate = { screen = it })
    }
}

// v3.1 — OpNS as third category next to SNS and BSVmaps; one shared selection
// across TWO stacked segmented bars (user design): row 1 SNS + OpNS,
// row 2 BSVmaps + For sale. Labels kept compact so the counters always fit.
private const val TAB_SNS = 0
private const val TAB_OPNS = 1
private const val TAB_BSVMAP = 2
private const val TAB_SALE = 3

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HomeMain(store: WalletStore, onNavigate: (HomeScreen) -> Unit) {
    var tab by remember { mutableStateOf(0) }
    var search by remember { mutableStateOf("") }
    var copied by remember { mutableStateOf(false) }

    // bulk list / delist selection mode
    var bulkMode by remember { mutableStateOf(false) }
    var bulkSelection by remember { mutableStateOf(setOf<String>()) }   // Holding.id
    var showBulkSheet by remember { mutableStateOf(false) }

    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current

    fun bulkEligible(h: Holding): Boolean {
        return if (tab == TAB_SALE) h.kind == HoldingKind.BSVMAP && h.isListed
        else h.kind == HoldingKind.BSVMAP && !h.isListed && h.status != "contract"
    }

    val filtered = remember(store.holdings, tab, search) {
        val q = search.trim().lowercase()
        val matches: (Holding) -> Boolean = { h ->
            q.isEmpty() || h.name.lowercase().contains(q) ||
                (h.district?.toString()?.contains(q) ?: false)
        }
        val arr = store.holdings.filter { h ->
            if (tab == TAB_SALE) {
                // v3.3 — domain-listed SNS names (USD registry) count as for sale
                if (!h.isForSaleAnywhere) return@filter false
            } else {
                val want = when (tab) {
                    TAB_SNS -> HoldingKind.SNS
                    TAB_OPNS -> HoldingKind.OPNS
                    else -> HoldingKind.BSVMAP
                }
                if (h.kind != want) return@filter false
            }
            matches(h)
        }
        // listed items always on top, original order preserved within each group.
        // distinctBy: the rows are LazyColumn items keyed on id — a duplicate
        // entry from the indexer must never crash the list with a duplicate key.
        arr.distinctBy { it.id }.sortedByDescending { if (it.isListed) 1 else 0 }
    }

    // v2.1 — paginering (20 per pagina), balk BOVEN de lijst — SNS-patroon van de extensie
    var page by remember { mutableStateOf(0) }
    val perPage = 20
    val pages = maxOf(1, (filtered.size + perPage - 1) / perPage)
    val safePage = page.coerceIn(0, pages - 1)
    val pageItems = filtered.drop(safePage * perPage).take(perPage)

    fun count(t: Int): Int = when (t) {
        TAB_SNS -> store.holdings.count { it.kind == HoldingKind.SNS }
        TAB_OPNS -> store.holdings.count { it.kind == HoldingKind.OPNS }
        TAB_BSVMAP -> store.holdings.count { it.kind == HoldingKind.BSVMAP }
        else -> store.holdings.count { it.isForSaleAnywhere }
    }

    /** each segment shows "—" when ITS OWN index is unreachable */
    fun countLabel(t: Int): String {
        val ok = if (t == TAB_OPNS) store.opnsOk else store.indexerOk
        return if (ok) count(t).toString() else "—"
    }

    // the OpNS tab degrades on ITS OWN flag — a broken OpNS index never
    // touches the SNS/BSVmaps tabs, and vice versa
    val emptyNote = when {
        search.trim().isNotEmpty() -> "No items match \"$search\"."
        tab == TAB_OPNS && !store.opnsOk -> "Could not reach the OpNS index at search.ordnet.io."
        tab == TAB_OPNS -> "No OpNS names on this address yet."
        !store.indexerOk -> "Could not reach the ORDnet indexer at bsvmap.io."
        tab == TAB_SALE -> "Nothing listed for sale yet. Use the tag button on an SNS name or BSVmap to list it."
        tab == TAB_SNS -> "No SNS names on this address yet."
        else -> "No BSVmaps on this address yet. Claim one on bsvmap.io!"
    }

    val bulkHint: String? = if (bulkMode && filtered.none { bulkEligible(it) }) {
        when {
            tab == TAB_SNS -> "Bulk list currently covers BSVmaps — SNS listings coming soon."
            tab == TAB_OPNS -> "OpNS names cannot be listed for sale — display, resolve and send only."
            tab == TAB_SALE -> "No listed BSVmaps to delist here."
            else -> "No unlisted BSVmaps here."
        }
    } else null

    LaunchedEffect(Unit) {
        if (store.balance == null) store.refreshBalance()
        if (store.holdings.isEmpty()) store.loadHoldings()
    }

    OrdnetScreen(
        title = store.activeAccount?.name ?: "Wallet",
        // v3.2 — Settings and UTXO tools live top-left on the lock's line
        // (user layout: settings first, then UTXO); lock stays right
        leadingActions = {
            IconButton(onClick = { onNavigate(HomeScreen.Settings) }) {
                Icon(Icons.Filled.Settings, contentDescription = "Settings", tint = Theme.ink())
            }
            IconButton(onClick = { onNavigate(HomeScreen.Utxo) }) {
                Icon(Icons.Filled.CallSplit, contentDescription = "UTXO tools", tint = Theme.ink())
            }
        },
        actions = {
            IconButton(onClick = { store.lock() }) {
                Icon(Icons.Filled.Lock, contentDescription = "Lock", tint = Theme.ink())
            }
        },
        // a wallet can hold THOUSANDS of BSVmaps: everything lives in one lazy
        // list (header sections included) so rows compose on demand. The old
        // eager forEach composed every row at once and froze the main thread
        // for many seconds (ANR) as soon as the holdings tab rendered.
        scrollable = false
    ) {
      LazyColumn(
          modifier = Modifier.fillMaxSize(),
          verticalArrangement = Arrangement.spacedBy(14.dp),
          contentPadding = PaddingValues(bottom = 24.dp)
      ) {
        item {
        // balance card
        Column(
            Modifier.fillMaxWidth().card(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text("BitcoinSV", fontSize = 13.sp, color = Theme.secondaryText())
            val b = store.balance
            if (b != null) {
                val sats = b.total
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(Fmt.bsv(sats), fontSize = 34.sp, fontWeight = FontWeight.Bold, color = Theme.ink())
                    Text(" BSV", fontSize = 17.sp, color = Theme.secondaryText(),
                        modifier = Modifier.padding(bottom = 4.dp))
                }
                var sub = "${store.activeAccount?.name ?: "Account"} · ${Fmt.sats(sats)} sats"
                val rate = store.usdRate
                if (rate != null && rate > 0) {
                    sub += " · ≈ $" + String.format(java.util.Locale.US, "%.2f", sats / 1e8 * rate)
                }
                Text(sub, fontSize = 12.sp, color = Theme.secondaryText())
            } else {
                CircularProgressIndicator(
                    color = Theme.ink(), strokeWidth = 2.dp,
                    modifier = Modifier.size(22.dp).padding(vertical = 2.dp)
                )
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.clickable {
                    clipboard.setText(AnnotatedString(store.address))
                    copied = true
                    scope.launch {
                        delay(1400)
                        copied = false
                    }
                }
            ) {
                Text(
                    store.address,
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = if (copied) Theme.statusGreen else Theme.secondaryText(),
                    modifier = Modifier.weight(1f, fill = false)
                )
                Icon(
                    if (copied) Icons.Filled.Check else Icons.Filled.ContentCopy,
                    contentDescription = "Copy address",
                    tint = if (copied) Theme.statusGreen else Theme.secondaryText(),
                    modifier = Modifier.size(13.dp)
                )
            }
        }
        }

        // action row: Send (prominent) / Receive / History
        item {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
            OrdnetProminentButton(onClick = { onNavigate(HomeScreen.Send) }, modifier = Modifier.weight(1f)) {
                ActionLabel(Icons.Filled.NorthEast, "Send")
            }
            OrdnetOutlineButton(onClick = { onNavigate(HomeScreen.Receive) }, modifier = Modifier.weight(1f)) {
                ActionLabel(Icons.Filled.QrCode, "Receive")
            }
            OrdnetOutlineButton(onClick = { onNavigate(HomeScreen.History) }, modifier = Modifier.weight(1f)) {
                ActionLabel(Icons.Filled.History, "History")
            }
        }
        }

        // holdings header
        item {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text("HOLDINGS", fontSize = 12.sp, letterSpacing = 0.5.sp, color = Theme.secondaryText())
            Spacer(Modifier.weight(1f))
            Text(
                if (bulkMode) "Done" else (if (tab == TAB_SALE) "Bulk delist" else "Bulk list"),
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                color = Theme.ink(),
                modifier = Modifier.clickable {
                    bulkMode = !bulkMode
                    bulkSelection = if (bulkMode) {
                        // pre-select every eligible item, like the extension —
                        // capped at 300, the same limit "Select all" and manual
                        // toggles enforce (one bulk run must stay rate-limit friendly)
                        filtered.filter { bulkEligible(it) }.map { it.id }.take(300).toSet()
                    } else emptySet()
                }
            )
        }
        }

        item {
        // v3.1 — two stacked segmented bars sharing ONE selection (user
        // design, iOS v2.2.2 parity): row 1 SNS + OpNS, row 2 BSVmaps +
        // For sale. The bar without the selected tag shows no highlight
        // (selected index -1). No separator between the bars and half the
        // usual gap, exactly like the iOS layout.
        val selectTab: (Int) -> Unit = {
            tab = it; page = 0
            if (bulkMode) { bulkMode = false; bulkSelection = emptySet() }
        }
        Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
            SegmentedPicker(
                options = listOf("SNS (${countLabel(TAB_SNS)})", "OpNS (${countLabel(TAB_OPNS)})"),
                selected = if (tab <= TAB_OPNS) tab else -1,
                onSelect = { selectTab(it) }
            )
            SegmentedPicker(
                options = listOf("BSVmaps (${countLabel(TAB_BSVMAP)})", "For sale (${countLabel(TAB_SALE)})"),
                selected = if (tab >= TAB_BSVMAP) tab - TAB_BSVMAP else -1,
                onSelect = { selectTab(it + TAB_BSVMAP) }
            )
        }
        }

        if (store.holdings.isNotEmpty() || search.isNotEmpty()) {
            item {
            OrdnetTextField(value = search, onValueChange = { search = it; page = 0 },
                placeholder = "Search name or district…")
            }
        }

        // v2.1 — pagineringsbalk boven de lijst (zoekveld -> balk -> rijen)
        if (pages > 1) {
            item {
            PagerBar(
                page = safePage, pages = pages, total = filtered.size,
                onPrev = { page = safePage - 1 },
                onNext = { page = safePage + 1 }
            )
            }
        }

        if (bulkMode) {
            item {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text(
                    "${bulkSelection.size} selected${if (bulkSelection.size >= 300) " (max)" else ""}",
                    fontSize = 13.sp, color = Theme.ink()
                )
                Spacer(Modifier.weight(1f))
                Text("Select all", fontSize = 13.sp, color = Theme.ink(), modifier = Modifier.clickable {
                    val sel = bulkSelection.toMutableSet()
                    for (h in filtered) {
                        if (bulkEligible(h) && sel.size < 300) sel.add(h.id)
                    }
                    bulkSelection = sel
                })
                Spacer(Modifier.width(16.dp))
                Text(
                    if (tab == TAB_SALE) "Delist…" else "List…",
                    fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                    color = if (bulkSelection.isEmpty()) Theme.secondaryText() else Theme.ink(),
                    modifier = Modifier.clickable(enabled = bulkSelection.isNotEmpty()) { showBulkSheet = true }
                )
            }
            if (bulkHint != null) {
                InlineAlert(AlertKind.WARNING, bulkHint)
            }
            }
            }
        }

        if (filtered.isEmpty()) {
            item {
            Text(emptyNote, fontSize = 13.sp, color = Theme.secondaryText(),
                modifier = Modifier.padding(vertical = 8.dp))
            }
        } else {
            items(pageItems, key = { it.id }) { h ->
                HoldingRow(
                    h = h,
                    bulkMode = bulkMode,
                    selected = bulkSelection.contains(h.id),
                    eligible = bulkEligible(h),
                    onToggle = {
                        if (!bulkMode || !bulkEligible(h)) return@HoldingRow
                        bulkSelection = if (bulkSelection.contains(h.id)) bulkSelection - h.id
                        else if (bulkSelection.size < 300) bulkSelection + h.id
                        else bulkSelection
                    },
                    onNavigate = onNavigate,
                    onManageDomain = { name -> store.domainsOpenRequest = name }
                )
            }
        }
      }
    }

    if (showBulkSheet) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = { showBulkSheet = false },
            sheetState = sheetState,
            containerColor = Theme.bgPrimary()
        ) {
            BulkActionSheet(
                store = store,
                kind = if (tab == TAB_SALE) BulkKind.DELIST else BulkKind.LIST,
                items = store.holdings.filter { bulkSelection.contains(it.id) },
                onDone = {
                    bulkMode = false
                    bulkSelection = emptySet()
                    showBulkSheet = false
                }
            )
        }
    }
}

@Composable
private fun ActionLabel(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.padding(vertical = 8.dp)
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
        Text(title, fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 1)
    }
}

@Composable
private fun HoldingRow(
    h: Holding,
    bulkMode: Boolean,
    selected: Boolean,
    eligible: Boolean,
    onToggle: () -> Unit,
    onNavigate: (HomeScreen) -> Unit,
    onManageDomain: (String) -> Unit
) {
    var menuOpen by remember { mutableStateOf(false) }
    val uriHandler = LocalUriHandler.current

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = bulkMode) { onToggle() }
            .padding(vertical = 4.dp)
    ) {
        if (bulkMode) {
            Icon(
                if (selected) Icons.Filled.CheckBox else Icons.Filled.CheckBoxOutlineBlank,
                contentDescription = null,
                tint = if (eligible) Theme.ink() else Theme.secondaryText().copy(alpha = 0.3f),
                modifier = Modifier.size(22.dp)
            )
        }
        Box(
            Modifier.size(34.dp).clip(RoundedCornerShape(8.dp)).background(Color.Black),
            contentAlignment = Alignment.Center
        ) {
            if (h.kind == HoldingKind.BSVMAP) {
                Box(Modifier.size(18.dp).clip(RoundedCornerShape(3.dp)).background(Theme.bsvmapOrange))
            } else if (h.kind == HoldingKind.OPNS) {
                // @-icon like SNS on search.ordnet.io — and deliberately
                // NO ✓ badge: that mark is reserved for ORDnet inscriptions
                Icon(
                    Icons.Filled.AlternateEmail, contentDescription = null,
                    tint = Color.White, modifier = Modifier.size(16.dp)
                )
            } else {
                OrdplugLogo(size = 22.dp)
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(h.name, fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 1,
                overflow = TextOverflow.Ellipsis, color = Theme.ink())
            Text(
                when (h.kind) {
                    HoldingKind.BSVMAP -> "district #${h.district ?: 0} · block ${h.claimHeight}"
                    // OpNS API responses carry no block height — the row shows just "OpNS"
                    HoldingKind.OPNS -> "OpNS"
                    else -> "block ${h.claimHeight}"
                },
                fontSize = 11.sp, color = Theme.secondaryText()
            )
        }
        StatusPill(h)

        if (!bulkMode) {
            Box {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(Icons.Filled.MoreHoriz, contentDescription = "Actions", tint = Theme.secondaryText())
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false },
                    containerColor = Theme.bgPrimary()) {
                    // OpNS: display, resolve and send ONLY — no marketplace
                    // flows (that decision has explicitly not been taken)
                    if (h.kind == HoldingKind.SNS && h.domainListedUsd != null) {
                        // v3.3 — listed on the DOMAIN registry (USD): manage it
                        // there; deliberately NO second (bsvmap) listing offered
                        DropdownMenuItem(
                            text = { Text("Manage domain listing", color = Theme.ink()) },
                            onClick = { menuOpen = false; onManageDomain(h.name) }
                        )
                    } else if (h.kind != HoldingKind.OPNS) {
                        if (h.isListed) {
                            DropdownMenuItem(
                                text = { Text("Remove listing", color = Theme.ink()) },
                                onClick = { menuOpen = false; onNavigate(HomeScreen.Delist(h)) }
                            )
                        } else if (h.status != "contract") {
                            DropdownMenuItem(
                                text = { Text("List for sale", color = Theme.ink()) },
                                onClick = { menuOpen = false; onNavigate(HomeScreen.ListOrd(h)) }
                            )
                        }
                    }
                    DropdownMenuItem(
                        text = { Text("Send", color = Theme.ink()) },
                        onClick = { menuOpen = false; onNavigate(HomeScreen.SendOrd(h)) }
                    )
                    if (h.kind == HoldingKind.SNS || h.kind == HoldingKind.OPNS) {
                        DropdownMenuItem(
                            text = { Text("View on ORDnet", color = Theme.ink()) },
                            onClick = {
                                menuOpen = false
                                uriHandler.openUri("https://search.ordnet.io/?q=${io.ordnet.wallet.core.Api.enc(h.name)}")
                            }
                        )
                    } else if (h.district != null) {
                        DropdownMenuItem(
                            text = { Text("View on bsvmap.io", color = Theme.ink()) },
                            onClick = {
                                menuOpen = false
                                uriHandler.openUri("https://bsvmap.io/#${h.district}")
                            }
                        )
                    }
                }
            }
        }
    }
}

/** v2.1 — pagineringsbalk (Prev / "Page x / y · N total" / Next), boven de lijst */
@Composable
fun PagerBar(page: Int, pages: Int, total: Int, onPrev: () -> Unit, onNext: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(
            "‹ Prev", fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
            color = if (page <= 0) Theme.secondaryText().copy(alpha = 0.4f) else Theme.ink(),
            modifier = Modifier.clickable(enabled = page > 0) { onPrev() }.padding(vertical = 6.dp)
        )
        Spacer(Modifier.weight(1f))
        Text("Page ${page + 1} / $pages · $total total", fontSize = 12.sp, color = Theme.secondaryText())
        Spacer(Modifier.weight(1f))
        Text(
            "Next ›", fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
            color = if (page >= pages - 1) Theme.secondaryText().copy(alpha = 0.4f) else Theme.ink(),
            modifier = Modifier.clickable(enabled = page < pages - 1) { onNext() }.padding(vertical = 6.dp)
        )
    }
}
