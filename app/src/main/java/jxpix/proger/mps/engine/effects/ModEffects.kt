package jxpix.proger.mps.engine.effects

import jxpix.proger.mps.R
import jxpix.proger.mps.engine.DeviceType
import jxpix.proger.mps.engine.Effect
import jxpix.proger.mps.engine.PPQ
import jxpix.proger.mps.engine.ParamCurve
import jxpix.proger.mps.engine.ParamSpec
import jxpix.proger.mps.engine.ParamUnit
import jxpix.proger.mps.engine.ProcessContext
import jxpix.proger.mps.engine.dsp.DENORMAL
import jxpix.proger.mps.engine.dsp.DelayLine
import jxpix.proger.mps.engine.dsp.FastMath
import kotlin.math.exp
import kotlin.math.min

private fun dry(mix: Float) = min(1f, 2f * (1f - mix))
private fun wet(mix: Float) = min(1f, 2f * mix)

/** Flanger: a very short delay swept up and down, fed back into itself - the "jet plane" sweep. */
class Flanger : Effect() {
    private lateinit var lineL: DelayLine
    private lateinit var lineR: DelayLine
    private var rate = 0.25f
    private var depth = 0.7f
    private var base = 2f
    private var feedback = 0.6f
    private var mix = 0.5f
    private var phase = 0f

    override fun prepare(sampleRate: Int) {
        super.prepare(sampleRate)
        val max = (this.sampleRate * 0.03f).toInt()
        lineL = DelayLine(max)
        lineR = DelayLine(max)
    }

    override fun setParams(p: FloatArray) {
        rate = p[RATE]
        depth = p[DEPTH]
        base = p[DELAY]
        feedback = p[FEEDBACK]
        mix = p[MIX]
    }

    override fun process(l: FloatArray, r: FloatArray, frames: Int, ctx: ProcessContext) {
        val inc = rate / sampleRate
        val ms = sampleRate / 1000f
        val d = dry(mix)
        val w = wet(mix)
        // the sweep goes from the base delay down to almost nothing
        val swing = base * depth * 0.48f
        val centre = base - swing
        for (i in 0 until frames) {
            phase += inc
            if (phase >= 1f) phase -= 1f
            val dl = (centre + swing * FastMath.sin01(phase)) * ms + 1f
            val dr = (centre + swing * FastMath.sin01(phase + 0.25f)) * ms + 1f
            val yl = lineL.readFrac(dl)
            val yr = lineR.readFrac(dr)
            lineL.write(l[i] + yl * feedback + DENORMAL)
            lineR.write(r[i] + yr * feedback + DENORMAL)
            l[i] = l[i] * d + yl * w
            r[i] = r[i] * d + yr * w
        }
    }

    override fun reset() {
        lineL.clear(); lineR.clear()
    }

    companion object {
        const val TYPE_ID = "flanger"
        const val RATE = 0
        const val DEPTH = 1
        const val DELAY = 2
        const val FEEDBACK = 3
        const val MIX = 4

        val PARAMS = listOf(
            ParamSpec("rate", R.string.p_rate, 0.02f, 5f, 0.25f, ParamUnit.HZ, ParamCurve.EXP),
            ParamSpec("depth", R.string.p_depth, 0f, 1f, 0.7f, ParamUnit.PERCENT),
            ParamSpec("delay", R.string.p_delay, 0.5f, 8f, 2.5f, ParamUnit.MILLIS),
            ParamSpec("feedback", R.string.p_resonance, 0f, 0.95f, 0.6f, ParamUnit.PERCENT),
            ParamSpec("mix", R.string.p_mix, 0f, 1f, 0.5f, ParamUnit.PERCENT),
        )
        val TYPE = DeviceType(TYPE_ID, R.string.fx_flanger, R.string.fx_flanger_desc, false, PARAMS) { Flanger() }
    }
}

/**
 * Tremolo and auto-pan: the volume moves up and down in a steady rhythm - free or locked to
 * the tempo. With the two sides moving against each other the sound travels left and right.
 */
class Tremolo : Effect() {
    private var sync = 0
    private var rate = 5f
    private var depth = 0.6f
    private var shape = 0f
    private var stereo = 0f
    private var phase = 0.0
    private var gl = 1f
    private var gr = 1f
    private var smooth = 0.01f

    override fun prepare(sampleRate: Int) {
        super.prepare(sampleRate)
        // even the square shape gets a 2 ms edge, so that it chops without clicking
        smooth = 1f - exp(-1f / (0.002f * this.sampleRate))
    }

    override fun setParams(p: FloatArray) {
        sync = p[SYNC].toInt()
        rate = p[RATE]
        depth = p[DEPTH]
        shape = p[SHAPE]
        stereo = p[STEREO]
    }

