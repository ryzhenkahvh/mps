package jxpix.proger.mps.ui.home

import jxpix.proger.mps.ui.studio.projectFileName
import jxpix.proger.mps.ui.components.Chip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.rememberLauncherForActivityResult
import android.text.format.DateUtils
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import jxpix.proger.mps.R
import jxpix.proger.mps.data.ProjectInfo
import jxpix.proger.mps.model.ProjectFactory
import jxpix.proger.mps.model.Template
import jxpix.proger.mps.studio.Studio
import jxpix.proger.mps.ui.Fmt
import jxpix.proger.mps.ui.Ic
import jxpix.proger.mps.ui.components.AppSheet
import jxpix.proger.mps.ui.components.ConfirmDialog
import jxpix.proger.mps.ui.components.IconBtn
import jxpix.proger.mps.ui.components.SheetItem
import jxpix.proger.mps.ui.components.TextInputDialog
import jxpix.proger.mps.ui.theme.Mps

/** First screen: start a new track from a template or open a saved project. */
@Composable
fun HomeScreen(studio: Studio, onSettings: () -> Unit, onHelp: () -> Unit) {
    val c = Mps.colors
    val context = LocalContext.current
    var menuFor by remember { mutableStateOf<ProjectInfo?>(null) }
    var renameFor by remember { mutableStateOf<ProjectInfo?>(null) }
    var deleteFor by remember { mutableStateOf<ProjectInfo?>(null) }
    var opening by remember { mutableStateOf(false) }
    // the project whose file is being saved; survives the trip to the system file picker
    var exportId by rememberSaveable { mutableStateOf<String?>(null) }

    val templates = remember { ProjectFactory.templates.filter { it.id != ProjectFactory.DEMO_ID } }
    val demo = remember { ProjectFactory.template(ProjectFactory.DEMO_ID) }
    val projects = studio.projects

    val importFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) studio.importProjectFile({ context.contentResolver.openInputStream(uri) })
    }
    val exportFile = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val id = exportId
        exportId = null
        if (uri != null && id != null) studio.exportProjectFile(id) { context.contentResolver.openOutputStream(uri, "wt") }
    }
    val importFailed = stringResource(R.string.msg_project_import_failed)
    val exportFailed = stringResource(R.string.msg_project_export_failed)

    val header: @Composable () -> Unit = {
        Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Ic.Logo, "MPS", Modifier.size(width = 96.dp, height = 34.dp), tint = c.text)
            Spacer(Modifier.weight(1f))
            IconBtn(Ic.Help, stringResource(R.string.help), onHelp, tint = c.textDim)
            IconBtn(Ic.Settings, stringResource(R.string.settings), onSettings, tint = c.textDim)
        }
        Text(
            stringResource(R.string.app_tagline), Modifier.padding(start = 20.dp, end = 20.dp, top = 2.dp), color = c.textDim,
            fontSize = 13.sp,
        )
    }
    val templateRow: @Composable () -> Unit = {
        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            items(templates, key = { it.id }) { t ->
                TemplateCard(t, enabled = !opening) {
                    opening = true
                    studio.createProject(t.id) { opening = false }
                }
            }
        }
    }
    val demoCard: @Composable () -> Unit = {
        if (demo != null) {
            DemoCard(demo, enabled = !opening) {
                opening = true
                studio.createProject(demo.id) { opening = false }
            }
        }
    }
    /** "My projects" with the button that opens a project file. */
    val projectsTitle: @Composable () -> Unit = {
        Row(Modifier.fillMaxWidth().padding(end = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) { SectionTitle(stringResource(R.string.home_projects)) }
            Chip(stringResource(R.string.project_import), {
                try {
                    importFile.launch(arrayOf("*/*"))
                } catch (_: Exception) {
                    studio.message = importFailed
                }
            }, Modifier.padding(top = 12.dp), icon = Ic.Folder)
        }
    }
    val emptyNote: @Composable () -> Unit = {
        Text(
            stringResource(R.string.home_empty), Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 28.dp),
            color = c.textDim, fontSize = 14.sp, lineHeight = 20.sp, textAlign = TextAlign.Center,
        )
    }
    val projectRow: @Composable (ProjectInfo) -> Unit = { info ->
        ProjectRow(
            info,
            onOpen = {
                if (!opening) {
                    opening = true
                    studio.openProject(info.id) { opening = false }
                }
            },
            onMenu = { menuFor = info },
        )
    }

    BoxWithConstraints(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing), contentAlignment = Alignment.TopCenter) {
        val wide = maxWidth >= 640.dp && maxWidth > maxHeight
        if (wide) {
            // on its side: new tracks on the left, saved projects on the right
            Row(Modifier.fillMaxSize()) {
                LazyColumn(Modifier.weight(0.47f).fillMaxHeight(), contentPadding = PaddingValues(bottom = 24.dp)) {
                    item(key = "header") { header() }
                    item(key = "start") { SectionTitle(stringResource(R.string.home_start)) }
                    item(key = "templates") { templateRow() }
                    item(key = "demo") { demoCard() }
                }
                Box(Modifier.width(1.dp).fillMaxHeight().background(c.line))
                LazyColumn(Modifier.weight(0.53f).fillMaxHeight(), contentPadding = PaddingValues(bottom = 24.dp)) {
                    item(key = "projects") { projectsTitle() }
                    if (projects.isEmpty()) item(key = "empty") { emptyNote() }
                    else items(projects, key = { it.id }) { info -> projectRow(info) }
                }
            }
        } else {
            LazyColumn(Modifier.fillMaxSize().widthIn(max = 720.dp), contentPadding = PaddingValues(bottom = 32.dp)) {
                item(key = "header") { header() }
                item(key = "start") { SectionTitle(stringResource(R.string.home_start)) }
                item(key = "templates") { templateRow() }
                item(key = "demo") { demoCard() }
                item(key = "projects") { projectsTitle() }
                if (projects.isEmpty()) item(key = "empty") { emptyNote() }
                else items(projects, key = { it.id }) { info -> projectRow(info) }
            }
        }
    }

    menuFor?.let { info ->
        AppSheet(onDismiss = { menuFor = null }, title = info.name) {
            SheetItem(stringResource(R.string.open), icon = Ic.Folder, onClick = {
                menuFor = null
                studio.openProject(info.id)
            })
            SheetItem(stringResource(R.string.rename), icon = Ic.Rename, onClick = {
                menuFor = null
                renameFor = info
            })
            val copyName = stringResource(R.string.copy_of, info.name)
            SheetItem(stringResource(R.string.duplicate), icon = Ic.Copy, onClick = {
                menuFor = null
                studio.duplicateProject(info.id, copyName)
            })
            SheetItem(stringResource(R.string.project_export), icon = Ic.Save, description = stringResource(R.string.project_export_desc), onClick = {
                menuFor = null
                exportId = info.id
                try {
                    exportFile.launch(projectFileName(info.name))
                } catch (_: Exception) {
                    exportId = null
                    studio.message = exportFailed
                }
            })
            SheetItem(stringResource(R.string.delete), icon = Ic.Delete, danger = true, onClick = {
                menuFor = null
                deleteFor = info
            })
        }
    }
    renameFor?.let { info ->
        TextInputDialog(
            stringResource(R.string.rename_project), info.name,
            onConfirm = {
                renameFor = null
                studio.renameProject(info.id, it)
            },
            onDismiss = { renameFor = null },
        )
    }
    deleteFor?.let { info ->
        ConfirmDialog(
            stringResource(R.string.delete_project_title), stringResource(R.string.delete_project_text, info.name),
            stringResource(R.string.delete),
            onConfirm = {
                deleteFor = null
                studio.deleteProject(info.id)
            },
            onDismiss = { deleteFor = null },
        )
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text, Modifier.padding(start = 20.dp, end = 20.dp, top = 22.dp, bottom = 10.dp).semantics { heading() },
        color = Mps.colors.text, fontSize = 17.sp, fontWeight = FontWeight.Bold,
    )
}

