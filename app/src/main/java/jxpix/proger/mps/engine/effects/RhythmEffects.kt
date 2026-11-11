package jxpix.proger.mps.engine.effects

import jxpix.proger.mps.R
import jxpix.proger.mps.engine.DeviceType
import jxpix.proger.mps.engine.Effect
import jxpix.proger.mps.engine.PPQ
import jxpix.proger.mps.engine.ParamSpec
import jxpix.proger.mps.engine.ParamUnit
import jxpix.proger.mps.engine.ProcessContext
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Counts frames through a stretch of the song that comes round again and again (a bar, half a
 * bar...). While the song plays it follows the song, so an effect lands on the same beats on
 * every pass; otherwise it keeps running at the tempo of the project.
 */
internal class GridClock {
    /** Frames since the stretch began. */
    var pos = 0.0
        private set

    /** Length of the stretch in frames. */
    var length = 1.0
        private set

    /** Ticks that pass during one frame. */
    var ticksPerFrame = 0.01
        private set

    private var started = false

    /** Call at the start of a block. True when the position jumped: playback began, looped, or was moved. */
    fun begin(ctx: ProcessContext, periodTicks: Double): Boolean {
        ticksPerFrame = if (ctx.ticksPerFrame > 0.0) ctx.ticksPerFrame else ctx.bpm * PPQ / (60.0 * ctx.sampleRate)
        length = max(8.0, periodTicks / ticksPerFrame)
        var jumped = !started
        started = true
        if (ctx.playing && ctx.tickPos >= 0.0) {
            val p = (ctx.tickPos % periodTicks) / ticksPerFrame
            var off = abs(p - pos)
            // the last frame of one round and the first of the next are neighbours
            if (off > length * 0.5) off = length - off
            if (off > 2.0) {
                pos = p
                jumped = true
            }
        }
        if (pos >= length) {
            pos %= length
            jumped = true
        }
        return jumped
    }

    /** Moves on by one frame; true when that frame is the first of a new round. */
    fun step(): Boolean {
        pos += 1.0
        if (pos >= length) {
            pos -= length
            return true
        }
        return false
    }

    fun reset() {
        pos = 0.0
        started = false
    }
}

/**
 * Half-time: plays the sound at half speed in time with the song. Every bar (or half bar...)
 * starts on the beat, then drags behind and drops an octave - the slow, heavy feel of trap.
 */
class HalfTime : Effect() {
    private var bufL = FloatArray(0)
    private var bufR = FloatArray(0)
    private val clock = GridClock()

    private var length = 2
    private var slow = 2f
    private var smooth = 0.3f
    private var mix = 1f

    /** Frames taken in since this round began. */
    private var taken = 0
    private var takeLimit = 0

    // the end of the previous round, fading out under the start of this one
    private var oldAt = 0.0
    private var fade = 0f
    private var fadeStep = 0f

    override fun prepare(sampleRate: Int) {
        super.prepare(sampleRate)
        val frames = (this.sampleRate * MEMORY_SECONDS).toInt()
        bufL = FloatArray(frames)
        bufR = FloatArray(frames)
    }

    override fun setParams(p: FloatArray) {
        length = p[LENGTH].toInt().coerceIn(0, BARS.size - 1)
        slow = SPEEDS[p[SPEED].toInt().coerceIn(0, SPEEDS.size - 1)]
        smooth = p[SMOOTH]
        mix = p[MIX]
    }

