package jxpix.proger.mps

import jxpix.proger.mps.engine.Devices
import jxpix.proger.mps.engine.Engine
import jxpix.proger.mps.engine.EngineWarmup
import jxpix.proger.mps.engine.Instrument
import jxpix.proger.mps.engine.OfflineRenderer
import jxpix.proger.mps.engine.PPQ
import jxpix.proger.mps.engine.SampleData
import jxpix.proger.mps.engine.SamplePool
import jxpix.proger.mps.engine.Wav
import jxpix.proger.mps.engine.instruments.DrumSynth
import jxpix.proger.mps.model.autoValueAt
import jxpix.proger.mps.model.repeatPoints
import jxpix.proger.mps.model.AutoPoint
import jxpix.proger.mps.model.Note
import jxpix.proger.mps.model.PARAM_PAN
import jxpix.proger.mps.model.PARAM_VOLUME
import jxpix.proger.mps.model.Presets
import jxpix.proger.mps.model.Project
import jxpix.proger.mps.model.ProjectFactory
import jxpix.proger.mps.model.STRIP_DEVICE
import jxpix.proger.mps.model.addChannel
import jxpix.proger.mps.model.addEffect
import jxpix.proger.mps.model.addPattern
import jxpix.proger.mps.model.addPatternClip
import jxpix.proger.mps.model.cleanPoints
import jxpix.proger.mps.model.duplicateChannel
import jxpix.proger.mps.model.removeEffect
import jxpix.proger.mps.model.replaceInstrument
import jxpix.proger.mps.model.sanitized
import jxpix.proger.mps.model.setAutomation
import jxpix.proger.mps.model.setNotes
import jxpix.proger.mps.model.swingTick
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The sound engine is plain Kotlin, so it can be rendered and checked on the JVM
 * without a device: every template must produce clean, audible, bounded audio.
 */
class EngineTest {

    private val names: (Int) -> String = { "name" }
    private val rate = 48000

    /** Set MPS_RENDER_DIR to keep the rendered files for listening. */
    private fun outFile(name: String): File {
        val dir = System.getenv("MPS_RENDER_DIR")?.let { File(it) } ?: File(System.getProperty("java.io.tmpdir"), "mps-test")
        dir.mkdirs()
        return File(dir, name)
    }

    private fun rms(data: ShortArray): Double {
        var sum = 0.0
        for (v in data) sum += (v / 32768.0) * (v / 32768.0)
        return sqrt(sum / max(1, data.size))
    }

    @Test
    fun everyTemplateRendersCleanAudio() {
        for (t in ProjectFactory.templates) {
            val project = t.build(names)
            val file = outFile("${t.id}.wav")
            val started = System.nanoTime()
            val result = if (project.clips.isEmpty()) {
                val pattern = project.patterns.first()
                if (pattern.isEmpty) continue
                OfflineRenderer.render(project, SamplePool(), file, rate, patternId = pattern.id, loops = 4)
            } else {
                OfflineRenderer.render(project, SamplePool(), file, rate)
            }
            val took = (System.nanoTime() - started) / 1e9
            assertNotNull("${t.id} rendered nothing", result)
            val wav = Wav.read(file)
            assertEquals(rate, wav.sampleRate)
            assertTrue(wav.isStereo)
            val level = rms(wav.left)
            println(
                "template ${t.id}: %.1f s, peak %.3f, rms %.3f, limiter %.1f dB, rendered %.0fx faster than real time"
                    .format(result!!.seconds, result.peak, level, result.limiterDb, result.seconds / took)
            )
            assertTrue("${t.id} clips: ${result.peak}", result.peak <= 1.0f)
            assertTrue("${t.id} is too quiet: $level", level > 0.03)
        }
    }

