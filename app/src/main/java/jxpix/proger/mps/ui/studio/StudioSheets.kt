package jxpix.proger.mps.ui.studio

import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import jxpix.proger.mps.R
import jxpix.proger.mps.engine.Devices
import jxpix.proger.mps.engine.instruments.DrumSynth
import jxpix.proger.mps.model.MAX_BPM
import jxpix.proger.mps.model.MAX_CHANNELS
import jxpix.proger.mps.model.MAX_PATTERN_BARS
import jxpix.proger.mps.model.MIN_BPM
import jxpix.proger.mps.model.Presets
import jxpix.proger.mps.model.addChannel
import jxpix.proger.mps.model.addPattern
import jxpix.proger.mps.model.duplicatePattern
import jxpix.proger.mps.model.removePattern
import jxpix.proger.mps.model.setPatternBars
import jxpix.proger.mps.model.updateChannel
import jxpix.proger.mps.model.updatePattern
import jxpix.proger.mps.studio.Studio
import jxpix.proger.mps.ui.Fmt
import jxpix.proger.mps.ui.Ic
import jxpix.proger.mps.ui.components.AppSheet
import jxpix.proger.mps.ui.components.Chip
import jxpix.proger.mps.ui.components.ConfirmDialog
import jxpix.proger.mps.ui.components.IconBtn
import jxpix.proger.mps.ui.components.Segmented
import jxpix.proger.mps.ui.components.SheetDivider
import jxpix.proger.mps.ui.components.SheetItem
import jxpix.proger.mps.ui.components.SheetLabel
import jxpix.proger.mps.ui.components.TextInputDialog
import jxpix.proger.mps.ui.theme.Mps
import jxpix.proger.mps.ui.theme.NumberStyle
import kotlin.math.roundToInt

@Composable
fun sliderColors() = SliderDefaults.colors(
    thumbColor = Mps.colors.accent,
    activeTrackColor = Mps.colors.accent,
    inactiveTrackColor = Mps.colors.surfaceHigh,
    activeTickColor = Mps.colors.accent,
    inactiveTickColor = Mps.colors.surfaceHigh,
)

/** Tempo, swing and time signature. */
@Composable
fun TempoSheet(studio: Studio, onDismiss: () -> Unit) {
    val c = Mps.colors
    val p = studio.project
    val taps = remember { ArrayList<Long>() }
    AppSheet(onDismiss, title = stringResource(R.string.menu_song_settings)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
            IconBtn(Ic.Remove, "−1", {
                studio.setBpm(p.bpm - 1f)
            }, background = c.surfaceHigh, size = 48.dp)
            Column(Modifier.width(150.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(Fmt.bpm(p.bpm), color = c.text, fontSize = 44.sp, lineHeight = 48.sp, style = NumberStyle)
                Text(stringResource(R.string.bpm), color = c.textDim, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
            IconBtn(Ic.Add, "+1", {
                studio.setBpm(p.bpm + 1f)
            }, background = c.surfaceHigh, size = 48.dp)
        }
        Slider(
            value = p.bpm, onValueChange = { studio.setBpm(it.roundToInt().toFloat()) }, valueRange = MIN_BPM..MAX_BPM,
            onValueChangeFinished = { studio.commit() }, colors = sliderColors(),
            modifier = Modifier.padding(horizontal = 20.dp).semantics { contentDescription = "BPM" },
        )
        val tapLabel = stringResource(R.string.tap_tempo)
        Box(
            Modifier
                .padding(horizontal = 20.dp, vertical = 4.dp)
                .fillMaxWidth()
                .height(56.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(c.accentSoft)
                .semantics {
                    role = Role.Button
                    contentDescription = tapLabel
                }
                // reacts on touch-down: the moment of the tap matters, not the release
                .pointerInput(Unit) {
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false).consume()
                        val now = SystemClock.elapsedRealtime()
                        if (taps.isNotEmpty() && now - taps.last() > 2000) taps.clear()
                        taps += now
                        if (taps.size > 8) taps.removeAt(0)
                        if (taps.size >= 2) {
                            val interval = (taps.last() - taps.first()) / (taps.size - 1).toFloat()
                            if (interval > 0f) studio.setBpm((60000f / interval).roundToInt().toFloat())
                        }
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(tapLabel, color = c.accent, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                Text(stringResource(R.string.tap_tempo_hint), color = c.textDim, fontSize = 11.sp)
            }
        }

        SheetDivider()
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.swing), Modifier.weight(1f), color = c.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Text("${(p.swing * 100f).roundToInt()}%", color = c.text, fontSize = 14.sp, style = NumberStyle)
        }
        Slider(
            value = p.swing, onValueChange = { v -> studio.edit("swing") { it.copy(swing = (v * 20f).roundToInt() / 20f) } },
            valueRange = 0f..1f, onValueChangeFinished = { studio.commit() }, colors = sliderColors(),
            modifier = Modifier.padding(horizontal = 20.dp).semantics { contentDescription = "Swing" },
        )
        Text(stringResource(R.string.swing_hint), Modifier.padding(horizontal = 20.dp), color = c.textDim, fontSize = 12.sp, lineHeight = 16.sp)

        SheetDivider()
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.time_signature), Modifier.weight(1f), color = c.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            val options = listOf(3, 4, 5, 6, 7)
            Segmented(options.map { it.toString() }, options.indexOf(p.beatsPerBar), { i -> studio.edit { it.copy(beatsPerBar = options[i]) } })
        }
    }
}

