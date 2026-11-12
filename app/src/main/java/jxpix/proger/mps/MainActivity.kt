package jxpix.proger.mps

import jxpix.proger.mps.data.OrientationMode
import androidx.compose.runtime.LaunchedEffect
import android.content.pm.ActivityInfo
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.media.AudioManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.DisposableEffect
import jxpix.proger.mps.data.Prefs
import jxpix.proger.mps.data.ThemeMode
import jxpix.proger.mps.engine.EngineWarmup
import jxpix.proger.mps.studio.Studio
import jxpix.proger.mps.ui.App
import jxpix.proger.mps.ui.Intro
import jxpix.proger.mps.ui.IntroOverlay
import jxpix.proger.mps.ui.theme.MpsTheme
import java.util.Locale

class MainActivity : ComponentActivity() {

    private val studio: Studio get() = (application as MpsApp).studio

    /** The language can be chosen inside the app, independently of the phone's language. */
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(localized(newBase, Prefs.languageOf(newBase)))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // the volume buttons always control the music volume while the app is open
        volumeControlStream = AudioManager.STREAM_MUSIC

        // asked before the app opens its own sound output, which would count as "something is playing"
        val quiet = Intro.quiet(getSystemService(AudioManager::class.java))
        val studio = studio
        // the logo video: once per start of the app, not when Android brings the app back and not when a link opens it
        Intro.decide(
            enabled = studio.prefs.intro,
            fresh = savedInstanceState == null,
            fromLauncher = intent?.action == Intent.ACTION_MAIN && intent?.data == null,
            muted = quiet,
        )
        applyOrientation(studio.prefs.orientation)
        // compile the sound engine in the background while the user is still looking at the start screen
        EngineWarmup.start(studio.output.sampleRate, studio.output.framesPerBurst)
        // Texts created by the studio (names of new patterns, messages) follow the chosen language.
        // An application-level context is used so that the activity is not kept alive by the studio.
        val textContext = localized(applicationContext, studio.prefs.language)
        studio.strings = { textContext.getString(it) }

        setContent {
            val orientation = studio.prefs.orientation
            LaunchedEffect(orientation) { applyOrientation(orientation) }
            val dark = when (studio.prefs.theme) {
                ThemeMode.SYSTEM -> isSystemInDarkTheme()
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
            }
            DisposableEffect(dark) {
                val style = if (dark) SystemBarStyle.dark(Color.TRANSPARENT) else SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
                enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
                // no flash of the wrong colour behind the UI (keyboard animation, rotation)
                window.setBackgroundDrawable(ColorDrawable(if (dark) Color.BLACK else Color.WHITE))
                onDispose {}
            }
            MpsTheme(dark) {
                App(studio, onLanguageChanged = { recreate() })
            }
            // over the app, which gets ready underneath in the meantime
            if (Intro.pending) {
                IntroOverlay(Intro.muted, Intro.positionMs, onPosition = { if (Intro.pending) Intro.positionMs = it }, onDone = { Intro.done() })
            }
        }
        handleLink(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleLink(intent)
    }

    /** The browser comes back here after "Sign in with Google" in the community section. */
    private fun handleLink(intent: Intent?) {
        val data = intent?.data ?: return
        if (data.scheme == "jxpix.proger.mps" && data.host == "auth") {
            // once is enough: the activity may be recreated with the same intent
            intent.data = null
            studio.cloud.onAuthRedirect(data)
        }
    }

    override fun onStart() {
        super.onStart()
        studio.startAudio()
    }

    override fun onStop() {
        super.onStop()
        // A music editor has no reason to make sound (or use the battery) in the background.
        if (!isChangingConfigurations) {
            studio.stopAudio()
            // whoever left during the logo video comes back to the app, not to the rest of the video
            Intro.done()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // closed by the user, not rebuilt: the next start opens with the logo video again
        if (isFinishing) Intro.closed()
    }

    /**
     * A studio is at its best with the phone on its side: more steps, more bars, more mixer strips
     * fit the screen. That is how the app opens; the settings offer auto-rotation and portrait too.
     */
    @SuppressLint("SourceLockedOrientationActivity")
    private fun applyOrientation(mode: OrientationMode) {
        val wanted = when (mode) {
            OrientationMode.LANDSCAPE -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            OrientationMode.PORTRAIT -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
            OrientationMode.AUTO -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
        if (requestedOrientation != wanted) requestedOrientation = wanted
    }

    private fun localized(base: Context, language: String): Context {
        if (language.isEmpty()) return base
        val locale = Locale.forLanguageTag(language)
        Locale.setDefault(locale)
        val config = Configuration(base.resources.configuration)
        config.setLocale(locale)
        return base.createConfigurationContext(config)
    }
}
