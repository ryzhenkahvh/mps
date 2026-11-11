package jxpix.proger.mps.ui.cloud

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import jxpix.proger.mps.R
import jxpix.proger.mps.cloud.Cloud
import jxpix.proger.mps.cloud.CloudException
import jxpix.proger.mps.cloud.CloudItem
import jxpix.proger.mps.cloud.CloudKind
import jxpix.proger.mps.cloud.CloudProfile
import jxpix.proger.mps.data.ProjectInfo
import jxpix.proger.mps.data.SoundInfo
import jxpix.proger.mps.data.UserPreset
import jxpix.proger.mps.engine.Devices
import jxpix.proger.mps.studio.Studio
import jxpix.proger.mps.ui.Fmt
import jxpix.proger.mps.ui.Ic
import jxpix.proger.mps.ui.Links
import jxpix.proger.mps.ui.components.AppSheet
import jxpix.proger.mps.ui.components.Chip
import jxpix.proger.mps.ui.components.ConfirmDialog
import jxpix.proger.mps.ui.components.Segmented
import jxpix.proger.mps.ui.components.SheetDivider
import jxpix.proger.mps.ui.components.SheetItem
import jxpix.proger.mps.ui.components.SheetLabel
import jxpix.proger.mps.ui.theme.Mps

/** A coloured circle with the first letter of the name: nobody uploads pictures, which would cost space. */
@Composable
internal fun Avatar(name: String, color: Int, size: Int) {
    val c = Mps.colors
    val palette = c.channels
    Box(Modifier.size(size.dp).clip(CircleShape).background(palette[((color % palette.size) + palette.size) % palette.size]), contentAlignment = Alignment.Center) {
        Text(name.trim().take(1).uppercase(), color = Color.White, fontSize = (size * 0.45f).sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun Field(
    value: String,
    onChange: (String) -> Unit,
    label: String,
    hint: String? = null,
    max: Int = 60,
    lines: Int = 1,
    keyboard: KeyboardType = KeyboardType.Text,
    capital: KeyboardCapitalization = KeyboardCapitalization.None,
    secret: Boolean = false,
) {
    OutlinedTextField(
        value = value,
        onValueChange = { if (it.length <= max) onChange(if (lines == 1) it.replace("\n", "") else it) },
        singleLine = lines == 1,
        minLines = lines,
        maxLines = if (lines == 1) 1 else lines + 2,
        label = { Text(label) },
        supportingText = if (hint != null) ({ Text(hint) }) else null,
        keyboardOptions = KeyboardOptions(keyboardType = keyboard, capitalization = capital),
        visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 3.dp),
    )
}

/** The accent-coloured button at the end of a form. */
@Composable
private fun MainButton(text: String, enabled: Boolean, onClick: () -> Unit) {
    val c = Mps.colors
    Box(
        Modifier
            .padding(horizontal = 20.dp, vertical = 8.dp)
            .fillMaxWidth()
            .height(48.dp)
            .clip(RoundedCornerShape(24.dp))
            .background(if (enabled) c.accent else c.surfaceHigh)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = if (enabled) c.onAccent else c.textDim, fontSize = 15.sp, fontWeight = FontWeight.Bold)
    }
}

// ---------------------------------------------------------------------- one item

@Composable
internal fun ItemSheet(studio: Studio, item: CloudItem) {
    val cloud = studio.cloud
    val c = Mps.colors
    val mine = cloud.isMine(item)
    var reporting by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }

    AppSheet({ cloud.closeItem() }, message = studio.message) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            PlayButton(cloud, item, 52)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(item.title, color = c.text, fontSize = 19.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                val parts = ArrayList<String>()
                parts += stringResource(kindName(item.cloudKind))
                if (item.cloudKind == CloudKind.PRESET) {
                    Devices[item.device ?: ""]?.let { parts += stringResource(R.string.cloud_preset_for, stringResource(it.nameRes)) }
                }
                if (item.durationMs > 0) parts += Fmt.soundLength(item.durationMs / 1000f, LocalResources.current)
                if (item.bpm != null && item.bpm > 0f) parts += Fmt.bpm(item.bpm) + " BPM"
                if (item.sizeBytes >= 100_000) parts += stringResource(R.string.cloud_size_mb, megabytes(item.sizeBytes.toLong()))
                else if (item.sizeBytes > 0) parts += stringResource(R.string.cloud_size_kb, (item.sizeBytes + 1023) / 1024)
                Text(parts.joinToString(" · "), color = c.textDim, fontSize = 13.sp)
            }
        }
        Row(
            Modifier.fillMaxWidth().clickable {
                cloud.closeItem()
                cloud.showAuthor(item.owner)
            }.padding(horizontal = 20.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Avatar(item.author, item.authorAvatar, 28)
            Spacer(Modifier.width(10.dp))
            Text(item.author, Modifier.weight(1f), color = c.text, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Icon(Ic.ChevronRight, null, Modifier.size(20.dp), tint = c.textDim)
        }
        if (item.hidden) {
            Text(
                stringResource(R.string.cloud_hidden_note) + " " + stringResource(R.string.cloud_reports_count, item.reports),
                Modifier.padding(horizontal = 20.dp, vertical = 4.dp), color = c.record, fontSize = 13.sp, lineHeight = 18.sp,
            )
        }
        if (item.description.isNotBlank()) {
            Text(item.description, Modifier.padding(horizontal = 20.dp, vertical = 4.dp), color = c.text, fontSize = 14.sp, lineHeight = 20.sp)
        }
        if (item.tags.isNotEmpty()) {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                for (tag in item.tags) {
                    Chip("#$tag", {
                        cloud.closeItem()
                        cloud.closeAuthor()
                        cloud.searchFor(tag)
                    })
                }
            }
        }

        val use = when (item.cloudKind) {
            CloudKind.SOUND -> R.string.cloud_use_sound
            CloudKind.PRESET -> R.string.cloud_use_preset
            CloudKind.PROJECT -> R.string.cloud_use_project
            CloudKind.TRACK -> R.string.cloud_use_track
            null -> 0
        }
        if (use != 0) MainButton(stringResource(use), enabled = true) { cloud.use(item) }

        Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    stringResource(
                        when {
                            mine -> R.string.cloud_own_item
                            cloud.signedIn -> R.string.cloud_your_mark
                            else -> R.string.cloud_sign_in_to_rate
                        }
                    ),
                    color = c.textDim, fontSize = 12.sp,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Score(item)
                    Spacer(Modifier.width(10.dp))
                    Icon(Ic.Download, null, Modifier.size(14.dp), tint = c.textDim)
                    Spacer(Modifier.width(3.dp))
                    Text(item.downloads.toString(), color = c.textDim, fontSize = 12.sp)
                }
            }
            if (!mine) {
                for (n in 1..5) {
                    val label = stringResource(R.string.cloud_rate_stars, n)
                    val on = n <= cloud.myStars
                    Box(
                        Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .clickable(role = Role.Button) { cloud.rate(item, n) }
                            .semantics {
                                contentDescription = label
                                selected = on
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(if (on) Ic.StarFilled else Ic.Star, null, Modifier.size(26.dp), tint = if (on) c.accent else c.textDim)
                    }
                }
            }
        }
        SheetDivider()
        if (!mine) SheetItem(stringResource(R.string.cloud_report), { if (cloud.signedIn) reporting = true else cloud.askSignIn = true }, icon = Ic.Flag)
        if (cloud.isModerator) {
            SheetItem(stringResource(if (item.hidden) R.string.cloud_unhide else R.string.cloud_hide), { cloud.moderate(item, !item.hidden) }, icon = Ic.Lock)
        }
        if (mine || cloud.isModerator) SheetItem(stringResource(R.string.cloud_delete), { deleting = true }, icon = Ic.Delete, danger = true)
    }

    if (reporting) {
        ReportDialog(onSend = {
            reporting = false
            cloud.report(item, it)
        }, onDismiss = { reporting = false })
    }
    if (deleting) {
        ConfirmDialog(
            stringResource(R.string.cloud_delete), stringResource(R.string.cloud_delete_text, item.title), stringResource(R.string.delete),
            onConfirm = {
                deleting = false
                cloud.delete(item)
            },
            onDismiss = { deleting = false },
        )
    }
}

