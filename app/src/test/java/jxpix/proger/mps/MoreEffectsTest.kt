package jxpix.proger.mps

import jxpix.proger.mps.Signals.RATE
import jxpix.proger.mps.Signals.brightness
import jxpix.proger.mps.Signals.cents
import jxpix.proger.mps.Signals.db
import jxpix.proger.mps.Signals.effect
import jxpix.proger.mps.Signals.level
import jxpix.proger.mps.Signals.noise
import jxpix.proger.mps.Signals.note
import jxpix.proger.mps.Signals.peak
import jxpix.proger.mps.Signals.pitch
import jxpix.proger.mps.Signals.playing
import jxpix.proger.mps.Signals.rich
import jxpix.proger.mps.Signals.rms
import jxpix.proger.mps.Signals.run
import jxpix.proger.mps.Signals.step
import jxpix.proger.mps.Signals.stopped
import jxpix.proger.mps.Signals.tone
import jxpix.proger.mps.engine.Devices
import jxpix.proger.mps.engine.Engine
import jxpix.proger.mps.engine.PPQ
import jxpix.proger.mps.engine.SampleData
import jxpix.proger.mps.engine.SamplePool
import jxpix.proger.mps.engine.effects.AutoWah
import jxpix.proger.mps.engine.effects.BeatRepeat
import jxpix.proger.mps.engine.effects.DeEsser
import jxpix.proger.mps.engine.effects.Gate
import jxpix.proger.mps.engine.effects.HalfTime
import jxpix.proger.mps.engine.effects.Ott
import jxpix.proger.mps.engine.effects.PitchCorrect
import jxpix.proger.mps.engine.effects.PitchShift
import jxpix.proger.mps.engine.effects.Space3D
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
import jxpix.proger.mps.model.setNotes
import jxpix.proger.mps.model.setSongAutomation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.management.ManagementFactory
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sin

/** The effects for voices and for beats: each one has to do what its knobs say. */
class MoreEffectsTest {

    /** Left and right of a sound that is the same on both sides; the sound itself stays as it was. */
    private fun both(a: FloatArray) = a.copyOf() to a.copyOf()

    @Test
    fun gateShutsTheNoiseOutAndLetsTheVoiceIn() {
        // half a second of quiet hum, half a second of "voice", then hum again
        fun signal() = FloatArray(RATE * 3 / 2) { i ->
            val amp = if (i in RATE / 2 until RATE) 0.5f else 0.001f
            (sin(2 * Math.PI * 300 * i / RATE) * amp).toFloat()
        }
        val src = signal()
        val (l, r) = both(signal())
        run(effect(Gate.TYPE, mapOf("threshold" to -40f, "attack" to 1f, "hold" to 60f, "release" to 40f, "range" to 60f)), l, r)
        val before = rms(l, RATE / 10, RATE * 4 / 10) / rms(src, RATE / 10, RATE * 4 / 10)
        val during = rms(l, RATE / 2 + RATE / 50, RATE) / rms(src, RATE / 2 + RATE / 50, RATE)
        val held = rms(l, RATE + RATE / 100, RATE + RATE / 20) / rms(src, RATE + RATE / 100, RATE + RATE / 20)
        val after = rms(l, RATE * 14 / 10, RATE * 15 / 10) / rms(src, RATE * 14 / 10, RATE * 15 / 10)
        println("     info gate: before %.1f dB, during %.2f dB, held %.1f dB, after %.1f dB".format(db(before), db(during), db(held), db(after)))
        assertEquals("the hum is turned down by what the knob says", -60.0, db(before), 1.0)
        assertEquals("the voice passes untouched", 0.0, db(during), 0.1)
        assertTrue("it opens within a few milliseconds", rms(l, RATE / 2 + RATE / 200, RATE / 2 + RATE / 50) > 0.3)
        assertTrue("for the hold time the gate stays open", db(held) > -3.0)
        assertTrue("then it closes again", db(after) < -40.0)
        assertTrue("left and right are treated alike", l.contentEquals(r))
        // no clicks when it closes: the level only ever glides
        assertTrue(step(l, RATE + 10) < 0.001f)
        // a depth of nothing changes nothing
        val (l2, r2) = both(signal())
        run(effect(Gate.TYPE, mapOf("threshold" to -40f, "range" to 0f)), l2, r2)
        for (i in l2.indices step 97) assertEquals(src[i], l2[i], 1e-6f)
    }

    @Test
    fun deEsserTakesTheHissAndLeavesTheVoice() {
        val knobs = mapOf("freq" to 6000f, "threshold" to -30f, "amount" to 12f)
        // a hiss alone: 7 kHz, far above the threshold
        val (sl, sr) = both(tone(7000.0, 1.0, 0.3f))
        run(effect(DeEsser.TYPE, knobs), sl, sr)
        val hiss = db(level(sl, 7000.0, RATE / 2) / 0.3)
        // the body of a voice: nothing to do
        val (vl, vr) = both(tone(300.0, 1.0, 0.5f))
        run(effect(DeEsser.TYPE, knobs), vl, vr)
        val body = db(level(vl, 300.0, RATE / 2) / 0.5)
        // both at once: only the hiss goes down
        val mixed = FloatArray(RATE) { (sin(2 * Math.PI * 300 * it / RATE) * 0.4 + sin(2 * Math.PI * 7000 * it / RATE) * 0.2).toFloat() }
        val (ml, mr) = both(mixed)
        val fx = effect(DeEsser.TYPE, knobs) as DeEsser
        run(fx, ml, mr)
        val mixedBody = db(level(ml, 300.0, RATE / 2) / 0.4)
        val mixedHiss = db(level(ml, 7000.0, RATE / 2) / 0.2)
        println("     info de-esser: hiss %.1f dB, body %.2f dB; together: body %.2f dB, hiss %.1f dB (meter %.1f)".format(hiss, body, mixedBody, mixedHiss, fx.reductionDb))
        assertTrue("the hiss goes down by about the amount ($hiss dB)", hiss < -8.5 && hiss > -12.5)
        assertEquals("the voice itself stays", 0.0, body, 0.1)
        assertEquals(0.0, mixedBody, 0.3)
        assertTrue("$mixedHiss dB", mixedHiss < -8.0 && mixedHiss > -12.5)
        assertTrue(fx.reductionDb > 8f)
        // a quiet hiss below the threshold is left alone
        val (ql, qr) = both(tone(7000.0, 1.0, 0.01f))
        run(effect(DeEsser.TYPE, knobs), ql, qr)
        assertEquals(0.0, db(level(ql, 7000.0, RATE / 2) / 0.01), 0.3)
        // and with the amount at nothing the effect is not there
        val (nl, nr) = both(tone(7000.0, 1.0, 0.3f))
        run(effect(DeEsser.TYPE, knobs + ("amount" to 0f)), nl, nr)
        assertEquals(0.0, db(level(nl, 7000.0, RATE / 2) / 0.3), 0.1)
    }

