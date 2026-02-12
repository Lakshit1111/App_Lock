package com.applock

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp

@Composable
fun PatternLockView(
    currentPattern: List<Int>,
    onUpdatePattern: (Int) -> Unit,
    onComplete: () -> Unit
) {
    val dots = (0 until 9).toList()

    Box(
        modifier = Modifier
            .size(300.dp)
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { offset ->
                        val index = getDotIndex(offset, size.width, size.height)
                        if (index != -1) onUpdatePattern(index)
                    },
                    onDragEnd = { onComplete() },
                    onDrag = { change, _ ->
                        val index = getDotIndex(change.position, size.width, size.height)
                        if (index != -1) onUpdatePattern(index)
                    }
                )
            }
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val width = size.width
            val height = size.height
            val cellWidth = width / 3
            val cellHeight = height / 3

            // Draw connecting lines
            if (currentPattern.size > 1) {
                for (i in 0 until currentPattern.size - 1) {
                    val startDot = currentPattern[i]
                    val endDot = currentPattern[i+1]

                    // FIX: Explicitly convert indices to Float to prevent Type Mismatch errors
                    val startX = (startDot % 3).toFloat() * cellWidth + (cellWidth / 2)
                    val startY = (startDot / 3).toFloat() * cellHeight + (cellHeight / 2)
                    val endX = (endDot % 3).toFloat() * cellWidth + (cellWidth / 2)
                    val endY = (endDot / 3).toFloat() * cellHeight + (cellHeight / 2)

                    drawLine(
                        color = Color.Blue,
                        start = Offset(startX, startY),
                        end = Offset(endX, endY),
                        strokeWidth = 10f,
                        cap = StrokeCap.Round
                    )
                }
            }

            // Draw Dots
            dots.forEach { index ->
                val col = index % 3
                val row = index / 3
                
                // FIX: Explicit Float casting here as well for consistency
                val cx = col.toFloat() * cellWidth + (cellWidth / 2)
                val cy = row.toFloat() * cellHeight + (cellHeight / 2)

                val isSelected = currentPattern.contains(index)
                val radius = if (isSelected) 25f else 15f
                val color = if (isSelected) Color.Blue else Color.Gray

                drawCircle(
                    color = color,
                    center = Offset(cx, cy),
                    radius = radius
                )
            }
        }
    }
}

fun getDotIndex(offset: Offset, width: Float, height: Float): Int {
    if (offset.x < 0 || offset.x > width || offset.y < 0 || offset.y > height) return -1

    val col = (offset.x / (width / 3)).toInt()
    val row = (offset.y / (height / 3)).toInt()

    if (col in 0..2 && row in 0..2) {
        return row * 3 + col
    }
    return -1
}