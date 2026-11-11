package jxpix.proger.mps.model

/**
 * The scales a song can be in. A scale is the set of notes that sound "right" together; with the
 * tonic it makes the key of the song (C major, A minor...).
 *
 * The position of a scale in [steps] is what a project file stores, so new scales are only ever
 * added at the end.
 */
object Scales {
    const val NONE = 0
    const val MAJOR = 1
    const val MINOR = 2

    /** Semitones of each scale, counted from its tonic. Index 0 = no scale: every note belongs. */
    val steps: Array<IntArray> = arrayOf(
        intArrayOf(),
        intArrayOf(0, 2, 4, 5, 7, 9, 11),   // major
        intArrayOf(0, 2, 3, 5, 7, 8, 10),   // natural minor
        intArrayOf(0, 2, 4, 7, 9),          // major pentatonic
        intArrayOf(0, 3, 5, 7, 10),         // minor pentatonic
        intArrayOf(0, 3, 5, 6, 7, 10),      // blues
        intArrayOf(0, 2, 3, 5, 7, 8, 11),   // harmonic minor
        intArrayOf(0, 2, 3, 5, 7, 9, 11),   // melodic minor
        intArrayOf(0, 2, 3, 5, 7, 9, 10),   // dorian
        intArrayOf(0, 1, 3, 5, 7, 8, 10),   // phrygian
        intArrayOf(0, 2, 4, 6, 7, 9, 11),   // lydian
        intArrayOf(0, 2, 4, 5, 7, 9, 10),   // mixolydian
    )

    val count: Int get() = steps.size

    /** Every note of the octave. */
    const val ALL_NOTES = 0xFFF

    val NOTE_NAMES = arrayOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")

    /**
     * The notes of a key as twelve bits: bit 0 = C, bit 1 = C#... 0 when there is no scale,
     * which reads as "nothing is marked".
     */
    fun mask(root: Int, scale: Int): Int {
        if (scale <= 0 || scale >= steps.size) return 0
        val r = ((root % 12) + 12) % 12
        var m = 0
        for (s in steps[scale]) m = m or (1 shl ((r + s) % 12))
        return m
    }

    fun contains(mask: Int, pitch: Int): Boolean = mask and (1 shl (((pitch % 12) + 12) % 12)) != 0

    /** The closest note of the key; of two equally close ones the lower. [pitch] itself when there is no scale. */
    fun nearest(pitch: Int, mask: Int): Int {
        if (mask and ALL_NOTES == 0 || contains(mask, pitch)) return pitch
        for (d in 1..6) {
            if (contains(mask, pitch - d)) return pitch - d
            if (contains(mask, pitch + d)) return pitch + d
        }
        return pitch
    }
}
