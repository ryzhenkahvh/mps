package jxpix.proger.mps.model

import androidx.annotation.StringRes
import jxpix.proger.mps.R
import jxpix.proger.mps.engine.Devices
import jxpix.proger.mps.engine.PPQ
import jxpix.proger.mps.engine.TICKS_PER_STEP
import jxpix.proger.mps.engine.instruments.DrumSynth

/** A ready-made starting point shown on the home screen. */
class Template(
    val id: String,
    @param:StringRes val nameRes: Int,
    @param:StringRes val descRes: Int,
    val bpm: Int,
    /** Index into the channel colour palette, used for the template card. */
    val color: Int,
    val build: (str: (Int) -> String) -> Project,
)

/**
 * Builds new projects: the blank project, the genre templates and the demo song.
 * Names are resolved through [str] so that channels are created in the user's language.
 */
object ProjectFactory {

    const val DEMO_ID = "demo"

    val templates: List<Template> = listOf(
        Template("empty", R.string.tpl_empty, R.string.tpl_empty_desc, 120, 0) { empty(it) },
        Template("house", R.string.tpl_house, R.string.tpl_house_desc, 124, 5) { house(it) },
        Template("trap", R.string.tpl_trap, R.string.tpl_trap_desc, 140, 6) { trap(it) },
        Template("lofi", R.string.tpl_lofi, R.string.tpl_lofi_desc, 82, 2) { lofi(it) },
        Template(DEMO_ID, R.string.tpl_demo, R.string.tpl_demo_desc, 122, 7) { demo(it) },
    )

    fun template(id: String): Template? = templates.firstOrNull { it.id == id }

    // ---------------------------------------------------------------------------------- note helpers

    private const val S = TICKS_PER_STEP      // 16th
    private const val E = PPQ / 2             // 8th
    private const val Q = PPQ                 // quarter
    private const val H = PPQ * 2             // half
    private const val BAR = PPQ * 4

    /**
     * Step-sequencer notation: one character per 16th note.
     * `X` accent, `x` normal, `o` soft, anything else is a rest. Spaces and `|` are ignored.
     */
    private fun steps(spec: String, pitch: Int = 60, length: Int = S): List<Note> {
        val out = ArrayList<Note>()
        var step = 0
        for (c in spec) {
            if (c == ' ' || c == '|') continue
            val velocity = when (c) {
                'X' -> 1f
                'x' -> 0.8f
                'o' -> 0.5f
                else -> 0f
            }
            if (velocity > 0f) out += Note(step * S, length, pitch, velocity)
            step++
        }
        return out
    }

    /** Builds a monophonic line or chord sequence by appending notes one after another. */
    private class Line(var t: Int = 0) {
        val notes = ArrayList<Note>()

        fun n(pitch: Int, length: Int, velocity: Float = 0.8f, gate: Float = 0.92f): Line {
            notes += Note(t, (length * gate).toInt().coerceAtLeast(1), pitch, velocity)
            t += length
            return this
        }

        fun chord(length: Int, velocity: Float, gate: Float, vararg pitches: Int): Line {
            for (p in pitches) notes += Note(t, (length * gate).toInt().coerceAtLeast(1), p, velocity)
            t += length
            return this
        }

        fun rest(length: Int): Line {
            t += length
            return this
        }
    }

    // ---------------------------------------------------------------------------------- builder

    private class Builder(val str: (Int) -> String) {
        private var nextId = 1
        val channels = ArrayList<Channel>()
        val patterns = ArrayList<Pattern>()
        val clips = ArrayList<Clip>()

        fun id(): Int = nextId++

        fun fx(type: String, vararg values: Pair<String, Float>): DeviceState {
            val t = Devices[type] ?: error("Unknown effect $type")
            val map = HashMap(t.defaults())
            for ((k, v) in values) {
                require(t.spec(k) != null) { "Unknown parameter $k of $type" }
                map[k] = v
            }
            return DeviceState(id(), type, map)
        }

        fun drum(
            @StringRes name: Int, type: Int, color: Int, volume: Float = 0.8f, pan: Float = 0f, cut: Int = 0,
            effects: List<DeviceState> = emptyList(), root: Int = 60, vararg values: Pair<String, Float>,
        ): Channel {
            val map = HashMap(DrumSynth.defaultsFor(type))
            for ((k, v) in values) map[k] = v
            val ch = Channel(
                id = id(), name = str(name), color = color,
                instrument = DeviceState(id(), Devices.DRUM.id, map),
                volume = volume, pan = pan, effects = effects, cutGroup = cut, rootNote = root,
            )
            channels += ch
            return ch
        }

