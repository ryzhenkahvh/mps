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
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Eight-band parametric equalizer with per-band upward/downward dynamics on bells and shelves.
 * All filters, envelopes and detector state are prepared up front; processing stays allocation-free.
 * The response view is drawn from the same biquad design functions used by the audio path.
 */
class ParametricEq : Effect() {
    private val params = FloatArray(PARAMS.size)
    private val left = Array(BAND_COUNT) { Biquad() }
    private val right = Array(BAND_COUNT) { Biquad() }
    private val detectorLeft = Array(BAND_COUNT) { Biquad() }
    private val detectorRight = Array(BAND_COUNT) { Biquad() }
    private val active = BooleanArray(BAND_COUNT)
    private val dynamicActive = BooleanArray(BAND_COUNT)
    private val types = IntArray(BAND_COUNT)
    private val dynamicModes = IntArray(BAND_COUNT)
    private val frequencies = FloatArray(BAND_COUNT)
    private val gains = FloatArray(BAND_COUNT)
    private val qs = FloatArray(BAND_COUNT)
    private val thresholds = FloatArray(BAND_COUNT)
    private val ranges = FloatArray(BAND_COUNT)
    private val envelopes = FloatArray(BAND_COUNT)
    private val appliedDynamicDb = FloatArray(BAND_COUNT)
    private var attackCoefficient = 1f
    private var releaseCoefficient = 1f
    private var outputGain = 1f
    private var dirty = true

    override fun prepare(sampleRate: Int) {
        super.prepare(sampleRate)
        dirty = true
    }

    override fun setParams(p: FloatArray) {
        System.arraycopy(p, 0, params, 0, params.size)
        dirty = true
    }

    override fun process(l: FloatArray, r: FloatArray, frames: Int, ctx: ProcessContext) {
        if (dirty) configure()
        var maximumReduction = 0f
        for (i in 0 until frames) {
            var x = l[i]
            var y = r[i]
            for (band in 0 until BAND_COUNT) {
                if (!active[band]) continue
                if (dynamicActive[band]) {
                    // A linked stereo detector follows the larger of the two band-limited signals.
                    val detector = max(abs(detectorLeft[band].process(x)), abs(detectorRight[band].process(y)))
                    val envelope = envelopes[band]
                    val coefficient = if (detector > envelope) attackCoefficient else releaseCoefficient
                    val nextEnvelope = envelope + (detector - envelope) * coefficient
                    envelopes[band] = nextEnvelope

                    // Retune at 3 kHz rather than per sample; the envelope and gain are both smooth.
                    if ((i and CONTROL_MASK) == 0) {
                        val targetDb = dynamicGainFor(band, nextEnvelope)
                        val previousDb = appliedDynamicDb[band]
                        val smoothedDb = previousDb + (targetDb - previousDb) * GAIN_SMOOTHING
                        if (abs(smoothedDb - previousDb) >= MIN_GAIN_UPDATE_DB) {
                            appliedDynamicDb[band] = smoothedDb
                            val effectiveGain = gains[band] + smoothedDb
                            configureBand(left[band], types[band], frequencies[band], qs[band], effectiveGain, sampleRate)
                            configureBand(right[band], types[band], frequencies[band], qs[band], effectiveGain, sampleRate)
                        }
                    }
                    if (appliedDynamicDb[band] < 0f) {
                        maximumReduction = max(maximumReduction, -appliedDynamicDb[band])
                    }
                }
                x = left[band].process(x)
                y = right[band].process(y)
            }
            l[i] = x * outputGain
            r[i] = y * outputGain
        }
        scope[0] = maximumReduction
    }

    private fun dynamicGainFor(band: Int, envelope: Float): Float {
        if (envelope < SILENCE_FLOOR) return 0f
        val overThresholdDb = FastMath.gainToDb(envelope) - thresholds[band]
        return when (dynamicModes[band]) {
            DYNAMIC_DOWNWARD -> -min(ranges[band], max(0f, overThresholdDb))
            DYNAMIC_UPWARD -> min(ranges[band], max(0f, -overThresholdDb))
            else -> 0f
        }
    }

