package jxpix.proger.mps.engine.instruments

import jxpix.proger.mps.R
import jxpix.proger.mps.engine.DeviceType
import jxpix.proger.mps.engine.Instrument
import jxpix.proger.mps.engine.ParamCurve
import jxpix.proger.mps.engine.ParamSpec
import jxpix.proger.mps.engine.ParamUnit
import jxpix.proger.mps.engine.dsp.DENORMAL
import jxpix.proger.mps.engine.dsp.FastMath
import jxpix.proger.mps.engine.dsp.Noise
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/**
 * Plucked strings by physical modelling (Karplus-Strong): a burst of noise runs round a
 * short delay and loses a little of its top on every lap - exactly what a string does.
 * Guitars, harps, kotos and plucked basses that respond to how hard they are played.
 *
 * Every voice is two strings a few cents apart, one on each side.
 */
class StringSynth : Instrument() {
    private var voices = Array(MAX_VOICES) { Voice(16) }
    private val noise = Noise(0x51F3A7)
    private var counter = 0L

    private var bright = 0.6f
    private var sustainTime = 2.5f
    private var pick = 0.5f
    private var stereo = 0.4f
    private var releaseTime = 0.25f
    private var octave = 0f
    private var velSens = 0.7f
    private var level = 0.8f

    /** A string that has stayed below [SILENCE] for this many samples is switched off. */
    private var quietLimit = 1200

    override fun prepare(sampleRate: Int) {
        super.prepare(sampleRate)
        quietLimit = sampleRate / 40
        // room for the lowest note (about 20 Hz)
        val size = Integer.highestOneBit(sampleRate / 18) shl 1
        voices = Array(MAX_VOICES) { Voice(size) }
    }

    override fun setParams(p: FloatArray) {
        bright = p[P_BRIGHT]
        sustainTime = p[P_SUSTAIN]
        pick = p[P_PICK]
        stereo = p[P_STEREO]
        releaseTime = p[P_RELEASE]
        octave = p[P_OCTAVE]
        velSens = p[P_VELOCITY]
        level = p[P_LEVEL]
        for (v in voices) if (v.on) v.setDecay(if (v.gate) sustainTime else releaseTime, bright)
    }

    override val active: Boolean
        get() {
            for (v in voices) if (v.on) return true
            return false
        }

    override fun noteOn(pitch: Int, velocity: Float) {
        var chosen: Voice? = null
        var oldest = voices[0]
        for (v in voices) {
            if (!v.on) {
                chosen = v
                break
            }
            if (v.order < oldest.order) oldest = v
        }
        val v = chosen ?: oldest
        val freq = FastMath.noteToFreq(pitch + octave * 12f).coerceIn(20f, sampleRate * 0.25f)
        val spread = 2f.pow(stereo * 7f / 1200f)
        v.pitch = pitch
        v.order = ++counter
        v.gate = true
        v.on = true
        v.freq = freq
        v.panL = panLeft(notePan)
        v.panR = panRight(notePan)
        v.periodL = sampleRate / (freq / spread)
        v.periodR = sampleRate / (freq * spread)
        v.setDecay(sustainTime, bright)

        // The pluck: one period of noise, duller for a soft finger, brighter for a hard pick;
        // a harder hit is also a little brighter.
        val touch = (1f - velSens) + velSens * velocity
        val hardness = (0.08f + 0.92f * pick * (0.5f + 0.5f * touch)).coerceIn(0.02f, 1f)
        val amp = touch * touch
        v.pluck(noise, hardness, amp)
    }

    override fun noteOff(pitch: Int) {
        for (v in voices) if (v.on && v.gate && v.pitch == pitch) {
            v.gate = false
            v.setDecay(releaseTime, bright)
        }
    }

    override fun allNotesOff() {
        for (v in voices) if (v.on && v.gate) {
            v.gate = false
            v.setDecay(releaseTime, bright)
        }
    }

    override fun choke() {
        for (v in voices) if (v.on) {
            v.gate = false
            v.setDecay(0.02f, bright)
        }
    }

    override fun reset() {
        for (v in voices) v.on = false
    }

    override fun render(outL: FloatArray, outR: FloatArray, offset: Int, count: Int) {
        val out = level * 0.75f
        for (v in voices) {
            if (!v.on) continue
            val peak = v.render(outL, outR, offset, count, out)
            // rung out: free the voice (counted in time, so it does not depend on the size of the blocks)
            if (peak < SILENCE) {
                v.quiet += count
                if (v.quiet > quietLimit) v.on = false
            } else v.quiet = 0
        }
    }

    private class Voice(size: Int) {
        private val mask = size - 1
        val capacity = size - 4
        private val bufL = FloatArray(size)
        private val bufR = FloatArray(size)
        private var w = 0
        private var prevL = 0f
        private var prevR = 0f
        private var gain = 0.99f
        private var damp = 0.25f

        var on = false
        var gate = false
        var pitch = 0
        var order = 0L
        var freq = 440f
        var panL = 1f
        var panR = 1f

        /** Length of one period of each string, in samples. */
        var periodL = 100f
        var periodR = 100f
        private var lenL = 100f
        private var lenR = 100f
        var quiet = 0

