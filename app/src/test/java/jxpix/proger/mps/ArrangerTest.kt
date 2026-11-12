package jxpix.proger.mps

import jxpix.proger.mps.engine.Engine
import jxpix.proger.mps.engine.PPQ
import jxpix.proger.mps.engine.SamplePool
import jxpix.proger.mps.engine.instruments.DrumSynth
import jxpix.proger.mps.model.AutoPoint
import jxpix.proger.mps.model.Clip
import jxpix.proger.mps.model.MAX_TRACKS
import jxpix.proger.mps.model.Note
import jxpix.proger.mps.model.Project
import jxpix.proger.mps.model.ProjectFactory
import jxpix.proger.mps.model.STRIP_DEVICE
import jxpix.proger.mps.model.PARAM_VOLUME
import jxpix.proger.mps.model.addAudioClip
import jxpix.proger.mps.model.addChannel
import jxpix.proger.mps.model.addPatternClip
import jxpix.proger.mps.model.sanitized
import jxpix.proger.mps.model.setAutomation
import jxpix.proger.mps.model.setNotes
import jxpix.proger.mps.music.Arranger
import jxpix.proger.mps.music.Generator
import jxpix.proger.mps.music.Harmony
import jxpix.proger.mps.music.Key
import jxpix.proger.mps.model.addPattern
import jxpix.proger.mps.model.updatePattern
import jxpix.proger.mps.music.Role
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.max

/** Arrangement by template: a loop becomes a track of layers, and nothing of the loop is lost or changed on the way. */
class ArrangerTest {
    private val names: (Int) -> String = { id ->
        when (id) {
            R.string.ch_kick -> "Kick"
            R.string.ch_clap -> "Clap"
            R.string.ch_hat -> "Hat"
            R.string.ch_open_hat -> "Open hat"
            R.string.ch_snare -> "Snare"
            R.string.ch_bass -> "Bass"
            R.string.ch_chords -> "Chords"
            R.string.ch_pluck -> "Pluck"
            else -> "name"
        }
    }

    /** The house template with a melody added to its loop and an empty playlist: what a user has after writing a loop. */
    private fun loop(str: (Int) -> String = names): Project {
        val p = ProjectFactory.house(str).copy(clips = emptyList())
        // the pluck is the one synth of the template without notes; it comes last
        val pluck = p.channels.last()
        val melody = listOf(76, 72, 74, 79, 76, 72, 71, 72).mapIndexed { i, pitch -> Note(i * PPQ, PPQ / 2, pitch, 0.8f) }
        return p.setNotes(p.patterns.first().id, pluck.id, melody)
    }

    private fun arrange(p: Project, template: String = "dance", short: Boolean = false, replace: Boolean = true): Project {
        val t = Arranger.templates.first { it.id == template }
        val pattern = p.patterns.first()
        val bars = Arranger.bars(t, pattern.bars, short)
        val layers = Arranger.layers(p, pattern, Arranger.freeTracks(p, replace, bars.sum()).size)
        return Arranger.apply(p, pattern.id, t, layers, Arranger.plan(t, layers), bars, replace)!!
    }

    /** The channels that sound at a tick of the song, by name. */
    private fun sounding(p: Project, tick: Int): Set<String> {
        val out = HashSet<String>()
        for (clip in p.clips) {
            if (clip.isAudio || clip.muted || tick < clip.start || tick >= clip.end) continue
            val pattern = p.pattern(clip.patternId)!!
            for ((channelId, notes) in pattern.notes) if (notes.isNotEmpty()) out += p.channel(channelId)!!.name
        }
        return out
    }

    @Test
    fun everyChannelGetsThePartItPlays() {
        val p = loop()
        val pattern = p.patterns.first()
        val roles = p.channels.filter { pattern.notesOf(it.id).isNotEmpty() }.associate { it.name to Arranger.roleOf(it, pattern.notesOf(it.id)) }
        assertEquals(
            mapOf("Kick" to Role.KICK, "Clap" to Role.SNARE, "Hat" to Role.HATS, "Open hat" to Role.HATS, "Bass" to Role.BASS, "Chords" to Role.CHORDS, "Pluck" to Role.LEAD),
            roles.filterKeys { it != "Snare" },
        )
        // without telling names the notes decide: low and one at a time is a bass, several at once are chords, the rest a lead
        val nameless = loop { "name" }
        val byNotes = nameless.channels.filter { !it.isDrum }.map { Arranger.roleOf(it, nameless.patterns.first().notesOf(it.id)) }
        assertEquals(listOf(Role.BASS, Role.CHORDS, Role.LEAD), byNotes)
        // Russian names are understood as well
        val (q, id) = p.addChannel(jxpix.proger.mps.engine.Devices.SYNTH.id, "Бас глубокий")
        assertEquals(Role.BASS, Arranger.roleOf(q.channel(id)!!, listOf(Note(0, 100, 72, 0.8f))))
        // the 808 is a drum by its make and a bass by its part
        val trap = ProjectFactory.trap(names)
        val sub = trap.channels.first { it.isDrum && it.instrument.param("type").toInt() == DrumSynth.BASS808 }
        assertEquals(Role.BASS, Arranger.roleOf(sub, trap.patterns.first().notesOf(sub.id)))
    }