    @Test
    fun everySynthPresetAndDrumMakesSound() {
        val l = FloatArray(rate)
        val r = FloatArray(rate)
        fun measure(inst: Instrument, pitch: Int): Pair<Float, Double> {
            l.fill(0f); r.fill(0f)
            inst.noteOn(pitch, 0.8f)
            var done = 0
            while (done < rate / 2) { inst.render(l, r, done, 256); done += 256 }
            inst.noteOff(pitch)
            while (done + 256 <= rate) { inst.render(l, r, done, 256); done += 256 }
            var peak = 0f
            var sum = 0.0
            for (i in 0 until done) {
                assertFalse("NaN in output", l[i].isNaN() || r[i].isNaN())
                peak = max(peak, max(abs(l[i]), abs(r[i])))
                sum += l[i] * l[i]
            }
            return peak to sqrt(sum / done)
        }
        for (preset in Presets.synth) {
            val synth = Devices.SYNTH.create() as Instrument
            synth.prepare(rate)
            synth.setParams(Devices.SYNTH.toArray(preset.params))
            val pitch = if (preset.categoryRes == R.string.cat_bass) 36 else 60
            val (peak, level) = measure(synth, pitch)
            println("preset %-12s peak %.3f rms %.3f".format(preset.id, peak, level))
            assertTrue("${preset.id} is silent", peak > 0.02f)
            assertTrue("${preset.id} is too loud: $peak", peak < 1.2f)
        }
        for (type in 0 until DrumSynth.TYPE_COUNT) {
            val drum = Devices.DRUM.create() as Instrument
            drum.prepare(rate)
            drum.setParams(Devices.DRUM.toArray(DrumSynth.defaultsFor(type)))
            val (peak, level) = measure(drum, 60)
            println("drum %-2d peak %.3f rms %.3f".format(type, peak, level))
            assertTrue("drum $type is silent", peak > 0.05f)
            assertTrue("drum $type is too loud: $peak", peak < 1.3f)
        }
    }

    @Test
    fun patternModeLoopsAndStopKeepsSilence() {
        val project = ProjectFactory.house(names)
        val engine = Engine(rate)
        engine.setProject(project, SamplePool())
        engine.songMode = false
        engine.patternId = project.patterns.first().id
        val buf = FloatArray(512 * 2)

        engine.play(0)
        var peak = 0f
        val patternTicks = project.patternLength(project.patterns.first())
        var wrapped = false
        var last = 0.0
        // render three pattern lengths: the position must wrap around and stay in range
        val frames = (3 * patternTicks * 60.0 * rate / (project.bpm * PPQ)).toInt()
        var done = 0
        while (done < frames) {
            engine.render(buf, 512)
            for (v in buf) peak = max(peak, abs(v))
            val pos = engine.positionTicks
            assertTrue(pos >= 0 && pos < patternTicks)
            if (pos < last) wrapped = true
            last = pos
            done += 512
        }
        assertTrue(engine.playing)
        assertTrue("loop never wrapped", wrapped)
        assertTrue(peak > 0.1f)

        engine.stop()
        // after stop + release tails the output must decay to silence
        repeat(rate * 6 / 512) { engine.render(buf, 512) }
        assertFalse(engine.playing)
        var tail = 0f
        engine.render(buf, 512)
        for (v in buf) tail = max(tail, abs(v))
        assertTrue("still sounding after stop: $tail", tail < 0.001f)
    }

    @Test
    fun liveNotesAreHeard() {
        val project = ProjectFactory.empty(names)
        val engine = Engine(rate)
        engine.setProject(project, SamplePool())
        val buf = FloatArray(512 * 2)
        engine.render(buf, 512)
        for (ch in project.channels) {
            engine.noteOn(ch.id, ch.rootNote, 0.9f)
            var peak = 0f
            repeat(20) {
                engine.render(buf, 512)
                for (v in buf) peak = max(peak, abs(v))
            }
            engine.noteOff(ch.id, ch.rootNote)
            repeat(200) { engine.render(buf, 512) }
            assertTrue("channel ${ch.name} is silent", peak > 0.02f)
        }
    }

