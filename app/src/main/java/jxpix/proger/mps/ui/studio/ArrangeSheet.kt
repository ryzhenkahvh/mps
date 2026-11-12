package jxpix.proger.mps.ui.studio

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import jxpix.proger.mps.R
import jxpix.proger.mps.music.Arranger
import jxpix.proger.mps.studio.Studio
import jxpix.proger.mps.ui.Fmt
import jxpix.proger.mps.ui.Ic
import jxpix.proger.mps.ui.theme.NumberStyle
import jxpix.proger.mps.ui.SwitchRow
import jxpix.proger.mps.ui.components.AppSheet
import jxpix.proger.mps.ui.components.Segmented
import jxpix.proger.mps.ui.components.SheetLabel
import jxpix.proger.mps.ui.theme.Mps
import kotlin.math.max

private val CELL_WIDTH = 46.dp
private val CELL_HEIGHT = 34.dp
private val NAMES_WIDTH = 104.dp

/**
 * Arrangement by template: the loop that is selected is laid out in the playlist as a whole
 * track. The sheet shows the cake before it is baked - which layer plays in which part - and
 * every square of it can be switched.
 */
@Composable
internal fun ArrangeSheet(studio: Studio, onDismiss: () -> Unit) {
    val c = Mps.colors
    val ui = studio.ui
    val p = studio.project
    val res = LocalResources.current
    val pattern = studio.currentPattern ?: return
    val hasPatterns = p.clips.any { !it.isAudio }
    var replaceAsked by remember { mutableStateOf(true) }
    // with no pattern clips there is nothing to replace: the arrangement starts at the beginning, next to the audio
    val replace = replaceAsked || !hasPatterns

    val index = ui.arrangeTemplate.coerceIn(0, Arranger.templates.size - 1)
    val t = Arranger.templates[index]
    val bars = remember(t, pattern.bars, ui.arrangeShort) { Arranger.bars(t, pattern.bars, ui.arrangeShort) }
    val total = bars.sum()
    val free = remember(p, replace, total) { Arranger.freeTracks(p, replace, total) }
    val layers = remember(p.channels, pattern, free.size) { Arranger.layers(p, pattern, max(1, free.size)) }
    val plan = remember(t, layers) { Arranger.plan(t, layers) }
    // the squares the user switched by hand; a new shape starts from its own picture
    val switched = remember(index, layers.size) { mutableStateMapOf<Int, Boolean>() }
    fun on(section: Int, layer: Int): Boolean = switched[section * 64 + layer] ?: plan[section][layer]

    AppSheet(onDismiss, title = stringResource(R.string.arr_title), scroll = false) {
        if (layers.isEmpty()) {
            Text(
                stringResource(R.string.arr_empty, pattern.name), Modifier.padding(horizontal = 20.dp, vertical = 12.dp), color = c.textDim,
                fontSize = 14.sp, lineHeight = 20.sp,
            )
            return@AppSheet
        }
        Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
            Text(
                stringResource(R.string.arr_about, pattern.name), Modifier.padding(start = 20.dp, end = 20.dp, bottom = 4.dp), color = c.textDim,
                fontSize = 13.sp, lineHeight = 18.sp,
            )
            Choice(stringResource(R.string.arr_shape), Arranger.templates.map { stringResource(it.name) }, index) { ui.arrangeTemplate = it }
            Text(stringResource(t.desc), Modifier.padding(horizontal = 20.dp, vertical = 2.dp), color = c.textDim, fontSize = 12.sp, lineHeight = 16.sp)
            Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 16.dp, top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.arr_length), Modifier.weight(1f), color = c.text, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                Segmented(listOf(stringResource(R.string.arr_short), stringResource(R.string.arr_full)), if (ui.arrangeShort) 0 else 1, { ui.arrangeShort = it == 0 })
            }

            // ---- the cake: a row for every layer, a column for every part of the track
            SheetLabel(stringResource(R.string.arr_grid))
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, top = 2.dp)) {
                Column(Modifier.width(NAMES_WIDTH)) {
                    Spacer(Modifier.height(34.dp))
                    for (layer in layers) {
                        Row(Modifier.height(CELL_HEIGHT), verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(10.dp).clip(CircleShape).background(c.channel(layer.color)))
                            Spacer(Modifier.width(7.dp))
                            Text(layer.name, color = c.text, fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
                Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()).padding(end = 16.dp)) {
                    t.sections.forEachIndexed { s, section ->
                        val name = stringResource(section.name)
                        Column(Modifier.width(CELL_WIDTH), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(name, Modifier.height(16.dp), color = c.text, fontSize = 10.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, softWrap = false)
                            Text(
                                bars[s].toString(), Modifier.height(18.dp), color = c.textDim, fontSize = 10.sp, style = NumberStyle,
                                textAlign = TextAlign.Center, maxLines = 1,
                            )
                            layers.forEachIndexed { l, layer ->
                                val isOn = on(s, l)
                                val label = stringResource(R.string.arr_cell, layer.name, s + 1, name)
                                Box(
                                    Modifier
                                        .size(CELL_WIDTH, CELL_HEIGHT)
                                        .toggleable(isOn, role = Role.Switch) { switched[s * 64 + l] = it }
                                        .semantics { contentDescription = label }
                                        .padding(horizontal = 1.5.dp, vertical = 2.dp)
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(if (isOn) c.channel(layer.color) else c.surfaceHigh),
                                )
                            }
                        }
                    }
                }
            }
            Text(stringResource(R.string.arr_grid_hint), Modifier.padding(horizontal = 20.dp, vertical = 6.dp), color = c.textDim, fontSize = 12.sp, lineHeight = 16.sp)
            if (hasPatterns) {
                SwitchRow(stringResource(R.string.arr_replace), stringResource(R.string.arr_replace_desc), replaceAsked) { replaceAsked = it }
            }
            if (layers.size > free.size) {
                // only audio can be in the way: pattern clips are either replaced or left behind, before the arrangement
                Text(
                    stringResource(R.string.arr_no_room, layers.size, free.size) + " " +
                        stringResource(if (hasPatterns) R.string.arr_no_room_after else R.string.arr_no_room_move),
                    Modifier.padding(horizontal = 20.dp, vertical = 6.dp), color = c.record, fontSize = 13.sp, lineHeight = 18.sp,
                )
            }
        }

        // ---- how long it will be, and the button; they stay in view
        Box(Modifier.fillMaxWidth().height(1.dp).background(c.line))
        val time = Fmt.time((total * p.ticksPerBar * p.secondsPerTick).toFloat())
        val layersText = pluralStringResource(R.plurals.layers_count, layers.size, layers.size)
        Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 16.dp, top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                pluralStringResource(R.plurals.bars, total, total) + " · " + time, Modifier.weight(1f), color = c.text, fontSize = 14.sp,
                fontWeight = FontWeight.Medium, style = NumberStyle, maxLines = 1,
            )
            BigButton(stringResource(R.string.arr_apply), Ic.Layers, enabled = layers.size <= free.size) {
                val grid = Array(t.sections.size) { s -> BooleanArray(layers.size) { l -> on(s, l) } }
                val start = Arranger.startOf(studio.project, replace)
                var done = false
                // one step for "undo", whatever it took
                studio.edit { q -> Arranger.apply(q, pattern.id, t, layers, grid, bars, replace)?.also { done = true } ?: q }
                if (done) {
                    ui.selectedClip = 0
                    // the track is what there is to hear now
                    if (!studio.songMode) studio.changeSongMode(true)
                    // the whole cake at a glance; pinching brings it closer
                    val px = ui.playlistGridW / ((total + 2) * p.ticksPerBar).toFloat()
                    ui.playlist.pxPerTick = px
                    ui.playlist.scrollX = start * px
                    ui.playlist.scrollY = 0f
                    studio.message = res.getString(R.string.msg_arr_done, layersText, time)
                    onDismiss()
                } else {
                    studio.message = res.getString(R.string.msg_arr_failed)
                }
            }
        }
    }
}