    @Test
    fun aLoopIsLaidOutAsLayersAndNothingOfItIsLost() {
        val p = loop()
        val loop = p.patterns.first()
        for (t in Arranger.templates) for (short in listOf(false, true)) {
            val q = arrange(p, t.id, short)
            val where = "${t.id}${if (short) " short" else ""}"
            val layers = Arranger.layers(p, loop)
            val bars = Arranger.bars(t, loop.bars, short)
            assertEquals("$where: the track is as long as its parts", bars.sum() * p.ticksPerBar, q.songEnd)
            assertTrue("$where: every part is whole repeats of the loop", bars.all { it > 0 && it % loop.bars == 0 })
            assertEquals("$where: a pattern for every layer", p.patterns.size + layers.size, q.patterns.size)
            assertSame("$where: the loop itself is not touched", loop, q.pattern(loop.id))
            assertEquals("$where: the channels are not touched", p.channels, q.channels)
            // the layers together are the loop: every note of it, once
            val made = q.patterns.filter { it.id != loop.id && p.pattern(it.id) == null }
            val together = HashMap<Int, List<Note>>()
            for (m in made) for ((ch, notes) in m.notes) together[ch] = (together[ch] ?: emptyList()) + notes
            assertEquals("$where: the notes of the layers", loop.notes.filterValues { it.isNotEmpty() }, together)
            assertTrue("$where: a layer is one channel, as long as the loop", made.all { it.notes.size == 1 && it.bars == loop.bars })
            // every layer has a track of its own, and no two clips lie on top of each other
            assertEquals("$where: tracks", layers.size, q.clips.map { it.track }.distinct().size)
            assertTrue("$where: track count", q.tracks >= layers.size && q.tracks <= MAX_TRACKS)
            for ((_, onTrack) in q.clips.groupBy { it.track }) {
                assertEquals("$where: one layer per track", 1, onTrack.map { it.patternId }.distinct().size)
                val sorted = onTrack.sortedBy { it.start }
                for (i in 1 until sorted.size) assertTrue("$where: clips overlap", sorted[i].start >= sorted[i - 1].end)
            }
            // what the plan promises is what sounds, part by part
            val plan = Arranger.plan(t, layers)
            var at = 0
            for (s in t.sections.indices) {
                val expected = layers.indices.filter { plan[s][it] }.map { layers[it].name }.toSet()
                assertTrue("$where: part ${s + 1} is silent", expected.isNotEmpty())
                assertEquals("$where: part ${s + 1} at its start", expected, sounding(q, at * p.ticksPerBar))
                assertEquals("$where: part ${s + 1} in its middle", expected, sounding(q, (at + bars[s] / 2) * p.ticksPerBar))
                at += bars[s]
            }
            // the project is one the app can open and save
            assertEquals("$where: sanitized() changes nothing", q, q.sanitized())
        }
    }

