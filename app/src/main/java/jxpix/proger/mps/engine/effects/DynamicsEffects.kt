package jxpix.proger.mps.engine.effects

import jxpix.proger.mps.R
import jxpix.proger.mps.engine.DeviceType
import jxpix.proger.mps.engine.Effect
import jxpix.proger.mps.engine.PPQ
import jxpix.proger.mps.engine.ParamCurve
import jxpix.proger.mps.engine.ParamSpec
import jxpix.proger.mps.engine.ParamUnit
import jxpix.proger.mps.engine.ProcessContext
import jxpix.proger.mps.engine.dsp.FastMath
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max

/** Feed-forward stereo compressor. */
class Compressor : Effect() {
    private var threshold = 0.125f
    private var slope = 0.75f
    private var attack = 0.01f
    private var release = 0.001f
    private var makeup = 1f
    private var mix = 1f
    private var env = 0f

    /** Current gain reduction in dB (positive), readable from any thread for metering. */
    @Volatile
    var reductionDb = 0f
        private set

    override fun setParams(p: FloatArray) {
        threshold = FastMath.dbToGain(p[THRESHOLD])
        slope = 1f - 1f / p[RATIO]
        attack = FastMath.onePoleCoef(p[ATTACK] / 1000f, sampleRate)
        release = FastMath.onePoleCoef(p[RELEASE] / 1000f, sampleRate)
        makeup = FastMath.dbToGain(p[MAKEUP])
        mix = p[MIX]
    }

    override fun process(l: FloatArray, r: FloatArray, frames: Int, ctx: ProcessContext) {
        val dry = 1f - mix
        val wet = mix * makeup
        var minGain = 1f
        for (i in 0 until frames) {
            val xl = l[i]
            val xr = r[i]
            val peak = max(abs(xl), abs(xr))
            env += (if (peak > env) attack else release) * (peak - env)
            var g = 1f
            if (env > threshold) {
                g = exp(-slope * ln(env / threshold))
                if (g < minGain) minGain = g
            }
            l[i] = xl * dry + xl * g * wet
            r[i] = xr * dry + xr * g * wet
        }
        if (env < 1e-12f) env = 0f
        reductionDb = -FastMath.gainToDb(minGain)
        scope[0] = reductionDb
    }

    override fun reset() {
        env = 0f
    }

    companion object {
        const val TYPE_ID = "comp"
        const val THRESHOLD = 0
        const val RATIO = 1
        const val ATTACK = 2
        const val RELEASE = 3
        const val MAKEUP = 4
        const val MIX = 5

        val PARAMS = listOf(
            ParamSpec("threshold", R.string.p_threshold, -60f, 0f, -18f, ParamUnit.DB),
            ParamSpec("ratio", R.string.p_ratio, 1f, 20f, 4f, ParamUnit.RATIO, ParamCurve.EXP),
            ParamSpec("attack", R.string.p_attack, 0.1f, 200f, 10f, ParamUnit.MILLIS, ParamCurve.EXP),
            ParamSpec("release", R.string.p_release, 10f, 1000f, 120f, ParamUnit.MILLIS, ParamCurve.EXP),
            ParamSpec("makeup", R.string.p_makeup, 0f, 24f, 0f, ParamUnit.DB),
            ParamSpec("mix", R.string.p_mix, 0f, 1f, 1f, ParamUnit.PERCENT),
        )
        val TYPE = DeviceType(TYPE_ID, R.string.fx_comp, R.string.fx_comp_desc, false, PARAMS) { Compressor() }
    }
}

/**
 * Look-ahead peak limiter. The signal is delayed by 1.5 ms so that the gain can be turned
 * down smoothly *before* a peak arrives - loud material gets louder without crackling.
 * Also used (with fixed settings) as the always-on safety limiter of the master bus.
 */
class Limiter : Effect() {
    private var gainIn = 1f
    private var ceiling = 0.966f
    private var release = 0.0002f
    private var attack = 0.1f

    private var lookahead = 72
    private var delayL = FloatArray(72)
    private var delayR = FloatArray(72)
    private var w = 0
    private var env = 1f
    private var hold = 0
    private var g = 1f

    /** Lowest gain applied during the last block (1 = the limiter did nothing). */
    @Volatile
    var minGain = 1f
        private set

    override fun prepare(sampleRate: Int) {
        super.prepare(sampleRate)
        lookahead = (this.sampleRate * 0.0015f).toInt().coerceAtLeast(8)
        delayL = FloatArray(lookahead)
        delayR = FloatArray(lookahead)
        w = 0
        // the gain settles (98 %) within the look-ahead time
        attack = 1f - exp(-4f / lookahead)
    }

    override fun setParams(p: FloatArray) {
        configure(p[GAIN], p[CEILING], p[RELEASE])
    }

