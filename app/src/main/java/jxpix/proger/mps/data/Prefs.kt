package jxpix.proger.mps.data

import android.annotation.SuppressLint
import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import jxpix.proger.mps.audio.ExportFormat
import jxpix.proger.mps.audio.LatencyProfile

enum class ThemeMode { SYSTEM, LIGHT, DARK }

/** Which way the screen is held: on its side (the most room for the grids), any way, or upright. */
enum class OrientationMode { LANDSCAPE, AUTO, PORTRAIT }

/** Small persistent settings, exposed as observable state for the UI. */
class Prefs(context: Context) {
    private val sp = context.applicationContext.getSharedPreferences("mps", Context.MODE_PRIVATE)

    private inline fun <reified T : Enum<T>> enumOf(key: String, default: T): T =
        try {
            enumValueOf<T>(sp.getString(key, null) ?: default.name)
        } catch (e: IllegalArgumentException) {
            default
        }

    /** White-pink, black-pink, or whatever the system uses. */
    var theme by mutableStateOf(enumOf("theme", ThemeMode.LIGHT))
        private set

    var latency by mutableStateOf(enumOf("latency", LatencyProfile.AUTO))
        private set

    var orientation by mutableStateOf(enumOf("orientation", OrientationMode.LANDSCAPE))
        private set

    /** The choices of the last export, offered again next time. */
    var exportFormat by mutableStateOf(enumOf("exportFormat", ExportFormat.WAV))
        private set
    var exportKbps by mutableIntStateOf(sp.getInt("exportKbps", 192))
        private set

    /** "" = follow the system, otherwise a language tag such as "ru". */
    var language by mutableStateOf(sp.getString("language", "") ?: "")
        private set

    var metronome by mutableStateOf(sp.getBoolean("metronome", false))
        private set

    var countIn by mutableStateOf(sp.getBoolean("countIn", true))
        private set

    /** Snap recorded notes to the grid. */
    var quantize by mutableStateOf(sp.getBoolean("quantize", true))
        private set

    var keepScreenOn by mutableStateOf(sp.getBoolean("keepScreenOn", true))
        private set

    /** Quiet microphone takes are made louder automatically. */
    var autoLevel by mutableStateOf(sp.getBoolean("autoLevel", true))
        private set

    /** Extra shift applied to microphone recordings, in milliseconds (positive = earlier). */
    var recordOffsetMs by mutableIntStateOf(sp.getInt("recordOffsetMs", 0))
        private set

    var hintsSeen by mutableStateOf(sp.getStringSet("hints", emptySet())?.toSet() ?: emptySet())
        private set

    var lastProject by mutableStateOf(sp.getString("lastProject", null))
        private set

    fun updateTheme(value: ThemeMode) { theme = value; sp.edit().putString("theme", value.name).apply() }
    fun updateOrientation(value: OrientationMode) { orientation = value; sp.edit().putString("orientation", value.name).apply() }
    fun updateExport(format: ExportFormat, kbps: Int) {
        exportFormat = format
        exportKbps = kbps
        sp.edit().putString("exportFormat", format.name).putInt("exportKbps", kbps).apply()
    }
    fun updateLatency(value: LatencyProfile) { latency = value; sp.edit().putString("latency", value.name).apply() }
    /** Written synchronously: the activity is recreated right after and reads the value back at once. */
    @SuppressLint("ApplySharedPref")
    fun updateLanguage(value: String) { language = value; sp.edit().putString("language", value).commit() }
    fun updateMetronome(value: Boolean) { metronome = value; sp.edit().putBoolean("metronome", value).apply() }
    fun updateAutoLevel(value: Boolean) { autoLevel = value; sp.edit().putBoolean("autoLevel", value).apply() }
    fun updateCountIn(value: Boolean) { countIn = value; sp.edit().putBoolean("countIn", value).apply() }
    fun updateQuantize(value: Boolean) { quantize = value; sp.edit().putBoolean("quantize", value).apply() }
    fun updateKeepScreenOn(value: Boolean) { keepScreenOn = value; sp.edit().putBoolean("keepScreenOn", value).apply() }
    fun updateRecordOffset(value: Int) { recordOffsetMs = value; sp.edit().putInt("recordOffsetMs", value).apply() }
    fun updateLastProject(value: String?) { lastProject = value; sp.edit().putString("lastProject", value).apply() }

    fun markHintSeen(id: String) {
        if (id in hintsSeen) return
        hintsSeen = hintsSeen + id
        sp.edit().putStringSet("hints", hintsSeen).apply()
    }

    companion object {
        /** Read before any UI exists (the activity needs it to pick the language of its resources). */
        fun languageOf(context: Context): String =
            context.getSharedPreferences("mps", Context.MODE_PRIVATE).getString("language", "") ?: ""
    }
}
