package jxpix.proger.mps.ui.studio

import android.content.res.Resources
import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import jxpix.proger.mps.R
import jxpix.proger.mps.engine.Devices
import jxpix.proger.mps.engine.PPQ
import jxpix.proger.mps.engine.ParamSpec
import jxpix.proger.mps.engine.TICKS_PER_STEP
import jxpix.proger.mps.model.AutoPoint
import jxpix.proger.mps.model.Channel
import jxpix.proger.mps.model.DeviceState
import jxpix.proger.mps.model.PARAM_PAN
import jxpix.proger.mps.model.PARAM_VOLUME
import jxpix.proger.mps.model.Pattern
import jxpix.proger.mps.model.STRIP_DEVICE
import jxpix.proger.mps.model.setAutomation
import jxpix.proger.mps.studio.Studio
import jxpix.proger.mps.ui.Fmt
import jxpix.proger.mps.ui.Ic
import jxpix.proger.mps.ui.components.AppSheet
import jxpix.proger.mps.ui.components.Chip
import jxpix.proger.mps.ui.components.IconBtn
import jxpix.proger.mps.ui.components.SheetItem
import jxpix.proger.mps.ui.components.SheetLabel
import jxpix.proger.mps.ui.theme.Mps
import jxpix.proger.mps.ui.theme.NumberStyle
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

private val LANE_HEIGHT = 104.dp

/** One knob an automation curve can move. */
internal class LaneTarget(
    val device: Int,
    val param: String,
    /** Heading in the list of knobs: "Mixer", "Synth - Filter", "Reverb". */
    val section: String,
    val name: String,
    /** Null for the fader and the balance knob of the mixer strip. */
    val spec: ParamSpec?,
    /** Where the user left the knob, as a position 0..1. */
    val base: Float,
) {
    /** Text of the chip that shows what the lane edits. */
    /** The knob comes first: it is what stays readable when the chip gets short. */
    val title: String get() = if (device == STRIP_DEVICE) name else "$name · $section"

    /** A knob position as the value the user knows from the knob itself. */
    fun format(position: Float, res: Resources): String = when {
        spec != null -> Fmt.param(spec, spec.fromNorm(position), res)
        param == PARAM_PAN -> Fmt.pan(position * 2f - 1f, res)
        else -> Fmt.fader(position, res)
    }
}

/** Every knob of a channel that can be automated, in the order they are offered. */
internal fun laneTargets(channel: Channel, res: Resources): List<LaneTarget> {
    val out = ArrayList<LaneTarget>()
    val mixer = res.getString(R.string.tab_mixer)
    out += LaneTarget(STRIP_DEVICE, PARAM_VOLUME, mixer, res.getString(R.string.volume), null, channel.volume.coerceIn(0f, 1f))
    out += LaneTarget(STRIP_DEVICE, PARAM_PAN, mixer, res.getString(R.string.pan), null, ((channel.pan + 1f) / 2f).coerceIn(0f, 1f))

    fun add(state: DeviceState) {
        val type = Devices[state.type] ?: return
        val device = res.getString(type.nameRes)
        for (spec in type.params) {
            // switches (wave shape, filter type...) are not curves
            if (spec.isChoice) continue
            val section = if (spec.group != 0) device + " · " + res.getString(spec.group) else device
            out += LaneTarget(state.uid, spec.id, section, res.getString(spec.nameRes), spec, spec.toNorm(state.param(spec.id)))
        }
    }
    add(channel.instrument)
    for (fx in channel.effects) add(fx)
    return out
}

private class LaneModel(
    val patternTicks: Int,
    val snap: Int,
    val patternId: Int,
    val channelId: Int,
    val device: Int,
    val param: String,
)

/**
 * The automation lane under the piano roll: one curve that moves one knob of the channel
 * while the pattern plays. It scrolls and zooms together with the notes above it.
 */