    fun configure(gainDb: Float, ceilingDb: Float, releaseMs: Float) {
        gainIn = FastMath.dbToGain(gainDb)
        ceiling = FastMath.dbToGain(ceilingDb)
        release = FastMath.onePoleCoef(releaseMs / 1000f, sampleRate)
    }

    override fun process(l: FloatArray, r: FloatArray, frames: Int, ctx: ProcessContext) {
        process(l, r, frames)
    }

    fun process(l: FloatArray, r: FloatArray, frames: Int) {
        val ceil = ceiling
        val pre = gainIn
        val n = lookahead
        var env = env
        var g = g
        var hold = hold
        var w = w
        var lowest = 1f
        for (i in 0 until frames) {
            val xl = l[i] * pre
            val xr = r[i] * pre
            val peak = max(abs(xl), abs(xr))
            val need = if (peak > ceil) ceil / peak else 1f
            if (need <= env) {
                env = need
                hold = n
            } else if (hold > 0) {
                hold--
            } else {
                env += (need - env) * release
            }
            g += (env - g) * attack
            if (g < lowest) lowest = g

            var yl = delayL[w] * g
            var yr = delayR[w] * g
            delayL[w] = xl
            delayR[w] = xr
            if (++w == n) w = 0
            // the smoothed gain can miss the target by a hair: clip what is left
            if (yl > ceil) yl = ceil else if (yl < -ceil) yl = -ceil
            if (yr > ceil) yr = ceil else if (yr < -ceil) yr = -ceil
            l[i] = yl
            r[i] = yr
        }
        this.env = env
        this.g = g
        this.hold = hold
        this.w = w
        minGain = lowest
        scope[0] = -FastMath.gainToDb(lowest)
    }

    override fun reset() {
        delayL.fill(0f)
        delayR.fill(0f)
        env = 1f
        g = 1f
        hold = 0
    }

    companion object {
        const val TYPE_ID = "limiter"
        const val GAIN = 0
        const val CEILING = 1
        const val RELEASE = 2

        val PARAMS = listOf(
            ParamSpec("gain", R.string.p_gain, 0f, 24f, 0f, ParamUnit.DB),
            ParamSpec("ceiling", R.string.p_ceiling, -12f, 0f, -0.3f, ParamUnit.DB),
            ParamSpec("release", R.string.p_release, 10f, 1000f, 120f, ParamUnit.MILLIS, ParamCurve.EXP),
        )
        val TYPE = DeviceType(TYPE_ID, R.string.fx_limiter, R.string.fx_limiter_desc, false, PARAMS) { Limiter() }
    }
}

/**
 * Tempo-synced volume ducking ("sidechain pump") - the classic breathing effect of
 * dance music, without needing an actual sidechain signal.
 */
class Pumper : Effect() {
    private var periodTicks = PPQ.toDouble()
    private var depth = 0.7f
    private var recover = 0.6f
    private var current = 1f
    private var smooth = 0.01f

    override fun prepare(sampleRate: Int) {
        super.prepare(sampleRate)
        smooth = FastMath.onePoleCoef(0.003f, this.sampleRate)
    }

    override fun setParams(p: FloatArray) {
        periodTicks = when (p[RATE].toInt()) {
            0 -> PPQ * 2.0
            1 -> PPQ.toDouble()
            2 -> PPQ / 2.0
            else -> PPQ / 4.0
        }
        depth = p[DEPTH]
        recover = p[RECOVER].coerceAtLeast(0.02f)
    }

    override fun process(l: FloatArray, r: FloatArray, frames: Int, ctx: ProcessContext) {
        val active = ctx.playing && ctx.tickPos >= 0.0
        var phase = 0.0
        var inc = 0.0
        if (active) {
            phase = (ctx.tickPos % periodTicks) / periodTicks
            inc = ctx.ticksPerFrame / periodTicks
        }
        var cur = current
        val invRecover = 1f / recover
        for (i in 0 until frames) {
            var target = 1f
            if (active) {
                val x = (phase.toFloat() * invRecover).coerceAtMost(1f)
                val k = 1f - x
                target = 1f - depth * k * k
                phase += inc
                if (phase >= 1.0) phase -= 1.0
            }
            cur += (target - cur) * smooth
            l[i] *= cur
            r[i] *= cur
        }
        current = cur
    }

    override fun reset() {
        current = 1f
    }

    companion object {
        const val TYPE_ID = "pump"
        const val RATE = 0
        const val DEPTH = 1
        const val RECOVER = 2

        val PARAMS = listOf(
            ParamSpec("rate", R.string.p_rate, 0f, 3f, 1f, choicesRes = R.array.arr_pump_rates),
            ParamSpec("depth", R.string.p_depth, 0f, 1f, 0.7f, ParamUnit.PERCENT),
            ParamSpec("recover", R.string.p_recover, 0.05f, 1f, 0.6f, ParamUnit.PERCENT),
        )
        val TYPE = DeviceType(TYPE_ID, R.string.fx_pump, R.string.fx_pump_desc, false, PARAMS) { Pumper() }
    }
}
