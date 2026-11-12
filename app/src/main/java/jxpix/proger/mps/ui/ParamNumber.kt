package jxpix.proger.mps.ui

import android.content.res.Resources
import androidx.annotation.StringRes
import jxpix.proger.mps.R
import jxpix.proger.mps.engine.ParamCurve
import jxpix.proger.mps.engine.ParamSpec
import jxpix.proger.mps.engine.ParamUnit
import jxpix.proger.mps.model.noteName
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * A setting of a device as a number in a field: how it is written, what a typed text means and
 * what one press of an arrow does.
 *
 * The number in the field is the value in the unit people use for it (percent, milliseconds,
 * decibels), and an arrow changes it by one: one percent, one hertz, one decibel. Only where one
 * would be a leap - a rate of half a hertz, a width of 0.9 - an arrow moves the last digit that
 * is shown. Any value in between can be typed.
 */
object ParamNumber {
    /** How often an arrow that is held down repeats, in milliseconds. */
    const val REPEAT_MS = 70L

    /** How long an arrow has to be held before it starts repeating. */
    const val HOLD_MS = 400L

    /**
     * [scale]: the number shown is the value times this (a share of 0.8 is shown as 80 percent).
     * [step]: what one press of an arrow adds to the shown number; [decimals]: the digits after its point.
     */
    class Form(val scale: Float, val step: Float, val decimals: Int, @param:StringRes val unit: Int, val suffix: String, val note: Boolean)

    fun form(spec: ParamSpec): Form {
        var scale = 1f
        var unit = 0
        var suffix = ""
        when (spec.unit) {
            ParamUnit.PERCENT -> {
                scale = 100f
                suffix = "%"
            }
            ParamUnit.HZ -> unit = R.string.unit_hz
            // times that go down to a few milliseconds are written in milliseconds all the way up
            ParamUnit.SECONDS -> if (spec.min < 0.1f) {
                scale = 1000f
                unit = R.string.unit_ms
            } else unit = R.string.unit_s
            ParamUnit.MILLIS -> unit = R.string.unit_ms
            ParamUnit.DB, ParamUnit.DB_AMOUNT -> unit = R.string.unit_db
            ParamUnit.SEMITONES -> unit = R.string.unit_st
            ParamUnit.CENTS -> unit = R.string.unit_ct
            ParamUnit.DEGREES -> suffix = "°"
            ParamUnit.RATIO -> suffix = ":1"
            else -> {}
        }
        val lo = spec.min * scale
        val hi = spec.max * scale
        val step = when {
            spec.integer || spec.unit == ParamUnit.NOTE || spec.unit == ParamUnit.INT || spec.unit == ParamUnit.OCTAVES -> 1f
            // a setting that covers several powers of ten is as fine as its smallest value needs
            spec.curve == ParamCurve.EXP && lo > 0f -> min(1f, 10f.pow(floor(log10(lo) + 1e-4f)))
            hi - lo >= 20f -> 1f
            hi - lo >= 2f -> 0.1f
            else -> 0.01f
        }
        return Form(scale, step, if (step >= 0.999f) 0 else if (step >= 0.0999f) 1 else 2, unit, suffix, spec.unit == ParamUnit.NOTE)
    }

    private fun rounded(x: Double, decimals: Int): Double {
        val m = 10.0.pow(decimals)
        return Math.round(x * m) / m
    }

    /**
     * The number as it stands in the field: "80", "−18", "+2.5", "0.25", "C4".
     * [plain] is the same for the keyboard: a minus that can be typed, no plus.
     */
    fun text(spec: ParamSpec, value: Float, plain: Boolean = false): String {
        val f = form(spec)
        if (f.note) return noteName(value.roundToInt())
        val shown = value.toDouble() * f.scale
        // a value between two steps (typed in, or left there by a knob of an earlier version) shows one digit more
        var decimals = f.decimals
        if (decimals < 2 && abs(shown - rounded(shown, decimals)) > 0.05 * 10.0.pow(-decimals)) decimals++
        val r = rounded(shown, decimals)
        val digits = String.format(Locale.ROOT, "%.${decimals}f", abs(r))
        return when {
            r < 0.0 -> (if (plain) "-" else "−") + digits
            r > 0.0 && spec.min < 0f && !plain -> "+$digits"
            else -> digits
        }
    }

