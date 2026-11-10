package jxpix.proger.mps.ui.studio

import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import jxpix.proger.mps.R
import jxpix.proger.mps.model.Channel
import jxpix.proger.mps.model.isBlackKey
import jxpix.proger.mps.model.noteName
import jxpix.proger.mps.studio.RecordMode
import jxpix.proger.mps.studio.Studio
import jxpix.proger.mps.ui.Ic
import jxpix.proger.mps.ui.components.AppSheet
import jxpix.proger.mps.ui.components.Chip
import jxpix.proger.mps.ui.components.HintBanner
import jxpix.proger.mps.ui.components.IconBtn
import jxpix.proger.mps.ui.components.Segmented
import jxpix.proger.mps.ui.components.SheetItem
import jxpix.proger.mps.ui.components.SheetLabel
import jxpix.proger.mps.ui.theme.Mps
import jxpix.proger.mps.ui.theme.NumberStyle
import kotlin.math.max
import kotlin.math.min

/** Semitones of each scale, counted from its root. Index 0 = no scale. */
private val SCALES = arrayOf(
    intArrayOf(),
    intArrayOf(0, 2, 4, 5, 7, 9, 11),
    intArrayOf(0, 2, 3, 5, 7, 8, 10),
    intArrayOf(0, 2, 4, 7, 9),
    intArrayOf(0, 3, 5, 7, 10),
    intArrayOf(0, 3, 5, 6, 7, 10),
)
private val SCALE_NAMES = intArrayOf(
    R.string.scale_off, R.string.scale_major, R.string.scale_minor, R.string.scale_penta_major, R.string.scale_penta_minor,
    R.string.scale_blues,
)
private val ROOT_NAMES = arrayOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")

/** Keyboard and drum pads for playing live (and recording what is played). */
@Composable
fun KeysTab(studio: Studio) {
    val c = Mps.colors
    val ui = studio.ui
    val p = studio.project
    val channel = studio.currentChannel
    var channelSheet by remember { mutableStateOf(false) }
    var scaleSheet by remember { mutableStateOf(false) }
    val pads = ui.padsMode ?: (channel?.isDrum == true)

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().height(50.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Segmented(
                listOf(stringResource(R.string.keys_keyboard), stringResource(R.string.keys_pads)), if (pads) 1 else 0,
                { ui.padsMode = it == 1 },
            )
            Spacer(Modifier.width(6.dp))
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                if (!pads) {
                    Chip(
                        channel?.name ?: "—", { channelSheet = true }, Modifier.weight(1f, fill = false),
                        dot = channel?.let { c.channel(it.color) }, trailing = Ic.DropDown, maxWidth = 170.dp,
                    )
                }
            }
        }
        if (!pads && channel != null && !channel.isAudio) {
            Row(Modifier.fillMaxWidth().height(44.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconBtn(Ic.Remove, stringResource(R.string.octave_down), { ui.octave = max(0, ui.octave - 1) }, enabled = ui.octave > 0, background = c.surfaceHigh, size = 38.dp)
                Text(
                    stringResource(R.string.octave) + " " + ui.octave, Modifier.width(96.dp), color = c.text, fontSize = 13.sp,
                    style = NumberStyle, textAlign = TextAlign.Center, maxLines = 1,
                )
                IconBtn(Ic.Add, stringResource(R.string.octave_up), { ui.octave = min(7, ui.octave + 1) }, enabled = ui.octave < 7, background = c.surfaceHigh, size = 38.dp)
                Spacer(Modifier.weight(1f))
                Chip(
                    if (ui.scale == 0) stringResource(R.string.scale) else ROOT_NAMES[ui.scaleRoot] + " " + stringResource(SCALE_NAMES[ui.scale]).lowercase(),
                    { scaleSheet = true }, selected = ui.scale != 0, icon = Ic.Magic, maxWidth = 190.dp,
                )
            }
        }
        if ("keys" !in studio.prefs.hintsSeen) {
            HintBanner(stringResource(R.string.hint_keys), { studio.prefs.markHintSeen("keys") }, Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
        }
        if (studio.recordMode == RecordMode.NOTES) {
            Text(
                stringResource(if (studio.countingIn) R.string.count_in else R.string.recording_notes),
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), color = c.record, fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center,
            )
        }
        Box(Modifier.weight(1f).fillMaxWidth().padding(start = 8.dp, end = 8.dp, top = 4.dp, bottom = 8.dp)) {
            when {
                pads -> PadGrid(studio)
                channel == null || channel.isAudio -> Text(
                    stringResource(R.string.keys_audio_info), Modifier.align(Alignment.Center).padding(24.dp), color = c.textDim,
                    fontSize = 14.sp, lineHeight = 20.sp, textAlign = TextAlign.Center,
                )
                else -> BoxWithConstraints(Modifier.fillMaxSize()) {
                    // phones held upright get two keyboards, one above the other
                    val twoRows = maxHeight >= 300.dp && maxWidth < 600.dp
                    val whiteKeys = (maxWidth / 50.dp).toInt().coerceIn(7, 22)
                    val scale = if (ui.scale == 0) null else SCALES[ui.scale]
                    if (twoRows) {
                        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Keyboard(studio, channel, (ui.octave + 2) * 12, whiteKeys, scale, ui.scaleRoot, Modifier.weight(1f).fillMaxWidth())
                            Keyboard(studio, channel, (ui.octave + 1) * 12, whiteKeys, scale, ui.scaleRoot, Modifier.weight(1f).fillMaxWidth())
                        }
                    } else {
                        Keyboard(studio, channel, (ui.octave + 1) * 12, whiteKeys, scale, ui.scaleRoot, Modifier.fillMaxSize())
                    }
                }
            }
        }
    }

    if (channelSheet) {
        AppSheet({ channelSheet = false }, title = stringResource(R.string.channel)) {
            for (ch in p.channels) {
                if (ch.isAudio) continue
                SheetItem(ch.name, onClick = {
                    studio.selectChannel(ch.id)
                    channelSheet = false
                }, dot = c.channel(ch.color), selected = ch.id == channel?.id)
            }
        }
    }
    if (scaleSheet) {
        AppSheet({ scaleSheet = false }, title = stringResource(R.string.scale)) {
            SCALE_NAMES.forEachIndexed { i, name ->
                SheetItem(stringResource(name), { ui.scale = i }, selected = ui.scale == i)
            }
            if (ui.scale != 0) {
                SheetLabel(stringResource(R.string.scale_root))
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    ROOT_NAMES.forEachIndexed { i, name ->
                        val on = ui.scaleRoot == i
                        Box(
                            Modifier
                                .weight(1f)
                                .height(40.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(if (on) c.accent else c.surfaceHigh)
                                .pointerInput(i) {
                                    awaitEachGesture {
                                        awaitFirstDown().consume()
                                        ui.scaleRoot = i
                                    }
                                }
                                .semantics {
                                    role = Role.RadioButton
                                    contentDescription = name
                                },
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(name, color = if (on) c.onAccent else c.text, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                        }
                    }
                }
            }
        }
    }
}

