package jxpix.proger.mps.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTimestamp
import android.media.AudioTrack
import android.os.PerformanceHintManager
import android.os.Process
import android.util.Log
import kotlin.math.max
import kotlin.math.min

/** How much safety margin the output buffer gets. Smaller = lower latency, higher glitch risk. */
enum class LatencyProfile(val bursts: Int, val canGrow: Boolean) {
    /** Starts as low as possible and grows by itself when the device cannot keep up. */
    AUTO(2, true),
    LOW(2, false),
    BALANCED(4, false),
    SAFE(8, false),
}

/** Source of the audio. A dedicated interface (not a lambda) so that nothing gets boxed per call. */
fun interface AudioRenderer {
    /** Fills [out] with [frames] interleaved stereo frames. Runs on the audio thread. */
    fun render(out: FloatArray, frames: Int)
}

/**
 * Low-latency audio output.
 *
 * Android only gives an app the "fast" (low latency) mixer path if it plays by the rules,
 * so this class is careful to:
 *  - use the device's native sample rate and burst size (no resampling in the system mixer),
 *  - ask for [AudioTrack.PERFORMANCE_MODE_LOW_LATENCY],
 *  - keep the buffer at a small multiple of the burst and only grow it after real underruns,
 *  - feed the track from one dedicated thread running at urgent-audio priority which never
 *    allocates or takes locks (see the engine), and
 *  - tell the CPU governor how long a block may take (performance hints), so the cores do
 *    not fall asleep between two audio bursts.
 */
class AudioOutput(context: Context) {

