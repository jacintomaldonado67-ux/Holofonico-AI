package com.example.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

@Composable
fun SpatialRadar(
    interpolatedX: Float,
    interpolatedY: Float,
    targetX: Float,
    targetY: Float,
    onCoordinatesChanged: (x: Float, y: Float) -> Unit,
    modifier: Modifier = Modifier
) {
    // Cyberpunk/Cosmic Dark radar color palette
    val gridColor = Color(0xFF1E293B)
    val circleGridColor = Color(0xFF334155)
    val accentColor = Color(0xFF00E5FF) // Cyan spatial tracking
    val targetColor = Color(0xFF9D4EDD) // Purple spring target
    val centerColor = Color(0xFFF43F5E) // Coral listener

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(24.dp))
            .background(Color(0xFF070B15))
            .testTag("spatial_radar")
            .pointerInput(Unit) {
                // Detect tap on radar
                detectTapGestures { offset ->
                    val centerX = size.width / 2f
                    val centerY = size.height / 2f
                    val radius = size.width / 2f

                    // Map tap to coordinate space [-1, 1]
                    val xVal = (offset.x - centerX) / radius
                    // Invert Y so up is positive and down is negative
                    val yVal = -(offset.y - centerY) / radius
                    
                    // Clamp to circular boundary
                    val dist = sqrt(xVal * xVal + yVal * yVal)
                    if (dist > 1f) {
                        onCoordinatesChanged(xVal / dist, yVal / dist)
                    } else {
                        onCoordinatesChanged(xVal, yVal)
                    }
                }
            }
            .pointerInput(Unit) {
                // Detect drag on radar
                detectDragGestures { change, dragAmount ->
                    change.consume()
                    val centerX = size.width / 2f
                    val centerY = size.height / 2f
                    val radius = size.width / 2f

                    // Map current position relative to center
                    val currentPos = change.position
                    val xVal = (currentPos.x - centerX) / radius
                    val yVal = -(currentPos.y - centerY) / radius

                    // Clamp to circular boundary
                    val dist = sqrt(xVal * xVal + yVal * yVal)
                    if (dist > 1f) {
                        onCoordinatesChanged(xVal / dist, yVal / dist)
                    } else {
                        onCoordinatesChanged(xVal, yVal)
                    }
                }
            }
    ) {
        Canvas(modifier = Modifier.fillMaxSize().padding(16.dp)) {
            val width = size.width
            val height = size.height
            val centerX = width / 2f
            val centerY = height / 2f
            val maxRadius = width / 2f

            // --- 1. Draw Concentric Grid Circles (1m, 2m, 3m, 4m distances) ---
            val dashes = PathEffect.dashPathEffect(floatArrayOf(15f, 15f), 0f)
            val rings = 4
            for (r in 1..rings) {
                val radius = maxRadius * (r.toFloat() / rings)
                drawCircle(
                    color = if (r == rings) circleGridColor else gridColor,
                    radius = radius,
                    center = Offset(centerX, centerY),
                    style = Stroke(
                        width = if (r == rings) 2f else 1f,
                        pathEffect = if (r != rings) dashes else null
                    )
                )
            }

            // --- 2. Draw Crosshair Angular Lines ---
            // Horizontal line (Left/Right)
            drawLine(
                color = gridColor,
                start = Offset(centerX - maxRadius, centerY),
                end = Offset(centerX + maxRadius, centerY),
                strokeWidth = 1f
            )
            // Vertical line (Back/Front)
            drawLine(
                color = gridColor,
                start = Offset(centerX, centerY - maxRadius),
                end = Offset(centerX, centerY + maxRadius),
                strokeWidth = 1f
            )

            // Diagonal reference lines
            drawLine(
                color = gridColor.copy(alpha = 0.5f),
                start = Offset(centerX - maxRadius * 0.707f, centerY - maxRadius * 0.707f),
                end = Offset(centerX + maxRadius * 0.707f, centerY + maxRadius * 0.707f),
                strokeWidth = 1f,
                pathEffect = dashes
            )
            drawLine(
                color = gridColor.copy(alpha = 0.5f),
                start = Offset(centerX - maxRadius * 0.707f, centerY + maxRadius * 0.707f),
                end = Offset(centerX + maxRadius * 0.707f, centerY - maxRadius * 0.707f),
                strokeWidth = 1f,
                pathEffect = dashes
            )

            // --- 3. Draw Center Listener Icon (Head Avatar) ---
            // Draw head (Circle)
            drawCircle(
                color = centerColor,
                radius = 12.dp.toPx(),
                center = Offset(centerX, centerY)
            )
            // Draw nose pointing UP (Forward direction)
            val noseLength = 16.dp.toPx()
            val nosePath = androidx.compose.ui.graphics.Path().apply {
                moveTo(centerX, centerY - noseLength)
                lineTo(centerX - 4.dp.toPx(), centerY - 6.dp.toPx())
                lineTo(centerX + 4.dp.toPx(), centerY - 6.dp.toPx())
                close()
            }
            drawPath(path = nosePath, color = centerColor)
            
            // Draw ears (Left and Right)
            drawCircle(
                color = centerColor.copy(alpha = 0.7f),
                radius = 4.dp.toPx(),
                center = Offset(centerX - 12.dp.toPx(), centerY)
            )
            drawCircle(
                color = centerColor.copy(alpha = 0.7f),
                radius = 4.dp.toPx(),
                center = Offset(centerX + 12.dp.toPx(), centerY)
            )

            // --- 4. Draw Target Coordinate Orb (translucent, shows where user dragged) ---
            // Convert target X and Y from coordinate space [-1, 1] back to pixel offset
            val targetPixelX = centerX + (targetX * maxRadius)
            val targetPixelY = centerY - (targetY * maxRadius) // Inverted Y coordinates
            
            drawCircle(
                color = targetColor.copy(alpha = 0.5f),
                radius = 8.dp.toPx(),
                center = Offset(targetPixelX, targetPixelY)
            )
            drawCircle(
                color = targetColor,
                radius = 10.dp.toPx(),
                center = Offset(targetPixelX, targetPixelY),
                style = Stroke(width = 2f)
            )

            // --- 5. Draw Smooth Interpolated Active DSP Sound Node ---
            val activePixelX = centerX + (interpolatedX * maxRadius)
            val activePixelY = centerY - (interpolatedY * maxRadius)

            // Glowing concentric ripple rings representing physical delay sound expansion
            drawCircle(
                color = accentColor.copy(alpha = 0.15f),
                radius = 24.dp.toPx(),
                center = Offset(activePixelX, activePixelY)
            )
            drawCircle(
                color = accentColor.copy(alpha = 0.35f),
                radius = 14.dp.toPx(),
                center = Offset(activePixelX, activePixelY)
            )
            
            // Solid glowing center core of the sound node
            drawCircle(
                color = Color.White,
                radius = 6.dp.toPx(),
                center = Offset(activePixelX, activePixelY)
            )
            drawCircle(
                color = accentColor,
                radius = 7.dp.toPx(),
                center = Offset(activePixelX, activePixelY),
                style = Stroke(width = 2f)
            )

            // Draw a connection trail vector from target to active node (shows spring force/velocity!)
            drawLine(
                color = accentColor.copy(alpha = 0.4f),
                start = Offset(targetPixelX, targetPixelY),
                end = Offset(activePixelX, activePixelY),
                strokeWidth = 2f,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 10f), 0f)
            )
        }
    }
}
