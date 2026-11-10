package jxpix.proger.mps.ui.studio

import jxpix.proger.mps.ui.components.PanelColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import jxpix.proger.mps.R
import jxpix.proger.mps.engine.Devices
import jxpix.proger.mps.engine.instruments.DrumSynth
import jxpix.proger.mps.model.Channel
import jxpix.proger.mps.model.DeviceState
import jxpix.proger.mps.model.MASTER_ID
import jxpix.proger.mps.model.MAX_EFFECTS
import jxpix.proger.mps.model.Presets
import jxpix.proger.mps.model.addEffect
import jxpix.proger.mps.model.applyPreset
import jxpix.proger.mps.model.effectsOf
import jxpix.proger.mps.model.moveEffect
import jxpix.proger.mps.model.noteName
import jxpix.proger.mps.model.removeEffect
import jxpix.proger.mps.model.replaceInstrument
import jxpix.proger.mps.model.setParam
import jxpix.proger.mps.model.updateChannel
import jxpix.proger.mps.model.updateDevice
import jxpix.proger.mps.studio.Studio
import jxpix.proger.mps.studio.Tab
import jxpix.proger.mps.ui.Ic
import jxpix.proger.mps.ui.components.AppSheet
import jxpix.proger.mps.ui.components.Chip
import jxpix.proger.mps.ui.components.DeviceParams
import jxpix.proger.mps.ui.components.HintBanner
import jxpix.proger.mps.ui.components.IconBtn
import jxpix.proger.mps.ui.components.Panel
import jxpix.proger.mps.ui.components.Segmented
import jxpix.proger.mps.ui.components.SheetItem
import jxpix.proger.mps.ui.components.TextInputDialog
import jxpix.proger.mps.ui.theme.Mps
import jxpix.proger.mps.ui.theme.NumberStyle
import kotlin.math.max

/**
 * The sound of one channel: its instrument, its effect chain and a few channel settings.
 * With [MASTER_ID] it shows the effects of the master strip.
 */
@Composable
fun ChannelEditor(studio: Studio, ownerId: Int) {
    val c = Mps.colors
    val ui = studio.ui
    val p = studio.project
    val isMaster = ownerId == MASTER_ID
    val channel = if (isMaster) null else p.channel(ownerId)
    if (!isMaster && channel == null) {
        // the channel is gone (deleted, or its creation was undone)
        LaunchedEffect(Unit) { ui.editor = null }
        return
    }
    val tab = if (isMaster) 1 else ui.editorTab.coerceIn(0, 2)
    val color = channel?.let { c.channel(it.color) } ?: c.accent
    // on its side the phone has little height: title, preset and tabs share one row
    val compact = isWideAndShort()
    var presetSheet by remember { mutableStateOf(false) }
    val hasPresets = channel != null && !channel.isAudio && Devices[channel.instrument.type] != null
    val tabs = listOf(stringResource(R.string.tab_instrument), stringResource(R.string.tab_effects), stringResource(R.string.tab_channel))

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().height(48.dp).padding(end = if (compact) 12.dp else 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconBtn(Ic.Back, stringResource(R.string.back), { ui.editor = null })
            if (channel != null) {
                Box(Modifier.size(12.dp).clip(CircleShape).background(color))
                Spacer(Modifier.width(8.dp))
            }
            Text(
                channel?.name ?: stringResource(R.string.master_effects), Modifier.weight(1f), color = c.text, fontSize = 16.sp,
                fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            if (compact && channel != null) {
                if (hasPresets && tab == 0) {
                    Chip(
                        presetName(channel.instrument) ?: stringResource(R.string.preset_custom), { presetSheet = true }, icon = Ic.Library,
                        trailing = Ic.DropDown, maxWidth = 190.dp,
                    )
                    Spacer(Modifier.width(10.dp))
                }
                Segmented(tabs, tab, { ui.editorTab = it }, Modifier.width(340.dp), equalWidth = true)
            }
        }
        if (channel != null && !compact) {
            Segmented(tabs, tab, { ui.editorTab = it }, Modifier.padding(horizontal = 12.dp).fillMaxWidth(), equalWidth = true)
            Spacer(Modifier.height(8.dp))
        }
        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = 12.dp, end = 12.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            when {
                tab == 1 -> EffectsPage(studio, ownerId, color)
                tab == 2 && channel != null -> ChannelPage(studio, channel)
                channel != null -> InstrumentPage(studio, channel, color, presetRow = !compact) { presetSheet = true }
            }
        }
        // something to play the sound with while turning the knobs
        if (channel != null && !channel.isAudio) {
            Box(Modifier.fillMaxWidth().height(1.dp).background(c.line))
            if (channel.isDrum) {
                AuditionPad(studio, channel, color)
            } else {
                BoxWithConstraints(Modifier.fillMaxWidth().height(if (compact) 62.dp else 76.dp).padding(horizontal = 8.dp, vertical = 6.dp)) {
                    val keys = (maxWidth / 46.dp).toInt().coerceIn(7, 22)
                    Keyboard(studio, channel, (ui.octave + 1) * 12, keys, null, 0, Modifier.fillMaxSize())
                }
            }
        }
    }
    if (presetSheet && channel != null) {
        PresetSheet(studio, channel.instrument.uid, { presetSheet = false }) { studio.preview(channel.id, channel.rootNote, millis = 350) }
    }
}

