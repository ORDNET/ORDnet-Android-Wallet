package io.ordnet.wallet.ui.components

import android.graphics.Bitmap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import io.ordnet.wallet.ui.Theme

// MARK: - QR code rendering (replaces qrcode.js — native ZXing)

object QR {
    fun generate(text: String, size: Int = 512): Bitmap? {
        return try {
            val hints = mapOf(
                EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
                EncodeHintType.MARGIN to 1
            )
            val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, hints)
            val bmp = Bitmap.createBitmap(matrix.width, matrix.height, Bitmap.Config.ARGB_8888)
            for (x in 0 until matrix.width) {
                for (y in 0 until matrix.height) {
                    bmp.setPixel(x, y, if (matrix.get(x, y)) android.graphics.Color.BLACK else android.graphics.Color.WHITE)
                }
            }
            bmp
        } catch (e: Exception) {
            null
        }
    }
}

@Composable
fun QRCodeView(text: String, modifier: Modifier = Modifier) {
    val bitmap = remember(text) { QR.generate(text) }
    if (bitmap != null) {
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = "QR code",
            contentScale = ContentScale.Fit,
            filterQuality = FilterQuality.None,
            modifier = modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(12.dp))
                .background(Color.White)
                .padding(12.dp)
        )
    } else {
        Text("QR unavailable", color = Theme.secondaryText())
    }
}

// MARK: - QR scanning (send flow: scan a BSV address)

/**
 * Returns a launcher; call .launch() to open the full-screen scanner.
 * Tolerates bitcoin-style URIs: strips scheme + query, like the iOS scanner.
 */
@Composable
fun rememberQrScanner(onCode: (String) -> Unit): () -> Unit {
    val launcher = rememberLauncherForActivityResult(ScanContract()) { result ->
        var code = result.contents ?: return@rememberLauncherForActivityResult
        val lower = code.lowercase()
        if ((lower.startsWith("bitcoin") || lower.startsWith("bsv")) && code.contains(":")) {
            code = code.substringAfter(":")
        }
        code = code.substringBefore("?").trim()
        if (code.isNotEmpty()) onCode(code)
    }
    return {
        launcher.launch(
            ScanOptions()
                .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                .setPrompt("Scan address")
                .setBeepEnabled(false)
                .setOrientationLocked(true)
        )
    }
}
