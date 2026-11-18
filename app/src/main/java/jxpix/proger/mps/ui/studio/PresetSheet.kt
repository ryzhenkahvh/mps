package jxpix.proger.mps.ui.studio

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
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
import jxpix.proger.mps.ui.components.IconBtn
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
    var search by rememberSaveable(uid) { mutableStateOf("") }
    val needle = search.trim()
    val resources = LocalResources.current
    val focus = LocalFocusManager.current
    val visibleOwn = remember(own, needle) { own.filter { presetMatches(needle, it.name) } }
    val visibleBuiltIn = remember(builtIn, needle, resources) {
        builtIn.filter { preset ->
            val category = if (preset.categoryRes == 0) "" else resources.getString(preset.categoryRes)
            presetMatches(needle, resources.getString(preset.nameRes), category)
        }
    }
    val groups = remember(visibleBuiltIn) { visibleBuiltIn.groupBy { it.categoryRes } }
    val savePresetButton: @Composable () -> Unit = {
        Chip(stringResource(R.string.preset_save), { focus.clearFocus(); saving = true }, selected = true, icon = Ic.Save)
    }

    AppSheet(onDismiss, title = stringResource(R.string.presets_of, stringResource(type.nameRes))) {
        OutlinedTextField(
            value = search,
            onValueChange = { if (it.length <= 48) search = it },
            singleLine = true,
            label = { Text(stringResource(R.string.preset_search)) },
            leadingIcon = { Icon(Ic.Search, null, Modifier.size(20.dp)) },
            trailingIcon = {
                if (search.isNotEmpty()) {
                    IconBtn(Ic.Close, stringResource(R.string.preset_search_clear), { search = "" }, size = 40.dp, iconSize = 18.dp)
                }
            },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { focus.clearFocus() }),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        )

        if (needle.isEmpty() || visibleOwn.isNotEmpty()) {
            Row(Modifier.fillMaxWidth().padding(end = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) { SheetLabel(stringResource(R.string.presets_own)) }
                savePresetButton()
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
                    for (preset in visibleOwn) {
                        Chip(
                            preset.name,
                            {
                                if (deleting) toDelete = preset
                                else {
                                    focus.clearFocus()
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
        } else {
            Row(Modifier.fillMaxWidth().padding(end = 16.dp), horizontalArrangement = Arrangement.End) { savePresetButton() }
        }

        if (groups.isNotEmpty()) {
            SheetDivider()
            SheetLabel(stringResource(R.string.presets_built_in))
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
                            focus.clearFocus()
                            studio.edit { it.applyPreset(uid, preset) }
                            onApplied()
                        }, selected = preset.id == device.preset)
                    }
                }
            }
        }
        if (needle.isNotEmpty() && visibleOwn.isEmpty() && visibleBuiltIn.isEmpty()) {
            Text(
                stringResource(R.string.preset_search_empty),
                Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 24.dp), color = c.textDim,
                fontSize = 13.sp, lineHeight = 18.sp,
            )
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

/** Case-insensitive search through a preset's display name and optional category. */
internal fun presetMatches(query: String, name: String, category: String = ""): Boolean {
    val term = query.trim()
    return term.isEmpty() || name.contains(term, ignoreCase = true) || category.contains(term, ignoreCase = true)
}
