package jxpix.proger.mps.engine.instruments

import jxpix.proger.mps.R
import jxpix.proger.mps.engine.DeviceType
import jxpix.proger.mps.engine.Instrument
import jxpix.proger.mps.engine.ParamSpec
import jxpix.proger.mps.engine.ParamUnit
import jxpix.proger.mps.engine.dsp.FastMath
import jxpix.proger.mps.engine.dsp.Noise
import jxpix.proger.mps.engine.dsp.OnePole
import jxpix.proger.mps.engine.dsp.Svf
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.pow

/**
 * Analog-style drum synthesizer: every sound is generated in real time (no samples),
 * so each drum can be tuned and shaped freely.
 *
 * One instance = one drum channel. The pitch follows the played note, which makes
 * tuned toms or 808 bass lines possible straight from the piano roll.
 */
class DrumSynth : Instrument() {

    private val voices = Array(VOICES) { Voice() }
    private var nextVoice = 0
    private val noise = Noise(0x3C6EF372)

    private var type = KICK
    private var tune = 0f
    private var decay = 0.5f
    private var tone = 0.5f
    private var punch = 0.5f
    private var drive = 0f
    private var level = 0.8f

    override fun setParams(p: FloatArray) {
        type = p[P_TYPE].toInt().coerceIn(0, TYPE_COUNT - 1)
        tune = p[P_TUNE]
        decay = p[P_DECAY]
        tone = p[P_TONE]
        punch = p[P_PUNCH]
        drive = p[P_DRIVE]
        level = p[P_LEVEL]
    }

    override val active: Boolean
        get() {
            for (v in voices) if (v.active) return true
            return false
        }

    override fun noteOn(pitch: Int, velocity: Float) {
        // Drums are monophonic: a new hit quickly fades the previous one (no clicks).
        for (v in voices) if (v.active) v.fadeOut(0.003f)
        val v = voices[nextVoice]
        nextVoice = (nextVoice + 1) % VOICES
        v.panL = panLeft(notePan)
        v.panR = panRight(notePan)
        v.trigger(pitch, velocity)
    }

    override fun noteOff(pitch: Int) {
        // Only the sustained 808 bass reacts to note length; everything else is one-shot.
        for (v in voices) if (v.active && v.type == BASS808 && v.pitch == pitch && !v.fading) v.fadeOut(0.03f)
    }

    override fun allNotesOff() {
        for (v in voices) if (v.active && v.type == BASS808 && !v.fading) v.fadeOut(0.03f)
    }

    override fun choke() {
        for (v in voices) if (v.active) v.fadeOut(0.006f)
    }

    override fun reset() {
        for (v in voices) v.active = false
    }

    override fun render(outL: FloatArray, outR: FloatArray, offset: Int, count: Int) {
        for (v in voices) if (v.active) v.render(outL, outR, offset, count)
    }

