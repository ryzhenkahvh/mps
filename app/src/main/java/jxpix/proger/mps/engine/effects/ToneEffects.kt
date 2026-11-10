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
import jxpix.proger.mps.engine.dsp.OnePole
import jxpix.proger.mps.engine.dsp.Svf
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.pow

/** Three band equalizer: low shelf, sweepable mid bell, high shelf. */
class Eq3 : Effect() {
    private val lowL = Biquad()
    private val lowR = Biquad()
    private val midL = Biquad()
    private val midR = Biquad()
    private val highL = Biquad()
    private val highR = Biquad()
    private val p = FloatArray(PARAMS.size)
    private var dirty = true

    override fun setParams(p: FloatArray) {
        System.arraycopy(p, 0, this.p, 0, this.p.size)
        dirty = true
    }

    override fun process(l: FloatArray, r: FloatArray, frames: Int, ctx: ProcessContext) {
        if (dirty) {
            dirty = false
            lowL.setLowShelf(p[LOW_FREQ], p[LOW_GAIN], sampleRate)
            lowR.setLowShelf(p[LOW_FREQ], p[LOW_GAIN], sampleRate)
            midL.setPeaking(p[MID_FREQ], p[MID_Q], p[MID_GAIN], sampleRate)
            midR.setPeaking(p[MID_FREQ], p[MID_Q], p[MID_GAIN], sampleRate)
            highL.setHighShelf(p[HIGH_FREQ], p[HIGH_GAIN], sampleRate)
            highR.setHighShelf(p[HIGH_FREQ], p[HIGH_GAIN], sampleRate)
        }
        for (i in 0 until frames) {
            l[i] = highL.process(midL.process(lowL.process(l[i])))
            r[i] = highR.process(midR.process(lowR.process(r[i])))
        }
    }

    override fun reset() {
        lowL.reset(); lowR.reset(); midL.reset(); midR.reset(); highL.reset(); highR.reset()
    }

    companion object {
        const val TYPE_ID = "eq"
        const val LOW_GAIN = 0
        const val LOW_FREQ = 1
        const val MID_GAIN = 2
        const val MID_FREQ = 3
        const val MID_Q = 4
        const val HIGH_GAIN = 5
        const val HIGH_FREQ = 6

        val PARAMS = listOf(
            ParamSpec("lowGain", R.string.p_low, -18f, 18f, 0f, ParamUnit.DB),
            ParamSpec("lowFreq", R.string.p_low_freq, 40f, 600f, 120f, ParamUnit.HZ, ParamCurve.EXP),
            ParamSpec("midGain", R.string.p_mid, -18f, 18f, 0f, ParamUnit.DB),
            ParamSpec("midFreq", R.string.p_mid_freq, 200f, 8000f, 1000f, ParamUnit.HZ, ParamCurve.EXP),
            ParamSpec("midQ", R.string.p_width, 0.3f, 6f, 0.9f, ParamUnit.NONE, ParamCurve.EXP),
            ParamSpec("highGain", R.string.p_high, -18f, 18f, 0f, ParamUnit.DB),
            ParamSpec("highFreq", R.string.p_high_freq, 1500f, 16000f, 6000f, ParamUnit.HZ, ParamCurve.EXP),
        )
        val TYPE = DeviceType(TYPE_ID, R.string.fx_eq, R.string.fx_eq_desc, false, PARAMS) { Eq3() }
    }
}

/** Resonant multimode filter with an optional LFO sweep (auto-filter / wah). */
class FilterFx : Effect() {
    private val fl = Svf()
    private val fr = Svf()
    private var type = 0
    private var cutoff = 2000f
    private var resonance = 0.3f
    private var lfoRate = 1f
    private var lfoDepth = 0f
    private var current = 2000f
    private var lfoPhase = 0f
    private var counter = 0

    override fun setParams(p: FloatArray) {
        type = p[TYPE_P].toInt()
        cutoff = p[CUTOFF]
        resonance = p[RESO]
        lfoRate = p[LFO_RATE]
        lfoDepth = p[LFO_DEPTH]
    }

    override fun process(l: FloatArray, r: FloatArray, frames: Int, ctx: ProcessContext) {
        val type = type
        for (i in 0 until frames) {
            if (counter <= 0) {
                counter = CTRL
                lfoPhase += lfoRate * CTRL / sampleRate
                if (lfoPhase >= 1f) lfoPhase -= floor(lfoPhase)
                val target = cutoff * 2f.pow(FastMath.sin01(lfoPhase) * lfoDepth * 3f)
                current += (target - current) * 0.35f
                fl.set(current, resonance, sampleRate)
                fr.set(current, resonance, sampleRate)
            }
            counter--
            fl.process(l[i])
            fr.process(r[i])
            when (type) {
                0 -> { l[i] = fl.lp; r[i] = fr.lp }
                1 -> { l[i] = fl.hp; r[i] = fr.hp }
                2 -> { l[i] = fl.bp; r[i] = fr.bp }
                else -> { l[i] = fl.lp + fl.hp; r[i] = fr.lp + fr.hp }
            }
        }
    }

    override fun reset() {
        fl.reset(); fr.reset()
    }

