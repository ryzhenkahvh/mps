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
import jxpix.proger.mps.model.Scales
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Noise gate: lets the sound through while it is louder than the threshold and turns it down
 * in the pauses - the hum of a microphone, breathing, the spill of headphones.
 */
class Gate : Effect() {
    private var open = 0.01f
    private var close = 0.007f
    private var attack = 0.1f
    private var release = 0.001f
    private var floor = 0.001f
    private var holdFrames = 0

    private var env = 0f
    private var envFall = 0.999f
    private var isOpen = false
    private var holdLeft = 0
    private var gain = 0f

    override fun prepare(sampleRate: Int) {
        super.prepare(sampleRate)
        // the level meter of the gate: jumps up with the sound, falls back in a few milliseconds
        envFall = FastMath.decayCoef(0.004f, this.sampleRate)
    }

    override fun setParams(p: FloatArray) {
        open = FastMath.dbToGain(p[THRESHOLD])
        // once open, the gate stays open a little below the threshold, so it does not flutter
        close = open * 0.7f
        attack = FastMath.onePoleCoef(p[ATTACK] / 1000f, sampleRate)
        holdFrames = (p[HOLD] / 1000f * sampleRate).toInt()
        release = FastMath.onePoleCoef(p[RELEASE] / 1000f, sampleRate)
        floor = FastMath.dbToGain(-p[RANGE])
    }

    override fun process(l: FloatArray, r: FloatArray, frames: Int, ctx: ProcessContext) {
        for (i in 0 until frames) {
            val xl = l[i]
            val xr = r[i]
            val peak = max(abs(xl), abs(xr))
            env = if (peak > env) peak else env * envFall
            if (env >= open) {
                isOpen = true
                holdLeft = holdFrames
            } else if (isOpen && env < close) {
                if (holdLeft > 0) holdLeft-- else isOpen = false
            }
            val target = if (isOpen) 1f else floor
            gain += (if (target > gain) attack else release) * (target - gain)
            l[i] = xl * gain
            r[i] = xr * gain
        }
        if (env < 1e-12f) env = 0f
    }

    override fun reset() {
        env = 0f
        isOpen = false
        holdLeft = 0
        gain = floor
    }

    companion object {
        const val TYPE_ID = "gate"
        const val THRESHOLD = 0
        const val ATTACK = 1
        const val HOLD = 2
        const val RELEASE = 3
        const val RANGE = 4

        val PARAMS = listOf(
            ParamSpec("threshold", R.string.p_threshold, -80f, 0f, -40f, ParamUnit.DB),
            ParamSpec("attack", R.string.p_attack, 0.1f, 50f, 1f, ParamUnit.MILLIS, ParamCurve.EXP),
            ParamSpec("hold", R.string.p_hold, 0f, 500f, 60f, ParamUnit.MILLIS),
            ParamSpec("release", R.string.p_release, 5f, 1000f, 120f, ParamUnit.MILLIS, ParamCurve.EXP),
            ParamSpec("range", R.string.p_depth, 0f, 80f, 60f, ParamUnit.DB_AMOUNT),
        )
        val TYPE = DeviceType(TYPE_ID, R.string.fx_gate, R.string.fx_gate_desc, false, PARAMS) { Gate() }
    }
}

/**
 * De-esser: turns down the hiss of "s" and "sh" in a voice. Only the top of the sound is
 * touched, and only while it is too loud - the rest of the voice stays as it is.
 */
class DeEsser : Effect() {
    // the sound is split in two below the hiss (Linkwitz-Riley, 24 dB per octave): added up, the halves are the sound again
    private val lows = Array(4) { Biquad() }
    private val highs = Array(4) { Biquad() }
    private var threshold = 0.03f
    private var maxCut = 12f
    private var env = 0f
    private var gain = 1f
    private var target = 1f
    private var rise = 0.02f
    private var fall = 0.0005f
    private var smooth = 0.01f
    private var count = 0

    /** Strongest reduction during the last block, in dB (positive). */
    @Volatile
    var reductionDb = 0f
        private set

    override fun prepare(sampleRate: Int) {
        super.prepare(sampleRate)
        rise = FastMath.onePoleCoef(0.001f, this.sampleRate)
        fall = FastMath.onePoleCoef(0.05f, this.sampleRate)
        smooth = FastMath.onePoleCoef(0.002f, this.sampleRate)
    }

