package jxpix.proger.mps.ui.studio

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import jxpix.proger.mps.R
import jxpix.proger.mps.engine.PPQ
import jxpix.proger.mps.model.Channel
import jxpix.proger.mps.model.Note
import jxpix.proger.mps.model.Pattern
import jxpix.proger.mps.model.Project
import jxpix.proger.mps.model.Scales
import jxpix.proger.mps.model.MAX_PATTERN_BARS
import jxpix.proger.mps.model.doublePattern
import jxpix.proger.mps.model.setNotes
import jxpix.proger.mps.model.updatePattern
import jxpix.proger.mps.music.Arranger
import jxpix.proger.mps.music.Generator
import jxpix.proger.mps.music.Harmony
import jxpix.proger.mps.music.Key
import jxpix.proger.mps.music.Role as Part
import jxpix.proger.mps.studio.GeneratorState
import jxpix.proger.mps.studio.Studio
import jxpix.proger.mps.ui.Ic
import jxpix.proger.mps.ui.components.AppSheet
import jxpix.proger.mps.ui.components.Chip
import jxpix.proger.mps.ui.components.IconBtn
import jxpix.proger.mps.ui.components.Segmented
import jxpix.proger.mps.ui.components.SheetLabel
import jxpix.proger.mps.ui.SwitchRow
import jxpix.proger.mps.ui.theme.Mps
import kotlin.math.max

/** How often an arpeggio plays a note: a quarter, an eighth, a triplet, a sixteenth. */
private val ARP_STEPS = intArrayOf(PPQ, PPQ / 2, PPQ / 3, PPQ / 4)

/** Everything the generator works from, worked out anew for every press: the project may have changed in between. */
internal class GeneratorPlan(
    val key: Key,
    /** True when the song had no key and its notes were enough to tell one. */
    val guessed: Boolean,
    /** How long the pattern has to be for all the chords to fit: its own length, or that doubled as often as it takes. */
    val bars: Int,
    /** True when the channel had chords of its own to make an arpeggio of. */
    val chordal: Boolean,
    /** The chords the other channels of the pattern spell out; null when they are silent. */
    val heard: Harmony?,
    val harmony: Harmony,
    /** True when an arpeggio is being made of the notes of the channel itself. */
    val ofNotes: Boolean,
    val notes: List<Note>,
)

/**
 * The key to write in: the key of the song, or the one its notes point to, or A minor when there
 * is nothing to go by. The second value tells whether it was guessed from the notes.
 */
internal fun generatorKey(p: Project): Pair<Key, Boolean> {
    if (p.scale != 0) return Key(p.keyRoot, p.scale) to false
    val guess = Key.guess(Generator.everything(p))
    return (guess ?: Key(9, Scales.MINOR)) to (guess != null)
}

/** True when at some moment two different notes sound together: the notes are chords, not a line. */
internal fun chordal(notes: List<Note>): Boolean =
    notes.any { a -> notes.any { b -> b.pitch != a.pitch && b.start < a.end && a.start < b.end } }

/**
 * @param before the pattern as it was when the generator was opened: an arpeggio is made of the
 *   notes the channel had then, however often they were replaced since
 */
internal fun generatorPlan(p: Project, pattern: Pattern, before: Pattern, channel: Channel, g: GeneratorState): GeneratorPlan {
    val bar = p.ticksPerBar
    val (key, guessed) = generatorKey(p)
    val heard = Harmony.detect(key, Generator.surroundings(p, pattern, channel.id), p.patternLength(pattern), max(PPQ, bar / 2), g.sevenths)
    val each = when (g.chordLength) {
        0 -> max(PPQ, bar / 2)
        2 -> bar * 2
        else -> bar
    }
    val own = before.notesOf(channel.id)
    val chordal = chordal(own)
    val ofNotes = g.kind == Generator.ARP && g.fromNotes && chordal
    val steps = if (ofNotes || (g.progression < 0 && heard != null)) null else key.progressions[g.progression.coerceIn(0, key.progressions.size - 1)]
    // a progression that does not fit makes the pattern longer: twice, four times... as long, so that what is in it stays in step
    var bars = pattern.bars
    if (steps != null) while (bars * bar < steps.size * each && bars * 2 <= MAX_PATTERN_BARS) bars *= 2
    val length = bars * bar
    val harmony = if (steps == null) heard ?: Harmony.of(key, key.progressions[0], length, each, g.sevenths) else Harmony.of(key, steps, length, each, g.sevenths)
    // the notes the channel had, repeated as the pattern was when it grew
    val base = ArrayList(own)
    val was = before.bars * bar
    var shift = was
    while (shift < length && was > 0) {
        for (n in own) if (n.start < was) base += n.copy(start = n.start + shift)
        shift += was
    }
    val step = ARP_STEPS[g.step.coerceIn(0, ARP_STEPS.size - 1)]
    val notes = when (g.kind) {
        Generator.MELODY -> Generator.melody(key, harmony, g.density, bar, g.seed)
        Generator.BASS -> Generator.bass(key, harmony, g.bassStyle, bar, g.seed, Generator.bassLow(channel))
        Generator.ARP ->
            if (ofNotes) Generator.arpeggiate(base, length, g.order, step, g.octaves, g.seed)
            else Generator.arpeggio(harmony, g.order, step, g.octaves, g.seed)
        else -> Generator.chords(harmony, g.chordStyle, bar, g.seed)
    }
    return GeneratorPlan(key, guessed, bars, chordal, heard, harmony, ofNotes, notes)
}