@Composable
private fun AuditionPad(studio: Studio, channel: Channel, color: Color) {
    val c = Mps.colors
    var down by remember { mutableStateOf(false) }
    val label = stringResource(R.string.audition)
    Box(
        Modifier
            .fillMaxWidth()
            .height(if (isWideAndShort()) 58.dp else 72.dp)
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(if (down) color else color.copy(alpha = 0.22f))
            .border(1.5.dp, color, RoundedCornerShape(16.dp))
            .semantics {
                role = Role.Button
                contentDescription = label
            }
            .pointerInput(channel.id, channel.rootNote) {
                awaitEachGesture {
                    val first = awaitFirstDown(requireUnconsumed = false)
                    first.consume()
                    studio.noteOn(channel.id, channel.rootNote, 0.9f)
                    down = true
                    try {
                        while (true) {
                            val change = awaitPointerEvent().changes.firstOrNull { it.id == first.id } ?: break
                            if (!change.pressed) break
                            change.consume()
                        }
                    } finally {
                        studio.noteOff(channel.id, channel.rootNote)
                        down = false
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = if (down) Color.White else c.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
    }
}

// ------------------------------------------------------------------------------------ instrument

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun InstrumentPage(studio: Studio, channel: Channel, color: Color, presetRow: Boolean, onPresets: () -> Unit) {
    val c = Mps.colors
    val ui = studio.ui
    val state = channel.instrument
    val type = Devices[state.type]

    if ("knob" !in studio.prefs.hintsSeen && !channel.isAudio) {
        HintBanner(stringResource(R.string.knob_hint), { studio.prefs.markHintSeen("knob") })
    }
    val onParam = { id: String, value: Float -> studio.edit("param${state.uid}:$id") { it.setParam(state.uid, id, value) } }
    val onEnd = { studio.commit() }

    when {
        channel.isAudio || type == null -> {
            Panel(stringResource(R.string.dev_audio)) {
                Column {
                    Text(stringResource(R.string.piano_audio_info), color = c.textDim, fontSize = 13.sp, lineHeight = 18.sp)
                    Spacer(Modifier.height(10.dp))
                    Chip(stringResource(R.string.tab_playlist), {
                        ui.editor = null
                        ui.tab = Tab.PLAYLIST
                    }, icon = Ic.Playlist)
                }
            }
        }
        channel.isDrum -> {
            val names = stringArrayResource(R.array.arr_drum_types)
            val current = state.param("type").toInt().coerceIn(0, names.size - 1)
            Panel(stringResource(R.string.p_sound)) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    names.forEachIndexed { i, name ->
                        Chip(name, {
                            if (i != current) {
                                studio.edit { p ->
                                    val q = p.updateDevice(state.uid) { it.copy(params = DrumSynth.defaultsFor(i)) }
                                    // a channel still carrying the name of its old sound follows the new one
                                    if (channel.name == names[current]) q.updateChannel(channel.id) { it.copy(name = name) } else q
                                }
                            }
                            studio.preview(channel.id, channel.rootNote)
                        }, selected = i == current)
                    }
                }
            }
            if (presetRow) PresetRow(state, onPresets)
            DeviceParams(type, state, color, onParam, onEnd, skip = setOf("type"))
        }
        else -> {
            if (presetRow) PresetRow(state, onPresets)
            // the sound file of a sampler sits among the knob panels, so that it gets a column of its own on a wide screen
            val before: List<@Composable () -> Unit> =
                if (state.type == Devices.SAMPLER.id) listOf({ SamplePanel(studio, channel, color) }) else emptyList()
            DeviceParams(type, state, color, onParam, onEnd, before = before)
        }
    }

}

/** "Preset: Deep bass" with a button that opens the list of presets. */
@Composable
private fun PresetRow(state: DeviceState, onOpen: () -> Unit) {
    val c = Mps.colors
    Panel(null) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.preset), Modifier.weight(1f), color = c.accent, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            Chip(presetName(state) ?: stringResource(R.string.preset_custom), onOpen, icon = Ic.Library, trailing = Ic.DropDown, maxWidth = 220.dp)
        }
    }
}

