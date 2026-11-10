package jxpix.proger.mps.audio

import kotlin.math.roundToInt
import kotlin.math.log10
import java.util.Locale
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
 * Phones differ a lot here, so nothing is taken for granted: several microphone paths and
 * sample rates are tried until one really delivers sound, and what happened is kept in
 * [report] so that a failure can be explained instead of just "it does not work".
 */
class Recorder(context: Context) {

    private val audioManager = context.applicationContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    @Volatile var recording = false; private set

    /** Peak level (0..1) of the incoming signal since the last read; drives the input meter. */
    @Volatile var level = 0f

    /** Loudest input (0..1) since the recording started. */
    @Volatile var peak = 0f; private set

    /** Time ([System.nanoTime]) at which the first recorded frame was captured by the microphone. */
    @Volatile var firstFrameNanos = 0L; private set

    @Volatile private var frames = 0

    /** Rate of the running (or last) recording; not always the one that was asked for. */
    @Volatile var sampleRate = 48000; private set

    /** The microphone path in use: one of the MediaRecorder.AudioSource constants. */
    @Volatile var source = 0; private set

    /** Why the microphone could not be started or stopped delivering; null when all is well. */
    @Volatile var error: String? = null; private set

    private var thread: Thread? = null
    private val chunks = ArrayList<ShortArray>()
    @Volatile private var stopRequested = false

    val seconds: Float get() = frames.toFloat() / sampleRate

    /** One line for the settings screen and for bug reports. */
    val report: String
        get() {
            val e = error
            val name = when (source) {
                MediaRecorder.AudioSource.VOICE_RECOGNITION -> "VOICE_RECOGNITION"
                MediaRecorder.AudioSource.MIC -> "MIC"
                MediaRecorder.AudioSource.UNPROCESSED -> "UNPROCESSED"
                MediaRecorder.AudioSource.DEFAULT -> "DEFAULT"
                else -> "-"
            }
            val db = if (peak > 0f) (20.0 * log10(peak.toDouble())).roundToInt().toString() + " dB" else "-inf dB"
            return if (e != null) "$name $sampleRate Hz: $e" else "$name $sampleRate Hz, ${"%.1f".format(Locale.ROOT, seconds)} s, peak $db"
        }

    /**
     * Starts recording. The caller must hold the RECORD_AUDIO permission.
     *
     * @param preferredRate the rate of the sound engine; another one is used if the microphone
     *                      does not offer it (recordings carry their own rate).
     */
    @SuppressLint("MissingPermission")
    @Synchronized
    fun start(preferredRate: Int): Boolean {
        if (recording) return false
        error = null

        // The speech-recognition path comes first: Android requires it to be free of automatic
        // gain and noise suppression, at a sensible level - and it is the low-latency one.
        // The "unprocessed" path is just as clean but 18 dB quieter, so it is only a fallback.
        val sources = ArrayList<Int>()
        sources += MediaRecorder.AudioSource.VOICE_RECOGNITION
        sources += MediaRecorder.AudioSource.MIC
        if (audioManager.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED) == "true") {
            sources += MediaRecorder.AudioSource.UNPROCESSED
        }
        sources += MediaRecorder.AudioSource.DEFAULT

        val rates = LinkedHashSet<Int>()
        if (preferredRate == 44100 || preferredRate == 48000) rates += preferredRate
        rates += 48000
        rates += 44100
        rates += 16000

        var rec: AudioRecord? = null
        var problem = "no microphone path could be opened"
        search@ for (candidateSource in sources) {
            for (rate in rates) {
                val minBytes = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
                if (minBytes <= 0) {
                    problem = "$rate Hz is not offered (code $minBytes)"
                    continue
                }
                var candidate: AudioRecord? = null
                try {
                    candidate = AudioRecord(
                        candidateSource, rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, max(minBytes, rate / 5 * 2),
                    )
                    if (candidate.state != AudioRecord.STATE_INITIALIZED) {
                        problem = "source $candidateSource at $rate Hz did not initialise"
                        candidate.release()
                        continue
                    }
                    candidate.startRecording()
                    if (candidate.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                        problem = "source $candidateSource at $rate Hz did not start (is another app recording?)"
                        candidate.release()
                        continue
                    }
                    rec = candidate
                    source = candidateSource
                    sampleRate = rate
                    break@search
                } catch (e: Exception) {
                    problem = "source $candidateSource at $rate Hz: ${e.javaClass.simpleName} ${e.message ?: ""}"
                    Log.w(TAG, problem, e)
                    try {
                        candidate?.release()
                    } catch (_: Exception) {
                    }
                }
            }
        }
        if (rec == null) {
            error = problem
            Log.w(TAG, "Microphone not started: $problem")
            return false
        }

        synchronized(chunks) { chunks.clear() }
        frames = 0
        firstFrameNanos = 0L
        level = 0f
        peak = 0f
        stopRequested = false
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
        val rate = sampleRate
        val chunkFrames = max(256, rate / 50)
        val maxFrames = rate * MAX_SECONDS
        var emptyReads = 0
        try {
            while (!stopRequested && frames < maxFrames) {
                val chunk = ShortArray(chunkFrames)
                val n = rec.read(chunk, 0, chunkFrames, AudioRecord.READ_BLOCKING)
                if (n < 0) {
                    error = "the microphone stopped delivering sound (code $n)"
                    break
                }
                if (n == 0) {
                    // nothing yet: do not spin, and give up if it stays that way
                    if (++emptyReads > 400) {
                        error = "the microphone delivers no data"
                        break
                    }
                    try {
                        Thread.sleep(5)
                    } catch (_: InterruptedException) {
                        break
                    }
                    continue
                }
                emptyReads = 0
                if (firstFrameNanos == 0L) {
                    val now = System.nanoTime()
                    var first = now - n * 1_000_000_000L / rate
                    try {
                        if (rec.getTimestamp(timestamp, AudioTimestamp.TIMEBASE_MONOTONIC) == AudioRecord.SUCCESS) {
                            val t = timestamp.nanoTime - timestamp.framePosition * 1_000_000_000L / rate
                            // trust the hardware time stamp only if it is plausible
                            if (abs(t - first) < 500_000_000L) first = t
                        }
                    } catch (_: Throwable) {
                    }
                    firstFrameNanos = first
                }
                var top = 0
                for (i in 0 until n) {
                    val a = abs(chunk[i].toInt())
                    if (a > top) top = a
                }
                val p = top / 32768f
                if (p > level) level = p
                if (p > peak) peak = p
                synchronized(chunks) { chunks += if (n == chunkFrames) chunk else chunk.copyOf(n) }
                frames += n
            }
        } catch (e: Throwable) {
            error = "${e.javaClass.simpleName} ${e.message ?: ""}"
            Log.w(TAG, "Recording failed", e)
        } finally {
            try {
                rec.stop()
            } catch (_: Exception) {
            }
            try {
                rec.release()
            } catch (_: Exception) {
            }
            recording = false
        }
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
