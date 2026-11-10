package jxpix.proger.mps.studio

import jxpix.proger.mps.model.usedSamples
import jxpix.proger.mps.model.stemOf
import jxpix.proger.mps.model.audibleChannels
import jxpix.proger.mps.audio.Mp3Encoder
import jxpix.proger.mps.audio.ExportFormat
import kotlinx.coroutines.CoroutineStart
import java.util.Locale
import java.io.OutputStream
import java.io.InputStream
import java.io.IOException
import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import jxpix.proger.mps.R
import jxpix.proger.mps.audio.AudioDecoder
import jxpix.proger.mps.audio.AudioExporter
import jxpix.proger.mps.audio.AudioOutput
import jxpix.proger.mps.audio.AudioRenderer
import jxpix.proger.mps.audio.MidiInput
import jxpix.proger.mps.audio.Recorder
import jxpix.proger.mps.data.Prefs
import jxpix.proger.mps.data.ProjectInfo
import jxpix.proger.mps.data.ProjectStore
import jxpix.proger.mps.engine.Devices
import jxpix.proger.mps.engine.Engine
import jxpix.proger.mps.engine.OfflineRenderer
import jxpix.proger.mps.engine.PPQ
import jxpix.proger.mps.engine.SampleData
import jxpix.proger.mps.engine.SamplePool
import jxpix.proger.mps.engine.TICKS_PER_STEP
import jxpix.proger.mps.model.Clip
import jxpix.proger.mps.model.MAX_BPM
import jxpix.proger.mps.model.MIN_BPM
import jxpix.proger.mps.model.Note
import jxpix.proger.mps.model.Project
import jxpix.proger.mps.model.ProjectFactory
import jxpix.proger.mps.model.addAudioClip
import jxpix.proger.mps.model.addChannel
import jxpix.proger.mps.model.addPatternClip
import jxpix.proger.mps.model.freeTrack
import jxpix.proger.mps.model.setNotes
import jxpix.proger.mps.model.setTrackCount
import jxpix.proger.mps.model.updateDevice
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.max
import kotlin.math.roundToInt

enum class RecordMode { NONE, NOTES, AUDIO }

/** What the user asked for in the export sheet. */
class ExportOptions(
    val format: ExportFormat = ExportFormat.WAV,
    /** One WAV file per channel instead of the finished mix. */
    val stems: Boolean = false,
    val mp3Kbps: Int = 192,
)

/**
 * @param name the file name - or, for separate tracks, the name of the folder they are in.
 */
class ExportResult(val uris: List<Uri>, val name: String, val mime: String, val seconds: Double, val stems: Boolean = false)

/** Level meter of one mixer strip; updated once per frame, read while drawing. */
class Meter {
    var level = 0f
    var hold = 0f
    internal var holdFrames = 0
}

/**
 * The single source of truth of the app: the open project, selection, transport and undo history.
 *
 * It lives as long as the process (see MpsApp), so sound keeps running across screen rotations.
 * All methods must be called on the main thread unless stated otherwise.
 */
class Studio(context: Context) {

    private val app = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val main = Handler(Looper.getMainLooper())

    val prefs = Prefs(app)
    val store = ProjectStore(app)
    val samples = SamplePool()
    val output = AudioOutput(app)
    val engine = Engine(output.sampleRate)
    val recorder = Recorder(app)
    val midi = MidiInput(app) { on, pitch, velocity -> onMidiNote(on, pitch, velocity) }

    /** Text lookup in the language of the UI; replaced by the activity. */
    var strings: (Int) -> String = { app.getString(it) }

    /** View state of the screens (open tab, zoom...). */
    val ui = UiState()

    // ------------------------------------------------------------------------ observable state

    var project by mutableStateOf(Project())
        private set
    var projectId by mutableStateOf<String?>(null)
        private set
    var projects by mutableStateOf<List<ProjectInfo>>(emptyList())
        private set

    /** Pattern shown in the channel rack / piano roll. */
    var patternId by mutableIntStateOf(0)
        private set

    /** Channel shown in the piano roll, keyboard and instrument editor. */
    var channelId by mutableIntStateOf(0)
        private set

    /** false: loop the current pattern. true: play the whole playlist. */
    var songMode by mutableStateOf(false)
        private set

    var playing by mutableStateOf(false)
        private set
    var recordMode by mutableStateOf(RecordMode.NONE)
        private set

    /** True while the metronome counts in before a recording. */
    var countingIn by mutableStateOf(false)
        private set

    var canUndo by mutableStateOf(false)
        private set
    var canRedo by mutableStateOf(false)
        private set

    /** One-line message for the snackbar. */
    var message by mutableStateOf<String?>(null)

