package jxpix.proger.mps.engine

import jxpix.proger.mps.R
import jxpix.proger.mps.engine.dsp.Adsr
import jxpix.proger.mps.engine.dsp.Biquad
import jxpix.proger.mps.engine.dsp.FastMath
import jxpix.proger.mps.engine.dsp.Fft
import jxpix.proger.mps.engine.effects.Ott
import jxpix.proger.mps.model.Scales
import jxpix.proger.mps.model.isBlackKey
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/** How a line or a mark of a picture is meant; the screen gives each its colour and weight. */
enum class Ink { MAIN, SOFT, DIM, GRID }

/** What moves in a picture while sound runs through the device (see [Effect.scope]). */
enum class Live {
    NONE,

    /** The spectrum of what the effect puts out, over the frequency axis. */
    SPECTRUM,

    /** How many decibels the effect takes away right now: `scope[0]`. */
    REDUCTION,

    /** Where the 3D source is right now: `scope[0]` is its angle, [Picture.lo] its distance from the middle. */
    SOURCE,

    /** The note that is heard: `scope[0]`, a MIDI number or -1. */
    NOTE,

    /** The play head, over a picture that is [Picture.hi] bars long. */
    BAR,

    /** A frequency that moves: `scope[0]`, in hertz, over the frequency axis. */
    FREQ,
}

sealed class Mark

/** A line through the points x0, y0, x1, y1... (0..1 both ways, y upwards). With [fill] the room between the line and [base] is shaded. */
class Stroke(val xy: FloatArray, val ink: Ink, val fill: Boolean = false, val base: Float = 0f) : Mark()

/** A shape between two outlines over the same columns: the envelope of a sound. */
class Area(val top: FloatArray, val bottom: FloatArray, val ink: Ink) : Mark()

/** An upright bar; [width] is a share of the width of the picture, 0 = a thin one. */
class Bar(val x: Float, val from: Float, val to: Float, val ink: Ink, val width: Float = 0f) : Mark()

class Dot(val x: Float, val y: Float, val ink: Ink, val size: Float = 1f) : Mark()

/** A straight guide across the whole picture. */
class Rule(val vertical: Boolean, val at: Float, val ink: Ink = Ink.GRID, val dashed: Boolean = false) : Mark()

/** A circle or a part of one. Angles in degrees, 0 = up, clockwise; the radius is a share of the height. */
class Arc(val x: Float, val y: Float, val radius: Float, val from: Float, val sweep: Float, val ink: Ink, val fill: Boolean = false) : Mark()

/** A short text. [unit] says how [value] is to be written: the screen knows the words of the language. */
class Caption(val x: Float, val y: Float, val text: String, val align: Int = 0, val value: Float = 0f, val unit: Int = PLAIN) : Mark() {
    companion object {
        const val PLAIN = 0
        const val SECONDS = 1
        const val LEFT = 2
        const val RIGHT = 3
    }
}

/**
 * What a device does with its current settings, as lines to draw. A [square] picture is a view
 * from above (a head, a stage): it keeps its proportions and stands in the middle of the room it
 * gets, where it may reach out of its square to the sides; the others stretch to the room they get.
 */
class Picture(val marks: List<Mark>, val live: Live = Live.NONE, val square: Boolean = false, val lo: Float = 0f, val hi: Float = 0f)

/**
 * Runs devices on test signals, away from the audio thread: where a picture can be had from the
 * device itself, it is. The reverb draws the tail it really leaves, a drum the hit it really makes.
 */
object Lab {
    const val RATE = 48000

    /** Enough for a picture of how a level moves. */
    const val SLOW = 12000
    private const val BLOCK = 256

    fun context(rate: Int, bpm: Float, playing: Boolean): ProcessContext = ProcessContext().apply {
        sampleRate = rate
        this.bpm = bpm.toDouble()
        this.playing = playing
        tickPos = 0.0
        ticksPerFrame = bpm * PPQ / 60.0 / rate
        ticksPerBar = 4 * PPQ
    }

    fun effect(typeId: String, values: FloatArray, rate: Int): Effect? {
        val fx = Devices[typeId]?.create() as? Effect ?: return null
        fx.prepare(rate)
        fx.setParams(values)
        return fx
    }

    /** Sends [l] and [r] through the effect, in place, block by block the way the engine does. */
    fun run(fx: Effect, l: FloatArray, r: FloatArray, ctx: ProcessContext) {
        val bl = FloatArray(BLOCK)
        val br = FloatArray(BLOCK)
        var at = 0
        while (at < l.size) {
            val n = min(BLOCK, l.size - at)
            System.arraycopy(l, at, bl, 0, n)
            System.arraycopy(r, at, br, 0, n)
            fx.process(bl, br, n, ctx)
            System.arraycopy(bl, 0, l, at, n)
            System.arraycopy(br, 0, r, at, n)
            if (ctx.playing) ctx.tickPos += n * ctx.ticksPerFrame
            at += n
        }
    }

    /** How loud (in dB) each of [freqs] comes out of the effect, from what it answers to a single click. */
    fun response(fx: Effect, freqs: FloatArray, rate: Int, size: Int = 8192, click: Float = 1f): FloatArray {
        val warm = 1024
        val l = FloatArray(warm + size)
        val r = FloatArray(warm + size)
        l[warm] = click
        r[warm] = click
        run(fx, l, r, context(rate, 120f, false))
        val re = FloatArray(size)
        val im = FloatArray(size)
        // the end of a tail that is still ringing fades out, so that cutting it leaves no ripples
        val fade = size / 4
        for (i in 0 until size) {
            val w = if (i < size - fade) 1f else 0.5f + 0.5f * cos(PI * (i - (size - fade)) / fade).toFloat()
            re[i] = l[warm + i] * w
        }
        Fft(size).transform(re, im)
        return FloatArray(freqs.size) { k ->
            val bin = (freqs[k] * size / rate).coerceIn(0f, size / 2f - 1.001f)
            val i = bin.toInt()
            val t = bin - i
            val a = sqrt(re[i] * re[i] + im[i] * im[i])
            val b = sqrt(re[i + 1] * re[i + 1] + im[i + 1] * im[i + 1])
            20f * log10((a + (b - a) * t) / click + 1e-9f)
        }
    }

