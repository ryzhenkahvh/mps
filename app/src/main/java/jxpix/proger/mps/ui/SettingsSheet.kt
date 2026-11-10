package jxpix.proger.mps.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import jxpix.proger.mps.R
import jxpix.proger.mps.audio.LatencyProfile
import jxpix.proger.mps.data.ThemeMode
import jxpix.proger.mps.studio.Studio
import jxpix.proger.mps.ui.components.AppSheet
import jxpix.proger.mps.ui.components.IconBtn
import jxpix.proger.mps.ui.components.SheetDivider
import jxpix.proger.mps.ui.components.SheetItem
import jxpix.proger.mps.ui.components.SheetLabel
import jxpix.proger.mps.ui.theme.Mps
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

@Composable
fun SettingsSheet(studio: Studio, onDismiss: () -> Unit, onLanguageChanged: () -> Unit) {
    val c = Mps.colors
    val prefs = studio.prefs
    val context = LocalContext.current
    AppSheet(onDismiss, title = stringResource(R.string.settings)) {
        // ---- look
        SheetLabel(stringResource(R.string.settings_look))
        Option(stringResource(R.string.theme_light), prefs.theme == ThemeMode.LIGHT, Ic.Light) { prefs.updateTheme(ThemeMode.LIGHT) }
        Option(stringResource(R.string.theme_dark), prefs.theme == ThemeMode.DARK, Ic.Dark) { prefs.updateTheme(ThemeMode.DARK) }
        Option(stringResource(R.string.theme_system), prefs.theme == ThemeMode.SYSTEM, Ic.Settings) { prefs.updateTheme(ThemeMode.SYSTEM) }

        // ---- language
        SheetDivider()
        SheetLabel(stringResource(R.string.settings_language))
        val setLanguage = { tag: String ->
            if (prefs.language != tag) {
                prefs.updateLanguage(tag)
                onDismiss()
                onLanguageChanged()
            }
        }
        Option(stringResource(R.string.lang_system), prefs.language == "", Ic.Language) { setLanguage("") }
        Option("English", prefs.language == "en", null) { setLanguage("en") }
        Option("Русский", prefs.language == "ru", null) { setLanguage("ru") }

        // ---- sound
        SheetDivider()
        SheetLabel(stringResource(R.string.settings_sound))
        Text(
            stringResource(R.string.latency), Modifier.padding(horizontal = 20.dp, vertical = 4.dp), color = c.text,
            fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
        )
        val setLatency = { p: LatencyProfile ->
            prefs.updateLatency(p)
            studio.output.profile = p
        }
        Option(stringResource(R.string.latency_auto), prefs.latency == LatencyProfile.AUTO, null, stringResource(R.string.latency_auto_desc)) { setLatency(LatencyProfile.AUTO) }
        Option(stringResource(R.string.latency_low), prefs.latency == LatencyProfile.LOW, null, stringResource(R.string.latency_low_desc)) { setLatency(LatencyProfile.LOW) }
        Option(stringResource(R.string.latency_balanced), prefs.latency == LatencyProfile.BALANCED, null, stringResource(R.string.latency_balanced_desc)) { setLatency(LatencyProfile.BALANCED) }
        Option(stringResource(R.string.latency_safe), prefs.latency == LatencyProfile.SAFE, null, stringResource(R.string.latency_safe_desc)) { setLatency(LatencyProfile.SAFE) }
        AudioStats(studio)

        // ---- recording
        SheetDivider()
        SheetLabel(stringResource(R.string.settings_recording))
        SwitchRow(stringResource(R.string.set_count_in), stringResource(R.string.set_count_in_desc), prefs.countIn) { prefs.updateCountIn(it) }
        SwitchRow(stringResource(R.string.set_quantize), stringResource(R.string.set_quantize_desc), prefs.quantize) { prefs.updateQuantize(it) }
        Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.set_mic_offset), color = c.text, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                Text(stringResource(R.string.set_mic_offset_desc), color = c.textDim, fontSize = 12.sp, lineHeight = 16.sp)
            }
            IconBtn(Ic.Remove, "−10", { prefs.updateRecordOffset((prefs.recordOffsetMs - 10).coerceIn(-300, 300)) })
            Text(
                stringResource(R.string.ms_value, prefs.recordOffsetMs), Modifier.width(64.dp), color = c.text, fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold, textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
            IconBtn(Ic.Add, "+10", { prefs.updateRecordOffset((prefs.recordOffsetMs + 10).coerceIn(-300, 300)) })
        }
        SwitchRow(stringResource(R.string.set_keep_screen), null, prefs.keepScreenOn) { prefs.updateKeepScreenOn(it) }

        // ---- midi
        SheetDivider()
        SheetLabel(stringResource(R.string.settings_midi))
        val midi = studio.midi.deviceNames
        if (midi.isEmpty()) {
            Text(stringResource(R.string.midi_none), Modifier.padding(horizontal = 20.dp, vertical = 6.dp), color = c.textDim, fontSize = 13.sp, lineHeight = 18.sp)
        } else {
            for (name in midi) SheetItem(name, onClick = {}, icon = Ic.Usb)
        }

        // ---- about
        SheetDivider()
        SheetLabel(stringResource(R.string.settings_about))
        val version = remember {
            try {
                context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: ""
            } catch (_: Exception) {
                ""
            }
        }
        Text(
            "MPS · " + stringResource(R.string.about_version, version), Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
            color = c.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
        )
        Text(stringResource(R.string.about_plugins), Modifier.padding(horizontal = 20.dp, vertical = 4.dp), color = c.textDim, fontSize = 13.sp, lineHeight = 18.sp)
        Spacer(Modifier.size(8.dp))
    }
}

