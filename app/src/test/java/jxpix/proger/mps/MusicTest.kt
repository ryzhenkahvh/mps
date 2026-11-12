package jxpix.proger.mps

import jxpix.proger.mps.engine.PPQ
import jxpix.proger.mps.engine.TICKS_PER_STEP
import jxpix.proger.mps.model.Note
import jxpix.proger.mps.model.ProjectFactory
import jxpix.proger.mps.model.Scales
import jxpix.proger.mps.music.Generator
import jxpix.proger.mps.music.Harmony
import jxpix.proger.mps.music.Key
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/** The generator of chords, bass lines, melodies and arpeggios: what it writes has to be music in the key, every time. */
class MusicTest {
    private val bar = PPQ * 4
    private val aMinor = Key(9, Scales.MINOR)
    private val cMajor = Key(0, Scales.MAJOR)

    private fun names(notes: List<Note>): String = notes.sortedBy { it.start }.joinToString(" ") { Scales.NOTE_NAMES[it.pitch % 12] + (it.pitch / 12 - 1) }

    /** Every way the generator can be asked to write something, for one key and one seed. */
    private fun everything(key: Key, length: Int, each: Int, seed: Long, seventh: Boolean): Map<String, List<Note>> {
        val out = LinkedHashMap<String, List<Note>>()
        key.progressions.forEachIndexed { pi, steps ->
            val h = Harmony.of(key, steps, length, each, seventh)
            for (style in 0..2) {
                out["chords p$pi s$style"] = Generator.chords(h, style, bar, seed)
                out["bass p$pi s$style"] = Generator.bass(key, h, style, bar, seed)
                out["melody p$pi d$style"] = Generator.melody(key, h, style, bar, seed)
            }
            for (order in 0..3) out["arp p$pi o$order"] = Generator.arpeggio(h, order, listOf(PPQ, PPQ / 2, PPQ / 3, PPQ / 4)[order], 1 + order % 3, seed)
        }
        return out
    }

    @Test
    fun everyGeneratedNoteIsInTheKeyAndInsideThePattern() {
        var checked = 0
        for (scale in 1 until Scales.count) for (root in 0 until 12) {
            val key = Key(root, scale)
            assertTrue("no progressions for scale $scale", key.progressions.isNotEmpty())
            val seed = (root * 31 + scale).toLong()
            val bars = listOf(1, 2, 4, 8)[(root + scale) % 4]
            val each = listOf(bar / 2, bar, bar * 2)[root % 3]
            for ((what, notes) in everything(key, bars * bar, each, seed, seventh = root % 2 == 1)) {
                val where = "$what, ${Scales.NOTE_NAMES[root]} scale $scale, $bars bars"
                assertTrue("nothing written: $where", notes.isNotEmpty())
                for (n in notes) {
                    assertTrue("${n.pitch} is outside of the key: $where", key.contains(n.pitch))
                    assertTrue("a note outside of the pattern: $where ($n)", n.start >= 0 && n.length > 0 && n.end <= bars * bar)
                    assertTrue("velocity ${n.velocity}: $where", n.velocity > 0.2f && n.velocity <= 1f)
                    assertTrue("pitch ${n.pitch}: $where", n.pitch in 24..108)
                }
                // one key cannot be pressed twice at once
                for ((_, same) in notes.groupBy { it.pitch }) {
                    val sorted = same.sortedBy { it.start }
                    for (i in 1 until sorted.size) assertTrue("overlapping notes of one pitch: $where", sorted[i].start >= sorted[i - 1].end)
                }
                checked++
            }
        }
        assertTrue(checked > 3000)
    }

    @Test
    fun oddTimeSignaturesWork() {
        // three and five beats to the bar: the rhythms are made for four
        for (beats in listOf(3, 5, 7)) {
            val odd = PPQ * beats
            val h = Harmony.of(aMinor, aMinor.progressions[0], odd * 4, odd)
            for (style in 0..2) {
                val all = Generator.chords(h, style, odd, 5) + Generator.bass(aMinor, h, style, odd, 5) + Generator.melody(aMinor, h, style, odd, 5)
                assertTrue(all.isNotEmpty())
                assertTrue(all.all { aMinor.contains(it.pitch) && it.start >= 0 && it.length > 0 && it.end <= odd * 4 })
            }
        }
    }

