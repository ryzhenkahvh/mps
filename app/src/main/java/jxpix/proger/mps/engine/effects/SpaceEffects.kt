package jxpix.proger.mps.engine.effects

import jxpix.proger.mps.R
import jxpix.proger.mps.engine.DeviceType
import jxpix.proger.mps.engine.Effect
import jxpix.proger.mps.engine.ParamCurve
import jxpix.proger.mps.engine.ParamSpec
import jxpix.proger.mps.engine.ParamUnit
import jxpix.proger.mps.engine.ProcessContext
import jxpix.proger.mps.engine.dsp.DENORMAL
import jxpix.proger.mps.engine.dsp.DelayLine
import jxpix.proger.mps.engine.dsp.FastMath
import jxpix.proger.mps.engine.dsp.OnePole
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.tan

/** Dry stays at full level up to 50% mix, then fades; the wet does the opposite. */
private fun dryGain(mix: Float) = min(1f, 2f * (1f - mix))
private fun wetGain(mix: Float) = min(1f, 2f * mix)

/** Stereo chorus: two modulated delay lines, 90 degrees apart. */
class Chorus : Effect() {
    private lateinit var lineL: DelayLine
    private lateinit var lineR: DelayLine
    private var rate = 0.8f
    private var depth = 3f
    private var base = 12f
    private var feedback = 0f
    private var mix = 0.5f
    private var phase = 0f

    override fun prepare(sampleRate: Int) {
        super.prepare(sampleRate)
        val max = (this.sampleRate * 0.06f).toInt()
        lineL = DelayLine(max)
        lineR = DelayLine(max)
    }

    override fun setParams(p: FloatArray) {
        rate = p[RATE]
        depth = p[DEPTH] * 6f
        base = p[DELAY]
        feedback = p[FEEDBACK]
        mix = p[MIX]
    }

    override fun process(l: FloatArray, r: FloatArray, frames: Int, ctx: ProcessContext) {
        val inc = rate / sampleRate
        val msToSamples = sampleRate / 1000f
        val dry = dryGain(mix)
        val wet = wetGain(mix)
        val d = min(depth, base - 1f).coerceAtLeast(0f)
        for (i in 0 until frames) {
            phase += inc
            if (phase >= 1f) phase -= 1f
            val dl = (base + d * FastMath.sin01(phase)) * msToSamples
            val dr = (base + d * FastMath.sin01(phase + 0.25f)) * msToSamples
            val wl = lineL.readFrac(dl)
            val wr = lineR.readFrac(dr)
            lineL.write(l[i] + wl * feedback + DENORMAL)
            lineR.write(r[i] + wr * feedback + DENORMAL)
            l[i] = l[i] * dry + wl * wet
            r[i] = r[i] * dry + wr * wet
        }
    }

    override fun reset() {
        lineL.clear(); lineR.clear()
    }

    companion object {
        const val TYPE_ID = "chorus"
        const val RATE = 0
        const val DEPTH = 1
        const val DELAY = 2
        const val FEEDBACK = 3
        const val MIX = 4

        val PARAMS = listOf(
            ParamSpec("rate", R.string.p_rate, 0.05f, 8f, 0.8f, ParamUnit.HZ, ParamCurve.EXP),
            ParamSpec("depth", R.string.p_depth, 0f, 1f, 0.5f, ParamUnit.PERCENT),
            ParamSpec("delay", R.string.p_delay, 2f, 30f, 12f, ParamUnit.MILLIS),
            ParamSpec("feedback", R.string.p_feedback, 0f, 0.9f, 0.1f, ParamUnit.PERCENT),
            ParamSpec("mix", R.string.p_mix, 0f, 1f, 0.5f, ParamUnit.PERCENT),
        )
        val TYPE = DeviceType(TYPE_ID, R.string.fx_chorus, R.string.fx_chorus_desc, false, PARAMS) { Chorus() }
    }
}

