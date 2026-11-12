package jxpix.proger.mps.music

import jxpix.proger.mps.engine.PPQ
import jxpix.proger.mps.engine.TICKS_PER_STEP
import jxpix.proger.mps.engine.instruments.DrumSynth
import jxpix.proger.mps.model.Channel
import jxpix.proger.mps.model.Note
import jxpix.proger.mps.model.Pattern
import jxpix.proger.mps.model.Project
import jxpix.proger.mps.model.Scales
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

/**
 * Writes notes for the user: chords, a bass line, a melody or an arpeggio, all in the key of the
 * song and all following the same chords, so that what is generated on one channel fits what is
 * on the others.
 *
 * Everything here is plain arithmetic on notes: the same seed gives the same notes, and what
 * comes out is ordinary notes in the piano roll that can be edited like any others.
 */
object Generator {
    const val CHORDS = 0
    const val MELODY = 1
    const val BASS = 2
    const val ARP = 3

    // order of an arpeggio
    const val UP = 0
    const val DOWN = 1
    const val UP_DOWN = 2
    const val RANDOM = 3

    /** Lowest note of a bass line: A1. */
    const val BASS_LOW = 33

    /** Where melodies live: C4 to C6. */
    const val MELODY_LOW = 60
    const val MELODY_HIGH = 84

    // ------------------------------------------------------------------------------ chords

    /**
     * The notes of every chord of [h], placed so that each chord is as close to the one before
     * it as possible: the hand stays where it is and only the fingers that must move do.
     */
    fun voicings(h: Harmony, centre: Int = 60): List<IntArray> {
        val out = ArrayList<IntArray>(h.chords.size)
        var previous: IntArray? = null
        for (chord in h.chords) {
            val n = chord.tones.size
            var best: IntArray? = null
            var bestCost = Double.MAX_VALUE
            for (inversion in 0 until n) {
                val bottomClass = (chord.root + chord.tones[inversion]) % 12
                for (lift in 0..1) {
                    val voicing = IntArray(n)
                    var pitch = pitchAtOrAbove(bottomClass, centre - 12) + 12 * lift
                    voicing[0] = pitch
                    for (j in 1 until n) {
                        pitch = pitchAtOrAbove((chord.root + chord.tones[(inversion + j) % n]) % 12, pitch + 1)
                        voicing[j] = pitch
                    }
                    val middle = voicing.average()
                    var cost = 0.25 * abs(middle - centre)
                    val p = previous
                    if (p == null) {
                        cost = abs(middle - centre)
                    } else {
                        for (a in voicing) cost += p.minOf { abs(it - a) }
                        for (b in p) cost += voicing.minOf { abs(it - b) }
                    }
                    if (cost < bestCost - 1e-9) {
                        bestCost = cost
                        best = voicing
                    }
                }
            }
            out += best!!
            previous = best
        }
        return out
    }

    /** One hit of a rhythm: where in the bar, for how long, how hard. */
    private class Hit(val step: Int, val steps: Int, val velocity: Float = 0.78f)

    private val STABS = listOf(
        // 3 + 3 + 2, twice
        listOf(Hit(0, 2, 0.84f), Hit(3, 2), Hit(6, 2), Hit(8, 2, 0.84f), Hit(11, 2), Hit(14, 2)),
        // between the beats
        listOf(Hit(2, 2), Hit(6, 2), Hit(10, 2), Hit(14, 2)),
        // a held first chord and three pushes
        listOf(Hit(0, 4, 0.84f), Hit(6, 2), Hit(10, 3), Hit(14, 2)),
    )

    /** The hits of one bar of [steps] sixteenths for a chord rhythm. */
    private fun chordHits(style: Int, steps: Int, seed: Long): List<Hit> = when {
        style == 1 && steps == 16 -> STABS[pick(seed, STABS.size)]
        style == 1 -> (0 until steps / 4).map { Hit(it * 4 + 2, 2) }
        pick(seed, 2) == 0 -> (0 until steps step 2).map { Hit(it, 2, if (it % 4 == 0) 0.8f else 0.62f) }
        else -> (0 until steps step 4).map { Hit(it, 3, if (it == 0) 0.84f else 0.74f) }
    }

    /** One of [count] variants; seeds that follow each other pick different ones. */
    private fun pick(seed: Long, count: Int): Int = ((seed % count + count) % count).toInt()