    @Test
    fun theShapesAreWhatTheirNamesSay() {
        val p = loop()
        val dance = arrange(p, "dance")
        val bar = p.ticksPerBar
        // intro: drums only; drop: everything; break: no drums and no bass
        assertEquals(setOf("Kick", "Hat", "Open hat"), sounding(dance, 0))
        val everything = setOf("Kick", "Clap", "Hat", "Open hat", "Snare", "Bass", "Chords", "Pluck").filter { n ->
            p.channels.any { it.name == n && p.patterns.first().notesOf(it.id).isNotEmpty() }
        }.toSet()
        assertEquals(everything, sounding(dance, 24 * bar))
        assertEquals(setOf("Chords", "Pluck"), sounding(dance, 40 * bar))
        // the bar before a drop: the kick steps back, the rest of the build goes on
        val beforeDrop = sounding(dance, 23 * bar)
        assertFalse("the kick plays through the last bar before the drop", "Kick" in beforeDrop)
        assertTrue("Hat" in beforeDrop && "Chords" in beforeDrop)
        assertTrue("Kick" in sounding(dance, 22 * bar))
        assertEquals("16 + 8 + 16 + 8 + 8 + 16 + 16 bars", 88 * bar, dance.songEnd)

        // layer by layer: nothing ever leaves before the main part, and something joins in every part
        val layers = arrange(p, "layers")
        var previous = emptySet<String>()
        for (s in 0 until 5) {
            val now = sounding(layers, (s * 8) * bar)
            assertTrue("part ${s + 1}: $previous -> $now", now.containsAll(previous) && now.size > previous.size)
            previous = now
        }
        assertEquals(everything, previous)

        // the short version is half as long; a loop longer than a part stretches the part
        assertEquals(44 * bar, arrange(p, "dance", short = true).songEnd)
        val long = p.copy(patterns = listOf(p.patterns.first().copy(bars = 16)))
        val t = Arranger.templates.first { it.id == "beat" }
        assertEquals(listOf(16, 16, 16, 16, 16, 16), Arranger.bars(t, 16, false).toList())
        assertEquals(listOf(3, 9, 6, 9, 6, 3), Arranger.bars(t, 3, true).toList())
        assertNotNull(arrange(long, "beat"))
    }

    @Test
    fun aLoopWithoutThePartsAShapeAsksForStillGetsNoSilentPart() {
        // only a kick and a bass: an intro of "chords and hats" has nothing to play
        var p = loop()
        val pattern = p.patterns.first()
        for (ch in p.channels) if (ch.name != "Kick" && ch.name != "Bass") p = p.setNotes(pattern.id, ch.id, emptyList())
        for (t in Arranger.templates) {
            val q = arrange(p, t.id)
            val bars = Arranger.bars(t, pattern.bars, false)
            var at = 0
            for (s in t.sections.indices) {
                assertTrue("${t.id}: part ${s + 1} is silent", sounding(q, at * p.ticksPerBar).isNotEmpty())
                at += bars[s]
            }
        }
        // a pattern without notes gives nothing to lay out
        val empty = ProjectFactory.empty(names)
        assertTrue(Arranger.layers(empty, empty.patterns.first()).isEmpty())
        val t = Arranger.templates.first()
        assertNull(Arranger.apply(empty, empty.patterns.first().id, t, emptyList(), Arranger.plan(t, emptyList()), Arranger.bars(t, 1, false), true))
    }

    @Test
    fun whatIsInThePlaylistIsReplacedOrKeptAsAsked() {
        var p = loop()
        // the loop laid twice by hand, and a recording on the first track
        p = p.addPatternClip(p.patterns.first().id, 1, 0, p.ticksPerBar * 4).first
        p = p.addPatternClip(p.patterns.first().id, 2, p.ticksPerBar * 4, p.ticksPerBar * 3).first
        val before = p.clips.filter { !it.isAudio }
        assertEquals(2, before.size)
        val voice = p.addChannel(jxpix.proger.mps.engine.Devices.AUDIO_TYPE_ID, "Voice")
        p = voice.first.addAudioClip(voice.second, "take.wav", 0, 0, p.ticksPerBar * 20).first
        val audio = p.clips.first { it.isAudio }

        val replaced = arrange(p, "song", replace = true)
        assertTrue("the old pattern clips are gone", replaced.clips.none { c -> before.any { it.id == c.id } })
        assertEquals("the recording stays where it was", audio, replaced.clip(audio.id))
        assertTrue("no layer lies on the track of the recording", replaced.clips.none { !it.isAudio && it.track == audio.track })
        assertEquals(0, replaced.clips.filter { !it.isAudio }.minOf { it.start })

        val added = arrange(p, "song", replace = false)
        assertTrue("everything that was there is still there", added.clips.containsAll(p.clips))
        val end = (p.songEnd + p.ticksPerBar - 1) / p.ticksPerBar * p.ticksPerBar
        val new = added.clips.filter { c -> p.clips.none { it.id == c.id } }
        assertEquals("the arrangement begins on the bar after the end", end, new.minOf { it.start })
        assertEquals(end + 80 * p.ticksPerBar, added.songEnd)

        // trying another shape does not pile up copies of the layers
        val again = arrange(replaced, "dance", replace = true)
        assertEquals("the layer patterns are used again", replaced.patterns, again.patterns)
        assertEquals(88 * p.ticksPerBar, again.clips.filter { !it.isAudio }.maxOf { it.end })
        // ...unless the loop was changed in between: then the layer is made anew, under a name of its own
        val loop = replaced.patterns.first()
        val kick = replaced.channels.first { it.name == "Kick" }
        val changed = replaced.setNotes(loop.id, kick.id, loop.notesOf(kick.id).drop(1))
        val third = arrange(changed, "dance", replace = true)
        assertEquals(replaced.patterns.size + 1, third.patterns.size)
        assertEquals(listOf("Kick", "Kick 2"), third.patterns.filter { it.name.startsWith("Kick") }.map { it.name })
        assertTrue("the new arrangement plays the new layer", third.clips.none { !it.isAudio && third.pattern(it.patternId)!!.name == "Kick" })
    }