/** Six stage phaser. */
class Phaser : Effect() {
    private val sl = FloatArray(STAGES)
    private val sr = FloatArray(STAGES)
    private var rate = 0.4f
    private var depth = 0.7f
    private var feedback = 0.5f
    private var center = 800f
    private var mix = 0.5f
    private var phase = 0f
    private var coefL = 0f
    private var coefR = 0f
    private var lastL = 0f
    private var lastR = 0f
    private var counter = 0

    override fun setParams(p: FloatArray) {
        rate = p[RATE]
        depth = p[DEPTH]
        feedback = p[FEEDBACK]
        center = p[CENTER]
        mix = p[MIX]
    }

    private fun coef(freq: Float): Float {
        val t = tan(FastMath.PI_F * freq.coerceIn(20f, sampleRate * 0.45f) / sampleRate)
        return (1f - t) / (1f + t)
    }

    override fun process(l: FloatArray, r: FloatArray, frames: Int, ctx: ProcessContext) {
        val dry = dryGain(mix)
        val wet = wetGain(mix)
        for (i in 0 until frames) {
            if (counter <= 0) {
                counter = CTRL
                phase += rate * CTRL / sampleRate
                if (phase >= 1f) phase -= 1f
                coefL = coef(center * 2f.pow(FastMath.sin01(phase) * depth * 2f))
                coefR = coef(center * 2f.pow(FastMath.sin01(phase + 0.2f) * depth * 2f))
            }
            counter--
            var xl = l[i] + lastL * feedback
            var xr = r[i] + lastR * feedback
            for (s in 0 until STAGES) {
                val yl = -coefL * xl + sl[s]
                sl[s] = xl + coefL * yl + DENORMAL
                xl = yl
                val yr = -coefR * xr + sr[s]
                sr[s] = xr + coefR * yr + DENORMAL
                xr = yr
            }
            lastL = xl
            lastR = xr
            l[i] = l[i] * dry + xl * wet
            r[i] = r[i] * dry + xr * wet
        }
    }

    override fun reset() {
        sl.fill(0f); sr.fill(0f); lastL = 0f; lastR = 0f
    }

    companion object {
        const val TYPE_ID = "phaser"
        private const val STAGES = 6
        private const val CTRL = 16
        const val RATE = 0
        const val DEPTH = 1
        const val FEEDBACK = 2
        const val CENTER = 3
        const val MIX = 4

        val PARAMS = listOf(
            ParamSpec("rate", R.string.p_rate, 0.02f, 8f, 0.4f, ParamUnit.HZ, ParamCurve.EXP),
            ParamSpec("depth", R.string.p_depth, 0f, 1f, 0.7f, ParamUnit.PERCENT),
            ParamSpec("feedback", R.string.p_feedback, 0f, 0.9f, 0.5f, ParamUnit.PERCENT),
            ParamSpec("center", R.string.p_frequency, 100f, 4000f, 800f, ParamUnit.HZ, ParamCurve.EXP),
            ParamSpec("mix", R.string.p_mix, 0f, 1f, 0.5f, ParamUnit.PERCENT),
        )
        val TYPE = DeviceType(TYPE_ID, R.string.fx_phaser, R.string.fx_phaser_desc, false, PARAMS) { Phaser() }
    }
}

/** Stereo / ping-pong echo, optionally locked to the project tempo. */
class Delay : Effect() {
    private lateinit var lineL: DelayLine
    private lateinit var lineR: DelayLine
    private val dampL = OnePole()
    private val dampR = OnePole()
    private val cutL = OnePole()
    private val cutR = OnePole()
    private var sync = 5
    private var timeMs = 300f
    private var feedback = 0.4f
    private var mix = 0.3f
    private var pingPong = false
    private var current = -1f
    private var maxDelay = 1f

    override fun prepare(sampleRate: Int) {
        super.prepare(sampleRate)
        val max = (this.sampleRate * MAX_SECONDS).toInt()
        lineL = DelayLine(max + 8)
        lineR = DelayLine(max + 8)
        maxDelay = max.toFloat()
        cutL.setCutoff(120f, this.sampleRate)
        cutR.setCutoff(120f, this.sampleRate)
    }

