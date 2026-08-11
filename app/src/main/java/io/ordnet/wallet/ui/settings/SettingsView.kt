package io.ordnet.wallet.ui.settings

import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Book
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.VerifiedUser
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.ordnet.wallet.core.Account
import io.ordnet.wallet.core.Fees
import io.ordnet.wallet.core.ImportMode
import io.ordnet.wallet.core.Vault
import io.ordnet.wallet.core.WalletStore
import io.ordnet.wallet.ui.AlertKind
import io.ordnet.wallet.ui.FormSection
import io.ordnet.wallet.ui.InlineAlert
import io.ordnet.wallet.ui.KVRow
import io.ordnet.wallet.ui.OrdnetOutlineButton
import io.ordnet.wallet.ui.OrdnetProminentButton
import io.ordnet.wallet.ui.Theme
import io.ordnet.wallet.ui.components.OrdnetScreen
import io.ordnet.wallet.ui.components.OrdnetTextField
import io.ordnet.wallet.ui.components.SegmentedPicker
import io.ordnet.wallet.ui.onboarding.WalletPresetPicker
import io.ordnet.wallet.ui.onboarding.importSegments
import kotlinx.coroutines.launch
import org.json.JSONObject

sealed class SettingsScreen {
    data object Main : SettingsScreen()
    data object Accounts : SettingsScreen()
    data class Backup(val accountIndex: Int) : SettingsScreen()
    data object AddressBook : SettingsScreen()
    data object ConnectedSites : SettingsScreen()
    data object Brc100Grants : SettingsScreen()
}

@Composable
fun SettingsView(store: WalletStore, activity: AppCompatActivity, onClose: (() -> Unit)? = null) {
    var screen by remember { mutableStateOf<SettingsScreen>(SettingsScreen.Main) }
    val back = { screen = SettingsScreen.Main }

    when (val s = screen) {
        is SettingsScreen.Accounts -> AccountsView(store, activity, onBack = back,
            onBackup = { screen = SettingsScreen.Backup(it) })
        is SettingsScreen.Backup -> BackupView(store, activity, accountIndex = s.accountIndex, onBack = back)
        is SettingsScreen.AddressBook -> AddressBookScreen(store, onBack = back)
        is SettingsScreen.ConnectedSites -> ConnectedSitesView(store, onBack = back)
        is SettingsScreen.Brc100Grants -> Brc100GrantsView(store, onBack = back)
        is SettingsScreen.Main -> SettingsMain(store, onNavigate = { screen = it }, onClose = onClose)
    }
}

@Composable
private fun SettingsMain(store: WalletStore, onNavigate: (SettingsScreen) -> Unit, onClose: (() -> Unit)? = null) {
    var autolock by remember { mutableStateOf(store.autolockMinutes) }
    var confirmRemove by remember { mutableStateOf(false) }

    OrdnetScreen(title = "Settings", onBack = onClose) {
        FormSection(header = "Accounts") {
            SettingsRow(Icons.Filled.Group, "Manage accounts") { onNavigate(SettingsScreen.Accounts) }
            SettingsRow(Icons.Filled.Key, "Backup / reveal secret") { onNavigate(SettingsScreen.Backup(store.active)) }
        }

        FormSection(header = "Security") {
            Text("Auto-lock", fontSize = 13.sp, color = Theme.secondaryText())
            val options = listOf(5, 15, 60, 0)
            val labels = listOf("5 min", "15 min", "1 hour", "Never")
            SegmentedPicker(
                options = labels,
                selected = options.indexOf(autolock).let { if (it < 0) 1 else it },
                onSelect = { i ->
                    autolock = options[i]
                    store.autolockMinutes = options[i]
                }
            )
            SettingsRow(Icons.Filled.Lock, "Lock now") { store.lock() }
            SettingsRow(Icons.Filled.Link, "Connected sites") { onNavigate(SettingsScreen.ConnectedSites) }
            // v3.2 — verleende BRC-100-permissies inzien en intrekken
            SettingsRow(Icons.Filled.VerifiedUser, "BRC-100 permissions") { onNavigate(SettingsScreen.Brc100Grants) }
        }

        FormSection(header = "Address book") {
            SettingsRow(Icons.Filled.Book, "Trusted recipients") { onNavigate(SettingsScreen.AddressBook) }
        }

        FormSection(
            footer = "ORDnet Wallet for Android · engine parity with Chrome extension V3.4 · keys never leave the hardware-encrypted Keystore vault."
        ) {
            if (confirmRemove) {
                Text(
                    "This deletes your keys from this device. Coins are only recoverable with your recovery phrase or WIF backup. Are you sure?",
                    fontSize = 13.sp, color = Theme.statusRed
                )
                Row(Modifier.fillMaxWidth()) {
                    OrdnetOutlineButton(onClick = { confirmRemove = false }) { Text("Keep wallet") }
                    Spacer(Modifier.weight(1f))
                    OrdnetProminentButton(onClick = { store.removeWallet() }, destructive = true) {
                        Text("Remove wallet")
                    }
                }
            } else {
                SettingsRow(Icons.Filled.Delete, "Remove wallet from this device", tint = Theme.statusRed) {
                    confirmRemove = true
                }
            }
        }
    }
}

