package jxpix.proger.mps.ui

import android.content.res.Resources
import jxpix.proger.mps.R
import jxpix.proger.mps.engine.ParamSpec
import jxpix.proger.mps.engine.ParamUnit
import jxpix.proger.mps.model.faderToDb
import jxpix.proger.mps.model.noteName
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/** Human readable texts for numbers: parameter values, times, levels. */
object Fmt {

    private fun num(v: Float, decimals: Int): String = String.format(Locale.ROOT, "%.${decimals}f", v)

    private fun signed(v: Int): String = if (v > 0) "+$v" else if (v < 0) "−${-v}" else "0"

    fun param(spec: ParamSpec, value: Float, res: Resources): String {
        if (spec.isChoice) {
            val names = res.getStringArray(spec.choicesRes)
            return names.getOrElse(value.roundToInt().coerceIn(0, names.size - 1)) { "" }
        }
        return when (spec.unit) {
            ParamUnit.PERCENT -> "${(value * 100f).roundToInt()}%"
            ParamUnit.HZ -> when {
                value >= 10000f -> res.getString(R.string.unit_khz, num(value / 1000f, 1))
                value >= 1000f -> res.getString(R.string.unit_khz, num(value / 1000f, 2))
                value >= 100f -> res.getString(R.string.unit_hz, value.roundToInt().toString())
                value >= 10f -> res.getString(R.string.unit_hz, num(value, 1))
                else -> res.getString(R.string.unit_hz, num(value, 2))
            }
            ParamUnit.SECONDS -> when {
                value < 0.0995f -> res.getString(R.string.unit_ms, (value * 1000f).roundToInt().toString())
                value < 1f -> res.getString(R.string.unit_ms, ((value * 100f).roundToInt() * 10).toString())
                else -> res.getString(R.string.unit_s, num(value, 2))
            }
            ParamUnit.MILLIS -> when {
                value < 10f -> res.getString(R.string.unit_ms, num(value, 1))
                else -> res.getString(R.string.unit_ms, value.roundToInt().toString())
            }
            ParamUnit.DB -> db(value, res)
            ParamUnit.SEMITONES -> res.getString(R.string.unit_st, signed(value.roundToInt()))
            ParamUnit.CENTS -> res.getString(R.string.unit_ct, signed(value.roundToInt()))
            ParamUnit.OCTAVES -> signed(value.roundToInt())
            ParamUnit.RATIO -> num(value, 1) + ":1"
            ParamUnit.NOTE -> noteName(value.roundToInt())
            ParamUnit.INT -> value.roundToInt().toString()
            ParamUnit.NONE -> if (spec.integer) value.roundToInt().toString() else num(value, 2)
        }
    }

    fun db(value: Float, res: Resources): String {
        val r = (value * 10f).roundToInt() / 10f
        val text = if (r > 0f) "+" + num(r, 1) else if (r < 0f) "−" + num(-r, 1) else "0.0"
        return res.getString(R.string.unit_db, text)
    }

    /** Fader position -> "−3.5 dB". */
    fun fader(position: Float, res: Resources): String {
        val d = faderToDb(position)
        return if (d.isInfinite() || d < -70f) res.getString(R.string.db_silence) else db(d, res)
    }

    fun pan(pan: Float, res: Resources): String {
        val v = (pan * 100f).roundToInt()
        return when {
            abs(v) < 1 -> res.getString(R.string.pan_center)
            v < 0 -> res.getString(R.string.pan_left, -v)
            else -> res.getString(R.string.pan_right, v)
        }
    }

    /** Where the play head is: 75.9 -> "1:15" (a second is shown once it has fully passed). */
    fun clock(seconds: Double): String {
        val total = seconds.coerceAtLeast(0.0).toInt()
        return "${total / 60}:${(total % 60).toString().padStart(2, '0')}"
    }

    /** Length of a sound: short ones in seconds with a decimal ("0.4 s"), long ones as minutes ("1:15"). */
    fun soundLength(seconds: Float): String = if (seconds < 10f) num(seconds, 1) + " s" else time(seconds)

    /** Megabytes with one decimal below ten: 1.4, 21. */
    fun megabytes(bytes: Double): String {
        val mb = bytes / (1024.0 * 1024.0)
        return if (mb < 9.95) num(mb.toFloat().coerceAtLeast(0.1f), 1) else mb.roundToInt().toString()
    }

    /** 75.3 -> "1:15". */
    fun time(seconds: Float): String {
        val total = seconds.coerceAtLeast(0f).roundToInt()
        return "${total / 60}:${(total % 60).toString().padStart(2, '0')}"
    }

    fun bpm(bpm: Float): String = if (bpm == bpm.roundToInt().toFloat()) bpm.roundToInt().toString() else num(bpm, 1)

    /** Song position as bar.beat.step, counted from 1 like musicians do. */
    fun position(tick: Int, ticksPerBar: Int, ppq: Int): String {
        val t = tick.coerceAtLeast(0)
        val bar = t / ticksPerBar
        val beat = (t % ticksPerBar) / ppq
        val step = (t % ppq) / (ppq / 4)
        return "${bar + 1}.${beat + 1}.${step + 1}"
    }
}
