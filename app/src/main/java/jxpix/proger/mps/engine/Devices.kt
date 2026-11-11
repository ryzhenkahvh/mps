package jxpix.proger.mps.engine

import jxpix.proger.mps.engine.effects.Tape
import jxpix.proger.mps.engine.effects.StereoWidth
import jxpix.proger.mps.engine.effects.Tremolo
import jxpix.proger.mps.engine.effects.Flanger
import jxpix.proger.mps.engine.instruments.StringSynth
import jxpix.proger.mps.engine.instruments.FmSynth
import jxpix.proger.mps.R
import jxpix.proger.mps.engine.effects.BitCrusher
import jxpix.proger.mps.engine.effects.Chorus
import jxpix.proger.mps.engine.effects.Compressor
import jxpix.proger.mps.engine.effects.Delay
import jxpix.proger.mps.engine.effects.Distortion
import jxpix.proger.mps.engine.effects.Eq3
import jxpix.proger.mps.engine.effects.FilterFx
import jxpix.proger.mps.engine.effects.Limiter
import jxpix.proger.mps.engine.effects.Phaser
import jxpix.proger.mps.engine.effects.Pumper
import jxpix.proger.mps.engine.effects.Reverb
import jxpix.proger.mps.engine.instruments.DrumSynth
import jxpix.proger.mps.engine.instruments.PolySynth
import jxpix.proger.mps.engine.instruments.Sampler

/**
 * Catalogue of every built-in instrument and effect.
 *
 * The rest of the app only talks to devices through [DeviceType] metadata, so adding a
 * new effect is a matter of writing the DSP class and listing it here.
 */
object Devices {
    /** Channel that plays audio clips placed in the playlist (recordings, loops). */
    const val AUDIO_TYPE_ID = "audio"

    val SYNTH: DeviceType = PolySynth.TYPE
    val DRUM: DeviceType = DrumSynth.TYPE
    val SAMPLER: DeviceType = Sampler.TYPE
    val FM: DeviceType = FmSynth.TYPE
    val STRING: DeviceType = StringSynth.TYPE
    val AUDIO: DeviceType = DeviceType(AUDIO_TYPE_ID, R.string.dev_audio, R.string.dev_audio_desc, true, emptyList()) { null }

    val instruments: List<DeviceType> = listOf(DRUM, SYNTH, FM, STRING, SAMPLER, AUDIO)

    /** The kinds of instrument a channel can be switched between; they all play notes. */
    val playable: List<DeviceType> = listOf(DRUM, SYNTH, FM, STRING, SAMPLER)

    val effects: List<DeviceType> = listOf(
        Eq3.TYPE,
        Compressor.TYPE,
        Reverb.TYPE,
        Delay.TYPE,
        FilterFx.TYPE,
        Distortion.TYPE,
        Chorus.TYPE,
        Phaser.TYPE,
        BitCrusher.TYPE,
        Flanger.TYPE,
        Tremolo.TYPE,
        Pumper.TYPE,
        StereoWidth.TYPE,
        Tape.TYPE,
        Limiter.TYPE,
    )

    private val byId: Map<String, DeviceType> = (instruments + effects).associateBy { it.id }

    operator fun get(id: String): DeviceType? = byId[id]
}