    @Test
    fun theSameSeedWritesTheSameNotesAndAnotherSeedOtherNotes() {
        val h = Harmony.of(aMinor, aMinor.progressions[0], bar * 4, bar)
        for (density in 0..2) {
            assertEquals(Generator.melody(aMinor, h, density, bar, 42), Generator.melody(aMinor, h, density, bar, 42))
            val variants = (1L..12L).map { Generator.melody(aMinor, h, density, bar, it) }.distinct()
            assertTrue("only ${variants.size} different melodies out of 12", variants.size >= 11)
        }
        assertEquals(Generator.bass(aMinor, h, 2, bar, 7), Generator.bass(aMinor, h, 2, bar, 7))
        assertTrue((1L..12L).map { Generator.bass(aMinor, h, 2, bar, it) }.distinct().size >= 8)
        assertEquals(Generator.arpeggio(h, Generator.RANDOM, PPQ / 4, 2, 9), Generator.arpeggio(h, Generator.RANDOM, PPQ / 4, 2, 9))
        assertNotEquals(Generator.arpeggio(h, Generator.RANDOM, PPQ / 4, 2, 9), Generator.arpeggio(h, Generator.RANDOM, PPQ / 4, 2, 10))
    }

    @Test
    fun anotherOneIsNeverTheSameAgain() {
        // the button "another one" moves to the next seed: whatever is asked for, the notes must change
        val h = Harmony.of(aMinor, aMinor.progressions[0], bar * 4, bar)
        val held = listOf(Note(0, bar, 60, 0.8f), Note(0, bar, 64, 0.8f), Note(0, bar, 67, 0.8f))
        val ways = LinkedHashMap<String, (Long) -> List<Note>>()
        for (style in 0..2) {
            ways["chords $style"] = { seed -> Generator.chords(h, style, bar, seed) }
            ways["bass $style"] = { seed -> Generator.bass(aMinor, h, style, bar, seed) }
            ways["melody $style"] = { seed -> Generator.melody(aMinor, h, style, bar, seed) }
        }
        for (order in 0..3) {
            ways["arpeggio $order"] = { seed -> Generator.arpeggio(h, order, PPQ / 2, 1, seed) }
            ways["arpeggio of notes $order"] = { seed -> Generator.arpeggiate(held, bar, order, PPQ / 2, 1, seed) }
        }
        for ((what, make) in ways) {
            var distinct = HashSet<List<Note>>()
            for (seed in 1L..12L) {
                assertNotEquals("$what: seeds $seed and ${seed + 1} write the same", make(seed), make(seed + 1))
                distinct.add(make(seed))
            }
            assertTrue("$what: only ${distinct.size} variants", distinct.size >= 2)
        }
        // chords played as stabs, with gaps between them, still give an arpeggio without holes...
        val stabs = (0 until 4).flatMap { beat -> listOf(60, 64, 67).map { Note(beat * PPQ, PPQ / 2, it, 0.8f) } }
        assertEquals(List(8) { it * PPQ / 2 }, Generator.arpeggiate(stabs, bar, Generator.UP, PPQ / 2, 1, 1).map { it.start })
        assertEquals("C4 E4 G4 C4 E4 G4 C4 E4", names(Generator.arpeggiate(stabs, bar, Generator.UP, PPQ / 2, 1, 1)))
        // ...while a real rest stays a rest: a chord in the first half of the bar leaves the second half silent
        val half = listOf(60, 64, 67).map { Note(0, PPQ * 2, it, 0.8f) }
        assertEquals(listOf(0, PPQ / 2, PPQ, PPQ * 3 / 2), Generator.arpeggiate(half, bar, Generator.UP, PPQ / 2, 1, 1).map { it.start })
        // the first press on a fresh install is the plain figure: the chord from its lowest note
        assertEquals("C4 E4 G4 C4 E4 G4 C4 E4", names(Generator.arpeggiate(held, bar, Generator.UP, PPQ / 2, 1, 1)))
        assertEquals("E4 G4 C4 E4 G4 C4 E4 G4", names(Generator.arpeggiate(held, bar, Generator.UP, PPQ / 2, 1, 2)))
    }

