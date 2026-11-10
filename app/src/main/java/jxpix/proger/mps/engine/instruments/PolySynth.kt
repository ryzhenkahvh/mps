package jxpix.proger.mps.engine.instruments

import jxpix.proger.mps.R
import jxpix.proger.mps.engine.DeviceType
import jxpix.proger.mps.engine.Instrument
import jxpix.proger.mps.engine.ParamCurve
import jxpix.proger.mps.engine.ParamSpec
import jxpix.proger.mps.engine.ParamUnit
import jxpix.proger.mps.engine.dsp.Adsr
import jxpix.proger.mps.engine.dsp.FastMath
import jxpix.proger.mps.engine.dsp.Noise
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * Polyphonic virtual-analog synthesizer.
 *
 * Signal path per voice: 2 band-limited oscillators (with up to 5x unison) + sub + noise
 * -> drive -> resonant multimode filter -> amplifier. Pitch, cutoff and level can be
 * modulated by an LFO; the filter has its own envelope.
 */
class PolySynth : Instrument() {

    private val voices = Array(MAX_VOICES) { Voice() }
    private val noise = Noise(0x51ED270B)

    // ---- parameters (derived values, refreshed in setParams) ----
    private var wave1 = WAVE_SAW
    private var wave2 = WAVE_SAW
    private var level1 = 0.8f
    private var level2 = 0f
    private var subLevel = 0f
    private var noiseLevel = 0f
    private var pulseWidth = 0.5f
    private var osc1Semis = 0f
    private var osc2Ratio = 1f
    private var unison = 1
    private val uniRatio = FloatArray(MAX_UNISON) { 1f }
    private val uniPanL = FloatArray(MAX_UNISON) { 1f }
    private val uniPanR = FloatArray(MAX_UNISON) { 1f }
    private var stereo = false
    private var filterType = FILTER_LP12
    private var cutoff = 8000f
    private var resonance = 0.2f
    private var envAmount = 0f
    private var keyTrack = 0f
    private var drive = 0f
    private var driveGain = 1f
    private var driveComp = 1f
    private var ampA = 0.005f
    private var ampD = 0.2f
    private var ampS = 0.8f
    private var ampR = 0.2f
    private var fltA = 0.005f
    private var fltD = 0.2f
    private var fltS = 0.5f
    private var fltR = 0.2f
    private var lfoWave = 0
    private var lfoInc = 0f
    private var lfoPitch = 0f
    private var lfoCutoff = 0f
    private var lfoAmp = 0f
    private var mode = MODE_POLY
    private var glideCoef = 1f
    private var velSens = 0.5f
    private var level = 0.7f

    // ---- runtime state ----
    private var controlRate = 3000f
    private var dcCoef = 0.002f
    private var ctrlCountdown = 0
    private var lfoPhase = 0f
    private var lfoValue = 0f
    private var lfoRandom = 0f
    private var noteCounter = 0L
    private var lastNote = 60f
    private var hasLastNote = false
    private val held = IntArray(HELD_MAX)
    private var heldCount = 0
    private var paramsReady = false

    override fun prepare(sampleRate: Int) {
        super.prepare(sampleRate)
        controlRate = this.sampleRate / CTRL
        dcCoef = FastMath.TWO_PI * 15f / this.sampleRate
    }

