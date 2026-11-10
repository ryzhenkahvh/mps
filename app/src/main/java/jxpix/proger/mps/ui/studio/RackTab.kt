package jxpix.proger.mps.ui.studio

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import jxpix.proger.mps.R
import jxpix.proger.mps.engine.TICKS_PER_STEP
import jxpix.proger.mps.model.Channel
import jxpix.proger.mps.model.Note
import jxpix.proger.mps.model.duplicateChannel
import jxpix.proger.mps.model.moveChannel
import jxpix.proger.mps.model.removeChannel
import jxpix.proger.mps.model.setNotes
import jxpix.proger.mps.model.setStep
import jxpix.proger.mps.model.shiftNotes
import jxpix.proger.mps.model.stepActive
import jxpix.proger.mps.model.updateChannel
import jxpix.proger.mps.studio.Studio
import jxpix.proger.mps.studio.Tab
import jxpix.proger.mps.ui.Ic
import jxpix.proger.mps.ui.components.AppSheet
import jxpix.proger.mps.ui.components.Chip
import jxpix.proger.mps.ui.components.ConfirmDialog
import jxpix.proger.mps.ui.components.HintBanner
import jxpix.proger.mps.ui.components.Segmented
import jxpix.proger.mps.ui.components.SheetDivider
import jxpix.proger.mps.ui.components.SheetItem
import jxpix.proger.mps.ui.components.TextInputDialog
import jxpix.proger.mps.ui.theme.Mps
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

private val ROW_HEIGHT = 52.dp
private val ROW_HEIGHT_SHORT = 44.dp
private val SIDE_PAD = 8.dp
private val MUTE_WIDTH = 30.dp
private val NAME_WIDTH = 84.dp
private val NAME_GAP = 6.dp

