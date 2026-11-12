package jxpix.proger.mps.ui.components

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.isSpecified
import androidx.compose.ui.unit.coerceIn
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import jxpix.proger.mps.engine.DeviceType
import jxpix.proger.mps.engine.ParamSpec
import jxpix.proger.mps.engine.ParamUnit
import jxpix.proger.mps.engine.Pictures
import jxpix.proger.mps.model.DeviceState
import jxpix.proger.mps.studio.Studio
import jxpix.proger.mps.ui.ParamNumber
import jxpix.proger.mps.ui.Ic
import jxpix.proger.mps.ui.theme.Mps
import kotlin.math.roundToInt

/** Control for a parameter with named options (waveform, filter type, on/off...). */
@Composable
fun ChoiceCell(
    label: String,
    options: Array<String>,
    index: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    color: Color = Mps.colors.accent,
) {
    val c = Mps.colors
    var open by remember { mutableStateOf(false) }
    val binary = options.size == 2
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.height(46.dp), contentAlignment = Alignment.Center) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(38.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(if (binary && index == 1) color.copy(alpha = 0.22f) else c.surfaceHigh)
                    .border(1.dp, if (binary && index == 1) color else c.line, RoundedCornerShape(12.dp))
                    .clickable {
                        if (binary) onSelect(1 - index) else open = true
                    }
                    .semantics { contentDescription = label }
                    .padding(start = 8.dp, end = if (binary) 8.dp else 2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
            ) {
                Text(
                    options.getOrElse(index) { "" }, Modifier.weight(1f), color = c.text, fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
                )
                if (!binary) Icon(Ic.DropDown, null, Modifier.size(18.dp), tint = c.textDim)
            }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }, containerColor = c.surface) {
                options.forEachIndexed { i, name ->
                    DropdownMenuItem(
                        text = {
                            Text(name, color = if (i == index) c.accent else c.text, fontWeight = if (i == index) FontWeight.Bold else FontWeight.Normal)
                        },
                        onClick = {
                            open = false
                            onSelect(i)
                        },
                    )
                }
            }
        }
        Spacer(Modifier.height(3.dp))
        Text(
            label, color = c.textDim, fontSize = 10.sp, lineHeight = 12.sp, maxLines = 2, textAlign = TextAlign.Center,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** One parameter: a number between two arrows, or a selector when the parameter has named options. */
@Composable
fun ParamControl(
    spec: ParamSpec,
    value: Float,
    color: Color,
    onChange: (Float) -> Unit,
    onEnd: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val label = stringResource(spec.nameRes)
    if (spec.isChoice) {
        val options = stringArrayResource(spec.choicesRes)
        ChoiceCell(
            label, options, value.roundToInt().coerceIn(0, options.size - 1),
            onSelect = {
                onChange(it.toFloat())
                onEnd()
            },
            modifier = modifier.fillMaxWidth().padding(horizontal = 3.dp), color = color,
        )
    } else {
        val res = LocalResources.current
        var typing by remember { mutableStateOf(false) }
        // the arrows take what the number can spare: 32 dp where three cells share a phone, up to 44 dp in a wide cell
        val cell = LocalCellWidth.current
        val room = (if (cell.isSpecified) minOf(cell, 180.dp) else 118.dp) - 6.dp
        NumberField(
            label = label,
            number = ParamNumber.text(spec, value),
            unit = ParamNumber.unit(spec, res),
            position = spec.toNorm(value),
            // presses that follow each other closely are one step for "undo": the studio joins them by itself
            onStep = { direction, held -> onChange(ParamNumber.stepped(spec, value, direction, ParamNumber.speed(spec, held))) },
            onOpen = { typing = true },
            // in a wide cell the two arrows stay within reach of each other
            modifier = modifier.widthIn(max = 180.dp).fillMaxWidth().padding(horizontal = 3.dp),
            arrow = ((room - 48.dp) / 2).coerceIn(30.dp, 44.dp),
            color = color,
            bipolar = spec.min < 0f && spec.max > 0f && spec.min == -spec.max,
            canLess = value > spec.min,
            canMore = value < spec.max,
            onReset = {
                if (value != spec.default) {
                    onChange(spec.default)
                    onEnd()
                }
            },
            onPosition = {
                onChange(spec.fromNorm(it))
                onEnd()
            },
        )
        if (typing) {
            NumberDialog(
                title = label,
                initial = ParamNumber.text(spec, value, plain = true),
                range = ParamNumber.range(spec, res),
                unit = ParamNumber.unit(spec, res),
                signed = spec.min < 0f,
                letters = spec.unit == ParamUnit.NOTE,
                accept = { typed ->
                    val v = ParamNumber.parse(spec, typed)
                    if (v != null) {
                        onChange(v)
                        onEnd()
                    }
                    v != null
                },
                onDismiss = { typing = false },
            )
        }
    }
}

/**
 * All parameters of a device, grouped the way the device declares them, each group under the
 * picture of what it does (see [DevicePicture]) where there is one.
 * [skip] hides parameters that the caller presents in another way; [sounding] tells whether
 * sound runs through the device right now, for what moves in the pictures.
 */
@Composable
fun DeviceParams(
    type: DeviceType,
    state: DeviceState,
    color: Color,
    onParam: (String, Float) -> Unit,
    onEnd: () -> Unit,
    modifier: Modifier = Modifier,
    skip: Set<String> = emptySet(),
    framed: Boolean = true,
    before: List<@Composable () -> Unit> = emptyList(),
    studio: Studio? = null,
    sounding: () -> Boolean = { false },
) {
    val specs = remember(type, skip) { type.params.filter { it.id !in skip } }
    // consecutive parameters of the same group form one panel
    val groups = remember(specs) {
        val out = ArrayList<Pair<Int, List<ParamSpec>>>()
        var current = ArrayList<ParamSpec>()
        var group = Int.MIN_VALUE
        for (s in specs) {
            if (s.group != group) {
                if (current.isNotEmpty()) out += group to current
                current = ArrayList()
                group = s.group
            }
            current += s
        }
        if (current.isNotEmpty()) out += group to current
        out
    }
    val panels = ArrayList<@Composable () -> Unit>(before)
    for ((group, list) in groups) {
        panels += {
            val grid: @Composable () -> Unit = {
                val cells: @Composable () -> Unit = {
                    // a number between its arrows needs more room than a knob did
                    CellGrid(list.size, minCell = 116.dp, span = { if (list[it].wide) 2 else 1 }) { i ->
                        val spec = list[i]
                        ParamControl(spec, state.param(spec.id), color, onChange = { onParam(spec.id, it) }, onEnd = onEnd)
                    }
                }
                if (studio == null || !Pictures.has(type.id, group)) {
                    cells()
                } else {
                    // a phone on its side has little height
                    val short = LocalConfiguration.current.screenHeightDp < 480
                    BoxWithConstraints(Modifier.fillMaxWidth()) {
                        if (maxWidth >= 600.dp) {
                            // room to the side: the picture stands next to its settings instead of above them
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                DevicePicture(studio, type, group, state, color, sounding, Modifier.width(300.dp), height = 104.dp)
                                Spacer(Modifier.width(14.dp))
                                Box(Modifier.weight(1f)) { cells() }
                            }
                        } else {
                            Column {
                                DevicePicture(studio, type, group, state, color, sounding, Modifier.fillMaxWidth(), height = if (short) 76.dp else if (framed) 84.dp else 96.dp)
                                Spacer(Modifier.height(10.dp))
                                cells()
                            }
                        }
                    }
                }
            }
            if (framed) Panel(if (group != 0) stringResource(group) else null) { grid() } else grid()
        }
    }
    PanelColumns(panels, modifier, allowTwo = framed)
}

/**
 * Panels one under another - or, when the screen is wide enough (a phone on its side, a tablet),
 * in two columns, so that twice as many settings are in view without scrolling.
 */
@Composable
fun PanelColumns(panels: List<@Composable () -> Unit>, modifier: Modifier = Modifier, allowTwo: Boolean = true) {
    BoxWithConstraints(modifier.fillMaxWidth()) {
        if (allowTwo && panels.size > 1 && maxWidth >= 640.dp) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                for (side in 0..1) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        for (i in panels.indices) if (i % 2 == side) panels[i]()
                    }
                }
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                for (panel in panels) panel()
            }
        }
    }
}
