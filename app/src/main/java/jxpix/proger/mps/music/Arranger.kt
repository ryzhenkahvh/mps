package jxpix.proger.mps.music

import androidx.annotation.StringRes
import jxpix.proger.mps.R
import jxpix.proger.mps.engine.instruments.DrumSynth
import jxpix.proger.mps.model.Channel
import jxpix.proger.mps.model.MASTER_ID
import jxpix.proger.mps.model.MAX_PATTERNS
import jxpix.proger.mps.model.MAX_TRACKS
import jxpix.proger.mps.model.Note
import jxpix.proger.mps.model.Pattern
import jxpix.proger.mps.model.Project
import jxpix.proger.mps.model.addPattern
import jxpix.proger.mps.model.addPatternClip
import jxpix.proger.mps.model.updatePattern
import kotlin.math.max

/** The part an instrument plays in a track. Decides in which sections of an arrangement it sounds. */
enum class Role { KICK, SNARE, HATS, PERC, BASS, CHORDS, LEAD }

/** A stretch of a track: how long it is (in bars, for a loop of up to four bars) and what plays in it. */
class Section(
    @param:StringRes val name: Int,
    val bars: Int,
    val roles: Set<Role>,
    /** The kick and the bass stop one bar before the end: the breath before the part that follows hits. */
    val gap: Boolean = false,
)

/** The shape of a whole track: its sections in order. */
class Arrangement(val id: String, @param:StringRes val name: Int, @param:StringRes val desc: Int, val sections: List<Section>)

/** One layer of the cake: the channels that share a track of the playlist, and the pattern made for them. */
class Layer(val role: Role, val channels: List<Int>, val name: String, val color: Int)

/**
 * Lays a loop out as a whole track.
 *
 * The loop is taken apart into layers - a pattern per instrument, each on a track of its own -
 * and the layers are switched on and off section by section the way the chosen shape says. What
 * comes out is ordinary clips: any of them can be moved, cut, muted or deleted afterwards, and a
 * note changed in a layer changes everywhere that layer plays.
 */
object Arranger {
    private val ALL = Role.entries.toSet()
    private fun of(vararg roles: Role): Set<Role> = roles.toSet()

    val templates: List<Arrangement> = listOf(
        Arrangement(
            "song", R.string.arr_song, R.string.arr_song_desc,
            listOf(
                Section(R.string.sec_intro, 8, of(Role.CHORDS, Role.HATS)),
                Section(R.string.sec_verse, 16, of(Role.KICK, Role.SNARE, Role.HATS, Role.BASS, Role.CHORDS)),
                Section(R.string.sec_chorus, 16, ALL),
                Section(R.string.sec_verse, 16, of(Role.KICK, Role.SNARE, Role.HATS, Role.PERC, Role.BASS, Role.CHORDS)),
                Section(R.string.sec_chorus, 16, ALL),
                Section(R.string.sec_outro, 8, of(Role.CHORDS, Role.LEAD)),
            ),
        ),
        Arrangement(
            "dance", R.string.arr_dance, R.string.arr_dance_desc,
            listOf(
                Section(R.string.sec_intro, 16, of(Role.KICK, Role.HATS, Role.PERC)),
                Section(R.string.sec_build, 8, of(Role.KICK, Role.SNARE, Role.HATS, Role.PERC, Role.CHORDS), gap = true),
                Section(R.string.sec_drop, 16, ALL),
                Section(R.string.sec_break, 8, of(Role.CHORDS, Role.LEAD)),
                Section(R.string.sec_build, 8, of(Role.KICK, Role.SNARE, Role.HATS, Role.CHORDS, Role.LEAD), gap = true),
                Section(R.string.sec_drop, 16, ALL),
                Section(R.string.sec_outro, 16, of(Role.KICK, Role.HATS, Role.PERC, Role.BASS)),
            ),
        ),
        Arrangement(
            "beat", R.string.arr_beat, R.string.arr_beat_desc,
            listOf(
                Section(R.string.sec_intro, 4, of(Role.CHORDS, Role.LEAD)),
                Section(R.string.sec_verse, 16, of(Role.KICK, Role.SNARE, Role.HATS, Role.BASS, Role.CHORDS)),
                Section(R.string.sec_hook, 8, ALL),
                Section(R.string.sec_verse, 16, of(Role.KICK, Role.SNARE, Role.HATS, Role.PERC, Role.BASS, Role.LEAD), gap = true),
                Section(R.string.sec_hook, 8, ALL),
                Section(R.string.sec_outro, 4, of(Role.CHORDS, Role.LEAD)),
            ),
        ),
        Arrangement(
            "layers", R.string.arr_layers, R.string.arr_layers_desc,
            listOf(
                Section(R.string.sec_intro, 8, of(Role.CHORDS)),
                Section(R.string.sec_build, 8, of(Role.CHORDS, Role.HATS, Role.PERC)),
                Section(R.string.sec_build, 8, of(Role.CHORDS, Role.HATS, Role.PERC, Role.KICK)),
                Section(R.string.sec_build, 8, of(Role.CHORDS, Role.HATS, Role.PERC, Role.KICK, Role.SNARE, Role.BASS), gap = true),
                Section(R.string.sec_main, 16, ALL),
                Section(R.string.sec_break, 8, of(Role.CHORDS, Role.LEAD, Role.HATS)),
                Section(R.string.sec_main, 16, ALL),
                Section(R.string.sec_outro, 8, of(Role.CHORDS, Role.HATS)),
            ),
        ),
    )

