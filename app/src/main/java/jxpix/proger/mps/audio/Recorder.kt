package jxpix.proger.mps.audio

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTimestamp
import android.media.MediaRecorder
import android.os.Process
import android.util.Log
import jxpix.proger.mps.engine.SampleData
import kotlin.math.abs
import kotlin.math.max

/**
 * Records the microphone into memory (mono, 16-bit).
 *
 * The raw, unprocessed microphone is preferred when the device offers it: the automatic
 * gain and noise suppression meant for phone calls would ruin a vocal take.
 */
class Recorder(context: Context) {

    private val audioManager = context.applicationContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    @Volatile var recording = false; private set

    /** Peak level (0..1) of the incoming signal since the last read; drives the input meter. */
    @Volatile var level = 0f

    /** Time ([System.nanoTime]) at which the first recorded frame was captured by the microphone. */
    @Volatile var firstFrameNanos = 0L; private set

    @Volatile private var frames = 0
    private var sampleRate = 48000
    private var thread: Thread? = null
    private var record: AudioRecord? = null
    private val chunks = ArrayList<ShortArray>()
    @Volatile private var stopRequested = false

    val seconds: Float get() = frames.toFloat() / sampleRate

    /** Starts recording. The caller must hold the RECORD_AUDIO permission. */
    @SuppressLint("MissingPermission")
    @Synchronized
    fun start(rate: Int): Boolean {
        if (recording) return false
        sampleRate = rate
        val minBytes = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (minBytes <= 0) return false
        val sources = ArrayList<Int>()
        if (audioManager.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED) == "true") {
            sources += MediaRecorder.AudioSource.UNPROCESSED
        }
        sources += MediaRecorder.AudioSource.VOICE_RECOGNITION
        sources += MediaRecorder.AudioSource.MIC

        var rec: AudioRecord? = null
        for (source in sources) {
            try {
                val candidate = AudioRecord(
                    source, rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, max(minBytes, rate / 5 * 2),
                )
                if (candidate.state == AudioRecord.STATE_INITIALIZED) {
                    rec = candidate
                    break
                }
                candidate.release()
            } catch (e: Exception) {
                Log.w(TAG, "Audio source $source is not available", e)
            }
        }
        if (rec == null) return false

        synchronized(chunks) { chunks.clear() }
        frames = 0
        firstFrameNanos = 0L
        level = 0f
        stopRequested = false
        record = rec
        try {
            rec.startRecording()
        } catch (e: Exception) {
            rec.release()
            record = null
            return false
        }
        if (rec.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
            rec.release()
            record = null
            return false
        }
        recording = true
        thread = Thread({ loop(rec) }, "MPS-Record").also { it.start() }
        return true
    }

    private fun loop(rec: AudioRecord) {
        try {
            Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
        } catch (_: Exception) {
        }
        val timestamp = AudioTimestamp()
        val chunkFrames = max(256, sampleRate / 50)
        val maxFrames = sampleRate * MAX_SECONDS
        while (!stopRequested && frames < maxFrames) {
            val chunk = ShortArray(chunkFrames)
            val n = rec.read(chunk, 0, chunkFrames, AudioRecord.READ_BLOCKING)
            if (n <= 0) {
                if (n < 0) break
                continue
            }
            if (firstFrameNanos == 0L) {
                val now = System.nanoTime()
                var first = now - n * 1_000_000_000L / sampleRate
                try {
                    if (rec.getTimestamp(timestamp, AudioTimestamp.TIMEBASE_MONOTONIC) == AudioRecord.SUCCESS) {
                        val t = timestamp.nanoTime - timestamp.framePosition * 1_000_000_000L / sampleRate
                        // trust the hardware time stamp only if it is plausible
                        if (abs(t - first) < 500_000_000L) first = t
                    }
                } catch (_: Exception) {
                }
                firstFrameNanos = first
            }
            var peak = 0
            for (i in 0 until n) {
                val a = abs(chunk[i].toInt())
                if (a > peak) peak = a
            }
            val p = peak / 32768f
            if (p > level) level = p
            synchronized(chunks) { chunks += if (n == chunkFrames) chunk else chunk.copyOf(n) }
            frames += n
        }
        try {
            rec.stop()
        } catch (_: Exception) {
        }
        rec.release()
        recording = false
    }

    /** Stops and returns what was recorded (null if nothing). */
    @Synchronized
    fun stop(): SampleData? {
        val t = thread ?: return null
        stopRequested = true
        try {
            t.join(1500)
        } catch (_: InterruptedException) {
        }
        thread = null
        record = null
        recording = false
        val data: ShortArray
        synchronized(chunks) {
            var total = 0
            for (c in chunks) total += c.size
            data = ShortArray(total)
            var o = 0
            for (c in chunks) {
                System.arraycopy(c, 0, data, o, c.size)
                o += c.size
            }
            chunks.clear()
        }
        return if (data.size < sampleRate / 20) null else SampleData(sampleRate, data)
    }

    private companion object {
        const val TAG = "MPS-Record"
        const val MAX_SECONDS = 300
    }
}
