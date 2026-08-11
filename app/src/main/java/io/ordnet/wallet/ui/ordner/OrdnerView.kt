package io.ordnet.wallet.ui.ordner

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DataObject
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Image as ImageIcon
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.NorthEast
import androidx.compose.material.icons.filled.Public
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.ordnet.wallet.core.Account
import io.ordnet.wallet.core.Api
import io.ordnet.wallet.core.Fmt
import io.ordnet.wallet.core.Holding
import io.ordnet.wallet.core.HoldingKind
import io.ordnet.wallet.core.OrdnerFile
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
import io.ordnet.wallet.ui.components.SegmentedPicker
import io.ordnet.wallet.ui.components.SpinnerRow
import io.ordnet.wallet.ui.wallet.SendOrdinalView
import org.json.JSONObject

/** internal navigation of the ORD/ner tab */
private sealed class OrdnerScreen {
    data object Main : OrdnerScreen()
    data class Folder(val account: Account) : OrdnerScreen()
    data class Detail(val file: OrdnerFile, val account: Account) : OrdnerScreen()
    data class SendFile(val file: OrdnerFile, val account: Account) : OrdnerScreen()
}

/**
 * ORD/ner (v3.2) — the on-chain file browser, native port of ord-app v42:
 * accounts are FOLDERS; a folder shows every inscription the address
 * currently holds (1Sat index), in grid or list view. Tapping a file opens
 * a detail screen with preview + "Open in Browser" / "Copy TX info" / "Send"
 * (1-sat ordinal transfer). The Upload tab's "Inscribed with this wallet"
 * log lives here now: it supplies filenames, and items the address no
 * longer holds appear with a "sent" label (hideable).
 */
@Composable
fun OrdnerView(store: WalletStore, onOpenInBrowser: () -> Unit) {
    var screen by remember { mutableStateOf<OrdnerScreen>(OrdnerScreen.Main) }

    when (val s = screen) {
        is OrdnerScreen.Folder -> OrdnerFolderView(store, s.account,
            onBack = { screen = OrdnerScreen.Main },
            onOpen = { f -> screen = OrdnerScreen.Detail(f, s.account) })
        is OrdnerScreen.Detail -> OrdnerFileDetailView(store, s.file, s.account,
            onBack = { screen = OrdnerScreen.Folder(s.account) },
            onSend = { screen = OrdnerScreen.SendFile(s.file, s.account) },
            onOpenInBrowser = onOpenInBrowser)
        is OrdnerScreen.SendFile -> SendOrdinalView(store, s.file.asHolding(),
            onBack = { screen = OrdnerScreen.Detail(s.file, s.account) })
        is OrdnerScreen.Main -> OrdnerMain(store, onOpenFolder = { screen = OrdnerScreen.Folder(it) })
    }
}

/** the existing 1-sat ordinal transfer flow does the rest (incl. the
 *  ownership check — sending from a non-active account explains itself) */
private fun OrdnerFile.asHolding(): Holding = Holding(
    kind = HoldingKind.INSCRIPTION,
    name = displayName,
    district = null,
    claimHeight = height ?: 0,
    status = "held",
    currentTxid = currentTxid,
    currentVout = currentVout,
    priceSat = null
)

@Composable
private fun OrdnerMain(store: WalletStore, onOpenFolder: (Account) -> Unit) {
    OrdnetScreen(title = "ORD/ner") {
        FormSection(header = "ORD/ner") {
            store.accounts.forEachIndexed { i, acc ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxWidth().clickable { onOpenFolder(acc) }.padding(vertical = 6.dp)
                ) {
                    Icon(Icons.Filled.Folder, contentDescription = null,
                        tint = Theme.statusYellow, modifier = Modifier.size(26.dp))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(acc.name, fontSize = 15.sp, fontWeight = FontWeight.Medium, color = Theme.ink())
                        Text(Fmt.shortAddress(acc.address), fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace, color = Theme.secondaryText())
                    }
                    if (i == store.active) {
                        Text("active", fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
                            color = Theme.statusGreen,
                            modifier = Modifier
                                .clip(CircleShape)
                                .background(Theme.statusGreen.copy(alpha = 0.15f))
                                .padding(horizontal = 7.dp, vertical = 3.dp))
                    }
                }
            }
            Text("Every account is a folder with the on-chain files it currently holds.",
                fontSize = 12.sp, color = Theme.secondaryText())
        }
    }
}

// MARK: - folder: the files of one account