    @Test
    fun pitchShifterMovesTheNoteAndKeepsTheTime() {
        val a3 = 220.0
        for ((semis, name) in listOf(12f to "an octave up", -12f to "an octave down", 7f to "a fifth up", -5f to "a fourth down", 1f to "a semitone up")) {
            val want = a3 * Math.pow(2.0, semis / 12.0)
            // a voice-like tone and a plain one
            for (plain in listOf(false, true)) {
                val src = if (plain) tone(a3, 2.0, 0.4f) else rich(a3, 2.0)
                val (l, r) = both(src)
                run(effect(PitchShift.TYPE, mapOf("semitones" to semis, "mix" to 1f)), l, r)
                val got = pitch(l, RATE, want)
                val off = cents(got, want)
                val loud = db(rms(l, RATE / 2) / rms(src, RATE / 2))
                // a clean sine in must be a clean sine out: no join may be heard as a click
                val smooth = if (plain) step(l, RATE / 2) / (2 * Math.PI * want / RATE * 0.4) else 0.0
                println("     info pitch shifter %s (%s): %.1f Hz, %+.1f cents, level %+.1f dB%s".format(
                    name, if (plain) "sine" else "rich", got, off, loud, if (plain) ", steepest step %.2f of a clean tone".format(smooth) else ""))
                assertTrue("$name: $got Hz instead of $want", abs(off) < 12.0)
                assertTrue("$name: the level stays ($loud dB)", abs(loud) < 2.5)
                if (plain) assertTrue("$name: a sine stays smooth ($smooth)", smooth < 1.6)
                assertTrue(l.contentEquals(r))
            }
        }
        // the fine knob: 50 cents
        val (cl, cr) = both(rich(a3, 2.0))
        run(effect(PitchShift.TYPE, mapOf("semitones" to 0f, "cents" to 50f, "mix" to 1f)), cl, cr)
        assertEquals(50.0, cents(pitch(cl, RATE, a3 * 1.03), a3), 6.0)
        // nothing to shift: the sound comes out as it went in, a fraction of a millisecond later
        val src = rich(a3, 1.0)
        val (zl, zr) = both(src)
        run(effect(PitchShift.TYPE, mapOf("semitones" to 0f, "cents" to 0f, "mix" to 1f)), zl, zr)
        for (i in 1000 until zl.size step 53) assertEquals(src[i - 8], zl[i], 1e-5f)
        // mix at nothing: the dry sound
        val (dl, dr) = both(src)
        run(effect(PitchShift.TYPE, mapOf("semitones" to 7f, "mix" to 0f)), dl, dr)
        assertTrue(dl.contentEquals(src))
        // a chord goes up as a whole
        val chord = FloatArray(RATE * 2) { i -> (listOf(220.0, 277.18, 329.63).sumOf { sin(2 * Math.PI * it * i / RATE) } * 0.15).toFloat() }
        val (kl, kr) = both(chord)
        run(effect(PitchShift.TYPE, mapOf("semitones" to 3f, "mix" to 1f)), kl, kr)
        val up = Math.pow(2.0, 3 / 12.0)
        val moved = listOf(220.0, 277.18, 329.63).map { level(kl, it * up, RATE) / 0.15 }
        // (the old top note is where the middle one has moved to, so it says nothing)
        val left = listOf(220.0, 277.18).map { level(kl, it, RATE) / 0.15 }
        println("     info pitch shifter chord +3: new notes " + moved.joinToString { "%.2f".format(it) } + ", old notes " + left.joinToString { "%.2f".format(it) })
        assertTrue("the notes of the chord are at their new places: $moved", moved.all { it > 0.45 })
        assertTrue("and gone from the old ones: $left", left.all { it < 0.3 })
        // the time stays: a sound that starts at one second starts at one second
        val late = FloatArray(RATE * 2) { if (it >= RATE) src[it - RATE] else 0f }
        for (semis in listOf(-12f, 12f)) {
            val (ll, lr) = both(late)
            run(effect(PitchShift.TYPE, mapOf("semitones" to semis, "mix" to 1f)), ll, lr)
            assertTrue("silence before", rms(ll, 0, RATE - 10) < 1e-6)
            assertTrue("$semis: the sound is there within 40 ms", rms(ll, RATE, RATE + RATE / 25) > 0.02)
        }
    }

    /** Pitch of [a] in windows of 43 ms, as cents away from [reference]. */
    private fun track(a: FloatArray, from: Int, to: Int, reference: Double): List<Double> {
        val out = ArrayList<Double>()
        var at = from
        while (at + 2048 + 600 < to) {
            out += cents(pitch(a, at, reference, 2048), reference)
            at += 1024
        }
        return out
    }

