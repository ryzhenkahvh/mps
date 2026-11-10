package jxpix.proger.mps.ui.studio

import android.os.SystemClock
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import jxpix.proger.mps.R
import jxpix.proger.mps.model.Channel
import jxpix.proger.mps.model.MASTER_ID
import jxpix.proger.mps.model.updateChannel
import jxpix.proger.mps.studio.Meter
import jxpix.proger.mps.studio.Studio
import jxpix.proger.mps.ui.Fmt
import jxpix.proger.mps.ui.Ic
import jxpix.proger.mps.ui.components.HintBanner
import jxpix.proger.mps.ui.components.Knob
import jxpix.proger.mps.ui.components.ToggleTag
import jxpix.proger.mps.ui.theme.Mps
import jxpix.proger.mps.ui.theme.NumberStyle
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.cbrt
import kotlin.math.sign

private val STRIP_WIDTH = 70.dp
private val MASTER_WIDTH = 82.dp

/** Mixer: volume, panning and effects of every channel, plus the master strip. */
@Composable
fun MixerTab(studio: Studio) {
    val c = Mps.colors
    val ui = studio.ui
    val p = studio.project

    // the safety limiter reports how hard it had to work; warn when the mix is clearly too loud
    var tooLoud by remember { mutableStateOf(false) }
    LaunchedEffect(studio) {
        var until = 0L
        studio.engine.limiterReductionDb = 0f
        while (true) {
            delay(400)
            val db = studio.engine.limiterReductionDb
            studio.engine.limiterReductionDb = 0f
            val now = SystemClock.elapsedRealtime()
            if (db > 3f) until = now + 3000
            tooLoud = now < until
        }
    }

    Column(Modifier.fillMaxSize()) {
        if ("mixer" !in studio.prefs.hintsSeen) {
            HintBanner(stringResource(R.string.hint_mixer), { studio.prefs.markHintSeen("mixer") }, Modifier.padding(start = 8.dp, end = 8.dp, top = 6.dp))
        }
        if (tooLoud) {
            Row(
                Modifier.padding(start = 8.dp, end = 8.dp, top = 6.dp).fillMaxWidth().clip(RoundedCornerShape(14.dp))
                    .background(c.record.copy(alpha = 0.14f)).padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Ic.Warning, null, Modifier.size(18.dp), tint = c.record)
                Spacer(Modifier.width(10.dp))
                Text(stringResource(R.string.limiter_working), color = c.text, fontSize = 12.sp, lineHeight = 16.sp)
            }
        }
        Row(Modifier.weight(1f).fillMaxWidth().padding(top = 6.dp, bottom = 6.dp)) {
            MasterStrip(studio, Modifier.padding(start = 6.dp).width(MASTER_WIDTH).fillMaxHeight())
            Box(Modifier.padding(horizontal = 5.dp).width(1.dp).fillMaxHeight().background(c.line))
            LazyRow(
                Modifier.weight(1f).fillMaxHeight(), contentPadding = PaddingValues(end = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                items(p.channels, key = { it.id }) { ch ->
                    ChannelStrip(studio, ch, selected = ch.id == studio.channelId, Modifier.width(STRIP_WIDTH).fillMaxHeight())
                }
            }
        }
    }
}

