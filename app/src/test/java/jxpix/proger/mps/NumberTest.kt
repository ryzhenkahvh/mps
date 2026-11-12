package jxpix.proger.mps

import jxpix.proger.mps.engine.DeviceType
import jxpix.proger.mps.engine.Devices
import jxpix.proger.mps.engine.ParamSpec
import jxpix.proger.mps.ui.ParamNumber
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.pow

/** The settings of the devices as numbers in a field: what is written, what an arrow does, what a typed text means. */
class NumberTest {
    private fun spec(type: String, id: String): ParamSpec = Devices[type]!!.spec(id)!!

    private fun all(): List<Pair<DeviceType, ParamSpec>> = (Devices.playable + Devices.effects).flatMap { type -> type.params.filter { !it.isChoice }.map { type to it } }

    @Test
    fun everySettingCanBeWalkedFromEndToEndAndReadBack() {
        val table = StringBuilder()
        for ((type, s) in all()) {
            val name = "${type.id}.${s.id}"
            val f = ParamNumber.form(s)
            val steps = (s.max - s.min) * f.scale / f.step
            table.append("$name: ${ParamNumber.text(s, s.min)} … ${ParamNumber.text(s, s.max)}, step ${f.step}, ${steps.toInt()} steps, default ${ParamNumber.text(s, s.default)}\n")
            assertTrue("$name: $steps steps is no range for arrows", steps >= 3.9f && steps <= 20000f)
            // what is written can be typed back and gives the value again, to the last digit that is shown:
            // a value between two steps is written with one digit more than the step has, two at the most
            val shown = minOf(2, f.decimals + 1)
            for (norm in floatArrayOf(0f, 0.13f, 0.5f, 0.77f, 1f)) {
                val v = s.fromNorm(norm)
                val written = ParamNumber.text(s, v, plain = true)
                val back = ParamNumber.parse(s, written)
                assertNotNull("$name: \"$written\" is not read", back)
                assertEquals("$name: $v is written as \"$written\"", v * f.scale, back!! * f.scale, 0.505f * 10f.pow(-shown) + abs(v * f.scale) * 1e-5f)
                // the same with the sign and the minus of the screen
                assertEquals(back, ParamNumber.parse(s, ParamNumber.text(s, v))!!, 0f)
            }
            // the arrows reach every step: up from the bottom to the top and down again, never standing still
            var v = s.min
            var n = 0
            while (v < s.max) {
                val next = ParamNumber.stepped(s, v, 1)
                assertTrue("$name: stuck at $v on the way up", next > v)
                v = next
                assertTrue("$name: too many steps", ++n < 21000)
            }
            assertEquals(name, s.max, v, 0f)
            assertEquals("$name: at the top an arrow changes nothing", s.max, ParamNumber.stepped(s, s.max, 1), 0f)
            while (v > s.min) {
                val next = ParamNumber.stepped(s, v, -1)
                assertTrue("$name: stuck at $v on the way down", next < v)
                v = next
            }
            assertEquals(name, s.min, v, 0f)
            // held down, an arrow never slows, and crosses the whole range in a few seconds
            var covered = 0f
            var last = 0
            var held = ParamNumber.HOLD_MS
            while (held < 9000L) {
                val speed = ParamNumber.speed(s, held)
                assertTrue("$name: slower after $held ms", speed >= last)
                last = speed
                covered += speed
                held += ParamNumber.REPEAT_MS
            }
            assertTrue("$name: ${covered.toInt()} of ${steps.toInt()} steps in nine seconds", covered >= steps)
            assertEquals("$name: the first second is exact", 1, ParamNumber.speed(s, 1000L))
        }
        println("    info numbers: ${all().size} settings")
        System.getenv("MPS_RENDER_DIR")?.let { java.io.File(it, "numbers.txt").writeText(table.toString()) }
    }

