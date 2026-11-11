package jxpix.proger.mps.engine.instruments

import jxpix.proger.mps.R
import jxpix.proger.mps.engine.DeviceType
import jxpix.proger.mps.engine.Instrument
import jxpix.proger.mps.engine.ParamCurve
import jxpix.proger.mps.engine.ParamSpec
import jxpix.proger.mps.engine.ParamUnit
import jxpix.proger.mps.engine.dsp.Adsr
import jxpix.proger.mps.engine.dsp.FastMath
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * FM synthesizer: one sine wave bends the pitch of another so fast that new overtones appear.
 * Good at what the subtractive synth cannot do - electric pianos, bells, glassy keys, metallic basses.
 *
 * Every voice is a pair of two-operator stacks, slightly detuned and panned apart.
 */
class FmSynth : Instrument() {
    private val voices = Array(MAX_VOICES) { Voice() }
    private var counter = 0L

    private var ratio = 2f
    private var amount = 0.4f
    private var feedback = 0f
    private var brightDecay = 0.4f
    private var brightHold = 0.3f
    private var detune = 6f
    private var octave = 0f
    private var velSens = 0.6f
    private var level = 0.8f
    private var modCoef = 0f

    override fun setParams(p: FloatArray) {
        ratio = p[P_RATIO]
        amount = p[P_AMOUNT]
        feedback = p[P_FEEDBACK]
        brightDecay = p[P_BRIGHT_DECAY]
        brightHold = p[P_BRIGHT_HOLD]
        detune = p[P_DETUNE]
        octave = p[P_OCTAVE]
        velSens = p[P_VELOCITY]
        level = p[P_LEVEL]
        modCoef = exp(-1f / (brightDecay * sampleRate))
        for (v in voices) v.env.set(p[P_ATTACK], p[P_DECAY], p[P_SUSTAIN], p[P_RELEASE], sampleRate)
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
        val freq = FastMath.noteToFreq(pitch + octave * 12f)
        val spread = 2f.pow(detune / 2400f)
        v.incL = freq / spread / sampleRate
        v.incR = freq * spread / sampleRate
        v.pitch = pitch
        v.order = ++counter
        v.gate = true
        v.mod = 1f
        // a hard hit is louder and brighter, like on a real instrument
        val touch = (1f - velSens) + velSens * velocity
        v.gain = touch * touch
        v.bright = 0.4f + 0.6f * touch
        // High notes get less modulation: the overtones it makes have to stay below what the sample
        // rate can carry, otherwise they fold back as harsh, out-of-tune noise.
        val highest = 0.45f * sampleRate / (freq * spread)
        val room = (highest - 1f) / (max(0.5f, ratio) * (1f + 2f * feedback)) - 1f
        v.maxIndex = max(0f, room) / FastMath.TWO_PI
        if (!v.on) {
            v.on = true
            v.pcL = 0f; v.pcR = 0f; v.pmL = 0f; v.pmR = 0f
            v.fbL = 0f; v.fbR = 0f
            v.env.kill()
        }
        v.env.gateOn()
    }

    override fun noteOff(pitch: Int) {
        for (v in voices) if (v.on && v.gate && v.pitch == pitch) {
            v.gate = false
            v.env.gateOff()
        }
    }

    override fun allNotesOff() {
        for (v in voices) if (v.on && v.gate) {
            v.gate = false
            v.env.gateOff()
        }
    }

    override fun reset() {
        for (v in voices) {
            v.on = false
            v.env.kill()
        }
    }

    override fun render(outL: FloatArray, outR: FloatArray, offset: Int, count: Int) {
        // modulation depth in cycles: the knob is squared so that the mellow end has room
        val depth = amount * amount * MAX_INDEX
        val fb = feedback * 0.3f
        val out = level * 0.4f
        val hold = brightHold
        val coef = modCoef
        val r = ratio
        for (v in voices) {
            if (!v.on) continue
            val env = v.env
            var pcL = v.pcL
            var pcR = v.pcR
            var pmL = v.pmL
            var pmR = v.pmR
            var fbL = v.fbL
            var fbR = v.fbR
            var mod = v.mod
            val incL = v.incL
            val incR = v.incR
            val g = v.gain * out
            val bright = min(depth * v.bright, v.maxIndex)
            for (i in offset until offset + count) {
                val a = env.next()
                // the brightness falls from full to its hold level, the way a struck tine rings out
                mod = hold + (mod - hold) * coef
                val index = bright * mod
                val mL = FastMath.sin01(pmL + fbL * fb + PHASE_BIAS)
                val mR = FastMath.sin01(pmR + fbR * fb + PHASE_BIAS)
                fbL = (fbL + mL) * 0.5f
                fbR = (fbR + mR) * 0.5f
                outL[i] += FastMath.sin01(pcL + mL * index + PHASE_BIAS) * a * g
                outR[i] += FastMath.sin01(pcR + mR * index + PHASE_BIAS) * a * g
                pcL += incL
                if (pcL >= 1f) pcL -= 1f
                pcR += incR
                if (pcR >= 1f) pcR -= 1f
                pmL += incL * r
                if (pmL >= 1f) pmL -= pmL.toInt()
                pmR += incR * r
                if (pmR >= 1f) pmR -= pmR.toInt()
            }
            v.pcL = pcL; v.pcR = pcR; v.pmL = pmL; v.pmR = pmR
            v.fbL = fbL; v.fbR = fbR
            v.mod = mod
            if (env.isIdle) v.on = false
        }
    }

