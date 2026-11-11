package jxpix.proger.mps.ui.studio

import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import jxpix.proger.mps.model.Scales
import jxpix.proger.mps.model.snapNotesToKey
import jxpix.proger.mps.ui.Fmt
import kotlin.math.abs
import android.graphics.Paint
import androidx.compose.animation.rememberSplineBasedDecay
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import jxpix.proger.mps.R
import jxpix.proger.mps.engine.PPQ
import jxpix.proger.mps.engine.TICKS_PER_STEP
import jxpix.proger.mps.model.Channel
import jxpix.proger.mps.model.MAX_PATTERN_BARS
import jxpix.proger.mps.model.Note
import jxpix.proger.mps.model.Pattern
import jxpix.proger.mps.model.isBlackKey
import jxpix.proger.mps.model.noteName
import jxpix.proger.mps.model.repeatPoints
import jxpix.proger.mps.model.setNotes
import jxpix.proger.mps.model.updatePattern
import jxpix.proger.mps.studio.Studio
import jxpix.proger.mps.studio.Tab
import jxpix.proger.mps.ui.Ic
import jxpix.proger.mps.ui.SwitchRow
import jxpix.proger.mps.ui.components.AppSheet
import jxpix.proger.mps.ui.components.Chip
import jxpix.proger.mps.ui.components.HintBanner
import jxpix.proger.mps.ui.components.IconBtn
import jxpix.proger.mps.ui.components.SheetDivider
import jxpix.proger.mps.ui.components.SheetItem
import jxpix.proger.mps.ui.theme.Mps
import jxpix.proger.mps.ui.theme.NumberStyle
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

private const val LOW_PITCH = 12     // C0
private const val HIGH_PITCH = 119   // B8
private const val ROWS = HIGH_PITCH - LOW_PITCH + 1
internal val KEY_WIDTH = 46.dp
private val RULER_HEIGHT = 24.dp
private val ROW_HEIGHT = 22.dp

private val SNAPS = intArrayOf(PPQ, PPQ / 2, PPQ / 4, PPQ / 8, PPQ / 6)
private val SNAP_LABELS = arrayOf("1/4", "1/8", "1/16", "1/32", "1/16T")

fun snapLabel(ticks: Int): String = SNAP_LABELS.getOrElse(SNAPS.indexOf(ticks)) { "1/16" }

