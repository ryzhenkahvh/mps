package jxpix.proger.mps.ui.components

import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke as Outline
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import jxpix.proger.mps.R
import jxpix.proger.mps.engine.Arc
import jxpix.proger.mps.engine.Area
import jxpix.proger.mps.engine.Bar
import jxpix.proger.mps.engine.Caption
import jxpix.proger.mps.engine.DeviceType
import jxpix.proger.mps.engine.Dot
import jxpix.proger.mps.engine.Effect
import jxpix.proger.mps.engine.Ink
import jxpix.proger.mps.engine.Live
import jxpix.proger.mps.engine.Picture
import jxpix.proger.mps.engine.Pictures
import jxpix.proger.mps.engine.Rule
import jxpix.proger.mps.engine.Stroke
import jxpix.proger.mps.engine.Tap
import jxpix.proger.mps.engine.dsp.Fft
import jxpix.proger.mps.model.DeviceState
import jxpix.proger.mps.studio.Studio
import jxpix.proger.mps.ui.Fmt
import jxpix.proger.mps.ui.theme.Mps
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/** Where the pictures of the devices are worked out: some of them run the device, which is no work for the thread of the screen. */
object PictureWork {
    @Volatile
    var dispatcher: CoroutineDispatcher = Dispatchers.Default
}

/** The spectrum behind a picture: what the effect has just put out, in bands along the frequency axis. */
private class Spectrum {
    val bands = FloatArray(BANDS)
    var tap: Tap? = null
    private val fft = Fft(SIZE)
    private val re = FloatArray(SIZE)
    private val im = FloatArray(SIZE)
    private val window = FloatArray(SIZE) { 0.5f - 0.5f * cos(2.0 * PI * it / SIZE).toFloat() }

    fun update(source: Tap, rate: Int) {
        source.read(re)
        for (i in 0 until SIZE) re[i] *= window[i]
        im.fill(0f)
        fft.transform(re, im)
        for (b in 0 until BANDS) {
            val from = (Pictures.freqAt(b / BANDS.toFloat()) * SIZE / rate).toInt().coerceIn(1, SIZE / 2 - 1)
            val to = (Pictures.freqAt((b + 1) / BANDS.toFloat()) * SIZE / rate).toInt().coerceIn(from, SIZE / 2 - 1)
            var power = 0f
            for (i in from..to) power = max(power, re[i] * re[i] + im[i] * im[i])
            // a tone at full scale stands at the top, 78 dB below it is the bottom
            val level = ((10f * log10(power + 1e-20f) - 20f * log10(SIZE / 4f) + 78f) / 78f).coerceIn(0f, 1f)
            // up at once, down slowly: the way level meters are read
            bands[b] = if (level > bands[b]) level else max(level, bands[b] - 0.025f)
        }
    }

    fun clear() {
        bands.fill(0f)
    }

    companion object {
        const val SIZE = 2048
        const val BANDS = 72
    }
}

/**
 * What a device does with its current settings, drawn above them: the curve of an equaliser,
 * the tail of a reverb, the hit of a drum (see [Pictures]). While sound runs through an effect,
 * the picture shows that too: the spectrum behind a filter, how much a compressor takes away,
 * where a 3D source is.
 *
 * @param sounding whether sound is running through the device right now
 */