    @Test
    fun pitchCorrectionPullsTheVoiceOntoTheNote() {
        val a3 = note(57.0)
        val all = mapOf("scale" to 1f, "speed" to 0f, "amount" to 1f)
        // ---- sharp and flat singing end up on the note
        for (wrong in listOf(35.0, -40.0, 12.0)) {
            val sung = a3 * Math.pow(2.0, wrong / 1200.0)
            val (l, r) = both(rich(sung, 2.0))
            val fx = effect(PitchCorrect.TYPE, all) as PitchCorrect
            run(fx, l, r)
            val got = cents(pitch(l, RATE, a3), a3)
            println("     info tune: sung %+.0f cents -> %+.1f cents, level %+.2f dB".format(wrong, got, db(rms(l, RATE) / rms(rich(sung, 2.0), RATE))))
            assertEquals("sung $wrong cents off", 0.0, got, 5.0)
            assertEquals("the note it hears is A3", 57, fx.heardNote)
            assertTrue(l.contentEquals(r))
            assertEquals(0.0, db(rms(l, RATE) / rms(rich(sung, 2.0), RATE)), 1.0)
        }
        // in tune already: nothing changes but for a fraction of a millisecond
        val clean = rich(a3, 1.0)
        val (il, ir) = both(clean)
        run(effect(PitchCorrect.TYPE, all), il, ir)
        assertEquals("in tune stays in tune", 0.0, cents(pitch(il, RATE / 2, a3), a3), 1.5)
        val overtones = (1..6).map { db(level(il, a3 * it, RATE / 2) / level(clean, a3 * it, RATE / 2)) }
        println("     info tune: a note that is in tune keeps its overtones within " + overtones.joinToString(" ") { "%+.2f".format(it) } + " dB")
        assertTrue("and keeps its colour: $overtones", overtones.all { abs(it) < 0.5 })

        // ---- half the amount, half the way
        val sharp = a3 * Math.pow(2.0, 40.0 / 1200.0)
        val (hl, hr) = both(rich(sharp, 2.0))
        run(effect(PitchCorrect.TYPE, all + ("amount" to 0.5f)), hl, hr)
        assertEquals(20.0, cents(pitch(hl, RATE, a3), a3), 5.0)
        val (nl, nr) = both(rich(sharp, 2.0))
        run(effect(PitchCorrect.TYPE, all + ("amount" to 0f)), nl, nr)
        assertEquals(40.0, cents(pitch(nl, RATE, a3), a3), 3.0)

        // ---- the key decides which notes exist. C sharp, sung a little high, in C major: D
        val cs = note(61.0) * Math.pow(2.0, 20.0 / 1200.0)
        val (ml, mr) = both(rich(cs, 2.0))
        run(effect(PitchCorrect.TYPE, mapOf("scale" to 2f, "key" to 0f, "speed" to 0f)), ml, mr)
        assertEquals("C major has no C sharp: up to D", 0.0, cents(pitch(ml, RATE, note(62.0)), note(62.0)), 6.0)
        // a little low: C
        val (ol, or) = both(rich(note(61.0) * Math.pow(2.0, -20.0 / 1200.0), 2.0))
        run(effect(PitchCorrect.TYPE, mapOf("scale" to 2f, "key" to 0f, "speed" to 0f)), ol, or)
        assertEquals("down to C", 0.0, cents(pitch(ol, RATE, note(60.0)), note(60.0)), 6.0)
        // A major has a C sharp
        val (al, ar) = both(rich(cs, 2.0))
        run(effect(PitchCorrect.TYPE, mapOf("scale" to 2f, "key" to 9f, "speed" to 0f)), al, ar)
        assertEquals(0.0, cents(pitch(al, RATE, note(61.0)), note(61.0)), 6.0)
        // "as in the project": the key comes with the song
        val inKey = stopped().also { it.keyMask = Scales.mask(9, Scales.MINOR) }
        val (pl, pr) = both(rich(cs, 2.0))
        run(effect(PitchCorrect.TYPE, mapOf("scale" to 0f, "speed" to 0f)), pl, pr, inKey)
        assertEquals("A minor in the project: D", 0.0, cents(pitch(pl, RATE, note(62.0)), note(62.0)), 6.0)
        val (xl, xr) = both(rich(cs, 2.0))
        run(effect(PitchCorrect.TYPE, mapOf("scale" to 0f, "speed" to 0f)), xl, xr, stopped())
        assertEquals("a project without a key: the nearest semitone", 0.0, cents(pitch(xl, RATE, note(61.0)), note(61.0)), 6.0)

        // ---- a wobbling voice: at once it is ironed flat, slowly it keeps its vibrato
        fun vibrato(): FloatArray {
            var phase = 0.0
            return FloatArray(RATE * 3) { i ->
                val f = a3 * Math.pow(2.0, 50.0 * sin(2 * Math.PI * 5.0 * i / RATE) / 1200.0)
                phase += f / RATE
                var s = 0.0
                for (k in 1..8) s += sin(2 * Math.PI * phase * k) / k
                (s * 0.3).toFloat()
            }
        }
        val raw = track(vibrato(), RATE, RATE * 3, a3)
        val (fl, fr) = both(vibrato())
        run(effect(PitchCorrect.TYPE, all), fl, fr)
        val flat = track(fl, RATE, RATE * 3, a3)
        val (sl, sr) = both(vibrato())
        run(effect(PitchCorrect.TYPE, all + ("speed" to 400f)), sl, sr)
        val slow = track(sl, RATE, RATE * 3, a3)
        println("     info tune vibrato: in %.0f..%.0f cents, at once %.0f..%.0f, slowly %.0f..%.0f".format(raw.min(), raw.max(), flat.min(), flat.max(), slow.min(), slow.max()))
        assertTrue("the test voice wobbles", raw.max() - raw.min() > 70)
        assertTrue("at once: the wobble is ironed out (${flat.min()}..${flat.max()})", flat.max() - flat.min() < 30)
        assertTrue("slowly: most of it stays (${slow.min()}..${slow.max()})", slow.max() - slow.min() > 50)
        assertEquals("but the middle of it is on the note", 0.0, slow.average(), 8.0)

        // ---- a new note: at once it is there at once, slowly it glides
        fun twoNotes() = FloatArray(RATE * 2) { i ->
            // A3 sung 40 cents high for a second, then C4 sung 40 cents low
            val f = if (i < RATE) a3 * Math.pow(2.0, 40.0 / 1200.0) else note(60.0) * Math.pow(2.0, -40.0 / 1200.0)
            var s = 0.0
            for (k in 1..8) s += sin(2 * Math.PI * f * k * i / RATE) / k
            (s * 0.3).toFloat()
        }
        val (gl, gr) = both(twoNotes())
        run(effect(PitchCorrect.TYPE, all + ("speed" to 250f)), gl, gr)
        val early = cents(pitch(gl, RATE + RATE / 20, note(60.0), 2048), note(60.0))
        val settled = cents(pitch(gl, RATE * 2 - 9000, note(60.0)), note(60.0))
        val (jl, jr) = both(twoNotes())
        run(effect(PitchCorrect.TYPE, all), jl, jr)
        val jumped = cents(pitch(jl, RATE + RATE / 20, note(60.0), 2048), note(60.0))
        println("     info tune new note: slow %+.0f cents after 50 ms, %+.0f at the end; at once %+.0f after 50 ms".format(early, settled, jumped))
        assertTrue("slowly: still on its way after 50 ms ($early)", abs(early) > 15.0)
        assertEquals(0.0, settled, 6.0)
        assertEquals("at once: there after 50 ms", 0.0, jumped, 8.0)

        // ---- transposing on top
        val (tl, tr) = both(rich(a3 * 1.01, 2.0))
        run(effect(PitchCorrect.TYPE, all + ("shift" to 12f)), tl, tr)
        assertEquals(0.0, cents(pitch(tl, RATE, a3 * 2), a3 * 2), 6.0)
        val (ul, ur) = both(rich(a3 * 1.01, 2.0))
        run(effect(PitchCorrect.TYPE, all + ("shift" to -5f)), ul, ur)
        assertEquals(0.0, cents(pitch(ul, RATE, note(52.0)), note(52.0)), 6.0)

        // ---- a low and a high voice
        val (bl, br) = both(rich(note(40.0) * 1.015, 2.0))
        run(effect(PitchCorrect.TYPE, all + ("voice" to 0f)), bl, br)
        assertEquals("a bass singer at E2", 0.0, cents(pitch(bl, RATE, note(40.0), 16384), note(40.0)), 6.0)
        val (wl, wr) = both(rich(note(81.0) * 1.015, 2.0, harmonics = 5))
        run(effect(PitchCorrect.TYPE, all + ("voice" to 2f)), wl, wr)
        assertEquals("a soprano at A5", 0.0, cents(pitch(wl, RATE, note(81.0)), note(81.0)), 8.0)

        // ---- what has no pitch (a breath, an "s") passes as it is
        val hiss = noise(1.0, 0.2f)
        val (ql, qr) = both(hiss)
        val quiet = effect(PitchCorrect.TYPE, all) as PitchCorrect
        run(quiet, ql, qr)
        assertEquals(0.0, db(rms(ql, RATE / 4) / rms(hiss, RATE / 4)), 0.5)
        assertEquals("no note is heard in noise", -1, quiet.heardNote)
        assertFalse(ql.any { it.isNaN() })
    }

