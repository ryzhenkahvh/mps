package jxpix.proger.mps.music

import jxpix.proger.mps.model.Note
import jxpix.proger.mps.model.Scales
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * The key the generators work in: the notes a line may use, and the seven-note scale the chords
 * are built on.
 *
 * For a scale of seven notes the two are the same. A pentatonic or blues scale has too few notes
 * for chords of its own, so chords stand on the steps of its parent (major or natural minor) and
 * every note of a chord that is not in the scale moves to the nearest one that is - which gives
 * the open, "sus" chords such music uses anyway. Nothing the generators write leaves the scale.
 */
class Key(root: Int, val scale: Int) {
    val root: Int = ((root % 12) + 12) % 12

    /** The notes of the key as twelve bits (see [Scales.mask]). */
    val mask: Int = Scales.mask(this.root, scale)

    /** Semitones of the seven steps chords are built on, counted from the tonic. */
    val parent: IntArray = when {
        scale in 1 until Scales.count && Scales.steps[scale].size == 7 -> Scales.steps[scale]
        scale in 1 until Scales.count && Scales.steps[scale].contains(4) -> Scales.steps[Scales.MAJOR]
        else -> Scales.steps[Scales.MINOR]
    }

    /** True when the third above the tonic is a minor one. */
    val minor: Boolean get() = parent[2] == 3

    fun contains(pitch: Int): Boolean = Scales.contains(mask, pitch)

    /** Pitch class of a step of the parent scale; steps past the seventh go round. */
    fun stepClass(step: Int): Int = (root + parent[((step % 7) + 7) % 7]) % 12

    /** The triad (or seventh chord) that stands on a step, with every note inside the key. Null when its root is not. */
    fun chordOn(step: Int, seventh: Boolean = false): IntArray? {
        val rootClass = stepClass(step)
        if (!contains(rootClass)) return null
        val tones = ArrayList<Int>(4)
        tones += 0
        for (k in if (seventh) intArrayOf(2, 4, 6) else intArrayOf(2, 4)) {
            var pc = stepClass(step + k)
            if (!contains(pc)) pc = ((Scales.nearest(pc + 12, mask)) % 12 + 12) % 12
            val interval = (pc - rootClass + 12) % 12
            if (interval != 0 && interval !in tones) tones += interval
        }
        tones.sort()
        return tones.toIntArray()
    }

    /** A diminished or augmented triad: correct, but nothing a first idea should be built on. */
    fun harsh(step: Int): Boolean {
        val third = (stepClass(step + 2) - stepClass(step) + 12) % 12
        val fifth = (stepClass(step + 4) - stepClass(step) + 12) % 12
        return fifth != 7 || (third != 3 && third != 4)
    }

    /**
     * How usual the chord on a step is, 0..1: the tonic first, then the chords a fourth and a
     * fifth away, then the rest. Settles which chord a lone note most likely belongs to.
     */
    fun usual(step: Int): Double = when (((step % 7) + 7) % 7) {
        0 -> 1.0
        3, 4 -> 0.8
        5 -> 0.7
        6 -> 0.5
        2 -> 0.45
        else -> 0.35
    }

    /**
     * The chord progressions that suit this key, the most usual first. Each is a list of steps of
     * the parent scale (0 = the tonic). Progressions with a chord that would sound sour in this
     * scale, or whose root the scale does not have, are left out.
     */
    val progressions: List<IntArray> by lazy {
        // only chords with a plain name: major, minor, or the open ones a scale of five notes makes of them
        fun usable(p: IntArray) = p.all { step -> !harsh(step) && chordOn(step)?.let { Chord.suffix(it) in PLAIN } == true }
        val own = (if (minor) MINOR_PROGRESSIONS else MAJOR_PROGRESSIONS).filter(::usable)
        val other = (if (minor) MAJOR_PROGRESSIONS else MINOR_PROGRESSIONS).filter(::usable)
        val all = (own + other.filter { o -> own.none { it.contentEquals(o) } }).take(10)
        all.ifEmpty { listOf(intArrayOf(0, 0, 0, 0)) }
    }

