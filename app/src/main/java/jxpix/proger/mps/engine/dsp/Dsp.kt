package jxpix.proger.mps.engine.dsp

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * Small, allocation-free DSP building blocks shared by every instrument and effect.
 *
 * Everything in this file is safe to call from the real-time audio thread:
 * no allocations, no locks, no I/O.
 */
object FastMath {
    const val PI_F = 3.1415927f
    const val TWO_PI = 6.2831855f

    private const val SIZE = 4096
    private const val MASK = SIZE - 1
    private val sinTable = FloatArray(SIZE + 1) { sin(2.0 * PI * it / SIZE).toFloat() }

    /** Sine of a normalised phase where `1.0` is one full cycle. Phase must be >= 0. */
    fun sin01(phase: Float): Float {
        val p = phase * SIZE
        val i = p.toInt()
        val f = p - i
        val idx = i and MASK
        val a = sinTable[idx]
        return a + (sinTable[idx + 1] - a) * f
    }

    /** Cheap rational tanh approximation, exact at 0 and saturating at |x| >= 3. */
    fun tanh(x: Float): Float {
        if (x >= 3f) return 1f
        if (x <= -3f) return -1f
        val x2 = x * x
        return x * (27f + x2) / (27f + 9f * x2)
    }

    fun noteToFreq(note: Float): Float = 440f * 2f.pow((note - 69f) / 12f)

    fun semitonesToRatio(semitones: Float): Float = 2f.pow(semitones / 12f)

    fun dbToGain(db: Float): Float = 10f.pow(db / 20f)

    fun gainToDb(gain: Float): Float = if (gain <= 1e-6f) -120f else 20f * log10(gain)

    /** Coefficient for a one-pole smoother reaching ~63% of the target in [seconds]. */
    fun onePoleCoef(seconds: Float, sampleRate: Float): Float =
        if (seconds <= 0f) 1f else 1f - exp(-1f / (seconds * sampleRate))

    /** Per-sample multiplier for an exponential decay with time constant [seconds]. */
    fun decayCoef(seconds: Float, sampleRate: Float): Float =
        if (seconds <= 0f) 0f else exp(-1f / (seconds * sampleRate))
}

/** White noise in -1..1 (xorshift32). */
class Noise(seed: Int = 0x2F6E2B1) {
    private var s = if (seed == 0) 1 else seed

    fun next(): Float {
        var x = s
        x = x xor (x shl 13)
        x = x xor (x ushr 17)
        x = x xor (x shl 5)
        s = x
        return x * 4.656613e-10f
    }
}

/** Exponential ADSR envelope (analog style curves). */
class Adsr {
    private var stage = IDLE
    var level = 0f
        private set

    private var aCoef = 0f
    private var aBase = 0f
    private var dCoef = 0f
    private var dBase = 0f
    private var rCoef = 0f
    private var rBase = 0f
    private var sustain = 1f

    val isIdle: Boolean get() = stage == IDLE
    val isReleasing: Boolean get() = stage == RELEASE || stage == IDLE

    /** Times are in seconds, [rate] is how many times per second [next] is called. */
    fun set(attack: Float, decay: Float, sustain: Float, release: Float, rate: Float) {
        this.sustain = sustain
        aCoef = coef(attack * rate, ATTACK_RATIO)
        aBase = (1f + ATTACK_RATIO) * (1f - aCoef)
        dCoef = coef(decay * rate, DECAY_RATIO)
        dBase = (sustain - DECAY_RATIO) * (1f - dCoef)
        setRelease(release, rate)
    }

    fun setRelease(release: Float, rate: Float) {
        rCoef = coef(release * rate, DECAY_RATIO)
        rBase = -DECAY_RATIO * (1f - rCoef)
    }

    private fun coef(samples: Float, ratio: Float): Float =
        if (samples <= 0.5f) 0f else exp(-ln((1f + ratio) / ratio) / samples)

    /** Starts the attack from the current level (so re-triggering never clicks). */
    fun gateOn() {
        stage = ATTACK
    }

    fun gateOff() {
        if (stage != IDLE) stage = RELEASE
    }

    fun kill() {
        stage = IDLE
        level = 0f
    }