    @Test
    fun chordsHaveTheirNamesAndMoveAsLittleAsTheyCan() {
        assertEquals("the progression everybody knows comes first in a minor key", "Am  F  C  G", Harmony.of(aMinor, aMinor.progressions[0], bar * 4, bar).names())
        assertEquals("C  G  Am  F", Harmony.of(cMajor, cMajor.progressions[0], bar * 4, bar).names())
        assertEquals("Am7  Fmaj7  Cmaj7  G7", Harmony.of(aMinor, aMinor.progressions[0], bar * 4, bar, seventh = true).names())
        // a scale of five notes has no thirds on some of its steps: its chords are the open ones
        val penta = Key(9, 4)
        for (steps in penta.progressions) {
            val text = Harmony.of(penta, steps, bar * steps.size, bar).names()
            assertFalse("an unnamed chord in $text", text.contains("*") || text.contains("#5") || text.contains("add"))
        }
        assertTrue(penta.progressions.size >= 3)
        // no scale offers a chord that sounds sour
        for (scale in 1 until Scales.count) {
            val key = Key(0, scale)
            for (steps in key.progressions) {
                val text = Harmony.of(key, steps, bar * steps.size, bar).names()
                assertFalse("scale $scale offers $text", text.contains("dim") || text.contains("aug") || text.contains("*"))
            }
            assertTrue("scale $scale has only ${key.progressions.size} progressions", key.progressions.size >= 3)
        }

        val h = Harmony.of(aMinor, aMinor.progressions[0], bar * 4, bar)
        val voiced = Generator.voicings(h)
        assertEquals(4, voiced.size)
        var moved = 0
        var jumped = 0
        for (i in voiced.indices) {
            val v = voiced[i]
            assertEquals(3, v.size)
            assertTrue("a chord out of its register: ${v.toList()}", v.all { it in 48..76 } && v.max() - v.min() <= 12)
            assertEquals("the notes of ${h.chords[i].name}", h.chords[i].tones.map { (h.chords[i].root + it) % 12 }.toSet(), v.map { it % 12 }.toSet())
            if (i > 0) {
                moved += v.indices.sumOf { j -> abs(v.sorted()[j] - voiced[i - 1].sorted()[j]) }
                // the same chords with the root always at the bottom
                jumped += 3 * abs(pitchAbove(h.chords[i].root) - pitchAbove(h.chords[i - 1].root))
            }
        }
        assertTrue("the hand moves $moved semitones in all, in root position it would be $jumped", moved <= 12 && moved * 2 < jumped)

        val held = Generator.chords(h, 0, bar, 1)
        assertEquals(12, held.size)
        assertTrue(held.all { it.length == bar && it.start % bar == 0 })
        val stabs = Generator.chords(h, 1, bar, 0)
        assertTrue(stabs.size > 12 && stabs.all { it.length <= TICKS_PER_STEP * 4 })
        // every hit is a whole chord
        assertTrue(stabs.groupBy { it.start }.values.all { it.size == 3 })
    }

    private fun pitchAbove(pitchClass: Int): Int = 48 + pitchClass

    @Test
    fun theBassFollowsTheRootsOfTheChords() {
        val h = Harmony.of(aMinor, aMinor.progressions[0], bar * 4, bar)
        assertEquals("A1 F2 C2 G2", names(Generator.bass(aMinor, h, 0, bar, 1)))
        // the other way to hold a bass: the last beat of every chord leads into the next one
        assertEquals("A1 E2 F2 D2 C2 F2 G2 B1", names(Generator.bass(aMinor, h, 0, bar, 2)))
        assertEquals(List(4) { listOf(it * bar, it * bar + bar - PPQ) }.flatten(), Generator.bass(aMinor, h, 0, bar, 2).map { it.start })
        for (style in 1..2) for (seed in 0L..11L) {
            val notes = Generator.bass(aMinor, h, style, bar, seed)
            assertTrue(notes.all { it.pitch in 31..60 })
            for (chord in h.chords) {
                val inChord = notes.filter { it.start >= chord.start && it.start < chord.end }.sortedBy { it.start }
                assertTrue("style $style seed $seed: nothing under ${chord.name}", inChord.isNotEmpty())
                assertEquals("style $style seed $seed: the first note under ${chord.name}", chord.root, inChord.first().pitch % 12)
                assertTrue(inChord.first().pitch in 33..44)
                // most of what the bass plays under a chord is its root
                val root = inChord.filter { it.pitch % 12 == chord.root }.sumOf { it.length }
                assertTrue("style $style seed $seed: the root is lost under ${chord.name}", root * 2 >= inChord.sumOf { it.length })
            }
        }
        // a groove walks into the next chord: its last note is next to the root that follows
        var walks = 0
        for (seed in 0L..11L) {
            val notes = Generator.bass(aMinor, h, 2, bar, seed).sortedBy { it.start }
            for (i in 0 until h.chords.size - 1) {
                val last = notes.last { it.start < h.chords[i].end }
                val next = notes.first { it.start >= h.chords[i].end }
                if (abs(last.pitch - next.pitch) in 1..2) walks++
            }
        }
        assertEquals(36, walks)
        // a pitched drum has its own idea of where the bass is
        val drum = Generator.bass(aMinor, h, 0, bar, 1, low = 51)
        assertEquals("A3 F3 C4 G3", names(drum))
    }

