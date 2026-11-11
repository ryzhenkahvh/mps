package jxpix.proger.mps.engine

import jxpix.proger.mps.engine.instruments.DrumSynth
import jxpix.proger.mps.model.MASTER_ID
import jxpix.proger.mps.model.Note
import jxpix.proger.mps.model.Presets
import jxpix.proger.mps.model.Project
import jxpix.proger.mps.model.addAudioClip
import jxpix.proger.mps.model.addChannel
import jxpix.proger.mps.model.addEffect
import jxpix.proger.mps.model.addPattern
import jxpix.proger.mps.model.addPatternClip
import jxpix.proger.mps.model.setNotes
import jxpix.proger.mps.model.updateChannel
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sin

/**
 * Gets the sound engine up to speed before the first note is played.
 *
 * Android turns code into fast machine code only after it has been running for a while.
 * Left alone, the first seconds of playback would run in the slow interpreter: the sound
 * would stutter and the automatic output buffer would grow (more latency) for no good reason.
 *
 * So right after the app starts, a throwaway engine plays a busy little project into nowhere
 * on a background thread. Every drum sound, every synth preset, the sampler, an audio clip
 * and every effect get their turn, which is all the runtime needs to compile them.
 */
object EngineWarmup {

    class Result(
        val frames: Long,
        val peak: Float,
        val millis: Long,
        val project: Project,
    )

    private val started = AtomicBoolean(false)

    /** True once the background run is over (successfully or not). */
    @Volatile
    var finished = false
        private set

    /** Starts the warm-up in the background. Only the first call does anything. */
    fun start(sampleRate: Int, blockFrames: Int) {
        if (!started.compareAndSet(false, true)) return
        val thread = Thread({
            try {
                run(sampleRate, blockFrames)
            } catch (_: Throwable) {
                // purely an optimisation: whatever goes wrong here must not reach the user
            } finally {
                finished = true
            }
        }, "MPS-Warmup")
        thread.isDaemon = true
        thread.priority = Thread.NORM_PRIORITY - 2
        thread.start()
    }

    /**
     * Plays [seconds] of the warm-up project in blocks of [blockFrames] (the size the real
     * output uses) and throws the sound away. Gives up after [budgetMillis] so that a slow
     * phone is not kept busy for long.
     */
    fun run(sampleRate: Int, blockFrames: Int, seconds: Double = 2.5, budgetMillis: Long = 6000): Result {
        val startedAt = System.nanoTime()
        val samples = SamplePool()
        val project = buildProject(samples, sampleRate)
        val engine = Engine(sampleRate)
        engine.setProject(project, samples)
        engine.stopAtEnd = false
        engine.songMode = true

        val block = blockFrames.coerceIn(16, 4096)
        val buffer = FloatArray(block * 2)
        val total = (seconds * sampleRate).toLong()
        val deadline = startedAt + budgetMillis * 1_000_000L
        var frames = 0L
        var peak = 0f
        engine.play(0)
        while (frames < total && System.nanoTime() < deadline) {
            engine.render(buffer, block)
            for (i in buffer.indices) peak = max(peak, abs(buffer[i]))
            frames += block
        }
        return Result(frames, peak, (System.nanoTime() - startedAt) / 1_000_000L, project)
    }

    /** One bar that loops, with something for every kind of device to do. */
    internal fun buildProject(samples: SamplePool, sampleRate: Int): Project {
        var p = Project(name = "warm-up", bpm = 140f, tracks = 2, loopEnabled = true, loopStart = 0, loopEnd = 1)
        val bar = p.ticksPerBar
        val beat = bar / p.beatsPerBar
        val patternId = p.addPattern("").let { p = it.first; it.second }
        val owners = ArrayList<Int>()

        fun channel(type: String, params: Map<String, Float>?, notes: List<Note>): Int {
            val id = p.addChannel(type, "", params = params).let { p = it.first; it.second }
            if (notes.isNotEmpty()) p = p.setNotes(patternId, id, notes)
            owners += id
            return id
        }

        // every drum sound, eight hits per bar
        for (type in 0 until DrumSynth.TYPE_COUNT) {
            channel(Devices.DRUM.id, DrumSynth.defaultsFor(type), List(8) { Note(it * beat / 2, beat / 4, 60, 0.9f) })
        }
        // every synth preset, a two-note chord on each beat
        Presets.synth.forEachIndexed { i, preset ->
            val root = 45 + i % 12
            val notes = ArrayList<Note>()
            for (b in 0 until p.beatsPerBar) {
                notes += Note(b * beat, beat * 4 / 5, root, 0.8f)
                notes += Note(b * beat, beat * 4 / 5, root + 7, 0.7f)
            }
            channel(Devices.SYNTH.id, preset.params, notes)
        }
        // the other instruments, with a couple of their presets each
        for (type in listOf(Devices.FM, Devices.STRING)) {
            Presets.forType(type.id).take(3).forEachIndexed { i, preset ->
                channel(type.id, preset.params, List(p.beatsPerBar) { Note(it * beat, beat * 3 / 4, 48 + i * 7 + it * 2, 0.8f) })
            }
        }
        // a short tone for the sampler and for an audio clip
        val tone = ShortArray(sampleRate / 2) {
            (sin(2.0 * PI * 220.0 * it / sampleRate) * 12000.0 * (1.0 - it.toDouble() / (sampleRate / 2))).toInt().toShort()
        }
        samples.put(SAMPLE, SampleData(sampleRate, tone))
        val sampler = channel(Devices.SAMPLER.id, null, List(p.beatsPerBar) { Note(it * beat, beat / 2, 55 + it * 5, 0.8f) })
        p = p.updateChannel(sampler) { it.copy(instrument = it.instrument.copy(sample = SAMPLE)) }
        val audio = channel(Devices.AUDIO_TYPE_ID, null, emptyList())
        p = p.addAudioClip(audio, SAMPLE, track = 1, start = 0, length = bar).first
        p = p.addPatternClip(patternId, track = 0, start = 0, length = bar).first

        // every effect once on a channel that makes sound, and a typical chain on the master
        Devices.effects.forEachIndexed { i, type ->
            p = p.addEffect(owners[(i * 3) % owners.size], type.id).first
        }
        for (id in MASTER_CHAIN) p = p.addEffect(MASTER_ID, id).first
        return p
    }

    private const val SAMPLE = "warm-up"
    private val MASTER_CHAIN = listOf("eq", "comp", "limiter")
}
