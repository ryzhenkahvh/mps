package jxpix.proger.mps

import jxpix.proger.mps.audio.Mp3Encoder
import jxpix.proger.mps.data.ProjectStore
import jxpix.proger.mps.engine.OfflineRenderer
import jxpix.proger.mps.engine.SampleData
import jxpix.proger.mps.engine.SamplePool
import jxpix.proger.mps.engine.Wav
import jxpix.proger.mps.model.ProjectFactory
import jxpix.proger.mps.model.audibleChannels
import jxpix.proger.mps.model.stemOf
import jxpix.proger.mps.model.updateChannel
import jxpix.proger.mps.model.usedSamples
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/** Everything that leaves the app as a file: MP3, separate tracks, project files. */
class ExportTest {

    private val names: (Int) -> String = { "name" }

    private fun tempDir(name: String): File {
        val dir = System.getenv("MPS_RENDER_DIR")?.let { File(it) } ?: File(System.getProperty("java.io.tmpdir"), "mps-test")
        return File(dir, name).also {
            it.deleteRecursively()
            it.mkdirs()
        }
    }

    private class Mp3Info(val frames: Int, val bitRates: Set<Int>, val sampleRates: Set<Int>, val tagBytes: Int, val junkBytes: Int)

    /** Walks through an MP3 file frame by frame, the way a player does. */
    private fun parseMp3(bytes: ByteArray): Mp3Info {
        val kbps = intArrayOf(0, 32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320, 0)
        val rates = intArrayOf(44100, 48000, 32000, 0)
        var pos = 0
        var tag = 0
        if (bytes.size > 10 && bytes[0] == 'I'.code.toByte() && bytes[1] == 'D'.code.toByte() && bytes[2] == '3'.code.toByte()) {
            val size = ((bytes[6].toInt() and 0x7f) shl 21) or ((bytes[7].toInt() and 0x7f) shl 14) or
                ((bytes[8].toInt() and 0x7f) shl 7) or (bytes[9].toInt() and 0x7f)
            tag = size + 10
            pos = tag
        }
        var frames = 0
        var junk = 0
        val bitRates = HashSet<Int>()
        val sampleRates = HashSet<Int>()
        while (pos + 4 <= bytes.size) {
            val b1 = bytes[pos].toInt() and 0xff
            val b2 = bytes[pos + 1].toInt() and 0xff
            val b3 = bytes[pos + 2].toInt() and 0xff
            // sync word, MPEG-1, layer III
            if (b1 != 0xff || (b2 and 0xfe) != 0xfa) {
                junk++
                pos++
                continue
            }
            val rate = rates[(b3 shr 2) and 3]
            val bitRate = kbps[b3 shr 4]
            if (rate == 0 || bitRate == 0) {
                junk++
                pos++
                continue
            }
            val padding = (b3 shr 1) and 1
            pos += 144000 * bitRate / rate + padding
            frames++
            bitRates += bitRate
            sampleRates += rate
        }
        return Mp3Info(frames, bitRates, sampleRates, tag, junk)
    }

    @Test
    fun mp3ExportWritesAFileEveryPlayerCanRead() {
        val dir = tempDir("mp3")
        val project = ProjectFactory.template(ProjectFactory.DEMO_ID)!!.build(names)
        for ((rate, kbps) in listOf(44100 to 128, 48000 to 192, 48000 to 320)) {
            val wav = File(dir, "demo_$rate.wav")
            val rendered = OfflineRenderer.render(project, SamplePool(), wav, rate, patternId = project.patterns.first().id, loops = 2)!!
            val mp3 = File(dir, "demo_${rate}_$kbps.mp3")
            val started = System.nanoTime()
            var last = 0f
            assertTrue(Mp3Encoder.encode(wav, mp3, rate, kbps = kbps, title = "Демо / Demo") { last = it; true })
            val ms = (System.nanoTime() - started) / 1e6
            assertEquals(1f, last, 0.001f)

            val info = parseMp3(mp3.readBytes())
            assertTrue("the title is stored in a tag", info.tagBytes > 20)
            assertEquals("nothing but frames after the tag", 0, info.junkBytes)
            assertEquals(setOf(kbps), info.bitRates)
            assertEquals(setOf(rate), info.sampleRates)
            // 1152 samples per frame; the encoder adds at most a few frames of padding
            val seconds = info.frames * 1152.0 / rate
            assertTrue("mp3 $seconds s for ${rendered.seconds} s of sound", seconds >= rendered.seconds && seconds < rendered.seconds + 0.12)
            // constant bit rate: the size is known in advance, which is what the export sheet shows
            assertEquals(seconds * kbps * 125.0, (mp3.length() - info.tagBytes).toDouble(), 2000.0)
            println(
                "     info mp3 $rate Hz $kbps kbps: ${"%.1f".format(rendered.seconds)} s -> ${mp3.length() / 1024} KB in ${ms.toInt()} ms " +
                    "(${"%.0f".format(rendered.seconds * 1000 / ms)}x real time)"
            )
        }
        assertFalse(Mp3Encoder.supports(96000))
        assertTrue(Mp3Encoder.supports(44100) && Mp3Encoder.supports(48000))
    }