@Composable
private fun SettingsRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    tint: androidx.compose.ui.graphics.Color? = null,
    onClick: () -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.fillMaxWidth().clickable { onClick() }.padding(vertical = 8.dp)
    ) {
        Icon(icon, contentDescription = null, tint = tint ?: Theme.ink(), modifier = Modifier.size(20.dp))
        Text(label, fontSize = 15.sp, color = tint ?: Theme.ink())
    }
}

// MARK: - Accounts

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountsView(
    store: WalletStore,
    activity: AppCompatActivity,
    onBack: () -> Unit,
    onBackup: (Int) -> Unit
) {
    var confirmRemove by remember { mutableStateOf<Int?>(null) }
    var renaming by remember { mutableStateOf<Int?>(null) }
    var renameText by remember { mutableStateOf("") }
    var showAdd by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }

    OrdnetScreen(title = "Accounts", onBack = onBack) {
        FormSection(header = "${store.accounts.size} account${if (store.accounts.size == 1) "" else "s"}") {
            store.accounts.forEachIndexed { i, a ->
                AccountRow(
                    store = store, i = i, a = a,
                    renaming = renaming == i,
                    renameText = renameText,
                    onRenameText = { renameText = it },
                    onRenameCommit = {
                        store.renameAccount(i, renameText)
                        renaming = null
                    },
                    onStartRename = { renameText = a.name; renaming = i },
                    confirmRemove = confirmRemove == i,
                    onRemoveTap = {
                        if (confirmRemove == i) {
                            store.removeAccount(i)
                            confirmRemove = null
                        } else confirmRemove = i
                    },
                    onBackup = { onBackup(i) }
                )
            }
        }
        FormSection {
            InlineAlert(AlertKind.ERROR, error)
            OrdnetOutlineButton(onClick = { showAdd = true }, modifier = Modifier.fillMaxWidth()) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                    Text("Add account")
                }
            }
        }
    }

    if (showAdd) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = { showAdd = false },
            sheetState = sheetState,
            containerColor = Theme.bgPrimary()
        ) {
            AddAccountSheet(store, onDone = { showAdd = false })
        }
    }
}

@Composable
private fun AccountRow(
    store: WalletStore,
    i: Int,
    a: Account,
    renaming: Boolean,
    renameText: String,
    onRenameText: (String) -> Unit,
    onRenameCommit: () -> Unit,
    onStartRename: () -> Unit,
    confirmRemove: Boolean,
    onRemoveTap: () -> Unit,
    onBackup: () -> Unit
) {
    var menuOpen by remember { mutableStateOf(false) }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clickable { if (i != store.active) store.selectAccount(i) }
            .padding(vertical = 4.dp)
    ) {
        Box(
            Modifier.size(34.dp).clip(CircleShape).background(Theme.secondaryText().copy(alpha = 0.15f)),
            contentAlignment = Alignment.Center
        ) {
            Text(
                (a.name.firstOrNull() ?: 'A').uppercase(),
                fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Theme.ink()
            )
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            if (renaming) {
                OrdnetTextField(
                    value = renameText, onValueChange = onRenameText,
                    placeholder = "Name", modifier = Modifier.fillMaxWidth()
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Save", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Theme.ink(),
                        modifier = Modifier.clickable { onRenameCommit() })
                }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(a.name, fontSize = 15.sp, fontWeight = FontWeight.Medium, color = Theme.ink())
                    if (i == store.active) {
                        Text(
                            "active",
                            fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = Theme.statusGreen,
                            modifier = Modifier
                                .clip(CircleShape)
                                .background(Theme.statusGreen.copy(alpha = 0.15f))
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }
            }
            Text(a.address, fontSize = 11.sp, fontFamily = FontFamily.Monospace,
                color = Theme.secondaryText(), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Box {
            IconButton(onClick = { menuOpen = true }) {
                Icon(Icons.Filled.MoreHoriz, contentDescription = "Account actions", tint = Theme.secondaryText())
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false },
                containerColor = Theme.bgPrimary()) {
                if (i != store.active) {
                    DropdownMenuItem(text = { Text("Use this account", color = Theme.ink()) },
                        onClick = { menuOpen = false; store.selectAccount(i) })
                }
                DropdownMenuItem(text = { Text("Rename", color = Theme.ink()) },
                    onClick = { menuOpen = false; onStartRename() })
                DropdownMenuItem(text = { Text("Export key / backup", color = Theme.ink()) },
                    onClick = { menuOpen = false; onBackup() })
                if (store.accounts.size > 1) {
                    DropdownMenuItem(
                        text = {
                            Text(
                                if (confirmRemove) "Confirm remove" else "Remove… (tap again to confirm)",
                                color = Theme.statusRed
                            )
                        },
                        onClick = {
                            onRemoveTap()
                            if (confirmRemove) menuOpen = false
                        }
                    )
                }
            }
        }
    }
}