        fun synth(
            @StringRes name: Int, preset: String, color: Int, volume: Float = 0.75f, pan: Float = 0f,
            effects: List<DeviceState> = emptyList(), root: Int = 60, vararg values: Pair<String, Float>,
        ): Channel {
            val map = HashMap(Presets.synth(preset).params)
            for ((k, v) in values) map[k] = v
            val ch = Channel(
                id = id(), name = str(name), color = color,
                instrument = DeviceState(id(), Devices.SYNTH.id, map, preset = preset),
                volume = volume, pan = pan, effects = effects, rootNote = root,
            )
            channels += ch
            return ch
        }

        fun pattern(name: String, bars: Int, color: Int, vararg parts: Pair<Channel, List<Note>>): Pattern {
            val notes = LinkedHashMap<Int, List<Note>>()
            for ((ch, list) in parts) notes[ch.id] = (notes[ch.id] ?: emptyList()) + list
            val p = Pattern(id(), name, color, bars, notes)
            patterns += p
            return p
        }

        fun clip(p: Pattern, track: Int, bar: Int, bars: Int) {
            clips += Clip(id(), track, bar * BAR, bars * BAR, patternId = p.id)
        }

        fun build(
            name: String, bpm: Float, swing: Float = 0f, master: List<DeviceState> = emptyList(), tracks: Int = 6,
        ): Project = Project(
            name = name, bpm = bpm, swing = swing, masterEffects = master,
            channels = channels, patterns = patterns, clips = clips,
            tracks = maxOf(tracks, (clips.maxOfOrNull { it.track } ?: 0) + 1),
            loopEnd = 4, nextId = nextId,
        )

        fun patternName(n: Int) = str(R.string.pattern_n).replace("%d", n.toString()).replace("%1\$d", n.toString())
    }

    // ---------------------------------------------------------------------------------- templates

    /** Fader position for a level in decibels (0 dB = unity gain). */
    private fun db(level: Float): Float = 0.8f * Math.pow(10.0, level / 60.0).toFloat()

    /** Colour indexes (see the channel palette in the theme). */
    private const val PINK = 0
    private const val ORANGE = 1
    private const val YELLOW = 2
    private const val GREEN = 3
    private const val TEAL = 4
    private const val BLUE = 5
    private const val VIOLET = 6
    private const val MAGENTA = 7

    fun empty(str: (Int) -> String): Project = Builder(str).run {
        drum(R.string.ch_kick, DrumSynth.KICK, PINK)
        drum(R.string.ch_snare, DrumSynth.SNARE, ORANGE)
        drum(R.string.ch_clap, DrumSynth.CLAP, ORANGE, volume = db(-3f))
        drum(R.string.ch_hat, DrumSynth.HAT, YELLOW, volume = db(-2f), cut = 1)
        drum(R.string.ch_open_hat, DrumSynth.OPEN_HAT, YELLOW, volume = db(-3f), cut = 1)
        synth(R.string.ch_bass, "deep_bass", BLUE, volume = db(-1f), root = 45)
        synth(
            R.string.ch_keys, "e_piano", VIOLET, volume = db(-2f),
            effects = listOf(fx("reverb", "mix" to 0.2f, "size" to 0.45f)),
        )
        pattern(patternName(1), 1, PINK)
        build(str(R.string.new_project_name), 120f)
    }

    fun house(str: (Int) -> String): Project = Builder(str).run {
        val kick = drum(R.string.ch_kick, DrumSynth.KICK, PINK)
        val clap = drum(R.string.ch_clap, DrumSynth.CLAP, ORANGE, volume = db(-3f),
            effects = listOf(fx("reverb", "mix" to 0.15f, "size" to 0.35f)))
        val hat = drum(R.string.ch_hat, DrumSynth.HAT, YELLOW, volume = db(-2f), cut = 1)
        val open = drum(R.string.ch_open_hat, DrumSynth.OPEN_HAT, YELLOW, volume = db(-3f), cut = 1, values = arrayOf("decay" to 0.35f))
        drum(R.string.ch_snare, DrumSynth.SNARE, ORANGE, volume = db(-3f))
        val bass = synth(R.string.ch_bass, "deep_bass", BLUE, volume = db(-1f), root = 45,
            effects = listOf(fx("pump", "depth" to 0.55f)))
        val chords = synth(R.string.ch_chords, "warm_chords", VIOLET, volume = db(-4f),
            effects = listOf(fx("pump", "depth" to 0.7f), fx("reverb", "mix" to 0.25f)))
        synth(R.string.ch_pluck, "pluck", TEAL, volume = db(-2f),
            effects = listOf(fx("delay", "sync" to 6f, "mix" to 0.25f)))

        val stab = Line()
        // Am7 stabs on the classic "3-3-2" rhythm
        for (bar in 0 until 2) {
            val c = if (bar == 0) intArrayOf(57, 60, 64, 67) else intArrayOf(53, 57, 60, 64)
            stab.chord(3 * S, 0.8f, 0.6f, *c).chord(3 * S, 0.7f, 0.6f, *c).chord(2 * S, 0.75f, 0.7f, *c)
                .chord(3 * S, 0.7f, 0.6f, *c).chord(3 * S, 0.7f, 0.6f, *c).chord(2 * S, 0.75f, 0.7f, *c)
        }
        pattern(
            patternName(1), 2, PINK,
            kick to steps("x...x...x...x...|x...x...x...x..."),
            clap to steps("....X.......X...|....X.......X..."),
            hat to steps("x..ox..ox..ox..o|x..ox..ox..oxoxo"),
            open to steps("..x...x...x...x.|..x...x...x...x."),
            bass to (offbeatBass(45, 0) + offbeatBass(41, BAR)),
            chords to stab.notes,
        )
        build(str(R.string.tpl_house), 124f, master = listOf(masterGlue()))
    }

