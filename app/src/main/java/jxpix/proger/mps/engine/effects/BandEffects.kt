package jxpix.proger.mps.engine.effects

import jxpix.proger.mps.R
import jxpix.proger.mps.engine.DeviceType
import jxpix.proger.mps.engine.Effect
import jxpix.proger.mps.engine.ParamCurve
import jxpix.proger.mps.engine.ParamSpec
import jxpix.proger.mps.engine.ParamUnit
import jxpix.proger.mps.engine.ProcessContext
import jxpix.proger.mps.engine.dsp.Biquad
import jxpix.proger.mps.engine.dsp.FastMath
import jxpix.proger.mps.engine.dsp.Svf
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * "OTT" - the over-the-top multiband compressor of electronic music. The sound is split into
 * bass, middle and top; in each band the loud parts are pushed down and the quiet parts are
 * pulled up, until everything sits at one level: dense, bright, in your face.
 */
class Ott : Effect() {
    // crossover: bass | the rest, then middle | top; the bass takes the same detour so the bands stay in step
    private val split1 = Array(2) { Crossover() }
    private val split2 = Array(2) { Crossover() }
    private val align = Array(2) { Crossover() }

    private var depth = 0.5f
    private var out = 1f
    private val trim = FloatArray(BANDS)
    private val attack = FloatArray(BANDS)
    private val release = FloatArray(BANDS)

    private val env = FloatArray(BANDS)
    private val gain = FloatArray(BANDS) { 1f }
    private val target = FloatArray(BANDS) { 1f }
    private var smooth = 0.01f
    private var count = 0

    override fun prepare(sampleRate: Int) {
        super.prepare(sampleRate)
        for (c in 0 until 2) {
            split1[c].set(LOW_SPLIT, this.sampleRate)
            split2[c].set(HIGH_SPLIT, this.sampleRate)
            align[c].set(HIGH_SPLIT, this.sampleRate)
        }
        smooth = FastMath.onePoleCoef(0.003f, this.sampleRate)
    }

    override fun setParams(p: FloatArray) {
        depth = p[DEPTH]
        // the time knob makes every band four times quicker or slower
        val scale = 4f.pow(2f * p[TIME] - 1f)
        for (b in 0 until BANDS) {
            attack[b] = FastMath.onePoleCoef(ATTACK_MS[b] * scale / 1000f, sampleRate)
            release[b] = FastMath.onePoleCoef(RELEASE_MS[b] * scale / 1000f, sampleRate)
        }
        trim[0] = p[LOW]
        trim[1] = p[MID]
        trim[2] = p[HIGH]
        out = FastMath.dbToGain(p[OUT])
    }

    override fun process(l: FloatArray, r: FloatArray, frames: Int, ctx: ProcessContext) {
        val s1l = split1[0]
        val s1r = split1[1]
        val s2l = split2[0]
        val s2r = split2[1]
        val al = align[0]
        val ar = align[1]
        for (i in 0 until frames) {
            s1l.process(l[i])
            s1r.process(r[i])
            s2l.process(s1l.high)
            s2r.process(s1r.high)
            al.process(s1l.low)
            ar.process(s1r.low)
            val lowL = al.low + al.high
            val lowR = ar.low + ar.high
            val midL = s2l.low
            val midR = s2r.low
            val highL = s2l.high
            val highR = s2r.high

            follow(0, max(abs(lowL), abs(lowR)))
            follow(1, max(abs(midL), abs(midR)))
            follow(2, max(abs(highL), abs(highR)))
            if (--count <= 0) {
                count = CONTROL
                for (b in 0 until BANDS) target[b] = aim(b)
            }
            val g0 = gain[0] + (target[0] - gain[0]) * smooth
            val g1 = gain[1] + (target[1] - gain[1]) * smooth
            val g2 = gain[2] + (target[2] - gain[2]) * smooth
            gain[0] = g0
            gain[1] = g1
            gain[2] = g2
            l[i] = (lowL * g0 + midL * g1 + highL * g2) * out
            r[i] = (lowR * g0 + midR * g1 + highR * g2) * out
        }
        for (b in 0 until BANDS) if (env[b] < 1e-12f) env[b] = 0f
    }

