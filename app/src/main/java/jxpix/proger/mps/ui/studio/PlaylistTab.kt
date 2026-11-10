package jxpix.proger.mps.ui.studio

import android.graphics.Paint
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.rememberSplineBasedDecay
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import jxpix.proger.mps.R
import jxpix.proger.mps.engine.PPQ
import jxpix.proger.mps.engine.TICKS_PER_STEP
import jxpix.proger.mps.model.Clip
import jxpix.proger.mps.model.MAX_TRACKS
import jxpix.proger.mps.model.Project
import jxpix.proger.mps.model.addPatternClip
import jxpix.proger.mps.model.duplicateClip
import jxpix.proger.mps.model.removeClip
import jxpix.proger.mps.model.setTrackCount
import jxpix.proger.mps.model.splitClip
import jxpix.proger.mps.model.updateClip
import jxpix.proger.mps.studio.Studio
import jxpix.proger.mps.studio.Tab
import jxpix.proger.mps.ui.Fmt
import jxpix.proger.mps.ui.Ic
import jxpix.proger.mps.ui.SwitchRow
import jxpix.proger.mps.ui.components.AppSheet
import jxpix.proger.mps.ui.components.Chip
import jxpix.proger.mps.ui.components.HintBanner
import jxpix.proger.mps.ui.components.IconBtn
import jxpix.proger.mps.ui.components.Segmented
import jxpix.proger.mps.ui.components.SheetDivider
import jxpix.proger.mps.ui.components.SheetItem
import jxpix.proger.mps.ui.theme.Mps
import jxpix.proger.mps.ui.theme.NumberStyle
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

private val HEADER_WIDTH = 30.dp
private val RULER_HEIGHT = 26.dp
private val TRACK_HEIGHT = 58.dp

/** What a pattern clip shows inside: one thin lane per channel that has notes. */
private class PatternPreview(val lanes: Int, val marks: FloatArray)

private fun buildPreviews(p: Project): Map<Int, PatternPreview> {
    val out = HashMap<Int, PatternPreview>()
    for (pattern in p.patterns) {
        val length = p.patternLength(pattern).toFloat()
        val marks = ArrayList<Float>()
        var lane = 0
        for (ch in p.channels) {
            val notes = pattern.notes[ch.id]
            if (notes.isNullOrEmpty()) continue
            for (n in notes) {
                if (n.start >= length) continue
                marks += lane.toFloat()
                marks += n.start / length
                marks += min(n.length, (length - n.start).toInt()) / length
            }
            lane++
        }
        out[pattern.id] = PatternPreview(lane, marks.toFloatArray())
    }
    return out
}

private fun snapTicks(p: Project, mode: Int): Int = when (mode) {
    1 -> PPQ
    2 -> TICKS_PER_STEP
    else -> p.ticksPerBar
}

