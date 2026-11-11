package jxpix.proger.mps

import jxpix.proger.mps.engine.DeviceType
import jxpix.proger.mps.engine.Devices
import jxpix.proger.mps.engine.Effect
import jxpix.proger.mps.engine.Engine
import jxpix.proger.mps.engine.Instrument
import jxpix.proger.mps.engine.PPQ
import jxpix.proger.mps.engine.ProcessContext
import jxpix.proger.mps.engine.SamplePool
import jxpix.proger.mps.engine.effects.Flanger
import jxpix.proger.mps.engine.effects.StereoWidth
import jxpix.proger.mps.engine.effects.Tape
import jxpix.proger.mps.engine.effects.Tremolo
import jxpix.proger.mps.model.Note
import jxpix.proger.mps.model.Presets
import jxpix.proger.mps.model.Project
import jxpix.proger.mps.model.addChannel
import jxpix.proger.mps.model.addEffect
import jxpix.proger.mps.model.addPattern
import jxpix.proger.mps.model.addPatternClip
import jxpix.proger.mps.model.setNotes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.management.ManagementFactory
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/** The instruments and effects added after the first version: FM synth, string, flanger, tremolo, stereo, tape. */
class PluginTest {

    private val rate = 48000

    private fun instrument(type: DeviceType, values: Map<String, Float> = emptyMap()): Instrument {
        val inst = type.create() as Instrument
        inst.prepare(rate)
        inst.setParams(type.toArray(values))
        return inst
    }

    private fun effect(type: DeviceType, values: Map<String, Float> = emptyMap()): Effect {
        val fx = type.create() as Effect
        fx.prepare(rate)
        fx.setParams(type.toArray(values))
        return fx
    }

    /** Plays one note for [hold] seconds, then lets it ring for [tail] seconds. Returns left and right. */
    private fun play(inst: Instrument, pitch: Int, hold: Double, tail: Double, velocity: Float = 0.9f): Pair<FloatArray, FloatArray> {
        val n = ((hold + tail) * rate).toInt()
        val l = FloatArray(n)
        val r = FloatArray(n)
        val off = (hold * rate).toInt()
        inst.noteOn(pitch, velocity)
        var pos = 0
        var released = false
        while (pos < n) {
            if (!released && pos >= off) {
                inst.noteOff(pitch)
                released = true
            }
            val count = minOf(256, n - pos, if (released) n else max(1, off - pos))
            inst.render(l, r, pos, count)
            pos += count
        }
        return l to r
    }

    private fun rms(a: FloatArray, from: Int = 0, to: Int = a.size): Double {
        var s = 0.0
        for (i in from until to) s += a[i].toDouble() * a[i]
        return sqrt(s / max(1, to - from))
    }

    /** Pitch of a steady tone from its autocorrelation, in Hz. */
    private fun pitchOf(a: FloatArray, from: Int, expected: Double): Double {
        val window = 8192
        val guess = rate / expected
        var best = 0.0
        var bestLag = 0
        val lo = (guess * 0.9).toInt()
        val hi = (guess * 1.1).toInt() + 1
        val c = DoubleArray(hi + 2)
        for (lag in lo - 1..hi + 1) {
            var s = 0.0
            for (i in from until from + window) s += a[i].toDouble() * a[i + lag]
            c[lag] = s
            if (lag in lo..hi && s > best) {
                best = s
                bestLag = lag
            }
        }
        // parabola through the peak and its neighbours
        val y0 = c[bestLag - 1]
        val y1 = c[bestLag]
        val y2 = c[bestLag + 1]
        val shift = 0.5 * (y0 - y2) / (y0 - 2 * y1 + y2)
        return rate / (bestLag + shift)
    }

    private fun cents(measured: Double, expected: Double) = 1200.0 * ln(measured / expected) / ln(2.0)

    private fun freq(note: Int) = 440.0 * Math.pow(2.0, (note - 69) / 12.0)