    /** One note of the test phrase: where it starts (seconds), the note that is meant, and how far off it is sung (cents). */
    private class Sung(val at: Double, val note: Int, val off: Double)

    private val phrase = listOf(Sung(0.10, 60, 38.0), Sung(0.62, 64, -42.0), Sung(1.14, 67, 25.0), Sung(1.66, 65, -30.0), Sung(2.18, 62, 44.0), Sung(2.70, 60, -20.0))
    private val noteSeconds = 0.44

    /**
     * Something like a sung phrase: a buzzing source with an unsteady period and loudness, the three
     * resonances of an "ah", breath noise, slides into the notes, vibrato on the held ones, and a
     * hissed consonant in the gap before every note.
     */
    private fun sing(): FloatArray {
        val n = (3.3 * RATE).toInt()
        val out = FloatArray(n)
        val random = java.util.Random(3)
        val formants = listOf(700f to 0.9f, 1220f to 0.6f, 2600f to 0.35f).map { (hz, gain) ->
            jxpix.proger.mps.engine.dsp.Svf().also { it.set(hz, 0.92f, RATE.toFloat()) } to gain
        }
        var phase = 0.0
        var wobble = 1.0
        var loud = 1.0
        var cur = 60.0 + phrase[0].off / 100
        for (i in 0 until n) {
            val t = i.toDouble() / RATE
            val k = phrase.indexOfLast { t >= it.at }
            var x = 0.0
            if (k >= 0 && t < phrase[k].at + noteSeconds) {
                val into = t - phrase[k].at
                var target = phrase[k].note + phrase[k].off / 100
                // vibrato comes in on a held note
                if (into > 0.15) target += 0.18 * sin(2 * Math.PI * 5.5 * (into - 0.15)) * minOf(1.0, (into - 0.15) / 0.1)
                // the voice slides to the note rather than jumping
                cur += (target - cur) * (1 - Math.exp(-1.0 / (0.012 * RATE)))
                val hz = note(cur) * wobble
                phase += hz / RATE
                if (phase >= 1.0) {
                    phase -= 1.0
                    wobble = 1.0 + 0.004 * random.nextGaussian()
                    loud = 1.0 + 0.03 * random.nextGaussian()
                }
                // a buzz with a soft top, plus breath
                val buzz = (2 * phase - 1) * loud + 0.05 * random.nextGaussian()
                val env = minOf(1.0, into / 0.02) * minOf(1.0, (noteSeconds - into) / 0.03)
                for ((filter, gain) in formants) {
                    filter.process((buzz * env).toFloat())
                    x += filter.bp * gain
                }
                x *= 0.5
            } else {
                for ((filter, _) in formants) filter.process(0f)
                // the consonant: 40 ms of hiss right before the next note
                val next = phrase.firstOrNull { it.at > t }
                if (next != null && next.at - t < 0.05 && next.at - t > 0.01) x = 0.12 * random.nextGaussian()
            }
            out[i] = x.toFloat()
        }
        return out
    }

    /** Median distance (cents) of what is heard from the given pitch, over the held part of the note [k]. */
    private fun heldPitch(a: FloatArray, k: Int, hz: Double): Double {
        val values = ArrayList<Double>()
        var at = ((phrase[k].at + 0.10) * RATE).toInt()
        val end = ((phrase[k].at + noteSeconds - 0.05) * RATE).toInt()
        while (at + 2048 + 400 < end) {
            values += cents(pitch(a, at, hz, 2048), hz)
            at += 1024
        }
        return values.sorted()[values.size / 2]
    }

    /**
     * Looks for clicks in the held notes of the phrase: how much sharper the sharpest corner of the
     * processed sound is than the sharpest corner of the voice itself around the same moment.
     * A join that can be heard shows as a corner the voice never makes.
     */
    private fun sharpest(out: FloatArray, voice: FloatArray): Double {
        fun corner(a: FloatArray, from: Int, to: Int): Float {
            var m = 0f
            for (i in max(2, from) until minOf(a.size, to)) m = max(m, abs(a[i] - 2 * a[i - 1] + a[i - 2]))
            return m
        }
        var worst = 0.0
        val window = RATE / 50
        for (k in phrase.indices) {
            var at = ((phrase[k].at + 0.06) * RATE).toInt()
            val end = ((phrase[k].at + noteSeconds - 0.06) * RATE).toInt()
            while (at + window < end) {
                val reference = corner(voice, at - window, at + 2 * window)
                worst = max(worst, corner(out, at, at + window).toDouble() / reference)
                at += window
            }
        }
        return worst
    }