/** Playlist: patterns and recordings arranged on a timeline to form the whole track. */
@Composable
fun PlaylistTab(studio: Studio, onRecordMic: () -> Unit) {
    val c = Mps.colors
    val ui = studio.ui
    val p = studio.project
    val pattern = studio.currentPattern
    var patternSheet by remember { mutableStateOf(false) }
    var audioSheet by remember { mutableStateOf(false) }
    var moreSheet by remember { mutableStateOf(false) }
    val selected = p.clip(ui.selectedClip)
    val res = LocalResources.current

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) studio.importToPlaylist(uri)
    }
    val snapNames = listOf(stringResource(R.string.snap_bar), stringResource(R.string.snap_beat), stringResource(R.string.snap_step))

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().height(50.dp).padding(start = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                Chip(
                    pattern?.name ?: "—", { patternSheet = true }, Modifier.weight(1f, fill = false),
                    dot = pattern?.let { c.channel(it.color) }, trailing = Ic.DropDown, maxWidth = 170.dp,
                )
            }
            Spacer(Modifier.width(6.dp))
            Chip(snapNames[ui.playlistSnap.coerceIn(0, 2)], { ui.playlistSnap = (ui.playlistSnap + 1) % 3 }, icon = Ic.Grid)
            Spacer(Modifier.width(6.dp))
            Chip(stringResource(R.string.add_audio), { audioSheet = true }, icon = Ic.Mic)
            IconBtn(Ic.More, stringResource(R.string.more), { moreSheet = true })
        }
        if ("playlist" !in studio.prefs.hintsSeen) {
            HintBanner(stringResource(R.string.hint_playlist), { studio.prefs.markHintSeen("playlist") }, Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            Timeline(studio, Modifier.fillMaxSize())
            if (p.clips.isEmpty() && pattern != null) {
                Text(
                    stringResource(R.string.playlist_empty, pattern.name), Modifier.align(Alignment.Center).padding(start = 48.dp, end = 24.dp),
                    color = c.textDim, fontSize = 14.sp, lineHeight = 20.sp, textAlign = TextAlign.Center,
                )
            }
            if (studio.loadingSamples > 0) {
                Text(
                    stringResource(R.string.loading_samples), Modifier.align(Alignment.BottomCenter).padding(10.dp), color = c.textDim,
                    fontSize = 12.sp,
                )
            }
        }
        if (selected != null) {
            ClipBar(studio, selected)
        }
    }

    if (patternSheet) PatternSheet(studio) { patternSheet = false }
    if (audioSheet) {
        AppSheet({ audioSheet = false }, title = stringResource(R.string.add_audio)) {
            SheetItem(stringResource(R.string.record_mic), {
                audioSheet = false
                onRecordMic()
            }, icon = Ic.Mic, description = stringResource(R.string.rec_audio_desc))
            SheetItem(stringResource(R.string.import_audio), {
                audioSheet = false
                try {
                    picker.launch(arrayOf("audio/*"))
                } catch (_: Exception) {
                    studio.message = res.getString(R.string.msg_import_failed)
                }
            }, icon = Ic.AudioFile)
        }
    }
    if (moreSheet) {
        val view = ui.playlist
        AppSheet({ moreSheet = false }, title = stringResource(R.string.tab_playlist)) {
            Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.tracks_count, p.tracks), Modifier.weight(1f), color = c.text, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                val lastUsed = p.clips.any { it.track == p.tracks - 1 }
                IconBtn(
                    Ic.Remove, stringResource(R.string.remove_track), { studio.edit { it.setTrackCount(it.tracks - 1) } },
                    enabled = p.tracks > 1 && !lastUsed, background = c.surfaceHigh, size = 40.dp,
                )
                Spacer(Modifier.width(8.dp))
                IconBtn(
                    Ic.Add, stringResource(R.string.add_track), { studio.edit { it.setTrackCount(it.tracks + 1) } },
                    enabled = p.tracks < MAX_TRACKS, background = c.surfaceHigh, size = 40.dp,
                )
            }
            SheetDivider()
            SwitchRow(stringResource(R.string.loop_song), null, p.loopEnabled) { on ->
                studio.edit { q ->
                    if (on && q.loopEnd <= q.loopStart) q.copy(loopEnabled = true, loopStart = 0, loopEnd = max(1, min(4, q.songBars)))
                    else q.copy(loopEnabled = on)
                }
            }
            if (p.loopEnabled) {
                BarStepper(stringResource(R.string.loop_from), p.loopStart + 1, 1, p.loopEnd) { v -> studio.edit("loop") { it.copy(loopStart = v - 1) } }
                BarStepper(stringResource(R.string.loop_to), p.loopEnd, p.loopStart + 1, 999) { v -> studio.edit("loop") { it.copy(loopEnd = v) } }
            }
            SheetDivider()
            SheetItem(stringResource(R.string.zoom_in), { view.pxPerTick *= 1.5f }, icon = Ic.ZoomIn)
            SheetItem(stringResource(R.string.zoom_out), { view.pxPerTick /= 1.5f }, icon = Ic.ZoomOut)
        }
    }
}

@Composable
private fun BarStepper(label: String, value: Int, min: Int, max: Int, onChange: (Int) -> Unit) {
    val c = Mps.colors
    Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, top = 2.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), color = c.textDim, fontSize = 14.sp)
        IconBtn(Ic.Remove, "$label −", { onChange(value - 1) }, enabled = value > min, background = c.surfaceHigh, size = 40.dp)
        Text("$value", Modifier.width(52.dp), color = c.text, fontSize = 16.sp, style = NumberStyle, textAlign = TextAlign.Center)
        IconBtn(Ic.Add, "$label +", { onChange(value + 1) }, enabled = value < max, background = c.surfaceHigh, size = 40.dp)
    }
}