    /** What the effect makes of a steady level of 1: its gain, moment by moment, left and right. */
    fun gains(fx: Effect, seconds: Float, rate: Int, bpm: Float, playing: Boolean): Pair<FloatArray, FloatArray> {
        val n = max(2, (seconds * rate).toInt())
        val l = FloatArray(n) { 1f }
        val r = FloatArray(n) { 1f }
        run(fx, l, r, context(rate, bpm, playing))
        return l to r
    }

    /**
     * Which moment of the sound that came in is heard at each moment of what goes out (0..1 of the
     * stretch, NaN where nothing is heard) and how loud. The left side carries the time, the right
     * side a steady level: an effect that plays things again or slower does the same to both.
     */
    fun replay(fx: Effect, seconds: Float, rate: Int, bpm: Float): Pair<FloatArray, FloatArray> {
        val n = max(2, (seconds * rate).toInt())
        val l = FloatArray(n) { 0.25f + 0.5f * it / n }
        val r = FloatArray(n) { 1f }
        run(fx, l, r, context(rate, bpm, true))
        val time = FloatArray(n) { if (r[it] > 0.02f) (l[it] / r[it] - 0.25f) / 0.5f else Float.NaN }
        return time to r
    }

    /** A sine of [period] samples through the effect, once it has settled: [cycles] of it, before and after. */
    fun sine(fx: Effect, period: Int, cycles: Int, level: Float, rate: Int): Pair<FloatArray, FloatArray> {
        val warm = period * 12
        val n = warm + period * cycles
        val dry = FloatArray(n) { level * sin(2.0 * PI * it / period).toFloat() }
        val l = dry.copyOf()
        val r = dry.copyOf()
        run(fx, l, r, context(rate, 120f, false))
        return dry.copyOfRange(warm, n) to l.copyOfRange(warm, n)
    }

    /** A note of an instrument, both sides added together. The key is let go after [hold] seconds. */
    fun note(typeId: String, values: FloatArray, pitch: Int, seconds: Float, hold: Float, rate: Int = RATE): FloatArray {
        val inst = Devices[typeId]?.create() as? Instrument ?: return FloatArray(0)
        inst.prepare(rate)
        inst.setParams(values)
        val n = (seconds * rate).toInt()
        val out = FloatArray(n)
        val bl = FloatArray(BLOCK)
        val br = FloatArray(BLOCK)
        val off = (hold * rate).toInt()
        var released = false
        inst.noteOn(pitch, 1f)
        var at = 0
        while (at < n) {
            val c = min(BLOCK, n - at)
            if (!released && at >= off) {
                inst.noteOff(pitch)
                released = true
            }
            bl.fill(0f)
            br.fill(0f)
            inst.render(bl, br, 0, c)
            for (i in 0 until c) out[at + i] = (bl[i] + br[i]) * 0.5f
            at += c
        }
        return out
    }
}

/**
 * The picture of every instrument and effect: what a desktop plug-in shows above its knobs. A
 * picture is made of plain marks, so one piece of drawing code serves them all, and it can be
 * held against the sound in a test.
 */
object Pictures {
    private const val RATE = Lab.RATE.toFloat()

    /** Points of a curve. */
    private const val N = 96

    /** Echo times of the delay in beats, by its "sync" choice; the first is "off". */
    private val DELAY_BEATS = doubleArrayOf(0.0, 0.125, 1.0 / 6, 0.25, 1.0 / 3, 0.5, 0.75, 2.0 / 3, 1.0, 1.5, 2.0)
    private val TREMOLO_TICKS = intArrayOf(0, PPQ * 2, PPQ, PPQ / 2, PPQ / 3, PPQ / 4)
    private val HALF_BARS = floatArrayOf(0.25f, 0.5f, 1f, 2f)
    private val REPEAT_BARS = floatArrayOf(0.5f, 1f, 2f, 4f)

    /** Where a frequency stands on the axis of a picture: 20 Hz at the left edge, 20 kHz at the right. */
    fun xOf(freq: Float): Float = (ln(freq.coerceAtLeast(1f) / 20f) / ln(1000f)).coerceIn(0f, 1f)

    fun freqAt(x: Float): Float = 20f * 1000f.pow(x)

    /** Whether there is a picture for this group of knobs of this device. Effects have one group, 0. */
    fun has(typeId: String, group: Int): Boolean = when (typeId) {
        "synth" -> group == R.string.g_osc1 || group == R.string.g_osc2 || group == R.string.g_filter || group == R.string.g_amp_env ||
            group == R.string.g_filter_env || group == R.string.g_lfo
        "drum" -> group == R.string.g_drum
        "fm" -> group == R.string.g_fm || group == R.string.g_amp_env
        "strings" -> group == R.string.g_string
        "sampler" -> group == R.string.g_amp_env
        else -> Devices[typeId]?.isInstrument == false
    }

    /**
     * The picture, or null when the device has none. [v] gives the value of a knob by its id,
     * [bpm] and [keyMask] are the tempo and the key of the song. May take a few milliseconds:
     * some pictures are made by running the device.
     */
    fun of(typeId: String, group: Int, v: (String) -> Float, bpm: Float = 120f, keyMask: Int = 0): Picture? {
        if (!has(typeId, group)) return null
        val type = Devices[typeId] ?: return null
        val s = Sketch()
        return when (typeId) {
            "eq" -> eq(s, v)
            "filter" -> filter(s, v)
            "comp" -> compressor(s, v)
            "limiter" -> limiter(s, v)
            "gate" -> gate(s, v)
            "deess" -> deEsser(s, v)
            "ott" -> ott(s, v)
            "reverb" -> reverb(s, type, v)
            "delay" -> delay(s, v, bpm)
            "dist" -> wave(s, type, v, level = 0.5f)
            "crush" -> wave(s, type, v, level = 0.8f)
            "tape" -> tape(s, type, v)
            "chorus" -> chorus(s, v)
            "flanger" -> flanger(s, v)
            "phaser" -> phaser(s, type, v)
            "tremolo" -> tremolo(s, type, v, bpm)
            "pump" -> pump(s, type, v, bpm)
            "stereo" -> stereo(s, v)
            "space3d" -> space(s, v)
            "tune" -> tune(s, v, keyMask)
            "pitch" -> shift(s, v)
            "halftime" -> halfTime(s, type, v, bpm)
            "repeat" -> repeat(s, type, v, bpm)
            "wah" -> wah(s, v)
            "synth" -> when (group) {
                R.string.g_osc1 -> oscillator(s, v("osc1Wave").roundToInt(), v("osc1Level"), v("pulseWidth"))
                R.string.g_osc2 -> oscillator(s, v("osc2Wave").roundToInt(), v("osc2Level"), v("pulseWidth"))
                R.string.g_filter -> synthFilter(s, v)
                R.string.g_amp_env -> envelope(s, v("ampAttack"), v("ampDecay"), v("ampSustain"), v("ampRelease"))
                R.string.g_filter_env -> envelope(s, v("fltAttack"), v("fltDecay"), v("fltSustain"), v("fltRelease"))
                else -> lfo(s, v("lfoWave").roundToInt(), v("lfoRate"))
            }
            "drum" -> hit(s, Lab.note("drum", raw(type, v), 60, 1f, 1f), 1f, Float.NaN)
            "strings" -> hit(s, Lab.note("strings", raw(type, v), 52, 3f, 2f), 3f, 2f / 3f)
            "fm" -> if (group == R.string.g_fm) fm(s, type, v) else envelope(s, v("attack"), v("decay"), v("sustain"), v("release"))
            "sampler" -> envelope(s, v("attack"), v("decay"), v("sustain"), v("release"))
            else -> null
        }
    }