    fun next(): Float {
        when (stage) {
            ATTACK -> {
                level = aBase + level * aCoef
                if (level >= 1f) {
                    level = 1f
                    stage = DECAY
                }
            }
            DECAY -> {
                level = dBase + level * dCoef
                if (level <= sustain) {
                    level = sustain
                    stage = SUSTAIN
                }
            }
            SUSTAIN -> level = sustain
            RELEASE -> {
                level = rBase + level * rCoef
                if (level <= 0f) {
                    level = 0f
                    stage = IDLE
                }
            }
        }
        return level
    }

    private companion object {
        const val IDLE = 0
        const val ATTACK = 1
        const val DECAY = 2
        const val SUSTAIN = 3
        const val RELEASE = 4
        const val ATTACK_RATIO = 0.3f
        const val DECAY_RATIO = 0.0001f
    }
}

/** Zero-delay-feedback state variable filter (Cytomic/Zavalishin TPT form). */
class Svf {
    private var ic1 = 0f
    private var ic2 = 0f
    private var k = 1f
    private var a1 = 0f
    private var a2 = 0f
    private var a3 = 0f

    var lp = 0f
        private set
    var bp = 0f
        private set
    var hp = 0f
        private set

    /** [resonance] 0..1 where 1 is close to self oscillation. */
    fun set(cutoff: Float, resonance: Float, sampleRate: Float) {
        val fc = cutoff.coerceIn(16f, sampleRate * 0.47f)
        val g = tan(FastMath.PI_F * fc / sampleRate)
        k = 2f - 2f * resonance.coerceIn(0f, 0.98f)
        a1 = 1f / (1f + g * (g + k))
        a2 = g * a1
        a3 = g * a2
    }

    fun process(x: Float) {
        val v3 = x - ic2
        val v1 = a1 * ic1 + a2 * v3
        val v2 = ic2 + a2 * ic1 + a3 * v3
        ic1 = 2f * v1 - ic1
        ic2 = 2f * v2 - ic2
        lp = v2
        bp = v1
        hp = x - k * v1 - v2
    }

    fun reset() {
        ic1 = 0f
        ic2 = 0f
        lp = 0f
        bp = 0f
        hp = 0f
    }
}

/** One-pole low-pass (use [hp] for the complementary high-pass). */
class OnePole {
    private var z = 0f
    private var a = 1f

    fun setCutoff(cutoff: Float, sampleRate: Float) {
        a = (1f - exp(-FastMath.TWO_PI * cutoff / sampleRate)).coerceIn(0f, 1f)
    }

    fun lp(x: Float): Float {
        z += a * (x - z) + DENORMAL
        return z
    }

    fun hp(x: Float): Float = x - lp(x)

    fun reset() {
        z = 0f
    }
}

/** RBJ cookbook biquad, transposed direct form II. */
class Biquad {
    private var b0 = 1f
    private var b1 = 0f
    private var b2 = 0f
    private var a1 = 0f
    private var a2 = 0f
    private var z1 = 0f
    private var z2 = 0f

    fun process(x: Float): Float {
        val y = b0 * x + z1
        z1 = b1 * x - a1 * y + z2 + DENORMAL
        z2 = b2 * x - a2 * y
        return y
    }

    fun reset() {
        z1 = 0f
        z2 = 0f
    }

    private fun set(nb0: Float, nb1: Float, nb2: Float, a0: Float, na1: Float, na2: Float) {
        val inv = 1f / a0
        b0 = nb0 * inv
        b1 = nb1 * inv
        b2 = nb2 * inv
        a1 = na1 * inv
        a2 = na2 * inv
    }

    fun setPeaking(freq: Float, q: Float, gainDb: Float, sampleRate: Float) {
        val a = 10f.pow(gainDb / 40f)
        val w0 = FastMath.TWO_PI * freq.coerceIn(10f, sampleRate * 0.47f) / sampleRate
        val cs = cos(w0)
        val alpha = sin(w0) / (2f * q)
        set(1f + alpha * a, -2f * cs, 1f - alpha * a, 1f + alpha / a, -2f * cs, 1f - alpha / a)
    }

