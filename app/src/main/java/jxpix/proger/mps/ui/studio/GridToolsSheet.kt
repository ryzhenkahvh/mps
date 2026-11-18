package jxpix.proger.mps.ui.studio

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import jxpix.proger.mps.R
import jxpix.proger.mps.ui.Ic
import jxpix.proger.mps.ui.components.AppSheet
import jxpix.proger.mps.ui.components.IconBtn
import jxpix.proger.mps.ui.theme.Mps

/** Precise, repeatable edits without obscuring the grid with more permanent controls. */
@Composable
internal fun GridToolsSheet(
    snapLabel: String,
    snapTicks: Int,
    onMove: (Int) -> Unit,
    onLength: (Int) -> Unit,
    onTrack: ((Int) -> Unit)? = null,
    onDismiss: () -> Unit,
) {
    AppSheet(onDismiss, title = stringResource(R.string.grid_tools)) {
        Text(stringResource(R.string.grid_tools_hint, snapLabel), Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
            color = Mps.colors.textDim, fontSize = 13.sp, lineHeight = 18.sp)
        GridToolRow(stringResource(R.string.grid_move_step), { onMove(-snapTicks) }, { onMove(snapTicks) })
        GridToolRow(stringResource(R.string.grid_move_tick), { onMove(-1) }, { onMove(1) })
        GridToolRow(stringResource(R.string.grid_length_step), { onLength(-snapTicks) }, { onLength(snapTicks) })
        GridToolRow(stringResource(R.string.grid_length_tick), { onLength(-1) }, { onLength(1) })
        if (onTrack != null) {
            GridToolRow(stringResource(R.string.grid_track), { onTrack(-1) }, { onTrack(1) })
        }
    }
}

@Composable
private fun GridToolRow(label: String, onMinus: () -> Unit, onPlus: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 16.dp, top = 3.dp, bottom = 3.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), color = Mps.colors.text, fontSize = 14.sp)
        IconBtn(Ic.Remove, "$label −", onMinus, background = Mps.colors.surfaceHigh, size = 44.dp)
        Spacer(Modifier.width(8.dp))
        IconBtn(Ic.Add, "$label +", onPlus, background = Mps.colors.surfaceHigh, size = 44.dp)
    }
}
