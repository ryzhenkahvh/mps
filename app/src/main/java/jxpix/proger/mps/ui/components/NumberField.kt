package jxpix.proger.mps.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import jxpix.proger.mps.R
import jxpix.proger.mps.ui.Ic
import jxpix.proger.mps.ui.ParamNumber
import jxpix.proger.mps.ui.theme.Mps
import jxpix.proger.mps.ui.theme.NumberStyle

/**
 * A setting as a number between two arrows.
 *
 * An arrow changes the number by one step; held down, it repeats, faster and faster. A tap on
 * the number opens a field to type it in, a long press brings back the default. The thin line
 * under the number shows where in its range the value stands.
 *
 * @param number the value as text, without its [unit]
 * @param position where the value stands in its range, 0..1
 * @param onStep an arrow was pressed: -1 or +1, and for how long it has been held (0 for a tap)
 * @param compact the small form without a name underneath, for the strips of the mixer
 * @param arrow how wide an arrow is (the wider the cell, the wider the arrow can be)
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun NumberField(
    label: String,
    number: String,
    unit: String,
    position: Float,
    onStep: (direction: Int, heldMs: Long) -> Unit,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
    color: Color = Mps.colors.accent,
    bipolar: Boolean = false,
    canLess: Boolean = true,
    canMore: Boolean = true,
    onReset: (() -> Unit)? = null,
    onPosition: ((Float) -> Unit)? = null,
    enabled: Boolean = true,
    compact: Boolean = false,
    arrow: Dp = 32.dp,
) {
    val c = Mps.colors
    val shape = RoundedCornerShape(if (compact) 9.dp else 12.dp)
    val setPosition by rememberUpdatedState(onPosition)
    val tight = unit == "%" || unit == "°" || unit == ":1"
    // a long number ("20000 Hz", "−18.5 dB") is set a little smaller, so that it stays whole between the arrows of a narrow cell
    val weight = number.length + if (unit.isEmpty()) 0f else 0.67f * (unit.length + if (tight) 0f else 0.5f)
    val type = if (compact) 11.sp else if (weight <= 6.2f) 14.sp else if (weight <= 7.4f) 12.sp else 11.sp
    val field: @Composable () -> Unit = {
        Row(
            Modifier
                .fillMaxWidth()
                .height(if (compact) 36.dp else 44.dp)
                .clip(shape)
                .background(c.surfaceHigh)
                .border(1.dp, c.line, shape),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StepArrow(Ic.ChevronLeft, stringResource(R.string.num_less, label), enabled && canLess, if (compact) 21.dp else arrow) { onStep(-1, it) }
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .combinedClickable(
                        enabled = enabled, role = Role.Button, onLongClickLabel = stringResource(R.string.num_default),
                        onLongClick = onReset, onClick = onOpen,
                    )
                    .semantics {
                        contentDescription = label
                        progressBarRangeInfo = ProgressBarRangeInfo(position, 0f..1f)
                        setProgress { v ->
                            val set = setPosition
                            if (set != null) set(v.coerceIn(0f, 1f))
                            set != null
                        }
                    }
                    .drawBehind {
                        // where in its range the value stands: from the left end, or from the middle for a value that goes both ways
                        val h = 2.dp.toPx()
                        val p = position.coerceIn(0f, 1f)
                        val ink = if (enabled) color else c.textDim
                        if (bipolar) {
                            val from = minOf(0.5f, p) * size.width
                            val to = maxOf(0.5f, p) * size.width
                            if (to - from > 0.5f) drawRect(ink, Offset(from, size.height - h), Size(to - from, h))
                        } else if (p > 0.003f) {
                            drawRect(ink, Offset(0f, size.height - h), Size(size.width * p, h))
                        }
                    },
                contentAlignment = Alignment.Center,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        number, color = if (enabled) c.text else c.textDim, fontSize = type, style = NumberStyle,
                        maxLines = 1, softWrap = false, overflow = TextOverflow.Clip,
                    )
                    if (unit.isNotEmpty() && !compact) {
                        Text(
                            if (tight) unit else " $unit", color = c.textDim, fontSize = 10.sp, maxLines = 1,
                            softWrap = false, overflow = TextOverflow.Clip,
                        )
                    }
                }
            }
            StepArrow(Ic.ChevronRight, stringResource(R.string.num_more, label), enabled && canMore, if (compact) 21.dp else arrow) { onStep(1, it) }
        }
    }
    if (compact) {
        Box(modifier) { field() }
    } else {
        Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
            // as tall as a selector, so that a row of fields and selectors stands in line
            Box(Modifier.height(46.dp), contentAlignment = Alignment.Center) { field() }
            Spacer(Modifier.height(3.dp))
            Text(
                label, color = c.textDim, fontSize = 10.sp, lineHeight = 12.sp, maxLines = 2, textAlign = TextAlign.Center,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * One arrow of a [NumberField]. A tap is one step. Held down for a moment, it starts to repeat
 * and tells [onStep] how long it has been held, so that the steps can grow. A finger that lands
 * on the arrow and moves away is scrolling the page, and changes nothing.
 */
