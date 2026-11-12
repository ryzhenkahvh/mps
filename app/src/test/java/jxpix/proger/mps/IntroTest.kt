package jxpix.proger.mps

import jxpix.proger.mps.ui.Intro
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** When the logo video is shown at the start of the app. */
class IntroTest {

    @Before
    fun freshStart() {
        Intro.reset()
    }

    @Test
    fun shownOncePerStartOfTheApp() {
        Intro.decide(enabled = true, fresh = true, fromLauncher = true, muted = false)
        assertTrue(Intro.pending)
        assertFalse(Intro.muted)

        // the screen is rebuilt while the video plays (the phone is turned): it is still owed, and nothing is decided anew
        Intro.positionMs = 3200
        Intro.decide(enabled = false, fresh = false, fromLauncher = false, muted = true)
        assertTrue(Intro.pending)
        assertFalse(Intro.muted)
        assertEquals(3200, Intro.positionMs)

        Intro.done()
        assertFalse(Intro.pending)
        assertEquals(0, Intro.positionMs)

        // rebuilt again later (another language is chosen): no second showing
        Intro.decide(enabled = true, fresh = true, fromLauncher = true, muted = false)
        assertFalse(Intro.pending)
    }

    @Test
    fun anAppThatWasClosedStartsWithTheVideoAgain() {
        Intro.decide(enabled = true, fresh = true, fromLauncher = true, muted = false)
        Intro.done()
        // Back on the first screen closed the app, Android kept its process, and the user opens it again
        Intro.closed()
        Intro.decide(enabled = true, fresh = true, fromLauncher = true, muted = true)
        assertTrue(Intro.pending)
        assertTrue("and how loud it is, is asked anew", Intro.muted)

        // closed in the middle of the video: nothing of it is left for the next start
        Intro.positionMs = 5100
        Intro.closed()
        assertFalse(Intro.pending)
        assertEquals(0, Intro.positionMs)
    }

    @Test
    fun notShownWhenSwitchedOffRestoredOrOpenedByALink() {
        Intro.decide(enabled = false, fresh = true, fromLauncher = true, muted = false)
        assertFalse("switched off in the settings", Intro.pending)

        Intro.reset()
        Intro.decide(enabled = true, fresh = false, fromLauncher = true, muted = false)
        assertFalse("Android brings the app back after closing it in the background", Intro.pending)

        Intro.reset()
        Intro.decide(enabled = true, fresh = true, fromLauncher = false, muted = false)
        assertFalse("a link opened the app", Intro.pending)

        Intro.reset()
        Intro.decide(enabled = true, fresh = true, fromLauncher = true, muted = true)
        assertTrue("a silenced phone still sees the picture", Intro.pending)
        assertTrue(Intro.muted)
    }

    @Test
    fun silentWhenThePhoneCannotBeAsked() {
        assertTrue(Intro.quiet(null))
    }
}