/** Channel rack: every channel with its row of steps (the heart of beat making). */
@Composable
fun RackTab(studio: Studio) {
    val c = Mps.colors
    val ui = studio.ui
    val p = studio.project
    val pattern = studio.currentPattern ?: return
    var patternSheet by remember { mutableStateOf(false) }
    var addSheet by remember { mutableStateOf(false) }
    var menuFor by remember { mutableStateOf<Int?>(null) }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val stepsWidth = maxWidth - SIDE_PAD * 2 - MUTE_WIDTH - NAME_WIDTH - NAME_GAP
        val stepsPerBar = p.stepsPerBar
        // as many whole bars as fit while the steps stay big enough to hit
        val barsVisible = max(1, min(pattern.bars, (stepsWidth / (stepsPerBar * 14).dp).toInt()))
        val pages = (pattern.bars + barsVisible - 1) / barsVisible
        val page = ui.rackPage.coerceIn(0, pages - 1)
        val firstStep = page * barsVisible * stepsPerBar
        val stepCount = barsVisible * stepsPerBar
        val following = studio.playing && !studio.songMode

        // Follow the play head from page to page, until the user picks a page or edits a step:
        // from then on the page stays put, so a bar can be worked on while the loop keeps running.
        var follow by remember { mutableStateOf(true) }
        LaunchedEffect(following) { if (following) follow = true }
        if (following && follow && pages > 1) {
            val ticksPerPage = p.ticksPerBar * barsVisible
            LaunchedEffect(pattern.id, ticksPerPage, pages) {
                snapshotFlow { (studio.playhead.doubleValue / ticksPerPage).toInt() }
                    .distinctUntilChanged()
                    .collect { ui.rackPage = it.coerceIn(0, pages - 1) }
            }
        }

        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier.fillMaxWidth().height(50.dp).padding(horizontal = SIDE_PAD), verticalAlignment = Alignment.CenterVertically,
            ) {
                Chip(pattern.name, { patternSheet = true }, dot = c.channel(pattern.color), trailing = Ic.DropDown, maxWidth = 180.dp)
                Spacer(Modifier.weight(1f))
                if (pages > 1) {
                    Segmented(
                        List(pages) { i ->
                            val from = i * barsVisible + 1
                            val to = min(pattern.bars, from + barsVisible - 1)
                            if (from == to) "$from" else "$from–$to"
                        },
                        page, {
                            ui.rackPage = it
                            follow = false
                        },
                    )
                }
            }
            if ("rack" !in studio.prefs.hintsSeen) {
                HintBanner(stringResource(R.string.hint_rack), { studio.prefs.markHintSeen("rack") }, Modifier.padding(horizontal = SIDE_PAD, vertical = 4.dp))
            }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                val listState = rememberLazyListState()
                LazyColumn(Modifier.fillMaxSize(), state = listState, contentPadding = PaddingValues(top = 2.dp, bottom = 16.dp)) {
                    items(p.channels, key = { it.id }) { ch ->
                        ChannelRow(
                            studio, ch, pattern.id, pattern.notesOf(ch.id), firstStep, stepCount,
                            selected = ch.id == studio.channelId, onEdit = { follow = false }, onMenu = {
                                studio.selectChannel(ch.id)
                                menuFor = ch.id
                            },
                        )
                    }
                    item(key = "add") {
                        Box(Modifier.fillMaxWidth().padding(horizontal = SIDE_PAD, vertical = 8.dp), contentAlignment = Alignment.Center) {
                            Chip(stringResource(R.string.add_channel), { addSheet = true }, icon = Ic.Add)
                        }
                    }
                }
                // the play head: one translucent column over all rows, redrawn without recomposition
                if (following) {
                    val bandColor = c.text.copy(alpha = if (c.dark) 0.22f else 0.14f)
                    val left = SIDE_PAD + MUTE_WIDTH + NAME_WIDTH + NAME_GAP
                    Canvas(Modifier.fillMaxSize()) {
                        val step = (studio.playhead.doubleValue / TICKS_PER_STEP).toInt() - firstStep
                        if (step in 0 until stepCount) {
                            val x0 = left.toPx()
                            val w = (size.width - x0 - SIDE_PAD.toPx()) / stepCount
                            // the band ends with the last channel, not at the bottom of the screen
                            val below = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.key == "add" }
                            val bottom = (below?.offset?.toFloat() ?: size.height).coerceIn(0f, size.height)
                            drawRoundRect(
                                bandColor, topLeft = Offset(x0 + step * w, 0f), size = Size(w, bottom),
                                cornerRadius = CornerRadius(4.dp.toPx()),
                            )
                        }
                    }
                }
            }
        }
    }

    if (patternSheet) PatternSheet(studio) { patternSheet = false }
    if (addSheet) AddChannelSheet(studio, onDismiss = { addSheet = false })
    menuFor?.let { id ->
        val ch = p.channel(id)
        if (ch == null) menuFor = null else ChannelMenu(studio, ch, pattern.id, onDismiss = { menuFor = null })
    }
}