    /** Non-null while something long is running (import, export). 0..1, or negative when unknown. */
    var busy by mutableStateOf<Float?>(null)
        private set
    var busyText by mutableStateOf("")
        private set
    var exportResult by mutableStateOf<ExportResult?>(null)

    /** Number of samples still being loaded for the open project. */
    var loadingSamples by mutableIntStateOf(0)
        private set

    /** Bumped whenever the sample pool changes, so waveform views refresh. */
    var samplesVersion by mutableIntStateOf(0)
        private set

    // ---- values that change every frame: read them only inside draw lambdas ----

    /** Play head in ticks (song position in song mode, position inside the pattern otherwise). */
    val playhead = mutableDoubleStateOf(0.0)

    /** Incremented on every animation frame while there is something to animate. */
    val frame = mutableLongStateOf(0L)
    val inputLevel = mutableFloatStateOf(0f)
    val masterMeterL = Meter()
    val masterMeterR = Meter()
    private val meters = HashMap<Int, Meter>()

    fun meter(channelId: Int): Meter = meters.getOrPut(channelId) { Meter() }

    /** Where playback starts, in ticks. */
    var cursor by mutableIntStateOf(0)
        private set

    val currentPattern get() = project.pattern(patternId) ?: project.patterns.firstOrNull()
    val currentChannel get() = project.channel(channelId) ?: project.channels.firstOrNull()

    // ------------------------------------------------------------------------ lifecycle

    init {
        output.renderer = AudioRenderer { out, frames -> engine.render(out, frames) }
        output.profile = prefs.latency
        output.onFocusLost = { main.post { if (playing) pause() } }
        engine.metronome = prefs.metronome
        refreshProjects()
    }

    /** Call when the app comes to the foreground. */
    fun startAudio() {
        output.profile = prefs.latency
        output.start()
        midi.start()
    }

    /** Call when the app goes to the background: stops the sound and saves. */
    fun stopAudio() {
        if (recordMode != RecordMode.NONE) stopRecording()
        if (playing) pause()
        engine.panic()
        midi.stop()
        output.stop()
        saveNow()
    }

    fun refreshProjects() {
        scope.launch {
            projects = withContext(Dispatchers.IO) { store.list() }
        }
    }

    // ------------------------------------------------------------------------ projects

    fun createProject(templateId: String, onReady: () -> Unit = {}) {
        val template = ProjectFactory.template(templateId) ?: return
        val built = template.build(strings)
        val name = if (templateId == "empty") uniqueName(built.name) else built.name
        val p = built.copy(name = name)
        scope.launch {
            val id = withContext(Dispatchers.IO) { store.create(p) }
            setOpenProject(id, p)
            refreshProjects()
            onReady()
        }
    }

    private fun uniqueName(base: String): String {
        val names = projects.map { it.name }.toSet()
        if (base !in names) return base
        var n = 2
        while ("$base $n" in names) n++
        return "$base $n"
    }

    fun openProject(id: String, onReady: (Boolean) -> Unit = {}) {
        scope.launch {
            val p = withContext(Dispatchers.IO) { store.load(id) }
            if (p == null) {
                message = strings(R.string.msg_open_failed)
                onReady(false)
            } else {
                setOpenProject(id, p)
                onReady(true)
            }
        }
    }

    private fun setOpenProject(id: String, p: Project) {
        stopTransport()
        engine.panic()
        samples.clear()
        undoStack.clear()
        redoStack.clear()
        lastEditKey = null
        updateUndoState()
        projectId = id
        project = p
        prefs.updateLastProject(id)
        patternId = p.patterns.firstOrNull()?.id ?: 0
        channelId = p.channels.firstOrNull()?.id ?: 0
        applySongMode(p.clips.isNotEmpty())
        ui.reset()
        if (p.clips.isNotEmpty()) ui.tab = Tab.PLAYLIST
        cursor = 0
        playhead.doubleValue = 0.0
        dirty = false
        syncEngine()
        loadSamples(id, p)
    }

    fun closeProject() {
        if (recordMode != RecordMode.NONE) stopRecording()
        stopTransport()
        engine.panic()
        saveNow()
        val id = projectId
        if (id != null) {
            val used = project.usedSamples()
            // files that only the undo history still points to are not needed any more
            scope.launch(Dispatchers.IO) { store.pruneSamples(id, used) }
        }
        projectId = null
        refreshProjects()
    }

    fun deleteProject(id: String) {
        if (id == projectId) projectId = null
        if (prefs.lastProject == id) prefs.updateLastProject(null)
        scope.launch {
            withContext(Dispatchers.IO) { store.delete(id) }
            refreshProjects()
        }
    }

    fun duplicateProject(id: String, newName: String) {
        scope.launch {
            withContext(Dispatchers.IO) { store.duplicate(id, newName) }
            refreshProjects()
        }
    }