    override fun process(l: FloatArray, r: FloatArray, frames: Int, ctx: ProcessContext) {
        var period = ctx.ticksPerBar * BARS[length]
        val tpf = if (ctx.ticksPerFrame > 0.0) ctx.ticksPerFrame else ctx.bpm * PPQ / (60.0 * ctx.sampleRate)
        // a round too long to remember (a very slow song) is halved until it fits
        while (period / tpf / slow > bufL.size - FADE_ROOM && period > PPQ / 4) period /= 2.0
        if (clock.begin(ctx, period)) begin(false)

        val step = 1.0 / slow
        val dry = 1f - mix
        val last = bufL.size - 2
        for (i in 0 until frames) {
            val xl = l[i]
            val xr = r[i]
            val k = taken
            if (k < takeLimit) {
                bufL[k] = xl
                bufR[k] = xr
            }
            // what came in is given back, stretched
            val at = min(k * step, (takeLimit - 1).toDouble())
            var ip = at.toInt()
            var fr = (at - ip).toFloat()
            if (ip >= last) {
                ip = last
                fr = 0f
            }
            // the sample after the newest one is not there yet: it counts for nothing at that moment
            var yl = if (fr == 0f) bufL[ip] else bufL[ip] + (bufL[ip + 1] - bufL[ip]) * fr
            var yr = if (fr == 0f) bufR[ip] else bufR[ip] + (bufR[ip + 1] - bufR[ip]) * fr
            if (fade > 0f) {
                var op = oldAt.toInt()
                var of = (oldAt - op).toFloat()
                if (op >= last) {
                    op = last
                    of = 0f
                }
                val ol = bufL[op] + (bufL[op + 1] - bufL[op]) * of
                val or = bufR[op] + (bufR[op + 1] - bufR[op]) * of
                yl += (ol - yl) * fade
                yr += (or - yr) * fade
                oldAt += step
                fade -= fadeStep
            }
            l[i] = xl * dry + yl * mix
            r[i] = xr * dry + yr * mix
            taken++
            if (clock.step()) begin(true)
        }
    }

    /** A new round starts with the next frame. */
    private fun begin(following: Boolean) {
        val round = clock.length
        val fadeFrames = max(sampleRate * 0.002, smooth * min(sampleRate * 0.04, round / 8.0))
        if (following) {
            oldAt = taken / slow.toDouble()
            fade = 1f
            fadeStep = (1.0 / fadeFrames).toFloat()
        } else {
            fade = 0f
        }
        taken = 0
        // only the part that will be played is kept, and a little more for the fade into the next round
        takeLimit = min(bufL.size - 2, ((round + fadeFrames) / slow).toInt() + 4)
    }

    override fun reset() {
        java.util.Arrays.fill(bufL, 0f)
        java.util.Arrays.fill(bufR, 0f)
        clock.reset()
        taken = 0
        fade = 0f
    }

    companion object {
        const val TYPE_ID = "halftime"
        const val LENGTH = 0
        const val SPEED = 1
        const val SMOOTH = 2
        const val MIX = 3

        /** Length of a round in bars, and how many times slower it is played, for the choices of the two switches. */
        private val BARS = doubleArrayOf(0.25, 0.5, 1.0, 2.0)
        private val SPEEDS = floatArrayOf(1.5f, 2f, 4f)
        private const val MEMORY_SECONDS = 6f
        private const val FADE_ROOM = 4096

        val PARAMS = listOf(
            ParamSpec("length", R.string.p_loop_length, 0f, 3f, 2f, choicesRes = R.array.arr_half_length),
            ParamSpec("speed", R.string.p_slowdown, 0f, 2f, 1f, choicesRes = R.array.arr_half_speed),
            ParamSpec("smooth", R.string.p_smooth, 0f, 1f, 0.3f, ParamUnit.PERCENT),
            ParamSpec("mix", R.string.p_mix, 0f, 1f, 1f, ParamUnit.PERCENT),
        )
        val TYPE = DeviceType(TYPE_ID, R.string.fx_halftime, R.string.fx_halftime_desc, false, PARAMS) { HalfTime() }
    }
}

/**
 * Beat repeat (stutter): at the end of every bar (or two, or four) it catches a short piece of
 * the sound and plays it over and over until the bar is out - an automatic fill.
 */
class BeatRepeat : Effect() {
    private var bufL = FloatArray(0)
    private var bufR = FloatArray(0)
    private val clock = GridClock()

    private var every = 1
    private var length = 0
    private var slice = 3
    private var decay = 0f
    private var mix = 1f

    /** True once the piece of the current fill has been caught completely. */
    private var caught = false
    private var edge = 96f

    override fun prepare(sampleRate: Int) {
        super.prepare(sampleRate)
        val frames = (this.sampleRate * MEMORY_SECONDS).toInt()
        bufL = FloatArray(frames)
        bufR = FloatArray(frames)
        edge = this.sampleRate * 0.002f
    }