    @Test
    fun mp3ExportCanBeCancelled() {
        val dir = tempDir("mp3cancel")
        val wav = File(dir, "tone.wav")
        val rate = 44100
        Wav.write(wav, SampleData(rate, ShortArray(rate * 4) { (sin(2 * Math.PI * 330 * it / rate) * 12000).toInt().toShort() }, ShortArray(rate * 4)))
        val mp3 = File(dir, "tone.mp3")
        var calls = 0
        assertFalse(Mp3Encoder.encode(wav, mp3, rate) { ++calls < 3 })
        assertEquals(3, calls)
        assertFalse("a cancelled export leaves no file behind", mp3.exists())
    }

    private fun readStereo(file: File): Pair<ShortArray, ShortArray> {
        val data = Wav.read(file)
        return data.left to (data.right ?: data.left)
    }

    @Test
    fun stemsHaveOneLengthAndAddUpToTheMix() {
        val dir = tempDir("stems")
        val rate = 44100
        val demo = ProjectFactory.template(ProjectFactory.DEMO_ID)!!.build(names)
        // a muted channel must not get a file; the rest are rendered one by one
        val muted = demo.channels.last().id
        val project = demo.updateChannel(muted) { it.copy(mute = true) }
        val audible = project.audibleChannels()
        assertEquals(demo.channels.size - 1, audible.size)
        assertTrue(audible.none { it.id == muted })

        // what the stems are compared with: the same song with nothing on the master strip
        val mixFile = File(dir, "mix.wav")
        val mix = OfflineRenderer.render(project.copy(masterEffects = emptyList()), SamplePool(), mixFile, rate, tailSeconds = 2.0, fixedTail = true)!!
        val (mixL, mixR) = readStereo(mixFile)
        val sumL = DoubleArray(mixL.size)
        val sumR = DoubleArray(mixR.size)
        var written = 0
        for (channel in audible) {
            val stem = project.stemOf(channel.id)
            assertTrue(stem.masterEffects.isEmpty())
            assertEquals(listOf(channel.id), stem.audibleChannels().map { it.id })
            val file = File(dir, "stem_${channel.id}.wav")
            val result = OfflineRenderer.render(stem, SamplePool(), file, rate, tailSeconds = 2.0, fixedTail = true)!!
            assertEquals("every stem is exactly as long as the mix", mix.frames, result.frames)
            if (result.peak < 0.0005f) continue
            written++
            val (l, r) = readStereo(file)
            assertEquals(mixL.size, l.size)
            for (i in l.indices) {
                sumL[i] += l[i].toDouble()
                sumR[i] += r[i].toDouble()
            }
        }
        assertTrue("most channels of the demo play something", written >= audible.size - 1 && written >= 3)

        // The stems laid on top of each other are the mix. Only the master limiter, which works
        // on each file by itself, may make a difference on the loudest hits.
        var diff = 0.0
        var energy = 0.0
        var dot = 0.0
        var sumEnergy = 0.0
        for (i in mixL.indices) {
            val dl = sumL[i] - mixL[i]
            val dr = sumR[i] - mixR[i]
            diff += dl * dl + dr * dr
            energy += mixL[i].toDouble() * mixL[i] + mixR[i].toDouble() * mixR[i]
            sumEnergy += sumL[i] * sumL[i] + sumR[i] * sumR[i]
            dot += sumL[i] * mixL[i] + sumR[i] * mixR[i]
        }
        // the intro of the demo is quiet enough to leave the limiter alone: there the match is exact
        var quietDiff = 0.0
        var quietEnergy = 0.0
        for (i in 0 until rate * 10) {
            val dl = sumL[i] - mixL[i]
            quietDiff += dl * dl
            quietEnergy += mixL[i].toDouble() * mixL[i]
        }
        assertTrue("quiet part: ${sqrt(quietDiff / quietEnergy)}", quietEnergy > 0.0 && sqrt(quietDiff / quietEnergy) < 0.005)

        val correlation = dot / sqrt(energy * sumEnergy)
        val error = sqrt(diff / max(1.0, energy))
        println("     info stems: $written files, ${"%.1f".format(mix.seconds)} s each, correlation ${"%.4f".format(correlation)}, difference ${"%.1f".format(error * 100)} %")
        assertTrue("stems vs mix: correlation $correlation", correlation > 0.97)
        assertTrue("stems vs mix: difference $error", error < 0.25)
    }