/** Piano roll: the notes of one channel inside the current pattern. */
@Composable
fun PianoRollTab(studio: Studio) {
    val c = Mps.colors
    val ui = studio.ui
    val pattern = studio.currentPattern ?: return
    val channel = studio.currentChannel
    var channelSheet by remember { mutableStateOf(false) }
    var patternSheet by remember { mutableStateOf(false) }
    var snapSheet by remember { mutableStateOf(false) }
    var moreSheet by remember { mutableStateOf(false) }
    var keySheet by remember { mutableStateOf(false) }
    val res = LocalResources.current

    val notes = if (channel == null) emptyList() else pattern.notesOf(channel.id)
    val selected = remember(notes, ui.rollSelection) {
        if (ui.rollSelection.isEmpty()) emptySet() else ui.rollSelection.intersect(notes.toSet())
    }
    LaunchedEffect(pattern.id, channel?.id) { ui.rollSelection = emptySet() }

    /** Changes the selected notes and keeps them selected. */
    fun mapSelected(key: String?, f: (Note) -> Note) {
        val ch = channel ?: return
        val mapped = HashSet<Note>()
        studio.edit(key) { p ->
            p.setNotes(pattern.id, ch.id, notes.map { n -> if (n in selected) f(n).also { mapped += it } else n })
        }
        ui.rollSelection = mapped
    }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().height(50.dp).padding(start = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                Chip(
                    channel?.name ?: "—", { channelSheet = true }, Modifier.weight(1f, fill = false),
                    dot = channel?.let { c.channel(it.color) }, trailing = Ic.DropDown, maxWidth = 170.dp,
                )
                Spacer(Modifier.width(6.dp))
                Chip(pattern.name, { patternSheet = true }, Modifier.weight(1f, fill = false), trailing = Ic.DropDown, maxWidth = 150.dp)
            }
            Spacer(Modifier.width(6.dp))
            Chip(snapLabel(ui.rollSnap), { snapSheet = true }, icon = Ic.Grid)
            IconBtn(Ic.More, stringResource(R.string.more), { moreSheet = true })
        }
        if (channel == null || channel.isAudio) {
            Box(Modifier.weight(1f).fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        stringResource(R.string.piano_audio_info), color = c.textDim, fontSize = 14.sp, lineHeight = 20.sp,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(16.dp))
                    Chip(stringResource(R.string.tab_playlist), { ui.tab = Tab.PLAYLIST }, icon = Ic.Playlist)
                }
            }
        } else {
            if ("piano" !in studio.prefs.hintsSeen) {
                HintBanner(stringResource(R.string.hint_piano), { studio.prefs.markHintSeen("piano") }, Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
            }
            RollCanvas(studio, pattern, channel, notes, selected, Modifier.weight(1f).fillMaxWidth())
            if (ui.rollAutomation) AutomationLane(studio, pattern, channel)
            if (selected.isNotEmpty()) {
                SelectionBar(
                    count = selected.size,
                    velocity = selected.sumOf { it.velocity.toDouble() }.toFloat() / selected.size,
                    onVelocity = { v -> mapSelected("velocity") { it.copy(velocity = v) } },
                    onVelocityEnd = { studio.commit() },
                    pan = selected.sumOf { it.pan.toDouble() }.toFloat() / selected.size,
                    onPan = { v -> mapSelected("note-pan") { it.copy(pan = v) } },
                    onTranspose = { d ->
                        val lo = selected.minOf { it.pitch }
                        val hi = selected.maxOf { it.pitch }
                        if (lo + d >= LOW_PITCH && hi + d <= HIGH_PITCH) {
                            mapSelected(null) { it.copy(pitch = it.pitch + d) }
                            selected.firstOrNull()?.let { studio.preview(channel.id, it.pitch + d, it.velocity) }
                        }
                    },
                    onDuplicate = {
                        val from = selected.minOf { it.start }
                        val span = selected.maxOf { it.end } - from
                        val snap = ui.rollSnap
                        val shift = max(snap, ceil(span / snap.toFloat()).toInt() * snap)
                        val limit = studio.project.patternLength(pattern)
                        val copies = selected.map { it.copy(start = it.start + shift) }.filter { it.start < limit }
                        if (copies.isNotEmpty()) {
                            studio.edit { p -> p.setNotes(pattern.id, channel.id, notes + copies) }
                            ui.rollSelection = copies.toSet()
                        }
                    },
                    onDelete = {
                        studio.edit { p -> p.setNotes(pattern.id, channel.id, notes.filter { it !in selected }) }
                        ui.rollSelection = emptySet()
                    },
                    onClose = { ui.rollSelection = emptySet() },
                )
            }
        }
    }

    if (channelSheet) {
        AppSheet({ channelSheet = false }, title = stringResource(R.string.channel)) {
            for (ch in studio.project.channels) {
                SheetItem(
                    ch.name,
                    onClick = {
                        studio.selectChannel(ch.id)
                        channelSheet = false
                    },
                    dot = c.channel(ch.color), selected = ch.id == channel?.id,
                    icon = if (ch.isAudio) Ic.Wave else null,
                )
            }
        }
    }
    if (patternSheet) PatternSheet(studio) { patternSheet = false }
    if (snapSheet) {
        AppSheet({ snapSheet = false }, title = stringResource(R.string.snap)) {
            SNAPS.forEachIndexed { i, ticks ->
                SheetItem(SNAP_LABELS[i], {
                    ui.rollSnap = ticks
                    ui.rollNoteLength = ticks
                    snapSheet = false
                }, selected = ui.rollSnap == ticks)
            }
        }
    }
    if (keySheet) KeySheet(studio) { keySheet = false }
    if (moreSheet && channel != null) {
        val view = ui.roll
        AppSheet({ moreSheet = false }, title = channel.name) {
            SheetItem(stringResource(R.string.select_all), {
                ui.rollSelection = notes.toSet()
                moreSheet = false
            }, icon = Ic.SelectAll, enabled = notes.isNotEmpty())
            SheetItem(stringResource(R.string.quantize), {
                val snap = ui.rollSnap
                val limit = studio.project.patternLength(pattern)
                val all = selected.isEmpty()
                val mapped = HashSet<Note>()
                studio.edit { p ->
                    p.setNotes(pattern.id, channel.id, notes.map { n ->
                        if (all || n in selected) {
                            var start = (n.start / snap.toFloat()).roundToInt() * snap
                            if (start >= limit) start -= snap
                            n.copy(start = max(0, start)).also { if (!all) mapped += it }
                        } else n
                    })
                }
                ui.rollSelection = mapped
                moreSheet = false
            }, icon = Ic.Magic, enabled = notes.isNotEmpty())
            SheetItem(stringResource(R.string.double_length), {
                studio.edit { p ->
                    val length = p.patternLength(pattern)
                    p.updatePattern(pattern.id) { pat ->
                        pat.copy(
                            bars = pat.bars * 2,
                            notes = pat.notes.mapValues { (_, list) -> list + list.map { it.copy(start = it.start + length) } },
                            automation = pat.automation.map { lane -> lane.copy(points = repeatPoints(lane.points, length)) },
                        )
                    }
                }
                moreSheet = false
            }, icon = Ic.Duplicate, enabled = pattern.bars * 2 <= MAX_PATTERN_BARS)
            SheetItem(stringResource(R.string.clear_notes), {
                studio.edit { p -> p.setNotes(pattern.id, channel.id, emptyList()) }
                ui.rollSelection = emptySet()
                moreSheet = false
            }, icon = Ic.Clear, enabled = notes.isNotEmpty())
            SheetDivider()
            // the key of the song: its notes are marked in the grid
            SheetItem(stringResource(R.string.key_title), {
                moreSheet = false
                keySheet = true
            }, icon = Ic.Piano, description = keyName(studio.project, res) ?: stringResource(R.string.key_none))
            SheetItem(stringResource(R.string.key_snap), {
                val before = studio.project
                if (before.scale == 0) {
                    studio.message = res.getString(R.string.msg_key_first)
                } else {
                    val only = if (selected.isEmpty()) null else selected
                    studio.edit { it.snapNotesToKey(pattern.id, channel.id, only) }
                    studio.message = res.getString(if (studio.project === before) R.string.msg_key_all_in else R.string.msg_key_snapped)
                    ui.rollSelection = emptySet()
                }
                moreSheet = false
            }, icon = Ic.Check, description = stringResource(R.string.key_snap_desc), enabled = notes.isNotEmpty())
            SheetDivider()
            SwitchRow(stringResource(R.string.automation), stringResource(R.string.automation_desc), ui.rollAutomation) {
                ui.rollAutomation = it
                moreSheet = false
            }
            SwitchRow(stringResource(R.string.ghost_notes), null, ui.rollGhosts) { ui.rollGhosts = it }
            SheetItem(stringResource(R.string.zoom_in), { view.pxPerTick *= 1.5f }, icon = Ic.ZoomIn)
            SheetItem(stringResource(R.string.zoom_out), { view.pxPerTick /= 1.5f }, icon = Ic.ZoomOut)
            SheetDivider()
            SheetItem(stringResource(R.string.ch_sound), {
                moreSheet = false
                ui.editorTab = 0
                ui.editor = channel.id
            }, icon = Ic.Tune)
        }
    }
}