@Composable
private fun ChannelRow(
    studio: Studio,
    ch: Channel,
    patternId: Int,
    notes: List<Note>,
    firstStep: Int,
    stepCount: Int,
    selected: Boolean,
    onEdit: () -> Unit,
    onMenu: () -> Unit,
) {
    val c = Mps.colors
    val color = c.channel(ch.color)
    val muteLabel = stringResource(R.string.mute)
    Row(
        Modifier.fillMaxWidth().height(if (isShortScreen()) ROW_HEIGHT_SHORT else ROW_HEIGHT).padding(horizontal = SIDE_PAD, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // "LED": lit = the channel sounds, dark = muted
        Box(
            Modifier
                .width(MUTE_WIDTH)
                .fillMaxHeight()
                .clip(RoundedCornerShape(10.dp))
                .clickable(role = Role.Switch) { studio.edit { it.updateChannel(ch.id) { x -> x.copy(mute = !x.mute) } } }
                .semantics {
                    contentDescription = muteLabel + ": " + ch.name
                    this.selected = ch.mute
                },
            contentAlignment = Alignment.Center,
        ) {
            Box(
                Modifier.size(14.dp).clip(CircleShape).background(if (ch.mute) Color.Transparent else color)
                    .border(2.dp, if (ch.mute) c.lineStrong else color, CircleShape)
            )
        }
        Row(
            Modifier
                .width(NAME_WIDTH)
                .fillMaxHeight()
                .clip(RoundedCornerShape(12.dp))
                .background(if (selected) c.accentSoft else c.surface)
                .border(1.dp, if (selected) c.accent else c.line, RoundedCornerShape(12.dp))
                .clickable(role = Role.Button, onClick = onMenu),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.padding(start = 5.dp).width(4.dp).height(26.dp).clip(RoundedCornerShape(2.dp)).background(color))
            Text(
                ch.name, Modifier.padding(start = 6.dp, end = 4.dp), color = if (ch.mute) c.textDim else c.text, fontSize = 12.sp,
                lineHeight = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(NAME_GAP))
        Box(Modifier.weight(1f).fillMaxHeight().padding(vertical = 2.dp)) {
            when {
                ch.isAudio -> Row(
                    Modifier.fillMaxSize().clip(RoundedCornerShape(10.dp)).background(c.surface)
                        .clickable {
                            studio.selectChannel(ch.id)
                            studio.ui.tab = Tab.PLAYLIST
                        }
                        .padding(horizontal = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Ic.Wave, null, Modifier.size(16.dp), tint = color)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.audio_row_hint), color = c.textDim, fontSize = 11.sp, lineHeight = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                // rows whose notes all sit on the step note are shown as steps, melodies as a preview
                ch.isDrum || notes.all { it.pitch == ch.rootNote } ->
                    StepRow(studio, ch, patternId, notes, firstStep, stepCount, color, dim = ch.mute, onEdit = onEdit)
                else -> MelodyPreview(notes, firstStep, stepCount, color, stringResource(R.string.melody_preview) + ": " + ch.name) {
                    studio.selectChannel(ch.id)
                    studio.ui.tab = Tab.PIANO
                }
            }
        }
    }
}

/** One channel's steps. Tap toggles a step, sliding sideways paints several. */
@Composable
private fun StepRow(
    studio: Studio,
    ch: Channel,
    patternId: Int,
    notes: List<Note>,
    firstStep: Int,
    stepCount: Int,
    color: Color,
    dim: Boolean,
    onEdit: () -> Unit,
) {
    val c = Mps.colors
    val latestNotes by rememberUpdatedState(notes)
    val latestChannel by rememberUpdatedState(ch)
    val latestOnEdit by rememberUpdatedState(onEdit)
    // what each visible step holds: 0 = nothing, otherwise the velocity of its first note
    val velocities = remember(notes, firstStep, stepCount) {
        FloatArray(stepCount).also { out ->
            for (n in notes) {
                val i = n.start / TICKS_PER_STEP - firstStep
                if (i in 0 until stepCount && out[i] == 0f) out[i] = n.velocity.coerceAtLeast(0.05f)
            }
        }
    }
    val rolls = remember(notes, firstStep, stepCount) {
        IntArray(stepCount).also { out ->
            for (n in notes) {
                val i = n.start / TICKS_PER_STEP - firstStep
                if (i in 0 until stepCount) out[i]++
            }
        }
    }
    val active = remember(velocities) { velocities.count { it > 0f } }
    val stepA = c.stepA
    val stepB = c.stepB
    val markColor = if (c.dark) Color.Black else Color.White
    Canvas(
        Modifier
            .fillMaxSize()
            .semantics {
                contentDescription = ch.name
                stateDescription = "$active / $stepCount"
            }
            .pointerInput(patternId, ch.id, firstStep, stepCount) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val slop = viewConfiguration.touchSlop
                    val stepW = size.width.toFloat() / stepCount
                    val first = (down.position.x / stepW).toInt().coerceIn(0, stepCount - 1)
                    val turnOn = !stepActive(latestNotes, firstStep + first)
                    val channel = latestChannel

                    fun set(index: Int, key: String?) {
                        latestOnEdit()
                        studio.edit(key) { it.setStep(patternId, channel.id, firstStep + index, turnOn) }
                    }

                    var mode = 0 // 0 undecided, 1 painting, 2 vertical scroll (not ours)
                    var last = first
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) {
                            if (mode == 0) {
                                studio.selectChannel(channel.id)
                                set(first, null)
                                if (turnOn && !studio.playing) studio.preview(channel.id, channel.rootNote)
                                change.consume()
                            }
                            break
                        }
                        val dx = change.position.x - down.position.x
                        val dy = change.position.y - down.position.y
                        if (mode == 0) {
                            if (abs(dx) > slop && abs(dx) > abs(dy)) {
                                mode = 1
                                studio.selectChannel(channel.id)
                                set(first, "paint")
                            } else if (abs(dy) > slop) {
                                mode = 2
                            }
                        }
                        if (mode == 1) {
                            val s = (change.position.x / stepW).toInt().coerceIn(0, stepCount - 1)
                            if (s != last) {
                                for (i in min(last, s)..max(last, s)) set(i, "paint")
                                last = s
                            }
                            change.consume()
                        } else if (mode == 2) break
                    }
                    if (mode == 1) studio.commit()
                }
            },
    ) {
        val w = size.width / stepCount
        val gap = 2.dp.toPx()
        val radius = CornerRadius(4.dp.toPx())
        val alpha = if (dim) 0.4f else 1f
        for (i in 0 until stepCount) {
            val v = velocities[i]
            val beat = ((firstStep + i) / 4) % 2 == 0
            val col = if (v > 0f) color.copy(alpha = (0.5f + 0.5f * v) * alpha) else if (beat) stepA else stepB
            drawRoundRect(col, topLeft = Offset(i * w + gap / 2, 0f), size = Size(w - gap, size.height), cornerRadius = radius)
            if (rolls[i] > 1) {
                // several notes inside one step (a roll): mark it with small dots
                val n = min(rolls[i], 4)
                val r = min(w * 0.11f, 2.2.dp.toPx())
                for (k in 0 until n) {
                    drawCircle(markColor, r, Offset(i * w + w / 2, size.height * (0.25f + 0.5f * (k + 0.5f) / n)))
                }
            }
        }
    }
}