@Composable
internal fun AutomationLane(studio: Studio, pattern: Pattern, channel: Channel, modifier: Modifier = Modifier) {
    val c = Mps.colors
    val ui = studio.ui
    val res = LocalResources.current
    val targets = remember(channel, res) { laneTargets(channel, res) }
    val target = targets.firstOrNull { it.device == ui.laneDevice && it.param == ui.laneParam } ?: targets[0]
    val points = pattern.lane(channel.id, target.device, target.param)?.points ?: emptyList()
    var sheet by remember { mutableStateOf(false) }
    // knob position under the finger while a curve is being drawn
    var touching by remember { mutableStateOf<Float?>(null) }

    Column(modifier.fillMaxWidth().background(c.surface)) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(c.lineStrong))
        Row(Modifier.fillMaxWidth().height(46.dp).padding(start = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Chip(target.title, { sheet = true }, Modifier.weight(1f, fill = false), icon = Ic.Curve, trailing = Ic.DropDown, maxWidth = 230.dp)
            Spacer(Modifier.width(10.dp))
            val now = touching
            Text(
                when {
                    now != null -> target.format(now, res)
                    points.isEmpty() -> stringResource(R.string.auto_draw)
                    else -> stringResource(R.string.auto_knob, target.format(target.base, res))
                },
                Modifier.weight(1f), color = if (now != null) c.text else c.textDim, fontSize = 12.sp, lineHeight = 15.sp,
                style = NumberStyle, maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
            IconBtn(
                Ic.Delete, stringResource(R.string.auto_clear),
                { studio.edit { it.setAutomation(pattern.id, channel.id, target.device, target.param, emptyList()) } },
                enabled = points.isNotEmpty(), size = 42.dp,
            )
            IconBtn(Ic.Close, stringResource(R.string.auto_hide), { ui.rollAutomation = false }, size = 42.dp, tint = c.textDim)
        }
        LaneCanvas(studio, pattern, channel, target, points, { touching = it })
    }

    if (sheet) {
        val used = remember(pattern.automation, channel.id) {
            pattern.automation.filter { it.channelId == channel.id }.map { it.device to it.param }.toSet()
        }
        AppSheet({ sheet = false }, title = stringResource(R.string.auto_pick)) {
            var section = ""
            for (t in targets) {
                if (t.section != section) {
                    section = t.section
                    SheetLabel(section)
                }
                SheetItem(
                    t.name,
                    onClick = {
                        ui.laneDevice = t.device
                        ui.laneParam = t.param
                        sheet = false
                    },
                    selected = t === target,
                    // knobs that already have a curve in this pattern
                    trailing = if ((t.device to t.param) in used) {
                        { Box(Modifier.size(8.dp).clip(CircleShape).background(c.accent)) }
                    } else null,
                )
            }
        }
    }
}