/** "A minor" for a key, in the words of the key sheet. */
@Composable
private fun keyText(key: Key): String {
    val res = LocalResources.current
    return keyName(Project(keyRoot = key.root, scale = key.scale), res) ?: ""
}

/**
 * The generator: writes chords, a melody, a bass line or an arpeggio onto the channel that is
 * open in the piano roll, in the key of the song and over the chords the pattern already has.
 */
@Composable
internal fun GeneratorSheet(studio: Studio, onDismiss: () -> Unit) {
    val c = Mps.colors
    val ui = studio.ui
    val g = ui.generator
    val p = studio.project
    val pattern = studio.currentPattern ?: return
    val channel = studio.currentChannel ?: return
    // the pattern as it was when the sheet was opened: an arpeggio is made of what the channel held, and "put back" returns it all
    val before = remember { pattern }
    val base = before.notesOf(channel.id)
    // the key the song had: the generator gives the song a key when it has none, and "put back" takes that back as well
    val keyBefore = remember { p.keyRoot to p.scale }
    var keyChosen by remember { mutableStateOf(false) }
    var made by remember { mutableStateOf(false) }
    var keySheet by remember { mutableStateOf(false) }

    // a channel called "Bass" most likely wants a bass line
    LaunchedEffect(Unit) {
        when {
            channel.isDrum -> g.kind = Generator.BASS
            "arp" in channel.name.lowercase() || "арп" in channel.name.lowercase() -> g.kind = Generator.ARP
            else -> when (Arranger.byName(channel)) {
                Part.BASS -> g.kind = Generator.BASS
                Part.CHORDS -> g.kind = Generator.CHORDS
                Part.LEAD -> g.kind = Generator.MELODY
                else -> {}
            }
        }
    }

    fun write() {
        val now = studio.project
        val pat = now.pattern(pattern.id) ?: return
        val ch = now.channel(channel.id) ?: return
        val plan = generatorPlan(now, pat, before, ch, g)
        // every variant tried while the sheet is open is one step for "undo"
        studio.edit("generate", mergeWindowMs = 3_600_000L) { q ->
            var r = if (q.scale == 0) q.copy(keyRoot = plan.key.root, scale = plan.key.scale) else q
            // room for all the chords: the pattern is repeated, with everything that is in it
            var guard = 0
            while ((r.pattern(pat.id)?.bars ?: plan.bars) < plan.bars && guard++ < 4) r = r.doublePattern(pat.id)
            r.setNotes(pat.id, ch.id, plan.notes)
        }
        ui.rollSelection = emptySet()
        // the piano roll finds the new notes itself
        ui.roll.scrollY = -1f
        made = true
    }

    /** A choice was changed: what is written follows it at once. */
    fun change(set: () -> Unit) {
        set()
        if (made) write()
    }

    fun close() {
        studio.commit()
        onDismiss()
    }

    if (keySheet) {
        KeySheet(studio) {
            keySheet = false
            // a key picked by hand is the user's own doing: it stays whatever happens to the notes
            if (studio.project.keyRoot to studio.project.scale != keyBefore) keyChosen = true
            if (made) write()
        }
        return
    }

    AppSheet(::close, title = stringResource(R.string.gen_title), scroll = false) {
        if (!Generator.pitched(channel)) {
            Text(
                stringResource(R.string.gen_not_pitched), Modifier.padding(horizontal = 20.dp, vertical = 12.dp), color = c.textDim,
                fontSize = 14.sp, lineHeight = 20.sp,
            )
            return@AppSheet
        }
        val plan = remember(p, pattern, channel, g.kind, g.progression, g.chordLength, g.chordStyle, g.bassStyle, g.density, g.sevenths, g.fromNotes, g.order, g.step, g.octaves, g.seed) {
            generatorPlan(p, pattern, before, channel, g)
        }
        val key = plan.key
        Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
            Segmented(
                listOf(stringResource(R.string.gen_chords), stringResource(R.string.gen_melody), stringResource(R.string.gen_bass), stringResource(R.string.gen_arp)),
                g.kind, { k -> change { g.kind = k } }, Modifier.padding(horizontal = 16.dp, vertical = 4.dp).fillMaxWidth(), equalWidth = true,
            )

            // ---- the key
            Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 16.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.gen_key), Modifier.weight(1f), color = c.text, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                Chip(keyText(key), { keySheet = true }, icon = Ic.Piano, trailing = Ic.DropDown)
            }
            if (p.scale == 0) {
                Text(
                    stringResource(if (plan.guessed) R.string.gen_key_auto else R.string.gen_key_default, keyText(key)),
                    Modifier.padding(horizontal = 20.dp, vertical = 4.dp), color = c.textDim,
                    fontSize = 12.sp, lineHeight = 16.sp,
                )
            }

            // ---- what is special about each kind
            when (g.kind) {
                Generator.CHORDS -> Choice(
                    stringResource(R.string.gen_rhythm),
                    listOf(stringResource(R.string.gen_held), stringResource(R.string.gen_stabs), stringResource(R.string.gen_pulse)), g.chordStyle,
                ) { v -> change { g.chordStyle = v } }
                Generator.MELODY -> Choice(
                    stringResource(R.string.gen_density),
                    listOf(stringResource(R.string.gen_sparse), stringResource(R.string.gen_medium), stringResource(R.string.gen_busy)), g.density,
                ) { v -> change { g.density = v } }
                Generator.BASS -> Choice(
                    stringResource(R.string.gen_rhythm),
                    listOf(stringResource(R.string.gen_held), stringResource(R.string.gen_pulse), stringResource(R.string.gen_groove)), g.bassStyle,
                ) { v -> change { g.bassStyle = v } }
                else -> {
                    // a channel that holds chords can have them played one note at a time
                    if (plan.chordal) {
                        Choice(
                            stringResource(R.string.gen_source), listOf(stringResource(R.string.gen_src_notes), stringResource(R.string.gen_src_chords)),
                            if (g.fromNotes) 0 else 1,
                        ) { v -> change { g.fromNotes = v == 0 } }
                        if (g.fromNotes) {
                            Text(
                                stringResource(R.string.gen_src_notes_desc, channel.name), Modifier.padding(horizontal = 20.dp), color = c.textDim,
                                fontSize = 12.sp, lineHeight = 16.sp,
                            )
                        }
                    }
                    Choice(
                        stringResource(R.string.gen_order),
                        listOf(stringResource(R.string.gen_up), stringResource(R.string.gen_down), stringResource(R.string.gen_updown), stringResource(R.string.gen_random)),
                        g.order,
                    ) { v -> change { g.order = v } }
                    Choice(stringResource(R.string.gen_step), listOf("1/4", "1/8", stringResource(R.string.gen_triplets), "1/16"), g.step) { v -> change { g.step = v } }
                    Choice(stringResource(R.string.gen_octaves), listOf("1", "2", "3"), g.octaves - 1) { v -> change { g.octaves = v + 1 } }
                }
            }

            // ---- the chords everything follows
            if (!plan.ofNotes) {
                val heard = plan.heard
                val options = ArrayList<String>()
                if (heard != null) options += stringResource(R.string.gen_from_pattern, heard.names())
                val length = p.patternLength(pattern)
                key.progressions.forEach { steps -> options += Harmony.of(key, steps, max(length, steps.size * p.ticksPerBar), p.ticksPerBar, g.sevenths).names() }
                val shift = if (heard != null) 1 else 0
                val chosen = if (g.progression < 0 && heard != null) 0 else g.progression.coerceIn(0, key.progressions.size - 1) + shift
                Choice(stringResource(if (g.kind == Generator.CHORDS) R.string.gen_harmony_chords else R.string.gen_harmony), options, chosen) { v ->
                    change { g.progression = v - shift }
                }
                if (chosen >= shift) {
                    Choice(
                        stringResource(R.string.gen_chord_len),
                        listOf(stringResource(R.string.gen_len_half), stringResource(R.string.gen_len_1), stringResource(R.string.gen_len_2)), g.chordLength,
                    ) { v -> change { g.chordLength = v } }
                }
                SwitchRow(stringResource(R.string.gen_sevenths), stringResource(R.string.gen_sevenths_desc), g.sevenths) { v -> change { g.sevenths = v } }
                if (plan.bars > pattern.bars) {
                    Text(
                        stringResource(R.string.gen_longer, pluralStringResource(R.plurals.bars, plan.bars, plan.bars)),
                        Modifier.padding(horizontal = 20.dp, vertical = 4.dp), color = c.textDim, fontSize = 12.sp, lineHeight = 16.sp,
                    )
                }
            }
            Spacer(Modifier.height(6.dp))
        }

        // ---- what was written, and the buttons; they stay in view while the choices scroll
        Box(Modifier.fillMaxWidth().height(1.dp).background(c.line))
        if (made) {
            val written = studio.project.pattern(pattern.id)?.notesOf(channel.id) ?: emptyList()
            NotesPreview(written, p.patternLength(pattern), p.ticksPerBar, c.channel(channel.color))
            val count = pluralStringResource(R.plurals.notes_count, written.size, written.size)
            Text(
                stringResource(R.string.gen_written, if (plan.ofNotes) count else count + " · " + plan.harmony.names()),
                Modifier.padding(horizontal = 20.dp), color = c.textDim, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        } else if (base.isNotEmpty() && !plan.ofNotes) {
            Text(
                stringResource(R.string.gen_replace_note, channel.name), Modifier.padding(start = 20.dp, end = 20.dp, top = 8.dp), color = c.textDim,
                fontSize = 12.sp, lineHeight = 16.sp,
            )
        }
        Row(
            Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Chip(stringResource(if (studio.playing) R.string.gen_pause else R.string.gen_listen), {
                if (studio.playing) {
                    studio.pause()
                } else {
                    // what was written is in the pattern: that is what has to be heard
                    if (studio.songMode) studio.changeSongMode(false)
                    studio.play()
                }
            }, icon = if (studio.playing) Ic.Stop else Ic.Play)
            if (made) {
                // everything the generator did to the pattern is taken back: the notes, the length, the key it chose
                IconBtn(Ic.Undo, stringResource(R.string.gen_revert), {
                    studio.edit("generate", mergeWindowMs = 3_600_000L) { q ->
                        (if (keyChosen) q else q.copy(keyRoot = keyBefore.first, scale = keyBefore.second)).updatePattern(pattern.id) { before }
                    }
                    ui.roll.scrollY = -1f
                    made = false
                }, background = c.surfaceHigh, size = 36.dp, iconSize = 18.dp)
            }
            Spacer(Modifier.weight(1f))
            BigButton(stringResource(if (made) R.string.gen_again else R.string.gen_make), Ic.Bolt) {
                if (made) g.seed++
                write()
            }
        }
    }
}

