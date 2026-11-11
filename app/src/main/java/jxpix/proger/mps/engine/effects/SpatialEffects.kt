package jxpix.proger.mps.engine.effects

import jxpix.proger.mps.R
import jxpix.proger.mps.engine.DeviceType
import jxpix.proger.mps.engine.Effect
import jxpix.proger.mps.engine.MAX_BLOCK
import jxpix.proger.mps.engine.ParamCurve
import jxpix.proger.mps.engine.ParamSpec
import jxpix.proger.mps.engine.ParamUnit
import jxpix.proger.mps.engine.ProcessContext
import jxpix.proger.mps.engine.dsp.DENORMAL
import jxpix.proger.mps.engine.dsp.DelayLine
import jxpix.proger.mps.engine.dsp.FastMath
import jxpix.proger.mps.engine.dsp.OnePole
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * 3D sound for headphones: puts a sound at any point around the listener's head and can move it
 * around - in full circles ("8D") or swinging to and fro.
 *
 * The ears tell where a sound comes from by three things, and each of them is modelled here
 * (a spherical-head model after Brown and Duda, "A structural model for binaural sound
 * synthesis", 1998):
 *
 *  - it reaches the nearer ear a little earlier (up to 0.66 ms);
 *  - the head shadows the farther ear, which loses its high frequencies;
 *  - the outer ear adds tiny echoes that change with the height of the source, and it muffles
 *    what comes from behind.
 *
 * Distance turns the direct sound down and dulls it, while the room around the listener stays
 * as loud as it was - that balance is what makes a sound seem far away.
 */
class Space3D : Effect() {
    private var angle = 0f
    private var height = 0f
    private var distance = 0.2f
    private var room = 0.25f
    private var motion = 0
    private var rate = 0.2f
    private var arc = 360f

    /** Where in its round trip the movement is, 0..1. */
    private var phase = 0.0

    // where the source is heard right now: its angles in degrees, and its direction as right, front, up
    private var nowAz = 0f
    private var nowEl = 0f
    private var dirX = 0f
    private var dirY = 1f
    private var dirZ = 0f
    private var placed = false
    private var glide = 0.05f

    private lateinit var pinna: DelayLine
    private lateinit var ears: DelayLine
    private lateinit var reverb: Reverb
    private var roomL = FloatArray(0)
    private var roomR = FloatArray(0)
    private val air = OnePole()
    private val behind = OnePole()

    // what changes smoothly from one small block to the next
    private var delayL = 1f
    private var delayR = 1f
    private var alphaL = 1f
    private var alphaR = 1f
    private var rear = 0f
    private var direct = 1f
    private var levelL = 1f
    private var levelR = 1f
    private var send = 0f
    private val tap = FloatArray(ECHOES)

    // head shadow filters: last input and output of each ear
    private var xL = 0f
    private var yL = 0f
    private var xR = 0f
    private var yR = 0f
    private var b0k = 0f
    private var b1k = 0f
    private var a1 = 0f
    private var shadowScale = 0f
    private var roomRings = 0

    override fun prepare(sampleRate: Int) {
        super.prepare(sampleRate)
        val fs = this.sampleRate
        pinna = DelayLine((fs * 0.002f).toInt() + 8)
        ears = DelayLine((fs * 0.002f).toInt() + 8)
        roomL = FloatArray(MAX_BLOCK)
        roomR = FloatArray(MAX_BLOCK)
        reverb = Reverb()
        reverb.prepare(sampleRate)
        // a small, soft room - only its wet sound is used
        reverb.setParams(floatArrayOf(0.32f, 0.55f, 6f, 180f, 1f, 1f))
        behind.setCutoff(2200f, fs)
        air.setCutoff(18000f, fs)
        // (alpha * s + beta) / (s + beta) with beta = 2c / a, through the bilinear transform
        val beta = 2f * SOUND_SPEED / HEAD_RADIUS
        val a0 = beta + 2f * fs
        b0k = beta / a0
        b1k = beta / a0
        shadowScale = 2f * fs / a0
        a1 = (beta - 2f * fs) / a0
        glide = FastMath.onePoleCoef(0.02f, fs / STEP)
        placed = false
    }

    override fun setParams(p: FloatArray) {
        angle = p[ANGLE]
        height = p[HEIGHT]
        distance = p[DISTANCE]
        room = p[ROOM]
        motion = p[MOTION].toInt()
        rate = p[RATE]
        arc = p[ARC]
    }

    override fun process(l: FloatArray, r: FloatArray, frames: Int, ctx: ProcessContext) {
        var inc = 0.0
        if (motion == MOTION_FREE) {
            inc = rate.toDouble() / sampleRate
        } else if (motion > MOTION_FREE) {
            val period = ctx.ticksPerBar * BARS[min(motion, BARS.size - 1)]
            inc = ctx.ticksPerFrame / period
            // while the song runs, the sound is at the same place every time a bar comes round
            if (ctx.playing && ctx.tickPos >= 0.0) phase = (ctx.tickPos % period) / period
        }

        val useRoom = room > 0.001f
        var i = 0
        while (i < frames) {
            val n = min(STEP, frames - i)
            aim()
            val inv = 1f / n
            val dL = (targetDelayL - delayL) * inv
            val dR = (targetDelayR - delayR) * inv
            val aL = (targetAlphaL - alphaL) * inv
            val aR = (targetAlphaR - alphaR) * inv
            val dRear = (targetRear - rear) * inv
            val dDirect = (targetDirect - direct) * inv
            val gL = (targetLevelL - levelL) * inv
            val gR = (targetLevelR - levelR) * inv
            val dSend = (targetSend - send) * inv
            for (k in i until i + n) {
                val x = (l[k] + r[k]) * 0.5f
                roomL[k] = x * send
                send += dSend

                // the outer ear: five tiny echoes whose timing depends on where the sound is
                pinna.write(x)
                var s = x
                for (e in 0 until ECHOES) s += ECHO_GAIN[e] * pinna.readFrac(tap[e])
                s = air.lp(s)
                // from behind, the outer ear takes the top end away
                rear += dRear
                s += rear * REAR_DEPTH * (behind.lp(s) - s)
                direct += dDirect
                s *= direct

                ears.write(s)
                delayL += dL
                delayR += dR
                alphaL += aL
                alphaR += aR
                val eL = ears.readFrac(delayL)
                val eR = ears.readFrac(delayR)
                // the head: full sound for the ear that faces the source, a dull one for the other
                val oL = (b0k + alphaL * shadowScale) * eL + (b1k - alphaL * shadowScale) * xL - a1 * yL + DENORMAL
                xL = eL
                yL = oL
                val oR = (b0k + alphaR * shadowScale) * eR + (b1k - alphaR * shadowScale) * xR - a1 * yR + DENORMAL
                xR = eR
                yR = oR
                levelL += gL
                levelR += gR
                l[k] = oL * levelL
                r[k] = oR * levelR
            }
            phase += inc * n
            if (phase >= 1.0) phase -= phase.toInt()
            i += n
        }

        // the room the listener sits in
        if (useRoom || roomRings > 0) {
            System.arraycopy(roomL, 0, roomR, 0, frames)
            reverb.process(roomL, roomR, frames, ctx)
            for (k in 0 until frames) {
                l[k] += roomL[k]
                r[k] += roomR[k]
            }
            roomRings = if (useRoom) (sampleRate * ROOM_TAIL_SECONDS).toInt() else max(0, roomRings - frames)
        }
    }

    private var targetDelayL = 1f
    private var targetDelayR = 1f
    private var targetAlphaL = 1f
    private var targetAlphaR = 1f
    private var targetRear = 0f
    private var targetDirect = 1f
    private var targetLevelL = 1f
    private var targetLevelR = 1f
    private var targetSend = 0f

    /** Works out, for the next few samples, what each ear should hear from where the source is now. */
    private fun aim() {
        var az = angle
        if (motion != MOTION_NONE) {
            az += if (arc >= FULL_CIRCLE) 360f * phase.toFloat() else arc * 0.5f * FastMath.sin01(phase.toFloat())
        }
        val el = height * MAX_HEIGHT
        if (!placed) {
            nowAz = az
            nowEl = el
            placed = true
        } else {
            // a turned knob does not make the sound jump: it travels there, the short way round the head
            var turn = az - nowAz
            turn -= 360f * Math.round(turn / 360f)
            nowAz += turn * glide
            if (nowAz > 720f || nowAz < -720f) nowAz -= 360f * Math.round(nowAz / 360f)
            nowEl += (el - nowEl) * glide
        }
        val azR = nowAz * RAD
        val elR = nowEl * RAD
        val ce = cos(elR)
        dirX = sin(azR) * ce
        dirY = cos(azR) * ce
        dirZ = sin(elR)
        val x = dirX.coerceIn(-1f, 1f)

        // later at the far ear (Woodworth): a / c * (angle + sin angle)
        val side = asin(x)
        val itd = HEAD_RADIUS / SOUND_SPEED * (abs(side) + sin(abs(side))) * sampleRate
        targetDelayL = 1f + if (x > 0f) itd else 0f
        targetDelayR = 1f + if (x < 0f) itd else 0f

        // head shadow: how much of the top end each ear keeps, from the angle between the ear and the source
        val toRight = acos(x)
        targetAlphaR = shadow(toRight)
        targetAlphaL = shadow(FastMath.PI_F - toRight)

        val back = (-dirY).coerceIn(0f, 1f)
        targetRear = back * back * (3f - 2f * back)

        // echoes of the outer ear; their delays are given for 44.1 kHz
        val around = atan2(dirX, dirY)
        val up = asin(dirZ.coerceIn(-1f, 1f))
        val scale = sampleRate / 44100f
        val c = cos(around * 0.5f)
        for (e in 0 until ECHOES) {
            tap[e] = 1f + (ECHO_A[e] * c * sin(ECHO_D[e] * (HALF_PI - up)) + ECHO_B[e]) * scale
        }

        val d = distance
        targetDirect = 1f / (1f + 3.5f * d * d)
        // a source close to the head is also plainly louder in the ear it is next to
        val near = (0.1f + 0.2f * (1f - d) * (1f - d)) * x
        targetLevelL = 1f - near
        targetLevelR = 1f + near
        targetSend = room * (0.3f + 0.7f * d) * 0.9f
        air.setCutoff(18000f * (1f - 0.8f * d), sampleRate)
    }

    private fun shadow(angle: Float): Float = 1.05f + 0.95f * cos(angle * 1.2f)

    override fun reset() {
        pinna.clear()
        ears.clear()
        reverb.reset()
        air.reset()
        behind.reset()
        xL = 0f; yL = 0f; xR = 0f; yR = 0f
        phase = 0.0
        placed = false
        roomRings = 0
    }

    companion object {
        const val TYPE_ID = "space3d"
        const val ANGLE = 0
        const val HEIGHT = 1
        const val DISTANCE = 2
        const val ROOM = 3
        const val MOTION = 4
        const val RATE = 5
        const val ARC = 6

        const val MOTION_NONE = 0
        const val MOTION_FREE = 1

        /** Length of one round trip, in bars, for each choice of [MOTION] (the first two are "off" and "free"). */
        private val BARS = doubleArrayOf(0.0, 0.0, 8.0, 4.0, 2.0, 1.0, 0.5, 0.25)

        private const val STEP = 32
        private const val HEAD_RADIUS = 0.0875f
        private const val SOUND_SPEED = 343f
        private const val RAD = FastMath.PI_F / 180f
        private const val HALF_PI = FastMath.PI_F / 2f
        private const val MAX_HEIGHT = 75f
        private const val FULL_CIRCLE = 359.5f
        private const val REAR_DEPTH = 0.7f
        private const val ROOM_TAIL_SECONDS = 3f

        private const val ECHOES = 5
        private val ECHO_GAIN = floatArrayOf(0.3f, -0.6f, 0.3f, -0.15f, 0.15f)
        private val ECHO_A = floatArrayOf(1f, 5f, 5f, 5f, 5f)
        private val ECHO_B = floatArrayOf(2f, 4f, 7f, 11f, 13f)
        private val ECHO_D = floatArrayOf(1f, 0.5f, 0.5f, 0.5f, 0.5f)

        val PARAMS = listOf(
            ParamSpec("angle", R.string.p_angle, -180f, 180f, 0f, ParamUnit.DEGREES),
            ParamSpec("height", R.string.p_height, -1f, 1f, 0f, ParamUnit.PERCENT),
            ParamSpec("distance", R.string.p_distance, 0f, 1f, 0.2f, ParamUnit.PERCENT),
            ParamSpec("room", R.string.p_room, 0f, 1f, 0.25f, ParamUnit.PERCENT),
            ParamSpec("motion", R.string.p_motion, 0f, 7f, 3f, choicesRes = R.array.arr_3d_motion, wide = true),
            ParamSpec("rate", R.string.p_rate, 0.02f, 4f, 0.2f, ParamUnit.HZ, ParamCurve.EXP),
            ParamSpec("arc", R.string.p_arc, 0f, 360f, 360f, ParamUnit.DEGREES),
        )
        val TYPE = DeviceType(TYPE_ID, R.string.fx_3d, R.string.fx_3d_desc, false, PARAMS) { Space3D() }
    }
}
