package io.ordnet.wallet.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.ordnet.wallet.ui.Theme

/** screen with ORDnet background + top bar; back arrow when onBack != null */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OrdnetScreen(
    title: String,
    onBack: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
    /** v3.2 — leading (top-left) icons, e.g. Settings + UTXO tools on the
     *  Wallet screen (user layout); only used when there is no back arrow */
    leadingActions: (@Composable RowScope.() -> Unit)? = null,
    scrollable: Boolean = true,
    content: @Composable ColumnScope.() -> Unit
) {
    if (onBack != null) {
        BackHandler { onBack() }
    }
    // v3.1.1 — tapping anywhere outside a text field dismisses the keyboard
    // (clears focus + hides the IME). Buttons and rows keep working: their
    // own click handlers consume the tap before this parent gesture sees it.
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    Scaffold(
        modifier = Modifier.pointerInput(Unit) {
            detectTapGestures(onTap = {
                keyboard?.hide()
                focusManager.clearFocus()
            })
        },
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Text(title, fontWeight = FontWeight.SemiBold, fontSize = 18.sp, color = Theme.ink())
                },
                navigationIcon = {
                    if (onBack != null) {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Theme.ink())
                        }
                    } else if (leadingActions != null) {
                        Row { leadingActions() }
                    }
                },
                actions = actions,
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .then(if (scrollable) Modifier.verticalScroll(rememberScrollState()) else Modifier)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            content()
            androidx.compose.foundation.layout.Spacer(Modifier.size(24.dp))
        }
    }
}

/** ORDnet text field */
@Composable
fun OrdnetTextField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier.fillMaxWidth(),
    mono: Boolean = false,
    singleLine: Boolean = true,
    minLines: Int = 1,
    secure: Boolean = false,
    keyboardType: KeyboardType = KeyboardType.Text,
    enabled: Boolean = true,
    // v2.1 — optionele toetsenbord-actie (bv. Go in de browser-balk)
    imeAction: ImeAction = ImeAction.Default,
    onImeAction: (() -> Unit)? = null
) {
    // v3.1.1 — the keyboard's confirm key (the green ✓ on numeric keyboards)
    // did nothing: the KeyboardActions handlers replaced the platform default
    // (dismiss the keyboard) with a no-op whenever no onImeAction was given,
    // so the keyboard could not be closed at all on fields without their own
    // action. Single-line fields without an explicit action now advertise
    // Done, and every action without a handler dismisses the keyboard
    // (hide IME + clear focus). Multiline fields keep Default so Enter still
    // inserts a newline; explicit actions (e.g. Go in the browser bar) are
    // untouched.
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val dismiss: () -> Unit = {
        keyboard?.hide()
        focusManager.clearFocus()
    }
    val effectiveImeAction = when {
        imeAction != ImeAction.Default -> imeAction
        singleLine -> ImeAction.Done
        else -> ImeAction.Default
    }
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        placeholder = { Text(placeholder, color = Theme.secondaryText(), fontSize = 14.sp) },
        modifier = modifier,
        singleLine = singleLine,
        minLines = minLines,
        enabled = enabled,
        textStyle = TextStyle(
            fontSize = 15.sp,
            fontFamily = if (mono) FontFamily.Monospace else null,
            color = Theme.ink()
        ),
        visualTransformation = if (secure) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = KeyboardOptions(
            keyboardType = if (secure) KeyboardType.Password else keyboardType,
            autoCorrectEnabled = false,
            imeAction = effectiveImeAction
        ),
        keyboardActions = KeyboardActions(
            onGo = { if (onImeAction != null) onImeAction() else dismiss() },
            onDone = { if (onImeAction != null) onImeAction() else dismiss() },
            onSearch = { if (onImeAction != null) onImeAction() else dismiss() },
            onNext = { if (onImeAction != null) onImeAction() else dismiss() },
            onSend = { if (onImeAction != null) onImeAction() else dismiss() }
        ),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = Theme.ink(),
            unfocusedBorderColor = Theme.ink().copy(alpha = 0.25f),
            cursorColor = Theme.ink(),
            focusedTextColor = Theme.ink(),
            unfocusedTextColor = Theme.ink()
        ),
        shape = androidx.compose.foundation.shape.RoundedCornerShape(10.dp)
    )
}

/** segmented control — port of the iOS segmented Picker */
@Composable
fun SegmentedPicker(
    options: List<String>,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier.fillMaxWidth()
) {
    Row(
        modifier = modifier
            .clip(CircleShape)
            .background(Theme.ink().copy(alpha = 0.08f))
            .padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        options.forEachIndexed { i, label ->
            val isSel = i == selected
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(CircleShape)
                    .background(if (isSel) Theme.bgPrimary() else androidx.compose.ui.graphics.Color.Transparent)
                    .clickable { onSelect(i) }
                    .padding(vertical = 7.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    label,
                    fontSize = 12.sp,
                    maxLines = 1,
                    fontWeight = if (isSel) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (isSel) Theme.ink() else Theme.secondaryText()
                )
            }
        }
    }
}

@Composable
fun SpinnerRow() {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.Center
    ) {
        CircularProgressIndicator(color = Theme.ink(), modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
    }
}

@Composable
fun ButtonSpinner() {
    CircularProgressIndicator(
        color = Theme.bgPrimary(),
        modifier = Modifier.size(18.dp),
        strokeWidth = 2.dp
    )
}