@Composable
fun RecordSheet(onDismiss: () -> Unit, onNotes: () -> Unit, onMic: () -> Unit) {
    val c = Mps.colors
    AppSheet(onDismiss, title = stringResource(R.string.rec_title)) {
        SheetItem(stringResource(R.string.rec_notes), onNotes, icon = Ic.Piano, description = stringResource(R.string.rec_notes_desc))
        SheetItem(stringResource(R.string.rec_audio), onMic, icon = Ic.Mic, description = stringResource(R.string.rec_audio_desc))
        Row(Modifier.padding(horizontal = 20.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Ic.Headphones, null, Modifier.size(18.dp), tint = c.textDim)
            Spacer(Modifier.width(10.dp))
            Text(stringResource(R.string.rec_headphones), color = c.textDim, fontSize = 12.sp, lineHeight = 16.sp)
        }
    }
}

@Composable
fun MenuSheet(
    studio: Studio,
    onDismiss: () -> Unit,
    onExport: () -> Unit,
    onTempo: () -> Unit,
    onMaster: () -> Unit,
    onRename: () -> Unit,
    onSettings: () -> Unit,
    onHelp: () -> Unit,
) {
    val c = Mps.colors
    AppSheet(onDismiss, title = studio.project.name) {
        SheetItem(stringResource(R.string.menu_export), onExport, icon = Ic.Download)
        SheetItem(stringResource(R.string.menu_song_settings), onTempo, icon = Ic.Speed)
        SheetItem(stringResource(R.string.master_effects), onMaster, icon = Ic.Tune)
        SheetItem(stringResource(R.string.menu_rename), onRename, icon = Ic.Rename)
        SheetDivider()
        SheetItem(stringResource(R.string.settings), onSettings, icon = Ic.Settings)
        SheetItem(stringResource(R.string.help), onHelp, icon = Ic.Help)
        SheetItem(stringResource(R.string.cd_home), {
            onDismiss()
            studio.closeProject()
        }, icon = Ic.Home)
        Text(
            stringResource(R.string.autosave_note), Modifier.padding(horizontal = 20.dp, vertical = 8.dp), color = c.textDim, fontSize = 12.sp,
        )
    }
}

@Composable
fun ExportSheet(studio: Studio, onDismiss: () -> Unit) {
    val c = Mps.colors
    AppSheet(onDismiss, title = stringResource(R.string.export_title)) {
        Text(
            stringResource(if (studio.project.clips.isEmpty()) R.string.export_pattern else R.string.export_song),
            Modifier.padding(horizontal = 20.dp, vertical = 4.dp), color = c.textDim, fontSize = 13.sp, lineHeight = 18.sp,
        )
        SheetItem(stringResource(R.string.export_wav), {
            onDismiss()
            studio.export(aac = false)
        }, icon = Ic.Wave, description = stringResource(R.string.export_wav_desc))
        SheetItem(stringResource(R.string.export_m4a), {
            onDismiss()
            studio.export(aac = true)
        }, icon = Ic.AudioFile, description = stringResource(R.string.export_m4a_desc))
    }
}