    // ------------------------------------------------------------------ what the pictures are drawn with

    private class Sketch {
        val marks = ArrayList<Mark>()

        /** A curve over the whole width: [y] of x, both 0..1. */
        fun plot(ink: Ink, fill: Boolean = false, base: Float = 0f, points: Int = N, y: (Float) -> Float) {
            val xy = FloatArray((points + 1) * 2)
            for (i in 0..points) {
                val x = i / points.toFloat()
                val value = y(x)
                xy[i * 2] = x
                xy[i * 2 + 1] = if (value.isNaN()) 0f else value.coerceIn(0f, 1f)
            }
            marks += Stroke(xy, ink, fill, base)
        }

        /** Samples as a line: the values [low] and [high] are drawn at the heights [bottom] and [top]. */
        fun samples(data: FloatArray, ink: Ink, low: Float, high: Float, bottom: Float = 0f, top: Float = 1f, fill: Boolean = false) {
            val points = min(data.size - 1, 240).coerceAtLeast(1)
            val xy = FloatArray((points + 1) * 2)
            for (i in 0..points) {
                val value = data[(i.toLong() * (data.size - 1) / points).toInt()]
                xy[i * 2] = i / points.toFloat()
                xy[i * 2 + 1] = (bottom + (top - bottom) * ((value - low) / (high - low))).coerceIn(0f, 1f)
            }
            marks += Stroke(xy, ink, fill, bottom)
        }

        fun frequencies() {
            for ((f, text) in listOf(100f to "100", 1000f to "1k", 10000f to "10k")) {
                marks += Rule(true, xOf(f))
                marks += Caption(xOf(f) + 0.008f, 0.03f, text, -1)
            }
        }

        /** A frequency response between [lo] and [hi] decibels, with the line of "nothing changed". */
        fun response(lo: Float, hi: Float, ink: Ink, fill: Boolean = false, db: (Float) -> Float) {
            plot(ink, fill, base = -lo / (hi - lo)) { x -> (db(freqAt(x)) - lo) / (hi - lo) }
        }

        fun zero(lo: Float, hi: Float) {
            marks += Rule(false, -lo / (hi - lo), Ink.DIM)
        }

        /** How long the stretch of time in the picture is. */
        fun seconds(length: Float) {
            marks += Caption(0.985f, 0.03f, "", 1, length, Caption.SECONDS)
        }

        /** The frame of a "what goes in / what comes out" picture, [lo]..[hi] dB in and [lo]..[top] dB out. */
        fun levels(lo: Float, hi: Float, top: Float) {
            var d = -12f
            while (d > lo) {
                if (d < hi) marks += Rule(true, (d - lo) / (hi - lo))
                marks += Rule(false, (d - lo) / (top - lo))
                d -= 12f
            }
            if (top > 0f) marks += Rule(false, -lo / (top - lo))
            // what comes out when nothing is done
            marks += Stroke(floatArrayOf(0f, 0f, 1f, (hi - lo) / (top - lo)), Ink.DIM)
        }

        fun transfer(lo: Float, hi: Float, top: Float, ink: Ink, out: (Float) -> Float) {
            plot(ink, points = 120) { x -> (out(lo + (hi - lo) * x) - lo) / (top - lo) }
        }

        fun done(live: Live = Live.NONE, square: Boolean = false, lo: Float = 0f, hi: Float = 0f) = Picture(marks, live, square, lo, hi)
    }

    private fun db(gain: Float): Float = 20f * log10(max(gain, 1e-9f))

    private fun gain(db: Float): Float = 10f.pow(db / 20f)

    /** The settings as the device reads them, by position, with some of them replaced; nothing is clamped. */
    private fun raw(type: DeviceType, v: (String) -> Float, vararg replaced: Pair<String, Float>): FloatArray {
        val out = FloatArray(type.params.size) { v(type.params[it].id) }
        for ((id, value) in replaced) {
            val i = type.indexOf(id)
            if (i >= 0) out[i] = value
        }
        return out
    }

    /** The longest stretch of time, of a few round ones, in which a movement of [period] seconds does not repeat more than eight times. */
    private fun stretch(period: Float): Float {
        for (w in floatArrayOf(4f, 2f, 1f, 0.5f, 0.25f)) if (w / period <= 8.001f) return w
        return 0.125f
    }

    /**
     * How loud the state variable filter of the engine lets [f] through.
     * [kind]: 0 low-pass, 1 high-pass, 2 band-pass, 3 notch.
     */
    fun svf(kind: Int, f: Float, cutoff: Float, resonance: Float, rate: Float = RATE): Float {
        val fc = cutoff.coerceIn(16f, rate * 0.47f)
        val w = (tan(PI * min(f, rate * 0.499f) / rate) / tan(PI * fc / rate)).toFloat()
        val k = 2f - 2f * resonance.coerceIn(0f, 0.98f)
        val den = sqrt((1f - w * w) * (1f - w * w) + k * w * k * w) + 1e-12f
        return when (kind) {
            0 -> 1f / den
            1 -> w * w / den
            2 -> w / den
            else -> abs(1f - w * w) / den
        }
    }