    private inner class Voice {
        var active = false
        var type = KICK
        var pitch = 60
        var fading = false
        var panL = 1f
        var panR = 1f

        private var t = 0
        private var gain = 0f
        private var fade = 1f
        private var fadeCoef = 1f
        private var done = false

        private var ph1 = 0f
        private var ph2 = 0f
        private var inc1 = 0f
        private var inc2 = 0f
        private val mph = FloatArray(6)
        private val minc = FloatArray(6)

        private var env1 = 0f
        private var env2 = 0f
        private var c1 = 0f
        private var c2 = 0f
        private var pEnv = 0f
        private var pCoef = 0f
        private var pAmt = 0f
        private var hold = 0
        private var burst = 1
        private var a = 0f
        private var b = 0f
        private var shape = 1f

        private var driveOn = false
        private var driveGain = 1f
        private var driveComp = 1f

        private val svf = Svf()
        private val lpf = OnePole()
        private val hpf = OnePole()

        fun fadeOut(seconds: Float) {
            fading = true
            fadeCoef = FastMath.decayCoef(seconds, sampleRate)
        }

        fun trigger(pitch: Int, velocity: Float) {
            val sr = sampleRate
            val d = decay
            val tn = tone
            val pu = punch
            val ratio = 2f.pow((tune + (pitch - 60)) / 12f)

            active = true
            done = false
            fading = false
            fade = 1f
            fadeCoef = 1f
            t = 0
            this.pitch = pitch
            type = this@DrumSynth.type
            ph1 = 0f
            ph2 = 0f
            env1 = 1f
            env2 = 1f
            pEnv = 1f
            hold = 0
            svf.reset()
            lpf.reset()
            hpf.reset()
            driveOn = drive > 0.001f
            driveGain = 1f + drive * 8f
            driveComp = 1f / (1f + drive * 0.6f)
            var typeGain = 1f

            when (type) {
                KICK -> {
                    inc1 = 50f * ratio / sr
                    pAmt = 1.5f + pu * 6f
                    pCoef = FastMath.decayCoef(0.015f, sr)
                    c1 = FastMath.decayCoef(0.03f + 0.32f * d * d, sr)
                    hold = (0.018f * sr).toInt()
                    env2 = tn * 0.5f
                    c2 = FastMath.decayCoef(0.0015f, sr)
                    shape = 1f + tn * 1.5f
                    typeGain = 1.0f
                }
                BASS808 -> {
                    inc1 = 65.406f * ratio / sr
                    pAmt = pu * 3f
                    pCoef = FastMath.decayCoef(0.03f, sr)
                    c1 = FastMath.decayCoef(0.12f + 1.7f * d * d, sr)
                    hold = (0.03f * sr).toInt()
                    env2 = tn * 0.25f
                    c2 = FastMath.decayCoef(0.002f, sr)
                    shape = 1f + tn * 3f
                    typeGain = 0.9f
                }
                TOM -> {
                    inc1 = 110f * ratio / sr
                    pAmt = 0.4f + pu * 1.2f
                    pCoef = FastMath.decayCoef(0.03f, sr)
                    c1 = FastMath.decayCoef(0.06f + 0.4f * d * d, sr)
                    hold = (0.008f * sr).toInt()
                    env2 = tn * 0.4f
                    c2 = FastMath.decayCoef(0.004f, sr)
                    shape = 1f + tn
                    typeGain = 0.9f
                }
                SNARE -> {
                    inc1 = 185f * ratio / sr
                    inc2 = 330f * ratio / sr
                    pAmt = 0.5f + pu * 0.5f
                    pCoef = FastMath.decayCoef(0.012f, sr)
                    c1 = FastMath.decayCoef(0.035f + 0.12f * d, sr)
                    c2 = FastMath.decayCoef(0.04f + 0.3f * d * d, sr)
                    a = 0.9f                       // body level
                    b = 0.45f + 0.75f * pu         // snare wires level
                    hpf.setCutoff(900f, sr)
                    lpf.setCutoff(2500f + tn * 10000f, sr)
                    typeGain = 0.8f
                }
                CLAP -> {
                    svf.set(min((900f + tn * 900f) * ratio, sr * 0.4f), 0.6f, sr)
                    burst = (0.011f * sr).toInt().coerceAtLeast(1)
                    c1 = FastMath.decayCoef(0.0035f, sr)
                    c2 = FastMath.decayCoef(0.04f + 0.35f * d * d, sr)
                    a = 0.55f + 0.45f * pu
                    hpf.setCutoff(500f, sr)
                    typeGain = 1.7f
                }
                HAT, OPEN_HAT -> {
                    for (i in 0 until 6) {
                        mph[i] = 0.13f * i
                        minc[i] = min(0.49f, METAL[i] * 2.0f * ratio / sr)
                    }
                    svf.set(min(6000f + tn * 5000f, sr * 0.45f), 0.3f, sr)
                    c1 = if (type == HAT) FastMath.decayCoef(0.008f + 0.07f * d * d, sr)
                    else FastMath.decayCoef(0.08f + 0.7f * d * d, sr)
                    env2 = pu
                    c2 = FastMath.decayCoef(0.002f, sr)
                    typeGain = 0.7f
                }
                RIM -> {
                    inc1 = 480f * ratio / sr
                    inc2 = min(0.45f, 1690f * ratio / sr)
                    c1 = FastMath.decayCoef(0.006f + 0.03f * d, sr)
                    env2 = 0.4f + tn * 0.6f
                    c2 = FastMath.decayCoef(0.001f, sr)
                    hpf.setCutoff(350f, sr)
                    a = 0.5f + pu * 0.5f
                    typeGain = 1.0f
                }
                COWBELL -> {
                    mph[0] = 0f
                    mph[1] = 0.3f
                    minc[0] = 540f * ratio / sr
                    minc[1] = min(0.49f, 800f * ratio / sr)
                    svf.set(min((1400f + tn * 2400f) * ratio, sr * 0.4f), 0.45f, sr)
                    c1 = FastMath.decayCoef(0.012f, sr)
                    c2 = FastMath.decayCoef(0.05f + 0.4f * d * d, sr)
                    a = 0.25f + 0.5f * pu
                    typeGain = 1.2f
                }
                CRASH -> {
                    for (i in 0 until 6) {
                        mph[i] = 0.17f * i
                        minc[i] = min(0.49f, METAL[i] * 1.45f * ratio / sr)
                    }
                    svf.set(min(2500f + tn * 5000f, sr * 0.45f), 0.2f, sr)
                    c1 = FastMath.decayCoef(0.25f + 2.2f * d * d, sr)
                    env2 = pu
                    c2 = FastMath.decayCoef(0.012f, sr)
                    typeGain = 0.7f
                }
                else -> { // SHAKER
                    svf.set(min((5000f + tn * 4000f) * ratio, sr * 0.45f), 0.35f, sr)
                    c1 = FastMath.decayCoef(0.015f + 0.12f * d * d, sr)
                    c2 = FastMath.decayCoef(0.002f + 0.012f * (1f - pu), sr)
                    typeGain = 1.6f
                }
            }
            gain = velocity * velocity * level * OUTPUT_GAIN * typeGain
        }

        fun render(outL: FloatArray, outR: FloatArray, offset: Int, count: Int) {
            for (i in offset until offset + count) {
                var s = when (type) {
                    KICK, BASS808, TOM -> membrane()
                    SNARE -> snare()
                    CLAP -> clap()
                    HAT, OPEN_HAT, CRASH -> metal()
                    RIM -> rim()
                    COWBELL -> cowbell()
                    else -> shaker()
                }
                if (driveOn) s = FastMath.tanh(s * driveGain) * driveComp
                s *= gain * fade
                if (fading) fade *= fadeCoef
                outL[i] += s * panL
                outR[i] += s * panR
                t++
            }
            if (done || fade < 0.0001f) active = false
        }

        /** Sine with a falling pitch: kick, tom and 808 bass. */
        private fun membrane(): Float {
            ph1 += inc1 * (1f + pAmt * pEnv)
            if (ph1 >= 1f) ph1 -= 1f
            pEnv *= pCoef
            var s = FastMath.sin01(ph1)
            if (shape > 1.01f) s = FastMath.tanh(s * shape) / FastMath.tanh(shape)
            s *= env1
            if (t > hold) env1 *= c1
            if (env2 > 0.0005f) {
                s += noise.next() * env2
                env2 *= c2
            }
            if (env1 < 0.0001f) done = true
            return s
        }

        private fun snare(): Float {
            val bend = 1f + pAmt * pEnv
            pEnv *= pCoef
            ph1 += inc1 * bend
            if (ph1 >= 1f) ph1 -= 1f
            ph2 += inc2 * bend
            if (ph2 >= 1f) ph2 -= 1f
            val body = (FastMath.sin01(ph1) * 0.7f + FastMath.sin01(ph2) * 0.35f) * env1 * a
            val wires = lpf.lp(hpf.hp(noise.next())) * env2 * b
            env1 *= c1
            env2 *= c2
            if (env1 < 0.0001f && env2 < 0.0001f) done = true
            return body + wires
        }

        private fun clap(): Float {
            svf.process(hpf.hp(noise.next()))
            val amp: Float
            if (t < burst * 3) {
                if (t % burst == 0) env1 = 1f
                amp = env1 * a
                env1 *= c1
                env2 = 1f
            } else {
                amp = env2
                env2 *= c2
                if (env2 < 0.0001f) done = true
            }
            return svf.bp * amp
        }

        /** Cluster of detuned square waves + noise through a high-pass: hats and cymbals. */
        private fun metal(): Float {
            var m = 0f
            for (i in 0 until 6) {
                var p = mph[i] + minc[i]
                if (p >= 1f) p -= 1f
                mph[i] = p
                m += if (p < 0.5f) 1f else -1f
            }
            val x = if (type == CRASH) m * 0.07f + noise.next() * 0.75f else m * 0.12f + noise.next() * 0.45f
            svf.process(x)
            val s = svf.hp * env1 * (1f + env2 * 1.5f)
            env1 *= c1
            env2 *= c2
            if (env1 < 0.0001f) done = true
            return s
        }

        private fun rim(): Float {
            ph1 += inc1
            if (ph1 >= 1f) ph1 -= 1f
            ph2 += inc2
            if (ph2 >= 1f) ph2 -= 1f
            val tri = 1f - 4f * abs(ph1 - 0.5f)
            var s = (tri * 0.55f + FastMath.sin01(ph2) * 0.6f * a) * env1
            s += noise.next() * env2 * 0.6f
            env1 *= c1
            env2 *= c2
            if (env1 < 0.0001f) done = true
            return hpf.hp(s)
        }

        private fun cowbell(): Float {
            var m = 0f
            for (i in 0 until 2) {
                var p = mph[i] + minc[i]
                if (p >= 1f) p -= 1f
                mph[i] = p
                m += if (p < 0.5f) 0.5f else -0.5f
            }
            svf.process(m)
            val s = (svf.bp * 1.3f + m * 0.15f) * (env1 * a + env2 * (1f - a))
            env1 *= c1
            env2 *= c2
            if (env2 < 0.0001f) done = true
            return s
        }

        private fun shaker(): Float {
            svf.process(noise.next())
            val s = (svf.bp + svf.hp * 0.3f) * env1 * (1f - env2)
            env1 *= c1
            env2 *= c2
            if (env1 < 0.0001f) done = true
            return s
        }
    }