    override fun setParams(p: FloatArray) {
        sync = p[SYNC].toInt()
        timeMs = p[TIME]
        feedback = p[FEEDBACK]
        mix = p[MIX]
        pingPong = p[PING_PONG] > 0.5f
        val cutoff = 18000f * (1000f / 18000f).pow(p[DAMP])
        dampL.setCutoff(min(cutoff, sampleRate * 0.45f), sampleRate)
        dampR.setCutoff(min(cutoff, sampleRate * 0.45f), sampleRate)
    }

    override fun process(l: FloatArray, r: FloatArray, frames: Int, ctx: ProcessContext) {
        val seconds = if (sync in 1 until BEATS.size) (BEATS[sync] * 60.0 / ctx.bpm).toFloat() else timeMs / 1000f
        val target = (seconds * sampleRate).coerceIn(1f, maxDelay)
        if (current < 0f) current = target
        val dry = dryGain(mix)
        val wet = wetGain(mix)
        val fb = feedback
        var cur = current
        for (i in 0 until frames) {
            cur += (target - cur) * 0.0006f
            val dl = lineL.readFrac(cur)
            val dr = lineR.readFrac(cur)
            val fl = cutL.hp(dampL.lp(dl))
            val fr = cutR.hp(dampR.lp(dr))
            if (pingPong) {
                lineL.write((l[i] + r[i]) * 0.5f + fr * fb)
                lineR.write(fl)
            } else {
                lineL.write(l[i] + fl * fb)
                lineR.write(r[i] + fr * fb)
            }
            l[i] = l[i] * dry + dl * wet
            r[i] = r[i] * dry + dr * wet
        }
        current = cur
    }

    override fun reset() {
        lineL.clear(); lineR.clear()
        dampL.reset(); dampR.reset(); cutL.reset(); cutR.reset()
    }

    companion object {
        const val TYPE_ID = "delay"
        private const val MAX_SECONDS = 2.5f
        const val SYNC = 0
        const val TIME = 1
        const val FEEDBACK = 2
        const val MIX = 3
        const val PING_PONG = 4
        const val DAMP = 5

        /** Delay lengths in quarter notes, matching R.array.arr_delay_sync (index 0 = free time). */
        private val BEATS = doubleArrayOf(0.0, 0.125, 1.0 / 6, 0.25, 1.0 / 3, 0.5, 0.75, 2.0 / 3, 1.0, 1.5, 2.0)

        val PARAMS = listOf(
            ParamSpec("sync", R.string.p_sync, 0f, 10f, 6f, choicesRes = R.array.arr_delay_sync),
            ParamSpec("time", R.string.p_time, 10f, 2000f, 300f, ParamUnit.MILLIS, ParamCurve.EXP),
            ParamSpec("feedback", R.string.p_feedback, 0f, 0.95f, 0.4f, ParamUnit.PERCENT),
            ParamSpec("mix", R.string.p_mix, 0f, 1f, 0.25f, ParamUnit.PERCENT),
            ParamSpec("pingPong", R.string.p_ping_pong, 0f, 1f, 1f, choicesRes = R.array.arr_off_on),
            ParamSpec("damp", R.string.p_damping, 0f, 1f, 0.4f, ParamUnit.PERCENT),
        )
        val TYPE = DeviceType(TYPE_ID, R.string.fx_delay, R.string.fx_delay_desc, false, PARAMS) { Delay() }
    }
}

/**
 * Plate reverb after Jon Dattorro ("Effect Design, Part 1", JAES 1997):
 * four input diffusers feeding a figure-eight tank with modulated all-passes.
 */
class Reverb : Effect() {
    private var decay = 0.6f
    private var damp = 0.3f
    private var mix = 0.25f
    private var width = 1f
    private var preSamples = 1