/** The one button of a sheet that does what the sheet is for. */
@Composable
internal fun BigButton(text: String, icon: ImageVector?, enabled: Boolean = true, onClick: () -> Unit) {
    val c = Mps.colors
    Row(
        Modifier
            .heightIn(min = 44.dp)
            .clip(RoundedCornerShape(22.dp))
            .background(if (enabled) c.accent else c.surfaceHigh)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val fg = if (enabled) c.onAccent else c.textDim
        if (icon != null) {
            Icon(icon, null, Modifier.size(18.dp), tint = fg)
            Spacer(Modifier.width(8.dp))
        }
        Text(text, color = fg, fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 1)
    }
}

/** A label and its options, of which one is chosen. The options wrap onto as many lines as they need. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun Choice(label: String, options: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    SheetLabel(label)
    FlowRow(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        options.forEachIndexed { i, text -> Chip(text, { onSelect(i) }, selected = i == selected, maxWidth = 340.dp) }
    }
}

/** The notes that were written, small: enough to see their shape. */
@Composable
private fun NotesPreview(notes: List<Note>, length: Int, bar: Int, color: Color) {
    val c = Mps.colors
    val label = stringResource(R.string.gen_title)
    Canvas(
        Modifier
            .padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 6.dp)
            .fillMaxWidth()
            .height(64.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(c.surface)
            .semantics { contentDescription = label },
    ) {
        if (notes.isEmpty() || length <= 0) return@Canvas
        val pad = 6.dp.toPx()
        val w = size.width - 2 * pad
        val h = size.height - 2 * pad
        var at = bar
        while (at < length) {
            val x = pad + w * at / length
            drawLine(c.line, Offset(x, pad), Offset(x, pad + h), 1.dp.toPx())
            at += bar
        }
        val low = notes.minOf { it.pitch }
        val high = notes.maxOf { it.pitch }
        val rows = max(10, high - low + 1)
        val rowH = h / rows
        val top = pad + (rows - (high - low + 1)) / 2f * rowH
        for (n in notes) {
            if (n.start >= length) continue
            val x = pad + w * n.start / length
            val nw = max(2.dp.toPx(), w * n.length / length - 1.dp.toPx())
            drawRoundRect(
                color, Offset(x, top + (high - n.pitch) * rowH), Size(nw, max(2.dp.toPx(), rowH - 0.5f)), CornerRadius(1.dp.toPx()),
                alpha = 0.55f + 0.45f * n.velocity.coerceIn(0f, 1f),
            )
        }
    }
}