/** The audio file of a sampler channel: waveform, facts and the button to load another one. */
@Composable
private fun SamplePanel(studio: Studio, channel: Channel, color: Color) {
    val c = Mps.colors
    val state = channel.instrument
    val res = LocalResources.current
    @Suppress("UNUSED_VARIABLE") val version = studio.samplesVersion
    val data = studio.samples[state.sample]
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) studio.importSample(uri, channel.id)
    }
    var fromLibrary by remember { mutableStateOf(false) }
    Panel(stringResource(R.string.sample_file)) {
        Column {
            if (data == null) {
                Text(
                    stringResource(if (state.sample != null && studio.loadingSamples > 0) R.string.loading_samples else R.string.sample_none),
                    color = c.textDim, fontSize = 13.sp, lineHeight = 18.sp,
                )
            } else {
                val start = state.param("start")
                val end = state.param("end")
                Canvas(Modifier.fillMaxWidth().height(72.dp).clip(RoundedCornerShape(10.dp)).background(c.surfaceHigh)) {
                    val overview = data.overview
                    if (overview.isNotEmpty()) {
                        val step = 2.dp.toPx()
                        val mid = size.height / 2
                        var x = 0f
                        while (x < size.width) {
                            val i0 = (x / size.width * overview.size).toInt().coerceIn(0, overview.size - 1)
                            val i1 = ((x + step) / size.width * overview.size).toInt().coerceIn(i0, overview.size - 1)
                            var peak = 0f
                            for (i in i0..i1) if (overview[i] > peak) peak = overview[i]
                            val half = max(0.5.dp.toPx(), peak * (mid - 3.dp.toPx()))
                            val inside = x / size.width in start..end
                            drawLine(if (inside) color else c.textDim.copy(alpha = 0.4f), Offset(x, mid - half), Offset(x, mid + half), step * 0.6f)
                            x += step
                        }
                    }
                    // the part that plays
                    drawRect(c.text, Offset(start * size.width, 0f), Size(1.5.dp.toPx(), size.height))
                    drawRect(c.text, Offset((end * size.width - 1.5.dp.toPx()).coerceAtLeast(0f), 0f), Size(1.5.dp.toPx(), size.height))
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    (state.sample ?: "").substringBeforeLast('.'), color = c.text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                Text(
                    stringResource(R.string.sample_meta, data.durationSeconds, data.sampleRate, stringResource(if (data.isStereo) R.string.stereo else R.string.mono)),
                    color = c.textDim, fontSize = 12.sp,
                )
            }
            Spacer(Modifier.height(10.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Chip(stringResource(R.string.sounds_title), { fromLibrary = true }, icon = Ic.Library)
                Chip(stringResource(if (data == null) R.string.load_file else R.string.replace_file), {
                    try {
                        picker.launch(arrayOf("audio/*"))
                    } catch (_: Exception) {
                        studio.message = res.getString(R.string.msg_import_failed)
                    }
                }, icon = Ic.Upload)
                val sample = state.sample
                if (data != null && sample != null) {
                    Chip(stringResource(R.string.sound_keep), { studio.saveToLibrary(sample) }, icon = Ic.Save)
                }
            }
        }
    }
    if (fromLibrary) SoundsSheet(studio, SoundTarget.Sampler(channel.id), { fromLibrary = false })
}

// ------------------------------------------------------------------------------------ effects

@Composable
private fun EffectsPage(studio: Studio, ownerId: Int, color: Color) {
    val c = Mps.colors
    val effects = studio.project.effectsOf(ownerId)
    var addSheet by remember { mutableStateOf(false) }

    if (effects.isEmpty()) {
        Text(
            stringResource(R.string.no_effects), Modifier.fillMaxWidth().padding(vertical = 24.dp), color = c.textDim, fontSize = 14.sp,
            textAlign = TextAlign.Center,
        )
    }
    effects.forEachIndexed { index, fx ->
        val type = Devices[fx.type] ?: return@forEachIndexed
        EffectCard(
            studio, ownerId, fx, stringResource(type.nameRes), color, first = index == 0, last = index == effects.lastIndex,
        )
    }
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Chip(stringResource(R.string.add_effect), { addSheet = true }, icon = Ic.Add, enabled = effects.size < MAX_EFFECTS)
    }
    if (effects.size >= MAX_EFFECTS) {
        Text(
            stringResource(R.string.effects_limit, MAX_EFFECTS), Modifier.fillMaxWidth(), color = c.textDim, fontSize = 12.sp,
            textAlign = TextAlign.Center,
        )
    }

    if (addSheet) {
        AppSheet({ addSheet = false }, title = stringResource(R.string.add_effect)) {
            for (type in Devices.effects) {
                SheetItem(stringResource(type.nameRes), {
                    addSheet = false
                    studio.edit { it.addEffect(ownerId, type.id).first }
                }, description = stringResource(type.descRes))
            }
        }
    }
}