/** Switch between patterns and manage the current one. */
@Composable
fun PatternSheet(studio: Studio, onDismiss: () -> Unit) {
    val c = Mps.colors
    val p = studio.project
    val current = studio.currentPattern
    var rename by remember { mutableStateOf(false) }
    var delete by remember { mutableStateOf(false) }
    AppSheet(onDismiss, title = stringResource(R.string.patterns)) {
        if (current != null) {
            Column(
                Modifier.padding(horizontal = 16.dp, vertical = 4.dp).fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(c.surface).padding(12.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(14.dp).clip(RoundedCornerShape(5.dp)).background(c.channel(current.color)))
                    Spacer(Modifier.width(10.dp))
                    Text(current.name, Modifier.weight(1f), color = c.text, fontSize = 16.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                }
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.pattern_length), Modifier.weight(1f), color = c.textDim, fontSize = 14.sp)
                    IconBtn(Ic.Remove, "−", { studio.edit { it.setPatternBars(current.id, current.bars - 1) } }, enabled = current.bars > 1, background = c.surfaceHigh, size = 40.dp)
                    Text(
                        pluralStringResource(R.plurals.bars, current.bars, current.bars), Modifier.width(96.dp), color = c.text,
                        fontSize = 15.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center,
                    )
                    IconBtn(Ic.Add, "+", { studio.edit { it.setPatternBars(current.id, current.bars + 1) } }, enabled = current.bars < MAX_PATTERN_BARS, background = c.surfaceHigh, size = 40.dp)
                }
                Spacer(Modifier.height(10.dp))
                @OptIn(ExperimentalLayoutApi::class)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Chip(stringResource(R.string.rename), { rename = true }, icon = Ic.Rename)
                    val copyName = stringResource(R.string.copy_of, current.name)
                    Chip(stringResource(R.string.duplicate), {
                        studio.edit { it.duplicatePattern(current.id, copyName).first }
                        // the copy is inserted right after the original
                        val list = studio.project.patterns
                        val i = list.indexOfFirst { it.id == current.id }
                        list.getOrNull(i + 1)?.let { studio.selectPattern(it.id) }
                    }, icon = Ic.Copy)
                    Chip(stringResource(R.string.clear_pattern), { studio.edit { it.updatePattern(current.id) { pat -> pat.copy(notes = emptyMap()) } } }, icon = Ic.Clear, enabled = !current.isEmpty)
                    Chip(stringResource(R.string.delete), { delete = true }, icon = Ic.Delete, enabled = p.patterns.size > 1)
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        for (pat in p.patterns) {
            SheetItem(
                pat.name,
                onClick = {
                    studio.selectPattern(pat.id)
                    onDismiss()
                },
                dot = c.channel(pat.color),
                description = pluralStringResource(R.plurals.bars, pat.bars, pat.bars),
                selected = pat.id == current?.id,
            )
        }
        val newName = stringResource(R.string.pattern_n, p.patterns.size + 1)
        SheetItem(stringResource(R.string.add_pattern), {
            studio.edit { it.addPattern(newName).first }
            studio.project.patterns.lastOrNull()?.let { studio.selectPattern(it.id) }
            onDismiss()
        }, icon = Ic.Add)
    }
    if (rename && current != null) {
        TextInputDialog(
            stringResource(R.string.rename_pattern), current.name,
            onConfirm = { name ->
                rename = false
                studio.edit { it.updatePattern(current.id) { pat -> pat.copy(name = name) } }
            },
            onDismiss = { rename = false },
        )
    }
    if (delete && current != null) {
        ConfirmDialog(
            stringResource(R.string.delete_pattern_title), stringResource(R.string.delete_pattern_text, current.name), stringResource(R.string.delete),
            onConfirm = {
                delete = false
                studio.edit { it.removePattern(current.id) }
            },
            onDismiss = { delete = false },
        )
    }
}

/** Pick the instrument for a new channel. One tap creates it. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AddChannelSheet(studio: Studio, onDismiss: () -> Unit, onCreated: (Int) -> Unit = {}) {
    val c = Mps.colors
    val drumNames = stringArrayResource(R.array.arr_drum_types)
    val limit = stringResource(R.string.limit_channels)

    fun create(type: String, name: String, params: Map<String, Float>?, preset: String?, cut: Int = 0) {
        if (studio.project.channels.size >= MAX_CHANNELS) {
            studio.message = limit
            onDismiss()
            return
        }
        var created = 0
        studio.edit { p ->
            val (q, id) = p.addChannel(type, name, params = params, preset = preset)
            created = id
            if (cut != 0) q.updateChannel(id) { it.copy(cutGroup = cut) } else q
        }
        if (created != 0) {
            studio.selectChannel(created)
            studio.preview(created, 60)
            onCreated(created)
        }
        onDismiss()
    }

    AppSheet(onDismiss, title = stringResource(R.string.add_channel_title)) {
        SheetLabel(stringResource(R.string.dev_drum))
        Text(stringResource(R.string.dev_drum_desc), Modifier.padding(horizontal = 20.dp), color = c.textDim, fontSize = 12.sp, lineHeight = 16.sp)
        FlowRow(
            Modifier.padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            drumNames.forEachIndexed { type, name ->
                Chip(name, {
                    val cut = if (type == DrumSynth.HAT || type == DrumSynth.OPEN_HAT) 1 else 0
                    create(Devices.DRUM.id, name, DrumSynth.defaultsFor(type), null, cut)
                })
            }
        }

        SheetDivider()
        SheetLabel(stringResource(R.string.dev_synth))
        Text(stringResource(R.string.dev_synth_desc), Modifier.padding(horizontal = 20.dp), color = c.textDim, fontSize = 12.sp, lineHeight = 16.sp)
        val byCategory = remember { Presets.synth.groupBy { it.categoryRes } }
        for ((category, list) in byCategory) {
            Text(
                stringResource(category), Modifier.padding(start = 20.dp, top = 10.dp), color = c.text, fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
            )
            FlowRow(
                Modifier.padding(horizontal = 16.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                for (preset in list) {
                    val name = stringResource(preset.nameRes)
                    Chip(name, { create(Devices.SYNTH.id, name, preset.params, preset.id) })
                }
            }
        }

        SheetDivider()
        val samplerName = stringResource(R.string.ch_sampler)
        SheetItem(stringResource(R.string.dev_sampler), {
            create(Devices.SAMPLER.id, samplerName, null, null)
        }, icon = Ic.AudioFile, description = stringResource(R.string.dev_sampler_desc))
        val audioName = stringResource(R.string.ch_audio)
        SheetItem(stringResource(R.string.dev_audio), {
            create(Devices.AUDIO_TYPE_ID, audioName, null, null)
        }, icon = Ic.Mic, description = stringResource(R.string.dev_audio_desc))
    }
}
