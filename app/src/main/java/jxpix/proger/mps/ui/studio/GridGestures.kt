package jxpix.proger.mps.ui.studio

import androidx.compose.animation.core.AnimationState
import androidx.compose.animation.core.DecayAnimationSpec
import androidx.compose.animation.core.animateDecay
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.unit.Velocity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * Shared touch handling for the two big editors (piano roll and playlist):
 * one finger pans, two fingers zoom, a quick release keeps the view gliding.
 */
class PanZoom(
    private val scope: CoroutineScope,
    private val decay: DecayAnimationSpec<Float>,
    /** Moves the view by the given number of pixels (already clamped by the receiver). */
    private val onPan: (dx: Float, dy: Float) -> Unit,
    /** Zooms by [factor] keeping the content under [centroid] in place. */
    private val onZoom: (factor: Float, centroid: Offset) -> Unit,
) {
    private var fling: Job? = null

    /** True while a finger is down; used to pause "follow the play head". */
    var touching = false
        private set

    fun stopFling() {
        fling?.cancel()
        fling = null
    }

    fun begin() {
        stopFling()
        touching = true
    }

    fun end() {
        touching = false
    }

    /**
     * Runs after the first finger went down on empty space.
     * Returns true when the gesture was a plain tap (no movement, one finger).
     */
    suspend fun AwaitPointerEventScope.track(down: PointerInputChange, slop: Float): Boolean {
        val tracker = VelocityTracker()
        tracker.addPosition(down.uptimeMillis, down.position)
        var moving = false
        var multi = false
        while (true) {
            val event = awaitPointerEvent()
            val pressed = event.changes.count { it.pressed }
            if (pressed == 0) break
            if (pressed >= 2) {
                multi = true
                moving = true
            }
            if (!moving) {
                val first = event.changes.firstOrNull { it.id == down.id }
                if (first != null && (first.position - down.position).getDistance() > slop) moving = true
            }
            if (moving) {
                if (pressed >= 2) {
                    val zoom = event.calculateZoom()
                    if (zoom != 1f) onZoom(zoom, event.calculateCentroid(useCurrent = false))
                }
                val pan = event.calculatePan()
                if (pan != Offset.Zero) onPan(-pan.x, -pan.y)
                for (ch in event.changes) {
                    if (ch.positionChanged()) ch.consume()
                }
                if (pressed == 1) {
                    val ch = event.changes.first { it.pressed }
                    tracker.addPosition(ch.uptimeMillis, ch.position)
                } else {
                    tracker.resetTracking()
                }
            }
        }
        if (!moving) return true
        if (!multi) startFling(tracker.calculateVelocity())
        return false
    }

    private fun startFling(v: Velocity) {
        if (abs(v.x) < 200f && abs(v.y) < 200f) return
        fling = scope.launch {
            launch {
                var last = 0f
                AnimationState(0f, v.x).animateDecay(decay) {
                    onPan(-(value - last), 0f)
                    last = value
                }
            }
            launch {
                var last = 0f
                AnimationState(0f, v.y).animateDecay(decay) {
                    onPan(0f, -(value - last))
                    last = value
                }
            }
        }
    }
}