/** Live numbers from the audio output, so the effect of the latency setting is visible. */
@Composable
private fun AudioStats(studio: Studio) {
    val c = Mps.colors
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(500)
            tick++
        }
    }
    val out = studio.output
    @Suppress("UNUSED_EXPRESSION") tick
    Column(
        Modifier.padding(horizontal = 20.dp, vertical = 8.dp).fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(c.surface).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        val style = androidx.compose.ui.text.TextStyle(color = c.textDim, fontSize = 12.sp, lineHeight = 17.sp)
        Text(stringResource(R.string.stats_output, out.sampleRate, out.bufferFrames), style = style)
        Text(stringResource(R.string.stats_latency, out.latencyMs.roundToInt()), style = style)
        Text(stringResource(if (out.lowLatency) R.string.stats_fast_yes else R.string.stats_fast_no), style = style)
        Text(stringResource(R.string.stats_load, (out.load * 100f).roundToInt().coerceIn(0, 999)), style = style)
        Text(stringResource(R.string.stats_underruns, out.underruns), style = style)
    }
}

@Composable
private fun Option(title: String, selected: Boolean, icon: ImageVector?, description: String? = null, onClick: () -> Unit) {
    val c = Mps.colors
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(role = Role.RadioButton, onClick = onClick).padding(horizontal = 20.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(20.dp).clip(CircleShape).background(if (selected) c.accent else c.surfaceHigh),
            contentAlignment = Alignment.Center,
        ) {
            if (selected) Icon(Ic.Check, null, Modifier.size(14.dp), tint = c.onAccent)
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = c.text, fontSize = 15.sp, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
            if (description != null) Text(description, color = c.textDim, fontSize = 12.sp, lineHeight = 16.sp)
        }
        if (icon != null) Icon(icon, null, Modifier.size(20.dp), tint = c.textDim)
    }
}

@Composable
fun SwitchRow(title: String, description: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    val c = Mps.colors
    Row(
        Modifier.fillMaxWidth().heightIn(min = 52.dp).clickable(role = Role.Switch) { onChange(!checked) }.padding(horizontal = 20.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, color = c.text, fontSize = 15.sp, fontWeight = FontWeight.Medium)
            if (description != null) Text(description, color = c.textDim, fontSize = 12.sp, lineHeight = 16.sp)
        }
        Spacer(Modifier.width(12.dp))
        Switch(
            checked = checked, onCheckedChange = null,
            colors = SwitchDefaults.colors(
                checkedThumbColor = c.onAccent, checkedTrackColor = c.accent, uncheckedThumbColor = c.textDim,
                uncheckedTrackColor = c.surfaceHigh, uncheckedBorderColor = c.lineStrong,
            ),
        )
    }
}

/** "How it works": the whole idea of the app on one page. */
@Composable
fun HelpSheet(onDismiss: () -> Unit) {
    val c = Mps.colors
    val items = listOf(
        Triple(Ic.Rack, R.string.help_1_title, R.string.help_1_text),
        Triple(Ic.Grid, R.string.help_2_title, R.string.help_2_text),
        Triple(Ic.Playlist, R.string.help_3_title, R.string.help_3_text),
        Triple(Ic.Mixer, R.string.help_4_title, R.string.help_4_text),
        Triple(Ic.Mic, R.string.help_5_title, R.string.help_5_text),
        Triple(Ic.Download, R.string.help_6_title, R.string.help_6_text),
    )
    AppSheet(onDismiss, title = stringResource(R.string.help)) {
        items.forEachIndexed { i, (icon, title, text) ->
            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)) {
                Box(Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(c.accentSoft), contentAlignment = Alignment.Center) {
                    Icon(icon, null, Modifier.size(22.dp), tint = c.accent)
                }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text("${i + 1}. " + stringResource(title), color = c.text, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                    Text(stringResource(text), color = c.textDim, fontSize = 13.sp, lineHeight = 18.sp)
                }
            }
        }
        Text(
            stringResource(R.string.autosave_note), Modifier.padding(horizontal = 20.dp, vertical = 10.dp), color = c.textDim,
            fontSize = 12.sp,
        )
    }
}
