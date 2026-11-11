package jxpix.proger.mps.model

import jxpix.proger.mps.engine.Devices
import jxpix.proger.mps.engine.ParamSpec
import jxpix.proger.mps.engine.TICKS_PER_STEP
import jxpix.proger.mps.engine.instruments.DrumSynth

/*
 * Every edit of a project is a pure function: Project -> Project.
 * The store applies them, pushes the previous value on the undo stack and
 * hands the new value to the audio engine.
 */

const val MAX_EFFECTS = 6
const val MAX_CHANNELS = 32
const val MAX_PATTERNS = 64
const val MAX_TRACKS = 16
const val MAX_PATTERN_BARS = 16

// ------------------------------------------------------------------------------------ channels

fun Project.updateChannel(id: Int, f: (Channel) -> Channel): Project {
    val i = channels.indexOfFirst { it.id == id }
    if (i < 0) return this
    val updated = f(channels[i])
    if (updated === channels[i]) return this
    return copy(channels = channels.toMutableList().also { it[i] = updated })
}

/** Creates a new channel of the given instrument type and returns the project with its id. */
fun Project.addChannel(
    type: String,
    name: String,
    color: Int = channels.size % 8,
    params: Map<String, Float>? = null,
    preset: String? = null,
    rootNote: Int = 60,
): Pair<Project, Int> {
    val t = Devices[type] ?: return this to 0
    val id = nextId
    val channel = Channel(
        id = id,
        name = name,
        color = color,
        instrument = DeviceState(id + 1, type, params ?: t.defaults(), preset = preset),
        rootNote = rootNote,
    )
    return copy(channels = channels + channel, nextId = nextId + 2) to id
}

fun Project.removeChannel(id: Int): Project {
    if (channels.none { it.id == id }) return this
    return copy(
        channels = channels.filter { it.id != id },
        patterns = patterns.map { p ->
            if (id in p.notes || p.automation.any { it.channelId == id }) {
                p.copy(notes = p.notes - id, automation = p.automation.filter { it.channelId != id })
            } else p
        },
        clips = clips.filter { !(it.isAudio && it.channelId == id) },
        automation = automation.filter { it.channelId != id },
    )
}

/** Copies a channel with its instrument, effects and the notes it has in every pattern. */
fun Project.duplicateChannel(id: Int, newName: String): Pair<Project, Int> {
    val index = channels.indexOfFirst { it.id == id }
    if (index < 0) return this to 0
    val src = channels[index]
    var next = nextId
    val newId = next++
    val uids = HashMap<Int, Int>()
    uids[STRIP_DEVICE] = STRIP_DEVICE
    val copy = src.copy(
        id = newId,
        name = newName,
        instrument = src.instrument.copy(uid = next++.also { uids[src.instrument.uid] = it }),
        effects = src.effects.map { fx -> fx.copy(uid = next++.also { uids[fx.uid] = it }) },
        solo = false,
    )
    val list = channels.toMutableList().also { it.add(index + 1, copy) }
    val pats = patterns.map { p ->
        val notes = p.notes[id]
        val lanes = p.automation.filter { it.channelId == id }.mapNotNull { lane ->
            uids[lane.device]?.let { lane.copy(channelId = newId, device = it) }
        }
        if (notes.isNullOrEmpty() && lanes.isEmpty()) p else p.copy(
            notes = if (notes.isNullOrEmpty()) p.notes else p.notes + (newId to notes),
            automation = p.automation + lanes,
        )
    }
    val songLanes = automation.filter { it.channelId == id }.mapNotNull { lane ->
        uids[lane.device]?.let { lane.copy(channelId = newId, device = it) }
    }
    return copy(channels = list, patterns = pats, nextId = next, automation = automation + songLanes) to newId
}

fun Project.moveChannel(id: Int, delta: Int): Project {
    val from = channels.indexOfFirst { it.id == id }
    if (from < 0) return this
    val to = (from + delta).coerceIn(0, channels.lastIndex)
    if (to == from) return this
    val list = channels.toMutableList()
    list.add(to, list.removeAt(from))
    return copy(channels = list)
}

