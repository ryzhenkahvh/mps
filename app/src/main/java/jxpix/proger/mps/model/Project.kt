package jxpix.proger.mps.model

import jxpix.proger.mps.engine.Devices
import jxpix.proger.mps.engine.PPQ
import jxpix.proger.mps.engine.TICKS_PER_STEP
import kotlinx.serialization.Serializable
import kotlin.math.log10
import kotlin.math.pow

/*
 * The whole project is an immutable tree of data classes.
 *
 *  - Every edit creates a new tree (unchanged branches are shared), which gives us
 *    undo/redo for free and lets Compose skip everything that did not change.
 *  - The audio engine receives the new tree, "compiles" it off the audio thread and
 *    swaps it in atomically - so there are no locks between the UI and the sound.
 */

/** An instrument or effect instance: its type plus the current parameter values. */
@Serializable
data class DeviceState(
    /** Unique inside the project; lets the engine keep the running DSP object across edits. */
    val uid: Int,
    val type: String,
    val params: Map<String, Float> = emptyMap(),
    val enabled: Boolean = true,
    /** File name (inside the project's sample folder) for sample based instruments. */
    val sample: String? = null,
    /** Name of the preset the parameters were loaded from, for display only. */
    val preset: String? = null,
) {
    fun param(id: String): Float = params[id] ?: Devices[type]?.spec(id)?.default ?: 0f
}

@Serializable
data class Note(
    /** Position inside the pattern, in ticks ([PPQ] per quarter note). */
    val start: Int,
    val length: Int,
    /** MIDI note number, 60 = C4. */
    val pitch: Int,
    val velocity: Float = 0.8f,
) {
    val end: Int get() = start + length
}

/** One row of the channel rack: an instrument with its own mixer strip. */
@Serializable
data class Channel(
    val id: Int,
    val name: String,
    /** Index into the channel colour palette. */
    val color: Int = 0,
    val instrument: DeviceState,
    /** Fader position 0..1 (0.8 = 0 dB). */
    val volume: Float = 0.8f,
    /** -1 (left) .. +1 (right). */
    val pan: Float = 0f,
    val mute: Boolean = false,
    val solo: Boolean = false,
    val effects: List<DeviceState> = emptyList(),
    /** Channels sharing a non-zero cut group silence each other (closed/open hi-hat). */
    val cutGroup: Int = 0,
    /** Note played by the step sequencer. */
    val rootNote: Int = 60,
) {
    val isAudio: Boolean get() = instrument.type == Devices.AUDIO_TYPE_ID
    val isDrum: Boolean get() = instrument.type == Devices.DRUM.id
}

/** One point of an automation curve. */
@Serializable
data class AutoPoint(
    /** Position inside the pattern, in ticks. */
    val tick: Int,
    /** Knob position 0..1 (see [jxpix.proger.mps.engine.ParamSpec.toNorm]). */
    val value: Float,
)

/**
 * Automation: a curve that turns one knob while its pattern plays - the volume or balance of
 * a channel, or any parameter of its instrument and effects. Between the points the value
 * moves in a straight line; before the first and after the last point it stays put.
 * When the pattern is not playing the knob is back where the user left it.
 */
@Serializable
data class AutoLane(
    val channelId: Int,
    /** Uid of the instrument or effect, or [STRIP_DEVICE] for the mixer strip of the channel. */
    val device: Int = STRIP_DEVICE,
    /** Parameter id; on the mixer strip [PARAM_VOLUME] or [PARAM_PAN]. */
    val param: String,
    /** Sorted by tick, one point per tick at most. */
    val points: List<AutoPoint> = emptyList(),
) {
    fun targets(channelId: Int, device: Int, param: String): Boolean =
        this.channelId == channelId && this.device == device && this.param == param
}

/** [AutoLane.device] of the lanes that move the fader or the balance knob of the mixer. */
const val STRIP_DEVICE = 0
const val PARAM_VOLUME = "volume"
const val PARAM_PAN = "pan"

/** A loop of notes for any number of channels (what FL Studio calls a pattern). */
@Serializable
data class Pattern(
    val id: Int,
    val name: String,
    val color: Int = 0,
    val bars: Int = 1,
    /** Notes per channel id. Lists are kept in insertion order; the engine sorts them. */
    val notes: Map<Int, List<Note>> = emptyMap(),
    val automation: List<AutoLane> = emptyList(),
) {
    fun notesOf(channelId: Int): List<Note> = notes[channelId] ?: emptyList()
    val isEmpty: Boolean get() = notes.values.all { it.isEmpty() }

    fun lane(channelId: Int, device: Int, param: String): AutoLane? =
        automation.firstOrNull { it.targets(channelId, device, param) }
}

