package jxpix.proger.mps.ui.studio

import android.content.res.Resources
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import jxpix.proger.mps.R
import jxpix.proger.mps.model.Project
import jxpix.proger.mps.model.Scales
import jxpix.proger.mps.studio.Studio
import jxpix.proger.mps.ui.components.AppSheet
import jxpix.proger.mps.ui.components.SheetItem
import jxpix.proger.mps.ui.components.SheetLabel
import jxpix.proger.mps.ui.theme.Mps

/** Name of every scale of [Scales], by its number there. */
private val SCALE_NAMES = intArrayOf(
    R.string.key_none, R.string.scale_major, R.string.scale_minor, R.string.scale_penta_major, R.string.scale_penta_minor,
    R.string.scale_blues, R.string.scale_harm_minor, R.string.scale_mel_minor, R.string.scale_dorian, R.string.scale_phrygian,
    R.string.scale_lydian, R.string.scale_mixolydian,
)

/** The order the scales are offered in: the two everybody knows first, their relatives next. */
private val SCALE_ORDER = intArrayOf(0, 1, 2, 6, 7, 8, 9, 10, 11, 3, 4, 5)

/** "A minor"; null when the song has no key. */
internal fun keyName(p: Project, res: Resources): String? {
    if (p.scale <= 0 || p.scale >= SCALE_NAMES.size) return null
    return Scales.NOTE_NAMES[((p.keyRoot % 12) + 12) % 12] + " " + res.getString(SCALE_NAMES[p.scale]).lowercase()
}

/**
 * Choice of the key of the song: a scale and its tonic. The key is part of the project; its notes
 * are marked in the piano roll and on the keyboard, and pitch correction can follow it.
 */
@Composable
internal fun KeySheet(studio: Studio, onDismiss: () -> Unit) {
    val c = Mps.colors
    val p = studio.project
    AppSheet(onDismiss, title = stringResource(R.string.key_title)) {
        Text(
            stringResource(R.string.key_desc), Modifier.padding(start = 20.dp, end = 20.dp, bottom = 6.dp),
            color = c.textDim, fontSize = 13.sp, lineHeight = 18.sp,
        )
        if (p.scale != 0) {
            SheetLabel(stringResource(R.string.scale_root))
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Scales.NOTE_NAMES.forEachIndexed { i, name ->
                    val on = p.keyRoot == i
                    Box(
                        Modifier
                            .weight(1f)
                            .height(40.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(if (on) c.accent else c.surfaceHigh)
                            .pointerInput(i) {
                                awaitEachGesture {
                                    awaitFirstDown().consume()
                                    studio.edit { it.copy(keyRoot = i) }
                                }
                            }
                            .semantics {
                                role = Role.RadioButton
                                selected = on
                                contentDescription = name
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(name, color = if (on) c.onAccent else c.text, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                    }
                }
            }
            SheetLabel(stringResource(R.string.scale))
        }
        for (i in SCALE_ORDER) {
            SheetItem(stringResource(SCALE_NAMES[i]), { studio.edit { it.copy(scale = i) } }, selected = p.scale == i)
        }
    }
}
