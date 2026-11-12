package jxpix.proger.mps.studio

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import jxpix.proger.mps.engine.TICKS_PER_STEP
import jxpix.proger.mps.model.Note
import jxpix.proger.mps.model.PARAM_PAN
import jxpix.proger.mps.model.PARAM_VOLUME
import jxpix.proger.mps.model.STRIP_DEVICE

enum class Tab { RACK, PIANO, PLAYLIST, MIXER, KEYS }

/** Scroll position and zoom of a note grid (piano roll, playlist). Units are pixels. */
class GridView {
    var scrollX by mutableFloatStateOf(0f)
    var scrollY by mutableFloatStateOf(-1f)

    /** Horizontal zoom; 0 until the view has been laid out for the first time. */
    var pxPerTick by mutableFloatStateOf(0f)
}

/** The choices made in the generator of notes; kept so that opening it again continues where one stopped. */
class GeneratorState {
    /** One of the kinds of [jxpix.proger.mps.music.Generator]: chords, melody, bass, arpeggio. */
    var kind by mutableIntStateOf(0)

    /** -1 = the chords heard in the pattern, otherwise the number of a progression of the key. */
    var progression by mutableIntStateOf(-1)

    /** How long a chord of a progression lasts: 0 = half a bar, 1 = a bar, 2 = two bars. */
    var chordLength by mutableIntStateOf(1)
    var chordStyle by mutableIntStateOf(0)
    var bassStyle by mutableIntStateOf(2)
    var density by mutableIntStateOf(1)
    var sevenths by mutableStateOf(false)

    /** An arpeggio is made of the notes the channel holds (true) or of the chords of the song. */
    var fromNotes by mutableStateOf(true)
    var order by mutableIntStateOf(0)
    var step by mutableIntStateOf(1)
    var octaves by mutableIntStateOf(1)

    /** Changes with every "another one". */
    var seed by mutableLongStateOf(1L)
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

    /** Automation lane under the piano roll: shown or not, and the knob it edits (device uid + parameter id). */
    var rollAutomation by mutableStateOf(false)
    var laneDevice by mutableIntStateOf(STRIP_DEVICE)
    var laneParam by mutableStateOf(PARAM_VOLUME)

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

    /**
     * Automation lane under the playlist - the curves of audio tracks, which run along the song:
     * shown or not, the audio channel it belongs to and the knob it edits.
     */
    var playlistAutomation by mutableStateOf(false)
    var songLaneChannel by mutableIntStateOf(0)
    var songLaneDevice by mutableIntStateOf(STRIP_DEVICE)
    var songLaneParam by mutableStateOf(PARAM_PAN)

    // arrangement by template: the shape chosen last and whether the short version was wanted
    var arrangeTemplate by mutableIntStateOf(0)
    var arrangeShort by mutableStateOf(false)

    val generator = GeneratorState()

    /** Ask the playlist to scroll this track into view (-1 = nothing to do); the playlist resets it. */
    var revealTrack by mutableIntStateOf(-1)

    // keys
    /** null = decide from the type of the selected channel. */
    var padsMode by mutableStateOf<Boolean?>(null)
    var octave by mutableIntStateOf(3)

    fun reset() {
        tab = Tab.RACK
        editor = null
        editorTab = 0
        rackPage = 0
        roll.scrollX = 0f
        roll.scrollY = -1f
        roll.pxPerTick = 0f
        rollSelection = emptySet()
        rollAutomation = false
        laneDevice = STRIP_DEVICE
        laneParam = PARAM_VOLUME
        playlist.scrollX = 0f
        playlist.scrollY = -1f
        playlist.pxPerTick = 0f
        selectedClip = 0
        playlistAutomation = false
        songLaneChannel = 0
        songLaneDevice = STRIP_DEVICE
        songLaneParam = PARAM_PAN
        revealTrack = -1
        padsMode = null
    }
}
