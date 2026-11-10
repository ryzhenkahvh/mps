package jxpix.proger.mps.engine

import jxpix.proger.mps.engine.effects.Limiter
import jxpix.proger.mps.engine.dsp.FastMath
import jxpix.proger.mps.model.Note
import jxpix.proger.mps.model.Project
import jxpix.proger.mps.model.faderToGain
import jxpix.proger.mps.model.swingTick
import java.util.concurrent.atomic.AtomicLongArray
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/**
 * The sound engine: sequencer + instruments + mixer.
 *
 * Threading model (this is what keeps the latency low and the audio glitch free):
 *
 *  - The **control thread** (UI) calls [setProject] after every edit. The project is
 *    "compiled" into an immutable [Plan]: sorted note arrays, parameter arrays and
 *    references to long-lived DSP objects. All allocation happens here.
 *  - The **audio thread** calls [render]. It picks up the newest plan with a single
 *    volatile read and never allocates, locks or waits.
 *  - Live input (keyboard, pads, MIDI) and transport commands travel through a
 *    lock-free queue and are applied at the start of the next block.
 *
 * The same class is used for real-time playback and for offline export.
 */
class Engine(val sampleRate: Int) {

    // ------------------------------------------------------------------ public state

    /** True while the transport is running. Written by the audio thread. */
    @Volatile
    var playing = false
        private set

    /** Current position in ticks: song position in song mode, position inside the pattern otherwise. */
    @Volatile
    var positionTicks = 0.0
        private set

    /** Song mode plays the playlist; pattern mode loops [patternId]. */
    @Volatile
    var songMode = false

    @Volatile
    var patternId = 0

    @Volatile
    var metronome = false

    /** When true the transport stops by itself at the end of the song. */
    @Volatile
    var stopAtEnd = true

    @Volatile
    var masterPeakL = 0f

    @Volatile
    var masterPeakR = 0f

    /** Fraction of the available time spent rendering (1.0 = no headroom left). */
    @Volatile
    var load = 0f

    /**
     * Strongest gain reduction (in dB, positive) applied by the built-in safety limiter since the
     * value was last reset. Anything above a few dB means the mix is too loud.
     */
    @Volatile
    var limiterReductionDb = 0f

    // ------------------------------------------------------------------ audition (sound library)

    /** True while a sound started with [audition] is playing. Written by the audio thread. */
    @Volatile
    var auditioning = false
        private set

    @Volatile
    private var auditionRequest: SampleData? = null

    @Volatile
    private var auditionSerial = 0
    private var auditionSeen = 0
    private var auditionData: SampleData? = null
    private var auditionPos = 0.0

    /**
     * Plays a sound that is not part of the project, straight to the output - for listening to
     * the sounds of the library before using one. Null stops it. Any thread.
     */
    fun audition(data: SampleData?) {
        auditionRequest = data
        auditionSerial++
        if (data != null) auditioning = true
    }

    private fun renderAudition(n: Int) {
        var fadeOut = false
        val serial = auditionSerial
        if (serial != auditionSeen) {
            auditionSeen = serial
            // what is sounding now fades out over this block, the new sound starts with the next one
            if (auditionData != null) fadeOut = true
            else {
                auditionData = auditionRequest
                auditionPos = 0.0
            }
        }
        val d = auditionData
        if (d != null) {
            val left = d.left
            val right = d.right ?: left
            val last = d.frames - 1
            val step = d.sampleRate.toDouble() / sampleRate
            var p = auditionPos
            var ended = false
            for (i in 0 until n) {
                val i0 = p.toInt()
                if (i0 >= last) {
                    ended = true
                    break
                }
                val f = (p - i0).toFloat()
                val g = if (fadeOut) AUDITION_GAIN * (n - i) / n else AUDITION_GAIN
                masterL[i] += (left[i0] + (left[i0 + 1] - left[i0]) * f) * g
                masterR[i] += (right[i0] + (right[i0 + 1] - right[i0]) * f) * g
                p += step
            }
            auditionPos = p
            if (fadeOut) {
                auditionData = auditionRequest
                auditionPos = 0.0
            } else if (ended) {
                auditionData = null
            }
        }
        val on = auditionData != null
        if (auditioning != on && auditionSerial == auditionSeen) auditioning = on
    }

    // ------------------------------------------------------------------ compile side (control thread)

    private class DeviceEntry(val type: DeviceType, val device: Device) {
        var lastMap: Map<String, Float>? = null
        var array: FloatArray = FloatArray(0)
        var generation = 0

        /** Parameter array last pushed into the device; touched by the audio thread only. */
        var applied: FloatArray? = null

        /**
         * Working copy of the parameters while automation moves some of them, and the values the
         * device got last. Created once, on the control thread, before a plan refers to them.
         */
        var auto: FloatArray? = null
        var autoSent: FloatArray? = null

        /** Automation wrote into [auto] during this block / the device currently runs on automated values. Audio thread only. */
        var autoNow = false
        var autoOn = false
    }

    private class SeqEntry {
        var notes: List<Note>? = null
        var swing = -1f
        var seq: NoteSeq = NoteSeq.EMPTY
        var generation = 0
    }

    private val devices = HashMap<Int, DeviceEntry>()
    private val sequences = HashMap<Long, SeqEntry>()
    private val runtimes = HashMap<Int, ChannelRuntime>()
    private var generation = 0

    @Volatile
    private var plan: Plan = Plan.EMPTY

    /** Compiles [project] and hands it to the audio thread. Call from one thread only. */
    fun setProject(project: Project, samples: SamplePool) {
        plan = compile(project, samples)
    }

