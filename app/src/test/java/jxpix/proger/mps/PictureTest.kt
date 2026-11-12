package jxpix.proger.mps

import jxpix.proger.mps.engine.Arc
import jxpix.proger.mps.engine.Area
import jxpix.proger.mps.engine.Bar
import jxpix.proger.mps.engine.Caption
import jxpix.proger.mps.engine.DeviceType
import jxpix.proger.mps.engine.Devices
import jxpix.proger.mps.engine.Dot
import jxpix.proger.mps.engine.Ink
import jxpix.proger.mps.engine.Lab
import jxpix.proger.mps.engine.Live
import jxpix.proger.mps.engine.Picture
import jxpix.proger.mps.engine.Pictures
import jxpix.proger.mps.engine.Rule
import jxpix.proger.mps.engine.Stroke
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Font
import java.awt.RenderingHints
import java.awt.geom.Arc2D
import java.awt.geom.Path2D
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/** The pictures of the devices: every device has one, and what a picture shows is what the device does to sound. */
class PictureTest {
    private val rate = Lab.RATE

    private fun values(type: DeviceType, over: Map<String, Float> = emptyMap()): (String) -> Float = { id -> over[id] ?: type.spec(id)!!.default }

    private fun array(type: DeviceType, over: Map<String, Float> = emptyMap()) = FloatArray(type.params.size) { over[type.params[it].id] ?: type.params[it].default }

    /** Every picture there is: the device, the group of its knobs, a name for the file. */
    private fun all(): List<Triple<DeviceType, Int, String>> {
        val out = ArrayList<Triple<DeviceType, Int, String>>()
        for (type in Devices.effects) out += Triple(type, 0, type.id)
        for (type in Devices.playable) {
            for ((n, group) in type.params.map { it.group }.distinct().withIndex()) {
                if (Pictures.has(type.id, group)) out += Triple(type, group, type.id + "_" + n)
            }
        }
        return out
    }

    private fun check(what: String, p: Picture) {
        assertTrue("$what: nothing is drawn", p.marks.isNotEmpty())
        fun ok(x: Float) = !x.isNaN() && x >= -0.001f && x <= 1.001f
        // a view from above stands in the middle of a wide picture and may reach out of its square to the sides
        fun wide(x: Float) = if (p.square) !x.isNaN() && x >= -0.5f && x <= 1.5f else ok(x)
        for (m in p.marks) when (m) {
            is Stroke -> {
                assertTrue("$what: a line needs two points", m.xy.size >= 4 && m.xy.size % 2 == 0)
                assertTrue("$what: a line leaves the picture", m.xy.indices.all { if (it % 2 == 0) wide(m.xy[it]) else ok(m.xy[it]) })
            }
            is Area -> assertTrue("$what: an area leaves the picture", m.top.size == m.bottom.size && m.top.all { ok(it) } && m.bottom.all { ok(it) })
            is Bar -> assertTrue("$what: a bar leaves the picture", ok(m.x) && ok(m.from) && ok(m.to))
            is Dot -> assertTrue("$what: a dot leaves the picture (${m.x}, ${m.y})", ok(m.x) && ok(m.y))
            is Rule -> assertTrue("$what: a guide leaves the picture", ok(m.at))
            is Arc -> assertTrue("$what: a circle is off", ok(m.x) && ok(m.y) && m.radius > 0f && !m.from.isNaN() && !m.sweep.isNaN())
            is Caption -> assertTrue("$what: a caption leaves the picture", wide(m.x) && ok(m.y))
        }
    }

    @Test
    fun everyEffectAndEveryInstrumentHasItsPicture() {
        val pictures = all()
        assertEquals("every effect has a picture", Devices.effects.size, pictures.count { !it.first.isInstrument })
        // the synth has six, the FM synth two, a drum, a string and the sampler one each
        assertEquals(11, pictures.count { it.first.isInstrument })
        val started = System.nanoTime()
        for ((type, group, name) in pictures) {
            val p = Pictures.of(type.id, group, values(type), 124f, 0)
            assertNotNull(name, p)
            check(name, p!!)
        }
        val ms = (System.nanoTime() - started) / 1e6
        println("    info pictures: ${pictures.size} drawn in ${"%.0f".format(ms)} ms")
        // with every knob at one end, at the other end, and one at a time
        for ((type, group, name) in pictures) {
            for (end in 0..1) {
                val all = type.params.associate { it.id to if (end == 0) it.min else it.max }
                check("$name, everything at ${if (end == 0) "min" else "max"}", Pictures.of(type.id, group, values(type, all), 60f + 140f * end, 0)!!)
            }
            for (spec in type.params) for (x in floatArrayOf(spec.min, spec.max)) {
                check("$name, ${spec.id} = $x", Pictures.of(type.id, group, values(type, mapOf(spec.id to x)), 120f, 0b101010110101)!!)
            }
        }
        // there is no picture where there is nothing to show
        assertEquals(null, Pictures.of("audio", 0, { 0f }))
        assertEquals(null, Pictures.of("synth", R.string.g_voice, values(Devices.SYNTH)))
    }