/** Swaps the instrument of a channel, keeping its notes, effects and mixer settings. */
fun Project.replaceInstrument(
    channelId: Int, type: String, params: Map<String, Float>? = null, preset: String? = null,
): Project {
    val t = Devices[type] ?: return this
    val old = channel(channelId)?.instrument?.uid ?: return this
    return updateChannel(channelId) {
        it.copy(instrument = DeviceState(nextId, type, params ?: t.defaults(), preset = preset))
    }.withoutLanesOf(old).copy(nextId = nextId + 1)
}

// ------------------------------------------------------------------------------------ devices

/** Applies [f] to the device with the given uid, wherever it lives. */
fun Project.updateDevice(uid: Int, f: (DeviceState) -> DeviceState): Project {
    for ((ci, ch) in channels.withIndex()) {
        if (ch.instrument.uid == uid) {
            val list = channels.toMutableList()
            list[ci] = ch.copy(instrument = f(ch.instrument))
            return copy(channels = list)
        }
        val fi = ch.effects.indexOfFirst { it.uid == uid }
        if (fi >= 0) {
            val fx = ch.effects.toMutableList()
            fx[fi] = f(fx[fi])
            val list = channels.toMutableList()
            list[ci] = ch.copy(effects = fx)
            return copy(channels = list)
        }
    }
    val mi = masterEffects.indexOfFirst { it.uid == uid }
    if (mi >= 0) {
        val fx = masterEffects.toMutableList()
        fx[mi] = f(fx[mi])
        return copy(masterEffects = fx)
    }
    return this
}

fun Project.findDevice(uid: Int): DeviceState? {
    for (ch in channels) {
        if (ch.instrument.uid == uid) return ch.instrument
        for (fx in ch.effects) if (fx.uid == uid) return fx
    }
    return masterEffects.firstOrNull { it.uid == uid }
}

fun Project.setParam(uid: Int, paramId: String, value: Float): Project = updateDevice(uid) { d ->
    val spec = Devices[d.type]?.spec(paramId) ?: return@updateDevice d
    val v = value.coerceIn(spec.min, spec.max)
    if (d.params[paramId] == v) d else d.copy(params = d.params + (paramId to v), preset = d.preset)
}

fun Project.applyPreset(uid: Int, preset: Preset): Project =
    updateDevice(uid) { it.copy(params = preset.params, preset = preset.id) }

/** Marks [DeviceState.preset] as one of the user's own presets; the rest of the string is its name. */
const val USER_PRESET_PREFIX = "user:"

/**
 * Sets all knobs of a device from a saved set of values. Values the device does not know are
 * dropped, missing ones get their defaults, so presets saved by another version still load.
 */
fun Project.applyParams(uid: Int, params: Map<String, Float>, presetId: String?): Project = updateDevice(uid) { d ->
    val type = Devices[d.type] ?: return@updateDevice d
    val clean = HashMap(type.defaults())
    for ((id, value) in params) {
        val spec = type.spec(id) ?: continue
        if (!value.isNaN()) clean[id] = value.coerceIn(spec.min, spec.max)
    }
    d.copy(params = clean, preset = presetId)
}

/** Effects of a channel, or of the master when [ownerId] is [MASTER_ID]. */
fun Project.effectsOf(ownerId: Int): List<DeviceState> =
    if (ownerId == MASTER_ID) masterEffects else channel(ownerId)?.effects ?: emptyList()

private fun Project.withEffects(ownerId: Int, list: List<DeviceState>): Project =
    if (ownerId == MASTER_ID) copy(masterEffects = list) else updateChannel(ownerId) { it.copy(effects = list) }

fun Project.addEffect(ownerId: Int, type: String): Pair<Project, Int> {
    val t = Devices[type] ?: return this to 0
    val current = effectsOf(ownerId)
    if (current.size >= MAX_EFFECTS || t.isInstrument) return this to 0
    val uid = nextId
    return withEffects(ownerId, current + DeviceState(uid, type, t.defaults())).copy(nextId = nextId + 1) to uid
}

fun Project.removeEffect(ownerId: Int, uid: Int): Project =
    withEffects(ownerId, effectsOf(ownerId).filter { it.uid != uid }).withoutLanesOf(uid)