    fun renameProject(id: String, name: String) {
        if (id == projectId) {
            edit { it.copy(name = name) }
            saveNow()
            refreshProjects()
        } else scope.launch {
            withContext(Dispatchers.IO) { store.rename(id, name) }
            refreshProjects()
        }
    }

    private fun loadSamples(id: String, p: Project) {
        val names = p.usedSamples()
        if (names.isEmpty()) return
        loadingSamples = names.size
        for (name in names) {
            scope.launch {
                val data = withContext(Dispatchers.IO) { store.loadSample(id, name) }
                if (projectId == id) {
                    if (data != null) samples.put(name, data)
                    loadingSamples = max(0, loadingSamples - 1)
                    samplesChanged()
                }
            }
        }
    }

    private fun samplesChanged() {
        samplesVersion++
        syncEngine()
    }

    // ------------------------------------------------------------------------ saving

    private var dirty = false
    private var saveJob: Job? = null

    private fun scheduleSave() {
        dirty = true
        saveJob?.cancel()
        saveJob = scope.launch {
            delay(1500)
            val id = projectId ?: return@launch
            val p = project
            dirty = false
            withContext(Dispatchers.IO) { store.save(id, p) }
        }
    }

    /** Writes the project to disk right away (used when leaving the app). */
    fun saveNow() {
        saveJob?.cancel()
        val id = projectId ?: return
        if (!dirty) return
        dirty = false
        store.save(id, project)
    }

    // ------------------------------------------------------------------------ editing + undo

    private val undoStack = ArrayDeque<Project>()
    private val redoStack = ArrayDeque<Project>()
    private var lastEditKey: String? = null
    private var lastEditTime = 0L

    /**
     * Applies a change to the project.
     *
     * Edits sharing the same non-null [key] that follow each other closely (a knob being
     * turned, a note being dragged) are merged into a single undo step.
     */
    fun edit(key: String? = null, mergeWindowMs: Long = 1500, transform: (Project) -> Project) {
        val before = project
        val after = transform(before)
        if (after === before) return
        val now = System.currentTimeMillis()
        val merge = key != null && key == lastEditKey && now - lastEditTime < mergeWindowMs && undoStack.isNotEmpty()
        if (!merge) {
            undoStack.addLast(before)
            if (undoStack.size > MAX_UNDO) undoStack.removeFirst()
        }
        redoStack.clear()
        lastEditKey = key
        lastEditTime = now
        project = after
        afterChange()
    }

    /** Ends the current run of merged edits (call when a drag gesture finishes). */
    fun commit() {
        lastEditKey = null
    }

    fun undo() {
        val prev = undoStack.removeLastOrNull() ?: return
        redoStack.addLast(project)
        project = prev
        lastEditKey = null
        afterChange()
    }

    fun redo() {
        val next = redoStack.removeLastOrNull() ?: return
        undoStack.addLast(project)
        project = next
        lastEditKey = null
        afterChange()
    }

    private fun afterChange() {
        updateUndoState()
        validateSelection()
        syncEngine()
        scheduleSave()
    }

    private fun updateUndoState() {
        canUndo = undoStack.isNotEmpty()
        canRedo = redoStack.isNotEmpty()
    }

    private fun validateSelection() {
        val p = project
        if (p.pattern(patternId) == null) {
            patternId = p.patterns.firstOrNull()?.id ?: 0
            engine.patternId = patternId
        }
        if (p.channel(channelId) == null) channelId = p.channels.firstOrNull()?.id ?: 0
    }

    private fun syncEngine() {
        engine.patternId = patternId
        engine.setProject(project, samples)
    }

    // ------------------------------------------------------------------------ selection

    fun selectPattern(id: Int) {
        if (project.pattern(id) == null) return
        patternId = id
        engine.patternId = id
    }

    fun selectChannel(id: Int) {
        if (project.channel(id) != null) channelId = id
    }

    fun changeSongMode(song: Boolean) {
        if (song == songMode) return
        if (recordMode != RecordMode.NONE) stopRecording()
        stopTransport()
        applySongMode(song)
        cursor = 0
        playhead.doubleValue = 0.0
    }

    private fun applySongMode(song: Boolean) {
        songMode = song
        engine.songMode = song
    }

    // ------------------------------------------------------------------------ transport

    /** How far the sound we hear lags behind the engine position, in ticks. */
    private fun latencyTicks(extraMs: Float = 0f): Double =
        (output.effectiveLatencyMs + extraMs) / 1000.0 * project.bpm * PPQ / 60.0

    fun play() {
        if (playing) return
        if (songMode && project.clips.isEmpty()) {
            message = strings(R.string.msg_playlist_empty)
            return
        }
        // pressing play at the very end starts over
        if (songMode && cursor >= project.songEnd) cursor = 0
        engine.stopAtEnd = true
        engineStarted = false
        engine.play(if (songMode) cursor else 0)
        playing = true
    }

