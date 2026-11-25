package com.danielbrog.activitystats.segments

import com.danielbrog.activitystats.location.RoutePoint
import kotlin.math.roundToLong

/**
 * Google's encoded polyline format: a list of points as a short text string, at 1e-5 degree
 * precision (about a metre). Used to store a segment's path in one database column. Plain
 * Kotlin, unit-tested; the format is the same one Google's map tools read.
 */
object Polyline {

    private const val SCALE = 1e5

    fun encode(points: List<RoutePoint>): String = buildString {
        var lastLat = 0L
        var lastLon = 0L
        for (p in points) {
            val lat = (p.latitude * SCALE).roundToLong()
            val lon = (p.longitude * SCALE).roundToLong()
            writeNumber(lat - lastLat)
            writeNumber(lon - lastLon)
            lastLat = lat
            lastLon = lon
        }
    }

    /** The points of an encoded string. Throws [IllegalArgumentException] if it isn't one. */
    fun decode(encoded: String): List<RoutePoint> {
        val points = mutableListOf<RoutePoint>()
        var index = 0
        var lat = 0L
        var lon = 0L
        fun readNumber(): Long {
            var result = 0L
            var shift = 0
            while (true) {
                require(index < encoded.length) { "Polyline ends in the middle of a number" }
                val b = encoded[index++].code - 63
                require(b in 0..63) { "Not a polyline character" }
                result = result or ((b and 0x1f).toLong() shl shift)
                shift += 5
                if (b < 0x20) break
            }
            // The lowest bit is the sign.
            return if (result and 1L != 0L) (result shr 1).inv() else result shr 1
        }
        while (index < encoded.length) {
            lat += readNumber()
            lon += readNumber()
            points += RoutePoint(lat / SCALE, lon / SCALE)
        }
        return points
    }

    private fun StringBuilder.writeNumber(value: Long) {
        // Zig-zag the sign into the lowest bit, then write 5 bits at a time, lowest first.
        var v = if (value < 0) (value shl 1).inv() else value shl 1
        while (v >= 0x20) {
            append(((0x20 or (v and 0x1f).toInt()) + 63).toChar())
            v = v shr 5
        }
        append((v.toInt() + 63).toChar())
    }

    /** [decode], or null if [encoded] isn't a whole polyline. */
    fun decodeOrNull(encoded: String): List<RoutePoint>? = try {
        decode(encoded)
    } catch (e: IllegalArgumentException) {
        null
    }
}