    private fun deviceEntry(uid: Int, typeId: String, params: Map<String, Float>, gen: Int): DeviceEntry? {
        val type = Devices[typeId] ?: return null
        var e = devices[uid]
        if (e == null || e.type !== type) {
            val device = type.create() ?: return null
            device.prepare(sampleRate)
            e = DeviceEntry(type, device)
            devices[uid] = e
        }
        if (e.lastMap !== params) {
            e.lastMap = params
            e.array = type.toArray(params)
        }
        e.generation = gen
        return e
    }

    private fun sequence(patternId: Int, channelId: Int, notes: List<Note>, swing: Float, gen: Int): NoteSeq {
        val key = (patternId.toLong() shl 32) or (channelId.toLong() and 0xFFFFFFFFL)
        val e = sequences.getOrPut(key) { SeqEntry() }
        if (e.notes !== notes || e.swing != swing) {
            e.notes = notes
            e.swing = swing
            e.seq = NoteSeq.build(notes, swing)
        }
        e.generation = gen
        return e.seq
    }

    private fun compile(p: Project, samples: SamplePool): Plan {
        val gen = ++generation
        val count = p.channels.size
        var anySolo = false

        val channels = Array(count) { i ->
            val ch = p.channels[i]
            val rt = runtimes.getOrPut(ch.id) { ChannelRuntime() }
            rt.generation = gen
            val inst = deviceEntry(ch.instrument.uid, ch.instrument.type, ch.instrument.params, gen)
            if (ch.solo) anySolo = true
            ChannelPlan(
                id = ch.id,
                runtime = rt,
                instrument = inst?.device as? Instrument,
                instrumentEntry = inst,
                instrumentParams = inst?.array,
                gain = faderToGain(ch.volume),
                pan = ch.pan.coerceIn(-1f, 1f),
                mute = ch.mute,
                solo = ch.solo,
                effects = effectPlans(ch.effects, gen),
                cutGroup = ch.cutGroup,
                sample = samples[ch.instrument.sample],
            )
        }

        val autoEntries = LinkedHashSet<DeviceEntry>()
        val autoStrips = LinkedHashSet<ChannelRuntime>()
        val patterns = Array(p.patterns.size) { pi ->
            val pat = p.patterns[pi]
            val seqs = arrayOfNulls<NoteSeq>(count)
            if (pat.notes.isNotEmpty()) {
                for (ci in 0 until count) {
                    val id = p.channels[ci].id
                    val notes = pat.notes[id]
                    if (!notes.isNullOrEmpty()) seqs[ci] = sequence(pat.id, id, notes, p.swing, gen)
                }
            }
            PatternPlan(pat.id, max(1, pat.bars * p.ticksPerBar), seqs, lanePlans(p, pat, channels, autoEntries, autoStrips))
        }

        val patternClips = ArrayList<ClipPlan>()
        val audioClips = ArrayList<AudioClipPlan>()
        var songEnd = 0
        for (c in p.clips) {
            if (c.length <= 0) continue
            songEnd = max(songEnd, c.end)
            if (c.muted) continue
            if (c.isAudio) {
                val data = samples[c.sample] ?: continue
                val ci = p.channels.indexOfFirst { it.id == c.channelId }
                if (ci < 0) continue
                audioClips += AudioClipPlan(c.start, c.end, ci, data, c.offset.toDouble(), c.gain)
            } else {
                val pi = p.patterns.indexOfFirst { it.id == c.patternId }
                if (pi >= 0) patternClips += ClipPlan(c.start, c.end, pi)
            }
        }

        val loopOn = p.loopEnabled && p.loopEnd > p.loopStart
        val result = Plan(
            bpm = p.bpm.toDouble().coerceIn(20.0, 400.0),
            ticksPerBar = p.ticksPerBar,
            channels = channels,
            patterns = patterns,
            clips = patternClips.toTypedArray(),
            audioClips = audioClips.toTypedArray(),
            masterEffects = effectPlans(p.masterEffects, gen),
            masterGain = faderToGain(p.masterVolume),
            songEnd = songEnd,
            loopEnabled = loopOn,
            loopStart = p.loopStart * p.ticksPerBar,
            loopEnd = p.loopEnd * p.ticksPerBar,
            anySolo = anySolo,
            autoEntries = autoEntries.toTypedArray(),
            autoStrips = autoStrips.toTypedArray(),
        )

        // Forget DSP objects and caches that are no longer referenced.
        devices.values.removeAll { it.generation != gen }
        sequences.values.removeAll { it.generation != gen }
        runtimes.values.removeAll { it.generation != gen }
        return result
    }

    /** Compiles the automation curves of one pattern. Curves whose knob no longer exists are skipped. */
    private fun lanePlans(
        p: Project, pat: jxpix.proger.mps.model.Pattern, channels: Array<ChannelPlan>,
        autoEntries: MutableSet<DeviceEntry>, autoStrips: MutableSet<ChannelRuntime>,
    ): Array<LanePlan> {
        if (pat.automation.isEmpty()) return NO_LANES
        val out = ArrayList<LanePlan>(pat.automation.size)
        for (lane in pat.automation) {
            if (lane.points.isEmpty()) continue
            val ci = p.channels.indexOfFirst { it.id == lane.channelId }
            if (ci < 0) continue
            val ticks = IntArray(lane.points.size) { lane.points[it].tick }
            val values = FloatArray(lane.points.size) { lane.points[it].value.coerceIn(0f, 1f) }
            var sorted = true
            for (i in 1 until ticks.size) if (ticks[i] <= ticks[i - 1]) sorted = false
            if (!sorted) continue
            if (lane.device == jxpix.proger.mps.model.STRIP_DEVICE) {
                val kind = when (lane.param) {
                    jxpix.proger.mps.model.PARAM_VOLUME -> LANE_VOLUME
                    jxpix.proger.mps.model.PARAM_PAN -> LANE_PAN
                    else -> continue
                }
                val rt = channels[ci].runtime
                autoStrips += rt
                out += LanePlan(kind, rt, null, -1, null, ticks, values)
            } else {
                val ch = p.channels[ci]
                if (ch.instrument.uid != lane.device && ch.effects.none { it.uid == lane.device }) continue
                val e = devices[lane.device] ?: continue
                val index = e.type.indexOf(lane.param)
                if (index < 0) continue
                if (e.auto == null) {
                    e.autoSent = FloatArray(e.type.params.size)
                    e.auto = FloatArray(e.type.params.size)
                }
                autoEntries += e
                out += LanePlan(LANE_PARAM, null, e, index, e.type.params[index], ticks, values)
            }
        }
        return if (out.isEmpty()) NO_LANES else out.toTypedArray()
    }