    @Test
    fun aSungPhraseEndsUpInKeyWithoutClicks() {
        val voice = sing()
        Signals.save("phrase-in.wav", voice)
        val sungOff = phrase.indices.map { heldPitch(voice, it, note(phrase[it].note.toDouble())) }
        println("     info phrase as sung: " + sungOff.joinToString(" ") { "%+.0f".format(it) } + " cents off")
        for (k in phrase.indices) assertEquals("the test voice is off by what it should be", phrase[k].off, sungOff[k], 6.0)

        for ((label, knobs, limit) in listOf(
            Triple("at once", mapOf("scale" to 2f, "key" to 0f, "speed" to 0f), 12.0),
            Triple("tight", mapOf("scale" to 2f, "key" to 0f, "speed" to 25f), 12.0),
            Triple("all notes", mapOf("scale" to 1f, "speed" to 0f), 12.0),
        )) {
            val (l, r) = both(voice)
            run(effect(PitchCorrect.TYPE, knobs), l, r)
            Signals.save("phrase-tuned-" + label.replace(' ', '-') + ".wav", l)
            val off = phrase.indices.map { heldPitch(l, it, note(phrase[it].note.toDouble())) }
            val corner = sharpest(l, voice)
            println("     info phrase tuned ($label): " + off.joinToString(" ") { "%+.0f".format(it) } + " cents off; level %+.2f dB; sharpest corner %.2f of the voice's own".format(
                db(rms(l) / rms(voice)), corner))
            assertTrue("$label: no join can be heard ($corner)", corner < 1.3)
            for (k in phrase.indices) assertTrue("$label: note $k is ${off[k]} cents off", abs(off[k]) < limit)
            assertEquals("$label: as loud as it was", 0.0, db(rms(l) / rms(voice)), 0.7)
            // a join that could be heard would show as a jump no part of the voice itself makes
            assertTrue("$label: no clicks (${step(l)} against ${step(voice)})", step(l) < step(voice) * 1.25f)
            // the consonants are still there, at their places
            for (k in 1 until phrase.size) {
                val a = ((phrase[k].at - 0.045) * RATE).toInt()
                val b = ((phrase[k].at - 0.012) * RATE).toInt()
                assertEquals("$label: the consonant before note $k", 0.0, db(rms(l, a, b) / rms(voice, a, b)), 2.0)
            }
            assertTrue(l.contentEquals(r))
        }

        // the shifter on the same voice: every note four semitones up or down, nothing else changed
        for (semis in listOf(4f, -4f)) {
            val (l, r) = both(voice)
            run(effect(PitchShift.TYPE, mapOf("semitones" to semis, "mix" to 1f)), l, r)
            Signals.save("phrase-shifted-${semis.toInt()}.wav", l)
            val off = phrase.indices.map { k ->
                val hz = note(phrase[k].note + semis.toDouble())
                heldPitch(l, k, hz) - phrase[k].off
            }
            // played faster, every corner of the voice is sharper by the square of the speed - and no more than that
            val speed = Math.pow(2.0, semis / 12.0)
            val corner = sharpest(l, voice) / max(1.0, speed * speed)
            println("     info phrase shifted by %+.0f: ".format(semis) + off.joinToString(" ") { "%+.0f".format(it) } + " cents from where it should be; level %+.2f dB; sharpest corner %.2f of what the speed explains".format(
                db(rms(l) / rms(voice)), corner))
            assertTrue("shifted by $semis: no join can be heard ($corner)", corner < 1.3)
            for (k in phrase.indices) assertTrue("shifted by $semis: note $k is ${off[k]} cents off", abs(off[k]) < 15.0)
            assertEquals(0.0, db(rms(l) / rms(voice)), 1.5)
            assertTrue("no clicks (${step(l)} against ${step(voice)})", step(l) < step(voice) * 1.6f)
        }
    }

