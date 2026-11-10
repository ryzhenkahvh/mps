package jxpix.proger.mps.ui.studio

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import jxpix.proger.mps.R
import jxpix.proger.mps.data.PresetStore
import jxpix.proger.mps.data.UserPreset
import jxpix.proger.mps.engine.Devices
import jxpix.proger.mps.model.DeviceState
import jxpix.proger.mps.model.Presets
import jxpix.proger.mps.model.USER_PRESET_PREFIX
import jxpix.proger.mps.model.applyPreset
import jxpix.proger.mps.model.findDevice
import jxpix.proger.mps.studio.Studio
import jxpix.proger.mps.ui.Ic
import jxpix.proger.mps.ui.components.AppSheet
import jxpix.proger.mps.ui.components.Chip
import jxpix.proger.mps.ui.components.ConfirmDialog
import jxpix.proger.mps.ui.components.SheetDivider
import jxpix.proger.mps.ui.components.SheetLabel
import jxpix.proger.mps.ui.components.TextInputDialog
import jxpix.proger.mps.ui.theme.Mps

/** What to call the preset a device is set to: a built-in name, the user's own name, or null. */
@Composable
fun presetName(device: DeviceState): String? {
    val id = device.preset ?: return null
    if (id.startsWith(USER_PRESET_PREFIX)) return id.substring(USER_PRESET_PREFIX.length)
    return Presets.find(device.type, id)?.let { stringResource(it.nameRes) }
}

/**
 * Presets of one instrument or effect: the user's own (save the current sound, pick, delete)
 * and the built-in ones.
 *
 * @param onApplied called after a preset was chosen - a chance to play a note with the new sound.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PresetSheet(studio: Studio, uid: Int, onDismiss: () -> Unit, onApplied: () -> Unit = {}) {
    val c = Mps.colors
    val device = studio.project.findDevice(uid)
    val type = device?.let { Devices[it.type] }
    if (device == null || type == null) {
        LaunchedEffect(Unit) { onDismiss() }
        return
    }
    val own = studio.presetsOf(device.type)
    val builtIn = remember(device.type) { Presets.forType(device.type) }
    var saving by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    var toDelete by remember { mutableStateOf<UserPreset?>(null) }
    val current = presetName(device)

    AppSheet(onDismiss, title = stringResource(R.string.presets_of, stringResource(type.nameRes))) {
        Row(Modifier.fillMaxWidth().padding(end = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) { SheetLabel(stringResource(R.string.presets_own)) }
            Chip(stringResource(R.string.preset_save), { saving = true }, selected = true, icon = Ic.Save)
        }
        if (own.isEmpty()) {
            Text(
                stringResource(R.string.presets_own_empty), Modifier.padding(horizontal = 20.dp, vertical = 6.dp), color = c.textDim,
                fontSize = 13.sp, lineHeight = 18.sp,
            )
        } else {
            FlowRow(
                Modifier.padding(horizontal = 16.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                for (preset in own) {
                    Chip(
                        preset.name,
                        {
                            if (deleting) toDelete = preset
                            else {
                                studio.applyUserPreset(uid, preset)
                                onApplied()
                            }
                        },
                        selected = !deleting && device.preset == USER_PRESET_PREFIX + preset.name,
                        trailing = if (deleting) Ic.Close else null,
                    )
                }
            }
            Chip(
                stringResource(if (deleting) R.string.done else R.string.preset_delete_mode), { deleting = !deleting },
                Modifier.padding(start = 16.dp, top = 2.dp, bottom = 6.dp), icon = if (deleting) Ic.Check else Ic.Delete,
            )
        }

        if (builtIn.isNotEmpty()) {
            SheetDivider()
            SheetLabel(stringResource(R.string.presets_built_in))
            // synth presets come in categories, the others are one short list
            val groups = remember(builtIn) { builtIn.groupBy { it.categoryRes } }
            for ((category, list) in groups) {
                if (category != 0) {
                    Text(
                        stringResource(category), Modifier.padding(start = 20.dp, top = 8.dp), color = c.text, fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                FlowRow(
                    Modifier.padding(horizontal = 16.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    for (preset in list) {
                        Chip(stringResource(preset.nameRes), {
                            studio.edit { it.applyPreset(uid, preset) }
                            onApplied()
                        }, selected = preset.id == device.preset)
                    }
                }
            }
        }
    }

    if (saving) {
        // offer the name of the user's preset the sound came from, so that "save" updates it
        val suggestion = if (device.preset?.startsWith(USER_PRESET_PREFIX) == true) current ?: "" else ""
        TextInputDialog(
            stringResource(R.string.preset_save_title), suggestion,
            onConfirm = { name ->
                saving = false
                studio.savePreset(device, name)
            },
            onDismiss = { saving = false }, maxLength = PresetStore.MAX_NAME,
        )
    }
    toDelete?.let { preset ->
        ConfirmDialog(
            stringResource(R.string.preset_delete_title), stringResource(R.string.preset_delete_text, preset.name), stringResource(R.string.delete),
            onConfirm = {
                toDelete = null
                studio.deletePreset(device.type, preset.name)
                if (own.size <= 1) deleting = false
            },
            onDismiss = { toDelete = null },
        )
    }
}