@Composable
fun DevicePicture(
    studio: Studio,
    type: DeviceType,
    group: Int,
    state: DeviceState,
    color: Color,
    sounding: () -> Boolean,
    modifier: Modifier = Modifier,
    height: Dp = 96.dp,
) {
    val c = Mps.colors
    val res = LocalResources.current
    val bpm = studio.project.bpm
    val keyMask = studio.project.keyMask
    val params = state.params
    // the picture of the settings before stays until the new one is ready
    val picture by produceState<Picture?>(null, type, group, params, bpm, keyMask) {
        value = withContext(PictureWork.dispatcher) {
            try {
                Pictures.of(type.id, group, { id -> params[id] ?: type.spec(id)?.default ?: 0f }, bpm, keyMask)
            } catch (_: Exception) {
                null
            }
        }
    }
    val live = picture?.live ?: Live.NONE
    val effect = remember(state.uid, studio.project) { studio.engine.deviceOf(state.uid) as? Effect }
    val spectrum = remember { Spectrum() }
    // while the picture shows a spectrum, the engine leaves what the effect puts out for it
    DisposableEffect(effect, live == Live.SPECTRUM) {
        val tap = if (effect != null && live == Live.SPECTRUM) Tap() else null
        if (tap != null) effect?.listener = tap
        spectrum.tap = tap
        onDispose {
            if (tap != null && effect?.listener === tap) effect?.listener = null
            spectrum.tap = null
        }
    }
    val meter = remember { FloatArray(1) }
    val paint = remember { Paint(Paint.ANTI_ALIAS_FLAG) }
    val description = stringResource(R.string.picture_of, stringResource(type.nameRes))
    val left = stringResource(R.string.side_left)
    val right = stringResource(R.string.side_right)

    Canvas(
        modifier.height(height).clip(RoundedCornerShape(10.dp)).background(c.surfaceHigh).semantics { contentDescription = description },
    ) {
        val p = picture ?: return@Canvas
        // something moves in the picture: it is drawn again on every frame the studio counts
        val moving = if (p.live == Live.NONE) false else {
            studio.frame.longValue
            if (p.live == Live.BAR) studio.playing else sounding()
        }
        val pad = 6.dp.toPx()
        val h = size.height - 2 * pad
        val w = if (p.square) h else size.width - 2 * pad
        val x0 = if (p.square) (size.width - h) / 2f else pad
        fun px(x: Float) = x0 + x * w
        fun py(y: Float) = pad + (1f - y) * h
        fun ink(i: Ink): Color = when (i) {
            Ink.MAIN -> color
            Ink.SOFT -> color.copy(alpha = 0.45f)
            Ink.DIM -> c.textDim.copy(alpha = 0.85f)
            Ink.GRID -> c.line
        }

        if (p.live == Live.SPECTRUM) {
            val tap = spectrum.tap
            if (moving && tap != null) {
                spectrum.update(tap, studio.engine.sampleRate)
                val path = Path()
                path.moveTo(px(0f), py(0f))
                for (b in 0 until Spectrum.BANDS) path.lineTo(px((b + 0.5f) / Spectrum.BANDS), py(spectrum.bands[b] * 0.94f))
                path.lineTo(px(1f), py(0f))
                path.close()
                drawPath(path, c.textDim.copy(alpha = 0.3f))
            } else {
                spectrum.clear()
            }
        }

        paint.textSize = 9.dp.toPx() * fontScale
        paint.color = c.textDim.toArgb()
        for (m in p.marks) {
            when (m) {
                is Rule -> {
                    val dash = if (m.dashed) PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx())) else null
                    val width = (if (m.ink == Ink.GRID) 1.dp else 1.2.dp).toPx()
                    if (m.vertical) {
                        drawLine(ink(m.ink), Offset(px(m.at), py(1f)), Offset(px(m.at), py(0f)), width, pathEffect = dash)
                    } else {
                        drawLine(ink(m.ink), Offset(px(0f), py(m.at)), Offset(px(1f), py(m.at)), width, pathEffect = dash)
                    }
                }
                is Stroke -> line(m, ::px, ::py, ink(m.ink))
                is Area -> {
                    val n = m.top.size
                    if (n >= 2) {
                        val path = Path()
                        path.moveTo(px(0f), py(m.top[0]))
                        for (i in 1 until n) path.lineTo(px(i / (n - 1f)), py(m.top[i]))
                        for (i in n - 1 downTo 0) path.lineTo(px(i / (n - 1f)), py(m.bottom[i]))
                        path.close()
                        drawPath(path, ink(m.ink))
                    }
                }
                is Bar -> {
                    val width = if (m.width > 0f) m.width * w else 3.dp.toPx()
                    val top = py(max(m.from, m.to))
                    val bottom = py(min(m.from, m.to))
                    drawRoundRect(ink(m.ink), Offset(px(m.x) - width / 2f, top), Size(width, bottom - top), CornerRadius(min(width / 2f, 2.dp.toPx())))
                }
                is Dot -> drawCircle(ink(m.ink), 3.5.dp.toPx() * m.size, Offset(px(m.x), py(m.y)))
                is Arc -> {
                    val r = m.radius * h
                    val corner = Offset(px(m.x) - r, py(m.y) - r)
                    if (m.fill) {
                        drawArc(ink(m.ink), m.from - 90f, m.sweep, true, corner, Size(2 * r, 2 * r))
                    } else {
                        drawArc(ink(m.ink), m.from - 90f, m.sweep, false, corner, Size(2 * r, 2 * r), style = Outline((if (m.ink == Ink.MAIN) 2.dp else 1.3.dp).toPx()))
                    }
                }
                is Caption -> {
                    val text = when (m.unit) {
                        Caption.SECONDS -> seconds(m.value, res)
                        Caption.LEFT -> left
                        Caption.RIGHT -> right
                        else -> m.text
                    }
                    paint.textAlign = if (m.align < 0) Paint.Align.LEFT else if (m.align > 0) Paint.Align.RIGHT else Paint.Align.CENTER
                    drawContext.canvas.nativeCanvas.drawText(text, px(m.x), py(m.y), paint)
                }
            }
        }

        // ---- what moves while there is sound
        when (p.live) {
            Live.REDUCTION -> {
                val now = if (moving) (effect?.scope?.get(0) ?: 0f).let { if (it.isNaN()) 0f else it.coerceIn(0f, 60f) } else 0f
                meter[0] = if (!moving) 0f else if (now > meter[0]) now else max(now, meter[0] - 0.6f)
                if (meter[0] > 0.05f) {
                    // a meter that grows downwards from the top: how many decibels are taken away right now
                    val bar = 5.dp.toPx()
                    val x = size.width - pad - bar
                    drawRoundRect(c.line, Offset(x, pad), Size(bar, h), CornerRadius(bar / 2f))
                    drawRoundRect(c.meterMid, Offset(x, pad), Size(bar, max(bar, h * min(1f, meter[0] / 24f))), CornerRadius(bar / 2f))
                    paint.textAlign = Paint.Align.RIGHT
                    paint.color = c.text.toArgb()
                    drawContext.canvas.nativeCanvas.drawText(Fmt.db(-meter[0], res), x - 4.dp.toPx(), pad + 9.dp.toPx() * fontScale, paint)
                }
            }
            Live.FREQ -> {
                val f = effect?.scope?.get(0) ?: 0f
                if (moving && f > 20f) drawLine(c.text, Offset(px(Pictures.xOf(f)), py(1f)), Offset(px(Pictures.xOf(f)), py(0f)), 1.5.dp.toPx())
            }
            Live.SOURCE -> {
                val angle = effect?.scope?.get(0) ?: Float.NaN
                if (moving && !angle.isNaN()) {
                    val a = Math.toRadians(angle.toDouble())
                    val at = Offset(px(0.5f + p.lo * sin(a).toFloat()), py(0.5f + p.lo * cos(a).toFloat()))
                    drawCircle(color.copy(alpha = 0.35f), 8.dp.toPx(), at)
                    drawCircle(c.text, 3.5.dp.toPx(), at)
                }
            }
            Live.NOTE -> {
                val note = effect?.scope?.get(0) ?: -1f
                if (moving && note >= 0f) {
                    val key = ((note.roundToInt() % 12) + 12) % 12
                    drawCircle(c.text, 3.5.dp.toPx(), Offset(px((key + 0.5f) / 12f), py(0.95f)))
                }
            }
            Live.BAR -> {
                val at = studio.engine.positionTicks
                val period = p.hi * studio.project.ticksPerBar
                if (moving && at >= 0.0 && period > 0f) {
                    val x = px(((at % period) / period).toFloat())
                    drawLine(c.text, Offset(x, py(1f)), Offset(x, py(0f)), 1.5.dp.toPx())
                }
            }
            else -> {}
        }
    }
}

