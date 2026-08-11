package io.ordnet.wallet.ui.onboarding

import androidx.fragment.app.FragmentActivity

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddCircleOutline
import androidx.compose.material.icons.filled.SaveAlt
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Icon
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.ordnet.wallet.core.Fees
import io.ordnet.wallet.core.ImportMode
import io.ordnet.wallet.core.WalletStore
import io.ordnet.wallet.ui.AlertKind
import io.ordnet.wallet.ui.FormSection
import io.ordnet.wallet.ui.InlineAlert
import io.ordnet.wallet.ui.KVRow
import io.ordnet.wallet.ui.OrdnetOutlineButton
import io.ordnet.wallet.ui.OrdnetProminentButton
import io.ordnet.wallet.ui.OrdplugLogo
import io.ordnet.wallet.ui.Theme
import io.ordnet.wallet.ui.components.ButtonSpinner
import io.ordnet.wallet.ui.components.OrdnetScreen
import io.ordnet.wallet.ui.components.OrdnetTextField
import io.ordnet.wallet.ui.components.SegmentedPicker
import io.ordnet.wallet.ui.components.SpinnerRow
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * First-run: create a new wallet or import an existing one.
 * Port of the extension's setup view (V11) — including BIP44 / other-wallet
 * presets / legacy / WIF import with address preview.
 */
@Composable
fun SetupView(store: WalletStore, activity: FragmentActivity) {
    var screen by remember { mutableStateOf("landing") }

    when (screen) {
        "create" -> CreateWalletView(store, activity, onBack = { screen = "landing" })
        "import" -> ImportWalletView(store, activity, onBack = { screen = "landing" })
        else -> SetupLanding(
            onCreate = { screen = "create" },
            onImport = { screen = "import" }
        )
    }
}

@Composable
private fun SetupLanding(onCreate: () -> Unit, onImport: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(24.dp)
    ) {
        Spacer(Modifier.weight(1f))
        OrdplugLogo(size = 84.dp)
        Text("ORDnet Wallet", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = Theme.ink())
        Text(
            "Browse .web3 domains and manage your ORD/net wallet — send & receive BSV, SNS names and BSVmaps on 1SatOrdinals.",
            fontSize = 15.sp,
            color = Theme.secondaryText(),
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.weight(1f))
        Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
            OrdnetProminentButton(onClick = onCreate, modifier = Modifier.fillMaxWidth().height(48.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.AddCircleOutline, contentDescription = null)
                    Text("Create a new wallet", fontWeight = FontWeight.SemiBold)
                }
            }
            OrdnetOutlineButton(onClick = onImport, modifier = Modifier.fillMaxWidth().height(48.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.SaveAlt, contentDescription = null)
                    Text("Import an existing wallet", fontWeight = FontWeight.Medium)
                }
            }
        }
        Text(
            "Keys live only on this device, protected by hardware-backed encryption and your biometrics.",
            fontSize = 12.sp,
            color = Theme.secondaryText(),
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(bottom = 12.dp)
        )
    }
}