    override fun setParams(p: FloatArray) {
        wave1 = p[OSC1_WAVE].toInt()
        wave2 = p[OSC2_WAVE].toInt()
        level1 = p[OSC1_LEVEL]
        level2 = p[OSC2_LEVEL]
        subLevel = p[SUB_LEVEL]
        noiseLevel = p[NOISE_LEVEL]
        pulseWidth = p[PULSE_WIDTH]
        osc1Semis = p[OSC1_OCT] * 12f
        val osc2Semis = p[OSC2_OCT] * 12f + p[OSC2_SEMI] + p[OSC2_DETUNE] / 100f
        osc2Ratio = 2f.pow((osc2Semis - osc1Semis) / 12f)

        unison = p[UNISON].toInt().coerceIn(1, MAX_UNISON)
        val detune = p[UNI_DETUNE] * 0.5f // up to +-50 cents
        val spread = p[UNI_SPREAD]
        stereo = unison > 1 && spread > 0.001f
        val norm = sqrt(2f / unison)
        for (u in 0 until unison) {
            val pos = if (unison == 1) 0f else (u.toFloat() / (unison - 1)) * 2f - 1f
            uniRatio[u] = 2f.pow(pos * detune / 12f)
            val pan = pos * spread
            uniPanL[u] = sqrt(0.5f * (1f - pan)) * norm
            uniPanR[u] = sqrt(0.5f * (1f + pan)) * norm
        }

        filterType = p[FLT_TYPE].toInt()
        cutoff = p[CUTOFF]
        resonance = p[RESO] * if (filterType == FILTER_LP24) 0.8f else 1f
        envAmount = p[ENV_AMT] * 96f
        keyTrack = p[KEYTRACK]
        drive = p[DRIVE]
        driveGain = 1f + drive * 9f
        driveComp = 1f / (1f + drive)

        ampA = p[AMP_A]; ampD = p[AMP_D]; ampS = p[AMP_S]; ampR = p[AMP_R]
        fltA = p[FLT_A]; fltD = p[FLT_D]; fltS = p[FLT_S]; fltR = p[FLT_R]
        for (v in voices) {
            v.amp.set(ampA, ampD, ampS, ampR, sampleRate)
            v.fenv.set(fltA, fltD, fltS, fltR, controlRate)
        }

        lfoWave = p[LFO_WAVE].toInt()
        lfoInc = p[LFO_RATE] / controlRate
        lfoPitch = p[LFO_PITCH] * p[LFO_PITCH] * 12f
        lfoCutoff = p[LFO_CUTOFF] * 48f
        lfoAmp = p[LFO_AMP]

        val newMode = p[MODE].toInt()
        if (newMode != mode) {
            mode = newMode
            heldCount = 0
            for (v in voices) if (v.active && v.gate) v.release()
        }
        val glide = p[GLIDE]
        glideCoef = if (glide < 0.002f) 1f else 1f - exp(-1f / (glide * controlRate))
        velSens = p[VEL_SENS]
        level = p[LEVEL] * if (mode == MODE_POLY) 1f else MONO_BOOST
        paramsReady = true
    }

    override val active: Boolean
        get() {
            for (v in voices) if (v.active) return true
            return false
        }

    override fun noteOn(pitch: Int, velocity: Float) {
        if (!paramsReady) return
        if (mode == MODE_POLY) {
            start(allocate(pitch), pitch, velocity)
        } else {
            pushHeld(pitch)
            val v = voices[0]
            if (mode == MODE_LEGATO && v.active && v.gate) {
                v.pitch = pitch
                v.target = pitch.toFloat()
            } else {
                start(v, pitch, velocity)
            }
        }
        lastNote = pitch.toFloat()
        hasLastNote = true
    }

    override fun noteOff(pitch: Int) {
        if (mode == MODE_POLY) {
            for (v in voices) if (v.active && v.gate && v.pitch == pitch) v.release()
        } else {
            removeHeld(pitch)
            val v = voices[0]
            if (heldCount > 0) {
                val back = held[heldCount - 1]
                if (v.pitch != back) {
                    v.pitch = back
                    v.target = back.toFloat()
                }
            } else if (v.active && v.gate) {
                v.release()
            }
        }
    }

    override fun allNotesOff() {
        heldCount = 0
        for (v in voices) if (v.active && v.gate) v.release()
    }

    override fun reset() {
        heldCount = 0
        for (v in voices) {
            v.active = false
            v.amp.kill()
            v.fenv.kill()
        }
    }