    fun trap(str: (Int) -> String): Project = Builder(str).run {
        val kick = drum(R.string.ch_kick, DrumSynth.KICK, PINK, volume = db(-1f), values = arrayOf("decay" to 0.25f, "punch" to 0.7f))
        val sub = drum(R.string.ch_808, DrumSynth.BASS808, BLUE, volume = db(-4f), root = 57,
            values = arrayOf("decay" to 0.6f, "tone" to 0.55f, "drive" to 0.5f))
        val snare = drum(R.string.ch_snare, DrumSynth.SNARE, ORANGE, volume = db(-2f),
            effects = listOf(fx("reverb", "mix" to 0.18f, "size" to 0.4f)))
        val clap = drum(R.string.ch_clap, DrumSynth.CLAP, ORANGE, volume = db(-5f))
        val hat = drum(R.string.ch_hat, DrumSynth.HAT, YELLOW, volume = db(-1f), cut = 1, values = arrayOf("decay" to 0.2f))
        val open = drum(R.string.ch_open_hat, DrumSynth.OPEN_HAT, YELLOW, volume = db(-3f), cut = 1)
        val bell = synth(R.string.ch_bell, "bell", TEAL, volume = db(0f),
            effects = listOf(fx("delay", "sync" to 6f, "mix" to 0.22f), fx("reverb", "mix" to 0.3f, "size" to 0.6f)))
        synth(R.string.ch_pad, "soft_pad", VIOLET, volume = db(-5f), effects = listOf(fx("reverb", "mix" to 0.35f)))

        // hi-hats: straight 8ths with a 16th pick-up and a 32nd-note roll at the end
        val hats = ArrayList(steps("x.x.x.x.x.x.x.xo|x.x.x.x.x.x....."))
        for (k in 0 until 8) hats += Note(BAR + 3 * Q + k * (S / 2), S / 2, 60, 0.35f + 0.08f * k)

        val bass = Line()
            .n(57, 6 * S, 0.9f).n(57, 4 * S, 0.8f).n(60, 6 * S, 0.85f)
            .n(55, 3 * S, 0.9f).n(55, 7 * S, 0.8f).n(53, 4 * S, 0.85f).n(52, 2 * S, 0.8f)

        val melody = Line()
            .n(76, 3 * E, 0.8f).n(72, 3 * E, 0.7f).n(71, Q, 0.7f)
            .n(69, 3 * E, 0.8f).n(72, 3 * E, 0.7f).n(71, E, 0.65f).n(67, E, 0.6f)

        pattern(
            patternName(1), 2, PINK,
            kick to steps("x.....x...x.....|x..x......x...x."),
            sub to bass.notes,
            snare to steps("........X.......|........X......."),
            clap to steps("........x.......|........x......."),
            hat to hats,
            open to steps("................|............x..."),
            bell to melody.notes,
        )
        build(str(R.string.tpl_trap), 140f, master = listOf(masterGlue()))
    }