    private val appContext = context.applicationContext
    private val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    /** Native output rate of the device (usually 48000). */
    val sampleRate: Int = audioManager.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)?.toIntOrNull()
        ?.takeIf { it in 22050..192000 } ?: 48000

    /** Number of frames the audio hardware consumes at a time. */
    val framesPerBurst: Int = audioManager.getProperty(AudioManager.PROPERTY_OUTPUT_FRAMES_PER_BUFFER)?.toIntOrNull()
        ?.takeIf { it in 16..8192 } ?: 256

    @Volatile
    var renderer: AudioRenderer? = null

    /** Called (on the main thread) when another app takes the audio focus, e.g. a phone call. */
    var onFocusLost: (() -> Unit)? = null

    // ---- live statistics, readable from any thread ----
    @Volatile var running = false; private set
    @Volatile var lowLatency = false; private set
    @Volatile var bufferFrames = 0; private set
    @Volatile var underruns = 0; private set
    @Volatile var latencyMs = 0f; private set
    /**
     * Output delay to work with: the measured value, or - while the device has not reported a
     * time stamp yet (some never do) - an estimate from the buffer size.
     */
    val effectiveLatencyMs: Float
        get() {
            val measured = latencyMs
            if (measured > 0f) return measured
            val frames = bufferFrames
            return if (frames > 0) frames * 1000f / sampleRate + HARDWARE_GUESS_MS else 0f
        }

    /** Share of the available time used for rendering, 0..1+. */
    @Volatile var load = 0f; private set

    @Volatile
    var profile = LatencyProfile.AUTO
        set(value) {
            if (field != value) learnedBursts = 0
            field = value
            profileChanged = true
        }

    @Volatile private var profileChanged = false

    /**
     * Buffer size (in bursts) the automatic profile has arrived at. Kept while the app lives, so the
     * same glitches are not needed again every time the output is reopened (coming back from
     * another app, plugging in headphones).
     */
    @Volatile private var learnedBursts = 0

    private fun startBursts(): Int {
        val p = profile
        return if (p.canGrow) max(p.bursts, learnedBursts) else p.bursts
    }
    private var thread: Thread? = null

    /**
     * Changes on every start and stop. An audio thread only keeps running while its own number
     * is current, so a thread that is slow to finish can never interfere with its successor.
     */
    @Volatile private var generation = 0

    private fun alive(gen: Int) = running && generation == gen
    private var focusRequest: AudioFocusRequest? = null

    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
            onFocusLost?.invoke()
        }
    }

    @Synchronized
    fun start() {
        if (running) return
        running = true
        val gen = ++generation
        requestFocus()
        thread = Thread({ runLoop(gen) }, "MPS-Audio").also {
            it.priority = Thread.MAX_PRIORITY
            it.start()
        }
    }

    @Synchronized
    fun stop() {
        if (!running) return
        running = false
        generation++
        val t = thread
        thread = null
        try {
            t?.join(1000)
        } catch (_: InterruptedException) {
        }
        abandonFocus()
    }

    private fun requestFocus() {
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(attrs)
            .setOnAudioFocusChangeListener(focusListener)
            .build()
        focusRequest = request
        try {
            audioManager.requestAudioFocus(request)
        } catch (e: Exception) {
            Log.w(TAG, "Audio focus request failed", e)
        }
    }

    private fun abandonFocus() {
        val request = focusRequest ?: return
        focusRequest = null
        try {
            audioManager.abandonAudioFocusRequest(request)
        } catch (_: Exception) {
        }
    }

    private fun createTrack(encoding: Int): AudioTrack? {
        val format = AudioFormat.Builder()
            .setSampleRate(sampleRate)
            .setEncoding(encoding)
            .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
            .build()
        val bytesPerFrame = if (encoding == AudioFormat.ENCODING_PCM_FLOAT) 8 else 4
        val minBytes = AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_STEREO, encoding)
        // room to grow if the device needs it
        val capacity = max(max(minBytes, 0), framesPerBurst * MAX_BURSTS * bytesPerFrame)
        return try {
            val track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                .setAudioFormat(format)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                .setBufferSizeInBytes(capacity)
                .build()
            if (track.state != AudioTrack.STATE_INITIALIZED) {
                track.release()
                null
            } else track
        } catch (e: Exception) {
            Log.w(TAG, "AudioTrack creation failed", e)
            null
        }
    }

    private fun applyProfile(track: AudioTrack, bursts: Int): Int {
        val wanted = (framesPerBurst * bursts).coerceAtMost(track.bufferCapacityInFrames)
        val actual = track.setBufferSizeInFrames(wanted)
        bufferFrames = if (actual > 0) actual else track.bufferSizeInFrames
        return bufferFrames
    }

    private fun runLoop(gen: Int) {
        try {
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        } catch (e: Exception) {
            try {
                Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
            } catch (_: Exception) {
            }
        }
        val burst = framesPerBurst
        val floats = FloatArray(burst * 2)
        val burstNanos = burst * 1_000_000_000L / sampleRate
        val hints = createHintSession(burstNanos)
        var failures = 0
        try {
            // If the output dies (for example the audio route changed), open it again.
            while (alive(gen)) {
                val ok = playOnce(floats, burstNanos, hints, gen)
                if (!alive(gen)) break
                failures = if (ok) 0 else failures + 1
                if (failures > 5) {
                    Log.e(TAG, "Giving up on the audio output")
                    break
                }
                try {
                    Thread.sleep(150)
                } catch (_: InterruptedException) {
                }
            }
        } finally {
            try {
                hints?.close()
            } catch (_: Exception) {
            }
            if (generation == gen) {
                load = 0f
                running = false
            }
        }
    }

    /** Opens a track and feeds it until [stop] is called or the track fails. Returns false on failure to open. */
    private fun playOnce(floats: FloatArray, burstNanos: Long, hints: PerformanceHintManager.Session?, gen: Int): Boolean {
        // Float keeps full quality; fall back to 16-bit if that is what the fast path wants.
        var track = createTrack(AudioFormat.ENCODING_PCM_FLOAT)
        var float = true
        if (track != null && track.performanceMode != AudioTrack.PERFORMANCE_MODE_LOW_LATENCY) {
            val alt = createTrack(AudioFormat.ENCODING_PCM_16BIT)
            if (alt != null && alt.performanceMode == AudioTrack.PERFORMANCE_MODE_LOW_LATENCY) {
                track.release()
                track = alt
                float = false
            } else alt?.release()
        }
        if (track == null) {
            track = createTrack(AudioFormat.ENCODING_PCM_16BIT)
            float = false
        }
        if (track == null) {
            Log.e(TAG, "No audio output available")
            return false
        }

        lowLatency = track.performanceMode == AudioTrack.PERFORMANCE_MODE_LOW_LATENCY
        var bursts = startBursts()
        applyProfile(track, bursts)
        profileChanged = false

        val burst = framesPerBurst
        val shorts = if (float) null else ShortArray(burst * 2)
        val timestamp = AudioTimestamp()
        var written = 0L
        var lastUnderruns = track.underrunCount
        // A track that has just been opened starts with an empty buffer and often reports a few
        // underruns before it settles. They say nothing about the speed of the phone.
        var settling = SETTLE_CHECKS
        var statsCountdown = 0
        var loadAvg = 0f
        val statsEvery = max(1, sampleRate / burst / 4) // about four times per second

        try {
            track.play()
            while (alive(gen)) {
                val t0 = System.nanoTime()
                val r = renderer
                if (r != null) r.render(floats, burst) else java.util.Arrays.fill(floats, 0f)
                val t1 = System.nanoTime()

                val result = if (shorts == null) {
                    track.write(floats, 0, floats.size, AudioTrack.WRITE_BLOCKING)
                } else {
                    for (i in floats.indices) {
                        val v = floats[i]
                        shorts[i] = (if (v > 1f) 32767f else if (v < -1f) -32767f else v * 32767f).toInt().toShort()
                    }
                    track.write(shorts, 0, shorts.size, AudioTrack.WRITE_BLOCKING)
                }
                if (result < 0) {
                    Log.w(TAG, "AudioTrack write failed: $result")
                    return true
                }
                written += burst

                val work = t1 - t0
                loadAvg += (work.toFloat() / burstNanos - loadAvg) * 0.05f
                if (hints != null) {
                    try {
                        hints.reportActualWorkDuration(max(1L, work))
                    } catch (_: Exception) {
                    }
                }

                if (--statsCountdown <= 0) {
                    statsCountdown = statsEvery
                    load = loadAvg
                    if (profileChanged) {
                        profileChanged = false
                        bursts = startBursts()
                        applyProfile(track, bursts)
                    }
                    val u = track.underrunCount
                    if (settling > 0) {
                        settling--
                        lastUnderruns = u
                    } else if (u != lastUnderruns) {
                        underruns += (u - lastUnderruns).coerceAtLeast(0)
                        lastUnderruns = u
                        // the device could not keep up: give it one more burst of safety
                        if (profile.canGrow && bursts < MAX_BURSTS) {
                            bursts++
                            learnedBursts = bursts
                            applyProfile(track, bursts)
                        }
                    }
                    try {
                        if (track.getTimestamp(timestamp)) {
                            val presented = timestamp.framePosition +
                                (System.nanoTime() - timestamp.nanoTime) * sampleRate / 1_000_000_000L
                            val ms = (written - presented) * 1000f / sampleRate
                            if (ms in 0f..1000f) latencyMs += (ms - latencyMs) * if (latencyMs == 0f) 1f else 0.3f
                        }
                    } catch (_: Exception) {
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Audio thread error", e)
        } finally {
            try {
                track.pause()
                track.flush()
            } catch (_: Exception) {
            }
            track.release()
        }
        return true
    }

    private fun createHintSession(targetNanos: Long): PerformanceHintManager.Session? = try {
        val manager = appContext.getSystemService(PerformanceHintManager::class.java)
        manager?.createHintSession(intArrayOf(Process.myTid()), min(targetNanos, 20_000_000L).coerceAtLeast(500_000L))
    } catch (e: Exception) {
        null
    }

    private companion object {
        const val TAG = "MPS-Audio"

        /** Typical delay of the fast output path behind the app's own buffer. */
        const val HARDWARE_GUESS_MS = 12f

        /** Largest buffer the automatic profile may grow to, in bursts. */
        const val MAX_BURSTS = 16

        /** Number of statistics rounds (a quarter of a second each) a new track gets to settle. */
        const val SETTLE_CHECKS = 3
    }
}