    override fun setParams(p: FloatArray) {
        // the knob names where the hiss is; the split lies a little below, so that all of the hiss is above it
        val split = p[FREQ] * SPLIT
        for (f in lows) f.setLowPass(split, 0.707f, sampleRate)
        for (f in highs) f.setHighPass(split, 0.707f, sampleRate)
        threshold = FastMath.dbToGain(p[THRESHOLD])
        maxCut = p[AMOUNT]
    }

    override fun process(l: FloatArray, r: FloatArray, frames: Int, ctx: ProcessContext) {
        var strongest = 1f
        for (i in 0 until frames) {
            val xl = l[i]
            val xr = r[i]
            val lowL = lows[1].process(lows[0].process(xl))
            val lowR = lows[3].process(lows[2].process(xr))
            val highL = highs[1].process(highs[0].process(xl))
            val highR = highs[3].process(highs[2].process(xr))
            val peak = max(abs(highL), abs(highR))
            env += (if (peak > env) rise else fall) * (peak - env)
            if (--count <= 0) {
                count = CONTROL
                // what is above the threshold is taken back, up to the amount the knob allows
                target = if (env > threshold) {
                    val over = 20f * ln(env / threshold) * LOG10_E
                    FastMath.dbToGain(-min(maxCut, over))
                } else 1f
            }
            gain += (target - gain) * smooth
            if (gain < strongest) strongest = gain
            l[i] = lowL + highL * gain
            r[i] = lowR + highR * gain
        }
        if (env < 1e-12f) env = 0f
        reductionDb = -FastMath.gainToDb(strongest)
    }

    override fun reset() {
        for (f in lows) f.reset()
        for (f in highs) f.reset()
        env = 0f
        gain = 1f
        target = 1f
    }

    companion object {
        const val TYPE_ID = "deess"
        const val FREQ = 0
        const val THRESHOLD = 1
        const val AMOUNT = 2
        private const val CONTROL = 8
        private const val LOG10_E = 0.4342945f
        private const val SPLIT = 0.7f

        val PARAMS = listOf(
            ParamSpec("freq", R.string.p_frequency, 2000f, 12000f, 6000f, ParamUnit.HZ, ParamCurve.EXP),
            ParamSpec("threshold", R.string.p_threshold, -60f, 0f, -30f, ParamUnit.DB),
            ParamSpec("amount", R.string.p_amount, 0f, 24f, 12f, ParamUnit.DB_AMOUNT),
        )
        val TYPE = DeviceType(TYPE_ID, R.string.fx_deess, R.string.fx_deess_desc, false, PARAMS) { DeEsser() }
    }
}

/**
 * Plays a sound back a little faster or slower than it comes in - which raises or lowers its
 * pitch - and stays in step with the input by repeating or skipping short pieces of it. Where
 * the sound has a pitch a piece is exactly one period long, so the joins cannot be heard.
 *
 * Shared by the pitch correction and the pitch shifter. Not thread safe; audio thread only.
 */
internal class PitchCore(sampleRate: Int) {
    private val size = Integer.highestOneBit(max(8192, sampleRate / 3) - 1) shl 1
    private val mask = size - 1
    private val bufL = FloatArray(size)
    private val bufR = FloatArray(size)
    private val bufM = FloatArray(size)
    private var w = 0

    /** How far behind the newest sample the play head is, in samples. */
    private var delay = BASE.toDouble()

    // the tap that is fading out after a join
    private var oldDelay = 0.0
    private var fade = 0f
    private var fadeStep = 0f

    private val minPiece = max(16, sampleRate / 1000)
    private val maxPiece = sampleRate / 40
    private val minFade = max(16, sampleRate / 500)
    private val maxFade = sampleRate / 100

    var outL = 0f
        private set
    var outR = 0f
        private set

    fun reset() {
        java.util.Arrays.fill(bufL, 0f)
        java.util.Arrays.fill(bufR, 0f)
        java.util.Arrays.fill(bufM, 0f)
        delay = BASE.toDouble()
        fade = 0f
        outL = 0f
        outR = 0f
    }