    // what people call their channels and samples; the first match wins
    private val WORDS: List<Pair<Role, List<String>>> = listOf(
        Role.BASS to listOf("bass", "808", "sub", "бас"),
        Role.KICK to listOf("kick", "kik", "бочк"),
        Role.SNARE to listOf("snare", "clap", "rim", "снейр", "малый", "хлоп", "клэп", "римшот"),
        Role.HATS to listOf("hat", "hh", "shaker", "ride", "хэт", "хет", "шейкер", "райд"),
        Role.PERC to listOf("perc", "tom", "conga", "bongo", "cowbell", "crash", "cymbal", "перк", "том", "тарел", "крэш"),
        Role.CHORDS to listOf("pad", "chord", "piano", "keys", "organ", "string", "пэд", "аккорд", "пиано", "клавиш", "орган", "струн"),
        Role.LEAD to listOf("lead", "pluck", "arp", "melod", "bell", "vocal", "vox", "лид", "плак", "арп", "мелод", "колокол", "вокал"),
    )

    /** The part the name of a channel (or of its sample) speaks of, if it speaks of any. */
    fun byName(channel: Channel): Role? {
        val name = (channel.name + " " + (channel.instrument.sample ?: "")).lowercase()
        for ((role, words) in WORDS) if (words.any { it in name }) return role
        return null
    }

    /** What part a channel most likely plays: by what it is, by what it is called and by the notes it has in the loop. */
    fun roleOf(channel: Channel, notes: List<Note>): Role {
        if (channel.isDrum) {
            return when (channel.instrument.param("type").toInt()) {
                DrumSynth.KICK -> Role.KICK
                DrumSynth.BASS808 -> Role.BASS
                DrumSynth.SNARE, DrumSynth.CLAP, DrumSynth.RIM -> Role.SNARE
                DrumSynth.HAT, DrumSynth.OPEN_HAT, DrumSynth.SHAKER -> Role.HATS
                else -> Role.PERC
            }
        }
        byName(channel)?.let { return it }
        if (notes.isEmpty()) return Role.LEAD
        val pitches = notes.map { it.pitch }.sorted()
        // a sound that is always struck at the same pitch is a hit, not a tune
        if (channel.instrument.sample != null && pitches.first() == pitches.last()) return Role.PERC
        val median = pitches[pitches.size / 2]
        // how many notes sound together, on average over the time anything sounds
        var held = 0L
        val edges = notes.flatMap { listOf(it.start, it.end) }.distinct().sorted()
        var sounding = 0L
        for (i in 0 until edges.size - 1) {
            val together = notes.count { it.start <= edges[i] && it.end > edges[i] }
            if (together > 0) {
                held += together.toLong() * (edges[i + 1] - edges[i])
                sounding += edges[i + 1] - edges[i]
            }
        }
        val voices = if (sounding > 0) held.toDouble() / sounding else 1.0
        return when {
            voices >= 1.8 -> Role.CHORDS
            median < 50 -> Role.BASS
            else -> Role.LEAD
        }
    }

    /**
     * The layers of a loop: one for every channel that has notes in it. When there are more
     * channels than [most] tracks to put them on, channels with the same part share a layer.
     */
    fun layers(p: Project, pattern: Pattern, most: Int = MAX_TRACKS): List<Layer> {
        val single = ArrayList<Layer>()
        for (ch in p.channels) {
            if (ch.isAudio) continue
            val notes = pattern.notesOf(ch.id)
            if (notes.isEmpty()) continue
            single += Layer(roleOf(ch, notes), listOf(ch.id), ch.name, ch.color)
        }
        if (single.size <= most) return single
        return single.groupBy { it.role }.map { (role, list) ->
            Layer(role, list.flatMap { it.channels }, list.joinToString(" + ") { it.name }, list.first().color)
        }.sortedBy { layer -> single.indexOfFirst { it.channels.first() == layer.channels.first() } }
    }