    @Test
    fun halfTimeDragsTheSoundInTimeWithTheSong() {
        // 120 BPM: a bar is two seconds
        val bar = RATE * 2
        val knobs = mapOf("length" to 2f, "speed" to 1f, "smooth" to 0f, "mix" to 1f)
        // ---- a tone comes out an octave lower
        val (tl, tr) = both(tone(440.0, 4.0, 0.4f))
        run(effect(HalfTime.TYPE, knobs), tl, tr, playing())
        assertEquals("half speed is an octave down", 0.0, cents(pitch(tl, RATE / 2, 220.0), 220.0), 3.0)
        assertEquals(0.0, db(rms(tl, RATE / 2, bar - 1000) / rms(tone(440.0, 1.0, 0.4f))), 0.3)
        assertTrue(tl.contentEquals(tr))

        // ---- every moment of the first half of the bar is heard twice as late; the second half is not heard at all
        fun clicks(): FloatArray {
            val a = FloatArray(bar * 2)
            // a short blip every quarter of a second, each one a little louder than the one before
            for (k in 1 until 16) for (i in 0 until 96) a[k * RATE / 4 + i] = sin(Math.PI * i / 96).toFloat() * (0.2f + 0.03f * k)
            return a
        }
        val (cl, cr) = both(clicks())
        run(effect(HalfTime.TYPE, knobs), cl, cr, playing())
        fun loudAt(a: FloatArray, seconds: Double) = peak(a, (seconds * RATE).toInt() - 200, (seconds * RATE).toInt() + 600)
        println("     info half-time: blips at 0.5 s %.2f, 1.0 s %.2f, 1.5 s %.2f; at 0.25 s %.3f, 0.75 s %.3f; next bar 2.5 s %.2f".format(
            loudAt(cl, 0.5), loudAt(cl, 1.0), loudAt(cl, 1.5), loudAt(cl, 0.25), loudAt(cl, 0.75), loudAt(cl, 2.5)))
        assertEquals("the blip of 0.25 s is heard at 0.5 s", 0.23f, loudAt(cl, 0.5), 0.02f)
        assertEquals("the one of 0.5 s at 1.0 s", 0.26f, loudAt(cl, 1.0), 0.02f)
        assertEquals("the one of 0.75 s at 1.5 s", 0.29f, loudAt(cl, 1.5), 0.02f)
        assertTrue("nothing at 0.25 s and 0.75 s", loudAt(cl, 0.25) < 0.01f && loudAt(cl, 0.75) < 0.01f)
        assertEquals("the next bar starts afresh: the blip of 2.25 s at 2.5 s", 0.2f + 0.03f * 9, loudAt(cl, 2.5), 0.02f)
        // a blip is twice as long as it was
        var width = 0
        for (i in RATE / 2 - 400 until RATE / 2 + 800) if (abs(cl[i]) > 0.02f) width++
        assertTrue("the blip is stretched to twice its length ($width samples)", width in 160..192)

        // ---- other speeds and lengths
        val (ql, qr) = both(tone(440.0, 4.0, 0.4f))
        run(effect(HalfTime.TYPE, knobs + ("speed" to 2f)), ql, qr, playing())
        assertEquals("four times slower: two octaves down", 0.0, cents(pitch(ql, RATE / 2, 110.0), 110.0), 3.0)
        val (el, er) = both(tone(440.0, 4.0, 0.4f))
        run(effect(HalfTime.TYPE, knobs + ("speed" to 0f)), el, er, playing())
        assertEquals("one and a half times slower: a fifth down", 0.0, cents(pitch(el, RATE / 2, 440.0 / 1.5), 440.0 / 1.5), 3.0)
        val (hl, hr) = both(clicks())
        run(effect(HalfTime.TYPE, knobs + ("length" to 1f)), hl, hr, playing())
        assertEquals("rounds of half a bar: 0.25 s is heard at 0.5 s", 0.23f, loudAt(hl, 0.5), 0.02f)
        assertEquals("and 1.25 s at 1.5 s", 0.2f + 0.03f * 5, loudAt(hl, 1.5), 0.02f)

        // ---- mix: none of it, or half under the original
        val (dl, dr) = both(clicks())
        run(effect(HalfTime.TYPE, knobs + ("mix" to 0f)), dl, dr, playing())
        assertTrue(dl.contentEquals(clicks()))
        val (ml, mr) = both(clicks())
        run(effect(HalfTime.TYPE, knobs + ("mix" to 0.5f)), ml, mr, playing())
        assertEquals("the original blip at 0.75 s, at half level", 0.29f / 2, loudAt(ml, 0.75), 0.02f)

        // ---- the join between two bars does not click
        for (smooth in listOf(0f, 1f)) {
            val (sl, sr) = both(tone(110.0, 6.0, 0.5f))
            run(effect(HalfTime.TYPE, knobs + ("smooth" to smooth)), sl, sr, playing())
            // a 110 Hz tone of this size moves by 0.0072 per sample
            println("     info half-time join (smooth %.0f): largest step %.4f".format(smooth, step(sl, bar - 4000, bar * 2 + 4000)))
            assertTrue("smooth $smooth: ${step(sl, bar - 4000, bar * 2 + 4000)}", step(sl, bar - 4000, bar * 2 + 4000) < 0.03f)
        }

        // ---- started in the middle of a bar it falls into step at the next bar line
        val (jl, jr) = both(clicks())
        run(effect(HalfTime.TYPE, knobs), jl, jr, playing(fromTick = 2.0 * PPQ))
        // one second into this run a new bar begins; the blip a quarter of a second later is heard at half a second after that
        assertEquals(0.2f + 0.03f * 5, loudAt(jl, 1.5), 0.02f)
        // ---- without a running song it keeps time by itself
        val (fl, fr) = both(tone(440.0, 4.0, 0.4f))
        run(effect(HalfTime.TYPE, knobs), fl, fr, stopped())
        assertEquals(0.0, cents(pitch(fl, RATE / 2, 220.0), 220.0), 3.0)
        // ---- a very slow song: the round is shortened rather than cut off
        val (vl, vr) = both(tone(440.0, 8.0, 0.4f))
        run(effect(HalfTime.TYPE, knobs + ("length" to 3f)), vl, vr, playing(bpm = 40.0))
        assertTrue("the sound never stops", (1 until 15).all { rms(vl, it * RATE / 2, it * RATE / 2 + 4000) > 0.2 })
    }

    @Test
    fun beatRepeatStuttersTheEndOfTheBar() {
        // 120 BPM: the last beat of the bar is 1.5 .. 2.0 s; a sixteenth is an eighth of a second
        val src = noise(4.0, 0.3f, seed = 11)
        val knobs = mapOf("every" to 1f, "length" to 0f, "slice" to 3f, "decay" to 0f, "mix" to 1f)
        val (l, r) = both(src)
        run(effect(BeatRepeat.TYPE, knobs), l, r, playing())
        val beat = RATE / 2
        val piece = RATE / 8
        // until the fill the sound is untouched, and so is the piece that is caught
        for (i in 0 until 3 * beat + piece step 37) assertEquals(src[i], l[i], 0f)
        // then the piece comes again, and again, and again
        fun matches(a: FloatArray, at: Int, from: Int, gain: Float = 1f): Float {
            var worst = 0f
            for (i in 200 until piece - 200) worst = max(worst, abs(a[at + i] - src[from + i] * gain))
            return worst
        }
        for (k in 1..3) assertTrue("repeat $k is the piece again", matches(l, 3 * beat + k * piece, 3 * beat) < 1e-4f)
        // the next bar plays on as if nothing had happened, and its fill repeats its own piece
        for (i in 4 * beat until 7 * beat step 41) assertEquals(src[i], l[i], 0f)
        assertTrue(matches(l, 7 * beat + 2 * piece, 7 * beat) < 1e-4f)
        assertTrue(l.contentEquals(r))
        // the joins are soft
        assertTrue("a repeat begins at nothing", abs(l[3 * beat + piece]) < 0.02f && abs(l[3 * beat + 2 * piece]) < 0.02f)

        // every repeat quieter than the one before
        val (dl, dr) = both(src)
        run(effect(BeatRepeat.TYPE, knobs + ("decay" to 1f)), dl, dr, playing())
        assertTrue(matches(dl, 3 * beat + piece, 3 * beat, 0.5f) < 1e-4f)
        assertTrue(matches(dl, 3 * beat + 3 * piece, 3 * beat, 0.125f) < 1e-4f)
        // smaller pieces, a longer fill: thirty-seconds over the last two beats
        val (sl, sr) = both(src)
        run(effect(BeatRepeat.TYPE, knobs + mapOf("slice" to 5f, "length" to 1f)), sl, sr, playing())
        val small = RATE / 16
        for (i in 0 until 2 * beat step 37) assertEquals(src[i], sl[i], 0f)
        var worst = 0f
        for (k in 1 until 16) for (i in 200 until small - 200) worst = max(worst, abs(sl[2 * beat + k * small + i] - src[2 * beat + i]))
        assertTrue("fifteen repeats of a thirty-second: $worst", worst < 1e-4f)
        // only every second bar
        val (el, er) = both(src)
        run(effect(BeatRepeat.TYPE, knobs + ("every" to 2f)), el, er, playing())
        for (i in 0 until 7 * beat + piece step 37) assertEquals("the first bar has no fill", src[i], el[i], 0f)
        assertTrue(matches(el, 7 * beat + piece, 7 * beat) < 1e-4f)
        // mix: half of the original stays underneath; none of the effect at nothing
        val (ml, mr) = both(src)
        run(effect(BeatRepeat.TYPE, knobs + ("mix" to 0.5f)), ml, mr, playing())
        val at = 3 * beat + piece + piece / 2
        assertEquals(0.5f * src[at] + 0.5f * src[at - piece], ml[at], 1e-4f)
        val (nl, nr) = both(src)
        run(effect(BeatRepeat.TYPE, knobs + ("mix" to 0f)), nl, nr, playing())
        assertTrue(nl.contentEquals(src))
        // a song that starts inside the fill has no piece to repeat: it just plays
        val (jl, jr) = both(src)
        run(effect(BeatRepeat.TYPE, knobs), jl, jr, playing(fromTick = 3.6 * PPQ))
        for (i in 0 until beat / 4 step 13) assertEquals(src[i], jl[i], 0f)
    }