@Composable
fun AddAccountSheet(store: WalletStore, onDone: () -> Unit) {
    var mode by remember { mutableStateOf(0) }   // 0 = Generate new, 1 = Import
    var seg by remember { mutableStateOf(0) }
    var mnemonic by remember { mutableStateOf("") }
    var wifInput by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var presets by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var presetId by remember { mutableStateOf("ordplug") }
    var customPath by remember { mutableStateOf(Fees.BIP44_PATH) }
    var pin by remember { mutableStateOf("") }
    var error by remember { mutableStateOf("") }
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

    fun add() {
        error = ""
        scope.launch {
            try {
                if (mode == 0) {
                    store.addAccount(name, result = null)
                } else {
                    val (m, path, pinVal) = when (seg) {
                        0 -> Triple(ImportMode.BIP44, null, "")
                        2 -> Triple(ImportMode.LEGACY, null, "")
                        3 -> Triple(ImportMode.WIF, null, "")
                        else -> Triple(
                            ImportMode.PATH,
                            if (isCustom) customPath.trim() else preset?.optString("path")?.ifEmpty { null },
                            if (needsPin) pin else ""
                        )
                    }
                    val r = store.resolveImport(m, mnemonic, wifInput, presetPath = path, pin = pinVal)
                    store.addAccount(name, r)
                }
                onDone()
            } catch (e: Exception) {
                error = e.message ?: "Could not add the account."
            }
        }
    }

    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .padding(bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Add account", fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = Theme.ink())
        SegmentedPicker(options = listOf("Generate new", "Import"), selected = mode, onSelect = { mode = it })

        if (mode == 1) {
            SegmentedPicker(options = importSegments, selected = seg, onSelect = { seg = it })
            if (seg == 1) {
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
            if (seg == 3) {
                OrdnetTextField(value = wifInput, onValueChange = { wifInput = it },
                    placeholder = "WIF private key", secure = true)
            } else {
                OrdnetTextField(value = mnemonic, onValueChange = { mnemonic = it },
                    placeholder = "Recovery phrase", mono = true, singleLine = false, minLines = 3)
            }
        }

        OrdnetTextField(value = name, onValueChange = { name = it }, placeholder = "Account name (optional)")
        InlineAlert(AlertKind.ERROR, error)
        OrdnetProminentButton(onClick = { add() }, modifier = Modifier.fillMaxWidth()) {
            Text("Add account", fontWeight = FontWeight.SemiBold)
        }
    }
}

// MARK: - Backup (biometric-gated reveal, port of the Face ID gated reveal)

@Composable
fun BackupView(store: WalletStore, activity: AppCompatActivity, accountIndex: Int, onBack: () -> Unit) {
    var revealed by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    var copied by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current

    val account: Account? = store.accounts.getOrNull(accountIndex)

    fun reveal() {
        error = ""
        scope.launch {
            try {
                Vault.authenticate(activity, "Reveal the backup secret for this account")
                val a = account
                if (a != null && store.sessionPhrases[a.address] == null) {
                    note = if (a.origin == "wif" || a.origin == "random")
                        "This account has no recovery phrase (it was added from a private key). Back up the WIF below."
                    else
                        "The recovery phrase is not held in memory for this account. Back up the WIF below, or re-import the account from its phrase to reveal it."
                }
                revealed = true
            } catch (e: Exception) {
                error = e.message ?: "Authentication failed."
            }
        }
    }

    OrdnetScreen(title = "Backup", onBack = onBack) {
        val a = account
        if (a != null) {
            FormSection {
                KVRow(k = "Account", v = "${a.name} · ${a.originLabel}")
            }
            if (!revealed) {
                FormSection {
                    Text(
                        "Your secret is protected by your biometrics. Never share it — anyone with the phrase or WIF controls the coins.",
                        fontSize = 13.sp, color = Theme.secondaryText()
                    )
                    InlineAlert(AlertKind.ERROR, error)
                    OrdnetProminentButton(onClick = { reveal() }, modifier = Modifier.fillMaxWidth()) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.Fingerprint, contentDescription = null, modifier = Modifier.size(18.dp))
                            Text("Reveal secret", fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            } else {
                val phrase = store.sessionPhrases[a.address]
                if (phrase != null) {
                    FormSection(header = "Recovery phrase (${if (a.origin == "legacy") "legacy" else "BIP44"})") {
                        androidx.compose.foundation.text.selection.SelectionContainer {
                            Text(phrase, fontSize = 15.sp, fontFamily = FontFamily.Monospace, color = Theme.ink())
                        }
                        OrdnetOutlineButton(onClick = {
                            clipboard.setText(AnnotatedString(phrase))
                            copied = "Recovery phrase copied to clipboard."
                        }) {
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Filled.ContentCopy, contentDescription = null, modifier = Modifier.size(14.dp))
                                Text("Copy phrase")
                            }
                        }
                    }
                } else if (note.isNotEmpty()) {
                    InlineAlert(AlertKind.SUCCESS, note)
                }
                FormSection(header = "Private key (WIF)") {
                    androidx.compose.foundation.text.selection.SelectionContainer {
                        Text(a.wif, fontSize = 15.sp, fontFamily = FontFamily.Monospace, color = Theme.ink())
                    }
                    OrdnetOutlineButton(onClick = {
                        clipboard.setText(AnnotatedString(a.wif))
                        copied = "WIF copied to clipboard."
                    }) {
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.ContentCopy, contentDescription = null, modifier = Modifier.size(14.dp))
                            Text("Copy WIF")
                        }
                    }
                }
                FormSection {
                    InlineAlert(AlertKind.SUCCESS, copied)
                    Text(
                        "Anything copied to the clipboard can be read by other apps — paste it into your password manager and clear the clipboard.",
                        fontSize = 12.sp, color = Theme.statusYellow
                    )
                }
            }
        }
    }
}