fun Project.moveEffect(ownerId: Int, uid: Int, delta: Int): Project {
    val list = effectsOf(ownerId).toMutableList()
    val from = list.indexOfFirst { it.uid == uid }
    if (from < 0) return this
    val to = (from + delta).coerceIn(0, list.lastIndex)
    if (to == from) return this
    list.add(to, list.removeAt(from))
    return withEffects(ownerId, list)
}

// ------------------------------------------------------------------------------------ patterns

fun Project.updatePattern(id: Int, f: (Pattern) -> Pattern): Project {
    val i = patterns.indexOfFirst { it.id == id }
    if (i < 0) return this
    val updated = f(patterns[i])
    if (updated === patterns[i]) return this
    return copy(patterns = patterns.toMutableList().also { it[i] = updated })
}

fun Project.addPattern(name: String, bars: Int = 1): Pair<Project, Int> {
    if (patterns.size >= MAX_PATTERNS) return this to 0
    val id = nextId
    val p = Pattern(id, name, color = patterns.size % 8, bars = bars.coerceIn(1, MAX_PATTERN_BARS))
    return copy(patterns = patterns + p, nextId = nextId + 1) to id
}

fun Project.duplicatePattern(id: Int, name: String): Pair<Project, Int> {
    val index = patterns.indexOfFirst { it.id == id }
    if (index < 0 || patterns.size >= MAX_PATTERNS) return this to 0
    val newId = nextId
    val list = patterns.toMutableList()
    list.add(index + 1, patterns[index].copy(id = newId, name = name))
    return copy(patterns = list, nextId = nextId + 1) to newId
}

/** Deletes a pattern together with its clips. The last pattern cannot be removed. */
fun Project.removePattern(id: Int): Project {
    if (patterns.size <= 1 || patterns.none { it.id == id }) return this
    return copy(
        patterns = patterns.filter { it.id != id },
        clips = clips.filter { it.isAudio || it.patternId != id },
    )
}

fun Project.setNotes(patternId: Int, channelId: Int, notes: List<Note>): Project = updatePattern(patternId) { p ->
    if (notes.isEmpty()) {
        if (channelId in p.notes) p.copy(notes = p.notes - channelId) else p
    } else {
        p.copy(notes = p.notes + (channelId to notes))
    }
}

// ------------------------------------------------------------------------------------ automation

/** Most points one curve may have (a 16 bar pattern drawn at 1/32). */
const val MAX_AUTO_POINTS = 512

/**
 * Sets the automation curve of one knob inside a pattern; an empty list removes the curve.
 * [device] is the uid of an instrument or effect of the channel, or [STRIP_DEVICE].
 */
fun Project.setAutomation(patternId: Int, channelId: Int, device: Int, param: String, points: List<AutoPoint>): Project =
    updatePattern(patternId) { p ->
        val others = p.automation.filter { !it.targets(channelId, device, param) }
        if (points.isEmpty()) {
            if (others.size == p.automation.size) p else p.copy(automation = others)
        } else {
            p.copy(automation = others + AutoLane(channelId, device, param, cleanPoints(points)))
        }
    }

/** Most points a curve of the song may have: it runs over the whole track, not over a few bars. */
const val MAX_SONG_AUTO_POINTS = 4096

/**
 * Sets the curve that moves one knob of a channel along the song; an empty list removes it.
 * This is the automation of audio tracks, which have no patterns to carry a curve.
 */
fun Project.setSongAutomation(channelId: Int, device: Int, param: String, points: List<AutoPoint>): Project {
    val others = automation.filter { !it.targets(channelId, device, param) }
    return if (points.isEmpty()) {
        if (others.size == automation.size) this else copy(automation = others)
    } else {
        copy(automation = others + AutoLane(channelId, device, param, cleanPoints(points, MAX_SONG_AUTO_POINTS)))
    }
}

/** Sorted, one point per tick (the later one wins), values inside 0..1. */
fun cleanPoints(points: List<AutoPoint>, most: Int = MAX_AUTO_POINTS): List<AutoPoint> {
    val byTick = java.util.TreeMap<Int, Float>()
    for (pt in points) if (pt.tick >= 0 && !pt.value.isNaN()) byTick[pt.tick] = pt.value.coerceIn(0f, 1f)
    val out = ArrayList<AutoPoint>(byTick.size)
    for ((tick, value) in byTick) {
        if (out.size >= most) break
        out += AutoPoint(tick, value)
    }
    return out
}