// MARK: - Create

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CreateWalletView(store: WalletStore, activity: FragmentActivity, onBack: () -> Unit) {
    var mnemonic by remember { mutableStateOf("") }
    var address by remember { mutableStateOf("—") }
    var name by remember { mutableStateOf("") }
    var error by remember { mutableStateOf("") }
    var confirmedBackup by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        try {
            val m = store.engine.generateMnemonic()
            mnemonic = m
            val wif = store.engine.wifFromMnemonic(m, ImportMode.BIP44)
            address = store.engine.wifToAddress(wif)
        } catch (e: Exception) {
            error = "Could not generate a wallet: ${e.message}"
        }
    }

    fun create() {
        busy = true
        error = ""
        scope.launch {
            try {
                store.createWallet(activity, mnemonic, name)
                store.refreshBalance()
            } catch (e: Exception) {
                error = e.message ?: "Could not create the wallet."
            }
            busy = false
        }
    }

    OrdnetScreen(title = "New wallet", onBack = onBack) {
        FormSection {
            Text(
                "Write these 12 words down in order and keep them offline. They are the ONLY way to restore your wallet.",
                fontSize = 13.sp,
                color = Theme.statusYellow
            )
        }
        FormSection(header = "Recovery phrase") {
            if (mnemonic.isEmpty()) {
                SpinnerRow()
            } else {
                val words = mnemonic.split(" ")
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    words.forEachIndexed { i, w ->
                        Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                            Text("${i + 1}.", fontSize = 11.sp, color = Theme.secondaryText())
                            Text(w, fontSize = 15.sp, fontFamily = FontFamily.Monospace, color = Theme.ink())
                        }
                    }
                }
            }
            KVRow(k = "First address", v = address, mono = true)
        }
        FormSection(header = "Account") {
            OrdnetTextField(value = name, onValueChange = { name = it }, placeholder = "Account name (optional)")
        }
        FormSection {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(
                    checked = confirmedBackup,
                    onCheckedChange = { confirmedBackup = it },
                    colors = CheckboxDefaults.colors(
                        checkedColor = Theme.ink(),
                        checkmarkColor = Theme.bgPrimary()
                    )
                )
                Text("I wrote down my recovery phrase", fontSize = 14.sp, color = Theme.ink())
            }
            InlineAlert(AlertKind.ERROR, error)
            OrdnetProminentButton(
                onClick = { create() },
                enabled = confirmedBackup && mnemonic.isNotEmpty() && !busy,
                modifier = Modifier.fillMaxWidth()
            ) {
                if (busy) ButtonSpinner() else Text("Create wallet", fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

// MARK: - Import (shared segments, also used by AddAccountSheet)

val importSegments = listOf("BIP44", "Other wallet", "Legacy", "WIF")

@Composable
fun ImportWalletView(store: WalletStore, activity: FragmentActivity, onBack: () -> Unit) {
    var seg by remember { mutableStateOf(0) }
    var mnemonic by remember { mutableStateOf("") }
    var wifInput by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var presets by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var presetId by remember { mutableStateOf("ordplug") }
    var customPath by remember { mutableStateOf(Fees.BIP44_PATH) }
    var pin by remember { mutableStateOf("") }
    var previewRows by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    var error by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        presets = try {
            val arr = store.engine.array("walletPresets")
            (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }
        } catch (e: Exception) { emptyList() }
    }

    val preset = presets.firstOrNull { it.optString("id") == presetId }
    val isCustom = preset?.optBoolean("custom", false) == true
    val needsPin = preset?.optBoolean("pin", false) == true

    val hint = when (seg) {
        0 -> "Standard BSV derivation (m/44'/236'/0'/0/0) — compatible with most BSV wallets."
        1 -> preset?.optString("note")?.ifEmpty { null }
            ?: "Pick the app where this wallet was created; the matching derivation path is applied automatically."
        2 -> "ORD/net V9 derivation — use this to restore a wallet created in an earlier ORD/plug extension version."
        else -> "Paste a single private key in WIF format (starts with K, L or 5)."
    }

    fun preview() {
        error = ""
        previewRows = emptyList()
        scope.launch {
            val m = mnemonic.trim().lowercase()
            val valid = try { store.engine.validateMnemonic(m) } catch (e: Exception) { false }
            if (!valid) {
                error = "Enter a valid recovery phrase first."
                return@launch
            }
            val mainPath = if (isCustom) customPath.trim() else preset?.optString("path")?.ifEmpty { null } ?: Fees.BIP44_PATH
            val pinVal = if (needsPin) pin else ""
            val rows = ArrayList<Pair<String, String>>()
            suspend fun tryPath(label: String, path: String) {
                try {
                    val w = store.engine.string("mnemonicToWifPath",
                        store.engine.args("mnemonic" to m, "path" to path, "passphrase" to pinVal))
                    val a = store.engine.wifToAddress(w)
                    rows.add(Pair(label, a))
                } catch (e: Exception) {
                    rows.add(Pair(label, "invalid path"))
                }
            }
            tryPath("${preset?.optString("name")?.ifEmpty { null } ?: "wallet"} (main)", mainPath)
            val alts = preset?.optJSONArray("alt")
            if (alts != null) {
                for (i in 0 until alts.length()) {
                    val alt = alts.optString(i)
                    tryPath("alt ${i + 1} ($alt)", alt)
                }
            }
            previewRows = rows
        }
    }

    fun doImport() {
        busy = true
        error = ""
        scope.launch {
            try {
                val (mode, path, pinVal) = when (seg) {
                    0 -> Triple(ImportMode.BIP44, null, "")
                    2 -> Triple(ImportMode.LEGACY, null, "")
                    3 -> Triple(ImportMode.WIF, null, "")
                    else -> Triple(
                        ImportMode.PATH,
                        if (isCustom) customPath.trim() else preset?.optString("path")?.ifEmpty { null } ?: Fees.BIP44_PATH,
                        if (needsPin) pin else ""
                    )
                }
                val r = store.resolveImport(mode, mnemonic, wifInput, presetPath = path, pin = pinVal)
                store.importWallet(activity, r, name)
                store.refreshBalance()
            } catch (e: Exception) {
                error = e.message ?: "Import failed."
            }
            busy = false
        }
    }

    OrdnetScreen(title = "Import wallet", onBack = onBack) {
        FormSection {
            SegmentedPicker(options = importSegments, selected = seg, onSelect = { seg = it; previewRows = emptyList() })
            Text(hint, fontSize = 13.sp, color = Theme.secondaryText())
        }

        if (seg == 1) {
            FormSection(header = "Source wallet") {
                WalletPresetPicker(presets = presets, selectedId = presetId, onSelect = { presetId = it })
                if (isCustom) {
                    OrdnetTextField(value = customPath, onValueChange = { customPath = it },
                        placeholder = "Derivation path", mono = true)
                }
                if (needsPin) {
                    OrdnetTextField(value = pin, onValueChange = { pin = it },
                        placeholder = "Wallet PIN (passphrase)",
                        keyboardType = androidx.compose.ui.text.input.KeyboardType.Number)
                }
            }
        }

        if (seg == 3) {
            FormSection(header = "Private key") {
                OrdnetTextField(value = wifInput, onValueChange = { wifInput = it },
                    placeholder = "WIF private key", secure = true)
            }
        } else {
            FormSection(header = "Recovery phrase") {
                OrdnetTextField(value = mnemonic, onValueChange = { mnemonic = it },
                    placeholder = "12–24 words separated by spaces",
                    mono = true, singleLine = false, minLines = 3)
            }
            if (seg == 1) {
                FormSection {
                    OrdnetOutlineButton(onClick = { preview() }) { Text("Preview address") }
                    previewRows.forEach { (k, v) -> KVRow(k = k, v = v, mono = true) }
                    if (previewRows.isNotEmpty()) {
                        Text(
                            "This is the address that will be imported. If your coins are on a different address, try another wallet or a custom path.",
                            fontSize = 12.sp, color = Theme.secondaryText()
                        )
                    }
                }
            }
        }

        FormSection(header = "Account") {
            OrdnetTextField(value = name, onValueChange = { name = it }, placeholder = "Account name (optional)")
        }

        FormSection {
            InlineAlert(AlertKind.ERROR, error)
            OrdnetProminentButton(onClick = { doImport() }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                if (busy) ButtonSpinner() else Text("Import wallet", fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

/** dropdown for the wallet presets (RelayX, Yours/Panda, Twetch, …) */
@Composable
fun WalletPresetPicker(presets: List<JSONObject>, selectedId: String, onSelect: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val selected = presets.firstOrNull { it.optString("id") == selectedId }
    androidx.compose.foundation.layout.Box {
        OrdnetOutlineButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) {
            Text(selected?.optString("name")?.ifEmpty { null } ?: "Pick a wallet…")
        }
        androidx.compose.material3.DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            containerColor = Theme.bgPrimary()
        ) {
            presets.forEach { p ->
                androidx.compose.material3.DropdownMenuItem(
                    text = { Text(p.optString("name"), color = Theme.ink()) },
                    onClick = {
                        onSelect(p.optString("id"))
                        expanded = false
                    }
                )
            }
        }
    }
}