    // ------------------------------------------------------------------ a picture shows what the device does

    private val probes = floatArrayOf(40f, 80f, 150f, 300f, 600f, 1000f, 2000f, 4000f, 8000f, 14000f)

    private fun heard(typeId: String, over: Map<String, Float>, click: Float = 1f): FloatArray =
        Lab.response(Lab.effect(typeId, array(Devices[typeId]!!, over), rate)!!, probes, rate, 16384, click)

    /** What comes out, in dB, when a steady level goes in for a quarter of a second. */
    private fun steady(typeId: String, over: Map<String, Float>, levelDb: Float): Float {
        val fx = Lab.effect(typeId, array(Devices[typeId]!!, over), rate)!!
        val n = rate / 4
        val a = 10f.pow(levelDb / 20f)
        val l = FloatArray(n) { a }
        val r = FloatArray(n) { a }
        Lab.run(fx, l, r, Lab.context(rate, 120f, false))
        return 20f * log10(abs(l[n - 1]) + 1e-12f)
    }

    /** By how many dB a loud tone of [freq] comes out louder or quieter than it went in. */
    private fun tone(typeId: String, over: Map<String, Float>, freq: Float): Float {
        val fx = Lab.effect(typeId, array(Devices[typeId]!!, over), rate)!!
        val n = rate / 3
        val dry = FloatArray(n) { sin(2.0 * Math.PI * freq * it / rate).toFloat() }
        val l = dry.copyOf()
        val r = dry.copyOf()
        Lab.run(fx, l, r, Lab.context(rate, 120f, false))
        var a = 0.0
        var b = 0.0
        for (i in n - 8192 until n) {
            a += dry[i] * dry[i]
            b += l[i] * l[i]
        }
        return (10.0 * log10(b / a + 1e-20)).toFloat()
    }

    @Test
    fun theCurvesOfTheFiltersAreTheOnesThatAreHeard() {
        // the equaliser, with every band doing something
        val eq = mapOf("lowGain" to 9f, "lowFreq" to 200f, "midGain" to -12f, "midFreq" to 1500f, "midQ" to 2.5f, "highGain" to 6f, "highFreq" to 5000f)
        val drawn = Pictures.eqDb(values(Devices["eq"]!!, eq))
        val real = heard("eq", eq)
        for (i in probes.indices) assertEquals("equaliser at ${probes[i]} Hz", real[i], drawn(probes[i]), 0.4f)
        assertEquals("the middle band is at its frequency", -12f, drawn(1500f), 0.8f)

        // the filter: low-pass, high-pass, band-pass, notch
        for (type in 0..3) {
            val set = mapOf("type" to type.toFloat(), "cutoff" to 1200f, "resonance" to 0.6f, "lfoDepth" to 0f)
            val line = Pictures.filterDb(type, 1200f, 0.6f)
            val sound = heard("filter", set)
            for (i in probes.indices) if (sound[i] > -45f) assertEquals("filter $type at ${probes[i]} Hz", sound[i], line(probes[i]), 1f)
        }
        val low = Pictures.filterDb(0, 1200f, 0.6f)
        assertTrue("a low-pass lets the lows through and takes the highs away", abs(low(100f)) < 0.5f && low(10000f) < -30f)
        assertTrue("resonance is a peak at the cutoff", low(1200f) > 1.5f)

        // the auto-wah at rest (a click too quiet to move its filter), alone and next to the untouched sound
        for (mix in floatArrayOf(1f, 0.5f)) {
            val set = mapOf("freq" to 500f, "range" to 0.5f, "reso" to 0.7f, "mix" to mix)
            val line = Pictures.wahDb(values(Devices["wah"]!!, set), 500f)
            val sound = heard("wah", set, click = 1e-5f)
            for (i in probes.indices) if (sound[i] > -45f) assertEquals("wah, mix $mix, at ${probes[i]} Hz", sound[i], line(probes[i]), 1f)
        }

        // the comb of the flanger standing still
        val set = mapOf("depth" to 0f, "delay" to 3f, "feedback" to 0.5f, "mix" to 0.5f)
        val comb = Pictures.flangerDb(values(Devices["flanger"]!!, set), 3f)
        val sound = heard("flanger", set)
        for (i in probes.indices) if (comb(probes[i]) > -12f) assertEquals("flanger at ${probes[i]} Hz", sound[i], comb(probes[i]), 1.5f)
    }