    private fun pushHeld(pitch: Int) {
        removeHeld(pitch)
        if (heldCount == HELD_MAX) {
            for (i in 1 until HELD_MAX) held[i - 1] = held[i]
            heldCount--
        }
        held[heldCount++] = pitch
    }

    private fun removeHeld(pitch: Int) {
        var i = 0
        while (i < heldCount) {
            if (held[i] == pitch) {
                for (j in i + 1 until heldCount) held[j - 1] = held[j]
                heldCount--
            } else i++
        }
    }

    private fun allocate(pitch: Int): Voice {
        var free: Voice? = null
        var oldestReleased: Voice? = null
        var oldest = voices[0]
        for (v in voices) {
            if (v.active && v.pitch == pitch) return v
            if (!v.active) {
                if (free == null) free = v
            } else {
                if (!v.gate && (oldestReleased == null || v.order < oldestReleased.order)) oldestReleased = v
                if (v.order < oldest.order) oldest = v
            }
        }
        return free ?: oldestReleased ?: oldest
    }

    private fun start(v: Voice, pitch: Int, velocity: Float) {
        val wasActive = v.active
        v.active = true
        v.gate = true
        v.pitch = pitch
        v.order = ++noteCounter
        v.velGain = (1f - velSens) + velSens * velocity * velocity
        v.target = pitch.toFloat()
        if (glideCoef >= 1f || !hasLastNote) {
            v.note = v.target
        } else if (!wasActive || mode == MODE_POLY) {
            v.note = lastNote
        } // else: a sounding mono voice keeps gliding from wherever it is
        if (!wasActive) {
            v.clearFilter()
            for (u in 0 until MAX_UNISON) {
                val random = unison > 1
                v.ph1[u] = if (random) (noise.next() * 0.5f + 0.5f) * 0.999f else 0f
                v.ph2[u] = if (random) (noise.next() * 0.5f + 0.5f) * 0.999f else 0.25f
            }
            v.subPh = 0f
            v.amp.kill()
            v.fenv.kill()
        }
        v.amp.gateOn()
        v.fenv.gateOn()
        v.control()
    }

    override fun render(outL: FloatArray, outR: FloatArray, offset: Int, count: Int) {
        var any = false
        for (v in voices) if (v.active) { any = true; break }
        if (!any) return
        var o = offset
        var remaining = count
        while (remaining > 0) {
            if (ctrlCountdown <= 0) {
                tickLfo()
                for (v in voices) if (v.active) v.control()
                ctrlCountdown = CTRL
            }
            val n = min(remaining, ctrlCountdown)
            for (v in voices) if (v.active) v.render(outL, outR, o, n)
            o += n
            remaining -= n
            ctrlCountdown -= n
        }
    }

    private fun tickLfo() {
        lfoPhase += lfoInc
        if (lfoPhase >= 1f) {
            lfoPhase -= lfoPhase.toInt()
            lfoRandom = noise.next()
        }
        lfoValue = when (lfoWave) {
            0 -> FastMath.sin01(lfoPhase)
            1 -> 1f - 4f * abs(lfoPhase - 0.5f)
            2 -> 1f - 2f * lfoPhase
            3 -> if (lfoPhase < 0.5f) 1f else -1f
            else -> lfoRandom
        }
    }

    private fun blep(t: Float, dt: Float): Float = when {
        t < dt -> {
            val x = t / dt
            x + x - x * x - 1f
        }
        t > 1f - dt -> {
            val x = (t - 1f) / dt
            x * x + x + x + 1f
        }
        else -> 0f
    }

    private fun osc(wave: Int, ph: Float, dt: Float): Float = when (wave) {
        WAVE_SINE -> FastMath.sin01(ph)
        WAVE_TRIANGLE -> 1f - 4f * abs(ph - 0.5f)
        WAVE_SAW -> 2f * ph - 1f - blep(ph, dt)
        WAVE_SQUARE -> {
            val pw = pulseWidth
            var v = (if (ph < pw) 1f else -1f) + blep(ph, dt)
            var p2 = ph + 1f - pw
            if (p2 >= 1f) p2 -= 1f
            v -= blep(p2, dt)
            v - (2f * pw - 1f)
        }
        else -> noise.next()
    }