    /**
     * Takes one stereo sample and leaves the next output sample in [outL] and [outR].
     * [ratio] is the change of pitch as a frequency ratio (2 = an octave up); [period] is the
     * length of one period of the sound in samples where that is known, 0 otherwise.
     */
    fun process(inL: Float, inR: Float, ratio: Float, period: Float) {
        val wi = w and mask
        bufL[wi] = inL
        bufR[wi] = inR
        bufM[wi] = (inL + inR) * 0.5f
        w++

        val drift = 1.0 - ratio
        delay += drift
        if (fade > 0f) {
            oldDelay += drift
            if (oldDelay < 2.0) oldDelay = 2.0
        } else if (ratio > 1f) {
            // The play head is catching up with the input. Before it gets there it goes back by
            // one piece; the old tap runs on while it fades out, so it needs room for that long.
            val room = BASE + (ratio - 1f) * maxFade * 0.75f
            if (delay < room) join(true, ratio, period)
        } else if (ratio < 1f) {
            val piece = if (period > 0f) min(period * 1.1f, maxPiece.toFloat()) else maxPiece.toFloat()
            if (delay > BASE + piece) join(false, ratio, period)
        }
        if (delay < 2.0) delay = 2.0
        if (delay > size - 8) delay = (size - 8).toDouble()

        var di = delay.toInt()
        var fr = (delay - di).toFloat()
        var yl = read(bufL, di, fr)
        var yr = read(bufR, di, fr)
        if (fade > 0f) {
            di = oldDelay.toInt()
            fr = (oldDelay - di).toFloat()
            yl += (read(bufL, di, fr) - yl) * fade
            yr += (read(bufR, di, fr) - yr) * fade
            fade -= fadeStep
        }
        outL = yl
        outR = yr
    }

    /** The sample [di] + [fr] behind the newest one, through a smooth curve over four samples. */
    private fun read(buf: FloatArray, di: Int, fr: Float): Float {
        val i = w - 1 - di
        val xm = buf[(i + 1) and mask]
        val x0 = buf[i and mask]
        val x1 = buf[(i - 1) and mask]
        val x2 = buf[(i - 2) and mask]
        val c1 = 0.5f * (x1 - xm)
        val c2 = xm - 2.5f * x0 + 2f * x1 - 0.5f * x2
        val c3 = 0.5f * (x2 - xm) + 1.5f * (x0 - x1)
        return ((c3 * fr + c2) * fr + c1) * fr + x0
    }

    /**
     * Moves the play head by one piece - [back] in time (the piece is heard twice) or forward
     * (it is skipped) - to a place where the sound looks like it does right here.
     */
    private fun join(back: Boolean, ratio: Float, period: Float) {
        val here = w - 1 - delay.toInt()
        // going forward, the head has to stay behind the input
        val limit = if (back) maxPiece else min(maxPiece, delay.toInt() - BASE)
        if (limit < minPiece) return
        val piece: Int
        if (period >= minPiece && period <= limit / 0.92f) {
            // the pitch is known: one period, give or take what it has changed since it was measured
            val lo = max(minPiece, (period * 0.92f).toInt())
            val hi = min(limit, (period * 1.08f).toInt() + 1)
            piece = if (hi <= lo) min(limit, period.roundToInt()) else best(here, back, lo, hi, 1, min(512, max(96, period.toInt())), 2)
        } else {
            val coarse = best(here, back, minPiece, limit, 4, min(1024, maxPiece), 4)
            piece = best(here, back, max(minPiece, coarse - 4), min(limit, coarse + 4), 1, min(1024, maxPiece), 2)
        }
        oldDelay = delay
        delay += if (back) piece.toDouble() else -piece.toDouble()
        var length = piece.coerceIn(minFade, maxFade)
        // going back while pitching up, the new tap must not lose in the fade what the join has won
        if (back && ratio > 1f) length = min(length, max(minFade / 2, (0.5f * piece / (ratio - 1f)).toInt()))
        fade = 1f
        fadeStep = 1f / length
    }