    @Test
    fun ottLevelsEverythingOut() {
        val off = mapOf("depth" to 0f)
        // ---- with the depth at nothing the sound keeps its level at every frequency
        for (hz in listOf(40.0, 120.0, 400.0, 1000.0, 2500.0, 6000.0, 12000.0)) {
            val (l, r) = both(tone(hz, 1.0, 0.25f))
            run(effect(Ott.TYPE, off), l, r)
            assertEquals("$hz Hz passes unchanged", 0.0, db(level(l, hz, RATE / 2) / 0.25), 0.15)
        }
        // ---- the band knobs are plain volume knobs for bass, middle and top
        // (the bands overlap a little where they meet, so each is measured well inside)
        val (bl, br) = both(tone(25.0, 1.0, 0.1f))
        run(effect(Ott.TYPE, off + ("low" to 6f)), bl, br)
        assertEquals(6.0, db(level(bl, 25.0, RATE / 2) / 0.1), 0.5)
        val (hl, hr) = both(tone(14000.0, 1.0, 0.1f))
        run(effect(Ott.TYPE, off + mapOf("low" to 6f, "high" to -6f)), hl, hr)
        assertEquals(-6.0, db(level(hl, 14000.0, RATE / 2) / 0.1), 0.5)
        val (ml, mr) = both(tone(550.0, 1.0, 0.1f))
        run(effect(Ott.TYPE, off + mapOf("mid" to 4f, "out" to -10f)), ml, mr)
        assertEquals(-6.0, db(level(ml, 550.0, RATE / 2) / 0.1), 0.7)

        // ---- full depth: quiet comes up, loud goes down
        val changes = ArrayList<String>()
        for (hz in listOf(60.0, 1000.0, 8000.0)) {
            val quiet = both(tone(hz, 2.0, 0.003f)).also { run(effect(Ott.TYPE, mapOf("depth" to 1f)), it.first, it.second) }
            val loud = both(tone(hz, 2.0, 0.6f)).also { run(effect(Ott.TYPE, mapOf("depth" to 1f)), it.first, it.second) }
            val up = db(level(quiet.first, hz, RATE) / 0.003)
            val down = db(level(loud.first, hz, RATE) / 0.6)
            changes += "%.0f Hz: quiet %+.1f dB, loud %+.1f dB".format(hz, up, down)
            assertTrue("$hz Hz: a quiet sound comes up ($up dB)", up > 10.0)
            assertTrue("$hz Hz: a loud one goes down ($down dB)", down < -3.0)
            // 46 dB apart going in, a few dB apart coming out
            assertTrue("$hz Hz: the two end up close together", (db(0.6 / 0.003) + down - up) < 22.0)
        }
        println("     info OTT: " + changes.joinToString("; "))
        // half the depth, about half the change
        val half = both(tone(1000.0, 2.0, 0.003f)).also { run(effect(Ott.TYPE, mapOf("depth" to 0.5f)), it.first, it.second) }
        val full = both(tone(1000.0, 2.0, 0.003f)).also { run(effect(Ott.TYPE, mapOf("depth" to 1f)), it.first, it.second) }
        assertEquals(db(level(full.first, 1000.0, RATE) / 0.003) / 2, db(level(half.first, 1000.0, RATE) / 0.003), 1.0)
        // silence stays silence: the hiss of nothing is not pulled up
        val (zl, zr) = both(FloatArray(RATE))
        run(effect(Ott.TYPE, mapOf("depth" to 1f)), zl, zr)
        assertTrue(peak(zl) < 1e-9f)
        // a whole mix stays in bounds
        val mix = FloatArray(RATE * 2) { i -> (sin(2 * Math.PI * 55 * i / RATE) * 0.5 + sin(2 * Math.PI * 440 * i / RATE) * 0.2 + sin(2 * Math.PI * 7000 * i / RATE) * 0.02).toFloat() }
        val (xl, xr) = both(mix)
        run(effect(Ott.TYPE, mapOf("depth" to 1f)), xl, xr)
        println("     info OTT on a mix: 55 Hz %+.1f dB, 440 Hz %+.1f dB, 7 kHz %+.1f dB, peak %.2f".format(
            db(level(xl, 55.0, RATE) / 0.5), db(level(xl, 440.0, RATE) / 0.2), db(level(xl, 7000.0, RATE) / 0.02), peak(xl, RATE)))
        assertTrue("the quiet top comes up against the loud bass", db(level(xl, 7000.0, RATE) / 0.02) - db(level(xl, 55.0, RATE) / 0.5) > 8.0)
        assertTrue(peak(xl) < 2.5f)
    }