    // ------------------------------------------------------------------ tone

    /** The equaliser, in decibels at [f]: the three bands of the effect, made the way the effect makes them. */
    fun eqDb(v: (String) -> Float): (Float) -> Float {
        val low = Biquad().apply { setLowShelf(v("lowFreq"), v("lowGain"), RATE) }
        val mid = Biquad().apply { setPeaking(v("midFreq"), v("midQ"), v("midGain"), RATE) }
        val high = Biquad().apply { setHighShelf(v("highFreq"), v("highGain"), RATE) }
        return { f -> low.magnitudeDb(f, RATE) + mid.magnitudeDb(f, RATE) + high.magnitudeDb(f, RATE) }
    }

    private fun eq(s: Sketch, v: (String) -> Float): Picture {
        val curve = eqDb(v)
        s.frequencies()
        s.zero(-24f, 24f)
        s.response(-24f, 24f, Ink.MAIN, fill = true, db = curve)
        for (f in floatArrayOf(v("lowFreq"), v("midFreq"), v("highFreq"))) {
            s.marks += Dot(xOf(f), ((curve(f) + 24f) / 48f).coerceIn(0f, 1f), Ink.MAIN)
        }
        return s.done(Live.SPECTRUM)
    }

    fun filterDb(type: Int, cutoff: Float, resonance: Float): (Float) -> Float = { f -> db(svf(type.coerceIn(0, 3), f, cutoff, resonance)) }

    private fun filter(s: Sketch, v: (String) -> Float): Picture {
        val type = v("type").roundToInt()
        val depth = v("lfoDepth")
        s.frequencies()
        s.zero(-36f, 30f)
        if (depth > 0.005f) {
            // how far the sweep carries the filter, down and up
            s.response(-36f, 30f, Ink.DIM, db = filterDb(type, v("cutoff") * 2f.pow(-3f * depth), v("resonance")))
            s.response(-36f, 30f, Ink.DIM, db = filterDb(type, v("cutoff") * 2f.pow(3f * depth), v("resonance")))
        }
        s.response(-36f, 30f, Ink.MAIN, fill = true, db = filterDb(type, v("cutoff"), v("resonance")))
        return s.done(Live.SPECTRUM)
    }

    private fun synthFilter(s: Sketch, v: (String) -> Float): Picture {
        val type = v("filterType").roundToInt()
        fun curve(cutoff: Float): (Float) -> Float = { f ->
            when (type) {
                0 -> db(svf(0, f, cutoff, v("resonance")))
                // the steep one is the same filter twice, a little less sharp
                1 -> 2f * db(svf(0, f, cutoff, v("resonance") * 0.8f))
                2 -> db(svf(1, f, cutoff, v("resonance")))
                else -> db(svf(2, f, cutoff, v("resonance")))
            }
        }
        s.frequencies()
        s.zero(-36f, 30f)
        val amount = v("envAmount")
        // where the envelope takes the filter at its peak: eight octaves at the most
        if (abs(amount) > 0.01f) s.response(-36f, 30f, Ink.DIM, db = curve((v("cutoff") * 2f.pow(8f * amount)).coerceIn(20f, RATE * 0.45f)))
        s.response(-36f, 30f, Ink.MAIN, fill = true, db = curve(v("cutoff")))
        return s.done()
    }

    /** The auto-wah with its filter at [cutoff]: a resonant low-pass next to the untouched sound. */
    fun wahDb(v: (String) -> Float, cutoff: Float): (Float) -> Float {
        val reso = v("reso")
        val k = 2f - 2f * (0.25f + 0.7f * reso).coerceIn(0f, 0.98f)
        val wet = v("mix") / (1f + 1.2f * reso * reso)
        val dry = 1f - v("mix")
        val fc = cutoff.coerceIn(16f, RATE * 0.47f)
        return { f ->
            val w = (tan(PI * min(f, RATE * 0.499f) / RATE) / tan(PI * fc / RATE)).toFloat()
            val a = 1f - w * w
            val b = k * w
            val d = a * a + b * b + 1e-12f
            // the low-pass is 1 / (a + jb)
            val re = dry + wet * a / d
            val im = -wet * b / d
            db(sqrt(re * re + im * im))
        }
    }

    private fun wah(s: Sketch, v: (String) -> Float): Picture {
        val open = min(RATE * 0.4f, v("freq") * 2f.pow(v("range") * 4.5f))
        s.frequencies()
        s.zero(-36f, 30f)
        // loud playing takes the filter up to here
        if (v("range") > 0.005f) s.response(-36f, 30f, Ink.DIM, db = wahDb(v, open))
        s.response(-36f, 30f, Ink.MAIN, fill = true, db = wahDb(v, v("freq")))
        return s.done(Live.FREQ)
    }

    /** The comb of the flanger with its delay standing still at [delayMs]. */
    fun flangerDb(v: (String) -> Float, delayMs: Float): (Float) -> Float {
        val mix = v("mix")
        val dry = min(1f, 2f * (1f - mix))
        val wet = min(1f, 2f * mix)
        val fb = v("feedback")
        val samples = delayMs * RATE / 1000f + 1f
        return { f ->
            val w = 2.0 * PI * f / RATE * samples
            val c = cos(w).toFloat()
            val sn = sin(w).toFloat()
            // z^-D / (1 - fb z^-D), with z^-D = c - j sn
            val dr = 1f - fb * c
            val di = fb * sn
            val d = dr * dr + di * di + 1e-12f
            val re = dry + wet * (c * dr - sn * di) / d
            val im = wet * (-sn * dr - c * di) / d
            db(sqrt(re * re + im * im))
        }
    }

    private fun flanger(s: Sketch, v: (String) -> Float): Picture {
        val base = v("delay")
        val swing = base * v("depth") * 0.48f
        s.frequencies()
        s.zero(-30f, 30f)
        // the comb in the middle of its sweep: three of them on top of each other would be a thicket
        s.plot(Ink.MAIN, points = 320) { x -> (flangerDb(v, base - swing)(freqAt(x)) + 30f) / 60f }
        return s.done()
    }