    @Test
    fun samplerPlaysAndAudioClipsPlay() {
        // one second of a 440 Hz sine as the "recording"
        val data = ShortArray(rate) { (sin(2 * Math.PI * 440 * it / rate) * 20000).toInt().toShort() }
        val pool = SamplePool()
        pool.put("tone.wav", SampleData(rate, data))

        val file = outFile("tone.wav")
        Wav.write(file, pool["tone.wav"]!!)
        val back = Wav.read(file)
        assertEquals(rate, back.frames)
        assertEquals(data[1234], back.left[1234])

        val sampler = Devices.SAMPLER.create() as Instrument
        sampler.prepare(rate)
        sampler.setParams(Devices.SAMPLER.toArray(emptyMap()))
        sampler.setSample(pool["tone.wav"])
        val l = FloatArray(4096)
        val r = FloatArray(4096)
        sampler.noteOn(72, 1f)
        sampler.render(l, r, 0, 4096)
        assertTrue(l.maxOf { abs(it) } > 0.3f)
        assertTrue(sampler.active)
    }

    /** Prints the level of every channel of the demo song (solo), to help balancing the mix. */
    @Test
    fun mixReport() {
        val dir = System.getenv("MPS_RENDER_DIR") ?: return
        val which = System.getenv("MPS_REPORT") ?: return
        val project = ProjectFactory.template(which)!!.build { id -> "r$id" }
        for (ch in project.channels) {
            val solo = project.copy(channels = project.channels.map { it.copy(solo = it.id == ch.id) }, masterEffects = emptyList())
            val file = File(dir, "solo_${which}_${project.channels.indexOf(ch)}.wav")
            val pattern = if (project.clips.isEmpty()) project.patterns.first().id else 0
            val res = OfflineRenderer.render(solo, SamplePool(), file, rate, patternId = pattern, loops = 2) ?: continue
            val wav = Wav.read(file)
            println("info solo %-2d %-10s peak %.3f rms %.4f limiter %.1f".format(project.channels.indexOf(ch), ch.instrument.preset ?: ch.instrument.param("type").toInt().toString(), res.peak, rms(wav.left), res.limiterDb))
        }
    }

    /** The warm-up only helps if it really runs every kind of device the app has. */
    @Test
    fun warmUpPlaysEveryKindOfDevice() {
        val pool = SamplePool()
        val project = EngineWarmup.buildProject(pool, rate)
        assertEquals(Devices.instruments.map { it.id }.toSet(), project.channels.map { it.instrument.type }.toSet())
        assertEquals(
            Devices.effects.map { it.id }.toSet(),
            (project.channels.flatMap { it.effects } + project.masterEffects).map { it.type }.toSet(),
        )
        val drums = project.channels.filter { it.instrument.type == Devices.DRUM.id }.map { it.instrument.param("type").toInt() }
        assertEquals((0 until DrumSynth.TYPE_COUNT).toList(), drums)
        assertEquals(Presets.synth.size, project.channels.count { it.instrument.type == Devices.SYNTH.id })

        // every channel is heard during one bar
        val engine = Engine(rate)
        engine.setProject(project, pool)
        engine.songMode = true
        engine.stopAtEnd = false
        engine.play(0)
        val buf = FloatArray(192 * 2)
        val heard = HashMap<Int, Float>()
        repeat((rate * 60 / 140 * 4) / 192) {
            engine.render(buf, 192)
            for (ch in project.channels) heard[ch.id] = max(heard[ch.id] ?: 0f, engine.takeChannelPeak(ch.id))
        }
        for (ch in project.channels) {
            assertTrue("channel ${project.channels.indexOf(ch)} (${ch.instrument.type}) is silent", (heard[ch.id] ?: 0f) > 0.003f)
        }

        val result = EngineWarmup.run(rate, 192)
        assertTrue(result.frames >= (2.5 * rate).toLong())
        assertTrue("peak ${result.peak}", result.peak > 0.05f && result.peak < 1.2f)
        println("info warm-up: %.1f s of sound (%d channels) in %d ms".format(result.frames.toDouble() / rate, project.channels.size, result.millis))
    }

    // ------------------------------------------------------------------ automation

