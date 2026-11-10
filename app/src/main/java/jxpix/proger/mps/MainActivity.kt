package jxpix.proger.mps

import android.content.Context
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
import jxpix.proger.mps.studio.Studio
import jxpix.proger.mps.ui.App
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

        val studio = studio
        // Texts created by the studio (names of new patterns, messages) follow the chosen language.
        // An application-level context is used so that the activity is not kept alive by the studio.
        val textContext = localized(applicationContext, studio.prefs.language)
        studio.strings = { textContext.getString(it) }

        setContent {
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
        }
    }

    override fun onStart() {
        super.onStart()
        studio.startAudio()
    }

    override fun onStop() {
        super.onStop()
        // A music editor has no reason to make sound (or use the battery) in the background.
        if (!isChangingConfigurations) studio.stopAudio()
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