    /** Where the chords sit: around middle C, a little higher or a little lower. Changes once every [after] seeds. */
    private fun centre(seed: Long, after: Int): Int = intArrayOf(60, 65, 56)[pick((seed - 1) / after, 3)]

    /**
     * The chords of [h] as notes. [style]: 0 = each chord held for as long as it lasts, 1 = short
     * hits in a rhythm, 2 = an even pulse. The seed chooses between the rhythms of a style and
     * between the places on the keyboard the chords are played at.
     */
    fun chords(h: Harmony, style: Int, bar: Int, seed: Long): List<Note> {
        val steps = bar / TICKS_PER_STEP
        val rhythms = when {
            style == 0 -> 1
            style == 1 -> if (steps == 16) STABS.size else 1
            else -> 2
        }
        val voiced = voicings(h, centre(seed, rhythms))
        val out = ArrayList<Note>()
        if (style == 0) {
            h.chords.forEachIndexed { i, chord -> for (p in voiced[i]) out += Note(chord.start, chord.length, p, 0.72f) }
            return out
        }
        val hits = chordHits(style, steps, seed)
        var barStart = 0
        while (barStart < h.length) {
            for (hit in hits) {
                val at = barStart + hit.step * TICKS_PER_STEP
                if (at >= h.length) continue
                val chord = h.at(at)
                val length = min(hit.steps * TICKS_PER_STEP, chord.end - at)
                if (length <= 0) continue
                for (p in voiced[h.chords.indexOf(chord)]) out += Note(at, length, p, hit.velocity)
            }
            barStart += bar
        }
        return out
    }

    // ------------------------------------------------------------------------------ bass

    private val GROOVES = listOf(
        listOf(Hit(0, 3, 0.9f), Hit(3, 3), Hit(6, 2), Hit(10, 2), Hit(12, 2, 0.84f), Hit(14, 2)),
        listOf(Hit(0, 2, 0.9f), Hit(3, 1), Hit(6, 2), Hit(8, 2, 0.84f), Hit(11, 1), Hit(14, 2)),
        listOf(Hit(0, 4, 0.9f), Hit(6, 2), Hit(8, 3, 0.84f), Hit(11, 1), Hit(12, 2), Hit(14, 2)),
        listOf(Hit(0, 6, 0.9f), Hit(6, 2), Hit(10, 4, 0.84f), Hit(14, 2)),
    )

    /**
     * Where the bass of a channel lives. A pitched drum (the 808) is tuned around the note its
     * steps play, so its bass sits around that note; everything else gets the real bass register.
     */
    fun bassLow(channel: Channel): Int = if (channel.isDrum) channel.rootNote - 6 else BASS_LOW

    /** True for a channel that plays the notes it is given at their pitch: everything but plain drums and recordings. */
    fun pitched(channel: Channel): Boolean =
        !channel.isAudio && (!channel.isDrum || channel.instrument.param("type").toInt() == DrumSynth.BASS808)

