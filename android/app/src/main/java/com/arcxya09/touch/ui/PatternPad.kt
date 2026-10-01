package com.arcxya09.touch.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.arcxya09.touch.security.Pattern
import kotlin.math.hypot

@Composable fun PatternPad(modifier: Modifier = Modifier, visible: Boolean, onPattern: (List<Int>) -> Unit) {
    var points by remember { mutableStateOf(emptyList<Int>()) }
    val callback by rememberUpdatedState(onPattern)
    val color = MaterialTheme.colorScheme.primary
    fun point(position: Offset, size: IntSize): Int? {
        val step = size.width / 3f
        for (index in 0..8) {
            val center = Offset((index % 3 + 0.5f) * step, (index / 3 + 0.5f) * step)
            if (hypot(position.x - center.x, position.y - center.y) <= step * 0.38f) return index
        }
        return null
    }
    Canvas(modifier.aspectRatio(1f).pointerInput(Unit) {
        awaitEachGesture {
            // Capture the down position before touch slop; quick strokes must keep their first point.
            val down = awaitFirstDown(requireUnconsumed = false)
            points = point(down.position, size)?.let { listOf(it) } ?: emptyList()
            down.consume()
            var completed = false
            do {
                val event = awaitPointerEvent()
                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                if (event.changes.count { it.pressed } > 1 || change.isConsumed) break
                point(change.position, size)?.let { points = Pattern.append(points, it) }
                change.consume()
                completed = !change.pressed
            } while (!completed)
            if (completed && points.isNotEmpty()) callback(points)
            points = emptyList()
        }
    }) {
        if (visible) {
            val step = size.width / 3f
            fun center(index: Int) = Offset((index % 3 + 0.5f) * step, (index / 3 + 0.5f) * step)
            points.zipWithNext().forEach { (a, b) -> drawLine(color.copy(alpha = 0.6f), center(a), center(b), 2.dp.toPx()) }
            for (i in 0..8) drawCircle(color.copy(alpha = if (i in points) 1f else 0.3f), if (i in points) 5.dp.toPx() else 3.dp.toPx(), center(i))
        }
    }
}