    /**
     * At full brightness a high note would make overtones above half the sample rate; they come back
     * as tones that belong to no harmonic of the note. The synth turns the modulation down for such notes.
     */
    @Test
    fun fmSynthKeepsHighNotesClean() {
        val inst = instrument(
            Devices.FM,
            mapOf("ratio" to 3f, "amount" to 1f, "brightHold" to 1f, "detune" to 0f, "sustain" to 1f, "attack" to 0.001f),
        )
        val note = 96 // C7, 2093 Hz
        val (l, _) = play(inst, note, 1.0, 0.0)
        val f0 = freq(note)
        val from = rate / 4
        val n = rate / 2
        var total = 0.0
        for (i in 0 until n) total += l[from + i].toDouble() * l[from + i]
        // the part of the energy that sits on whole multiples of the note
        var harmonic = 0.0
        var k = 1
        while (k * f0 < rate / 2) {
            val w = 2 * Math.PI * k * f0 / rate
            var re = 0.0
            var im = 0.0
            for (i in 0 until n) {
                re += l[from + i] * cos(w * i)
                im += l[from + i] * sin(w * i)
            }
            harmonic += 2.0 * (re * re + im * im) / n
            k++
        }
        assertTrue("the note sounds", total / n > 1e-4)
        val stray = 1.0 - harmonic / total
        // 74% without the limit, 0.2% with it
        assertTrue("energy outside the harmonics: $stray", stray < 0.02)

        // lower down nothing is taken away: with the same settings most of the sound is high overtones
        val low = instrument(
            Devices.FM,
            mapOf("ratio" to 3f, "amount" to 1f, "brightHold" to 1f, "detune" to 0f, "sustain" to 1f, "attack" to 0.001f),
        )
        val (b, _) = play(low, 48, 1.0, 0.0)
        val g0 = freq(48)
        var all = 0.0
        for (i in 0 until n) all += b[from + i].toDouble() * b[from + i]
        var firstTen = 0.0
        for (h in 1..10) {
            val w = 2 * Math.PI * h * g0 / rate
            var re = 0.0
            var im = 0.0
            for (i in 0 until n) {
                re += b[from + i] * cos(w * i)
                im += b[from + i] * sin(w * i)
            }
            firstTen += 2.0 * (re * re + im * im) / n
        }
        // 20% as designed; it would be over 99% if the limit for high notes touched this one
        assertTrue("share of the first ten harmonics: ${firstTen / all}", firstTen / all < 0.5)
    }

    @Test
    fun fmSynthIsInTuneAndEveryPresetPlays() {
        // with the modulation off it is a pure sine at the pitch of the key
        val plain = instrument(Devices.FM, mapOf("amount" to 0f, "detune" to 0f, "sustain" to 1f))
        val (l, _) = play(plain, 69, 1.0, 0.1)
        assertEquals(0.0, cents(pitchOf(l, rate / 4, 440.0), 440.0), 1.0)

        // modulation adds overtones without moving the note; an octave switch moves it by octaves
        val bright = instrument(Devices.FM, mapOf("amount" to 0.8f, "detune" to 0f, "sustain" to 1f, "brightHold" to 1f, "octave" to -1f))
        val (b, _) = play(bright, 69, 1.0, 0.1)
        assertEquals(0.0, cents(pitchOf(b, rate / 4, 220.0), 220.0), 2.0)
        var crossingsPlain = 0
        var crossingsBright = 0
        for (i in rate / 4 until rate / 2) {
            if (l[i - 1] < 0f && l[i] >= 0f) crossingsPlain++
            if (b[i - 1] < 0f && b[i] >= 0f) crossingsBright++
        }
        assertTrue("overtones: $crossingsBright crossings against $crossingsPlain", crossingsBright > crossingsPlain)

        for (preset in Presets.forType(Devices.FM.id)) {
            val inst = instrument(Devices.FM, preset.params)
            val (pl, pr) = play(inst, 60, 0.5, 9.0)
            val peak = max(pl.maxOf { abs(it) }, pr.maxOf { abs(it) })
            println("     preset fm ${preset.id.padEnd(12)} peak ${"%.3f".format(peak)} rms ${"%.3f".format(rms(pl, 0, rate / 2))}")
            assertTrue("${preset.id}: audible and not clipping ($peak)", peak > 0.08f && peak < 0.9f)
            assertFalse("${preset.id}: no NaN", pl.any { it.isNaN() } || pr.any { it.isNaN() })
            assertFalse("${preset.id}: the voice is freed when the note has rung out", inst.active)
            // a chord of eight notes still fits under full scale
            val chord = instrument(Devices.FM, preset.params)
            val cl = FloatArray(rate / 2)
            val cr = FloatArray(rate / 2)
            for (k in 0 until 8) chord.noteOn(48 + k * 3, 1f)
            chord.render(cl, cr, 0, cl.size)
            assertTrue("${preset.id}: chord peak ${cl.maxOf { abs(it) }}", cl.maxOf { abs(it) } < 2.6f)
        }
    }