    private fun effectPlans(list: List<jxpix.proger.mps.model.DeviceState>, gen: Int): Array<EffectPlan> {
        val out = ArrayList<EffectPlan>(list.size)
        for (fx in list) {
            val e = deviceEntry(fx.uid, fx.type, fx.params, gen) ?: continue
            val effect = e.device as? Effect ?: continue
            out += EffectPlan(effect, e, e.array, fx.enabled)
        }
        return out.toTypedArray()
    }

    // ------------------------------------------------------------------ commands (any thread)

    private val commands = CommandQueue(1024)

    fun play(fromTick: Int) = commands.offer(CMD_PLAY, 0, 0, fromTick)

    /** Continues from the current position. */
    fun resume() = commands.offer(CMD_RESUME, 0, 0, 0)

    fun pause() = commands.offer(CMD_PAUSE, 0, 0, 0)

    /** Stops and moves the play head to [toTick]. */
    fun stop(toTick: Int = 0) = commands.offer(CMD_STOP, 0, 0, toTick)

    fun seek(tick: Int) = commands.offer(CMD_SEEK, 0, 0, tick)

    fun noteOn(channelId: Int, pitch: Int, velocity: Float) =
        commands.offer(CMD_NOTE_ON, channelId, pitch, velocity.coerceIn(0.01f, 1f).toRawBits())

    fun noteOff(channelId: Int, pitch: Int) = commands.offer(CMD_NOTE_OFF, channelId, pitch, 0)

    /** Cuts all sound at once, including echo and reverb tails. */
    fun panic() = commands.offer(CMD_PANIC, 0, 0, 0)

    /** Peak level (0..1+) of a channel since the last call; for the mixer meters. */
    fun takeChannelPeak(channelId: Int): Float {
        val rt = runtimes[channelId] ?: return 0f
        val v = rt.peak
        rt.peak = 0f
        return v
    }

    /** True if the channel produced sound recently (used to flash pads / activity LEDs). */
    fun channelTriggerCount(channelId: Int): Int = runtimes[channelId]?.triggers ?: 0

    fun takeMasterPeaks(out: FloatArray) {
        out[0] = masterPeakL
        out[1] = masterPeakR
        masterPeakL = 0f
        masterPeakR = 0f
    }

    // ------------------------------------------------------------------ audio thread

    private var active: Plan = Plan.EMPTY
    private val ctx = ProcessContext().also { it.sampleRate = sampleRate }
    private val masterL = FloatArray(MAX_BLOCK)
    private val masterR = FloatArray(MAX_BLOCK)
    private val limiter = Limiter().also {
        it.prepare(sampleRate)
        it.configure(0f, -0.2f, 150f)
    }
    private var pos = 0.0
    private var isPlaying = false
    private var frameCounter = 0L
    private var masterGainCur = -1f

    // block segmentation (a block is split where the loop wraps around)
    private val segFrame = IntArray(MAX_SEGMENTS)
    private val segCount = IntArray(MAX_SEGMENTS)
    private val segTick = DoubleArray(MAX_SEGMENTS)
    private val segRangeStart = DoubleArray(MAX_SEGMENTS)
    private val segRangeEnd = DoubleArray(MAX_SEGMENTS)
    private var segN = 0
    private var patternIndex = -1

    // metronome click
    private var clickPhase = 0f
    private var clickInc = 0f
    private var clickEnv = 0f
    private var clickAt = -1
    private var clickAccent = false
    private val clickDecay = FastMath.decayCoef(0.012f, sampleRate.toFloat())

    /**
     * Renders [frames] stereo frames into [out] (interleaved L R L R...).
     * Real-time safe.
     */
    fun render(out: FloatArray, frames: Int) {
        var done = 0
        while (done < frames) {
            val n = min(MAX_BLOCK, frames - done)
            renderBlock(n)
            var o = done * 2
            for (i in 0 until n) {
                out[o++] = masterL[i]
                out[o++] = masterR[i]
            }
            done += n
        }
    }