/** Tools for the selected notes. */
@Composable
private fun SelectionBar(
    count: Int,
    velocity: Float,
    onVelocity: (Float) -> Unit,
    onVelocityEnd: () -> Unit,
    pan: Float,
    onPan: (Float) -> Unit,
    onTranspose: (Int) -> Unit,
    onDuplicate: () -> Unit,
    onDelete: () -> Unit,
    onClose: () -> Unit,
) {
    val c = Mps.colors
    val res = LocalResources.current
    // the slider edits one thing at a time: how hard the notes are hit, or where they sit between the speakers
    var showPan by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().background(c.surface)) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(c.line))
        Row(Modifier.fillMaxWidth().height(44.dp).padding(start = 8.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            TwoWay(stringResource(R.string.velocity), stringResource(R.string.pan), showPan, stringResource(R.string.note_prop_switch)) { showPan = it }
            if (showPan) {
                val label = stringResource(R.string.note_pan)
                Slider(
                    value = pan.coerceIn(-1f, 1f),
                    // the middle is easy to hit
                    onValueChange = { v -> onPan(if (abs(v) < 0.05f) 0f else v) },
                    valueRange = -1f..1f, onValueChangeFinished = onVelocityEnd, colors = sliderColors(),
                    modifier = Modifier.weight(1f).padding(horizontal = 10.dp).semantics { contentDescription = label },
                )
                Text(Fmt.pan(pan, res), Modifier.width(44.dp), color = c.text, fontSize = 13.sp, style = NumberStyle, textAlign = TextAlign.End)
            } else {
                val label = stringResource(R.string.velocity)
                Slider(
                    value = velocity.coerceIn(0.05f, 1f), onValueChange = onVelocity, valueRange = 0.05f..1f,
                    onValueChangeFinished = onVelocityEnd, colors = sliderColors(),
                    modifier = Modifier.weight(1f).padding(horizontal = 10.dp).semantics { contentDescription = label },
                )
                Text(
                    "${(velocity * 100).roundToInt()}%", Modifier.width(44.dp), color = c.text, fontSize = 13.sp, style = NumberStyle,
                    textAlign = TextAlign.End,
                )
            }
            Spacer(Modifier.width(10.dp))
        }
        Row(
            Modifier.fillMaxWidth().height(48.dp).padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                stringResource(R.string.n_selected, count), Modifier.padding(start = 8.dp).weight(1f), color = c.text, fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold, maxLines = 1,
            )
            IconBtn(Ic.ExpandMore, stringResource(R.string.octave_down), { onTranspose(-12) }, size = 42.dp, iconSize = 26.dp)
            IconBtn(Ic.ArrowDown, stringResource(R.string.semitone_down), { onTranspose(-1) }, size = 42.dp)
            IconBtn(Ic.ArrowUp, stringResource(R.string.semitone_up), { onTranspose(1) }, size = 42.dp)
            IconBtn(Ic.ExpandLess, stringResource(R.string.octave_up), { onTranspose(12) }, size = 42.dp, iconSize = 26.dp)
            IconBtn(Ic.Copy, stringResource(R.string.duplicate), onDuplicate, size = 42.dp)
            IconBtn(Ic.Delete, stringResource(R.string.delete), onDelete, size = 42.dp, tint = c.record)
            IconBtn(Ic.Close, stringResource(R.string.deselect), onClose, size = 42.dp, tint = c.textDim)
        }
    }
}

