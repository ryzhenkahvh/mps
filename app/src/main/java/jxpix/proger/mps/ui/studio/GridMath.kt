package jxpix.proger.mps.ui.studio

import jxpix.proger.mps.model.Note
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/** Number of cells belonging to the pattern on a fixed-width rack page. */
internal fun rackValidSteps(totalSteps: Int, firstStep: Int, pageSteps: Int): Int =
    (totalSteps - firstStep).coerceIn(0, pageSteps)

/** Keep the tick under the zoom anchor stationary, unless a content edge is reached. */
internal fun zoomScrollX(scroll: Float, anchor: Float, oldScale: Float, newScale: Float, maxScroll: Float): Float =
    ((scroll + anchor) / oldScale * newScale - anchor).coerceIn(0f, maxScroll)

/** Notes outside a shortened pattern are retained untouched for when it is lengthened again. */
internal fun quantizePatternStart(start: Int, snap: Int, patternTicks: Int): Int {
    if (start !in 0 until patternTicks) return start
    val lastGrid = (patternTicks - 1) / snap * snap
    return ((start.toDouble() / snap).roundToInt() * snap).coerceIn(0, lastGrid)
}

/** Bound a group move without changing the relative, possibly off-grid positions of its notes. */
internal fun boundNoteMove(delta: Int, minStart: Int, maxStart: Int, patternTicks: Int, snap: Int): Int {
    val lowest = -Math.floorDiv(minStart, snap) * snap
    val highest = Math.floorDiv(patternTicks - 1 - maxStart, snap) * snap
    return if (lowest <= highest) delta.coerceIn(lowest, highest) else 0
}

/** Duration stays positive and cannot overflow its end position. */
internal fun adjustGridLength(length: Int, delta: Int, maxLength: Int): Int =
    (length.toLong() + delta).coerceIn(1L, maxLength.coerceAtLeast(1).toLong()).toInt()

/** A cut lands on the closest grid line; snap = 1 represents magnet off. */
internal fun gridCutTick(tick: Float, snap: Int): Int =
    ((tick.toDouble() / snap).roundToLong() * snap).coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()

/** Splitting preserves pitch, velocity and pan, and does nothing on either edge. */
internal fun splitGridNote(note: Note, at: Int): Pair<Note, Note>? {
    if (at <= note.start || at >= note.end) return null
    return note.copy(length = at - note.start) to note.copy(start = at, length = note.end - at)
}