/**
 * A multi-touch piano keyboard. Sliding from key to key plays a glissando; touching a key
 * lower down plays it louder.
 */
@Composable
fun Keyboard(studio: Studio, channel: Channel, firstPitch: Int, whiteKeys: Int, scale: IntArray?, scaleRoot: Int, modifier: Modifier) {
    val c = Mps.colors
    val color = c.channel(channel.color)
    val channelId by rememberUpdatedState(channel.id)
    val first by rememberUpdatedState(firstPitch)
    val count by rememberUpdatedState(whiteKeys)
    var pressed by remember { mutableStateOf<Set<Int>>(emptySet()) }
    val held = remember { HashMap<PointerId, Int>() }
    val labelPaint = remember { Paint(Paint.ANTI_ALIAS_FLAG) }
    val description = stringResource(R.string.keys_keyboard) + " " + noteName(firstPitch)

    // nothing may keep sounding when the keyboard goes away (tab switch, octave change)
    DisposableEffect(channel.id, firstPitch) {
        onDispose {
            for (pitch in held.values) studio.noteOff(channel.id, pitch)
            held.clear()
            pressed = emptySet()
        }
    }

    Canvas(
        modifier
            .clip(RoundedCornerShape(12.dp))
            .semantics { contentDescription = description }
            .pointerInput(Unit) {
                /** White keys of an octave -> semitone. */
                val whites = intArrayOf(0, 2, 4, 5, 7, 9, 11)

                fun pitchAt(pos: Offset): Int {
                    val w = size.width.toFloat() / count
                    val index = (pos.x / w).toInt().coerceIn(0, count - 1)
                    val white = first + (index / 7) * 12 + whites[index % 7]
                    if (pos.y < size.height * 0.6f) {
                        // black keys sit on the border between two white keys
                        val rel = pos.x - index * w
                        if (rel > w * 0.68f && index < count - 1 && isBlackKey(white + 1)) return white + 1
                        if (rel < w * 0.32f && index > 0 && isBlackKey(white - 1)) return white - 1
                    }
                    return white
                }

                fun velocityAt(pos: Offset): Float = (0.45f + 0.55f * pos.y / size.height).coerceIn(0.3f, 1f)

                awaitEachGesture {
                    do {
                        val event = awaitPointerEvent()
                        for (ch in event.changes) {
                            val old = held[ch.id]
                            if (ch.pressed) {
                                val pitch = pitchAt(ch.position).coerceIn(0, 127)
                                if (old != pitch) {
                                    if (old != null) {
                                        held.remove(ch.id)
                                        if (old !in held.values) studio.noteOff(channelId, old)
                                    }
                                    if (pitch !in held.values) studio.noteOn(channelId, pitch, velocityAt(ch.position))
                                    held[ch.id] = pitch
                                    pressed = held.values.toSet()
                                }
                                ch.consume()
                            } else if (old != null) {
                                held.remove(ch.id)
                                if (old !in held.values) studio.noteOff(channelId, old)
                                pressed = held.values.toSet()
                            }
                        }
                    } while (event.changes.any { it.pressed })
                    // safety net: a cancelled gesture must not leave notes hanging
                    for (pitch in held.values) studio.noteOff(channelId, pitch)
                    held.clear()
                    if (pressed.isNotEmpty()) pressed = emptySet()
                }
            },
    ) {
        val whites = intArrayOf(0, 2, 4, 5, 7, 9, 11)
        val w = size.width / whiteKeys
        val gap = 1.5.dp.toPx()
        val radius = CornerRadius(6.dp.toPx())
        val blackH = size.height * 0.6f
        val blackW = w * 0.62f
        labelPaint.textAlign = Paint.Align.CENTER
        labelPaint.textSize = 11.dp.toPx() * fontScale
        val dot = 3.5.dp.toPx()

        fun inScale(pitch: Int): Boolean = scale != null && ((pitch - scaleRoot) % 12 + 12) % 12 in scale
        fun isRoot(pitch: Int): Boolean = scale != null && ((pitch - scaleRoot) % 12 + 12) % 12 == 0

        drawRect(c.keyBlack)
        for (i in 0 until whiteKeys) {
            val pitch = firstPitch + (i / 7) * 12 + whites[i % 7]
            val x = i * w
            val on = pitch in pressed
            // straight edges: the rounded outline comes from the clip of the whole keyboard
            drawRect(if (on) color else c.keyWhite, Offset(x + gap / 2, 0f), Size(w - gap, size.height))
            if (inScale(pitch)) {
                drawCircle(if (on) Color.White else if (isRoot(pitch)) c.accent else c.accent.copy(alpha = 0.4f), dot, Offset(x + w / 2, size.height - 30.dp.toPx()))
            }
            if (pitch % 12 == 0) {
                labelPaint.color = if (on) Color.White.toArgb() else 0xFF8A7780.toInt()
                drawContext.canvas.nativeCanvas.drawText(noteName(pitch), x + w / 2, size.height - 10.dp.toPx(), labelPaint)
            }
        }
        for (i in 0 until whiteKeys - 1) {
            val pitch = firstPitch + (i / 7) * 12 + whites[i % 7] + 1
            if (!isBlackKey(pitch)) continue
            val x = (i + 1) * w - blackW / 2
            val on = pitch in pressed
            drawRoundRect(if (on) color else c.keyBlack, Offset(x, -radius.y), Size(blackW, blackH + radius.y), radius)
            if (!on) drawRoundRect(Color.White.copy(alpha = 0.10f), Offset(x + blackW * 0.18f, blackH * 0.08f), Size(blackW * 0.64f, blackH * 0.8f), radius)
            if (inScale(pitch)) drawCircle(if (on) Color.White else if (isRoot(pitch)) c.accent else Color.White.copy(alpha = 0.55f), dot, Offset(x + blackW / 2, blackH - 16.dp.toPx()))
        }
    }
}