    fun pause() {
        if (!playing && !engine.playing) return
        if (recordMode != RecordMode.NONE) {
            stopRecording()
            return
        }
        val pos = engine.positionTicks
        engine.pause()
        playing = false
        countingIn = false
        if (songMode) {
            cursor = max(0.0, pos - latencyTicks()).toInt()
            playhead.doubleValue = cursor.toDouble()
        }
    }

    fun togglePlay() {
        if (playing) pause() else play()
    }

    /** Stops and jumps back to the beginning. Pressed again while stopped, it also cuts echo and reverb tails. */
    fun stop() {
        val idle = !playing && recordMode == RecordMode.NONE
        if (recordMode != RecordMode.NONE) stopRecording()
        stopTransport()
        if (idle) engine.panic()
        cursor = 0
        playhead.doubleValue = 0.0
    }

    private fun stopTransport() {
        engine.stop(0)
        playing = false
        countingIn = false
    }

    /** Moves the play head (song mode). */
    fun seek(tick: Int) {
        val t = tick.coerceAtLeast(0)
        cursor = t
        playhead.doubleValue = t.toDouble()
        if (playing && songMode) engine.seek(t)
    }

    fun changeMetronome(on: Boolean) {
        prefs.updateMetronome(on)
        engine.metronome = on
    }

    /** Called from the UI on every animation frame. Returns true while more frames are needed. */
    fun onFrame(): Boolean {
        val enginePlaying = engine.playing
        var animating = false

        if (playing) {
            animating = true
            val raw = engine.positionTicks
            if (raw < 0) {
                countingIn = true
                playhead.doubleValue = raw
            } else {
                if (countingIn) countingIn = false
                var pos = raw - latencyTicks()
                if (!songMode) {
                    val len = currentPattern?.let { project.patternLength(it) }?.toDouble() ?: 1.0
                    pos = ((pos % len) + len) % len
                } else if (pos < cursor.toDouble() && raw >= cursor) {
                    pos = cursor.toDouble()
                }
                playhead.doubleValue = pos
            }
            // the engine stops by itself at the end of the song
            if (enginePlaying) {
                engineStarted = true
            } else if (engineStarted) {
                engineStarted = false
                playing = false
                countingIn = false
                if (recordMode != RecordMode.NONE) stopRecording()
                cursor = 0
                playhead.doubleValue = 0.0
            }
        }

        // meters
        var loud = false
        val p = project
        for (ch in p.channels) {
            val m = meter(ch.id)
            if (decay(m, engine.takeChannelPeak(ch.id))) loud = true
        }
        engine.takeMasterPeaks(peakTmp)
        if (decay(masterMeterL, peakTmp[0])) loud = true
        if (decay(masterMeterR, peakTmp[1])) loud = true
        if (recorder.recording) {
            val lv = recorder.level
            recorder.level = 0f
            inputLevel.floatValue = max(lv, inputLevel.floatValue * 0.9f)
            animating = true
        } else if (inputLevel.floatValue != 0f) inputLevel.floatValue = 0f

        if (animating || loud) {
            frame.longValue++
            return true
        }
        return false
    }

    private val peakTmp = FloatArray(2)

    /** Set once the audio thread has picked up the last play command. */
    private var engineStarted = false

    private fun decay(m: Meter, peak: Float): Boolean {
        m.level = if (peak >= m.level) peak else m.level * 0.86f
        if (m.level < 0.0005f) m.level = 0f
        if (peak >= m.hold) {
            m.hold = peak
            m.holdFrames = 45
        } else if (m.holdFrames > 0) {
            m.holdFrames--
        } else {
            m.hold *= 0.92f
            if (m.hold < 0.0005f) m.hold = 0f
        }
        return m.level > 0f || m.hold > 0f
    }

    // ------------------------------------------------------------------------ live playing

    private class Held(val channelId: Int, val pitch: Int, val start: Int, val velocity: Float, val patternId: Int)

    private val held = ArrayList<Held>()
    private var takeId = 0

    /** A key or pad was pressed. */
    fun noteOn(channelId: Int, pitch: Int, velocity: Float = 0.8f) {
        engine.noteOn(channelId, pitch, velocity)
        if (recordMode == RecordMode.NOTES && playing && !songMode) {
            val pos = engine.positionTicks
            if (pos >= 0) {
                val pattern = currentPattern ?: return
                val len = project.patternLength(pattern)
                val t = (((pos - latencyTicks(TOUCH_LATENCY_MS)) % len) + len) % len
                held += Held(channelId, pitch, t.toInt(), velocity, pattern.id)
            }
        }
    }

