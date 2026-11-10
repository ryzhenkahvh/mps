package jxpix.proger.mps

import jxpix.proger.mps.data.PresetStore
import jxpix.proger.mps.data.ProjectStore
import jxpix.proger.mps.data.SoundLibrary
import jxpix.proger.mps.data.UserPreset
import jxpix.proger.mps.engine.Devices
import jxpix.proger.mps.engine.Engine
import jxpix.proger.mps.engine.SampleData
import jxpix.proger.mps.engine.SamplePool
import jxpix.proger.mps.model.Presets
import jxpix.proger.mps.model.ProjectFactory
import jxpix.proger.mps.model.USER_PRESET_PREFIX
import jxpix.proger.mps.model.addEffect
import jxpix.proger.mps.model.applyParams
import jxpix.proger.mps.model.applyPreset
import jxpix.proger.mps.model.findDevice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.abs
import kotlin.math.sin

/** The things that belong to the user rather than to one project: sounds and presets. */
class LibraryTest {

    private val names: (Int) -> String = { "name" }

    private fun tempDir(name: String): File {
        val dir = System.getenv("MPS_RENDER_DIR")?.let { File(it) } ?: File(System.getProperty("java.io.tmpdir"), "mps-test")
        return File(dir, name).also {
            it.deleteRecursively()
            it.mkdirs()
        }
    }

    private fun tone(rate: Int, seconds: Double, hz: Double, stereo: Boolean = false): SampleData {
        val n = (rate * seconds).toInt()
        val left = ShortArray(n) { (sin(2 * Math.PI * hz * it / rate) * 20000).toInt().toShort() }
        return SampleData(rate, left, if (stereo) left.copyOf() else null)
    }

    @Test
    fun soundLibraryKeepsTheUsersSounds() {
        val root = tempDir("library")
        val library = SoundLibrary(File(root, "sounds"))
        assertTrue(library.list().isEmpty())

        val kick = library.add("Kick", tone(44100, 0.5, 60.0))
        val second = library.add("Kick", tone(44100, 0.25, 80.0))
        val loop = library.add("loop: 120/bpm?", tone(48000, 2.0, 220.0, stereo = true))
        assertEquals("Kick.wav", kick)
        assertEquals("a second sound of the same name gets a number", "Kick 2.wav", second)
        assertFalse("no characters a file system may refuse", loop.contains(':') || loop.contains('/') || loop.contains('?'))

        val list = library.list()
        assertEquals(listOf("Kick", "Kick 2", loop.removeSuffix(".wav")), list.map { it.title })
        assertEquals(0.5f, list[0].seconds, 0.001f)
        assertEquals(44100, list[0].sampleRate)
        assertFalse(list[0].stereo)
        assertTrue(list[2].stereo)
        assertEquals(2f, list[2].seconds, 0.001f)

        // the audio comes back as it went in
        val back = library.load(kick)!!
        assertEquals(22050, back.frames)
        assertEquals(tone(44100, 0.5, 60.0).left[1000], back.left[1000])

        assertNull("a name that is taken is refused", library.rename(second, "Kick"))
        assertEquals("Snare.wav", library.rename(second, "Snare"))
        assertEquals(listOf("Kick", loop.removeSuffix(".wav"), "Snare"), library.list().map { it.title })
        assertNull(library.rename("gone.wav", "x"))
        // file names from outside cannot reach other folders
        File(root, "secret.wav").writeText("x")
        assertNull(library.load("../secret.wav"))
        assertFalse(library.delete("../secret.wav"))
        assertTrue(File(root, "secret.wav").exists())

        assertTrue(library.delete(kick))
        assertEquals(2, library.list().size)

        // using a sound in a project copies it there once, however often it is used
        val store = ProjectStore(File(root, "projects"))
        val id = store.create(ProjectFactory.template(ProjectFactory.DEMO_ID)!!.build(names))
        val inProject = store.adoptSample(id, library.fileOf("Snare.wav"))
        assertEquals("Snare.wav", inProject)
        assertEquals("the same sound again is not stored twice", "Snare.wav", store.adoptSample(id, library.fileOf("Snare.wav")))
        // a different sound of the same name gets a place of its own
        library.delete("Snare.wav")
        library.add("Snare", tone(44100, 0.3, 300.0))
        assertEquals("Snare 2.wav", store.adoptSample(id, library.fileOf("Snare.wav")))
        assertEquals(2, store.samplesDir(id).list()!!.size)
        assertNotNull(store.loadSample(id, "Snare 2.wav"))

        // and back: a sound of a project goes into the library, once
        assertEquals("Snare.wav", library.adopt(File(store.samplesDir(id), "Snare 2.wav")))
        assertEquals("Snare 2.wav", library.adopt(File(store.samplesDir(id), "Snare.wav")))
        assertEquals("Snare 2.wav", library.adopt(File(store.samplesDir(id), "Snare.wav")))
        assertEquals(3, library.list().size)
    }

