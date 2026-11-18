package jxpix.proger.mps.ui.studio

import org.junit.Assert.assertEquals
import org.junit.Test

class GridMathTest {
    @Test
    fun rackKeepsPageWidthButDisablesCellsAfterPatternEnd() {
        assertEquals(32, rackValidSteps(totalSteps = 48, firstStep = 0, pageSteps = 32))
        assertEquals(16, rackValidSteps(totalSteps = 48, firstStep = 32, pageSteps = 32))
        assertEquals(0, rackValidSteps(totalSteps = 48, firstStep = 64, pageSteps = 32))
    }

    @Test
    fun zoomKeepsAnchorAndClampsAtContentEdges() {
        assertEquals(250f, zoomScrollX(100f, 50f, 1f, 2f, 1000f), 0.001f)
        assertEquals(0f, zoomScrollX(0f, 100f, 2f, 1f, 1000f), 0.001f)
        assertEquals(300f, zoomScrollX(250f, 100f, 1f, 2f, 300f), 0.001f)
        assertEquals(200f, zoomScrollX(400f, 0f, 2f, 1f, 200f), 0.001f)
    }
}