/** Value of a curve at [tick], the same way the engine reads it. Null for an empty curve. */
fun autoValueAt(points: List<AutoPoint>, tick: Float): Float? {
    if (points.isEmpty()) return null
    if (tick <= points.first().tick) return points.first().value
    if (tick >= points.last().tick) return points.last().value
    var lo = 0
    var hi = points.lastIndex
    while (hi - lo > 1) {
        val mid = (lo + hi) ushr 1
        if (points[mid].tick <= tick) lo = mid else hi = mid
    }
    val a = points[lo]
    val b = points[hi]
    return a.value + (b.value - a.value) * (tick - a.tick) / (b.tick - a.tick)
}

/** File names of the audio the project plays: sampler sounds and audio clips. */
fun Project.usedSamples(): Set<String> {
    val out = HashSet<String>()
    for (c in channels) c.instrument.sample?.let { out += it }
    for (c in clips) c.sample?.let { out += it }
    return out
}

/** The channels that are heard in the mix: not muted, or - when something is soloed - only the soloed ones. */
fun Project.audibleChannels(): List<Channel> {
    val anySolo = channels.any { it.solo }
    return channels.filter { if (anySolo) it.solo else !it.mute }
}

/**
 * The project with a single channel left audible and nothing on the master strip - what is
 * rendered for one "stem" file. Put together, the stems give the mix before master effects.
 */
fun Project.stemOf(channelId: Int): Project = copy(
    masterEffects = emptyList(),
    channels = channels.map { it.copy(solo = false, mute = it.id != channelId) },
)

/**
 * The curve played twice in a row, for a pattern that is doubled from [length] ticks: the second
 * pass starts with the value the curve had at its very beginning, just like a loop would.
 */
fun repeatPoints(points: List<AutoPoint>, length: Int): List<AutoPoint> {
    if (points.isEmpty() || length <= 1 || points.all { it.value == points[0].value }) return points
    val atStart = autoValueAt(points, 0f) ?: return points
    val atEnd = autoValueAt(points, (length - 1).toFloat()) ?: return points
    val out = ArrayList<AutoPoint>(points.size * 2 + 2)
    for (pt in points) if (pt.tick < length) out += pt
    out += AutoPoint(length - 1, atEnd)
    out += AutoPoint(length, atStart)
    for (pt in points) if (pt.tick > 0) out += pt.copy(tick = pt.tick + length)
    return cleanPoints(out)
}

/** Forgets the curves that belonged to a device which is gone. */
internal fun Project.withoutLanesOf(deviceUid: Int): Project {
    if (deviceUid == STRIP_DEVICE) return this
    if (patterns.none { p -> p.automation.any { it.device == deviceUid } } && automation.none { it.device == deviceUid }) return this
    return copy(
        patterns = patterns.map { p ->
            if (p.automation.any { it.device == deviceUid }) p.copy(automation = p.automation.filter { it.device != deviceUid }) else p
        },
        automation = automation.filter { it.device != deviceUid },
    )
}

/** The automatable parameter a lane points at, or null if it does not exist (any more). */
fun Project.laneSpec(lane: AutoLane): ParamSpec? {
    val ch = channel(lane.channelId) ?: return null
    if (lane.device == STRIP_DEVICE) return null
    val dev = if (ch.instrument.uid == lane.device) ch.instrument else ch.effects.firstOrNull { it.uid == lane.device }
    return dev?.let { Devices[it.type]?.spec(lane.param) }
}

private fun Project.laneIsValid(lane: AutoLane): Boolean =
    if (lane.device == STRIP_DEVICE) {
        channel(lane.channelId) != null && (lane.param == PARAM_VOLUME || lane.param == PARAM_PAN)
    } else laneSpec(lane) != null

/** Changes the length of a pattern. Notes beyond the new end are kept (just not played). */
fun Project.setPatternBars(patternId: Int, bars: Int): Project =
    updatePattern(patternId) { it.copy(bars = bars.coerceIn(1, MAX_PATTERN_BARS)) }