    /** The piece length between [lo] and [hi] at which the sound before the head repeats itself best. */
    private fun best(here: Int, back: Boolean, lo: Int, hi: Int, lagStep: Int, window: Int, step: Int): Int {
        var energy = 1e-9f
        var j = 0
        while (j < window) {
            val a = bufM[(here - j) and mask]
            energy += a * a
            j += step
        }
        var bestLag = lo
        var bestScore = -2f
        var lag = lo
        while (lag <= hi) {
            val there = if (back) here - lag else here + lag
            var dot = 0f
            var other = 1e-9f
            j = 0
            while (j < window) {
                val a = bufM[(here - j) and mask]
                val b = bufM[(there - j) and mask]
                dot += a * b
                other += b * b
                j += step
            }
            val score = dot / sqrt(energy * other)
            if (score > bestScore) {
                bestScore = score
                bestLag = lag
            }
            lag += lagStep
        }
        return bestLag
    }

    companion object {
        /** The closest the play head comes to the newest sample. */
        const val BASE = 8
    }
}

/**
 * Finds the pitch of a voice (or any single-voiced sound): the YIN method of de Cheveigné and
 * Kawahara, run on a copy of the sound at a quarter of the sample rate to keep it light.
 */
internal class PitchTracker(private val sampleRate: Int) {
    private val decim = max(1, (sampleRate / 12000f).roundToInt())
    private val rate = sampleRate.toFloat() / decim
    private val lowPass1 = Biquad()
    private val lowPass2 = Biquad()
    private var skip = 0

    private val longest = (rate / 55f).toInt() + 2
    private val ring = FloatArray(Integer.highestOneBit(longest * 3) shl 1)
    private val ringMask = ring.size - 1
    private var w = 0
    private val diff = FloatArray(longest + 2)

    private var minLag = 12
    private var maxLag = 150

    /** Length of one period in samples of the full rate; valid while [voiced]. */
    var period = 0f
        private set
    var voiced = false
        private set

    init {
        lowPass1.setLowPass(3400f, 0.54f, sampleRate.toFloat())
        lowPass2.setLowPass(3400f, 1.31f, sampleRate.toFloat())
    }

    /** How much sound one look takes in, in samples of the full rate. */
    var window = 0
        private set

    /** The lowest and the highest note to look for, in Hz. */
    fun setRange(low: Float, high: Float) {
        maxLag = min(longest, (rate / low).toInt() + 1)
        minLag = max(4, (rate / high).toInt())
        window = (maxLag + maxLag / 2) * decim
    }

    fun reset() {
        java.util.Arrays.fill(ring, 0f)
        lowPass1.reset()
        lowPass2.reset()
        voiced = false
        period = 0f
    }

    fun push(x: Float) {
        val y = lowPass2.process(lowPass1.process(x))
        if (++skip >= decim) {
            skip = 0
            ring[w and ringMask] = y
            w++
        }
    }

    /** Looks at the most recent sound; afterwards [voiced] and [period] are up to date. */
    fun analyse() {
        val window = maxLag + maxLag / 2
        val newest = w - 1
        var energy = 0f
        for (j in 0 until window) {
            val a = ring[(newest - j) and ringMask]
            energy += a * a
        }
        if (energy / window < SILENCE) {
            voiced = false
            return
        }
        // how different the sound is from itself, shifted by every possible period
        var running = 0f
        var chosen = -1
        var lowest = 1f
        var lowestAt = -1
        diff[0] = 1f
        for (lag in 1..maxLag) {
            var sum = 0f
            for (j in 0 until window) {
                val d = ring[(newest - j) and ringMask] - ring[(newest - j - lag) and ringMask]
                sum += d * d
            }
            running += sum
            // compared with the average so far, so that short shifts do not win by default
            diff[lag] = if (running > 0f) sum * lag / running else 1f
        }
        var lag = minLag
        while (lag < maxLag) {
            val d = diff[lag]
            if (d < lowest) {
                lowest = d
                lowestAt = lag
            }
            if (d < CLEAR) {
                // the first clear dip: walk down to its bottom
                while (lag + 1 < maxLag && diff[lag + 1] < diff[lag]) lag++
                chosen = lag
                break
            }
            lag++
        }
        if (chosen < 0 && lowest < UNSURE) chosen = lowestAt
        if (chosen <= 0) {
            voiced = false
            return
        }
        // the bottom of the dip usually lies between two samples
        var exact = chosen.toFloat()
        if (chosen > 1 && chosen < maxLag) {
            val a = diff[chosen - 1]
            val b = diff[chosen]
            val c = diff[chosen + 1]
            val curve = a - 2f * b + c
            if (curve > 1e-9f) exact += (0.5f * (a - c) / curve).coerceIn(-1f, 1f)
        }
        period = exact * decim
        voiced = true
    }

