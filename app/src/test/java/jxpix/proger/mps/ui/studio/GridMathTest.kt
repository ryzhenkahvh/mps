package jxpix.proger.mps.ui.studio

import jxpix.proger.mps.model.Clip
import jxpix.proger.mps.model.Note
import jxpix.proger.mps.model.Project
import jxpix.proger.mps.model.splitClip
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GridMathTest {
    @Test
    fun rackKeepsPageWidthButDisablesCellsAfterPatternEnd() {
        assertEquals(32, rackValidSteps(totalSteps = 48, firstStep = 0, pageSteps = 32))
        assertEquals(16, rackValidSteps(totalSteps = 48, firstStep = 32, pageSteps = 32))
        assertEquals(0, rackValidSteps(totalSteps = 48, firstStep = 64, pageSteps = 32))
    }

    @Test
    fun quantizeRespectsPatternEndAndPreservesHiddenNotes() {
        assertEquals(120, quantizePatternStart(70, 120, 1920))
        assertEquals(0, quantizePatternStart(59, 120, 1920))
        assertEquals(1800, quantizePatternStart(1919, 120, 1920))
        assertEquals(60, quantizePatternStart(99, 60, 100))
        assertEquals(0, quantizePatternStart(19, 120, 20))
        assertEquals(1920, quantizePatternStart(1920, 120, 1920))
        assertEquals(3840, quantizePatternStart(3840, 120, 1920))
        assertEquals(-1, quantizePatternStart(-1, 120, 1920))
    }

    @Test
    fun groupMoveStaysInPatternWithoutChangingRelativeTiming() {
        assertEquals(-120, boundNoteMove(-480, 130, 710, 960, 120))
        assertEquals(240, boundNoteMove(480, 130, 710, 960, 120))
        assertEquals(0, boundNoteMove(120, 0, 960, 960, 120))
        assertEquals(-120, boundNoteMove(-120, 240, 1000, 960, 120))
        assertEquals(0, boundNoteMove(-120, 10, 1000, 960, 120))
    }

    @Test
    fun knifeUsesNearestGridOrSingleTickWithMagnetOff() {
        assertEquals(120, gridCutTick(170f, 120))
        assertEquals(240, gridCutTick(185f, 120))
        assertEquals(171, gridCutTick(170.6f, 1))
        assertEquals(0, gridCutTick(-2f, 1))
        val note = Note(start = 100, length = 200, pitch = 64, velocity = 0.6f, pan = -0.3f)
        assertNull(splitGridNote(note, 100))
        assertNull(splitGridNote(note, 300))
        val parts = splitGridNote(note, 180)!!
        assertEquals(note.copy(length = 80), parts.first)
        assertEquals(note.copy(start = 180, length = 120), parts.second)
    }

    @Test
    fun audioCutPreservesSourcePositionAndProperties() {
        val clip = Clip(id = 1, track = 0, start = 100, length = 960, channelId = 2,
            sample = "take.wav", offset = 0.75f, gain = 0.6f, pan = -0.3f)
        val project = Project(bpm = 120f, clips = listOf(clip), nextId = 10)
        val cut = project.splitClip(1, 580)
        assertEquals(2, cut.clips.size)
        assertEquals(clip.copy(length = 480), cut.clips[0])
        assertEquals(clip.copy(id = 10, start = 580, length = 480, offset = 1.25f), cut.clips[1])
        assertEquals(project, project.splitClip(1, 100))
        assertEquals(project, project.splitClip(1, 1060))
    }

    @Test
    fun preciseLengthEditsStayPositiveAndRespectLimits() {
        assertEquals(1, adjustGridLength(3, -120, 1000))
        assertEquals(121, adjustGridLength(120, 1, 1000))
        assertEquals(200, adjustGridLength(199, 120, 200))
        assertEquals(Int.MAX_VALUE, adjustGridLength(Int.MAX_VALUE, 120, Int.MAX_VALUE))
    }

    @Test
    fun zoomKeepsAnchorAndClampsAtContentEdges() {
        assertEquals(250f, zoomScrollX(100f, 50f, 1f, 2f, 1000f), 0.001f)
        assertEquals(0f, zoomScrollX(0f, 100f, 2f, 1f, 1000f), 0.001f)
        assertEquals(300f, zoomScrollX(250f, 100f, 1f, 2f, 300f), 0.001f)
        assertEquals(200f, zoomScrollX(400f, 0f, 2f, 1f, 200f), 0.001f)
    }
}
