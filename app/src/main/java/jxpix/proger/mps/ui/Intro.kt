package jxpix.proger.mps.ui

import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.net.Uri
import android.widget.VideoView
import androidx.activity.compose.BackHandler
import androidx.annotation.VisibleForTesting
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import jxpix.proger.mps.R
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The logo video that opens the app: whether it is shown in this run of the app, and how.
 *
 * It is shown once per start: not again after the screen was rebuilt (a rotation, a change of
 * language), not when the user comes back to the app they left open, not when Android brings
 * the app back after it was closed in the background (the user returns to what they were doing),
 * and not when a link opens the app. An app that was closed starts with the video again.
 */
object Intro {
    /** True while the video still has to be shown, or is being shown. */
    var pending by mutableStateOf(false)
        private set

    /** Played without its sound. */
    var muted = false
        private set

    /** Where the video was when the screen was rebuilt, so that it goes on from there. */
    var positionMs = 0

    private var decided = false

    /** Decides once whether the video is shown; later calls (the screen being rebuilt) change nothing. */
    fun decide(enabled: Boolean, fresh: Boolean, fromLauncher: Boolean, muted: Boolean) {
        if (decided) return
        decided = true
        pending = enabled && fresh && fromLauncher
        this.muted = muted
    }

    /**
     * The video keeps its sound to itself when the phone is silenced, and when something else is
     * playing already: a podcast or a song does not get a logo sting on top of it.
     */
    fun quiet(audio: AudioManager?): Boolean =
        audio == null || audio.ringerMode != AudioManager.RINGER_MODE_NORMAL || audio.isMusicActive

    /** The video has ended, was skipped, or the app went to the background. */
    fun done() {
        pending = false
        positionMs = 0
    }

    /**
     * The user closed the app (Back on its first screen, on the versions of Android where that
     * closes it) while Android keeps the process for a while: the next start is a start like any
     * other, with the video.
     */
    fun closed() {
        decided = false
        pending = false
        positionMs = 0
    }

    /** As if the app had just been started. */
    @VisibleForTesting
    fun reset() {
        closed()
        muted = false
    }
}

/** A video that shows no picture this long after it was asked for is given up on: the app must not wait for it. */
const val INTRO_START_TIMEOUT_MS = 2500L

/** When the hint "tap to skip" appears. */
const val INTRO_HINT_AFTER_MS = 1400L

private const val GETTING_READY = 0
private const val PLAYING = 1
private const val LEAVING = 2

/**
 * The logo video over the whole screen. It is black around the logo, like the window the app
 * opens with, so that the logo seems to appear by itself. A tap or the Back button skips it;
 * a video that cannot be played is skipped by itself. When it is over, the screen fades through
 * black into the app, which has been getting ready underneath.
 *
 * @param muted play without sound
 * @param startAtMs where to go on from, after the screen was rebuilt in the middle of the video
 * @param onPosition told where the video was when the screen is taken apart before the end
 */
@Composable
fun IntroOverlay(muted: Boolean, startAtMs: Int, onPosition: (Int) -> Unit, onDone: () -> Unit) {
    var stage by remember { mutableIntStateOf(GETTING_READY) }
    // the player has been told to start (the first picture follows a moment later)
    var started by remember { mutableStateOf(false) }
    var durationMs by remember { mutableIntStateOf(10_000) }
    // the view of the video is taken out before the overlay fades: a video surface cannot be faded
    var showVideo by remember { mutableStateOf(true) }
    var hint by remember { mutableStateOf(false) }
    var view by remember { mutableStateOf<VideoView?>(null) }
    // black over the video (1 hides it), and the opacity of everything once the video is gone
    val cover = remember { Animatable(1f) }
    val whole = remember { Animatable(1f) }
    val finish by rememberUpdatedState(onDone)
    val savePosition by rememberUpdatedState(onPosition)
    val leave = {
        if (stage != LEAVING) {
            stage = LEAVING
            // the sound ends with the tap, not a frame later
            view?.pause()
        }
    }

    LaunchedEffect(stage) {
        when (stage) {
            GETTING_READY -> {
                delay(INTRO_START_TIMEOUT_MS)
                if (!started) leave()
            }
            PLAYING -> {
                launch { cover.animateTo(0f, tween(260)) }
                delay(INTRO_HINT_AFTER_MS)
                hint = true
                // a player that never reports the end
                delay(durationMs + 1500L)
                leave()
            }
            else -> {
                // the picture goes to black, and black gives way to the app
                cover.animateTo(1f, tween(160))
                view?.stopPlayback()
                showVideo = false
                whole.animateTo(0f, tween(280))
                finish()
            }
        }
    }
    // some players do not say when the first picture is on the screen: it is taken to be there shortly after the start
    LaunchedEffect(started) {
        if (started) {
            delay(450)
            if (stage == GETTING_READY) stage = PLAYING
        }
    }
    BackHandler { leave() }

    val label = stringResource(R.string.intro_label)
    val skip = stringResource(R.string.intro_skip_action)
    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .then(if (showVideo) Modifier else Modifier.graphicsLayer { alpha = whole.value })
            .background(Color.Black)
            .clipToBounds()
            .testTag("intro"),
        contentAlignment = Alignment.Center,
    ) {
        if (showVideo) {
            // The picture is square with the logo in its middle and black above and below. On a wide screen it
            // may be larger than the screen is high, so that the logo is not lost in the middle of it.
            val side = minOf(maxWidth, maxHeight * 1.6f)
            AndroidView(
                factory = { context ->
                    VideoView(context).apply {
                        // whoever is playing keeps playing: the video never asks for the sound of the phone
                        setAudioFocusRequest(AudioManager.AUDIOFOCUS_NONE)
                        setAudioAttributes(
                            AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MOVIE).build()
                        )
                        setOnPreparedListener { player ->
                            // skipped while it was still getting ready: no sound after the tap
                            if (stage == LEAVING) return@setOnPreparedListener
                            if (muted) player.setVolume(0f, 0f)
                            if (player.duration > 0) durationMs = player.duration
                            if (startAtMs > 0) seekTo(startAtMs)
                            start()
                            started = true
                        }
                        setOnInfoListener { _, what, _ ->
                            if (what == MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START && stage == GETTING_READY) stage = PLAYING
                            false
                        }
                        setOnCompletionListener { leave() }
                        setOnErrorListener { _, _, _ ->
                            leave()
                            true
                        }
                        setVideoURI(Uri.parse("android.resource://${context.packageName}/${R.raw.intro}"))
                        view = this
                    }
                },
                modifier = Modifier.requiredSize(side),
                onRelease = {
                    // the screen is taken apart in the middle of the video (the phone was turned): it goes on from here
                    if (stage != LEAVING) savePosition(it.currentPosition)
                    it.stopPlayback()
                },
            )
        }
        Box(Modifier.fillMaxSize().graphicsLayer { alpha = cover.value }.background(Color.Black))
        // the whole screen is the button "skip": it lies over the video, so the tap never ends up in the video view
        Box(
            Modifier
                .fillMaxSize()
                .semantics { contentDescription = label }
                .clickable(
                    interactionSource = remember { MutableInteractionSource() }, indication = null,
                    onClickLabel = skip, role = Role.Button, onClick = leave,
                )
        )
        AnimatedVisibility(
            hint && stage == PLAYING, Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 20.dp),
            enter = fadeIn(tween(400)), exit = fadeOut(tween(120)),
        ) {
            Text(stringResource(R.string.intro_skip), color = Color.White.copy(alpha = 0.55f), fontSize = 13.sp)
        }
    }
}