    @Test
    fun aMelodyLeansOnTheChordsAndComesBackToItsMotif() {
        val h = Harmony.of(aMinor, aMinor.progressions[0], bar * 4, bar)
        for (density in 0..2) for (seed in 0L..19L) {
            val notes = Generator.melody(aMinor, h, density, bar, seed).sortedBy { it.start }
            val where = "density $density seed $seed"
            assertTrue("$where: ${notes.size} notes", notes.size in (if (density == 0) 6..16 else if (density == 1) 12..26 else 20..44))
            assertTrue("$where: out of range", notes.all { it.pitch in Generator.MELODY_LOW..Generator.MELODY_HIGH })
            // one voice
            for (i in 1 until notes.size) assertTrue("$where: two notes at once", notes[i].start >= notes[i - 1].end)
            // the first note of every bar and the last note of all are notes of the chord
            for (b in 0 until 4) {
                val first = notes.first { it.start >= b * bar }
                assertTrue("$where: bar ${b + 1} opens off the chord", h.at(first.start).has(first.pitch))
            }
            assertTrue("$where: ends off the chord", h.at(notes.last().start).has(notes.last().pitch))
            // bars one and three share their rhythm: the motif returns
            fun rhythm(b: Int) = notes.filter { it.start / bar == b }.map { it.start % bar to it.length }
            assertEquals("$where: the motif does not return", rhythm(0), rhythm(2))
            // no wild leaps: a melody is sung, not thrown
            val leaps = (1 until notes.size).count { abs(notes[it].pitch - notes[it - 1].pitch) > 7 }
            assertTrue("$where: $leaps leaps over a fifth", leaps <= notes.size / 6 + 1)
        }
        // busier means more notes
        val counts = (0..2).map { d -> (0L..19L).sumOf { Generator.melody(aMinor, h, d, bar, it).size } }
        assertTrue("notes by density: $counts", counts[0] * 1.4 < counts[1] && counts[1] * 1.3 < counts[2])
    }