private fun DrawScope.line(m: Stroke, px: (Float) -> Float, py: (Float) -> Float, color: Color) {
    val n = m.xy.size / 2
    if (n < 2) return
    val path = Path()
    path.moveTo(px(m.xy[0]), py(m.xy[1]))
    for (i in 1 until n) path.lineTo(px(m.xy[i * 2]), py(m.xy[i * 2 + 1]))
    if (m.fill) {
        val area = Path()
        area.addPath(path)
        area.lineTo(px(m.xy[n * 2 - 2]), py(m.base))
        area.lineTo(px(m.xy[0]), py(m.base))
        area.close()
        drawPath(area, color.copy(alpha = color.alpha * 0.17f))
    }
    val width = when (m.ink) {
        Ink.MAIN -> 2.dp
        Ink.SOFT -> 1.6.dp
        else -> 1.2.dp
    }
    drawPath(path, color, style = Outline(width.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
}

/** A stretch of time under a picture: "350 ms", "2 s", "1.94 s". */
private fun seconds(value: Float, res: android.content.res.Resources): String =
    if (value < 0.9995f) {
        res.getString(R.string.unit_ms, (value * 1000f).roundToInt().toString())
    } else {
        val text = String.format(Locale.ROOT, "%.2f", value).trimEnd('0').trimEnd('.')
        res.getString(R.string.unit_s, text)
    }