@Composable
private fun StripFrame(selected: Boolean, modifier: Modifier, content: @Composable ColumnScope.() -> Unit) {
    val c = Mps.colors
    Column(
        modifier
            .clip(RoundedCornerShape(16.dp))
            .background(c.surface)
            .border(1.dp, if (selected) c.accent else c.line, RoundedCornerShape(16.dp))
            .padding(bottom = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) { content() }
}

@Composable
private fun ChannelStrip(studio: Studio, ch: Channel, selected: Boolean, modifier: Modifier) {
    val c = Mps.colors
    val ui = studio.ui
    val res = LocalResources.current
    val color = c.channel(ch.color)
    StripFrame(selected, modifier) {
        // name: opens the sound of the channel
        Column(
            Modifier.fillMaxWidth().height(40.dp).clickable(role = Role.Button) {
                studio.selectChannel(ch.id)
                ui.editorTab = 0
                ui.editor = ch.id
            },
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(Modifier.fillMaxWidth().height(4.dp).background(color))
            Box(Modifier.fillMaxSize().padding(horizontal = 4.dp), contentAlignment = Alignment.Center) {
                Text(
                    ch.name, color = if (ch.mute) c.textDim else c.text, fontSize = 11.sp, lineHeight = 13.sp, fontWeight = FontWeight.SemiBold,
                    maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
                )
            }
        }
        val panLabel = stringResource(R.string.pan) + ": " + ch.name
        Knob(
            value = (ch.pan + 1f) / 2f,
            onValueChange = { v ->
                // a small dead zone makes it easy to return to the centre
                val pan = (v * 2f - 1f).let { if (abs(it) < 0.04f) 0f else it }
                studio.edit("pan${ch.id}") { it.updateChannel(ch.id) { x -> x.copy(pan = pan) } }
            },
            size = 38.dp, color = color, defaultValue = 0.5f, bipolar = true, onChangeEnd = { studio.commit() }, description = panLabel,
        )
        Text(Fmt.pan(ch.pan, res), color = c.textDim, fontSize = 10.sp, lineHeight = 12.sp, style = NumberStyle)
        Fader(
            value = ch.volume,
            onChange = { v -> studio.edit("vol${ch.id}") { it.updateChannel(ch.id) { x -> x.copy(volume = v) } } },
            onEnd = { studio.commit() },
            studio = studio, left = studio.meter(ch.id), right = null, color = color,
            description = stringResource(R.string.volume) + ": " + ch.name,
            modifier = Modifier.weight(1f).fillMaxWidth().padding(top = 4.dp),
        )
        Text(Fmt.fader(ch.volume, res), color = c.text, fontSize = 10.sp, lineHeight = 14.sp, style = NumberStyle, maxLines = 1)
        Spacer(Modifier.height(4.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            ToggleTag(
                stringResource(R.string.mute_short), ch.mute, { studio.edit { it.updateChannel(ch.id) { x -> x.copy(mute = !x.mute) } } },
                onColor = c.meterMid, description = stringResource(R.string.mute) + ": " + ch.name, width = 29.dp, height = 28.dp,
            )
            ToggleTag(
                stringResource(R.string.solo_short), ch.solo, { studio.edit { it.updateChannel(ch.id) { x -> x.copy(solo = !x.solo) } } },
                onColor = c.meterLow, description = stringResource(R.string.solo) + ": " + ch.name, width = 29.dp, height = 28.dp,
            )
        }
        Spacer(Modifier.height(4.dp))
        FxButton(ch.effects.size, stringResource(R.string.effects) + ": " + ch.name) {
            studio.selectChannel(ch.id)
            ui.editorTab = 1
            ui.editor = ch.id
        }
    }
}

@Composable
private fun MasterStrip(studio: Studio, modifier: Modifier) {
    val c = Mps.colors
    val ui = studio.ui
    val p = studio.project
    val res = LocalResources.current
    val name = stringResource(R.string.master)
    StripFrame(false, modifier) {
        Column(Modifier.fillMaxWidth().height(40.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.fillMaxWidth().height(4.dp).background(c.text))
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(name, color = c.text, fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1)
            }
        }
        Fader(
            value = p.masterVolume,
            onChange = { v -> studio.edit("master-vol") { it.copy(masterVolume = v) } },
            onEnd = { studio.commit() },
            studio = studio, left = studio.masterMeterL, right = studio.masterMeterR, color = c.accent,
            description = stringResource(R.string.volume) + ": " + name,
            modifier = Modifier.weight(1f).fillMaxWidth().padding(top = 4.dp),
        )
        Text(Fmt.fader(p.masterVolume, res), color = c.text, fontSize = 10.sp, lineHeight = 14.sp, style = NumberStyle, maxLines = 1)
        Spacer(Modifier.height(4.dp))
        FxButton(p.masterEffects.size, stringResource(R.string.master_effects)) {
            ui.editorTab = 1
            ui.editor = MASTER_ID
        }
    }
}

@Composable
private fun FxButton(count: Int, description: String, onClick: () -> Unit) {
    val c = Mps.colors
    val on = count > 0
    Box(
        Modifier
            .width(62.dp)
            .height(30.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(if (on) c.accentSoft else c.surfaceHigh)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            if (on) stringResource(R.string.fx) + " $count" else stringResource(R.string.fx), color = if (on) c.accent else c.textDim,
            fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1,
        )
    }
}

/** Peak level -> position on the fader scale, so that 0 dB of signal lines up with 0 dB of the fader. */
private fun levelToPosition(level: Float): Float = (0.8f * cbrt(level.toDouble()).toFloat()).coerceIn(0f, 1f)

/**
 * A volume fader with the level meter next to it.
 * Dragging is relative (the cap does not jump under the finger) and only starts on a vertical
 * movement, so swiping sideways still scrolls the mixer.
 */
@Composable
private fun Fader(
    value: Float,
    onChange: (Float) -> Unit,
    onEnd: () -> Unit,
    studio: Studio,
    left: Meter,
    right: Meter?,
    color: Color,
    description: String,
    modifier: Modifier = Modifier,
) {
    val c = Mps.colors
    val res = LocalResources.current
    val current by rememberUpdatedState(value)
    val change by rememberUpdatedState(onChange)
    val end by rememberUpdatedState(onEnd)
    val capColor = c.text
    val capLine = c.bg
    Canvas(
        modifier
            .semantics {
                contentDescription = description
                stateDescription = Fmt.fader(value, res)
                progressBarRangeInfo = ProgressBarRangeInfo(value, 0f..1f)
                setProgress { v ->
                    change(v.coerceIn(0f, 1f))
                    end()
                    true
                }
            }
            .pointerInput(Unit) {
                val slop = viewConfiguration.touchSlop
                val doubleTap = viewConfiguration.doubleTapTimeoutMillis
                var lastTap = 0L
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val start = current
                    val travel = (size.height - 28.dp.toPx()).coerceAtLeast(1f)
                    var mode = 0 // 0 undecided, 1 dragging, 2 not ours (horizontal scroll)
                    while (true) {
                        val ch = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                        if (!ch.pressed) break
                        val dx = ch.position.x - down.position.x
                        val dy = ch.position.y - down.position.y
                        if (mode == 0) {
                            if (abs(dy) > slop && abs(dy) >= abs(dx)) mode = 1 else if (abs(dx) > slop) mode = 2
                        }
                        if (mode == 1) {
                            ch.consume()
                            change((start - (dy - sign(dy) * slop) / travel).coerceIn(0f, 1f))
                        } else if (mode == 2) break
                    }
                    if (mode == 1) {
                        end()
                    } else if (mode == 0) {
                        // double tap: back to 0 dB
                        val now = SystemClock.uptimeMillis()
                        if (now - lastTap < doubleTap) {
                            change(0.8f)
                            end()
                            lastTap = 0L
                        } else lastTap = now
                    }
                }
            },
    ) {
        // read the frame counter so that the meter is redrawn while there is sound
        @Suppress("UNUSED_EXPRESSION") studio.frame.longValue
        val capH = 28.dp.toPx()
        val top = capH / 2
        val travel = size.height - capH
        fun yOf(pos: Float) = top + (1f - pos) * travel

        val meters = if (right != null) 2 else 1
        val meterW = 5.dp.toPx()
        val gap = 2.dp.toPx()
        val metersW = meters * meterW + (meters - 1) * gap
        val trackX = (size.width - metersW - 10.dp.toPx()) / 2
        val meterX = trackX + 16.dp.toPx()

        // track and scale
        drawRoundRect(c.surfaceHigh, Offset(trackX - 2.dp.toPx(), top), Size(4.dp.toPx(), travel), CornerRadius(2.dp.toPx()))
        drawRoundRect(color.copy(alpha = 0.55f), Offset(trackX - 2.dp.toPx(), yOf(value)), Size(4.dp.toPx(), yOf(0f) - yOf(value)), CornerRadius(2.dp.toPx()))
        val unity = yOf(0.8f)
        drawLine(c.lineStrong, Offset(trackX - 12.dp.toPx(), unity), Offset(trackX - 6.dp.toPx(), unity), 1.5.dp.toPx())

        // meters
        val yellowFrom = yOf(levelToPosition(0.5f))
        val redFrom = yOf(levelToPosition(0.9f))
        for (i in 0 until meters) {
            val m = if (i == 0) left else right!!
            val x = meterX + i * (meterW + gap)
            drawRoundRect(c.surfaceHigh, Offset(x, top), Size(meterW, travel), CornerRadius(meterW / 2))
            val y = yOf(levelToPosition(m.level))
            val bottom = top + travel
            if (y < bottom - 0.5f) {
                drawRect(c.meterLow, Offset(x, maxOf(y, yellowFrom)), Size(meterW, bottom - maxOf(y, yellowFrom)))
                if (y < yellowFrom) drawRect(c.meterMid, Offset(x, maxOf(y, redFrom)), Size(meterW, yellowFrom - maxOf(y, redFrom)))
                if (y < redFrom) drawRect(c.meterHigh, Offset(x, y), Size(meterW, redFrom - y))
            }
            if (m.hold > 0.003f) {
                val hy = yOf(levelToPosition(m.hold))
                drawRect(if (m.hold >= 0.9f) c.meterHigh else c.textDim, Offset(x, hy), Size(meterW, 2.dp.toPx()))
            }
        }

        // cap
        val capW = 24.dp.toPx()
        val cy = yOf(value)
        drawRoundRect(capColor, Offset(trackX - capW / 2, cy - capH / 2), Size(capW, capH), CornerRadius(7.dp.toPx()))
        drawLine(capLine, Offset(trackX - capW / 2 + 5.dp.toPx(), cy), Offset(trackX + capW / 2 - 5.dp.toPx(), cy), 2.dp.toPx())
    }
}