    companion object {
        const val TYPE_ID = "drum"
        private const val VOICES = 3
        private const val OUTPUT_GAIN = 1.05f

        const val KICK = 0
        const val BASS808 = 1
        const val SNARE = 2
        const val CLAP = 3
        const val HAT = 4
        const val OPEN_HAT = 5
        const val TOM = 6
        const val RIM = 7
        const val COWBELL = 8
        const val CRASH = 9
        const val SHAKER = 10
        const val TYPE_COUNT = 11

        const val P_TYPE = 0
        const val P_TUNE = 1
        const val P_DECAY = 2
        const val P_TONE = 3
        const val P_PUNCH = 4
        const val P_DRIVE = 5
        const val P_LEVEL = 6

        /** Inharmonic oscillator bank of the TR-808 cymbal circuit. */
        private val METAL = floatArrayOf(205.3f, 304.4f, 369.6f, 522.7f, 540f, 800f)

        val PARAMS: List<ParamSpec> = listOf(
            ParamSpec("type", R.string.p_sound, 0f, (TYPE_COUNT - 1).toFloat(), 0f, choicesRes = R.array.arr_drum_types, group = R.string.g_drum),
            ParamSpec("tune", R.string.p_tune, -24f, 24f, 0f, ParamUnit.SEMITONES, group = R.string.g_drum),
            ParamSpec("decay", R.string.p_decay, 0f, 1f, 0.5f, ParamUnit.PERCENT, group = R.string.g_drum),
            ParamSpec("tone", R.string.p_tone, 0f, 1f, 0.5f, ParamUnit.PERCENT, group = R.string.g_drum),
            ParamSpec("punch", R.string.p_punch, 0f, 1f, 0.5f, ParamUnit.PERCENT, group = R.string.g_drum),
            ParamSpec("drive", R.string.p_drive, 0f, 1f, 0f, ParamUnit.PERCENT, group = R.string.g_drum),
            ParamSpec("level", R.string.p_level, 0f, 1f, 0.8f, ParamUnit.PERCENT, group = R.string.g_drum),
        )

        val TYPE = DeviceType(TYPE_ID, R.string.dev_drum, R.string.dev_drum_desc, true, PARAMS) { DrumSynth() }

        /** Sensible starting values for each drum sound. */
        fun defaultsFor(type: Int): Map<String, Float> {
            fun m(tune: Float, decay: Float, tone: Float, punch: Float, drive: Float = 0f, level: Float = 0.8f) = mapOf(
                "type" to type.toFloat(), "tune" to tune, "decay" to decay, "tone" to tone,
                "punch" to punch, "drive" to drive, "level" to level,
            )
            return when (type) {
                KICK -> m(0f, 0.33f, 0.4f, 0.5f, 0.15f)
                BASS808 -> m(0f, 0.55f, 0.35f, 0.4f, 0.35f)
                SNARE -> m(0f, 0.45f, 0.6f, 0.6f)
                CLAP -> m(0f, 0.45f, 0.5f, 0.6f)
                HAT -> m(0f, 0.3f, 0.6f, 0.4f, level = 0.7f)
                OPEN_HAT -> m(0f, 0.45f, 0.55f, 0.3f, level = 0.65f)
                TOM -> m(0f, 0.5f, 0.3f, 0.5f)
                RIM -> m(0f, 0.3f, 0.5f, 0.6f)
                COWBELL -> m(0f, 0.4f, 0.5f, 0.5f, level = 0.7f)
                CRASH -> m(0f, 0.55f, 0.5f, 0.4f, level = 0.6f)
                else -> m(0f, 0.4f, 0.5f, 0.5f, level = 0.7f)
            }
        }
    }
}
