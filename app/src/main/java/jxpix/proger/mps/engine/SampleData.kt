package jxpix.proger.mps.engine

import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlin.math.max

/**
 * Immutable PCM audio held in memory as 16-bit samples (half the footprint of float,
 * which matters for long recordings on a phone).
 */
class SampleData(
    val sampleRate: Int,
    val left: ShortArray,
    val right: ShortArray? = null,
) {
    val frames: Int get() = left.size
    val isStereo: Boolean get() = right != null
    val durationSeconds: Float get() = if (sampleRate > 0) frames.toFloat() / sampleRate else 0f

    /** Normalised peak overview (0..1) used to draw waveforms; computed once on demand. */
    val overview: FloatArray by lazy(LazyThreadSafetyMode.PUBLICATION) { buildOverview(OVERVIEW_SIZE) }

    private fun buildOverview(buckets: Int): FloatArray {
        val n = frames
        if (n == 0) return FloatArray(0)
        val count = minOf(buckets, n)
        val out = FloatArray(count)
        val r = right
        for (b in 0 until count) {
            val from = (b.toLong() * n / count).toInt()
            val to = max(from + 1, ((b + 1).toLong() * n / count).toInt())
            var peak = 0
            // Sub-sample long buckets: an overview does not need every frame.
            val stride = max(1, (to - from) / 64)
            var i = from
            while (i < to) {
                var v = abs(left[i].toInt())
                if (r != null) v = max(v, abs(r[i].toInt()))
                if (v > peak) peak = v
                i += stride
            }
            out[b] = peak / 32768f
        }
        return out
    }

    companion object {
        const val OVERVIEW_SIZE = 2048
        const val TO_FLOAT = 1f / 32768f
    }
}

/** Thread-safe registry of the samples used by the open project, keyed by file name. */
class SamplePool {
    private val map = ConcurrentHashMap<String, SampleData>()

    /** Bumped on every change so that observers (the engine compiler, the UI) can refresh. */
    @Volatile
    var version = 0
        private set

    operator fun get(key: String?): SampleData? = if (key == null) null else map[key]

    fun put(key: String, data: SampleData) {
        map[key] = data
        version++
    }

    fun remove(key: String) {
        if (map.remove(key) != null) version++
    }

    fun clear() {
        map.clear()
        version++
    }

    fun keys(): Set<String> = map.keys.toSet()
}