/** Miniature of a melody; opens the piano roll. */
@Composable
private fun MelodyPreview(notes: List<Note>, firstStep: Int, stepCount: Int, color: Color, description: String, onClick: () -> Unit) {
    val c = Mps.colors
    val low = remember(notes) { notes.minOfOrNull { it.pitch } ?: 60 }
    val high = remember(notes) { notes.maxOfOrNull { it.pitch } ?: 60 }
    Canvas(
        Modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(10.dp))
            .background(c.surface)
            .border(1.dp, c.line, RoundedCornerShape(10.dp))
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = description },
    ) {
        val from = firstStep * TICKS_PER_STEP
        val span = (stepCount * TICKS_PER_STEP).toFloat()
        val range = max(6, high - low + 1)
        val pad = 5.dp.toPx()
        val h = max(2.dp.toPx(), (size.height - pad * 2) / range)
        for (n in notes) {
            val x0 = (n.start - from) / span * size.width
            val x1 = (n.end - from) / span * size.width
            if (x1 < 0f || x0 > size.width) continue
            val y = size.height - pad - (n.pitch - low + 1) * (size.height - pad * 2) / range
            drawRoundRect(
                color.copy(alpha = 0.5f + 0.5f * n.velocity), topLeft = Offset(max(0f, x0), y),
                size = Size(max(2.dp.toPx(), min(size.width, x1) - max(0f, x0) - 1.dp.toPx()), h),
                cornerRadius = CornerRadius(h / 2),
            )
        }
    }
}