    /**
     * A bass line under the chords of [h]. [style]: 0 = the root of each chord, held; 1 = an even
     * pulse of eighth notes; 2 = a groove that leans on the root, colours it with another note of
     * the chord and walks into the next chord.
     */
    fun bass(key: Key, h: Harmony, style: Int, bar: Int, seed: Long, low: Int = BASS_LOW): List<Note> {
        fun bassRoot(chord: Chord): Int = pitchAtOrAbove(chord.root, low)
        val out = ArrayList<Note>()
        if (style == 0) {
            h.chords.forEachIndexed { i, chord ->
                val next = h.chords[(i + 1) % h.chords.size]
                // every other variant leaves the last beat of a chord to a note that leads into the next one
                val lead = if (pick(seed, 2) == 0 && chord.length >= PPQ * 2) PPQ else 0
                out += Note(chord.start, chord.length - lead, bassRoot(chord), 0.86f)
                if (lead > 0) {
                    val root = bassRoot(chord)
                    val pitch = if (next.root == chord.root) root + chord.tones.last() else approach(key, root, bassRoot(next))
                    out += Note(chord.end - lead, lead, pitch, 0.74f)
                }
            }
            return out
        }
        val steps = bar / TICKS_PER_STEP
        val variant = pick(seed, 3)
        val hits: List<Hit> = when {
            style == 1 && variant == 2 -> (0 until steps / 4).map { Hit(it * 4 + 2, 2, 0.84f) }
            style == 1 -> (0 until steps step 2).map { Hit(it, 2, if (it % 4 == 0) 0.86f else 0.72f) }
            steps == 16 -> GROOVES[pick(seed, GROOVES.size)]
            else -> (0 until steps step 4).map { Hit(it, 3, 0.86f) } + Hit(steps - 2, 2)
        }
        // which hit of the bar plays another note of the chord instead of its root
        val colour = if (style == 2 && hits.size > 2) 1 + pick(seed / GROOVES.size, hits.size - 2) else -1
        val gate = if (style == 1) 0.85f else 1f
        var barStart = 0
        while (barStart < h.length) {
            hits.forEachIndexed { i, hit ->
                val at = barStart + hit.step * TICKS_PER_STEP
                if (at >= h.length) return@forEachIndexed
                val chord = h.at(at)
                val root = bassRoot(chord)
                val length = min((hit.steps * TICKS_PER_STEP * gate).toInt(), chord.end - at)
                if (length <= 0) return@forEachIndexed
                val next = h.after(at)
                val lastOfChord = at + hit.steps * TICKS_PER_STEP >= chord.end
                val pitch = when {
                    style == 1 && variant == 1 && hit.step % 4 != 0 -> root + 12
                    style == 2 && lastOfChord && i > 0 && next.root != chord.root -> approach(key, root, bassRoot(next))
                    i == colour -> if (pick(seed / 2, 2) == 0) root + 12 else root + chord.tones.last()
                    else -> root
                }
                out += Note(at, length, pitch, hit.velocity)
            }
            barStart += bar
        }
        return out
    }

    /**
     * The note that leads a bass from one root into the next: a step of the scale away from the
     * root that comes, on the side the line is on. When the roots are neighbours that note is the
     * one already sounding, so the line reaches past and comes back from the other side.
     */
    private fun approach(key: Key, root: Int, target: Int): Int {
        val side = if (target > root) -1 else 1
        val pitch = scaleMove(target, side, key.mask)
        return if (pitch == root) scaleMove(target, -side, key.mask) else pitch
    }

    // ------------------------------------------------------------------------------ melody

    /** One note of a motif: where in the bar it starts, how long it is and how far from the anchor, in steps of the scale. */
    private class Cell(val step: Int, val steps: Int, val offset: Int, val strong: Boolean)

    /** [count] different positions of the bar, the strong ones the more likely. */
    private fun onsets(rng: Random, steps: Int, count: Int, sixteenths: Double, until: Int = steps): IntArray {
        val weight = DoubleArray(steps) { s ->
            when {
                s >= until -> 0.0
                s == 0 -> 3.0
                s % 4 == 0 -> 2.0
                s % 2 == 0 -> 1.2
                else -> sixteenths
            }
        }
        val chosen = ArrayList<Int>()
        if (rng.nextDouble() < 0.8) {
            chosen += 0
            weight[0] = 0.0
        }
        while (chosen.size < count) {
            val total = weight.sum()
            if (total <= 0.0) break
            var r = rng.nextDouble() * total
            var s = 0
            while (s < steps - 1 && (weight[s] <= 0.0 || r >= weight[s])) {
                r -= weight[s]
                s++
            }
            if (weight[s] <= 0.0) break
            chosen += s
            weight[s] = 0.0
        }
        return chosen.sorted().toIntArray()
    }