    @Test
    fun theCurvesOfTheDynamicsAreTheOnesThatAreHeard() {
        // the timing knobs are set as quick as they go: the curve is about where the level ends up
        for (mix in floatArrayOf(1f, 0.5f)) {
            val set = mapOf("threshold" to -24f, "ratio" to 4f, "attack" to 0.1f, "release" to 10f, "makeup" to 6f, "mix" to mix)
            val v = values(Devices["comp"]!!, set)
            var level = -50f
            while (level <= 0f) {
                assertEquals("compressor, mix $mix, at $level dB", steady("comp", set, level), Pictures.compressorDb(v, level), 0.3f)
                level += 5f
            }
        }
        val comp = values(Devices["comp"]!!, mapOf("threshold" to -20f, "ratio" to 4f, "makeup" to 0f, "mix" to 1f))
        assertEquals("below the threshold nothing happens", -30f, Pictures.compressorDb(comp, -30f), 0.001f)
        assertEquals("20 dB above it come out as 5", -15f, Pictures.compressorDb(comp, 0f), 0.001f)

        val gate = mapOf("threshold" to -30f, "attack" to 0.1f, "hold" to 0f, "release" to 5f, "range" to 40f)
        for (level in floatArrayOf(-60f, -50f, -40f, -20f, -10f, 0f)) {
            assertEquals("gate at $level dB", steady("gate", gate, level), Pictures.gateDb(values(Devices["gate"]!!, gate), level), 0.5f)
        }

        val limiter = mapOf("gain" to 6f, "ceiling" to -3f, "release" to 10f)
        for (level in floatArrayOf(-40f, -20f, -12f, -6f, 0f)) {
            assertEquals("limiter at $level dB", steady("limiter", limiter, level), Pictures.limiterDb(values(Devices["limiter"]!!, limiter), level), 0.3f)
        }

        // the de-esser is drawn at work on the loudest hiss: a loud tone gets exactly that
        val ess = mapOf("freq" to 5000f, "threshold" to -30f, "amount" to 12f)
        val line = Pictures.deEsserDb(values(Devices["deess"]!!, ess), 12f)
        for (f in floatArrayOf(500f, 2000f, 3500f, 5000f, 8000f, 12000f)) assertEquals("de-esser at $f Hz", tone("deess", ess, f), line(f), 1f)
        assertTrue("the voice below the hiss is left alone", abs(line(800f)) < 0.3f)
        assertEquals("the hiss is taken back by the amount", -12f, line(12000f), 0.6f)
    }

