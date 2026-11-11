package jxpix.proger.mps.model

import jxpix.proger.mps.engine.effects.Tape
import jxpix.proger.mps.engine.effects.StereoWidth
import jxpix.proger.mps.engine.effects.Tremolo
import jxpix.proger.mps.engine.effects.Flanger
import jxpix.proger.mps.engine.instruments.StringSynth
import jxpix.proger.mps.engine.instruments.FmSynth
import androidx.annotation.StringRes
import jxpix.proger.mps.R
import jxpix.proger.mps.engine.DeviceType
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
import jxpix.proger.mps.engine.Devices

/** A named set of parameter values for a device. Missing parameters fall back to their defaults. */
class Preset(
    val id: String,
    @param:StringRes val nameRes: Int,
    @param:StringRes val categoryRes: Int,
    val params: Map<String, Float>,
)

object Presets {

    private const val SINE = 0f
    private const val TRI = 1f
    private const val SAW = 2f
    private const val SQUARE = 3f
    private const val NOISE = 4f

    private const val LP12 = 0f
    private const val LP24 = 1f
    private const val HP = 2f
    private const val BP = 3f

    private const val POLY = 0f
    private const val MONO = 1f
    private const val LEGATO = 2f

    private fun amp(a: Float, d: Float, s: Float, r: Float) =
        arrayOf("ampAttack" to a, "ampDecay" to d, "ampSustain" to s, "ampRelease" to r)

    private fun flt(a: Float, d: Float, s: Float, r: Float) =
        arrayOf("fltAttack" to a, "fltDecay" to d, "fltSustain" to s, "fltRelease" to r)

    private fun synth(id: String, name: Int, category: Int, vararg values: Pair<String, Float>): Preset {
        val map = HashMap(Devices.SYNTH.defaults())
        for ((k, v) in values) {
            require(Devices.SYNTH.spec(k) != null) { "Unknown synth parameter $k" }
            map[k] = v
        }
        return Preset(id, name, category, map)
    }

    private val BASS = R.string.cat_bass
    private val LEAD = R.string.cat_lead
    private val KEYS = R.string.cat_keys
    private val PAD = R.string.cat_pad
    private val FX = R.string.cat_fx