        /** [seconds] is the time the string takes to fall silent (-60 dB); [bright] how much top survives a lap. */
        fun setDecay(seconds: Float, bright: Float) {
            gain = Math.pow(10.0, -3.0 / (freq * max(0.01f, seconds)).toDouble()).toFloat().coerceIn(0f, 0.99995f)
            // The loop filter averages each sample with the one before it. Fully mixed (0.5) it is the
            // classic plucked string whose top fades within a second; at 0 nothing is taken away and
            // the string keeps its bite to the end, like a harpsichord.
            damp = 0.5f * (1f - bright)
            // The filter delays the wave a little, which would flatten the note: make the string shorter by that much.
            lenL = (periodL - filterDelay(periodL)).coerceIn(2f, capacity.toFloat())
            lenR = (periodR - filterDelay(periodR)).coerceIn(2f, capacity.toFloat())
        }

        /** Delay of the loop filter, in samples, for a wave with the given period. */
        private fun filterDelay(period: Float): Float {
            val w = FastMath.TWO_PI / period
            return atan2(damp * sin(w), (1f - damp) + damp * cos(w)) / w
        }

        fun pluck(noise: Noise, hardness: Float, amp: Float) {
            val n = min(capacity, max(periodL, periodR).toInt() + 2)
            var lp = 0f
            var sum = 0f
            // the newest sample sits just behind the write head
            for (i in 0 until n) {
                lp += (noise.next() - lp) * hardness
                bufL[(w - 1 - i) and mask] = lp
                sum += lp
            }
            // no DC, and the same loudness whatever the hardness
            val mean = sum / n
            var top = 1e-6f
            for (i in 0 until n) {
                val x = bufL[(w - 1 - i) and mask] - mean
                bufL[(w - 1 - i) and mask] = x
                if (x > top) top = x else if (-x > top) top = -x
            }
            val k = amp / top
            for (i in 0 until n) {
                val idx = (w - 1 - i) and mask
                val x = bufL[idx] * k
                bufL[idx] = x
                bufR[idx] = x
            }
            prevL = bufL[(w - 1) and mask]
            prevR = prevL
            quiet = 0
        }

        fun render(outL: FloatArray, outR: FloatArray, offset: Int, count: Int, out: Float): Float {
            val dL = lenL.toInt()
            val fL = lenL - dL
            val dR = lenR.toInt()
            val fR = lenR - dR
            val g = gain
            val a = damp
            val outL0 = out * panL
            val outR0 = out * panR
            var peak = 0f
            var pos = w
            var pl = prevL
            var pr = prevR
            for (i in offset until offset + count) {
                val l0 = bufL[(pos - dL) and mask]
                val xl = l0 + (bufL[(pos - dL - 1) and mask] - l0) * fL
                val r0 = bufR[(pos - dR) and mask]
                val xr = r0 + (bufR[(pos - dR - 1) and mask] - r0) * fR
                bufL[pos] = (xl + (pl - xl) * a) * g + DENORMAL
                bufR[pos] = (xr + (pr - xr) * a) * g + DENORMAL
                pl = xl
                pr = xr
                pos = (pos + 1) and mask
                outL[i] += xl * outL0
                outR[i] += xr * outR0
                val m = if (xl < 0f) -xl else xl
                if (m > peak) peak = m
            }
            w = pos
            prevL = pl
            prevR = pr
            return peak
        }
    }

    companion object {
        const val TYPE_ID = "strings"
        private const val MAX_VOICES = 8
        private const val SILENCE = 0.0002f

        const val P_BRIGHT = 0
        const val P_SUSTAIN = 1
        const val P_PICK = 2
        const val P_RELEASE = 3
        const val P_OCTAVE = 4
        const val P_STEREO = 5
        const val P_VELOCITY = 6
        const val P_LEVEL = 7

        val PARAMS: List<ParamSpec> = listOf(
            ParamSpec("bright", R.string.p_brightness, 0f, 1f, 0.6f, ParamUnit.PERCENT, group = R.string.g_string),
            ParamSpec("sustain", R.string.p_ring, 0.1f, 12f, 2.5f, ParamUnit.SECONDS, ParamCurve.EXP, group = R.string.g_string),
            ParamSpec("pick", R.string.p_pick, 0f, 1f, 0.5f, ParamUnit.PERCENT, group = R.string.g_string),
            ParamSpec("release", R.string.p_release, 0.02f, 4f, 0.25f, ParamUnit.SECONDS, ParamCurve.EXP, group = R.string.g_string),
            ParamSpec("octave", R.string.p_octave, -2f, 2f, 0f, ParamUnit.OCTAVES, group = R.string.g_voice, integer = true),
            ParamSpec("stereo", R.string.p_stereo, 0f, 1f, 0.4f, ParamUnit.PERCENT, group = R.string.g_voice),
            ParamSpec("velocity", R.string.p_velocity, 0f, 1f, 0.7f, ParamUnit.PERCENT, group = R.string.g_voice),
            ParamSpec("level", R.string.p_level, 0f, 1f, 0.8f, ParamUnit.PERCENT, group = R.string.g_voice),
        )
        val TYPE = DeviceType(TYPE_ID, R.string.dev_strings, R.string.dev_strings_desc, true, PARAMS) { StringSynth() }
    }
}