    /** Plays [bars] bars and returns the loudness (RMS) of every eighth of a bar. */
    private fun loudness(engine: Engine, project: Project, bars: Int): DoubleArray {
        val framesPerBar = (rate * 60.0 / project.bpm * project.beatsPerBar).toInt()
        val slice = framesPerBar / 8
        val out = DoubleArray(bars * 8)
        val buf = FloatArray(256 * 2)
        var frame = 0
        while (frame < framesPerBar * bars) {
            val n = min(256, framesPerBar * bars - frame)
            engine.render(buf, n)
            for (i in 0 until n) {
                val index = min(out.size - 1, (frame + i) / slice)
                out[index] += (buf[i * 2] * buf[i * 2] + buf[i * 2 + 1] * buf[i * 2 + 1]).toDouble()
            }
            frame += n
        }
        return DoubleArray(out.size) { sqrt(out[it] / (2 * slice)) }
    }

    /** A synth holding one long note in two patterns ("a" and "b"); returns project, pattern ids, channel id. */
    private fun heldNote(): Array<Any> {
        var p = Project(bpm = 120f, tracks = 2)
        val a = p.addPattern("a").let { p = it.first; it.second }
        val b = p.addPattern("b").let { p = it.first; it.second }
        val ch = p.addChannel(Devices.SYNTH.id, "s", params = Presets.synth("init").params).let { p = it.first; it.second }
        val note = listOf(Note(0, p.ticksPerBar, 48, 0.9f))
        p = p.setNotes(a, ch, note).setNotes(b, ch, note)
        return arrayOf(p, a, b, ch)
    }

    @Test
    fun automationMovesTheFader() {
        val (p0, a, _, ch) = heldNote()
        var p = p0 as Project
        val bar = p.ticksPerBar
        val plain = Engine(rate).let { e ->
            e.setProject(p, SamplePool()); e.patternId = a as Int; e.play(0)
            loudness(e, p, 1)
        }
        p = p.setAutomation(a as Int, ch as Int, STRIP_DEVICE, PARAM_VOLUME, listOf(AutoPoint(0, 0f), AutoPoint(bar, 0.8f)))
        val engine = Engine(rate)
        engine.setProject(p, SamplePool())
        engine.patternId = a
        engine.play(0)
        val faded = loudness(engine, p, 1)
        println("info fader automation: " + faded.joinToString(" ") { "%.3f".format(it) } + " (without: %.3f)".format(plain[4]))
        assertTrue(plain[0] > 0.02)
        assertTrue("starts silent", faded[0] < plain[0] * 0.05)
        for (i in 1 until 8) assertTrue("rises at $i", faded[i] > faded[i - 1])
        assertTrue("ends near the fader's own level", faded[7] > plain[7] * 0.6 && faded[7] <= plain[7] * 1.02)

        // balance: hard left at the start, hard right at the end
        p = (p0).setAutomation(a, ch, STRIP_DEVICE, PARAM_PAN, listOf(AutoPoint(0, 0f), AutoPoint(bar, 1f)))
        val e2 = Engine(rate)
        e2.setProject(p, SamplePool())
        e2.patternId = a
        e2.play(0)
        val buf = FloatArray(256 * 2)
        var firstL = 0.0; var firstR = 0.0; var lastL = 0.0; var lastR = 0.0
        val blocks = rate * 2 / 256
        for (blk in 0 until blocks) {
            e2.render(buf, 256)
            for (i in 0 until 256) {
                if (blk < blocks / 8) { firstL += abs(buf[i * 2]); firstR += abs(buf[i * 2 + 1]) }
                if (blk >= blocks * 7 / 8) { lastL += abs(buf[i * 2]); lastR += abs(buf[i * 2 + 1]) }
            }
        }
        assertTrue("left first ($firstL / $firstR)", firstL > firstR * 4)
        assertTrue("right last ($lastL / $lastR)", lastR > lastL * 4)
    }