    @Test
    fun projectFileCarriesTheProjectAndItsSounds() {
        val root = tempDir("projectfile")
        val store = ProjectStore(File(root, "phone1"))
        val rate = 48000
        val tone = SampleData(rate, ShortArray(rate / 2) { (sin(2 * Math.PI * 440 * it / rate) * 20000).toInt().toShort() })

        val template = ProjectFactory.template(ProjectFactory.DEMO_ID)!!.build(names)
        val id = store.create(template)
        val used = store.saveSample(id, "My kick", tone)
        val unused = store.saveSample(id, "Old take", tone)
        val first = template.channels.first()
        val project = template.copy(name = "Трек №1").updateChannel(first.id) { it.copy(instrument = it.instrument.copy(sample = used)) }
        store.save(id, project)
        assertEquals(setOf(used), project.usedSamples())

        val file = ByteArrayOutputStream()
        store.exportTo(id, file)
        val bytes = file.toByteArray()
        assertTrue(bytes.size > 1000)

        // on "another phone": an empty store
        val other = ProjectStore(File(root, "phone2"))
        val newId = other.importFrom(ByteArrayInputStream(bytes))
        val loaded = other.load(newId)
        assertNotNull(loaded)
        assertEquals(project, loaded)
        assertEquals(listOf("Трек №1"), other.list().map { it.name })
        assertArrayEquals(File(store.samplesDir(id), used).readBytes(), File(other.samplesDir(newId), used).readBytes())
        assertFalse("sounds the project does not use stay at home", File(other.samplesDir(newId), unused).exists())
        assertNotNull(other.loadSample(newId, used))

        // the same file again: a second project, renamed by the caller
        val again = other.importFrom(ByteArrayInputStream(bytes)) { "$it 2" }
        assertEquals("Трек №1 2", other.load(again)!!.name)
        assertEquals(2, other.list().size)

        // not a project at all
        for (junk in listOf(ByteArray(0), "hello".toByteArray(), bytes.copyOf(bytes.size / 2).also { it[40] = 0 })) {
            try {
                other.importFrom(ByteArrayInputStream(junk))
                assertTrue("junk must not import", false)
            } catch (_: IOException) {
            }
        }
        assertEquals("a failed import leaves nothing behind", 2, File(root, "phone2").listFiles()!!.size)
        assertNull(other.load("nope"))

        // an archive that tries to write outside of its folder
        val evil = ByteArrayOutputStream()
        ZipOutputStream(evil).use { zip ->
            zip.putNextEntry(ZipEntry("project.json"))
            zip.write(File(File(root, "phone1/$id"), "project.json").readBytes())
            zip.closeEntry()
            for (name in listOf("samples/../../escaped.wav", "samples/sub/inner.wav", "../outside.txt", "samples/$used")) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(byteArrayOf(1, 2, 3))
                zip.closeEntry()
            }
        }
        val third = other.importFrom(ByteArrayInputStream(evil.toByteArray()))
        assertEquals(listOf(used), other.samplesDir(third).list()!!.toList())
        val all = root.walkTopDown().filter { it.isFile }.map { it.name }.toList()
        assertTrue(all.none { it == "escaped.wav" || it == "inner.wav" || it == "outside.txt" })
        assertFalse(File(root.parentFile, "outside.txt").exists())
        assertTrue(abs(other.list().size - 3) == 0)
    }
}