@Composable
private fun EffectCard(studio: Studio, ownerId: Int, fx: DeviceState, name: String, color: Color, first: Boolean, last: Boolean) {
    val c = Mps.colors
    val type = Devices[fx.type] ?: return
    var presets by remember { mutableStateOf(false) }
    // the name of the preset next to the name of the effect: "Reverb · Hall"
    val title = presetName(fx)?.let { "$name · $it" } ?: name
    Panel(
        title,
        trailing = {
            IconBtn(Ic.Library, stringResource(R.string.preset) + ": " + name, { presets = true }, tint = c.textDim, size = 34.dp, iconSize = 18.dp)
            Spacer(Modifier.width(4.dp))
            IconBtn(
                Ic.Power, stringResource(R.string.fx_bypass) + ": " + name, { studio.edit { it.updateDevice(fx.uid) { d -> d.copy(enabled = !d.enabled) } } },
                tint = if (fx.enabled) c.onAccent else c.textDim, background = if (fx.enabled) c.accent else c.surfaceHigh, size = 34.dp, iconSize = 18.dp,
                modifier = Modifier.semantics { selected = fx.enabled },
            )
            Spacer(Modifier.width(4.dp))
            IconBtn(Ic.ArrowUp, stringResource(R.string.fx_move_up) + ": " + name, { studio.edit { it.moveEffect(ownerId, fx.uid, -1) } }, enabled = !first, size = 34.dp, iconSize = 18.dp)
            IconBtn(Ic.ArrowDown, stringResource(R.string.fx_move_down) + ": " + name, { studio.edit { it.moveEffect(ownerId, fx.uid, 1) } }, enabled = !last, size = 34.dp, iconSize = 18.dp)
            IconBtn(Ic.Delete, stringResource(R.string.fx_remove) + ": " + name, { studio.edit { it.removeEffect(ownerId, fx.uid) } }, tint = c.record, size = 34.dp, iconSize = 18.dp)
        },
    ) {
        DeviceParams(
            type, fx, color,
            onParam = { id, value -> studio.edit("param${fx.uid}:$id") { it.setParam(fx.uid, id, value) } },
            onEnd = { studio.commit() },
            modifier = Modifier.alpha(if (fx.enabled) 1f else 0.45f), framed = false,
        )
    }
    if (presets) PresetSheet(studio, fx.uid, { presets = false })
}

// ------------------------------------------------------------------------------------ channel