/**
 * Two words side by side, one of them chosen: says which of two things the slider next to it edits.
 * Shared by the bar of the selected notes and the bar of the selected clip.
 */
@Composable
internal fun TwoWay(first: String, second: String, secondChosen: Boolean, description: String, onChoose: (Boolean) -> Unit) {
    val c = Mps.colors
    Row(
        Modifier.clip(RoundedCornerShape(10.dp)).background(c.surfaceHigh).semantics { contentDescription = description },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        for (side in 0..1) {
            val chosen = (side == 1) == secondChosen
            Text(
                if (side == 0) first else second,
                Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .background(if (chosen) c.accent else Color.Transparent)
                    .clickable(role = Role.Tab) { onChoose(side == 1) }
                    .semantics { selected = chosen }
                    .padding(horizontal = 10.dp, vertical = 7.dp),
                color = if (chosen) c.onAccent else c.textDim, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
            )
        }
    }
}

private class RollModel(
    val notes: List<Note>,
    val selected: Set<Note>,
    val patternTicks: Int,
    val snap: Int,
    val channelId: Int,
    val patternId: Int,
)

@Composable
private fun RollCanvas(studio: Studio, pattern: Pattern, channel: Channel, notes: List<Note>, selected: Set<Note>, modifier: Modifier) {
    val c = Mps.colors
    val ui = studio.ui
    val view = ui.roll
    val p = studio.project
    val density = LocalDensity.current
    val keyW = with(density) { KEY_WIDTH.toPx() }
    val rulerH = with(density) { RULER_HEIGHT.toPx() }
    val rowH = with(density) { ROW_HEIGHT.toPx() }
    val dp = density.density
    val patternTicks = p.patternLength(pattern)
    val ticksPerBar = p.ticksPerBar
    val color = c.channel(channel.color)
    val following = studio.playing && !studio.songMode
    val keyMask = p.keyMask
    val keyRoot = p.keyRoot

    val ghosts = remember(pattern, channel.id, ui.rollGhosts, p.channels) {
        if (!ui.rollGhosts) emptyList() else {
            val audible = p.channels.filter { !it.isAudio && it.id != channel.id }.map { it.id }.toSet()
            pattern.notes.filterKeys { it in audible }.values.flatten()
        }
    }
    val model by rememberUpdatedState(RollModel(notes, selected, patternTicks, ui.rollSnap, channel.id, pattern.id))
    var pressedKey by remember { mutableIntStateOf(-1) }
    var marquee by remember { mutableStateOf<Rect?>(null) }
    val haptics = LocalHapticFeedback.current

    val labelPaint = remember { Paint(Paint.ANTI_ALIAS_FLAG) }
    val description = stringResource(R.string.tab_piano) + ": " + channel.name

    BoxWithConstraints(modifier) {
        val gridW = (constraints.maxWidth - keyW).coerceAtLeast(1f)
        val gridH = (constraints.maxHeight - rulerH).coerceAtLeast(1f)
        val minPx = gridW / (max(patternTicks, ticksPerBar) + ticksPerBar).toFloat()
        val maxPx = 72f * dp / TICKS_PER_STEP

        // first visit: one bar across the screen (two for longer patterns), centred on the existing notes
        if (view.pxPerTick <= 0f) view.pxPerTick = gridW / (ticksPerBar * min(pattern.bars, 2)).toFloat()
        if (view.pxPerTick < minPx || view.pxPerTick > maxPx) view.pxPerTick = view.pxPerTick.coerceIn(minPx, maxPx)
        val maxScrollY = max(0f, ROWS * rowH - gridH)
        if (view.scrollY < 0f) {
            val centre = if (notes.isEmpty()) channel.rootNote else (notes.minOf { it.pitch } + notes.maxOf { it.pitch }) / 2
            view.scrollY = ((HIGH_PITCH - centre + 0.5f) * rowH - gridH / 2).coerceIn(0f, maxScrollY)
        }
        // the gesture code lives longer than one composition: it reads the current geometry from here
        ui.rollGridW = gridW
        ui.rollMaxScrollY = maxScrollY
        ui.rollMinPx = minPx
        val maxScrollX = remember(view, ui) {
            { max(0f, model.patternTicks * view.pxPerTick + 56f * dp - ui.rollGridW) }
        }

        val scope = rememberCoroutineScope()
        val decay = rememberSplineBasedDecay<Float>()
        val panZoom = remember(scope, decay) {
            PanZoom(
                scope, decay,
                onPan = { dx, dy ->
                    view.scrollX = (view.scrollX + dx).coerceIn(0f, maxScrollX())
                    view.scrollY = (view.scrollY + dy).coerceIn(0f, ui.rollMaxScrollY)
                },
                onZoom = { factor, centroid ->
                    val cx = centroid.x - keyW
                    val tick = (view.scrollX + cx) / view.pxPerTick
                    val px = (view.pxPerTick * factor).coerceIn(ui.rollMinPx, maxPx)
                    view.pxPerTick = px
                    view.scrollX = max(0f, tick * px - cx)
                },
            )
        }

        // keep the play head on screen
        if (following) {
            LaunchedEffect(pattern.id) {
                snapshotFlow { studio.playhead.doubleValue.toFloat() * view.pxPerTick }.collect { x ->
                    if (!panZoom.touching) {
                        val rel = x - view.scrollX
                        val w = ui.rollGridW
                        if (rel < 0f || rel > w * 0.96f) view.scrollX = (x - w * 0.08f).coerceIn(0f, maxScrollX())
                    }
                }
            }
        }

        Canvas(
            Modifier
                .fillMaxSize()
                .graphicsLayer()
                .semantics { contentDescription = description }
                .pointerInput(pattern.id, channel.id) {
                    val slop = viewConfiguration.touchSlop
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        panZoom.begin()
                        try {
                            val m = model
                            val x = down.position.x
                            val y = down.position.y
                            val px = view.pxPerTick
                            val sx = view.scrollX.coerceIn(0f, maxScrollX())
                            val sy = view.scrollY

                            fun pitchAt(py: Float): Int = (HIGH_PITCH - floor((py - rulerH + sy) / rowH).toInt()).coerceIn(LOW_PITCH, HIGH_PITCH)

                            if (x < keyW && y >= rulerH) {
                                // ---- the keys on the left play the sound
                                var pitch = pitchAt(y)
                                studio.noteOn(m.channelId, pitch)
                                pressedKey = pitch
                                try {
                                    while (true) {
                                        val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                                        if (!change.pressed) break
                                        change.consume()
                                        val now = pitchAt(change.position.y)
                                        if (now != pitch) {
                                            studio.noteOff(m.channelId, pitch)
                                            pitch = now
                                            studio.noteOn(m.channelId, pitch)
                                            pressedKey = pitch
                                        }
                                    }
                                } finally {
                                    studio.noteOff(m.channelId, pitch)
                                    pressedKey = -1
                                }
                                return@awaitEachGesture
                            }

                            // ---- which note is under the finger?
                            var hit: Note? = null
                            var resize = false
                            if (y >= rulerH && x >= keyW) {
                                val tick = (x - keyW + sx) / px
                                val pitch = pitchAt(y)
                                for (i in m.notes.indices.reversed()) {
                                    val n = m.notes[i]
                                    if (n.pitch == pitch && tick >= n.start && tick < n.end) {
                                        hit = n
                                        val w = n.length * px
                                        val grip = min(22f * dp, w * 0.35f)
                                        resize = w >= 26f * dp && (n.end - tick) * px <= grip
                                        break
                                    }
                                }
                            }

                            val target = hit
                            if (target != null) {
                                // ---- move or resize
                                studio.preview(m.channelId, target.pitch, target.velocity)
                                val group = if (target in m.selected) m.selected.toList() else listOf(target)
                                val groupSet = group.toSet()
                                val others = m.notes.filter { it !in groupSet }
                                val wasSelected = target in m.selected
                                val snap = m.snap
                                val minStart = group.minOf { it.start }
                                val maxStart = group.maxOf { it.start }
                                val lowest = group.minOf { it.pitch }
                                val highest = group.maxOf { it.pitch }
                                var moved = false
                                var lastTicks = 0
                                var lastPitch = 0
                                var result = group
                                while (true) {
                                    val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                                    if (!change.pressed) break
                                    val dx = change.position.x - x
                                    val dy = change.position.y - y
                                    if (!moved && hypot(dx, dy) > slop) moved = true
                                    if (!moved) continue
                                    change.consume()
                                    var dTicks = (dx / px / snap).roundToInt() * snap
                                    if (resize) {
                                        if (dTicks != lastTicks) {
                                            lastTicks = dTicks
                                            result = group.map { n -> n.copy(length = max(min(n.length, snap), n.length + dTicks)) }
                                            studio.edit("roll-drag") { it.setNotes(m.patternId, m.channelId, others + result) }
                                            if (wasSelected) ui.rollSelection = result.toSet()
                                        }
                                    } else {
                                        while (maxStart + dTicks >= m.patternTicks) dTicks -= snap
                                        while (minStart + dTicks < 0) dTicks += snap
                                        val dPitch = (-dy / rowH).roundToInt().coerceIn(LOW_PITCH - lowest, HIGH_PITCH - highest)
                                        if (dTicks != lastTicks || dPitch != lastPitch) {
                                            if (dPitch != lastPitch) studio.preview(m.channelId, target.pitch + dPitch, target.velocity)
                                            lastTicks = dTicks
                                            lastPitch = dPitch
                                            result = group.map { n -> n.copy(start = n.start + dTicks, pitch = n.pitch + dPitch) }
                                            studio.edit("roll-drag") { it.setNotes(m.patternId, m.channelId, others + result) }
                                            if (wasSelected) ui.rollSelection = result.toSet()
                                        }
                                    }
                                }
                                if (moved) {
                                    studio.commit()
                                    if (resize) ui.rollNoteLength = result[group.indexOf(target)].length
                                } else {
                                    // a tap selects / deselects
                                    ui.rollSelection = if (wasSelected) m.selected - target else m.selected + target
                                }
                            } else {
                                // ---- empty space: what the finger does next decides the gesture.
                                // 0 = lifted (a tap), 1 = moved or joined by a second finger (scroll / zoom),
                                // null = held still long enough (selection box)
                                val early: Int? = if (y >= rulerH && x >= keyW) {
                                    withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                                        var outcome = -1
                                        while (outcome < 0) {
                                            val event = awaitPointerEvent()
                                            val change = event.changes.firstOrNull { it.id == down.id }
                                            outcome = when {
                                                change == null || !change.pressed -> 0
                                                event.changes.size > 1 -> 1
                                                hypot(change.position.x - x, change.position.y - y) > slop -> 1
                                                else -> -1
                                            }
                                        }
                                        outcome
                                    }
                                } else 1

                                if (early == null) {
                                    // ---- selection box: every note it touches gets selected
                                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                    marquee = Rect(x, y, x, y)
                                    try {
                                        while (true) {
                                            val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                                            if (!change.pressed) break
                                            change.consume()
                                            val x1 = change.position.x.coerceIn(keyW, size.width.toFloat())
                                            val y1 = change.position.y.coerceIn(rulerH, size.height.toFloat())
                                            val box = Rect(min(x, x1), min(y, y1), max(x, x1), max(y, y1))
                                            marquee = box
                                            val fromTick = (box.left - keyW + sx) / px
                                            val toTick = (box.right - keyW + sx) / px
                                            val high = pitchAt(box.top)
                                            val low = pitchAt(box.bottom)
                                            ui.rollSelection = m.notes.filter {
                                                it.pitch in low..high && it.end > fromTick && it.start < toTick
                                            }.toSet()
                                        }
                                    } finally {
                                        marquee = null
                                    }
                                    return@awaitEachGesture
                                }

                                val tap = if (early == 0) true else with(panZoom) { track(down, slop) }
                                if (tap && y >= rulerH && x >= keyW) {
                                    if (m.selected.isNotEmpty()) {
                                        ui.rollSelection = emptySet()
                                    } else {
                                        val tick = (x - keyW + sx) / px
                                        val start = floor(tick / m.snap).toInt() * m.snap
                                        if (start >= 0 && start < m.patternTicks) {
                                            val pitch = pitchAt(y)
                                            val length = min(ui.rollNoteLength, m.patternTicks - start).coerceAtLeast(1)
                                            studio.edit { it.setNotes(m.patternId, m.channelId, m.notes + Note(start, length, pitch, 0.8f)) }
                                            studio.preview(m.channelId, pitch)
                                        }
                                    }
                                }
                            }
                        } finally {
                            panZoom.end()
                        }
                    }
                },
        ) {
            val px = view.pxPerTick
            val sx = view.scrollX.coerceIn(0f, maxScrollX())
            val sy = view.scrollY.coerceIn(0f, maxScrollY)
            val right = size.width
            val bottom = size.height
            val thin = 1f * dp

            clipRect(keyW, rulerH, right, bottom) {
                // rows
                val firstRow = (sy / rowH).toInt().coerceAtLeast(0)
                val lastRow = min(ROWS - 1, ((sy + gridH) / rowH).toInt())
                for (r in firstRow..lastRow) {
                    val pitch = HIGH_PITCH - r
                    val y = rulerH + r * rowH - sy
                    if (keyMask == 0) {
                        drawRect(if (isBlackKey(pitch)) c.rowBlack else c.rowWhite, Offset(keyW, y), Size(gridW, rowH))
                    } else {
                        // With a key chosen the stripes show the key instead of the black keys (those are still
                        // on the keyboard at the left): its notes are light, the others dark, the tonic is marked.
                        val inKey = Scales.contains(keyMask, pitch)
                        drawRect(if (inKey) c.rowWhite else c.rowBlack, Offset(keyW, y), Size(gridW, rowH))
                        // the two shades of the theme are close: the rows of the key are told apart a little more
                        // (lifted on the dark theme, the others dimmed on the light one)
                        if (c.dark) {
                            if (inKey) drawRect(c.text.copy(alpha = 0.075f), Offset(keyW, y), Size(gridW, rowH))
                        } else if (!inKey) {
                            drawRect(c.text.copy(alpha = 0.05f), Offset(keyW, y), Size(gridW, rowH))
                        }
                        if (inKey && (pitch - keyRoot) % 12 == 0) drawRect(c.accent.copy(alpha = 0.2f), Offset(keyW, y), Size(gridW, rowH))
                    }
                    val semitone = pitch % 12
                    if (semitone == 0) drawLine(c.lineStrong, Offset(keyW, y + rowH), Offset(right, y + rowH), thin)
                    else if (semitone == 5) drawLine(c.line, Offset(keyW, y + rowH), Offset(right, y + rowH), thin)
                }
                // vertical grid: the finest lines that are still comfortably apart
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
                    val x = keyW + t * px - sx
                    when {
                        t % ticksPerBar == 0 -> drawLine(c.lineStrong, Offset(x, rulerH), Offset(x, bottom), 1.5f * dp)
                        t % PPQ == 0 -> drawLine(c.lineStrong.copy(alpha = 0.6f), Offset(x, rulerH), Offset(x, bottom), thin)
                        else -> drawLine(c.line, Offset(x, rulerH), Offset(x, bottom), thin)
                    }
                    t += unit
                }
                // everything after the end of the pattern is not played
                val endX = keyW + patternTicks * px - sx
                if (endX < right) drawRect(c.bg.copy(alpha = 0.72f), Offset(max(keyW, endX), rulerH), Size(right - max(keyW, endX), gridH))

                fun rowTop(pitch: Int) = rulerH + (HIGH_PITCH - pitch) * rowH - sy

                // notes of the other channels, for orientation
                val ghostColor = c.textDim.copy(alpha = 0.22f)
                for (n in ghosts) {
                    val x0 = keyW + n.start * px - sx
                    val x1 = x0 + n.length * px
                    if (x1 < keyW || x0 > right) continue
                    val y = rowTop(n.pitch)
                    if (y + rowH < rulerH || y > bottom) continue
                    drawRoundRect(ghostColor, Offset(x0, y + rowH * 0.3f), Size(max(2f * dp, x1 - x0 - thin), rowH * 0.4f), CornerRadius(2f * dp))
                }
                // notes
                val radius = CornerRadius(4f * dp)
                val gripColor = (if (c.dark) Color.Black else Color.White).copy(alpha = 0.55f)
                val panMark = if (c.dark) Color.White else Color.Black
                for (n in notes) {
                    val x0 = keyW + n.start * px - sx
                    val x1 = x0 + n.length * px
                    if (x1 < keyW || x0 > right) continue
                    val y = rowTop(n.pitch)
                    if (y + rowH < rulerH || y > bottom) continue
                    val w = max(3f * dp, x1 - x0 - thin)
                    val topLeft = Offset(x0 + thin / 2, y + thin)
                    val rect = Size(w, rowH - 2 * thin)
                    drawRoundRect(color.copy(alpha = 0.4f + 0.6f * n.velocity.coerceIn(0f, 1f)), topLeft, rect, radius)
                    if (n.pan != 0f && w >= 8f * dp) {
                        // a note that sits to one side carries a mark at that end
                        val mark = min(4f * dp, w / 3f)
                        val mx = if (n.pan < 0f) x0 + thin / 2 else x0 + thin / 2 + w - mark
                        drawRoundRect(panMark.copy(alpha = 0.45f + 0.5f * abs(n.pan).coerceIn(0f, 1f)), Offset(mx, y + thin), Size(mark, rowH - 2 * thin), radius)
                    }
                    if (w >= 26f * dp) {
                        val gx = x0 + w - 5f * dp
                        drawLine(gripColor, Offset(gx, y + rowH * 0.28f), Offset(gx, y + rowH * 0.72f), 2f * dp)
                    }
                    if (n in selected) drawRoundRect(c.text, topLeft, rect, radius, style = Stroke(2f * dp))
                }
            }

            // ruler with bar numbers
            drawRect(c.surface, Offset(keyW, 0f), Size(gridW, rulerH))
            labelPaint.textSize = 11f * dp * fontScale
            labelPaint.color = c.textDim.toArgb()
            labelPaint.textAlign = Paint.Align.LEFT
            labelPaint.isFakeBoldText = true
            clipRect(keyW, 0f, right, rulerH) {
                val barPx = ticksPerBar * px
                val beatPx = PPQ * px
                var bar = floor(sx / barPx).toInt().coerceAtLeast(0)
                while (bar * barPx - sx < gridW && bar * ticksPerBar <= patternTicks) {
                    val x = keyW + bar * barPx - sx
                    drawLine(c.lineStrong, Offset(x, rulerH * 0.25f), Offset(x, rulerH), 1.5f * dp)
                    if (bar * ticksPerBar < patternTicks) {
                        drawContext.canvas.nativeCanvas.drawText("${bar + 1}", x + 5f * dp, rulerH * 0.72f, labelPaint)
                        if (beatPx >= 26f * dp) {
                            for (beat in 1 until p.beatsPerBar) {
                                val bx = x + beat * beatPx
                                drawLine(c.lineStrong.copy(alpha = 0.6f), Offset(bx, rulerH * 0.6f), Offset(bx, rulerH), thin)
                            }
                        }
                    }
                    bar++
                }
            }
            drawLine(c.lineStrong, Offset(0f, rulerH), Offset(right, rulerH), thin)

            // keyboard
            clipRect(0f, rulerH, keyW, bottom) {
                drawRect(c.keyWhite, Offset(0f, rulerH), Size(keyW, gridH))
                val firstRow = (sy / rowH).toInt().coerceAtLeast(0)
                val lastRow = min(ROWS - 1, ((sy + gridH) / rowH).toInt())
                labelPaint.textAlign = Paint.Align.RIGHT
                labelPaint.isFakeBoldText = false
                labelPaint.textSize = 10f * dp * fontScale
                val keyLine = Color(0x33000000)
                for (r in firstRow..lastRow) {
                    val pitch = HIGH_PITCH - r
                    val y = rulerH + r * rowH - sy
                    val black = isBlackKey(pitch)
                    if (pitch == pressedKey) {
                        drawRect(c.accent, Offset(0f, y), Size(if (black) keyW * 0.64f else keyW, rowH))
                    } else if (black) {
                        drawRoundRect(c.keyBlack, Offset(-4f * dp, y + thin), Size(keyW * 0.64f + 4f * dp, rowH - 2 * thin), CornerRadius(3f * dp))
                    }
                    val semitone = pitch % 12
                    if (semitone == 0 || semitone == 5) drawLine(keyLine, Offset(0f, y + rowH), Offset(keyW, y + rowH), thin)
                    if (semitone == 0) {
                        labelPaint.color = if (pitch == pressedKey) Color.White.toArgb() else 0xFF6B5A63.toInt()
                        drawContext.canvas.nativeCanvas.drawText(noteName(pitch), keyW - 4f * dp, y + rowH * 0.7f, labelPaint)
                    }
                    if (pitch == channel.rootNote && pitch != pressedKey) {
                        drawCircle(c.accent, 3f * dp, Offset(keyW - (if (semitone == 0) 28f else 9f) * dp, y + rowH / 2))
                    }
                    // the keys that belong to the song's key carry a line along their edge
                    if (keyMask != 0 && Scales.contains(keyMask, pitch) && pitch != pressedKey) {
                        drawRect(c.accent.copy(alpha = if ((pitch - keyRoot) % 12 == 0) 1f else 0.5f), Offset(keyW - 3f * dp, y + thin), Size(3f * dp, rowH - 2 * thin))
                    }
                }
            }
            drawLine(c.lineStrong, Offset(keyW, 0f), Offset(keyW, bottom), thin)
            drawRect(c.surface, Offset(0f, 0f), Size(keyW - thin / 2, rulerH - thin / 2))

            // selection box (hold and drag)
            marquee?.let { box ->
                drawRect(c.accent.copy(alpha = 0.16f), box.topLeft, box.size)
                drawRect(c.accent, box.topLeft, box.size, style = Stroke(1.5f * dp))
            }
        }

        // The play head lives in its own layer: while the pattern plays only this thin line is redrawn.
        if (following) {
            Canvas(Modifier.fillMaxSize()) {
                val x = keyW + studio.playhead.doubleValue.toFloat() * view.pxPerTick - view.scrollX.coerceIn(0f, maxScrollX())
                if (x >= keyW && x <= size.width) {
                    drawLine(c.accent, Offset(x, rulerH - 7f * dp), Offset(x, size.height), 2f * dp)
                    drawCircle(c.accent, 4f * dp, Offset(x, rulerH - 7f * dp))
                }
            }
        }
    }
}
