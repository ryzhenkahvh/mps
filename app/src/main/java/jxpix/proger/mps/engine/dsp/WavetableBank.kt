package jxpix.proger.mps.engine.dsp

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Small built-in, band-limited wavetable library for the polyphonic synth.
 * Tables are prepared once during device-registry initialization, never on the audio thread.
 * Each family has 32 morph frames and eight mip levels, so high notes do not replay harmonics
 * that would fold back above Nyquist.
 */
object WavetableBank {
    const val TABLE_COUNT = 4
    const val FRAME_COUNT = 32
    const val SIZE = 512

    private val mipHarmonics = intArrayOf(128, 64, 32, 16, 8, 4, 2, 1)
    private val data = build()

    /** Call while the device catalogue is being initialized, before rendering can start. */
    fun prime() {
        data.size
    }

    /** A mip level whose highest partial stays below 0.45 of the sample rate. */
    fun mipFor(increment: Float): Int {
        val safeHarmonics = 0.45f / increment.coerceAtLeast(1e-9f)
        for (i in mipHarmonics.indices) if (safeHarmonics >= mipHarmonics[i]) return i
        return mipHarmonics.lastIndex
    }

    /** Read one sample, interpolating both the cyclic table and adjacent morph frames. */
    fun sample(table: Int, position: Float, phase: Float, mip: Int = 0): Float {
        val family = table.coerceIn(0, TABLE_COUNT - 1)
        val level = mip.coerceIn(0, mipHarmonics.lastIndex)
        val framePosition = position.coerceIn(0f, 1f) * (FRAME_COUNT - 1)
        val frameA = framePosition.toInt()
        val frameB = (frameA + 1).coerceAtMost(FRAME_COUNT - 1)
        val frameMix = framePosition - frameA

        val samplePosition = phase.coerceIn(0f, 1f) * SIZE
        val index = samplePosition.toInt().coerceIn(0, SIZE - 1)
        val next = if (index + 1 == SIZE) 0 else index + 1
        val sampleMix = samplePosition - index
        val startA = offset(family, level, frameA)
        val startB = offset(family, level, frameB)
        val a0 = data[startA + index]
        val a1 = data[startA + next]
        val b0 = data[startB + index]
        val b1 = data[startB + next]
        val a = a0 + (a1 - a0) * sampleMix
        val b = b0 + (b1 - b0) * sampleMix
        return a + (b - a) * frameMix
    }

    private fun offset(table: Int, mip: Int, frame: Int): Int =
        ((table * mipHarmonics.size + mip) * FRAME_COUNT + frame) * SIZE

    private fun build(): FloatArray {
        val maxHarmonic = mipHarmonics[0]
        val basis = Array(maxHarmonic) { harmonic ->
            FloatArray(SIZE) { sample ->
                sin(2.0 * Math.PI * (harmonic + 1) * sample / SIZE).toFloat()
            }
        }
        val out = FloatArray(TABLE_COUNT * mipHarmonics.size * FRAME_COUNT * SIZE)
        for (table in 0 until TABLE_COUNT) {
            for (mip in mipHarmonics.indices) {
                val partials = mipHarmonics[mip]
                for (frame in 0 until FRAME_COUNT) {
                    val position = frame / (FRAME_COUNT - 1f)
                    val start = offset(table, mip, frame)
                    for (harmonic in 1..partials) {
                        val amplitude = amplitude(table, position, harmonic)
                        if (abs(amplitude) < 1e-6f) continue
                        val wave = basis[harmonic - 1]
                        for (sample in 0 until SIZE) out[start + sample] += wave[sample] * amplitude
                    }
                    var peak = 0f
                    for (sample in 0 until SIZE) peak = maxOf(peak, abs(out[start + sample]))
                    if (peak > 1e-8f) {
                        val scale = 0.9f / peak
                        for (sample in 0 until SIZE) out[start + sample] *= scale
                    }
                }
            }
        }
        return out
    }

    /** Four intentionally different spectral journeys, each smoothly swept by the position knob. */
    private fun amplitude(table: Int, position: Float, harmonic: Int): Float {
        val h = harmonic.toFloat()
        return when (table) {
            // A sine grows into a progressively brighter saw-like spectrum.
            0 -> {
                val cutoff = 1f + position * 127f
                val fade = (cutoff + 1f - h).coerceIn(0f, 1f)
                if (h <= cutoff + 1f) fade / h else 0f
            }
            // A narrow spectral peak moves upward through the harmonic series.
            1 -> {
                val center = 2f + position * 38f
                val width = 1.4f + position * 1.5f
                val distance = (h - center) / width
                0.18f / h + exp(-0.5f * distance * distance) / sqrt(h)
            }
            // Two vowel-like formants move at different rates.
            2 -> {
                val first = (h - (2f + position * 6f)) / 1.6f
                val second = (h - (8f + position * 22f)) / 3f
                0.14f / h + 0.85f * exp(-0.5f * first * first) / sqrt(h) +
                    0.62f * exp(-0.5f * second * second) / sqrt(h)
            }
            // A shifting comb of strong and weak partials gives a more metallic, digital tone.
            else -> {
                val tilt = 1.35f - 0.65f * position
                val comb = if (harmonic % 2 == 0) {
                    0.15f + 0.85f * (0.5f + 0.5f * cos(h * (0.18f + position * 0.23f)))
                } else {
                    0.15f + 0.85f * (0.5f - 0.5f * cos(h * (0.18f + position * 0.23f)))
                }
                comb / h.pow(tilt)
            }
        }
    }
}