    private fun motif(rng: Random, steps: Int, count: Int, density: Int, sixteenths: Double, ending: Boolean): List<Cell> {
        val at = onsets(rng, steps, count, sixteenths, if (ending) max(4, steps * 5 / 8) else steps)
        val cells = ArrayList<Cell>()
        // an ending comes home from a neighbour; everything else sets out from home
        var offset = if (ending && at.size > 1) intArrayOf(-2, -1, 1, 2)[rng.nextInt(4)] else 0
        var direction = if (rng.nextBoolean()) 1 else -1
        for (k in at.indices) {
            val room = (if (k + 1 < at.size) at[k + 1] else steps) - at[k]
            // most notes last until the next one; some stop early and leave a breath
            var length = room
            if (!ending || k + 1 < at.size) {
                if (length > 8) length = 8
                if (length >= 2 && rng.nextDouble() < 0.3) length = max(1, length / 2)
            } else {
                length = max(1, room - 2)
            }
            if (k > 0) {
                // a line mostly keeps going the way it went, and turns round at the edge of its range
                if (rng.nextDouble() > 0.62) direction = -direction
                if (offset >= 4) direction = -1
                if (offset <= -3) direction = 1
                val r = rng.nextDouble()
                // few notes take bigger steps; many notes run along the scale
                val size = when (density) {
                    0 -> if (r < 0.3) 1 else if (r < 0.75) 2 else 3
                    1 -> if (r < 0.06) 0 else if (r < 0.66) 1 else if (r < 0.92) 2 else 3
                    else -> if (r < 0.05) 0 else if (r < 0.78) 1 else if (r < 0.96) 2 else 3
                }
                offset = (offset + direction * size).coerceIn(-4, 5)
            }
            if (ending && k == at.size - 1) offset = 0
            val onBeat = at[k] % 4 == 0
            cells += Cell(at[k], length, offset, k == 0 || (onBeat && length >= 4) || (ending && k == at.size - 1))
        }
        return cells
    }

    /**
     * A melody over the chords of [h]: a short motif that comes back from bar to bar, moved to
     * where each chord is, with a second idea in between and an ending. Notes that carry weight
     * are notes of the chord; the rest walk along the scale. [density]: 0 sparse, 1 medium, 2 busy.
     */
    fun melody(key: Key, h: Harmony, density: Int, bar: Int, seed: Long): List<Note> {
        val rng = Random(seed)
        val steps = max(4, bar / TICKS_PER_STEP)
        val bars = (h.length + bar - 1) / bar
        val sixteenths = when (density) {
            0 -> 0.04
            1 -> 0.3
            else -> 0.9
        }
        fun count(): Int {
            val inFourFour = when (density) {
                0 -> rng.nextInt(2, 5)
                1 -> rng.nextInt(4, 7)
                else -> rng.nextInt(7, 11)
            }
            return min(steps, max(1, inFourFour * steps / 16))
        }
        val first = motif(rng, steps, count(), density, sixteenths, false)
        val second = motif(rng, steps, count(), density, sixteenths, false)
        val turn = motif(rng, steps, max(2, count() * 2 / 3), density, sixteenths, true)
        val close = motif(rng, steps, max(2, count() / 2), density, sixteenths, true)

        val out = ArrayList<Note>()
        var anchor = (MELODY_LOW + MELODY_HIGH) / 2 + rng.nextInt(-3, 4)
        for (b in 0 until bars) {
            val barStart = b * bar
            val chord = h.at(barStart)
            anchor = chordTone(anchor, chord).coerceToRange()
            val cells = when {
                b == bars - 1 && bars > 1 -> close
                b % 4 == 3 -> turn
                b % 2 == 1 -> second
                else -> first
            }
            var before: Cell? = null
            var beforePitch = 0
            for (cell in cells) {
                val at = barStart + cell.step * TICKS_PER_STEP
                if (at >= h.length) continue
                var pitch = scaleMove(anchor, cell.offset, key.mask)
                if (cell.strong) pitch = chordTone(pitch, h.at(at))
                // the line moved but the nearest note of the chord is the one just played: take the next one that way
                val b = before
                if (b != null && pitch == beforePitch && cell.offset != b.offset) {
                    val way = if (cell.offset > b.offset) 1 else -1
                    if (cell.strong) {
                        pitch += way
                        while (!h.at(at).has(pitch)) pitch += way
                    } else {
                        pitch = scaleMove(pitch, way, key.mask)
                    }
                }
                pitch = pitch.coerceToRange()
                before = cell
                beforePitch = pitch
                val velocity = when {
                    cell.step % 4 == 0 -> 0.82f
                    cell.step % 2 == 0 -> 0.72f
                    else -> 0.64f
                } + (rng.nextFloat() - 0.5f) * 0.06f
                out += Note(at, min(cell.steps * TICKS_PER_STEP, h.length - at), pitch, velocity)
            }
        }
        return out
    }

    private fun Int.coerceToRange(): Int {
        var p = this
        while (p < MELODY_LOW) p += 12
        while (p > MELODY_HIGH) p -= 12
        return p
    }