    private fun configure() {
        for (band in 0 until BAND_COUNT) {
            val offset = band * PARAMS_PER_BAND
            val type = params[offset + TYPE_INDEX].roundToInt().coerceIn(OFF, NOTCH)
            val mode = params[offset + DYNAMIC_MODE_INDEX].roundToInt().coerceIn(DYNAMIC_OFF, DYNAMIC_UPWARD)
            val frequency = params[offset + FREQUENCY].coerceIn(20f, 20000f)
            val gain = params[offset + GAIN].coerceIn(-24f, 24f)
            val q = params[offset + Q].coerceIn(0.1f, 10f)
            val supportsDynamics = type == BELL || type == LOW_SHELF || type == HIGH_SHELF
            val useDynamics = type != OFF && supportsDynamics && mode != DYNAMIC_OFF &&
                params[offset + RANGE_INDEX] > 0f
            val wasActive = active[band]
            val modeChanged = dynamicModes[band] != mode

            types[band] = type
            dynamicModes[band] = mode
            frequencies[band] = frequency
            gains[band] = gain
            qs[band] = q
            thresholds[band] = params[offset + THRESHOLD_INDEX].coerceIn(-60f, 0f)
            ranges[band] = params[offset + RANGE_INDEX].coerceIn(0f, 24f)
            active[band] = type != OFF

            if (!active[band]) {
                if (dynamicActive[band] || wasActive) {
                    detectorLeft[band].reset()
                    detectorRight[band].reset()
                    envelopes[band] = 0f
                    appliedDynamicDb[band] = 0f
                }
                dynamicActive[band] = false
                continue
            }

            if (!wasActive) {
                left[band].reset()
                right[band].reset()
            }
            if (useDynamics != dynamicActive[band] || (useDynamics && modeChanged)) {
                detectorLeft[band].reset()
                detectorRight[band].reset()
                envelopes[band] = 0f
                appliedDynamicDb[band] = 0f
            }
            dynamicActive[band] = useDynamics
            if (useDynamics) {
                detectorLeft[band].setBandPass(frequency, q, sampleRate)
                detectorRight[band].setBandPass(frequency, q, sampleRate)
            }

            val effectiveGain = gain + if (useDynamics) appliedDynamicDb[band] else 0f
            configureBand(left[band], type, frequency, q, effectiveGain, sampleRate)
            configureBand(right[band], type, frequency, q, effectiveGain, sampleRate)
        }
        attackCoefficient = FastMath.onePoleCoef(params[DYNAMIC_ATTACK] * 0.001f, sampleRate)
        releaseCoefficient = FastMath.onePoleCoef(params[DYNAMIC_RELEASE] * 0.001f, sampleRate)
        outputGain = FastMath.dbToGain(params[OUTPUT_GAIN])
        dirty = false
    }

    override fun reset() {
        for (band in 0 until BAND_COUNT) {
            left[band].reset()
            right[band].reset()
            detectorLeft[band].reset()
            detectorRight[band].reset()
        }
        envelopes.fill(0f)
        appliedDynamicDb.fill(0f)
        scope[0] = 0f
        dirty = true
    }