    private fun follow(band: Int, peak: Float) {
        val e = env[band]
        env[band] = e + (if (peak > e) attack[band] else release[band]) * (peak - e)
    }

    /** The gain a band should have right now, from how loud it is. */
    private fun aim(band: Int): Float {
        val level = 20f * LOG10_E * ln(env[band] + 1e-9f)
        var change = 0f
        if (level > ABOVE) {
            change = (ABOVE - level) * (1f - 1f / RATIO_DOWN)
        } else if (level < BELOW) {
            change = min(MAX_LIFT, (BELOW - level) * (1f - 1f / RATIO_UP))
            // near silence there is nothing worth lifting: only hiss would come up
            if (level < FLOOR) change *= max(0f, 1f - (FLOOR - level) / FLOOR_FADE)
        }
        return FastMath.dbToGain(depth * (change + MAKEUP[band]) + trim[band])
    }

    override fun reset() {
        for (c in 0 until 2) {
            split1[c].reset()
            split2[c].reset()
            align[c].reset()
        }
        for (b in 0 until BANDS) {
            env[b] = 0f
            gain[b] = 1f
            target[b] = 1f
        }
    }

    /**
     * Splits a signal in two at one frequency (Linkwitz-Riley, 24 dB per octave): added back
     * together the two halves give the signal again, at every frequency as loud as it was.
     */
    private class Crossover {
        private val lp1 = Biquad()
        private val lp2 = Biquad()
        private val hp1 = Biquad()
        private val hp2 = Biquad()
        var low = 0f
        var high = 0f

        fun set(freq: Float, sampleRate: Float) {
            lp1.setLowPass(freq, 0.707f, sampleRate)
            lp2.setLowPass(freq, 0.707f, sampleRate)
            hp1.setHighPass(freq, 0.707f, sampleRate)
            hp2.setHighPass(freq, 0.707f, sampleRate)
        }

        fun process(x: Float) {
            low = lp2.process(lp1.process(x))
            high = hp2.process(hp1.process(x))
        }

        fun reset() {
            lp1.reset()
            lp2.reset()
            hp1.reset()
            hp2.reset()
        }
    }

    companion object {
        const val TYPE_ID = "ott"
        const val DEPTH = 0
        const val TIME = 1
        const val LOW = 2
        const val MID = 3
        const val HIGH = 4
        const val OUT = 5

        private const val BANDS = 3
        private const val CONTROL = 16
        private const val LOG10_E = 0.4342945f
        private const val LOW_SPLIT = 120f
        private const val HIGH_SPLIT = 2500f

        /** Above this level a band is pushed down, below the other one it is pulled up (dB). */
        private const val ABOVE = -26f
        private const val BELOW = -38f
        private const val RATIO_DOWN = 12f
        private const val RATIO_UP = 3f
        private const val MAX_LIFT = 18f
        private const val FLOOR = -62f
        private const val FLOOR_FADE = 14f
        private val MAKEUP = floatArrayOf(8f, 6f, 8f)
        private val ATTACK_MS = floatArrayOf(30f, 15f, 8f)
        private val RELEASE_MS = floatArrayOf(250f, 180f, 120f)

        val PARAMS = listOf(
            ParamSpec("depth", R.string.p_depth, 0f, 1f, 0.5f, ParamUnit.PERCENT),
            ParamSpec("time", R.string.p_time, 0f, 1f, 0.5f, ParamUnit.PERCENT),
            ParamSpec("low", R.string.p_low, -12f, 12f, 0f, ParamUnit.DB),
            ParamSpec("mid", R.string.p_mid, -12f, 12f, 0f, ParamUnit.DB),
            ParamSpec("high", R.string.p_high, -12f, 12f, 0f, ParamUnit.DB),
            ParamSpec("out", R.string.p_output, -24f, 12f, 0f, ParamUnit.DB),
        )
        val TYPE = DeviceType(TYPE_ID, R.string.fx_ott, R.string.fx_ott_desc, false, PARAMS) { Ott() }
    }
}