/** 4×4 pads: every pad plays one channel, so a whole drum kit can be played with the fingers. */
@Composable
private fun PadGrid(studio: Studio) {
    val c = Mps.colors
    val channels = studio.project.channels.filter { !it.isAudio }.take(16)
    if (channels.isEmpty()) return
    val columns = 4
    val rows = (channels.size + columns - 1) / columns
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for (r in 0 until rows) {
            Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (col in 0 until columns) {
                    val ch = channels.getOrNull(r * columns + col)
                    if (ch == null) Spacer(Modifier.weight(1f)) else Pad(studio, ch, Modifier.weight(1f).fillMaxHeight())
                }
            }
        }
    }
}

@Composable
private fun Pad(studio: Studio, ch: Channel, modifier: Modifier) {
    val c = Mps.colors
    val color = c.channel(ch.color)
    var down by remember { mutableStateOf(false) }
    val channel by rememberUpdatedState(ch)
    DisposableEffect(ch.id) {
        onDispose { if (down) studio.noteOff(ch.id, ch.rootNote) }
    }
    Box(
        modifier
            .clip(RoundedCornerShape(16.dp))
            .background(if (down) color else color.copy(alpha = if (c.dark) 0.28f else 0.22f))
            .border(1.5.dp, color, RoundedCornerShape(16.dp))
            .semantics {
                role = Role.Button
                contentDescription = ch.name
            }
            .pointerInput(ch.id) {
                awaitEachGesture {
                    val first = awaitFirstDown(requireUnconsumed = false)
                    first.consume()
                    val target = channel
                    val pitch = target.rootNote
                    studio.selectChannel(target.id)
                    studio.noteOn(target.id, pitch, (0.6f + 0.4f * first.position.y / size.height).coerceIn(0.5f, 1f))
                    down = true
                    try {
                        while (true) {
                            val change = awaitPointerEvent().changes.firstOrNull { it.id == first.id } ?: break
                            if (!change.pressed) break
                            change.consume()
                        }
                    } finally {
                        studio.noteOff(target.id, pitch)
                        down = false
                    }
                }
            }
            .padding(6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            ch.name, color = if (down) Color.White else c.text, fontSize = 12.sp, lineHeight = 14.sp, fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis,
        )
    }
}