    fun setLowShelf(freq: Float, gainDb: Float, sampleRate: Float) {
        val a = 10f.pow(gainDb / 40f)
        val w0 = FastMath.TWO_PI * freq.coerceIn(10f, sampleRate * 0.47f) / sampleRate
        val cs = cos(w0)
        val beta = 2f * sqrt(a) * (sin(w0) * 0.70710677f)
        set(
            a * ((a + 1f) - (a - 1f) * cs + beta),
            2f * a * ((a - 1f) - (a + 1f) * cs),
            a * ((a + 1f) - (a - 1f) * cs - beta),
            (a + 1f) + (a - 1f) * cs + beta,
            -2f * ((a - 1f) + (a + 1f) * cs),
            (a + 1f) + (a - 1f) * cs - beta
        )
    }

    fun setHighShelf(freq: Float, gainDb: Float, sampleRate: Float) {
        val a = 10f.pow(gainDb / 40f)
        val w0 = FastMath.TWO_PI * freq.coerceIn(10f, sampleRate * 0.47f) / sampleRate
        val cs = cos(w0)
        val beta = 2f * sqrt(a) * (sin(w0) * 0.70710677f)
        set(
            a * ((a + 1f) + (a - 1f) * cs + beta),
            -2f * a * ((a - 1f) + (a + 1f) * cs),
            a * ((a + 1f) + (a - 1f) * cs - beta),
            (a + 1f) - (a - 1f) * cs + beta,
            2f * ((a - 1f) - (a + 1f) * cs),
            (a + 1f) - (a - 1f) * cs - beta
        )
    }

    fun setLowPass(freq: Float, q: Float, sampleRate: Float) {
        val w0 = FastMath.TWO_PI * freq.coerceIn(10f, sampleRate * 0.47f) / sampleRate
        val cs = cos(w0)
        val alpha = sin(w0) / (2f * q)
        set((1f - cs) * 0.5f, 1f - cs, (1f - cs) * 0.5f, 1f + alpha, -2f * cs, 1f - alpha)
    }

    fun setHighPass(freq: Float, q: Float, sampleRate: Float) {
        val w0 = FastMath.TWO_PI * freq.coerceIn(10f, sampleRate * 0.47f) / sampleRate
        val cs = cos(w0)
        val alpha = sin(w0) / (2f * q)
        set((1f + cs) * 0.5f, -(1f + cs), (1f + cs) * 0.5f, 1f + alpha, -2f * cs, 1f - alpha)
    }

    /** Magnitude response in dB at [freq]; handy for drawing EQ curves. */
    fun magnitudeDb(freq: Float, sampleRate: Float): Float {
        val w = FastMath.TWO_PI * freq / sampleRate
        val c1 = cos(w)
        val s1 = sin(w)
        val c2 = cos(2f * w)
        val s2 = sin(2f * w)
        val nr = b0 + b1 * c1 + b2 * c2
        val ni = -(b1 * s1 + b2 * s2)
        val dr = 1f + a1 * c1 + a2 * c2
        val di = -(a1 * s1 + a2 * s2)
        val mag = sqrt((nr * nr + ni * ni) / (dr * dr + di * di + 1e-20f))
        return 20f * log10(mag + 1e-9f)
    }
}

/** Power-of-two circular delay line with integer and linearly interpolated taps. */
class DelayLine(maxSamples: Int) {
    private val size: Int = Integer.highestOneBit((maxSamples + 4).coerceAtLeast(8) - 1) shl 1
    private val mask = size - 1
    private val buf = FloatArray(size)
    private var w = 0

    val capacity: Int get() = size - 2

    fun write(x: Float) {
        buf[w] = x
        w = (w + 1) and mask
    }

    /** Sample written [delay] writes ago (1 = the most recent one). */
    fun read(delay: Int): Float = buf[(w - delay) and mask]

    fun readFrac(delay: Float): Float {
        val d = delay.toInt()
        val f = delay - d
        val a = buf[(w - d) and mask]
        val b = buf[(w - d - 1) and mask]
        return a + (b - a) * f
    }

    fun clear() {
        java.util.Arrays.fill(buf, 0f)
    }
}

/** Tiny offset that keeps recursive filters out of the (slow) denormal range. */
const val DENORMAL = 1e-20f