    @Test
    fun anArrowChangesTheNumberByOne() {
        // one hertz, one percent, one decibel, one millisecond, one semitone, one degree
        val cutoff = spec("synth", "cutoff")
        assertEquals("6000", ParamNumber.text(cutoff, 6000f))
        assertEquals(6001f, ParamNumber.stepped(cutoff, 6000f, 1), 0f)
        assertEquals(5999f, ParamNumber.stepped(cutoff, 6000f, -1), 0f)
        val level = spec("synth", "osc1Level")
        assertEquals("80", ParamNumber.text(level, 0.8f))
        assertEquals(0.81f, ParamNumber.stepped(level, 0.8f, 1), 1e-6f)
        assertEquals("a share that a float cannot hold exactly", 0.3f, ParamNumber.stepped(level, 0.29f, 1), 1e-6f)
        assertEquals(0.28f, ParamNumber.stepped(level, 0.29f, -1), 1e-6f)
        val gain = spec("eq", "lowGain")
        assertEquals("+6", ParamNumber.text(gain, 6f))
        assertEquals(7f, ParamNumber.stepped(gain, 6f, 1), 0f)
        assertEquals(-18f, ParamNumber.stepped(gain, -18f, -1), 0f)
        val attack = spec("synth", "ampAttack")
        assertEquals("times that begin at a millisecond are counted in milliseconds", "5", ParamNumber.text(attack, 0.005f))
        assertEquals(0.006f, ParamNumber.stepped(attack, 0.005f, 1), 1e-7f)
        assertEquals("5000", ParamNumber.text(attack, 5f))
        assertEquals(1f, ParamNumber.stepped(spec("synth", "osc2Semi"), 0f, 1), 0f)
        assertEquals(-89f, ParamNumber.stepped(spec("space3d", "angle"), -90f, 1), 0f)
        assertEquals(61f, ParamNumber.stepped(spec("sampler", "root"), 60f, 1), 0f)

        // where one would be a leap, an arrow moves the last digit that is shown
        val rate = spec("synth", "lfoRate")
        assertEquals("5.00", ParamNumber.text(rate, 5f))
        assertEquals(5.01f, ParamNumber.stepped(rate, 5f, 1), 1e-5f)
        assertEquals(0.05f, ParamNumber.stepped(rate, 0.06f, -1), 1e-6f)
        val width = spec("eq", "midQ")
        assertEquals("0.9", ParamNumber.text(width, 0.9f))
        assertEquals(1.0f, ParamNumber.stepped(width, 0.9f, 1), 1e-6f)
        val ceiling = spec("limiter", "ceiling")
        assertEquals("−0.3", ParamNumber.text(ceiling, -0.3f))
        assertEquals(-0.2f, ParamNumber.stepped(ceiling, -0.3f, 1), 1e-6f)
        val ring = spec("strings", "sustain")
        assertEquals("a time that begins at a tenth of a second stays in seconds", "2.5", ParamNumber.text(ring, 2.5f))
        assertEquals(2.6f, ParamNumber.stepped(ring, 2.5f, 1), 1e-6f)
        val quick = spec("comp", "attack")
        assertEquals("10.0", ParamNumber.text(quick, 10f))
        assertEquals(10.1f, ParamNumber.stepped(quick, 10f, 1), 1e-5f)

        // a value between two steps shows one digit more, and an arrow takes it to the nearer step on its side
        assertEquals("+2.5", ParamNumber.text(gain, 2.5f))
        assertEquals(3f, ParamNumber.stepped(gain, 2.5f, 1), 0f)
        assertEquals(2f, ParamNumber.stepped(gain, 2.5f, -1), 0f)
        assertEquals("440.5", ParamNumber.text(cutoff, 440.5f))
        assertEquals(441f, ParamNumber.stepped(cutoff, 440.5f, 1), 0f)
        assertEquals(440f, ParamNumber.stepped(cutoff, 440.5f, -1), 0f)
        assertEquals("what a knob of an earlier version left behind", "+3.5", ParamNumber.text(gain, 3.4721f))

        // held down: bigger steps, and the number lands on round values
        assertEquals(500f, ParamNumber.stepped(cutoff, 437f, 1, 100), 0f)
        assertEquals(600f, ParamNumber.stepped(cutoff, 500f, 1, 100), 0f)
        assertEquals(400f, ParamNumber.stepped(cutoff, 437f, -1, 100), 0f)
        assertEquals(20000f, ParamNumber.stepped(cutoff, 19950f, 1, 100), 0f)
        assertEquals(30f, ParamNumber.stepped(cutoff, 60f, -1, 100), 0f)
        assertEquals(1, ParamNumber.speed(cutoff, 0L))
        assertEquals(200, ParamNumber.speed(cutoff, 1500L))
        assertEquals(500, ParamNumber.speed(cutoff, 3000L))
        assertEquals("a hundred steps need no hurry", 1, ParamNumber.speed(level, 1500L))
        assertEquals(2, ParamNumber.speed(level, 3000L))
    }

