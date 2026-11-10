package jxpix.proger.mps.studio

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import jxpix.proger.mps.engine.TICKS_PER_STEP
import jxpix.proger.mps.model.Note

enum class Tab { RACK, PIANO, PLAYLIST, MIXER, KEYS }

/** Scroll position and zoom of a note grid (piano roll, playlist). Units are pixels. */
class GridView {
    var scrollX by mutableFloatStateOf(0f)
    var scrollY by mutableFloatStateOf(-1f)

    /** Horizontal zoom; 0 until the view has been laid out for the first time. */
    var pxPerTick by mutableFloatStateOf(0f)
}

/**
 * What the screens remember between visits: the open tab, zoom levels, tool settings.
 * Kept outside the composables so that rotating the phone or switching tabs loses nothing.
 */
class UiState {
    var tab by mutableStateOf(Tab.RACK)

    /** Channel whose sound editor is open ([jxpix.proger.mps.model.MASTER_ID] = master effects), or null. */
    var editor by mutableStateOf<Int?>(null)
    var editorTab by mutableIntStateOf(0)

    // channel rack
    var rackPage by mutableIntStateOf(0)

    // piano roll
    val roll = GridView()
    var rollSnap by mutableIntStateOf(TICKS_PER_STEP)
    var rollNoteLength by mutableIntStateOf(TICKS_PER_STEP)
    var rollGhosts by mutableStateOf(true)
    var rollSelection by mutableStateOf<Set<Note>>(emptySet())

    // geometry of the piano roll, written by its layout and read by its (longer living) gesture code
    var rollGridW = 1f
    var rollMaxScrollY = 0f
    var rollMinPx = 0.01f

    // playlist
    val playlist = GridView()
    /** 0 = bar, 1 = beat, 2 = step. */
    var playlistSnap by mutableIntStateOf(0)
    var playlistGridW = 1f
    var playlistGridH = 1f
    var selectedClip by mutableIntStateOf(0)

    // keys
    /** null = decide from the type of the selected channel. */
    var padsMode by mutableStateOf<Boolean?>(null)
    var octave by mutableIntStateOf(3)
    var scale by mutableIntStateOf(0)
    var scaleRoot by mutableIntStateOf(0)

    fun reset() {
        tab = Tab.RACK
        editor = null
        editorTab = 0
        rackPage = 0
        roll.scrollX = 0f
        roll.scrollY = -1f
        roll.pxPerTick = 0f
        rollSelection = emptySet()
        playlist.scrollX = 0f
        playlist.scrollY = -1f
        playlist.pxPerTick = 0f
        selectedClip = 0
        padsMode = null
    }
}