    /** The note of [chord] closest to [pitch]; of two equally close ones the lower. */
    fun chordTone(pitch: Int, chord: Chord): Int {
        for (d in 0..6) {
            if (chord.has(pitch - d)) return pitch - d
            if (chord.has(pitch + d)) return pitch + d
        }
        return pitch
    }

    /** [steps] notes of the scale up (or down, when negative) from [pitch], which is first moved onto the scale. */
    fun scaleMove(pitch: Int, steps: Int, mask: Int): Int {
        if (mask and Scales.ALL_NOTES == 0) return pitch + steps * 2
        var p = Scales.nearest(pitch, mask)
        var left = abs(steps)
        val direction = if (steps < 0) -1 else 1
        while (left > 0) {
            p += direction
            if (Scales.contains(mask, p)) left--
        }
        return p
    }

    // ------------------------------------------------------------------------------ arpeggio

    private class Arp(val order: Int, val step: Int, val octaves: Int, val rng: Random, val turn: Int = 0) {
        private var n = turn
        private var last = -1

        /** The figure begins again - from its first note, or [turn] notes into it. */
        fun restart() {
            n = turn
            last = -1
        }

        fun next(notes: IntArray): Int {
            val size = notes.size * octaves
            fun at(i: Int) = notes[i % notes.size] + 12 * (i / notes.size)
            val i = when (order) {
                DOWN -> size - 1 - n % size
                UP_DOWN -> {
                    val period = max(1, 2 * size - 2)
                    val m = n % period
                    if (m < size) m else period - m
                }
                RANDOM -> {
                    var r = rng.nextInt(size)
                    if (size > 1 && r == last) r = (r + 1 + rng.nextInt(size - 1)) % size
                    r
                }
                else -> n % size
            }
            n++
            last = i
            return min(at(i), 120)
        }
    }

    private fun emit(out: MutableList<Note>, arp: Arp, notes: IntArray, from: Int, to: Int, velocity: Float): Int {
        var at = from
        while (at < to) {
            val length = min(max(arp.step * 4 / 5, 20), to - at)
            out += Note(at, length, arp.next(notes), if (at % PPQ == 0) velocity else velocity * 0.86f)
            at += arp.step
        }
        return at
    }

    /**
     * The chords of [h] played one note at a time; the figure starts again with every chord.
     * The seed moves the chords to another place on the keyboard, which changes the note each begins on.
     */
    fun arpeggio(h: Harmony, order: Int, step: Int, octaves: Int, seed: Long): List<Note> {
        val voiced = voicings(h, centre(seed, 1) + 4)
        val arp = Arp(order, max(30, step), octaves.coerceIn(1, 4), Random(seed))
        val out = ArrayList<Note>()
        h.chords.forEachIndexed { i, chord ->
            // the same chord again goes on where the figure was
            if (i == 0 || !chord.sameAs(h.chords[i - 1])) arp.restart()
            emit(out, arp, voiced[i], chord.start, chord.end, 0.8f)
        }
        return out
    }

    /**
     * Turns held notes into an arpeggio: while a set of notes sounds, they are played one after
     * another instead of together. Whenever the set changes the figure starts again. A short gap
     * between two chords (chords played as stabs) is not a rest: the chord goes on until the next
     * one begins; a silence of two beats or more stays silent. The seed says which note of the
     * figure comes first: 1 = the figure as it is, 2 = from its second note...
     */
    fun arpeggiate(source: List<Note>, length: Int, order: Int, step: Int, octaves: Int, seed: Long): List<Note> {
        val most = source.groupBy { it.start }.values.maxOfOrNull { it.size } ?: 1
        val arp = Arp(order, max(30, step), octaves.coerceIn(1, 4), Random(seed), pick(seed - 1, max(1, most)))
        val edges = sortedSetOf(0, length)
        for (n in source) {
            if (n.start in 0 until length) edges += n.start
            if (n.end in 1 until length) edges += n.end
        }
        val out = ArrayList<Note>()
        val times = edges.toIntArray()
        val first = source.filter { it.start in 0 until length }.minOfOrNull { it.start } ?: return out
        var previous: IntArray? = null
        var velocity = 0.8f
        var next = 0
        for (i in 0 until times.size - 1) {
            val from = times[i]
            val to = times[i + 1]
            val sounding = source.filter { it.start <= from && it.end > from }
            val pitches: IntArray
            if (sounding.isEmpty()) {
                // the silence at the end of the pattern runs on into its beginning
                val gap = if (to == length) to - from + first else to - from
                val held = previous
                if (held == null || gap >= PPQ * 2) {
                    previous = null
                    continue
                }
                pitches = held
            } else {
                pitches = sounding.map { it.pitch }.distinct().sorted().toIntArray()
                velocity = (sounding.sumOf { it.velocity.toDouble() } / sounding.size).toFloat()
            }
            if (previous == null || !previous.contentEquals(pitches) || next < from) {
                arp.restart()
                next = from
            }
            if (next < to) next = emit(out, arp, pitches, next, to, velocity)
            previous = pitches
        }
        return out
    }