    @Test
    fun presetStoreKeepsTheUsersPresets() {
        val dir = File(tempDir("presets"), "presets")
        val store = PresetStore(dir)
        assertTrue(store.loadAll().isEmpty())
        assertTrue(store.list("synth").isEmpty())

        store.save("synth", UserPreset("Warm pad", mapOf("cutoff" to 800f)))
        store.save("synth", UserPreset("  Acid line  ", mapOf("cutoff" to 300f, "resonance" to 0.8f)))
        store.save("reverb", UserPreset("Cave", mapOf("size" to 0.9f)))
        assertEquals(listOf("Acid line", "Warm pad"), store.list("synth").map { it.name })

        // saving under the same name (whatever the case) replaces
        val list = store.save("synth", UserPreset("warm PAD", mapOf("cutoff" to 1200f)))
        assertEquals(listOf("Acid line", "warm PAD"), list.map { it.name })
        assertEquals(1200f, list[1].params["cutoff"])
        assertEquals("an empty name is not a preset", 2, store.save("synth", UserPreset("   ", emptyMap())).size)

        // another start of the app
        val again = PresetStore(dir)
        assertEquals(setOf("synth", "reverb"), again.loadAll().keys)
        assertEquals(0.8f, again.list("synth")[0].params["resonance"])

        assertEquals(listOf("warm PAD"), again.delete("synth", "Acid line").map { it.name })
        assertTrue(again.delete("reverb", "Cave").isEmpty())
        assertEquals(setOf("synth"), PresetStore(dir).loadAll().keys)

        // a damaged file costs the presets of that device, not the app
        File(dir, "synth.json").writeText("{ not json")
        assertTrue(PresetStore(dir).list("synth").isEmpty())
        assertTrue(PresetStore(dir).loadAll().isEmpty())
    }