    companion object {
        private val PLAIN = setOf("", "m", "sus2", "sus4", "5")
        private val MAJOR_PROGRESSIONS = listOf(
            intArrayOf(0, 4, 5, 3), intArrayOf(5, 3, 0, 4), intArrayOf(0, 5, 3, 4), intArrayOf(0, 3, 5, 4), intArrayOf(3, 0, 4, 5),
            intArrayOf(0, 3, 4, 3), intArrayOf(1, 4, 0, 5), intArrayOf(0, 4, 5, 2, 3, 0, 3, 4), intArrayOf(0, 1, 5, 4), intArrayOf(0, 6, 3, 0),
            intArrayOf(0, 1, 0, 6), intArrayOf(0, 2, 3, 4), intArrayOf(0, 1, 4, 5), intArrayOf(0, 5, 1, 4), intArrayOf(0, 4, 5, 4), intArrayOf(0, 5, 2, 4),
        )
        private val MINOR_PROGRESSIONS = listOf(
            intArrayOf(0, 5, 2, 6), intArrayOf(0, 5, 3, 4), intArrayOf(0, 6, 5, 6), intArrayOf(0, 3, 6, 2), intArrayOf(0, 2, 6, 3),
            intArrayOf(0, 6, 5, 4), intArrayOf(0, 4, 5, 3), intArrayOf(0, 3, 4, 0), intArrayOf(0, 3, 0, 4), intArrayOf(5, 6, 0, 0),
            intArrayOf(0, 1, 0, 6), intArrayOf(0, 3, 6, 2, 5, 1, 4, 0), intArrayOf(0, 1, 4, 0), intArrayOf(0, 3, 1, 4), intArrayOf(0, 6, 3, 0), intArrayOf(0, 2, 3, 6),
        )

        // how well each note of the octave fits a major and a minor key (Krumhansl and Kessler)
        private val MAJOR_FIT = doubleArrayOf(6.35, 2.23, 3.48, 2.33, 4.38, 4.09, 2.52, 5.19, 2.39, 3.66, 2.29, 2.88)
        private val MINOR_FIT = doubleArrayOf(6.33, 2.68, 3.52, 5.38, 2.60, 3.53, 2.54, 4.75, 3.98, 2.69, 3.34, 3.17)

        /**
         * The major or minor key the given notes most likely belong to, or null when there is too
         * little to go by (fewer than three different notes).
         */
        fun guess(notes: Collection<Note>): Key? {
            val weight = DoubleArray(12)
            for (n in notes) weight[((n.pitch % 12) + 12) % 12] += max(1, n.length).toDouble()
            if (weight.count { it > 0.0 } < 3) return null
            val mean = weight.average()
            var best = 0
            var bestFit = -2.0
            for (k in 0 until 24) {
                val profile = if (k < 12) MAJOR_FIT else MINOR_FIT
                val profileMean = profile.average()
                var top = 0.0
                var a = 0.0
                var b = 0.0
                for (pc in 0 until 12) {
                    val x = weight[pc] - mean
                    val y = profile[(pc - k % 12 + 12) % 12] - profileMean
                    top += x * y
                    a += x * x
                    b += y * y
                }
                val fit = if (a > 0.0 && b > 0.0) top / Math.sqrt(a * b) else -1.0
                if (fit > bestFit + 1e-9) {
                    bestFit = fit
                    best = k
                }
            }
            return Key(best % 12, if (best < 12) Scales.MAJOR else Scales.MINOR)
        }
    }
}

/** One chord of a progression: when it sounds and what it is made of. */
class Chord(
    val start: Int,
    val length: Int,
    /** Pitch class of the root, 0 = C. */
    val root: Int,
    /** Semitones of its notes above the root, rising, the root (0) first. */
    val tones: IntArray,
) {
    val end: Int get() = start + length

    fun has(pitch: Int): Boolean = ((pitch - root) % 12 + 12) % 12 in tones

    fun sameAs(other: Chord): Boolean = root == other.root && tones.contentEquals(other.tones)

    /** "Am", "F", "Gsus4"... */
    val name: String get() = Scales.NOTE_NAMES[root] + suffix(tones)

    companion object {
        fun suffix(tones: IntArray): String = when (tones.joinToString(",")) {
            "0,4,7" -> ""
            "0,3,7" -> "m"
            "0,3,6" -> "dim"
            "0,4,8" -> "aug"
            "0,5,7" -> "sus4"
            "0,2,7" -> "sus2"
            "0,7" -> "5"
            "0,4,7,11" -> "maj7"
            "0,4,7,10" -> "7"
            "0,3,7,10" -> "m7"
            "0,3,7,11" -> "m(maj7)"
            "0,3,6,10" -> "m7b5"
            "0,3,6,9" -> "dim7"
            "0,5,7,10" -> "7sus4"
            "0,2,7,10" -> "7sus2"
            "0,3,8" -> "m#5"
            "0,5,8" -> "sus4#5"
            "0,3,5" -> "m(add4)"
            "0,5,10" -> "sus4(7)"
            else -> if (3 in tones && 4 !in tones) "m*" else "*"
        }
    }
}

/** The chords of a pattern, one after another without gaps, from its first tick to its last. */
class Harmony(val chords: List<Chord>, val length: Int) {

    fun at(tick: Int): Chord {
        for (c in chords) if (tick < c.end) return c
        return chords.last()
    }

    /** The chord after the one that sounds at [tick]; the first one again after the last. */
    fun after(tick: Int): Chord {
        val i = chords.indexOf(at(tick))
        return chords[(i + 1) % chords.size]
    }