@Composable
private fun OrdnerFolderView(
    store: WalletStore,
    account: Account,
    onBack: () -> Unit,
    onOpen: (OrdnerFile) -> Unit
) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("ordplug_prefs", Context.MODE_PRIVATE) }
    var gridView by remember { mutableStateOf(prefs.getBoolean("ordner_view_grid", true)) }
    var hideSent by remember { mutableStateOf(prefs.getBoolean("ordner_hide_sent", false)) }
    var files by remember { mutableStateOf<List<OrdnerFile>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf("") }

    val visibleFiles = if (hideSent) files.filter { !it.sentLabel } else files
    val sentCount = files.count { it.sentLabel }

    suspend fun load() {
        error = ""
        val log = store.inscriptionLog(account.address)
        try {
            var chain = Api.ordnerFiles(account.address)
            // enrich with filenames from the app's inscription log
            val names = log.associate { it.txid to it.filename }
            chain = chain.map { f -> names[f.originTxid]?.let { f.copy(name = it) } ?: f }
            // log items the address no longer holds -> "sent" entries
            val held = chain.map { it.originTxid }.toSet()
            val sent = log.filter { !held.contains(it.txid) }.map { rec ->
                OrdnerFile(originTxid = rec.txid, originVout = 0,
                    currentTxid = rec.txid, currentVout = 0,
                    contentType = rec.contentType, size = rec.bytes,
                    height = null, name = rec.filename, sentLabel = true)
            }
            files = chain + sent
        } catch (e: Exception) {
            // index down: degrade to the local log only, inline note
            error = "Could not reach the 1Sat index — showing only files inscribed via this app."
            files = log.map { rec ->
                OrdnerFile(originTxid = rec.txid, originVout = 0,
                    currentTxid = rec.txid, currentVout = 0,
                    contentType = rec.contentType, size = rec.bytes,
                    height = null, name = rec.filename)
            }
        }
        loading = false
    }

    LaunchedEffect(account.address) { load() }

    OrdnetScreen(title = account.name, onBack = onBack, scrollable = false) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                // toolbar row: breadcrumb + grid/list toggle (the v42 pattern)
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Text("ORD/ner › ${account.name}", fontSize = 13.sp,
                        fontWeight = FontWeight.Medium, color = Theme.secondaryText())
                    Spacer(Modifier.weight(1f))
                    Box(Modifier.size(width = 110.dp, height = 34.dp)) {
                        SegmentedPicker(
                            options = listOf("Grid", "List"),
                            selected = if (gridView) 0 else 1,
                            onSelect = {
                                gridView = it == 0
                                prefs.edit().putBoolean("ordner_view_grid", gridView).apply()
                            }
                        )
                    }
                }
            }
            if (sentCount > 0) {
                item {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        Text("Hide sent items ($sentCount)", fontSize = 13.sp, color = Theme.ink())
                        Spacer(Modifier.weight(1f))
                        Switch(checked = hideSent, onCheckedChange = {
                            hideSent = it
                            prefs.edit().putBoolean("ordner_hide_sent", it).apply()
                        })
                    }
                }
            }
            when {
                loading -> item { SpinnerRow() }
                error.isNotEmpty() && visibleFiles.isEmpty() -> item { InlineAlert(AlertKind.ERROR, error) }
                else -> {
                    if (error.isNotEmpty()) item { InlineAlert(AlertKind.ERROR, error) }
                    if (visibleFiles.isEmpty()) {
                        item {
                            Text("No inscriptions in this folder yet.",
                                fontSize = 13.sp, color = Theme.secondaryText())
                        }
                    } else if (gridView) {
                        items(visibleFiles.chunked(3), key = { it.first().id }) { rowFiles ->
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                                for (f in rowFiles) {
                                    Column(
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                        verticalArrangement = Arrangement.spacedBy(6.dp),
                                        modifier = Modifier.weight(1f).clickable { onOpen(f) }.padding(vertical = 4.dp)
                                    ) {
                                        OrdnerThumb(f, side = 76.dp)
                                        Text(f.displayName, fontSize = 11.sp, maxLines = 2,
                                            textAlign = TextAlign.Center,
                                            overflow = TextOverflow.Ellipsis, color = Theme.ink())
                                        if (f.sentLabel) SentPill()
                                    }
                                }
                                repeat(3 - rowFiles.size) { Spacer(Modifier.weight(1f)) }
                            }
                        }
                    } else {
                        items(visibleFiles, key = { it.id }) { f ->
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                modifier = Modifier.fillMaxWidth().clickable { onOpen(f) }.padding(vertical = 4.dp)
                            ) {
                                OrdnerThumb(f, side = 34.dp)
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    Text(f.displayName, fontSize = 15.sp, fontWeight = FontWeight.Medium,
                                        maxLines = 1, overflow = TextOverflow.Ellipsis, color = Theme.ink())
                                    Text("${f.typeLabel} · ${f.sizeLabel} · ${f.height?.let { "block $it" } ?: "pending"}",
                                        fontSize = 11.sp, color = Theme.secondaryText())
                                }
                                if (f.sentLabel) SentPill()
                            }
                        }
                    }
                    item {
                        Text("${visibleFiles.size} file${if (visibleFiles.size == 1) "" else "s"} · on-chain holdings of ${Fmt.shortAddress(account.address)}",
                            fontSize = 12.sp, color = Theme.secondaryText(),
                            modifier = Modifier.padding(vertical = 6.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun SentPill() {
    Text("sent", fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
        color = Theme.secondaryText(),
        modifier = Modifier
            .clip(CircleShape)
            .background(Theme.secondaryText().copy(alpha = 0.12f))
            .padding(horizontal = 7.dp, vertical = 3.dp))
}

// MARK: - thumbnail (images render, everything else gets its type icon)

private fun ordnerIcon(contentType: String): ImageVector = when {
    contentType.startsWith("image/") -> Icons.Filled.ImageIcon
    contentType.startsWith("video/") -> Icons.Filled.Movie
    contentType.startsWith("audio/") -> Icons.Filled.Audiotrack
    contentType.startsWith("text/html") -> Icons.Filled.Language
    contentType.startsWith("text/") -> Icons.Filled.Description
    contentType.contains("json") -> Icons.Filled.DataObject
    else -> Icons.AutoMirrored.Filled.InsertDriveFile
}

@Composable
private fun OrdnerThumb(file: OrdnerFile, side: androidx.compose.ui.unit.Dp) {
    var bitmap by remember(file.id) { mutableStateOf<Bitmap?>(null) }

    LaunchedEffect(file.id) {
        if (bitmap != null || !file.contentType.startsWith("image/") ||
            file.size <= 0 || file.size >= 2 * 1024 * 1024) return@LaunchedEffect
        // content via our OWN path: raw hex + extractOrd (txHex is cached)
        try {
            val hex = Api.txHex(file.originTxid)
            val ord = WalletEngine.shared.call("extractOrd",
                WalletEngine.shared.args("rawTxHex" to hex, "vout" to file.originVout)) as? JSONObject
                ?: return@LaunchedEffect
            val b64 = ord.optString("dataB64")
            if (b64.isEmpty()) return@LaunchedEffect
            val bytes = Base64.decode(b64, Base64.DEFAULT)
            bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        } catch (e: Exception) { }
    }

    Box(
        Modifier.size(side).clip(RoundedCornerShape(if (side > 50.dp) 10.dp else 8.dp)).background(Color.Black),
        contentAlignment = Alignment.Center
    ) {
        val bmp = bitmap
        if (bmp != null) {
            Image(bmp.asImageBitmap(), contentDescription = null,
                contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } else {
            Icon(ordnerIcon(file.contentType), contentDescription = null,
                tint = Color.White, modifier = Modifier.size(side * 0.42f))
        }
    }
}

// MARK: - file detail: preview + Open in Browser / Copy TX info / Send

@Composable
private fun OrdnerFileDetailView(
    store: WalletStore,
    file: OrdnerFile,
    account: Account,
    onBack: () -> Unit,
    onSend: () -> Unit,
    onOpenInBrowser: () -> Unit
) {
    var textPreview by remember { mutableStateOf("") }
    var copied by remember { mutableStateOf("") }
    val clipboard = LocalClipboardManager.current

    LaunchedEffect(file.id) {
        val ct = file.contentType
        if (!(ct.startsWith("text/") || ct.contains("json")) || ct.startsWith("text/html") ||
            file.size >= 512 * 1024) return@LaunchedEffect
        try {
            val hex = Api.txHex(file.originTxid)
            val ord = WalletEngine.shared.call("extractOrd",
                WalletEngine.shared.args("rawTxHex" to hex, "vout" to file.originVout)) as? JSONObject
                ?: return@LaunchedEffect
            val b64 = ord.optString("dataB64")
            if (b64.isEmpty()) return@LaunchedEffect
            val text = String(Base64.decode(b64, Base64.DEFAULT), Charsets.UTF_8)
            textPreview = text.take(300)
        } catch (e: Exception) { }
    }

    OrdnetScreen(title = file.displayName, onBack = onBack) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            OrdnerThumb(file, side = 140.dp)
            if (textPreview.isNotEmpty()) {
                Text(textPreview, fontSize = 12.sp, fontFamily = FontFamily.Monospace,
                    maxLines = 6, overflow = TextOverflow.Ellipsis, color = Theme.secondaryText())
            }
        }

        if (file.sentLabel) {
            InlineAlert(AlertKind.WARNING,
                "This file was inscribed via this app but the address no longer holds it — it was sent or transferred.")
        }

        FormSection(header = "File") {
            file.name?.let { KVRow(k = "Name", v = it) }
            KVRow(k = "Type", v = file.contentType)
            KVRow(k = "Size", v = file.sizeLabel)
            KVRow(k = "Status", v = file.height?.let { "confirmed · block $it" }
                ?: (if (file.sentLabel) "—" else "pending (mempool)"))
        }

        // v3.3 (iOS v2.6.1 parity) — ONE TAP on a row copies the FULL value
        // (long-press used to copy only the truncated display text), with an
        // inline "copied ✓" confirmation and an explanatory footer
        var txCopied by remember { mutableStateOf("") }
        FormSection(header = "Transaction", footer = "Tap a row to copy the full value.") {
            CopyRow(k = "TXID", display = Fmt.shortTxid(file.originTxid),
                full = file.originTxid,
                onCopied = { txCopied = "TXID copied ✓" }, clipboard = clipboard)
            CopyRow(k = "Origin", display = "${Fmt.shortTxid(file.originTxid)}_${file.originVout}",
                full = "${file.originTxid}_${file.originVout}",
                onCopied = { txCopied = "Origin copied ✓" }, clipboard = clipboard)
            if (!file.sentLabel) {
                CopyRow(k = "Current UTXO", display = "${Fmt.shortTxid(file.currentTxid)}_${file.currentVout}",
                    full = "${file.currentTxid}_${file.currentVout}",
                    onCopied = { txCopied = "Current UTXO copied ✓" }, clipboard = clipboard)
            }
            InlineAlert(AlertKind.SUCCESS, txCopied)
        }

        FormSection {
            InlineAlert(AlertKind.SUCCESS, copied)
            OrdnetProminentButton(onClick = {
                store.browserOpenRequest = file.originTxid
                onOpenInBrowser()
            }, modifier = Modifier.fillMaxWidth()) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Public, contentDescription = null, modifier = Modifier.size(16.dp))
                    Text("Open in Browser")
                }
            }
            // v3.3 — full-width separators between the buttons (iOS v2.6.1)
            HorizontalDivider(color = Theme.ink().copy(alpha = 0.08f))
            // v3.3 — new "Copy TXID" button (just the full TXID)
            OrdnetOutlineButton(onClick = {
                clipboard.setText(AnnotatedString(file.originTxid))
                copied = "TXID copied to clipboard."
            }, modifier = Modifier.fillMaxWidth()) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                    Text("Copy TXID")
                }
            }
            HorizontalDivider(color = Theme.ink().copy(alpha = 0.08f))
            // v3.3 — renamed from "Copy TX info"
            OrdnetOutlineButton(onClick = {
                var info = "TXID: ${file.originTxid}\nOrigin: ${file.originTxid}_${file.originVout}"
                if (!file.sentLabel) {
                    info += "\nCurrent outpoint: ${file.currentTxid}_${file.currentVout}"
                }
                info += "\nContent-Type: ${file.contentType}\nSize: ${file.sizeLabel}"
                clipboard.setText(AnnotatedString(info))
                copied = "All info copied to clipboard."
            }, modifier = Modifier.fillMaxWidth()) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                    Text("Copy all info")
                }
            }
            if (!file.sentLabel) {
                HorizontalDivider(color = Theme.ink().copy(alpha = 0.08f))
                OrdnetOutlineButton(onClick = onSend, modifier = Modifier.fillMaxWidth()) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.NorthEast, contentDescription = null, modifier = Modifier.size(16.dp))
                        Text("Send (1Sat Ordinal)")
                    }
                }
            }
        }
    }
}

/** v3.3 — one tappable key/value row: ONE TAP copies the FULL value */
@Composable
private fun CopyRow(
    k: String,
    display: String,
    full: String,
    onCopied: () -> Unit,
    clipboard: androidx.compose.ui.platform.ClipboardManager
) {
    Row(
        verticalAlignment = Alignment.Top,
        modifier = Modifier
            .fillMaxWidth()
            .clickable {
                clipboard.setText(AnnotatedString(full))
                onCopied()
            }
            .padding(vertical = 3.dp)
    ) {
        Text(k, fontSize = 13.sp, color = Theme.secondaryText())
        Spacer(Modifier.size(12.dp))
        Text(
            display,
            fontSize = 13.sp,
            fontFamily = FontFamily.Monospace,
            color = Theme.ink(),
            textAlign = TextAlign.End,
            modifier = Modifier.weight(1f)
        )
    }
}
