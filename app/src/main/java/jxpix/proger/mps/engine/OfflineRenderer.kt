package jxpix.proger.mps.engine

import jxpix.proger.mps.model.Project
import java.io.File
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Renders a project to a WAV file faster than real time, using a private [Engine]
 * so that playback in the app is not disturbed.
 */
object OfflineRenderer {

    class Result(val frames: Long, val peak: Float, val seconds: Double, val limiterDb: Float)

    /**
     * @param patternId when non-zero, only this pattern is rendered ([loops] times);
     *                  otherwise the whole song (playlist) is rendered.
     * @param tailSeconds extra time after the end so reverbs and delays can ring out.
     * @param fixedTail keep the whole tail even when it is silent: files rendered one channel
     *                  at a time (stems) must all have exactly the same length.
     * @param progress called with 0..1; return false to cancel.
     */
    fun render(
        project: Project,
        samples: SamplePool,
        out: File,
        sampleRate: Int = 48000,
        patternId: Int = 0,
        loops: Int = 1,
        tailSeconds: Double = 3.0,
        fixedTail: Boolean = false,
        progress: (Float) -> Boolean = { true },
    ): Result? {
        val engine = Engine(sampleRate)
        engine.setProject(project.copy(loopEnabled = false), samples)
        engine.stopAtEnd = false

        val lengthTicks: Double
        if (patternId != 0) {
            val p = project.pattern(patternId) ?: return null
            engine.songMode = false
            engine.patternId = patternId
            lengthTicks = project.patternLength(p).toDouble() * max(1, loops)
        } else {
            engine.songMode = true
            lengthTicks = project.songEnd.toDouble()
        }
        if (lengthTicks <= 0.0) return null

        val framesPerTick = 60.0 * sampleRate / (project.bpm * PPQ)
        val bodyFrames = (lengthTicks * framesPerTick).toLong()
        val maxTail = (tailSeconds * sampleRate).toLong()
        val minTail = min(maxTail, (0.2 * sampleRate).toLong())

        val block = MAX_BLOCK
        val buf = FloatArray(block * 2)
        val writer = Wav.Writer(out, sampleRate, 2)
        var peak = 0f
        var done = 0L
        var quiet = 0L
        var ok = false
        try {
            engine.play(0)
            // ---- the music itself ----
            while (done < bodyFrames) {
                val n = min(block.toLong(), bodyFrames - done).toInt()
                engine.render(buf, n)
                for (i in 0 until n * 2) peak = max(peak, abs(buf[i]))
                writer.write(buf, n * 2)
                done += n
                if (!progress(done.toFloat() / (bodyFrames + maxTail))) return null
            }
            // ---- release / reverb tail: notes are let go, rendering stops once it is silent ----
            engine.pause()
            var tail = 0L
            while (tail < maxTail) {
                val n = min(block.toLong(), maxTail - tail).toInt()
                engine.render(buf, n)
                var blockPeak = 0f
                for (i in 0 until n * 2) blockPeak = max(blockPeak, abs(buf[i]))
                peak = max(peak, blockPeak)
                writer.write(buf, n * 2)
                tail += n
                done += n
                quiet = if (blockPeak < 0.0003f) quiet + n else 0
                if (!fixedTail && tail >= minTail && quiet > sampleRate / 5) break
                if (!progress(done.toFloat() / (bodyFrames + maxTail))) return null
            }
            ok = true
        } finally {
            writer.close()
            if (!ok) out.delete()
        }
        progress(1f)
        return Result(done, peak, done.toDouble() / sampleRate, engine.limiterReductionDb)
    }
}