    /** How many bars each section takes for a loop of [loopBars] bars: always whole repeats of the loop. */
    fun bars(t: Arrangement, loopBars: Int, short: Boolean): IntArray {
        val loop = max(1, loopBars)
        return IntArray(t.sections.size) { i ->
            val wanted = if (short) max(1, t.sections[i].bars / 2) else t.sections[i].bars
            (wanted + loop - 1) / loop * loop
        }
    }

    // the lightest first: what is left playing when a section asks for parts the loop does not have
    private val LIGHTEST = listOf(Role.CHORDS, Role.LEAD, Role.HATS, Role.PERC, Role.BASS, Role.SNARE, Role.KICK)

    /** Which layer plays in which section: `[section][layer]`. No section is left silent. */
    fun plan(t: Arrangement, layers: List<Layer>): Array<BooleanArray> = Array(t.sections.size) { s ->
        val row = BooleanArray(layers.size) { layers[it].role in t.sections[s].roles }
        if (row.none { it }) {
            val role = LIGHTEST.firstOrNull { r -> layers.any { it.role == r } }
            for (i in layers.indices) row[i] = layers[i].role == role
        }
        row
    }

    /** The tick the arrangement would start at: the beginning, or the first bar after everything that is there. */
    fun startOf(p: Project, replace: Boolean): Int {
        val bar = p.ticksPerBar
        return if (replace) 0 else (p.songEnd + bar - 1) / bar * bar
    }

    /** What stays in the playlist when the arrangement replaces what is there: recordings and other audio. */
    private fun kept(p: Project, replace: Boolean): Project = if (replace) p.copy(clips = p.clips.filter { it.isAudio }) else p

    /** The tracks nothing lies on for the whole stretch the arrangement would take. */
    fun freeTracks(p: Project, replace: Boolean, bars: Int): List<Int> {
        val q = kept(p, replace)
        val from = startOf(p, replace)
        val to = from + bars * p.ticksPerBar
        return (0 until MAX_TRACKS).filter { t -> q.clips.none { it.track == t && it.start < to && it.end > from } }
    }

    /** The pattern that holds exactly this layer of the loop, if there is one already. */
    private fun existing(p: Project, loop: Pattern, layer: Layer, lanes: List<jxpix.proger.mps.model.AutoLane>): Pattern? =
        p.patterns.firstOrNull { cand ->
            cand.bars == loop.bars &&
                cand.notes.filterValues { it.isNotEmpty() }.keys == layer.channels.toSet() &&
                layer.channels.all { cand.notesOf(it) == loop.notesOf(it) } &&
                cand.automation == lanes
        }

    /**
     * Lays the loop out. [on] says which layer plays in which section (see [plan]) and [bars] how
     * long each section is. Returns null when the playlist has no room for it: not enough free
     * tracks, or no room for more patterns.
     */
    fun apply(
        p: Project, patternId: Int, t: Arrangement, layers: List<Layer>, on: Array<BooleanArray>, bars: IntArray, replace: Boolean,
    ): Project? {
        val loop = p.pattern(patternId) ?: return null
        if (layers.isEmpty() || on.size != t.sections.size || bars.size != t.sections.size) return null
        val bar = p.ticksPerBar
        val start = startOf(p, replace)
        val tracks = freeTracks(p, replace, bars.sum())
        if (tracks.size < layers.size) return null
        var q = kept(p, replace)

        // a pattern for every layer; one that is there from an earlier attempt is used again
        val patterns = IntArray(layers.size)
        layers.forEachIndexed { i, layer ->
            val mine = layer.channels.toSet()
            // curves of the master go with the first layer: they have to live somewhere
            val lanes = loop.automation.filter { it.channelId in mine || (i == 0 && it.channelId == MASTER_ID) }
            val found = existing(q, loop, layer, lanes)
            if (found != null) {
                patterns[i] = found.id
            } else {
                if (q.patterns.size >= MAX_PATTERNS) return null
                var name = layer.name
                var n = 2
                while (q.patterns.any { it.name == name }) name = layer.name + " " + n++
                val (added, id) = q.addPattern(name, loop.bars)
                if (id == 0) return null
                q = added.updatePattern(id) { made ->
                    made.copy(color = layer.color, notes = loop.notes.filterKeys { it in mine }.filterValues { it.isNotEmpty() }, automation = lanes)
                }
                patterns[i] = id
            }
        }

        q = q.copy(tracks = max(q.tracks, tracks.take(layers.size).max() + 1))
        var at = start
        t.sections.forEachIndexed { s, section ->
            val length = bars[s] * bar
            layers.forEachIndexed { i, layer ->
                if (!on[s].getOrElse(i) { false }) return@forEachIndexed
                val early = section.gap && bars[s] >= 2 && (layer.role == Role.KICK || layer.role == Role.BASS)
                q = q.addPatternClip(patterns[i], tracks[i], at, if (early) length - bar else length).first
            }
            at += length
        }
        return q
    }
}