    // ------------------------------------------------------------------------------ the pattern around

    /** True for a channel whose notes are pitches of a tune (not drums, not a recording). */
    fun melodic(channel: Channel, notes: List<Note>): Boolean = when {
        !pitched(channel) -> false
        channel.isDrum -> notes.map { it.pitch }.distinct().size >= 2
        // a sampler may hold a one-shot: only a spread of notes shows that it plays a tune
        channel.instrument.sample != null || channel.instrument.type == "sampler" -> notes.map { it.pitch }.distinct().size >= 3
        else -> true
    }

    /**
     * The notes of the melodic channels of a pattern, [except] one, as they sound when the pattern
     * starts at [origin] of the song: what falls into [from, to) is added to [out], timed from [zero].
     */
    private fun heard(p: Project, pattern: Pattern, except: Int, origin: Int, from: Int, to: Int, zero: Int, out: MutableList<Note>) {
        val length = p.patternLength(pattern)
        for (ch in p.channels) {
            if (ch.id == except) continue
            val notes = pattern.notesOf(ch.id)
            if (notes.isEmpty() || !melodic(ch, notes)) continue
            for (n in notes) {
                if (n.start >= length) continue
                val start = max(origin + n.start, from)
                val end = min(origin + n.end, to)
                if (end > start) out += n.copy(start = start - zero, length = end - start)
            }
        }
    }

    /**
     * What sounds together with a pattern, in the time of the pattern: the notes of its other
     * melodic channels ([except] is the channel being written) and of the patterns that play
     * alongside it in the playlist. A pattern that is not in the playlist yet, or plays there
     * alone, is heard against the place where the song first plays a tune.
     */
    fun surroundings(p: Project, pattern: Pattern, except: Int): List<Note> {
        val length = p.patternLength(pattern)
        val out = ArrayList<Note>()
        heard(p, pattern, except, 0, 0, length, 0, out)
        // the patterns that have a tune to be heard
        val tuneful = p.patterns.filter { other ->
            other.id != pattern.id && p.channels.any { ch -> ch.id != except && other.notesOf(ch.id).let { it.isNotEmpty() && melodic(ch, it) } }
        }.map { it.id }.toSet()
        val others = p.clips.filter { !it.isAudio && !it.muted && it.length > 0 && it.patternId in tuneful }
        if (others.isEmpty()) return out
        // where to listen: at the clips of the pattern itself, the earliest first, then where the song first plays a tune
        val places = p.clips.filter { !it.isAudio && it.patternId == pattern.id }.map { it.start }.sorted() + others.minOf { it.start }
        for (from in places.distinct()) {
            val to = from + length
            val along = ArrayList<Note>()
            for (clip in others) {
                if (clip.end <= from || clip.start >= to) continue
                val other = p.pattern(clip.patternId) ?: continue
                val otherLength = p.patternLength(other)
                if (otherLength <= 0) continue
                // every repeat of the pattern inside the clip that reaches into the window
                var repeat = clip.start + max(0, (from - clip.start) / otherLength) * otherLength
                while (repeat < min(clip.end, to)) {
                    heard(p, other, except, repeat, max(from, clip.start), min(to, clip.end), from, along)
                    repeat += otherLength
                }
            }
            if (along.isNotEmpty()) {
                out += along
                break
            }
        }
        return out
    }

    /** Every note of every melodic channel of the project: what the key of the song is guessed from. */
    fun everything(p: Project): List<Note> {
        val out = ArrayList<Note>()
        for (pattern in p.patterns) for (ch in p.channels) {
            val notes = pattern.notesOf(ch.id)
            if (notes.isNotEmpty() && melodic(ch, notes)) out += notes
        }
        return out
    }
}
