package jxpix.proger.mps.model

import androidx.annotation.StringRes
import jxpix.proger.mps.R
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

    fun find(typeId: String, presetId: String?): Preset? =
        if (typeId == Devices.SYNTH.id && presetId != null) synthById[presetId] else null

    fun forType(typeId: String): List<Preset> = if (typeId == Devices.SYNTH.id) synth else emptyList()
}