    companion object {
        const val TYPE_ID = "peq"
        const val BAND_COUNT = 8

        const val OFF = 0
        const val BELL = 1
        const val LOW_SHELF = 2
        const val HIGH_SHELF = 3
        const val LOW_PASS = 4
        const val HIGH_PASS = 5
        const val NOTCH = 6

        const val DYNAMIC_OFF = 0
        const val DYNAMIC_DOWNWARD = 1
        const val DYNAMIC_UPWARD = 2

        private const val PARAMS_PER_BAND = 7
        private const val TYPE_INDEX = 0
        private const val FREQUENCY = 1
        private const val GAIN = 2
        private const val Q = 3
        private const val DYNAMIC_MODE_INDEX = 4
        private const val THRESHOLD_INDEX = 5
        private const val RANGE_INDEX = 6
        private const val OUTPUT_GAIN = BAND_COUNT * PARAMS_PER_BAND
        private const val DYNAMIC_ATTACK = OUTPUT_GAIN + 1
        private const val DYNAMIC_RELEASE = OUTPUT_GAIN + 2
        private const val CONTROL_MASK = 15
        private const val GAIN_SMOOTHING = 0.35f
        private const val MIN_GAIN_UPDATE_DB = 0.01f
        private const val SILENCE_FLOOR = 1e-7f

        private val LABELS = arrayOf(
            intArrayOf(R.string.peq_b1_type, R.string.peq_b1_freq, R.string.peq_b1_gain, R.string.peq_b1_q),
            intArrayOf(R.string.peq_b2_type, R.string.peq_b2_freq, R.string.peq_b2_gain, R.string.peq_b2_q),
            intArrayOf(R.string.peq_b3_type, R.string.peq_b3_freq, R.string.peq_b3_gain, R.string.peq_b3_q),
            intArrayOf(R.string.peq_b4_type, R.string.peq_b4_freq, R.string.peq_b4_gain, R.string.peq_b4_q),
            intArrayOf(R.string.peq_b5_type, R.string.peq_b5_freq, R.string.peq_b5_gain, R.string.peq_b5_q),
            intArrayOf(R.string.peq_b6_type, R.string.peq_b6_freq, R.string.peq_b6_gain, R.string.peq_b6_q),
            intArrayOf(R.string.peq_b7_type, R.string.peq_b7_freq, R.string.peq_b7_gain, R.string.peq_b7_q),
            intArrayOf(R.string.peq_b8_type, R.string.peq_b8_freq, R.string.peq_b8_gain, R.string.peq_b8_q),
        )
        private val DEFAULT_FREQUENCIES = floatArrayOf(45f, 110f, 260f, 620f, 1500f, 3600f, 8000f, 15000f)

        val PARAMS: List<ParamSpec> = buildList {
            for (band in 0 until BAND_COUNT) {
                val n = band + 1
                val labels = LABELS[band]
                val group = R.string.g_peq_bands
                add(ParamSpec("b${n}Type", labels[TYPE_INDEX], OFF.toFloat(), NOTCH.toFloat(), OFF.toFloat(), choicesRes = R.array.arr_param_eq_types, group = group))
                add(ParamSpec("b${n}Freq", labels[FREQUENCY], 20f, 20000f, DEFAULT_FREQUENCIES[band], ParamUnit.HZ, ParamCurve.EXP, group = group))
                add(ParamSpec("b${n}Gain", labels[GAIN], -24f, 24f, 0f, ParamUnit.DB, group = group))
                add(ParamSpec("b${n}Q", labels[Q], 0.1f, 10f, 0.707f, ParamUnit.NONE, ParamCurve.EXP, group = group))
                add(ParamSpec("b${n}DynamicMode", R.string.peq_dynamic_mode, DYNAMIC_OFF.toFloat(), DYNAMIC_UPWARD.toFloat(), DYNAMIC_OFF.toFloat(), choicesRes = R.array.arr_param_eq_dynamic_modes, group = group))
                add(ParamSpec("b${n}Threshold", R.string.peq_dynamic_threshold, -60f, 0f, -24f, ParamUnit.DB, group = group))
                add(ParamSpec("b${n}Range", R.string.peq_dynamic_range, 0f, 24f, 6f, ParamUnit.DB_AMOUNT, group = group))
            }
            add(ParamSpec("output", R.string.peq_output, -24f, 24f, 0f, ParamUnit.DB, group = R.string.g_peq_bands))
            add(ParamSpec("dynamicAttack", R.string.peq_dynamic_attack, 1f, 200f, 12f, ParamUnit.MILLIS, ParamCurve.EXP, group = R.string.g_peq_bands))
            add(ParamSpec("dynamicRelease", R.string.peq_dynamic_release, 10f, 1000f, 180f, ParamUnit.MILLIS, ParamCurve.EXP, group = R.string.g_peq_bands))
        }

        val TYPE = DeviceType(TYPE_ID, R.string.fx_param_eq, R.string.fx_param_eq_desc, false, PARAMS) { ParametricEq() }

        /** Applies the selected band shape. Shared by the real processor and the response graph. */
        fun configureBand(filter: Biquad, type: Int, frequency: Float, q: Float, gainDb: Float, sampleRate: Float) {
            when (type) {
                BELL -> filter.setPeaking(frequency, q, gainDb, sampleRate)
                LOW_SHELF -> filter.setLowShelf(frequency, gainDb, sampleRate)
                HIGH_SHELF -> filter.setHighShelf(frequency, gainDb, sampleRate)
                LOW_PASS -> filter.setLowPass(frequency, q, sampleRate)
                HIGH_PASS -> filter.setHighPass(frequency, q, sampleRate)
                NOTCH -> filter.setNotch(frequency, q, sampleRate)
            }
        }
    }
}