    private fun phaser(s: Sketch, type: DeviceType, v: (String) -> Float): Picture {
        val freqs = FloatArray(N + 1) { freqAt(it / N.toFloat()) }
        fun at(centre: Float, ink: Ink) {
            val fx = Lab.effect("phaser", raw(type, v, "depth" to 0f, "center" to centre), Lab.RATE) ?: return
            val curve = Lab.response(fx, freqs, Lab.RATE, 4096)
            s.plot(ink) { x -> (curve[(x * N).roundToInt().coerceIn(0, N)] + 24f) / 48f }
        }
        s.frequencies()
        s.zero(-24f, 24f)
        val depth = v("depth")
        if (depth > 0.005f) {
            at(v("center") * 2f.pow(-2f * depth), Ink.DIM)
            at(v("center") * 2f.pow(2f * depth), Ink.DIM)
        }
        at(v("center"), Ink.MAIN)
        return s.done()
    }

    /** A sine before and after the effect: what distortion and the bit crusher do to a wave. */
    private fun wave(s: Sketch, type: DeviceType, v: (String) -> Float, level: Float): Picture {
        val fx = Lab.effect(type.id, raw(type, v), Lab.RATE) ?: return s.done()
        val (dry, wet) = Lab.sine(fx, 256, 2, level, Lab.RATE)
        s.marks += Rule(false, 0.5f, Ink.GRID)
        s.samples(dry, Ink.DIM, -1.05f, 1.05f)
        s.samples(wet, Ink.MAIN, -1.05f, 1.05f)
        return s.done()
    }

    private fun tape(s: Sketch, type: DeviceType, v: (String) -> Float): Picture {
        // below: what the tape does to a wave, with the speed held steady so that the two can be compared
        val fx = Lab.effect("tape", raw(type, v, "wow" to 0f, "flutter" to 0f), Lab.RATE) ?: return s.done()
        val (dry, wet) = Lab.sine(fx, 256, 2, 0.7f, Lab.RATE)
        // the tape plays a few milliseconds late: line the two waves up again
        var shift = 0
        var best = -1f
        for (lag in 0 until 256) {
            var sum = 0f
            for (i in 0 until 256) sum += dry[i] * wet[(i + lag) % 512]
            if (sum > best) {
                best = sum
                shift = lag
            }
        }
        val lined = FloatArray(512) { wet[(it + shift) % 512] }
        s.marks += Rule(false, 0.34f, Ink.GRID)
        s.samples(dry, Ink.DIM, -1.05f, 1.05f, 0f, 0.68f)
        s.samples(lined, Ink.MAIN, -1.05f, 1.05f, 0f, 0.68f)
        // above: how the speed of the tape wanders in four seconds, slowly (wow) and quickly (flutter)
        // (the slow sway is ten times as wide, the quick one eleven times as fast: to the ear they weigh about the same)
        s.plot(Ink.SOFT, points = 240) { x ->
            val t = x * 4f
            0.85f + 0.13f * (0.47f * v("wow") * sin(2.0 * PI * 0.55 * t).toFloat() + 0.53f * v("flutter") * sin(2.0 * PI * 6.3 * t).toFloat())
        }
        return s.done()
    }

    // ------------------------------------------------------------------ dynamics

    /** What comes out of the compressor, in dB, for a steady sound of [level] dB going in. */
    fun compressorDb(v: (String) -> Float, level: Float): Float {
        val threshold = v("threshold")
        val slope = 1f - 1f / v("ratio")
        val squeezed = if (level > threshold) -slope * (level - threshold) else 0f
        val mix = v("mix")
        return level + db(1f - mix + mix * gain(v("makeup") + squeezed))
    }

    private fun compressor(s: Sketch, v: (String) -> Float): Picture {
        s.levels(-60f, 0f, 12f)
        s.marks += Rule(true, (v("threshold") + 60f) / 60f, Ink.DIM, dashed = true)
        s.transfer(-60f, 0f, 12f, Ink.MAIN) { compressorDb(v, it) }
        return s.done(Live.REDUCTION)
    }

    fun limiterDb(v: (String) -> Float, level: Float): Float = min(level + v("gain"), v("ceiling"))

    private fun limiter(s: Sketch, v: (String) -> Float): Picture {
        s.levels(-36f, 6f, 6f)
        s.marks += Rule(false, (v("ceiling") + 36f) / 42f, Ink.DIM, dashed = true)
        s.transfer(-36f, 6f, 6f, Ink.MAIN) { limiterDb(v, it) }
        return s.done(Live.REDUCTION)
    }

    /** What the gate leaves of a steady sound of [level] dB. */
    fun gateDb(v: (String) -> Float, level: Float): Float = if (level >= v("threshold")) level else level - v("range")

    private fun gate(s: Sketch, v: (String) -> Float): Picture {
        s.levels(-80f, 0f, 0f)
        s.marks += Rule(true, (v("threshold") + 80f) / 80f, Ink.DIM, dashed = true)
        s.transfer(-80f, 0f, 0f, Ink.MAIN) { gateDb(v, it) }
        return s.done(Live.REDUCTION)
    }

    /** The de-esser at work on a hiss that is as loud as it gets: everything above the split is taken back by [cut] dB. */
    fun deEsserDb(v: (String) -> Float, cut: Float): (Float) -> Float {
        val split = v("freq") * 0.7f
        val g = gain(-cut)
        return { f ->
            // two halves of a crossover that add up to the sound again; only the upper half is turned down
            val r = (f / split).pow(4)
            db((1f + g * r) / (1f + r))
        }
    }

    private fun deEsser(s: Sketch, v: (String) -> Float): Picture {
        s.frequencies()
        s.zero(-30f, 6f)
        s.marks += Rule(true, xOf(v("freq")), Ink.DIM, dashed = true)
        s.response(-30f, 6f, Ink.MAIN, fill = true, db = deEsserDb(v, v("amount")))
        return s.done(Live.REDUCTION)
    }

    private fun ott(s: Sketch, v: (String) -> Float): Picture {
        s.levels(-72f, 0f, 12f)
        val ids = arrayOf("low", "mid", "high")
        val inks = arrayOf(Ink.DIM, Ink.MAIN, Ink.SOFT)
        for (band in intArrayOf(0, 2, 1)) {
            s.transfer(-72f, 0f, 12f, inks[band]) { level -> level + Ott.changeDb(band, level, v("depth"), v(ids[band])) + v("out") }
        }
        return s.done()
    }