    /** 0..1 volume curve for a position in the cycle: a sine that [shape] squares up. */
    private fun curve(position: Float): Float {
        val s = FastMath.sin01(position)
        // steeper and steeper slope through the middle, clipped: sine -> square
        val k = 1f + shape * shape * 40f
        val x = s * k
        return 0.5f + 0.5f * (if (x > 1f) 1f else if (x < -1f) -1f else x)
    }

    override fun process(l: FloatArray, r: FloatArray, frames: Int, ctx: ProcessContext) {
        var inc = rate.toDouble() / sampleRate
        if (sync > 0) {
            val periodTicks = SYNC_TICKS[sync.coerceIn(1, SYNC_TICKS.size - 1)].toDouble()
            inc = ctx.ticksPerFrame / periodTicks
            // while the song runs the movement follows its grid, so it is the same on every pass
            if (ctx.playing && ctx.tickPos >= 0.0) phase = (ctx.tickPos % periodTicks) / periodTicks
        }
        val offset = stereo * 0.5f
        val floor = 1f - depth
        // A smooth tremolo is loudest right on the beat; a square chop opens on the beat and
        // stays open for the first half of the cycle. Either way a note on the beat is heard.
        val lead = 0.25f * (1f - shape)
        for (i in 0 until frames) {
            val p = phase.toFloat() + lead
            val tl = floor + depth * curve(p)
            val tr = floor + depth * curve(p + offset)
            gl += (tl - gl) * smooth
            gr += (tr - gr) * smooth
            l[i] *= gl
            r[i] *= gr
            phase += inc
            if (phase >= 1.0) phase -= 1.0
        }
    }

    override fun reset() {
        phase = 0.0
        gl = 1f
        gr = 1f
    }

    companion object {
        const val TYPE_ID = "tremolo"
        const val SYNC = 0
        const val RATE = 1
        const val DEPTH = 2
        const val SHAPE = 3
        const val STEREO = 4

        /** Length of one cycle for each choice of [SYNC]: free, 1/2, 1/4, 1/8, 1/8T, 1/16. */
        private val SYNC_TICKS = intArrayOf(0, PPQ * 2, PPQ, PPQ / 2, PPQ / 3, PPQ / 4)

        val PARAMS = listOf(
            ParamSpec("sync", R.string.p_sync, 0f, 5f, 3f, choicesRes = R.array.arr_trem_sync),
            ParamSpec("rate", R.string.p_rate, 0.1f, 20f, 5f, ParamUnit.HZ, ParamCurve.EXP),
            ParamSpec("depth", R.string.p_depth, 0f, 1f, 0.6f, ParamUnit.PERCENT),
            ParamSpec("shape", R.string.p_shape, 0f, 1f, 0f, ParamUnit.PERCENT),
            ParamSpec("stereo", R.string.p_stereo, 0f, 1f, 0f, ParamUnit.PERCENT),
        )
        val TYPE = DeviceType(TYPE_ID, R.string.fx_tremolo, R.string.fx_tremolo_desc, false, PARAMS) { Tremolo() }
    }
}

/**
 * Stereo tool: makes a sound wider or narrower, spreads a mono sound across the speakers
 * with a tiny delay on one side, and keeps the bass in the middle where it belongs.
 */
class StereoWidth : Effect() {
    private lateinit var line: DelayLine
    private var width = 1.3f
    private var haas = 0f
    private var monoBelow = 120f
    private var lowSide = 0f
    private var coef = 0f

    override fun prepare(sampleRate: Int) {
        super.prepare(sampleRate)
        line = DelayLine((this.sampleRate * 0.03f).toInt())
    }

    override fun setParams(p: FloatArray) {
        width = p[WIDTH]
        haas = p[HAAS]
        monoBelow = p[MONO_BELOW]
        coef = 1f - exp(-FastMath.TWO_PI * monoBelow / sampleRate)
    }

    override fun process(l: FloatArray, r: FloatArray, frames: Int, ctx: ProcessContext) {
        val delay = haas * sampleRate / 1000f
        val useDelay = haas > 0.05f
        val keepBass = monoBelow > 21f
        for (i in 0 until frames) {
            val xl = l[i]
            var xr = r[i]
            // written even while the delay is off, so that switching it on never plays back stale sound
            line.write(xr)
            // the right side a few milliseconds late: the ear hears width, not an echo
            if (useDelay) xr = line.readFrac(delay + 1f)
            val mid = (xl + xr) * 0.5f
            var side = (xl - xr) * 0.5f
            if (keepBass) {
                // what the two sides disagree about below the chosen frequency is dropped
                lowSide += (side - lowSide) * coef + DENORMAL
                side -= lowSide
            }
            side *= width
            l[i] = mid + side
            r[i] = mid - side
        }
    }

    override fun reset() {
        line.clear()
        lowSide = 0f
    }

