package jxpix.proger.mps

import jxpix.proger.mps.engine.DeviceType
import jxpix.proger.mps.engine.Effect
import jxpix.proger.mps.engine.PPQ
import jxpix.proger.mps.engine.ProcessContext
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/** Test signals and measurements shared by the tests of the effects. */
object Signals {
    const val RATE = 48000

    fun effect(type: DeviceType, values: Map<String, Float> = emptyMap(), rate: Int = RATE): Effect {
        val fx = type.create() as Effect
        fx.prepare(rate)
        fx.setParams(type.toArray(values))
        fx.reset()
        return fx
    }

    fun tone(hz: Double, seconds: Double, amp: Float = 0.5f): FloatArray =
        FloatArray((RATE * seconds).toInt()) { (sin(2 * Math.PI * hz * it / RATE) * amp).toFloat() }

    /** A tone with overtones, like a voice or a bowed string: harmonics falling off as 1/k. */
    fun rich(hz: Double, seconds: Double, amp: Float = 0.3f, harmonics: Int = 10): FloatArray =
        FloatArray((RATE * seconds).toInt()) { i ->
            var s = 0.0
            for (k in 1..harmonics) s += sin(2 * Math.PI * hz * k * i / RATE) / k
            (s * amp).toFloat()
        }

    fun noise(seconds: Double, amp: Float = 0.5f, seed: Long = 7): FloatArray {
        val random = java.util.Random(seed)
        return FloatArray((RATE * seconds).toInt()) { (random.nextFloat() * 2f - 1f) * amp }
    }

    /** A transport that is not running. */
    fun stopped(bpm: Double = 120.0): ProcessContext = ProcessContext().also {
        it.sampleRate = RATE
        it.bpm = bpm
        it.ticksPerFrame = bpm * PPQ / (60.0 * RATE)
    }

    /** A song that plays from [fromTick] on. */
    fun playing(bpm: Double = 120.0, fromTick: Double = 0.0): ProcessContext = stopped(bpm).also {
        it.playing = true
        it.tickPos = fromTick
    }

    /** Sends the sound through the effect in blocks, the way the engine does; the song moves on while it plays. */
    fun run(fx: Effect, l: FloatArray, r: FloatArray, ctx: ProcessContext = stopped(), block: Int = 256) {
        var pos = 0
        val bl = FloatArray(block)
        val br = FloatArray(block)
        while (pos < l.size) {
            val n = minOf(block, l.size - pos)
            System.arraycopy(l, pos, bl, 0, n)
            System.arraycopy(r, pos, br, 0, n)
            fx.process(bl, br, n, ctx)
            System.arraycopy(bl, 0, l, pos, n)
            System.arraycopy(br, 0, r, pos, n)
            pos += n
            if (ctx.playing) ctx.tickPos += n * ctx.ticksPerFrame
        }
    }

    fun rms(a: FloatArray, from: Int = 0, to: Int = a.size): Double {
        var s = 0.0
        for (i in from until to) s += a[i].toDouble() * a[i]
        return sqrt(s / max(1, to - from))
    }

    fun peak(a: FloatArray, from: Int = 0, to: Int = a.size): Float {
        var p = 0f
        for (i in from until to) p = max(p, abs(a[i]))
        return p
    }

    fun db(ratio: Double): Double = 20.0 * ln(max(ratio, 1e-12)) / ln(10.0)

    /** How bright a sound is: the level of its sample-to-sample changes against its own level. */
    fun brightness(a: FloatArray, from: Int = 1, to: Int = a.size): Double {
        var s = 0.0
        for (i in max(1, from) until to) s += (a[i] - a[i - 1]).toDouble() * (a[i] - a[i - 1])
        return sqrt(s / max(1, to - from)) / max(1e-12, rms(a, from, to))
    }

    /** Amplitude of the part of the sound that has the frequency [hz]. */
    fun level(a: FloatArray, hz: Double, from: Int = 0, to: Int = a.size): Double {
        var re = 0.0
        var im = 0.0
        for (i in from until to) {
            val w = 2 * Math.PI * hz * i / RATE
            re += a[i] * cos(w)
            im += a[i] * sin(w)
        }
        return 2.0 * sqrt(re * re + im * im) / max(1, to - from)
    }

    /** Pitch of a steady sound around [expected] Hz, from the shift at which it repeats itself. */
    fun pitch(a: FloatArray, from: Int, expected: Double, window: Int = 8192): Double {
        val guess = RATE / expected
        val lo = (guess * 0.88).toInt()
        val hi = (guess * 1.12).toInt() + 1
        val c = DoubleArray(hi + 2)
        var best = -1e30
        var bestLag = lo
        for (lag in lo - 1..hi + 1) {
            var s = 0.0
            var e = 1e-12
            for (i in from until from + window) {
                s += a[i].toDouble() * a[i + lag]
                e += a[i + lag].toDouble() * a[i + lag]
            }
            // by level, so that a sound that is fading in or out does not pull the result
            c[lag] = s / sqrt(e)
            if (lag in lo..hi && c[lag] > best) {
                best = c[lag]
                bestLag = lag
            }
        }
        val y0 = c[bestLag - 1]
        val y1 = c[bestLag]
        val y2 = c[bestLag + 1]
        val bend = y0 - 2 * y1 + y2
        val shift = if (abs(bend) < 1e-12) 0.0 else (0.5 * (y0 - y2) / bend).coerceIn(-1.0, 1.0)
        return RATE / (bestLag + shift)
    }

    fun cents(measured: Double, expected: Double): Double = 1200.0 * ln(measured / expected) / ln(2.0)

    fun note(midi: Double): Double = 440.0 * Math.pow(2.0, (midi - 69.0) / 12.0)

    /** Keeps a sound for listening when MPS_RENDER_DIR is set (see EngineTest). */
    fun save(name: String, a: FloatArray) {
        val dir = System.getenv("MPS_RENDER_DIR") ?: return
        java.io.File(dir).mkdirs()
        val data = ShortArray(a.size) { (a[it].coerceIn(-1f, 1f) * 32767f).toInt().toShort() }
        jxpix.proger.mps.engine.Wav.write(java.io.File(dir, name), jxpix.proger.mps.engine.SampleData(RATE, data))
    }

    /** Largest jump from one sample to the next. */
    fun step(a: FloatArray, from: Int = 1, to: Int = a.size): Float {
        var s = 0f
        for (i in max(1, from) until to) s = max(s, abs(a[i] - a[i - 1]))
        return s
    }
}