    @Test
    fun stringIsInTuneAndRingsAsLongAsAsked() {
        // the loop filter must not pull the note flat, whatever the brightness
        for (bright in listOf(0.15f, 0.6f, 1f)) {
            for (note in listOf(40, 52, 64, 76, 88)) {
                val inst = instrument(Devices.STRING, mapOf("bright" to bright, "stereo" to 0f, "sustain" to 8f))
                val (l, _) = play(inst, note, 1.0, 0.0)
                val error = cents(pitchOf(l, rate / 10, freq(note)), freq(note))
                assertTrue("note $note at brightness $bright is ${"%.1f".format(error)} cents off", abs(error) < 6.0)
            }
        }

        // "ring time" is how long the string takes to fall silent; letting go of the key damps it
        val long = instrument(Devices.STRING, mapOf("sustain" to 4f, "bright" to 1f, "release" to 0.1f))
        val (held, _) = play(long, 57, 6.0, 0.0)
        val start = rms(held, rate / 20, rate / 5)
        val at2 = rms(held, 2 * rate, 2 * rate + rate / 10)
        val at4 = rms(held, 4 * rate, 4 * rate + rate / 10)
        println("     info string ring: start ${"%.3f".format(start)} 2 s ${"%.4f".format(at2)} 4 s ${"%.5f".format(at4)}")
        assertTrue("still ringing half way: ${at2 / start}", at2 / start in 0.01..0.2)
        assertTrue("silent (-60 dB) at the ring time: ${at4 / start}", at4 / start < 0.003)
        val damped = instrument(Devices.STRING, mapOf("sustain" to 4f, "bright" to 1f, "release" to 0.1f))
        val (cut, _) = play(damped, 57, 0.5, 0.5)
        assertTrue("released: ${rms(cut, rate * 8 / 10, rate) / start}", rms(cut, rate * 8 / 10, rate) / start < 0.01)
        assertFalse(damped.active)

        // a hard hit is louder and brighter than a soft one
        val soft = play(instrument(Devices.STRING), 57, 0.3, 0.0, velocity = 0.2f).first
        val hard = play(instrument(Devices.STRING), 57, 0.3, 0.0, velocity = 1f).first
        assertTrue(rms(hard) > rms(soft) * 2)

        for (preset in Presets.forType(Devices.STRING.id)) {
            val inst = instrument(Devices.STRING, preset.params)
            val (pl, pr) = play(inst, 60, 0.4, 6.0)
            val peak = max(pl.maxOf { abs(it) }, pr.maxOf { abs(it) })
            println("     preset string ${preset.id.padEnd(10)} peak ${"%.3f".format(peak)} rms ${"%.3f".format(rms(pl, 0, rate / 2))}")
            assertTrue("${preset.id}: audible and not clipping ($peak)", peak > 0.08f && peak < 1.0f)
            assertFalse("${preset.id}: no NaN", pl.any { it.isNaN() } || pr.any { it.isNaN() })
            assertFalse("${preset.id}: the voice is freed", inst.active)
            assertEquals("${preset.id}: no DC", 0.0, pl.average(), 0.01)
        }
    }

    private fun tone(hz: Double, seconds: Double, amp: Float = 0.5f, phase: Double = 0.0) =
        FloatArray((rate * seconds).toInt()) { (sin(2 * Math.PI * hz * it / rate + phase) * amp).toFloat() }

