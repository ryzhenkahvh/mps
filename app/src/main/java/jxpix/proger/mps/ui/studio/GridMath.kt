package jxpix.proger.mps.ui.studio

/** Number of cells belonging to the pattern on a fixed-width rack page. */
internal fun rackValidSteps(totalSteps: Int, firstStep: Int, pageSteps: Int): Int =
    (totalSteps - firstStep).coerceIn(0, pageSteps)

/** Keep the tick under the zoom anchor stationary, unless a content edge is reached. */
internal fun zoomScrollX(scroll: Float, anchor: Float, oldScale: Float, newScale: Float, maxScroll: Float): Float =
    ((scroll + anchor) / oldScale * newScale - anchor).coerceIn(0f, maxScroll)
