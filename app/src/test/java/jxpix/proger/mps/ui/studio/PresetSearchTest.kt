package jxpix.proger.mps.ui.studio

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PresetSearchTest {
    @Test
    fun blankSearchKeepsEveryPreset() {
        assertTrue(presetMatches("   ", "Soft Pad", "Pads"))
    }

    @Test
    fun nameSearchIgnoresCaseAndSurroundingSpaces() {
        assertTrue(presetMatches("  sOfT pAd  ", "Soft Pad", "Pads"))
    }

    @Test
    fun categorySearchKeepsEveryPresetInThatCategory() {
        assertTrue(presetMatches("pads", "Warm Strings", "Synthwave Pads"))
    }

    @Test
    fun unrelatedSearchDoesNotMatch() {
        assertFalse(presetMatches("brass", "Soft Pad", "Pads"))
    }
}