@Composable
private fun StepArrow(icon: ImageVector, description: String, enabled: Boolean, width: Dp, onStep: (heldMs: Long) -> Unit) {
    val c = Mps.colors
    val step by rememberUpdatedState(onStep)
    Box(
        Modifier
            .width(width)
            .fillMaxHeight()
            .semantics(mergeDescendants = true) {
                role = Role.Button
                contentDescription = description
                if (!enabled) disabled()
                onClick {
                    if (enabled) step(0L)
                    enabled
                }
            }
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                val slop = viewConfiguration.touchSlop
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    // a tap, the beginning of a hold, or of a scroll?
                    var lifted = false
                    var left = false
                    withTimeoutOrNull(ParamNumber.HOLD_MS) {
                        while (true) {
                            val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id }
                            if (change == null || change.isConsumed || (change.position - down.position).getDistance() > slop) {
                                left = true
                                break
                            }
                            if (!change.pressed) {
                                change.consume()
                                lifted = true
                                break
                            }
                        }
                    }
                    if (left) return@awaitEachGesture
                    if (lifted) {
                        step(0L)
                        return@awaitEachGesture
                    }
                    // held: step after step until the finger is lifted; the page under it stays where it is
                    var held = ParamNumber.HOLD_MS
                    while (true) {
                        step(held)
                        val released = withTimeoutOrNull(ParamNumber.REPEAT_MS) {
                            while (true) {
                                val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                                change.consume()
                                if (!change.pressed) break
                            }
                            true
                        }
                        if (released == true) break
                        held += ParamNumber.REPEAT_MS
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, null, Modifier.size(if (width < 30.dp) 16.dp else 20.dp), tint = if (enabled) c.text else c.textDim.copy(alpha = 0.35f))
    }
}

/**
 * Asks for a number: the field behind a tap on a [NumberField]. It opens with the current value
 * selected, so that typing replaces it at once. [accept] gets the text and says whether it was a
 * number; while it was not, the field stays open and shows so.
 *
 * @param range what can be typed, shown under the field
 * @param unit the unit of the number, shown after it
 * @param signed the value can be negative: a button changes the sign, whatever the keyboard offers
 * @param letters the value can be written in letters (the name of a note)
 */
@Composable
fun NumberDialog(
    title: String,
    initial: String,
    range: String,
    unit: String,
    signed: Boolean,
    letters: Boolean,
    accept: (String) -> Boolean,
    onDismiss: () -> Unit,
) {
    val c = Mps.colors
    var field by remember { mutableStateOf(TextFieldValue(initial, TextRange(0, initial.length))) }
    var wrong by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    val submit = { if (accept(field.text)) onDismiss() else wrong = true }
    val body: @Composable () -> Unit = {
        OutlinedTextField(
            value = field,
            onValueChange = {
                if (it.text.length <= 12) {
                    field = it
                    wrong = false
                }
            },
            singleLine = true,
            isError = wrong,
            // what can be typed stands under the field. (As the label of the field a long text would be measured
            // over and over: the field never comes to rest.)
            supportingText = { Text(if (wrong) stringResource(R.string.num_wrong) else range, fontSize = 12.sp, lineHeight = 16.sp) },
            suffix = if (unit.isEmpty()) null else {
                { Text(unit, color = c.textDim, fontSize = 14.sp) }
            },
            trailingIcon = if (!signed) null else {
                {
                    val sign = stringResource(R.string.num_sign)
                    Box(
                        Modifier
                            .padding(end = 6.dp)
                            .size(40.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(c.surfaceHigh)
                            .clickable(role = Role.Button) {
                                val t = field.text.trim()
                                val flipped = if (t.startsWith("-") || t.startsWith("−")) t.substring(1) else "-$t"
                                field = TextFieldValue(flipped, TextRange(flipped.length))
                                wrong = false
                            }
                            .semantics { contentDescription = sign },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("±", color = c.text, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            },
            keyboardOptions = KeyboardOptions(
                capitalization = if (letters) KeyboardCapitalization.Characters else KeyboardCapitalization.None,
                keyboardType = if (letters) KeyboardType.Ascii else KeyboardType.Decimal, imeAction = ImeAction.Done,
            ),
            keyboardActions = KeyboardActions(onDone = { submit() }),
            modifier = Modifier.fillMaxWidth().focusRequester(focus),
        )
        // the field takes the keyboard as soon as it is there (asked for here, inside the dialog: outside of it
        // the request would come before the field exists)
        LaunchedEffect(Unit) {
            try {
                if (DialogHost.keyboard) focus.requestFocus()
            } catch (_: Exception) {
            }
        }
    }
    if (DialogHost.window && LocalConfiguration.current.screenHeightDp < DialogHost.LOW_SCREEN_DP) {
        // a phone on its side: the low dialog, whole above the keyboard
        LowInputDialog(title, onDismiss, submit, body)
    } else if (DialogHost.window) {
        AlertDialog(
            onDismissRequest = onDismiss,
            containerColor = c.bg,
            titleContentColor = c.text,
            title = { Text(title, fontSize = 18.sp, fontWeight = FontWeight.Bold) },
            text = body,
            confirmButton = { TextButton(onClick = submit) { Text(stringResource(R.string.ok)) } },
            dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
        )
    } else {
        // the same parts without a window around them: see DialogHost
        Column(Modifier.background(c.bg)) {
            Text(title, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            body()
            Row {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
                TextButton(onClick = submit) { Text(stringResource(R.string.ok)) }
            }
        }
    }
}