@Composable
private fun ChannelPage(studio: Studio, channel: Channel) {
    val c = Mps.colors
    var rename by remember { mutableStateOf(false) }

    // on a wide screen the panels stand in two columns
    val panels = ArrayList<@Composable () -> Unit>()
    panels += {
    Panel(stringResource(R.string.name)) {
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(role = Role.Button) { rename = true }.padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(channel.name, Modifier.weight(1f), color = c.text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            IconBtn(Ic.Rename, stringResource(R.string.ch_rename), { rename = true }, tint = c.textDim, size = 36.dp)
        }
    }
    }
    panels += {
    Panel(stringResource(R.string.ch_color)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            val label = stringResource(R.string.ch_color)
            for (i in c.channels.indices) {
                Box(
                    Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .background(c.channels[i])
                        .border(if (i == channel.color) 3.dp else 0.dp, if (i == channel.color) c.text else Color.Transparent, CircleShape)
                        .clickable(role = Role.RadioButton) { studio.edit { it.updateChannel(channel.id) { x -> x.copy(color = i) } } }
                        .semantics {
                            contentDescription = "$label ${i + 1}"
                            selected = i == channel.color
                        },
                )
            }
        }
    }
    }
    if (!channel.isAudio) {
        panels += {
        Panel(stringResource(R.string.instrument_type)) {
            Column {
                val types = remember { listOf(Devices.DRUM, Devices.SYNTH, Devices.SAMPLER) }
                Segmented(
                    types.map { stringResource(it.nameRes) }, types.indexOfFirst { it.id == channel.instrument.type }.coerceAtLeast(0),
                    { i ->
                        val type = types[i]
                        if (type.id != channel.instrument.type) {
                            studio.edit { p ->
                                when (type.id) {
                                    Devices.DRUM.id -> p.replaceInstrument(channel.id, type.id, DrumSynth.defaultsFor(0))
                                    Devices.SYNTH.id -> Presets.synth("init").let { p.replaceInstrument(channel.id, type.id, it.params, it.id) }
                                    else -> p.replaceInstrument(channel.id, type.id)
                                }
                            }
                            studio.preview(channel.id, channel.rootNote)
                        }
                    },
                    Modifier.fillMaxWidth(), equalWidth = true,
                )
                Spacer(Modifier.height(6.dp))
                Text(stringResource(R.string.instrument_type_hint), color = c.textDim, fontSize = 12.sp, lineHeight = 16.sp)
            }
        }
        }
        panels += {
        Panel(stringResource(R.string.step_note)) {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val set = { note: Int ->
                        val n = note.coerceIn(24, 96)
                        studio.edit("root${channel.id}") { it.updateChannel(channel.id) { x -> x.copy(rootNote = n) } }
                        studio.preview(channel.id, n)
                    }
                    IconBtn(Ic.Remove, stringResource(R.string.semitone_down), { set(channel.rootNote - 1) }, enabled = channel.rootNote > 24, background = c.surfaceHigh, size = 40.dp)
                    Text(noteName(channel.rootNote), Modifier.width(72.dp), color = c.text, fontSize = 18.sp, style = NumberStyle, textAlign = TextAlign.Center)
                    IconBtn(Ic.Add, stringResource(R.string.semitone_up), { set(channel.rootNote + 1) }, enabled = channel.rootNote < 96, background = c.surfaceHigh, size = 40.dp)
                    Spacer(Modifier.width(12.dp))
                    IconBtn(Ic.ExpandMore, stringResource(R.string.octave_down), { set(channel.rootNote - 12) }, enabled = channel.rootNote - 12 >= 24, background = c.surfaceHigh, size = 40.dp, iconSize = 26.dp)
                    Spacer(Modifier.width(6.dp))
                    IconBtn(Ic.ExpandLess, stringResource(R.string.octave_up), { set(channel.rootNote + 12) }, enabled = channel.rootNote + 12 <= 96, background = c.surfaceHigh, size = 40.dp, iconSize = 26.dp)
                }
                Spacer(Modifier.height(6.dp))
                Text(stringResource(R.string.step_note_hint), color = c.textDim, fontSize = 12.sp, lineHeight = 16.sp)
            }
        }
        }
        panels += {
        Panel(stringResource(R.string.cut_group)) {
            Column {
                Segmented(
                    listOf(stringResource(R.string.off), "1", "2", "3", "4"), channel.cutGroup.coerceIn(0, 4),
                    { g -> studio.edit { it.updateChannel(channel.id) { x -> x.copy(cutGroup = g) } } }, Modifier.fillMaxWidth(), equalWidth = true,
                )
                Spacer(Modifier.height(6.dp))
                Text(stringResource(R.string.cut_group_hint), color = c.textDim, fontSize = 12.sp, lineHeight = 16.sp)
            }
        }
        }
    }
    PanelColumns(panels)
    if (rename) {
        TextInputDialog(
            stringResource(R.string.ch_rename), channel.name,
            onConfirm = { name ->
                rename = false
                studio.edit { it.updateChannel(channel.id) { x -> x.copy(name = name) } }
            },
            onDismiss = { rename = false }, maxLength = 24,
        )
    }
}