    companion object {
        const val TYPE_ID = "stereo"
        const val WIDTH = 0
        const val HAAS = 1
        const val MONO_BELOW = 2

        val PARAMS = listOf(
            ParamSpec("width", R.string.p_width, 0f, 2f, 1.3f, ParamUnit.PERCENT),
            ParamSpec("haas", R.string.p_haas, 0f, 25f, 0f, ParamUnit.MILLIS),
            ParamSpec("monoBelow", R.string.p_mono_below, 20f, 400f, 120f, ParamUnit.HZ, ParamCurve.EXP),
        )
        val TYPE = DeviceType(TYPE_ID, R.string.fx_stereo, R.string.fx_stereo_desc, false, PARAMS) { StereoWidth() }
    }
}

/**
 * Tape: the gentle pitch drift of a worn cassette, soft saturation and a rolled-off top.
 * The quickest way to make a clean sound warm and a little nostalgic.
 */
class Tape : Effect() {
    private lateinit var lineL: DelayLine
    private lateinit var lineR: DelayLine
    private var wow = 0.3f
    private var flutter = 0.2f
    private var drive = 0.3f
    private var tone = 0.6f
    private var mix = 1f
    private var wowPhase = 0f
    private var flutterPhase = 0f
    private var lpL = 0f
    private var lpR = 0f

    override fun prepare(sampleRate: Int) {
        super.prepare(sampleRate)
        val max = (this.sampleRate * 0.04f).toInt()
        lineL = DelayLine(max)
        lineR = DelayLine(max)
    }

    override fun setParams(p: FloatArray) {
        wow = p[WOW]
        flutter = p[FLUTTER]
        drive = p[DRIVE]
        tone = p[TONE]
        mix = p[MIX]
    }

    override fun process(l: FloatArray, r: FloatArray, frames: Int, ctx: ProcessContext) {
        val ms = sampleRate / 1000f
        val wowInc = WOW_HZ / sampleRate
        val flutterInc = FLUTTER_HZ / sampleRate
        val wowDepth = wow * wow * 3.5f * ms
        val flutterDepth = flutter * flutter * 0.35f * ms
        val base = 6f * ms
        // 1.5 kHz (old cassette) .. 18 kHz (fresh tape)
        val cutoff = 1500f * Math.pow(12.0, tone.toDouble()).toFloat()
        val a = 1f - exp(-FastMath.TWO_PI * min(cutoff, sampleRate * 0.45f) / sampleRate)
        val gain = 1f + drive * 5f
        // a signal at half scale keeps its level: quieter ones come up a little, louder ones are squeezed
        val norm = 1f / FastMath.tanh(gain * 0.5f) * 0.5f
        val d = dry(mix)
        val w = wet(mix)
        for (i in 0 until frames) {
            wowPhase += wowInc
            if (wowPhase >= 1f) wowPhase -= 1f
            flutterPhase += flutterInc
            if (flutterPhase >= 1f) flutterPhase -= 1f
            lineL.write(l[i])
            lineR.write(r[i])
            val delay = base + wowDepth * FastMath.sin01(wowPhase) + flutterDepth * FastMath.sin01(flutterPhase)
            var yl = lineL.readFrac(delay + 1f)
            // the right side is read a touch differently, as two tracks of one tape are
            var yr = lineR.readFrac(delay + 1f + flutterDepth * 0.3f * FastMath.sin01(flutterPhase + 0.3f))
            yl = FastMath.tanh(yl * gain) * norm
            yr = FastMath.tanh(yr * gain) * norm
            lpL += (yl - lpL) * a + DENORMAL
            lpR += (yr - lpR) * a + DENORMAL
            l[i] = l[i] * d + lpL * w
            r[i] = r[i] * d + lpR * w
        }
    }

    override fun reset() {
        lineL.clear(); lineR.clear()
        lpL = 0f; lpR = 0f
    }

    companion object {
        const val TYPE_ID = "tape"
        const val WOW = 0
        const val FLUTTER = 1
        const val DRIVE = 2
        const val TONE = 3
        const val MIX = 4
        private const val WOW_HZ = 0.55f
        private const val FLUTTER_HZ = 6.3f

        val PARAMS = listOf(
            ParamSpec("wow", R.string.p_wow, 0f, 1f, 0.3f, ParamUnit.PERCENT),
            ParamSpec("flutter", R.string.p_flutter, 0f, 1f, 0.2f, ParamUnit.PERCENT),
            ParamSpec("drive", R.string.p_drive, 0f, 1f, 0.3f, ParamUnit.PERCENT),
            ParamSpec("tone", R.string.p_tone, 0f, 1f, 0.6f, ParamUnit.PERCENT),
            ParamSpec("mix", R.string.p_mix, 0f, 1f, 1f, ParamUnit.PERCENT),
        )
        val TYPE = DeviceType(TYPE_ID, R.string.fx_tape, R.string.fx_tape_desc, false, PARAMS) { Tape() }
    }
}