/**
 * Auto-wah: a resonant filter that opens with every note you hit - the harder, the further.
 * The "quack" of funk guitar, clavinet and bass.
 */
class AutoWah : Effect() {
    private val filterL = Svf()
    private val filterR = Svf()

    private var sens = 4f
    private var base = 350f
    private var octaves = 3f
    private var resonance = 0.6f
    private var rise = 0.01f
    private var fall = 0.001f
    private var mix = 1f
    private var level = 1f

    private var env = 0f
    private var count = 0

    override fun prepare(sampleRate: Int) {
        super.prepare(sampleRate)
        rise = FastMath.onePoleCoef(0.004f, this.sampleRate)
        filterL.set(base, resonance, this.sampleRate)
        filterR.set(base, resonance, this.sampleRate)
    }

    override fun setParams(p: FloatArray) {
        // from "only the hardest notes open it" to "a whisper is enough"
        sens = 10f.pow(p[SENS] * 1.6f)
        base = p[FREQ]
        octaves = p[RANGE] * MAX_OCTAVES
        resonance = 0.25f + 0.7f * p[RESO]
        fall = FastMath.onePoleCoef(p[SPEED] / 1000f, sampleRate)
        mix = p[MIX]
        // a sharp peak is loud: keep the overall level where it was
        level = 1f / (1f + 1.2f * p[RESO] * p[RESO])
    }

    override fun process(l: FloatArray, r: FloatArray, frames: Int, ctx: ProcessContext) {
        val dry = 1f - mix
        val wet = mix * level
        val top = sampleRate * 0.4f
        for (i in 0 until frames) {
            val xl = l[i]
            val xr = r[i]
            val peak = max(abs(xl), abs(xr))
            env += (if (peak > env) rise else fall) * (peak - env)
            if (--count <= 0) {
                count = CONTROL
                // quiet playing already moves the filter; loud playing takes it all the way
                val open = sqrt(min(1f, env * sens))
                val cutoff = min(top, base * 2f.pow(octaves * open))
                filterL.set(cutoff, resonance, sampleRate)
                filterR.set(cutoff, resonance, sampleRate)
            }
            filterL.process(xl)
            filterR.process(xr)
            l[i] = xl * dry + filterL.lp * wet
            r[i] = xr * dry + filterR.lp * wet
        }
        if (env < 1e-12f) env = 0f
    }

    override fun reset() {
        filterL.reset()
        filterR.reset()
        env = 0f
    }

    companion object {
        const val TYPE_ID = "wah"
        const val SENS = 0
        const val FREQ = 1
        const val RANGE = 2
        const val RESO = 3
        const val SPEED = 4
        const val MIX = 5
        private const val CONTROL = 16
        private const val MAX_OCTAVES = 4.5f

        val PARAMS = listOf(
            ParamSpec("sens", R.string.p_sensitivity, 0f, 1f, 0.6f, ParamUnit.PERCENT),
            ParamSpec("freq", R.string.p_frequency, 100f, 2000f, 350f, ParamUnit.HZ, ParamCurve.EXP),
            ParamSpec("range", R.string.p_wah_range, 0f, 1f, 0.7f, ParamUnit.PERCENT),
            ParamSpec("reso", R.string.p_resonance, 0f, 1f, 0.6f, ParamUnit.PERCENT),
            ParamSpec("speed", R.string.p_release, 5f, 300f, 40f, ParamUnit.MILLIS, ParamCurve.EXP),
            ParamSpec("mix", R.string.p_mix, 0f, 1f, 1f, ParamUnit.PERCENT),
        )
        val TYPE = DeviceType(TYPE_ID, R.string.fx_wah, R.string.fx_wah_desc, false, PARAMS) { AutoWah() }
    }
}
