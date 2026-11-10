package jxpix.proger.mps

import android.app.Application
import jxpix.proger.mps.studio.Studio

class MpsApp : Application() {
    /** Created on first use and kept for the life of the process, so sound survives screen rotation. */
    val studio: Studio by lazy { Studio(this) }
}