    private fun renderBlock(n: Int) {
        val plan = this.plan
        var previous: Plan? = null
        if (plan !== active) {
            previous = active
            applyPlan(plan)
            active = plan
        }
        val tpf = plan.bpm * PPQ / (60.0 * sampleRate)

        for (cp in plan.channels) cp.runtime.eventCount = 0
        drainCommands(plan)

        ctx.bpm = plan.bpm
        ctx.playing = isPlaying
        ctx.tickPos = pos
        ctx.ticksPerFrame = tpf

        clickAt = -1
        segN = 0
        if (previous != null) beginAutomation(previous)
        beginAutomation(plan)
        if (isPlaying) {
            buildSegments(plan, n, tpf)
            for (s in 0 until segN) scheduleSegment(plan, s, tpf)
        }
        finishAutomation(plan)
        // knobs that only the replaced plan had curves for get their own value back
        if (previous != null) finishAutomation(previous)
        collectNoteOffs(plan, n)
        applyCutGroups(plan)

        java.util.Arrays.fill(masterL, 0, n, 0f)
        java.util.Arrays.fill(masterR, 0, n, 0f)
        for (cp in plan.channels) renderChannel(plan, cp, n, tpf)

        for (fx in plan.masterEffects) if (fx.enabled) fx.effect.process(masterL, masterR, n, ctx)

        // master fader (smoothed)
        val target = plan.masterGain
        if (masterGainCur < 0f) masterGainCur = target
        var g = masterGainCur
        val step = (target - g) / n
        for (i in 0 until n) {
            g += step
            masterL[i] *= g
            masterR[i] *= g
        }
        masterGainCur = target

        renderClick(n)
        renderAudition(n)
        limiter.process(masterL, masterR, n)
        val lg = limiter.minGain
        if (lg < 0.999f) {
            val db = -FastMath.gainToDb(lg)
            if (db > limiterReductionDb) limiterReductionDb = db
        }

        var pl = 0f
        var pr = 0f
        for (i in 0 until n) {
            val a = abs(masterL[i])
            if (a > pl) pl = a
            val b = abs(masterR[i])
            if (b > pr) pr = b
        }
        // NaN guard: never send garbage to the speaker
        if (pl.isNaN() || pr.isNaN() || pl > 64f || pr > 64f) {
            java.util.Arrays.fill(masterL, 0, n, 0f)
            java.util.Arrays.fill(masterR, 0, n, 0f)
            recover(plan)
            pl = 0f
            pr = 0f
        }
        if (pl > masterPeakL) masterPeakL = pl
        if (pr > masterPeakR) masterPeakR = pr

        frameCounter += n
        positionTicks = pos
        playing = isPlaying
    }

    private fun applyPlan(plan: Plan) {
        for (cp in plan.channels) {
            val e = cp.instrumentEntry
            val inst = cp.instrument
            if (e != null && inst != null) {
                val arr = cp.instrumentParams
                if (arr != null && e.applied !== arr) {
                    inst.setParams(arr)
                    e.applied = arr
                    e.autoOn = false // automation has to be laid over the new knob values again
                }
                inst.setSample(cp.sample)
            }
            for (fx in cp.effects) fx.apply()
        }
        for (fx in plan.masterEffects) fx.apply()
        if (!songMode) {
            // keep the play head inside the (possibly shortened) pattern
            val idx = plan.patternIndexOf(patternId)
            if (idx >= 0) {
                val len = plan.patterns[idx].length.toDouble()
                if (pos >= len) pos %= len
            }
        }
    }

    /** Hard silence: every voice, echo and reverb tail is cut at once. Also the last resort after a NaN. */
    private fun recover(plan: Plan) {
        for (cp in plan.channels) {
            cp.instrument?.reset()
            for (fx in cp.effects) fx.effect.reset()
            cp.runtime.offCount = 0
        }
        for (fx in plan.masterEffects) fx.effect.reset()
        limiter.reset()
    }

    private fun drainCommands(plan: Plan) {
        while (true) {
            val c = commands.poll()
            if (c == CommandQueue.EMPTY) break
            val type = CommandQueue.type(c)
            val value = CommandQueue.value(c)
            when (type) {
                CMD_PLAY -> {
                    if (isPlaying) silence(plan)
                    pos = value.toDouble()
                    isPlaying = true
                }
                CMD_RESUME -> isPlaying = true
                CMD_PAUSE -> {
                    isPlaying = false
                    silence(plan)
                }
                CMD_STOP -> {
                    isPlaying = false
                    pos = value.toDouble()
                    silence(plan)
                }
                CMD_SEEK -> {
                    pos = value.toDouble()
                    if (isPlaying) silence(plan)
                }
                CMD_PANIC -> recover(plan)
                CMD_NOTE_ON, CMD_NOTE_OFF -> {
                    val id = CommandQueue.channel(c)
                    for (cp in plan.channels) {
                        if (cp.id == id) {
                            if (type == CMD_NOTE_ON) {
                                cp.runtime.addEvent(0, EV_ON, CommandQueue.pitch(c), java.lang.Float.intBitsToFloat(value))
                            } else {
                                cp.runtime.addEvent(0, EV_OFF, CommandQueue.pitch(c), 0f)
                            }
                            break
                        }
                    }
                }
            }
        }
    }

    /** Releases every sounding note (natural release tails are kept). */
    private fun silence(plan: Plan) {
        for (cp in plan.channels) {
            cp.instrument?.allNotesOff()
            cp.runtime.offCount = 0
        }
    }

    private fun buildSegments(plan: Plan, n: Int, tpf: Double) {
        var looping: Boolean
        val loopS: Double
        val loopE: Double
        if (!songMode) {
            patternIndex = plan.patternIndexOf(patternId)
            loopS = 0.0
            loopE = if (patternIndex >= 0) plan.patterns[patternIndex].length.toDouble() else plan.ticksPerBar.toDouble()
            looping = true
            if (pos >= loopE) pos %= loopE
        } else {
            loopS = plan.loopStart.toDouble()
            loopE = plan.loopEnd.toDouble()
            looping = plan.loopEnabled && pos < loopE
        }

        var frame = 0
        var p = pos
        var rangeStart = p
        while (frame < n) {
            var count = n - frame
            if (looping) {
                val toEnd = ceil((loopE - p) / tpf).toInt()
                if (toEnd < count) count = max(0, toEnd)
            }
            if (segN == MAX_SEGMENTS - 1) {
                // absurdly short loop: stop splitting, play the rest straight
                count = n - frame
                looping = false
            }
            if (count > 0) {
                segFrame[segN] = frame
                segCount[segN] = count
                segTick[segN] = p
                segRangeStart[segN] = rangeStart
                val end = p + count * tpf
                segRangeEnd[segN] = if (looping) min(end, loopE) else end
                segN++
                frame += count
                p = end
                rangeStart = p
            }
            if (looping && p >= loopE) {
                p = loopS + (p - loopE)
                if (p >= loopE) p = loopS
                // notes sitting exactly on the loop start must not be skipped
                rangeStart = loopS
            }
        }
        pos = p

        val insideLoop = plan.loopEnabled && pos < plan.loopEnd
        if (songMode && stopAtEnd && plan.songEnd > 0 && !insideLoop && pos >= plan.songEnd) {
            isPlaying = false
            pos = 0.0
            silence(plan)
        }
    }