@Composable
private fun ReportDialog(onSend: (String) -> Unit, onDismiss: () -> Unit) {
    val c = Mps.colors
    var text by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = c.bg,
        titleContentColor = c.text,
        title = { Text(stringResource(R.string.cloud_report_title), fontSize = 18.sp, fontWeight = FontWeight.Bold) },
        text = {
            OutlinedTextField(
                value = text, onValueChange = { if (it.length <= 300) text = it }, minLines = 2, maxLines = 4,
                label = { Text(stringResource(R.string.cloud_report_hint)) },
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = { TextButton(onClick = { onSend(text.trim()) }) { Text(stringResource(R.string.cloud_report_send)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

// ---------------------------------------------------------------------- an author

@Composable
private fun Stat(value: String, label: String, modifier: Modifier) {
    val c = Mps.colors
    Column(modifier.semantics(mergeDescendants = true) {}, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, color = c.text, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        Text(label, color = c.textDim, fontSize = 12.sp)
    }
}

@Composable
internal fun AuthorSheet(studio: Studio) {
    val cloud = studio.cloud
    val c = Mps.colors
    val profile = cloud.author
    val mine = cloud.session != null && cloud.session?.userId == cloud.authorId
    var editing by remember { mutableStateOf(false) }
    var leaving by remember { mutableStateOf(false) }

    AppSheet({ cloud.closeAuthor() }, message = studio.message) {
        if (profile == null) {
            Box(Modifier.fillMaxWidth().height(120.dp), contentAlignment = Alignment.Center) {
                if (cloud.authorLoading) CircularProgressIndicator(color = c.accent, trackColor = c.accentSoft)
                else Text(stringResource(R.string.cloud_err_not_found), color = c.textDim, fontSize = 14.sp)
            }
            return@AppSheet
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Avatar(profile.username, profile.avatar, 52)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(profile.username, color = c.text, fontSize = 19.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (profile.isAdmin) Text(stringResource(R.string.cloud_moderator), color = c.accent, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            }
        }
        if (profile.bio.isNotBlank()) {
            Text(profile.bio, Modifier.padding(horizontal = 20.dp, vertical = 4.dp), color = c.text, fontSize = 14.sp, lineHeight = 20.sp)
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp)) {
            Stat(profile.items.toString(), stringResource(R.string.cloud_stat_items), Modifier.weight(1f))
            Stat(profile.downloads.toString(), stringResource(R.string.cloud_stat_downloads), Modifier.weight(1f))
            Stat(if (profile.ratingCount > 0) stars(profile.rating) else "—", stringResource(R.string.cloud_stat_rating), Modifier.weight(1f))
        }
        if (mine) {
            cloud.storage?.let { use ->
                if (use.quota > 0) {
                    Text(
                        stringResource(R.string.cloud_storage, megabytes(use.used), megabytes(use.quota)),
                        Modifier.padding(horizontal = 20.dp, vertical = 2.dp), color = c.textDim, fontSize = 12.sp,
                    )
                }
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Chip(stringResource(R.string.cloud_edit_profile), { editing = true }, icon = Ic.Edit)
                Chip(stringResource(R.string.cloud_sign_out), { cloud.signOut() }, icon = Ic.Logout)
            }
        }
        SheetDivider()
        if (cloud.authorItems.isEmpty()) {
            Box(Modifier.fillMaxWidth().height(72.dp), contentAlignment = Alignment.Center) {
                if (cloud.authorLoading) CircularProgressIndicator(Modifier.size(24.dp), color = c.accent, trackColor = c.accentSoft, strokeWidth = 3.dp)
                else Text(stringResource(R.string.cloud_author_empty), color = c.textDim, fontSize = 14.sp)
            }
        } else {
            Column(Modifier.padding(horizontal = 8.dp)) {
                for (item in cloud.authorItems) ItemRow(cloud, item)
            }
        }
        if (mine) {
            SheetDivider()
            SheetItem(stringResource(R.string.cloud_delete_account), { leaving = true }, icon = Ic.Delete, danger = true)
        }
    }

    if (editing && profile != null) ProfileDialog(studio, profile) { editing = false }
    if (leaving) {
        ConfirmDialog(
            stringResource(R.string.cloud_delete_account), stringResource(R.string.cloud_delete_account_text), stringResource(R.string.delete),
            onConfirm = {
                leaving = false
                cloud.deleteAccount()
            },
            onDismiss = { leaving = false },
        )
    }
}

private fun megabytes(bytes: Long): String {
    val mb = bytes / (1024.0 * 1024.0)
    return if (mb >= 10 || mb == 0.0) String.format(java.util.Locale.ROOT, "%.0f", mb) else String.format(java.util.Locale.ROOT, "%.1f", mb)
}

@Composable
private fun ProfileDialog(studio: Studio, profile: CloudProfile, onDismiss: () -> Unit) {
    val cloud = studio.cloud
    val c = Mps.colors
    var name by remember { mutableStateOf(profile.username) }
    var bio by remember { mutableStateOf(profile.bio) }
    var avatar by remember { mutableIntStateOf(profile.avatar) }
    var error by remember { mutableStateOf<CloudException.Kind?>(null) }
    var saving by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = c.bg,
        titleContentColor = c.text,
        title = { Text(stringResource(R.string.cloud_edit_profile), fontSize = 18.sp, fontWeight = FontWeight.Bold) },
        text = {
            Column {
                OutlinedTextField(
                    value = name, onValueChange = { if (it.length <= 24) name = it.replace("\n", "") }, singleLine = true,
                    label = { Text(stringResource(R.string.cloud_username)) }, modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(
                    value = bio, onValueChange = { if (it.length <= 300) bio = it }, minLines = 2, maxLines = 4,
                    label = { Text(stringResource(R.string.cloud_bio)) },
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                    val label = stringResource(R.string.cloud_avatar_color)
                    c.channels.forEachIndexed { i, color ->
                        Box(
                            Modifier
                                .size(32.dp)
                                .clip(CircleShape)
                                .background(color)
                                .then(if (i == avatar) Modifier.border(3.dp, c.text, CircleShape) else Modifier)
                                .clickable(role = Role.RadioButton) { avatar = i }
                                .semantics {
                                    contentDescription = "$label ${i + 1}"
                                    selected = i == avatar
                                },
                        )
                    }
                }
                error?.let { Text(cloud.errorText(it), color = c.record, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp)) }
            }
        },
        confirmButton = {
            TextButton(enabled = !saving, onClick = {
                saving = true
                error = null
                cloud.saveProfile(name, bio, avatar) { problem ->
                    saving = false
                    if (problem == null) onDismiss() else error = problem
                }
            }) { Text(stringResource(R.string.ok)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

// ---------------------------------------------------------------------- signing in

@Composable
internal fun AuthSheet(studio: Studio) {
    val cloud = studio.cloud
    val c = Mps.colors
    val context = LocalContext.current
    var mode by rememberSaveable { mutableIntStateOf(0) }
    var email by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    var name by rememberSaveable { mutableStateOf("") }
    var code by rememberSaveable { mutableStateOf("") }
    var newPassword by rememberSaveable { mutableStateOf("") }
    val failed = stringResource(R.string.cloud_err_google)
    val codeFor = cloud.codeFor
    val reset = cloud.codeStep == Cloud.CodeStep.PASSWORD
    // every letter brings a code of its own
    LaunchedEffect(codeFor, cloud.codeStep) {
        code = ""
        newPassword = ""
    }

    val title = when {
        codeFor == null -> R.string.cloud_sign_in_title
        reset -> R.string.cloud_password_title
        else -> R.string.cloud_code_title
    }
    AppSheet({
        // the code step stays: the person may be on the way to the mail app and back
        cloud.askSignIn = false
        cloud.signInError = null
    }, title = stringResource(title), message = studio.message) {
        if (codeFor != null) {
            Text(
                stringResource(if (reset) R.string.cloud_password_sent else R.string.cloud_code_sent, codeFor),
                Modifier.padding(horizontal = 20.dp, vertical = 4.dp), color = c.text, fontSize = 14.sp, lineHeight = 20.sp,
            )
            Field(code, { code = it.filter(Char::isDigit).take(6) }, stringResource(R.string.cloud_code), max = 12, keyboard = KeyboardType.Number)
            if (reset) {
                Field(
                    newPassword, { newPassword = it }, stringResource(R.string.cloud_password_title), stringResource(R.string.cloud_password_hint),
                    max = 72, keyboard = KeyboardType.Password, secret = true,
                )
            }
            cloud.signInError?.let {
                Text(cloud.errorText(it), Modifier.padding(horizontal = 20.dp, vertical = 4.dp), color = c.record, fontSize = 13.sp, lineHeight = 18.sp)
            }
            MainButton(stringResource(R.string.cloud_code_confirm), enabled = !cloud.signingIn && code.length == 6 && (!reset || newPassword.length >= 6)) {
                cloud.confirmCode(code, newPassword)
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 2.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val wait = cloud.resendIn
                Chip(
                    if (wait > 0) stringResource(R.string.cloud_code_resend_in, wait) else stringResource(R.string.cloud_code_resend),
                    { cloud.resendCode() }, enabled = wait == 0 && !cloud.signingIn, icon = Ic.Refresh,
                )
                Chip(stringResource(R.string.cloud_code_back), { cloud.cancelCode() }, icon = Ic.ChevronLeft)
            }
            Spacer(Modifier.height(8.dp))
            return@AppSheet
        }
        Segmented(
            listOf(stringResource(R.string.cloud_sign_in), stringResource(R.string.cloud_sign_up)), mode,
            {
                mode = it
                cloud.signInError = null
            },
            Modifier.padding(horizontal = 20.dp, vertical = 6.dp).fillMaxWidth(), equalWidth = true,
        )
        if (mode == 1) Field(name, { name = it }, stringResource(R.string.cloud_username), stringResource(R.string.cloud_username_hint), max = 24)
        Field(email, { email = it.trim() }, stringResource(R.string.cloud_email), max = 100, keyboard = KeyboardType.Email)
        Field(
            password, { password = it }, stringResource(R.string.cloud_password),
            if (mode == 1) stringResource(R.string.cloud_password_hint) else null, max = 72, keyboard = KeyboardType.Password, secret = true,
        )
        cloud.signInError?.let {
            Text(cloud.errorText(it), Modifier.padding(horizontal = 20.dp, vertical = 4.dp), color = c.record, fontSize = 13.sp, lineHeight = 18.sp)
        }
        MainButton(stringResource(if (mode == 0) R.string.cloud_sign_in else R.string.cloud_sign_up), enabled = !cloud.signingIn) {
            if (mode == 0) cloud.signIn(email, password) else cloud.signUp(email, password, name)
        }
        if (mode == 0) {
            Row(Modifier.padding(horizontal = 20.dp, vertical = 2.dp)) {
                Chip(stringResource(R.string.cloud_forgot), { cloud.forgotPassword(email) }, enabled = !cloud.signingIn)
            }
        } else {
            Text(stringResource(R.string.cloud_rules), Modifier.padding(horizontal = 20.dp, vertical = 2.dp), color = c.textDim, fontSize = 12.sp, lineHeight = 16.sp)
            if (Links.available) {
                Row(Modifier.padding(horizontal = 20.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Chip(stringResource(R.string.cloud_terms), { Links.open(context, Links.terms(context)) })
                    Chip(stringResource(R.string.cloud_privacy), { Links.open(context, Links.privacy(context)) })
                }
            }
        }
        if (cloud.googleSignIn) {
            SheetDivider()
            SheetItem(stringResource(R.string.cloud_google), {
                try {
                    context.startActivity(cloud.googleIntent())
                } catch (e: Exception) {
                    studio.message = failed
                }
            }, icon = Ic.Language)
        }
    }
}

// ---------------------------------------------------------------------- publishing

private sealed class Source(val name: String) {
    class Sound(val info: SoundInfo) : Source(info.title)
    class Preset(val type: String, val preset: UserPreset) : Source(preset.name)
    class Saved(val info: ProjectInfo, val track: Boolean) : Source(info.name)
}

@Composable
internal fun PublishSheet(studio: Studio, onDismiss: () -> Unit) {
    val cloud = studio.cloud
    val c = Mps.colors
    var kind by remember { mutableStateOf<CloudKind?>(null) }
    var source by remember { mutableStateOf<Source?>(null) }

    val title = when {
        kind == null -> stringResource(R.string.cloud_publish_title)
        source != null -> stringResource(kindName(kind)) + ": " + (source?.name ?: "")
        kind == CloudKind.SOUND -> stringResource(R.string.cloud_pick_sound)
        kind == CloudKind.PRESET -> stringResource(R.string.cloud_pick_preset)
        else -> stringResource(R.string.cloud_pick_project)
    }
    AppSheet(onDismiss, title = title, message = studio.message) {
        if (kind != null) {
            Row(Modifier.padding(horizontal = 16.dp, vertical = 2.dp)) {
                Chip(stringResource(R.string.back), {
                    if (source != null) source = null else kind = null
                }, icon = Ic.ChevronLeft)
            }
        }
        val chosen = source
        when {
            kind == null -> {
                SheetItem(stringResource(R.string.cloud_publish_sound), { kind = CloudKind.SOUND }, icon = kindIcon(CloudKind.SOUND), description = stringResource(R.string.cloud_publish_sound_desc))
                SheetItem(stringResource(R.string.cloud_publish_preset), { kind = CloudKind.PRESET }, icon = kindIcon(CloudKind.PRESET), description = stringResource(R.string.cloud_publish_preset_desc))
                SheetItem(stringResource(R.string.cloud_publish_project), { kind = CloudKind.PROJECT }, icon = kindIcon(CloudKind.PROJECT), description = stringResource(R.string.cloud_publish_project_desc))
                SheetItem(stringResource(R.string.cloud_publish_track), { kind = CloudKind.TRACK }, icon = kindIcon(CloudKind.TRACK), description = stringResource(R.string.cloud_publish_track_desc))
            }
            chosen != null -> PublishForm(chosen) { name, description, tags ->
                val done = { ok: Boolean -> if (ok) onDismiss() }
                when (chosen) {
                    is Source.Sound -> cloud.publishSound(chosen.info.file, name, description, tags, done)
                    is Source.Preset -> cloud.publishPreset(chosen.type, chosen.preset, name, description, tags, done)
                    is Source.Saved -> cloud.publishProject(chosen.info.id, name, description, tags, chosen.track, done)
                }
            }
            kind == CloudKind.SOUND -> {
                if (studio.sounds.isEmpty()) Empty(stringResource(R.string.cloud_no_sounds))
                for (sound in studio.sounds) {
                    SheetItem(sound.title, { source = Source.Sound(sound) }, icon = Ic.Wave, description = Fmt.soundLength(sound.seconds, LocalResources.current))
                }
            }
            kind == CloudKind.PRESET -> {
                val all = studio.userPresets.entries.sortedBy { it.key }.flatMap { (type, list) -> list.map { type to it } }
                if (all.isEmpty()) Empty(stringResource(R.string.cloud_no_presets))
                for ((type, preset) in all) {
                    val device = Devices[type]
                    SheetItem(
                        preset.name, { source = Source.Preset(type, preset) }, icon = Ic.Tune,
                        description = if (device != null) stringResource(device.nameRes) else type,
                    )
                }
            }
            else -> {
                if (studio.projects.isEmpty()) Empty(stringResource(R.string.cloud_no_projects))
                for (info in studio.projects) {
                    SheetItem(info.name, { source = Source.Saved(info, track = kind == CloudKind.TRACK) }, icon = kindIcon(kind))
                }
            }
        }
    }
}

@Composable
private fun Empty(text: String) {
    Text(text, Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 20.dp), color = Mps.colors.textDim, fontSize = 14.sp, lineHeight = 20.sp)
}

@Composable
private fun PublishForm(source: Source, onPublish: (String, String, String) -> Unit) {
    val c = Mps.colors
    var name by remember(source) { mutableStateOf(source.name.take(60)) }
    var description by remember(source) { mutableStateOf("") }
    var tags by remember(source) { mutableStateOf("") }
    var agreed by remember(source) { mutableStateOf(false) }
    Field(name, { name = it }, stringResource(R.string.cloud_title), max = 60, capital = KeyboardCapitalization.Sentences)
    Field(description, { description = it }, stringResource(R.string.cloud_description), max = 600, lines = 2, capital = KeyboardCapitalization.Sentences)
    Field(tags, { tags = it.lowercase() }, stringResource(R.string.cloud_tags), stringResource(R.string.cloud_tags_hint), max = 120)
    Row(
        Modifier.fillMaxWidth().clickable(role = Role.Checkbox) { agreed = !agreed }.padding(start = 8.dp, end = 20.dp, top = 2.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(
            checked = agreed, onCheckedChange = { agreed = it },
            colors = CheckboxDefaults.colors(checkedColor = c.accent, uncheckedColor = c.textDim, checkmarkColor = c.onAccent),
        )
        Text(stringResource(R.string.cloud_rights), color = c.text, fontSize = 13.sp, lineHeight = 18.sp)
    }
    MainButton(stringResource(R.string.cloud_publish), enabled = agreed && name.trim().length >= 2) { onPublish(name, description, tags) }
}
