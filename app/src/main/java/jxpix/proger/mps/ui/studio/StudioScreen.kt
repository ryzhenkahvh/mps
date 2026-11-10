package jxpix.proger.mps.ui.studio

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import jxpix.proger.mps.R
import jxpix.proger.mps.engine.PPQ
import jxpix.proger.mps.model.MASTER_ID
import jxpix.proger.mps.studio.RecordMode
import jxpix.proger.mps.studio.Studio
import jxpix.proger.mps.studio.Tab
import jxpix.proger.mps.ui.Fmt
import jxpix.proger.mps.ui.Ic
import jxpix.proger.mps.ui.components.IconBtn
import jxpix.proger.mps.ui.components.Segmented
import jxpix.proger.mps.ui.components.TextInputDialog
import jxpix.proger.mps.ui.theme.Mps
import jxpix.proger.mps.ui.theme.NumberStyle

private enum class Sheet { NONE, TEMPO, RECORD, MENU, EXPORT, RENAME }

/** The open project: transport on top, the five workspaces below. */
@Composable
fun StudioScreen(studio: Studio, onSettings: () -> Unit, onHelp: () -> Unit) {
    val c = Mps.colors
    val ui = studio.ui
    var sheet by remember { mutableStateOf(Sheet.NONE) }
    val context = LocalContext.current

    BackHandler {
        if (ui.editor != null) ui.editor = null else studio.closeProject()
    }

    val micDenied = stringResource(R.string.mic_denied)
    val startMic = {
        if (studio.startAudioRecording()) {
            ui.editor = null
            ui.tab = Tab.PLAYLIST
        }
    }
    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startMic() else studio.message = micDenied
    }
    val onRecord = {
        if (studio.recordMode != RecordMode.NONE) studio.stopRecording() else sheet = Sheet.RECORD
    }
    val modeHintPattern = stringResource(R.string.mode_pattern_hint)
    val modeHintSong = stringResource(R.string.mode_song_hint)
    val onMode = { song: Boolean ->
        if (song != studio.songMode) {
            studio.changeSongMode(song)
            studio.message = if (song) modeHintSong else modeHintPattern
        }
    }

    BoxWithConstraints(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
        val wide = maxWidth >= 600.dp && maxWidth > maxHeight
        val roomy = maxWidth >= 400.dp
        val content: @Composable (Modifier) -> Unit = { modifier ->
            Box(modifier) {
                val editor = ui.editor
                if (editor != null) {
                    ChannelEditor(studio, editor)
                } else {
                    when (ui.tab) {
                        Tab.RACK -> RackTab(studio)
                        Tab.PIANO -> PianoRollTab(studio)
                        Tab.PLAYLIST -> PlaylistTab(studio, onRecordMic = {
                            if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                                startMic()
                            } else micPermission.launch(Manifest.permission.RECORD_AUDIO)
                        })
                        Tab.MIXER -> MixerTab(studio)
                        Tab.KEYS -> KeysTab(studio)
                    }
                }
            }
        }
        if (wide) {
            Row(Modifier.fillMaxSize()) {
                NavRail(studio)
                Column(Modifier.weight(1f).fillMaxHeight()) {
                    TransportWide(studio, onRecord, onMode, onTempo = { sheet = Sheet.TEMPO }, onMenu = { sheet = Sheet.MENU })
                    content(Modifier.weight(1f).fillMaxWidth())
                }
            }
        } else {
            Column(Modifier.fillMaxSize()) {
                TransportCompact(studio, onRecord, onMode, onTempo = { sheet = Sheet.TEMPO }, onMenu = { sheet = Sheet.MENU }, roomy = roomy)
                content(Modifier.weight(1f).fillMaxWidth())
                BottomNav(studio)
            }
        }
    }

    when (sheet) {
        Sheet.TEMPO -> TempoSheet(studio) { sheet = Sheet.NONE }
        Sheet.RECORD -> RecordSheet(
            onDismiss = { sheet = Sheet.NONE },
            onNotes = {
                sheet = Sheet.NONE
                ui.editor = null
                if (ui.tab != Tab.KEYS) ui.tab = Tab.KEYS
                studio.startNoteRecording()
            },
            onMic = {
                sheet = Sheet.NONE
                if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                    startMic()
                } else micPermission.launch(Manifest.permission.RECORD_AUDIO)
            },
        )
        Sheet.MENU -> MenuSheet(
            studio,
            onDismiss = { sheet = Sheet.NONE },
            onExport = { sheet = Sheet.EXPORT },
            onTempo = { sheet = Sheet.TEMPO },
            onMaster = {
                sheet = Sheet.NONE
                ui.editorTab = 1
                ui.editor = MASTER_ID
            },
            onRename = { sheet = Sheet.RENAME },
            onSettings = {
                sheet = Sheet.NONE
                onSettings()
            },
            onHelp = {
                sheet = Sheet.NONE
                onHelp()
            },
        )
        Sheet.EXPORT -> ExportSheet(studio) { sheet = Sheet.NONE }
        Sheet.RENAME -> TextInputDialog(
            stringResource(R.string.rename_project), studio.project.name,
            onConfirm = { name ->
                sheet = Sheet.NONE
                studio.projectId?.let { studio.renameProject(it, name) }
            },
            onDismiss = { sheet = Sheet.NONE },
        )
        Sheet.NONE -> {}
    }
}