    private inner class Voice {
        var active = false
        var gate = false
        var pitch = -1
        var order = 0L
        var velGain = 1f
        var note = 60f
        var target = 60f

        val ph1 = FloatArray(MAX_UNISON)
        val ph2 = FloatArray(MAX_UNISON)
        val dt1 = FloatArray(MAX_UNISON)
        val dt2 = FloatArray(MAX_UNISON)
        var subPh = 0f
        var subDt = 0f

        val amp = Adsr()
        val fenv = Adsr()

        private var a1 = 0f
        private var a2 = 0f
        private var a3 = 0f
        private var k = 1f
        private var gain = 0f

        // two cascaded stereo SVF stages
        private var l1a = 0f
        private var l1b = 0f
        private var r1a = 0f
        private var r1b = 0f
        private var l2a = 0f
        private var l2b = 0f
        private var r2a = 0f
        private var r2b = 0f
        private var dcL = 0f
        private var dcR = 0f

        fun clearFilter() {
            l1a = 0f; l1b = 0f; r1a = 0f; r1b = 0f
            l2a = 0f; l2b = 0f; r2a = 0f; r2b = 0f
            dcL = 0f; dcR = 0f
        }

        fun release() {
            gate = false
            amp.gateOff()
            fenv.gateOff()
        }

        /** Control-rate update: glide, pitch, filter coefficients, tremolo. */
        fun control() {
            val diff = target - note
            note = if (glideCoef >= 1f || abs(diff) < 0.001f) target else note + diff * glideCoef

            val n1 = note + osc1Semis + lfoValue * lfoPitch
            val f1 = 440f * 2f.pow((n1 - 69f) / 12f)
            val inv = 1f / sampleRate
            val f2 = f1 * osc2Ratio
            for (u in 0 until unison) {
                val r = uniRatio[u]
                dt1[u] = min(0.45f, f1 * r * inv)
                dt2[u] = min(0.45f, f2 * r * inv)
            }
            subDt = min(0.45f, f1 * 0.5f * inv)

            val env = fenv.next()
            val semis = envAmount * env + keyTrack * (note - 60f) + lfoValue * lfoCutoff
            val fc = (cutoff * 2f.pow(semis / 12f)).coerceIn(20f, sampleRate * 0.45f)
            val g = tan(FastMath.PI_F * fc * inv)
            k = 2f - 2f * resonance.coerceIn(0f, 0.97f)
            a1 = 1f / (1f + g * (g + k))
            a2 = g * a1
            a3 = g * a2

            gain = VOICE_GAIN * level * velGain * (1f - lfoAmp * (0.5f - 0.5f * lfoValue))
        }

        fun render(outL: FloatArray, outR: FloatArray, offset: Int, count: Int) {
            val uni = unison
            val w1 = wave1
            val w2 = wave2
            val lv1 = level1
            val lv2 = level2
            val sub = subLevel
            val nz = noiseLevel
            val type = filterType
            val a1 = a1
            val a2 = a2
            val a3 = a3
            val k = k
            val gain = gain
            val isStereo = stereo
            val useOsc2 = lv2 > 0.0005f
            val useDrive = drive > 0.001f
            var l1a = l1a
            var l1b = l1b
            var r1a = r1a
            var r1b = r1b
            var l2a = l2a
            var l2b = l2b
            var r2a = r2a
            var r2b = r2b
            var subPh = subPh
            var dcL = dcL
            var dcR = dcR

            for (i in offset until offset + count) {
                var sl = 0f
                var sr = 0f
                for (u in 0 until uni) {
                    var ph = ph1[u]
                    val d = dt1[u]
                    var s = osc(w1, ph, d) * lv1
                    ph += d
                    if (ph >= 1f) ph -= 1f
                    ph1[u] = ph
                    if (useOsc2) {
                        var q = ph2[u]
                        val d2 = dt2[u]
                        s += osc(w2, q, d2) * lv2
                        q += d2
                        if (q >= 1f) q -= 1f
                        ph2[u] = q
                    }
                    sl += s * uniPanL[u]
                    sr += s * uniPanR[u]
                }
                var center = 0f
                if (sub > 0.0005f) {
                    center += FastMath.sin01(subPh) * sub
                    subPh += subDt
                    if (subPh >= 1f) subPh -= 1f
                }
                if (nz > 0.0005f) center += noise.next() * nz
                sl += center
                sr += center

                if (useDrive) {
                    // saturation of an asymmetric wave creates a DC offset: track and remove it
                    sl = FastMath.tanh(sl * driveGain) * driveComp
                    dcL += (sl - dcL) * dcCoef
                    sl -= dcL
                    if (isStereo) {
                        sr = FastMath.tanh(sr * driveGain) * driveComp
                        dcR += (sr - dcR) * dcCoef
                        sr -= dcR
                    }
                }

                // ---- filter, left (or mono) ----
                var v3 = sl - l1b
                var v1 = a1 * l1a + a2 * v3
                var v2 = l1b + a2 * l1a + a3 * v3
                l1a = 2f * v1 - l1a
                l1b = 2f * v2 - l1b
                var yl = when (type) {
                    FILTER_LP12 -> v2
                    FILTER_LP24 -> {
                        val x2 = v2
                        v3 = x2 - l2b
                        v1 = a1 * l2a + a2 * v3
                        v2 = l2b + a2 * l2a + a3 * v3
                        l2a = 2f * v1 - l2a
                        l2b = 2f * v2 - l2b
                        v2
                    }
                    FILTER_HP -> sl - k * v1 - v2
                    else -> v1
                }
                var yr = yl
                if (isStereo) {
                    v3 = sr - r1b
                    v1 = a1 * r1a + a2 * v3
                    v2 = r1b + a2 * r1a + a3 * v3
                    r1a = 2f * v1 - r1a
                    r1b = 2f * v2 - r1b
                    yr = when (type) {
                        FILTER_LP12 -> v2
                        FILTER_LP24 -> {
                            val x2 = v2
                            v3 = x2 - r2b
                            v1 = a1 * r2a + a2 * v3
                            v2 = r2b + a2 * r2a + a3 * v3
                            r2a = 2f * v1 - r2a
                            r2b = 2f * v2 - r2b
                            v2
                        }
                        FILTER_HP -> sr - k * v1 - v2
                        else -> v1
                    }
                }

                val a = amp.next() * gain
                yl *= a
                yr *= a
                outL[i] += yl
                outR[i] += yr
            }

            this.l1a = l1a
            this.l1b = l1b
            this.r1a = r1a
            this.r1b = r1b
            this.l2a = l2a
            this.l2b = l2b
            this.r2a = r2a
            this.r2b = r2b
            this.subPh = subPh
            this.dcL = dcL
            this.dcR = dcR
            if (amp.isIdle) active = false
        }
    }

