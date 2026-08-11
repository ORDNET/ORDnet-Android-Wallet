package io.ordnet.wallet.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Button
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Sell
import androidx.compose.material.icons.filled.WarningAmber
import io.ordnet.wallet.core.Fmt
import io.ordnet.wallet.core.Holding

/**
 * ORDnet design system, translated from the extension's CSS custom properties
 * (and 1-on-1 from the iOS Theme.swift).
 * Light: warm paper (#fcfaf5) with near-black ink. Dark: deep night (#0a0a0f).
 */
object Theme {
    /** ORDnet beige (#fbf9f2) in light mode, deep night (#0a0a0f) in dark mode */
    val bgPrimaryLight = Color(0xFFFBF9F2)
    val bgPrimaryDark = Color(0xFF0A0A0F)
    val bgSecondaryLight = Color(0xFFF5F3ED)
    val bgSecondaryDark = Color(0xFF12121A)
    val inkLight = Color(0xFF0A0A0A)
    val inkDark = Color(0xFFFCFAF5)

    val statusGreen = Color(0xFF22C55E)
    val statusRed = Color(0xFFEF4444)
    val statusYellow = Color(0xFFEAB308)
    val statusBlue = Color(0xFF3B82F6)
    val bsvmapOrange = Color(0xFFF7931E)

    @Composable
    fun bgPrimary(): Color =
        if (isSystemInDarkTheme()) bgPrimaryDark else bgPrimaryLight

    @Composable
    fun bgSecondary(): Color =
        if (isSystemInDarkTheme()) bgSecondaryDark else bgSecondaryLight

    /** the "ink" — SwiftUI's Color.primary */
    @Composable
    fun ink(): Color = if (isSystemInDarkTheme()) inkDark else inkLight

    @Composable
    fun secondaryText(): Color = ink().copy(alpha = 0.55f)
}

private val LightColors = lightColorScheme(
    primary = Theme.inkLight,
    onPrimary = Theme.bgPrimaryLight,
    secondary = Theme.inkLight,
    background = Theme.bgPrimaryLight,
    onBackground = Theme.inkLight,
    surface = Theme.bgPrimaryLight,
    onSurface = Theme.inkLight,
    surfaceVariant = Theme.bgSecondaryLight,
    onSurfaceVariant = Color(0xFF5A5850),
    outline = Color(0x14000000),
    error = Theme.statusRed
)

private val DarkColors = darkColorScheme(
    primary = Theme.inkDark,
    onPrimary = Theme.bgPrimaryDark,
    secondary = Theme.inkDark,
    background = Theme.bgPrimaryDark,
    onBackground = Theme.inkDark,
    surface = Theme.bgPrimaryDark,
    onSurface = Theme.inkDark,
    surfaceVariant = Theme.bgSecondaryDark,
    onSurfaceVariant = Color(0xFFA0A0A8),
    outline = Color(0x14FFFFFF),
    error = Theme.statusRed
)

@Composable
fun OrdplugTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
        content = content
    )
}

// MARK: - card (port of CardBackground)

@Composable
fun Modifier.card(): Modifier {
    val dark = isSystemInDarkTheme()
    return this
        .clip(RoundedCornerShape(14.dp))
        .background(if (dark) Color.White.copy(alpha = 0.04f) else Color.White.copy(alpha = 0.85f))
        .border(
            1.dp,
            if (dark) Color.White.copy(alpha = 0.08f) else Color.Black.copy(alpha = 0.08f),
            RoundedCornerShape(14.dp)
        )
        .padding(14.dp)
}

// MARK: - ORDnet capsule buttons
// Capsule (border-radius: 999px) is the ORDnet signature shape — outline
// buttons get exactly the same geometry as the prominent (black) buttons.

@Composable
fun OrdnetOutlineButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable () -> Unit
) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier.defaultMinSize(minHeight = 36.dp),
        enabled = enabled,
        shape = CircleShape,
        border = BorderStroke(1.5.dp, Theme.ink().copy(alpha = if (enabled) 1f else 0.4f)),
        colors = ButtonDefaults.outlinedButtonColors(
            containerColor = Theme.bgPrimary(),
            contentColor = Theme.ink(),
            disabledContainerColor = Theme.bgPrimary(),
            disabledContentColor = Theme.ink().copy(alpha = 0.4f)
        ),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 14.dp, vertical = 8.dp)
    ) {
        content()
    }
}

/** black capsule — identical geometry to the outline style */
@Composable
fun OrdnetProminentButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    destructive: Boolean = false,
    content: @Composable () -> Unit
) {
    Button(
        onClick = onClick,
        modifier = modifier.defaultMinSize(minHeight = 36.dp),
        enabled = enabled,
        shape = CircleShape,
        colors = ButtonDefaults.buttonColors(
            containerColor = if (destructive) Theme.statusRed else Theme.ink(),
            contentColor = if (destructive) Color.White else Theme.bgPrimary(),
            disabledContainerColor = (if (destructive) Theme.statusRed else Theme.ink()).copy(alpha = 0.4f),
            disabledContentColor = Theme.bgPrimary()
        ),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 14.dp, vertical = 8.dp)
    ) {
        content()
    }
}

// MARK: - inline alert — errors are ALWAYS shown inline, never as popups

enum class AlertKind { ERROR, SUCCESS, WARNING }