/** A block in the playlist: either a pattern instance or a piece of recorded/imported audio. */
@Serializable
data class Clip(
    val id: Int,
    val track: Int,
    /** Song position in ticks. */
    val start: Int,
    val length: Int,
    /** Pattern clips: the pattern to play (it repeats when the clip is longer than the pattern). */
    val patternId: Int = 0,
    /** Audio clips: the audio channel (mixer strip) the clip plays through. */
    val channelId: Int = 0,
    /** Audio clips: sample file name. */
    val sample: String? = null,
    /** Audio clips: where inside the sample the clip starts, in seconds. */
    val offset: Float = 0f,
    val gain: Float = 1f,
    val muted: Boolean = false,
) {
    val isAudio: Boolean get() = sample != null
    val end: Int get() = start + length
}

@Serializable
data class Project(
    val version: Int = 1,
    val name: String = "",
    val bpm: Float = 120f,
    val beatsPerBar: Int = 4,
    /** 0 = straight, 1 = hard shuffle. Delays every second 16th note. */
    val swing: Float = 0f,
    val masterVolume: Float = 0.8f,
    val masterEffects: List<DeviceState> = emptyList(),
    val channels: List<Channel> = emptyList(),
    val patterns: List<Pattern> = emptyList(),
    val clips: List<Clip> = emptyList(),
    /** Number of lanes in the playlist. */
    val tracks: Int = 6,
    val loopEnabled: Boolean = false,
    /** Loop region of the song, in bars. */
    val loopStart: Int = 0,
    val loopEnd: Int = 4,
    /** Next free id for channels, patterns, clips and devices. */
    val nextId: Int = 1,
) {
    val ticksPerBar: Int get() = beatsPerBar * PPQ
    val stepsPerBar: Int get() = beatsPerBar * 4

    fun channel(id: Int): Channel? = channels.firstOrNull { it.id == id }
    fun pattern(id: Int): Pattern? = patterns.firstOrNull { it.id == id }
    fun clip(id: Int): Clip? = clips.firstOrNull { it.id == id }
    fun patternLength(p: Pattern): Int = p.bars * ticksPerBar

    /** End of the last clip, in ticks. */
    val songEnd: Int get() = clips.maxOfOrNull { it.end } ?: 0
    val songBars: Int get() = (songEnd + ticksPerBar - 1) / ticksPerBar

    val secondsPerTick: Double get() = 60.0 / (bpm * PPQ)
}

/** Id used to address the master strip wherever a channel id is expected. */
const val MASTER_ID = 0

const val MIN_BPM = 40f
const val MAX_BPM = 240f

/** Fader position (0..1, 0.8 = unity) -> linear gain, with an audio taper. */
fun faderToGain(position: Float): Float =
    if (position <= 0.001f) 0f else (position / 0.8f).pow(3f)

fun faderToDb(position: Float): Float {
    val g = faderToGain(position)
    return if (g <= 0.00001f) Float.NEGATIVE_INFINITY else 20f * log10(g)
}

/** Shifts every second 16th note to create shuffle. Monotonic, so note order is preserved. */
fun swingTick(tick: Int, swing: Float): Int {
    if (swing <= 0.001f || tick < 0) return tick
    val pair = TICKS_PER_STEP * 2
    val base = (tick / pair) * pair
    val ph = (tick - base).toFloat()
    val shift = swing * TICKS_PER_STEP * 0.5f
    val warped = if (ph < TICKS_PER_STEP) ph * (TICKS_PER_STEP + shift) / TICKS_PER_STEP
    else (TICKS_PER_STEP + shift) + (ph - TICKS_PER_STEP) * (TICKS_PER_STEP - shift) / TICKS_PER_STEP
    return base + Math.round(warped)
}

private val NOTE_NAMES = arrayOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")

/** 60 -> "C4". */
fun noteName(pitch: Int): String = NOTE_NAMES[((pitch % 12) + 12) % 12] + (pitch / 12 - 1)

fun isBlackKey(pitch: Int): Boolean = when (((pitch % 12) + 12) % 12) {
    1, 3, 6, 8, 10 -> true
    else -> false
}
