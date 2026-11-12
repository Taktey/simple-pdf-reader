package com.taktey.simplepdfreader

import androidx.compose.foundation.gestures.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.unit.Velocity
import kotlin.math.abs

/** One owner for drag, pinch and fling; a second finger never competes with scrollables. */
internal suspend fun PointerInputScope.detectReaderGestures(
    onDown: () -> Unit,
    onTransform: (Offset, Offset, Float) -> Unit,
    onFling: (Velocity) -> Unit,
) = awaitEachGesture {
    val down = awaitFirstDown(requireUnconsumed = false)
    onDown()
    val tracker = VelocityTracker()
    tracker.addPosition(down.uptimeMillis, down.position)
    var totalPan = Offset.Zero
    var totalZoom = 1f
    var pastSlop = false
    var multiplePointers = false
    var cancelled = false
    do {
        val event = awaitPointerEvent()
        if (event.changes.any { it.isConsumed }) { cancelled = true; break }
        if (event.changes.count { it.pressed } > 1) multiplePointers = true
        event.changes.firstOrNull { it.id == down.id }?.let { tracker.addPosition(it.uptimeMillis, it.position) }
        val pan = event.calculatePan()
        val zoom = event.calculateZoom()
        if (!pastSlop) {
            totalPan += pan
            totalZoom *= zoom
            pastSlop = totalPan.getDistance() > viewConfiguration.touchSlop ||
                abs(1f - totalZoom) * event.calculateCentroidSize(useCurrent = false) > viewConfiguration.touchSlop
        }
        if (pastSlop) {
            val centroid = event.calculateCentroid(useCurrent = false)
            if (centroid != Offset.Unspecified && (pan != Offset.Zero || zoom != 1f)) onTransform(centroid, pan, zoom)
            event.changes.filter { it.positionChanged() }.forEach { it.consume() }
        }
    } while (event.changes.any { it.pressed })
    if (pastSlop && !multiplePointers && !cancelled) {
        val limit = 8000f * density
        val speed = tracker.calculateVelocity(Velocity(limit, limit))
        if (abs(speed.x) > 50f * density || abs(speed.y) > 50f * density) onFling(speed)
    }
}