    /** A key or pad was released. */
    fun noteOff(channelId: Int, pitch: Int) {
        engine.noteOff(channelId, pitch)
        val i = held.indexOfFirst { it.channelId == channelId && it.pitch == pitch }
        if (i >= 0) {
            val h = held.removeAt(i)
            finishRecordedNote(h)
        }
    }

    private fun finishRecordedNote(h: Held, positionTicks: Double = engine.positionTicks) {
        val pattern = project.pattern(h.patternId) ?: return
        val len = project.patternLength(pattern)
        val now = (((positionTicks - latencyTicks(TOUCH_LATENCY_MS)) % len) + len) % len
        var length = (now.toInt() - h.start + len) % len
        var start = h.start
        if (prefs.quantize) {
            start = ((start + TICKS_PER_STEP / 2) / TICKS_PER_STEP * TICKS_PER_STEP) % len
            length = max(TICKS_PER_STEP, ((length + TICKS_PER_STEP / 2) / TICKS_PER_STEP) * TICKS_PER_STEP)
        }
        val drum = project.channel(h.channelId)?.isDrum == true
        if (drum) length = TICKS_PER_STEP
        length = length.coerceIn(TICKS_PER_STEP / 4, len)
        val note = Note(start, length, h.pitch, h.velocity)
        // the whole take is one undo step, however long it lasts
        edit(key = "take$takeId", mergeWindowMs = Long.MAX_VALUE) { p ->
            val existing = p.pattern(h.patternId)?.notesOf(h.channelId) ?: return@edit p
            // playing the same step again replaces the note instead of stacking duplicates
            val cleaned = existing.filter { !(it.pitch == note.pitch && it.start == note.start) }
            p.setNotes(h.patternId, h.channelId, cleaned + note)
        }
    }

    private fun onMidiNote(on: Boolean, pitch: Int, velocity: Float) {
        // called on a MIDI thread: sound first (thread-safe), bookkeeping on the main thread
        val id = channelId
        if (on) engine.noteOn(id, pitch, velocity) else engine.noteOff(id, pitch)
        main.post {
            if (recordMode == RecordMode.NOTES && playing && !songMode) {
                if (on) {
                    val pos = engine.positionTicks
                    val pattern = currentPattern
                    if (pos >= 0 && pattern != null) {
                        val len = project.patternLength(pattern)
                        val t = (((pos - latencyTicks()) % len) + len) % len
                        held += Held(id, pitch, t.toInt(), velocity, pattern.id)
                    }
                } else {
                    val i = held.indexOfFirst { it.channelId == id && it.pitch == pitch }
                    if (i >= 0) finishRecordedNote(held.removeAt(i))
                }
            }
        }
    }

    // ------------------------------------------------------------------------ recording

    private var audioRecordStartTick = 0.0
    private var audioRecordStartNanos = 0L
    private var audioRecordChannel = 0

    /** Records what is played on the keys and pads into the current pattern (loop recording). */
    fun startNoteRecording() {
        if (recordMode != RecordMode.NONE) return
        if (playing) stopTransport()
        applySongMode(false)
        takeId++
        held.clear()
        recordMode = RecordMode.NOTES
        engine.stopAtEnd = false
        engineStarted = false
        val countIn = if (prefs.countIn) -project.ticksPerBar else 0
        engine.play(countIn)
        countingIn = countIn < 0
        playing = true
    }

    /**
     * Records the microphone while the song plays. Needs the RECORD_AUDIO permission.
     * Returns false if the microphone could not be opened.
     */
    fun startAudioRecording(): Boolean {
        if (recordMode != RecordMode.NONE) return false
        if (playing) stopTransport()

        // make sure there is an audio channel to record on
        var channel = currentChannel?.takeIf { it.isAudio } ?: project.channels.firstOrNull { it.isAudio }
        if (channel == null) {
            edit { p -> p.addChannel(Devices.AUDIO_TYPE_ID, strings(R.string.ch_voice), color = 3).first }
            channel = project.channels.lastOrNull { it.isAudio } ?: return false
        }
        // nothing in the playlist yet: put the current beat there so there is something to sing to
        val pattern = currentPattern
        if (project.clips.none { !it.isAudio } && pattern != null && !pattern.isEmpty) {
            val length = project.patternLength(pattern)
            val bars = max(8, pattern.bars * 4)
            edit { p -> p.addPatternClip(pattern.id, 0, 0, max(length, bars * p.ticksPerBar)).first }
            message = strings(R.string.msg_pattern_placed)
        }

        if (!recorder.start(engine.sampleRate)) {
            message = strings(R.string.msg_mic_failed)
            return false
        }
        audioRecordChannel = channel.id
        channelId = channel.id
        applySongMode(true)
        recordMode = RecordMode.AUDIO
        engine.stopAtEnd = false
        val countIn = if (prefs.countIn) cursor - project.ticksPerBar else cursor
        audioRecordStartTick = countIn.toDouble()
        audioRecordStartNanos = System.nanoTime()
        engineStarted = false
        engine.play(countIn)
        countingIn = countIn < cursor && countIn < 0
        playing = true
        return true
    }