    private fun scheduleSegment(plan: Plan, s: Int, tpf: Double) {
        val rangeEnd = segRangeEnd[s]
        val tick0 = segTick[s]
        val frame0 = segFrame[s]
        val count = segCount[s]

        // ---- metronome (always audible during the count-in, i.e. at negative positions) ----
        if (metronome || segRangeStart[s] < 0.0) {
            var beat = ceil(segRangeStart[s] / PPQ).toLong()
            while (beat * PPQ < rangeEnd) {
                val t = beat * PPQ
                clickAt = (frame0 + ceil((t - tick0) / tpf).toInt()).coerceIn(frame0, frame0 + count - 1)
                clickAccent = t % plan.ticksPerBar == 0L
                beat++
            }
        }

        if (rangeEnd <= 0.0) return // count-in: nothing but the click
        val rangeStart = max(segRangeStart[s], 0.0)

        if (!songMode) {
            if (patternIndex >= 0) {
                val pat = plan.patterns[patternIndex]
                schedulePattern(plan, pat, rangeStart, rangeEnd, 0.0, Int.MAX_VALUE, s, tpf)
                if (pat.lanes.isNotEmpty()) automate(pat, rangeStart)
            }
            return
        }
        for (clip in plan.clips) {
            if (clip.end <= rangeStart || clip.start >= rangeEnd) continue
            val pat = plan.patterns[clip.pattern]
            val len = pat.length.toDouble()
            var g = max(rangeStart, clip.start.toDouble())
            val b = min(rangeEnd, clip.end.toDouble())
            var guard = 0
            while (g < b && guard++ < 64) {
                var local = (g - clip.start) % len
                if (len - local < 1e-6) local = 0.0
                val span = min(b - g, len - local)
                schedulePattern(plan, pat, local, local + span, g - local, clip.end, s, tpf)
                if (pat.lanes.isNotEmpty()) automate(pat, local)
                g += max(span, 1e-6)
            }
        }
    }

    /**
     * Fires the notes of [pat] whose (pattern local) start lies in [from, to).
     * [base] is the song tick of the pattern's local zero.
     */
    private fun schedulePattern(
        plan: Plan, pat: PatternPlan, from: Double, to: Double, base: Double, clipEnd: Int, s: Int, tpf: Double,
    ) {
        val tick0 = segTick[s]
        val frame0 = segFrame[s]
        val last = frame0 + segCount[s] - 1
        val seqs = pat.seqs
        for (ci in seqs.indices) {
            val seq = seqs[ci] ?: continue
            val cp = plan.channels[ci]
            if (cp.mute || (plan.anySolo && !cp.solo)) continue
            val starts = seq.start
            var i = seq.lowerBound(from)
            while (i < starts.size && starts[i] < to) {
                val tick = base + starts[i]
                val frame = (frame0 + ceil((tick - tick0) / tpf).toInt()).coerceIn(frame0, last)
                var length = seq.length[i]
                if (clipEnd != Int.MAX_VALUE) length = min(length, clipEnd - tick.toInt())
                val rt = cp.runtime
                rt.addEvent(frame, EV_ON, seq.pitch[i], seq.velocity[i])
                rt.addOff(seq.pitch[i], frameCounter + frame + max(1L, (length / tpf).toLong()))
                i++
            }
        }
    }

    // ------------------------------------------------------------------ automation (audio thread)

    /*
     * Automation is applied once per block: every pattern that is playing right now writes the
     * values of its curves, then the devices whose values changed get their parameters. A knob
     * that no curve moves any more (the pattern ended, playback stopped, the curve was deleted)
     * goes back to the value the user set.
     */

    private fun beginAutomation(plan: Plan) {
        for (e in plan.autoEntries) e.autoNow = false
        for (rt in plan.autoStrips) {
            rt.autoGainOn = false
            rt.autoPanOn = false
        }
    }

    /** Reads the curves of [pat] at the pattern position [local] (ticks). */
    private fun automate(pat: PatternPlan, local: Double) {
        for (lane in pat.lanes) {
            val v = lane.valueAt(local)
            when (lane.kind) {
                LANE_VOLUME -> {
                    val rt = lane.strip ?: continue
                    rt.autoGain = faderToGain(v)
                    rt.autoGainOn = true
                }
                LANE_PAN -> {
                    val rt = lane.strip ?: continue
                    rt.autoPan = v * 2f - 1f
                    rt.autoPanOn = true
                }
                else -> {
                    val e = lane.entry ?: continue
                    val work = e.auto ?: continue
                    val base = e.applied ?: continue
                    val spec = lane.spec ?: continue
                    if (base.size != work.size) continue
                    if (!e.autoNow) {
                        // start from the knob positions, then let the curves overwrite theirs
                        System.arraycopy(base, 0, work, 0, work.size)
                        e.autoNow = true
                    }
                    work[lane.index] = spec.fromNorm(v)
                }
            }
        }
    }

    private fun finishAutomation(plan: Plan) {
        for (e in plan.autoEntries) {
            if (e.autoNow) {
                val work = e.auto ?: continue
                val sent = e.autoSent ?: continue
                var changed = !e.autoOn
                if (!changed) {
                    for (i in work.indices) {
                        if (work[i] != sent[i]) {
                            changed = true
                            break
                        }
                    }
                }
                if (changed) {
                    e.device.setParams(work)
                    System.arraycopy(work, 0, sent, 0, work.size)
                    e.autoOn = true
                }
            } else if (e.autoOn) {
                e.applied?.let { e.device.setParams(it) }
                e.autoOn = false
            }
        }
    }