    @Test
    fun theEchoesAreDrawnWhereTheyAreHeard() {
        val type = Devices["delay"]!!
        fun click(over: Map<String, Float>, bpm: Float, seconds: Float): Pair<FloatArray, FloatArray> {
            val fx = Lab.effect("delay", array(type, over), rate)!!
            val n = (seconds * rate).toInt()
            val l = FloatArray(n)
            val r = FloatArray(n)
            l[0] = 1f
            r[0] = 1f
            Lab.run(fx, l, r, Lab.context(rate, bpm, false))
            return l to r
        }
        fun energy(x: FloatArray, at: Float): Double {
            var sum = 0.0
            val centre = (at * rate).toInt()
            for (i in max(1, centre - 300) until min(x.size, centre + 300)) sum += x[i] * x[i]
            return sum
        }

        // a free time: an echo every quarter of a second, each half as loud as the one before
        val free = mapOf("sync" to 0f, "time" to 250f, "feedback" to 0.5f, "mix" to 0.5f, "pingPong" to 0f, "damp" to 0f)
        val drawn = Pictures.echoes(values(type, free), 120f, 2f)
        assertEquals(listOf(0.25f, 0.5f, 0.75f, 1f), drawn.take(4).map { it[0] })
        assertEquals(listOf(1f, 0.5f, 0.25f, 0.125f), drawn.take(4).map { it[1] })
        assertTrue("both sides", drawn.all { it[2] == 0f })
        val (l, _) = click(free, 120f, 1.2f)
        for (k in 1..3) {
            val here = energy(l, k * 0.25f)
            assertTrue("echo $k is where it is drawn", here > 20 * energy(l, k * 0.25f - 0.1f))
            if (k > 1) assertEquals("echo $k is half as loud as the one before", -6.02, 10.0 * log10(here / energy(l, (k - 1) * 0.25f)), 1.5)
        }

        // from side to side: the first on the left, the second on the right
        val ping = free + mapOf("pingPong" to 1f)
        val sides = Pictures.echoes(values(type, ping), 120f, 2f)
        assertEquals(listOf(-1f, 1f, -1f, 1f), sides.take(4).map { it[2] })
        assertEquals(listOf(1f, 1f, 0.5f, 0.5f), sides.take(4).map { it[1] })
        val (pl, pr) = click(ping, 120f, 1.2f)
        assertTrue("left first", energy(pl, 0.25f) > 50 * energy(pr, 0.25f))
        assertTrue("then right", energy(pr, 0.5f) > 50 * energy(pl, 0.5f))
        // (a click loses a little more on the way: it goes through the tone of the echo twice, there and back)
        assertEquals("the third is half as loud as the first", -6.02, 10.0 * log10(energy(pl, 0.75f) / energy(pl, 0.25f)), 2.5)

        // in time with the song: every choice of the list, at 100 beats a minute
        val choices = type.spec("sync")!!.max.toInt()
        assertEquals(10, choices)
        for (sync in 1..choices) {
            val set = free + mapOf("sync" to sync.toFloat())
            val at = Pictures.echoes(values(type, set), 100f, 3f).first()[0]
            val (sl, _) = click(set, 100f, at + 0.3f)
            var loudest = 1
            for (i in 2 until sl.size) if (abs(sl[i]) > abs(sl[loudest])) loudest = i
            assertEquals("choice $sync", at, loudest / rate.toFloat(), 0.004f)
        }
    }