    fun stopRecording() {
        val mode = recordMode
        if (mode == RecordMode.NONE) return
        recordMode = RecordMode.NONE
        val endTick = engine.positionTicks
        stopTransport()
        engine.stopAtEnd = true
        when (mode) {
            RecordMode.NOTES -> {
                // keys still held down end where the recording ended
                for (h in held.toList()) finishRecordedNote(h, endTick)
                held.clear()
                commit()
            }
            RecordMode.AUDIO -> finishAudioRecording(endTick)
            RecordMode.NONE -> {}
        }
        playhead.doubleValue = cursor.toDouble()
    }

    private fun finishAudioRecording(endTick: Double) {
        val data = recorder.stop()
        val id = projectId
        if (data == null || id == null) {
            message = strings(R.string.msg_record_empty)
            return
        }
        val p = project
        val ticksPerSecond = p.bpm * PPQ / 60.0
        // Which song position was *audible* when the first recorded frame hit the microphone?
        val first = if (recorder.firstFrameNanos != 0L) recorder.firstFrameNanos else audioRecordStartNanos
        val sinceStart = (first - audioRecordStartNanos) / 1e9
        val heardAt = audioRecordStartTick + sinceStart * ticksPerSecond -
            latencyTicks(prefs.recordOffsetMs.toFloat())
        // The clip starts where the take was started; what was captured before that
        // (count-in, latency) is skipped with the clip offset.
        val clipStart = max(0.0, max(heardAt, cursor.toDouble()))
        val skipSeconds = ((clipStart - heardAt) / ticksPerSecond).coerceAtLeast(0.0)
        val usable = data.durationSeconds - skipSeconds
        if (usable < 0.15) {
            message = strings(R.string.msg_record_empty)
            return
        }
        val length = max(1.0, usable * ticksPerSecond).roundToInt()
        val base = strings(R.string.rec_name)
        busy = -1f
        busyText = strings(R.string.busy_saving)
        scope.launch {
            val name = try {
                withContext(Dispatchers.IO) { store.saveSample(id, base, data) }
            } catch (e: Exception) {
                null
            }
            busy = null
            if (name == null || projectId != id) {
                message = strings(R.string.msg_save_failed)
                return@launch
            }
            samples.put(name, data)
            samplesChanged()
            edit { pr ->
                var q = pr
                var track = q.freeTrack(clipStart.toInt(), length, preferred = q.tracks - 1)
                if (track < 0) {
                    q = q.setTrackCount(q.tracks + 1)
                    track = q.tracks - 1
                }
                val (withClip, clipId) = q.addAudioClip(audioRecordChannel, name, track, clipStart.toInt(), length)
                withClip.copy(clips = withClip.clips.map { c ->
                    if (c.id == clipId) c.copy(offset = skipSeconds.toFloat()) else c
                })
            }
            message = strings(R.string.msg_recorded)
        }
    }

    // ------------------------------------------------------------------------ audio files

    /** Imports an audio file as the sample of a sampler channel. */
    fun importSample(uri: Uri, channelId: Int) {
        importAudio(uri) { name, _ ->
            edit { p ->
                val ch = p.channel(channelId) ?: return@edit p
                p.updateDevice(ch.instrument.uid) { it.copy(sample = name) }
            }
        }
    }

    /** Imports an audio file as a clip in the playlist. */
    fun importClip(uri: Uri, channelId: Int, track: Int, start: Int) {
        importAudio(uri) { name, data ->
            edit { p ->
                val length = max(1.0, data.durationSeconds * p.bpm * PPQ / 60.0).roundToInt()
                p.addAudioClip(channelId, name, track, start, length).first
            }
        }
    }

    /** Imports an audio file and puts it into the playlist at the play head, on a free track. */
    fun importToPlaylist(uri: Uri) {
        importAudio(uri) { name, data ->
            var clipId = 0
            edit { p0 ->
                var p = p0
                var channel = (p.channel(channelId)?.takeIf { it.isAudio } ?: p.channels.firstOrNull { it.isAudio })?.id
                if (channel == null) {
                    val (q, id) = p.addChannel(Devices.AUDIO_TYPE_ID, strings(R.string.ch_audio), color = 3)
                    p = q
                    channel = id
                }
                val length = max(1.0, data.durationSeconds * p.bpm * PPQ / 60.0).roundToInt()
                val start = if (songMode) cursor else 0
                var track = p.freeTrack(start, length, preferred = p.tracks - 1)
                if (track < 0) {
                    p = p.setTrackCount(p.tracks + 1)
                    track = p.tracks - 1
                }
                val (withClip, id) = p.addAudioClip(channel, name, track, start, length)
                clipId = id
                withClip
            }
            ui.selectedClip = clipId
        }
    }