// MARK: - Address book

@Composable
fun AddressBookScreen(store: WalletStore, prefillAddress: String = "", onBack: () -> Unit) {
    var name by remember { mutableStateOf("") }
    var address by remember { mutableStateOf(prefillAddress) }
    var error by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()

    OrdnetScreen(title = "Address book", onBack = onBack) {
        FormSection(header = "Add trusted recipient") {
            OrdnetTextField(value = name, onValueChange = { name = it },
                placeholder = "Label (e.g. \"Cold storage\")")
            OrdnetTextField(value = address, onValueChange = { address = it },
                placeholder = "BSV address", mono = true)
            InlineAlert(AlertKind.ERROR, error)
            OrdnetOutlineButton(onClick = {
                error = ""
                scope.launch {
                    try {
                        store.bookAdd(name = name, addr = address.trim())
                        name = ""; address = ""
                    } catch (e: Exception) {
                        error = e.message ?: "Could not add."
                    }
                }
            }, modifier = Modifier.fillMaxWidth()) {
                Text("Add")
            }
        }
        FormSection {
            if (store.addressBook.isEmpty()) {
                Text(
                    "No saved addresses yet. Add trusted recipients here so you can pick them when sending.",
                    fontSize = 13.sp, color = Theme.secondaryText()
                )
            } else {
                store.addressBook.sortedBy { it.name }.forEach { e ->
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(e.name, fontSize = 15.sp, fontWeight = FontWeight.Medium, color = Theme.ink())
                            Text(e.address, fontSize = 11.sp, fontFamily = FontFamily.Monospace,
                                color = Theme.secondaryText())
                        }
                        IconButton(onClick = { store.bookRemove(e.address) }) {
                            Icon(Icons.Filled.Delete, contentDescription = "Remove",
                                tint = Theme.statusRed, modifier = Modifier.size(18.dp))
                        }
                    }
                }
            }
        }
    }
}

// MARK: - Connected sites

@Composable
fun ConnectedSitesView(store: WalletStore, onBack: () -> Unit) {
    OrdnetScreen(title = "Connected sites", onBack = onBack) {
        FormSection {
            val origins = store.connectedSites.filterValues { it }.keys.sorted()
            if (origins.isEmpty()) {
                Text(
                    "No sites are connected in this session. Sites connect when you approve a wallet request in the .web3 browser.",
                    fontSize = 13.sp, color = Theme.secondaryText()
                )
            } else {
                origins.forEach { o ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Filled.Link, contentDescription = null,
                            tint = Theme.secondaryText(), modifier = Modifier.size(18.dp))
                        Text(o.replace("https://", ""), fontSize = 15.sp, color = Theme.ink(),
                            modifier = Modifier.weight(1f))
                        IconButton(onClick = { store.disconnectSite(o) }) {
                            Icon(Icons.Filled.Delete, contentDescription = "Disconnect",
                                tint = Theme.statusRed, modifier = Modifier.size(18.dp))
                        }
                    }
                }
            }
        }
    }
}