    @Test
    fun anArpeggioPlaysTheNotesOfAChordInOrder() {
        val chord = listOf(Note(0, bar, 60, 0.8f), Note(0, bar, 64, 0.8f), Note(0, bar, 67, 0.8f))
        val eighth = PPQ / 2
        assertEquals("C4 E4 G4 C4 E4 G4 C4 E4", names(Generator.arpeggiate(chord, bar, Generator.UP, eighth, 1, 1)))
        assertEquals("G4 E4 C4 G4 E4 C4 G4 E4", names(Generator.arpeggiate(chord, bar, Generator.DOWN, eighth, 1, 1)))
        assertEquals("C4 E4 G4 E4 C4 E4 G4 E4", names(Generator.arpeggiate(chord, bar, Generator.UP_DOWN, eighth, 1, 1)))
        assertEquals("C4 E4 G4 C5 E5 G5 C4 E4", names(Generator.arpeggiate(chord, bar, Generator.UP, eighth, 2, 1)))
        assertEquals("C4 E4 G4 C5 E5 G5 E5 C5 G4 E4 C4 E4 G4 C5 E5 G5", names(Generator.arpeggiate(chord, bar, Generator.UP_DOWN, PPQ / 4, 2, 1)))
        val random = Generator.arpeggiate(chord, bar, Generator.RANDOM, PPQ / 4, 1, 4)
        assertEquals(16, random.size)
        assertTrue(random.all { it.pitch in listOf(60, 64, 67) } && random.map { it.pitch }.distinct().size == 3)
        assertTrue("the same note twice in a row", (1 until random.size).all { random[it].pitch != random[it - 1].pitch })
        // triplets: twelve to the bar
        val triplets = Generator.arpeggiate(chord, bar, Generator.UP, PPQ / 3, 1, 1)
        assertEquals(12, triplets.size)
        assertEquals(List(12) { it * PPQ / 3 }, triplets.map { it.start })
        // the notes are short enough to be heard one by one, and none runs over the end
        assertTrue(triplets.all { it.length < PPQ / 3 && it.end <= bar })

        // two chords one after the other: the figure starts again with the second; silence stays silence
        val two = chord.map { it.copy(length = bar / 2) } + listOf(Note(bar, bar / 2, 57, 0.6f), Note(bar, bar / 2, 60, 0.6f), Note(bar, bar / 2, 64, 0.6f))
        val out = Generator.arpeggiate(two, bar * 2, Generator.UP, eighth, 1, 1)
        assertEquals("C4 E4 G4 C4 A3 C4 E4 A3", names(out))
        assertEquals(listOf(0, 240, 480, 720, 1920, 2160, 2400, 2640), out.map { it.start })
        assertTrue("the arpeggio keeps the strength of what it was made from", out.take(4).all { it.velocity > 0.65f } && out.drop(4).all { it.velocity < 0.61f })
        // a note that joins a held chord changes what is played from where it joins
        val joined = chord + Note(bar / 2, bar / 2, 72, 0.8f)
        assertEquals("C4 E4 G4 C4 C4 E4 G4 C5", names(Generator.arpeggiate(joined, bar, Generator.UP, eighth, 1, 1)))
        assertTrue(Generator.arpeggiate(emptyList(), bar, Generator.UP, eighth, 1, 1).isEmpty())

        // from the chords of the song: every chord is spelled from its lowest note up
        val h = Harmony.of(aMinor, aMinor.progressions[0], bar * 4, bar)
        val arp = Generator.arpeggio(h, Generator.UP, eighth, 1, 1)
        assertEquals(32, arp.size)
        for (b in 0 until 4) {
            val inBar = arp.filter { it.start / bar == b }.sortedBy { it.start }
            assertTrue(inBar.all { h.chords[b].has(it.pitch) })
            assertTrue(inBar[0].pitch < inBar[1].pitch && inBar[1].pitch < inBar[2].pitch && inBar[3].pitch == inBar[0].pitch)
        }
    }