    // ------------------------------------------------------------------ time

    private fun reverb(s: Sketch, type: DeviceType, v: (String) -> Float): Picture {
        val seconds = 3f
        val fx = Lab.effect("reverb", raw(type, v, "mix" to 1f), Lab.RATE) ?: return s.done()
        val n = (seconds * Lab.RATE).toInt()
        val l = FloatArray(n)
        val r = FloatArray(n)
        l[0] = 1f
        r[0] = 1f
        Lab.run(fx, l, r, Lab.context(Lab.RATE, 120f, false))
        // the loudest moment of every 20 milliseconds
        val window = Lab.RATE / 50
        val peaks = FloatArray(n / window) { w ->
            var peak = 0f
            for (i in w * window until (w + 1) * window) peak = max(peak, max(abs(l[i]), abs(r[i])))
            peak
        }
        val top = max(peaks.max(), 1e-6f)
        for (t in 1..2) s.marks += Rule(true, t / seconds)
        // 60 dB from the loudest moment down to the bottom of the picture
        s.plot(Ink.MAIN, fill = true, points = peaks.size - 1) { x -> 1f + db(peaks[(x * (peaks.size - 1)).roundToInt()] / top) / 60f }
        s.seconds(seconds)
        return s.done()
    }

    /** The echoes of the delay: when they come (seconds), how loud (1 = as loud as the sound) and on which side (-1 left, 1 right, 0 both). */
    fun echoes(v: (String) -> Float, bpm: Float, until: Float): List<FloatArray> {
        val sync = v("sync").roundToInt()
        val time = if (sync in 1 until DELAY_BEATS.size) (DELAY_BEATS[sync] * 60.0 / bpm).toFloat() else v("time") / 1000f
        val feedback = v("feedback")
        val ping = v("pingPong") > 0.5f
        val out = ArrayList<FloatArray>()
        var k = 1
        while (k * time <= until && out.size < 200) {
            // bouncing from side to side, the first echo is on the left and the feedback comes in on every second one
            val level = if (ping) feedback.pow((k - 1) / 2) else feedback.pow(k - 1)
            if (level < 0.004f) break
            out += floatArrayOf(k * time, level, if (!ping) 0f else if (k % 2 == 1) -1f else 1f)
            k++
        }
        return out
    }

    private fun delay(s: Sketch, v: (String) -> Float, bpm: Float): Picture {
        val seconds = 2f
        s.marks += Rule(false, 0.5f, Ink.GRID)
        s.marks += Rule(true, 0.5f)
        // the sound itself, then its echoes: upwards on the left side, downwards on the right
        s.marks += Bar(0.004f, 0.06f, 0.94f, Ink.DIM)
        for (e in echoes(v, bpm, seconds)) {
            val h = 0.44f * (1f + db(e[1]) / 48f).coerceIn(0.04f, 1f)
            val x = (e[0] / seconds).coerceIn(0f, 1f)
            if (e[2] <= 0f) s.marks += Bar(x, 0.5f, 0.5f + h, Ink.MAIN)
            if (e[2] >= 0f) s.marks += Bar(x, 0.5f - h, 0.5f, Ink.MAIN)
        }
        s.marks += Caption(0.02f, 0.88f, "", -1, unit = Caption.LEFT)
        s.marks += Caption(0.02f, 0.03f, "", -1, unit = Caption.RIGHT)
        s.seconds(seconds)
        return s.done()
    }

    private fun chorus(s: Sketch, v: (String) -> Float): Picture {
        val base = v("delay")
        val swing = min(v("depth") * 6f, base - 1f).coerceAtLeast(0f)
        val seconds = stretch(1f / v("rate"))
        // how late the second voice is: the longer the delay the higher the line, and it sways as far as the depth
        // says (six milliseconds at the most), the two sides a quarter of a turn apart
        val middle = 0.22f + 0.56f * (base - 2f) / 28f
        fun height(turn: Double) = middle + 0.2f * swing / 6f * sin(2.0 * PI * turn).toFloat()
        s.marks += Rule(false, middle, Ink.DIM)
        s.plot(Ink.SOFT, points = 200) { x -> height(x * seconds * v("rate") + 0.25) }
        s.plot(Ink.MAIN, points = 200) { x -> height((x * seconds * v("rate")).toDouble()) }
        s.seconds(seconds)
        return s.done()
    }

    private fun tremolo(s: Sketch, type: DeviceType, v: (String) -> Float, bpm: Float): Picture {
        val sync = v("sync").roundToInt()
        val period = if (sync in 1 until TREMOLO_TICKS.size) TREMOLO_TICKS[sync] / PPQ.toFloat() * 60f / bpm else 1f / v("rate")
        val seconds = stretch(period)
        val fx = Lab.effect("tremolo", raw(type, v), Lab.SLOW) ?: return s.done()
        val (l, r) = Lab.gains(fx, seconds, Lab.SLOW, bpm, playing = true)
        if (v("stereo") > 0.005f) s.samples(r, Ink.SOFT, 0f, 1f, 0.04f, 0.96f)
        s.samples(l, Ink.MAIN, 0f, 1f, 0.04f, 0.96f, fill = true)
        s.seconds(seconds)
        return s.done()
    }

    private fun pump(s: Sketch, type: DeviceType, v: (String) -> Float, bpm: Float): Picture {
        val seconds = 4f * 60f / bpm
        val fx = Lab.effect("pump", raw(type, v), Lab.SLOW) ?: return s.done()
        val (l, _) = Lab.gains(fx, seconds, Lab.SLOW, bpm, playing = true)
        for (beat in 1..3) s.marks += Rule(true, beat / 4f)
        s.samples(l, Ink.MAIN, 0f, 1f, 0.04f, 0.96f, fill = true)
        return s.done(Live.BAR, hi = 1f)
    }

    private fun halfTime(s: Sketch, type: DeviceType, v: (String) -> Float, bpm: Float): Picture {
        val loop = HALF_BARS[v("length").roundToInt().coerceIn(0, HALF_BARS.size - 1)]
        return replay(s, type, raw(type, v, "mix" to 1f), bars = 2f * loop, bpm = bpm)
    }