    @Test
    fun thereIsRoomOrTheArrangementIsRefused() {
        // twenty channels with notes and sixteen tracks: channels with the same part share a layer
        var p = loop()
        val pattern = p.patterns.first()
        repeat(14) { i ->
            val (q, id) = p.addChannel(jxpix.proger.mps.engine.Devices.SYNTH.id, "Lead ${i + 1}")
            p = q.setNotes(pattern.id, id, listOf(Note(i * 120, 120, 72 + i % 5, 0.8f)))
        }
        val withNotes = p.channels.count { p.patterns.first().notesOf(it.id).isNotEmpty() }
        assertTrue(withNotes > MAX_TRACKS)
        val layers = Arranger.layers(p, p.patterns.first())
        assertTrue("${layers.size} layers", layers.size <= Role.entries.size)
        assertEquals("every channel is in a layer, once", withNotes, layers.sumOf { it.channels.size })
        val q = arrange(p, "song")
        assertEquals(p.patterns.first().notes.filterValues { it.isNotEmpty() }.keys, q.patterns.drop(p.patterns.size).flatMap { it.notes.keys }.toSet())
        assertEquals(q, q.sanitized())

        // every track taken by recordings for the whole length: nothing can be laid out, and nothing is changed
        var full = loop()
        val voice = full.addChannel(jxpix.proger.mps.engine.Devices.AUDIO_TYPE_ID, "Voice")
        full = voice.first.copy(tracks = MAX_TRACKS)
        for (track in 0 until MAX_TRACKS - 2) full = full.addAudioClip(voice.second, "take.wav", track, 0, full.ticksPerBar * 200).first
        val t = Arranger.templates.first()
        val bars = Arranger.bars(t, 2, false)
        assertEquals(2, Arranger.freeTracks(full, true, bars.sum()).size)
        val few = Arranger.layers(full, full.patterns.first(), 2)
        assertTrue(few.size > 2)
        assertNull(Arranger.apply(full, full.patterns.first().id, t, few, Arranger.plan(t, few), bars, true))
        // after the end of the recordings there is room again
        assertEquals(MAX_TRACKS, Arranger.freeTracks(full, false, bars.sum()).size)
    }

    @Test
    fun theCurvesOfTheLoopGoWithTheirLayers() {
        var p = loop()
        val loop = p.patterns.first()
        val chords = p.channels.first { it.name == "Chords" }
        val points = listOf(AutoPoint(0, 0.2f), AutoPoint(PPQ * 8, 0.9f))
        p = p.setAutomation(loop.id, chords.id, STRIP_DEVICE, PARAM_VOLUME, points)
        val q = arrange(p, "dance")
        val layer = q.patterns.first { it.name == "Chords" }
        assertEquals(points, layer.lane(chords.id, STRIP_DEVICE, PARAM_VOLUME)!!.points)
        assertTrue("no other layer carries the curve", q.patterns.filter { it.id != layer.id && p.pattern(it.id) == null }.all { it.automation.isEmpty() })
    }