    @Test
    fun automationTurnsKnobsAndGivesThemBack() {
        val (p0, a, b, ch) = heldNote()
        var p = p0 as Project
        val bar = p.ticksPerBar
        val inst = p.channel(ch as Int)!!.instrument.uid
        val reference = Engine(rate).let { e ->
            e.setProject(p, SamplePool()); e.patternId = b as Int; e.play(0)
            loudness(e, p, 1)
        }
        // the filter opens from fully closed to fully open over the bar
        p = p.setAutomation(a as Int, ch, inst, "cutoff", listOf(AutoPoint(0, 0f), AutoPoint(bar, 1f)))
        val engine = Engine(rate)
        engine.setProject(p, SamplePool())
        engine.patternId = a
        engine.play(0)
        val sweep = loudness(engine, p, 1)
        println("info cutoff automation: " + sweep.joinToString(" ") { "%.3f".format(it) })
        assertTrue("closed filter is quiet", sweep[0] < reference[0] * 0.3)
        assertTrue("open filter is loud", sweep[7] > sweep[0] * 4)

        // the other pattern has no curve: the knob is back where the user left it
        engine.stop(0)
        engine.render(FloatArray(512), 256)
        engine.panic()
        engine.patternId = b as Int
        engine.play(0)
        val after = loudness(engine, p, 1)
        for (i in 0 until 8) assertEquals("slice $i", reference[i], after[i], reference[i] * 0.1 + 0.002)

        // deleting the curve while it plays also returns the knob
        engine.stop(0)
        engine.render(FloatArray(512), 256)
        engine.panic()
        engine.patternId = a
        engine.play(0)
        loudness(engine, p, 1)
        engine.setProject(p.setAutomation(a, ch, inst, "cutoff", emptyList()), SamplePool())
        engine.stop(0)
        engine.render(FloatArray(512), 256)
        engine.panic()
        engine.play(0)
        val cleared = loudness(engine, p, 1)
        for (i in 0 until 8) assertEquals("slice $i after delete", reference[i], cleared[i], reference[i] * 0.1 + 0.002)
    }

    @Test
    fun automationFollowsTheClipInTheSong() {
        val (p0, a, _, ch) = heldNote()
        var p = p0 as Project
        val bar = p.ticksPerBar
        p = p.setAutomation(a as Int, ch as Int, STRIP_DEVICE, PARAM_VOLUME, listOf(AutoPoint(0, 0f), AutoPoint(bar, 0.8f)))
        p = p.addPatternClip(a, 0, bar, bar).first   // the pattern starts in the second bar
        val engine = Engine(rate)
        engine.setProject(p, SamplePool())
        engine.songMode = true
        engine.stopAtEnd = false
        engine.play(0)
        val l = loudness(engine, p, 2)
        for (i in 0 until 8) assertTrue("first bar is empty", l[i] < 0.0005)
        assertTrue("the curve starts with the clip", l[8] < l[15] * 0.1)
        for (i in 9 until 16) assertTrue("rises at $i", l[i] > l[i - 1])
    }

    @Test
    fun automationSurvivesEditsAndSaving() {
        val (p0, a, _, ch) = heldNote()
        var p = p0 as Project
        a as Int; ch as Int
        val fx = p.addEffect(ch, "filter").let { p = it.first; it.second }
        val inst = p.channel(ch)!!.instrument.uid
        // points arrive in any order, with duplicates and out of range values
        val messy = listOf(AutoPoint(960, 2f), AutoPoint(0, 0.25f), AutoPoint(960, 0.5f), AutoPoint(-5, 1f), AutoPoint(480, -1f))
        assertEquals(listOf(AutoPoint(0, 0.25f), AutoPoint(480, 0f), AutoPoint(960, 0.5f)), cleanPoints(messy))
        p = p.setAutomation(a, ch, fx, "cutoff", messy)
            .setAutomation(a, ch, inst, "cutoff", listOf(AutoPoint(0, 1f)))
            .setAutomation(a, ch, STRIP_DEVICE, PARAM_VOLUME, listOf(AutoPoint(0, 0.4f)))
        assertEquals(3, p.pattern(a)!!.automation.size)
        assertEquals(3, p.pattern(a)!!.lane(ch, fx, "cutoff")!!.points.size)
        // setting a curve again replaces it, an empty list removes it
        p = p.setAutomation(a, ch, STRIP_DEVICE, PARAM_VOLUME, listOf(AutoPoint(0, 0.6f)))
        assertEquals(3, p.pattern(a)!!.automation.size)
        assertEquals(0.6f, p.pattern(a)!!.lane(ch, STRIP_DEVICE, PARAM_VOLUME)!!.points[0].value)

        // saved and loaded again
        val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }
        val text = json.encodeToString(Project.serializer(), p)
        assertEquals(p, json.decodeFromString(Project.serializer(), text).sanitized())
        // a project saved before automation existed still loads
        val old = json.encodeToString(Project.serializer(), p0)
        assertFalse(old.contains("automation"))
        assertEquals(p0, json.decodeFromString(Project.serializer(), old))

