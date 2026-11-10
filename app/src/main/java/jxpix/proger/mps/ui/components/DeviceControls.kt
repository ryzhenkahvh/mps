package jxpix.proger.mps.ui.components

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
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import jxpix.proger.mps.engine.DeviceType
import jxpix.proger.mps.engine.ParamSpec
import jxpix.proger.mps.model.DeviceState
import jxpix.proger.mps.ui.Fmt
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

/** One parameter: a knob, or a selector when the parameter has named options. */
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
        val steps = if (spec.integer) (spec.max - spec.min).roundToInt() else 0
        LabeledKnob(
            label = label,
            valueText = Fmt.param(spec, value, res),
            value = spec.toNorm(value),
            onValueChange = { onChange(spec.fromNorm(it)) },
            modifier = modifier,
            color = color,
            defaultValue = spec.toNorm(spec.default),
            bipolar = spec.min < 0f && spec.max > 0f && spec.min == -spec.max,
            steps = steps,
            onChangeEnd = onEnd,
        )
    }
}

/**
 * All parameters of a device, grouped the way the device declares them.
 * [skip] hides parameters that the caller presents in another way.
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
    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        for ((group, list) in groups) {
            val grid: @Composable () -> Unit = {
                CellGrid(list.size) { i ->
                    val spec = list[i]
                    ParamControl(spec, state.param(spec.id), color, onChange = { onParam(spec.id, it) }, onEnd = onEnd)
                }
            }
            if (framed) Panel(if (group != 0) stringResource(group) else null) { grid() } else grid()
        }
    }
}