@Composable
private fun LaneCanvas(
    studio: Studio, pattern: Pattern, channel: Channel, target: LaneTarget, points: List<AutoPoint>,
    onTouch: (Float?) -> Unit,
) {
    val c = Mps.colors
    val ui = studio.ui
    val view = ui.roll
    val p = studio.project
    val res = LocalResources.current
    val density = LocalDensity.current
    val dp = density.density
    val keyW = with(density) { KEY_WIDTH.toPx() }
    val pad = 8f * dp
    val patternTicks = p.patternLength(pattern)
    val ticksPerBar = p.ticksPerBar
    val color = c.channel(channel.color)
    val following = studio.playing && !studio.songMode
    val model by rememberUpdatedState(LaneModel(patternTicks, ui.rollSnap, pattern.id, channel.id, target.device, target.param))
    val touch by rememberUpdatedState(onTouch)
    val labelPaint = remember { Paint(Paint.ANTI_ALIAS_FLAG) }
    val description = stringResource(R.string.auto_lane, target.title)
    val top = target.format(1f, res)
    val bottom = target.format(0f, res)

    Canvas(
        Modifier
            .fillMaxWidth()
            .height(LANE_HEIGHT)
            .semantics { contentDescription = description }
            .pointerInput(pattern.id, channel.id, target.device, target.param) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    if (down.position.x < keyW) return@awaitEachGesture
                    down.consume()
                    val height = size.height.toFloat()
                    var lastTick = -1
                    var lastValue = 0f

                    /** Puts the finger position on the curve; a fast stroke fills every grid line it passed. */
                    fun draw(position: Offset) {
                        val m = model
                        val snap = max(1, m.snap)
                        val raw = (position.x - keyW + view.scrollX) / view.pxPerTick
                        val tick = ((raw / snap).roundToInt() * snap).coerceIn(0, m.patternTicks)
                        val value = ((height - pad - position.y) / (height - 2 * pad)).coerceIn(0f, 1f)
                        val from = if (lastTick < 0) tick else min(lastTick, tick)
                        val to = if (lastTick < 0) tick else max(lastTick, tick)
                        // the curve as it is right now: several touch events can arrive between two frames
                        val current = studio.project.patterns.firstOrNull { it.id == m.patternId }
                            ?.lane(m.channelId, m.device, m.param)?.points ?: emptyList()
                        val next = ArrayList<AutoPoint>(current.size + 8)
                        // what the stroke passes over is replaced, the rest of the curve stays
                        for (pt in current) if (pt.tick < from || pt.tick > to) next += pt
                        if (lastTick < 0 || lastTick == tick) {
                            next += AutoPoint(tick, value)
                        } else {
                            var t = from
                            while (t <= to) {
                                val f = (t - lastTick).toFloat() / (tick - lastTick)
                                next += AutoPoint(t, lastValue + (value - lastValue) * f)
                                t += snap
                            }
                        }
                        studio.edit("automation") { it.setAutomation(m.patternId, m.channelId, m.device, m.param, next) }
                        lastTick = tick
                        lastValue = value
                        touch(value)
                    }

                    try {
                        draw(down.position)
                        while (true) {
                            val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                            if (!change.pressed) break
                            change.consume()
                            draw(change.position)
                        }
                    } finally {
                        touch(null)
                        studio.commit()
                    }
                }
            },
    ) {
        val px = view.pxPerTick
        if (px <= 0f) return@Canvas
        val sx = max(0f, view.scrollX)
        val right = size.width
        val h = size.height
        val gridW = right - keyW
        val thin = 1f * dp
        fun xOf(tick: Float) = keyW + tick * px - sx
        fun yOf(value: Float) = h - pad - value.coerceIn(0f, 1f) * (h - 2 * pad)

        drawRect(c.bg, Offset(keyW, 0f), Size(gridW, h))
        clipRect(keyW, 0f, right, h) {
            // the same vertical grid as the notes above
            val snap = model.snap
            val unit = when {
                snap * px >= 9f * dp -> snap
                TICKS_PER_STEP * px >= 9f * dp -> TICKS_PER_STEP
                PPQ * px >= 9f * dp -> PPQ
                else -> ticksPerBar
            }
            var t = (floor(sx / px / unit).toInt() * unit).coerceAtLeast(0)
            val lastTick = min(patternTicks.toFloat(), (sx + gridW) / px)
            while (t <= lastTick) {
                val x = xOf(t.toFloat())
                when {
                    t % ticksPerBar == 0 -> drawLine(c.lineStrong, Offset(x, 0f), Offset(x, h), 1.5f * dp)
                    t % PPQ == 0 -> drawLine(c.lineStrong.copy(alpha = 0.6f), Offset(x, 0f), Offset(x, h), thin)
                    else -> drawLine(c.line, Offset(x, 0f), Offset(x, h), thin)
                }
                t += unit
            }
            for (q in 0..4) {
                val y = yOf(q / 4f)
                drawLine(if (q == 0 || q == 4) c.lineStrong else c.line, Offset(keyW, y), Offset(right, y), thin)
            }

            val endX = xOf(patternTicks.toFloat())
            // where the knob itself stands: the value outside of this curve
            val baseY = yOf(target.base)
            drawLine(
                c.textDim.copy(alpha = 0.7f), Offset(keyW, baseY), Offset(min(right, endX), baseY), 1.5f * dp,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(5f * dp, 5f * dp)),
            )

            if (points.isNotEmpty()) {
                // flat before the first and after the last point, straight lines in between
                val line = Path()
                val first = points.first()
                val last = points.last()
                line.moveTo(xOf(0f), yOf(first.value))
                for (pt in points) line.lineTo(xOf(pt.tick.toFloat()), yOf(pt.value))
                val tail = max(xOf(last.tick.toFloat()), endX)
                line.lineTo(tail, yOf(last.value))
                val area = Path()
                area.addPath(line)
                area.lineTo(tail, h)
                area.lineTo(xOf(0f), h)
                area.close()
                drawPath(area, color.copy(alpha = 0.2f))
                drawPath(line, color, style = Stroke(2f * dp))
                var previous = Float.NEGATIVE_INFINITY
                for (pt in points) {
                    val x = xOf(pt.tick.toFloat())
                    if (x < keyW - 8f * dp || x > right + 8f * dp) continue
                    // dots only where there is room for them
                    if (abs(x - previous) < 7f * dp) continue
                    previous = x
                    drawCircle(c.bg, 4f * dp, Offset(x, yOf(pt.value)))
                    drawCircle(color, 3f * dp, Offset(x, yOf(pt.value)))
                }
            }
            // everything after the end of the pattern is not played
            if (endX < right) drawRect(c.bg.copy(alpha = 0.72f), Offset(max(keyW, endX), 0f), Size(right - max(keyW, endX), h))

            if (following) {
                val x = xOf(studio.playhead.doubleValue.toFloat())
                if (x >= keyW && x <= right) drawLine(c.accent, Offset(x, 0f), Offset(x, h), 2f * dp)
            }
        }

        // the scale: the values at the top and at the bottom of the lane
        drawRect(c.surface, Offset(0f, 0f), Size(keyW, h))
        drawLine(c.lineStrong, Offset(keyW, 0f), Offset(keyW, h), thin)
        labelPaint.color = c.textDim.toArgb()
        labelPaint.textAlign = Paint.Align.RIGHT
        labelPaint.textSize = 9f * dp * fontScale
        clipRect(0f, 0f, keyW - thin, h) {
            val canvas = drawContext.canvas.nativeCanvas
            canvas.drawText(top, keyW - 4f * dp, pad + 8f * dp, labelPaint)
            canvas.drawText(bottom, keyW - 4f * dp, h - pad, labelPaint)
        }
    }
}
