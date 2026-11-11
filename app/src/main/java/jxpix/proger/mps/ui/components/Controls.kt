package jxpix.proger.mps.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import jxpix.proger.mps.ui.Ic
import jxpix.proger.mps.ui.theme.Mps
import jxpix.proger.mps.ui.theme.NumberStyle
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/** Round icon button with a comfortable touch target. */
@Composable
fun IconBtn(
    icon: ImageVector,
    description: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = Mps.colors.text,
    enabled: Boolean = true,
    size: Dp = 44.dp,
    iconSize: Dp = 22.dp,
    background: Color = Color.Transparent,
) {
    Box(
        modifier
            .size(size)
            .clip(CircleShape)
            .background(background)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, null, Modifier.size(iconSize), tint = if (enabled) tint else tint.copy(alpha = 0.3f))
    }
}

/** Pill-shaped button: the workhorse of the toolbars. */
@Composable
fun Chip(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    icon: ImageVector? = null,
    trailing: ImageVector? = null,
    enabled: Boolean = true,
    dot: Color? = null,
    maxWidth: Dp = 220.dp,
) {
    val c = Mps.colors
    val bg = if (selected) c.accent else c.surfaceHigh
    val fg = (if (selected) c.onAccent else c.text).copy(alpha = if (enabled) 1f else 0.4f)
    Row(
        modifier
            // also gives the text a limit to shrink against where the parent has none (a scrolling row)
            .widthIn(max = maxWidth)
            .heightIn(min = 36.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(bg)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics { this.selected = selected }
            .padding(start = if (icon != null || dot != null) 10.dp else 14.dp, end = if (trailing != null) 6.dp else 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (dot != null) {
            Box(Modifier.size(10.dp).clip(CircleShape).background(dot))
            Spacer(Modifier.width(7.dp))
        }
        if (icon != null) {
            Icon(icon, null, Modifier.size(18.dp), tint = fg)
            Spacer(Modifier.width(6.dp))
        }
        Text(
            text, color = fg, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
            overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false).defaultMinSize(minWidth = 8.dp),
        )
        if (trailing != null) Icon(trailing, null, Modifier.size(20.dp), tint = fg)
    }
}

/** Two or more mutually exclusive options in one pill. */
@Composable
fun Segmented(
    options: List<String>,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    height: Dp = 36.dp,
    equalWidth: Boolean = false,
) {
    val c = Mps.colors
    Row(
        modifier.height(height).clip(RoundedCornerShape(height / 2)).background(c.surfaceHigh).padding(3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        options.forEachIndexed { i, label ->
            val on = i == selected
            Box(
                Modifier
                    .then(if (equalWidth) Modifier.weight(1f) else Modifier)
                    .height(height - 6.dp)
                    .clip(RoundedCornerShape(height / 2))
                    .background(if (on) c.accent else Color.Transparent)
                    .clickable(role = Role.Tab) { onSelect(i) }
                    .semantics { this.selected = on }
                    .padding(horizontal = 12.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label, color = if (on) c.onAccent else c.textDim, fontSize = 12.sp, fontWeight = FontWeight.Bold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/**
 * Rotary knob. Drag up/right to increase, down/left to decrease; double-tap to reset.
 *
 * @param value position 0..1
 * @param steps for stepped parameters: the number of intervals (positions - 1), 0 = continuous
 */
@Composable
fun Knob(
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 46.dp,
    color: Color = Mps.colors.accent,
    defaultValue: Float? = null,
    bipolar: Boolean = false,
    steps: Int = 0,
    onChangeEnd: () -> Unit = {},
    enabled: Boolean = true,
    description: String = "",
) {
    val c = Mps.colors
    val current by rememberUpdatedState(value)
    val change by rememberUpdatedState(onValueChange)
    val end by rememberUpdatedState(onChangeEnd)
    val default by rememberUpdatedState(defaultValue)
    val lastTap = remember { mutableLongStateOf(0L) }
    val track = c.line
    val body = c.surfaceHigh
    val pointer = if (enabled) c.text else c.textDim
    val arc = if (enabled) color else c.textDim

    Canvas(
        modifier
            .size(size)
            .semantics {
                contentDescription = description
                progressBarRangeInfo = ProgressBarRangeInfo(value, 0f..1f, steps = if (steps > 1) steps - 1 else 0)
                setProgress { v ->
                    change(v.coerceIn(0f, 1f))
                    end()
                    true
                }
            }
            .pointerInput(steps, enabled) {
                if (!enabled) return@pointerInput
                val travel = 190.dp.toPx()
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    down.consume()
                    var position = current
                    var moved = 0f
                    drag(down.id) { ev ->
                        val d = ev.positionChange()
                        ev.consume()
                        val delta = (d.x - d.y) / travel
                        moved += kotlin.math.abs(d.x) + kotlin.math.abs(d.y)
                        position = (position + delta).coerceIn(0f, 1f)
                        val v = if (steps > 0) (position * steps).roundToInt() / steps.toFloat() else position
                        if (v != current) change(v)
                    }
                    if (moved > viewConfiguration.touchSlop) {
                        end()
                    } else {
                        val now = System.currentTimeMillis()
                        val reset = default
                        if (reset != null && now - lastTap.longValue < 350) {
                            lastTap.longValue = 0
                            if (reset != current) {
                                change(reset)
                                end()
                            }
                        } else lastTap.longValue = now
                    }
                }
            },
    ) {
        val stroke = this.size.minDimension * 0.095f
        val radius = (this.size.minDimension - stroke) / 2f
        val topLeft = Offset(center.x - radius, center.y - radius)
        val arcSize = Size(radius * 2, radius * 2)
        val start = 135f
        val sweep = 270f
        drawArc(track, start, sweep, false, topLeft, arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
        if (bipolar) {
            val s = (value - 0.5f) * sweep
            if (kotlin.math.abs(s) > 0.5f) drawArc(arc, 270f, s, false, topLeft, arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
        } else if (value > 0.002f) {
            drawArc(arc, start, sweep * value, false, topLeft, arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
        }
        val inner = radius - stroke * 1.25f
        drawCircle(body, inner)
        val angle = Math.toRadians((start + sweep * value).toDouble())
        val dx = cos(angle).toFloat()
        val dy = sin(angle).toFloat()
        drawLine(
            pointer,
            Offset(center.x + dx * inner * 0.35f, center.y + dy * inner * 0.35f),
            Offset(center.x + dx * inner * 0.82f, center.y + dy * inner * 0.82f),
            strokeWidth = stroke * 0.85f, cap = StrokeCap.Round,
        )
    }
}

/** Knob with its value and name underneath. */
@Composable
fun LabeledKnob(
    label: String,
    valueText: String,
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    color: Color = Mps.colors.accent,
    defaultValue: Float? = null,
    bipolar: Boolean = false,
    steps: Int = 0,
    onChangeEnd: () -> Unit = {},
    enabled: Boolean = true,
    size: Dp = 46.dp,
) {
    val c = Mps.colors
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Knob(
            value, onValueChange, size = size, color = color, defaultValue = defaultValue, bipolar = bipolar,
            steps = steps, onChangeEnd = onChangeEnd, enabled = enabled, description = label,
        )
        Spacer(Modifier.height(3.dp))
        Text(
            valueText, color = c.text, fontSize = 11.sp, lineHeight = 13.sp, maxLines = 1, style = NumberStyle,
            overflow = TextOverflow.Clip, softWrap = false,
        )
        Text(
            label, color = c.textDim, fontSize = 10.sp, lineHeight = 12.sp, maxLines = 2, textAlign = TextAlign.Center,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** A dismissible one-time tip shown at the top of a screen. */
@Composable
fun HintBanner(text: String, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    val c = Mps.colors
    Row(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(c.accentSoft)
            .padding(start = 12.dp, top = 6.dp, bottom = 6.dp, end = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Ic.Info, null, Modifier.size(18.dp), tint = c.accent)
        Spacer(Modifier.width(8.dp))
        Text(text, Modifier.weight(1f), color = c.text, fontSize = 12.sp, lineHeight = 16.sp)
        IconBtn(Ic.Close, androidx.compose.ui.res.stringResource(jxpix.proger.mps.R.string.got_it), onDismiss, size = 36.dp, iconSize = 18.dp)
    }
}

/** Small rounded square toggle with a letter or short word (M / S / FX). */
@Composable
fun ToggleTag(
    text: String,
    on: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onColor: Color = Mps.colors.accent,
    description: String = text,
    width: Dp = 34.dp,
    height: Dp = 30.dp,
) {
    val c = Mps.colors
    Box(
        modifier
            .size(width, height)
            .clip(RoundedCornerShape(9.dp))
            .background(if (on) onColor else c.surfaceHigh)
            .clickable(role = Role.Switch, onClick = onClick)
            .semantics {
                contentDescription = description
                selected = on
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = if (on) Color.White else c.textDim, fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1)
    }
}

/** Titled block used in editors and settings. */
@Composable
fun Panel(
    title: String?,
    modifier: Modifier = Modifier,
    padding: PaddingValues = PaddingValues(12.dp),
    trailing: (@Composable RowScope.() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val c = Mps.colors
    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(c.surface)
            .border(1.dp, c.line, RoundedCornerShape(16.dp))
            .padding(padding),
    ) {
        if (title != null || trailing != null) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().heightIn(min = 24.dp)) {
                Text(
                    title ?: "", Modifier.weight(1f), color = c.accent, fontSize = 12.sp, fontWeight = FontWeight.Bold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                if (trailing != null) trailing()
            }
            Spacer(Modifier.height(8.dp))
        }
        content()
    }
}

/** Lays children out in rows of equal-width cells, as many per row as fit [minCell]. */
@Composable
fun CellGrid(
    count: Int,
    modifier: Modifier = Modifier,
    minCell: Dp = 76.dp,
    spacing: Dp = 10.dp,
    cell: @Composable (Int) -> Unit,
) {
    androidx.compose.foundation.layout.BoxWithConstraints(modifier.fillMaxWidth()) {
        val columns = (maxWidth / minCell).toInt().coerceIn(1, maxOf(1, count))
        val rows = (count + columns - 1) / columns
        Column(verticalArrangement = Arrangement.spacedBy(spacing)) {
            for (r in 0 until rows) {
                Row(Modifier.fillMaxWidth()) {
                    for (col in 0 until columns) {
                        val i = r * columns + col
                        Box(Modifier.weight(1f), contentAlignment = Alignment.TopCenter) {
                            if (i < count) cell(i)
                        }
                    }
                }
            }
        }
    }
}