    /** The unit that stands after the number: "Hz", "%", "dB"... or nothing. */
    fun unit(spec: ParamSpec, res: Resources): String {
        val f = form(spec)
        return if (f.unit != 0) res.getString(f.unit, "").trim() else f.suffix
    }

    /** "−18 … +18 dB": what can be typed. */
    fun range(spec: ParamSpec, res: Resources): String = text(spec, spec.min) + " … " + text(spec, spec.max) + unit(spec, res).let { if (it.isEmpty() || it == "%" || it == "°" || it == ":1") it else " $it" }

    private fun fromShown(spec: ParamSpec, f: Form, shown: Double): Float {
        var v = (rounded(shown, 3) / f.scale).toFloat()
        if (spec.integer || f.note) v = v.roundToInt().toFloat()
        return v.coerceIn(spec.min, spec.max)
    }

    /**
     * The value one press of an arrow away: the next step up ([direction] > 0) or down. A value
     * between two steps goes to the nearer one in that direction. [times] makes the step that many
     * times as large (an arrow that is held down), and the value lands on round numbers.
     */
    fun stepped(spec: ParamSpec, value: Float, direction: Int, times: Int = 1): Float {
        val f = form(spec)
        val s = f.step.toDouble() * max(1, times)
        val at = value.toDouble() * f.scale / s
        val next = if (direction > 0) floor(at + 1e-3) + 1.0 else ceil(at - 1e-3) - 1.0
        return fromShown(spec, f, next * s)
    }

    /**
     * How many steps at once an arrow makes after it has been held for [heldMs]: one at first, so
     * that a short hold is still exact, then more and more - the whole range of any setting is
     * crossed in a few seconds.
     */
    fun speed(spec: ParamSpec, heldMs: Long): Int {
        if (heldMs < 1200L) return 1
        val f = form(spec)
        val steps = (spec.max - spec.min) * f.scale / f.step
        // a share of the range per second, in steps per repeat
        val wanted = steps * (if (heldMs < 2600L) 0.15f else 0.45f) * REPEAT_MS / 1000f
        var best = 1
        for (m in intArrayOf(1, 2, 5, 10, 20, 50, 100, 200, 500, 1000, 2000, 5000)) if (m <= wanted) best = m
        return best
    }

    private val NOTE = Regex("^([A-Ga-g])([#b♯♭]?)(-?\\d)$")
    private val NUMBER = Regex("^[+-]?(\\d+\\.?\\d*|\\.\\d+)")

    /**
     * What a typed text means, or null when it is no number. A comma is as good as a point, a unit
     * after the number does no harm, and a number outside of the range becomes the nearest end of it.
     */
    fun parse(spec: ParamSpec, typed: String): Float? {
        val f = form(spec)
        var t = typed.trim().replace('−', '-').replace(',', '.').replace(" ", "").replace("\u00A0", "")
        if (f.note) {
            val m = NOTE.find(t)
            if (m != null) {
                val base = intArrayOf(9, 11, 0, 2, 4, 5, 7)[m.groupValues[1].uppercase()[0] - 'A']
                val bend = when (m.groupValues[2]) {
                    "#", "♯" -> 1
                    "b", "♭" -> -1
                    else -> 0
                }
                return fromShown(spec, f, (base + bend + (m.groupValues[3].toInt() + 1) * 12).toDouble())
            }
        }
        if (t.startsWith("+")) t = t.substring(1)
        val number = NUMBER.find(t)?.value?.toDoubleOrNull() ?: return null
        if (number.isNaN() || number.isInfinite()) return null
        return fromShown(spec, f, number)
    }
}