    override fun setParams(p: FloatArray) {
        every = p[EVERY].toInt().coerceIn(0, EVERY_BARS.size - 1)
        length = p[LENGTH].toInt().coerceIn(0, LENGTH_BEATS.size - 1)
        slice = p[SLICE].toInt().coerceIn(0, SLICE_TICKS.size - 1)
        decay = p[DECAY]
        mix = p[MIX]
    }

    override fun process(l: FloatArray, r: FloatArray, frames: Int, ctx: ProcessContext) {
        val period = ctx.ticksPerBar * EVERY_BARS[every]
        if (clock.begin(ctx, period)) caught = false
        val tpf = clock.ticksPerFrame
        val fill = min(if (LENGTH_BEATS[length] <= 0) ctx.ticksPerBar.toDouble() else PPQ * LENGTH_BEATS[length].toDouble(), period) / tpf
        val piece = (SLICE_TICKS[slice] / tpf).coerceIn(64.0, (bufL.size - 2).toDouble())
        val fillStart = clock.length - fill
        val perRepeat = 1f - 0.5f * decay
        val dry = 1f - mix

        for (i in 0 until frames) {
            val local = clock.pos - fillStart
            if (local >= 0.0) {
                val n = (local / piece).toInt()
                val at = local - n * piece
                val ip = at.toInt()
                if (n == 0) {
                    // the piece itself: heard as it is, and remembered
                    bufL[ip] = l[i]
                    bufR[ip] = r[i]
                    if (at >= piece - 1.5) caught = true
                } else if (caught) {
                    val fr = (at - ip).toFloat()
                    val next = if (ip + 1 < piece) ip + 1 else ip
                    // every repeat starts and ends softly, so the joins do not click
                    val fromEdge = min(at, piece - at).toFloat()
                    var g = if (fromEdge < edge) fromEdge / edge else 1f
                    var k = n
                    while (k-- > 0) g *= perRepeat
                    l[i] = l[i] * dry + (bufL[ip] + (bufL[next] - bufL[ip]) * fr) * g * mix
                    r[i] = r[i] * dry + (bufR[ip] + (bufR[next] - bufR[ip]) * fr) * g * mix
                }
            }
            if (clock.step()) caught = false
        }
    }

    override fun reset() {
        java.util.Arrays.fill(bufL, 0f)
        java.util.Arrays.fill(bufR, 0f)
        clock.reset()
        caught = false
    }

    companion object {
        const val TYPE_ID = "repeat"
        const val EVERY = 0
        const val LENGTH = 1
        const val SLICE = 2
        const val DECAY = 3
        const val MIX = 4

        private val EVERY_BARS = doubleArrayOf(0.5, 1.0, 2.0, 4.0)

        /** Length of the fill in beats; 0 = a whole bar. */
        private val LENGTH_BEATS = intArrayOf(1, 2, 0)

        /** 1/4, 1/8, 1/8T, 1/16, 1/16T, 1/32. */
        private val SLICE_TICKS = doubleArrayOf(PPQ.toDouble(), PPQ / 2.0, PPQ / 3.0, PPQ / 4.0, PPQ / 6.0, PPQ / 8.0)
        private const val MEMORY_SECONDS = 1.6f

        val PARAMS = listOf(
            ParamSpec("every", R.string.p_every, 0f, 3f, 1f, choicesRes = R.array.arr_repeat_every),
            ParamSpec("length", R.string.p_fill_length, 0f, 2f, 0f, choicesRes = R.array.arr_repeat_length),
            ParamSpec("slice", R.string.p_slice, 0f, 5f, 3f, choicesRes = R.array.arr_repeat_slice),
            ParamSpec("decay", R.string.p_decay, 0f, 1f, 0f, ParamUnit.PERCENT),
            ParamSpec("mix", R.string.p_mix, 0f, 1f, 1f, ParamUnit.PERCENT),
        )
        val TYPE = DeviceType(TYPE_ID, R.string.fx_repeat, R.string.fx_repeat_desc, false, PARAMS) { BeatRepeat() }
    }
}