    @Test
    fun presetsSetTheKnobsOfADevice() {
        var project = ProjectFactory.template(ProjectFactory.DEMO_ID)!!.build(names)
        val synth = project.channels.first { it.instrument.type == Devices.SYNTH.id }.instrument

        // a preset written by another version: an unknown knob, a value out of range, knobs missing
        project = project.applyParams(synth.uid, mapOf("cutoff" to 1e9f, "resonance" to 0.42f, "noSuchKnob" to 1f, "glide" to Float.NaN), USER_PRESET_PREFIX + "Mine")
        val after = project.findDevice(synth.uid)!!
        assertEquals(USER_PRESET_PREFIX + "Mine", after.preset)
        assertEquals(Devices.SYNTH.params.map { it.id }.toSet(), after.params.keys)
        assertEquals(Devices.SYNTH.spec("cutoff")!!.max, after.params["cutoff"])
        assertEquals(0.42f, after.params["resonance"])
        assertEquals(Devices.SYNTH.spec("glide")!!.default, after.params["glide"])

        // every built-in preset of every effect is complete, in range and can be found again
        var count = 0
        for (type in Devices.effects) {
            val list = Presets.forType(type.id)
            assertTrue("${type.id} has presets", list.size >= 3)
            assertEquals("${type.id}: preset ids are unique", list.size, list.map { it.id }.toSet().size)
            val (withFx, uid) = project.addEffect(project.channels.first().id, type.id)
            for (preset in list) {
                assertEquals(type.params.map { it.id }.toSet(), preset.params.keys)
                for (spec in type.params) {
                    val v = preset.params.getValue(spec.id)
                    assertTrue("${preset.id}.${spec.id} = $v", v >= spec.min && v <= spec.max)
                }
                assertTrue(Presets.find(type.id, preset.id) === preset)
                val applied = withFx.applyPreset(uid, preset).findDevice(uid)!!
                assertEquals(preset.id, applied.preset)
                assertEquals(preset.params, applied.params)
                count++
            }
        }
        assertTrue("$count effect presets", count >= 40)
        assertNull(Presets.find(Devices.SYNTH.id, "no_such"))
        assertNull(Presets.find("no_such_device", "x"))
    }

    @Test
    fun auditionPlaysASoundOutsideOfTheProject() {
        val rate = 48000
        val engine = Engine(rate)
        engine.setProject(ProjectFactory.template(ProjectFactory.DEMO_ID)!!.build(names), SamplePool())
        val out = FloatArray(512 * 2)
        fun block(): Float {
            engine.render(out, 512)
            return out.maxOf { abs(it) }
        }
        assertEquals(0f, block(), 0f)
        assertFalse(engine.auditioning)

        // half a second at another sample rate, in stereo
        engine.audition(tone(22050, 0.5, 440.0, stereo = true))
        assertTrue("known at once, before the audio thread has taken it", engine.auditioning)
        var heard = 0
        var loud = 0f
        var blocks = 0
        while (engine.auditioning && blocks < 200) {
            val p = block()
            if (p > 0.01f) heard++
            if (p > loud) loud = p
            blocks++
        }
        val seconds = blocks * 512.0 / rate
        assertTrue("plays for its own length: $seconds s", seconds > 0.45 && seconds < 0.6)
        assertTrue("at a healthy level: $loud", loud > 0.3f && loud < 0.7f)
        assertTrue(heard >= blocks - 2)
        // the master limiter looks 1.5 ms ahead, so the very end comes out one block later
        block()
        assertEquals("and then it is quiet again", 0f, block(), 0f)

        // stopping fades out within one block instead of cutting the wave
        engine.audition(tone(48000, 2.0, 220.0))
        repeat(10) { block() }
        engine.audition(null)
        val fade = FloatArray(3 * 512)
        for (b in 0 until 3) {
            engine.render(out, 512)
            for (i in 0 until 512) fade[b * 512 + i] = out[i * 2]
        }
        assertTrue("the sound is still there when the fade begins", fade.take(64).maxOf { abs(it) } > 0.1f)
        var jump = 0f
        for (i in 1 until fade.size) jump = maxOf(jump, abs(fade[i] - fade[i - 1]))
        // a 220 Hz wave at this level moves 0.014 per sample; a cut would be a step of up to 0.5
        assertTrue("no click: the largest step is $jump", jump < 0.03f)
        assertEquals("silent after the fade", 0f, fade.drop(700).maxOf { abs(it) }, 0f)
        assertEquals(0f, block(), 0f)
        assertFalse(engine.auditioning)

        // a new sound while another plays: the old one fades, the new one starts
        engine.audition(tone(48000, 1.0, 220.0))
        repeat(4) { block() }
        engine.audition(tone(48000, 0.2, 880.0))
        var n = 0
        while (engine.auditioning && n < 100) {
            block()
            n++
        }
        assertTrue("the second sound is the short one: $n blocks", n in 18..24)
    }
}