    @Test
    fun theMovingPicturesMoveAsTheSoundDoes() {
        fun main(p: Picture) = p.marks.filterIsInstance<Stroke>().filter { it.ink == Ink.MAIN }

        // sidechain pump: the level falls by the depth on every beat and is back before the next
        val pump = Devices["pump"]!!
        val line = main(Pictures.of("pump", 0, values(pump, mapOf("rate" to 1f, "depth" to 0.7f, "recover" to 0.5f)), 120f)!!).single().xy
        val heights = (0 until line.size / 2).map { line[it * 2 + 1] }
        // (the level cannot jump: it is on its way back up before it has quite reached the bottom)
        val lowest = (heights.min() - 0.04f) / 0.92f
        assertTrue("the lowest point is about 1 - depth: $lowest", lowest in 0.3f..0.42f)
        assertEquals("back at full level", 0.96f, heights.max(), 0.01f)
        val dips = (1 until heights.size - 1).count { heights[it] < 0.5f && heights[it] <= heights[it - 1] && heights[it] < heights[it + 1] }
        assertEquals("one dip on every beat of the bar", 4, dips)

        // half-time: the sound climbs half as steeply as time goes by, and catches up when the loop starts again
        val half = Devices["halftime"]!!
        val slow = main(Pictures.of("halftime", 0, values(half), 120f)!!)
        assertEquals("two loops, two lines", 2, slow.size)
        for (piece in slow) {
            val xy = piece.xy
            val slope = (xy[xy.size - 1] - xy[1]) / (xy[xy.size - 2] - xy[0])
            assertEquals("twice as slow", 0.5f, slope, 0.06f)
        }
        assertEquals("the second loop starts where the song is by then", slow[1].xy[0], slow[1].xy[1], 0.03f)

        // beat repeat: the song runs straight, then the last beat plays one slice over and over
        val repeat = Devices["repeat"]!!
        val again = main(Pictures.of("repeat", 0, values(repeat), 120f)!!)
        assertTrue("the fill is in pieces: ${again.size}", again.size >= 4)
        assertEquals("until the fill the song is untouched", again[0].xy[again[0].xy.size - 2], again[0].xy[again[0].xy.size - 1], 0.02f)
        assertTrue("the fill is the last beat of the bar", again[1].xy[0] > 0.72f)
        assertTrue("every piece starts at the same moment of the song", again.drop(1).map { it.xy[1] }.let { it.max() - it.min() } < 0.03f)

        // an envelope: up in the attack, down to the sustain, away after the key is let go
        val (env, total, off) = Pictures.envelopeLine(0.1f, 0.2f, 0.5f, 0.3f, 600)
        assertEquals(0.75f, total, 0.001f)
        val peak = env.indices.maxBy { env[it] }
        assertEquals("the top is where the attack ends", 0.1f / total, peak / 600f, 0.02f)
        assertEquals(1f, env[peak], 0.02f)
        assertEquals("the sustain", 0.5f, env[(off * 600).toInt() - 1], 0.03f)
        assertTrue("silence at the end", env.last() < 0.02f)

        // a bigger room rings longer
        fun ring(size: Float): Float {
            val xy = main(Pictures.of("reverb", 0, values(Devices["reverb"]!!, mapOf("size" to size, "preDelay" to 0f)), 120f)!!).single().xy
            for (i in xy.size / 2 - 1 downTo 0) if (xy[i * 2 + 1] > 0.5f) return xy[i * 2]
            return 0f
        }
        assertTrue("small ${ring(0.2f)}, large ${ring(0.9f)}", ring(0.9f) > ring(0.2f) * 1.5f)

        // the notes the pitch correction pulls to: C major lights the white keys
        val tune = Devices["tune"]!!
        assertEquals(0b101010110101, Pictures.tuneMask(values(tune, mapOf("scale" to 2f, "key" to 0f)), 0))
        assertEquals("the key of the project", 0b10, Pictures.tuneMask(values(tune, mapOf("scale" to 0f)), 0b10))
        assertEquals("no key in the project: every note", 0xFFF, Pictures.tuneMask(values(tune, mapOf("scale" to 0f)), 0))
        val lit = Pictures.of("tune", 0, values(tune, mapOf("scale" to 2f, "key" to 0f)))!!.marks.filterIsInstance<Bar>().count { it.ink == Ink.MAIN }
        assertEquals(7, lit)
    }

    // ------------------------------------------------------------------ for the eyes