    private fun importAudio(uri: Uri, onReady: (String, SampleData) -> Unit) {
        val id = projectId ?: return
        busy = -1f
        busyText = strings(R.string.busy_importing)
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                try {
                    val data = AudioDecoder.decode(app, uri)
                    val name = store.saveSample(id, displayName(uri), data)
                    name to data
                } catch (e: Exception) {
                    null
                }
            }
            busy = null
            if (result == null || projectId != id) {
                message = strings(R.string.msg_import_failed)
            } else {
                samples.put(result.first, result.second)
                samplesChanged()
                onReady(result.first, result.second)
            }
        }
    }

    private fun displayName(uri: Uri): String {
        var name: String? = null
        try {
            app.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) name = c.getString(0)
            }
        } catch (_: Exception) {
        }
        return (name ?: uri.lastPathSegment ?: "audio").substringBeforeLast('.')
    }

    // ------------------------------------------------------------------------ export

    @Volatile
    private var exportJob: Job? = null

    /** Sample rate of exported files: the phone's own rate when it is a standard one. */
    private fun exportRate(): Int = engine.sampleRate.let { if (it == 44100 || it == 48000) it else 48000 }

    private fun safeFileName(name: String, fallback: String = "MPS"): String =
        name.replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_").trim().trim('.').take(60).ifEmpty { fallback }

    /**
     * Renders the song (or, when the playlist is empty, the current pattern four times)
     * and saves it to Music/MPS: as one file, or as one WAV per channel.
     */
    fun export(options: ExportOptions) {
        if (busy != null) return
        if (playing) stop()
        val p = project
        val usePattern = p.clips.isEmpty()
        val pattern = currentPattern
        if (usePattern && (pattern == null || pattern.isEmpty)) {
            message = strings(R.string.msg_nothing_to_export)
            return
        }
        busy = 0f
        busyText = strings(R.string.busy_exporting)
        val rate = exportRate()
        val patternId = if (usePattern) pattern!!.id else 0
        val job = scope.launch(start = CoroutineStart.LAZY) {
            var result: ExportResult? = null
            var failed = false
            try {
                result = withContext(Dispatchers.Default) {
                    try {
                        if (options.stems) exportStems(p, patternId, rate) else exportMix(p, patternId, rate, options)
                    } catch (e: Exception) {
                        failed = true
                        null
                    }
                }
            } finally {
                // also reached when the user cancels: the dialog must always go away
                busy = null
                if (result != null) exportResult = result
                else if (failed || exportJob?.isCancelled != true) message = strings(R.string.msg_export_failed)
            }
        }
        exportJob = job
        job.start()
    }

    private fun exporting(): Boolean = exportJob?.isActive == true

    /** Runs on a background thread. Returns null when cancelled. */
    private fun exportMix(p: Project, patternId: Int, rate: Int, options: ExportOptions): ExportResult? {
        val wav = File(app.cacheDir, "export.wav")
        val packed = File(app.cacheDir, "export." + options.format.extension + ".tmp")
        try {
            val format = options.format
            // rendering is most of the work; packing gets the rest of the progress bar
            val share = if (format == ExportFormat.WAV) 1f else 0.6f
            val rendered = OfflineRenderer.render(p, samples, wav, rate, patternId = patternId, loops = 4) { progress ->
                reportProgress(progress * share)
                exporting()
            } ?: return null
            val file = when (format) {
                ExportFormat.WAV -> wav
                ExportFormat.MP3 -> {
                    val done = Mp3Encoder.encode(wav, packed, rate, kbps = options.mp3Kbps, title = p.name) { progress ->
                        reportProgress(share + (1f - share) * progress)
                        exporting()
                    }
                    if (!done) return null
                    packed
                }
                ExportFormat.M4A -> {
                    val done = AudioExporter.encodeAac(wav, packed, rate) { progress ->
                        reportProgress(share + (1f - share) * progress)
                        exporting()
                    }
                    if (!done) {
                        if (exporting()) throw IOException("AAC encoder failed")
                        return null
                    }
                    packed
                }
            }
            val name = safeFileName(p.name) + "." + format.extension
            val uri = AudioExporter.saveToMusic(app, file, name, format.mime)
            return ExportResult(listOf(uri), name, format.mime, rendered.seconds)
        } finally {
            wav.delete()
            packed.delete()
        }
    }

    /**
     * One WAV per audible channel, all of the same length, in a folder of their own.
     * Runs on a background thread. Returns null when cancelled.
     */
    private fun exportStems(p: Project, patternId: Int, rate: Int): ExportResult? {
        val channels = p.audibleChannels()
        if (channels.isEmpty()) throw IOException("Nothing is audible")
        val folder = safeFileName(p.name) + " " + strings(R.string.stems_folder)
        val wav = File(app.cacheDir, "stem.wav")
        val uris = ArrayList<Uri>()
        var seconds = 0.0
        var ok = false
        try {
            for ((i, channel) in channels.withIndex()) {
                val rendered = OfflineRenderer.render(
                    p.stemOf(channel.id), samples, wav, rate, patternId = patternId, loops = 4, tailSeconds = STEM_TAIL_SECONDS,
                    fixedTail = true,
                ) { progress ->
                    reportProgress((i + progress) / channels.size)
                    exporting()
                } ?: return null
                seconds = rendered.seconds
                // a channel that plays nothing in this song gets no file
                if (rendered.peak < 0.0005f) continue
                val name = String.format(Locale.ROOT, "%02d ", uris.size + 1) + safeFileName(channel.name, "Track") + ".wav"
                uris += AudioExporter.saveToMusic(app, wav, name, ExportFormat.WAV.mime, subFolder = folder)
            }
            if (uris.isEmpty()) throw IOException("Every channel is silent")
            ok = true
            return ExportResult(uris, folder, ExportFormat.WAV.mime, seconds, stems = true)
        } finally {
            wav.delete()
            // half a set of tracks is of no use to anyone
            if (!ok) for (uri in uris) AudioExporter.delete(app, uri)
        }
    }

    // ------------------------------------------------------------------------ project files

    /**
     * Saves a project with all its audio into one file (see [ProjectStore.exportTo]).
     *
     * @param open opens the file the user picked; called on a background thread.
     */
    fun exportProjectFile(id: String, open: () -> OutputStream?) {
        if (busy != null) return
        if (id == projectId) saveNow()
        busy = -1f
        busyText = strings(R.string.busy_project_saving)
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                try {
                    val out = open() ?: return@withContext false
                    out.use { store.exportTo(id, it) }
                    true
                } catch (e: Exception) {
                    false
                }
            }
            busy = null
            message = strings(if (ok) R.string.msg_project_exported else R.string.msg_project_export_failed)
        }
    }

    /** Adds a project saved with [exportProjectFile] to the list. [onReady] gets its id, or null. */
    fun importProjectFile(open: () -> InputStream?, onReady: (String?) -> Unit = {}) {
        if (busy != null) return
        busy = -1f
        busyText = strings(R.string.busy_project_opening)
        val taken = projects.map { it.name }.toSet()
        scope.launch {
            val id = withContext(Dispatchers.IO) {
                try {
                    val input = open() ?: return@withContext null
                    input.use { stream ->
                        store.importFrom(stream) { name ->
                            if (name !in taken) name else generateSequence(2) { it + 1 }.map { "$name $it" }.first { it !in taken }
                        }
                    }
                } catch (e: Exception) {
                    null
                }
            }
            busy = null
            if (id == null) {
                message = strings(R.string.msg_project_import_failed)
            } else {
                projects = withContext(Dispatchers.IO) { store.list() }
                message = strings(R.string.msg_project_imported)
            }
            onReady(id)
        }
    }

    fun cancelBusy() {
        exportJob?.cancel()
    }

    private var lastProgress = 0L

    /** Called from the render thread; forwards to the UI at most ~20 times per second. */
    private fun reportProgress(value: Float) {
        val now = System.nanoTime()
        if (now - lastProgress < 50_000_000L && value < 1f) return
        lastProgress = now
        main.post { if (busy != null) busy = value }
    }

    // ------------------------------------------------------------------------ small conveniences

    fun setBpm(bpm: Float) {
        val v = (bpm.coerceIn(MIN_BPM, MAX_BPM) * 10f).roundToInt() / 10f
        edit("bpm") { p ->
            if (p.bpm == v) p else p.copy(
                bpm = v,
                // audio keeps its real duration, so its length in beats changes with the tempo
                clips = p.clips.map { c -> if (c.isAudio) c.copy(length = max(1, (c.length * v / p.bpm).roundToInt())) else c },
            )
        }
    }

    /** Plays a short preview note on a channel (used when tapping a channel or placing a note). */
    fun preview(channelId: Int, pitch: Int, velocity: Float = 0.8f, millis: Long = 180) {
        engine.noteOn(channelId, pitch, velocity)
        main.postDelayed({ engine.noteOff(channelId, pitch) }, millis)
    }

    fun clipSeconds(c: Clip): Double = c.length * project.secondsPerTick

    private companion object {
        const val MAX_UNDO = 100

        /** Typical delay between touching the glass and the app hearing about it. */
        const val TOUCH_LATENCY_MS = 25f

        /** Room for echoes after the end of every separately exported track. */
        const val STEM_TAIL_SECONDS = 2.0
    }
}
