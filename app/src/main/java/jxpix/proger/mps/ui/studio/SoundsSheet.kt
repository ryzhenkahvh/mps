package jxpix.proger.mps.ui.studio

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import jxpix.proger.mps.R
import jxpix.proger.mps.data.SoundInfo
import jxpix.proger.mps.studio.Studio
import jxpix.proger.mps.studio.Tab
import jxpix.proger.mps.ui.Fmt
import jxpix.proger.mps.ui.Ic
import jxpix.proger.mps.ui.components.AppSheet
import jxpix.proger.mps.ui.components.Chip
import jxpix.proger.mps.ui.components.ConfirmDialog
import jxpix.proger.mps.ui.components.IconBtn
import jxpix.proger.mps.ui.components.TextInputDialog
import jxpix.proger.mps.ui.theme.Mps

/** What tapping a sound of the library does - depends on where the list was opened from. */
sealed interface SoundTarget {
    /** Opened from the menu: nothing in particular, every sound offers all of its actions. */
    data object Browse : SoundTarget

    /** A new sampler channel is made for the sound. */
    data object NewChannel : SoundTarget

    /** The sound is loaded into this sampler channel. */
    data class Sampler(val channelId: Int) : SoundTarget

    /** The sound goes into the playlist as an audio clip. */
    data object Playlist : SoundTarget
}

/**
 * "My sounds": the user's own drum hits, loops and recordings. Add files from the phone,
 * listen, rename, delete - and use a sound in the open project.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SoundsSheet(studio: Studio, target: SoundTarget, onDismiss: () -> Unit) {
    val c = Mps.colors
    val sounds = studio.sounds
    var open by remember { mutableStateOf<String?>(null) }
    var rename by remember { mutableStateOf<SoundInfo?>(null) }
    var delete by remember { mutableStateOf<SoundInfo?>(null) }
    val failed = stringResource(R.string.msg_import_failed)

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        studio.importSounds(uris)
    }
    // leaving the list stops whatever is still playing
    DisposableEffect(Unit) { onDispose { studio.previewSound(null) } }

    val toChannel = { s: SoundInfo ->
        onDismiss()
        studio.soundToNewChannel(s.file)
    }
    val toPlaylist = { s: SoundInfo ->
        onDismiss()
        studio.ui.editor = null
        studio.ui.tab = Tab.PLAYLIST
        studio.soundToPlaylist(s.file)
    }

    AppSheet(onDismiss, title = stringResource(R.string.sounds_title)) {
        Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 16.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(
                    when (target) {
                        SoundTarget.Browse -> R.string.sounds_hint
                        SoundTarget.NewChannel -> R.string.sounds_hint_channel
                        is SoundTarget.Sampler -> R.string.sounds_hint_sampler
                        SoundTarget.Playlist -> R.string.sounds_hint_playlist
                    }
                ),
                Modifier.weight(1f), color = c.textDim, fontSize = 13.sp, lineHeight = 18.sp,
            )
            Spacer(Modifier.width(12.dp))
            Chip(stringResource(R.string.sounds_add), {
                try {
                    picker.launch(arrayOf("audio/*"))
                } catch (_: Exception) {
                    studio.message = failed
                }
            }, selected = true, icon = Ic.Add)
        }
        if (sounds.isEmpty()) {
            Text(
                stringResource(R.string.sounds_empty), Modifier.padding(horizontal = 20.dp, vertical = 18.dp), color = c.textDim,
                fontSize = 14.sp, lineHeight = 20.sp,
            )
        }
        for (sound in sounds) {
            val playing = studio.previewingSound == sound.file
            val expanded = open == sound.file
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 52.dp)
                    .clickable(role = Role.Button) {
                        when (target) {
                            SoundTarget.Browse -> open = if (expanded) null else sound.file
                            SoundTarget.NewChannel -> toChannel(sound)
                            is SoundTarget.Sampler -> {
                                onDismiss()
                                studio.soundToSampler(sound.file, target.channelId)
                            }
                            SoundTarget.Playlist -> toPlaylist(sound)
                        }
                    }
                    .padding(start = 8.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconBtn(
                    if (playing) Ic.Stop else Ic.Play, stringResource(if (playing) R.string.sound_stop else R.string.sound_listen) + ": " + sound.title,
                    { studio.previewSound(sound.file) }, tint = if (playing) c.onAccent else c.accent,
                    background = if (playing) c.accent else c.accentSoft, size = 40.dp, iconSize = 20.dp,
                )
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(sound.title, color = c.text, fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        Fmt.soundLength(sound.seconds) + " · " + stringResource(if (sound.stereo) R.string.stereo else R.string.mono),
                        color = c.textDim, fontSize = 12.sp,
                    )
                }
                IconBtn(
                    if (expanded) Ic.ExpandLess else Ic.More, stringResource(R.string.more) + ": " + sound.title,
                    { open = if (expanded) null else sound.file }, tint = c.textDim,
                )
            }
            if (expanded) {
                FlowRow(
                    Modifier.padding(start = 60.dp, end = 16.dp, bottom = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Chip(stringResource(R.string.sound_to_channel), { toChannel(sound) }, icon = Ic.Add)
                    Chip(stringResource(R.string.sound_to_playlist), { toPlaylist(sound) }, icon = Ic.Playlist)
                    Chip(stringResource(R.string.rename), { rename = sound }, icon = Ic.Rename)
                    Chip(stringResource(R.string.delete), { delete = sound }, icon = Ic.Delete)
                }
            }
        }
        if (sounds.isNotEmpty()) {
            Text(
                stringResource(R.string.sounds_note), Modifier.padding(horizontal = 20.dp, vertical = 10.dp), color = c.textDim, fontSize = 12.sp,
                lineHeight = 16.sp,
            )
        }
    }

    rename?.let { sound ->
        TextInputDialog(
            stringResource(R.string.sound_rename), sound.title,
            onConfirm = { title ->
                rename = null
                open = null
                if (title != sound.title) studio.renameSound(sound.file, title)
            },
            onDismiss = { rename = null }, maxLength = 40,
        )
    }
    delete?.let { sound ->
        ConfirmDialog(
            stringResource(R.string.sound_delete_title), stringResource(R.string.sound_delete_text, sound.title), stringResource(R.string.delete),
            onConfirm = {
                delete = null
                open = null
                studio.deleteSound(sound.file)
            },
            onDismiss = { delete = null },
        )
    }
}