    private lateinit var pre: DelayLine
    private lateinit var in1: DelayLine
    private lateinit var in2: DelayLine
    private lateinit var in3: DelayLine
    private lateinit var in4: DelayLine
    private lateinit var apA: DelayLine
    private lateinit var dA1: DelayLine
    private lateinit var apA2: DelayLine
    private lateinit var dA2: DelayLine
    private lateinit var apB: DelayLine
    private lateinit var dB1: DelayLine
    private lateinit var apB2: DelayLine
    private lateinit var dB2: DelayLine

    private var nIn1 = 0
    private var nIn2 = 0
    private var nIn3 = 0
    private var nIn4 = 0
    private var nApA = 0f
    private var nDA1 = 0
    private var nApA2 = 0
    private var nDA2 = 0
    private var nApB = 0f
    private var nDB1 = 0
    private var nApB2 = 0
    private var nDB2 = 0
    private val tapL = IntArray(7)
    private val tapR = IntArray(7)
    private var excursion = 0f

    private val lowCut = OnePole()
    private var dampA = 0f
    private var dampB = 0f
    private var lfo1 = 0f
    private var lfo2 = 0.3f
    private var lfoInc1 = 0f
    private var lfoInc2 = 0f

    override fun prepare(sampleRate: Int) {
        super.prepare(sampleRate)
        val k = this.sampleRate / 29761f
        fun n(x: Int) = (x * k).toInt().coerceAtLeast(1)
        excursion = 12f * k
        pre = DelayLine((this.sampleRate * 0.26f).toInt())
        nIn1 = n(142); in1 = DelayLine(nIn1)
        nIn2 = n(107); in2 = DelayLine(nIn2)
        nIn3 = n(379); in3 = DelayLine(nIn3)
        nIn4 = n(277); in4 = DelayLine(nIn4)
        nApA = 672 * k; apA = DelayLine((nApA + excursion).toInt() + 4)
        nDA1 = n(4453); dA1 = DelayLine(nDA1)
        nApA2 = n(1800); apA2 = DelayLine(nApA2)
        nDA2 = n(3720); dA2 = DelayLine(nDA2)
        nApB = 908 * k; apB = DelayLine((nApB + excursion).toInt() + 4)
        nDB1 = n(4217); dB1 = DelayLine(nDB1)
        nApB2 = n(2656); apB2 = DelayLine(nApB2)
        nDB2 = n(3163); dB2 = DelayLine(nDB2)
        val l = intArrayOf(266, 2974, 1913, 1996, 1990, 187, 1066)
        val r = intArrayOf(353, 3627, 1228, 2673, 2111, 335, 121)
        for (i in 0 until 7) {
            tapL[i] = n(l[i])
            tapR[i] = n(r[i])
        }
        lfoInc1 = 0.5f / this.sampleRate
        lfoInc2 = 0.31f / this.sampleRate
    }

    override fun setParams(p: FloatArray) {
        decay = 0.15f + 0.8f * p[SIZE].pow(0.8f)
        damp = p[DAMP] * 0.85f
        preSamples = (p[PRE_DELAY] / 1000f * sampleRate).toInt().coerceIn(1, pre.capacity)
        lowCut.setCutoff(p[LOW_CUT], sampleRate)
        width = p[WIDTH]
        mix = p[MIX]
    }

    private fun diffuse(line: DelayLine, length: Int, g: Float, x: Float): Float {
        val d = line.read(length)
        val w = x - g * d
        line.write(w)
        return d + g * w
    }

