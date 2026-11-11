package jxpix.proger.mps.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import jxpix.proger.mps.R
import jxpix.proger.mps.audio.AudioExporter
import jxpix.proger.mps.studio.Studio
import jxpix.proger.mps.studio.Tab
import jxpix.proger.mps.ui.cloud.CommunityScreen
import jxpix.proger.mps.ui.home.HomeScreen
import jxpix.proger.mps.ui.studio.StudioScreen
import jxpix.proger.mps.ui.theme.Mps
import kotlinx.coroutines.delay

/** Root of the UI: the project list or the open studio, plus the overlays shared by both. */
@Composable
fun App(studio: Studio, onLanguageChanged: () -> Unit) {
    val c = Mps.colors
    var showSettings by remember { mutableStateOf(false) }
    var showHelp by remember { mutableStateOf(false) }

    // One animation loop for the play head and the level meters. It idles when nothing moves.
    LaunchedEffect(studio) {
        while (true) {
            val active = withFrameNanos { studio.onFrame() }
            if (!active) delay(60)
        }
    }

    val inStudio = studio.projectId != null
    val view = LocalView.current
    val keepOn = inStudio && studio.prefs.keepScreenOn
    DisposableEffect(keepOn) {
        view.keepScreenOn = keepOn
        onDispose { view.keepScreenOn = false }
    }

    Box(Modifier.fillMaxSize().background(c.bg)) {
        val community = studio.cloud.visible
        if (community) {
            // takes the place of the screen underneath, which comes back as it was left
            CommunityScreen(studio)
        } else if (inStudio) {
            StudioScreen(studio, onSettings = { showSettings = true }, onHelp = { showHelp = true })
        } else {
            HomeScreen(studio, onSettings = { showSettings = true }, onHelp = { showHelp = true })
        }
        MessageBar(studio, Modifier.align(Alignment.BottomCenter), raised = inStudio, community = community)
    }

    if (showSettings) SettingsSheet(studio, onDismiss = { showSettings = false }, onLanguageChanged = onLanguageChanged)
    if (showHelp) HelpSheet(onDismiss = { showHelp = false })
    BusyDialog(studio)
    ExportDoneDialog(studio)
}

/** Short messages from the studio ("Recording added to the playlist"). */
@Composable
private fun MessageBar(studio: Studio, modifier: Modifier, raised: Boolean, community: Boolean) {
    val c = Mps.colors
    val message = studio.message ?: return
    // stay clear of the bottom navigation and of the action bar of a selection
    val ui = studio.ui
    val bottom = when {
        // above the "Publish" button
        community -> 76.dp
        !raised -> 20.dp
        ui.editor == null && ui.tab == Tab.PLAYLIST && ui.selectedClip != 0 -> 136.dp
        ui.editor == null && ui.tab == Tab.PIANO && ui.rollSelection.isNotEmpty() -> 184.dp
        else -> 76.dp
    }
    LaunchedEffect(message) {
        // long messages need more time to be read
        delay((message.length * 75L).coerceIn(4000L, 12000L))
        if (studio.message == message) studio.message = null
    }
    Box(
        modifier
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(start = 16.dp, end = 16.dp, bottom = bottom)
            .widthIn(max = 520.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(c.text)
            .semantics { liveRegion = LiveRegionMode.Polite }
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Text(message, color = c.bg, fontSize = 13.sp, lineHeight = 18.sp)
    }
}

/** Blocks the screen while something long is running (import, export). */
@Composable
private fun BusyDialog(studio: Studio) {
    val c = Mps.colors
    val progress = studio.busy ?: return
    Dialog(onDismissRequest = {}, properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false)) {
        Column(
            Modifier.clip(RoundedCornerShape(24.dp)).background(c.bg).padding(24.dp).widthIn(min = 220.dp, max = 320.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (progress < 0f) {
                CircularProgressIndicator(color = c.accent, trackColor = c.accentSoft)
            } else {
                LinearProgressIndicator(
                    progress = { progress.coerceIn(0f, 1f) }, color = c.accent, trackColor = c.accentSoft,
                    modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)),
                    drawStopIndicator = {},
                )
            }
            Spacer(Modifier.height(16.dp))
            Text(studio.busyText, color = c.text, fontSize = 15.sp, fontWeight = FontWeight.Medium, textAlign = TextAlign.Center)
            if (progress >= 0f) {
                Spacer(Modifier.height(8.dp))
                TextButton(onClick = { studio.cancelBusy() }) { Text(stringResource(R.string.cancel)) }
            }
        }
    }
}

@Composable
private fun ExportDoneDialog(studio: Studio) {
    val c = Mps.colors
    val result = studio.exportResult ?: return
    val context = LocalContext.current
    val shareTitle = stringResource(R.string.share)
    AlertDialog(
        onDismissRequest = { studio.exportResult = null },
        containerColor = c.bg,
        titleContentColor = c.text,
        textContentColor = c.textDim,
        title = {
            Text(
                stringResource(if (result.stems) R.string.export_done_stems_title else R.string.export_done_title), fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
            )
        },
        text = {
            val text = if (result.stems) stringResource(R.string.export_done_stems, result.uris.size, result.name)
            else stringResource(R.string.export_done_text, result.name)
            Text(text + "\n" + stringResource(R.string.track_length, Fmt.time(result.seconds.toFloat())), fontSize = 14.sp, lineHeight = 20.sp)
        },
        confirmButton = {
            TextButton(onClick = {
                studio.exportResult = null
                try {
                    context.startActivity(AudioExporter.shareIntent(result.uris, result.mime, shareTitle))
                } catch (_: Exception) {
                }
            }) { Text(shareTitle, fontWeight = FontWeight.Bold) }
        },
        dismissButton = { TextButton(onClick = { studio.exportResult = null }) { Text(stringResource(R.string.done)) } },
    )
}