    fun lofi(str: (Int) -> String): Project = Builder(str).run {
        val kick = drum(R.string.ch_kick, DrumSynth.KICK, PINK, volume = db(-1f), values = arrayOf("tone" to 0.2f, "decay" to 0.4f))
        val snare = drum(R.string.ch_snare, DrumSynth.SNARE, ORANGE, volume = db(-2f), values = arrayOf("tone" to 0.4f),
            effects = listOf(fx("reverb", "mix" to 0.15f, "size" to 0.3f)))
        val hat = drum(R.string.ch_hat, DrumSynth.HAT, YELLOW, volume = db(-1f), cut = 1, values = arrayOf("tone" to 0.45f))
        drum(R.string.ch_rim, DrumSynth.RIM, GREEN, volume = db(-6f))
        val bass = synth(R.string.ch_bass, "sub_bass", BLUE, volume = db(-9f), root = 45)
        val keys = synth(R.string.ch_keys, "e_piano", VIOLET, volume = db(0f), effects = listOf(
            fx("chorus", "mix" to 0.35f),
            fx("crush", "bits" to 11f, "rate" to 2.2f, "mix" to 0.5f),
            fx("reverb", "mix" to 0.28f, "size" to 0.5f),
        ))

        val chords = Line()
            .chord(H, 0.7f, 0.95f, 50, 53, 57, 60, 64)   // Dm9
            .chord(H, 0.65f, 0.95f, 43, 53, 59, 64)      // G13
            .chord(H, 0.7f, 0.95f, 48, 52, 55, 59, 62)   // Cmaj9
            .chord(H, 0.65f, 0.95f, 45, 55, 60, 64)      // Am7
        val line = Line()
            .n(38, 3 * E, 0.85f).n(38, E, 0.7f).n(43, 3 * E, 0.85f).n(43, E, 0.7f)
            .n(36, 3 * E, 0.85f).n(36, E, 0.7f).n(45, 3 * E, 0.85f).n(40, E, 0.7f)

        pattern(
            patternName(1), 2, PINK,
            kick to steps("x.........x.....|x......x..x....."),
            snare to steps("....x.......x...|....x.......x..o"),
            hat to steps("x.x.x.xox.x.x.xo|x.x.x.xox.x.xoxo"),
            bass to line.notes,
            keys to chords.notes,
        )
        build(
            str(R.string.tpl_lofi), 82f, swing = 0.45f,
            master = listOf(fx("filter", "type" to 0f, "cutoff" to 8000f, "resonance" to 0.05f), masterGlue()),
        )
    }

    private fun Builder.masterGlue(): DeviceState =
        fx("comp", "threshold" to -14f, "ratio" to 2f, "attack" to 15f, "release" to 160f, "makeup" to 2f)

    /** Four off-beat 8th notes: the bass answers the kick drum. */
    private fun offbeatBass(root: Int, at: Int): List<Note> {
        val l = Line(at)
        for (beat in 0 until 4) {
            l.rest(E)
            l.n(if (beat == 3) root + 12 else root, E, if (beat == 3) 0.7f else 0.85f, 0.8f)
        }
        return l.notes
    }

    // ---------------------------------------------------------------------------------- demo song