/** True if the step of the step sequencer holds at least one note. */
fun stepActive(notes: List<Note>, step: Int): Boolean {
    val from = step * TICKS_PER_STEP
    val to = from + TICKS_PER_STEP
    for (n in notes) if (n.start in from until to) return true
    return false
}

/** Velocity of the first note in a step, or 0 if the step is empty. */
fun stepVelocity(notes: List<Note>, step: Int): Float {
    val from = step * TICKS_PER_STEP
    val to = from + TICKS_PER_STEP
    for (n in notes) if (n.start in from until to) return n.velocity
    return 0f
}

/** Switches a step of the step sequencer on or off. */
fun Project.setStep(patternId: Int, channelId: Int, step: Int, on: Boolean, velocity: Float = 0.8f): Project {
    val pattern = pattern(patternId) ?: return this
    val ch = channel(channelId) ?: return this
    val notes = pattern.notesOf(channelId)
    val from = step * TICKS_PER_STEP
    val to = from + TICKS_PER_STEP
    val has = stepActive(notes, step)
    if (has == on) return this
    val updated = if (on) notes + Note(from, TICKS_PER_STEP, ch.rootNote, velocity)
    else notes.filter { it.start !in from until to }
    return setNotes(patternId, channelId, updated)
}

/** Moves every note of a channel inside a pattern by [ticks], wrapping around the pattern. */
fun Project.shiftNotes(patternId: Int, channelId: Int, ticks: Int): Project {
    val p = pattern(patternId) ?: return this
    val length = patternLength(p)
    val notes = p.notesOf(channelId)
    if (notes.isEmpty()) return this
    return setNotes(patternId, channelId, notes.map { it.copy(start = ((it.start + ticks) % length + length) % length) })
}

// ------------------------------------------------------------------------------------ playlist

fun Project.updateClip(id: Int, f: (Clip) -> Clip): Project {
    val i = clips.indexOfFirst { it.id == id }
    if (i < 0) return this
    val updated = f(clips[i])
    if (updated === clips[i]) return this
    return copy(clips = clips.toMutableList().also { it[i] = updated })
}

fun Project.addPatternClip(patternId: Int, track: Int, start: Int, length: Int): Pair<Project, Int> {
    val id = nextId
    val clip = Clip(id, track.coerceIn(0, tracks - 1), start.coerceAtLeast(0), length.coerceAtLeast(1), patternId = patternId)
    return copy(clips = clips + clip, nextId = nextId + 1) to id
}

fun Project.addAudioClip(channelId: Int, sample: String, track: Int, start: Int, length: Int): Pair<Project, Int> {
    val id = nextId
    val clip = Clip(
        id, track.coerceIn(0, tracks - 1), start.coerceAtLeast(0), length.coerceAtLeast(1),
        channelId = channelId, sample = sample,
    )
    return copy(clips = clips + clip, nextId = nextId + 1) to id
}

fun Project.removeClip(id: Int): Project =
    if (clips.none { it.id == id }) this else copy(clips = clips.filter { it.id != id })

/** Puts a copy of the clip right after the original. */
fun Project.duplicateClip(id: Int): Pair<Project, Int> {
    val src = clip(id) ?: return this to 0
    val newId = nextId
    return copy(clips = clips + src.copy(id = newId, start = src.end), nextId = nextId + 1) to newId
}

/** Cuts a clip in two at the given song position. */
fun Project.splitClip(id: Int, at: Int): Project {
    val src = clip(id) ?: return this
    if (at <= src.start || at >= src.end) return this
    val first = src.copy(length = at - src.start)
    val secondOffset = if (src.isAudio) src.offset + ((at - src.start) * secondsPerTick).toFloat() else src.offset
    val second = src.copy(id = nextId, start = at, length = src.end - at, offset = secondOffset)
    // a split pattern clip restarts its pattern, which matches what the user sees
    return copy(clips = clips.map { if (it.id == id) first else it } + second, nextId = nextId + 1)
}

fun Project.setTrackCount(count: Int): Project {
    val n = count.coerceIn(1, MAX_TRACKS)
    return copy(tracks = n, clips = clips.filter { it.track < n })
}