    private class Voice {
        val env = Adsr()
        var on = false
        var gate = false
        var pitch = 0
        var order = 0L
        var incL = 0f
        var incR = 0f
        var pcL = 0f
        var pcR = 0f
        var pmL = 0f
        var pmR = 0f
        var fbL = 0f
        var fbR = 0f
        var mod = 0f
        var gain = 0f
        var bright = 1f

        /** The most modulation this note can take without aliasing, in cycles of phase. */
        var maxIndex = 1f
    }

    companion object {
        const val TYPE_ID = "fm"
        private const val MAX_VOICES = 8

        /** Strongest modulation, in cycles of phase. */
        private const val MAX_INDEX = 1.6f

        /** Keeps the phase handed to the sine table positive whatever the modulation does. */
        private const val PHASE_BIAS = 8f

        const val P_RATIO = 0
        const val P_AMOUNT = 1
        const val P_BRIGHT_DECAY = 2
        const val P_BRIGHT_HOLD = 3
        const val P_FEEDBACK = 4
        const val P_ATTACK = 5
        const val P_DECAY = 6
        const val P_SUSTAIN = 7
        const val P_RELEASE = 8
        const val P_OCTAVE = 9
        const val P_DETUNE = 10
        const val P_VELOCITY = 11
        const val P_LEVEL = 12

        val PARAMS: List<ParamSpec> = listOf(
            ParamSpec("ratio", R.string.p_fm_ratio, 0.5f, 12f, 2f, ParamUnit.RATIO, group = R.string.g_fm),
            ParamSpec("amount", R.string.p_brightness, 0f, 1f, 0.45f, ParamUnit.PERCENT, group = R.string.g_fm),
            ParamSpec("brightDecay", R.string.p_fm_decay, 0.01f, 5f, 0.5f, ParamUnit.SECONDS, ParamCurve.EXP, group = R.string.g_fm),
            ParamSpec("brightHold", R.string.p_fm_hold, 0f, 1f, 0.25f, ParamUnit.PERCENT, group = R.string.g_fm),
            ParamSpec("feedback", R.string.p_fm_feedback, 0f, 1f, 0f, ParamUnit.PERCENT, group = R.string.g_fm),
            ParamSpec("attack", R.string.p_attack, 0.001f, 5f, 0.002f, ParamUnit.SECONDS, ParamCurve.EXP, group = R.string.g_amp_env),
            ParamSpec("decay", R.string.p_decay, 0.005f, 8f, 1.2f, ParamUnit.SECONDS, ParamCurve.EXP, group = R.string.g_amp_env),
            ParamSpec("sustain", R.string.p_sustain, 0f, 1f, 0.35f, ParamUnit.PERCENT, group = R.string.g_amp_env),
            ParamSpec("release", R.string.p_release, 0.005f, 8f, 0.35f, ParamUnit.SECONDS, ParamCurve.EXP, group = R.string.g_amp_env),
            ParamSpec("octave", R.string.p_octave, -2f, 2f, 0f, ParamUnit.OCTAVES, group = R.string.g_voice, integer = true),
            ParamSpec("detune", R.string.p_detune, 0f, 30f, 6f, ParamUnit.CENTS, group = R.string.g_voice),
            ParamSpec("velocity", R.string.p_velocity, 0f, 1f, 0.6f, ParamUnit.PERCENT, group = R.string.g_voice),
            ParamSpec("level", R.string.p_level, 0f, 1f, 0.8f, ParamUnit.PERCENT, group = R.string.g_voice),
        )
        val TYPE = DeviceType(TYPE_ID, R.string.dev_fm, R.string.dev_fm_desc, true, PARAMS) { FmSynth() }
    }
}
