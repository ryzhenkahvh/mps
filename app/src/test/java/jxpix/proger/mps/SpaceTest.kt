package jxpix.proger.mps

import jxpix.proger.mps.Signals.RATE
import jxpix.proger.mps.Signals.brightness
import jxpix.proger.mps.Signals.cents
import jxpix.proger.mps.Signals.db
import jxpix.proger.mps.Signals.effect
import jxpix.proger.mps.Signals.noise
import jxpix.proger.mps.Signals.note
import jxpix.proger.mps.Signals.pitch
import jxpix.proger.mps.Signals.playing
import jxpix.proger.mps.Signals.rms
import jxpix.proger.mps.Signals.run
import jxpix.proger.mps.Signals.step
import jxpix.proger.mps.Signals.stopped
import jxpix.proger.mps.Signals.tone
import jxpix.proger.mps.engine.Devices
import jxpix.proger.mps.engine.Engine
import jxpix.proger.mps.engine.Instrument
import jxpix.proger.mps.engine.PPQ
import jxpix.proger.mps.engine.SampleData
import jxpix.proger.mps.engine.SamplePool
import jxpix.proger.mps.engine.effects.Space3D
import jxpix.proger.mps.engine.instruments.DrumSynth
import jxpix.proger.mps.model.AutoPoint
import jxpix.proger.mps.model.Note
import jxpix.proger.mps.model.PARAM_PAN
import jxpix.proger.mps.model.Presets
import jxpix.proger.mps.model.Project
import jxpix.proger.mps.model.STRIP_DEVICE
import jxpix.proger.mps.model.Scales
import jxpix.proger.mps.model.addAudioClip
import jxpix.proger.mps.model.addChannel
import jxpix.proger.mps.model.addEffect
import jxpix.proger.mps.model.addPattern
import jxpix.proger.mps.model.addPatternClip
import jxpix.proger.mps.model.duplicateChannel
import jxpix.proger.mps.model.removeChannel
import jxpix.proger.mps.model.removeEffect
import jxpix.proger.mps.model.sanitized
import jxpix.proger.mps.model.setNotes
import jxpix.proger.mps.model.setParam
import jxpix.proger.mps.model.setSongAutomation
import jxpix.proger.mps.model.snapNotesToKey
import jxpix.proger.mps.model.updateClip
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/** Where a sound sits between the speakers and around the head: notes, clips, curves, the 3D effect. */
class SpaceTest {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }

    /**
     * "In the middle" for a moving source, in dB between the ears: the sound follows the knob with a
     * delay of a few hundredths of a second (so that a turned knob never clicks), and noise is told
     * apart by the ears within a few degrees.
     */
    private val MIDDLE = 4.5

    /** What the engine plays during the next [seconds]: left and right. */
    private fun play(engine: Engine, seconds: Double): Pair<FloatArray, FloatArray> {
        val frames = (seconds * RATE).toInt()
        val l = FloatArray(frames)
        val r = FloatArray(frames)
        val buf = FloatArray(256 * 2)
        var done = 0
        while (done < frames) {
            val n = minOf(256, frames - done)
            engine.render(buf, n)
            for (i in 0 until n) {
                l[done + i] = buf[i * 2]
                r[done + i] = buf[i * 2 + 1]
            }
            done += n
        }
        return l to r
    }

    /** Left and right level of what the engine plays between two moments (in seconds). */
    private fun levels(engine: Engine, seconds: Double, windows: List<Pair<Double, Double>>): List<Pair<Double, Double>> {
        val (l, r) = play(engine, seconds)
        return windows.map { (a, b) -> rms(l, (a * RATE).toInt(), (b * RATE).toInt()) to rms(r, (a * RATE).toInt(), (b * RATE).toInt()) }
    }

    @Test
    fun everyInstrumentPlacesANoteBetweenTheSpeakers() {
        val sample = SampleData(RATE, ShortArray(RATE) { (sin(2 * Math.PI * 330 * it / RATE) * 20000).toInt().toShort() })
        for (type in Devices.playable) {
            val params = if (type === Devices.DRUM) DrumSynth.defaultsFor(DrumSynth.SNARE) else type.defaults()
            fun play(pan: Float?): Pair<Double, Double> {
                val inst = type.create() as Instrument
                inst.prepare(RATE)
                inst.setParams(type.toArray(params))
                inst.setSample(sample)
                val l = FloatArray(RATE / 4)
                val r = FloatArray(RATE / 4)
                if (pan == null) inst.noteOn(60, 0.9f) else inst.noteOn(60, 0.9f, pan)
                var pos = 0
                while (pos < l.size) {
                    val n = minOf(256, l.size - pos)
                    inst.render(l, r, pos, n)
                    pos += n
                }
                return rms(l) to rms(r)
            }
            val plain = play(null)
            val centre = play(0f)
            assertTrue("${type.id} sounds", plain.first > 0.005 && plain.second > 0.005)
            assertEquals("${type.id}: a note in the middle is the note as it always was", plain.first, centre.first, 1e-12)
            assertEquals(plain.second, centre.second, 1e-12)
            val left = play(-1f)
            assertEquals("${type.id}: hard left keeps the left side", plain.first, left.first, plain.first * 1e-6)
            assertTrue("${type.id}: hard left leaves nothing on the right (${left.second})", left.second < 1e-9)
            val right = play(1f)
            assertTrue("${type.id}: hard right leaves nothing on the left (${right.first})", right.first < 1e-9)
            assertEquals(plain.second, right.second, plain.second * 1e-6)
            val half = play(0.5f)
            assertEquals("${type.id}: half right halves the left side", plain.first * 0.5, half.first, plain.first * 1e-4)
            assertEquals(plain.second, half.second, plain.second * 1e-6)
            // the next note is in the middle again
            val inst = type.create() as Instrument
            inst.prepare(RATE)
            inst.setParams(type.toArray(params))
            inst.setSample(sample)
            val l = FloatArray(4096)
            val r = FloatArray(4096)
            inst.noteOn(60, 0.9f, -1f)
            inst.render(l, r, 0, 2048)
            inst.allNotesOff()
            inst.noteOn(72, 0.9f)
            inst.render(l, r, 2048, 2048)
            assertTrue("${type.id}: the balance of one note does not stick to the next", rms(r, 2048, 4096) > 0.001)
        }
    }

    @Test
    fun notesAndClipsKeepTheirPlaceInTheSong() {
        // ---- two notes of one pattern: hard left, then hard right
        var p = Project(name = "pan", bpm = 120f, tracks = 2)
        val pattern = p.addPattern("p").let { p = it.first; it.second }
        val synth = p.addChannel(Devices.SYNTH.id, "s", params = Presets.synth("init").params).let { p = it.first; it.second }
        p = p.setNotes(pattern, synth, listOf(Note(0, PPQ, 60, 0.9f, pan = -1f), Note(2 * PPQ, PPQ, 60, 0.9f, pan = 1f)))
        var engine = Engine(RATE)
        engine.setProject(p, SamplePool())
        engine.patternId = pattern
        engine.play(0)
        val notes = levels(engine, 2.0, listOf(0.05 to 0.45, 1.05 to 1.45))
        println("     info note pan: first note L %.4f R %.6f, second note L %.6f R %.4f".format(notes[0].first, notes[0].second, notes[1].first, notes[1].second))
        assertTrue("the first note is on the left", notes[0].first > 0.01 && notes[0].second < notes[0].first * 0.001)
        assertTrue("the second one on the right", notes[1].second > 0.01 && notes[1].first < notes[1].second * 0.05)

        // a project written by the first version has no balance: its notes are in the middle
        val old = json.decodeFromString(Note.serializer(), """{"start":0,"length":120,"pitch":60,"velocity":0.5}""")
        assertEquals(0f, old.pan, 0f)
        assertFalse("a note in the middle is stored as before", json.encodeToString(Note.serializer(), old).contains("pan"))
        assertEquals(-0.25f, json.decodeFromString(Note.serializer(), json.encodeToString(Note.serializer(), old.copy(pan = -0.25f))).pan, 0f)

        // ---- an audio clip with a balance of its own
        val pool = SamplePool()
        pool.put("tone.wav", SampleData(RATE, ShortArray(RATE * 2) { (sin(2 * Math.PI * 440 * it / RATE) * 20000).toInt().toShort() }))
        var q = Project(name = "audio", bpm = 120f, tracks = 2)
        val audio = q.addChannel(Devices.AUDIO_TYPE_ID, "a").let { q = it.first; it.second }
        val clip = q.addAudioClip(audio, "tone.wav", 0, 0, 4 * PPQ).let { q = it.first; it.second }
        fun song(project: Project, windows: List<Pair<Double, Double>>): List<Pair<Double, Double>> {
            val e = Engine(RATE)
            e.songMode = true
            e.setProject(project, pool)
            e.play(0)
            return levels(e, 2.0, windows)
        }
        val whole = listOf(0.1 to 1.9)
        val centre = song(q, whole)[0]
        assertEquals("a clip in the middle is as loud on both sides", centre.first, centre.second, 1e-6)
        val left = song(q.updateClip(clip) { it.copy(pan = -1f) }, whole)[0]
        assertEquals(centre.first, left.first, centre.first * 1e-4)
        assertTrue("clip hard left: ${left.second}", left.second < 1e-6)
        val right = song(q.updateClip(clip) { it.copy(pan = 0.5f) }, whole)[0]
        assertEquals(centre.first * 0.5, right.first, centre.first * 1e-3)
        assertEquals(centre.second, right.second, centre.second * 1e-4)

        // ---- a curve along the song moves the balance knob of the audio track
        val ends = listOf(0.02 to 0.2, 1.8 to 1.98)
        var curved = q.setSongAutomation(audio, STRIP_DEVICE, PARAM_PAN, listOf(AutoPoint(0, 0f), AutoPoint(4 * PPQ, 1f)))
        val sweep = song(curved, ends)
        println("     info track curve: start L %.4f R %.4f, end L %.4f R %.4f".format(sweep[0].first, sweep[0].second, sweep[1].first, sweep[1].second))
        assertTrue("the track starts on the left", sweep[0].first > sweep[0].second * 5)
        assertTrue("and ends on the right", sweep[1].second > sweep[1].first * 5)
        // the same audio without the curve stays in the middle
        val still = song(q, ends)
        assertEquals(still[0].first, still[0].second, 1e-6)

        // ---- and it turns the knobs of the track's effects: the 3D angle from the left ear to the right
        val fx = curved.addEffect(audio, Space3D.TYPE_ID).let { curved = it.first; it.second }
        curved = curved.setSongAutomation(audio, STRIP_DEVICE, PARAM_PAN, emptyList())
        assertTrue(curved.automation.isEmpty())
        val spec = Space3D.TYPE.spec("angle")!!
        curved = curved.copy(channels = curved.channels.map { c ->
            c.copy(effects = c.effects.map { it.copy(params = it.params + mapOf("motion" to 0f, "room" to 0f)) })
        })
        curved = curved.setSongAutomation(audio, fx, "angle", listOf(AutoPoint(0, spec.toNorm(-90f)), AutoPoint(4 * PPQ, spec.toNorm(90f))))
        val turned = song(curved, ends)
        println("     info 3D curve: start L %.4f R %.4f, end L %.4f R %.4f".format(turned[0].first, turned[0].second, turned[1].first, turned[1].second))
        assertTrue("the sound starts at the left ear", turned[0].first > turned[0].second * 1.5)
        assertTrue("and ends at the right one", turned[1].second > turned[1].first * 1.5)

        // ---- the curves belong to the track and its devices
        assertEquals(1, curved.automation.size)
        val saved = json.decodeFromString(Project.serializer(), json.encodeToString(Project.serializer(), curved)).sanitized()
        assertEquals("the curve is saved with the project", curved.automation, saved.automation)
        val copy = curved.duplicateChannel(audio, "copy")
        assertEquals("a copied track brings its curve along", 2, copy.first.automation.size)
        assertNotNull(copy.first.automation.firstOrNull { it.channelId == copy.second })
        assertTrue("a removed effect takes its curve with it", curved.removeEffect(audio, fx).automation.isEmpty())
        assertTrue("a removed track too", curved.removeChannel(audio).automation.isEmpty())
        val stray = curved.copy(automation = curved.automation + curved.automation[0].copy(channelId = 9999))
        assertEquals("a curve without a track is dropped when the project is opened", 1, stray.sanitized().automation.size)
        // a long song drawn finely still fits
        val many = List(3000) { AutoPoint(it * 60, (it % 100) / 100f) }
        assertEquals(3000, q.setSongAutomation(audio, STRIP_DEVICE, PARAM_PAN, many).automation[0].points.size)
        // a project of the first version has none of this
        val first = json.decodeFromString(Project.serializer(), """{"name":"old","bpm":90.0}""").sanitized()
        assertTrue(first.automation.isEmpty())
        assertEquals(0, first.scale)
        assertFalse("and is written back the way it was", json.encodeToString(Project.serializer(), first).contains("automation"))
    }

    @Test
    fun keyOfTheSongIsKeptAndNotesCanBePulledIntoIt() {
        assertEquals(0, Scales.mask(0, Scales.NONE))
        val cMajor = Scales.mask(0, Scales.MAJOR)
        for (pitch in listOf(60, 62, 64, 65, 67, 69, 71, 72)) assertTrue("$pitch", Scales.contains(cMajor, pitch))
        for (pitch in listOf(61, 63, 66, 68, 70)) assertFalse("$pitch", Scales.contains(cMajor, pitch))
        assertEquals("A minor has the notes of C major", cMajor, Scales.mask(9, Scales.MINOR))
        assertEquals(60, Scales.nearest(61, cMajor))
        assertEquals(65, Scales.nearest(66, cMajor))
        assertEquals(72, Scales.nearest(72, cMajor))
        assertEquals("without a key every note stays", 61, Scales.nearest(61, 0))
        // every scale starts on its tonic and stays inside the octave
        for (s in 1 until Scales.count) {
            assertEquals(0, Scales.steps[s][0])
            assertTrue(Scales.steps[s].all { it in 0..11 })
            assertEquals(Scales.steps[s].size, Integer.bitCount(Scales.mask(5, s)))
        }

        var p = Project(name = "key", keyRoot = 2, scale = Scales.MINOR)
        val pattern = p.addPattern("p").let { p = it.first; it.second }
        val ch = p.addChannel(Devices.SYNTH.id, "s").let { p = it.first; it.second }
        // D minor: D E F G A Bb C
        val notes = listOf(Note(0, 120, 62), Note(120, 120, 63), Note(240, 120, 66, pan = 0.5f), Note(360, 120, 70))
        p = p.setNotes(pattern, ch, notes)
        val snapped = p.snapNotesToKey(pattern, ch).pattern(pattern)!!.notesOf(ch)
        assertEquals(listOf(62, 62, 65, 70), snapped.map { it.pitch })
        assertEquals("only the pitch changes", 0.5f, snapped[2].pan, 0f)
        val only = p.snapNotesToKey(pattern, ch, only = setOf(notes[2])).pattern(pattern)!!.notesOf(ch)
        assertEquals(listOf(62, 63, 65, 70), only.map { it.pitch })
        assertEquals("nothing to do without a key", notes, p.copy(scale = 0).snapNotesToKey(pattern, ch).pattern(pattern)!!.notesOf(ch))

        val back = json.decodeFromString(Project.serializer(), json.encodeToString(Project.serializer(), p)).sanitized()
        assertEquals(2, back.keyRoot)
        assertEquals(Scales.MINOR, back.scale)
        assertEquals(Scales.mask(2, Scales.MINOR), back.keyMask)
        assertEquals("a scale this version does not know is no scale", 0, p.copy(scale = 99).sanitized().scale)
        assertNull(p.songLane(ch, STRIP_DEVICE, PARAM_PAN))
    }

    /** The source: a second of noise, the same on both sides. */
    private fun place(values: Map<String, Float>, seconds: Double = 1.0, tail: Double = 0.0, ctx: jxpix.proger.mps.engine.ProcessContext = stopped()): Pair<FloatArray, FloatArray> {
        val src = noise(seconds, 0.4f)
        val l = FloatArray(((seconds + tail) * RATE).toInt()) { if (it < src.size) src[it] else 0f }
        val r = l.copyOf()
        run(effect(Space3D.TYPE, mapOf("motion" to 0f, "room" to 0f) + values), l, r, ctx)
        return l to r
    }

    /** By how many samples the left side lags behind the right one (negative: it leads). */
    private fun lag(l: FloatArray, r: FloatArray): Int {
        var best = -1e30
        var bestLag = 0
        for (lag in -60..60) {
            var s = 0.0
            for (i in 2000 until 30000) s += l[i + lag].toDouble() * r[i]
            if (s > best) {
                best = s
                bestLag = lag
            }
        }
        return bestLag
    }

    @Test
    fun threeDSoundPutsTheSourceWhereTheKnobsSay() {
        val from = RATE / 10
        // ---- straight ahead: both ears hear the same
        val (fl, fr) = place(mapOf("angle" to 0f))
        var worst = 0f
        for (i in fl.indices) worst = maxOf(worst, abs(fl[i] - fr[i]))
        assertTrue("front: the ears differ by $worst", worst < 1e-5f)
        assertTrue(rms(fl, from) > 0.05)

        // ---- to the right: louder, earlier and brighter in the right ear
        val (rl, rr) = place(mapOf("angle" to 90f))
        val ild = db(rms(rr, from) / rms(rl, from))
        val itd = lag(rl, rr)
        println("     info 3D right: level difference %.1f dB, left ear %d samples later, brightness L %.3f R %.3f".format(ild, itd, brightness(rl, from), brightness(rr, from)))
        assertTrue("right is louder on the right: $ild dB", ild > 4.0)
        assertTrue("the left ear hears it 0.5 - 0.75 ms later: $itd samples", itd in 24..36)
        assertTrue("the far ear is duller", brightness(rl, from) < brightness(rr, from) * 0.8)
        // ---- to the left: the mirror image
        val (ll, lr) = place(mapOf("angle" to -90f))
        assertEquals(rms(rr, from), rms(ll, from), rms(rr, from) * 0.01)
        assertEquals(rms(rl, from), rms(lr, from), rms(rl, from) * 0.01)
        assertEquals(-itd, lag(ll, lr))
        // half way there the difference is smaller
        val (hl, hr) = place(mapOf("angle" to 30f))
        val part = db(rms(hr, from) / rms(hl, from))
        assertTrue("30 degrees: $part dB", part > 1.0 && part < ild)

        // ---- behind: the same in both ears again, but duller than in front
        val (bl, br) = place(mapOf("angle" to 180f))
        worst = 0f
        for (i in bl.indices) worst = maxOf(worst, abs(bl[i] - br[i]))
        assertTrue("behind: the ears differ by $worst", worst < 1e-4f)
        println("     info 3D front / behind brightness: %.3f / %.3f".format(brightness(fl, from), brightness(bl, from)))
        assertTrue("behind is duller", brightness(bl, from) < brightness(fl, from) * 0.8)

        // ---- above: the same in both ears, with a colour of its own
        val (ul, ur) = place(mapOf("angle" to 0f, "height" to 1f))
        worst = 0f
        for (i in ul.indices) worst = maxOf(worst, abs(ul[i] - ur[i]))
        assertTrue(worst < 1e-5f)
        var differs = 0.0
        for (i in from until ul.size) differs += abs(ul[i] - fl[i])
        assertTrue("height changes the colour of the sound", differs / (ul.size - from) > 0.005)
        // a source right above the head is not to one side any more
        val (sl, sr) = place(mapOf("angle" to 90f, "height" to 1f))
        assertTrue(db(rms(sr, from) / rms(sl, from)) < ild * 0.6)

        // ---- distance: far away is quieter and duller; the room stays
        val (nl, _) = place(mapOf("distance" to 0.05f))
        val (dl, _) = place(mapOf("distance" to 0.95f))
        val drop = db(rms(nl, from) / rms(dl, from))
        println("     info 3D distance: far is %.1f dB quieter, brightness near %.3f far %.3f".format(drop, brightness(nl, from), brightness(dl, from)))
        assertTrue("far is quieter: $drop dB", drop > 8.0)
        assertTrue("and duller", brightness(dl, from) < brightness(nl, from) * 0.9)
        val (dryL, _) = place(mapOf("room" to 0f), tail = 1.0)
        val (wetL, wetR) = place(mapOf("room" to 0.6f), tail = 1.0)
        assertTrue("without a room the sound ends when it ends", rms(dryL, RATE + RATE / 10) < 1e-5)
        assertTrue("the room rings on", rms(wetL, RATE + RATE / 20, RATE + RATE / 4) > 0.002)
        var same = 0.0
        for (i in RATE + 2000 until RATE + 12000) same += abs(wetL[i] - wetR[i])
        assertTrue("and it is wide: the two ears hear different echoes", same > 1.0)
        assertTrue("the room dies away", rms(wetL, wetL.size - RATE / 10) < rms(wetL, RATE + RATE / 20, RATE + RATE / 4) * 0.2)

        // ---- movement: once round the head in one bar (two seconds at 120 BPM), clockwise
        fun around(values: Map<String, Float>, ctx: jxpix.proger.mps.engine.ProcessContext): List<Double> {
            val (l, r) = place(values, seconds = 4.0, ctx = ctx)
            // right minus left, in dB, around every eighth of a bar (the very start has nothing before it)
            return (0 until 16).map { k ->
                val a = (k * 0.25 * RATE).toInt()
                val lo = maxOf(600, a - 2400)
                db(rms(r, lo, a + 2400) / rms(l, lo, a + 2400))
            }
        }
        val circle = around(mapOf("motion" to 5f, "arc" to 360f), playing())
        println("     info 3D circle: " + circle.joinToString(" ") { "%.1f".format(it) })
        assertTrue("a quarter of the way round it is on the right", circle[2] > 6.0)
        assertTrue("three quarters round on the left", circle[6] < -6.0)
        assertTrue("behind the head in between", abs(circle[4]) < MIDDLE)
        assertTrue("and in front again when the bar is over", abs(circle[8]) < MIDDLE)
        assertTrue("an eighth of the way: on the right already, but less", circle[1] > 2.0 && circle[1] < circle[2])
        for (k in 1 until 8) assertEquals("the second bar repeats the first ($k)", circle[k], circle[k + 8], 0.8)
        // wherever the song starts, the sound is where that moment of the bar puts it
        val late = around(mapOf("motion" to 5f, "arc" to 360f), playing(fromTick = 2.0 * PPQ))
        assertTrue("started half a bar in: on the left a quarter of a bar later (${late[2]})", late[2] < -6.0)
        assertTrue("and in front after half a bar (${late[4]})", abs(late[4]) < MIDDLE)
        // the angle knob turns the whole circle
        val turned = around(mapOf("motion" to 5f, "arc" to 360f, "angle" to 90f), playing())
        assertTrue("the circle starts where the angle knob points", turned[0] > 6.0 && turned[4] < -6.0 && abs(turned[2]) < MIDDLE)
        // free running at 1 Hz, no song needed
        val free = around(mapOf("motion" to 1f, "rate" to 1f, "arc" to 360f), stopped())
        assertTrue("free: right after a quarter of a second, left after three (${free[1]}, ${free[3]})", free[1] > 6.0 && free[3] < -6.0)
        // a swing of 180 degrees goes right, back to the middle, left - and never behind
        val swing = around(mapOf("motion" to 5f, "arc" to 180f), playing())
        println("     info 3D swing: " + swing.take(8).joinToString(" ") { "%.1f".format(it) })
        assertTrue(swing[2] > 6.0 && swing[6] < -6.0 && abs(swing[4]) < MIDDLE)
        // in front at the turning point of a full circle's "behind": the swing is not duller there
        assertTrue(swing[1] > 2.0 && swing[3] > 2.0 && swing[5] < -2.0 && swing[7] < -2.0)
        // no movement: it stays where it is
        val fixed = around(mapOf("motion" to 0f, "angle" to 45f), playing())
        for (k in 1 until 16) assertEquals("a fixed source stays put ($k)", fixed[1], fixed[k], 1.5)
        assertTrue(fixed[1] > 3.0)

        // ---- a turned knob does not click: the sound travels to its new place
        val fx = effect(Space3D.TYPE, mapOf("motion" to 0f, "room" to 0f, "angle" to -90f))
        val tl = tone(200.0, 1.0, 0.5f)
        val tr = tl.copyOf()
        val block = FloatArray(256)
        val blockR = FloatArray(256)
        var pos = 0
        while (pos < tl.size) {
            if (pos == 256 * 90) fx.setParams(Space3D.TYPE.toArray(mapOf("motion" to 0f, "room" to 0f, "angle" to 90f)))
            val n = minOf(256, tl.size - pos)
            System.arraycopy(tl, pos, block, 0, n)
            System.arraycopy(tr, pos, blockR, 0, n)
            fx.process(block, blockR, n, stopped())
            System.arraycopy(block, 0, tl, pos, n)
            System.arraycopy(blockR, 0, tr, pos, n)
            pos += n
        }
        // a 200 Hz tone of this size moves by 0.013 per sample at most
        println("     info 3D knob jump: largest step L %.4f R %.4f".format(step(tl, 4000), step(tr, 4000)))
        assertTrue("left ${step(tl, 4000)}", step(tl, 4000) < 0.03f)
        assertTrue("right ${step(tr, 4000)}", step(tr, 4000) < 0.03f)
        assertTrue("it has arrived on the right", rms(tr, RATE * 3 / 4) > rms(tl, RATE * 3 / 4) * 1.3)
    }

    /**
     * A movement that takes longer than the pattern must not start again every time the pattern does:
     * with a pattern of one bar and a circle of four bars the sound still goes all the way round.
     */
    @Test
    fun movementLongerThanThePatternGoesAllTheWayRound() {
        val pool = SamplePool()
        var p = Project(name = "loop", bpm = 120f, tracks = 2)
        val pattern = p.addPattern("p").let { p = it.first; it.second }
        val synth = p.addChannel(Devices.SYNTH.id, "s", params = Presets.synth("init").params).let { p = it.first; it.second }
        // four notes in the bar, so that there is sound all the time
        p = p.setNotes(pattern, synth, (0 until 4).map { Note(it * PPQ, PPQ - 10, 72, 0.9f) })
        val fx = p.addEffect(synth, "space3d").let { p = it.first; it.second }
        p = p.setParam(fx, "motion", 3f).setParam(fx, "room", 0f)
        assertEquals("the pattern is one bar long", 1, p.patterns.first { it.id == pattern }.bars)

        fun sides(engine: Engine, moments: List<Double>): List<Double> =
            levels(engine, moments.last() + 0.5, moments.map { (it - 0.2) to (it + 0.2) }).map { (l, r) -> db(r / l) }

        // one bar is two seconds, the circle takes eight
        val engine = Engine(RATE)
        engine.setProject(p, pool)
        engine.patternId = pattern
        engine.play(0)
        val moments = listOf(1.0, 2.0, 3.0, 4.0, 5.0, 6.0, 7.0, 8.0, 10.0, 14.0)
        val heard = sides(engine, moments)
        println("     info 3D over a looped bar: " + heard.joinToString(" ") { "%.1f".format(it) })
        assertTrue("a quarter of the way round: on the right (${heard[1]})", heard[1] > 6.0)
        assertTrue("half way: behind the head (${heard[3]})", abs(heard[3]) < MIDDLE)
        assertTrue("three quarters: on the LEFT, though the pattern has started again three times (${heard[5]})", heard[5] < -6.0)
        assertTrue("and in front again after four bars (${heard[7]})", abs(heard[7]) < MIDDLE)
        assertTrue("the second round is like the first", heard[8] > 6.0 && heard[9] < -6.0)
        assertTrue("on the way it passes the places in between", heard[0] > 2.0 && heard[0] < heard[1] && heard[4] < -2.0 && heard[4] > heard[5])

        // the same notes laid out along the song sound the same: the pattern coming round changes nothing
        var q = p
        for (bar in 0 until 8) q = q.addPatternClip(pattern, 0, bar * 4 * PPQ, 4 * PPQ).first
        val song = Engine(RATE)
        song.songMode = true
        song.setProject(q, pool)
        song.play(0)
        val along = sides(song, moments)
        for (k in moments.indices) assertEquals("moment ${moments[k]}", along[k], heard[k], 0.3)

        // playing again starts the movement again
        engine.stop()
        engine.play(0)
        val again = sides(engine, listOf(2.0, 6.0))
        assertEquals(heard[1], again[0], 0.3)
        assertEquals(heard[5], again[1], 0.3)

        // a loop of one bar in the song does not cut the circle short either
        val looped = Engine(RATE)
        looped.songMode = true
        looped.setProject(q.copy(loopEnabled = true, loopStart = 0, loopEnd = 1), pool)
        looped.play(0)
        val inLoop = sides(looped, listOf(2.0, 6.0))
        assertTrue("right first: ${inLoop[0]}", inLoop[0] > 6.0)
        assertTrue("left after three rounds of the loop: ${inLoop[1]}", inLoop[1] < -6.0)
    }

    /**
     * Effects that count bars ("a fill every second bar", "half-time over two bars") keep counting
     * when a short pattern comes round: the pattern is one bar long here, and the fill belongs to
     * every SECOND time it is played.
     */
    @Test
    fun barCountingEffectsCountThePassesOfAShortPattern() {
        val pool = SamplePool()
        var p = Project(name = "fills", bpm = 120f, tracks = 2)
        val pattern = p.addPattern("p").let { p = it.first; it.second }
        val synth = p.addChannel(Devices.SYNTH.id, "s", params = Presets.synth("init").params).let { p = it.first; it.second }
        // a different note on every beat: one sixteenth of the bar never sounds like the one before it
        p = p.setNotes(pattern, synth, listOf(60, 64, 67, 71).mapIndexed { i, pitch -> Note(i * PPQ, PPQ - 10, pitch, 0.9f) })
        assertEquals(1, p.patterns.first { it.id == pattern }.bars)
        val plain = p
        val fx = p.addEffect(synth, "repeat").let { p = it.first; it.second }
        // every 2 bars, the last beat, in sixteenths
        p = p.setParam(fx, "every", 2f).setParam(fx, "length", 0f).setParam(fx, "slice", 3f).setParam(fx, "decay", 0f).setParam(fx, "mix", 1f)

        val piece = RATE / 8
        /** How much a sixteenth differs from the one before it: next to nothing inside a fill. */
        fun change(a: FloatArray, seconds: Double): Double {
            val at = (seconds * RATE).toInt()
            var diff = 0.0
            var size = 0.0
            for (i in 300 until piece - 300) {
                val d = (a[at + piece + i] - a[at + i]).toDouble()
                diff += d * d
                size += a[at + i].toDouble() * a[at + i]
            }
            return sqrt(diff / max(size, 1e-12))
        }

        fun fills(project: Project, song: Boolean, seconds: Double, moments: List<Double>): List<Double> {
            val engine = Engine(RATE)
            engine.songMode = song
            engine.setProject(project, pool)
            if (!song) engine.patternId = pattern
            engine.play(0)
            val (l, _) = play(engine, seconds)
            return moments.map { change(l, it) }
        }

        // one bar is two seconds; the last beat of the passes begins at 1.5, 3.5, 5.5 and 7.5 s
        val lastBeats = listOf(1.5, 3.5, 5.5, 7.5)
        val without = fills(plain, false, 8.5, lastBeats)
        val heard = fills(p, false, 8.5, lastBeats)
        println("     info fill every 2 bars over a 1-bar pattern: " + heard.joinToString(" ") { "%.3f".format(it) } + "  (without: " + without.joinToString(" ") { "%.3f".format(it) } + ")")
        for (v in without) assertTrue("an untouched beat is no repeat of itself ($v)", v > 0.3)
        assertTrue("first pass: no fill (${heard[0]})", heard[0] > 0.3)
        assertTrue("second pass: the fill (${heard[1]})", heard[1] < 0.03)
        assertTrue("third pass: none (${heard[2]})", heard[2] > 0.3)
        assertTrue("fourth pass: the fill again (${heard[3]})", heard[3] < 0.03)

        // laid out along the song it is the same
        var q = p
        for (bar in 0 until 5) q = q.addPatternClip(pattern, 0, bar * 4 * PPQ, 4 * PPQ).first
        val along = fills(q, true, 8.5, lastBeats)
        for (k in lastBeats.indices) assertEquals("bar ${k + 1}", along[k] < 0.03, heard[k] < 0.03)

        // and half-time over two bars really takes two passes: the second one goes on where the first one stopped
        var h = plain
        val half = h.addEffect(synth, "halftime").let { h = it.first; it.second }
        h = h.setParam(half, "length", 3f).setParam(half, "speed", 1f).setParam(half, "smooth", 0f).setParam(half, "mix", 1f)
        val engine = Engine(RATE)
        engine.setProject(h, pool)
        engine.patternId = pattern
        engine.play(0)
        val (slow, _) = play(engine, 8.5)
        val source = Engine(RATE).also {
            it.setProject(plain, pool)
            it.patternId = pattern
            it.play(0)
        }.let { play(it, 8.5).first }
        // at half speed the four notes of the first pass (60, 64, 67, 71) take two passes, an octave down:
        // the third of them (67 -> 55) is heard during the SECOND pass, between 2 and 3 s
        val firstPass = pitch(slow, (0.4 * RATE).toInt(), note(48.0))
        val secondPass = pitch(slow, (2.4 * RATE).toInt(), note(55.0))
        println("     info half-time over 2 bars on a 1-bar pattern: %.1f Hz in the first pass, %.1f Hz in the second (source %.1f Hz)".format(firstPass, secondPass, pitch(source, (0.4 * RATE).toInt(), note(60.0))))
        assertEquals("first pass: the first note an octave down", 0.0, cents(firstPass, note(48.0)), 30.0)
        assertEquals("second pass: the THIRD note an octave down, not the first again", 0.0, cents(secondPass, note(55.0)), 30.0)
    }
}
