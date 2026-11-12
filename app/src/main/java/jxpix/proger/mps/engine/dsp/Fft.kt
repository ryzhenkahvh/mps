package jxpix.proger.mps.engine.dsp

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Fourier transform of [size] samples (a power of two), in place. It serves the pictures of the
 * devices and the spectrum drawn behind them; the audio thread never calls it.
 */
class Fft(val size: Int) {
    private val cosTable = FloatArray(size / 2)
    private val sinTable = FloatArray(size / 2)
    private val reversed = IntArray(size)

    init {
        require(size >= 2 && size and (size - 1) == 0) { "the size has to be a power of two" }
        for (i in 0 until size / 2) {
            cosTable[i] = cos(2.0 * PI * i / size).toFloat()
            sinTable[i] = sin(2.0 * PI * i / size).toFloat()
        }
        var bits = 0
        while (1 shl bits < size) bits++
        for (i in 0 until size) reversed[i] = Integer.reverse(i) ushr (32 - bits)
    }

    /** Replaces the signal in [re] and [im] with its spectrum. */
    fun transform(re: FloatArray, im: FloatArray) {
        val n = size
        for (i in 0 until n) {
            val j = reversed[i]
            if (j > i) {
                val tr = re[i]
                re[i] = re[j]
                re[j] = tr
                val ti = im[i]
                im[i] = im[j]
                im[j] = ti
            }
        }
        var half = 1
        while (half < n) {
            val step = n / (half * 2)
            var start = 0
            while (start < n) {
                var k = 0
                for (i in start until start + half) {
                    val c = cosTable[k]
                    val s = sinTable[k]
                    val j = i + half
                    val xr = re[j] * c + im[j] * s
                    val xi = im[j] * c - re[j] * s
                    re[j] = re[i] - xr
                    im[j] = im[i] - xi
                    re[i] += xr
                    im[i] += xi
                    k += step
                }
                start += half * 2
            }
            half *= 2
        }
    }
}
