package jxpix.proger.mps.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import jxpix.proger.mps.R
import jxpix.proger.mps.ui.theme.Mps

/** Bottom sheet in the look of the app. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppSheet(
    onDismiss: () -> Unit,
    title: String? = null,
    scroll: Boolean = true,
    message: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val c = Mps.colors
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = state,
        containerColor = c.bg,
        contentColor = c.text,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
        dragHandle = {
            Box(
                Modifier.padding(top = 10.dp, bottom = 6.dp).size(36.dp, 4.dp).clip(RoundedCornerShape(2.dp)).background(c.lineStrong)
            )
        },
    ) {
        // the message bar of the screen is hidden behind the sheet: what it says is repeated here
        if (message != null) {
            Text(
                message,
                Modifier
                    .padding(start = 16.dp, end = 16.dp, bottom = 8.dp)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(c.text)
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                color = c.bg, fontSize = 13.sp, lineHeight = 18.sp,
            )
        }
        Column(
            Modifier
                .fillMaxWidth()
                .then(if (scroll) Modifier.verticalScroll(rememberScrollState()) else Modifier)
                .navigationBarsPadding()
                .padding(bottom = 12.dp),
        ) {
            if (title != null) {
                Text(
                    title, Modifier.padding(horizontal = 20.dp, vertical = 8.dp), color = c.text, fontSize = 18.sp,
                    fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
            }
            content()
        }
    }
}

/** One row of a menu sheet. */
@Composable
fun SheetItem(
    text: String,
    onClick: () -> Unit,
    icon: ImageVector? = null,
    description: String? = null,
    danger: Boolean = false,
    selected: Boolean = false,
    enabled: Boolean = true,
    dot: Color? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    val c = Mps.colors
    val fg = (if (danger) c.record else if (selected) c.accent else c.text).copy(alpha = if (enabled) 1f else 0.35f)
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .background(if (selected) c.accentSoft else Color.Transparent)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (dot != null) {
            Box(Modifier.size(14.dp).clip(RoundedCornerShape(5.dp)).background(dot))
            Spacer(Modifier.width(16.dp))
        }
        if (icon != null) {
            Icon(icon, null, Modifier.size(22.dp), tint = fg)
            Spacer(Modifier.width(16.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(text, color = fg, fontSize = 15.sp, fontWeight = FontWeight.Medium)
            if (description != null) {
                Text(description, color = c.textDim.copy(alpha = if (enabled) 1f else 0.35f), fontSize = 12.sp, lineHeight = 16.sp)
            }
        }
        if (trailing != null) {
            Spacer(Modifier.width(8.dp))
            trailing()
        }
    }
}

@Composable
fun SheetDivider() {
    Box(Modifier.padding(horizontal = 20.dp, vertical = 6.dp).fillMaxWidth().height(1.dp).background(Mps.colors.line))
}

@Composable
fun SheetLabel(text: String) {
    Text(
        text, Modifier.padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 4.dp), color = Mps.colors.accent,
        fontSize = 12.sp, fontWeight = FontWeight.Bold,
    )
}

/** Asks for a short text (names). */
@Composable
fun TextInputDialog(
    title: String,
    initial: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
    maxLength: Int = 40,
) {
    val c = Mps.colors
    var field by remember { mutableStateOf(TextFieldValue(initial, TextRange(0, initial.length))) }
    val focus = remember { FocusRequester() }
    val submit = {
        val t = field.text.trim()
        if (t.isNotEmpty()) onConfirm(t) else onDismiss()
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = c.bg,
        titleContentColor = c.text,
        title = { Text(title, fontSize = 18.sp, fontWeight = FontWeight.Bold) },
        text = {
            OutlinedTextField(
                value = field,
                onValueChange = { if (it.text.length <= maxLength) field = it },
                singleLine = true,
                label = { Text(stringResource(R.string.name)) },
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { submit() }),
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
            )
        },
        confirmButton = { TextButton(onClick = submit) { Text(stringResource(R.string.ok)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
    LaunchedEffect(Unit) {
        try {
            focus.requestFocus()
        } catch (_: Exception) {
        }
    }
}

@Composable
fun ConfirmDialog(
    title: String,
    text: String,
    confirm: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    danger: Boolean = true,
) {
    val c = Mps.colors
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = c.bg,
        titleContentColor = c.text,
        textContentColor = c.textDim,
        title = { Text(title, fontSize = 18.sp, fontWeight = FontWeight.Bold) },
        text = { Text(text, fontSize = 14.sp, lineHeight = 20.sp) },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(confirm, color = if (danger) c.record else c.accent, fontWeight = FontWeight.Bold) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