    /** Turns note-offs that are due in this block into events. */
    private fun collectNoteOffs(plan: Plan, n: Int) {
        val blockEnd = frameCounter + n
        for (cp in plan.channels) {
            val rt = cp.runtime
            var i = 0
            while (i < rt.offCount) {
                val at = rt.offFrame[i]
                if (at < blockEnd) {
                    rt.addEvent(max(0L, at - frameCounter).toInt(), EV_OFF, rt.offPitch[i], 0f)
                    val lastIdx = --rt.offCount
                    rt.offPitch[i] = rt.offPitch[lastIdx]
                    rt.offFrame[i] = rt.offFrame[lastIdx]
                } else i++
            }
        }
    }

    private fun applyCutGroups(plan: Plan) {
        val chans = plan.channels
        for (cp in chans) {
            if (cp.cutGroup == 0) continue
            val rt = cp.runtime
            for (e in 0 until rt.eventCount) {
                if (rt.evType[e] != EV_ON) continue
                for (other in chans) {
                    if (other !== cp && other.cutGroup == cp.cutGroup) {
                        other.runtime.addEvent(rt.evFrame[e], EV_CHOKE, 0, 0f)
                    }
                }
            }
        }
    }

    private fun renderChannel(plan: Plan, cp: ChannelPlan, n: Int, tpf: Double) {
        val rt = cp.runtime
        val inst = cp.instrument
        val bufL = rt.bufL
        val bufR = rt.bufR
        val hadEvents = rt.eventCount > 0
        var input = false

        val audible = !cp.mute && (!plan.anySolo || cp.solo)
        val hasAudio = isPlaying && songMode && plan.audioClips.isNotEmpty() && audible && hasAudioFor(plan, cp)
        val instActive = inst != null && (inst.active || hadEvents)
        val tail = rt.tailFrames > 0

        if (!instActive && !hasAudio && !tail) {
            // completely silent: skip all processing
            rt.curL = -1f
            return
        }

        java.util.Arrays.fill(bufL, 0, n, 0f)
        java.util.Arrays.fill(bufR, 0, n, 0f)

        if (inst != null && instActive) {
            rt.sortEvents()
            var cursor = 0
            for (e in 0 until rt.eventCount) {
                val at = rt.evFrame[e]
                if (at > cursor) {
                    inst.render(bufL, bufR, cursor, at - cursor)
                    cursor = at
                }
                when (rt.evType[e]) {
                    EV_ON -> {
                        inst.noteOn(rt.evPitch[e], rt.evVelocity[e])
                        rt.triggers++
                    }
                    EV_OFF -> inst.noteOff(rt.evPitch[e])
                    else -> inst.choke()
                }
            }
            if (cursor < n) inst.render(bufL, bufR, cursor, n - cursor)
            input = true
        }

        if (hasAudio) {
            renderAudioClips(plan, cp, tpf)
            input = true
        }

        var any = false
        for (fx in cp.effects) {
            if (fx.enabled) {
                fx.effect.process(bufL, bufR, n, ctx)
                any = true
            }
        }

        // fader + balance with a short ramp (no zipper noise), then sum into the master bus
        var tl = 0f
        var tr = 0f
        if (audible) {
            val gain = if (rt.autoGainOn) rt.autoGain else cp.gain
            val pan = if (rt.autoPanOn) rt.autoPan else cp.pan
            tl = gain * min(1f, 1f - pan)
            tr = gain * min(1f, 1f + pan)
        }
        if (rt.curL < 0f) {
            rt.curL = tl
            rt.curR = tr
        }
        var gl = rt.curL
        var gr = rt.curR
        val sl = (tl - gl) / n
        val sr = (tr - gr) / n
        var peak = 0f
        var raw = 0f
        for (i in 0 until n) {
            gl += sl
            gr += sr
            val xl = bufL[i]
            val xr = bufR[i]
            val a = abs(xl) + abs(xr)
            if (a > raw) raw = a
            val l = xl * gl
            val r = xr * gr
            masterL[i] += l
            masterR[i] += r
            val m = max(abs(l), abs(r))
            if (m > peak) peak = m
        }
        rt.curL = tl
        rt.curR = tr
        if (peak > rt.peak) rt.peak = peak

        // Keep processing while the effects still ring out (reverb / delay tails). The hold
        // time is longer than the longest echo, so a quiet gap between repeats is not cut.
        if (input) {
            rt.tailFrames = if (any) sampleRate * TAIL_SECONDS else 0
        } else if (raw > TAIL_THRESHOLD) {
            rt.tailFrames = sampleRate * TAIL_SECONDS
        } else {
            rt.tailFrames = max(0, rt.tailFrames - n)
        }
    }

    private fun hasAudioFor(plan: Plan, cp: ChannelPlan): Boolean {
        val chans = plan.channels
        for (ac in plan.audioClips) if (chans[ac.channel] === cp) return true
        return false
    }

