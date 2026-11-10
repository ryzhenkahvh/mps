package jxpix.proger.mps.engine.instruments

import jxpix.proger.mps.R
import jxpix.proger.mps.engine.DeviceType
import jxpix.proger.mps.engine.Instrument
import jxpix.proger.mps.engine.ParamCurve
import jxpix.proger.mps.engine.ParamSpec
import jxpix.proger.mps.engine.ParamUnit
import jxpix.proger.mps.engine.SampleData
import jxpix.proger.mps.engine.dsp.Adsr
import kotlin.math.max
import kotlin.math.pow

/**
 * Plays an audio file (imported or recorded) chromatically across the keyboard.
 * Supports trimming, looping, reverse playback and a full ADSR envelope.
 */
class Sampler : Instrument() {

    private var sample: SampleData? = null
    private val voices = Array(MAX_VOICES) { Voice() }
    private var noteCounter = 0L

    private var root = 60f
    private var fine = 0f
    private var start = 0f
    private var end = 1f
    private var oneShot = true
    private var loop = false
    private var reverse = false
    private var mono = false
    private var level = 0.8f
    private var velSens = 0.7f
    private var releaseTime = 0.08f

    override fun setParams(p: FloatArray) {
        root = p[P_ROOT]
        fine = p[P_FINE]
        start = p[P_START]
        end = p[P_END]
        oneShot = p[P_MODE] < 0.5f
        loop = p[P_LOOP] > 0.5f
        reverse = p[P_REVERSE] > 0.5f
        mono = p[P_POLY] > 0.5f
        level = p[P_LEVEL]
        releaseTime = p[P_RELEASE]
        for (v in voices) v.env.set(p[P_ATTACK], p[P_DECAY], p[P_SUSTAIN], releaseTime, sampleRate)
    }

    override fun setSample(sample: SampleData?) {
        if (sample !== this.sample) {
            for (v in voices) {
                v.active = false
                v.env.kill()
            }
            this.sample = sample
        }
    }

    override val active: Boolean
        get() {
            for (v in voices) if (v.active) return true
            return false
        }

    override fun noteOn(pitch: Int, velocity: Float) {
        val s = sample ?: return
        if (s.frames < 2) return
        var startF = (start * s.frames).toInt().coerceIn(0, s.frames - 2)
        var endF = (end * s.frames).toInt().coerceIn(0, s.frames)
        if (endF - startF < 2) {
            startF = 0
            endF = s.frames
        }

        if (mono) for (v in voices) if (v.active) v.fastRelease()

        var chosen: Voice? = null
        var oldest = voices[0]
        for (v in voices) {
            if (!v.active) {
                chosen = v
                break
            }
            if (v.order < oldest.order) oldest = v
        }
        val v = chosen ?: oldest

        v.active = true
        v.gate = true
        v.pitch = pitch
        v.order = ++noteCounter
        v.startF = startF
        v.endF = endF
        v.step = (s.sampleRate.toDouble() / sampleRate) * 2.0.pow((pitch - root + fine / 100.0) / 12.0)
        v.pos = if (reverse) (endF - 1).toDouble() else startF.toDouble()
        v.gain = ((1f - velSens) + velSens * velocity * velocity) * level * 1.25f
        v.env.kill()
        v.env.setRelease(releaseTime, sampleRate) // undo a possible fast (choke) release
        v.env.gateOn()
    }

    override fun noteOff(pitch: Int) {
        if (oneShot) return
        for (v in voices) if (v.active && v.gate && v.pitch == pitch) {
            v.gate = false
            v.env.gateOff()
        }
    }

    override fun allNotesOff() {
        for (v in voices) if (v.active && v.gate && (!oneShot || loop)) {
            v.gate = false
            v.env.gateOff()
        }
    }

    override fun choke() {
        for (v in voices) if (v.active) v.fastRelease()
    }

    override fun reset() {
        for (v in voices) {
            v.active = false
            v.env.kill()
        }
    }

    override fun render(outL: FloatArray, outR: FloatArray, offset: Int, count: Int) {
        val s = sample ?: return
        for (v in voices) if (v.active) v.render(s, outL, outR, offset, count)
    }