    @Test
    fun autoWahOpensWithTheNote() {
        val knobs = mapOf("sens" to 0.6f, "freq" to 300f, "range" to 0.8f, "reso" to 0.5f, "speed" to 40f, "mix" to 1f)
        // the same noise, played softly and played hard
        val (ql, qr) = both(noise(1.0, 0.004f))
        run(effect(AutoWah.TYPE, knobs), ql, qr)
        val (hl, hr) = both(noise(1.0, 0.5f))
        run(effect(AutoWah.TYPE, knobs), hl, hr)
        println("     info auto-wah: brightness soft %.3f, hard %.3f".format(brightness(ql, RATE / 2), brightness(hl, RATE / 2)))
        assertTrue("played hard, the filter opens", brightness(hl, RATE / 2) > brightness(ql, RATE / 2) * 2.5)
        assertTrue(hl.contentEquals(hr))
        // one note: bright at the start, closing as it dies away
        val pluck = noise(1.0, 0.5f).also { for (i in it.indices) it[i] *= Math.exp(-i / (0.15 * RATE)).toFloat() }
        val (pl, pr) = both(pluck)
        run(effect(AutoWah.TYPE, knobs), pl, pr)
        val head = brightness(pl, 500, 3000)
        val body = brightness(pl, RATE / 2, RATE * 3 / 4)
        println("     info auto-wah on one note: brightness at the start %.3f, later %.3f".format(head, body))
        assertTrue("the filter closes with the note", head > body * 2)
        // more sensitive: a soft note opens it too
        val (sl, sr) = both(noise(1.0, 0.004f))
        run(effect(AutoWah.TYPE, knobs + ("sens" to 1f)), sl, sr)
        assertTrue(brightness(sl, RATE / 2) > brightness(ql, RATE / 2) * 1.3)
        // no effect at mix nothing
        val (nl, nr) = both(noise(1.0, 0.5f))
        run(effect(AutoWah.TYPE, knobs + ("mix" to 0f)), nl, nr)
        assertTrue(nl.contentEquals(noise(1.0, 0.5f)))
        assertTrue(peak(hl) < 3f)
    }

    @Test
    fun theNewEffectsAreLightAndDoNotAllocate() {
        // how long each effect needs for ten seconds of sound (a phone is several times slower than this machine)
        val types = listOf(Space3D.TYPE, PitchCorrect.TYPE, PitchShift.TYPE, Gate.TYPE, DeEsser.TYPE, HalfTime.TYPE, BeatRepeat.TYPE, Ott.TYPE, AutoWah.TYPE)
        for (type in types) {
            val fx = effect(type)
            val warm = rich(180.0, 2.0)
            run(fx, warm, warm.copyOf(), playing())
            val l = rich(196.0, 10.0)
            val r = l.copyOf()
            val started = System.nanoTime()
            run(fx, l, r, playing())
            val ms = (System.nanoTime() - started) / 1e6
            println("     info cost %-9s %6.1f ms for 10 s of sound (%.2f%% of one core)".format(type.id, ms, ms / 100.0))
            assertTrue("${type.id} takes $ms ms for ten seconds", ms < 2500)
            assertFalse(l.any { it.isNaN() })
        }

        // a song that uses all of it: notes with a place of their own, an audio track with curves, every new effect
        val pool = SamplePool()
        pool.put("voice.wav", SampleData(RATE, ShortArray(RATE * 4) { i ->
            var s = 0.0
            for (k in 1..6) s += sin(2 * Math.PI * 233.0 * k * i / RATE) / k
            (s * 9000).toInt().toShort()
        }))
        var p = Project(name = "all", bpm = 128f, tracks = 2, keyRoot = 9, scale = Scales.MINOR)
        val pattern = p.addPattern("p").let { p = it.first; it.second }
        val synth = p.addChannel(Devices.SYNTH.id, "s", params = Presets.synth("init").params).let { p = it.first; it.second }
        p = p.setNotes(pattern, synth, List(16) { Note(it * PPQ / 4, PPQ / 4, 48 + it % 5 * 3, 0.8f, pan = (it % 5 - 2) / 2f) })
        for (fx in listOf("halftime", "repeat", "ott", "wah")) p = p.addEffect(synth, fx).first
        val audio = p.addChannel(Devices.AUDIO_TYPE_ID, "a").let { p = it.first; it.second }
        var angle = 0
        for (fx in listOf("gate", "deess", "tune", "pitch", "space3d")) p = p.addEffect(audio, fx).let { angle = it.second; it.first }
        p = p.addPatternClip(pattern, 0, 0, p.ticksPerBar * 64).first
        for (k in 0 until 16) p = p.addAudioClip(audio, "voice.wav", 1, k * 4 * p.ticksPerBar, 4 * p.ticksPerBar).first
        p = p.setSongAutomation(audio, STRIP_DEVICE, PARAM_PAN, List(64) { AutoPoint(it * p.ticksPerBar, (it % 2).toFloat()) })
        p = p.setSongAutomation(audio, angle, "distance", List(64) { AutoPoint(it * p.ticksPerBar, (it % 3) / 2f) })

        val engine = Engine(RATE)
        engine.songMode = true
        engine.setProject(p, pool)
        val out = FloatArray(512 * 2)
        var bytes = -1L
        var peak = 0f
        val thread = Thread {
            val bean = ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean
            val me = Thread.currentThread().id
            engine.play(0)
            // let the JIT and the class loader finish their work first
            repeat(800) { engine.render(out, 256) }
            val before = bean.getThreadAllocatedBytes(me)
            repeat(800) {
                engine.render(out, 256)
                for (i in 0 until 512) if (abs(out[i]) > peak) peak = abs(out[i])
            }
            bytes = bean.getThreadAllocatedBytes(me) - before
        }
        thread.start()
        thread.join()
        println("     info new effects in a song: $bytes bytes allocated in 800 blocks, peak ${"%.3f".format(peak)}")
        assertTrue("sound comes out: $peak", peak > 0.05f)
        assertFalse(peak.isNaN())
        assertTrue("$bytes bytes allocated on the audio thread", bytes <= 16384)
    }
}