    private fun run(fx: Effect, l: FloatArray, r: FloatArray, ctx: ProcessContext = ProcessContext().also { it.sampleRate = rate }) {
        var pos = 0
        val bl = FloatArray(256)
        val br = FloatArray(256)
        while (pos < l.size) {
            val n = minOf(256, l.size - pos)
            System.arraycopy(l, pos, bl, 0, n)
            System.arraycopy(r, pos, br, 0, n)
            fx.process(bl, br, n, ctx)
            System.arraycopy(bl, 0, l, pos, n)
            System.arraycopy(br, 0, r, pos, n)
            pos += n
            if (ctx.playing) ctx.tickPos += n * ctx.ticksPerFrame
        }
    }

    @Test
    fun newEffectsDoWhatTheirKnobsSay() {
        // ---- flanger: nothing at mix 0, a moving comb filter otherwise
        val dryL = tone(440.0, 1.0)
        val dryR = dryL.copyOf()
        run(effect(Flanger.TYPE, mapOf("mix" to 0f)), dryL, dryR)
        assertEquals(0.5f, dryL.maxOf { abs(it) }, 0.001f)
        assertEquals(tone(440.0, 1.0)[12345], dryL[12345], 1e-6f)
        val wetL = tone(440.0, 2.0)
        val wetR = wetL.copyOf()
        run(effect(Flanger.TYPE, mapOf("rate" to 1f, "depth" to 1f, "feedback" to 0.7f)), wetL, wetR)
        val levels = (0 until 20).map { rms(wetL, it * rate / 10, (it + 1) * rate / 10) }
        assertTrue("the sweep changes the level of a steady tone: $levels", levels.max() / levels.min() > 1.3)
        assertTrue(wetL.maxOf { abs(it) } < 2.5f)

        // ---- tremolo: free running square chop at 5 Hz
        val tl = tone(1000.0, 1.0)
        val tr = tl.copyOf()
        run(effect(Tremolo.TYPE, mapOf("sync" to 0f, "rate" to 5f, "depth" to 1f, "shape" to 1f, "stereo" to 0f)), tl, tr)
        // 5 Hz: 100 ms loud, 100 ms silent, starting loud
        assertTrue("on: ${rms(tl, 0, rate / 20)}", rms(tl, rate / 100, rate * 8 / 100) > 0.3)
        assertTrue("off: ${rms(tl, rate * 12 / 100, rate * 18 / 100)}", rms(tl, rate * 12 / 100, rate * 18 / 100) < 0.02)
        assertTrue(rms(tl, rate * 21 / 100, rate * 28 / 100) > 0.3)
        var step = 0f
        for (i in 1 until tl.size) step = max(step, abs(tl[i] - tl[i - 1]))
        assertTrue("the chop has soft edges: $step", step < 0.1f)

        // locked to the song: at 120 BPM an eighth is 250 ms, loud on every eighth - wherever playback starts
        val ctx = ProcessContext().also {
            it.sampleRate = rate
            it.bpm = 120.0
            it.playing = true
            it.ticksPerFrame = 120.0 * PPQ / (60.0 * rate)
            it.tickPos = PPQ * 4.0 + PPQ / 4.0   // one sixteenth past the start of bar 2
        }
        val sl = tone(1000.0, 1.0)
        val sr = sl.copyOf()
        run(effect(Tremolo.TYPE, mapOf("sync" to 3f, "depth" to 1f, "shape" to 1f)), sl, sr, ctx)
        // a sixteenth (125 ms) into the eighth: the quiet half comes first, then loud from the next eighth on
        assertTrue("quiet half: ${rms(sl, rate * 2 / 100, rate * 10 / 100)}", rms(sl, rate * 2 / 100, rate * 10 / 100) < 0.03)
        assertTrue("next eighth: ${rms(sl, rate * 14 / 100, rate * 23 / 100)}", rms(sl, rate * 14 / 100, rate * 23 / 100) > 0.3)

        // auto-pan: the sides take turns, the sum stays level
        val pl = tone(1000.0, 1.0)
        val pr = pl.copyOf()
        run(effect(Tremolo.TYPE, mapOf("sync" to 0f, "rate" to 2f, "depth" to 1f, "shape" to 0f, "stereo" to 1f)), pl, pr)
        val leftFirst = rms(pl, 0, rate / 10)
        val rightFirst = rms(pr, 0, rate / 10)
        val leftLater = rms(pl, rate / 4, rate / 4 + rate / 10)
        val rightLater = rms(pr, rate / 4, rate / 4 + rate / 10)
        assertTrue("$leftFirst $rightFirst $leftLater $rightLater", leftFirst > rightFirst * 3 && rightLater > leftLater * 3)

        // ---- stereo width
        val a = tone(500.0, 0.5)
        val b = tone(700.0, 0.5)
        val ml = a.copyOf()
        val mr = b.copyOf()
        run(effect(StereoWidth.TYPE, mapOf("width" to 0f, "monoBelow" to 20f)), ml, mr)
        for (i in 0 until ml.size step 97) assertEquals("width 0 is mono", ml[i], mr[i], 1e-6f)
        val ul = a.copyOf()
        val ur = b.copyOf()
        run(effect(StereoWidth.TYPE, mapOf("width" to 1f, "haas" to 0f, "monoBelow" to 20f)), ul, ur)
        for (i in 0 until ul.size step 97) {
            assertEquals("width 100 % changes nothing", a[i], ul[i], 1e-5f)
            assertEquals(b[i], ur[i], 1e-5f)
        }
        // a mono sound becomes wide with the delay on one side
        val hl = tone(300.0, 0.5)
        val hr = hl.copyOf()
        run(effect(StereoWidth.TYPE, mapOf("width" to 1f, "haas" to 12f, "monoBelow" to 20f)), hl, hr)
        var diff = 0.0
        for (i in rate / 10 until hl.size) diff += abs(hl[i] - hr[i])
        assertTrue("the sides differ now", diff / hl.size > 0.05)
        // bass that the sides disagree about is pulled to the middle; the rest is left alone
        val bl = tone(50.0, 1.0)
        val br = FloatArray(bl.size) { -bl[it] }
        run(effect(StereoWidth.TYPE, mapOf("width" to 1f, "haas" to 0f, "monoBelow" to 250f)), bl, br)
        assertTrue("out-of-phase bass is gone: ${rms(bl, rate / 2)}", rms(bl, rate / 2) < 0.08)
        val tl2 = tone(3000.0, 1.0)
        val tr2 = FloatArray(tl2.size) { -tl2[it] }
        run(effect(StereoWidth.TYPE, mapOf("width" to 1f, "haas" to 0f, "monoBelow" to 250f)), tl2, tr2)
        assertTrue("treble keeps its width: ${rms(tl2, rate / 2)}", rms(tl2, rate / 2) > 0.3)

        // ---- tape: the tone knob takes the top off, the drive squeezes, wow moves the pitch
        fun through(values: Map<String, Float>, hz: Double, amp: Float = 0.3f): FloatArray {
            val x = tone(hz, 1.0, amp)
            run(effect(Tape.TYPE, values), x, x.copyOf())
            return x
        }
        val still = mapOf("wow" to 0f, "flutter" to 0f, "drive" to 0f)
        val dull = rms(through(still + ("tone" to 0f), 10000.0), rate / 2)
        val open = rms(through(still + ("tone" to 1f), 10000.0), rate / 2)
        assertTrue("tone: $dull against $open", dull < open * 0.3)
        val lowDull = rms(through(still + ("tone" to 0f), 200.0), rate / 2)
        assertEquals("the bass passes untouched", 0.3 / sqrt(2.0), lowDull, 0.03)
        val quiet = through(still + ("drive" to 1f) + ("tone" to 1f), 200.0, 0.05f).maxOf { abs(it) }
        val loud = through(still + ("drive" to 1f) + ("tone" to 1f), 200.0, 0.9f).maxOf { abs(it) }
        assertTrue("drive lifts the quiet ($quiet) and holds the loud ($loud)", quiet > 0.1f && loud < 0.6f)
        val wobbly = through(mapOf("wow" to 1f, "flutter" to 0f, "drive" to 0f, "tone" to 1f), 1000.0)
        val periods = ArrayList<Int>()
        var last = -1
        for (i in rate / 10 until wobbly.size) if (wobbly[i - 1] < 0f && wobbly[i] >= 0f) {
            if (last >= 0) periods += i - last
            last = i
        }
        assertTrue("wow: periods from ${periods.min()} to ${periods.max()} samples", periods.max() - periods.min() >= 1)
    }

