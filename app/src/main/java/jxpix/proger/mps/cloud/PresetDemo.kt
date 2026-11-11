package jxpix.proger.mps.cloud

import jxpix.proger.mps.engine.Devices
import jxpix.proger.mps.engine.OfflineRenderer
import jxpix.proger.mps.engine.PPQ
import jxpix.proger.mps.engine.SampleData
import jxpix.proger.mps.engine.SamplePool
import jxpix.proger.mps.engine.Wav
import jxpix.proger.mps.model.Note
import jxpix.proger.mps.model.Project
import jxpix.proger.mps.model.addChannel
import jxpix.proger.mps.model.addEffect
import jxpix.proger.mps.model.addPattern
import jxpix.proger.mps.model.applyParams
import jxpix.proger.mps.model.setNotes
import java.io.File

/**
 * Lets a preset be heard before it is saved. A preset is only a set of knob positions, so nothing
 * is downloaded: the app plays a short phrase with these settings on its own instrument.
 */
object PresetDemo {

    /** True when a preset of this kind of device can be played as it is (a sampler needs its sound). */
    fun canPlay(device: String?): Boolean {
        val type = Devices[device ?: return false] ?: return false
        return type.id != Devices.SAMPLER.id
    }

    /** A two-bar project that shows off the preset; the second value is the pattern to play. */
    fun project(device: String, params: Map<String, Float>): Pair<Project, Int>? {
        val type = Devices[device] ?: return null
        if (!canPlay(device)) return null
        var p = Project(name = "Preset", bpm = 110f)
        val channel: Int
        if (type.isInstrument) {
            val added = p.addChannel(device, "Preset")
            val uid = added.first.channel(added.second)?.instrument?.uid ?: return null
            p = added.first.applyParams(uid, params, null)
            channel = added.second
        } else {
            // an effect is heard on a plain synthesizer
            val added = p.addChannel(Devices.SYNTH.id, "Preset")
            val effect = added.first.addEffect(added.second, device)
            p = effect.first.applyParams(effect.second, params, null)
            channel = added.second
        }
        val pattern = p.addPattern("Preset", bars = 2)
        val notes = if (device == Devices.DRUM.id) {
            listOf(0f, 1f, 2f, 2.5f, 3f, 4f, 5f, 6f, 6.5f, 7f).map { Note((it * PPQ).toInt(), PPQ / 4, 60, 0.85f) }
        } else {
            listOf(
                // a rising figure, then a chord left to ring
                Note(0, PPQ, 60, 0.85f), Note(PPQ, PPQ, 63, 0.75f), Note(PPQ * 2, PPQ, 67, 0.8f), Note(PPQ * 3, PPQ, 72, 0.9f),
                Note(PPQ * 4, PPQ * 7 / 2, 48, 0.8f), Note(PPQ * 4, PPQ * 7 / 2, 55, 0.7f), Note(PPQ * 4, PPQ * 7 / 2, 63, 0.7f),
            )
        }
        return pattern.first.setNotes(pattern.second, channel, notes) to pattern.second
    }

    /** Renders the phrase; [work] is a scratch file. Null when the device is unknown to this version of the app. */
    fun render(device: String, params: Map<String, Float>, work: File, sampleRate: Int = 44100): SampleData? {
        val (project, pattern) = project(device, params) ?: return null
        try {
            work.parentFile?.mkdirs()
            OfflineRenderer.render(project, SamplePool(), work, sampleRate, patternId = pattern, loops = 1, tailSeconds = 2.0) ?: return null
            return Wav.read(work)
        } finally {
            work.delete()
        }
    }
}