    @Test
    fun aNumberIsWrittenTheWayItIsRead() {
        // the sign of a value that goes both ways; none where it cannot be negative
        val env = spec("synth", "envAmount")
        assertEquals("−35", ParamNumber.text(env, -0.35f))
        assertEquals("-35", ParamNumber.text(env, -0.35f, plain = true))
        assertEquals("+50", ParamNumber.text(env, 0.5f))
        assertEquals("50", ParamNumber.text(env, 0.5f, plain = true))
        assertEquals("0", ParamNumber.text(env, 0f))
        assertEquals("0", ParamNumber.text(env, -0.0001f))
        assertEquals("360", ParamNumber.text(spec("space3d", "arc"), 360f))
        assertEquals("−90", ParamNumber.text(spec("space3d", "angle"), -90f))
        assertEquals("4", ParamNumber.text(spec("comp", "ratio"), 4f))
        assertEquals("1.5", ParamNumber.text(spec("comp", "ratio"), 1.5f))
        assertEquals("−12", ParamNumber.text(spec("pitch", "semitones"), -12f))
        assertEquals("C4", ParamNumber.text(spec("sampler", "root"), 60f))

        // typed: a comma or a point, a sign, a unit after it, spaces around it
        val cutoff = spec("synth", "cutoff")
        assertEquals(1250f, ParamNumber.parse(cutoff, "1250")!!, 0f)
        assertEquals(1250f, ParamNumber.parse(cutoff, " 1250 Hz ")!!, 0f)
        assertEquals(440.5f, ParamNumber.parse(cutoff, "440,5")!!, 0f)
        assertEquals(0.75f, ParamNumber.parse(spec("synth", "lfoRate"), "0,75")!!, 0f)
        assertEquals(0.75f, ParamNumber.parse(spec("synth", "lfoRate"), ".75")!!, 0f)
        assertEquals(-0.35f, ParamNumber.parse(env, "-35")!!, 1e-6f)
        assertEquals(-0.35f, ParamNumber.parse(env, "−35 %")!!, 1e-6f)
        assertEquals(0.5f, ParamNumber.parse(env, "+50")!!, 1e-6f)
        assertEquals("milliseconds are typed as milliseconds", 0.25f, ParamNumber.parse(spec("synth", "ampAttack"), "250")!!, 1e-6f)
        // outside of the range: the nearest end
        assertEquals(20000f, ParamNumber.parse(cutoff, "99999")!!, 0f)
        assertEquals(30f, ParamNumber.parse(cutoff, "-5")!!, 0f)
        assertEquals(1f, ParamNumber.parse(env, "250")!!, 0f)
        // a setting that takes whole numbers takes the nearest one
        assertEquals(8f, ParamNumber.parse(spec("pitch", "semitones"), "7.6")!!, 0f)
        // a note by its name or by its number
        val root = spec("sampler", "root")
        assertEquals(49f, ParamNumber.parse(root, "c#3")!!, 0f)
        assertEquals(49f, ParamNumber.parse(root, "Db3")!!, 0f)
        assertEquals(69f, ParamNumber.parse(root, "A4")!!, 0f)
        assertEquals(61f, ParamNumber.parse(root, "61")!!, 0f)
        assertEquals("the lowest note there is", 24f, ParamNumber.parse(root, "C0")!!, 0f)
        // what is no number is not taken
        for (text in listOf("", " ", "-", "fast", "Hz", "..", "+", ",")) assertNull("\"$text\"", ParamNumber.parse(cutoff, text))
        assertNull(ParamNumber.parse(root, "H2"))
    }
}