    val synth: List<Preset> = listOf(
        synth("init", R.string.pre_init, LEAD),

        // ------------------------------------------------------------------ bass
        synth(
            "deep_bass", R.string.pre_deep_bass, BASS,
            "osc1Wave" to SAW, "osc2Wave" to SQUARE, "osc2Oct" to -1f, "osc2Level" to 0.5f, "osc2Detune" to 4f,
            "subLevel" to 0.6f, "filterType" to LP24, "cutoff" to 220f, "resonance" to 0.2f,
            "envAmount" to 0.32f, "keyTrack" to 0.5f, "drive" to 0.25f,
            *amp(0.003f, 0.35f, 0.7f, 0.09f), *flt(0.002f, 0.2f, 0.15f, 0.1f),
            "mode" to MONO, "velSens" to 0.3f, "level" to 0.8f,
        ),
        synth(
            "sub_bass", R.string.pre_sub_bass, BASS,
            "osc1Wave" to SINE, "osc1Level" to 0.9f, "osc2Wave" to TRI, "osc2Oct" to 1f, "osc2Level" to 0.18f,
            "osc2Detune" to 0f, "filterType" to LP12, "cutoff" to 900f, "resonance" to 0f, "drive" to 0.3f,
            *amp(0.005f, 0.3f, 0.9f, 0.12f), "mode" to LEGATO, "glide" to 0.2f, "velSens" to 0.2f, "level" to 0.85f,
        ),
        synth(
            "reese", R.string.pre_reese, BASS,
            "osc1Wave" to SAW, "osc2Wave" to SAW, "osc2Level" to 0.8f, "osc2Detune" to 22f,
            "unison" to 3f, "uniDetune" to 0.35f, "uniSpread" to 0.5f, "subLevel" to 0.4f,
            "filterType" to LP24, "cutoff" to 700f, "resonance" to 0.25f, "keyTrack" to 0.4f, "drive" to 0.3f,
            *amp(0.01f, 0.3f, 0.9f, 0.15f), "lfoRate" to 0.3f, "lfoCutoff" to 0.25f,
            "mode" to LEGATO, "glide" to 0.25f, "velSens" to 0.2f, "level" to 0.7f,
        ),
        synth(
            "acid", R.string.pre_acid, BASS,
            "osc1Wave" to SAW, "filterType" to LP24, "cutoff" to 260f, "resonance" to 0.8f,
            "envAmount" to 0.42f, "keyTrack" to 0.3f, "drive" to 0.5f,
            *amp(0.002f, 0.3f, 0.6f, 0.06f), *flt(0.002f, 0.18f, 0.05f, 0.1f),
            "mode" to LEGATO, "glide" to 0.22f, "velSens" to 0.5f, "level" to 0.7f,
        ),
        synth(
            "wobble", R.string.pre_wobble, BASS,
            "osc1Wave" to SAW, "osc2Wave" to SQUARE, "osc2Level" to 0.6f, "osc2Detune" to 10f, "subLevel" to 0.5f,
            "filterType" to LP24, "cutoff" to 500f, "resonance" to 0.45f, "drive" to 0.4f,
            *amp(0.005f, 0.3f, 1f, 0.1f), "lfoWave" to 0f, "lfoRate" to 4f, "lfoCutoff" to 0.6f,
            "mode" to MONO, "velSens" to 0.2f, "level" to 0.7f,
        ),

        // ------------------------------------------------------------------ leads
        synth(
            "supersaw", R.string.pre_supersaw, LEAD,
            "osc1Wave" to SAW, "osc2Wave" to SAW, "osc2Oct" to 1f, "osc2Level" to 0.35f, "osc2Detune" to 12f,
            "unison" to 5f, "uniDetune" to 0.45f, "uniSpread" to 0.9f,
            "filterType" to LP12, "cutoff" to 7500f, "resonance" to 0.1f, "envAmount" to 0.1f,
            *amp(0.005f, 0.4f, 0.75f, 0.3f), "velSens" to 0.5f, "level" to 0.7f,
        ),
        synth(
            "mono_lead", R.string.pre_mono_lead, LEAD,
            "osc1Wave" to SAW, "osc2Wave" to SQUARE, "osc2Level" to 0.5f, "osc2Detune" to 9f,
            "filterType" to LP24, "cutoff" to 1800f, "resonance" to 0.3f, "envAmount" to 0.2f, "keyTrack" to 0.5f,
            *amp(0.004f, 0.3f, 0.8f, 0.15f), *flt(0.004f, 0.35f, 0.4f, 0.2f),
            "lfoRate" to 5.5f, "lfoPitch" to 0.14f, "mode" to LEGATO, "glide" to 0.18f, "level" to 0.65f,
        ),
        synth(
            "chip", R.string.pre_chip, LEAD,
            "osc1Wave" to SQUARE, "pulseWidth" to 0.25f, "filterType" to LP12, "cutoff" to 16000f, "resonance" to 0f,
            "keyTrack" to 0f, *amp(0.001f, 0.1f, 1f, 0.04f), "lfoRate" to 6f, "lfoPitch" to 0.12f,
            "velSens" to 0.3f, "level" to 0.5f,
        ),
        synth(
            "brass", R.string.pre_brass, LEAD,
            "osc1Wave" to SAW, "osc2Wave" to SAW, "osc2Level" to 0.7f, "osc2Detune" to 12f,
            "unison" to 3f, "uniDetune" to 0.2f, "uniSpread" to 0.5f,
            "filterType" to LP12, "cutoff" to 500f, "resonance" to 0.15f, "envAmount" to 0.34f, "keyTrack" to 0.4f,
            *amp(0.03f, 0.3f, 0.85f, 0.15f), *flt(0.05f, 0.35f, 0.6f, 0.2f), "level" to 0.65f,
        ),

        // ------------------------------------------------------------------ keys
        synth(
            "pluck", R.string.pre_pluck, KEYS,
            "osc1Wave" to SAW, "osc2Wave" to SQUARE, "osc2Level" to 0.4f, "osc2Detune" to 7f,
            "unison" to 3f, "uniDetune" to 0.2f, "uniSpread" to 0.8f,
            "filterType" to LP12, "cutoff" to 500f, "resonance" to 0.2f, "envAmount" to 0.4f, "keyTrack" to 0.5f,
            *amp(0.002f, 0.4f, 0f, 0.35f), *flt(0.001f, 0.2f, 0f, 0.25f), "velSens" to 0.7f, "level" to 0.7f,
        ),
        synth(
            "e_piano", R.string.pre_e_piano, KEYS,
            "osc1Wave" to SINE, "osc1Level" to 0.9f, "osc2Wave" to TRI, "osc2Oct" to 1f, "osc2Level" to 0.32f,
            "osc2Detune" to 3f, "filterType" to LP12, "cutoff" to 2200f, "resonance" to 0f, "envAmount" to 0.2f,
            "keyTrack" to 0.6f, *amp(0.002f, 1.4f, 0.12f, 0.4f), *flt(0.001f, 0.5f, 0.1f, 0.4f),
            "lfoRate" to 4.5f, "lfoAmp" to 0.18f, "velSens" to 0.85f, "level" to 0.85f,
        ),
        synth(
            "organ", R.string.pre_organ, KEYS,
            "osc1Wave" to SINE, "osc1Level" to 0.8f, "osc2Wave" to SINE, "osc2Oct" to 1f, "osc2Level" to 0.5f,
            "osc2Detune" to 0f, "subLevel" to 0.5f, "filterType" to LP12, "cutoff" to 6000f, "resonance" to 0f,
            *amp(0.004f, 0.1f, 1f, 0.06f), "lfoRate" to 6.5f, "lfoPitch" to 0.08f, "velSens" to 0.1f, "level" to 0.65f,
        ),
        synth(
            "bell", R.string.pre_bell, KEYS,
            "osc1Wave" to SINE, "osc1Level" to 0.8f, "osc2Wave" to SINE, "osc2Oct" to 1f, "osc2Semi" to 7f,
            "osc2Level" to 0.35f, "osc2Detune" to 5f, "filterType" to LP12, "cutoff" to 9000f, "resonance" to 0f,
            *amp(0.001f, 1.8f, 0f, 1.6f), "velSens" to 0.8f, "level" to 0.8f,
        ),

        // ------------------------------------------------------------------ pads
        synth(
            "soft_pad", R.string.pre_soft_pad, PAD,
            "osc1Wave" to SAW, "osc2Wave" to TRI, "osc2Level" to 0.6f, "osc2Detune" to 10f,
            "unison" to 3f, "uniDetune" to 0.3f, "uniSpread" to 0.9f,
            "filterType" to LP12, "cutoff" to 1100f, "resonance" to 0.1f, "envAmount" to 0.12f, "keyTrack" to 0.4f,
            *amp(0.5f, 1f, 0.8f, 1.2f), *flt(0.8f, 1.5f, 0.6f, 1.2f),
            "lfoRate" to 0.25f, "lfoCutoff" to 0.12f, "velSens" to 0.3f, "level" to 0.6f,
        ),
        synth(
            "strings", R.string.pre_strings, PAD,
            "osc1Wave" to SAW, "osc2Wave" to SAW, "osc2Oct" to 1f, "osc2Level" to 0.3f, "osc2Detune" to 9f,
            "unison" to 5f, "uniDetune" to 0.32f, "uniSpread" to 1f,
            "filterType" to LP12, "cutoff" to 3200f, "resonance" to 0.05f, "keyTrack" to 0.3f,
            *amp(0.22f, 0.5f, 0.85f, 0.6f), "lfoRate" to 5.2f, "lfoPitch" to 0.06f, "velSens" to 0.4f, "level" to 0.55f,
        ),
        synth(
            "warm_chords", R.string.pre_warm_chords, PAD,
            "osc1Wave" to SAW, "osc2Wave" to SQUARE, "osc2Level" to 0.35f, "osc2Detune" to 8f, "pulseWidth" to 0.4f,
            "unison" to 3f, "uniDetune" to 0.25f, "uniSpread" to 0.8f,
            "filterType" to LP24, "cutoff" to 900f, "resonance" to 0.12f, "envAmount" to 0.22f, "keyTrack" to 0.4f,
            *amp(0.01f, 0.6f, 0.7f, 0.4f), *flt(0.01f, 0.5f, 0.35f, 0.4f), "velSens" to 0.4f, "level" to 0.6f,
        ),

        // ------------------------------------------------------------------ effects
        synth(
            "riser", R.string.pre_riser, FX,
            "osc1Wave" to NOISE, "osc1Level" to 0.7f, "filterType" to BP, "cutoff" to 300f, "resonance" to 0.5f,
            "envAmount" to 0.6f, "keyTrack" to 0f, *amp(1.5f, 0.5f, 1f, 0.3f), *flt(3.5f, 0.5f, 1f, 0.3f),
            "velSens" to 0.1f, "level" to 0.6f,
        ),
        synth(
            "air", R.string.pre_air, FX,
            "osc1Wave" to TRI, "osc2Wave" to NOISE, "osc2Level" to 0.25f, "unison" to 3f, "uniDetune" to 0.4f,
            "uniSpread" to 1f, "filterType" to HP, "cutoff" to 700f, "resonance" to 0.2f,
            *amp(0.8f, 1f, 0.8f, 1.8f), "lfoRate" to 0.4f, "lfoCutoff" to 0.2f, "level" to 0.55f,
        ),
    )