    @Test
    fun effectsFallSilentAndStayInBounds() {
        // loud noise in, then nothing: every effect must stay bounded and ring out to silence
        val noise = java.util.Random(7)
        for (type in Devices.effects) {
            for (preset in Presets.forType(type.id)) {
                val fx = effect(type, preset.params)
                val l = FloatArray(rate * 9) { if (it < rate) (noise.nextFloat() * 2f - 1f) * 0.7f else 0f }
                val r = FloatArray(rate * 9) { if (it < rate) (noise.nextFloat() * 2f - 1f) * 0.7f else 0f }
                val ctx = ProcessContext().also {
                    it.sampleRate = rate
                    it.bpm = 120.0
                    it.playing = true
                    it.ticksPerFrame = 120.0 * PPQ / (60.0 * rate)
                }
                run(fx, l, r, ctx)
                val peak = max(l.maxOf { abs(it) }, r.maxOf { abs(it) })
                assertFalse("${preset.id}: NaN", l.any { it.isNaN() } || r.any { it.isNaN() })
                assertTrue("${preset.id}: peak $peak", peak < 8f)
                val tail = max(rms(l, rate * 8), rms(r, rate * 8))
                // long halls and dub echoes are meant to hang in the air; everything else is silent by now
                val limit = if (type.id == "reverb" || type.id == "delay") 0.03 else 0.002
                assertTrue("${preset.id}: still ${"%.5f".format(tail)} seven seconds after the sound stopped", tail < limit)
                val loud = max(rms(l, 0, rate), rms(r, 0, rate))
                assertTrue("${preset.id}: the tail ($tail) is well below the sound ($loud)", tail < loud * 0.1)
            }
        }
    }