    private inner class Voice {
        var active = false
        var gate = false
        var pitch = 60
        var order = 0L
        var pos = 0.0
        var step = 1.0
        var startF = 0
        var endF = 0
        var gain = 1f
        val env = Adsr()

        fun fastRelease() {
            gate = false
            env.setRelease(0.006f, sampleRate)
            env.gateOff()
        }

        fun render(s: SampleData, outL: FloatArray, outR: FloatArray, offset: Int, count: Int) {
            val left = s.left
            val right = s.right ?: left
            val startF = startF
            val endF = minOf(endF, left.size)
            val length = endF - startF
            if (length < 2) {
                active = false
                return
            }
            val k = SampleData.TO_FLOAT * gain
            val rev = reverse
            val looping = loop
            var pos = pos
            val step = step

            for (i in offset until offset + count) {
                var ip = pos.toInt()
                if (ip >= endF || ip < startF || pos < startF) {
                    if (looping) {
                        pos = if (rev) pos + length else pos - length
                        if (pos < startF || pos >= endF) pos = if (rev) (endF - 1).toDouble() else startF.toDouble()
                        ip = pos.toInt()
                    } else {
                        env.kill()
                        break
                    }
                }
                val frac = (pos - ip).toFloat()
                val i1 = if (ip + 1 < endF) ip + 1 else if (looping) startF else ip
                var e = env.next()
                if (!looping) {
                    // de-click the very end of one-shots
                    val remaining = if (rev) ip - startF else endF - 1 - ip
                    if (remaining < FADE) e *= max(0, remaining).toFloat() / FADE
                }
                val g = e * k
                val l0 = left[ip].toFloat()
                outL[i] += (l0 + (left[i1] - l0) * frac) * g
                val r0 = right[ip].toFloat()
                outR[i] += (r0 + (right[i1] - r0) * frac) * g
                pos += if (rev) -step else step
            }
            this.pos = pos
            if (env.isIdle) active = false
        }
    }

    companion object {
        const val TYPE_ID = "sampler"
        private const val MAX_VOICES = 10
        private const val FADE = 48

        const val P_ROOT = 0
        const val P_FINE = 1
        const val P_START = 2
        const val P_END = 3
        const val P_ATTACK = 4
        const val P_DECAY = 5
        const val P_SUSTAIN = 6
        const val P_RELEASE = 7
        const val P_MODE = 8
        const val P_LOOP = 9
        const val P_REVERSE = 10
        const val P_POLY = 11
        const val P_LEVEL = 12

        val PARAMS: List<ParamSpec> = listOf(
            ParamSpec("root", R.string.p_root_note, 24f, 96f, 60f, ParamUnit.NOTE, group = R.string.g_sample, integer = true),
            ParamSpec("fine", R.string.p_fine, -100f, 100f, 0f, ParamUnit.CENTS, group = R.string.g_sample),
            ParamSpec("start", R.string.p_start, 0f, 1f, 0f, ParamUnit.PERCENT, group = R.string.g_sample),
            ParamSpec("end", R.string.p_end, 0f, 1f, 1f, ParamUnit.PERCENT, group = R.string.g_sample),
            ParamSpec("attack", R.string.p_attack, 0.001f, 5f, 0.001f, ParamUnit.SECONDS, ParamCurve.EXP, group = R.string.g_amp_env),
            ParamSpec("decay", R.string.p_decay, 0.005f, 5f, 0.3f, ParamUnit.SECONDS, ParamCurve.EXP, group = R.string.g_amp_env),
            ParamSpec("sustain", R.string.p_sustain, 0f, 1f, 1f, ParamUnit.PERCENT, group = R.string.g_amp_env),
            ParamSpec("release", R.string.p_release, 0.005f, 8f, 0.08f, ParamUnit.SECONDS, ParamCurve.EXP, group = R.string.g_amp_env),
            ParamSpec("mode", R.string.p_trigger, 0f, 1f, 0f, choicesRes = R.array.arr_sampler_modes, group = R.string.g_playback),
            ParamSpec("loop", R.string.p_loop, 0f, 1f, 0f, choicesRes = R.array.arr_off_on, group = R.string.g_playback),
            ParamSpec("reverse", R.string.p_reverse, 0f, 1f, 0f, choicesRes = R.array.arr_off_on, group = R.string.g_playback),
            ParamSpec("poly", R.string.p_voices, 0f, 1f, 0f, choicesRes = R.array.arr_poly_mono, group = R.string.g_playback),
            ParamSpec("level", R.string.p_level, 0f, 1f, 0.8f, ParamUnit.PERCENT, group = R.string.g_playback),
        )

        val TYPE = DeviceType(TYPE_ID, R.string.dev_sampler, R.string.dev_sampler_desc, true, PARAMS) { Sampler() }
    }
}