    private companion object {
        const val CLEAR = 0.15f
        const val UNSURE = 0.35f
        const val SILENCE = 1e-7f
    }
}

/**
 * Pitch correction: listens to the pitch of a voice and pulls it onto the nearest note of the
 * key - gently, so that nobody notices, or at once, which is the well-known robotic effect.
 * Works on one voice at a time, not on chords.
 */
class PitchCorrect : Effect() {
    private lateinit var core: PitchCore
    private lateinit var tracker: PitchTracker

    private var scale = 0
    private var key = 0
    private var glide = 1f
    private var amount = 1f
    private var shift = 0f
    private var voice = -1

    private var hop = 384
    private var count = 0
    private var holdHops = 8

    /** Where the voice was at the last look, in semitones; -1 when it had no pitch. */
    private var lastSung = -1f

    /** Note the voice is being pulled to, or -1; and how far it is pulled right now, in semitones. */
    private var note = -1
    private var wanted = 0f
    private var bend = 0f
    private var silentHops = 0
    private var ratio = 1f
    private var period = 0f

    /** Note the voice was last pulled to (MIDI number), for display. -1 when no note is heard. */
    @Volatile
    var heardNote = -1
        private set

    override fun prepare(sampleRate: Int) {
        super.prepare(sampleRate)
        core = PitchCore(sampleRate)
        tracker = PitchTracker(sampleRate)
        hop = max(64, sampleRate / 125)
        holdHops = max(1, (0.06f * sampleRate / hop).toInt())
        voice = -1
    }

    override fun setParams(p: FloatArray) {
        scale = p[SCALE].toInt()
        key = p[KEY].toInt()
        val seconds = p[SPEED] / 1000f
        // the pull is renewed once per hop
        glide = if (seconds <= 0.0005f) 1f else 1f - exp(-hop / (seconds * sampleRate))
        amount = p[AMOUNT]
        shift = p[SHIFT]
        val v = p[VOICE].toInt()
        if (v != voice) {
            voice = v
            when (v) {
                0 -> tracker.setRange(60f, 420f)
                2 -> tracker.setRange(130f, 1100f)
                else -> tracker.setRange(80f, 700f)
            }
        }
    }

    override fun process(l: FloatArray, r: FloatArray, frames: Int, ctx: ProcessContext) {
        val notes = when (scale) {
            SCALE_PROJECT -> if (ctx.keyMask == 0) Scales.ALL_NOTES else ctx.keyMask
            SCALE_ALL -> Scales.ALL_NOTES
            else -> Scales.mask(key, scale - 1).let { if (it == 0) Scales.ALL_NOTES else it }
        }
        for (i in 0 until frames) {
            val xl = l[i]
            val xr = r[i]
            tracker.push((xl + xr) * 0.5f)
            if (++count >= hop) {
                count = 0
                listen(notes)
            }
            core.process(xl, xr, ratio, period)
            l[i] = core.outL
            r[i] = core.outR
        }
    }

    /** Once per hop: where is the voice, and where should it be? */
    private fun listen(notes: Int) {
        tracker.analyse()
        if (tracker.voiced) {
            silentHops = 0
            period = tracker.period
            val sung = 69f + 12f * ln(sampleRate / period / 440f) * INV_LN2
            var target = nearest(sung, notes)
            // stay with the note that is being sung until another one is clearly closer
            if (note >= 0 && Scales.contains(notes, note) && abs(sung - note) < abs(sung - target) + STICK) target = note
            note = target
            heardNote = target
            // What is measured is the pitch of a moment ago (the look needs some sound to work on).
            // A voice that is sliding or wobbling has moved on since: follow its slope up to now.
            var now = sung
            if (lastSung >= 0f) {
                val slope = sung - lastSung
                if (abs(slope) < MAX_SLOPE) now += slope * ((tracker.window + period) * 0.5f + hop * 0.5f) / hop
            }
            lastSung = sung
            wanted = (target - now) * amount
            // far-off jumps (a new note) are taken at once when the knob says "instantly"
            bend += (wanted - bend) * glide
        } else {
            // a consonant or a breath: hold the last correction briefly, then let go
            lastSung = -1f
            if (++silentHops > holdHops) {
                period = 0f
                bend *= 0.7f
                note = -1
                heardNote = -1
            }
        }
        ratio = exp((bend + shift) * LN2_12).coerceIn(0.5f, 2f)
    }

