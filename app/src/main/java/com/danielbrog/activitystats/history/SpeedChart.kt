package com.danielbrog.activitystats.history

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import com.danielbrog.activitystats.stats.DistanceUnit
import com.danielbrog.activitystats.stats.Format
import com.danielbrog.activitystats.stats.SpeedSample
import com.danielbrog.activitystats.stats.SpeedSeries
import java.util.Locale
import kotlin.math.abs

/**
 * Speed along the ride: distance across, speed up, with a dashed line at the average.
 * Touch and drag to read the exact distance and speed under your finger.
 */
@Composable
fun SpeedChart(
    samples: List<SpeedSample>,
    avgSpeedMps: Double,
    unit: DistanceUnit,
    modifier: Modifier = Modifier,
) {
    val lineColor = MaterialTheme.colorScheme.primary
    val gridColor = MaterialTheme.colorScheme.outlineVariant
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    val markerColor = MaterialTheme.colorScheme.onSurface
    val labelStyle = MaterialTheme.typography.labelSmall.copy(color = labelColor)
    val readoutStyle = MaterialTheme.typography.labelLarge.copy(color = markerColor)
    val textMeasurer = rememberTextMeasurer()
    val density = LocalDensity.current

    // Convert once into the units shown: km or mi across, km/h or mph up.
    val metersPerUnit = if (unit == DistanceUnit.Km) 1_000.0 else Format.METERS_PER_MILE
    val speedFactor = if (unit == DistanceUnit.Km) 3.6 else 3_600.0 / Format.METERS_PER_MILE
    val points = remember(samples, unit) {
        samples.map { (it.distanceMeters / metersPerUnit) to (it.speedMps * speedFactor) }
    }
    val maxX = points.maxOfOrNull { it.first } ?: 0.0
    val xTicks = remember(maxX) { SpeedSeries.niceTicks(maxX) }
    val yTicks = remember(points) { SpeedSeries.niceTicks(points.maxOfOrNull { it.second } ?: 0.0) }
    val xTop = xTicks.last().takeIf { it > 0 } ?: 1.0
    val yTop = yTicks.last().takeIf { it > 0 } ?: 1.0
    val avg = avgSpeedMps * speedFactor

    var touchX by remember { mutableStateOf<Float?>(null) }

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(200.dp)
            .pointerInput(points) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    touchX = down.position.x
                    down.consume()
                    while (true) {
                        val change = awaitPointerEvent().changes.firstOrNull() ?: break
                        if (!change.pressed) break
                        touchX = change.position.x
                        change.consume()
                    }
                    touchX = null
                }
            },
    ) {
        val leftPad = with(density) { 36.dp.toPx() }
        val bottomPad = with(density) { 20.dp.toPx() }
        val topPad = with(density) { 24.dp.toPx() } // room for the readout
        val plotW = size.width - leftPad
        val plotH = size.height - bottomPad - topPad
        fun xOf(v: Double) = leftPad + (v / xTop * plotW).toFloat()
        fun yOf(v: Double) = topPad + plotH - (v / yTop * plotH).toFloat()

        // Grid lines and labels.
        for (t in yTicks) {
            val y = yOf(t)
            drawLine(gridColor, Offset(leftPad, y), Offset(size.width, y), strokeWidth = 1f)
            val label = textMeasurer.measure(formatTick(t), labelStyle)
            drawText(label, topLeft = Offset(leftPad - label.size.width - 6f, y - label.size.height / 2))
        }
        for (t in xTicks) {
            val label = textMeasurer.measure(formatTick(t), labelStyle)
            val x = (xOf(t) - label.size.width / 2).coerceIn(leftPad, size.width - label.size.width)
            drawText(label, topLeft = Offset(x, topPad + plotH + 4f))
        }

        if (points.size < 2) return@Canvas

        // Shaded area and speed line.
        val line = Path()
        points.forEachIndexed { i, (d, s) ->
            if (i == 0) line.moveTo(xOf(d), yOf(s)) else line.lineTo(xOf(d), yOf(s))
        }
        val area = Path().apply {
            addPath(line)
            lineTo(xOf(points.last().first), yOf(0.0))
            lineTo(xOf(points.first().first), yOf(0.0))
            close()
        }
        drawPath(area, lineColor.copy(alpha = 0.15f))
        drawPath(line, lineColor, style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))

        // Average speed.
        if (avg > 0) {
            val y = yOf(avg)
            drawLine(
                markerColor.copy(alpha = 0.6f),
                Offset(leftPad, y),
                Offset(size.width, y),
                strokeWidth = 1.5.dp.toPx(),
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 10f)),
            )
        }

        // Touch readout: the sample nearest the finger.
        touchX?.let { tx ->
            val nearest = points.minBy { abs(xOf(it.first) - tx) }
            val x = xOf(nearest.first)
            drawLine(markerColor, Offset(x, topPad), Offset(x, topPad + plotH), strokeWidth = 1.5.dp.toPx())
            drawCircle(lineColor, radius = 5.dp.toPx(), center = Offset(x, yOf(nearest.second)))
            val unitLabel = if (unit == DistanceUnit.Km) "km" else "mi"
            val speedLabel = if (unit == DistanceUnit.Km) "km/h" else "mph"
            val text = textMeasurer.measure(
                "%.1f %s · %.1f %s".format(Locale.US, nearest.first, unitLabel, nearest.second, speedLabel),
                readoutStyle,
            )
            val left = (x - text.size.width / 2).coerceIn(0f, size.width - text.size.width)
            drawText(text, topLeft = Offset(left, 0f))
        }
    }
}

/** "10", "2.5", "0.25": no trailing zeros. */
private fun formatTick(value: Double): String =
    java.math.BigDecimal.valueOf(value).setScale(2, java.math.RoundingMode.HALF_UP)
        .stripTrailingZeros().toPlainString()