    @Test
    fun theGeneratorHearsTheLayersThatPlayAlongside() {
        val p = loop()
        val q = arrange(p, "dance")
        val key = Key.guess(Generator.everything(q))!!
        val bass = q.channels.first { it.name == "Bass" }
        val layer = q.patterns.first { it.name == "Bass" }
        assertEquals("the layer holds the bass alone", setOf(bass.id), layer.notes.keys)
        // ...yet next to it in the playlist the chords play, and they are what a new bass line has to follow
        val around = Generator.surroundings(q, layer, bass.id)
        assertTrue(around.isNotEmpty() && around.all { it.start >= 0 && it.end <= q.patternLength(layer) })
        assertEquals("Am  F", Harmony.detect(key, around, q.patternLength(layer), PPQ * 2)!!.names())
        // the same is heard in the loop itself, where the chords are in the pattern
        val loop = q.patterns.first()
        assertEquals("Am  F", Harmony.detect(key, Generator.surroundings(q.copy(clips = emptyList()), loop, bass.id), q.patternLength(loop), PPQ * 2)!!.names())

        // a new pattern that is nowhere in the playlist yet is heard against the place where the song first plays a tune
        val (r, id) = q.addPattern("Lead 2")
        val fresh = r.pattern(id)!!
        val heard = Harmony.detect(key, Generator.surroundings(r, fresh, bass.id), r.patternLength(fresh), PPQ * 2)
        assertNotNull("nothing is heard for a pattern outside of the playlist", heard)
        assertTrue(heard!!.names(), heard.names().startsWith("Am"))
        // a clip that starts in the middle of the window lands in the middle of what is heard, not at its start
        val solo = p.setNotes(p.patterns.first().id, p.channels.last().id, emptyList())
        val (a, chordsOnly) = solo.addPattern("Chords only")
        val chords = solo.channels.first { it.name == "Chords" }
        val (b, lead) = a.setNotes(chordsOnly, chords.id, listOf(Note(0, PPQ * 4, 53, 0.8f), Note(0, PPQ * 4, 57, 0.8f), Note(0, PPQ * 4, 60, 0.8f))).addPattern("Lead only")
        val c = b.updatePattern(lead) { it.copy(bars = 2) }
            .copy(clips = emptyList())
            .addPatternClip(lead, 0, 0, b.ticksPerBar * 2).first
            .addPatternClip(chordsOnly, 1, b.ticksPerBar, b.ticksPerBar).first
        val late = Generator.surroundings(c, c.pattern(lead)!!, -1)
        assertEquals("the chord sounds in the second bar of the lead", listOf(c.ticksPerBar), late.map { it.start }.distinct())
        // muted clips are not heard
        val muted = c.copy(clips = c.clips.map { if (it.patternId == chordsOnly) it.copy(muted = true) else it })
        assertTrue(Generator.surroundings(muted, muted.pattern(lead)!!, -1).isEmpty())
    }

    /** Plays [frames] frames of a project from its start and returns what came out. */
    private fun play(p: Project, song: Boolean, frames: Int): FloatArray {
        val engine = Engine(48000)
        engine.setProject(p, SamplePool())
        engine.songMode = song
        engine.patternId = p.patterns.first().id
        engine.play(0)
        val out = FloatArray(frames * 2)
        val block = FloatArray(512 * 2)
        var done = 0
        while (done < frames) {
            val n = minOf(512, frames - done)
            engine.render(block, n)
            System.arraycopy(block, 0, out, done * 2, n * 2)
            done += n
        }
        return out
    }

    @Test
    fun theLayersTogetherSoundLikeTheLoop() {
        // a part in which every layer plays must be the loop itself, to the sample
        val p = loop().copy(swing = 0.2f)
        val loop = p.patterns.first()
        val t = Arranger.templates.first { it.id == "song" }
        val layers = Arranger.layers(p, loop)
        val all = Array(t.sections.size) { BooleanArray(layers.size) { true } }
        val q = Arranger.apply(p, loop.id, t, layers, all, Arranger.bars(t, loop.bars, false), true)!!
        val frames = (p.patternLength(loop) * p.secondsPerTick * 48000).toInt()
        val asLoop = play(p, song = false, frames = frames)
        val asLayers = play(q, song = true, frames = frames)
        var peak = 0f
        var worst = 0f
        for (i in asLoop.indices) {
            peak = max(peak, abs(asLoop[i]))
            worst = max(worst, abs(asLoop[i] - asLayers[i]))
        }
        assertTrue("the loop is silent", peak > 0.1f)
        assertTrue("the layers differ from the loop by $worst (the loop peaks at $peak)", worst < 1e-4f)

        // and with a layer switched off the difference is that layer: the test above can tell
        val noKick = Array(t.sections.size) { BooleanArray(layers.size) { layers[it].name != "Kick" } }
        val r = Arranger.apply(p, loop.id, t, layers, noKick, Arranger.bars(t, loop.bars, false), true)!!
        val without = play(r, song = true, frames = frames)
        var differs = 0f
        for (i in asLoop.indices) differs = max(differs, abs(asLoop[i] - without[i]))
        assertTrue("switching the kick off changes nothing: $differs", differs > 0.05f)
    }
}
