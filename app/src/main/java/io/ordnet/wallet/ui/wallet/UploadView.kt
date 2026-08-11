package io.ordnet.wallet.ui.wallet

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Base64
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Photo
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.ordnet.wallet.core.Fmt
import io.ordnet.wallet.core.InscriptionRecord
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
import io.ordnet.wallet.ui.components.SegmentedPicker
import java.io.ByteArrayOutputStream
import java.text.DateFormat
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Upload & inscribe — fourth tab. Pick an image, text or HTML file and inscribe
 * it as a 1Sat Ordinal on the active wallet. The transaction is built by the
 * same engine call the ORDnet HTML tools use (identical envelope, ORDnet.io
 * OP_RETURN, service fees, fee formula and 100MB limit). Below the tool:
 * every TXID inscribed via this app with this wallet.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun UploadView(store: WalletStore) {
    var fileData by remember { mutableStateOf<ByteArray?>(null) }
    var filename by remember { mutableStateOf("") }
    var contentType by remember { mutableStateOf("") }
    var error by remember { mutableStateOf("") }
    var success by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    // v3.3 (iOS v2.6.1) — persistent success section: the old success message
    // lived in the file-selection section that is cleared right after a
    // successful inscribe (fileData = null), so it was never visible. This
    // survives until a NEW file/text is picked.
    var lastInscribedTxid by remember { mutableStateOf<String?>(null) }
    var txidCopied by remember { mutableStateOf(false) }
    // v3.3 (iOS v2.6.2) — "Upload this text" scrolls to the Selected section
    val selectedAnchor = remember { BringIntoViewRequester() }

    // type-it-directly editor
    var typedText by remember { mutableStateOf("") }
    var typedKind by remember { mutableStateOf(0) }   // 0 = Text, 1 = HTML
    var staged by remember { mutableStateOf("") }

    // image compression (JPEG/PNG sources)
    var originalImageData by remember { mutableStateOf<ByteArray?>(null) }
    var originalImageCT by remember { mutableStateOf("") }
    var quality by remember { mutableFloatStateOf(1.0f) }   // 1.0 = original

    var minerFee by remember { mutableStateOf(0) }
    var serviceFees by remember { mutableStateOf(3996) }
    var typedFee by remember { mutableStateOf(0) }

    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val uriHandler = LocalUriHandler.current

    val maxSize = 100 * 1024 * 1024   // 100MB — parity with the ORDnet tools

    fun sizeLabel(bytes: Int): String = when {
        bytes < 1024 -> "$bytes bytes"
        bytes < 1024 * 1024 -> String.format(Locale.US, "%.1f KB", bytes / 1024.0)
        else -> String.format(Locale.US, "%.2f MB", bytes / 1024.0 / 1024.0)
    }

    fun sniffImageType(d: ByteArray): String? {
        if (d.size >= 4 && d[0] == 0x89.toByte() && d[1] == 0x50.toByte() && d[2] == 0x4E.toByte() && d[3] == 0x47.toByte()) return "image/png"
        if (d.size >= 3 && d[0] == 0xFF.toByte() && d[1] == 0xD8.toByte() && d[2] == 0xFF.toByte()) return "image/jpeg"
        if (d.size >= 3 && d[0] == 0x47.toByte() && d[1] == 0x49.toByte() && d[2] == 0x46.toByte()) return "image/gif"
        if (d.size > 12 && String(d, 8, 4, Charsets.US_ASCII) == "WEBP") return "image/webp"
        return null
    }

    fun mimeType(name: String, data: ByteArray, resolverType: String?): String {
        sniffImageType(data)?.let { return it }
        if (!resolverType.isNullOrEmpty() && resolverType != "application/octet-stream") return resolverType
        return when (name.substringAfterLast('.', "").lowercase()) {
            "html", "htm" -> "text/html"
            "txt", "md" -> "text/plain"
            "json" -> "application/json"
            "svg" -> "image/svg+xml"
            else -> "text/plain"
        }
    }

    suspend fun refreshFees() {
        minerFee = try { store.engine.fees(fileData?.size ?: 0).inscribeMinerFee } catch (e: Exception) { 0 }
        serviceFees = try { store.engine.fees().totalServiceFees } catch (e: Exception) { 3996 }
    }

    LaunchedEffect(fileData) { refreshFees() }
    LaunchedEffect(typedText) {
        if (typedText.isNotEmpty()) {
            typedFee = try {
                store.engine.fees(typedText.toByteArray(Charsets.UTF_8).size).inscribeMinerFee
            } catch (e: Exception) { 0 }
        }
    }
    LaunchedEffect(Unit) { store.loadInscriptions() }

    /** compression is offered for JPEG/PNG sources (GIF/WebP stay untouched) */
    fun setupCompression(data: ByteArray, ct: String) {
        staged = ""
        if (ct == "image/jpeg" || ct == "image/png") {
            originalImageData = data
            originalImageCT = ct
            quality = 1.0f
        } else {
            originalImageData = null
            originalImageCT = ""
        }
    }

    fun syncFilenameExtension(ct: String) {
        val ext = when (ct) {
            "image/png" -> "png"
            "image/jpeg" -> "jpg"
            else -> return
        }
        val dot = filename.lastIndexOf('.')
        if (dot < 0) return
        filename = filename.substring(0, dot) + ".$ext"
    }

    /**
     * re-encode the ORIGINAL image at the chosen quality; never let
     * "compression" make the file bigger than the original
     */
    fun applyCompression() {
        val orig = originalImageData ?: return
        val q = quality   // snapshot: the slider may move again while we encode
        if (q >= 0.999f) {
            fileData = orig
            contentType = originalImageCT
            syncFilenameExtension(originalImageCT)
            return
        }
        scope.launch {
            val jpeg = withContext(Dispatchers.Default) {
                try {
                    val bmp: Bitmap? = BitmapFactory.decodeByteArray(orig, 0, orig.size)
                    if (bmp == null) null else {
                        val out = ByteArrayOutputStream()
                        bmp.compress(Bitmap.CompressFormat.JPEG, (q * 100).toInt().coerceIn(1, 100), out)
                        out.toByteArray()
                    }
                } catch (e: Exception) { null }
            } ?: return@launch
            if (quality != q) return@launch   // a newer slider position won; drop this result
            if (jpeg.size < orig.size) {
                fileData = jpeg
                contentType = "image/jpeg"
                syncFilenameExtension("image/jpeg")
            } else {
                fileData = orig
                contentType = originalImageCT
                syncFilenameExtension(originalImageCT)
            }
        }
    }

    fun loadUri(uri: Uri, fromPhotos: Boolean) {
        error = ""; success = ""
        lastInscribedTxid = null; txidCopied = false   // new pick clears the success section
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                try {
                    val data = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    var name = ""
                    context.contentResolver.query(uri, null, null, null, null)?.use { c ->
                        val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (idx >= 0 && c.moveToFirst()) name = c.getString(idx) ?: ""
                    }
                    Triple(data, name, context.contentResolver.getType(uri))
                } catch (e: Exception) { Triple(null, "", null) }
            }
            val data = result.first
            if (data == null) {
                error = if (fromPhotos) "Could not read the selected photo." else "Could not read the selected file."
                return@launch
            }
            if (data.size > maxSize) {
                error = "File too large! Maximum size is 100MB."
                return@launch
            }
            if (fromPhotos) {
                val ct = sniffImageType(data) ?: "image/jpeg"
                fileData = data
                contentType = ct
                val ext = ct.substringAfterLast('/').let { if (it == "jpeg") "jpg" else it }
                filename = result.second.ifEmpty { "photo-${System.currentTimeMillis() / 1000}.$ext" }
                setupCompression(data, ct)
            } else {
                fileData = data
                filename = result.second.ifEmpty { "file" }
                contentType = mimeType(filename, data, result.third)
                setupCompression(data, contentType)
            }
        }
    }

    val photoPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) loadUri(uri, fromPhotos = true)
    }
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) loadUri(uri, fromPhotos = false)
    }

    fun clearSelection() {
        lastInscribedTxid = null; txidCopied = false
        fileData = null
        filename = ""
        contentType = ""
        error = ""
        success = ""
        staged = ""
        originalImageData = null
        originalImageCT = ""
        quality = 1.0f
    }

    /** stage the typed text/HTML as the inscription selection — same flow as a picked file */
    fun useTypedText() {
        error = ""; success = ""
        lastInscribedTxid = null; txidCopied = false   // new pick clears the success section
        val text = typedText
        if (text.trim().isEmpty()) return
        val data = text.toByteArray(Charsets.UTF_8)
        if (data.size > maxSize) {
            error = "Content too large! Maximum size is 100MB."
            return
        }
        fileData = data
        contentType = if (typedKind == 1) "text/html" else "text/plain"
        filename = "inscription-${System.currentTimeMillis() / 1000}.${if (typedKind == 1) "html" else "txt"}"
        originalImageData = null
        originalImageCT = ""
        staged = "Staged above ✓ — review the details and tap Inscribe on-chain."
        // v3.3 — the Selected section now sits ABOVE this editor: auto-scroll
        // to it so the typed-text flow keeps working identically
        scope.launch {
            kotlinx.coroutines.delay(150)   // let the section compose first
            try { selectedAnchor.bringIntoView() } catch (e: Exception) { }
        }
    }

    fun inscribe() {
        val data = fileData ?: return
        error = ""; success = ""; busy = true
        scope.launch {
            try {
                val b64 = withContext(Dispatchers.Default) { Base64.encodeToString(data, Base64.NO_WRAP) }
                val txid = store.inscribe(contentType = contentType, dataB64 = b64)
                store.recordInscription(txid = txid, contentType = contentType,
                    filename = filename, bytes = data.size)
                lastInscribedTxid = txid
                txidCopied = false
                fileData = null
                filename = ""
                contentType = ""
                staged = ""
                originalImageData = null
                originalImageCT = ""
                quality = 1.0f
                store.refreshBalance()
            } catch (e: Exception) {
                error = e.message ?: "Inscribe failed."
            }
            busy = false
        }
    }

    OrdnetScreen(title = "Upload & Inscribe") {
        FormSection(header = "Pick a file to inscribe") {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                OrdnetOutlineButton(
                    onClick = {
                        photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Photo, contentDescription = null, modifier = Modifier.size(16.dp))
                        Text("Photos")
                    }
                }
                OrdnetOutlineButton(
                    onClick = {
                        filePicker.launch(arrayOf("image/*", "text/*", "text/html", "application/json"))
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Folder, contentDescription = null, modifier = Modifier.size(16.dp))
                        Text("Files")
                    }
                }
            }
            Text(
                "Supported: images (JPEG, PNG, GIF, WebP), text files and HTML. Content is written permanently on-chain as a 1Sat Ordinal.",
                fontSize = 12.sp, color = Theme.secondaryText()
            )
        }

        // v3.3 (iOS v2.6.2) — the Selected section (preview, compression,
        // fees, Inscribe button) sits DIRECTLY under "Pick a file to inscribe"
        // instead of below the "Or type it directly" editor: pick photo →
        // inscribe is the common route. The editor's "Upload this ..." button
        // auto-scrolls up to this anchor, so the typed-text flow is unchanged.
        val data = fileData
        if (data != null) {
          Column(Modifier.bringIntoViewRequester(selectedAnchor)) {
            FormSection(header = "Selected") {
                // preview
                if (contentType.startsWith("image/")) {
                    // decode OFF the main thread and downsampled to preview size —
                    // files here may be up to 100MB; a synchronous full decode in
                    // composition froze the UI (ANR) or OOM-crashed on big photos
                    val bmp by produceState<Bitmap?>(initialValue = null, data) {
                        value = withContext(Dispatchers.Default) {
                            try {
                                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                                BitmapFactory.decodeByteArray(data, 0, data.size, bounds)
                                var sample = 1
                                while (bounds.outWidth / (sample * 2) >= 1024 || bounds.outHeight / (sample * 2) >= 1024) sample *= 2
                                val opts = BitmapFactory.Options().apply { inSampleSize = sample }
                                BitmapFactory.decodeByteArray(data, 0, data.size, opts)
                            } catch (e: Exception) { null }
                        }
                    }
                    val preview = bmp   // local copy: delegated state can't be smart-cast
                    if (preview != null) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                            Image(
                                bitmap = preview.asImageBitmap(),
                                contentDescription = null,
                                modifier = Modifier.heightIn(max = 200.dp).clip(RoundedCornerShape(10.dp))
                            )
                        }
                    }
                } else if (contentType.startsWith("text/")) {
                    val text = try {
                        String(data, 0, minOf(600, data.size), Charsets.UTF_8) + (if (data.size > 600) "…" else "")
                    } catch (e: Exception) { "" }
                    Text(text, fontSize = 12.sp, fontFamily = FontFamily.Monospace,
                        color = Theme.secondaryText(), maxLines = 10, overflow = TextOverflow.Ellipsis)
                }
                val orig = originalImageData
                if (orig != null) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(Modifier.fillMaxWidth()) {
                            Text("Compression", fontSize = 13.sp, color = Theme.ink())
                            Spacer(Modifier.weight(1f))
                            Text(
                                if (quality >= 0.999f) "Original · ${sizeLabel(orig.size)}"
                                else "${(quality * 100).toInt()}% · ${sizeLabel(orig.size)} → ${sizeLabel(data.size)}",
                                fontSize = 12.sp, color = Theme.secondaryText()
                            )
                        }
                        Slider(
                            value = quality,
                            onValueChange = { quality = it },
                            onValueChangeFinished = { applyCompression() },
                            valueRange = 0.1f..1.0f,
                            steps = 17,
                            colors = SliderDefaults.colors(
                                thumbColor = Theme.ink(),
                                activeTrackColor = Theme.ink(),
                                inactiveTrackColor = Theme.ink().copy(alpha = 0.2f)
                            )
                        )
                    }
                }
                KVRow(k = "File", v = filename)
                KVRow(k = "Content type", v = contentType)
                KVRow(k = "Size", v = sizeLabel(data.size))
                KVRow(k = "Miner fee", v = "~${Fmt.bsv(minerFee)} BSV")
                KVRow(k = "Service fee", v = "${Fmt.bsv(serviceFees)} BSV")
                KVRow(k = "Inscribe to", v = Fmt.shortAddress(store.address), mono = true)
            }
            FormSection {
                InlineAlert(AlertKind.ERROR, error)
                InlineAlert(AlertKind.SUCCESS, success)
                OrdnetProminentButton(onClick = { inscribe() }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                    if (busy) ButtonSpinner()
                    else Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Upload, contentDescription = null, modifier = Modifier.size(16.dp))
                        Text("Inscribe on-chain", fontWeight = FontWeight.SemiBold)
                    }
                }
                OrdnetOutlineButton(onClick = { clearSelection() }, enabled = !busy) {
                    Text("Clear selection")
                }
            }
          }
        } else if (error.isNotEmpty()) {
            InlineAlert(AlertKind.ERROR, error)
        }

        // v3.3 (iOS v2.6.1) — persistent success section: FULL TXID, one tap
        // copies it; cleared only when a new file/text is picked
        val inscribedTxid = lastInscribedTxid
        if (inscribedTxid != null) {
            FormSection(
                header = "Inscribed successfully ✓",
                footer = "Tap the TXID to copy it. Your inscription is now in the ORD/ner tab."
            ) {
                Text(
                    inscribedTxid,
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                    color = if (txidCopied) Theme.statusGreen else Theme.ink(),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            clipboard.setText(AnnotatedString(inscribedTxid))
                            txidCopied = true
                        }
                        .padding(vertical = 4.dp)
                )
                if (txidCopied) {
                    InlineAlert(AlertKind.SUCCESS, "TXID copied to clipboard.")
                }
            }
        }

        FormSection(
            header = "Or type it directly",
            footer = "The Text/HTML switch sets the content-type in the ordinal envelope: text/plain renders as plain text, text/html as a live on-chain page."
        ) {
            OrdnetTextField(
                value = typedText, onValueChange = { typedText = it },
                placeholder = "Type or paste your text / HTML…",
                mono = true, singleLine = false, minLines = 6,
                modifier = Modifier.fillMaxWidth().heightIn(min = 140.dp)
            )
            SegmentedPicker(options = listOf("Text", "HTML"), selected = typedKind, onSelect = { typedKind = it })
            if (typedText.isNotEmpty()) {
                val bytes = typedText.toByteArray(Charsets.UTF_8).size
                Text(
                    "${sizeLabel(bytes)} · ${if (typedKind == 1) "text/html" else "text/plain"} · ~${Fmt.bsv((typedFee + serviceFees).toLong())} BSV total",
                    fontSize = 12.sp, color = Theme.secondaryText()
                )
            }
            InlineAlert(AlertKind.SUCCESS, staged)
            OrdnetOutlineButton(
                onClick = { useTypedText() },
                enabled = typedText.trim().isNotEmpty(),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Description, contentDescription = null, modifier = Modifier.size(16.dp))
                    Text("Upload this ${if (typedKind == 1) "HTML" else "text"}")
                }
            }
        }

        // v3.2 — the "Inscribed with this wallet" list moved to ORD/ner,
        // where every file is browsable, copyable and sendable
        FormSection {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(Icons.Filled.Folder, contentDescription = null,
                    tint = Theme.secondaryText(), modifier = Modifier.size(18.dp))
                Text(
                    "Your inscribed files now live in the ORD/ner tab — browse, copy TX info and send them from there.",
                    fontSize = 13.sp, color = Theme.secondaryText()
                )
            }
        }
    }
}