    /** "Am  F  C  G" - neighbours that are the same chord are named once. */
    fun names(): String {
        val out = ArrayList<String>()
        var last: Chord? = null
        for (c in chords) {
            if (last == null || !last.sameAs(c)) out += c.name
            last = c
        }
        return out.joinToString("  ")
    }

    companion object {
        /** A progression laid over [length] ticks, every chord lasting [each] ticks; it starts again when it is over. */
        fun of(key: Key, steps: IntArray, length: Int, each: Int, seventh: Boolean = false): Harmony {
            val out = ArrayList<Chord>()
            var at = 0
            var i = 0
            while (at < length) {
                val step = steps[i % steps.size]
                val tones = key.chordOn(step, seventh) ?: key.chordOn(0, seventh) ?: intArrayOf(0, 7)
                val root = if (key.contains(key.stepClass(step))) key.stepClass(step) else key.root
                out += Chord(at, min(each, length - at), root, tones)
                at += each
                i++
            }
            return Harmony(out, length)
        }

        /**
         * The chords the given notes spell out, heard piece by piece ([each] ticks long): for every
         * piece the chord of the key that covers most of what sounds there. A low note counts extra
         * as a likely root - a bass says more about the chord than a tune does - a seventh on top of
         * a chord is taken for its colour, and where the notes leave a choice (a melody alone fits
         * several chords) the more usual chord is taken.
         * Diminished chords are heard only when nothing else fits. Pieces where nothing sounds take
         * the chord of their neighbour. Null when the notes are silent altogether.
         */
        fun detect(key: Key, notes: Collection<Note>, length: Int, each: Int, seventh: Boolean = false): Harmony? {
            if (notes.none { it.start < length }) return null
            // for every step its triad and the same chord with a seventh on top
            val candidates = (0 until 7).mapNotNull { step -> key.chordOn(step, false)?.let { Triple(step, it, key.chordOn(step, true) ?: it) } }
            if (candidates.isEmpty()) return null
            val pieces = (length + each - 1) / each
            val found = arrayOfNulls<Int>(pieces)
            var previous = -1
            for (i in 0 until pieces) {
                val from = i * each
                val to = min(length, from + each)
                val weight = DoubleArray(12)
                var lowest = Int.MAX_VALUE
                var any = false
                for (n in notes) {
                    val overlap = min(n.end, to) - max(n.start, from)
                    if (overlap <= 0) continue
                    any = true
                    weight[((n.pitch % 12) + 12) % 12] += overlap.toDouble()
                    if (n.pitch < lowest) lowest = n.pitch
                }
                if (!any) continue
                val bass = ((lowest % 12) + 12) % 12
                // the lowest note names the root when it is low: fully from the C below middle C down, not at all an octave above it
                val bassCounts = ((72 - lowest) / 24.0).coerceIn(0.0, 1.0)
                val total = weight.sum()
                var best = -1
                var bestScore = -1e18
                for ((step, tones, rich) in candidates) {
                    val root = key.stepClass(step)
                    var score = 0.0
                    for (pc in 0 until 12) {
                        val interval = (pc - root + 12) % 12
                        // a seventh on top colours a chord without making it another one: A C E G is A minor, not C
                        score += weight[pc] * (if (interval in tones) 1.0 else if (interval in rich) 0.3 else -0.6)
                    }
                    if (bass == root) score += 0.5 * bassCounts * weight[bass]
                    score += 0.1 * total * key.usual(step)
                    if (key.harsh(step)) score -= 0.5 * total
                    // of equals: the chord that is sounding already
                    if (step == previous) score += 1e-3 * total
                    if (score > bestScore + 1e-9) {
                        bestScore = score
                        best = step
                    }
                }
                found[i] = best
                previous = best
            }
            // silent pieces: the chord before them goes on; before the first chord, the first chord
            var carry = found.firstOrNull { it != null } ?: return null
            val out = ArrayList<Chord>()
            for (i in 0 until pieces) {
                carry = found[i] ?: carry
                val from = i * each
                val root = key.stepClass(carry)
                val tones = key.chordOn(carry, seventh) ?: intArrayOf(0, 7)
                val last = out.lastOrNull()
                // one chord held over several pieces is one chord
                if (last != null && last.root == root && last.tones.contentEquals(tones)) {
                    out[out.size - 1] = Chord(last.start, last.length + min(each, length - from), root, tones)
                } else {
                    out += Chord(from, min(each, length - from), root, tones)
                }
            }
            return Harmony(out, length)
        }
    }
}

/** MIDI pitch of a pitch class at or above [floor]. */
internal fun pitchAtOrAbove(pitchClass: Int, floor: Int): Int {
    var p = floor - (((floor - pitchClass) % 12) + 12) % 12
    if (p < floor) p += 12
    return p
}

internal fun nearestTo(target: Int, candidates: IntArray): Int {
    var best = candidates[0]
    for (c in candidates) if (abs(c - target) < abs(best - target)) best = c
    return best
}