    private fun repeat(s: Sketch, type: DeviceType, v: (String) -> Float, bpm: Float): Picture {
        val every = REPEAT_BARS[v("every").roundToInt().coerceIn(0, REPEAT_BARS.size - 1)]
        return replay(s, type, raw(type, v, "mix" to 1f), bars = every, bpm = bpm)
    }

    /**
     * Time against time: along the bottom the song as it goes by, upwards the moment of it that is
     * heard. Untouched sound is the straight line from corner to corner; a repeat goes back and
     * climbs again, slowed sound climbs half as steeply.
     */
    private fun replay(s: Sketch, type: DeviceType, values: FloatArray, bars: Float, bpm: Float): Picture {
        val seconds = bars * 4f * 60f / bpm
        val fx = Lab.effect(type.id, values, Lab.SLOW) ?: return s.done()
        val (time, level) = Lab.replay(fx, seconds, Lab.SLOW, bpm)
        val beats = (bars * 4f).roundToInt()
        val lines = if (beats <= 8) beats else (bars).roundToInt()
        for (i in 1 until lines) s.marks += Rule(true, i / lines.toFloat())
        s.marks += Stroke(floatArrayOf(0f, 0f, 1f, 1f), Ink.DIM)
        // how loud it is, along the bottom
        s.samples(level, Ink.SOFT, 0f, 1f, 0f, 0.16f, fill = true)
        // the line breaks where the sound jumps
        val points = 240
        var run = ArrayList<Float>()
        var last = Float.NaN
        for (i in 0..points) {
            val value = time[(i.toLong() * (time.size - 1) / points).toInt()]
            val jump = value.isNaN() || (!last.isNaN() && abs(value - last) > 0.04f)
            if (jump && run.size >= 4) s.marks += Stroke(run.toFloatArray(), Ink.MAIN)
            if (jump) run = ArrayList()
            if (!value.isNaN()) {
                run += i / points.toFloat()
                run += value.coerceIn(0f, 1f)
            }
            last = value
        }
        if (run.size >= 4) s.marks += Stroke(run.toFloatArray(), Ink.MAIN)
        return s.done(Live.BAR, hi = bars)
    }

    // ------------------------------------------------------------------ space

    private fun stereo(s: Sketch, v: (String) -> Float): Picture {
        val width = v("width").coerceIn(0f, 2f)
        val half = 45f * width
        val x = 0.5f
        val y = 0.1f
        val radius = 0.8f
        // the stage as it was: a quarter of a circle in front of the listener
        s.marks += Arc(x, y, radius, -45f, 90f, Ink.DIM)
        if (half > 0.5f) {
            s.marks += Arc(x, y, radius, -half, 2f * half, Ink.SOFT, fill = true)
            s.marks += Arc(x, y, radius, -half, 2f * half, Ink.MAIN)
            for (side in floatArrayOf(-1f, 1f)) {
                val a = Math.toRadians((side * half).toDouble())
                s.marks += Stroke(floatArrayOf(x, y, x + radius * sin(a).toFloat(), y + radius * cos(a).toFloat()), Ink.MAIN)
            }
        } else {
            s.marks += Stroke(floatArrayOf(x, y, x, y + radius), Ink.MAIN)
        }
        // the bass that is kept in the middle: the higher the limit, the longer the core
        val below = v("monoBelow")
        if (below > 21f) {
            val reach = radius * (0.2f + 0.45f * (ln(below / 20f) / ln(20f)).coerceIn(0f, 1f))
            s.marks += Arc(x, y, reach, -4f, 8f, Ink.MAIN, fill = true)
        }
        s.marks += Caption(-0.42f, 0.06f, "", 1, unit = Caption.LEFT)
        s.marks += Caption(1.42f, 0.06f, "", -1, unit = Caption.RIGHT)
        return s.done(square = true)
    }

    private fun space(s: Sketch, v: (String) -> Float): Picture {
        val radius = 0.17f + 0.27f * v("distance")
        val angle = v("angle")
        // the room around, the head from above with its nose to the front
        s.marks += Arc(0.5f, 0.5f, 0.45f, 0f, 360f, Ink.GRID)
        s.marks += Arc(0.5f, 0.5f, 0.09f, 0f, 360f, Ink.DIM, fill = true)
        s.marks += Stroke(floatArrayOf(0.46f, 0.575f, 0.5f, 0.635f, 0.54f, 0.575f), Ink.DIM)
        // the way the sound travels
        if (v("motion").roundToInt() != 0) {
            val arc = v("arc")
            if (arc >= 359.5f) s.marks += Arc(0.5f, 0.5f, radius, 0f, 360f, Ink.SOFT) else if (arc > 0.5f) s.marks += Arc(0.5f, 0.5f, radius, angle - arc / 2f, arc, Ink.SOFT)
        }
        val a = Math.toRadians(angle.toDouble())
        s.marks += Dot(0.5f + radius * sin(a).toFloat(), 0.5f + radius * cos(a).toFloat(), Ink.MAIN, 1.3f + 0.5f * v("height"))
        s.marks += Caption(0.02f, 0.46f, "", -1, unit = Caption.LEFT)
        s.marks += Caption(0.98f, 0.46f, "", 1, unit = Caption.RIGHT)
        return s.done(Live.SOURCE, square = true, lo = radius)
    }

    // ------------------------------------------------------------------ pitch

    /** Which notes the pitch correction pulls a voice to: twelve bits, bit 0 = C. */
    fun tuneMask(v: (String) -> Float, keyMask: Int): Int = when (val scale = v("scale").roundToInt()) {
        0 -> if (keyMask == 0) Scales.ALL_NOTES else keyMask
        1 -> Scales.ALL_NOTES
        else -> Scales.mask(v("key").roundToInt(), scale - 1).let { if (it == 0) Scales.ALL_NOTES else it }
    }

    private fun tune(s: Sketch, v: (String) -> Float, keyMask: Int): Picture {
        val mask = tuneMask(v, keyMask)
        // an octave of keys: the lit ones are the notes a voice is pulled to
        for (note in 0 until 12) {
            val on = Scales.contains(mask, note)
            val black = isBlackKey(note)
            s.marks += Bar((note + 0.5f) / 12f, if (black) 0.42f else 0.08f, 0.86f, if (on) Ink.MAIN else Ink.GRID, 0.066f)
        }
        s.marks += Caption(0.5f / 12f, 0.9f, "C", 0)
        return s.done(Live.NOTE)
    }