    private fun renderAudioClips(plan: Plan, cp: ChannelPlan, tpf: Double) {
        val rt = cp.runtime
        val secPerTick = 60.0 / (plan.bpm * PPQ)
        val fade = max(8, sampleRate / 250) // 4 ms edges
        for (ac in plan.audioClips) {
            if (plan.channels[ac.channel] !== cp) continue
            val data = ac.sample
            val left = data.left
            val right = data.right ?: left
            val rate = data.sampleRate.toDouble() / sampleRate
            val k = SampleData.TO_FLOAT * ac.gain
            for (s in 0 until segN) {
                val tick0 = segTick[s]
                val endTick = tick0 + segCount[s] * tpf
                if (ac.end <= tick0 || ac.start >= endTick) continue
                // frames relative to the clip start / end
                val sinceStart = (tick0 - ac.start) / tpf
                var first = 0
                if (sinceStart < 0) first = ceil(-sinceStart).toInt()
                val untilEnd = ceil((ac.end - tick0) / tpf).toInt()
                val lastEx = min(segCount[s], untilEnd)
                var src = ((tick0 - ac.start) * secPerTick + ac.offset) * data.sampleRate + first * rate
                val out0 = segFrame[s]
                for (f in first until lastEx) {
                    val ip = src.toInt()
                    if (ip >= left.size - 1) break
                    if (ip >= 0) {
                        val frac = (src - ip).toFloat()
                        var g = k
                        val fromStart = sinceStart + f
                        val toEnd = untilEnd - f
                        if (fromStart < fade) g *= (fromStart / fade).toFloat().coerceIn(0f, 1f)
                        if (toEnd < fade) g *= toEnd.toFloat() / fade
                        val l0 = left[ip].toFloat()
                        rt.bufL[out0 + f] += (l0 + (left[ip + 1] - l0) * frac) * g
                        val r0 = right[ip].toFloat()
                        rt.bufR[out0 + f] += (r0 + (right[ip + 1] - r0) * frac) * g
                    }
                    src += rate
                }
            }
        }
    }

    private fun renderClick(n: Int) {
        if (clickAt >= 0) {
            clickEnv = if (clickAccent) 0.5f else 0.32f
            clickInc = (if (clickAccent) 1760f else 1175f) / sampleRate
            clickPhase = 0f
        }
        if (clickEnv < 0.0005f) return
        val from = if (clickAt >= 0) clickAt else 0
        for (i in from until n) {
            val s = FastMath.sin01(clickPhase) * clickEnv
            clickPhase += clickInc
            if (clickPhase >= 1f) clickPhase -= 1f
            clickEnv *= clickDecay
            masterL[i] += s
            masterR[i] += s
        }
    }

    // ------------------------------------------------------------------ plan data

    private class EffectPlan(val effect: Effect, val entry: DeviceEntry, val params: FloatArray, val enabled: Boolean) {
        fun apply() {
            if (entry.applied !== params) {
                effect.setParams(params)
                entry.applied = params
                entry.autoOn = false // automation has to be laid over the new knob values again
            }
        }
    }

    private class ChannelPlan(
        val id: Int,
        val runtime: ChannelRuntime,
        val instrument: Instrument?,
        val instrumentEntry: DeviceEntry?,
        val instrumentParams: FloatArray?,
        val gain: Float,
        val pan: Float,
        val mute: Boolean,
        val solo: Boolean,
        val effects: Array<EffectPlan>,
        val cutGroup: Int,
        val sample: SampleData?,
    )

    private class PatternPlan(val id: Int, val length: Int, val seqs: Array<NoteSeq?>, val lanes: Array<LanePlan>)

    /** One automation curve, ready for the audio thread. Values are knob positions 0..1. */
    private class LanePlan(
        val kind: Int,
        val strip: ChannelRuntime?,
        val entry: DeviceEntry?,
        val index: Int,
        val spec: ParamSpec?,
        val ticks: IntArray,
        val values: FloatArray,
    ) {
        fun valueAt(tick: Double): Float {
            val last = ticks.size - 1
            if (tick <= ticks[0]) return values[0]
            if (tick >= ticks[last]) return values[last]
            var lo = 0
            var hi = last
            while (hi - lo > 1) {
                val mid = (lo + hi) ushr 1
                if (ticks[mid] <= tick) lo = mid else hi = mid
            }
            val f = ((tick - ticks[lo]) / (ticks[hi] - ticks[lo])).toFloat()
            return values[lo] + (values[hi] - values[lo]) * f
        }
    }

    private class ClipPlan(val start: Int, val end: Int, val pattern: Int)

    private class AudioClipPlan(
        val start: Int, val end: Int, val channel: Int, val sample: SampleData, val offset: Double, val gain: Float,
    )

    private class Plan(
        val bpm: Double,
        val ticksPerBar: Int,
        val channels: Array<ChannelPlan>,
        val patterns: Array<PatternPlan>,
        val clips: Array<ClipPlan>,
        val audioClips: Array<AudioClipPlan>,
        val masterEffects: Array<EffectPlan>,
        val masterGain: Float,
        val songEnd: Int,
        val loopEnabled: Boolean,
        val loopStart: Int,
        val loopEnd: Int,
        val anySolo: Boolean,
        /** Devices and mixer strips that some automation curve of this project moves. */
        val autoEntries: Array<DeviceEntry>,
        val autoStrips: Array<ChannelRuntime>,
    ) {
        fun patternIndexOf(id: Int): Int {
            for (i in patterns.indices) if (patterns[i].id == id) return i
            return -1
        }

        companion object {
            val EMPTY = Plan(
                120.0, 4 * PPQ, emptyArray(), emptyArray(), emptyArray(), emptyArray(), emptyArray(),
                1f, 0, false, 0, 0, false, emptyArray(), emptyArray(),
            )
        }
    }

    /** Per-channel state owned by the audio thread (buffers, pending events, meters). */
    private class ChannelRuntime {
        var generation = 0
        val bufL = FloatArray(MAX_BLOCK)
        val bufR = FloatArray(MAX_BLOCK)
        var curL = -1f
        var curR = -1f
        var tailFrames = 0

        // fader and balance while an automation curve moves them (set anew for every block)
        var autoGainOn = false
        var autoGain = 0f
        var autoPanOn = false
        var autoPan = 0f

        @Volatile
        var peak = 0f

        @Volatile
        var triggers = 0