@Composable
fun InlineAlert(kind: AlertKind, text: String, modifier: Modifier = Modifier) {
    if (text.isEmpty()) return
    val color = when (kind) {
        AlertKind.ERROR -> Theme.statusRed
        AlertKind.SUCCESS -> Theme.statusGreen
        AlertKind.WARNING -> Theme.statusYellow
    }
    val icon: ImageVector = when (kind) {
        AlertKind.ERROR -> Icons.Filled.WarningAmber
        AlertKind.SUCCESS -> Icons.Filled.CheckCircle
        AlertKind.WARNING -> Icons.Filled.ErrorOutline
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(color.copy(alpha = 0.10f))
            .padding(10.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.Top
    ) {
        Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(16.dp))
        androidx.compose.foundation.text.selection.SelectionContainer(Modifier.weight(1f)) {
            Text(text, color = color, fontSize = 13.sp, lineHeight = 17.sp)
        }
    }
}

// MARK: - key/value row — port of the extension's .kv rows

@Composable
fun KVRow(k: String, v: String, mono: Boolean = false) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        verticalAlignment = Alignment.Top
    ) {
        Text(k, fontSize = 13.sp, color = Theme.secondaryText())
        Spacer(Modifier.width(12.dp))
        androidx.compose.foundation.text.selection.SelectionContainer(Modifier.weight(1f)) {
            Text(
                v,
                fontSize = 13.sp,
                fontFamily = if (mono) FontFamily.Monospace else null,
                textAlign = TextAlign.End,
                modifier = Modifier.fillMaxWidth(),
                color = Theme.ink()
            )
        }
    }
}

// MARK: - status pill for holdings (held / listed / contract)

@Composable
fun StatusPill(holding: Holding) {
    val domainUsd = holding.domainListedUsd
    if (holding.isListed) {
        Row(
            modifier = Modifier
                .clip(CircleShape)
                .background(Theme.statusGreen.copy(alpha = 0.15f))
                .padding(horizontal = 7.dp, vertical = 3.dp),
            horizontalArrangement = Arrangement.spacedBy(3.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Filled.Sell, contentDescription = null,
                tint = Theme.statusGreen, modifier = Modifier.size(9.dp))
            val p = holding.priceSat
            if (p != null && p > 0) {
                Text("${Fmt.bsv(p)} BSV", color = Theme.statusGreen,
                    fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    } else if (domainUsd != null) {
        // v3.3 — SNS name listed on the DOMAIN registry (v2 platform, USD):
        // the other marketplace, synced into the holdings view (iOS v2.6.1)
        Row(
            modifier = Modifier
                .clip(CircleShape)
                .background(Theme.statusGreen.copy(alpha = 0.15f))
                .padding(horizontal = 7.dp, vertical = 3.dp),
            horizontalArrangement = Arrangement.spacedBy(3.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Filled.Sell, contentDescription = null,
                tint = Theme.statusGreen, modifier = Modifier.size(9.dp))
            Text(
                "For sale · ${'$'}" + (if (domainUsd == Math.floor(domainUsd)) domainUsd.toLong().toString()
                    else String.format(java.util.Locale.US, "%.2f", domainUsd)),
                color = Theme.statusGreen,
                fontSize = 11.sp, fontWeight = FontWeight.SemiBold
            )
        }
    } else {
        Text(
            holding.status,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            color = Theme.secondaryText(),
            modifier = Modifier
                .clip(CircleShape)
                .background(Theme.secondaryText().copy(alpha = 0.12f))
                .padding(horizontal = 7.dp, vertical = 3.dp)
        )
    }
}

// MARK: - ORD/plug segmented-donut "C" logo (port of the SNS mark SVG)

@Composable
fun OrdplugLogo(size: Dp = 44.dp) {
    val tile = Color(0xFF0A0A0A)
    val ring = Color(0xFFFCFAF5)
    Box(
        modifier = Modifier
            .size(size)
            .clip(RoundedCornerShape(size * 0.22f))
            .background(tile),
        contentAlignment = Alignment.Center
    ) {
        // donut ring
        Box(
            Modifier
                .size(size * 0.5f + size * 0.18f)
                .border(width = size * 0.18f, color = ring, shape = CircleShape)
        )
        // segmentation lines (port of the three cut lines)
        Box(
            Modifier
                .size(width = size * 0.07f, height = size * 0.44f)
                .offset(x = -size * 0.04f, y = -size * 0.22f)
                .background(tile)
        )
        Box(
            Modifier
                .size(width = size * 0.46f, height = size * 0.07f)
                .offset(x = size * 0.23f, y = -size * 0.02f)
                .background(tile)
        )
        Box(
            Modifier
                .size(width = size * 0.07f, height = size * 0.33f)
                .offset(x = size * 0.17f, y = size * 0.17f)
                .rotate(-45f)
                .background(tile)
        )
    }
}

// MARK: - form section helper (Compose has no SwiftUI Form; this mirrors the
// grouped-list look on the ORDnet beige background)

@Composable
fun FormSection(
    header: String? = null,
    footer: String? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(Modifier.fillMaxWidth()) {
        if (header != null) {
            Text(
                header.uppercase(),
                fontSize = 12.sp,
                letterSpacing = 0.5.sp,
                color = Theme.secondaryText(),
                modifier = Modifier.padding(start = 16.dp, bottom = 6.dp, top = 8.dp)
            )
        }
        val dark = isSystemInDarkTheme()
        Surface(
            shape = RoundedCornerShape(14.dp),
            color = if (dark) Color.White.copy(alpha = 0.04f) else Color.White.copy(alpha = 0.85f),
            border = BorderStroke(
                1.dp,
                if (dark) Color.White.copy(alpha = 0.08f) else Color.Black.copy(alpha = 0.08f)
            ),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                content()
            }
        }
        if (footer != null) {
            Text(
                footer,
                fontSize = 12.sp,
                color = Theme.secondaryText(),
                modifier = Modifier.padding(start = 16.dp, top = 6.dp)
            )
        }
    }
}
