package com.danielbrog.activitystats.export

import com.danielbrog.activitystats.data.ActivityType
import com.danielbrog.activitystats.location.FixStatus
import com.danielbrog.activitystats.location.GpsData
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Turns a saved activity into a GPX 1.1 file, the format Strava, Komoot, Garmin Connect and
 * RideWithGPS all import. Plain Kotlin, unit-tested.
 *
 * Only the fixes the noise filter kept are written, the same ones the map and stats use.
 * Elevation is the phone's raw GPS altitude; most apps replace it with their own.
 */
object GpxWriter {

    const val MIME_TYPE = "application/gpx+xml"

    private val utcTime: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).withZone(ZoneOffset.UTC)
    private val fileDate: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.US)

    /** Writes the whole file to [out], so a long ride is never built as one big string. */
    fun write(
        out: Appendable,
        title: String,
        type: String,
        startedAtMillis: Long,
        fixes: List<GpsData>,
    ) {
        val name = escape(title)
        out.append("""<?xml version="1.0" encoding="UTF-8"?>""").append('\n')
        out.append(
            """<gpx version="1.1" creator="Activity Stats" xmlns="http://www.topografix.com/GPX/1/1" """ +
                """xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" """ +
                """xsi:schemaLocation="http://www.topografix.com/GPX/1/1 http://www.topografix.com/GPX/1/1/gpx.xsd">"""
        ).append('\n')
        out.append("  <metadata>\n")
        out.append("    <name>").append(name).append("</name>\n")
        out.append("    <time>").append(time(startedAtMillis)).append("</time>\n")
        out.append("  </metadata>\n")
        out.append("  <trk>\n")
        out.append("    <name>").append(name).append("</name>\n")
        out.append("    <type>").append(gpxType(type)).append("</type>\n")
        out.append("    <trkseg>\n")
        for (fix in fixes.filter { it.status == FixStatus.Kept }.sortedBy { it.seq }) {
            out.append("      <trkpt lat=\"").append("%.7f".format(Locale.US, fix.latitude))
                .append("\" lon=\"").append("%.7f".format(Locale.US, fix.longitude)).append("\">")
            fix.altitudeMeters?.let { out.append("<ele>").append("%.1f".format(Locale.US, it)).append("</ele>") }
            out.append("<time>").append(time(fix.timeMillis)).append("</time></trkpt>\n")
        }
        out.append("    </trkseg>\n")
        out.append("  </trk>\n")
        out.append("</gpx>\n")
    }

    /** The whole file as text; handy for tests and short rides. */
    fun toGpx(title: String, type: String, startedAtMillis: Long, fixes: List<GpsData>): String =
        StringBuilder().also { write(it, title, type, startedAtMillis, fixes) }.toString()

    /**
     * "Morning Ride 2026-11-07.gpx": the title with characters that file systems reject
     * turned into spaces, then the local start date.
     */
    fun fileName(title: String, startedAtMillis: Long, zone: ZoneId = ZoneId.systemDefault()): String {
        val safe = title
            .replace(Regex("""[\\/:*?"<>|\p{Cntrl}]"""), " ")
            .replace(Regex("""\s+"""), " ")
            .trim()
            .ifEmpty { "Activity" }
        val date = fileDate.format(Instant.ofEpochMilli(startedAtMillis).atZone(zone))
        return "$safe $date.gpx"
    }

    private fun time(millis: Long): String = utcTime.format(Instant.ofEpochMilli(millis))

    /** The activity type names Strava and others recognise. */
    private fun gpxType(type: String): String = when (type) {
        ActivityType.RUN -> "running"
        ActivityType.WALK -> "walking"
        else -> "cycling"
    }

    private fun escape(text: String): String = buildString(text.length) {
        for (c in text) {
            when (c) {
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '"' -> append("&quot;")
                '\'' -> append("&apos;")
                else -> append(c)
            }
        }
    }
}