    @Test
    fun theChordsOfAPatternAreHeardFromItsNotes() {
        fun triad(at: Int, vararg pitches: Int) = pitches.map { Note(at, bar, it, 0.8f) }
        val chords = triad(0, 57, 60, 64) + triad(bar, 53, 57, 60) + triad(bar * 2, 55, 60, 64) + triad(bar * 3, 55, 59, 62)
        assertEquals("Am  F  C  G", Harmony.detect(aMinor, chords, bar * 4, bar / 2)!!.names())
        // a bass line alone says enough
        val bass = listOf(33, 29, 36, 31).mapIndexed { i, p -> Note(i * bar, bar, p, 0.8f) }
        assertEquals("Am  F  C  G", Harmony.detect(aMinor, bass, bar * 4, bar / 2)!!.names())
        // chords that change twice a bar, and a bar in which nothing plays
        val quick = triad(0, 57, 60, 64).map { it.copy(length = bar / 2) } + triad(bar / 2, 55, 59, 62).map { it.copy(length = bar / 2) } +
            triad(bar * 2, 53, 57, 60)
        val h = Harmony.detect(aMinor, quick, bar * 3, bar / 2)!!
        assertEquals("Am  G  F", h.names())
        assertEquals(listOf(0, bar / 2, bar * 2), h.chords.map { it.start })
        assertEquals("the chords cover the pattern without gaps", bar * 3, h.chords.sumOf { it.length })
        assertNull(Harmony.detect(aMinor, emptyList(), bar * 4, bar / 2))
        // what is generated over the chords that were heard fits them
        val line = Generator.bass(aMinor, Harmony.detect(aMinor, chords, bar * 4, bar / 2)!!, 0, bar, 1)
        assertEquals("A1 F2 C2 G2", names(line))

        // the key itself can be guessed from enough notes
        val guess = Key.guess(chords)
        assertNotNull(guess)
        assertTrue("A minor or its relative, C major", (guess!!.root == 9 && guess.scale == Scales.MINOR) || (guess.root == 0 && guess.scale == Scales.MAJOR))
        val inD = listOf(62, 66, 69, 62, 67, 71, 69, 73, 76, 62).mapIndexed { i, p -> Note(i * 240, 240, p, 0.8f) }
        val d = Key.guess(inD)!!
        assertEquals("D major", 2 to Scales.MAJOR, d.root to d.scale)
        assertNull(Key.guess(listOf(Note(0, 100, 60, 0.8f), Note(100, 100, 67, 0.8f))))
        // every template of the app is in a key the guess agrees with: all its notes fit
        for (t in ProjectFactory.templates) {
            val p = t.build { "name" }
            val notes = Generator.everything(p)
            if (notes.size < 6) continue
            val key = Key.guess(notes)!!
            val outside = notes.count { !key.contains(it.pitch) }
            assertTrue("${t.id}: $outside of ${notes.size} notes are outside of the guessed key", outside * 10 <= notes.size)
        }
        // ...and in each of them the generator hears the key and the chords a musician would name:
        // the longest pattern of the template, together with what plays alongside it in the playlist
        val heard = LinkedHashMap<String, String>()
        for (t in ProjectFactory.templates) {
            val p = t.build { "name" }
            val key = Key.guess(Generator.everything(p)) ?: continue
            val pattern = p.patterns.maxByOrNull { p.patternLength(it) }!!
            val chords = Harmony.detect(key, Generator.surroundings(p, pattern, -1), p.patternLength(pattern), bar / 2)
            heard[t.id] = Scales.NOTE_NAMES[key.root] + (if (key.scale == Scales.MINOR) " minor" else " major") + ": " + chords?.names()
        }
        assertEquals(
            mapOf("house" to "A minor: Am  F", "trap" to "A minor: Am  C", "lofi" to "C major: Dm  G  C  Am", "demo" to "C major: Am  F  C  G"),
            heard,
        )
    }

    @Test
    fun aTuneAloneStillSaysWhichChordsItWants() {
        // one note at a time fits three chords of the key: the usual one is heard, and never a diminished one
        fun heard(vararg pitches: Int): String =
            Harmony.detect(aMinor, pitches.mapIndexed { i, p -> Note(i * bar, bar, p, 0.8f) }, bar * pitches.size, bar)!!.names()
        assertEquals("the notes of the tonic chord mean the tonic", "Am", heard(81, 84, 88))
        assertEquals("B is in Em, G and Bdim; D in Dm, G and Bdim", "Em  Dm", heard(83, 86))
        assertEquals("Am  Dm  Em  Am", heard(81, 89, 79, 76))
        val cMajor = Key(0, Scales.MAJOR)
        val inC = listOf(76, 77, 79, 83, 84).mapIndexed { i, p -> Note(i * bar, bar, p, 0.8f) }
        assertEquals("C  F  C  G  C", Harmony.detect(cMajor, inC, bar * 5, bar)!!.names())
        // the same note low down is a bass, and a bass names the root
        val low = listOf(41, 43).mapIndexed { i, p -> Note(i * bar, bar, p, 0.8f) }
        assertEquals("F  G", Harmony.detect(aMinor, low, bar * 2, bar)!!.names())
        // B, D and F together are the top of G7 far more often than a chord of their own
        val top = listOf(71, 74, 77).map { Note(0, bar, it, 0.8f) }
        assertEquals("G", Harmony.detect(aMinor, top, bar, bar)!!.names())
        // nothing the templates of the app play is heard as a diminished chord
        for (t in ProjectFactory.templates) {
            val p = t.build { "name" }
            val key = Key.guess(Generator.everything(p)) ?: continue
            for (pattern in p.patterns) {
                val names = Harmony.detect(key, Generator.surroundings(p.copy(clips = emptyList()), pattern, -1), p.patternLength(pattern), bar / 2)?.names() ?: continue
                assertFalse("${t.id} / ${pattern.name}: $names", "dim" in names || "aug" in names || "b5" in names)
            }
        }
    }
}
