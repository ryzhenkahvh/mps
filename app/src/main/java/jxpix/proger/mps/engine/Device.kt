package jxpix.proger.mps.engine

import androidx.annotation.ArrayRes
import androidx.annotation.StringRes
import kotlin.math.pow
import kotlin.math.roundToInt

/** Musical resolution of the whole app: ticks per quarter note. */
const val PPQ = 480

/** One step of the step sequencer (a 16th note). */
const val TICKS_PER_STEP = PPQ / 4

/** Largest block the engine renders in one go. */
const val MAX_BLOCK = 512

/**
 * How the value of a knob is written. [DB] is a gain and carries its sign ("+6.0 dB"); [DB_AMOUNT] says
 * how much is taken away, not which way ("12.0 dB": how far a gate closes, how much a de-esser removes).
 */
enum class ParamUnit { NONE, PERCENT, HZ, SECONDS, MILLIS, DB, SEMITONES, CENTS, OCTAVES, RATIO, NOTE, INT, DEGREES, DB_AMOUNT }

enum class ParamCurve { LINEAR, EXP, SQUARE }

/**
 * Describes one automatable parameter of an instrument or effect.
 * The UI is generated from these specs, the DSP reads the values by index.
 */
class ParamSpec(
    val id: String,
    @param:StringRes val nameRes: Int,
    val min: Float,
    val max: Float,
    val default: Float,
    val unit: ParamUnit = ParamUnit.NONE,
    val curve: ParamCurve = ParamCurve.LINEAR,
    @param:ArrayRes val choicesRes: Int = 0,
    @param:StringRes val group: Int = 0,
    val integer: Boolean = false,
    /** A choice whose options have long names ("Harmonic minor"): its selector takes the room of two knobs. */
    val wide: Boolean = false,
) {
    val isChoice: Boolean get() = choicesRes != 0

    /** Maps a real value to a 0..1 knob position. */
    fun toNorm(value: Float): Float {
        val v = value.coerceIn(min, max)
        return when (curve) {
            ParamCurve.LINEAR -> (v - min) / (max - min)
            ParamCurve.SQUARE -> ((v - min) / (max - min)).pow(0.5f)
            ParamCurve.EXP -> (kotlin.math.ln(v / min) / kotlin.math.ln(max / min))
        }.coerceIn(0f, 1f)
    }

    /** Maps a 0..1 knob position back to a real value. */
    fun fromNorm(norm: Float): Float {
        val n = norm.coerceIn(0f, 1f)
        var v = when (curve) {
            ParamCurve.LINEAR -> min + (max - min) * n
            ParamCurve.SQUARE -> min + (max - min) * n * n
            ParamCurve.EXP -> min * (max / min).pow(n)
        }
        if (integer || isChoice) v = v.roundToInt().toFloat()
        return v.coerceIn(min, max)
    }
}

/** Everything a device may want to know about the transport while processing. */
class ProcessContext {
    var sampleRate = 48000
    var bpm = 120.0
    var playing = false

    /**
     * Position (in ticks) of the first frame of the current block. It is the song position as long
     * as nothing has come round; when a pattern or a loop starts again it keeps counting, so that
     * a movement longer than the loop is not cut short. Negative during the count-in.
     */
    var tickPos = 0.0
    var ticksPerFrame = 0.0

    /** Length of a bar in ticks. */
    var ticksPerBar = 4 * PPQ

    /** The notes of the song's key as twelve bits (bit 0 = C); 0 = the song has no key. */
    var keyMask = 0
}

abstract class Device {
    protected var sampleRate = 48000f

    /** Called once, off the audio thread, before the device is used. May allocate. */
    open fun prepare(sampleRate: Int) {
        this.sampleRate = sampleRate.toFloat()
    }

    /** Called on the audio thread whenever the parameter set changed. Must not allocate. */
    abstract fun setParams(p: FloatArray)

    open fun reset() {}
}

abstract class Instrument : Device() {
    /**
     * Balance of the note that is being started, -1 (left) .. +1 (right): implementations of
     * [noteOn] read it and give it to the voice they start. Zero outside of [noteOn].
     */
    protected var notePan = 0f

    abstract fun noteOn(pitch: Int, velocity: Float)

    /** Starts a note that has a place of its own between the speakers. */
    fun noteOn(pitch: Int, velocity: Float, pan: Float) {
        notePan = if (pan < -1f) -1f else if (pan > 1f) 1f else pan
        noteOn(pitch, velocity)
        notePan = 0f
    }

    /** Gain of the left side for a balance of [pan]: nothing is boosted, the far side is turned down. */
    protected fun panLeft(pan: Float): Float = if (pan > 0f) 1f - pan else 1f

    protected fun panRight(pan: Float): Float = if (pan < 0f) 1f + pan else 1f
    abstract fun noteOff(pitch: Int)
    abstract fun allNotesOff()

    /** Fast fade-out used by cut groups (e.g. closed hat choking the open hat). */
    open fun choke() = allNotesOff()

    /** Adds [count] frames of sound to the buffers starting at [offset]. */
    abstract fun render(outL: FloatArray, outR: FloatArray, offset: Int, count: Int)

    /** False when nothing is sounding, lets the engine skip silent channels. */
    abstract val active: Boolean

    open fun setSample(sample: SampleData?) {}
}

abstract class Effect : Device() {
    /** Processes the buffers in place. */
    abstract fun process(l: FloatArray, r: FloatArray, frames: Int, ctx: ProcessContext)
}

/** Registry entry: metadata + factory for one kind of device. */
class DeviceType(
    val id: String,
    @param:StringRes val nameRes: Int,
    @param:StringRes val descRes: Int,
    val isInstrument: Boolean,
    val params: List<ParamSpec>,
    private val factory: () -> Device?,
) {
    private val indexById: Map<String, Int> = params.withIndex().associate { it.value.id to it.index }

    fun create(): Device? = factory()

    fun spec(id: String): ParamSpec? = indexById[id]?.let { params[it] }

    fun indexOf(id: String): Int = indexById[id] ?: -1

    fun defaults(): Map<String, Float> = params.associate { it.id to it.default }

    /** Flattens a (possibly partial) id->value map into the array the DSP reads. */
    fun toArray(values: Map<String, Float>): FloatArray =
        FloatArray(params.size) { i ->
            val s = params[i]
            (values[s.id] ?: s.default).coerceIn(s.min, s.max)
        }
}