@Composable
private fun TemplateCard(t: Template, enabled: Boolean, onClick: () -> Unit) {
    val c = Mps.colors
    val color = c.channel(t.color)
    Column(
        Modifier
            .width(156.dp)
            .height(168.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(Brush.linearGradient(listOf(color, lerp(color, c.accent, 0.55f))))
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(14.dp),
    ) {
        Box(Modifier.size(34.dp).clip(RoundedCornerShape(11.dp)).background(Color.White.copy(alpha = 0.25f)), contentAlignment = Alignment.Center) {
            Icon(if (t.id == "empty") Ic.Add else Ic.Note, null, Modifier.size(20.dp), tint = Color.White)
        }
        Spacer(Modifier.weight(1f))
        Text(stringResource(t.nameRes), color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(
            stringResource(t.descRes), color = Color.White.copy(alpha = 0.9f), fontSize = 11.sp, lineHeight = 14.sp, maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(4.dp))
        Text("${t.bpm} BPM", color = Color.White.copy(alpha = 0.9f), fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun DemoCard(t: Template, enabled: Boolean, onClick: () -> Unit) {
    val c = Mps.colors
    Row(
        Modifier
            .padding(start = 16.dp, end = 16.dp, top = 12.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(c.surface)
            .border(1.dp, c.line, RoundedCornerShape(20.dp))
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(48.dp).clip(RoundedCornerShape(15.dp)).background(c.accent), contentAlignment = Alignment.Center) {
            Icon(Ic.Play, null, Modifier.size(28.dp), tint = c.onAccent)
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.home_demo), color = c.text, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            Text(stringResource(R.string.home_demo_desc), color = c.textDim, fontSize = 12.sp, lineHeight = 16.sp)
        }
        Icon(Ic.ChevronRight, null, Modifier.size(22.dp), tint = c.textDim)
    }
}

@Composable
private fun ProjectRow(info: ProjectInfo, onOpen: () -> Unit, onMenu: () -> Unit) {
    val c = Mps.colors
    Row(
        Modifier
            .padding(horizontal = 16.dp, vertical = 5.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(c.surface)
            .border(1.dp, c.line, RoundedCornerShape(18.dp))
            .clickable(role = Role.Button, onClick = onOpen)
            .padding(start = 12.dp, top = 10.dp, bottom = 10.dp, end = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Cover(info, Modifier.size(48.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(info.name, color = c.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            // a project without anything in the playlist has no length yet
            val tempo = if (info.seconds > 0.5f) stringResource(R.string.project_meta, Fmt.bpm(info.bpm), Fmt.time(info.seconds))
            else Fmt.bpm(info.bpm) + " BPM"
            val justNow = stringResource(R.string.just_now)
            val date = remember(info.modified, justNow) {
                val now = System.currentTimeMillis()
                if (now - info.modified < DateUtils.MINUTE_IN_MILLIS) justNow
                else DateUtils.getRelativeTimeSpanString(info.modified, now, DateUtils.MINUTE_IN_MILLIS).toString()
            }
            Text(
                "$tempo · $date", color = c.textDim, fontSize = 12.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        IconBtn(Ic.More, stringResource(R.string.more), onMenu, tint = c.textDim)
    }
}

/** A tiny generated "cover": equalizer bars in the colours of the project's channels. */
@Composable
private fun Cover(info: ProjectInfo, modifier: Modifier) {
    val c = Mps.colors
    val colors = if (info.colors.isEmpty()) listOf(0) else info.colors.take(6)
    val bg = c.surfaceHigh
    val palette = colors.map { c.channel(it) }
    val seed = info.id.hashCode()
    Canvas(modifier.clip(RoundedCornerShape(14.dp)).background(bg)) {
        val n = palette.size
        val pad = size.width * 0.16f
        val slot = (size.width - pad * 2) / n
        val barW = slot * 0.62f
        for (i in 0 until n) {
            // deterministic pseudo-random height per bar
            val h = 0.3f + 0.6f * (((seed ushr (i * 5)) and 31) / 31f)
            val barH = (size.height - pad * 2) * h
            drawRoundRect(
                palette[i],
                topLeft = Offset(pad + slot * i + (slot - barW) / 2, size.height - pad - barH),
                size = Size(barW, barH),
                cornerRadius = CornerRadius(barW / 2, barW / 2),
            )
        }
    }
}