    private val synthById = synth.associateBy { it.id }

    fun synth(id: String): Preset = synthById[id] ?: synth[0]

    // ------------------------------------------------------------------------------ other devices

    /** A preset of any device: the given knobs, everything else at its default. */
    private fun of(type: DeviceType, id: String, name: Int, vararg values: Pair<String, Float>): Preset {
        val map = HashMap(type.defaults())
        for ((k, v) in values) {
            val spec = requireNotNull(type.spec(k)) { "Unknown parameter $k of ${type.id}" }
            require(v >= spec.min && v <= spec.max) { "$id: $k = $v is out of range" }
            map[k] = v
        }
        return Preset(id, name, 0, map)
    }

    /** Starting points for the effects, by device type id. */
    private val others: Map<String, List<Preset>> = listOf(
        Eq3.TYPE.let { t ->
            t.id to listOf(
                of(t, "eq_bass", R.string.fxp_bass_boost, "lowGain" to 6f, "lowFreq" to 100f),
                of(t, "eq_bright", R.string.fxp_brighter, "highGain" to 5f, "highFreq" to 8000f),
                of(t, "eq_clean", R.string.fxp_less_mud, "lowGain" to -6f, "lowFreq" to 180f, "midGain" to -3f, "midFreq" to 350f, "midQ" to 1.2f),
                of(t, "eq_smile", R.string.fxp_smile, "lowGain" to 5f, "lowFreq" to 90f, "midGain" to -4f, "midFreq" to 900f, "midQ" to 0.7f, "highGain" to 5f, "highFreq" to 9000f),
                of(t, "eq_phone", R.string.fxp_telephone, "lowGain" to -18f, "lowFreq" to 400f, "midGain" to 8f, "midFreq" to 1800f, "midQ" to 1.2f, "highGain" to -18f, "highFreq" to 3000f),
            )
        },
        Compressor.TYPE.let { t ->
            t.id to listOf(
                of(t, "comp_gentle", R.string.fxp_gentle, "threshold" to -16f, "ratio" to 2f, "attack" to 20f, "release" to 150f, "makeup" to 2f),
                of(t, "comp_punch", R.string.fxp_punchy_drums, "threshold" to -20f, "ratio" to 4f, "attack" to 25f, "release" to 90f, "makeup" to 4f),
                of(t, "comp_vocal", R.string.fxp_vocal, "threshold" to -22f, "ratio" to 3f, "attack" to 8f, "release" to 140f, "makeup" to 5f),
                of(t, "comp_glue", R.string.fxp_glue, "threshold" to -14f, "ratio" to 2.5f, "attack" to 30f, "release" to 250f, "makeup" to 2f),
                of(t, "comp_parallel", R.string.fxp_parallel, "threshold" to -35f, "ratio" to 10f, "attack" to 3f, "release" to 100f, "makeup" to 10f, "mix" to 0.35f),
            )
        },
        Reverb.TYPE.let { t ->
            t.id to listOf(
                of(t, "rev_room", R.string.fxp_room, "size" to 0.3f, "damp" to 0.5f, "preDelay" to 5f, "mix" to 0.18f),
                of(t, "rev_hall", R.string.fxp_hall, "size" to 0.75f, "damp" to 0.35f, "preDelay" to 25f, "mix" to 0.3f),
                of(t, "rev_plate", R.string.fxp_plate, "size" to 0.55f, "damp" to 0.15f, "preDelay" to 0f, "lowCut" to 250f, "mix" to 0.25f),
                of(t, "rev_space", R.string.fxp_space, "size" to 0.97f, "damp" to 0.2f, "preDelay" to 40f, "mix" to 0.45f),
            )
        },
        Delay.TYPE.let { t ->
            t.id to listOf(
                of(t, "del_slap", R.string.fxp_slapback, "sync" to 0f, "time" to 110f, "feedback" to 0.12f, "mix" to 0.25f, "pingPong" to 0f, "damp" to 0.3f),
                of(t, "del_eighth", R.string.fxp_eighth_echo, "sync" to 5f, "feedback" to 0.4f, "mix" to 0.25f, "pingPong" to 0f),
                of(t, "del_dotted", R.string.fxp_dotted_eighth, "sync" to 6f, "feedback" to 0.45f, "mix" to 0.3f, "pingPong" to 1f),
                of(t, "del_ping", R.string.fxp_ping_pong, "sync" to 8f, "feedback" to 0.5f, "mix" to 0.3f, "pingPong" to 1f, "damp" to 0.5f),
                of(t, "del_dub", R.string.fxp_dub, "sync" to 9f, "feedback" to 0.7f, "mix" to 0.35f, "pingPong" to 0f, "damp" to 0.75f),
            )
        },
        FilterFx.TYPE.let { t ->
            t.id to listOf(
                of(t, "flt_dark", R.string.fxp_dark, "type" to 0f, "cutoff" to 900f, "resonance" to 0.2f),
                of(t, "flt_thin", R.string.fxp_thin, "type" to 1f, "cutoff" to 300f, "resonance" to 0.1f),
                of(t, "flt_radio", R.string.fxp_radio, "type" to 2f, "cutoff" to 1500f, "resonance" to 0.4f),
                of(t, "flt_wah", R.string.fxp_auto_wah, "type" to 2f, "cutoff" to 1200f, "resonance" to 0.7f, "lfoRate" to 2f, "lfoDepth" to 0.6f),
                of(t, "flt_sweep", R.string.fxp_slow_sweep, "type" to 0f, "cutoff" to 1500f, "resonance" to 0.5f, "lfoRate" to 0.15f, "lfoDepth" to 0.7f),
            )
        },
        Distortion.TYPE.let { t ->
            t.id to listOf(
                of(t, "dst_warm", R.string.fxp_warm, "type" to 3f, "drive" to 0.25f, "tone" to 0.6f, "level" to -1f),
                of(t, "dst_crunch", R.string.fxp_crunch, "type" to 0f, "drive" to 0.55f, "tone" to 0.7f, "level" to -4f),
                of(t, "dst_fuzz", R.string.fxp_fuzz, "type" to 1f, "drive" to 0.85f, "tone" to 0.55f, "level" to -8f),
                of(t, "dst_fold", R.string.fxp_metallic, "type" to 2f, "drive" to 0.6f, "tone" to 0.8f, "level" to -6f),
            )
        },
        Chorus.TYPE.let { t ->
            t.id to listOf(
                of(t, "cho_subtle", R.string.fxp_subtle, "rate" to 0.5f, "depth" to 0.3f, "delay" to 10f, "feedback" to 0f, "mix" to 0.3f),
                of(t, "cho_wide", R.string.fxp_wide, "rate" to 0.8f, "depth" to 0.7f, "delay" to 15f, "feedback" to 0.1f, "mix" to 0.5f),
                of(t, "cho_vibrato", R.string.fxp_vibrato, "rate" to 5f, "depth" to 0.4f, "delay" to 5f, "feedback" to 0f, "mix" to 1f),
            )
        },
        Phaser.TYPE.let { t ->
            t.id to listOf(
                of(t, "pha_slow", R.string.fxp_slow_sweep, "rate" to 0.15f, "depth" to 0.8f, "feedback" to 0.5f, "center" to 700f, "mix" to 0.5f),
                of(t, "pha_jet", R.string.fxp_jet, "rate" to 0.3f, "depth" to 1f, "feedback" to 0.85f, "center" to 1200f, "mix" to 0.5f),
                of(t, "pha_fast", R.string.fxp_fast_swirl, "rate" to 3f, "depth" to 0.6f, "feedback" to 0.4f, "center" to 1000f, "mix" to 0.5f),
            )
        },
        BitCrusher.TYPE.let { t ->
            t.id to listOf(
                of(t, "cru_lofi", R.string.fxp_lofi, "bits" to 10f, "rate" to 3f, "mix" to 0.7f),
                of(t, "cru_8bit", R.string.fxp_8bit, "bits" to 6f, "rate" to 8f, "mix" to 1f),
                of(t, "cru_radio", R.string.fxp_broken_radio, "bits" to 4f, "rate" to 20f, "mix" to 1f),
            )
        },
        Pumper.TYPE.let { t ->
            t.id to listOf(
                of(t, "pmp_soft", R.string.fxp_soft_pump, "rate" to 1f, "depth" to 0.4f, "recover" to 0.7f),
                of(t, "pmp_hard", R.string.fxp_hard_pump, "rate" to 1f, "depth" to 0.9f, "recover" to 0.5f),
                of(t, "pmp_gate", R.string.fxp_gate_eighths, "rate" to 2f, "depth" to 1f, "recover" to 0.3f),
            )
        },
        FmSynth.TYPE.let { t ->
            t.id to listOf(
                of(t, "fm_epiano", R.string.fmp_epiano, "ratio" to 1f, "amount" to 0.5f, "brightDecay" to 0.6f, "brightHold" to 0.15f, "decay" to 1.5f, "sustain" to 0.3f, "release" to 0.4f),
                of(t, "fm_bell", R.string.fmp_bell, "ratio" to 3.5f, "amount" to 0.6f, "brightDecay" to 2.5f, "brightHold" to 0.1f, "attack" to 0.001f, "decay" to 4f, "sustain" to 0f, "release" to 3f, "detune" to 10f),
                of(t, "fm_glass", R.string.fmp_glass, "ratio" to 7f, "amount" to 0.35f, "brightDecay" to 0.3f, "brightHold" to 0.1f, "decay" to 2f, "sustain" to 0.2f, "release" to 1f, "detune" to 12f),
                of(t, "fm_marimba", R.string.fmp_marimba, "ratio" to 4f, "amount" to 0.45f, "brightDecay" to 0.05f, "brightHold" to 0f, "attack" to 0.001f, "decay" to 0.5f, "sustain" to 0f, "release" to 0.4f, "detune" to 3f),
                of(t, "fm_organ", R.string.fmp_organ, "ratio" to 2f, "amount" to 0.3f, "brightDecay" to 5f, "brightHold" to 1f, "feedback" to 0.2f, "attack" to 0.005f, "decay" to 0.1f, "sustain" to 1f, "release" to 0.08f, "detune" to 5f),
                of(t, "fm_brass", R.string.fmp_brass, "ratio" to 1f, "amount" to 0.65f, "brightDecay" to 5f, "brightHold" to 0.9f, "feedback" to 0.5f, "attack" to 0.06f, "decay" to 0.3f, "sustain" to 0.8f, "release" to 0.15f, "detune" to 8f),
                of(t, "fm_bass", R.string.fmp_bass, "ratio" to 1f, "amount" to 0.7f, "brightDecay" to 0.15f, "brightHold" to 0.2f, "feedback" to 0.3f, "decay" to 0.4f, "sustain" to 0.6f, "release" to 0.1f, "octave" to -1f, "detune" to 0f),
            )
        },
        StringSynth.TYPE.let { t ->
            t.id to listOf(
                of(t, "st_guitar", R.string.stp_guitar, "bright" to 0.6f, "sustain" to 3f, "pick" to 0.55f, "release" to 0.25f, "stereo" to 0.3f),
                of(t, "st_nylon", R.string.stp_nylon, "bright" to 0.4f, "sustain" to 2f, "pick" to 0.25f, "release" to 0.2f, "stereo" to 0.3f),
                of(t, "st_harp", R.string.stp_harp, "bright" to 0.75f, "sustain" to 6f, "pick" to 0.35f, "release" to 2.5f, "stereo" to 0.6f),
                of(t, "st_koto", R.string.stp_koto, "bright" to 0.85f, "sustain" to 1.6f, "pick" to 0.9f, "release" to 0.3f, "stereo" to 0.4f),
                of(t, "st_harpsi", R.string.stp_harpsi, "bright" to 1f, "sustain" to 2.5f, "pick" to 1f, "release" to 0.15f, "stereo" to 0.5f),
                of(t, "st_muted", R.string.stp_muted, "bright" to 0.3f, "sustain" to 0.35f, "pick" to 0.6f, "release" to 0.08f, "stereo" to 0.2f),
                of(t, "st_bass", R.string.stp_bass, "bright" to 0.45f, "sustain" to 2f, "pick" to 0.4f, "release" to 0.12f, "octave" to -1f, "stereo" to 0f),
            )
        },
        Flanger.TYPE.let { t ->
            t.id to listOf(
                of(t, "flg_classic", R.string.fxp_classic),
                of(t, "flg_soft", R.string.fxp_soft, "rate" to 0.6f, "depth" to 0.5f, "delay" to 6f, "feedback" to 0.1f, "mix" to 0.4f),
                of(t, "flg_jet", R.string.fxp_jet, "rate" to 0.1f, "depth" to 1f, "delay" to 4f, "feedback" to 0.9f, "mix" to 0.5f),
                of(t, "flg_metal", R.string.fxp_metallic, "rate" to 0.5f, "depth" to 0.2f, "delay" to 1f, "feedback" to 0.92f, "mix" to 0.5f),
            )
        },
        Tremolo.TYPE.let { t ->
            t.id to listOf(
                of(t, "trm_soft", R.string.fxp_soft_tremolo, "sync" to 0f, "rate" to 5f, "depth" to 0.4f, "shape" to 0f, "stereo" to 0f),
                of(t, "trm_chop", R.string.fxp_chop, "sync" to 3f, "depth" to 1f, "shape" to 0.9f, "stereo" to 0f),
                of(t, "trm_pan", R.string.fxp_auto_pan, "sync" to 2f, "depth" to 0.8f, "shape" to 0f, "stereo" to 1f),
                of(t, "trm_heli", R.string.fxp_helicopter, "sync" to 5f, "depth" to 0.9f, "shape" to 0.6f, "stereo" to 0f),
            )
        },
        StereoWidth.TYPE.let { t ->
            t.id to listOf(
                of(t, "stw_wider", R.string.fxp_wider, "width" to 1.5f, "haas" to 0f, "monoBelow" to 120f),
                of(t, "stw_spread", R.string.fxp_spread_mono, "width" to 1.2f, "haas" to 12f, "monoBelow" to 150f),
                of(t, "stw_bass", R.string.fxp_bass_middle, "width" to 1f, "haas" to 0f, "monoBelow" to 200f),
                of(t, "stw_mono", R.string.fxp_mono, "width" to 0f, "haas" to 0f, "monoBelow" to 20f),
            )
        },
        Tape.TYPE.let { t ->
            t.id to listOf(
                of(t, "tap_warm", R.string.fxp_warm_tape, "wow" to 0.15f, "flutter" to 0.1f, "drive" to 0.35f, "tone" to 0.75f),
                of(t, "tap_cassette", R.string.fxp_cassette, "wow" to 0.5f, "flutter" to 0.45f, "drive" to 0.5f, "tone" to 0.35f),
                of(t, "tap_seasick", R.string.fxp_seasick, "wow" to 0.9f, "flutter" to 0.2f, "drive" to 0.2f, "tone" to 0.6f),
                of(t, "tap_hot", R.string.fxp_hot, "wow" to 0.05f, "flutter" to 0.05f, "drive" to 0.9f, "tone" to 0.6f),
            )
        },
        Limiter.TYPE.let { t ->
            t.id to listOf(
                of(t, "lim_safe", R.string.fxp_safety, "gain" to 0f, "ceiling" to -0.3f, "release" to 120f),
                of(t, "lim_loud", R.string.fxp_louder, "gain" to 4f, "ceiling" to -0.5f, "release" to 150f),
                of(t, "lim_max", R.string.fxp_loud, "gain" to 8f, "ceiling" to -0.8f, "release" to 100f),
            )
        },
    ).toMap()

    /** Built-in presets of a kind of device (empty when it has none). */
    fun forType(typeId: String): List<Preset> = if (typeId == Devices.SYNTH.id) synth else others[typeId] ?: emptyList()

    fun find(typeId: String, presetId: String?): Preset? = when {
        presetId == null -> null
        typeId == Devices.SYNTH.id -> synthById[presetId]
        else -> others[typeId]?.firstOrNull { it.id == presetId }
    }
}