    private fun paint(p: Picture, w: Int, h: Int): BufferedImage {
        val img = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        val g = img.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.color = Color(0x1B, 0x14, 0x20)
        g.fillRect(0, 0, w, h)
        val pad = 8f
        var x0 = pad
        var bw = w - 2 * pad
        val bh = h - 2 * pad
        if (p.square) {
            x0 = (w - bh) / 2f
            bw = bh
        }
        fun px(x: Float) = (x0 + x * bw).toDouble()
        fun py(y: Float) = (pad + (1f - y) * bh).toDouble()
        fun ink(i: Ink, alpha: Int = 255) = when (i) {
            Ink.MAIN -> Color(0xFF, 0x4F, 0x9A, alpha)
            Ink.SOFT -> Color(0xFF, 0x4F, 0x9A, alpha * 110 / 255)
            Ink.DIM -> Color(0xA9, 0x9D, 0xAE, alpha)
            Ink.GRID -> Color(0x45, 0x38, 0x4C, alpha)
        }
        for (m in p.marks) when (m) {
            is Rule -> {
                g.color = ink(m.ink)
                g.stroke = if (m.dashed) BasicStroke(1f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10f, floatArrayOf(4f, 4f), 0f) else BasicStroke(1f)
                if (m.vertical) g.draw(java.awt.geom.Line2D.Double(px(m.at), py(0f), px(m.at), py(1f))) else g.draw(java.awt.geom.Line2D.Double(px(0f), py(m.at), px(1f), py(m.at)))
            }
            is Stroke -> {
                val path = Path2D.Double()
                for (i in 0 until m.xy.size / 2) if (i == 0) path.moveTo(px(m.xy[0]), py(m.xy[1])) else path.lineTo(px(m.xy[i * 2]), py(m.xy[i * 2 + 1]))
                if (m.fill) {
                    val area = Path2D.Double(path)
                    area.lineTo(px(m.xy[m.xy.size - 2]), py(m.base))
                    area.lineTo(px(m.xy[0]), py(m.base))
                    area.closePath()
                    g.color = ink(m.ink, 60)
                    g.fill(area)
                }
                g.color = ink(m.ink)
                g.stroke = BasicStroke(if (m.ink == Ink.MAIN) 2.2f else 1.3f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
                g.draw(path)
            }
            is Area -> {
                val path = Path2D.Double()
                val n = m.top.size
                for (i in 0 until n) if (i == 0) path.moveTo(px(0f), py(m.top[0])) else path.lineTo(px(i / (n - 1f)), py(m.top[i]))
                for (i in n - 1 downTo 0) path.lineTo(px(i / (n - 1f)), py(m.bottom[i]))
                path.closePath()
                g.color = ink(m.ink)
                g.fill(path)
            }
            is Bar -> {
                g.color = ink(m.ink)
                val bwid = if (m.width > 0f) m.width * bw else 3f
                g.fill(java.awt.geom.Rectangle2D.Double(px(m.x) - bwid / 2, py(max(m.from, m.to)), bwid.toDouble(), abs(py(m.from) - py(m.to))))
            }
            is Dot -> {
                g.color = ink(m.ink)
                val r = 4.0 * m.size
                g.fill(java.awt.geom.Ellipse2D.Double(px(m.x) - r, py(m.y) - r, 2 * r, 2 * r))
            }
            is Arc -> {
                val r = (m.radius * bh).toDouble()
                val shape = Arc2D.Double(px(m.x) - r, py(m.y) - r, 2 * r, 2 * r, 90.0 - m.from, -m.sweep.toDouble(), if (m.fill) Arc2D.PIE else Arc2D.OPEN)
                g.color = ink(m.ink)
                g.stroke = BasicStroke(if (m.ink == Ink.MAIN) 2f else 1.3f)
                if (m.fill) g.fill(shape) else g.draw(shape)
            }
            is Caption -> {
                g.color = Color(0xA9, 0x9D, 0xAE)
                g.font = Font("SansSerif", Font.PLAIN, 10)
                val text = when (m.unit) {
                    Caption.SECONDS -> if (m.value < 1f) "${(m.value * 1000).toInt()} ms" else "%.2f s".format(m.value)
                    Caption.LEFT -> "L"
                    Caption.RIGHT -> "R"
                    else -> m.text
                }
                val tw = g.fontMetrics.stringWidth(text)
                val x = px(m.x) - when (m.align) { 0 -> tw / 2.0; 1 -> tw.toDouble(); else -> 0.0 }
                g.drawString(text, x.toFloat(), py(m.y).toFloat())
            }
        }
        g.dispose()
        return img
    }

    /** Draws every picture into one sheet for a look: not a check, the checks are the other tests. */
    @Test
    fun aSheetOfAllPictures() {
        val dir = System.getenv("MPS_RENDER_DIR") ?: return
        val list = all()
        val w = 380
        val h = 118
        val columns = 3
        fun sheet(name: String, over: (DeviceType) -> Map<String, Float>) {
            val rows = (list.size + columns - 1) / columns
            val img = BufferedImage(columns * (w + 10) + 10, rows * (h + 24) + 10, BufferedImage.TYPE_INT_RGB)
            val g = img.createGraphics()
            g.color = Color(0x10, 0x0B, 0x14)
            g.fillRect(0, 0, img.width, img.height)
            g.font = Font("SansSerif", Font.BOLD, 12)
            for ((i, item) in list.withIndex()) {
                val (type, group, label) = item
                val p = Pictures.of(type.id, group, values(type, over(type)), 124f, 0b101010110101) ?: continue
                val x = 10 + (i % columns) * (w + 10)
                val y = 10 + (i / columns) * (h + 24)
                g.color = Color.WHITE
                g.drawString(label + (if (p.live != Live.NONE) "   live: ${p.live}" else ""), x, y + 12)
                g.drawImage(paint(p, w, h), x, y + 16, null)
            }
            g.dispose()
            File(dir, "pictures").mkdirs()
            ImageIO.write(img, "png", File(dir, "pictures/$name.png"))
        }
        sheet("defaults") { emptyMap() }
        // the same devices set differently: the pictures have to follow
        sheet("turned") { type ->
            type.params.associate { s ->
                s.id to when {
                    s.isChoice -> ((s.default + 1f).let { if (it > s.max) s.min else it })
                    else -> s.fromNorm((s.toNorm(s.default) + 0.33f).let { if (it > 1f) it - 1f else it })
                }
            }
        }
    }
}