// ------------------------------------------------------------------------------------ transport

@Composable
private fun PlayButton(studio: Studio) {
    val c = Mps.colors
    val playing = studio.playing
    Box(
        Modifier
            .size(48.dp)
            .clip(CircleShape)
            .background(c.accent)
            .clickable(role = Role.Button) { studio.togglePlay() },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            if (playing) Ic.Pause else Ic.Play, stringResource(if (playing) R.string.cd_pause else R.string.cd_play),
            Modifier.size(30.dp), tint = c.onAccent,
        )
    }
}

@Composable
private fun RecordButton(studio: Studio, onRecord: () -> Unit) {
    val c = Mps.colors
    val recording = studio.recordMode != RecordMode.NONE
    Box(
        Modifier
            .size(44.dp)
            .clip(CircleShape)
            .background(if (recording) c.record else Color.Transparent)
            .clickable(role = Role.Button, onClick = onRecord),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            if (recording) Ic.Stop else Ic.Record, stringResource(R.string.cd_record), Modifier.size(if (recording) 22.dp else 26.dp),
            tint = if (recording) Color.White else c.record,
        )
    }
}

@Composable
private fun TempoButton(studio: Studio, onClick: () -> Unit) {
    val c = Mps.colors
    Column(
        Modifier
            .height(40.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(c.surfaceHigh)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(Fmt.bpm(studio.project.bpm), color = c.text, fontSize = 15.sp, lineHeight = 17.sp, style = NumberStyle)
        Text(stringResource(R.string.bpm), color = c.textDim, fontSize = 9.sp, lineHeight = 10.sp, fontWeight = FontWeight.Bold)
    }
}

/** Bar.beat.step read-out; recomposes only when the text changes, not on every frame. */
@Composable
private fun PositionText(studio: Studio, modifier: Modifier = Modifier) {
    val c = Mps.colors
    val text by remember(studio) {
        derivedStateOf {
            val p = studio.project
            Fmt.position(studio.playhead.doubleValue.toInt(), p.ticksPerBar, PPQ)
        }
    }
    val label = when {
        studio.countingIn -> stringResource(R.string.count_in)
        else -> text
    }
    Text(
        label, modifier, color = if (studio.recordMode != RecordMode.NONE) c.record else c.textDim, fontSize = 13.sp,
        style = NumberStyle, maxLines = 1, softWrap = false, overflow = TextOverflow.Clip,
    )
}

@Composable
private fun ModeSwitch(studio: Studio, onMode: (Boolean) -> Unit) {
    Segmented(
        listOf(stringResource(R.string.mode_pattern), stringResource(R.string.mode_song)),
        if (studio.songMode) 1 else 0, { onMode(it == 1) }, height = 40.dp,
    )
}

@Composable
private fun MetronomeButton(studio: Studio) {
    val c = Mps.colors
    val on = studio.prefs.metronome
    IconBtn(
        Ic.Metronome, stringResource(R.string.cd_metronome), { studio.changeMetronome(!on) },
        tint = if (on) c.onAccent else c.textDim, background = if (on) c.accent else Color.Transparent, size = 40.dp,
    )
}

/** Portrait phones: two rows. */
@Composable
private fun TransportCompact(
    studio: Studio, onRecord: () -> Unit, onMode: (Boolean) -> Unit, onTempo: () -> Unit, onMenu: () -> Unit, roomy: Boolean,
) {
    val c = Mps.colors
    Column(Modifier.fillMaxWidth().background(c.bg)) {
        Row(Modifier.fillMaxWidth().height(46.dp).padding(horizontal = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            IconBtn(Ic.Home, stringResource(R.string.cd_home), { studio.closeProject() }, tint = c.textDim)
            Text(
                studio.project.name, Modifier.weight(1f).padding(horizontal = 4.dp), color = c.text, fontSize = 15.sp,
                fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            PositionText(studio, Modifier.padding(horizontal = 6.dp))
            IconBtn(Ic.Undo, stringResource(R.string.cd_undo), { studio.undo() }, enabled = studio.canUndo, size = 40.dp)
            IconBtn(Ic.Redo, stringResource(R.string.cd_redo), { studio.redo() }, enabled = studio.canRedo, size = 40.dp)
            IconBtn(Ic.More, stringResource(R.string.cd_menu), onMenu, size = 40.dp)
        }
        Row(
            Modifier.fillMaxWidth().height(54.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically,
        ) {
            PlayButton(studio)
            Spacer(Modifier.width(if (roomy) 6.dp else 2.dp))
            IconBtn(Ic.ToStart, stringResource(R.string.cd_stop), { studio.stop() }, size = 42.dp, iconSize = 26.dp)
            RecordButton(studio, onRecord)
            Spacer(Modifier.weight(1f))
            ModeSwitch(studio, onMode)
            Spacer(Modifier.width(if (roomy) 8.dp else 6.dp))
            TempoButton(studio, onTempo)
            Spacer(Modifier.width(if (roomy) 4.dp else 0.dp))
            MetronomeButton(studio)
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(c.line))
    }
}

/** Landscape and tablets: everything in one row. */
@Composable
private fun TransportWide(studio: Studio, onRecord: () -> Unit, onMode: (Boolean) -> Unit, onTempo: () -> Unit, onMenu: () -> Unit) {
    val c = Mps.colors
    Column(Modifier.fillMaxWidth().background(c.bg)) {
        Row(Modifier.fillMaxWidth().height(54.dp).padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            PlayButton(studio)
            Spacer(Modifier.width(4.dp))
            IconBtn(Ic.ToStart, stringResource(R.string.cd_stop), { studio.stop() }, size = 42.dp, iconSize = 26.dp)
            RecordButton(studio, onRecord)
            Spacer(Modifier.width(8.dp))
            ModeSwitch(studio, onMode)
            Spacer(Modifier.width(8.dp))
            TempoButton(studio, onTempo)
            Spacer(Modifier.width(4.dp))
            MetronomeButton(studio)
            Spacer(Modifier.width(10.dp))
            PositionText(studio)
            Text(
                studio.project.name, Modifier.weight(1f).padding(horizontal = 12.dp), color = c.text, fontSize = 15.sp,
                fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            IconBtn(Ic.Undo, stringResource(R.string.cd_undo), { studio.undo() }, enabled = studio.canUndo, size = 40.dp)
            IconBtn(Ic.Redo, stringResource(R.string.cd_redo), { studio.redo() }, enabled = studio.canRedo, size = 40.dp)
            IconBtn(Ic.More, stringResource(R.string.cd_menu), onMenu, size = 40.dp)
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(c.line))
    }
}

// ------------------------------------------------------------------------------------ navigation

private class NavItem(val tab: Tab, val icon: ImageVector, val label: Int)

private val NAV = listOf(
    NavItem(Tab.RACK, Ic.Rack, R.string.tab_rack),
    NavItem(Tab.PIANO, Ic.Roll, R.string.tab_piano),
    NavItem(Tab.PLAYLIST, Ic.Playlist, R.string.tab_playlist),
    NavItem(Tab.MIXER, Ic.Mixer, R.string.tab_mixer),
    NavItem(Tab.KEYS, Ic.Piano, R.string.tab_keys),
)

@Composable
private fun NavCell(studio: Studio, item: NavItem, modifier: Modifier) {
    val c = Mps.colors
    val ui = studio.ui
    val on = ui.tab == item.tab && ui.editor == null
    Column(
        modifier
            .clickable(role = Role.Tab) {
                ui.editor = null
                ui.tab = item.tab
            }
            .semantics { selected = on },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            Modifier.size(width = 52.dp, height = 28.dp).clip(RoundedCornerShape(14.dp)).background(if (on) c.accentSoft else Color.Transparent),
            contentAlignment = Alignment.Center,
        ) {
            Icon(item.icon, null, Modifier.size(22.dp), tint = if (on) c.accent else c.textDim)
        }
        Spacer(Modifier.height(2.dp))
        Text(
            stringResource(item.label), color = if (on) c.accent else c.textDim, fontSize = 11.sp, lineHeight = 13.sp,
            fontWeight = if (on) FontWeight.Bold else FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun BottomNav(studio: Studio) {
    val c = Mps.colors
    Column(Modifier.fillMaxWidth().background(c.bg)) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(c.line))
        Row(Modifier.fillMaxWidth().height(58.dp)) {
            for (item in NAV) NavCell(studio, item, Modifier.weight(1f).fillMaxHeight())
        }
    }
}

@Composable
private fun NavRail(studio: Studio) {
    val c = Mps.colors
    Row(Modifier.fillMaxHeight()) {
        Column(Modifier.width(76.dp).fillMaxHeight().background(c.bg)) {
            Box(
                Modifier.fillMaxWidth().height(54.dp).clickable(role = Role.Button) { studio.closeProject() },
                contentAlignment = Alignment.Center,
            ) {
                Icon(Ic.Home, stringResource(R.string.cd_home), Modifier.size(22.dp), tint = c.textDim)
            }
            for (item in NAV) NavCell(studio, item, Modifier.weight(1f).fillMaxWidth())
        }
        Box(Modifier.width(1.dp).fillMaxHeight().background(c.line))
    }
}