/** Tools for the selected clip. */
@Composable
private fun ClipBar(studio: Studio, clip: Clip) {
    val c = Mps.colors
    val ui = studio.ui
    val p = studio.project
    val res = LocalResources.current
    val title = if (clip.isAudio) (clip.sample ?: "").substringBeforeLast('.') else p.pattern(clip.patternId)?.name ?: "—"
    Column(Modifier.fillMaxWidth().background(c.surface)) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(c.line))
        if (clip.isAudio) {
            Row(Modifier.fillMaxWidth().height(44.dp).padding(start = 14.dp, end = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                val label = stringResource(R.string.clip_gain)
                Text(label, color = c.textDim, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                Slider(
                    value = clip.gain.coerceIn(0f, 2f), onValueChange = { v -> studio.edit("clip-gain") { it.updateClip(clip.id) { x -> x.copy(gain = v) } } },
                    valueRange = 0f..2f, onValueChangeFinished = { studio.commit() }, colors = sliderColors(),
                    modifier = Modifier.weight(1f).padding(horizontal = 10.dp).semantics { contentDescription = label },
                )
                Text(
                    if (clip.gain <= 0.001f) res.getString(R.string.db_silence) else Fmt.db(20f * log10(clip.gain), res), Modifier.width(64.dp),
                    color = c.text, fontSize = 13.sp, style = NumberStyle, textAlign = TextAlign.End,
                )
            }
        }
        Row(Modifier.fillMaxWidth().height(48.dp).padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                title, Modifier.padding(start = 8.dp).weight(1f), color = c.text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            if (!clip.isAudio) {
                IconBtn(Ic.Roll, stringResource(R.string.edit_pattern), {
                    studio.selectPattern(clip.patternId)
                    ui.tab = Tab.RACK
                }, size = 42.dp)
            }
            IconBtn(Ic.Copy, stringResource(R.string.duplicate), {
                var id = 0
                studio.edit { q -> q.duplicateClip(clip.id).also { id = it.second }.first }
                if (id != 0) ui.selectedClip = id
            }, size = 42.dp)
            val at = studio.cursor
            IconBtn(
                Ic.Cut, stringResource(R.string.split_clip), { studio.edit { it.splitClip(clip.id, at) } }, size = 42.dp,
                enabled = studio.songMode && at > clip.start && at < clip.end,
            )
            IconBtn(
                if (clip.muted) Ic.VolumeOff else Ic.VolumeUp, stringResource(if (clip.muted) R.string.unmute_clip else R.string.mute_clip),
                { studio.edit { it.updateClip(clip.id) { x -> x.copy(muted = !x.muted) } } }, size = 42.dp,
                tint = if (clip.muted) c.textDim else c.text,
            )
            IconBtn(Ic.Delete, stringResource(R.string.delete), {
                studio.edit { it.removeClip(clip.id) }
                ui.selectedClip = 0
            }, size = 42.dp, tint = c.record)
            IconBtn(Ic.Close, stringResource(R.string.deselect), { ui.selectedClip = 0 }, size = 42.dp, tint = c.textDim)
        }
    }
}