    @Test
    fun newDevicesDoNotAllocateWhilePlaying() {
        var p = Project(name = "plugins", bpm = 128f, tracks = 1)
        val pattern = p.addPattern("p").let { p = it.first; it.second }
        val beat = PPQ
        for ((i, type) in listOf(Devices.FM, Devices.STRING).withIndex()) {
            val preset = Presets.forType(type.id).first()
            val id = p.addChannel(type.id, type.id, params = preset.params).let { p = it.first; it.second }
            p = p.setNotes(pattern, id, List(16) { Note(it * beat / 4, beat / 4, 48 + i * 12 + it % 5 * 3, 0.8f) })
            for (fx in listOf("flanger", "tremolo", "stereo", "tape")) p = p.addEffect(id, fx).first
        }
        p = p.addPatternClip(pattern, 0, 0, p.ticksPerBar * 64).first

        val engine = Engine(rate)
        engine.songMode = true
        engine.setProject(p, SamplePool())
        val out = FloatArray(512 * 2)
        var bytes = -1L
        var peak = 0f
        val thread = Thread {
            val bean = ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean
            val me = Thread.currentThread().id
            engine.play(0)
            // let the JIT and the class loader finish their work first
            repeat(600) { engine.render(out, 256) }
            val before = bean.getThreadAllocatedBytes(me)
            repeat(600) {
                engine.render(out, 256)
                for (i in 0 until 512) if (abs(out[i]) > peak) peak = abs(out[i])
            }
            bytes = bean.getThreadAllocatedBytes(me) - before
        }
        thread.start()
        thread.join()
        println("     info plugins: $bytes bytes allocated in 600 blocks, peak ${"%.3f".format(peak)}")
        assertTrue("sound comes out: $peak", peak > 0.05f)
        assertTrue("$bytes bytes allocated on the audio thread", bytes <= 16384)
    }
}