    companion object {
        const val TYPE_ID = "synth"

        const val MAX_VOICES = 12
        const val MAX_UNISON = 5
        private const val HELD_MAX = 16
        private const val CTRL = 16
        private const val VOICE_GAIN = 0.3f

        /** A single-voice patch may be louder: there are no other voices to add up. */
        private const val MONO_BOOST = 1.9f

        const val WAVE_SINE = 0
        const val WAVE_TRIANGLE = 1
        const val WAVE_SAW = 2
        const val WAVE_SQUARE = 3
        const val WAVE_NOISE = 4

        const val FILTER_LP12 = 0
        const val FILTER_LP24 = 1
        const val FILTER_HP = 2
        const val FILTER_BP = 3

        const val MODE_POLY = 0
        const val MODE_MONO = 1
        const val MODE_LEGATO = 2

        const val OSC1_WAVE = 0
        const val OSC1_OCT = 1
        const val OSC1_LEVEL = 2
        const val PULSE_WIDTH = 3
        const val OSC2_WAVE = 4
        const val OSC2_OCT = 5
        const val OSC2_SEMI = 6
        const val OSC2_DETUNE = 7
        const val OSC2_LEVEL = 8
        const val SUB_LEVEL = 9
        const val NOISE_LEVEL = 10
        const val UNISON = 11
        const val UNI_DETUNE = 12
        const val UNI_SPREAD = 13
        const val FLT_TYPE = 14
        const val CUTOFF = 15
        const val RESO = 16
        const val ENV_AMT = 17
        const val KEYTRACK = 18
        const val DRIVE = 19
        const val AMP_A = 20
        const val AMP_D = 21
        const val AMP_S = 22
        const val AMP_R = 23
        const val FLT_A = 24
        const val FLT_D = 25
        const val FLT_S = 26
        const val FLT_R = 27
        const val LFO_WAVE = 28
        const val LFO_RATE = 29
        const val LFO_PITCH = 30
        const val LFO_CUTOFF = 31
        const val LFO_AMP = 32
        const val MODE = 33
        const val GLIDE = 34
        const val VEL_SENS = 35
        const val LEVEL = 36

        private val G_OSC1 = R.string.g_osc1
        private val G_OSC2 = R.string.g_osc2
        private val G_MIX = R.string.g_mix
        private val G_UNISON = R.string.g_unison
        private val G_FILTER = R.string.g_filter
        private val G_AMP = R.string.g_amp_env
        private val G_FENV = R.string.g_filter_env
        private val G_LFO = R.string.g_lfo
        private val G_VOICE = R.string.g_voice

        val PARAMS: List<ParamSpec> = listOf(
            ParamSpec("osc1Wave", R.string.p_wave, 0f, 4f, 2f, choicesRes = R.array.arr_waves, group = G_OSC1),
            ParamSpec("osc1Oct", R.string.p_octave, -2f, 2f, 0f, ParamUnit.OCTAVES, group = G_OSC1, integer = true),
            ParamSpec("osc1Level", R.string.p_level, 0f, 1f, 0.8f, ParamUnit.PERCENT, group = G_OSC1),
            ParamSpec("pulseWidth", R.string.p_pulse_width, 0.05f, 0.95f, 0.5f, ParamUnit.PERCENT, group = G_OSC1),
            ParamSpec("osc2Wave", R.string.p_wave, 0f, 4f, 2f, choicesRes = R.array.arr_waves, group = G_OSC2),
            ParamSpec("osc2Oct", R.string.p_octave, -2f, 2f, 0f, ParamUnit.OCTAVES, group = G_OSC2, integer = true),
            ParamSpec("osc2Semi", R.string.p_semitones, -12f, 12f, 0f, ParamUnit.SEMITONES, group = G_OSC2, integer = true),
            ParamSpec("osc2Detune", R.string.p_detune, -50f, 50f, 8f, ParamUnit.CENTS, group = G_OSC2),
            ParamSpec("osc2Level", R.string.p_level, 0f, 1f, 0f, ParamUnit.PERCENT, group = G_OSC2),
            ParamSpec("subLevel", R.string.p_sub, 0f, 1f, 0f, ParamUnit.PERCENT, group = G_MIX),
            ParamSpec("noiseLevel", R.string.p_noise, 0f, 1f, 0f, ParamUnit.PERCENT, group = G_MIX),
            ParamSpec("unison", R.string.p_voices, 1f, 5f, 1f, ParamUnit.INT, group = G_UNISON, integer = true),
            ParamSpec("uniDetune", R.string.p_detune, 0f, 1f, 0.3f, ParamUnit.PERCENT, group = G_UNISON),
            ParamSpec("uniSpread", R.string.p_stereo, 0f, 1f, 0.7f, ParamUnit.PERCENT, group = G_UNISON),
            ParamSpec("filterType", R.string.p_type, 0f, 3f, 0f, choicesRes = R.array.arr_synth_filters, group = G_FILTER),
            ParamSpec("cutoff", R.string.p_cutoff, 30f, 20000f, 6000f, ParamUnit.HZ, ParamCurve.EXP, group = G_FILTER),
            ParamSpec("resonance", R.string.p_resonance, 0f, 1f, 0.15f, ParamUnit.PERCENT, group = G_FILTER),
            ParamSpec("envAmount", R.string.p_env_amount, -1f, 1f, 0f, ParamUnit.PERCENT, group = G_FILTER),
            ParamSpec("keyTrack", R.string.p_key_track, 0f, 1f, 0.3f, ParamUnit.PERCENT, group = G_FILTER),
            ParamSpec("drive", R.string.p_drive, 0f, 1f, 0f, ParamUnit.PERCENT, group = G_FILTER),
            ParamSpec("ampAttack", R.string.p_attack, 0.001f, 5f, 0.005f, ParamUnit.SECONDS, ParamCurve.EXP, group = G_AMP),
            ParamSpec("ampDecay", R.string.p_decay, 0.005f, 5f, 0.25f, ParamUnit.SECONDS, ParamCurve.EXP, group = G_AMP),
            ParamSpec("ampSustain", R.string.p_sustain, 0f, 1f, 0.8f, ParamUnit.PERCENT, group = G_AMP),
            ParamSpec("ampRelease", R.string.p_release, 0.005f, 8f, 0.2f, ParamUnit.SECONDS, ParamCurve.EXP, group = G_AMP),
            ParamSpec("fltAttack", R.string.p_attack, 0.001f, 5f, 0.005f, ParamUnit.SECONDS, ParamCurve.EXP, group = G_FENV),
            ParamSpec("fltDecay", R.string.p_decay, 0.005f, 5f, 0.3f, ParamUnit.SECONDS, ParamCurve.EXP, group = G_FENV),
            ParamSpec("fltSustain", R.string.p_sustain, 0f, 1f, 0.3f, ParamUnit.PERCENT, group = G_FENV),
            ParamSpec("fltRelease", R.string.p_release, 0.005f, 8f, 0.3f, ParamUnit.SECONDS, ParamCurve.EXP, group = G_FENV),
            ParamSpec("lfoWave", R.string.p_wave, 0f, 4f, 0f, choicesRes = R.array.arr_lfo_waves, group = G_LFO),
            ParamSpec("lfoRate", R.string.p_rate, 0.05f, 30f, 5f, ParamUnit.HZ, ParamCurve.EXP, group = G_LFO),
            ParamSpec("lfoPitch", R.string.p_to_pitch, 0f, 1f, 0f, ParamUnit.PERCENT, group = G_LFO),
            ParamSpec("lfoCutoff", R.string.p_to_cutoff, -1f, 1f, 0f, ParamUnit.PERCENT, group = G_LFO),
            ParamSpec("lfoAmp", R.string.p_to_volume, 0f, 1f, 0f, ParamUnit.PERCENT, group = G_LFO),
            ParamSpec("mode", R.string.p_mode, 0f, 2f, 0f, choicesRes = R.array.arr_voice_modes, group = G_VOICE),
            ParamSpec("glide", R.string.p_glide, 0f, 1f, 0f, ParamUnit.SECONDS, ParamCurve.SQUARE, group = G_VOICE),
            ParamSpec("velSens", R.string.p_velocity, 0f, 1f, 0.6f, ParamUnit.PERCENT, group = G_VOICE),
            ParamSpec("level", R.string.p_level, 0f, 1f, 0.7f, ParamUnit.PERCENT, group = G_VOICE),
        )

        val TYPE = DeviceType(TYPE_ID, R.string.dev_synth, R.string.dev_synth_desc, true, PARAMS) { PolySynth() }
    }
}