        // events of the current block
        val evFrame = IntArray(MAX_EVENTS)
        val evType = IntArray(MAX_EVENTS)
        val evPitch = IntArray(MAX_EVENTS)
        val evVelocity = FloatArray(MAX_EVENTS)
        var eventCount = 0

        // note-offs waiting for their time
        val offPitch = IntArray(MAX_PENDING)
        val offFrame = LongArray(MAX_PENDING)
        var offCount = 0

        fun addEvent(frame: Int, type: Int, pitch: Int, velocity: Float) {
            if (eventCount >= MAX_EVENTS) return
            val i = eventCount++
            evFrame[i] = frame
            evType[i] = type
            evPitch[i] = pitch
            evVelocity[i] = velocity
        }

        fun addOff(pitch: Int, frame: Long) {
            for (i in 0 until offCount) {
                if (offPitch[i] == pitch) {
                    offFrame[i] = frame // the newer note replaces the older one
                    return
                }
            }
            if (offCount >= MAX_PENDING) return
            offPitch[offCount] = pitch
            offFrame[offCount] = frame
            offCount++
        }

        /** Insertion sort by time; at equal times note-offs and chokes go before note-ons. */
        fun sortEvents() {
            for (i in 1 until eventCount) {
                val f = evFrame[i]
                val t = evType[i]
                val p = evPitch[i]
                val v = evVelocity[i]
                val key = f * 2 + (if (t == EV_ON) 1 else 0)
                var j = i - 1
                while (j >= 0 && evFrame[j] * 2 + (if (evType[j] == EV_ON) 1 else 0) > key) {
                    evFrame[j + 1] = evFrame[j]
                    evType[j + 1] = evType[j]
                    evPitch[j + 1] = evPitch[j]
                    evVelocity[j + 1] = evVelocity[j]
                    j--
                }
                evFrame[j + 1] = f
                evType[j + 1] = t
                evPitch[j + 1] = p
                evVelocity[j + 1] = v
            }
        }
    }

    companion object {
        private const val MAX_EVENTS = 192
        private const val MAX_PENDING = 128
        private const val MAX_SEGMENTS = 8
        private const val TAIL_THRESHOLD = 0.00002f
        private const val TAIL_SECONDS = 3

        private const val EV_ON = 1
        private const val EV_OFF = 2
        private const val EV_CHOKE = 3

        private const val LANE_PARAM = 0
        private const val LANE_VOLUME = 1
        private const val LANE_PAN = 2
        private val NO_LANES = emptyArray<LanePlan>()

        /** Level of auditioned sounds: 16-bit samples to a little below full scale. */
        private const val AUDITION_GAIN = 0.8f / 32768f

        private const val CMD_PLAY = 1
        private const val CMD_RESUME = 2
        private const val CMD_PAUSE = 3
        private const val CMD_STOP = 4
        private const val CMD_SEEK = 5
        private const val CMD_NOTE_ON = 6
        private const val CMD_NOTE_OFF = 7
        private const val CMD_PANIC = 8
    }
}

/** Notes of one channel in one pattern, sorted by start, with swing already applied. */
class NoteSeq(val start: IntArray, val length: IntArray, val pitch: IntArray, val velocity: FloatArray) {

    /** Index of the first note with start >= [tick]. */
    fun lowerBound(tick: Double): Int {
        var lo = 0
        var hi = start.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (start[mid] < tick) lo = mid + 1 else hi = mid
        }
        return lo
    }

    companion object {
        val EMPTY = NoteSeq(IntArray(0), IntArray(0), IntArray(0), FloatArray(0))

        fun build(notes: List<Note>, swing: Float): NoteSeq {
            val sorted = notes.filter { it.length > 0 && it.start >= 0 }.sortedBy { it.start }
            val n = sorted.size
            val start = IntArray(n)
            val length = IntArray(n)
            val pitch = IntArray(n)
            val velocity = FloatArray(n)
            for (i in 0 until n) {
                val note = sorted[i]
                val s = swingTick(note.start, swing)
                start[i] = s
                length[i] = max(1, swingTick(note.end, swing) - s)
                pitch[i] = note.pitch.coerceIn(0, 127)
                velocity[i] = note.velocity.coerceIn(0.01f, 1f)
            }
            return NoteSeq(start, length, pitch, velocity)
        }
    }
}

/**
 * Multi-producer / single-consumer queue of packed 64-bit commands.
 * Producers (UI, MIDI) take a tiny lock among themselves; the audio thread never blocks.
 */
class CommandQueue(capacity: Int) {
    private val size = Integer.highestOneBit(capacity)
    private val mask = (size - 1).toLong()
    private val buffer = AtomicLongArray(size)

    @Volatile
    private var head = 0L

    @Volatile
    private var tail = 0L

    fun offer(type: Int, channel: Int, pitch: Int, value: Int): Boolean = synchronized(this) {
        val t = tail
        if (t - head >= size) return false
        val packed = (type.toLong() shl 60) or
            ((channel.toLong() and 0xFFFFF) shl 40) or
            ((pitch.toLong() and 0xFF) shl 32) or
            (value.toLong() and 0xFFFFFFFFL)
        buffer.set((t and mask).toInt(), packed)
        tail = t + 1
        true
    }

    /** Consumer side; returns [EMPTY] when there is nothing to read. */
    fun poll(): Long {
        val h = head
        if (h == tail) return EMPTY
        val v = buffer.get((h and mask).toInt())
        head = h + 1
        return v
    }

    companion object {
        /** Never a valid command: command types start at 1. */
        const val EMPTY = 0L
        fun type(c: Long): Int = (c ushr 60).toInt() and 0xF
        fun channel(c: Long): Int = (c ushr 40).toInt() and 0xFFFFF
        fun pitch(c: Long): Int = (c ushr 32).toInt() and 0xFF
        fun value(c: Long): Int = c.toInt()
    }
}