    private fun shift(s: Sketch, v: (String) -> Float): Picture {
        val semitones = (v("semitones") + v("cents") / 100f).coerceIn(-12f, 12f)
        fun at(st: Float) = 0.04f + 0.92f * (st + 12f) / 24f
        // a ruler of semitones, an octave down and an octave up
        for (st in -12..12) s.marks += Bar(at(st.toFloat()), 0.1f, if (st % 12 == 0) 0.42f else 0.26f, Ink.GRID)
        s.marks += Bar(at(0f), 0.1f, 0.6f, Ink.DIM, 0.012f)
        s.marks += Bar(at(semitones), 0.1f, 0.6f, Ink.MAIN, 0.012f)
        // the leap from the note that comes in to the note that goes out
        val from = at(0f)
        val to = at(semitones)
        if (abs(to - from) > 0.01f) {
            val arch = FloatArray(42)
            for (i in 0..20) {
                val t = i / 20f
                arch[i * 2] = from + (to - from) * t
                arch[i * 2 + 1] = 0.6f + 0.3f * 4f * t * (1f - t)
            }
            s.marks += Stroke(arch, Ink.SOFT)
        }
        s.marks += Dot(to, 0.6f, Ink.MAIN)
        return s.done()
    }

    // ------------------------------------------------------------------ instruments

    private fun oscillator(s: Sketch, wave: Int, level: Float, pulse: Float): Picture {
        s.marks += Rule(false, 0.5f)
        var seed = 0x2F6E2B1
        s.plot(Ink.MAIN, points = if (wave == 4) 120 else 240) { x ->
            val phase = (x * 2f) % 1f
            val value = when (wave) {
                0 -> sin(2.0 * PI * phase).toFloat()
                1 -> 1f - 4f * abs(((phase + 0.25f) % 1f) - 0.5f)
                2 -> 2f * ((phase + 0.5f) % 1f) - 1f
                3 -> if (phase < pulse) 1f else -1f
                else -> {
                    // noise: the same every time it is drawn
                    seed = seed xor (seed shl 13)
                    seed = seed xor (seed ushr 17)
                    seed = seed xor (seed shl 5)
                    (seed and 0xFFFF) / 32768f - 1f
                }
            }
            0.5f + 0.45f * level * value
        }
        return s.done()
    }

    private fun lfo(s: Sketch, wave: Int, rate: Float): Picture {
        val seconds = stretch(1f / rate)
        s.marks += Rule(false, 0.5f)
        s.plot(Ink.MAIN, points = 320) { x ->
            val turns = x * seconds * rate
            val phase = turns - floor(turns)
            val value = when (wave) {
                0 -> sin(2.0 * PI * phase).toFloat()
                1 -> 1f - 4f * abs(phase - 0.5f)
                2 -> 1f - 2f * phase
                3 -> if (phase < 0.5f) 1f else -1f
                else -> {
                    // a new height on every turn; which one is anybody's guess
                    val turn = floor(turns).toInt()
                    ((turn * 7919 + 104729) * 31 % 2001) / 1000f - 1f
                }
            }
            0.5f + 0.42f * value
        }
        s.seconds(seconds)
        return s.done()
    }

    /** An envelope as the engine runs it: the key is held through attack, decay and a while of sustain, then let go. */
    fun envelopeLine(attack: Float, decay: Float, sustain: Float, release: Float, points: Int): Triple<FloatArray, Float, Float> {
        val hold = (0.25f * (attack + decay + release)).coerceIn(0.05f, 1f)
        val total = attack + decay + hold + release
        val rate = points / total
        val env = Adsr()
        env.set(attack, decay, sustain, release, rate)
        env.gateOn()
        val off = ((attack + decay + hold) * rate).toInt()
        val out = FloatArray(points)
        for (i in 0 until points) {
            if (i == off) env.gateOff()
            out[i] = env.next()
        }
        return Triple(out, total, off / points.toFloat())
    }

    private fun envelope(s: Sketch, attack: Float, decay: Float, sustain: Float, release: Float): Picture {
        val (line, total, off) = envelopeLine(attack, decay, sustain, release, 600)
        s.marks += Rule(true, off, Ink.DIM, dashed = true)
        s.samples(line, Ink.MAIN, 0f, 1f, 0f, 0.94f, fill = true)
        s.seconds(total)
        return s.done()
    }

    /** The sound of a hit or a pluck as it dies away. */
    private fun hit(s: Sketch, sound: FloatArray, seconds: Float, released: Float): Picture {
        val columns = 160
        val top = FloatArray(columns)
        val bottom = FloatArray(columns)
        val per = max(1, sound.size / columns)
        // the shape is what the picture is about: it fills the height whatever the level
        val scale = 0.47f / max(sound.maxOfOrNull { abs(it) } ?: 0f, 1e-4f)
        for (c in 0 until columns) {
            var hi = 0f
            var lo = 0f
            for (i in c * per until min(sound.size, (c + 1) * per)) {
                if (sound[i] > hi) hi = sound[i]
                if (sound[i] < lo) lo = sound[i]
            }
            top[c] = (0.5f + scale * hi).coerceIn(0f, 1f)
            bottom[c] = (0.5f + scale * lo).coerceIn(0f, 1f)
        }
        s.marks += Rule(false, 0.5f)
        if (!released.isNaN()) s.marks += Rule(true, released, Ink.DIM, dashed = true)
        s.marks += Area(top, bottom, Ink.MAIN)
        s.seconds(seconds)
        return s.done()
    }

    private fun fm(s: Sketch, type: DeviceType, v: (String) -> Float): Picture {
        // two turns of the wave of a low A, a moment after the key went down, while it is at its brightest
        val sound = Lab.note("fm", raw(type, v, "attack" to 0.001f, "octave" to 0f), 45, 0.06f, 1f)
        val period = Lab.RATE / 110f
        val from = (0.02f * Lab.RATE).toInt()
        val piece = FloatArray((period * 2f).toInt()) { sound[min(sound.size - 1, from + it)] }
        val peak = max(piece.maxOf { abs(it) }, 1e-6f)
        s.marks += Rule(false, 0.5f)
        s.samples(piece, Ink.MAIN, -peak * 1.1f, peak * 1.1f)
        return s.done()
    }
}