    companion object {
        const val TYPE_ID = "filter"
        private const val CTRL = 16
        const val TYPE_P = 0
        const val CUTOFF = 1
        const val RESO = 2
        const val LFO_RATE = 3
        const val LFO_DEPTH = 4

        val PARAMS = listOf(
            ParamSpec("type", R.string.p_type, 0f, 3f, 0f, choicesRes = R.array.arr_filter_types),
            ParamSpec("cutoff", R.string.p_cutoff, 30f, 20000f, 2000f, ParamUnit.HZ, ParamCurve.EXP),
            ParamSpec("resonance", R.string.p_resonance, 0f, 1f, 0.3f, ParamUnit.PERCENT),
            ParamSpec("lfoRate", R.string.p_lfo_rate, 0.05f, 20f, 1f, ParamUnit.HZ, ParamCurve.EXP),
            ParamSpec("lfoDepth", R.string.p_lfo_depth, 0f, 1f, 0f, ParamUnit.PERCENT),
        )
        val TYPE = DeviceType(TYPE_ID, R.string.fx_filter, R.string.fx_filter_desc, false, PARAMS) { FilterFx() }
    }
}

/** Overdrive / distortion with four characters and a tone control. */
class Distortion : Effect() {
    private var type = 0
    private var pre = 1f
    private var post = 1f
    private var mix = 1f
    private val toneL = OnePole()
    private val toneR = OnePole()
    private val dcL = OnePole()
    private val dcR = OnePole()

    override fun prepare(sampleRate: Int) {
        super.prepare(sampleRate)
        dcL.setCutoff(20f, this.sampleRate)
        dcR.setCutoff(20f, this.sampleRate)
    }

    override fun setParams(p: FloatArray) {
        type = p[TYPE_P].toInt()
        val drive = p[DRIVE]
        pre = FastMath.dbToGain(drive * 36f)
        post = FastMath.dbToGain(p[LEVEL]) / (1f + 2f * drive)
        mix = p[MIX]
        val cutoff = 800f * (20000f / 800f).pow(p[TONE])
        toneL.setCutoff(min(cutoff, sampleRate * 0.45f), sampleRate)
        toneR.setCutoff(min(cutoff, sampleRate * 0.45f), sampleRate)
    }

    private fun shape(x: Float): Float = when (type) {
        0 -> FastMath.tanh(x)
        1 -> x.coerceIn(-1f, 1f)
        2 -> FastMath.sin01(x.coerceIn(-200f, 200f) * 0.25f + 64f)
        else -> if (x > 0f) FastMath.tanh(x * 1.5f) else FastMath.tanh(x * 0.6f) * 0.8f
    }

    override fun process(l: FloatArray, r: FloatArray, frames: Int, ctx: ProcessContext) {
        val dry = 1f - mix
        val wet = mix * post
        for (i in 0 until frames) {
            val xl = l[i]
            val xr = r[i]
            val yl = toneL.lp(dcL.hp(shape(xl * pre)))
            val yr = toneR.lp(dcR.hp(shape(xr * pre)))
            l[i] = xl * dry + yl * wet
            r[i] = xr * dry + yr * wet
        }
    }

    override fun reset() {
        toneL.reset(); toneR.reset(); dcL.reset(); dcR.reset()
    }

    companion object {
        const val TYPE_ID = "dist"
        const val TYPE_P = 0
        const val DRIVE = 1
        const val TONE = 2
        const val LEVEL = 3
        const val MIX = 4

        val PARAMS = listOf(
            ParamSpec("type", R.string.p_type, 0f, 3f, 0f, choicesRes = R.array.arr_dist_types),
            ParamSpec("drive", R.string.p_drive, 0f, 1f, 0.4f, ParamUnit.PERCENT),
            ParamSpec("tone", R.string.p_tone, 0f, 1f, 0.7f, ParamUnit.PERCENT),
            ParamSpec("level", R.string.p_output, -24f, 6f, 0f, ParamUnit.DB),
            ParamSpec("mix", R.string.p_mix, 0f, 1f, 1f, ParamUnit.PERCENT),
        )
        val TYPE = DeviceType(TYPE_ID, R.string.fx_dist, R.string.fx_dist_desc, false, PARAMS) { Distortion() }
    }
}

/** Lo-fi effect: reduces bit depth and sample rate. */
class BitCrusher : Effect() {
    private var levels = 128f
    private var factor = 4f
    private var mix = 1f
    private var counter = 0f
    private var holdL = 0f
    private var holdR = 0f

    override fun setParams(p: FloatArray) {
        levels = 2f.pow(p[BITS] - 1f)
        factor = p[RATE]
        mix = p[MIX]
    }

    override fun process(l: FloatArray, r: FloatArray, frames: Int, ctx: ProcessContext) {
        val dry = 1f - mix
        val inv = 1f / levels
        for (i in 0 until frames) {
            counter += 1f
            if (counter >= factor) {
                counter -= factor
                holdL = floor(l[i] * levels + 0.5f) * inv
                holdR = floor(r[i] * levels + 0.5f) * inv
            }
            l[i] = l[i] * dry + holdL * mix
            r[i] = r[i] * dry + holdR * mix
        }
    }

    companion object {
        const val TYPE_ID = "crush"
        const val BITS = 0
        const val RATE = 1
        const val MIX = 2

        val PARAMS = listOf(
            ParamSpec("bits", R.string.p_bits, 2f, 16f, 8f, ParamUnit.NONE),
            ParamSpec("rate", R.string.p_downsample, 1f, 40f, 4f, ParamUnit.RATIO, ParamCurve.EXP),
            ParamSpec("mix", R.string.p_mix, 0f, 1f, 1f, ParamUnit.PERCENT),
        )
        val TYPE = DeviceType(TYPE_ID, R.string.fx_crush, R.string.fx_crush_desc, false, PARAMS) { BitCrusher() }
    }
}