    private fun nearest(sung: Float, notes: Int): Int {
        val centre = sung.roundToInt()
        var best = centre
        var bestDistance = 100f
        for (d in -6..6) {
            val n = centre + d
            if (!Scales.contains(notes, n)) continue
            val distance = abs(n - sung)
            if (distance < bestDistance) {
                bestDistance = distance
                best = n
            }
        }
        return best
    }

    override fun reset() {
        core.reset()
        tracker.reset()
        note = -1
        heardNote = -1
        lastSung = -1f
        bend = 0f
        wanted = 0f
        ratio = 1f
        period = 0f
        count = 0
        silentHops = 0
    }

    companion object {
        const val TYPE_ID = "tune"
        const val SCALE = 0
        const val KEY = 1
        const val SPEED = 2
        const val AMOUNT = 3
        const val SHIFT = 4
        const val VOICE = 5

        /** Choices of [SCALE]: the key of the project, every note, then the scales of [Scales] from major on. */
        const val SCALE_PROJECT = 0
        const val SCALE_ALL = 1

        private const val INV_LN2 = 1.442695f
        private const val LN2_12 = 0.057762265f
        private const val STICK = 0.2f

        /** A change of more than this many semitones between two looks is a new note, not a slide. */
        private const val MAX_SLOPE = 0.5f

        val PARAMS = listOf(
            ParamSpec("scale", R.string.p_scale, 0f, (Scales.count).toFloat(), 0f, choicesRes = R.array.arr_tune_scale, wide = true),
            ParamSpec("key", R.string.p_key, 0f, 11f, 0f, choicesRes = R.array.arr_note_names),
            ParamSpec("speed", R.string.p_retune, 0f, 400f, 25f, ParamUnit.MILLIS, ParamCurve.SQUARE),
            ParamSpec("amount", R.string.p_amount, 0f, 1f, 1f, ParamUnit.PERCENT),
            ParamSpec("shift", R.string.p_shift, -12f, 12f, 0f, ParamUnit.SEMITONES, integer = true),
            ParamSpec("voice", R.string.p_voice_range, 0f, 2f, 1f, choicesRes = R.array.arr_tune_voice),
        )
        val TYPE = DeviceType(TYPE_ID, R.string.fx_tune, R.string.fx_tune_desc, false, PARAMS) { PitchCorrect() }
    }
}

/**
 * Pitch shifter: makes a sound higher or lower without making it faster or slower. Best on a
 * voice or a single line; chords and whole beats get a grainy, "sampled" character.
 */
class PitchShift : Effect() {
    private lateinit var core: PitchCore
    private var ratio = 0.5f
    private var mix = 1f

    override fun prepare(sampleRate: Int) {
        super.prepare(sampleRate)
        core = PitchCore(sampleRate)
    }

    override fun setParams(p: FloatArray) {
        ratio = FastMath.semitonesToRatio(p[SEMITONES] + p[CENTS] / 100f).coerceIn(0.5f, 2f)
        mix = p[MIX]
    }

    override fun process(l: FloatArray, r: FloatArray, frames: Int, ctx: ProcessContext) {
        val dry = 1f - mix
        val wet = mix
        for (i in 0 until frames) {
            val xl = l[i]
            val xr = r[i]
            core.process(xl, xr, ratio, 0f)
            l[i] = xl * dry + core.outL * wet
            r[i] = xr * dry + core.outR * wet
        }
    }

    override fun reset() {
        core.reset()
    }

    companion object {
        const val TYPE_ID = "pitch"
        const val SEMITONES = 0
        const val CENTS = 1
        const val MIX = 2

        val PARAMS = listOf(
            ParamSpec("semitones", R.string.p_semitones, -12f, 12f, -12f, ParamUnit.SEMITONES, integer = true),
            ParamSpec("cents", R.string.p_fine, -100f, 100f, 0f, ParamUnit.CENTS),
            ParamSpec("mix", R.string.p_mix, 0f, 1f, 1f, ParamUnit.PERCENT),
        )
        val TYPE = DeviceType(TYPE_ID, R.string.fx_pitch, R.string.fx_pitch_desc, false, PARAMS) { PitchShift() }
    }
}