    /**
     * "Pink Horizon" - a one minute melodic house track that shows what the app can do:
     * patterns arranged in the playlist, synthesized drums, bass, chords, an arpeggio and a lead,
     * every channel with its own effect chain.
     */
    fun demo(str: (Int) -> String): Project = Builder(str).run {
        val kick = drum(R.string.ch_kick, DrumSynth.KICK, PINK)
        val clap = drum(R.string.ch_clap, DrumSynth.CLAP, ORANGE, volume = db(-3f),
            effects = listOf(fx("reverb", "mix" to 0.16f, "size" to 0.35f)))
        val hat = drum(R.string.ch_hat, DrumSynth.HAT, YELLOW, volume = db(-2f), cut = 1)
        val open = drum(R.string.ch_open_hat, DrumSynth.OPEN_HAT, YELLOW, volume = db(-3f), cut = 1, values = arrayOf("decay" to 0.35f))
        val snare = drum(R.string.ch_snare, DrumSynth.SNARE, ORANGE, volume = db(-4f),
            effects = listOf(fx("reverb", "mix" to 0.2f, "size" to 0.45f)))
        val crash = drum(R.string.ch_crash, DrumSynth.CRASH, GREEN, volume = db(-5f),
            effects = listOf(fx("reverb", "mix" to 0.3f, "size" to 0.7f)))
        val bass = synth(R.string.ch_bass, "deep_bass", BLUE, volume = db(-1f), root = 45,
            effects = listOf(fx("pump", "depth" to 0.5f)))
        val chords = synth(R.string.ch_chords, "warm_chords", VIOLET, volume = db(-5f), effects = listOf(
            fx("chorus", "mix" to 0.4f),
            fx("pump", "depth" to 0.75f),
            fx("reverb", "mix" to 0.3f, "size" to 0.6f),
        ))
        val pluck = synth(R.string.ch_pluck, "pluck", TEAL, volume = db(-1f), pan = -0.2f, effects = listOf(
            fx("delay", "sync" to 6f, "feedback" to 0.45f, "mix" to 0.28f),
            fx("reverb", "mix" to 0.2f, "size" to 0.5f),
        ))
        val lead = synth(R.string.ch_lead, "mono_lead", MAGENTA, volume = db(-5f), pan = 0.1f, effects = listOf(
            fx("delay", "sync" to 5f, "feedback" to 0.35f, "mix" to 0.2f),
            fx("reverb", "mix" to 0.25f, "size" to 0.6f),
        ))

        // ---- drums ----
        val beat = pattern(
            str(R.string.pat_beat), 2, PINK,
            kick to steps("x...x...x...x...|x...x...x...x..."),
            clap to steps("....X.......X...|....X.......X..."),
            hat to steps("x..ox..ox..ox..o|x..ox..ox..oxoxo"),
            open to steps("..x...x...x...x.|..x...x...x...x."),
        )
        val build = pattern(
            str(R.string.pat_build), 1, ORANGE,
            kick to steps("x...x...x...x..."),
            hat to steps("x.oox.oox.oox.oo"),
        )
        val roll = ArrayList<Note>()
        for (i in 0 until 8) roll += Note(8 * S + i * S, S, 60, 0.35f + 0.09f * i)
        val fill = pattern(
            str(R.string.pat_fill), 1, YELLOW,
            snare to (steps("x.....x.") + roll),
            kick to steps("x...x..."),
        )
        val hit = pattern(str(R.string.pat_crash), 1, GREEN, crash to steps("X"))

        // ---- harmony: Am7 | Fmaj7 | C | G ----
        val harmony = Line()
            .chord(BAR, 0.75f, 0.97f, 57, 60, 64, 67)
            .chord(BAR, 0.75f, 0.97f, 53, 57, 60, 64)
            .chord(BAR, 0.75f, 0.97f, 55, 60, 64, 67)
            .chord(BAR, 0.75f, 0.97f, 55, 59, 62, 67)
        val chordPattern = pattern(str(R.string.pat_chords), 4, VIOLET, chords to harmony.notes)

        val bassPattern = pattern(
            str(R.string.pat_bass), 4, BLUE,
            bass to (offbeatBass(45, 0) + offbeatBass(41, BAR) + offbeatBass(48, 2 * BAR) + offbeatBass(43, 3 * BAR)),
        )

        val arp = Line()
        val shapes = arrayOf(
            intArrayOf(76, 72, 69, 72),
            intArrayOf(77, 72, 69, 72),
            intArrayOf(76, 72, 67, 72),
            intArrayOf(74, 71, 67, 71),
        )
        for (shape in shapes) for (i in 0 until 8) arp.n(shape[i % 4], E, if (i % 4 == 0) 0.85f else 0.65f, 0.7f)
        val arpPattern = pattern(str(R.string.pat_arp), 4, TEAL, pluck to arp.notes)

        val melody = Line()
            .n(76, 3 * E, 0.85f).n(74, E, 0.7f).n(72, Q, 0.8f).n(69, Q, 0.75f)
            .n(72, 3 * E, 0.8f).n(69, E, 0.7f).n(72, H, 0.8f, 0.8f)
            .n(76, 3 * E, 0.85f).n(79, E, 0.8f).n(76, Q, 0.8f).n(74, Q, 0.75f)
            .n(74, H, 0.8f).n(71, Q, 0.75f).n(74, Q, 0.75f, 0.8f)
        val leadPattern = pattern(str(R.string.pat_lead), 4, MAGENTA, lead to melody.notes)

        // ---- arrangement (bars) ----
        // track 0 drums, 1 bass, 2 chords, 3 arp, 4 lead, 5 fills & crashes
        clip(chordPattern, 2, 0, 32)
        clip(arpPattern, 3, 0, 16)
        clip(arpPattern, 3, 20, 12)

        clip(build, 0, 4, 3)
        clip(fill, 5, 7, 1)

        clip(hit, 5, 8, 1)
        clip(beat, 0, 8, 8)
        clip(bassPattern, 1, 8, 8)
        clip(leadPattern, 4, 12, 4)

        clip(hit, 5, 16, 1)
        clip(leadPattern, 4, 16, 4)
        clip(fill, 5, 19, 1)

        clip(hit, 5, 20, 1)
        clip(beat, 0, 20, 8)
        clip(bassPattern, 1, 20, 8)
        clip(leadPattern, 4, 20, 8)

        clip(hit, 5, 28, 1)

        build(str(R.string.demo_name), 122f, master = listOf(masterGlue())).copy(loopEnd = 8)
    }
}