    override fun process(l: FloatArray, r: FloatArray, frames: Int, ctx: ProcessContext) {
        val dry = dryGain(mix)
        val wet = wetGain(mix)
        val decay = decay
        val damp = damp
        val keep = 1f - damp
        val side = 0.5f * width
        for (i in 0 until frames) {
            val inL = l[i]
            val inR = r[i]
            var x = lowCut.hp((inL + inR) * 0.5f)
            pre.write(x)
            x = pre.read(preSamples)
            x = diffuse(in1, nIn1, 0.75f, x)
            x = diffuse(in2, nIn2, 0.75f, x)
            x = diffuse(in3, nIn3, 0.625f, x)
            x = diffuse(in4, nIn4, 0.625f, x)

            lfo1 += lfoInc1
            if (lfo1 >= 1f) lfo1 -= 1f
            lfo2 += lfoInc2
            if (lfo2 >= 1f) lfo2 -= 1f

            val fromB = dB2.read(nDB2)
            val fromA = dA2.read(nDA2)

            // ---- side A ----
            var a = x + decay * fromB
            var d = apA.readFrac(nApA + excursion * FastMath.sin01(lfo1))
            var w = a + 0.7f * d
            apA.write(w)
            a = d - 0.7f * w
            dA1.write(a)
            a = dA1.read(nDA1)
            dampA = a * keep + dampA * damp + DENORMAL
            a = dampA * decay
            d = apA2.read(nApA2)
            w = a - 0.5f * d
            apA2.write(w)
            dA2.write(d + 0.5f * w)

            // ---- side B ----
            var b = x + decay * fromA
            d = apB.readFrac(nApB + excursion * FastMath.sin01(lfo2))
            w = b + 0.7f * d
            apB.write(w)
            b = d - 0.7f * w
            dB1.write(b)
            b = dB1.read(nDB1)
            dampB = b * keep + dampB * damp + DENORMAL
            b = dampB * decay
            d = apB2.read(nApB2)
            w = b - 0.5f * d
            apB2.write(w)
            dB2.write(d + 0.5f * w)

            val yl = 0.6f * (dB1.read(tapL[0]) + dB1.read(tapL[1]) - apB2.read(tapL[2]) + dB2.read(tapL[3]) -
                dA1.read(tapL[4]) - apA2.read(tapL[5]) - dA2.read(tapL[6]))
            val yr = 0.6f * (dA1.read(tapR[0]) + dA1.read(tapR[1]) - apA2.read(tapR[2]) + dA2.read(tapR[3]) -
                dB1.read(tapR[4]) - apB2.read(tapR[5]) - dB2.read(tapR[6]))

            val m = (yl + yr) * 0.5f
            val s = (yl - yr) * side
            l[i] = inL * dry + (m + s) * wet
            r[i] = inR * dry + (m - s) * wet
        }
    }

    override fun reset() {
        pre.clear(); in1.clear(); in2.clear(); in3.clear(); in4.clear()
        apA.clear(); dA1.clear(); apA2.clear(); dA2.clear()
        apB.clear(); dB1.clear(); apB2.clear(); dB2.clear()
        dampA = 0f; dampB = 0f
        lowCut.reset()
    }

    companion object {
        const val TYPE_ID = "reverb"
        const val SIZE = 0
        const val DAMP = 1
        const val PRE_DELAY = 2
        const val LOW_CUT = 3
        const val WIDTH = 4
        const val MIX = 5

        val PARAMS = listOf(
            ParamSpec("size", R.string.p_size, 0f, 1f, 0.5f, ParamUnit.PERCENT),
            ParamSpec("damp", R.string.p_damping, 0f, 1f, 0.4f, ParamUnit.PERCENT),
            ParamSpec("preDelay", R.string.p_pre_delay, 0f, 200f, 10f, ParamUnit.MILLIS),
            ParamSpec("lowCut", R.string.p_low_cut, 20f, 1000f, 150f, ParamUnit.HZ, ParamCurve.EXP),
            ParamSpec("width", R.string.p_stereo, 0f, 1f, 1f, ParamUnit.PERCENT),
            ParamSpec("mix", R.string.p_mix, 0f, 1f, 0.25f, ParamUnit.PERCENT),
        )
        val TYPE = DeviceType(TYPE_ID, R.string.fx_reverb, R.string.fx_reverb_desc, false, PARAMS) { Reverb() }
    }
}