        // a copy of the channel gets its own copies of the curves
        val (dup, copyId) = p.duplicateChannel(ch, "copy")
        val copy = dup.channel(copyId)!!
        val lanes = dup.pattern(a)!!.automation.filter { it.channelId == copyId }
        assertEquals(setOf(STRIP_DEVICE, copy.instrument.uid, copy.effects[0].uid), lanes.map { it.device }.toSet())
        assertEquals(dup, dup.sanitized())

        // curves disappear together with their knob
        assertEquals(2, p.removeEffect(ch, fx).pattern(a)!!.automation.size)
        val swapped = p.replaceInstrument(ch, Devices.DRUM.id)
        assertEquals(setOf(STRIP_DEVICE, fx), swapped.pattern(a)!!.automation.map { it.device }.toSet())
        assertEquals(swapped, swapped.sanitized())
        // a curve for a knob that does not exist is dropped when loading
        val broken = p.setAutomation(a, ch, inst, "no-such-knob", listOf(AutoPoint(0, 1f)))
        assertEquals(3, broken.sanitized().pattern(a)!!.automation.size)

        // doubling a pattern plays the curve twice
        val ramp = listOf(AutoPoint(0, 0.2f), AutoPoint(960, 1f), AutoPoint(1440, 0.5f))
        val twice = repeatPoints(ramp, 1920)
        for (t in intArrayOf(0, 300, 960, 1200, 1700, 1919)) {
            assertEquals("tick $t", autoValueAt(ramp, t.toFloat())!!, autoValueAt(twice, t.toFloat())!!, 1e-4f)
            assertEquals("tick $t, second pass", autoValueAt(ramp, t.toFloat())!!, autoValueAt(twice, t + 1920f)!!, 1e-4f)
        }
        assertEquals(listOf(AutoPoint(0, 0.4f)), repeatPoints(listOf(AutoPoint(0, 0.4f)), 1920))
    }

    /**
     * The promise the low latency rests on: once a project is playing, the audio thread does not
     * create objects (a garbage collection in the middle of a block is an audible click).
     *
     * The JVM itself may allocate a few hundred bytes once or twice when it swaps compiled code
     * (deoptimisation), so a tiny budget is allowed. Anything created per block or per note would
     * show up in hundreds of blocks and exceed it many times over.
     */
    @Test
    fun audioThreadDoesNotAllocate() {
        val bean = java.lang.management.ManagementFactory.getThreadMXBean() as? com.sun.management.ThreadMXBean ?: return
        if (!bean.isThreadAllocatedMemorySupported) return
        bean.isThreadAllocatedMemoryEnabled = true

        val pool = SamplePool()
        var project = EngineWarmup.buildProject(pool, rate)
        // plus automation on a fader, a synth knob and an effect knob
        val pattern = project.patterns[0].id
        val synth = project.channels.first { it.instrument.type == Devices.SYNTH.id }
        val withFx = project.channels.first { it.effects.isNotEmpty() }
        val bar = project.ticksPerBar
        project = project
            .setAutomation(pattern, synth.id, STRIP_DEVICE, PARAM_VOLUME, listOf(AutoPoint(0, 0.2f), AutoPoint(bar, 0.8f)))
            .setAutomation(pattern, synth.id, synth.instrument.uid, "cutoff", listOf(AutoPoint(0, 0.2f), AutoPoint(bar / 2, 0.9f), AutoPoint(bar, 0.3f)))
            .setAutomation(pattern, withFx.id, withFx.effects[0].uid, withFx.effects[0].let { Devices[it.type]!!.params.first { s -> !s.isChoice }.id }, listOf(AutoPoint(0, 0.1f), AutoPoint(bar, 0.9f)))

        val engine = Engine(rate)
        // everything the control thread does (and may allocate for) happens on another thread
        fun control(action: () -> Unit) = Thread(action).apply { start(); join() }
        control {
            engine.setProject(project, pool)
            engine.songMode = true
            engine.stopAtEnd = false
            engine.metronome = true
            engine.play(0)
        }
        val buf = FloatArray(192 * 2)
        val me = Thread.currentThread().id
        val perBlock = LongArray(1500)

        /** Renders [blocks] blocks and returns the bytes this thread allocated meanwhile. */
        fun measure(engine: Engine, blocks: Int, each: (Int) -> Unit = {}): Long {
            var total = 0L
            for (block in 0 until blocks) {
                val t0 = bean.getThreadAllocatedBytes(me)
                engine.render(buf, 192)
                val bytes = bean.getThreadAllocatedBytes(me) - t0
                each(block) // what the UI does between blocks is not part of the measurement
                if (block < perBlock.size) perBlock[block] = bytes
                total += bytes
            }
            return total
        }
        fun where() = perBlock.withIndex().filter { it.value != 0L }.take(12).joinToString(" ") { "${it.index}:${it.value}" }
        fun clean(bytes: Long) = bytes <= 16384 && perBlock.count { it != 0L } <= 8

        // The very first blocks load classes (once per app start; the warm-up takes care of that on the phone).
        measure(engine, 60) { block ->
            if (block == 10) engine.noteOn(synth.id, 72, 0.8f)
            if (block == 30) engine.noteOff(synth.id, 72)
        }

        // ---- every device playing, automation running, notes played live
        java.util.Arrays.fill(perBlock, 0L)
        val playing = measure(engine, 1500) { block ->
            if (block % 100 == 10) engine.noteOn(synth.id, 60 + block / 100, 0.8f)
            if (block % 100 == 60) engine.noteOff(synth.id, 60 + block / 100)
        }
        assertTrue("allocated $playing bytes while playing, blocks ${where()}", clean(playing))

        // ---- a changed project arrives while the song plays, then the transport is used
        control { engine.setProject(project.copy(bpm = 150f, swing = 0.3f), pool) }
        java.util.Arrays.fill(perBlock, 0L)
        val editing = measure(engine, 500) { block ->
            when (block) {
                300 -> engine.pause()
                350 -> engine.resume()
                360 -> engine.seek(bar / 2)
                400 -> engine.stop(0)
                410 -> engine.panic()
                420 -> engine.play(0)
            }
        }
        assertTrue("allocated $editing bytes around edits and transport, blocks ${where()}", clean(editing))

        // ---- the songs that come with the app, from start to end
        var songs = 0L
        for (template in ProjectFactory.templates) {
            val song = template.build(names)
            val e = Engine(rate)
            control {
                e.setProject(song, SamplePool())
                e.songMode = song.clips.isNotEmpty()
                if (!e.songMode) e.patternId = song.patterns.first().id
                e.stopAtEnd = false
                e.play(0)
            }
            val seconds = if (song.clips.isEmpty()) 8.0 else song.songEnd * song.secondsPerTick + 1.0
            measure(e, 20)
            java.util.Arrays.fill(perBlock, 0L)
            val bytes = measure(e, (seconds * rate / 192).toInt())
            assertTrue("${template.id}: allocated $bytes bytes, blocks ${where()}", clean(bytes))
            songs += bytes
        }
        println("info allocated on the audio thread: $playing bytes while playing, $editing around edits and transport, $songs in the bundled songs")
    }

    @Test
    fun swingKeepsNoteOrder() {
        var last = -1
        for (tick in 0 until PPQ * 8) {
            val s = swingTick(tick, 0.7f)
            assertTrue(s >= last)
            last = s
        }
        assertEquals(PPQ, swingTick(PPQ, 0.5f))
        assertEquals(240, swingTick(240, 1f))
        assertTrue(swingTick(120, 0.5f) > 120)
    }
}