/** First playlist lane that has no clip overlapping [start, start+length). */
/**
 * The track a new audio clip goes to: the topmost one with nothing at or after [start], so
 * that the clip lands right under the arrangement, in view. A track is added when all are
 * taken; -1 only when there is no room for another track either.
 */
fun Project.trackForNewAudio(start: Int): Pair<Project, Int> {
    for (t in 0 until tracks) if (clips.none { it.track == t && it.end > start }) return this to t
    val grown = setTrackCount(tracks + 1)
    return if (grown.tracks > tracks) grown to grown.tracks - 1 else this to -1
}

fun Project.freeTrack(start: Int, length: Int, preferred: Int = 0): Int {
    fun free(t: Int) = clips.none { it.track == t && it.start < start + length && it.end > start }
    if (free(preferred)) return preferred
    for (t in 0 until tracks) if (free(t)) return t
    return -1
}

// ------------------------------------------------------------------------------------ helpers

/** Default drum channel parameters for a drum type. */
fun drumParams(type: Int): Map<String, Float> = DrumSynth.defaultsFor(type)

/** Removes ids that no longer exist; keeps a loaded project consistent. */
fun Project.sanitized(): Project {
    val channelIds = channels.map { it.id }.toSet()
    val patternIds = patterns.map { it.id }.toSet()
    var maxId = 0
    for (c in channels) {
        maxId = maxOf(maxId, c.id, c.instrument.uid)
        for (fx in c.effects) maxId = maxOf(maxId, fx.uid)
    }
    for (fx in masterEffects) maxId = maxOf(maxId, fx.uid)
    for (p in patterns) maxId = maxOf(maxId, p.id)
    var maxTrack = 0
    for (c in clips) {
        maxId = maxOf(maxId, c.id)
        maxTrack = maxOf(maxTrack, c.track)
    }
    return copy(
        bpm = bpm.coerceIn(MIN_BPM, MAX_BPM),
        beatsPerBar = beatsPerBar.coerceIn(2, 8),
        swing = swing.coerceIn(0f, 1f),
        tracks = maxOf(tracks, maxTrack + 1).coerceIn(1, MAX_TRACKS),
        channels = channels.filter { Devices[it.instrument.type] != null }
            .map { c -> c.copy(effects = c.effects.filter { Devices[it.type] != null }) },
        masterEffects = masterEffects.filter { Devices[it.type] != null },
        patterns = patterns.map { p ->
            p.copy(
                notes = p.notes.filterKeys { it in channelIds },
                bars = p.bars.coerceIn(1, MAX_PATTERN_BARS),
                automation = p.automation.filter { laneIsValid(it) && it.points.isNotEmpty() }
                    .distinctBy { Triple(it.channelId, it.device, it.param) }
                    .map { it.copy(points = cleanPoints(it.points)) },
            )
        },
        clips = clips.filter { c ->
            c.length > 0 && c.track in 0 until MAX_TRACKS &&
                (if (c.isAudio) c.channelId in channelIds else c.patternId in patternIds)
        },
        loopEnd = maxOf(loopEnd, loopStart + 1),
        nextId = maxOf(nextId, maxId + 1),
        keyRoot = ((keyRoot % 12) + 12) % 12,
        scale = if (scale in 0 until Scales.count) scale else 0,
        automation = automation.filter { laneIsValid(it) && it.points.isNotEmpty() }
            .distinctBy { Triple(it.channelId, it.device, it.param) }
            .map { it.copy(points = cleanPoints(it.points, MAX_SONG_AUTO_POINTS)) },
    )
}

/** Moves the notes of a channel in a pattern that are outside of the song's key onto the nearest note of the key. */
fun Project.snapNotesToKey(patternId: Int, channelId: Int, only: Set<Note>? = null): Project {
    val mask = keyMask
    if (mask == 0) return this
    val notes = pattern(patternId)?.notesOf(channelId) ?: return this
    var changed = false
    val moved = notes.map { n ->
        if (only != null && n !in only) return@map n
        val pitch = Scales.nearest(n.pitch, mask)
        if (pitch != n.pitch) {
            changed = true
            n.copy(pitch = pitch)
        } else n
    }
    return if (changed) setNotes(patternId, channelId, moved) else this
}
