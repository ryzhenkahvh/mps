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
import jxpix.proger.mps.model.Presets
import jxpix.proger.mps.model.ProjectFactory
import jxpix.proger.mps.model.swingTick
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.abs
import kotlin.math.max
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