@Composable
private fun Timeline(studio: Studio, modifier: Modifier) {
    val c = Mps.colors
    val ui = studio.ui
    val view = ui.playlist
    val p = studio.project
    val density = LocalDensity.current
    val dp = density.density
    val headerW = with(density) { HEADER_WIDTH.toPx() }
    val rulerH = with(density) { RULER_HEIGHT.toPx() }
    val trackH = with(density) { TRACK_HEIGHT.toPx() }
    val ticksPerBar = p.ticksPerBar
    val res = LocalResources.current

    val project by rememberUpdatedState(p)
    val previews = remember(p.patterns, p.channels) { buildPreviews(p) }
    val samplesVersion = studio.samplesVersion
    val songMode = studio.songMode
    val labelPaint = remember { Paint(Paint.ANTI_ALIAS_FLAG) }
    val description = stringResource(R.string.tab_playlist)
    val noSpace = stringResource(R.string.no_free_track)
    val seekHint = stringResource(R.string.seek_hint)

    BoxWithConstraints(modifier) {
        val gridW = (constraints.maxWidth - headerW).coerceAtLeast(1f)
        val gridH = (constraints.maxHeight - rulerH).coerceAtLeast(1f)
        ui.playlistGridW = gridW
        ui.playlistGridH = gridH
        val maxPx = gridW / (PPQ * 2f)

        // total length of the timeline: the song plus room to grow
        val contentTicks = remember(view, ui) {
            { max(project.songEnd + project.ticksPerBar * 8, project.ticksPerBar * 16) }
        }
        val minPx = remember(view, ui) {
            { ui.playlistGridW / (max(64, project.songBars + 16) * project.ticksPerBar).toFloat() }
        }
        val maxScrollX = remember(view, ui) {
            { max(0f, contentTicks() * view.pxPerTick - ui.playlistGridW) }
        }
        val maxScrollY = remember(view, ui) {
            { max(0f, project.tracks * trackH + 12f * dp - ui.playlistGridH) }
        }
        if (view.pxPerTick <= 0f) view.pxPerTick = gridW / (ticksPerBar * 8f)
        if (view.pxPerTick < minPx() || view.pxPerTick > maxPx) view.pxPerTick = view.pxPerTick.coerceIn(minPx(), maxPx)
        if (view.scrollY < 0f) view.scrollY = 0f

        val scope = rememberCoroutineScope()
        val decay = rememberSplineBasedDecay<Float>()
        val panZoom = remember(scope, decay) {
            PanZoom(
                scope, decay,
                onPan = { dx, dy ->
                    view.scrollX = (view.scrollX + dx).coerceIn(0f, maxScrollX())
                    view.scrollY = (view.scrollY + dy).coerceIn(0f, maxScrollY())
                },
                onZoom = { factor, centroid ->
                    val cx = centroid.x - headerW
                    val tick = (view.scrollX + cx) / view.pxPerTick
                    val px = (view.pxPerTick * factor).coerceIn(minPx(), maxPx)
                    view.pxPerTick = px
                    view.scrollX = max(0f, tick * px - cx)
                },
            )
        }

        // keep the play head on screen while the song plays
        if (studio.playing && songMode) {
            LaunchedEffect(Unit) {
                snapshotFlow { studio.playhead.doubleValue.toFloat() * view.pxPerTick }.collect { x ->
                    if (!panZoom.touching) {
                        val rel = x - view.scrollX
                        val w = ui.playlistGridW
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
                .pointerInput(Unit) {
                    val slop = viewConfiguration.touchSlop
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        panZoom.begin()
                        try {
                            val pr = project
                            val x = down.position.x
                            val y = down.position.y
                            val px = view.pxPerTick
                            val sx = view.scrollX.coerceIn(0f, maxScrollX())
                            val sy = view.scrollY.coerceIn(0f, maxScrollY())
                            val snap = snapTicks(pr, ui.playlistSnap)

                            if (y < rulerH && x >= headerW) {
                                // ---- ruler: move the play head
                                fun seekTo(px0: Float) {
                                    val tick = ((px0 - headerW + sx) / px / snap).roundToInt() * snap
                                    studio.seek(tick)
                                }
                                if (!studio.songMode) {
                                    studio.changeSongMode(true)
                                    studio.message = res.getString(R.string.mode_song_hint)
                                } else if (!studio.playing && "seek" !in studio.prefs.hintsSeen) {
                                    studio.prefs.markHintSeen("seek")
                                    studio.message = seekHint
                                }
                                seekTo(x)
                                while (true) {
                                    val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                                    if (!change.pressed) break
                                    change.consume()
                                    seekTo(change.position.x)
                                }
                                return@awaitEachGesture
                            }

                            // ---- which clip is under the finger?
                            var hit: Clip? = null
                            var resize = false
                            if (x >= headerW && y >= rulerH) {
                                val tick = (x - headerW + sx) / px
                                val track = floor((y - rulerH + sy) / trackH).toInt()
                                for (i in pr.clips.indices.reversed()) {
                                    val clip = pr.clips[i]
                                    if (clip.track == track && tick >= clip.start && tick < clip.end) {
                                        hit = clip
                                        val w = clip.length * px
                                        resize = w >= 30f * dp && (clip.end - tick) * px <= min(24f * dp, w * 0.3f)
                                        break
                                    }
                                }
                            }

                            val target = hit
                            if (target != null) {
                                // ---- move or stretch a clip
                                var moved = false
                                var lastTicks = 0
                                var lastTrack = target.track
                                // an audio clip cannot be longer than its recording
                                val maxLength = if (target.isAudio) {
                                    val data = studio.samples[target.sample]
                                    if (data == null) Int.MAX_VALUE
                                    else max(1.0, (data.durationSeconds - target.offset) / pr.secondsPerTick).toInt()
                                } else Int.MAX_VALUE
                                while (true) {
                                    val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                                    if (!change.pressed) break
                                    val dx = change.position.x - x
                                    val dy = change.position.y - y
                                    if (!moved && hypot(dx, dy) > slop) {
                                        moved = true
                                        ui.selectedClip = target.id
                                    }
                                    if (!moved) continue
                                    change.consume()
                                    var dTicks = (dx / px / snap).roundToInt() * snap
                                    if (resize) {
                                        if (dTicks != lastTicks) {
                                            lastTicks = dTicks
                                            val length = max(min(target.length, snap), target.length + dTicks).coerceAtMost(maxLength)
                                            studio.edit("clip-drag") { it.updateClip(target.id) { cl -> cl.copy(length = length) } }
                                        }
                                    } else {
                                        while (target.start + dTicks < 0) dTicks += snap
                                        val track = (target.track + (dy / trackH).roundToInt()).coerceIn(0, pr.tracks - 1)
                                        if (dTicks != lastTicks || track != lastTrack) {
                                            lastTicks = dTicks
                                            lastTrack = track
                                            studio.edit("clip-drag") { it.updateClip(target.id) { cl -> cl.copy(start = target.start + dTicks, track = track) } }
                                        }
                                    }
                                }
                                if (moved) studio.commit()
                                else ui.selectedClip = if (ui.selectedClip == target.id) 0 else target.id
                            } else {
                                // ---- empty space: drag to scroll, pinch to zoom, tap to place the pattern
                                val tap = with(panZoom) { track(down, slop) }
                                if (tap && x >= headerW && y >= rulerH) {
                                    val track = floor((y - rulerH + sy) / trackH).toInt()
                                    val pattern = studio.currentPattern
                                    if (ui.selectedClip != 0) {
                                        ui.selectedClip = 0
                                    } else if (pattern != null && track in 0 until pr.tracks) {
                                        val tick = (x - headerW + sx) / px
                                        var start = floor(tick / snap).toInt() * snap
                                        val lane = pr.clips.filter { it.track == track }
                                        // the grid cell may begin inside the previous clip: start right after it
                                        lane.filter { it.start <= start && it.end > start }.maxOfOrNull { it.end }?.let { start = it }
                                        val next = lane.filter { it.start >= start }.minOfOrNull { it.start }
                                        var length = pr.patternLength(pattern)
                                        if (next != null) length = min(length, next - start)
                                        if (length <= 0 || start > tick) {
                                            studio.message = noSpace
                                        } else {
                                            val first = pr.clips.isEmpty()
                                            studio.edit { it.addPatternClip(pattern.id, track, start, length).first }
                                            if (first && !studio.songMode) {
                                                studio.changeSongMode(true)
                                                studio.message = res.getString(R.string.mode_song_hint)
                                            }
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
            @Suppress("UNUSED_EXPRESSION") samplesVersion
            val px = view.pxPerTick
            val sx = view.scrollX.coerceIn(0f, maxScrollX())
            val sy = view.scrollY.coerceIn(0f, maxScrollY())
            val right = size.width
            val bottom = size.height
            val thin = 1f * dp
            val barPx = ticksPerBar * px
            val selectedId = ui.selectedClip

            clipRect(headerW, rulerH, right, bottom) {
                // lanes
                for (t in 0 until p.tracks) {
                    val y = rulerH + t * trackH - sy
                    if (y > bottom || y + trackH < rulerH) continue
                    drawRect(if (t % 2 == 0) c.rowWhite else c.rowBlack, Offset(headerW, y), Size(gridW, trackH))
                    drawLine(c.line, Offset(headerW, y + trackH), Offset(right, y + trackH), thin)
                }
                val lanesBottom = min(bottom, rulerH + p.tracks * trackH - sy)
                // bar and beat lines
                val beatPx = PPQ * px
                var bar = floor(sx / barPx).toInt().coerceAtLeast(0)
                while (bar * barPx - sx <= gridW) {
                    val x = headerW + bar * barPx - sx
                    drawLine(if (bar % 4 == 0) c.lineStrong else c.line, Offset(x, rulerH), Offset(x, lanesBottom), if (bar % 4 == 0) 1.5f * dp else thin)
                    if (beatPx >= 14f * dp) {
                        for (beat in 1 until p.beatsPerBar) {
                            val bx = x + beat * beatPx
                            drawLine(c.line.copy(alpha = 0.6f), Offset(bx, rulerH), Offset(bx, lanesBottom), thin)
                        }
                    }
                    bar++
                }

                // clips
                labelPaint.textSize = 11f * dp * fontScale
                labelPaint.textAlign = Paint.Align.LEFT
                labelPaint.isFakeBoldText = true
                val labelH = 16f * dp
                val radius = CornerRadius(6f * dp)
                val canvas = drawContext.canvas.nativeCanvas
                for (clip in p.clips) {
                    val x0 = headerW + clip.start * px - sx
                    val x1 = x0 + clip.length * px
                    if (x1 < headerW || x0 > right) continue
                    val y0 = rulerH + clip.track * trackH - sy + 2f * dp
                    val y1 = y0 + trackH - 4f * dp
                    if (y1 < rulerH || y0 > bottom) continue
                    val w = max(4f * dp, x1 - x0 - thin)
                    val alpha = if (clip.muted) 0.35f else 1f
                    val pattern = if (clip.isAudio) null else p.pattern(clip.patternId)
                    val base = if (clip.isAudio) c.channel(p.channel(clip.channelId)?.color ?: 3) else c.channel(pattern?.color ?: 0)
                    drawRoundRect(base.copy(alpha = (if (c.dark) 0.30f else 0.22f) * alpha), Offset(x0, y0), Size(w, y1 - y0), radius)
                    drawRoundRect(base.copy(alpha = alpha), Offset(x0, y0), Size(w, labelH), radius)
                    drawRect(base.copy(alpha = alpha), Offset(x0, y0 + labelH / 2), Size(w, labelH / 2))

                    val visL = max(x0, headerW)
                    val visR = min(x0 + w, right)
                    clipRect(visL, y0, visR, y1) {
                        val bodyTop = y0 + labelH + 2f * dp
                        val bodyH = y1 - bodyTop - 2f * dp
                        val ink = base.copy(alpha = alpha)
                        if (clip.isAudio) {
                            // waveform
                            val data = studio.samples[clip.sample]
                            val overview = data?.overview
                            if (data != null && overview != null && overview.isNotEmpty()) {
                                val secPerPx = (p.secondsPerTick / px).toFloat()
                                val duration = data.durationSeconds
                                val stepPx = 2f * dp
                                val mid = bodyTop + bodyH / 2
                                var xi = visL
                                while (xi < visR) {
                                    val t0 = clip.offset + (xi - x0) * secPerPx
                                    if (t0 >= duration) break
                                    val i0 = (t0 / duration * overview.size).toInt().coerceIn(0, overview.size - 1)
                                    val i1 = ((t0 + stepPx * secPerPx) / duration * overview.size).toInt().coerceIn(i0, overview.size - 1)
                                    var peak = 0f
                                    for (i in i0..i1) if (overview[i] > peak) peak = overview[i]
                                    val half = max(0.5f * dp, min(1f, peak * clip.gain) * bodyH / 2)
                                    drawLine(ink, Offset(xi, mid - half), Offset(xi, mid + half), stepPx * 0.6f)
                                    xi += stepPx
                                }
                            }
                        } else if (pattern != null) {
                            // the notes of the pattern, repeated for as long as the clip lasts
                            val preview = previews[pattern.id]
                            val patPx = p.patternLength(pattern) * px
                            if (preview != null && preview.lanes > 0 && patPx > 0f) {
                                val laneH = min(7f * dp, bodyH / preview.lanes)
                                val markH = max(1.5f * dp, laneH - 1.5f * dp)
                                val top = bodyTop + (bodyH - laneH * preview.lanes) / 2
                                val marks = preview.marks
                                val reps = ceil((x1 - x0) / patPx).toInt()
                                for (rep in 0 until reps) {
                                    val bx = x0 + rep * patPx
                                    if (bx > visR) break
                                    if (bx + patPx < visL) continue
                                    if (rep > 0) drawLine(ink.copy(alpha = 0.55f * alpha), Offset(bx, y0 + labelH), Offset(bx, y1), thin)
                                    var i = 0
                                    while (i < marks.size) {
                                        val nx = bx + marks[i + 1] * patPx
                                        val nw = max(1.5f * dp, marks[i + 2] * patPx - thin)
                                        if (nx < visR && nx + nw > visL) {
                                            drawRect(ink, Offset(nx, top + marks[i] * laneH), Size(nw, markH))
                                        }
                                        i += 3
                                    }
                                }
                            }
                        }
                        labelPaint.color = Color.White.copy(alpha = alpha).toArgb()
                        val name = if (clip.isAudio) (clip.sample ?: "").substringBeforeLast('.') else pattern?.name ?: "?"
                        canvas.drawText(name, max(x0, headerW) + 6f * dp, y0 + labelH * 0.76f, labelPaint)
                    }
                    if (clip.id == selectedId) {
                        drawRoundRect(c.text, Offset(x0, y0), Size(w, y1 - y0), radius, style = Stroke(2f * dp))
                        if (w >= 30f * dp) {
                            val gx = x0 + w - 6f * dp
                            drawLine(c.text, Offset(gx, y0 + (y1 - y0) * 0.35f), Offset(gx, y0 + (y1 - y0) * 0.65f), 2f * dp)
                        }
                    }
                }
            }

            // ruler
            drawRect(c.surface, Offset(headerW, 0f), Size(gridW, rulerH))
            clipRect(headerW, 0f, right, rulerH) {
                if (p.loopEnabled && p.loopEnd > p.loopStart) {
                    val lx = headerW + p.loopStart * barPx - sx
                    drawRect(c.accent.copy(alpha = 0.3f), Offset(lx, 0f), Size((p.loopEnd - p.loopStart) * barPx, rulerH))
                }
                labelPaint.color = c.textDim.toArgb()
                labelPaint.textSize = 11f * dp * fontScale
                labelPaint.isFakeBoldText = true
                // label every bar when there is room, otherwise every 2nd / 4th / 8th...
                var every = 1
                while (every * barPx < 30f * dp) every *= 2
                var bar = (floor(sx / barPx).toInt() / every * every).coerceAtLeast(0)
                while (bar * barPx - sx <= gridW) {
                    val x = headerW + bar * barPx - sx
                    drawLine(c.lineStrong, Offset(x, rulerH * 0.3f), Offset(x, rulerH), 1.5f * dp)
                    drawContext.canvas.nativeCanvas.drawText("${bar + 1}", x + 5f * dp, rulerH * 0.72f, labelPaint)
                    bar += every
                }
                // end of the song
                if (p.clips.isNotEmpty()) {
                    val ex = headerW + p.songEnd * px - sx
                    drawLine(c.textDim, Offset(ex, 0f), Offset(ex, rulerH), 2f * dp)
                }
            }
            drawLine(c.lineStrong, Offset(0f, rulerH), Offset(right, rulerH), thin)

            // track numbers
            drawRect(c.surface, Offset(0f, rulerH), Size(headerW, gridH))
            clipRect(0f, rulerH, headerW, bottom) {
                labelPaint.textAlign = Paint.Align.CENTER
                labelPaint.color = c.textDim.toArgb()
                for (t in 0 until p.tracks) {
                    val y = rulerH + t * trackH - sy
                    if (y > bottom || y + trackH < rulerH) continue
                    drawContext.canvas.nativeCanvas.drawText("${t + 1}", headerW / 2, y + trackH / 2 + 4f * dp, labelPaint)
                    drawLine(c.line, Offset(0f, y + trackH), Offset(headerW, y + trackH), thin)
                }
            }
            drawLine(c.lineStrong, Offset(headerW, 0f), Offset(headerW, bottom), thin)
            drawRect(c.surface, Offset(0f, 0f), Size(headerW - thin / 2, rulerH - thin / 2))
        }

        // The play head lives in its own layer: while the song plays only this thin line is redrawn.
        if (songMode) {
            val head = remember { Path() }
            Canvas(Modifier.fillMaxSize()) {
                val x = headerW + studio.playhead.doubleValue.toFloat() * view.pxPerTick - view.scrollX.coerceIn(0f, maxScrollX())
                if (x >= headerW - 1f && x <= size.width) {
                    drawLine(c.accent, Offset(x, rulerH * 0.5f), Offset(x, size.height), 2f * dp)
                    head.reset()
                    head.moveTo(x - 6f * dp, rulerH * 0.25f)
                    head.lineTo(x + 6f * dp, rulerH * 0.25f)
                    head.lineTo(x, rulerH * 0.25f + 9f * dp)
                    head.close()
                    drawPath(head, c.accent)
                }
            }
        }
    }
}