/** Everything that can be done with a channel. */
@Composable
private fun ChannelMenu(studio: Studio, ch: Channel, patternId: Int, onDismiss: () -> Unit) {
    val c = Mps.colors
    val ui = studio.ui
    var rename by remember { mutableStateOf(false) }
    var delete by remember { mutableStateOf(false) }
    val index = studio.project.channels.indexOfFirst { it.id == ch.id }
    val stepsInPattern = studio.project.pattern(patternId)?.let { it.bars * studio.project.stepsPerBar } ?: 16

    if (!rename && !delete) {
        AppSheet(onDismiss, title = ch.name) {
            SheetItem(stringResource(R.string.ch_sound), {
                onDismiss()
                ui.editorTab = 0
                ui.editor = ch.id
            }, icon = Ic.Tune)
            if (!ch.isAudio) {
                SheetItem(stringResource(R.string.ch_open_notes), {
                    onDismiss()
                    ui.tab = Tab.PIANO
                }, icon = Ic.Roll)
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Ic.Palette, null, Modifier.size(22.dp), tint = c.text)
                Spacer(Modifier.width(16.dp))
                Row(Modifier.weight(1f), horizontalArrangement = Arrangement.SpaceBetween) {
                    val label = stringResource(R.string.ch_color)
                    for (i in c.channels.indices) {
                        Box(
                            Modifier
                                .size(30.dp)
                                .clip(CircleShape)
                                .background(c.channels[i])
                                .border(if (i == ch.color) 3.dp else 0.dp, if (i == ch.color) c.text else Color.Transparent, CircleShape)
                                .clickable(role = Role.RadioButton) { studio.edit { it.updateChannel(ch.id) { x -> x.copy(color = i) } } }
                                .semantics {
                                    contentDescription = "$label ${i + 1}"
                                    selected = i == ch.color
                                },
                        )
                    }
                }
            }
            SheetItem(stringResource(R.string.ch_rename), { rename = true }, icon = Ic.Rename)
            val copyName = stringResource(R.string.copy_of, ch.name)
            SheetItem(stringResource(R.string.ch_duplicate), {
                onDismiss()
                studio.edit { it.duplicateChannel(ch.id, copyName).first }
            }, icon = Ic.Copy)
            if (!ch.isAudio) {
                SheetDivider()
                val fill = { every: Int ->
                    onDismiss()
                    studio.edit { p ->
                        p.setNotes(patternId, ch.id, List((stepsInPattern + every - 1) / every) { i ->
                            Note(i * every * TICKS_PER_STEP, TICKS_PER_STEP, ch.rootNote, 0.8f)
                        })
                    }
                }
                SheetItem(stringResource(R.string.ch_fill_2), { fill(2) }, icon = Ic.Grid)
                SheetItem(stringResource(R.string.ch_fill_4), { fill(4) }, icon = Ic.Grid)
                SheetItem(stringResource(R.string.ch_shift_left), { studio.edit { it.shiftNotes(patternId, ch.id, -TICKS_PER_STEP) } }, icon = Ic.ArrowLeft)
                SheetItem(stringResource(R.string.ch_shift_right), { studio.edit { it.shiftNotes(patternId, ch.id, TICKS_PER_STEP) } }, icon = Ic.ArrowRight)
                SheetItem(stringResource(R.string.ch_clear), {
                    onDismiss()
                    studio.edit { it.setNotes(patternId, ch.id, emptyList()) }
                }, icon = Ic.Clear)
            }
            SheetDivider()
            SheetItem(stringResource(R.string.ch_move_up), { studio.edit { it.moveChannel(ch.id, -1) } }, icon = Ic.ArrowUp, enabled = index > 0)
            SheetItem(stringResource(R.string.ch_move_down), { studio.edit { it.moveChannel(ch.id, 1) } }, icon = Ic.ArrowDown, enabled = index < studio.project.channels.lastIndex)
            SheetItem(stringResource(R.string.ch_delete), { delete = true }, icon = Ic.Delete, danger = true)
        }
    }
    if (rename) {
        TextInputDialog(
            stringResource(R.string.ch_rename), ch.name,
            onConfirm = { name ->
                studio.edit { it.updateChannel(ch.id) { x -> x.copy(name = name) } }
                onDismiss()
            },
            onDismiss = onDismiss, maxLength = 24,
        )
    }
    if (delete) {
        ConfirmDialog(
            stringResource(R.string.delete_channel_title), stringResource(R.string.delete_channel_text, ch.name), stringResource(R.string.delete),
            onConfirm = {
                studio.edit { it.removeChannel(ch.id) }
                onDismiss()
            },
            onDismiss = onDismiss,
        )
    }
}
